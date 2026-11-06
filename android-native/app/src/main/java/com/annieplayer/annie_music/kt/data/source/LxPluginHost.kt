package com.annieplayer.annie_music.kt.data.source

import android.util.Base64
import android.util.Log
import com.annieplayer.annie_music.kt.data.network.anyToJsonElement
import com.annieplayer.annie_music.kt.data.network.globalOkHttp
import com.annieplayer.annie_music.kt.data.network.json
import com.annieplayer.annie_music.kt.data.network.jsonElementToAny
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * JS `decodeURIComponent` 的等价实现 —— **只解 `%XX`，不碰 `+`**。
 *
 * 为什么不直接用 `java.net.URLDecoder.decode`：那个按
 * `application/x-www-form-urlencoded` 语义把 `+` 解码成空格。JS 的
 * `encodeURIComponent` 反过来会把字面 `+` 编码成 `%2B`，用 `URLDecoder` 虽然当前能还原，
 * 但它对「上游换成 `encodeURI` / 手工拼串」这种情况会静默出错 ——
 * 而 md5 一旦错，插件签名就错，报错信息只会是「获取失败」，根本查不到这里。
 *
 * 宽容策略：不是合法 `%XX` 的（`%` 后面不足两位、或不是 hex）**原样保留**，
 * 与浏览器的 `decodeURIComponent` 抛错不同 —— 插件不应该因为一个残缺的 `%` 整体失败。
 *
 * 单独放在文件作用域（而不是 [LxPluginHost] 的成员）是为了让纯 JVM 单测直接调用，
 * 不必初始化宿主 object（那里有 OkHttp / QuickJs 的实例）。
 */
internal fun decodeUriComponent(text: String): String {
    if (!text.contains('%')) return text
    val out = StringBuilder(text.length)
    // 连续的 %XX 先攒成字节，再一次性按 UTF-8 还原（中文是 3 个 %XX 组成一个码点）
    val pending = java.io.ByteArrayOutputStream(8)
    fun flush() {
        if (pending.size() > 0) {
            out.append(String(pending.toByteArray(), Charsets.UTF_8))
            pending.reset()
        }
    }
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '%' && i + 2 < text.length) {
            val hi = Character.digit(text[i + 1], 16)
            val lo = Character.digit(text[i + 2], 16)
            if (hi >= 0 && lo >= 0) {
                pending.write((hi shl 4) or lo)
                i += 3
                continue
            }
        }
        flush()
        out.append(c)
        i++
    }
    flush()
    return out.toString()
}

/**
 * 落雪插件宿主：用 QuickJS 执行插件脚本，并把 `globalThis.lx` 完整注入。
 *
 * 设计对齐官方移动端宿主 `user-api-preload.js`（原生桥 + JS 前置脚本），但把原生桥
 * 压到两个函数：异步的 `__lxRequest` 与同步的 `__lxCall`（md5/aes/rsa/zlib/日志），
 * 其余（`lx` 对象、`utils.buffer`、`base64`、`setTimeout`、`console`）都在 JS 前置里实现。
 *
 * 关键点：**引擎必须能推进 microtask 队列**，否则插件的 Promise/async 永远 resolve 不了。
 * `app.cash.quickjs` 的 native `execute` 只做 `JS_EvalFunction`、不 drain，故改用
 * `io.github.dokar3:quickjs-kt`（`evaluate` 为 suspend，会驱动 job 循环）。
 */
object LxPluginHost {

    private const val TAG = "LxPlugin"

    /** 插件字节码缓存（按插件 id）：避免每次解析都重新编译（混淆插件可达数百 KB） */
    private val bytecodeCache = ConcurrentHashMap<String, ByteArray>()

    /** 常驻会话（按插件 id）：QuickJS 实例 + 已加载好的插件，**不再每次解析都重建/重跑插件** */
    private val sessions = ConcurrentHashMap<String, QuickJs>()

    /** 在飞的插件请求：reqId → OkHttp Call（供 `__lxAbort` 真正取消） */
    private val activeCalls = ConcurrentHashMap<Int, okhttp3.Call>()

    private val sessionCreateLock = Mutex()

    fun invalidate(cacheKey: String) {
        bytecodeCache.remove(cacheKey)
        sessions.remove(cacheKey)?.let { runCatching { it.close() } }
    }

