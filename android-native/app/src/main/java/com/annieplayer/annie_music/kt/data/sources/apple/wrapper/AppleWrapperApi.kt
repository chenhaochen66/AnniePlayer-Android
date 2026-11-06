package com.annieplayer.annie_music.kt.data.sources.apple.wrapper

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.annieplayer.annie_music.kt.data.network.CacheService
import com.annieplayer.annie_music.kt.data.network.httpGetText
import org.json.JSONObject

// ================================================================================================
// Apple Music Wrapper 客户端（**非官方接口的唯一实现**）
// ================================================================================================
//
// 服务是 WorldObservationLog/wrapper 那一族（含本机 wrapper-lite），接口形状固定：
//
//   GET /status                                 → data.ready / data.regions
//   GET /m3u8?adamId=                           → data.m3u8
//   GET /key?adamId=&uri=                       → data.{contentKey,ctx,state,rcx,…}
//   GET /lyrics?adamId=&language=&syllable=1    → data.lyrics（TTML）
//
// ## 两个源，互斥、**不自动回落**
//
// | `AppleServiceSettings.useLocal` | 目标 | 认证 |
// |---|---|---|
// | 开（默认） | `http://127.0.0.1:12341`（WrapperLite 应用托管） | 无 |
// | 关 | `AppleWrapperRegistry` 里选中的远程地址（如 `https://<token>@wm.wol.moe`） | HTTP Basic |
//
// 两边都不可用就**直接抛错**，不偷偷换源 —— 静默回落会让人分不清在用哪台机、
// 烧的是谁的账号额度。
//
// **以前那套已全部删除**：第三方站点 ak1ra（`/parse/song/:id`、`/lyrics/:id`）、
// Cloudflare 挑战 + 隐藏 WebView 兜底、25 秒超时重试、ak1ra↔wol 混源、
// 「本机失败自动回落第三方」。

/** 歌词（LRC + 可选译文 + 逐字）；取不到时 lrc 为空串 */
data class AppleLyric(
    val lrc: String,
    val tlyric: String?,
    val wordLyric: String? = null,
)

/** 一次请求要打到哪儿：根地址 + 认证头 */
internal data class AppleWrapperTarget(
    val base: String,
    val headers: Map<String, String>,
)

object AppleWrapperApi {

    private const val MASTER_URL_TTL_MS = 30 * 60 * 1000L
    private const val KEY_TEMPLATE_TTL_MS = 6 * 60 * 60 * 1000L

    private const val NO_REMOTE = "未添加远程 Wrapper 地址：请到「Apple Music 服务」里添加，或打开「使用本机服务」"

    /**
     * 缓存分区名 —— **换源必须换分区**，否则会串台。
     *
     * 这不是洁癖：`/m3u8` 给的是带签名的地址、`/key` 给的是**内容密钥模板**，
     * 两者都是「哪台 wrapper 就给哪一份」。历史上 ak1ra 与 wol 对同一首歌返回的 key 模板
     * 一个能解、一个是伪随机（`HybridAppleApi` 就是为绕开这个才存在）。缓存不分区的后果是：
     * 切到另一台后仍用上一台给的模板，解出来是垃圾数据，而且 TTL 有 6 小时。
     */
    fun cacheScope(): String =
        if (AppleServiceSettings.useLocal) {
            "local"
        } else {
            "r:" + (AppleWrapperRegistry.activeEndpoint()?.base ?: "none")
        }

    /**
     * 当前该用哪个源（**开关决定，不探测、不回落**）。
     *
     * @return null 只在「关掉本机开关但一条远程地址都没有」时出现
     */
    internal fun resolveTarget(useLocal: Boolean = AppleServiceSettings.useLocal): AppleWrapperTarget? {
        if (useLocal) {
            return AppleWrapperTarget(
                base = APPLE_LOCAL_WRAPPER_BASE,
                headers = mapOf("Accept" to "application/json"),
            )
        }
        val endpoint = AppleWrapperRegistry.activeEndpoint() ?: return null
        val auth = endpoint.basicPayload
            ?.let { Base64.encodeToString(it.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) }
        return AppleWrapperTarget(
            base = endpoint.base,
            headers = if (auth == null) {
                mapOf("Accept" to "application/json")
            } else {
                mapOf("Accept" to "application/json", "Authorization" to "Basic $auth")
            },
        )
    }

    private fun _target(): AppleWrapperTarget =
        resolveTarget() ?: throw IllegalStateException(NO_REMOTE)

    private fun _url(target: AppleWrapperTarget, path: String): String = target.base + path

    /**
     * 健康检查（设置页「检测」按钮用）。
     *
     * 可以对**任意**一条地址调用，不限于 active、也不看开关 —— 新加进来还没选中时也能先验一下。
     */
    suspend fun checkStatus(rawUrl: String): Result<String> = runCatching {
        val endpoint = parseWrapperUrl(rawUrl) ?: error("地址格式不正确")
        val auth = endpoint.basicPayload
            ?.let { Base64.encodeToString(it.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) }
        val headers = if (auth == null) {
            mapOf("Accept" to "application/json")
        } else {
            mapOf("Accept" to "application/json", "Authorization" to "Basic $auth")
        }
        _readStatus(endpoint.base, headers)
    }

