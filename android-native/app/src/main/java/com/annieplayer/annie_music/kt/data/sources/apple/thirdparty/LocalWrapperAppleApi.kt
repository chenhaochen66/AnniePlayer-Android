package com.annieplayer.annie_music.kt.data.sources.apple.thirdparty

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import com.annieplayer.annie_music.kt.data.network.CacheService
import com.annieplayer.annie_music.kt.data.network.httpGetText
import org.json.JSONObject

/**
 * 本机 wrapper-lite 服务客户端。
 *
 * 服务本体是另一个 App（WrapperLite）托管的 QEMU guest，跑在自己的 Apple Music 账号上；
 * 这里只做 HTTP 客户端，不负责启停。
 *
 * ```
 * GET /m3u8?adamId=                      → data.m3u8
 * GET /key?adamId=&uri=                  → data.{contentKey,ctx,state,rcx,...}
 * GET /lyrics?adamId=&language=&syllable=→ data.lyrics
 * GET /status                            → data.regions（健康检查）
 * ```
 */
object LocalWrapperAppleApi : AppleThirdPartyApi {

    /**
     * 直连 QEMU 转发的内部端口，**不走** WrapperLite App 里的 CORS 代理。
     *
     * 原生 App 发 HTTP 请求不受同源策略限制，不需要那层代理；而它的实现在长时间运行后
     * 会卡住（表现为端口在听但无响应），绕开它才能拿到 24ms 级的响应。
     */
    const val BASE = "http://127.0.0.1:12341"

    private fun url(path: String) = BASE.trimEnd('/') + path

    override suspend fun fetchMasterUrl(songId: String): String? {
        val id = rawAppleId(songId)
        if (id.isEmpty()) return null
        // masterUrl 带签名，短时复用（与 ak1ra 版一致 30 分钟）
        val cacheKey = "apple:master:local:$id"
        CacheService.get<String>(cacheKey)?.takeIf { it.isNotEmpty() }?.let { return it }
        return runCatching {
            val root = JSONObject(httpGetText(url("/m3u8?adamId=" + Uri.encode(id))))
            if (root.optInt("code", 0) != 0) return@runCatching null
            root.optJSONObject("data")
                ?.optString("m3u8")
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()?.also { CacheService.set(cacheKey, it, MASTER_URL_TTL_MS) }
    }

    override suspend fun fetchKeyTemplate(songId: String, keyUri: String): String? {
        if (keyUri.isEmpty()) return null
        // 分段 0 的固定 key 用包内模板：wrapper-lite 会拒绝 P000000000 这个 prefetch URI
        // （README: rejected unless adamId=0），直接走网络会拿到错误码导致整条链路失败。
        if (keyUri.contains("P000000000")) return AppleFixedKey.get()
        val id = rawAppleId(songId)
        // 模板对「同一首歌 + 同一 keyUri」是稳定的，缓存 6 小时（与 ak1ra 版一致）。
        // 不缓存会让每次切歌都吃 1~2 次本机 /key 往返（每次 0.7~2.3 秒），
        // 实测 open() 稳定在 3.6~5.9 秒 —— 这是「切歌慢但元数据秒切」的真正原因。
        val cacheKey = "apple:key:local:$id:$keyUri"
        CacheService.get<String>(cacheKey)?.takeIf { it.length > 2 }?.let { return it }
        return runCatching {
            val target = url(
                "/key?adamId=" + Uri.encode(id) + "&uri=" + Uri.encode(keyUri),
            )
            val root = JSONObject(httpGetText(target))
            if (root.optInt("code", 0) != 0) return@runCatching null
            (root.optJSONObject("data") ?: root)
                .toString()
                .takeIf { it.length > 2 }
        }.getOrNull()?.also { CacheService.set(cacheKey, it, KEY_TEMPLATE_TTL_MS) }
    }

    private const val MASTER_URL_TTL_MS = 30 * 60 * 1000L
    private const val KEY_TEMPLATE_TTL_MS = 6 * 60 * 60 * 1000L

    /**
     * 歌词：直接问本机 `/lyrics`（`syllable=1` 才会带逐字 span），
     * 拿到的 TTML 用与第三方实现同一套解析转成 LRC / 逐字 / 翻译。
     */
    override suspend fun fetchLyric(songId: String, language: String): AppleLyric {
        val id = rawAppleId(songId)
        if (id.isEmpty()) return AppleLyric("", null)
        val target = url(
            "/lyrics?adamId=" + Uri.encode(id) +
                "&language=" + Uri.encode(language) +
                "&syllable=1",
        )
        return try {
            val text = httpGetText(target)
            val root = JSONObject(text)
            val code = root.optInt("code", -1)
            if (code != 0) {
                Log.w(TAG, "lyrics code=$code adamId=$id")
                return AppleLyric("", null)
            }
            val ttml = root.optJSONObject("data")?.optString("lyrics").orEmpty()
            if (ttml.isEmpty()) {
                Log.w(TAG, "lyrics empty body adamId=$id respLen=${text.length}")
                return AppleLyric("", null)
            }
            val lrc = ttmlToLrc(ttml)
            if (lrc.isEmpty()) {
                Log.w(TAG, "lyrics ttml->lrc empty adamId=$id ttmlLen=${ttml.length}")
            }
            AppleLyric(
                lrc = lrc,
                tlyric = ttmlTranslation(ttml, language),
                wordLyric = ttmlToWordLyric(ttml),
            )
        } catch (e: Exception) {
            Log.w(TAG, "lyrics failed adamId=$id: ${e.javaClass.simpleName} ${e.message}")
            AppleLyric("", null)
        }
    }

    private const val TAG = "LocalAppleApi"

    /** 检测服务是否可达：直接请求 /status。 */
    suspend fun checkStatus(): Result<String> = runCatching {
        val root = JSONObject(httpGetText(url("/status")))
        val code = root.optInt("code", -1)
        if (code != 0) error("服务返回 code=$code")
        val arr = root.optJSONObject("data")?.optJSONArray("regions")
        val regions = buildList {
            if (arr != null) {
                for (i in 0 until arr.length()) add(arr.optString(i))
            }
        }
        if (regions.isEmpty()) {
            "已连接（未返回区域）"
        } else {
            "已连接 · 区域 " + regions.joinToString(", ").uppercase()
        }
    }
}

/**
 * 优先本机服务、失败回落第三方。
 *
 * **当前未启用**：开关打开后直接用 [LocalWrapperAppleApi]，不做静默回落 ——
 * 第三方（ak1ra）的 key 模板实测是坏的，回落过去只会让人误以为「能用」。
 * 保留这个实现是为了以后真需要一个「容错模式」时有现成的。
 */
object PreferLocalAppleApi : AppleThirdPartyApi {

