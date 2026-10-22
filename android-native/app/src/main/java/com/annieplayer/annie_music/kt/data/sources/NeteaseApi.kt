package com.annieplayer.annie_music.kt.data.sources

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
import com.annieplayer.annie_music.kt.data.network.anyToJsonElement
import com.annieplayer.annie_music.kt.core.util.lrcTimeOfMs
import com.annieplayer.annie_music.kt.core.util.mmssOfMs
import com.annieplayer.annie_music.kt.data.network.cacheService
import com.annieplayer.annie_music.kt.data.network.httpGetJson
import com.annieplayer.annie_music.kt.data.network.httpPostJson
import com.annieplayer.annie_music.kt.data.network.retryUntilSuccess

/**
 * 网易云音乐 API（1:1 移植 Flutter `lib/data/sources/` 下的 NETEASE 接口实现）。
 *
 * 保持与原版一致的函数名（含前导下划线）、参数与返回语义，供音乐仓库层与下载服务调用。
 * 缓存 key、TTL、请求头、接口端点与旧字段名回退均逐字节对齐。
 */

// ── 文件内返回结构（对齐 Dart records / 私有类） ──

/** 网易云封面统一 800x800（`?param=800y800`）；空串原样返回 */
private fun _cover800(url: Any?): String {
    val s = url?.toString() ?: ""
    if (s.isEmpty()) return ""
    return if (s.contains("?")) "$s&param=800y800" else "$s?param=800y800"
}

/** 歌词结果（对齐 Dart `({String lrc, String? tlyric})`，另带逐字） */
data class NeteaseLyric(
    val lrc: String,
    val tlyric: String?,
    val wordLyric: String? = null,
)

/** 网易云专辑内单曲（对齐 Dart `NeteaseAlbumSong`） */
data class NeteaseAlbumSong(
    val id: String,
    val name: String,
    val no: Int,
    val disc: String,
    val artist: String,
)

/** 网易云专辑详情（对齐 Dart `NeteaseAlbumDetail`） */
data class NeteaseAlbumDetail(
    val name: String,
    val artist: String,
    val publishTime: String,
    val company: String,
    val songs: List<NeteaseAlbumSong>,
)

// ── 缓存 TTL（与 RN 版常量照抄数值） ──

/** 搜索缓存 TTL（对齐 RN `SEARCH_CACHE_TTL` = 10 分钟） */
private const val _searchCacheTtl: Long = 10 * 60 * 1000L

/** 播放直链缓存 TTL（对齐 RN `PLAY_URL_CACHE_TTL` = 30 分钟） */
private const val _playUrlCacheTtl: Long = 30 * 60 * 1000L

/** 网易云网页 API 通用请求头 */
val neteaseApiHeaders: Map<String, String> = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    "Referer" to "https://music.163.com/",
    "Content-Type" to "application/x-www-form-urlencoded",
    "Cookie" to "os=pc; deviceId=pyncm!",
)

/** 第三方取链 API */
private const val _neteaseMusicApi = "https://c.wwwweb.top/music/url"

/** 第三方取链请求头 */
val neteaseMusicHeaders: Map<String, String> = mapOf(
    "User-Agent" to "lx-music-mobile/2.0.0",
    "Content-Type" to "application/json",
)

/** 音质顺序（与 models 中 Quality 对齐） */
private val _qualityOrder: List<Quality> = listOf(
    Quality.hires,
    Quality.flac,
    Quality.k320k,
    Quality.k128k,
)

// ── 通用辅助 ──

/** JS 真值语义：null / false / 0 / '' / NaN 为假，其余为真 */
private fun _truthy(value: Any?): Boolean = when (value) {
    null -> false
    is Boolean -> value
    is Number -> value.toDouble().let { it != 0.0 && !it.isNaN() }
    is String -> value.isNotEmpty()
    else -> true
}

/** 状态码数值比较（对齐 Dart `code != 200`，int/double 数值相等均可） */
private fun _isStatus(value: Any?, expected: Int): Boolean =
    value is Number && value.toDouble() == expected.toDouble()

/** 毫秒 -> 秒（对齐 Dart `dt ~/ 1000`）；非 num 返回 0 */
private fun _msToSec(value: Any?): Int =
    if (value is Number) (value.toLong() / 1000L).toInt() else 0

/**
 * 毫秒时间戳 -> UTC ISO 日期（yyyy-MM-dd）。
 *
 * 对齐 `new Date(ms).toISOString().slice(0,10)`：非数值按 Dart
 * `int.tryParse(...) ?? 0` 处理。
 */
