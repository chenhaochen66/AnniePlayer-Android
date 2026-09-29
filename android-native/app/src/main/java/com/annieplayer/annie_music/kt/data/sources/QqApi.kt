package com.annieplayer.annie_music.kt.data.sources

import android.util.Base64
import com.annieplayer.annie_music.kt.data.model.Album
import com.annieplayer.annie_music.kt.data.model.AlbumSong
import com.annieplayer.annie_music.kt.data.model.Artist
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Playlist
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.network.cacheService
import com.annieplayer.annie_music.kt.data.network.httpGetText
import com.annieplayer.annie_music.kt.data.network.httpPostJson
import com.annieplayer.annie_music.kt.data.network.json
import com.annieplayer.annie_music.kt.data.network.jsonElementToAny
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * QQ 音乐 API（1:1 移植 Flutter `lib/data/sources/qq_api.dart`）。
 *
 * 保持与原版一致的函数名、参数与返回语义，供音乐仓库层与下载服务调用。
 * 关键行为保留：搜索 `n` 上限 60、专辑 `t=8` / 歌手 `t=9`、歌手单曲走
 * musicu `get_singer_detail_info`、多歌手 `singer[]` 映射、歌手专辑兜底。
 */

// ── 通用请求头 ──

/** QQ 音乐网页 API 通用请求头 */
val qqHeaders: Map<String, String> = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    "Referer" to "https://y.qq.com/",
)

// ── 文件内返回结构（对齐 Dart records / 私有类） ──

/** 歌单单页结果（对齐 Dart 私有类 `_QQPage`） */
data class QqPage(
    val songs: List<Song>,
    val total: Int,
    val name: String,
    val creator: String,
    val cover: String,
)

/** 歌词结果（对齐 Dart `({String lrc, String? tlyric})`） */
data class QqLyric(
    val lrc: String,
    val tlyric: String?,
)

/** 单曲补充信息结果（对齐 Dart `({String? trackNumber, String? discNumber, String? releaseDate})`） */
data class QqEnrich(
    val trackNumber: String?,
    val discNumber: String?,
    val releaseDate: String?,
)

// ── 常量（与 RN / Dart 版完全一致） ──

/** 搜索缓存 TTL（对齐 kuwo / netease 的 10 分钟） */
private const val _searchCacheTtl: Long = 10 * 60 * 1000L

/** 长青解析接口：POST JSON `{source, rid, level}` → `{code:0, data:{url}}` */
private const val _changqingResolveUrl =
    "https://musicserver.haitangw.cc/v1/music/resolve-url"

/** 长青接口通用请求头 */
private val _changqingHeaders: Map<String, String> = mapOf(
    "Content-Type" to "application/json",
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36",
    "Accept" to "application/json",
)

/** QQ 音质 → 长青 level（对齐音源脚本 CHANGQING_QUALITY_MAP.tx） */
private val _changqingLevelTx: Map<String, String> = mapOf(
    "128k" to "standard",
    "320k" to "exhigh",
    "flac" to "lossless",
    "flac24bit" to "lossless",
    "hires" to "lossless",
)

// ── 通用辅助（对齐 JS 真值 / 取值语义） ──

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

/** 对齐 Dart `v ?? def`（null 用默认值，空串保持空串） */
private fun _or(v: Any?, def: String): String = v?.toString() ?: def

/** 对齐 Dart `x == 0`（仅数字 0 为真） */
private fun _isZero(v: Any?): Boolean = when (v) {
    is Number -> v.toDouble() == 0.0
    else -> false
}

/** 对齐 Dart `x != 0`（null / 非数字 / 非零均为真） */
private fun _neqZero(v: Any?): Boolean = !_isZero(v)

/** 将 JSON 对象安全转换为 `Map<String, Any?>` */
private fun _normMap(m: Map<*, *>): Map<String, Any?> =
    m.entries.associate { (k, v) -> k.toString() to v }

