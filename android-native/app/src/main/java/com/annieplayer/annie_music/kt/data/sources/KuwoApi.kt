package com.annieplayer.annie_music.kt.data.sources

import com.annieplayer.annie_music.kt.data.model.Album
import com.annieplayer.annie_music.kt.data.model.AlbumSong
import com.annieplayer.annie_music.kt.data.model.Artist
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Playlist
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import android.util.Base64
import com.annieplayer.annie_music.kt.core.util.LrcxDes
import com.annieplayer.annie_music.kt.core.util.lrcTimeOfMs
import com.annieplayer.annie_music.kt.core.util.mmssOfMs
import com.annieplayer.annie_music.kt.data.network.cacheService
import com.annieplayer.annie_music.kt.data.network.httpGetBytes
import com.annieplayer.annie_music.kt.data.network.httpGetJson
import com.annieplayer.annie_music.kt.data.network.httpGetText
import com.annieplayer.annie_music.kt.data.network.json
import com.annieplayer.annie_music.kt.data.network.jsonElementToAny
import com.annieplayer.annie_music.kt.data.network.retryUntilSuccess
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.Inflater
import kotlin.random.Random

/**
 * 酷我音乐 API（1:1 移植 Flutter `lib/data/sources/kuwo_api.dart`）。
 *
 * 保持与原版一致的函数名、参数与返回语义，供音乐仓库层与下载服务调用。
 * 加密算法（[generateKuwoSecret] / [v] / [getSecret]）逐字节对齐，服务器端会校验。
 */

// ── 文件内返回结构（对齐 Dart records） ──

/** 歌手歌曲分页结果（对齐 Dart `({List<Song> songs, int total})`） */
data class KuwoArtistSongs(
    val songs: List<Song>,
    val total: Int,
)

/** 专辑歌曲结果（对齐 Dart `({List<AlbumSong> songs, String pub})`） */
data class KuwoAlbumSongs(
    val songs: List<AlbumSong>,
    val pub: String,
)

/** 歌词结果（对齐 Dart `({String lrc, String? tlyric})`，另带逐字） */
data class KuwoLyric(
    val lrc: String,
    val tlyric: String?,
    val wordLyric: String? = null,
)

/** 单曲补充信息结果（对齐 Dart `({String? trackNumber, String? releaseDate})`） */
data class KuwoEnrich(
    val trackNumber: String?,
    val releaseDate: String?,
    val albumArtist: String?,
)

/** 歌单单页结果（对齐 Dart 私有类 `_KuwoPage`） */
data class KuwoPage(
    val songs: List<Song>,
    val total: Int,
    val name: String,
    val creator: String,
    val cover: String,
)

// ── 缓存 TTL（与 RN 版常量照抄数值） ──

/** 搜索缓存 TTL（对齐 RN `SEARCH_CACHE_TTL` = 10 分钟） */
private const val _searchCacheTtl: Long = 10 * 60 * 1000L

/** 播放直链缓存 TTL（对齐 RN `PLAY_URL_CACHE_TTL` = 30 分钟） */
private const val _playUrlCacheTtl: Long = 30 * 60 * 1000L

/** 歌手/专辑列表缓存 TTL（对齐 RN 字面量 `30 * 1000` = 30 秒） */
private const val _artistCacheTtl: Long = 30 * 1000L

// ── 常量（与 RN 版完全一致） ──

/** 酷我 APK 来源标识 */
private const val _kuwoSource = "kwplayerhd_ar_4.3.0.8_tianbao_T1A_qirui.apk"

/** 安卓客户端 UA（搜索/专辑接口） */
private const val _androidUa = "kwplayerhd_ar_4.3.0.8_tianbao_T1A_qirui.apk"

/** 移动端浏览器 UA（播放链接/歌词接口） */
private const val _mobileUa =
    "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

/** 桌面浏览器 UA（web www 接口） */
private const val _desktopUa =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"

/** 歌手/专辑 web 接口基地址 */
private const val _apiBase = "https://www.kuwo.cn/api/www"

/** 专辑接口基地址 */
private const val _apiBaseAlbum = "https://wapi.kuwo.cn/api/www"

/** 自定义签名使用的 cookie 名 */
private const val _kwCookieName = "Hm_Iuvt_cdb524f42f23cer9b268564v7y735ewrq2324"

/** 硬编码 cookie 值（供 [generateKuwoSecret] 调用方使用，与 RN 版保持一致） */
const val kuwoHardcodedCookie = "RiGQWD34iKkZrnSYQycpmdGKAkaawJEA"

/** 音质 -> 酷我 br 参数（保持 RN 版 BR_MAP 语义） */
private val _brMap: Map<Quality, String> = mapOf(
    Quality.k128k to "128kmp3",
    Quality.k320k to "320kmp3",
    Quality.flac to "2000kflac",
    Quality.hires to "4000kflac",
)

private val _reLeadingDigits by lazy { Regex("^\\d+/") }
private val _reAlbumCover by lazy { Regex("/star/albumcover/\\d+/(.+)") }
private val _reLevelKey by lazy { Regex("[=:]") }
// 注意：`}}` 这种未转义花括号在桌面 JVM 上合法，但 Android 用的是 ICU 正则引擎
// （com.android.icu.util.regex.PatternNative），会直接抛 PatternSyntaxException，
// 且这些是顶层 val → 触发 <clinit> 失败 → 整个 KuwoApi 类不可用（搜索一提交就闪退）。
// 因此 `}` 必须转义为 `\}`。
private val _reMusicList by lazy { Regex("\"musiclist\"\\s*:\\s*(\\[[\\s\\S]*?\\])\\s*(?:,\"|\\}\\})") }
private val _rePub by lazy { Regex("\"pub\"\\s*:\\s*\"([^\"]+)\"") }
private val _reCjk by lazy { Regex("[\\u4e00-\\u9fff]") }
private val _reKana by lazy { Regex("[\\u3040-\\u309f\\u30a0-\\u30ff]") }
// ── 通用辅助（对齐 JS 真值/取整/类型语义） ──

/** JS 真值语义：null / false / 0 / '' / NaN 为假，其余为真 */
private fun _truthy(value: Any?): Boolean = when (value) {
    null -> false
    is Boolean -> value
    is Number -> value.toDouble().let { it != 0.0 && !it.isNaN() }
    is String -> value.isNotEmpty()
    else -> true
}

/** `a || b || c` 语义：返回第一个真值；全为假返回 null */
private fun _firstTruthy(values: List<Any?>): Any? {
    for (v in values) {
        if (_truthy(v)) return v
    }
    return null
}

/** 转字符串（null -> ''） */
private fun _s(v: Any?): String = v?.toString() ?: ""