    /**
     * 取（或首次创建）该插件的常驻会话 —— 对齐落雪原版「长驻 JS 上下文里 load 一次、之后反复调 handler」。
     *
     * 关键性能点：插件的**顶层代码只跑一次**。混淆插件（javascript-obfuscator 等）的字符串解码器
     * 很贵，之前每次解析都重建实例、重跑一遍顶层，正是"用插件就变慢（混淆插件尤其）"的主因。
     */
    private suspend fun sessionFor(cacheKey: String, script: String, fileName: String): QuickJs {
        sessions[cacheKey]?.let { if (!it.isClosed) return it }
        return sessionCreateLock.withLock {
            sessions[cacheKey]?.let { if (!it.isClosed) return@withLock it }
            val instance = QuickJs.create(Dispatchers.Default)
            val state = LxNativeState()
            // 安妮独家插件：剥掉文件头 `#@…` 标识块再执行（洛雪类插件原样返回）
            val ident = LxIdentity.parse(script)
            // 插件加载时 fire-and-forget 的请求失败会产生未捕获 rejection，可能把加载求值打断；
            // 只要它已经 send('inited') 报过 sources，就按成功处理（官方宿主也不会因此判失败）
            val failure = runCatching {
                instance.installNatives(state)
                instance.installScriptInfo(script, ident)
                instance.evaluate<Any?>(LX_PRELUDE, "lx-prelude.js")
                instance.loadPlugin(cacheKey, ident.script, fileName)
                // 注册可能是异步的（插件先 await 心跳再 on(request)，如「章鱼音源特供版」）：
                // 给最多 2s 窗口，每次 100ms 驱动一次 JS 定时器队列
                var waited = 0
                while (!instance.evaluate<Boolean>("globalThis.__lxHandlerReady()") && waited < 2_000) {
                    runCatching { instance.evaluate<Any?>("await new Promise(function(r){ setTimeout(r, 100); })") }
                    waited += 100
                }
            }.exceptionOrNull()
            if (state.initedSourcesJson == null) {
                runCatching { instance.close() }
                failure?.let { throw it }
            }
            sessions[cacheKey] = instance
            instance
        }
    }

    // ── 对外 ──

    /** 插件 `send('inited', …)` 回报的音源声明（由原生回调直接拿到，不依赖加载后求值） */
    private class LxNativeState {
        @Volatile
        var initedSourcesJson: String? = null
    }

    /** 加载插件并取回它声明的音源；加载失败抛异常（导入时据此判定插件是否可用） */
    suspend fun inspect(cacheKey: String, script: String, fileName: String): List<LxPluginSource> =
        withContext(Dispatchers.Default) {
            val state = LxNativeState()
            val ident = LxIdentity.parse(script)
            val failure = runCatching {
                quickJs(Dispatchers.Default) {
                    installNatives(state)
                    installScriptInfo(script, ident)
                    evaluate<Any?>(LX_PRELUDE, "lx-prelude.js")
                    loadPlugin(cacheKey, ident.script, fileName)
                    // 少数插件把 inited 放在微任务/定时器里；安妮独家插件要先过本机握手才 send('inited')，
                    // 所以给到 2s 的窗口（每次 100ms 驱动一次 JS 定时器队列），避免被误判「未初始化」
                    var waited = 0
                    while (state.initedSourcesJson == null && waited < 2_000) {
                        runCatching { evaluate<Any?>("await new Promise(function(r){ setTimeout(r, 100); })") }
                        waited += 100
                    }
                }
            }.exceptionOrNull()
            // 已 send('inited') 报过 sources 就按成功处理：加载后冒出的未捕获 rejection 不算插件不可用
            if (state.initedSourcesJson == null) failure?.let { throw it }
            parseSources(state.initedSourcesJson ?: "{}")
        }

    /**
     * 让插件解析出播放/下载直链。
     *
     * [quality] 为请求音质字符串（`128k`/`320k`/`flac`/`flac24bit`）。
     */
    suspend fun resolveMusicUrl(
        cacheKey: String,
        script: String,
        fileName: String,
        source: String,
        quality: String,
        musicInfoJson: String,
    ): String = withContext(Dispatchers.Default) {
        val instance = sessionFor(cacheKey, script, fileName)
        val infoJson = buildJsonObject {
            put("type", quality)
            put("musicInfo", json.parseToJsonElement(musicInfoJson))
        }.toString()
        // 吸收插件 fire-and-forget 请求产生的未捕获 rejection，避免它打断下面的调用
        runCatching { instance.evaluate<Any?>("await new Promise(function(r){ setTimeout(r, 0); })") }
        // 参数**内联**进调用表达式：不用共享全局变量，多个解析并发时才不会互相覆盖
        val result = instance.evaluate<String?>(
            "await globalThis.__lxInvoke(" + jsLit(source) + ", " + jsLit(infoJson) + ")",
        )
        val url = result?.trim().orEmpty()
        if (url.isEmpty()) throw IllegalStateException("插件未返回播放地址")
        if (url.length > 2048 || !url.startsWith("http")) {
            throw IllegalStateException("插件返回的地址非法")
        }
        url
    }