/** 将任意值安全转换为 `Map<String, Any?>?` */
private fun _toMap(v: Any?): Map<String, Any?>? =
    if (v is Map<*, *>) _normMap(v) else null

/** 从 `qq:songmid` 中剥离前缀得到纯 mid */
private fun _rawId(songId: String): String = songId.removePrefix("qq:")

/**
 * 剥离响应开头的 UTF-8 BOM。
 *
 * RN 版用正则剥离。Dart 侧视解码路径不同，既可能是标准 U+FEFF（utf8 解码），
 * 也可能是 latin1 误解码出的三个字符，两者都处理以保证结果一致。
 */
private fun _stripBom(text: String): String = when {
    text.startsWith("\uFEFF") -> text.substring(1)
    text.startsWith("\u00EF\u00BB\u00BF") -> text.substring(3)
    else -> text
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

// ── 歌单 ──

/**
 * 将 QQ 歌曲 json 映射为统一 [Song]（sizeflac→flac, size320→320k ...）。
 *
 * 多歌手来自 `singer[].name` + `singer[].mid`，同时填充 [Song.artistNames]
 * 与 [Song.artistIds]（不取首歌手）。
 */
fun mapQQSong(s: Map<String, Any?>): Song {
    val singerRaw = s["singer"]
    val singerList = if (singerRaw is List<*>) {
        singerRaw.filterIsInstance<Map<*, *>>()
    } else {
        emptyList()
    }
    val artistNames = singerList
        .map { decodeHtmlEntities(_s(it["name"])) }
        .filter { it.isNotEmpty() }
    val artistIds = singerList
        .map { _s(it["mid"]) }
        .filter { it.isNotEmpty() }
    val singer = artistNames.joinToString(" / ")

    val qual = mutableListOf<Quality>()
    if (_truthy(s["sizeflac"])) qual.add(Quality.flac)
    if (_truthy(s["size320"])) qual.add(Quality.k320k)
    if (_truthy(s["size128"])) qual.add(Quality.k128k)
    if (qual.isEmpty()) qual.add(Quality.k128k)

    val albumMid = _s(s["albummid"])
    val coverUrl = if (albumMid.isNotEmpty()) {
        "https://y.gtimg.cn/music/photo_new/T002R800x800M000$albumMid.jpg"
    } else {
        ""
    }
    val artist = decodeHtmlEntities(singer)

    val songmid = _s(s["songmid"])
    val interval = s["interval"]

    return Song(
        id = "qq:$songmid",
        title = decodeHtmlEntities(_or(s["songname"], "未知歌曲")),
        artist = artist,
        cover = coverUrl,
        album = decodeHtmlEntities(_s(s["albumname"])),
        albumId = if (albumMid.isNotEmpty()) albumMid else null,
        duration = if (interval is Number) interval.toInt() else 0,
        platform = MusicPlatform.qq,
        playUrl = "",
        lyric = null,
        tlyric = null,
        qualities = qual,
        albumArtist = artist,
        artistNames = artistNames,
        artistIds = artistIds,
    )
}

private suspend fun _fetchQQPage(disstid: String, begin: Int, pageSize: Int): QqPage? {
    val url =
        "https://c.y.qq.com/v8/fcg-bin/fcg_v8_playlist_cp.fcg?id=$disstid&format=json" +
            "&song_begin=$begin&song_num=$pageSize&loginUin=0&hostUin=0" +
            "&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0"
    try {
        val text = httpGetText(url, qqHeaders)
        val decoded = jsonElementToAny(json.parseToJsonElement(_stripBom(text)))
        if (decoded !is Map<*, *>) return null
        if (_neqZero(decoded["code"])) return null

        val dataField = decoded["data"]
        val cdRaw = if (dataField is Map<*, *>) dataField["cdlist"] else null
        val cd = if (cdRaw is List<*> && cdRaw.isNotEmpty() && cdRaw[0] is Map<*, *>) {
            _toMap(cdRaw[0])
        } else {
            null
        }
        if (cd == null) return null

        val songlistRaw = cd["songlist"]
        val songlist = if (songlistRaw is List<*>) songlistRaw else emptyList<Any?>()
        val songs = songlist
            .filterIsInstance<Map<*, *>>()
            .map { mapQQSong(_normMap(it)) }

        val cur = cd["cur_song_num"]

        return QqPage(
            songs = songs,
            total = if (cur is Number) cur.toInt() else 0,
            name = _s(cd["dissname"]),
            creator = _s(cd["nickname"]),
            cover = _s(cd["logo"]),
        )
    } catch (_: Throwable) {
        return null
    }
}

/** 获取 QQ 音乐歌单（分页 200 条累加）；失败返回 null */
suspend fun getQQPlaylist(playlistId: String): Playlist? {
    val pageSize = 200
    val first = _fetchQQPage(playlistId, 0, pageSize)
    if (first == null || first.songs.isEmpty()) return null

    val total = first.total
    val pages = if (total <= 0) 0 else (total + pageSize - 1) / pageSize

    var allSongs = first.songs
    if (pages > 1) {
        for (i in 0 until pages - 1) {
            val page = _fetchQQPage(playlistId, (i + 1) * pageSize, pageSize)
            if (page != null) allSongs = allSongs + page.songs
        }
    }

    return Playlist(
        id = playlistId,
        name = first.name,
        creator = first.creator,
        cover = first.cover,
        platform = MusicPlatform.qq,
        songs = allSongs,
        songCount = allSongs.size,
    )
}

// ── 歌词（简化版：通过第三方 API 获取） ──

/** QQ 歌词 base64 解码：等价于 RN 版 `decodeURIComponent(escape(atob(x)))` */
private fun _decodeQQBase64(b64: String): String {
    val bytes = Base64.decode(b64, Base64.DEFAULT)
    // Dart: utf8.decode(latin1.decode(bytes).codeUnits) —— 等价于把原始字节按 UTF-8 解码；
    // 非法序列抛异常（由调用方 catch 回退占位歌词）。
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return decoder.decode(ByteBuffer.wrap(bytes)).toString()
}

/** 获取 QQ 歌词，返回原始 lrc 与翻译歌词；失败返回占位歌词 */
suspend fun fetchQQLyric(songId: String): QqLyric {
    val fallback = QqLyric("[00:00.00]暂无歌词", null)
    val songMid = _rawId(songId)
    try {
        val payload: Map<String, Any?> = mapOf(
            "request" to mapOf(
                "module" to "music.musichallSong.PlayLyricInfo",
                "method" to "GetPlayLyricInfo",
                "param" to mapOf("songMID" to songMid, "crypt" to 0, "qrc" to 0, "trans" to 1),
            ),
        )
        val data = httpPostJson(
            "https://u.y.qq.com/cgi-bin/musicu.fcg",
            body = payload,
            headers = mapOf(
                "User-Agent" to "okhttp/3.14.9",
                "Content-Type" to "application/json; charset=utf-8",
                "Cookie" to "tmeLoginType=-1;",
            ),
        )

        val reqRaw = data["request"]
        val dRaw = if (reqRaw is Map<*, *>) reqRaw["data"] else null
        val d = _toMap(dRaw)
        if (d == null || !_truthy(d["lyric"])) return fallback

        try {
            val lrcB64 = if (d["lyric"] is String) d["lyric"] as String else ""
            val lrc = _decodeQQBase64(lrcB64)
            var tlyric: String? = null
            if (_truthy(d["trans"])) {
                val transB64 = if (d["trans"] is String) d["trans"] as String else ""
                tlyric = _decodeQQBase64(transB64)
            }
            return QqLyric(lrc = lrc, tlyric = tlyric)
        } catch (_: Throwable) {
            return fallback
        }
    } catch (_: Throwable) {
        return fallback
    }
}

// ── 单曲详情（补音轨号/碟号/发行日期） ──

/** 补充 QQ 单曲 metadata；失败返回全 null */
suspend fun enrichQQSong(songId: String): QqEnrich {
    val empty = QqEnrich(null, null, null)
    val songmid = _rawId(songId)
    try {
        val url =
            "https://c.y.qq.com/v8/fcg-bin/fcg_play_single_song.fcg?songmid=$songmid" +
                "&tpl=yqq_song_detail&format=json&loginUin=0&hostUin=0" +
                "&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0"
        val text = httpGetText(url, qqHeaders)
        val decoded = jsonElementToAny(json.parseToJsonElement(_stripBom(text)))
        if (decoded !is Map<*, *>) return empty

        val arr = decoded["data"]
        if (_neqZero(decoded["code"]) || arr !is List<*> || arr.isEmpty() || arr[0] !is Map<*, *>) {
            return empty
        }

        val s = _toMap(arr[0])
        val idxAlbum = s?.get("index_album")
        val idxCd = s?.get("index_cd")
        val timePublic = s?.get("time_public")

        return QqEnrich(
            trackNumber = if (_truthy(idxAlbum)) idxAlbum.toString() else null,
            discNumber = if (_truthy(idxCd)) idxCd.toString() else null,
            releaseDate = if (_truthy(timePublic)) timePublic.toString() else null,
        )
    } catch (_: Throwable) {
        return empty
    }
}

// ── 单曲搜索 ──

/**
 * 搜索 QQ 音乐单曲。
 *
 * 网页搜索接口 `client_search_cp` 的条目字段与歌单接口完全一致
 * （songmid / songname / singer[0].name / albummid / albumname / interval /
 * size128·320·flac），因此直接复用 [mapQQSong]。
 *
 * [page] 从 1 开始；[pageSize] 每页条数。接口单次最多 60 条（`n > 60` 返回空），
 * 内部按 60 条/次 多次拉取再切片（每页 ≤60 时只需 1 次请求）。
 */
suspend fun searchQQMusic(query: String, page: Int = 1, pageSize: Int = 100): List<Song> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()

    val cacheKey = "search:qq:v2:$q:$page:$pageSize"
    val cached = cacheService.get<List<Any?>>(cacheKey)
    if (cached != null) return _mapQQSearchSongs(cached)

    val perRequest = 60
    val start = (page - 1) * pageSize
    val skip = start % perRequest
    val need = skip + pageSize

    val collected = mutableListOf<Any?>()
    var qqPage = start / perRequest + 1
    while (collected.size < need) {
        val batch = _fetchQQSearchRawPage(q, qqPage, perRequest)
        if (batch.isEmpty()) break
        collected.addAll(batch)
        qqPage++
        if (batch.size < perRequest) break
    }

    val list = collected.drop(skip).take(pageSize)
    // 缓存原始条目列表（JSON 可序列化），命中时按同一映射重建 Song
    cacheService.set(cacheKey, list, _searchCacheTtl)
    return _mapQQSearchSongs(list)
}

