package com.annieplayer.annie_music.kt.data.sources

import android.util.Base64
import com.annieplayer.annie_music.kt.core.util.KrcDes
import com.annieplayer.annie_music.kt.core.util.lrcTimeOfMs
import com.annieplayer.annie_music.kt.data.model.Album
import com.annieplayer.annie_music.kt.data.model.AlbumSong
import com.annieplayer.annie_music.kt.data.model.AlbumSongsPage
import com.annieplayer.annie_music.kt.data.model.Artist
import com.annieplayer.annie_music.kt.data.model.ArtistSongsPage
import com.annieplayer.annie_music.kt.data.model.ArtistSongsPageSize
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Playlist
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.network.cacheService
import com.annieplayer.annie_music.kt.data.network.httpGetJson
import com.annieplayer.annie_music.kt.data.network.httpGetText
import com.annieplayer.annie_music.kt.data.network.httpPostJson
import com.annieplayer.annie_music.kt.data.network.json
import com.annieplayer.annie_music.kt.data.network.jsonElementToAny
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Inflater
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 酷狗音乐 API（1:1 移植 Flutter lib 下的 kugou_api 源文件，行为完全一致）。
 *
 * 加密/解密逐字节对齐：
 * - [signParams]：MD5(key + sorted(k+'='+v).join('') + body + key)
 * - [decodeKrc]：base64 → 去前 4 字节 → 16 字节 XOR → zlib inflate → UTF-8
 */

// ── 硬编码密钥（逐字节一致） ──

/** 酷狗签名密钥 A（decodeGcid / 专辑接口） */
const val kugouKeyA: String = "OIlwieks28dk2k092lksi2UIkp"

/** 酷狗签名密钥 B（歌单接口） */
const val kugouKeyB: String = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt"

// ── 结果类型（对齐 Dart records / 私有类） ──

/** fetchPlaylistInfo 返回：name / cover */
data class KugouPlaylistInfo(
    val name: String,
    val cover: String,
)

/** fetchSpecialPage 返回：name / cover / songs */
data class KugouSpecialPage(
    val name: String,
    val cover: String,
    val songs: List<Any?>,
)

/** fetchKugouLyric 返回：lrc / tlyric / 逐字 */
data class KugouLyricResult(
    val lrc: String,
    val tlyric: String?,
    val wordLyric: String? = null,
)

/** enrichKuGouSong 返回：trackNumber / discNumber / releaseDate */
data class KugouSongEnrich(
    val trackNumber: String?,
    val discNumber: String?,
    val releaseDate: String?,
)

/** KRC 歌词行：time / text */
data class KugouKrcLine(
    val time: Int,
    val text: String,
)

/** 专辑曲目信息：sort / disc */
data class KugouAlbumTrack(
    val sort: String,
    val disc: String,
)

/** getKugouSongAlbumId 返回：albumId / publishDate */
data class KugouSongAlbumId(
    val albumId: String,
    val publishDate: String,
)

/** fetchKugouArtistInfo 返回：avatar / songCount / intro（歌手简介，可能为空） */
data class KugouArtistInfo(
    val avatar: String,
    val songCount: Int,
    val intro: String = "",
)

/** CDN 补充信息（封面 / 专辑名 / 发行日期） */
private data class KugouCdnEnrich(
    val cover: String,
    val albumName: String,
    val publishDate: String,
)

// ── 常量 ──

/** 搜索缓存 TTL（对齐其它平台 10 分钟） */
private const val _searchCacheTtl: Long = 10 * 60 * 1000L

/** 歌手补充信息缓存 TTL（头像/歌曲数变化慢，30 分钟） */
private const val _artistInfoTtl: Long = 30 * 60 * 1000L

/** 酷狗 web 接口通用 UA */
private const val _kugouWebUa: String =
    "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36"

/** 长青解析接口：POST JSON source/rid/level → code:0,data.url */
private const val _changqingResolveUrl: String =
    "https://musicserver.haitangw.cc/v1/music/resolve-url"

// ── 预编译正则 ──

private val _reDigits by lazy { Regex("^\\d+$") }
private val _reNonDigit by lazy { Regex("[^0-9]") }
private val _reGcid by lazy { Regex("gcid_([a-z0-9]+)", RegexOption.IGNORE_CASE) }
/**
 * 酷狗多歌手分隔符：**不含 `&`** —— 组合名常自带 `&`（如 "Lucas & Steve"），
 * 实测酷狗真正的分隔符是「、」（`Bassjackers、Lucas & Steve、Caroline Pennell`）。
 */
private val _reArtistSep by lazy { Regex("[、,/]") }
private val _reExt by lazy { Regex("\\.[^.]+${'$'}") }
private val _reKrcInline by lazy { Regex("<\\d+,\\d+,\\d+>") }
private val _reKrcLine by lazy { Regex("^\\[(\\d+),(\\d+)\\]") }
private val _reKrcLang by lazy { Regex("\\[language:([\\w=+/]+)\\]") }
private val _reGlobalData by lazy {
    Regex(
        "global\\.data\\s*=\\s*(\\[.*?\\]);",
        RegexOption.DOT_MATCHES_ALL,
    )
}
private val _reName by lazy { Regex("name:\\s*\"([^\"]*)\"") }
private val _rePic by lazy { Regex("pic:\\s*\"([^\"]*)\"") }
private val _reHttpCode by lazy { Regex("HTTP (\\d+)") }
/** 长青接口通用请求头 */
private val _changqingHeaders: Map<String, String> = mapOf(
    "Content-Type" to "application/json",
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36",
    "Accept" to "application/json",
)

/** 酷狗音质 → 长青 level */
private val _changqingLevelKg: Map<String, String> = mapOf(
    "128k" to "standard",
    "320k" to "exhigh",
    "flac" to "lossless",
    "flac24bit" to "hires",
    "atmos" to "atmos",
    "master" to "clear",
)

// ── 内部工具（对齐 JS 的 truthy 语义） ──

/** JS 真值语义：null / false / 0 / '' / NaN 为假，其余为真 */
private fun _truthy(v: Any?): Boolean = when (v) {
    null -> false
    is Boolean -> v
    is Number -> v.toDouble().let { it != 0.0 && !it.isNaN() }
    is String -> v.isNotEmpty()
    else -> true
}

/** `a || b || c` 语义：返回第一个真值；全为假返回 null */
private fun _firstTruthy(values: List<Any?>): Any? {
    for (v in values) {
        if (_truthy(v)) return v
    }
    return null
}

/** 返回第一个非空字符串（对齐 Dart 的同名辅助） */
private fun _firstNonEmpty(values: List<Any?>): String {
    for (v in values) {
        if (v == null) continue
        if (v is String) {
            if (v.isNotEmpty()) return v
        } else if (_truthy(v)) {
            return v.toString()
        }
    }
    return ""
}

/** 可空字符串：空串 / 0 / null 归一为 null */
private fun _optString(v: Any?): String? {
    if (v == null) return null
    if (v is String) return if (v.isEmpty()) null else v
    if (v is Number) return if (v.toDouble() == 0.0) null else v.toString()
    return v.toString()
}