private fun _isoDate(timestamp: Any?): String {
    val ms = when (timestamp) {
        is Number -> timestamp.toLong()
        else -> timestamp?.toString()?.toLongOrNull() ?: 0L
    }
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
    fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
    return fmt.format(java.util.Date(ms))
}

/** 从 `netease:123` 中剥离前缀得到纯 id */
private fun _rawId(songId: String): String =
    songId.replaceFirst(Regex("^netease:"), "")

/**
 * 从搜索原始条目中提取可用的网易云数字 id。
 *
 * 网易云歌曲 id 为 **64 位**（如 `3397732267`，已超过 `Int.MAX_VALUE`），
 * 且 cloudsearch 响应通常只给顶层 `id`、不带 `idStr`，因此按 `Long` 解析。
 * 取值顺序：顶层 `id` → `idStr`。两者都拿不到正整数时返回 null，
 * 调用方应丢弃该条目，避免生成 `netease:null` 这类不可用 id。
 */
private fun _extractNeteaseId(item: Map<String, Any?>): Long? {
    for (candidate in listOf(item["id"], item["idStr"])) {
        val parsed = when (candidate) {
            is Number -> candidate.toLong()
            is String -> candidate.trim().toLongOrNull()
            else -> null
        }
        if (parsed != null && parsed > 0L) return parsed
    }
    return null
}

/** 将任意值安全转换为 `Map<String, Any?>`；非 Map 返回 null */
private fun _asMap(value: Any?): Map<String, Any?>? {
    if (value !is Map<*, *>) return null
    val out = LinkedHashMap<String, Any?>(value.size)
    for ((k, v) in value) out[k.toString()] = v
    return out
}

