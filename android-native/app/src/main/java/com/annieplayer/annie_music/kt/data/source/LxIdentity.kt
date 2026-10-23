package com.annieplayer.annie_music.kt.data.source

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom

/**
 * 安妮独家插件的「标识块」：文件头连续的 `#@…` 明文本行，不参与混淆。
 *
 * ```
 * #@安妮音源 v6
 * #@name 安妮音源
 * #@description …
 * #@version v6
 * #@author …
 * ```
 *
 * 标识块不是合法 JS —— 宿主剥离后才能执行；落雪等不做剥离的宿主解析即语法错误，导入必然失败。
 * 落雪类第三方插件没有标识块 → [parse] 原样返回，老路径一个字节都不动。
 */
object LxIdentity {

    private const val PREFIX = "#@"
    private const val SCAN_LINES = 12

    private val tagLine = Regex("^#@安妮音源(?:\\s+(\\S+))?$")
    private val fieldLine = Regex("^#@(\\w+)\\s+(.+)$")
    private val supportedFields = setOf("name", "description", "version", "author", "homepage")

    /** 剥离结果：[script] 是去掉标识块后的可执行脚本；[exclusive] 为 true 表示安妮独家插件 */
    data class Parsed(
        val script: String,
        val tag: String = "",
        val version: String = "",
        val fields: Map<String, String> = emptyMap(),
    ) {
        val exclusive: Boolean get() = tag.isNotEmpty()
    }

    fun parse(script: String): Parsed {
        val lines = script.split('\n')
        var start = -1
        var end = -1
        var tag = ""
        var ver = ""
        val fields = LinkedHashMap<String, String>()
        val limit = minOf(lines.size, SCAN_LINES)
        for (i in 0 until limit) {
            val raw = lines[i].removePrefix("\uFEFF").trim()
            if (raw.isEmpty()) continue          // 空行：块前/块内都容忍
            if (!raw.startsWith(PREFIX)) break   // 首行非标识 → 块到此结束
            if (start < 0) start = i
            end = i
            val m = tagLine.find(raw)
            if (m != null && tag.isEmpty()) {
                tag = "安妮音源"
                ver = m.groupValues[1].trim()
            }
            val f = fieldLine.find(raw) ?: continue
            val key = f.groupValues[1].lowercase()
            if (key in supportedFields) fields.putIfAbsent(key, f.groupValues[2].trim())
        }
        if (start < 0) return Parsed(script)
        val body = (lines.subList(0, start) + lines.subList(end + 1, lines.size)).joinToString("\n")
        return Parsed(
            script = body,
            tag = tag,
            version = ver.ifEmpty { fields["version"].orEmpty() },
            fields = fields,
        )
    }
}

/**
 * 安妮独家插件握手：本机不真开端口，直接在 [LxPluginHost] 的请求桥里应答
 * `http://127.0.0.1:<port>/annie-source-handshake`。
 *
 * 密钥每次进程启动随机、只在宿主内存里，插件只能原样回带 → 冒名宿主拿不到密钥。
 * 用虚拟端点而非真 ServerSocket：无需明文流量豁免、不占端口、也不会被系统代理劫持。
 */
object AnnieHandshake {

    private const val TAG = "LxPlugin"
    const val PATH = "/annie-source-handshake"
    const val IDENTITY_TAG = "安妮音源"

    /** 本次进程的握手密钥（16 字节随机 → 32 位小写 hex） */
    val secret: String by lazy {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        bytes.joinToString("") { "%02x".format(it) }
    }

    /** 端口只用于插件拼 URL（端点按路径拦截），取随机值避免看起来像固定后门 */
    val port: Int by lazy { 20000 + SecureRandom().nextInt(40000) }

    /** 该 URL 是否为握手端点（只认回环地址 + 精确路径） */
    fun matches(url: String): Boolean {
        val u = url.trim()
        if (!u.startsWith("http://127.0.0.1:", ignoreCase = true) &&
            !u.startsWith("http://localhost:", ignoreCase = true)
        ) return false
        val path = u.substringAfter("://", "").substringAfter('/', "")
        return "/" + path.substringBefore('?') == PATH
    }

    /** 校验插件回带的凭据：密钥必须一致；声明了 tag 就必须是安妮音源 */
    fun isValid(bodyJson: String): Boolean {
        val obj = runCatching { Json.parseToJsonElement(bodyJson).jsonObject }.getOrNull() ?: return false
        val key = obj["key"]?.jsonPrimitive?.contentOrNullSafe().orEmpty()
        val client = obj["client"]?.jsonPrimitive?.contentOrNullSafe().orEmpty()
        val tag = obj["tag"]?.jsonPrimitive?.contentOrNullSafe().orEmpty().trim()
        val ok = key == secret && client == "annie-source" && (tag.isEmpty() || tag == IDENTITY_TAG)
        if (!ok) Log.w(TAG, "握手被拒绝：key 不匹配或 tag 非法")
        return ok
    }

    /** 成功应答体（与桌面端一致：`{ok, ts, tag, ver}`） */
    fun successBody(ver: String): String =
        """{"ok":true,"ts":${System.currentTimeMillis()},"tag":"$IDENTITY_TAG","ver":"${ver.replace("\"", "")}"}"""

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { content }.getOrNull()
}