/** Dart 数字相等语义（1 == 1.0 为真；字符串不相等） */
private fun _numEq(v: Any?, n: Int): Boolean =
    v is Number && v.toDouble() == n.toDouble()

/** 将任意 JSON 数组元素安全转成字符串键 Map */
private fun _asMap(v: Any?): Map<String, Any?>? {
    val m = v as? Map<*, *> ?: return null
    val out = HashMap<String, Any?>()
    for ((k, value) in m) out[k.toString()] = value
    return out
}

/**
 * 百分号编码（对齐 Dart Uri.encodeComponent，
 * 保留集 A-Za-z0-9-_.!~*'()，大写十六进制）。
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

/** MD5 十六进制小写（对齐 Dart md5.convert(utf8.encode(x)).toString()） */
private fun _md5Hex(input: String): String {
    val md = java.security.MessageDigest.getInstance("MD5")
    val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
    val hex = "0123456789abcdef"
    val sb = StringBuilder()
    for (b in bytes) {
        val v = b.toInt() and 0xFF
        sb.append(hex[v ushr 4])
        sb.append(hex[v and 0x0F])
    }
    return sb.toString()
}

// ── 签名 ──

/** MD5(key + sorted(k+'='+v).join('') + body + key) */
fun signParams(params: Map<String, String>, key: String, body: String = ""): String {
    val sorted = params.keys.sorted()
    val joined = sorted.joinToString("") { "$it=${params[it]}" }
    return _md5Hex("$key$joined$body$key")
}

// ── GCID 解码 ──

suspend fun decodeGcid(gcid: String): String {
    val ct = System.currentTimeMillis().toString()
    val params = linkedMapOf(
        "appid" to "1005",
        "clienttime" to ct,
        "clientver" to "20109",
        "dfid" to "-",
        "mid" to "0",
        "uuid" to "-",
    )
    val bodyStr = "{\"ret_info\":1,\"data\":[{\"id\":\"$gcid\",\"id_type\":2}]}"
    val sig = signParams(params, kugouKeyA, bodyStr)
    val qs = params.entries.joinToString("&") { "${it.key}=${it.value}" }
    val url = "https://t.kugou.com/v1/songlist/batch_decode?$qs&signature=$sig"
    val data = httpPostJson(
        url,
        headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10)",
            "Content-Type" to "application/json",
            "Referer" to "https://m.kugou.com/",
        ),
        body = bodyStr,
    )
    if (!_numEq(data["status"], 1)) throw IllegalStateException("GCID decode failed")
    val dataField = data["data"]
    val list = if (dataField is Map<*, *>) dataField["list"] else null
    if (list !is List<*> || list.isEmpty()) {
        throw IllegalStateException("GCID decode failed")
    }
    val firstMap = list.first() as? Map<*, *>
    return firstMap?.get("global_collection_id") as? String
        ?: throw IllegalStateException("GCID decode failed")
}

// ── 抓取歌单歌曲页 ──

suspend fun fetchSongPage(
    globalId: String,
    page: Int = 1,
    pageSize: Int = 300,
): Map<String, Any?> {
    val ct = System.currentTimeMillis().toString()
    val params = linkedMapOf(
        "appid" to "1058",
        "clienttime" to ct,
        "clientver" to "20000",
        "global_specialid" to globalId,
        "mid" to ct,
        "page" to "$page",
        "pagesize" to "$pageSize",
        "plat" to "0",
        "specialid" to "0",
        "srcappid" to "2919",
        "uuid" to ct,
        "version" to "8000",
    )
    val sig = signParams(params, kugouKeyB)
    val qs = params.entries.joinToString("&") { "${it.key}=${it.value}" }
    val url =
        "https://mobiles.kugou.com/api/v5/special/song_v2?$qs&signature=$sig"
    val data = httpGetJson(
        url,
        headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 11_0)",
            "Referer" to "https://m3ws.kugou.com/share/index.php",
            "mid" to ct,
            "dfid" to "-",
            "clienttime" to ct,
        ),
    )
    if (!_numEq(data["status"], 1)) {
        throw IllegalStateException("fetch songs failed")
    }
    val d = data["data"]
    return _asMap(d) ?: emptyMap()
}

// ── 抓取歌单信息 ──

suspend fun fetchPlaylistInfo(globalId: String): KugouPlaylistInfo {
    val ct = System.currentTimeMillis()
    val mid = System.currentTimeMillis()
    val uuid = System.currentTimeMillis()
    val params =
        "appid=1058&specialid=0&global_specialid=$globalId&format=jsonp" +
            "&srcappid=2919&clientver=20000&clienttime=$ct&mid=$mid&uuid=$uuid&dfid=-"
    val paramMap = linkedMapOf<String, String>()
    for (p in params.split("&")) {
        val idx = p.indexOf('=')
        if (idx >= 0) paramMap[p.substring(0, idx)] = p.substring(idx + 1)
    }
    val sig = signParams(paramMap, kugouKeyB)
    val url =
        "https://mobiles.kugou.com/api/v5/special/info_v2?$params&signature=$sig"
    try {
        val data = httpGetJson(
            url,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 11_0)",
                "Referer" to "https://m3ws.kugou.com/share/index.php",
                "mid" to "0",
                "dfid" to "-",
                "clienttime" to "0",
            ),
        )
        if (_numEq(data["status"], 1)) {
            val info = data["data"]
            if (info is Map<*, *>) {
                return KugouPlaylistInfo(
                    name = _firstNonEmpty(
                        listOf(info["specialname"], info["name"]),
                    ),
                    cover = _firstNonEmpty(
                        listOf(info["imgurl"], info["img"], info["pic"]),
                    ).replace("{size}", "800"),
                )
            }
        }
    } catch (_: Throwable) {
        // 与 Dart 一致：忽略并返回空
    }
    return KugouPlaylistInfo("", "")
}

// ── 数字 ID 路径（HTML 解析） ──

suspend fun fetchSpecialPage(pid: String): KugouSpecialPage {
    val url =
        "https://www2.kugou.kugou.com/yueku/v9/special/single/$pid-5-9999.html" +
            "?_=${System.currentTimeMillis()}"
    val html = httpGetText(
        url,
        headers = mapOf(
            "User-Agent" to
                "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36",
            "Accept" to "application/json",
        ),
    )
    val songMatch = _reGlobalData.find(html)
        ?: return KugouSpecialPage("", "", emptyList())
    val songs = jsonElementToAny(
        json.parseToJsonElement(songMatch.groupValues[1]),
    )
    val songsList = if (songs is List<*>) songs.map { it } else emptyList<Any?>()
    val nameMatch = _reName.find(html)
    val coverMatch = _rePic.find(html)
    return KugouSpecialPage(
        name = nameMatch?.groupValues?.getOrNull(1) ?: "",
        cover = (coverMatch?.groupValues?.getOrNull(1) ?: "")
            .replace("{size}", "800"),
        songs = songsList,
    )
}

fun parseSongDuration(durRaw: Any?): Int {
    if (durRaw is Number) {
        if (durRaw.toDouble() > 10000) {
            return floor(durRaw.toDouble() / 1000).toInt()
        }
        return durRaw.toInt()
    }
    return 0
}