    override suspend fun fetchMasterUrl(songId: String): String? =
        LocalWrapperAppleApi.fetchMasterUrl(songId)
            ?: Ak1raAppleApi.fetchMasterUrl(songId)

    override suspend fun fetchKeyTemplate(songId: String, keyUri: String): String? =
        LocalWrapperAppleApi.fetchKeyTemplate(songId, keyUri)
            ?: Ak1raAppleApi.fetchKeyTemplate(songId, keyUri)

    override suspend fun fetchLyric(songId: String, language: String): AppleLyric {
        val local = LocalWrapperAppleApi.fetchLyric(songId, language)
        if (local.lrc.isNotEmpty()) return local
        Log.w("LocalAppleApi", "lyrics fallback to ak1ra for $songId")
        return Ak1raAppleApi.fetchLyric(songId, language)
    }
}

/**
 * 「使用本机 Apple Music 服务」开关的持久化 + 源切换。
 *
 * 打开后 [AppleThirdPartyRegistry.current] 切到 [PreferLocalAppleApi]，
 * 关闭则回到 [Ak1raAppleApi]。
 */
object AppleServiceSettings {

    private const val PREFS = "apple_service"
    private const val KEY_USE_LOCAL = "use_local"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        applyCurrent()
    }

    /** 默认开启：本机服务给出的 key 模板才是可用的，第三方只作兜底。 */
    val useLocal: Boolean
        get() = prefs?.getBoolean(KEY_USE_LOCAL, true) ?: true

    fun setUseLocal(value: Boolean) {
        prefs?.edit()?.putBoolean(KEY_USE_LOCAL, value)?.apply()
        applyCurrent()
    }

    private fun applyCurrent() {
        // 开了本机服务就**只用**本机：明确失败比静默回落到第三方（key 模板可能是坏的）更好排查。
        AppleThirdPartyRegistry.current =
            if (useLocal) LocalWrapperAppleApi else Ak1raAppleApi
    }
}