/** 拉取 QQ 搜索单页原始条目（[pageSize] 不得超过 60） */
private suspend fun _fetchQQSearchRawPage(
    query: String,
    page: Int,
    pageSize: Int,
): List<Any?> {
    val url =
        "https://c.y.qq.com/soso/fcgi-bin/client_search_cp" +
            "?p=$page&n=$pageSize&w=${_encodeComponent(query)}&format=json"
    try {
        val text = httpGetText(url, qqHeaders)
        val decoded = jsonElementToAny(json.parseToJsonElement(_stripBom(text)))
        if (decoded !is Map<*, *>) return emptyList()
        val data = decoded["data"]
        val song = if (data is Map<*, *>) data["song"] else null
        val listRaw = if (song is Map<*, *>) song["list"] else null
        return if (listRaw is List<*>) listRaw else emptyList()
    } catch (_: Throwable) {
        return emptyList()
    }
}

/** 将 QQ 搜索原始条目映射为 [Song]（缓存命中与网络路径共用） */
private fun _mapQQSearchSongs(raw: Any?): List<Song> {
    val list = if (raw is List<*>) raw else emptyList<Any?>()
    return list
        .filterIsInstance<Map<*, *>>()
        .map { mapQQSong(_normMap(it)) }
}

// ── 专辑搜索 / 专辑曲目 ──