fun getKuGouQualities(s: Map<String, Any?>): List<Quality> {
    val q = ArrayList<Quality>()
    if (_truthy(s["sqhash"]) || _truthy(s["hash_sq"])) q.add(Quality.flac)
    if (_truthy(s["320hash"]) || _truthy(s["hash_320"])) q.add(Quality.k320k)
    q.add(Quality.k128k)
    return q
}

// ── 歌单主入口 ──

suspend fun getKugouPlaylist(input: String): Playlist? {
    try {
        var songs: List<Song> = emptyList()
        var name = ""
        var cover = ""

        // GCID 路径
        if (input.contains("gcid_") ||
            (!_reDigits.containsMatchIn(input) && input.length > 15)
        ) {
            val gcidMatch = _reGcid.find(input)
            val gcid = (gcidMatch?.groupValues?.getOrNull(1) ?: input).lowercase()
            val globalId = decodeGcid(gcid)

            coroutineScope {
                val infoDeferred = async { fetchPlaylistInfo(globalId) }
                val pageDeferred = async { fetchSongPage(globalId, 1, 300) }
                val info = infoDeferred.await()
                val firstPage = pageDeferred.await()

                name = info.name
                cover = info.cover
                val rawSongs = ArrayList<Any?>()
                val infoRaw = firstPage["info"]
                if (infoRaw is List<*>) {
                    for (e in infoRaw) rawSongs.add(e)
                }
                val totalRaw = firstPage["total"]
                val total = if (totalRaw is Number) totalRaw.toInt() else 0
                val pages = ceil(total / 300.0).toInt()
                if (pages > 1) {
                    val rest = coroutineScope {
                        (2..pages).map { p ->
                            async { fetchSongPage(globalId, p, 300) }
                        }.awaitAll()
                    }
                    for (page in rest) {
                        val pageInfo = page["info"]
                        if (pageInfo is List<*>) {
                            for (e in pageInfo) rawSongs.add(e)
                        }
                    }
                }

                // CDN 补充封面与专辑名
                val enrichMap = HashMap<String, KugouCdnEnrich>()
                try {
                    val ct2 = System.currentTimeMillis().toString()
                    val params2 = linkedMapOf(
                        "appid" to "1058",
                        "clienttime" to ct2,
                        "clientver" to "20000",
                        "global_collection_id" to globalId,
                        "mid" to ct2,
                        "module" to "playlist",
                        "page" to "1",
                        "pagesize" to "300",
                        "specialid" to "0",
                        "srcappid" to "2919",
                        "type" to "0",
                    )
                    val sig2 = signParams(params2, kugouKeyB, "")
                    val qs2 = params2.entries.joinToString("&") {
                        "${it.key}=${it.value}"
                    }
                    val cdnData = httpGetJson(
                        "https://pubsongscdn.kugou.com/v2/get_other_list_file" +
                            "?$qs2&signature=$sig2",
                        headers = mapOf(
                            "User-Agent" to
                                "Mozilla/5.0 (iPhone; CPU iPhone OS 11_0)",
                            "Referer" to
                                "https://m3ws.kugou.com/share/index.php",
                        ),
                    )
                    if (_numEq(cdnData["error_code"], 0)) {
                        val cdnDataField = cdnData["data"]
                        val cdnInfo = if (cdnDataField is Map<*, *>) {
                            cdnDataField["info"]
                        } else {
                            null
                        }
                        if (cdnInfo is List<*>) {
                            for (s in cdnInfo) {
                                if (s !is Map<*, *>) continue
                                val h = (s["hash"] ?: "").toString()
                                if (h.isNotEmpty()) {
                                    val albuminfo = s["albuminfo"]
                                    enrichMap[h.uppercase()] = KugouCdnEnrich(
                                        cover = (s["cover"] ?: "").toString(),
                                        albumName = (if (albuminfo is Map<*, *>) {
                                            albuminfo["name"] ?: ""
                                        } else {
                                            ""
                                        }).toString(),
                                        publishDate = (s["publish_date"] ?: "")
                                            .toString(),
                                    )
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {
                    // 与 Dart 一致：忽略
                }

                val built = ArrayList<Song>()
                for (s in rawSongs) {
                    val m = _asMap(s) ?: continue
                    val hash = (m["hash"] ?: "").toString().uppercase()
                    val enriched = enrichMap[hash]
                    val filename = m["filename"] as? String
                    val artist = decodeHtmlEntities(
                        _firstNonEmpty(
                            listOf(
                                m["singername"],
                                filename?.split(" - ")?.firstOrNull(),
                                "未知歌手",
                            ),
                        ),
                    )
                    val parts = filename?.split(" - ")
                    val titlePart = if (parts != null && parts.size > 1) {
                        _reExt.replace(parts[1], "")
                    } else {
                        null
                    }
                    val transParam = m["trans_param"]
                    built.add(
                        Song(
                            id = "kugou:${m["hash"] ?: ""}",
                            title = decodeHtmlEntities(
                                _firstNonEmpty(
                                    listOf(m["songname"], titlePart, "未知歌曲"),
                                ),
                            ),
                            artist = artist,
                            cover = _firstNonEmpty(
                                listOf(
                                    m["cover"],
                                    enriched?.cover,
                                    if (transParam is Map<*, *>) {
                                        transParam["union_cover"]
                                    } else {
                                        null
                                    },
                                ),
                            ).replace("{size}", "800"),
                            album = decodeHtmlEntities(
                                _firstNonEmpty(
                                    listOf(m["album_name"], enriched?.albumName),
                                ),
                            ),
                            albumId = _optString(m["album_id"]),
                            duration = parseSongDuration(
                                _firstTruthy(
                                    listOf(m["timelen"], m["duration"]),
                                ),
                            ),
                            platform = MusicPlatform.kugou,
                            playUrl = "",
                            qualities = getKuGouQualities(m),
                            releaseDate = _optString(
                                _firstTruthy(
                                    listOf(
                                        enriched?.publishDate,
                                        m["_publish_date"],
                                    ),
                                ),
                            ),
                            albumArtist = artist,
                        ),
                    )
                }
                songs = built
            }
        } else {
            // 数字 ID 路径
            val pid = _reNonDigit.replace(input, "")
            val page = fetchSpecialPage(pid)
            name = page.name
            cover = page.cover
            val built = ArrayList<Song>()
            for (s in page.songs) {
                val m = _asMap(s) ?: continue
                val filename = m["filename"] as? String
                val artist = decodeHtmlEntities(
                    _firstNonEmpty(
                        listOf(
                            m["singername"],
                            filename?.split(" - ")?.firstOrNull(),
                            "未知歌手",
                        ),
                    ),
                )
                val parts = filename?.split(" - ")
                val titlePart = if (parts != null && parts.size > 1) {
                    _reExt.replace(parts[1], "")
                } else {
                    null
                }
                built.add(
                    Song(
                        id = "kugou:${m["hash"] ?: ""}",
                        title = decodeHtmlEntities(
                            _firstNonEmpty(
                                listOf(m["songname"], titlePart, "未知歌曲"),
                            ),
                        ),
                        artist = artist,
                        cover = _firstNonEmpty(listOf(m["cover"], m["_cover"]))
                            .replace("{size}", "800"),
                        album = decodeHtmlEntities(
                            _firstNonEmpty(
                                listOf(m["album_name"], m["_album_name"]),
                            ),
                        ),
                        albumId = _optString(m["album_id"]),
                        duration = parseSongDuration(
                            _firstTruthy(listOf(m["timelen"], m["duration"])),
                        ),
                        platform = MusicPlatform.kugou,
                        playUrl = "",
                        qualities = getKuGouQualities(m),
                        releaseDate = _optString(m["_publish_date"]),
                        albumArtist = artist,
                    ),
                )
            }
            songs = built
        }

        if (songs.isEmpty()) return null
        return Playlist(
            id = input,
            name = name,
            creator = "",
            cover = cover,
            platform = MusicPlatform.kugou,
            songs = songs,
            songCount = songs.size,
        )
    } catch (_: Throwable) {
        return null
    }
}

// ── 歌词（基于 hash） ──

/** zlib 解压（对齐 Dart 的 ZLibCodec(level: -1).decode） */
private fun _inflate(data: ByteArray): ByteArray {
    val inflater = Inflater()
    inflater.setInput(data)
    val out = ByteArrayOutputStream(data.size * 3)
    val buf = ByteArray(8192)
    try {
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0) {
                if (inflater.needsInput() || inflater.needsDictionary()) break
            } else {
                out.write(buf, 0, n)
            }
        }
    } finally {
        inflater.end()
    }
    return out.toByteArray()
}

fun parseKrcLines(krcText: String): List<KugouKrcLine> {
    val result = ArrayList<KugouKrcLine>()
    for (line in krcText.split("\n")) {
        val m = _reKrcLine.find(line) ?: continue
        val startMs = m.groupValues[1].toIntOrNull() ?: 0
        val rest = line.substring(m.value.length)
        val text = _reKrcInline.replace(rest, "").trim()
        if (text.isNotEmpty()) result.add(KugouKrcLine(startMs, text))
    }
    return result
}

fun parseKrcTranslation(krcText: String): List<KugouKrcLine> {
    val m = _reKrcLang.find(krcText) ?: return emptyList()
    try {
        val decoded = String(
            Base64.decode(m.groupValues[1], Base64.DEFAULT),
            Charsets.UTF_8,
        )
        val data = jsonElementToAny(json.parseToJsonElement(decoded))
        if (data !is Map<*, *>) return emptyList()
        val content = data["content"]
        if (content !is List<*>) return emptyList()
        var tlyric: List<*>? = null
        for (item in content) {
            if (item is Map<*, *> && _numEq(item["type"], 1)) {
                val lc = item["lyricContent"]
                if (lc is List<*>) tlyric = lc
                break
            }
        }
        val tl = tlyric ?: return emptyList()
        val out = ArrayList<KugouKrcLine>()
        for (entry in tl) {
            val text = if (entry is List<*>) {
                (entry.firstOrNull() ?: "").toString()
            } else {
                entry.toString()
            }
            out.add(KugouKrcLine(0, text))
        }
        return out
    } catch (_: Throwable) {
        return emptyList()
    }
}

/** krc 词块标记：`<offset,duration,0>`（酷狗是相对行首的毫秒，无需酷我那种除数换算） */
private val _reKrcWord by lazy { Regex("<(-?\\d+),(-?\\d+),\\d+>([^<]*)") }

/** KRC -> 统一逐字格式 `[mm:ss.mmm]<绝对起始ms,时长ms>词…` */
fun kugouKrcToWordLyric(krcText: String): String? {
    val out = StringBuilder()
    for (line in krcText.split("\n")) {
        val m = _reKrcLine.find(line) ?: continue
        val startMs = m.groupValues[1].toIntOrNull() ?: continue
        val marks = _reKrcWord.findAll(line.substring(m.value.length)).toList()
        if (marks.isEmpty()) continue
        val text = marks.joinToString("") { it.groupValues[3] }.trim()
        if (text.isEmpty()) continue
        out.append(lrcTimeOfMs(startMs)).append(
            marks.joinToString("") { w ->
                val off = w.groupValues[1].toIntOrNull() ?: 0
                val dur = w.groupValues[2].toIntOrNull() ?: 0
                "<${startMs + off},$dur>${w.groupValues[3]}"
            },
        ).append('\n')
    }
    return if (out.isEmpty()) null else out.toString().trimEnd('\n')
}

fun fmtTime(ms: Int): String {
    val sec = ms / 1000.0
    val min = floor(sec / 60).toInt()
    val s = floor(sec % 60).toInt()
    val cs = floor((sec - floor(sec)) * 100).toInt()
    val mm = min.toString().padStart(2, '0')
    val ss = s.toString().padStart(2, '0')
    val ccs = cs.toString().padStart(2, '0')
    return "[$mm:$ss.$ccs]"
}

suspend fun fetchKugouLyric(
    songId: String,
    keyword: String = "",
): KugouLyricResult {
    val hash = songId.replaceFirst(Regex("^kugou:"), "")
    try {
        val searchUrl =
            "http://lyrics.kugou.com/search?ver=1&man=yes&client=pc" +
                "&keyword=${_encodeComponent(keyword)}&hash=$hash&timelength=254"
        val searchData = httpGetJson(
            searchUrl,
            headers = mapOf(
                "KG-RC" to "1",
                "KG-THash" to "expand_search_manager.cpp:852736169:451",
                "User-Agent" to "KuGou2012-9020-ExpandSearchManager",
            ),
        )
        val candidatesRaw = searchData["candidates"]
        val candidates = if (candidatesRaw is List<*>) {
            candidatesRaw
        } else {
            emptyList<Any?>()
        }
        if (candidates.isEmpty()) {
            return KugouLyricResult("[00:00.00]暂无歌词", null)
        }

        val info = candidates.first() as? Map<*, *>
        if (info == null) return KugouLyricResult("[00:00.00]暂无歌词", null)
        val idVal = info["id"]
        val accessKey = info["accesskey"]
        val fmt =
            if (_numEq(info["krctype"], 1) && !_numEq(info["contenttype"], 1)) {
                "krc"
            } else {
                "lrc"
            }

        val dlData = httpGetJson(
            "http://lyrics.kugou.com/download?ver=1&client=pc&id=$idVal" +
                "&accesskey=$accessKey&fmt=$fmt&charset=utf8",
            headers = mapOf(
                "KG-RC" to "1",
                "KG-THash" to "expand_search_manager.cpp:852736169:451",
                "User-Agent" to "KuGou2012-9020-ExpandSearchManager",
            ),
        )
        val content = (dlData["content"] ?: "").toString()
        if (fmt == "lrc") {
            val decoded = String(
                Base64.decode(content, Base64.DEFAULT),
                Charsets.UTF_8,
            )
            return KugouLyricResult(decoded, null)
        }
        val krcText = KrcDes.decrypt(content)
        val lyricLines = parseKrcLines(krcText)
        val transLines = parseKrcTranslation(krcText)

        val lrc = lyricLines.joinToString("\n") { fmtTime(it.time) + it.text }
        val tlyric = if (transLines.isNotEmpty()) {
            val parts = ArrayList<String>()
            for (i in lyricLines.indices) {
                val t = if (i < transLines.size) transLines[i].text else ""
                parts.add(fmtTime(lyricLines[i].time) + t)
            }
            parts.joinToString("\n")
        } else {
            null
        }

        return KugouLyricResult(lrc, tlyric, kugouKrcToWordLyric(krcText))
    } catch (_: Throwable) {
        return KugouLyricResult("[00:00.00]暂无歌词", null)
    }
}

// ── 单曲详情（补音轨号/碟号/发行日期） ──

suspend fun getKugouSongAlbumId(songHash: String): KugouSongAlbumId? {
    try {
        val payload: Map<String, Any?> = mapOf(
            "data" to listOf(mapOf("hash" to songHash)),
            "area_code" to "1",
            "show_privilege" to 1,
            "show_album_info" to "1",
            "is_publish" to "",
            "appid" to 1005,
            "clientver" to 11451,
            "mid" to "1",
            "dfid" to "-",
            "clienttime" to System.currentTimeMillis(),
            "key" to kugouKeyA,
            "fields" to
                "album_info,author_name,audio_info,base,songname,classification",
        )
        val data = httpPostJson(
            "http://gateway.kugou.com/v3/album_audio/audio",
            body = payload,
            headers = mapOf(
                "KG-THash" to "13a3164",
                "KG-RC" to "1",
                "KG-Fake" to "0",
                "KG-RF" to "00869891",
                "User-Agent" to
                    "Android712-AndroidPhone-11451-376-0-FeeCacheUpdate-wifi",
                "x-router" to "kmr.service.kugou.com",
                "Content-Type" to "application/json",
            ),
        )
        val outer = data["data"]
        if (outer !is List<*> || outer.isEmpty()) return null
        val inner = outer[0]
        if (inner !is List<*> || inner.isEmpty()) return null
        val item = inner[0]
        if (item !is Map<*, *>) return null
        val albumInfo = item["album_info"]
        if (albumInfo !is Map<*, *> || !_truthy(albumInfo["album_id"])) {
            return null
        }
        return KugouSongAlbumId(
            albumId = albumInfo["album_id"].toString(),
            publishDate = (albumInfo["publish_date"] ?: "").toString(),
        )
    } catch (_: Throwable) {
        return null
    }
}

private val _kugouAlbumTracksCache =
    ConcurrentHashMap<String, Map<String, KugouAlbumTrack>>()

suspend fun fetchKugouAlbumTracks(
    albumId: String,
): Map<String, KugouAlbumTrack> {
    val cached = _kugouAlbumTracksCache[albumId]
    if (cached != null) return cached
    try {
        val ct = System.currentTimeMillis().toString()
        val params = linkedMapOf(
            "album_id" to albumId,
            "appid" to "1005",
            "area_code" to "1",
            "clienttime" to ct,
            "clientver" to "20729",
            "dfid" to "-",
            "is_buy" to "0",
            "mid" to ct,
            "page" to "1",
            "pagesize" to "50",
            "show_album_audios_timelength" to "1",
            "show_album_info" to "1",
            "show_album_tags" to "1",
            "show_awards" to "1",
            "show_classical_author" to "1",
            "show_dycover" to "1",
            "show_short_intro_v2" to "1",
            "userid" to "0",
        )
        val sig = signParams(params, kugouKeyA)
        val qs = params.entries.joinToString("&") { "${it.key}=${it.value}" }
        val raw = httpGetJson(
            "https://gateway.kugou.com/openapi/v1/union/album/audios" +
                "?$qs&signature=$sig",
            headers = mapOf(
                "User-Agent" to
                    "Android16-AndroidPhone-20729-46-0-AlbumDetailFragment",
                "kg-thash" to "228424f",
                "kg-rc" to "1",
                "kg-fake" to "0",
                "kg-rf" to "DC6373DBF6F823AF925CC56D1A81DD72",
            ),
        )
        val data = raw["data"]
        val songData = if (data is Map<*, *>) data["song_data"] else null
        val list = if (songData is Map<*, *>) songData["song_list"] else null
        val lookup = HashMap<String, KugouAlbumTrack>()
        if (list is List<*>) {
            for (s in list) {
                if (s !is Map<*, *>) continue
                val audioInfo = s["audio_info"]
                val h = (
                    if (audioInfo is Map<*, *>) {
                        audioInfo["hash"] ?: ""
                    } else {
                        ""
                    }
                    ).toString()
                if (h.isNotEmpty()) {
                    val extend = s["extend"]
                    lookup[h.uppercase()] = KugouAlbumTrack(
                        sort = _firstNonEmpty(
                            listOf(
                                if (extend is Map<*, *>) extend["sort"] else null,
                            ),
                        ),
                        disc = _firstNonEmpty(
                            listOf(
                                if (extend is Map<*, *>) extend["disc"] else null,
                            ),
                        ),
                    )
                }
            }
        }
        _kugouAlbumTracksCache[albumId] = lookup
        return lookup
    } catch (_: Throwable) {
        return emptyMap()
    }
}

suspend fun enrichKuGouSong(songId: String): KugouSongEnrich {
    val hash = songId.replaceFirst(Regex("^kugou:"), "").uppercase()
    try {
        val info = getKugouSongAlbumId(hash)
            ?: return KugouSongEnrich(null, null, null)
        val lookup = fetchKugouAlbumTracks(info.albumId)
        val matched = lookup[hash]
        return KugouSongEnrich(
            trackNumber = matched?.sort,
            discNumber = matched?.disc,
            releaseDate = if (info.publishDate.isNotEmpty()) {
                info.publishDate
            } else {
                null
            },
        )
    } catch (_: Throwable) {
        return KugouSongEnrich(null, null, null)
    }
}

// ── 单曲搜索 ──

/**
 * 搜索酷狗单曲。
 *
 * 走 web 搜索接口 song_search_v2：条目自带
 * FileHash / SQFileHash / HQFileHash，正好能直接喂给
 * [fetchKugouMusicUrl] 解析直链。
 *
 * [page] 从 1 开始；[pageSize] 每页条数（酷狗单页上限 100）。
 */
suspend fun searchKugouMusic(
    query: String,
    page: Int = 1,
    pageSize: Int = 100,
): List<Song> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()

    val cacheKey = "search:kugou:v2:$q:$page:$pageSize"
    val cached = cacheService.get<List<Any?>>(cacheKey)
    if (cached != null) return _mapKugouSearchSongs(cached)

    val url =
        "https://songsearch.kugou.com/song_search_v2" +
            "?keyword=${_encodeComponent(q)}&page=$page&pagesize=$pageSize" +
            "&userid=-1&clientver=&platform=WebFilter&filter=2" +
            "&iscorrection=1&privilege_filter=0"

    val data = httpGetJson(
        url,
        headers = mapOf(
            "User-Agent" to
                "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36",
        ),
    )

    val d = data["data"]
    val listRaw = if (d is Map<*, *>) d["lists"] else null
    val list = if (listRaw is List<*>) listRaw.map { it } else emptyList<Any?>()

    // 缓存原始条目列表（JSON 可序列化），命中时按同一映射重建 Song
    cacheService.set(cacheKey, list, _searchCacheTtl)
    return _mapKugouSearchSongs(list)
}

/** 将酷狗搜索原始条目映射为 [Song]（字段名与歌单接口不同，需单独映射） */
private fun _mapKugouSearchSongs(raw: Any?): List<Song> {
    val list = if (raw is List<*>) raw else emptyList<Any?>()
    val out = ArrayList<Song>()

    for (item in list) {
        if (item !is Map<*, *>) continue
        val m = item

        val fileHash = _firstNonEmpty(listOf(m["FileHash"], m["ResFileHash"]))
        if (fileHash.isEmpty()) continue

        // 无版权（Privilege = 0）直接跳过
        val privilege = m["Privilege"]
        if (privilege is Number && privilege.toDouble() == 0.0) continue

        val qualities = ArrayList<Quality>()
        if (_truthy(m["SQFileHash"])) qualities.add(Quality.flac)
        if (_truthy(m["HQFileHash"])) qualities.add(Quality.k320k)
        qualities.add(Quality.k128k)

        val durationRaw = _firstTruthy(
            listOf(m["Duration"], m["HQDuration"], m["SQDuration"]),
        )
        val duration = if (durationRaw is Number) {
            durationRaw.toInt()
        } else {
            durationRaw?.toString()?.toIntOrNull() ?: 0
        }

        // 酷狗封面 Image 字段带 {size} 占位符
        val cover = _firstNonEmpty(listOf(m["Image"], m["UnionCover"]))
            .replace("{size}", "240")
        // SingerName 用「、」分隔多歌手，SingerId 是按下标配对的 id 数组
        val rawSinger = decodeHtmlEntities(
            _firstNonEmpty(listOf(m["SingerName"], "未知歌手")),
        ).trim()
        val idsRaw = m["SingerId"]
        val ids = if (idsRaw is List<*>) {
            idsRaw.map { it.toString() }.filter { it.isNotEmpty() }
        } else {
            emptyList<String>()
        }
        // id 只有一个 ⇒ 名字里的 & / 、 都是组合名的一部分（如 "Lucas & Steve"），不要拆
        val artistNames = if (ids.size <= 1) {
            listOf(rawSinger)
        } else {
            rawSinger.split(_reArtistSep).map { it.trim() }.filter { it.isNotEmpty() }
        }
        val artistIds = ids.takeIf { it.size == artistNames.size } ?: emptyList()

        out.add(
            Song(
                id = "kugou:$fileHash",
                title = decodeHtmlEntities(
                    _firstNonEmpty(listOf(m["SongName"], "未知歌曲")),
                ),
                artist = if (artistNames.isEmpty()) {
                    "未知歌手"
                } else {
                    artistNames.joinToString(" / ")
                },
                cover = cover,
                album = decodeHtmlEntities(
                    _firstNonEmpty(listOf(m["AlbumName"])),
                ),
                albumId = _firstNonEmpty(listOf(m["AlbumID"], m["AlbumId"])),
                duration = duration,
                platform = MusicPlatform.kugou,
                playUrl = "",
                qualities = qualities,
                albumArtist = if (artistNames.isEmpty()) {
                    ""
                } else {
                    artistNames.joinToString(" / ")
                },
                artistNames = artistNames,
                artistIds = artistIds,
            ),
        )
    }
    return out
}

// ── 专辑搜索 / 专辑曲目 ──

/** 搜索酷狗专辑（mobilecdn search/album） */
suspend fun searchKugouAlbum(
    query: String,
    page: Int = 1,
    pageSize: Int = 30,
): List<Album> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()

    val cacheKey = "search:kugou:album:v1:$q:$page:$pageSize"
    val cached = cacheService.get<List<Any?>>(cacheKey)
    if (cached != null) return _mapKugouAlbums(cached)

    val url =
        "http://mobilecdn.kugou.com/api/v3/search/album" +
            "?format=json&keyword=${_encodeComponent(q)}" +
            "&page=$page&pagesize=$pageSize"
    val data = httpGetJson(url, headers = mapOf("User-Agent" to _kugouWebUa))
    val d = data["data"]
    val listRaw = if (d is Map<*, *>) d["info"] else null
    val list = if (listRaw is List<*>) listRaw.map { it } else emptyList<Any?>()
    cacheService.set(cacheKey, list, _searchCacheTtl)
    return _mapKugouAlbums(list)
}

/** 酷狗专辑原始条目 → [Album] */
private fun _mapKugouAlbums(raw: Any?): List<Album> {
    val list = if (raw is List<*>) raw else emptyList<Any?>()
    val out = ArrayList<Album>()
    for (item in list) {
        if (item !is Map<*, *>) continue
        val m = item
        val pub = (m["publishtime"] ?: "").toString()
        out.add(
            Album(
                albumid = (m["albumid"] ?: "").toString(),
                name = decodeHtmlEntities((m["albumname"] ?: "").toString()),
                artist = decodeHtmlEntities((m["singername"] ?: "").toString()),
                platform = MusicPlatform.kugou,
                musiccnt = (m["songcount"] ?: "0").toString(),
                pub = if (pub.length >= 10) pub.substring(0, 10) else pub,
                pic = (m["imgurl"] ?: m["img"] ?: "").toString(),
            ),
        )
    }
    return out
}

// ── 跳歌手补 id（酷狗的专辑/歌单接口都不带歌手 id）──

/** 酷狗逐曲歌手（[fetchKugouSongAuthors] 的返回值）。 */
data class KugouAuthor(val id: String, val name: String)

/** 歌手 id 缓存 TTL：30 天（歌手 id 基本不变）。 */
private const val _authorsCacheTtl: Long = 30L * 24 * 60 * 60 * 1000L

/**
 * 取某首歌的逐曲歌手（含 `author_id`），供「跳转歌手」用。
 *
 * 背景：酷狗的**专辑接口与歌单接口都不返回任何歌手 id**（专辑响应里连歌手字段都没有，
 * `v3/album_audio/audio` 也只给 `author_name`）。实测可用的是移动端单曲信息接口：
 *
 * `http://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash=<歌曲hash>`
 * → `authors:[{author_id, author_name, avatar, ...}]`（**无需签名/key，纯 GET**）
 *
 * 该接口**只支持单曲**（不能批量），因此实现上按需调用：只在用户点击「跳转歌手」时查一次，
 * 结果按 hash 落盘缓存，之后同一首歌永久秒开。
 */
suspend fun fetchKugouSongAuthors(songHash: String): List<KugouAuthor> {
    if (songHash.isEmpty()) return emptyList()
    val cacheKey = "kugou:authors:v1:$songHash"
    cacheService.get<List<Any?>>(cacheKey)?.let { return _mapKugouAuthors(it) }
    return try {
        val data = httpGetJson(
            "http://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash=$songHash",
            headers = mapOf(
                "User-Agent" to _kugouWebUa,
                "Referer" to "http://m.kugou.com/",
            ),
        )
        val raw = data["authors"]
        val list: List<Any?> = if (raw is List<*>) raw.map { it } else emptyList()
        if (list.isNotEmpty()) cacheService.set(cacheKey, list, _authorsCacheTtl)
        _mapKugouAuthors(list)
    } catch (_: Throwable) {
        emptyList()
    }
}

private fun _mapKugouAuthors(raw: Any?): List<KugouAuthor> {
    val list = if (raw is List<*>) raw else return emptyList()
    val out = ArrayList<KugouAuthor>()
    for (item in list) {
        if (item !is Map<*, *>) continue
        val id = (item["author_id"] ?: "").toString().trim()
        val name = decodeHtmlEntities((item["author_name"] ?: "").toString().trim())
        if (id.isNotEmpty() && name.isNotEmpty()) out.add(KugouAuthor(id, name))
    }
    return out
}

/** 获取酷狗专辑曲目（mobilecdn album/song） */
suspend fun getKugouAlbumSongs(albumId: String): AlbumSongsPage {
    try {
        val url =
            "http://mobilecdn.kugou.com/api/v3/album/song" +
                "?albumid=$albumId&page=1&pagesize=100&version=9108&plat=0&cmd=1"
        val data = httpGetJson(url, headers = mapOf("User-Agent" to _kugouWebUa))
        val d = data["data"]
        val listRaw = if (d is Map<*, *>) d["info"] else null
        val list = if (listRaw is List<*>) listRaw else emptyList<Any?>()
        val out = ArrayList<AlbumSong>()
        var track = 0
        for (item in list) {
            if (item !is Map<*, *>) continue
            val m = item
            val hash = _firstNonEmpty(listOf(m["hash"], m["sqhash"]))
            if (hash.isEmpty()) continue
            track++
            val filename = m["filename"] as? String
            val parts = filename?.split(" - ")
            val title = _firstNonEmpty(
                listOf(
                    m["songname"],
                    if (parts != null && parts.size > 1) {
                        _reExt.replace(parts[1], "")
                    } else {
                        null
                    },
                ),
            )
            val artist = _firstNonEmpty(
                listOf(
                    m["singername"],
                    if (parts != null && parts.isNotEmpty()) {
                        parts.first()
                    } else {
                        null
                    },
                ),
            )
            val len = m["timelength"]
            // 酷狗的专辑歌曲响应里**没有任何歌手 id 字段**（实测：只有 filename/hash/duration…；
            // 单曲详情 `v3/album_audio/audio` 也只返回 author_name，无 author_id）。
            // 因此这里只能给出歌手名列表，id 留空 —— UI 层会退回按名字搜索
            // （该搜索本身有缓存，第二次起就是秒开）。
            // 没有 id 可校验，所以只按酷狗真正的多歌手分隔符「、」拆，不拆 `&`
            // （组合名常含 `&`，如 "Lucas & Steve"，按 & 拆会误变成两个歌手）。
            val artistNames = decodeHtmlEntities(artist)
                .split("、")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            out.add(
                AlbumSong(
                    id = "kugou:$hash",
                    name = decodeHtmlEntities(title),
                    artistName = decodeHtmlEntities(artist),
                    duration = if (len is Number) len.toInt() / 1000 else 0,
                    track = track,
                    artistNames = artistNames,
                ),
            )
        }
        return AlbumSongsPage(out, fetchKugouAlbumPub(albumId))
    } catch (_: Throwable) {
        return AlbumSongsPage(emptyList())
    }
}

/** 酷狗专辑发行时间：`album/song` 不带，需另取 `album/info` 的 `publishtime`（形如 `2013-06-03 00:00:00`） */
private suspend fun fetchKugouAlbumPub(albumId: String): String {
    return try {
        val info = httpGetJson(
            "http://mobilecdn.kugou.com/api/v3/album/info?albumid=$albumId&version=9108&plat=0",
            headers = mapOf("User-Agent" to _kugouWebUa),
        )
        val d = info["data"]
        val raw = if (d is Map<*, *>) (d["publishtime"] ?: "").toString() else ""
        raw.trim().take(10)
    } catch (_: Throwable) {
        ""
    }
}

// ── 歌手搜索 / 歌手单曲 / 歌手专辑 ──

/** 搜索酷狗歌手（mobilecdn search/singer） */
suspend fun searchKugouArtist(query: String): List<Artist> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()

    val cacheKey = "search:kugou:artist:v1:$q"
    val cached = cacheService.get<List<Any?>>(cacheKey)

    val list: List<Any?>
    if (cached != null) {
        list = cached
    } else {
        val url =
            "http://mobilecdn.kugou.com/api/v3/search/singer" +
                "?format=json&keyword=${_encodeComponent(q)}&page=1&pagesize=30"
        val data = httpGetJson(url, headers = mapOf("User-Agent" to _kugouWebUa))
        val raw = data["data"]
        list = if (raw is List<*>) raw.map { it } else emptyList<Any?>()
        cacheService.set(cacheKey, list, _searchCacheTtl)
    }

    // 酷狗歌手搜索接口不带头像/歌曲数：分批补 singer/info
    // （每批 5 个并发，避免一次 30 个并发被限流；缓存命中时也要补）
    val artists = _mapKugouArtists(list)
    if (artists.isEmpty()) return artists
    val out = ArrayList<Artist>()
    val batchSize = 5
    var i = 0
    while (i < artists.size) {
        val end = if (i + batchSize > artists.size) {
            artists.size
        } else {
            i + batchSize
        }
        val batch = artists.subList(i, end)
        val enriched = coroutineScope {
            batch.map { a ->
                async {
                    val info = fetchKugouArtistInfo(a.id)
                    Artist(
                        id = a.id,
                        name = a.name,
                        picture = info.avatar,
                        musicNum = info.songCount,
                    )
                }
            }.awaitAll()
        }
        out.addAll(enriched)
        i += batchSize
    }
    return out
}