/** 取字符串，空则用默认值（对齐 `v || def`） */
private fun _strOr(v: Any?, def: String): String {
    val s = _s(v)
    return if (s.isEmpty()) def else s
}

/** JS `parseInt(x, 10) || 0` 语义 */
private fun _toInt(v: Any?): Int = when (v) {
    is Int -> v
    is Long -> v.toInt()
    is Double -> v.toInt()
    is Float -> v.toInt()
    is Number -> v.toInt()
    else -> _s(v).toDoubleOrNull()?.toInt() ?: 0
}

/** 将 JSON 数组安全转换为 Map 列表 */
private fun _mapList(v: Any?): List<Map<String, Any?>> {
    if (v !is List<*>) return emptyList()
    val out = ArrayList<Map<String, Any?>>()
    for (e in v) {
        if (e is Map<*, *>) {
            out.add(e.entries.associate { (k, value) -> k.toString() to value })
        }
    }
    return out
}

/**
 * 百分号编码（对齐 Dart `Uri.encodeComponent`，保留集 `A-Za-z0-9-_.!~*'()`，大写十六进制）。
 *
 * `Uri.encode` / `URLEncoder` 与 Dart 语义不完全一致，故此私有实现逐字节对齐。
 */
private fun _encodeComponent(s: String): String {
    val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"
    val hexDigit = "0123456789ABCDEF"
    val sb = StringBuilder()
    for (b in s.toByteArray(Charsets.UTF_8)) {
        val code = b.toInt() and 0xFF
        val c = code.toChar()
        if (unreserved.indexOf(c) >= 0) {
            sb.append(c)
        } else {
            sb.append('%')
            sb.append(hexDigit[code ushr 4])
            sb.append(hexDigit[code and 0x0F])
        }
    }
    return sb.toString()
}

/**
 * 解码 HTML 实体（1:1 移植 Flutter `lib/core/utils/string_utils.dart`）。
 *
 * 供本文件及后续其它平台移植复用。
 */
internal fun decodeHtmlEntities(text: String): String = text
    .replace("&nbsp;", " ")
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&apos;", "'")
    .replace("\\u0026", "&")
    .replace("\\u0020", " ")
    .replace("\\&", "&")
    .replace(Regex("_+u0026"), "&")
    .replace(Regex("_+u0020"), " ")

// ── 封面 ──

/** 依据搜索条目构建 800px 封面地址（对齐 RN buildCoverUrl） */
fun buildCoverUrl(item: Map<String, Any?>): String {
    val raw = _strOr(
        _firstTruthy(
            listOf(
                item["web_albumpic_short"],
                item["hts_img"],
                item["img"],
                item["pic"],
                item["web_artistpic_short"],
            ),
        ),
        "",
    )
    if (raw.isEmpty()) return ""
    if (_reLeadingDigits.containsMatchIn(raw)) {
        return "http://img1.kuwo.cn/star/albumcover/800/" +
            _reLeadingDigits.replaceFirst(raw, "")
    }
    if (raw.startsWith("http")) {
        val m = _reAlbumCover.find(raw)
        if (m != null) {
            return "http://img1.kuwo.cn/star/albumcover/800/${m.groupValues[1]}"
        }
    }
    return raw
}

/** 依据专辑构建大图封面地址（各平台规则不同） */
fun buildAlbumCoverUrl(album: Album): String = when (album.platform) {
    MusicPlatform.qq -> {
        val raw = _strOr(_firstTruthy(listOf(album.pic, album.img)), "")
        if (raw.startsWith("http")) {
            raw
        } else if (album.albumid.isEmpty()) {
            ""
        } else {
            "https://y.gtimg.cn/music/photo_new/T002R800x800M000${album.albumid}.jpg"
        }
    }
    MusicPlatform.netease -> {
        // 搜索接口直接给 picUrl
        _strOr(_firstTruthy(listOf(album.pic, album.img)), "")
    }
    MusicPlatform.qobuz -> _strOr(_firstTruthy(listOf(album.pic, album.img)), "")
    MusicPlatform.kugou -> {
        // imgurl 带 {size} 占位符
        val raw = _strOr(_firstTruthy(listOf(album.pic, album.img)), "")
        raw.replace("{size}", "480")
    }
    MusicPlatform.kuwo,
    MusicPlatform.kkbox,
    MusicPlatform.qobuz,
    -> _buildKuwoAlbumCoverUrl(album)
}

/** 酷我专辑 800px 封面（对齐 RN buildAlbumCoverUrl） */
private fun _buildKuwoAlbumCoverUrl(album: Album): String {
    val raw = _strOr(_firstTruthy(listOf(album.pic, album.htsImg, album.img)), "")
    if (raw.isEmpty()) return ""
    if (_reLeadingDigits.containsMatchIn(raw)) {
        return "http://img1.kuwo.cn/star/albumcover/800/" +
            _reLeadingDigits.replaceFirst(raw, "")
    }
    if (raw.startsWith("http")) {
        val m = _reAlbumCover.find(raw)
        if (m != null) {
            return "http://img1.kuwo.cn/star/albumcover/800/${m.groupValues[1]}"
        }
    }
    return raw
}

// ── 音质 ──

/** 解析 MINFO 串中的可用音质（对齐 RN parseQualities） */
fun parseQualities(minfo: String): List<Quality> {
    val parts = minfo.split(";")
    val seen = HashSet<Quality>()
    val result = ArrayList<Quality>()
    for (part in parts) {
        val kv = part.split(",")
        var level: String? = null
        for (k in kv) {
            if (k.startsWith("level=") || k.startsWith("level:")) {
                val seg = k.split(_reLevelKey)
                if (seg.size > 1) level = seg[1]
                break
            }
        }
        if (level == "h") {
            if (seen.add(Quality.k128k)) result.add(Quality.k128k)
        } else if (level == "p" || level == "s") {
            if (seen.add(Quality.k320k)) result.add(Quality.k320k)
        } else if (level == "ff" || level == "zp" || level == "zply") {
            if (seen.add(Quality.flac)) result.add(Quality.flac)
        }
    }
    val order = listOf(Quality.flac, Quality.k320k, Quality.k128k)
    result.sortBy { order.indexOf(it) }
    return result
}

/**
 * 酷我曲目音质判定。
 *
 * **优先按 `MINFO` 解析** —— 专辑(`searchlist.kuwo.cn/r.s?stype=albuminfo`)、歌单、搜索
 * 三个接口的曲目都带 `MINFO`，而 `hasLossless` 只有部分接口有：专辑接口里它**根本不存在**
 * （实测：一张专辑响应 `MINFO` ×22、`hasLossless` ×0），所以此前专辑页音质永远封顶 320k。
 * 拿不到 `MINFO` 时才退回 `hasLossless` 兜底。
 */