    // ── 内部 ──

    private suspend fun QuickJs.loadPlugin(cacheKey: String, script: String, fileName: String) {
        val cached = bytecodeCache[cacheKey]
        if (cached != null) {
            try {
                evaluate<Any?>(cached)
                return
            } catch (e: Exception) {
                // 字节码失效（引擎版本变化等）：丢弃并重新编译
                bytecodeCache.remove(cacheKey)
                Log.w(TAG, "缓存字节码执行失败，重新编译: ${e.message}")
            }
        }
        val bytecode = try {
            compile(script, fileName)
        } catch (_: Exception) {
            null
        }
        if (bytecode != null) {
            bytecodeCache[cacheKey] = bytecode
            evaluate<Any?>(bytecode)
        } else {
            evaluate<Any?>(script, fileName)
        }
    }

    /** 原生绑定：两个函数 + 一个 sleep（`setTimeout` 需要）；`inited` 由这里直接回报给原生 */
    private fun QuickJs.installNatives(state: LxNativeState) {
        asyncFunction<String>("__lxRequest") { args ->
            val reqId = (args.getOrNull(0) as? Number)?.toInt() ?: 0
            val url = args.getOrNull(1) as? String ?: throw IllegalArgumentException("url required")
            val optionsJson = args.getOrNull(2) as? String ?: "{}"
            val binary = args.getOrNull(3) as? Boolean ?: false
            httpRequest(reqId, url, optionsJson, binary)
        }
        function<Unit>("__lxAbort") { args ->
            (args.getOrNull(0) as? Number)?.toInt()?.let { activeCalls.remove(it)?.cancel() }
        }
        function<String?>("__lxCall") { args ->
            val action = args.getOrNull(0) as? String ?: return@function null
            val payload = args.getOrNull(1) as? String ?: ""
            if (action == "inited") {
                state.initedSourcesJson = payload
                return@function null
            }
            nativeCall(action, payload)
        }
        asyncFunction<Unit>("__lxSleep") { args ->
            delay((args.getOrNull(0) as? Number)?.toLong() ?: 0L)
        }
    }

    /**
     * 插件**自己**不联网，全部走宿主。选项对齐官方桌面端 `preload.js`：
     * `method` / `headers` / `body` / `form` / `formData` / `timeout` / `binary`。
     *
     * - **对象 `body` 按 JSON 发**（官方 `needle` 默认）；**只有 `form`/`formData` 才 urlencoded**。
     * - 响应体：先取原始字符串，**再尝试 `JSON.parse`**（成功给对象、失败给字符串）—— 官方就是这样，
     *   插件会直接 `body.code` 用，也会 `typeof === 'string' ? JSON.parse : body` 两用。
     * - `timeout` 非法/为 0 时默认 60s，上限 60s。
     */
    private suspend fun httpRequest(
        reqId: Int,
        url: String,
        optionsJson: String,
        binary: Boolean,
    ): String =
        withContext(Dispatchers.IO) {
            val opt = (runCatching {
                jsonElementToAny(json.parseToJsonElement(optionsJson)) as? Map<*, *>
            }.getOrNull()).orEmpty()
            val method = (opt["method"] as? String ?: "get").uppercase()
            val headers = opt["headers"] as? Map<*, *>
            val timeoutMs = ((opt["timeout"] as? Number)?.toLong()?.takeIf { it > 0 } ?: 60_000L)
                .coerceAtMost(60_000L)
            val noRedirect = (opt["follow_max"] as? Number)?.toInt() == 0
            val bodyValue = opt["body"]
            val formValue = opt["form"] ?: opt["formData"]
            val bodyText: String?
            val defaultCt: String
            when {
                bodyValue != null -> {
                    bodyText = if (bodyValue is String) bodyValue else anyToJsonElement(bodyValue).toString()
                    defaultCt = "application/json; charset=utf-8"
                }
                formValue != null -> {
                    val map = formValue as? Map<*, *>
                    bodyText = if (map != null) urlEncodeForm(map) else formValue.toString()
                    defaultCt = "application/x-www-form-urlencoded; charset=utf-8"
                }
                else -> {
                    bodyText = null
                    defaultCt = "application/x-www-form-urlencoded; charset=utf-8"
                }
            }

            // 安妮独家握手：虚拟端点直接在本机应答（不开端口、不走 OkHttp、不受系统代理影响）
            if (AnnieHandshake.matches(url)) {
                val payload = runCatching {
                    json.parseToJsonElement(bodyText.orEmpty()).jsonObject
                }.getOrNull()
                val ver = payload?.get("ver")?.jsonPrimitive?.content.orEmpty()
                val ok = AnnieHandshake.isValid(bodyText.orEmpty())
                return@withContext buildJsonObject {
                    put("statusCode", if (ok) 200 else 403)
                    put("statusMessage", if (ok) "OK" else "Forbidden")
                    put("headers", buildJsonObject { put("Content-Type", "application/json") })
                    put(
                        "body",
                        if (ok) json.parseToJsonElement(AnnieHandshake.successBody(ver))
                        else buildJsonObject { put("ok", false) },
                    )
                }.toString()
            }

            val declaredCt = headers?.entries
                ?.firstOrNull { (it.key as? String)?.equals("Content-Type", ignoreCase = true) == true }
                ?.value?.toString()

            val builder = Request.Builder().url(url)
            headers?.forEach { (k, v) ->
                // 不转发 Accept-Encoding：OkHttp 只对「自己加的」该头透明解压 gzip；
                // 原样带上插件声明的 gzip 会拿到压缩字节，插件解密失败取不到播放地址
                if (k is String && v != null && !k.equals("Accept-Encoding", ignoreCase = true)) {
                    builder.header(k, v.toString())
                }
            }
            if (method != "GET" && method != "HEAD") {
                val mediaType = declaredCt?.toMediaTypeOrNull() ?: defaultCt.toMediaTypeOrNull()
                builder.method(method, (bodyText ?: "").toRequestBody(mediaType))
            }
            val client = globalOkHttp.newBuilder()
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
            val call = client.newCall(builder.build())
            if (reqId > 0) activeCalls[reqId] = call
            try {
                call.execute().use { resp ->
                    // follow_max:0 表示插件自己在跟重定向，只取状态码/头部、body 从不使用；
                    // 这里不读体，避免把整首音频（320k ~8MB、flac 20MB+）当探路数据白下一遍
                    val bodyJs: Any? = if (noRedirect) {
                        ""
                    } else {
                        val bytes = resp.body?.bytes() ?: ByteArray(0)
                        if (binary) {
                            Base64.encodeToString(bytes, Base64.NO_WRAP)
                        } else {
                            val text = bytes.toString(Charsets.UTF_8)
                            runCatching { jsonElementToAny(json.parseToJsonElement(text)) }.getOrNull() ?: text
                        }
                    }
                    buildJsonObject {
                        put("statusCode", resp.code)
                        put("statusMessage", resp.message)
                        put("headers", buildJsonObject { resp.headers.forEach { (k, v) -> put(k, v) } })
                        put("body", anyToJsonElement(bodyJs))
                    }.toString()
                }
            } finally {
                activeCalls.remove(reqId)
            }
        }

