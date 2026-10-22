package com.annieplayer.annie_music.kt.data.sources.apple.thirdparty

import android.content.Context
import android.net.Uri
import com.annieplayer.annie_music.kt.data.network.CacheService
import com.annieplayer.annie_music.kt.data.network.httpGetJson
import com.annieplayer.annie_music.kt.data.network.httpGetText
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import org.json.JSONObject

/**
 * Apple Music 非官方接口。
 *
 * 官方目录接口仍放在 `data/sources/AppleMusicApi.kt`；这里只放第三方 wrapper：
 * 歌词、`/parse/song`、`/key`、`/license` 等。以后换站点/加备用源，实现
 * [AppleThirdPartyApi] 并在 [AppleThirdPartyRegistry] 切换即可。
 */

private const val APPLE_WEB = "https://music.ak1ra.de5.net"

private const val APPLE_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

private val _headers = mapOf(
    "User-Agent" to APPLE_UA,
    "Referer" to "$APPLE_WEB/",
    "Origin" to APPLE_WEB,
    "Accept" to "application/json",
)

data class AppleLyric(
    val lrc: String,
    val tlyric: String?,
    val wordLyric: String? = null,
)

/** 非官方 Apple 能力接口（歌词 + parse/key）。 */
interface AppleThirdPartyApi {
    suspend fun fetchLyric(songId: String, language: String = "zh-Hans"): AppleLyric

    /** `/parse/song/{adamId}` 的 `masterUrl`。 */
    suspend fun fetchMasterUrl(songId: String): String?

    /** `/key` 返回的模板 JSON（Temari `tmpl_from_json` 直接使用）。 */
    suspend fun fetchKeyTemplate(songId: String, keyUri: String): String?
}

/** 当前使用的第三方源。以后加备用站时替换 current 即可。 */
object AppleThirdPartyRegistry {
    @Volatile
    var current: AppleThirdPartyApi = Ak1raAppleApi
}

/**
 * 混合源：masterUrl / 歌词走 ak1ra，**单曲 key 模板走 wol**。
 *
 * 为什么必须换 key：
 * - 同一个 asset（`P1247010844`）、同一个 playlist、同一个 `skd://…/c6` URI，ak1ra 的
 *   `/key` 返回的模板解出来是伪随机数据 —— 整首 ALAC 只有第 1 个分片能解，下载出来的
 *   m4a 任何播放器都打不开（实测 `invalid samples per frame` / `bits left`）。
 * - wol 站点（`wm.wol.moe`，amd.wol.moe 的后端）对**同样的请求**返回另一份模板，
 *   解密结果与官方 mobile 端一致（`20000404…` 合法 ALAC 帧头）。
 * - 两边 `/m3u8` 返回的 masterUrl 完全相同，所以只需要换 key 这一环。
 */
object HybridAppleApi : AppleThirdPartyApi {

    override suspend fun fetchLyric(songId: String, language: String): AppleLyric =
        Ak1raAppleApi.fetchLyric(songId, language)

    override suspend fun fetchMasterUrl(songId: String): String? =
        Ak1raAppleApi.fetchMasterUrl(songId)

    override suspend fun fetchKeyTemplate(songId: String, keyUri: String): String? =
        WolKeyApi.fetchTemplate(songId, keyUri)
}

/**
 * wol 站点的 `/key`。这是目前唯一能给出**可用** FairPlay 模板的来源。
 *
 * 两个必须注意的点：
 * 1. **必须带 cache-buster**（`&_=…`）：该端点响应头是
 *    `cache-control: public, max-age=2678400`（31 天）+ `cf-cache-status: HIT`，
 *    Cloudflare 缓存里那份模板是坏的；绕开缓存才能拿到正确的那份。
 * 2. 认证是 `Authorization: Bearer <key>`（不是 Basic），key 即站点 URL 里的用户名。
 */
object WolKeyApi {