private fun _kuwoQualities(item: Map<String, Any?>): List<Quality> {
    val byMinfo = parseQualities(_s(_firstTruthy(listOf(item["MINFO"], item["N_MINFO"]))))
    if (byMinfo.isNotEmpty()) return byMinfo
    val fallback = mutableListOf(Quality.k320k, Quality.k128k)
    if (_truthy(item["hasLossless"])) fallback.add(0, Quality.flac)
    return fallback
}

/** 各档位字节数（从 `MINFO` 的 `size:32.38Mb` 取）；取不到返回空表 */
private fun _kuwoQualitySizes(item: Map<String, Any?>): Map<Quality, Long> =
    parseQualitySizes(_s(_firstTruthy(listOf(item["MINFO"], item["N_MINFO"]))))

/** 解析 MINFO 串里各档位的字节数（`size:32.38Mb`） */
fun parseQualitySizes(minfo: String): Map<Quality, Long> {
    val out = HashMap<Quality, Long>()
    for (part in minfo.split(";")) {
        var level: String? = null
        var sizeBytes = 0L
        for (kv in part.split(",")) {
            when {
                kv.startsWith("level") -> level = kv.substringAfter(':').substringAfter('=')
                kv.startsWith("size") -> sizeBytes = _parseSizeText(kv.substringAfter(':').substringAfter('='))
            }
        }
        val q = when (level) {
            "h" -> Quality.k128k
            "p", "s" -> Quality.k320k
            "ff", "zp", "zply" -> Quality.flac
            else -> null
        } ?: continue
        if (sizeBytes > 0L && q !in out) out[q] = sizeBytes
    }
    return out
}

private fun _parseSizeText(text: String): Long {
    val t = text.trim().lowercase()
    val num = t.takeWhile { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return 0L
    return when {
        t.endsWith("gb") -> (num * 1024 * 1024 * 1024).toLong()
        t.endsWith("mb") -> (num * 1024 * 1024).toLong()
        t.endsWith("kb") -> (num * 1024).toLong()
        else -> num.toLong()
    }
}

// ── 伪 JSON 解析（单引号 -> 双引号，异常时正则兜底） ──

/**
 * 解析酷我搜索类响应：先整体替换 `'` 为 `"` 再解析，
 * 失败时用 `"key": [...]` 正则兜底（与 RN 版逻辑一致）。
 *
 * [keysPattern] 形如 `abslist|list`，用于定位数组字段。
 */
/** 解析酷我搜索系伪 JSON（单引号 -> 双引号）为顶层 Map；失败返回 null */
private fun _parseKuwoRoot(text: String): Map<String, Any?>? {
    if (text.isBlank()) return null
    return try {
        jsonElementToAny(json.parseToJsonElement(text.replace("'", "\""))) as? Map<String, Any?>
    } catch (_: Throwable) {
        null
    }
}

private fun _parseKuwoList(text: String, keysPattern: String): List<Map<String, Any?>> {
    val normalized = text.replace("'", "\"")
    try {
        val parsed = jsonElementToAny(json.parseToJsonElement(normalized))
        if (parsed is Map<*, *>) {
            for (key in keysPattern.split("|")) {
                val v = parsed[key]
                if (v is List<*>) return _mapList(v)
            }
        }
        return emptyList()
    } catch (_: Throwable) {
        // 落入正则兜底
    }
    // `}` 必须转义：Android ICU 正则不接受未转义花括号（桌面 JVM 会放行，容易漏测）
    val re by lazy { Regex("\"(?:$keysPattern)\":\\s*(\\[[\\s\\S]*?\\])\\s*(?:,\"|\\}\\})") }
    val match = re.find(normalized)
    if (match != null) {
        try {
            return _mapList(jsonElementToAny(json.parseToJsonElement(match.groupValues[1])))
        } catch (_: Throwable) {
            return emptyList()
        }
    }
    return emptyList()
}

// ── 搜索 ──

/** 酷我歌手头像：用歌手搜索按名字取（酷我 artistInfo 接口返回空，不可用） */
suspend fun fetchKuwoArtistAvatar(name: String): String {
    val q = name.trim()
    if (q.isEmpty()) return ""
    return try {
        val list = searchArtist(q)
        for (a in list) {
            if (a.name == q && a.picture.isNotEmpty()) return a.picture
        }
        if (list.isNotEmpty()) list.first().picture else ""
    } catch (_: Throwable) {
        ""
    }
}

/**
 * 搜索酷我歌曲，返回统一 [Song]（platform: kuwo）。
 *
 * 保持原接口形态（`rn=100`，且第 1 页不带 `pn`）。
 * 注意：酷我的 `pn` 是 **0 基**（`pn=0` 才是第一页），所以 [page] > 1 时传 `pn=page-1`。
 */
suspend fun searchMusic(query: String, page: Int = 1): List<Song> {
    val pnParam = if (page > 1) "&pn=${page - 1}" else ""
    val url =
        "http://search.kuwo.cn/r.s?cluster=0&strategy=2012&vermerge=1&stype=name" +
            "&show_copyright_off=1&rn=100$pnParam" +
            "&all=${_encodeComponent(query)}" +
            "&encoding=utf8&rformat=json&vipver=MUSIC_8.7.7.0_BCS37&ft=music" +
            "&newver=1&client=kt&_=${System.currentTimeMillis()}"

    val text = httpGetText(url, mapOf("User-Agent" to _androidUa))
    val list = _parseKuwoList(text, "abslist|list")
    return _mapSearchSongs(list)
}

/** 将酷我搜索原始条目映射为 [Song]（缓存命中与网络路径共用） */
private fun _mapSearchSongs(raw: Any?): List<Song> {
    return _mapList(raw).map { item ->
        val rawRid = _s(item["MUSICRID"])
        val rid = rawRid.replaceFirst("MUSIC_", "")
        val minfo = _s(_firstTruthy(listOf(item["MINFO"], item["N_MINFO"])))
        val qualities = parseQualities(minfo).toMutableList()
        if (qualities.isEmpty()) qualities.add(Quality.k128k)
        // 酷我：ARTIST 用 & 分隔多歌手；allartistid 是与之对应的 id 串（& 分隔）
        val artistNames = decodeHtmlEntities(_strOr(item["ARTIST"], "未知歌手"))
            .replace(Regex("\\\\+u0026"), "&")
            .split("&")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val artistIds = _s(item["allartistid"])
            // 酷我返回的 & 可能被转义成 \u0026 / \\u0026（伪 JSON 不走 jsonDecode），统一还原
            .replace(Regex("\\\\+u0026"), "&")
            .replace("\\", "")
            .split("&")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "0" }
            .takeIf { it.size == artistNames.size } ?: emptyList()
        Song(
            id = rid,
            title = decodeHtmlEntities(
                _strOr(_firstTruthy(listOf(item["SONGNAME"], item["NAME"])), "未知歌曲"),
            ),
            // 保持原样：直接用 ARTIST 原串（不做 & 拆分/重排）
            artist = decodeHtmlEntities(_strOr(item["ARTIST"], "未知歌手")),
            cover = buildCoverUrl(item),
            album = decodeHtmlEntities(_s(item["ALBUM"])),
            albumId = if (_truthy(item["ALBUMID"])) _s(item["ALBUMID"]) else null,
            duration = _toInt(item["DURATION"]),
            platform = MusicPlatform.kuwo,
            playUrl = "",
            lyric = null,
            tlyric = null,
            qualities = qualities,
            qualitySizes = parseQualitySizes(minfo),
            albumArtist = if (_truthy(item["AARTIST"])) {
                decodeHtmlEntities(_s(item["AARTIST"]))
            } else {
                null
            },
            artistNames = artistNames,
            artistIds = artistIds,
        )
    }
}

