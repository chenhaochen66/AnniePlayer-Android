package com.annieplayer.annie_music.kt.data.sources

import com.annieplayer.annie_music.kt.data.model.Album
import com.annieplayer.annie_music.kt.data.model.Artist
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.QobuzAlbum
import com.annieplayer.annie_music.kt.data.model.QobuzTrack
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.network.httpGetJson
import com.annieplayer.annie_music.kt.data.network.retryUntilSuccess
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.security.MessageDigest

/**
 * Qobuz 音乐 API（1:1 移植 Flutter `lib/data/sources/qobuz_api.dart`）。
 *
 * 直译说明：
 * - MD5 由 RN 版手写实现替换为 [MessageDigest]（与 RN 手写实现逐字节一致）；
 * - 网络层复用 `data/network` 的 [httpGetJson] / [retryUntilSuccess]（对齐 Dart globalDio）；
 * - 签名算法、请求头（Android 设备指纹）、URL 拼接均与 RN 版保持一致。
 *
 * 保持与原版一致的函数名、参数与返回语义。
 */

// ── 文件内返回结构（对齐 Dart records） ──

/** 请求签名结果（对齐 Dart `({String ts, String sig})`） */
data class QobuzSignature(
    val ts: String,
    val sig: String,
)

/** checkToken 返回（对齐 Dart `({String region, bool valid})`） */
data class QobuzTokenStatus(
    val region: String,
    val valid: Boolean,
)

/** getFileUrl 返回（对齐 Dart `({String url, String mimeType})`） */
data class QobuzFileUrl(
    val url: String,
    val mimeType: String,
)

/** parseQobuzUrl 返回（对齐 Dart `({String type, String id})`） */
data class QobuzUrlRef(
    val type: String,
    val id: String,
)

// ── 常量（与 RN 版完全一致） ──

/** Qobuz 应用 ID（对齐 RN 版 APP_ID） */
const val qobuzAppId = "312369995"

/** Qobuz 应用密钥（对齐 RN 版 APP_SECRET） */
const val qobuzAppSecret = "e79f8b9be485692b0e5f9dd895826368"

/** Qobuz API 基址（对齐 RN 版 BASE_URL） */
const val qobuzBaseUrl = "https://www.qobuz.com/api.json/0.2/"

/** getFileUrl 的 format_id：27 = FLAC 24bit，6 = FLAC CD，5 = MP3 320 */
const val qobuzHiresFormatId = 27
const val qobuzFlacFormatId = 6
const val qobuzMp3FormatId = 5

fun qobuzQualityLabel(q: Quality): String = when (q) {
    Quality.hires -> "FLAC Hires Max"
    Quality.flac -> "FLAC 16bit/44.1kHz"
    Quality.k320k -> "MP3 320"
    Quality.k128k -> "MP3 128"
}

/** 专辑规格文案（位深 / 采样率 / Hi-Res）；无数据返回空串 */
fun qobuzQualitySpec(a: QobuzAlbum): String = listOfNotNull(
    a.maxBitDepth?.let { "${it}bit" },
    a.maxSamplingRateKhz?.let { khz ->
        val text = if (khz % 1.0 == 0.0) khz.toInt().toString() else khz.toString()
        "${text}kHz"
    },
    if (a.hires) "Hi-Res" else null,
).joinToString(" / ")

fun qobuzQualityFormatId(q: Quality): Int = when (q) {
    Quality.hires -> qobuzHiresFormatId
    Quality.flac -> qobuzFlacFormatId
    else -> qobuzMp3FormatId
}

/** 活跃 token 提供者，由 AppShell 注入 `QobuzViewModel.getActiveToken()?.token` */
var qobuzTokenProvider: () -> String? = { null }

// ── 通用辅助（对齐 JS 真值/取整/类型语义） ──

/** 计算标准 MD5 十六进制串（等价 RN 版手写 md5，小写十六进制） */
private fun _md5Hex(s: String): String {
    val digest = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
    val sb = StringBuilder()
    for (b in digest) {
        val v = b.toInt() and 0xFF
        if (v < 16) sb.append('0')
        sb.append(v.toString(16))
    }
    return sb.toString()
}