    private const val BASE = "https://wm.wol.moe"
    private const val TOKEN = "qXut5osAOzeJN5dG-YXxq8-5"

    private const val WEB = "https://amd.wol.moe"
    private const val TTL_MS = 6 * 60 * 60 * 1000L

    private val headers = mapOf(
        "User-Agent" to APPLE_UA,
        "Authorization" to "Bearer $TOKEN",
        "Referer" to "$WEB/",
        "Origin" to WEB,
        "Accept" to "application/json",
    )

    suspend fun fetchTemplate(songId: String, keyUri: String): String? {
        val id = rawAppleId(songId)
        if (id.isEmpty() || keyUri.isEmpty()) return null
        val cacheKey = "apple:key:wol:$id:$keyUri"
        CacheService.get<String>(cacheKey)?.takeIf { it.length > 2 }?.let { return it }

        val url = BASE + "/key?adamId=" + Uri.encode(id) +
            "&uri=" + Uri.encode(keyUri) +
            "&_=" + System.nanoTime().toString(36)

        val json = runCatching {
            val text = try {
                httpGetText(url, headers)
            } catch (e: Exception) {
                // 被 CF 挑战拦下时退到 WebView 通道（与 ak1ra 相同策略）
                val message = e.message.orEmpty()
                if (!message.contains("HTTP 403") && !message.contains("HTTP 503")) throw e
                AppleWebViewFetch.getText(url) ?: throw e
            }
            val root = JSONObject(text)
            if (root.optInt("code", 0) != 0) return@runCatching null
            val data = root.optJSONObject("data") ?: root
            data.toString().takeIf { it.length > 2 }
        }.getOrNull()

        return json?.also { CacheService.set(cacheKey, it, TTL_MS) }
    }
}

/** 分段 0 的固定 FairPlay 模板；/key 不接受 P000000000，只能随包内置。 */
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

/** ak1ra 站点实现。 */
object Ak1raAppleApi : AppleThirdPartyApi {

    /**
     * 带 CF 挑战兜底的 GET。
     *
     * 三级链：
     * 1. 正常 OkHttp（有 cookie 时顺带带上）；
     * 2. 403 → [AppleChallenge] 用隐藏 WebView 过挑战，拿到 `cf_clearance` 后重试一次；
     * 3. 仍失败 → [AppleWebViewFetch] 直接在 WebView 里 `fetch`，请求从真 Chromium 发出，
     *    TLS/HTTP2/JS 指纹全部原生（站点改绑指纹时也能活）。
     */
    private suspend fun _getText(url: String): String {
        try {
            return httpGetText(url, _headersFor())
        } catch (e: Exception) {
            if (!_looksLikeChallenge(e)) throw e

            // 第二级：过挑战后带 cookie 重试
            if (AppleChallenge.solve(APPLE_WEB)) {
                try {
                    return httpGetText(url, _headersFor())
                } catch (retry: Exception) {
                    if (_looksLikeChallenge(retry)) AppleChallenge.invalidate() else throw retry
                }
            }

            // 第三级：WebView 里直接 fetch（指纹兜底）
            AppleWebViewFetch.getText(url)?.let { return it }
            throw e
        }
    }

    /** 请求头：有 webview 的 cookie/UA 时覆盖（cf_clearance 与 UA 绑定，必须成对使用） */
    private fun _headersFor(): Map<String, String> {
        val cookie = AppleChallenge.cookieHeader()
        val ua = AppleChallenge.userAgent()
        if (cookie == null || ua == null) return _headers
        return _headers + mapOf("Cookie" to cookie, "User-Agent" to ua)
    }

    /** 403/503 且是 CF 的挑战页（body 是 HTML）时按挑战处理 */
    private fun _looksLikeChallenge(e: Exception): Boolean {
        val message = e.message.orEmpty()
        return message.contains("HTTP 403") || message.contains("HTTP 503")
    }