/** 酷狗歌手原始条目 → [Artist]（搜索接口不带头像） */
private fun _mapKugouArtists(raw: Any?): List<Artist> {
    val list = if (raw is List<*>) raw else emptyList<Any?>()
    val out = ArrayList<Artist>()
    for (item in list) {
        if (item !is Map<*, *>) continue
        val m = item
        out.add(
            Artist(
                id = (m["singerid"] ?: "").toString(),
                name = decodeHtmlEntities((m["singername"] ?: "").toString()),
                picture = "",
            ),
        )
    }
    return out
}

/** 酷狗歌手补充信息（头像 + 歌曲数 + 简介），带 30 分钟缓存 */
suspend fun fetchKugouArtistInfo(singerId: String): KugouArtistInfo {
    val cacheKey = "kugou:artist:info:v3:$singerId"
    val cached = cacheService.get<Map<String, Any?>>(cacheKey)
    if (cached != null) {
        val cnt = cached["songCount"]
        return KugouArtistInfo(
            avatar = (cached["avatar"] ?: "").toString(),
            songCount = if (cnt is Number) cnt.toInt() else 0,
            intro = (cached["intro"] ?: "").toString(),
        )
    }

    var avatar = ""
    var songCount = 0
    var intro = ""
    try {
        val data = httpGetJson(
            "http://mobilecdn.kugou.com/api/v3/singer/info?singerid=$singerId",
            headers = mapOf("User-Agent" to _kugouWebUa),
        )
        val d = data["data"]
        if (d is Map<*, *>) {
            // imgurl 带 {size} 占位符
            avatar = (d["imgurl"] ?: "").toString().replace("{size}", "240")
            val cnt = d["songcount"]
            songCount = if (cnt is Number) cnt.toInt() else 0
            // intro 为短简介；长简介在 mix_intro
            intro = _firstNonEmpty(listOf(d["intro"], d["mix_intro"])).trim()
        }
    } catch (_: Throwable) {
        // 忽略：头像/歌曲数缺失不影响主流程
    }
    // 只在拿到有效数据时缓存：避免把「限流/失败」的空结果缓存 30 分钟
    if (avatar.isNotEmpty() || songCount > 0 || intro.isNotEmpty()) {
        cacheService.set(
            cacheKey,
            mapOf("avatar" to avatar, "songCount" to songCount, "intro" to intro),
            _artistInfoTtl,
        )
    }
    return KugouArtistInfo(avatar, songCount, intro)
}