/** JS 真值语义（用于直译 RN 版 `!!x` / `x || y` 中的真值判定） */
private fun _jsTruthy(value: Any?): Boolean = when (value) {
    null -> false
    is Boolean -> value
    is Number -> value.toDouble().let { it != 0.0 && !it.isNaN() }
    is String -> value.isNotEmpty()
    else -> true
}

/** `a || b || ...` 语义：返回首个真值；全为假时返回 null */
private fun _firstTruthy(values: List<Any?>): Any? {
    for (v in values) {
        if (_jsTruthy(v)) return v
    }
    return null
}

/** 转 Int，失败时回退 fallback（等价 `x || 0` 的数字语义） */
private fun _toIntOr(value: Any?, fallback: Int): Int {
    if (value is Int) return value
    if (value is Long) return value.toInt()
    if (value is Double) return value.toInt()
    if (value is Float) return value.toInt()
    if (value is Number) return value.toInt()
    if (value == null) return fallback
    val s = value.toString()
    if (s.isEmpty()) return fallback
    return s.toIntOrNull() ?: fallback
}

/** 取容器 `container.image.large`（用于 `album?.image?.large`） */
private fun _nestedLarge(container: Any?): Any? {
    if (container is Map<*, *>) {
        val image = container["image"]
        if (image is Map<*, *>) return image["large"]
    }
    return null
}

/** 取图像对象 `image.large`（用于 `image?.large`） */
private fun _directLarge(image: Any?): Any? {
    if (image is Map<*, *>) return image["large"]
    return null
}

/**
 * 百分号编码（对齐 Dart `Uri.encodeComponent`，保留集 `A-Za-z0-9-_.!~*'()`，大写十六进制，UTF-8）。
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

/** 从网络异常信息中解析 HTTP 状态码（网络层非 2xx 抛 `IOException("HTTP {code} for ...")`） */
private fun _httpStatusFrom(error: Throwable): Int? {
    val msg = error.message ?: return null
    val m = Regex("HTTP (\\d+)").find(msg) ?: return null
    return m.groupValues[1].toIntOrNull()
}

// ── 签名与请求头 ──

/**
 * 生成请求签名（对齐 RN 版 createSignature）。
 *
 * md5(methodKey + Σ(按 key 升序拼 k+v，过滤 app_id/user_auth_token) + ts + APP_SECRET)
 */
fun createSignature(method: String, params: Map<String, String>): QobuzSignature {
    val ts = (System.currentTimeMillis() / 1000).toString()
    val methodKey = method.replaceFirst("/", "")
    val keys = params.keys
        .filter { it != "app_id" && it != "user_auth_token" }
        .sorted()
    var raw = methodKey
    for (k in keys) {
        raw += k + (params[k] ?: "")
    }
    raw += ts + qobuzAppSecret
    return QobuzSignature(ts = ts, sig = _md5Hex(raw))
}

/** 构造 Qobuz 请求头（对齐 RN 版 headers，固定 Android 设备指纹） */
fun qobuzHeaders(token: String): Map<String, String> {
    val base = mapOf(
        "X-Device-Platform" to "android",
        "X-Device-Model" to "Pixel 3",
        "X-Device-Os-Version" to "10",
        "X-Device-Manufacturer-Id" to "482D8CB7-015D-402F-A93B-5EEF0E0996F3",
        "X-App-Version" to "5.16.1.5",
        "User-Agent" to
            "Dalvik/2.1.0 (Linux; U; Android 10; Pixel 3 Build/QP1A.190711.020))QobuzMobileAndroid/5.16.1.5-b21041415",
    )
    // 搜索 / 专辑 / 歌手接口免 token：为空时不带该头（带空串会被服务端判未授权）
    return if (token.isEmpty()) base else base + ("X-User-Auth-Token" to token)
}

/** 从文本解析 Qobuz 链接（对齐 RN 版 parseQobuzUrl） */
fun parseQobuzUrl(text: String): QobuzUrlRef? {
    val re by lazy { Regex("https?://open\\.qobuz\\.com/(track|album)/([a-zA-Z0-9]+)") }
    val m = re.find(text) ?: return null
    return QobuzUrlRef(type = m.groupValues[1], id = m.groupValues[2])
}