    /**
     * 失败后原地重试一次（播放解析 / 下载 / 歌词共用）。
     *
     * 第一次失败通常是「cookie 刚好过期」或「挑战状态变了」；此时作废旧 cookie、重新过一次
     * 挑战（拿不到会自动落到 [AppleWebViewFetch] 的浏览器通道），再完整重试一遍。
     * 加 25 秒总上限，避免两条通道都卡死时把起播拖太久。
     */
    private suspend fun <T> _retryOnFailure(block: suspend () -> T?): T? {
        block()?.let { return it }
        AppleChallenge.invalidate()
        AppleChallenge.solve(APPLE_WEB)
        return withTimeoutOrNull(25_000L) { block() }
    }

    override suspend fun fetchLyric(songId: String, language: String): AppleLyric {
        val id = rawAppleId(songId)
        if (id.isEmpty()) return AppleLyric("", null)
        return runCatching {
            val url = "$APPLE_WEB/lyrics/$id?language=" + Uri.encode(language)
            val ttml = _retryOnFailure { runCatching { _getText(url) }.getOrNull() }
                ?: return AppleLyric("", null)
            AppleLyric(
                lrc = ttmlToLrc(ttml),
                tlyric = ttmlTranslation(ttml, language),
                wordLyric = ttmlToWordLyric(ttml),
            )
        }.getOrDefault(AppleLyric("", null))
    }

    override suspend fun fetchMasterUrl(songId: String): String? {
        val id = rawAppleId(songId)
        if (id.isEmpty()) return null
        // 每次起播/下载都要拿 masterUrl，而它只是「这首歌的 master m3u8 地址」。
        // 分片已经走播放缓存，剩下的就是这几个固定请求，缓存掉能省一整次 RTT。
        CacheService.get<String>(_masterUrlKey(id))?.takeIf { it.isNotEmpty() }?.let { return it }
        val url = _retryOnFailure {
            runCatching {
                val body = JSONObject(_getText("$APPLE_WEB/parse/song/$id"))
                val data = body.optJSONObject("data")
                data?.optString("masterUrl")?.takeIf { it.isNotEmpty() }
            }.getOrNull()
        }
        return url?.also { CacheService.set(_masterUrlKey(id), it, _MASTER_URL_TTL_MS) }
    }

    override suspend fun fetchKeyTemplate(songId: String, keyUri: String): String? {
        if (keyUri.isEmpty()) return null
        if (keyUri.contains("P000000000")) return AppleFixedKey.get()
        // 分段 0 的固定 key 用 adamId=0；后续分片用真实 adamId。
        val id = rawAppleId(songId)
        if (id.isEmpty()) return null
        // 模板是这首歌的内容密钥材料，同一 keyUri 稳定不变；缓存它能把每次起播的两个
        // `/key` 请求省掉（Temari 句柄仍每次本地重建，那点开销可以忽略）
        val cacheKey = _keyTemplateKey(id, keyUri)
        CacheService.get<String>(cacheKey)?.takeIf { it.length > 2 }?.let { return it }
        val url = "$APPLE_WEB/key?adamId=" + Uri.encode(id) + "&uri=" + Uri.encode(keyUri)
        val json = _retryOnFailure {
            runCatching {
                val root = JSONObject(_getText(url))
                if (root.optInt("code", 0) != 0) return@runCatching null
                val data = root.optJSONObject("data") ?: root
                data.toString().takeIf { it.length > 2 }
            }.getOrNull()
        }
        return json?.also { CacheService.set(cacheKey, it, _KEY_TEMPLATE_TTL_MS) }
    }
}

/** 统一入口；默认中文（不做语言设置界面）。 */
suspend fun fetchAppleLyric(songId: String, language: String = "zh-Hans"): AppleLyric =
    AppleThirdPartyRegistry.current.fetchLyric(songId, language)

/** Apple `/parse/song` → master m3u8。 */
suspend fun fetchAppleMasterUrl(songId: String): String? =
    AppleThirdPartyRegistry.current.fetchMasterUrl(songId)