/** 酷狗歌手单曲（singer/song） */
suspend fun getKugouArtistSongs(singerId: String, page: Int = 1): ArtistSongsPage {
    try {
        val url =
            "http://mobilecdn.kugou.com/api/v3/singer/song" +
                "?singerid=$singerId&page=$page&pagesize=$ArtistSongsPageSize&version=9108&plat=0"
        val data = httpGetJson(url, headers = mapOf("User-Agent" to _kugouWebUa))
        val d = data["data"]
        val listRaw = if (d is Map<*, *>) d["info"] else null
        val list = if (listRaw is List<*>) listRaw else emptyList<Any?>()
        val total = if (d is Map<*, *>) (d["total"] as? Number)?.toInt() ?: 0 else 0
        return ArtistSongsPage(_mapKugouArtistSongs(list), total)
    } catch (_: Throwable) {
        return ArtistSongsPage(emptyList(), 0)
    }
}

/** 酷狗歌手单曲条目 → [Song]（singer/song 的 duration 直接是秒） */
private fun _mapKugouArtistSongs(raw: Any?): List<Song> {
    val list = if (raw is List<*>) raw else emptyList<Any?>()
    val out = ArrayList<Song>()
    for (item in list) {
        if (item !is Map<*, *>) continue
        val m = item
        val hash = _firstNonEmpty(listOf(m["sqhash"], m["hash"]))
        if (hash.isEmpty()) continue

        val filename = m["filename"] as? String
        val parts = filename?.split(" - ")
        val title = _firstNonEmpty(
            listOf(
                m["songname"],
                if (parts != null && parts.size > 1) {
                    _reExt.replace(parts[1], "")
                } else {
                    null
                },
            ),
        )
        val artist = _firstNonEmpty(
            listOf(
                m["singername"],
                if (parts != null && parts.isNotEmpty()) {
                    parts.first()
                } else {
                    null
                },
            ),
        )

        // singer/song 返回的 duration 单位是秒；timelength 可能为空
        val dur = m["duration"] ?: m["timelength"]
        val seconds = if (dur is Number) {
            if (dur.toDouble() > 10000) dur.toInt() / 1000 else dur.toInt()
        } else {
            0
        }

        val qualities = ArrayList<Quality>()
        if (_truthy(m["sqhash"])) qualities.add(Quality.flac)
        if (_truthy(m["320hash"])) qualities.add(Quality.k320k)
        qualities.add(Quality.k128k)

        // 专辑封面在 trans_param.union_cover（带 {size} 占位符）
        val tp = m["trans_param"]
        val cover = (
            if (tp is Map<*, *>) {
                (tp["union_cover"] ?: "")
            } else {
                ""
            }
            ).toString().replace("{size}", "240")

        out.add(
            Song(
                id = "kugou:$hash",
                title = decodeHtmlEntities(title),
                artist = decodeHtmlEntities(artist),
                cover = cover,
                album = decodeHtmlEntities(
                    _firstNonEmpty(listOf(m["album_name"])),
                ),
                albumId = _firstNonEmpty(listOf(m["album_id"])),
                duration = seconds,
                platform = MusicPlatform.kugou,
                qualities = qualities,
                albumArtist = decodeHtmlEntities(artist),
            ),
        )
    }
    return out
}