// ── 网络 ──

/** 发送 GET 并解析 JSON（非 2xx 抛 `HTTP {status}`，与 RN 版一致） */
private suspend fun _getJson(url: String, token: String): Map<String, Any?> {
    return try {
        httpGetJson(url, qobuzHeaders(token))
    } catch (e: IOException) {
        val status = _httpStatusFrom(e)
        if (status != null) throw IllegalStateException("HTTP $status")
        throw e
    }
}

/** 校验 Token 有效性（对齐 RN 版 checkToken，10 秒超时） */
suspend fun checkToken(token: String): QobuzTokenStatus {
    val params = mapOf("app_id" to qobuzAppId)
    val s = createSignature("user/get", params)
    val url =
        "${qobuzBaseUrl}user/get?app_id=$qobuzAppId&request_ts=${s.ts}&request_sig=${s.sig}"
    return try {
        val data = withTimeout(10_000L) { httpGetJson(url, qobuzHeaders(token)) }
        val region = _firstTruthy(listOf(data["region"], data["country"]))?.toString() ?: ""
        QobuzTokenStatus(region = region, valid = region.isNotEmpty())
    } catch (e: IOException) {
        val status = _httpStatusFrom(e)
        when {
            status == 401 -> QobuzTokenStatus(region = "", valid = false)
            status != null -> throw IllegalStateException("HTTP $status")
            else -> throw e
        }
    }
}

/** 拉取单曲原始 JSON（对齐 RN 版 getTrack 的网络行为） */
suspend fun fetchTrackJson(trackId: String, token: String): Map<String, Any?> {
    val params = mapOf("app_id" to qobuzAppId, "track_id" to trackId)
    val s = createSignature("track/get", params)
    val url =
        "${qobuzBaseUrl}track/get?app_id=$qobuzAppId&track_id=$trackId" +
            "&request_ts=${s.ts}&request_sig=${s.sig}"
    return retryUntilSuccess { _getJson(url, token) }
}

/** 拉取专辑原始 JSON（对齐 RN 版 getAlbum 的网络行为） */
suspend fun fetchAlbumJson(albumId: String, token: String): Map<String, Any?> {
    val params = mapOf("app_id" to qobuzAppId, "album_id" to albumId)
    val s = createSignature("album/get", params)
    val url =
        "${qobuzBaseUrl}album/get?app_id=$qobuzAppId&album_id=$albumId" +
            "&request_ts=${s.ts}&request_sig=${s.sig}"
    return retryUntilSuccess { _getJson(url, token) }
}

/** 获取单曲（返回映射后的 QobuzTrack） */
suspend fun getTrack(trackId: String, token: String): QobuzTrack =
    mapTrackToQobuzTrack(fetchTrackJson(trackId, token))

    /** 获取专辑（返回映射后的 QobuzAlbum） */
    suspend fun getQobuzAlbum(albumId: String, token: String): QobuzAlbum =
    mapAlbumToQobuzAlbum(fetchAlbumJson(albumId, token))

/**
 * 获取音频直链（对齐 RN 版 getFileUrl）。
 *
 * [qualityId] 对应 Qobuz 的 format_id，默认为 5。
 */
suspend fun getFileUrl(trackId: String, token: String, qualityId: Int = 5): QobuzFileUrl {
    val params = mapOf(
        "app_id" to qobuzAppId,
        "track_id" to trackId,
        "format_id" to qualityId.toString(),
        "intent" to "stream",
        "sample" to "false",
        "user_auth_token" to token,
    )
    val s = createSignature("track/getFileUrl", params)
    val allParams = LinkedHashMap<String, String>(params)
    allParams["request_ts"] = s.ts
    allParams["request_sig"] = s.sig
    val qs = allParams.entries.joinToString("&") { "${it.key}=${_encodeComponent(it.value)}" }
    val url = "${qobuzBaseUrl}track/getFileUrl?$qs"
    val data = retryUntilSuccess { _getJson(url, token) }
    return QobuzFileUrl(
        url = data["url"]?.toString() ?: "",
        mimeType = _firstTruthy(listOf(data["mime_type"]))?.toString() ?: "audio/flac",
    )
}