/** 搜索专辑，返回 [Album] 列表（对齐 RN searchAlbum 返回结构） */
suspend fun searchAlbum(keyword: String, page: Int = 1, pageSize: Int = 50): List<Album> {
    // 酷我专辑搜索的 rn 上限为 15：rn >= 20 时会返回空的 abslist（实测 rn=20/25/30/50 全空）
    val rn = if (pageSize > 15) 15 else pageSize
    val url =
        "http://search.kuwo.cn/r.s?ft=album&all=${_encodeComponent(keyword)}" +
            "&pn=$page&rn=$rn&rformat=json&client=kt&vipver=1&encoding=utf8" +
            "&cluster=0&strategy=2012"

    val text = httpGetText(url, mapOf("User-Agent" to _androidUa))
    val list = _parseKuwoList(text, "albumlist|list")

    return list.map { item ->
        Album(
            albumid = _s(item["albumid"]),
            name = decodeHtmlEntities(
                _strOr(_firstTruthy(listOf(item["name"], item["NAME"])), "未知专辑"),
            ),
            artist = decodeHtmlEntities(
                _strOr(_firstTruthy(listOf(item["artist"], item["ARTIST"])), "未知歌手"),
            ),
            platform = MusicPlatform.kuwo,
            musiccnt = _strOr(
                _firstTruthy(listOf(item["musiccnt"], item["MUSICCNT"])),
                "0",
            ),
            pub = _strOr(_firstTruthy(listOf(item["pub"], item["PUB"])), ""),
            pic = _strOr(_firstTruthy(listOf(item["pic"], item["PIC"])), ""),
            img = _strOr(_firstTruthy(listOf(item["img"], item["IMG"])), ""),
            htsImg = _strOr(_firstTruthy(listOf(item["hts_img"], item["HTS_IMG"])), ""),
            company = _strOr(_firstTruthy(listOf(item["company"], item["COMPANY"])), ""),
            lang = _strOr(_firstTruthy(listOf(item["lang"], item["LANG"])), ""),
            vip = _strOr(_firstTruthy(listOf(item["vip"], item["VIP"])), ""),
            playcnt = _strOr(item["PLAYCNT"], "0"),
        )
    }
}

// ── 歌手 ──

/** 生成请求 id（对齐 RN generateReqId 的 UUID 变体） */
fun generateReqId(): String {
    val hex = "0123456789abcdef"
    val buf = StringBuilder()
    for (i in 0 until 36) {
        when {
            i == 8 || i == 13 || i == 18 || i == 23 -> buf.append('-')
            i == 14 -> buf.append('1')
            i == 19 -> buf.append(hex[(Random.nextDouble() * 4).toInt() + 8])
            else -> buf.append(hex[(Random.nextDouble() * 16).toInt()])
        }
    }
    return buf.toString()
}

/**
 * 酷我歌手简介：走经典 `r.s` 歌手搜索的 `abslist[].desc`。
 *
 * 注意：[searchArtist] 走的是 `/api/www/search/searchArtistBykeyWord`，那条响应**不带** `desc`，
 * 所以简介必须用这个经典接口单独取一次。
 */
suspend fun fetchKuwoArtistBio(name: String): String {
    val q = name.trim()
    if (q.isEmpty()) return ""
    return try {
        val url =
            "http://search.kuwo.cn/r.s?ft=artist&all=${_encodeComponent(q)}" +
                "&rn=5&pn=1&rformat=json&encoding=utf8"
        val text = httpGetText(url, mapOf("User-Agent" to _desktopUa))
        val list = _parseKuwoList(text, "abslist|list")
        // 优先精确同名，否则取第一条
        val hit = list.firstOrNull { _s(it["ARTIST"]) == q } ?: list.firstOrNull()
        val raw = (hit?.get("desc") ?: "").toString()
        // 伪 JSON 里换行是转义的 `\n`（可能被多重转义），统一还原成空格并清掉残留下划线
        decodeHtmlEntities(
            raw.replace(Regex("\\\\+n"), " ")
                .replace(Regex("\\\\+"), "")
                .replace(Regex("\\s+"), " ")
                .trim(),
        )
    } catch (_: Throwable) {
        ""
    }
}

/** 搜索歌手，返回 [Artist] 列表；失败返回空 */
suspend fun searchArtist(keyword: String): List<Artist> {
    val reqId = generateReqId()
    val encodedKey = _encodeComponent(keyword)
    val url =
        "$_apiBase/search/searchArtistBykeyWord" +
            "?key=$encodedKey&pn=1&rn=30&httpsStatus=1" +
            "&reqId=$reqId&plat=web_www&from="
    return try {
        val resp = httpGetJson(
            url,
            mapOf(
                "User-Agent" to _desktopUa,
                "Accept" to "application/json, text/plain, */*",
                "sec-ch-ua-platform" to "\"Android\"",
                "Secret" to "5420e9d5615d1142b37ce4f778c0fc5466bbbf857cb7f1333cd123c1b5de34e80565f09f",
                "sec-ch-ua" to "\"Android WebView\";v=\"149\", \"Chromium\";v=\"149\", \"Not)A;Brand\";v=\"24\"",
                "sec-ch-ua-mobile" to "?1",
                "X-Requested-With" to "idm.internet.download.manager.plus",
                "Sec-Fetch-Site" to "same-origin",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Dest" to "empty",
                "Referer" to "https://www.kuwo.cn/search/singers?key=$encodedKey",
                "Accept-Language" to "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7",
                "Cookie" to "Hm_Iuvt_cdb524f42f23cer9b268564v7y735ewrq2324=wpbwpzyimYDf3zMyntNRKMm45QXX3XQd",
            ),
        )
        if (_toInt(resp["code"]) != 200) return emptyList()
        val data = resp["data"]
        val listRaw = if (data is Map<*, *>) {
            _firstTruthy(listOf(data["artistList"], data["list"]))
        } else {
            null
        }
        val rawList = _mapList(listRaw)
        _mapArtists(rawList)
    } catch (_: Throwable) {
        emptyList()
    }
}