/** Apple `/key` → Temari 模板 JSON。 */
suspend fun fetchAppleKeyTemplate(songId: String, keyUri: String): String? =
    AppleThirdPartyRegistry.current.fetchKeyTemplate(songId, keyUri)

internal fun rawAppleId(id: String): String = id.removePrefix("apple:")

// ── 播放/下载侧固定请求的缓存键与 TTL ──

/** masterUrl 带签名，有效期有限，只做短时复用（避免二次起播再打一次 `/parse/song`） */
private const val _MASTER_URL_TTL_MS = 30 * 60 * 1000L

/** 内容密钥模板对同一首歌稳定；缓存长一点，起播时就不用再等第三方站点的两次 `/key` */
private const val _KEY_TEMPLATE_TTL_MS = 6 * 60 * 60 * 1000L

private fun _masterUrlKey(id: String): String = "apple:master:$id"

private fun _keyTemplateKey(id: String, keyUri: String): String = "apple:key:$id:$keyUri"

// ── TTML → LRC / 逐字 / 翻译 ──

private data class TtmlLine(
    val key: String?,
    val beginMs: Int,
    val endMs: Int,
    val body: String,
)

private data class TtmlWord(
    val startMs: Int,
    val endMs: Int,
    val text: String,
)

private val _ttmlLine = Regex("""<p\b([^>]*)>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
private val _ttmlSpan = Regex("""<span\b([^>]*)>(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
private val _ttmlText = Regex("""<text\b([^>]*)>(.*?)</text>""", RegexOption.DOT_MATCHES_ALL)
private val _ttmlTranslationTag = Regex("""<translation\b([^>]*)>(.*?)</translation>""", RegexOption.DOT_MATCHES_ALL)
private val _ttmlTranslations = Regex("""<translations\b[^>]*>(.*?)</translations>""", RegexOption.DOT_MATCHES_ALL)
private val _ttmlBegin = Regex("""\bbegin="([^"]+)"""")
private val _ttmlEnd = Regex("""\bend="([^"]+)"""")
private val _ttmlKey = Regex("""\bitunes:key="([^"]+)"""")
private val _ttmlFor = Regex("""\bfor="([^"]+)"""")
private val _ttmlLang = Regex("""\bxml:lang="([^"]+)"""")
private val _ttmlTag = Regex("""<[^>]+>""")
private val _ttmlSpace = Regex("""\s+""")

private fun ttmlLines(ttml: String): List<TtmlLine> =
    _ttmlLine.findAll(ttml).mapNotNull { match ->
        val attrs = match.groupValues[1]
        val begin = _ttmlBegin.find(attrs)?.groupValues?.getOrNull(1) ?: return@mapNotNull null
        val beginMs = _ttmlSeconds(begin)?.let { (it * 1000).toInt() } ?: return@mapNotNull null
        val endMs = _ttmlEnd.find(attrs)?.groupValues?.getOrNull(1)
            ?.let { _ttmlSeconds(it)?.let { s -> (s * 1000).toInt() } }
            ?: beginMs
        TtmlLine(
            key = _ttmlKey.find(attrs)?.groupValues?.getOrNull(1),
            beginMs = beginMs,
            endMs = endMs,
            body = match.groupValues[2],
        )
    }.toList()

internal fun ttmlToLrc(ttml: String): String {
    return ttmlLines(ttml).mapNotNull { line ->
        val text = cleanTtml(line.body)
        if (text.isEmpty()) return@mapNotNull null
        "[${lrcTime(line.beginMs / 1000.0)}]$text"
    }.joinToString("\n")
}

internal fun ttmlToWordLyric(ttml: String): String? {
    val out = mutableListOf<String>()
    for (line in ttmlLines(ttml)) {
        val words = mutableListOf<TtmlWord>()
        var cursor = 0
        for (span in _ttmlSpan.findAll(line.body)) {
            val attrs = span.groupValues[1]
            val text = cleanTtml(span.groupValues[2])
            if (text.isEmpty()) {
                cursor = span.range.last + 1
                continue
            }
            val gap = line.body.substring(cursor.coerceAtMost(span.range.first), span.range.first)
            val prefix = if (words.isNotEmpty() && gap.any { it.isWhitespace() }) " " else ""
            val start = _ttmlBegin.find(attrs)?.groupValues?.getOrNull(1)
                ?.let { _ttmlSeconds(it)?.let { s -> (s * 1000).toInt() } }
                ?: line.beginMs
            val end = _ttmlEnd.find(attrs)?.groupValues?.getOrNull(1)
                ?.let { _ttmlSeconds(it)?.let { s -> (s * 1000).toInt() } }
                ?: start
            words += TtmlWord(start, end, prefix + text)
            cursor = span.range.last + 1
        }
        if (words.isEmpty()) continue
        val sb = StringBuilder()
        sb.append('[').append(mmssMs(line.beginMs)).append(']')
        for (word in words) {
            sb.append('<').append(mmssMs(word.startMs)).append('>').append(word.text)
        }
        val lastEnd = words.last().endMs.coerceAtLeast(words.last().startMs)
        sb.append('<').append(mmssMs(lastEnd)).append('>')
        out += sb.toString()
    }
    return out.joinToString("\n").ifEmpty { null }
}

internal fun ttmlTranslation(ttml: String, language: String): String? {
    val block = _ttmlTranslations.find(ttml)?.groupValues?.getOrNull(1) ?: return null
    val all = _ttmlTranslationTag.findAll(block).toList()
    if (all.isEmpty()) return null
    val wanted = language.substringBefore('-').lowercase(Locale.ROOT)
    val picked = all.firstOrNull {
        val lang = _ttmlLang.find(it.groupValues[1])?.groupValues?.getOrNull(1).orEmpty()
        lang.lowercase(Locale.ROOT).startsWith(wanted)
    } ?: all.first()
    val texts = _ttmlText.findAll(picked.groupValues[2]).associate { text ->
        val key = _ttmlFor.find(text.groupValues[1])?.groupValues?.getOrNull(1).orEmpty()
        key to cleanTtml(text.groupValues[2])
    }.filterValues { it.isNotEmpty() }
    if (texts.isEmpty()) return null
    return ttmlLines(ttml).mapNotNull { line ->
        val text = line.key?.let { texts[it] } ?: return@mapNotNull null
        "[${lrcTime(line.beginMs / 1000.0)}]$text"
    }.joinToString("\n").ifEmpty { null }
}

private fun cleanTtml(raw: String): String {
    val noTags = _ttmlTag.replace(raw, "")
    val decoded = runCatching {
        android.text.Html.fromHtml(noTags, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
    }.getOrDefault(noTags)
    return _ttmlSpace.replace(decoded, " ").trim()
}

private fun _ttmlSeconds(raw: String): Double? {
    val parts = raw.trim().split(":")
    return when (parts.size) {
        1 -> parts[0].toDoubleOrNull()
        2 -> parts[0].toDoubleOrNull()?.times(60)?.plus(parts[1].toDoubleOrNull() ?: return null)
        3 -> parts[0].toDoubleOrNull()?.times(3600)?.plus(
            (parts[1].toDoubleOrNull() ?: return null) * 60 +
                (parts[2].toDoubleOrNull() ?: return null),
        )
        else -> null
    }
}

private fun lrcTime(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0)
    val minutes = (total / 60).toInt()
    val sec = total - minutes * 60
    return String.format(Locale.US, "%02d:%05.2f", minutes, sec)
}

private fun mmssMs(ms: Int): String {
    val safe = ms.coerceAtLeast(0)
    val minutes = safe / 60000
    val seconds = (safe % 60000) / 1000
    val millis = safe % 1000
    return String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, millis)
}