// ── 映射 ──

/** 映射 Qobuz 单曲 JSON -> QobuzTrack（对齐 RN 版 mapTrackToQobuzTrack） */
fun mapTrackToQobuzTrack(raw: Map<String, Any?>): QobuzTrack {
    val performer = raw["performer"]
    val performerName = if (performer is Map<*, *>) performer["name"] else performer
    val artistRaw = raw["artist"]
    val artistName = if (artistRaw is Map<*, *>) artistRaw["name"] else artistRaw

    val album = raw["album"]
    var albumArtistName: Any? = null
    if (album is Map<*, *>) {
        val aa = album["artist"]
        albumArtistName = if (aa is Map<*, *>) aa["name"] else aa
    }

    val discRaw = _firstTruthy(listOf(raw["media_number"], raw["disc_number"]))

    // 只用 performer（该曲演唱者）：performers 逗号串会把制作人/工程师也算进来
    val performerId = if (performer is Map<*, *>) (performer["id"] ?: "").toString() else ""
    val artistNames = if (_jsTruthy(performerName)) listOf(performerName.toString()) else emptyList()
    val artistIds = if (performerId.isNotEmpty()) listOf(performerId) else emptyList()

    return QobuzTrack(
        id = raw["id"]?.toString() ?: "",
        title = (_firstTruthy(listOf(raw["title"])) ?: "").toString(),
        artist = (_firstTruthy(listOf(performerName, artistName)) ?: "").toString(),
        cover = (_firstTruthy(listOf(_nestedLarge(album), _directLarge(raw["image"]))) ?: "")
            .toString(),
        album = (_firstTruthy(listOf(if (album is Map<*, *>) album["title"] else null)) ?: "")
            .toString(),
        albumId = (_firstTruthy(listOf(if (album is Map<*, *>) album["id"] else null)) ?: "")
            .toString(),
        albumArtist = if (_jsTruthy(albumArtistName)) albumArtistName.toString() else null,
        trackNumber = _toIntOr(raw["track_number"], 0),
        discNumber = if (discRaw == null) null else _toIntOr(discRaw, 0),
        duration = _toIntOr(raw["duration"], 0),
        streamable = _jsTruthy(raw["streamable"]),
        label = (_firstTruthy(listOf(raw["label"])) ?: "").toString(),
        year = _toIntOr(raw["year"], 0),
        releaseDateOriginal = raw["release_date_original"]?.toString()?.takeIf { it.isNotEmpty() },
        isrc = raw["isrc"]?.toString()?.takeIf { it.isNotEmpty() },
        version = raw["version"]?.toString()?.takeIf { it.isNotEmpty() },
        copyright = raw["copyright"]?.toString()?.takeIf { it.isNotEmpty() },
        artistNames = artistNames,
        artistIds = artistIds,
    )
}