/** 将酷我歌手原始条目映射为 [Artist]（缓存命中与网络路径共用） */
private fun _mapArtists(raw: Any?): List<Artist> {
    return _mapList(raw).map { item ->
        Artist(
            id = _s(item["id"]),
            name = decodeHtmlEntities(_strOr(item["name"], "未知歌手")),
            picture = _strOr(_firstTruthy(listOf(item["pic300"], item["pic"])), ""),
            musicNum = _toInt(item["musicNum"]),
        )
    }
}

/** 获取歌手歌曲（分页 20 条）；失败返回空列表与 total=0 */
suspend fun getArtistSongs(artistId: String, page: Int = 1): KuwoArtistSongs {
    // 歌手歌曲接口（对齐 LX kw/artist.js 的 stype=artist2music）：伪 JSON，歌曲在 musiclist，带 MINFO
    // 注意 pn 是 0 基；旧 web 接口 artistMusic 的曲目没有 MINFO → 歌手页音质取不到大小
    val url =
        "http://search.kuwo.cn/r.s?stype=artist2music&artistid=$artistId" +
            "&pn=${page - 1}&rn=20&encode=utf8&rformat=json" +
            "&vipver=MUSIC_8.7.7.0_BCS37&newver=1&client=kt"
    return try {
        val root = _parseKuwoRoot(httpGetText(url, mapOf("User-Agent" to _androidUa)))
            ?: return KuwoArtistSongs(emptyList(), 0)
        val total = _toInt(root["total"])
        val songs = _mapList(root["musiclist"]).map { _mapKuwoSong(it) }
        KuwoArtistSongs(songs = songs, total = total)
    } catch (_: Throwable) {
        KuwoArtistSongs(emptyList(), 0)
    }
}

/**
 * 获取歌手全部专辑；失败返回空。
 *
 * 主源 `stype=albumlist` 的条目带 `musiccnt`；web `artist/artistAlbum` 没有曲目数字段
 * （用它卡片会恒显示 0 首），仅作主源失效时的兜底。
 */
suspend fun getArtistAlbums(artistId: String): List<Album> {
    // v2：v1 缓存的是无曲目数的旧结构，命中会继续显示 0 首
    val cacheKey = "artist:albums:v2:$artistId"
    val cached = cacheService.get<List<Any?>>(cacheKey)
    if (cached != null) return _mapAlbums(cached)

    var rawList = _fetchArtistAlbumsRaw(artistId)
    if (rawList.isEmpty()) rawList = _fetchArtistAlbumsRawFallback(artistId)
    if (rawList.isEmpty()) return emptyList()
    cacheService.set(cacheKey, rawList, _artistCacheTtl)
    return _mapAlbums(rawList)
}

/** 歌手专辑主源（`stype=albumlist`；一页 100，取不满即到底） */
private suspend fun _fetchArtistAlbumsRaw(artistId: String): List<Map<String, Any?>> {
    val out = ArrayList<Map<String, Any?>>()
    try {
        var page = 0
        while (page < 20) {
            val url =
                "http://search.kuwo.cn/r.s?stype=albumlist&artistid=$artistId" +
                    "&pn=$page&rn=100&rformat=json&encoding=utf8" +
                    "&vipver=MUSIC_8.7.7.0_BCS37&newver=1&client=kt&pcmp4=1"
            val items = _parseKuwoList(
                httpGetText(url, mapOf("User-Agent" to _desktopUa)),
                "albumlist",
            )
            out += items
            if (items.size < 100) break
            page += 1
        }
    } catch (_: Throwable) {
        return out
    }
    return out
}

/** 歌手专辑兜底源（web `artist/artistAlbum`；无曲目数） */
private suspend fun _fetchArtistAlbumsRawFallback(artistId: String): List<Map<String, Any?>> {
    return try {
        val reqId = generateReqId()
        val url =
            "$_apiBaseAlbum/artist/artistAlbum?artistid=$artistId&pn=1" +
                "&rn=100000&httpsStatus=1&reqId=$reqId&plat=web_www&from="
        val resp = httpGetJson(
            url,
            mapOf(
                "User-Agent" to _desktopUa,
                "Connection" to "Keep-Alive",
                "Accept" to "application/json, text/plain, */*",
                "Accept-Encoding" to "identity",
                "Cache-Control" to "no-cache, no-store, must-revalidate",
                "Origin" to "https://www.kuwo.cn",
                "Pragma" to "no-cache",
                "Range" to "bytes=0-",
                "Referer" to "https://www.kuwo.cn/",
                "sec-ch-ua" to "\"Android WebView\";v=\"149\", \"Chromium\";v=\"149\", \"Not)A;Brand\";v=\"24\"",
                "sec-ch-ua-mobile" to "?1",
                "sec-ch-ua-platform" to "\"Android\"",
            ),
        )
        if (_toInt(resp["code"]) != 200) return emptyList()
        val dataRaw = resp["data"]
        val data = if (dataRaw is Map<*, *>) dataRaw else emptyMap<String, Any?>()
        _mapList(data["albumList"])
    } catch (_: Throwable) {
        emptyList()
    }
}

/** 酷我专辑条目 -> [Album]（缓存命中与网络路径共用；两源字段名不同，故取并集） */
private fun _mapAlbums(raw: Any?): List<Album> {
    return _mapList(raw).map { item ->
        Album(
            albumid = _s(item["albumid"]),
            name = decodeHtmlEntities(
                _strOr(_firstTruthy(listOf(item["name"], item["NAME"], item["album"])), "未知专辑"),
            ),
            artist = decodeHtmlEntities(_strOr(item["artist"], "")),
            platform = MusicPlatform.kuwo,
            musiccnt = _strOr(
                _firstTruthy(listOf(item["musiccnt"], item["MUSICCNT"])),
                "0",
            ),
            pub = _strOr(
                _firstTruthy(listOf(item["pub"], item["PUB"], item["releaseDate"])),
                "",
            ),
            pic = _strOr(item["pic"], "").replace(Regex("/(120|240|500)/"), "/800/"),
            img = _strOr(item["img"], ""),
            htsImg = _strOr(item["hts_img"], ""),
            company = _strOr(item["company"], ""),
            lang = _strOr(item["lang"], ""),
            vip = _strOr(item["vip"], ""),
            playcnt = _strOr(item["PLAYCNT"], "0"),
        )
    }
}