    private fun nativeCall(action: String, payload: String): String? = when (action) {
        "log" -> {
            Log.d(TAG, payload)
            null
        }
        // JS 侧用 `encodeURIComponent` 过桥（避免特殊字符在原生字符串边界上出问题），
        // 所以这里必须先解回来再求摘要 —— 否则含 `|` `@` `#` `/` `&` `=` 或中文的输入
        // 会被按 `%7C` / `%40` / `%E4%B8%AD` 求 md5，签名全错（见 [decodeUriComponent]）。
        "md5" -> md5Hex(decodeUriComponent(payload))
        "random" -> secureRandom(payload)
        "aes" -> aesEncrypt(payload)
        "rsa" -> rsaEncrypt(payload)
        "inflate" -> zlib(payload, inflate = true)
        "deflate" -> zlib(payload, inflate = false)
        else -> null
    }

    /** 对象体 -> `k=v&k=v`（对齐落雪桌面端 needle / `querystring.stringify`：空格用 `%20` 而非 `+`） */
    private fun urlEncodeForm(map: Map<*, *>): String = map.entries.joinToString("&") { (k, v) ->
        val key = URLEncoder.encode(k?.toString() ?: "", "UTF-8").replace("+", "%20")
        val value = URLEncoder.encode(v?.toString() ?: "", "UTF-8").replace("+", "%20")
        "$key=$value"
    }