/** 映射 Qobuz 专辑 JSON -> QobuzAlbum（对齐 RN 版 mapAlbumToQobuzAlbum） */
fun mapAlbumToQobuzAlbum(raw: Map<String, Any?>): QobuzAlbum {
    val cover =
        (_firstTruthy(listOf(_directLarge(raw["image"]), _nestedLarge(raw["album"]))) ?: "")
            .toString()
    val artistRaw = raw["artist"]
    val artistName = if (artistRaw is Map<*, *>) artistRaw["name"] else artistRaw
    val performerRaw = raw["performer"]
    val performerName = if (performerRaw is Map<*, *>) performerRaw["name"] else performerRaw
    val artistStr = (_firstTruthy(listOf(artistName, performerName)) ?: "").toString()

    val tracksContainer = raw["tracks"]
    val items: List<Any?> = if (tracksContainer is Map<*, *> && tracksContainer["items"] is List<*>) {
        tracksContainer["items"] as List<Any?>
    } else {
        emptyList()
    }

    val tracks = items.map { t ->
        val merged = if (t is Map<*, *>) {
            LinkedHashMap<String, Any?>(t.entries.associate { (k, v) -> k.toString() to v })
        } else {
            LinkedHashMap<String, Any?>()
        }
        // 只在缺失时补：专辑接口的曲目相对专辑是「绝对」信息，曲目自带的 performer 才是该曲真实表演者
        //（单歌手专辑里也会有 feat. 曲目，覆盖会把多歌手信息压成专辑艺术家）
        if (merged["album"] == null) {
            merged["album"] = mapOf(
                "id" to raw["id"],
                "title" to raw["title"],
                "image" to mapOf("large" to cover),
                "artist" to mapOf("name" to artistStr),
            )
        }
        if (merged["performer"] == null) {
            merged["performer"] = mapOf("name" to artistStr)
        }
        mapTrackToQobuzTrack(merged)
    }

    return QobuzAlbum(
        id = raw["id"]?.toString() ?: "",
        title = (_firstTruthy(listOf(raw["title"])) ?: "").toString(),
        artist = artistStr,
        cover = cover,
        year = _toIntOr(raw["year"], 0),
        label = (_firstTruthy(listOf(raw["label"])) ?: "").toString(),
        tracks = tracks,
        maxBitDepth = (raw["maximum_bit_depth"] as? Number)?.toInt(),
        maxSamplingRateKhz = (raw["maximum_sampling_rate"] as? Number)?.toDouble(),
        hires = _jsTruthy(raw["hires"]),
        trackCount = _toIntOr(
            _firstTruthy(listOf(raw["tracks_count"], raw["media_count"])),
            tracks.size,
        ),
    )
}

// ── 统一模型映射（搜索 / 详情共用；UI 不再各自实现一份） ──

/** Qobuz 单曲 -> [Song]。[hires] 用于推断可用音质档位。 */
fun qobuzTrackToSong(track: QobuzTrack, hires: Boolean = false): Song = Song(
    id = "qobuz:${track.id}",
    title = track.title,
    artist = track.artist,
    cover = track.cover,
    album = track.album,
    albumId = track.albumId.ifEmpty { null },
    duration = track.duration,
    platform = MusicPlatform.qobuz,
    albumArtist = track.albumArtist,
    trackNumber = if (track.trackNumber > 0) track.trackNumber.toString() else null,
    discNumber = track.discNumber?.toString(),
    releaseDate = track.releaseDateOriginal
        ?: if (track.year > 0) track.year.toString() else null,
    qualities = if (hires) {
        listOf(Quality.hires, Quality.flac, Quality.k320k)
    } else {
        listOf(Quality.flac, Quality.k320k)
    },
    artistNames = track.artistNames,
    artistIds = track.artistIds,
)

/** Qobuz 专辑 -> [Album]（封面放 `pic`，`buildAlbumCoverUrl` 对 qobuz 直接取它）。 */
fun qobuzAlbumToAlbum(a: QobuzAlbum): Album = Album(
    albumid = a.id,
    name = a.title,
    artist = a.artist,
    platform = MusicPlatform.qobuz,
    musiccnt = a.trackCount.toString(),
    pic = a.cover,
    img = a.cover,
)

private fun _asMap(v: Any?): Map<String, Any?>? =
    if (v is Map<*, *>) v.entries.associate { (k, item) -> k.toString() to item } else null

private fun _containerItems(container: Any?): List<Any?> {
    val m = _asMap(container) ?: return emptyList()
    val items = m["items"]
    return if (items is List<*>) items else emptyList()
}

// ── 搜索（catalog/search：免 token、免签名，仅 app_id） ──

enum class QobuzSearchType(val value: String) {
    tracks("tracks"),
    albums("albums"),
    artists("artists"),
}

private suspend fun _searchQobuzRaw(
    type: QobuzSearchType,
    query: String,
    limit: Int,
    offset: Int,
): Map<String, Any?> {
    val url = "${qobuzBaseUrl}catalog/search?query=${_encodeComponent(query)}" +
        "&type=${type.value}&limit=$limit&offset=$offset&app_id=$qobuzAppId"
    return retryUntilSuccess { _getJson(url, "") }
}