// ── 专辑详情 ──

/** 哈希函数（对齐 RN v(data)，31 进制滚动哈希，掩码 0x7FFFFFFF） */
fun v(data: String): String {
    // 注意：必须用 Long —— Dart 的 int 是 64 位，Kotlin Int 会在掩码前溢出（多字节/中文输入尤甚）
    var value = 0L
    for (i in data.indices) {
        val c = data[i].code
        value = (value * 31 + c) and 0x7FFFFFFF
    }
    return value.toString()
}

/** 依据 cookie 推导 Secret 头（对齐 RN getSecret） */
fun getSecret(cookie: String): String {
    val s = v(cookie)
    val keys = listOf("3d6b", "b4a2", "9f7e", "2c81", "e5f0", "1a8b", "7c3d", "6e9f")
    val last2 = if (s.length >= 2) s.substring(s.length - 2) else s
    val idx = (last2.toIntOrNull() ?: 0) % keys.size
    return keys[idx]
}

/** 获取专辑歌曲与发行日期；失败返回空 */
suspend fun getAlbumSongs(albumId: String): KuwoAlbumSongs {
    val cookie = "kw_token=${System.currentTimeMillis()}"
    val secret = getSecret(cookie)

    val url =
        "http://searchlist.kuwo.cn/r.s?stype=albuminfo&albumid=$albumId" +
            "&show_copyright_off=1&alflac=1&vipver=1&sortby=1&newver=1&mobi=1"

    val text = retryUntilSuccess(maxRetries = 20, delayMs = 500L) {
        httpGetText(
            url,
            mapOf("User-Agent" to _androidUa, "Cookie" to cookie, "Secret" to secret),
        )
    }

    var songList = emptyList<Map<String, Any?>>()
    var pub = ""
    try {
        val parsed = jsonElementToAny(json.parseToJsonElement(text))
        if (parsed is Map<*, *>) {
            songList = _mapList(parsed["musiclist"])
            pub = _strOr(parsed["pub"], "")
        }
    } catch (_: Throwable) {
        val m = _reMusicList.find(text)
        if (m != null) {
            try {
                songList = _mapList(jsonElementToAny(json.parseToJsonElement(m.groupValues[1])))
            } catch (_: Throwable) {
                songList = emptyList()
            }
        }
        val pm = _rePub.find(text)
        if (pm != null) pub = pm.groupValues[1]
    }

    val songs = songList.map { item ->
        val artistNameRaw = decodeHtmlEntities(_strOr(item["artist"], "未知歌手"))
        // 酷我专辑歌曲自带 `artistid`（单歌手）与 `allartistid`（多歌手，逗号分隔）。
        // 必须在这里拆名并带上 id：否则 UI 层按下标配对时 id 为空，「跳歌手」会退化成
        // 按名字联网搜索（用户反馈的「点跳转歌手要等一会儿」）。
        val artistNames = splitArtistNames(artistNameRaw)
        val artistIds = splitArtistIds(
            _s(item["allartistid"]).ifEmpty { _s(item["artistid"]) },
        )
        AlbumSong(
            id = _s(item["id"]),
            name = decodeHtmlEntities(_strOr(item["name"], "未知歌曲")),
            artistName = artistNameRaw,
            duration = _toInt(item["duration"]),
            albumArtist = decodeHtmlEntities(_s(item["aartist"])).ifEmpty { null },
            // 带上真实音质：不带的话专辑弹窗的下载候选永远回退 flac/320/128（与本文件专辑路径同款判定）
            qualities = _kuwoQualities(item),
            qualitySizes = _kuwoQualitySizes(item),
            track = if (_truthy(item["track"])) {
                _s(item["track"]).toDoubleOrNull()?.toInt()
            } else {
                null
            },
            artistNames = artistNames.ifEmpty { listOf(artistNameRaw) },
            // 数量不一致时宁可不给 id（退回按名字搜索），也不要把 id 配错歌手
            artistIds = if (artistIds.size == artistNames.size) artistIds else emptyList(),
        )
    }
    return KuwoAlbumSongs(songs = songs, pub = pub)
}

private val _albumDetailCache = HashMap<String, KuwoAlbumSongs>()

/** 补充酷我单曲 metadata（曲目号/发行日期）；无专辑或无匹配返回全 null */
suspend fun enrichKuwoSong(songId: String, albumId: String): KuwoEnrich {
    if (albumId.isEmpty()) return KuwoEnrich(null, null, null)
    return try {
        val album = _albumDetailCache[albumId]
            ?: getAlbumSongs(albumId).also { _albumDetailCache[albumId] = it }
        var matched: AlbumSong? = null
        for (s in album.songs) {
            if (s.id == songId) {
                matched = s
                break
            }
        }
        KuwoEnrich(
            trackNumber = matched?.track?.toString(),
            releaseDate = if (album.pub.isNotEmpty()) album.pub else null,
            albumArtist = matched?.albumArtist,
        )
    } catch (_: Throwable) {
        KuwoEnrich(null, null, null)
    }
}

// ── 播放链接 ──

/** 请求酷我直链（底层接口，对齐 RN fetchKuwoMusicUrl） */
suspend fun fetchKuwoMusicUrl(songId: String, br: String): String {
    val url =
        "http://nmobi.kuwo.cn/mobi.s?f=web&user=0&source=$_kuwoSource" +
            "&type=convert_url_with_sign&rid=$songId&br=$br"

    val text = httpGetText(
        url,
        mapOf("User-Agent" to _mobileUa, "Accept" to "application/json"),
    )
    try {
        val data = jsonElementToAny(json.parseToJsonElement(text))
        if (data is Map<*, *>) {
            val inner = data["data"]
            if (_toInt(data["code"]) == 200 &&
                inner is Map<*, *> &&
                inner["url"] is String
            ) {
                return inner["url"] as String
            }
            if (data["url"] is String) return data["url"] as String
        }
    } catch (_: Throwable) {
        // 落入下方文本兜底（与 RN 版 try/catch 结构一致）
    }
    val trimmed = text.trim()
    if (trimmed.startsWith("http")) return trimmed
    throw IllegalStateException(
        "mobi.s 返回非 URL: ${if (trimmed.length > 100) trimmed.substring(0, 100) else trimmed}",
    )
}

/**
 * 获取播放直链（[quality] 为 null 用 320kmp3；**缓存键带档位**——否则换档命中旧档直链；最多重试 100 次）
 */