/** 酷狗歌手专辑（singer/album） */
suspend fun getKugouArtistAlbums(singerId: String): List<Album> {
    try {
        val url =
            "http://mobilecdn.kugou.com/api/v3/singer/album" +
                "?singerid=$singerId&page=1&pagesize=100&version=9108&plat=0"
        val data = httpGetJson(url, headers = mapOf("User-Agent" to _kugouWebUa))
        val d = data["data"]
        val listRaw = if (d is Map<*, *>) d["info"] else null
        val list = if (listRaw is List<*>) listRaw else emptyList<Any?>()
        return _mapKugouAlbums(list)
    } catch (_: Throwable) {
        return emptyList()
    }
}

// ── 播放/下载直链（长青解析接口） ──

/**
 * 获取酷狗直链。
 *
 * [hash]：文件 hash（不带 kugou: 前缀）；统一转大写。
 * [quality]：128k / 320k / flac / flac24bit / atmos / master，未知值按 lossless。
 */
suspend fun fetchKugouMusicUrl(hash: String, quality: String): String {
    if (hash.isEmpty()) throw IllegalStateException("无法找到歌曲标识符")

    val level = _changqingLevelKg[quality] ?: "lossless"
    val resp: Map<String, Any?> = try {
        httpPostJson(
            _changqingResolveUrl,
            body = mapOf(
                "source" to "kg",
                "rid" to hash.uppercase(),
                "level" to level,
            ),
            headers = _changqingHeaders,
        )
    } catch (e: IOException) {
        // 接口对查不到的 rid 直接返回 503（空 body），转成可读提示
        val code = _reHttpCode.find(e.message ?: "")
            ?.groupValues?.getOrNull(1) ?: "网络错误"
        throw IllegalStateException("酷狗直链解析失败 (HTTP $code)")
    }

    val code = resp["code"]
    val data = resp["data"]
    val url = if (data is Map<*, *>) data["url"] else null
    if (_numEq(code, 0) && url is String && url.isNotEmpty()) {
        // 酷狗直链是 302 跳转，先跟随跳转拿到最终直链再返回
        return _followRedirects(url)
    }

    val msg = resp["message"] ?: resp["msg"]
    throw IllegalStateException(
        if (msg is String && msg.isNotEmpty()) {
            msg
        } else {
            "酷狗直链获取失败 (code: $code)"
        },
    )
}

/** 跟随 3xx 跳转拿到最终直链（HEAD，失败时原样返回原 url） */
private suspend fun _followRedirects(url: String): String {
    return try {
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).head().build()
            val resp = com.annieplayer.annie_music.kt.data.network.globalOkHttp
                .newCall(req).execute()
            resp.use {
                val real = it.request.url.toString()
                if (real.isEmpty()) url else real
            }
        }
    } catch (_: Throwable) {
        url
    }
}





