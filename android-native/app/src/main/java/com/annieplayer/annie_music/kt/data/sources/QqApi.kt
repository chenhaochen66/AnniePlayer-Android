package com.annieplayer.annie_music.kt.data.sources

import android.util.Base64
import com.annieplayer.annie_music.kt.core.util.QrcDes
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
import com.annieplayer.annie_music.kt.data.network.httpGetText
import com.annieplayer.annie_music.kt.data.network.httpPostJson
import com.annieplayer.annie_music.kt.data.network.json
import com.annieplayer.annie_music.kt.data.network.jsonElementToAny
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

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

/** 歌词结果（对齐 Dart `({String lrc, String? tlyric})`，另带逐字） */
data class QqLyric(
    val lrc: String,
    val tlyric: String?,
    val wordLyric: String? = null,
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

/** 取嵌套字段：支持 `album.name` 这类路径（新接口把字段收进了子对象） */
private fun _deep(s: Map<String, Any?>, vararg path: String): Any? {
    var cur: Any? = s
    for (key in path) {
        cur = (cur as? Map<*, *>)?.get(key) ?: return null
    }
    return cur
}

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
        .takeIf { it.size == artistNames.size } ?: emptyList()
    val singer = artistNames.joinToString(" / ")

    // 新旧字段并存：旧接口平铺，musicu 收进 file.*
    val qual = mutableListOf<Quality>()
    if (_truthy(_firstTruthy(listOf(s["sizeflac"], _deep(s, "file", "size_flac"))))) {
        qual.add(Quality.flac)
    }
    if (_truthy(_firstTruthy(listOf(s["size320"], _deep(s, "file", "size_320mp3"))))) {
        qual.add(Quality.k320k)
    }
    if (_truthy(_firstTruthy(listOf(s["size128"], _deep(s, "file", "size_128mp3"))))) {
        qual.add(Quality.k128k)
    }
    if (qual.isEmpty()) qual.add(Quality.k128k)

    val albumMid = _s(_firstTruthy(listOf(s["albummid"], _deep(s, "album", "mid"))))
    val coverUrl = if (albumMid.isNotEmpty()) {
        "https://y.gtimg.cn/music/photo_new/T002R800x800M000$albumMid.jpg"
    } else {
        ""
    }
    val artist = decodeHtmlEntities(singer)

    // songmid / songname / albumname：新接口对应 `mid` / `name` / `album.name`
    val songmid = _s(_firstTruthy(listOf(s["songmid"], s["mid"])))
    val interval = s["interval"]

    return Song(
        id = "qq:$songmid",
        title = decodeHtmlEntities(_or(_firstTruthy(listOf(s["songname"], s["name"])), "未知歌曲")),
        artist = artist,
        cover = coverUrl,
        album = decodeHtmlEntities(
            _s(_firstTruthy(listOf(s["albumname"], _deep(s, "album", "name")))),
        ),
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

/** QRC 行：`[起始ms,时长ms]词(起始ms,时长ms)词…`（词起始小于行首时按相对值处理） */
private val _reQrcLine by lazy { Regex("^\\[(\\d+),(\\d+)\\](.+)$") }
/** 词块文本必须**遇换行即停**：`[^(]*` 会跨行吞掉下一行的 `[start,dur]`，拼接后就少了空格 */
private val _reQrcWord by lazy { Regex("\\((\\d+),(\\d+)\\)([^(\\n]*)") }

/** QRC -> 行级 lrc + 统一逐字格式 */
private fun _qrcToLrcAndWords(qrc: String): Pair<String, String?> {
    val lrc = StringBuilder()
    val wl = StringBuilder()
    for (line in qrc.split('\n')) {
        val m = _reQrcLine.find(line.trim()) ?: continue
        val lineStart = m.groupValues[1].toIntOrNull() ?: continue
        val body = m.groupValues[3]
        // QRC 行形如「首字(起始,时长)字(起始,时长)…」：首个标记**之前**还有一段文本（首字/首词），
        // 只取标记之后的文本会丢掉每行第一个字（真机现象：QQ 每行歌词第一个字不显示）。
        // 首个标记之前的文本（首词）**不要 trim**：QRC 里首词与第二个词之间的空格在 `(` 之前，
        // trim 掉就会变成「Helloit's me」（真机 + 下载标签都复现过）。
        val lead = body.substringBefore('(')
        val marks = _reQrcWord.findAll(body).toList()
        if (lead.isBlank() && marks.isEmpty()) continue
        val text = (lead + marks.joinToString("") { it.groupValues[3] }).trim()
        if (text.isEmpty()) continue
        val ts = lrcTimeOfMs(lineStart)
        lrc.append(ts).append(text).append('\n')
        val wordsSb = StringBuilder()
        if (lead.isNotBlank()) wordsSb.append('<').append(lineStart).append(",0>").append(lead)
        // QRC 的词时间是**相对行首**的（实测 Kill Bill：0/465/930…）；按整行判断，
        // 不要逐词判断「小于行首就当相对」，行首较小时会误判成绝对值。
        val allRelative = marks.all { (it.groupValues[1].toIntOrNull() ?: 0) < lineStart }
        for (w in marks) {
            val rawStart = w.groupValues[1].toIntOrNull() ?: 0
            val dur = w.groupValues[2].toIntOrNull() ?: 0
            val abs = if (allRelative) lineStart + rawStart else rawStart
            wordsSb.append('<').append(abs).append(',').append(dur).append('>').append(w.groupValues[3])
        }
        wl.append(ts).append(wordsSb).append('\n')
    }
    return lrc.toString().trimEnd('\n') to (if (wl.isEmpty()) null else wl.toString().trimEnd('\n'))
}

/** QQ `trans` 字段（base64 翻译歌词） */
private fun _qqTrans(d: Map<*, *>?): String? {
    if (d == null || !_truthy(d["trans"])) return null
    val b64 = if (d["trans"] is String) d["trans"] as String else return null
    return try {
        _decodeQQBase64(b64)
    } catch (_: Throwable) {
        null
    }
}

/** 获取 QQ 歌词，返回原始 lrc、翻译歌词与逐字歌词；失败返回占位歌词 */
suspend fun fetchQQLyric(songId: String): QqLyric {
    val fallback = QqLyric("[00:00.00]暂无歌词", null)
    val songMid = _rawId(songId)

    suspend fun requestLyric(qrc: Int): Map<*, *>? = try {
        val payload: Map<String, Any?> = mapOf(
            "request" to mapOf(
                "module" to "music.musichallSong.PlayLyricInfo",
                "method" to "GetPlayLyricInfo",
                "param" to mapOf("songMID" to songMid, "crypt" to 0, "qrc" to qrc, "trans" to 1),
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
        _toMap(if (reqRaw is Map<*, *>) reqRaw["data"] else null)
    } catch (_: Throwable) {
        null
    }

    val dQrc = requestLyric(1)
    val lyricQrc = if (dQrc?.get("lyric") is String) dQrc["lyric"] as String else ""
    val decrypted = QrcDes.decryptToQrc(lyricQrc)
    if (decrypted != null) {
        val parsed = _qrcToLrcAndWords(decrypted)
        if (parsed.first.isNotEmpty()) {
            return QqLyric(lrc = parsed.first, tlyric = _qqTrans(dQrc), wordLyric = parsed.second)
        }
    }

    // QRC 解密失败或该曲无 QRC：退回普通 LRC 再请求一次（保证歌词一定能显示）
    val dPlain = requestLyric(0)
    val lyricPlain = if (dPlain?.get("lyric") is String) dPlain["lyric"] as String else ""
    if (lyricPlain.isEmpty()) return fallback
    return try {
        QqLyric(lrc = _decodeQQBase64(lyricPlain), tlyric = _qqTrans(dPlain), wordLyric = null)
    } catch (_: Throwable) {
        fallback
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
 * [page] 从 1 开始；[pageSize] 每页条数（单次请求上限 60，超出分多次拉取再切片）。
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

/** 移动端搜索接口（单次 60 条，比旧网页接口快约 3 倍）。 */
private const val _qqMusicuSearchUrl = "https://u.y.qq.com/cgi-bin/musicu.fcg"

/** musicu `comm.guid`：32 位 hex，进程内随机一次。 */
private val _qqMusicuGuid: String by lazy {
    val hex = "0123456789abcdef"
    (1..32).joinToString("") { hex[(Math.random() * 16).toInt()].toString() }
}

/**
 * 拉取 QQ 搜索单页原始条目（[pageSize] ≤ 60）。
 *
 * musicu 三个坑：`grp` 必须 "1"（否则 item_song 为空）；`page_num` 必须是数字
 * （字符串会被忽略、永远第 1 页）；结果在 `body.item_song`，直接是数组、无 `.list`。
 */
private suspend fun _fetchQQSearchRawPage(
    query: String,
    page: Int,
    pageSize: Int,
): List<Any?> {
    val payload = mapOf(
        "req_0" to mapOf(
            "module" to "music.search.SearchCgiService",
            "method" to "DoSearchForQQMusicMobile",
            "param" to mapOf(
                "query" to query,
                "search_type" to "song",
                "num_per_page" to pageSize,
                // 必须是数字：字符串会被忽略（永远第 1 页）
                "page_num" to page,
                // 必须保留：去掉后 code=0 但 item_song 为空
                "grp" to "1",
            ),
        ),
        "comm" to mapOf(
            "guid" to _qqMusicuGuid,
            "ct" to 19,
            "cv" to 1914,
        ),
    )
    return try {
        val data = httpPostJson(_qqMusicuSearchUrl, qqHeaders, payload)
        val req0 = data["req_0"]
        val body = if (req0 is Map<*, *>) {
            val d = req0["data"]
            if (d is Map<*, *>) d["body"] else null
        } else {
            null
        }
        val songs = if (body is Map<*, *>) body["item_song"] else null
        if (songs is List<*>) songs.toList() else emptyList()
    } catch (_: Throwable) {
        emptyList()
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
suspend fun getQQAlbumSongs(albumMid: String): AlbumSongsPage {
    try {
        val url =
            "https://c.y.qq.com/v8/fcg-bin/fcg_v8_album_info_cp.fcg" +
                "?albummid=$albumMid&format=json&inCharset=utf8&outCharset=utf-8" +
                "&notice=0&platform=yqq.json&needNewCode=0"
        val text = httpGetText(url, qqHeaders)
        val decoded = jsonElementToAny(json.parseToJsonElement(_stripBom(text)))
        if (decoded !is Map<*, *>) return AlbumSongsPage(emptyList())
        val data = decoded["data"]
        val listRaw = if (data is Map<*, *>) data["list"] else null
        val list = if (listRaw is List<*>) listRaw else emptyList<Any?>()
        // 发行时间在 `data.aDate`（形如 2013-06-03；同一响应内，无需额外请求）
        val pub = if (data is Map<*, *>) _s(data["aDate"]) else ""
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
        return AlbumSongsPage(out, pub)
    } catch (_: Throwable) {
        return AlbumSongsPage(emptyList())
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
 * 注意：musicu 的 `get_singer_detail_info` 已不可用（500001）；
 * 单曲搜索走 `DoSearchForQQMusicMobile`（见 [_fetchQQSearchRawPage]）。
 */
/** QQ 歌手简介：与歌手单曲同一接口的 `data.singer_brief` */
suspend fun fetchQQArtistBio(singerMid: String): String {
    return try {
        val payload: Map<String, Any?> = mapOf(
            "comm" to mapOf("ct" to 24, "cv" to 0),
            "singer" to mapOf(
                "module" to "music.web_singer_info_svr",
                "method" to "get_singer_detail_info",
                "param" to mapOf("sort" to 5, "singermid" to singerMid, "begin" to 0, "num" to 1),
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
        if (d is Map<*, *>) (d["singer_brief"] ?: "").toString().trim() else ""
    } catch (_: Throwable) {
        ""
    }
}

/**
 * 获取 QQ 歌手单曲（`musichall.song_list_server` / `GetSingerSongList`，按 `begin`/`num` 真分页）。
 *
 * 模块名取自官网歌手页实际所用；老的 `music.web_singer_info_svr` 虽能返回 60 首，
 * 但 `begin` 被服务端忽略、无法翻页。每条结果为 `{ songInfo: {...} }`，歌曲对象在 `songInfo` 里。
 */
suspend fun getQQArtistSongs(singerMid: String, page: Int = 1): ArtistSongsPage {
    try {
        val payload: Map<String, Any?> = mapOf(
            "comm" to mapOf("ct" to 24, "cv" to 0),
            "songList" to mapOf(
                "module" to "musichall.song_list_server",
                "method" to "GetSingerSongList",
                "param" to mapOf(
                    "singerMid" to singerMid,
                    "order" to 1,
                    "begin" to (page - 1) * ArtistSongsPageSize,
                    "num" to ArtistSongsPageSize,
                ),
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
        val node = data["songList"]
        val d = if (node is Map<*, *>) node["data"] else null
        val listRaw = if (d is Map<*, *>) d["songList"] else null
        val list = if (listRaw is List<*>) listRaw else emptyList<Any?>()
        val total = if (d is Map<*, *>) (d["totalNum"] as? Number)?.toInt() ?: 0 else 0
        val songs = list.mapNotNull { item ->
            val info = if (item is Map<*, *>) item["songInfo"] else null
            if (info is Map<*, *>) _mapQQArtistSong(_normMap(info)) else null
        }
        return ArtistSongsPage(songs, total)
    } catch (_: Throwable) {
        return ArtistSongsPage(emptyList(), 0)
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
        .takeIf { it.size == artistNames.size } ?: emptyList()
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