private suspend fun _searchQobuzItems(
    type: QobuzSearchType,
    query: String,
    limit: Int,
    offset: Int = 0,
): List<Map<String, Any?>> =
    _containerItems(_searchQobuzRaw(type, query, limit, offset)[type.value])
        .mapNotNull { _asMap(it) }

suspend fun searchQobuzTracks(query: String, limit: Int = 60, offset: Int = 0): List<Song> =
    _searchQobuzItems(QobuzSearchType.tracks, query, limit, offset)
        .map { qobuzTrackToSong(mapTrackToQobuzTrack(it), hires = _jsTruthy(it["hires"])) }

suspend fun searchQobuzAlbums(query: String, limit: Int = 30, offset: Int = 0): List<Album> =
    _searchQobuzItems(QobuzSearchType.albums, query, limit, offset)
        .map { qobuzAlbumToAlbum(mapAlbumToQobuzAlbum(it)) }

suspend fun searchQobuzArtists(query: String, limit: Int = 60, offset: Int = 0): List<Artist> =
    _searchQobuzItems(QobuzSearchType.artists, query, limit, offset).map { r ->
        Artist(
            id = r["id"]?.toString() ?: "",
            name = (_firstTruthy(listOf(r["name"])) ?: "").toString(),
            picture = (_directLarge(r["image"]) ?: "").toString(),
            musicNum = _toIntOr(r["albums_count"], 0),
        )
    }

// ── 歌手详情（artist/get：免 token） ──

private suspend fun fetchArtistJson(artistId: String, limit: Int = 100, offset: Int = 0): Map<String, Any?> {
    val url = "${qobuzBaseUrl}artist/get?artist_id=$artistId&app_id=$qobuzAppId" +
        "&extra=albums,playlists,tracks_appears_on,albums_with_last_release,focusAll" +
        "&limit=$limit&offset=$offset"
    return retryUntilSuccess { _getJson(url, "") }
}

/** 歌手头像（`artist/get` 的 `image.large`）；失败或缺失返回空串 */
suspend fun fetchQobuzArtistPicture(artistId: String): String = try {
    (_directLarge(fetchArtistJson(artistId)["image"]) ?: "").toString()
} catch (_: Throwable) {
    ""
}

data class QobuzArtistPage(
    val name: String,
    val picture: String,
    val albums: List<Album>,
    val songs: List<Song>,
)

/**
 * 歌手详情数据。Qobuz 没有"歌手全部单曲"接口，故歌曲 = `tracks_appears_on`
 * ∪ 按歌手名搜索后按 performer.id 过滤（前者量少、后者量足），专辑取其 albums 全量。
 */
suspend fun getQobuzArtistPage(artistId: String, artistName: String = ""): QobuzArtistPage {
    val raw = fetchArtistJson(artistId)
    val name = (_firstTruthy(listOf(raw["name"])) ?: artistName).toString()
    val picture = (_directLarge(raw["image"]) ?: "").toString()
    val albums = _containerItems(raw["albums"])
        .mapNotNull { _asMap(it) }
        .map { qobuzAlbumToAlbum(mapAlbumToQobuzAlbum(it)) }

    val appearsOn = _containerItems(raw["tracks_appears_on"])
        .mapNotNull { _asMap(it) }
        .map { qobuzTrackToSong(mapTrackToQobuzTrack(it), hires = _jsTruthy(it["hires"])) }

    val byNameSearch = if (name.isEmpty()) {
        emptyList()
    } else {
        runCatching {
            _searchQobuzItems(QobuzSearchType.tracks, name, 60)
                .filter { r -> (_asMap(r["performer"])?.get("id")?.toString() ?: "") == artistId }
                .map { qobuzTrackToSong(mapTrackToQobuzTrack(it), hires = _jsTruthy(it["hires"])) }
        }.getOrDefault(emptyList())
    }

    return QobuzArtistPage(
        name = name,
        picture = picture,
        albums = albums,
        songs = (appearsOn + byNameSearch).distinctBy { it.id },
    )
}