    private fun md5Hex(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun secureRandom(sizeText: String): String {
        val size = sizeText.toIntOrNull()?.coerceIn(0, 1024) ?: 0
        val bytes = ByteArray(size)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** `{data, mode, key, iv}`（均 base64）→ base64 密文；模式对齐官方桌面端（任意 `aes-<bits>-<mode>`） */
    private fun aesEncrypt(payload: String): String {
        val p = payloadObject(payload)
        val data = Base64.decode(p["data"] as? String ?: "", Base64.DEFAULT)
        val mode = (p["mode"] as? String ?: "aes-128-cbc").lowercase()
        val key = Base64.decode(p["key"] as? String ?: "", Base64.DEFAULT)
        val iv = Base64.decode(p["iv"] as? String ?: "", Base64.DEFAULT)
        val blockMode = Regex("^aes-\\d+-([a-z0-9]+)$").find(mode)?.groupValues?.get(1) ?: "cbc"
        val cipher = Cipher.getInstance("AES/${blockMode.uppercase()}/PKCS5Padding")
        val keySpec = SecretKeySpec(key, "AES")
        if (blockMode == "ecb") {
            cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        } else {
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(iv))
        }
        return Base64.encodeToString(cipher.doFinal(data), Base64.NO_WRAP)
    }

    /** `{data(base64), key(PEM)}` → base64 密文；RSA/ECB/NoPadding + **左补零到密钥长度**（对齐官方） */
    private fun rsaEncrypt(payload: String): String {
        val p = payloadObject(payload)
        val raw = Base64.decode(p["data"] as? String ?: "", Base64.DEFAULT)
        val key = (p["key"] as? String ?: "").replace(Regex("-----[A-Z ]+-----"), "").replace(Regex("\\s"), "")
        val pub = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(Base64.decode(key, Base64.DEFAULT)))
        val blockSize = ((pub as? RSAPublicKey)?.modulus?.bitLength() ?: 1024) / 8
        val data = if (raw.size < blockSize) ByteArray(blockSize - raw.size) + raw else raw
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, pub) }
        return Base64.encodeToString(cipher.doFinal(data), Base64.NO_WRAP)
    }

    private fun zlib(payload: String, inflate: Boolean): String {
        val input = Base64.decode(payload, Base64.DEFAULT)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        if (inflate) {
            val inf = Inflater()
            inf.setInput(input)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && inf.needsInput()) break
                out.write(buf, 0, n)
            }
            inf.end()
        } else {
            val def = Deflater()
            def.setInput(input)
            def.finish()
            while (!def.finished()) out.write(buf, 0, def.deflate(buf))
            def.end()
        }
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    @Suppress("UNCHECKED_CAST")
    private fun payloadObject(payload: String): Map<*, *> =
        (jsonElementToAny(json.parseToJsonElement(payload)) as? Map<*, *>).orEmpty()

    /** `{ kw: {type, actions, qualitys}, ... }` → 只保留宿主白名单内的源（对齐 lxn `allSources`/`supportQualitys`） */
    private fun parseSources(raw: String): List<LxPluginSource> {
        val obj = (runCatching {
            jsonElementToAny(json.parseToJsonElement(raw)) as? Map<*, *>
        }.getOrNull()).orEmpty()
        val out = mutableListOf<LxPluginSource>()
        for (code in LX_ALL_SOURCES) {
            val entry = obj[code] as? Map<*, *> ?: continue
            if (entry["type"] != "music") continue
            val actions = (entry["actions"] as? List<*>).orEmpty()
                .mapNotNull { it as? String }.filter { it == "musicUrl" }
            if (actions.isEmpty()) continue
            val qualitys = (entry["qualitys"] as? List<*>).orEmpty()
                .mapNotNull { it as? String }.filter { it.lowercase() in LX_QUALITY_KEYS }
            out += LxPluginSource(source = code, actions = actions, qualities = qualitys)
        }
        return out
    }

    private suspend fun QuickJs.installScriptInfo(script: String, identity: LxIdentity.Parsed) {
        val header = LxPluginMeta.parseHeader(script, identity.fields)
        val infoJson = buildJsonObject {
            put("name", header.name)
            put("description", header.description)
            put("version", header.version)
            put("author", header.author)
            put("homepage", header.homepage)
            put("rawScript", script)
        }.toString()
        evaluate<Any?>("globalThis.__lxScriptInfoJson = " + jsLit(infoJson) + ";")
        // 安妮独家：把握手参数下发给插件（洛雪类插件不给 —— 与改造前行为一致）
        val hsJson = if (identity.exclusive) {
            buildJsonObject {
                put("port", AnnieHandshake.port)
                put("key", AnnieHandshake.secret)
                put("tag", identity.tag)
                put("ver", identity.version)
            }.toString()
        } else {
            ""
        }
        evaluate<Any?>("globalThis.__lxAnnieHsJson = " + jsLit(hsJson) + ";")
    }

    private fun jsLit(text: String): String {
        val sb = StringBuilder(text.length + 2)
        sb.append('"')
        for (c in text) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\u2028' -> sb.append("\\u2028")
                '\u2029' -> sb.append("\\u2029")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /**
     * JS 前置脚本（对齐官方 `user-api-preload.js` 的结构）。
     *
     * 注意：**故意不禁用 `eval`/`Function`** —— 混淆插件（javascript-obfuscator 等）的
     * 字符串解码器大量依赖它们，禁掉会导致「混淆插件取不到链」。
     */
    private val LX_PRELUDE = """
(function () {
  'use strict';
  var nativeRequest = globalThis.__lxRequest;
  var nativeCall = globalThis.__lxCall;
  var nativeSleep = globalThis.__lxSleep;
  var nativeAbort = globalThis.__lxAbort;
  // strict 模式下 delete 非可配置属性会抛异常；原生绑定可能不可配置，故各自吞掉
  try { delete globalThis.__lxRequest; } catch (e) {}
  try { delete globalThis.__lxCall; } catch (e) {}
  try { delete globalThis.__lxSleep; } catch (e) {}
  try { delete globalThis.__lxAbort; } catch (e) {}

  var EVENT_NAMES = { request: 'request', inited: 'inited', updateAlert: 'updateAlert' };
  var eventNames = [EVENT_NAMES.request, EVENT_NAMES.inited, EVENT_NAMES.updateAlert];

  function stringToBytes(str) {
    var bytes = [];
    for (var i = 0; i < str.length; i++) {
      var c = str.charCodeAt(i);
      if (c < 0x80) bytes.push(c);
      else if (c < 0x800) bytes.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f));
      else if (c >= 0xd800 && c <= 0xdbff && i + 1 < str.length) {
        var c2 = str.charCodeAt(++i);
        var cp = 0x10000 + ((c - 0xd800) << 10) + (c2 - 0xdc00);
        bytes.push(0xf0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3f), 0x80 | ((cp >> 6) & 0x3f), 0x80 | (cp & 0x3f));
      } else bytes.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f));
    }
    return new Uint8Array(bytes);
  }
  function bytesToString(bytes) {
    var out = '';
    for (var i = 0; i < bytes.length;) {
      var b = bytes[i], cp;
      if (b < 0x80) { cp = b; i += 1; }
      else if (b < 0xe0) { cp = ((b & 0x1f) << 6) | (bytes[i + 1] & 0x3f); i += 2; }
      else if (b < 0xf0) { cp = ((b & 0x0f) << 12) | ((bytes[i + 1] & 0x3f) << 6) | (bytes[i + 2] & 0x3f); i += 3; }
      else { cp = ((b & 0x07) << 18) | ((bytes[i + 1] & 0x3f) << 12) | ((bytes[i + 2] & 0x3f) << 6) | (bytes[i + 3] & 0x3f); i += 4; }
      if (cp > 0xffff) { cp -= 0x10000; out += String.fromCharCode(0xd800 + (cp >> 10), 0xdc00 + (cp & 0x3ff)); }
      else out += String.fromCharCode(cp);
    }
    return out;
  }

  var B64C = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  var B64L = (function () { var t = []; for (var i = 0; i < 256; i++) t.push(-1); for (var j = 0; j < B64C.length; j++) t[B64C.charCodeAt(j)] = j; return t; })();
  function bytesToB64(bytes) {
    var out = '';
    for (var i = 0; i < bytes.length; i += 3) {
      var b0 = bytes[i], b1 = bytes[i + 1], b2 = bytes[i + 2];
      out += B64C[b0 >> 2];
      out += B64C[((b0 & 3) << 4) | ((b1 === undefined ? 0 : b1) >> 4)];
      out += b1 === undefined ? '=' : B64C[((b1 & 15) << 2) | ((b2 === undefined ? 0 : b2) >> 6)];
      out += b2 === undefined ? '=' : B64C[b2 & 63];
    }
    return out;
  }
  function b64ToBytes(b64) {
    var clean = String(b64).replace(/[^A-Za-z0-9+/]/g, '');
    while (clean.length % 4 !== 0) clean += '=';
    var bytes = [];
    for (var i = 0; i < clean.length; i += 4) {
      var e0 = B64L[clean.charCodeAt(i)], e1 = B64L[clean.charCodeAt(i + 1)];
      var e2 = B64L[clean.charCodeAt(i + 2)], e3 = B64L[clean.charCodeAt(i + 3)];
      bytes.push((e0 << 2) | (e1 >> 4));
      if (e2 >= 0) bytes.push(((e1 & 15) << 4) | (e2 >> 2));
      if (e3 >= 0) bytes.push(((e2 & 3) << 6) | e3);
    }
    return new Uint8Array(bytes);
  }
  function dataToB64(data) {
    if (typeof data === 'string') return bytesToB64(stringToBytes(data));
    if (Array.isArray(data) || ArrayBuffer.isView(data)) return bytesToB64(new Uint8Array(data));
    throw new Error('data type error: ' + typeof data);
  }
  function fromB64(b64) { return b64ToBytes(b64); }
  function bytesToHex(bytes) { var s = ''; for (var i = 0; i < bytes.length; i++) s += (bytes[i] & 0xff).toString(16).padStart(2, '0'); return s; }
  function bytesToBinary(bytes) { var s = ''; for (var i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i] & 0xff); return s; }
  // 官方 from/aesEncrypt/rsaEncrypt/randomBytes/zlib 都返回真 Node Buffer，插件会调 .toString(enc)；
  // 这里给 Uint8Array 挂上同语义的 toString，避免插件拿到 "1,2,3" 这种逗号串。
  function makeBuffer(bytes) {
    var u = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
    u.toString = function (encoding) {
      var enc = String(encoding || 'utf8').toLowerCase();
      if (enc === 'base64') return bytesToB64(u);
      if (enc === 'hex') return bytesToHex(u);
      if (enc === 'binary' || enc === 'latin1') return bytesToBinary(u);
      return bytesToString(u);
    };
    return u;
  }

  var utils = {
    crypto: {
      md5: function (str) { if (typeof str !== 'string') throw new Error('param required a string'); return nativeCall('md5', encodeURIComponent(str)); },
      randomBytes: function (size) { return makeBuffer(fromB64(nativeCall('random', String(size)))); },
      aesEncrypt: function (buffer, mode, key, iv) {
        var payload = JSON.stringify({ data: dataToB64(buffer), mode: mode, key: dataToB64(key), iv: iv ? dataToB64(iv) : '' });
        return makeBuffer(fromB64(nativeCall('aes', payload)));
      },
      rsaEncrypt: function (buffer, key) {
        if (typeof key !== 'string') throw new Error('Invalid RSA key');
        return makeBuffer(fromB64(nativeCall('rsa', JSON.stringify({ data: dataToB64(buffer), key: key }))));
      },
    },
    buffer: {
      from: function (input, encoding) {
        if (typeof input === 'string') {
          if (encoding === 'binary') throw new Error('Binary encoding is not supported for input strings');
          if (encoding === 'base64') return makeBuffer(b64ToBytes(input));
          if (encoding === 'hex') return makeBuffer(new Uint8Array(input.match(/.{1,2}/g).map(function (b) { return parseInt(b, 16); })));
          return makeBuffer(stringToBytes(input));
        }
        if (Array.isArray(input)) return makeBuffer(new Uint8Array(input));
        throw new Error('Unsupported input type: ' + input + ' encoding: ' + encoding);
      },
      bufToString: function (buf, format) {
        if (Array.isArray(buf) || ArrayBuffer.isView(buf)) {
          var bytes = new Uint8Array(buf);
          if (format === 'binary') return buf;
          if (format === 'hex') return bytesToHex(bytes);
          if (format === 'base64') return bytesToB64(bytes);
          return bytesToString(bytes);
        }
        throw new Error('Input is not a valid buffer: ' + buf + ' format: ' + format);
      },
    },
    zlib: {
      inflate: function (buf) {
        return new Promise(function (resolve, reject) {
          try { resolve(makeBuffer(fromB64(nativeCall('inflate', dataToB64(buf))))); } catch (e) { reject(e); }
        });
      },
      deflate: function (data) {
        return new Promise(function (resolve, reject) {
          try { resolve(makeBuffer(fromB64(nativeCall('deflate', dataToB64(data))))); } catch (e) { reject(e); }
        });
      },
    },
  };

  var requestHandler = null;
  var isInited = false;
  var isShowedUpdateAlert = false;

  globalThis.lx = {
    EVENT_NAMES: EVENT_NAMES,
    version: '2.0.0',
    env: 'mobile',
    currentScriptInfo: JSON.parse(globalThis.__lxScriptInfoJson || '{}'),
    // 安妮独家：宿主指纹 + 握手参数（端口/密钥/身份）。洛雪类插件这两个字段为 true / undefined，
    // 与改造前一致（它们不读）；独家插件据此判断「是否跑在安妮里」。
    __annieHost: true,
    __annieHs: (function () {
      try {
        var raw = globalThis.__lxAnnieHsJson || '';
        return raw ? JSON.parse(raw) : undefined;
      } catch (e) { return undefined; }
    })(),
    utils: utils,
    request: function (url, options, callback) {
      options = options || {};
      var aborted = false;
      var reqId = (globalThis.__lxReqSeq = (globalThis.__lxReqSeq || 0) + 1);
      var opts = { method: options.method || 'get', headers: options.headers, timeout: options.timeout, body: options.body, form: options.form, formData: options.formData };
      nativeRequest(reqId, url, JSON.stringify(opts), options.binary === true).then(function (respJson) {
        if (aborted) return;
        var resp;
        try { resp = JSON.parse(respJson); } catch (e) { resp = { statusCode: 0, statusMessage: '', headers: {}, body: '' }; }
        callback(null, resp, resp.body);
      }).catch(function (err) { if (!aborted) callback(err, null, null); });
      return function () { aborted = true; try { nativeAbort(reqId); } catch (e) {} };
    },
    send: function (eventName, data) {
      return new Promise(function (resolve, reject) {
        if (eventNames.indexOf(eventName) < 0) return reject(new Error('The event is not supported: ' + eventName));
        if (eventName === EVENT_NAMES.inited) {
          if (isInited) return reject(new Error('Script is inited'));
          isInited = true;
          try { globalThis.__lxSourcesJson = JSON.stringify((data && data.sources) || {}); }
          catch (e) { globalThis.__lxSourcesJson = '{}'; }
          globalThis.__lxReportInited(data && data.sources);
          return resolve();
        }
        if (eventName === EVENT_NAMES.updateAlert) {
          if (isShowedUpdateAlert) return reject(new Error('The update alert can only be called once.'));
          isShowedUpdateAlert = true;
          return resolve();
        }
        reject(new Error('Unknown event name: ' + eventName));
      });
    },
    on: function (eventName, handler) {
      if (eventNames.indexOf(eventName) < 0) return Promise.reject(new Error('The event is not supported: ' + eventName));
      if (eventName === EVENT_NAMES.request) { requestHandler = handler; return Promise.resolve(); }
      return Promise.reject(new Error('The event is not supported: ' + eventName));
    },
  };

  var timerId = 0;
  var cancelled = {};
  globalThis.setTimeout = function (fn, ms) {
    var extra = Array.prototype.slice.call(arguments, 2);
    var id = ++timerId;
    nativeSleep(Number(ms) || 0).then(function () {
      if (cancelled[id]) return;
      delete cancelled[id];
      try { fn.apply(null, extra); } catch (e) { try { console.error(e); } catch (_) {} }
    });
    return id;
  };
  globalThis.clearTimeout = function (id) { cancelled[id] = true; };

  function logTo(tag) {
    return function () {
      try {
        var parts = Array.prototype.map.call(arguments, function (a) { return typeof a === 'string' ? a : JSON.stringify(a); });
        nativeCall('log', tag + ' ' + parts.join(' '));
      } catch (_) {}
    };
  }
  globalThis.console = { log: logTo('[log]'), info: logTo('[info]'), warn: logTo('[warn]'), error: logTo('[error]'), debug: logTo('[debug]') };

  globalThis.__lxInvoke = async function (source, infoJson) {
    if (typeof requestHandler !== 'function') throw new Error('插件未注册 request 处理器（缺少 on(EVENT_NAMES.request, ...)）');
    return await requestHandler.call(globalThis.lx, { source: source, action: 'musicUrl', info: JSON.parse(infoJson) });
  };
  globalThis.__lxHandlerReady = function () { return typeof requestHandler === 'function'; };
  globalThis.__lxReportInited = function (sources) { try { nativeCall('inited', JSON.stringify(sources || {})); } catch (e) {} };
})();
"""
}