/** 搜索 QQ 音乐专辑（`client_search_cp` 的 `t=8`） */
suspend fun searchQQAlbum(query: String, page: Int = 1, pageSize: Int = 30): List<Album> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()

    val cacheKey = "search:qq:album:v1:$q:$page:$pageSize"
    val cached = cacheService.get<List<Any?>>(cacheKey)
    if (cached != null) return _mapQQAlbums(cached)

    val url =
        "https://c.y.qq.com/soso/fcgi-bin/client_search_cp" +
            "?p=$page&n=$pageSize&t=8&w=${_encodeComponent(q)}&format=json"
    val text = httpGetText(url, qqHeaders)
    val decoded = jsonElementToAny(json.parseToJsonElement(_stripBom(text)))
    if (decoded !is Map<*, *>) return emptyList()
    val data = decoded["data"]
    val album = if (data is Map<*, *>) data["album"] else null
    val listRaw = if (album is Map<*, *>) album["list"] else null
    val list = if (listRaw is List<*>) listRaw else emptyList<Any?>()
    cacheService.set(cacheKey, list, _searchCacheTtl)
    return _mapQQAlbums(list)
}

/** QQ 专辑原始条目 → [Album] */
private fun _mapQQAlbums(raw: Any?): List<Album> {
    val list = if (raw is List<*>) raw else emptyList<Any?>()
    val out = mutableListOf<Album>()
    for (item in list) {
        val m = _toMap(item) ?: continue
        val mid = _s(m["albumMID"])
        out.add(
            Album(
                albumid = mid,
                name = decodeHtmlEntities(_s(m["albumName"])),
                artist = decodeHtmlEntities(_s(m["singerName"])),
                platform = MusicPlatform.qq,
                musiccnt = _or(m["song_count"], "0"),
                pub = _s(m["publicTime"]),
                pic = if (mid.isEmpty()) {
                    ""
                } else {
                    "https://y.gtimg.cn/music/photo_new/T002R800x800M000$mid.jpg"
                },
            ),
        )
    }
    return out
}