/** 过滤出列表中的 Map 元素（对齐 Dart `whereType<Map>()` + `cast`） */
private fun _listOfMaps(raw: Any?): List<Map<String, Any?>> {
    if (raw !is List<*>) return emptyList()
    val out = ArrayList<Map<String, Any?>>(raw.size)
    for (e in raw) {
        val m = _asMap(e)
        if (m != null) out.add(m)
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

/** 组装 query 串（对齐 Dart `Uri(queryParameters: ...).query` 的编码） */
private fun _query(params: List<Pair<String, String>>): String =
    params.joinToString("&") { (k, v) ->
        "${_encodeQueryComponent(k)}=${_encodeQueryComponent(v)}"
    }

/**
 * query 参数编码（对齐 Dart `Uri.encodeQueryComponent`）。
 *
 * 与 [encodeComponent][_encodeComponent]（RFC 2396，保留 `-_.!~*'()`）**不同**：
 * 官方文档明确本函数仅保留 `A-Za-z0-9-._~`，并把空格编码为 `+`，其余字节按 UTF-8
 * 百分号编码（大写十六进制）。网易/QQ 等使用 `Uri(queryParameters:)` 的接口必须用它，
 * 否则空格 `%20`≠`+`、`!*'()` 不编码，原始报文与 Flutter 不一致。
 */
private fun _encodeQueryComponent(s: String): String {
    val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    val hexDigit = "0123456789ABCDEF"
    val sb = StringBuilder()
    for (b in s.toByteArray(Charsets.UTF_8)) {
        val code = b.toInt() and 0xFF
        val c = code.toChar()
        when {
            unreserved.indexOf(c) >= 0 -> sb.append(c)
            code == 0x20 -> sb.append('+')
            else -> {
                sb.append('%')
                sb.append(hexDigit[code ushr 4])
                sb.append(hexDigit[code and 0x0F])
            }
        }
    }
    return sb.toString()
}

// ── 音质 ──

/** 依据搜索/详情返回的歌曲 json 推断可用音质（对齐 RN parseNeteaseQualities） */
fun parseNeteaseQualities(song: Map<String, Any?>): List<Quality> {
    val result = ArrayList<Quality>()
    // 搜索结果用扁平字段 h/m/l/sq/hr；歌手单曲等旧接口用 *Music 对象，两种都要认
    if (_truthy(song["hr"]) || _truthy(song["hrMusic"])) result.add(Quality.hires)
    if (_truthy(song["sq"]) || _truthy(song["sqMusic"])) result.add(Quality.flac)
    if (_truthy(song["h"]) || _truthy(song["hMusic"])) result.add(Quality.k320k)
    if (_truthy(song["l"]) || _truthy(song["m"]) ||
        _truthy(song["lMusic"]) || _truthy(song["mMusic"])
    ) {
        result.add(Quality.k128k)
    }
    result.sortBy { _qualityOrder.indexOf(it) }
    return result
}

/** 各档位字节数（网易的 `hr`/`sq`/`h`/`l` 是对象，里面的 `size` 就是字节数） */
fun parseNeteaseQualitySizes(song: Map<String, Any?>): Map<Quality, Long> {
    val out = HashMap<Quality, Long>()

    fun put(q: Quality, vararg keys: String) {
        for (k in keys) {
            val v = song[k] ?: continue
            val size = when (v) {
                is Number -> v.toLong()
                is String -> v.trim().toLongOrNull()
                is Map<*, *> -> (v["size"] as? Number)?.toLong()
                    ?: (v["size"] as? String)?.trim()?.toLongOrNull()
                else -> null
            }
            if (size != null && size > 0L) {
                out[q] = size
                return
            }
        }
    }

    put(Quality.hires, "hr", "hrMusic")
    put(Quality.flac, "sq", "sqMusic")
    put(Quality.k320k, "h", "hMusic")
    put(Quality.k128k, "l", "m", "lMusic", "mMusic")
    return out
}

// ── 搜索 ──
/**
 * 搜索网易云歌曲，返回统一 [Song]（platform: netease）。
 *
 * [page] 从 1 开始；[pageSize] 每页条数（网易单页上限 100）。
 */
suspend fun searchNeteaseMusic(
    query: String,
    page: Int = 1,
    pageSize: Int = 100,
): List<Song> {
    val body = _query(
        listOf(
            "s" to query,
            "type" to "1",
            "limit" to pageSize.toString(),
            "offset" to ((page - 1) * pageSize).toString(),
        ),
    )

    val data = httpPostJson(
        "https://music.163.com/api/cloudsearch/pc",
        headers = neteaseApiHeaders,
        body = body,
    )

    val result = data["result"]
    val rawList = if (result is Map<*, *>) result["songs"] else null
    val list = if (rawList is List<*>) rawList else emptyList<Any?>()
    return _mapSearchSongs(list)
}

/** 将网易云搜索原始条目映射为 [Song]（缓存命中与网络路径共用） */
private fun _mapSearchSongs(raw: Any?): List<Song> =
    _listOfMaps(raw).mapNotNull { _mapSearchSong(it) }

private fun _mapSearchSong(item: Map<String, Any?>): Song? {
    // id 缺失或不可解析的条目直接丢弃（返回 null）：不生成 `netease:null`，
    // 否则这条记录会进列表但点播放/下载必然失败且没有任何提示。
    val numericId = _extractNeteaseId(item) ?: return null
    val arList = _listOfMaps(item["ar"])
    val artistNames = arList
        .map { decodeHtmlEntities((it["name"] ?: "").toString()) }
        .filter { it.isNotEmpty() }
    val artistIds = arList
        .map { (it["id"] ?: "").toString() }
        .filter { it.isNotEmpty() }
        .takeIf { it.size == artistNames.size } ?: emptyList()
    val artist = artistNames.joinToString(" / ")

    val al = _asMap(item["al"]) ?: emptyMap()

    val dt = item["dt"]
    val no = item["no"]
    val cd = item["cd"]
    val publishTime = item["publishTime"]
    val firstArName = arList.firstOrNull()?.get("name")

    return Song(
        id = "netease:$numericId",
        title = decodeHtmlEntities((item["name"] ?: "未知歌曲").toString()),
        artist = artist,
        cover = _cover800(al["picUrl"]),
        album = decodeHtmlEntities((al["name"] ?: "").toString()),
        albumId = if (_truthy(al["id"])) al["id"].toString() else null,
        duration = _msToSec(dt),
        platform = MusicPlatform.netease,
        playUrl = "",
        lyric = null,
        qualities = parseNeteaseQualities(item),
        qualitySizes = parseNeteaseQualitySizes(item),
        albumArtist = if (_truthy(firstArName)) {
            decodeHtmlEntities(firstArName.toString())
        } else {
            null
        },
        trackNumber = if (_truthy(no)) no.toString() else null,
        discNumber = if (_truthy(cd)) cd.toString() else null,
        releaseDate = if (_truthy(publishTime)) _isoDate(publishTime) else null,
        artistNames = artistNames,
        artistIds = artistIds,
    )
}

// ── 专辑搜索 / 专辑曲目 ──

/** 搜索网易云专辑（cloudsearch `type=10`） */
suspend fun searchNeteaseAlbum(
    query: String,
    page: Int = 1,
    pageSize: Int = 30,
): List<Album> {
    val body = _query(
        listOf(
            "s" to query,
            "type" to "10",
            "limit" to pageSize.toString(),
            "offset" to ((page - 1) * pageSize).toString(),
        ),
    )

    val data = httpPostJson(
        "https://music.163.com/api/cloudsearch/pc",
        headers = neteaseApiHeaders,
        body = body,
    )
    val result = data["result"]
    val rawList = if (result is Map<*, *>) result["albums"] else null
    val list = if (rawList is List<*>) rawList else emptyList<Any?>()
    return _mapNeteaseAlbums(list)
}

/** 网易云专辑原始条目 -> [Album] */
private fun _mapNeteaseAlbums(raw: Any?): List<Album> {
    val out = ArrayList<Album>()
    for (m in _listOfMaps(raw)) {
        var artistName = ""
        val artist = m["artist"]
        if (artist is Map<*, *>) artistName = (artist["name"] ?: "").toString()
        if (artistName.isEmpty()) {
            val artists = m["artists"]
            if (artists is List<*> && artists.isNotEmpty()) {
                val first = artists.first()
                if (first is Map<*, *>) artistName = (first["name"] ?: "").toString()
            }
        }

        val publishTime = m["publishTime"]
        out.add(
            Album(
                albumid = (m["id"] ?: m["idStr"] ?: "").toString(),
                name = decodeHtmlEntities((m["name"] ?: "").toString()),
                artist = decodeHtmlEntities(artistName),
                platform = MusicPlatform.netease,
                musiccnt = (m["size"] ?: "0").toString(),
                pub = if (publishTime is Number && publishTime.toDouble() > 0) {
                    _isoDate(publishTime)
                } else {
                    ""
                },
                pic = _cover800(m["picUrl"] ?: m["blurPicUrl"]),
                company = (m["company"] ?: "").toString(),
            ),
        )
    }
    return out
}

/**
 * 获取网易云专辑曲目（`/api/v1/album/{id}`）。
 *
 * 注意：旧的 `/api/album/{id}` 现在会返回 `code:-462「请登录手机号」`，解析不到 `album.songs`，
 * 于是专辑页「只有专辑信息、没有歌曲、点了也放不出来」。v1 接口免登录可用，且歌曲自带
 * `ar[].id`（歌手 id），所以歌手跳转也不必再补别的接口。
 */
suspend fun getNeteaseAlbumSongs(albumId: String): AlbumSongsPage {
    return try {
        val data = httpGetJson(
            "https://music.163.com/api/v1/album/$albumId",
            neteaseApiHeaders,
        )
        val album = data["album"]
        val albumMap = if (album is Map<*, *>) album else null
        // v1 把歌曲放在**顶层 `songs`**（旧结构在 `album.songs`）—— 两者都兼容
        val songsRaw = data["songs"] ?: albumMap?.get("songs")
        val list = if (songsRaw is List<*>) songsRaw else emptyList<Any?>()
        val albumArtistName = run {
            val a = albumMap?.get("artist")
            val name = if (a is Map<*, *>) (a["name"] ?: "").toString() else ""
            if (name.isNotEmpty()) {
                decodeHtmlEntities(name)
            } else {
                val arr = albumMap?.get("artists")
                val first = if (arr is List<*>) arr.firstOrNull() else null
                if (first is Map<*, *>) decodeHtmlEntities((first["name"] ?: "").toString()) else ""
            }
        }
        val albumGenre = (albumMap?.get("tags") as? List<*>)
            ?.mapNotNull { it?.toString()?.takeIf { s -> s.isNotBlank() } }
            ?.joinToString(" / ")
            .orEmpty()
        val out = ArrayList<AlbumSong>()
        for (m in _listOfMaps(list)) {
            // 注意：`/api/album/{id}` 是旧接口 —— 歌手字段是 `artists`（不是 `ar`）、
            // 时长是 `duration`（不是 `dt`）。这里两个都兼容。
            val arRaw = m["ar"] ?: m["artists"]
            val arList = _listOfMaps(arRaw)
            val artistNames = arList
                .map { decodeHtmlEntities((it["name"] ?: "").toString()) }
                .filter { it.isNotEmpty() }
            val artistIds = arList
                .map { (it["id"] ?: "").toString() }
                .filter { it.isNotEmpty() }
                .takeIf { it.size == artistNames.size } ?: emptyList()
            val dt = m["dt"] ?: m["duration"]
            val no = m["no"]
            val cd = m["cd"] ?: m["disc"]
            out.add(
                AlbumSong(
                    id = "netease:${m["id"]}",
                    name = decodeHtmlEntities((m["name"] ?: "").toString()),
                    artistName = if (artistNames.isEmpty()) "" else artistNames.joinToString(" / "),
                    duration = _msToSec(dt),
                    track = if (no is Number) no.toInt() else null,
                    discNumber = if (cd is Number) cd.toInt() else cd?.toString()?.toIntOrNull(),
                    albumArtist = albumArtistName.ifEmpty { null },
                    // 专辑接口是按每首歌给音质标记的（hr/sq/h/l），必须带上 ——
                    // 不带的话详情页下载候选永远回退到 flac/320/128，Hi-Res 就丢了。
                    qualities = parseNeteaseQualities(m),
                    qualitySizes = parseNeteaseQualitySizes(m),
                    artistNames = artistNames,
                    artistIds = artistIds,
                    composer = (m["composer"] as? String)?.takeIf { it.isNotBlank() },
                    genre = albumGenre.ifEmpty { null },
                ),
            )
        }
        // 发行时间在 `album.publishTime`（毫秒时间戳）
        AlbumSongsPage(out, _isoDate(albumMap?.get("publishTime")))
    } catch (_: Throwable) {
        AlbumSongsPage(emptyList())
    }
}

// ── 歌手搜索 / 歌手单曲 / 歌手专辑 ──

/** 搜索网易云歌手（cloudsearch `type=100`） */
suspend fun searchNeteaseArtist(query: String): List<Artist> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()

    val body = _query(
        listOf("s" to q, "type" to "100", "limit" to "30", "offset" to "0"),
    )
    val data = httpPostJson(
        "https://music.163.com/api/cloudsearch/pc",
        headers = neteaseApiHeaders,
        body = body,
    )
    val result = data["result"]
    val rawList = if (result is Map<*, *>) result["artists"] else null
    val list = if (rawList is List<*>) rawList else emptyList<Any?>()
    return _mapNeteaseArtists(list)
}

/** 网易云歌手原始条目 -> [Artist] */
private fun _mapNeteaseArtists(raw: Any?): List<Artist> =
    _listOfMaps(raw).map { m ->
        val size = m["musicSize"]
        Artist(
            id = (m["id"] ?: "").toString(),
            name = decodeHtmlEntities((m["name"] ?: "").toString()),
            picture = (m["picUrl"] ?: "").toString(),
            musicNum = if (size is Number) size.toInt() else 0,
        )
    }

/** 网易云歌手头像（`/api/v1/artist/{id}` 的 `artist.picUrl`） */suspend fun fetchNeteaseArtistAvatar(artistId: String): String {
    return try {
        val data = httpGetJson(
            "https://music.163.com/api/v1/artist/$artistId",
            neteaseApiHeaders,
        )
        val a = data["artist"]
        if (a is Map<*, *>) (a["picUrl"] ?: "").toString() else ""
    } catch (_: Throwable) {
        ""
    }
}

/** 网易云歌手简介：同一接口的 `artist.briefDesc`（免登录） */
suspend fun fetchNeteaseArtistBio(artistId: String): String {
    return try {
        val data = httpGetJson(
            "https://music.163.com/api/v1/artist/$artistId",
            neteaseApiHeaders,
        )
        val a = data["artist"]
        if (a is Map<*, *>) (a["briefDesc"] ?: "").toString().trim() else ""
    } catch (_: Throwable) {
        ""
    }
}

/**
 * 获取网易云歌手单曲（`/api/v1/artist/songs`，按 `limit`/`offset` 真分页）。
 *
 * 旧实现走 `/api/v1/artist/{id}` 的 `hotSongs`：只有热门约 50 首、且不带总数，
 * 导致详情页「歌曲 N」和翻页全错。该接口同时返回真实总数 `total`。
 */
suspend fun getNeteaseArtistSongs(artistId: String, page: Int = 1): ArtistSongsPage {
    return try {
        val offset = (page - 1) * ArtistSongsPageSize
        val data = httpGetJson(
            "https://music.163.com/api/v1/artist/songs?id=$artistId" +
                "&limit=$ArtistSongsPageSize&offset=$offset",
            neteaseApiHeaders,
        )
        val songsRaw = data["songs"]
        val list = if (songsRaw is List<*>) songsRaw else emptyList<Any?>()
        val total = (data["total"] as? Number)?.toInt() ?: 0
        ArtistSongsPage(_listOfMaps(list).map { _mapNeteaseArtistSong(it) }, total)
    } catch (_: Throwable) {
        ArtistSongsPage(emptyList(), 0)
    }
}

/** 歌手单曲条目 -> [Song]（hotSongs 用旧字段名：artists / duration / al） */
private fun _mapNeteaseArtistSong(m: Map<String, Any?>): Song {
    val arRaw = m["ar"] ?: m["artists"]
    val arList = _listOfMaps(arRaw)
    val artistNames = arList
        .map { decodeHtmlEntities((it["name"] ?: "").toString()) }
        .filter { it.isNotEmpty() }
    val artistIds = arList
        .map { (it["id"] ?: "").toString() }
        .filter { it.isNotEmpty() }
        .takeIf { it.size == artistNames.size } ?: emptyList()
    val artistName = if (artistNames.isEmpty()) "" else artistNames.joinToString(" / ")
    val alMap = _asMap(m["al"] ?: m["album"]) ?: emptyMap()
    val dt = m["dt"] ?: m["duration"]
    val qualities = parseNeteaseQualities(m).toMutableList()
    if (qualities.isEmpty()) qualities.add(Quality.k128k)
    return Song(
        id = "netease:${m["id"]}",
        title = decodeHtmlEntities((m["name"] ?: "").toString()),
        artist = decodeHtmlEntities(artistName),
        cover = _cover800(alMap["picUrl"]),
        album = decodeHtmlEntities((alMap["name"] ?: "").toString()),
        albumId = (alMap["id"] ?: "").toString(),
        duration = _msToSec(dt),
        platform = MusicPlatform.netease,
        qualities = qualities,
        qualitySizes = parseNeteaseQualitySizes(m),
        albumArtist = decodeHtmlEntities(artistName),
        artistNames = artistNames,
        artistIds = artistIds,
    )
}

/** 网易云歌手专辑（`/api/artist/albums/{id}`） */
suspend fun getNeteaseArtistAlbums(artistId: String): List<Album> {
    val out = mutableListOf<Album>()
    return try {
        var offset = 0
        while (offset < 3000) {
            val data = httpGetJson(
                "https://music.163.com/api/artist/albums/$artistId?limit=100&offset=$offset",
                neteaseApiHeaders,
            )
            val raw = data["hotAlbums"]
            val list = if (raw is List<*>) raw else emptyList<Any?>()
            if (list.isEmpty()) break
            out += _mapNeteaseAlbums(list)
            if (list.size < 100) break
            offset += 100
        }
        out
    } catch (_: Throwable) {
        out
    }
}

// ── 播放链接 ──

private suspend fun _fetchNeteaseMusicUrl(songId: String, quality: String): String {
    // 网易云 id 为 64 位（可超过 Int.MAX，如 3397732267）：必须用 toLongOrNull。
    // 旧实现用 toIntOrNull()，遇到大 id 解析失败后静默回落 0，把 musicId=0 发出去，
    // 第三方接口必然返回「url 为空」且用户看不到任何原因。这里直接抛出明确文案。
    val rawId = _rawId(songId).trim().toLongOrNull()
    if (rawId == null || rawId <= 0L) {
        throw IllegalStateException("该歌曲在网易云无可用音源")
    }
    val payload = mapOf<String, Any?>(
        "source" to "wy",
        "musicId" to rawId,
        "quality" to quality,
    )

    val data = httpPostJson(
        _neteaseMusicApi,
        headers = neteaseMusicHeaders,
        body = anyToJsonElement(payload).toString(),
    )

    if (!_isStatus(data["code"], 200) || !_truthy(data["url"])) {
        throw IllegalStateException("网易云获取链接失败: ${data["msg"] ?: "unknown"}")
    }
    return data["url"].toString()
}

/**
 * 获取网易云播放链接。[quality] 为 null 时用 320k（旧行为）；
 * **缓存键带档位**——否则换档会命中旧档的直链。
 */
suspend fun fetchNeteasePlayUrl(songId: String, quality: Quality? = null): String {
    val q = quality ?: Quality.k320k
    val cacheKey = "netease:playurl:${_rawId(songId)}:${q.value}"
    val cached = cacheService.get<String>(cacheKey)
    if (cached != null && cached.isNotEmpty()) return cached

    val url = retryUntilSuccess(maxRetries = 2, delayMs = 500L) { _fetchNeteaseMusicUrl(songId, q.value) }
    cacheService.set(cacheKey, url, _playUrlCacheTtl)
    return url
}

/** 获取网易云下载链接（按指定音质） */
suspend fun fetchNeteaseDownloadUrl(songId: String, quality: Quality): String =
    retryUntilSuccess(maxRetries = 2, delayMs = 500L) { _fetchNeteaseMusicUrl(songId, quality.value) }

// ── 歌词 ──

/** 网易 `yrc` 行：`[行起始ms,行时长ms](词起始ms,词时长ms,0)词…`（词时间是绝对毫秒） */
private val _reYrcLine by lazy { Regex("^\\[(\\d+),\\d+\\]((?:\\(\\d+,\\d+,\\d+\\)[^(]*)+)") }
private val _reYrcWord by lazy { Regex("\\((\\d+),(\\d+),(\\d+)\\)([^(]*)") }

/** 网易 `yrc` -> 统一逐字格式 `[mm:ss.mmm]<起始ms,时长ms>词…` */
private fun _neteaseYrcToWordLyric(yrc: String): String? {
    if (yrc.isEmpty()) return null
    val out = StringBuilder()
    for (line in yrc.split('\n')) {
        val m = _reYrcLine.find(line.trim()) ?: continue
        val lineStart = m.groupValues[1].toIntOrNull() ?: continue
        val sb = StringBuilder()
        for (w in _reYrcWord.findAll(m.groupValues[2])) {
            val text = w.groupValues[4]
            if (text.isEmpty()) continue
            sb.append('<').append(mmssOfMs(w.groupValues[1].toIntOrNull() ?: 0))
                .append('>').append(text)
        }
        if (sb.isEmpty()) continue
        out.append(lrcTimeOfMs(lineStart)).append(sb).append('\n')
    }
    return if (out.isEmpty()) null else out.toString().trimEnd('\n')
}

/** 获取网易云歌词，返回原始 lrc、翻译歌词与逐字歌词 */
suspend fun fetchNeteaseLyric(songId: String): NeteaseLyric {
    val rawId = _rawId(songId)
    val body = _query(
        listOf("id" to rawId, "lv" to "-1", "kv" to "0", "tv" to "-1", "yv" to "-1"),
    )

    val data = httpPostJson(
        "https://interface3.music.163.com/api/song/lyric",
        headers = neteaseApiHeaders,
        body = body,
    )

    if (!_isStatus(data["code"], 200)) throw IllegalStateException("netease lyric api error")

    val lrcRaw = data["lrc"]
    val tlRaw = data["tlyric"]
    val yrcRaw = data["yrc"]
    val lrc = (if (lrcRaw is Map<*, *>) lrcRaw["lyric"] else null)?.toString() ?: ""
    val tl = (if (tlRaw is Map<*, *>) tlRaw["lyric"] else null)?.toString() ?: ""
    val yrc = (if (yrcRaw is Map<*, *>) yrcRaw["lyric"] else null)?.toString() ?: ""

    return NeteaseLyric(
        lrc = if (lrc.isEmpty()) "[00:00.00]暂无歌词" else lrc,
        tlyric = if (tl.isEmpty()) null else tl,
        wordLyric = _neteaseYrcToWordLyric(yrc),
    )
}

// ── 专辑详情 ──

/** 获取网易云专辑详情；失败返回 null（对齐 RN 版） */
suspend fun getNeteaseAlbumDetail(albumId: String): NeteaseAlbumDetail? {
    return try {
        // 与 getNeteaseAlbumSongs 同因：旧 `/api/album/{id}` 已需登录，改用免登录的 v1 接口
        val data = httpGetJson(
            "https://music.163.com/api/v1/album/$albumId",
            neteaseApiHeaders,
        )

        val albumRaw = data["album"]
        if (albumRaw !is Map<*, *>) return null
        val album = _asMap(albumRaw) ?: return null

        val artistRaw = album["artist"]
        val artistsRaw = album["artists"]
        val artistName = (if (artistRaw is Map<*, *>) artistRaw["name"] else null)
            ?: (
                if (artistsRaw is List<*> && artistsRaw.isNotEmpty() && artistsRaw.first() is Map<*, *>) {
                    (artistsRaw.first() as Map<*, *>)["name"]
                } else {
                    null
                }
                )
            ?: ""

        // v1 把歌曲放在顶层 `songs`
        val songsRaw = data["songs"] ?: album["songs"]
        val songsList = if (songsRaw is List<*>) songsRaw else emptyList<Any?>()
        val songs = _listOfMaps(songsList).map { sm ->
            // v1 歌曲的歌手字段是 `ar`（旧接口是 `artists`）
            val sArtistList = _listOfMaps(sm["ar"] ?: sm["artists"])
            val no = sm["no"]
            NeteaseAlbumSong(
                id = "netease:${sm["id"]}",
                name = decodeHtmlEntities((sm["name"] ?: "").toString()),
                no = if (no is Number) no.toInt() else 0,
                disc = (sm["disc"] ?: sm["cd"] ?: "1").toString(),
                artist = decodeHtmlEntities(
                    sArtistList.map { (it["name"] ?: "").toString() }.joinToString(" / "),
                ),
            )
        }

        NeteaseAlbumDetail(
            name = decodeHtmlEntities((album["name"] ?: "").toString()),
            artist = decodeHtmlEntities(artistName.toString()),
            publishTime = if (_truthy(album["publishTime"])) {
                _isoDate(album["publishTime"])
            } else {
                ""
            },
            company = (album["company"] ?: "").toString(),
            songs = songs,
        )
    } catch (_: Throwable) {
        null
    }
}

/** 网易云单曲补充信息（走专辑详情接口：曲目号/CD号/发行时间/专辑歌手）；失败返回全 null */
data class NeteaseEnrich(
    val trackNumber: String?,
    val discNumber: String?,
    val releaseDate: String?,
    val albumArtist: String?,
)

suspend fun enrichNeteaseSong(songId: String, albumId: String): NeteaseEnrich {
    val empty = NeteaseEnrich(null, null, null, null)
    if (albumId.isEmpty()) return empty
    return try {
        val album = getNeteaseAlbumDetail(albumId) ?: return empty
        val matched = album.songs.firstOrNull { it.id == songId }
        NeteaseEnrich(
            trackNumber = matched?.no?.takeIf { it > 0 }?.toString(),
            discNumber = matched?.disc?.takeIf { it.isNotEmpty() && it != "0" },
            releaseDate = album.publishTime.ifEmpty { null },
            albumArtist = album.artist.ifEmpty { null },
        )
    } catch (_: Throwable) {
        empty
    }
}

// ── 歌单 ──

private suspend fun _fetchNeteaseSongDetails(chunk: List<Map<String, Any?>>): List<Song> {
    return try {
        val data = httpPostJson(
            "https://music.163.com/api/v3/song/detail",
            headers = neteaseApiHeaders,
            body = _query(listOf("c" to anyToJsonElement(chunk).toString())),
        )

        val songsRaw = data["songs"]
        val list = if (songsRaw is List<*>) songsRaw else emptyList<Any?>()

        _listOfMaps(list).map { sm ->
            val al = _asMap(sm["al"]) ?: emptyMap()
            val cover = "${al["picUrl"] ?: ""}?param=800y800"

            val arList = _listOfMaps(sm["ar"])

            val qualities = ArrayList<Quality>()
            if (_truthy(sm["hr"])) qualities.add(Quality.hires)
            if (_truthy(sm["sq"])) qualities.add(Quality.flac)
            if (_truthy(sm["h"])) qualities.add(Quality.k320k)
            if (_truthy(sm["l"]) || _truthy(sm["m"])) qualities.add(Quality.k128k)

            val dt = sm["dt"]

            Song(
                id = "netease:${sm["id"]}",
                title = decodeHtmlEntities((sm["name"] ?: "").toString()),
                artist = decodeHtmlEntities(
                    arList.map { (it["name"] ?: "").toString() }.joinToString(" / "),
                ),
                cover = cover,
                album = decodeHtmlEntities((al["name"] ?: "").toString()),
                albumId = if (_truthy(al["id"])) al["id"].toString() else null,
                duration = _msToSec(dt),
                platform = MusicPlatform.netease,
                playUrl = "",
                lyric = null,
                tlyric = null,
                qualities = if (qualities.isNotEmpty()) qualities else listOf(Quality.k128k),
                qualitySizes = parseNeteaseQualitySizes(sm),
            )
        }
    } catch (_: Throwable) {
        emptyList()
    }
}

/** 获取网易云歌单（含 200 条分块批量拉取逻辑）；失败返回 null */
suspend fun getNeteasePlaylist(playlistId: String): Playlist? {
    return try {
        val data = httpPostJson(
            "https://music.163.com/api/v6/playlist/detail",
            headers = neteaseApiHeaders,
            body = _query(listOf("id" to playlistId, "n" to "100000")),
        )

        if (!_isStatus(data["code"], 200) || data["playlist"] !is Map<*, *>) return null
        val pl = _asMap(data["playlist"]) ?: return null

        val trackIds = _listOfMaps(pl["trackIds"])

        val chunkSize = 200
        val chunks = ArrayList<List<Map<String, Any?>>>()
        var i = 0
        while (i < trackIds.size) {
            val end = if (i + chunkSize < trackIds.size) i + chunkSize else trackIds.size
            chunks.add(
                trackIds.subList(i, end).map { t ->
                    val id = t["id"]
                    val v = t["v"]
                    mapOf<String, Any?>(
                        "id" to if (id is Number) id.toInt() else 0,
                        "v" to if (v is Number) v.toInt() else 0,
                    )
                },
            )
            i += chunkSize
        }

        val results = chunks.map { _fetchNeteaseSongDetails(it) }
        val songs = results.flatten()

        val creatorRaw = pl["creator"]
        val cover = (pl["coverImgUrl"] ?: "").toString()

        Playlist(
            id = playlistId,
            name = (pl["name"] ?: "").toString(),
            creator = if (creatorRaw is Map<*, *>) {
                (creatorRaw["nickname"] ?: "").toString()
            } else {
                ""
            },
            cover = "$cover?param=800y800",
            platform = MusicPlatform.netease,
            songs = songs,
            songCount = songs.size,
        )
    } catch (_: Throwable) {
        null
    }
}