/** 插件头部注释解析（对齐官方：块 `/^\/\*[\s\S]+?\*\//` + 字段 `@key value`，带长度截断） */
object LxPluginMeta {

    private val blockRegex = Regex("^/\\*[\\s\\S]+?\\*/")
    private val fieldRegex = Regex("^\\s?\\*\\s?@(\\w+)\\s(.+)$", RegexOption.MULTILINE)
    private val limits = mapOf(
        "name" to 24,
        "description" to 36,
        "author" to 56,
        "homepage" to 1024,
        "version" to 36,
    )

    /**
     * 解析插件头部元数据。
     *
     * [identityFields] 来自文件头 `#@name` / `#@description` … 标识块（安妮独家插件）：
     * 那几行是明文、不参与混淆，优先级高于头部 JSDoc 注释块（混淆后注释会被抹掉）。
     */
    fun parseHeader(script: String, identityFields: Map<String, String> = emptyMap()): LxPluginHeader {
        val block = blockRegex.find(script)?.value.orEmpty()
        val fields = mutableMapOf<String, String>()
        for (m in fieldRegex.findAll(block)) {
            val key = m.groupValues[1]
            fields[key] = m.groupValues[2].trim().take(limits[key] ?: 1024)
        }
        for ((key, value) in identityFields) {
            if (value.isNotBlank()) fields[key] = value.trim().take(limits[key] ?: 1024)
        }
        return LxPluginHeader(
            name = fields["name"].orEmpty(),
            description = fields["description"].orEmpty(),
            version = fields["version"].orEmpty(),
            author = fields["author"].orEmpty(),
            homepage = fields["homepage"].orEmpty(),
        )
    }
}