/** 获取 QQ 音乐专辑曲目（`fcg_v8_album_info_cp`） */
suspend fun getQQAlbumSongs(albumMid: String): List<AlbumSong> {
    try {
        val url =
            "https://c.y.qq.com/v8/fcg-bin/fcg_v8_album_info_cp.fcg" +
                "?albummid=$albumMid&format=json&inCharset=utf8&outCharset=utf-8" +
                "&notice=0&platform=yqq.json&needNewCode=0"
        val text = httpGetText(url, qqHeaders)
        val decoded = jsonElementToAny(json.parseToJsonElement(_stripBom(text)))
        if (decoded !is Map<*, *>) return emptyList()
        val data = decoded["data"]
        val listRaw = if (data is Map<*, *>) data["list"] else null
        val list = if (listRaw is List<*>) listRaw else emptyList<Any?>()
        val out = mutableListOf<AlbumSong>()
        for (item in list) {
            val m = _toMap(item) ?: continue
            val mid = _s(m["songmid"])
            if (mid.isEmpty()) continue
            // QQ 专辑歌曲自带 `singer:[{id, mid, name}]` —— 直接取**全部**歌手的名字与 mid。
            // 之前只取第一位歌手的名字、且不带 id，导致「跳歌手」缺 id 退化成联网搜索（有延迟）。
            val singer = m["singer"]
            val singerMaps = if (singer is List<*>) {
                singer.filterIsInstance<Map<*, *>>()
            } else {
                emptyList()
            }
            val artistNames = singerMaps
                .map { decodeHtmlEntities(_s(it["name"])) }
                .filter { it.isNotEmpty() }
            val artistIds = singerMaps
                .map { _s(it["mid"]) }
                .filter { it.isNotEmpty() }
            val artistName = artistNames.firstOrNull() ?: ""
            val interval = m["interval"]
            val idx = m["index_album"] ?: m["index_cd"]
            out.add(
                AlbumSong(
                    id = "qq:$mid",
                    name = decodeHtmlEntities(_s(m["songname"])),
                    artistName = decodeHtmlEntities(artistName),
                    duration = if (interval is Number) interval.toInt() else 0,
                    track = if (idx is Number) idx.toInt() else null,
                    artistNames = artistNames,
                    // 数量不一致时宁可不给 id（退回按名字搜索），也不要把 id 配错歌手
                    artistIds = if (artistIds.size == artistNames.size) artistIds else emptyList(),
                ),
            )
        }
        return out
    } catch (_: Throwable) {
        return emptyList()
    }
}