suspend fun fetchPlayUrl(songId: String, quality: Quality? = null): String {
    val q = quality ?: Quality.k320k
    val cacheKey = "playurl:$songId:${q.value}"
    val cached = cacheService.get<String>(cacheKey)
    if (cached != null && cached.isNotEmpty()) return cached

    val url = retryUntilSuccess(maxRetries = 2, delayMs = 500L) {
        fetchKuwoMusicUrl(songId, _brMap[q] ?: "128kmp3")
    }
    cacheService.set(cacheKey, url, _playUrlCacheTtl)
    return url
}

/** 下载链接（按指定音质，映射 BR_MAP，最多重试 100 次） */
suspend fun fetchDownloadUrl(songId: String, quality: Quality): String {
    val br = _brMap[quality] ?: "128kmp3"
    return retryUntilSuccess(maxRetries = 2, delayMs = 500L) {
        fetchKuwoMusicUrl(songId, br)
    }
}

/** 仓库层调用别名（与 netease/qq 命名约定对齐） */
suspend fun fetchKuwoPlayUrl(songId: String, quality: Quality? = null): String =
    fetchPlayUrl(songId, quality)

/** 仓库层调用别名（与 netease/qq 命名约定对齐） */
suspend fun fetchKuwoDownloadUrl(songId: String, quality: Quality): String =
    fetchDownloadUrl(songId, quality)

// ── 歌词 ──

/** 通过歌名+歌手搜索酷我 musicId；无结果抛异常 */
suspend fun searchKuwoMusicId(title: String, artist: String): String {
    val query = "$title $artist"
    val url =
        "http://search.kuwo.cn/r.s?cluster=0&strategy=2012&vermerge=1" +
            "&stype=name&show_copyright_off=1&rn=5&all=${_encodeComponent(query)}" +
            "&encoding=utf8&rformat=json&vipver=MUSIC_8.7.7.0_BCS37&ft=music" +
            "&newver=1&client=kt&_=${System.currentTimeMillis()}"

    val text = httpGetText(url, mapOf("User-Agent" to _androidUa))
    val list = _parseKuwoList(text, "abslist|list")
    if (list.isEmpty()) throw IllegalStateException("kuwo search no results")
    return _s(list.first()["MUSICRID"]).replaceFirst("MUSIC_", "")
}

/** LRCX 头里的 `[kuwo:NNN]`（八进制）：十位/个位分别作为词起止时间的除数 */
private val _reKuwoOffset by lazy { Regex("\\[kuwo:(\\d+)\\]") }

/** LRCX 行：`[mm:ss.mmm]<o,d>字<o,d>字…` */
private val _reKuwoLrcxLine by lazy {
    Regex("^\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\]((?:<-?\\d+,-?\\d+>[^<]*)+)")
}
private val _reKuwoWord by lazy { Regex("<(-?\\d+),(-?\\d+)>([^<]*)") }

/**
 * 请求酷我 PC 端歌词接口（LRCX 逐字 / 普通 LRC 同一接口，用 `lrcx` 参数切换）。
 *
 * 响应是「文本头 `tp=content` + CRLFCRLF + zlib 二进制」的混合体，故走 [httpGetBytes]。
 */
private suspend fun _fetchKuwoPcLyric(musicId: String, isLrcx: Boolean): String? = try {
    val params = "user=12345,web,web,web&requester=localhost&req=1&rid=MUSIC_$musicId" +
        if (isLrcx) "&lrcx=1" else ""
    val raw = httpGetBytes(
        "http://newlyric.kuwo.cn/newlyric.lrc?" + LrcxDes.xorBase64(params),
        mapOf("User-Agent" to _mobileUa),
    )
    LrcxDes.decryptResponse(raw, isLrcx)
} catch (_: Throwable) {
    null
}

/**
 * LRCX -> 行级 lrc + 翻译 tlyric + 统一逐字格式。
 *
 * 翻译行判据：所有词标记都是 `<0,0>` 且含中文（原文行一定有非零标记）。
 * 翻译行用**上一句原文的时间戳**输出，保证与 lrc 行的时间戳 key 一致
 * （播放页合并与下载写标签都按这个 key 对齐）。
 */
private fun _parseKuwoLrcx(lrcx: String): KuwoLyric {
    val tag = _reKuwoOffset.find(lrcx)?.groupValues?.get(1)?.toIntOrNull(8) ?: 11
    val d1 = (tag / 10).coerceAtLeast(1)
    val lrc = StringBuilder()
    val tl = StringBuilder()
    val wl = StringBuilder()
    var lastMs = 0
    var lastText: String? = null
    var lastWords = ""

    fun flush() {
        val text = lastText ?: return
        val ts = lrcTimeOfMs(lastMs)
        lrc.append(ts).append(text).append('\n')
        if (lastWords.isNotEmpty()) wl.append(ts).append(lastWords).append('\n')
        lastText = null
        lastWords = ""
    }

    for (line in lrcx.split('\n')) {
        val m = _reKuwoLrcxLine.find(line.trim()) ?: continue
        val ms = m.groupValues[1].toInt() * 60000 +
            m.groupValues[2].toInt() * 1000 +
            m.groupValues[3].toInt()
        val marks = _reKuwoWord.findAll(m.groupValues[4]).toList()
        if (marks.isEmpty()) continue
        val text = marks.joinToString("") { it.groupValues[3] }.trim()
        if (text.isEmpty()) continue
        val allZero = marks.all { it.groupValues[1] == "0" && it.groupValues[2] == "0" }
        if (allZero && _reCjk.containsMatchIn(text)) {
            if (lastText != null) tl.append(lrcTimeOfMs(lastMs)).append(text).append('\n')
            continue
        }
        flush()
        lastMs = ms
        lastText = text
        lastWords = marks.joinToString("") { w ->
            val o = w.groupValues[1].toInt()
            val d = w.groupValues[2].toInt()
            val start = ms + kotlin.math.abs(o + d) / (d1 * 2)
            "<${mmssOfMs(start)}>${w.groupValues[3]}"
        }
    }
    flush()
    return KuwoLyric(
        lrc = if (lrc.isEmpty()) "[00:00.00]暂无歌词" else lrc.toString().trimEnd('\n'),
        tlyric = if (tl.isEmpty()) null else tl.toString().trimEnd('\n'),
        wordLyric = if (wl.isEmpty()) null else wl.toString().trimEnd('\n'),
    )
}

/** 获取酷我歌词：PC 端接口（优先 LRCX 逐字；该曲无 LRCX 时退同一接口的普通 LRC） */
suspend fun fetchKuwoLyric(songId: String, title: String, artist: String): KuwoLyric {
    val musicId = if (songId.startsWith("qobuz:") || songId.startsWith("kkbox:")) {
        searchKuwoMusicId(title, artist)
    } else {
        songId
    }
    val lrcx = _fetchKuwoPcLyric(musicId, true)
    if (!lrcx.isNullOrEmpty()) {
        val parsed = _parseKuwoLrcx(lrcx)
        if (parsed.lrc != "[00:00.00]暂无歌词") return parsed
    }
    val plain = _fetchKuwoPcLyric(musicId, false)
    if (!plain.isNullOrEmpty()) return KuwoLyric(plain.trim(), null, null)
    return KuwoLyric("[00:00.00]暂无歌词", null, null)
}