    /** 本机服务检测（固定地址、无认证） */
    suspend fun checkLocalStatus(): Result<String> =
        runCatching {
            _readStatus(APPLE_LOCAL_WRAPPER_BASE, mapOf("Accept" to "application/json"))
        }

    private suspend fun _readStatus(base: String, headers: Map<String, String>): String {
        val root = JSONObject(httpGetText("$base/status", headers))
        val code = root.optInt("code", -1)
        if (code != 0) error("服务返回 code=$code")
        val data = root.optJSONObject("data")
        val regions = buildList {
            val arr = data?.optJSONArray("regions")
            if (arr != null) for (i in 0 until arr.length()) add(arr.optString(i))
        }
        val ready = data?.optBoolean("ready", true) ?: true
        return buildString {
            append(if (ready) "已连接" else "已连接 · 未就绪")
            if (regions.isNotEmpty()) append(" · 区域 ").append(regions.joinToString(", ").uppercase())
        }
    }

    /**
     * `/m3u8` → master m3u8 地址。
     *
     * 带签名、但短时稳定，缓存 30 分钟：一次起播 + 下载都要它，省一整次 RTT。
     * 键带 [cacheScope] —— 换源后不能拿上一台给的地址。
     */
    suspend fun fetchMasterUrl(songId: String): String? {
        val id = rawAppleId(songId)
        if (id.isEmpty()) return null
        val cacheKey = "apple:master:${cacheScope()}:$id"
        CacheService.get<String>(cacheKey)?.takeIf { it.isNotEmpty() }?.let { return it }
        val target = _target()
        val text = httpGetText(
            _url(target, "/m3u8?adamId=" + Uri.encode(id)),
            target.headers,
        )
        val root = JSONObject(text)
        if (root.optInt("code", 0) != 0) return null
        val url = root.optJSONObject("data")?.optString("m3u8").orEmpty()
        return url.takeIf { it.isNotEmpty() }?.also {
            CacheService.set(cacheKey, it, MASTER_URL_TTL_MS)
        }
    }

    /**
     * `/key` → Temari 模板 JSON。
     *
     * 分段 0 的 `P000000000` 走包内模板（wrapper 会拒绝这个 prefetch URI，直接请求必然拿到错误码）。
     * 其余模板对「同一首歌 + 同一 keyUri」稳定，缓存 6 小时 —— 不缓存时每次切歌都要多等 1~2 次往返
     * （本机实测 open() 会因为这两次 /key 从 1s 级拖到 3.6~5.9 秒）。
     */
    suspend fun fetchKeyTemplate(songId: String, keyUri: String): String? {
        if (keyUri.isEmpty()) return null
        if (keyUri.contains("P000000000")) return AppleFixedKey.get()
        val id = rawAppleId(songId)
        if (id.isEmpty()) return null
        // 键带 [cacheScope]：模板是**哪台 wrapper** 给的，换源必须重新要（见 cacheScope 注释）
        val cacheKey = "apple:key:${cacheScope()}:$id:$keyUri"
        CacheService.get<String>(cacheKey)?.takeIf { it.length > 2 }?.let { return it }
        val target = _target()
        val text = httpGetText(
            _url(target, "/key?adamId=" + Uri.encode(id) + "&uri=" + Uri.encode(keyUri)),
            target.headers,
        )
        val root = JSONObject(text)
        if (root.optInt("code", 0) != 0) return null
        val data = (root.optJSONObject("data") ?: root).toString()
        return data.takeIf { it.length > 2 }?.also {
            CacheService.set(cacheKey, it, KEY_TEMPLATE_TTL_MS)
        }
    }

    /** `/lyrics` → TTML → LRC / 逐字 / 译文（`syllable=1` 才有逐字 span） */
    suspend fun fetchLyric(songId: String, language: String = "zh-Hans"): AppleLyric {
        val id = rawAppleId(songId)
        if (id.isEmpty()) return AppleLyric("", null)
        val target = resolveTarget() ?: return AppleLyric("", null)
        return runCatching {
            val text = httpGetText(
                _url(
                    target,
                    "/lyrics?adamId=" + Uri.encode(id) +
                        "&language=" + Uri.encode(language) +
                        "&syllable=1",
                ),
                target.headers,
            )
            val root = JSONObject(text)
            if (root.optInt("code", -1) != 0) return AppleLyric("", null)
            val ttml = root.optJSONObject("data")?.optString("lyrics").orEmpty()
            if (ttml.isEmpty()) return AppleLyric("", null)
            AppleLyric(
                lrc = ttmlToLrc(ttml),
                tlyric = ttmlTranslation(ttml, language),
                wordLyric = ttmlToWordLyric(ttml),
            )
        }.getOrDefault(AppleLyric("", null))
    }
}

/**
 * 分段 0 的固定 FairPlay 模板；`/key` 不接受 `P000000000`，只能随包内置。
 */
object AppleFixedKey {
    private var json: String? = null

    fun init(context: Context) {
        json = runCatching {
            context.assets.open("apple_fixed_key.json")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        }.getOrNull()
    }

    fun get(): String? = json?.takeIf { it.length > 2 }
}

internal fun rawAppleId(id: String): String = id.removePrefix("apple:")