// ── 歌手搜索 / 歌手单曲 ──

/** 搜索 QQ 音乐歌手（`client_search_cp` 的 `t=9`，条目带 `singerPic`） */
suspend fun searchQQArtist(query: String): List<Artist> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()

    val cacheKey = "search:qq:artist:v1:$q"
    val cached = cacheService.get<List<Any?>>(cacheKey)
    if (cached != null) return _mapQQArtists(cached)

    val url =
        "https://c.y.qq.com/soso/fcgi-bin/client_search_cp" +
            "?p=1&n=30&t=9&w=${_encodeComponent(q)}&format=json"
    val text = httpGetText(url, qqHeaders)
    val decoded = jsonElementToAny(json.parseToJsonElement(_stripBom(text)))
    if (decoded !is Map<*, *>) return emptyList()
    val data = decoded["data"]
    val singer = if (data is Map<*, *>) data["singer"] else null
    val listRaw = if (singer is Map<*, *>) singer["list"] else null
    val list = if (listRaw is List<*>) listRaw else emptyList<Any?>()
    cacheService.set(cacheKey, list, _searchCacheTtl)
    return _mapQQArtists(list)
}

/** QQ 歌手原始条目 → [Artist] */
private fun _mapQQArtists(raw: Any?): List<Artist> {
    val list = if (raw is List<*>) raw else emptyList<Any?>()
    val out = mutableListOf<Artist>()
    for (item in list) {
        val m = _toMap(item) ?: continue
        val numSongs = m["songNum"]
        out.add(
            Artist(
                id = _s(m["singerMID"]),
                name = decodeHtmlEntities(_s(m["singerName"])),
                picture = _s(m["singerPic"]),
                musicNum = if (numSongs is Number) numSongs.toInt() else 0,
            ),
        )
    }
    return out
}

/**
 * QQ 音乐歌手单曲（musicu `get_singer_detail_info`）。
 *
 * 注意：musicu 的 `SearchCgiService` 返回错误码 500001 且不可用，
 * 搜索一律走 `client_search_cp`，不经过 musicu。
 */
suspend fun getQQArtistSongs(singerMid: String, limit: Int = 100): List<Song> {
    try {
        val payload: Map<String, Any?> = mapOf(
            "comm" to mapOf("ct" to 24, "cv" to 0),
            "singer" to mapOf(
                "module" to "music.web_singer_info_svr",
                "method" to "get_singer_detail_info",
                "param" to mapOf("sort" to 5, "singermid" to singerMid, "begin" to 0, "num" to limit),
            ),
        )
        val data = httpPostJson(
            "https://u.y.qq.com/cgi-bin/musicu.fcg",
            body = payload,
            headers = mapOf(
                "Content-Type" to "application/json",
                "User-Agent" to _or(qqHeaders["User-Agent"], ""),
                "Referer" to "https://y.qq.com/",
            ),
        )
        val singer = data["singer"]
        val d = if (singer is Map<*, *>) singer["data"] else null
        val listRaw = if (d is Map<*, *>) d["songlist"] else null
        val list = if (listRaw is List<*>) listRaw else emptyList<Any?>()
        return list
            .filterIsInstance<Map<*, *>>()
            .map { _mapQQArtistSong(_normMap(it)) }
    } catch (_: Throwable) {
        return emptyList()
    }
}