// ── Kuwo secret 生成（由 Python 脚本移植，逐字节对齐） ──

private fun _charAt(s: String, i: Int): String =
    if (i >= 0 && i < s.length) s.substring(i, i + 1) else ""

/**
 * 生成酷我签名 Secret（对齐 RN generateKuwoSecret，逐字节一致）。
 *
 * 自定义 PRNG：以 cookieName 的数字拼接串为种子，对 cookieVal 逐字符异或后
 * 生成十六进制串，末尾附 8 位随机十六进制。
 */
fun generateKuwoSecret(cookieVal: String, cookieName: String = _kwCookieName): String {
    val nBuf = StringBuilder()
    for (i in cookieName.indices) {
        nBuf.append(cookieName[i].code.toString())
    }
    val n = nBuf.toString()
    val o = n.length / 5
    val rStr =
        _charAt(n, o) +
            _charAt(n, 2 * o) +
            _charAt(n, 3 * o) +
            _charAt(n, 4 * o) +
            _charAt(n, 5 * o)
    val r = rStr.toLongOrNull() ?: 0L
    val c = (cookieName.length + 1) / 2 // ceil(len/2)
    val l = 2147483647L // 2^31 - 1
    if (r < 2L) return ""
    val d = (Random.nextDouble() * 1e9).toLong() % 100000000L
    var nStr = n + d.toString()

    // 前 10 位 + 后 15 位相加，避免 JS 大数精度问题（逐字节对齐 RN 循环）
    while (nStr.length > 10) {
        val first = nStr.substring(0, 10).toLongOrNull() ?: 0L
        val end = if (nStr.length < 25) nStr.length else 25
        val rest = nStr.substring(10, end).toLongOrNull() ?: 0L
        nStr = (first + rest).toString()
    }

    var nVal = (r * (nStr.toLongOrNull() ?: 0L) + c) % l
    val hBuf = StringBuilder()
    for (i in cookieVal.indices) {
        // 注意：Dart 的 `/` 是浮点除法，这里必须用 Double 才能逐字节对齐
        val xorKey = ((nVal.toDouble() / l.toDouble()) * 255.0).toInt()
        val fVal = cookieVal[i].code xor xorKey
        hBuf.append(if (fVal < 16) "0" else "")
        hBuf.append(fVal.toString(16))
        nVal = (r * nVal + c) % l
    }
    return hBuf.toString() + d.toString(16).padStart(8, '0')
}

// ── 歌单 ──

private fun _mapKuwoSong(item: Map<String, Any?>): Song {
    val coverUrl = buildCoverUrl(item).ifEmpty {
        _strOr(_firstTruthy(listOf(item["pic"], item["albumpic"], item["musicPic"])), "")
    }.replaceFirst("/120/", "/800/")
    val qualities = _kuwoQualities(item)
    // 与搜索/专辑路径一致：拆多歌手名并与 id 按下标配对，否则 UI 拿不到多歌手、跳转退化成按名字搜索
    val artistRaw = decodeHtmlEntities(_strOr(item["artist"], "未知歌手"))
    val artistNames = splitArtistNames(artistRaw).ifEmpty { listOf(artistRaw) }
    val artistIds = splitArtistIds(_s(item["allartistid"]).ifEmpty { _s(item["artistid"]) })
    return Song(
        id = _s(_firstTruthy(listOf(item["id"], item["rid"], item["musicrid"])))
            .replaceFirst("MUSIC_", ""),
        title = decodeHtmlEntities(_strOr(item["name"], "未知歌曲")),
        artist = artistRaw,
        cover = coverUrl,
        album = decodeHtmlEntities(_strOr(item["album"], "")),
        albumId = if (_truthy(item["albumid"])) _s(item["albumid"]) else null,
        duration = _toInt(item["duration"]),
        platform = MusicPlatform.kuwo,
        playUrl = "",
        lyric = null,
        tlyric = null,
        qualities = qualities,
        qualitySizes = _kuwoQualitySizes(item),
        artistNames = artistNames,
        // 数量不一致时宁可不给 id（退回按名字搜索），也不要把 id 配错歌手
        artistIds = if (artistIds.size == artistNames.size) artistIds else emptyList(),
    )
}

private suspend fun _fetchKuwoPage(playlistId: String, pn: Int, pageSize: Int): KuwoPage? {
    // 官方歌单接口（对齐 LX kw/songList.js）：返回标准 JSON，顶层 result=="ok"，歌曲在 musiclist
    // 注意 pn 是 0 基；旧版 m.kuwo.cn/newh5app/wapi 那条接口已废弃（无 code、字段为 musicList）
    val url =
        "http://nplserver.kuwo.cn/pl.svc?op=getlistinfo&pid=$playlistId" +
            "&pn=${pn - 1}&rn=$pageSize&encode=utf8&keyset=pl2012" +
            "&identity=kuwo&pcmp4=1&vipver=MUSIC_9.0.5.0_W1&newver=1"
    return try {
        val resp = httpGetJson(url, mapOf("User-Agent" to _desktopUa))
        if (_strOr(resp["result"], "") != "ok") return null
        KuwoPage(
            songs = _mapList(resp["musiclist"]).map { _mapKuwoSong(it) },
            total = _toInt(resp["total"]),
            name = _strOr(resp["title"], ""),
            creator = _strOr(resp["uname"], ""),
            cover = _strOr(resp["pic"], ""),
        )
    } catch (_: Throwable) {
        null
    }
}

/** 获取酷我歌单（分页 100 条累加）；失败返回 null */
suspend fun getKuwoPlaylist(playlistId: String): Playlist? {
    val pageSize = 100
    val first = _fetchKuwoPage(playlistId, 1, pageSize)
    if (first == null || first.songs.isEmpty()) return null

    val total = first.total
    val pages = if (total <= 0) 0 else (total + pageSize - 1) / pageSize

    var allSongs = first.songs
    if (pages > 1) {
        for (i in 0 until pages - 1) {
            val page = _fetchKuwoPage(playlistId, i + 2, pageSize)
            if (page != null) allSongs = allSongs + page.songs
        }
    }

    return Playlist(
        id = playlistId,
        name = first.name,
        creator = first.creator,
        cover = first.cover.replaceFirst("/120/", "/800/"),
        platform = MusicPlatform.kuwo,
        songs = allSongs,
        songCount = allSongs.size,
    )
}