/** QQ 歌手单曲条目 → [Song] */
private fun _mapQQArtistSong(m: Map<String, Any?>): Song {
    val mid = _s(m["mid"])
    val singerRaw = m["singer"]
    val singerList = if (singerRaw is List<*>) {
        singerRaw.filterIsInstance<Map<*, *>>()
    } else {
        emptyList()
    }
    val artistNames = singerList
        .map { decodeHtmlEntities(_s(it["name"])) }
        .filter { it.isNotEmpty() }
    val artistIds = singerList
        .map { _s(it["mid"]) }
        .filter { it.isNotEmpty() }
    val artist = artistNames.joinToString(" / ")
    val albumRaw = m["album"]
    val album = _toMap(albumRaw) ?: emptyMap()
    val albumMid = _s(album["mid"])
    val interval = m["interval"]
    return Song(
        id = "qq:$mid",
        title = decodeHtmlEntities(_s(m["name"])),
        artist = decodeHtmlEntities(artist),
        cover = if (albumMid.isEmpty()) {
            ""
        } else {
            "https://y.gtimg.cn/music/photo_new/T002R800x800M000$albumMid.jpg"
        },
        album = decodeHtmlEntities(_s(album["name"])),
        albumId = albumMid,
        duration = if (interval is Number) interval.toInt() else 0,
        platform = MusicPlatform.qq,
        albumArtist = decodeHtmlEntities(artist),
        artistNames = artistNames,
        artistIds = artistIds,
    )
}

/**
 * QQ 音乐歌手专辑。
 *
 * 已废弃：官方歌手专辑接口（musicu `get_singer_album`）已失效，恒返回空；
 * 官方网页改用签名版 `musics.fcg`。这里保留 Dart 原兜底——「专辑搜索 + 歌手名
 * 精确过滤」，等签名实现接入后替换。
 */
suspend fun getQQArtistAlbums(singerMid: String, singerName: String): List<Album> {
    if (singerName.isEmpty()) return emptyList()
    return try {
        val all = searchQQAlbum(singerName, pageSize = 60)
        all.filter { it.artist == singerName }
    } catch (_: Throwable) {
        emptyList()
    }
}

// ── 播放/下载直链（长青解析接口，取自「安妮独家音源 v5」） ──

/**
 * 获取 QQ 音乐直链。
 *
 * [songMid]：纯 songmid（不带 `qq:` 前缀）。
 * [quality]：`128k` / `320k` / `flac` / `flac24bit` / `hires`，未知值按 `lossless`。
 */
suspend fun fetchQQMusicUrl(songMid: String, quality: String): String {
    if (songMid.isEmpty()) throw IllegalStateException("缺少 songmid")

    val level = _changqingLevelTx[quality] ?: "lossless"
    val resp: Map<String, Any?>
    try {
        resp = httpPostJson(
            _changqingResolveUrl,
            body = mapOf("source" to "tx", "rid" to songMid, "level" to level),
            headers = _changqingHeaders,
        )
    } catch (e: IOException) {
        // 接口对查不到的 rid 直接返回 503（空 body），转成可读提示
        throw IllegalStateException("QQ 直链解析失败 (HTTP 网络错误)")
    }

    val code = resp["code"]
    val data = resp["data"]
    val url = if (data is Map<*, *>) data["url"] else null
    if (_isZero(code) && url is String && url.isNotEmpty()) return url

    val msg = _firstTruthy(listOf(resp["message"], resp["msg"]))
    throw IllegalStateException(
        if (msg is String && msg.isNotEmpty()) msg else "QQ 直链获取失败 (code: $code)",
    )
}
