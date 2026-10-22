package com.annieplayer.annie_music.kt.data.sources

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import com.annieplayer.annie_music.kt.data.model.Album
import com.annieplayer.annie_music.kt.data.model.AlbumSong
import com.annieplayer.annie_music.kt.data.model.Artist
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.network.httpGetJson
import com.annieplayer.annie_music.kt.data.network.httpGetText
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Apple Music 相关接口（官方目录 + 第三方 wrapper）。
 *
 * 官方目录走 `amp-api-edge.music.apple.com`，由站点 `/amp` 代取 developer token，
 * 不需要登录；歌词、`/parse/song`、`/key`、`/license` 是站点 wrapper。
 *
 * 当前只接目录/详情/歌词；播放与下载等 native 解密链路完成后再接。
 */

/** Apple Music 当前选中的 storefront（国家/地区代码，如 `cn` / `us` / `jp`）。 */
@Volatile
var appleMusicStorefront: String = "cn"

/** Apple 国家选择与 developer token 的持久化；未 init 时保持内存默认。 */
object AppleMusicSettings {
    private const val PREFS = "annie_apple_music"
    private const val KEY_STOREFRONT = "storefront"
    private const val KEY_TOKEN = "developer_token"
    private const val KEY_TOKEN_EXP = "developer_token_exp"
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs?.getString(KEY_STOREFRONT, null)
        if (!saved.isNullOrEmpty()) appleMusicStorefront = saved
    }

    fun setStorefront(code: String) {
        if (code.isEmpty()) return
        appleMusicStorefront = code
        prefs?.edit()?.putString(KEY_STOREFRONT, code)?.apply()
    }

    internal fun cachedToken(): String? =
        prefs?.getString(KEY_TOKEN, null)?.takeIf { it.isNotEmpty() }

    internal fun cachedTokenExpiry(): Long =
        prefs?.getLong(KEY_TOKEN_EXP, 0L) ?: 0L

    internal fun saveToken(token: String, expirySeconds: Long) {
        prefs?.edit()
            ?.putString(KEY_TOKEN, token)
            ?.putLong(KEY_TOKEN_EXP, expirySeconds)
            ?.apply()
    }
}

private const val APPLE_ORIGIN = "https://music.apple.com"
private const val APPLE_API = "https://amp-api-edge.music.apple.com"
private const val APPLE_BROWSE = "$APPLE_ORIGIN/us/browse"

private const val APPLE_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

private fun _appleHeaders(token: String): Map<String, String> = mapOf(
    "User-Agent" to APPLE_UA,
    "Referer" to "$APPLE_ORIGIN/",
    "Origin" to APPLE_ORIGIN,
    "Accept" to "application/json",
    "Authorization" to "Bearer $token",
)

private val _browseHeaders = mapOf("User-Agent" to APPLE_UA)
private val _jwt = Regex("""eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""")
private val _scriptSrc = Regex("""src="([^"]*index[^"]*\.js)"""")

/**
 * 从 music.apple.com 的主脚本取 developer token；缓存到 SharedPreferences。
 *
 * **并发必须互斥**：歌手详情页会同时发好几个请求，token 缺失/过期时如果各自去抓，
 * 会把「浏览页 + 主脚本」（一两 MB）抓 N 份，首屏直接慢到几秒。这里串行化并做双检：
 * 排队进来的请求如果发现别人刚刷过，直接用新 token。
 */
private val _tokenMutex = Mutex()

/** 最近一次真正抓取 token 的时间（ms）；用于识别「排队期间别人已经刷好了」 */
@Volatile
private var _lastTokenFetchAt: Long = 0L

/** 缓存里的 token 是否还能用 [minRemainingSeconds] 秒以上 */
private fun _cachedToken(minRemainingSeconds: Long = 3 * 24 * 3600): String? {
    val now = System.currentTimeMillis() / 1000
    val cached = AppleMusicSettings.cachedToken()
    val exp = AppleMusicSettings.cachedTokenExpiry()
    if (!cached.isNullOrEmpty() && exp - now > minRemainingSeconds) return cached
    return null
}

private suspend fun _developerToken(forceRefresh: Boolean = false): String {
    if (!forceRefresh) _cachedToken()?.let { return it }

    val requestedAt = System.currentTimeMillis()
    return _tokenMutex.withLock {
        // 排队期间别的请求已经刷新过 → 直接复用，别再抓一遍浏览页与主脚本
        if (_lastTokenFetchAt >= requestedAt) {
            _cachedToken(minRemainingSeconds = 60)?.let { return@withLock it }
        }
        if (!forceRefresh) _cachedToken()?.let { return@withLock it }
        val token = _fetchDeveloperToken()
        _lastTokenFetchAt = System.currentTimeMillis()
        token
    }
}

/** 真正去 music.apple.com 抓 token（只在 [_tokenMutex] 内调用） */
private suspend fun _fetchDeveloperToken(): String {
    val now = System.currentTimeMillis() / 1000

    val browse = httpGetText(APPLE_BROWSE, _browseHeaders)
    val scriptPath = _scriptSrc.find(browse)?.groupValues?.getOrNull(1)
        ?: throw IllegalStateException("Apple developer token 脚本未找到")
    val scriptUrl = if (scriptPath.startsWith("http")) scriptPath else APPLE_ORIGIN + scriptPath
    val script = httpGetText(scriptUrl, _browseHeaders)

    data class Candidate(val token: String, val expiry: Long, val issuer: String)
    val candidates = _jwt.findAll(script).mapNotNull { match ->
        val token = match.value
        val payload = token.split('.').getOrNull(1) ?: return@mapNotNull null
        val decoded = runCatching {
            val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
            String(Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP))
        }.getOrNull() ?: return@mapNotNull null
        val json = runCatching { org.json.JSONObject(decoded) }.getOrNull() ?: return@mapNotNull null
        Candidate(token, json.optLong("exp", 0L), json.optString("iss"))
    }.toList()

    val picked = candidates.firstOrNull { it.issuer == "AMPWebPlay" }
        ?: candidates.maxByOrNull { it.expiry }
        ?: throw IllegalStateException("Apple developer token 未找到")
    if (picked.expiry <= now) throw IllegalStateException("Apple developer token 已过期")
    AppleMusicSettings.saveToken(picked.token, picked.expiry)
    return picked.token
}

/** 地区（storefront）。 */
data class AppleStorefront(
    val code: String,
    val name: String,
)

/** 歌手单曲结果。 */
data class AppleArtistSongs(
    val songs: List<Song>,
    val total: Int,
)

/** 专辑曲目结果。 */
data class AppleAlbumSongs(
    val songs: List<AlbumSong>,
    val pub: String,
)

// ── 目录：搜索 / 详情 ──

suspend fun fetchAppleStorefronts(): List<AppleStorefront> {
    val data = _appleGet(
        "/v1/storefronts",
        mapOf("limit" to "250", "l" to "zh-Hans"),
    )
    return _list(data["data"]).mapNotNull { raw ->
        val item = _map(raw)
        val code = _str(item["id"])
        if (code.isEmpty()) return@mapNotNull null
        AppleStorefront(code, _str(_map(item["attributes"])["name"]).ifEmpty { code.uppercase() })
    }.sortedBy { it.name.lowercase(Locale.ROOT) }
}

suspend fun searchAppleMusic(query: String, page: Int = 1, limit: Int = 30): List<Song> =
    _search("songs", "song", query, page, limit).mapNotNull(::_songOf)

suspend fun searchAppleAlbums(query: String, page: Int = 1, limit: Int = 30): List<Album> =
    _search("albums", "album", query, page, limit).mapNotNull(::_albumOf)

suspend fun searchAppleArtists(query: String): List<Artist> =
    _search("artists", "artist", query, 1, 30).mapNotNull(::_artistOf)

suspend fun getAppleAlbumSongs(albumId: String): AppleAlbumSongs {
    val id = _rawAppleId(albumId)
    val data = _appleGet(
        "/v1/catalog/$appleMusicStorefront/albums/$id",
        mapOf(
            "platform" to "web",
            "include" to "songs,artists",
            "extend" to "editorialArtwork,extendedAssetUrls",
            "l" to _catalogLang(),
        ),
    )
    val album = _map(_list(data["data"]).firstOrNull())
    val attrs = _map(album["attributes"])
    val tracks = _list(_map(_map(album["relationships"])["tracks"])["data"])
    return AppleAlbumSongs(
        songs = tracks.mapNotNull { _albumSongOf(it) },
        pub = _str(attrs["releaseDate"]),
    )
}

suspend fun getAppleArtistSongs(artistId: String): AppleArtistSongs {
    val id = _rawAppleId(artistId)
    val data = _appleGet(
        "/v1/catalog/$appleMusicStorefront/artists/$id",
        mapOf(
            "platform" to "web",
            "views" to "top-songs",
            "limit[artists:top-songs]" to "50",
            "l" to _catalogLang(),
        ),
    )
    val artist = _map(_list(data["data"]).firstOrNull())
    val view = _map(_map(artist["views"])["top-songs"])
    val songs = _list(view["data"]).mapNotNull(::_songOf)
    val total = _int(_map(view["meta"])["total"]).takeIf { it > 0 } ?: songs.size
    return AppleArtistSongs(songs, total)
}

/** 歌手发行物：Apple 把专辑、EP/单曲、最新发行分在不同 view。 */
data class AppleArtistReleases(
    val albums: List<Album>,
    val singles: List<Album>,
)

suspend fun getAppleArtistReleases(artistId: String): AppleArtistReleases {
    val id = _rawAppleId(artistId)
    // 一次请求取三个 view：full-albums / singles / latest-release 本来就是**同一个 artist
    // 资源**的不同 view，分成三个请求只是多付两次 RTT（首屏 6 个请求里有 3 个是它）。
    runCatching { _artistViews(id, RELEASE_VIEWS) }.getOrNull()?.let { views ->
        return buildReleases(
            albumItems = (views["full-albums"].orEmpty() + views["latest-release"].orEmpty())
                .mapNotNull(::_albumOf),
            singleItems = views["singles"].orEmpty().mapNotNull(::_albumOf),
        )
    }
    // 合并请求失败（接口不认多 view 等）→ 退回逐 view 并行，行为与以前一致
    return coroutineScope {
        val full = async { runCatching { _artistView(id, "full-albums") }.getOrDefault(emptyList()) }
        val singles = async { runCatching { _artistView(id, "singles") }.getOrDefault(emptyList()) }
        val latest = async { runCatching { _artistView(id, "latest-release") }.getOrDefault(emptyList()) }

        buildReleases(
            albumItems = (full.await() + latest.await()).mapNotNull(::_albumOf),
            singleItems = singles.await().mapNotNull(::_albumOf),
        )
    }
}

private val RELEASE_VIEWS = listOf("full-albums", "singles", "latest-release")

/** 专辑段与 EP/单曲段互斥：singles 里出现过的专辑不算「专辑」 */
private fun buildReleases(
    albumItems: List<Album>,
    singleItems: List<Album>,
): AppleArtistReleases {
    val singleIds = singleItems.map { it.albumid }.toSet()
    return AppleArtistReleases(
        albums = albumItems.filter { it.albumid !in singleIds }.distinctBy { it.albumid },
        singles = singleItems.distinctBy { it.albumid },
    )
}

/**
 * 一次请求取多个 view，返回 `view -> items`（各 view 的 `next` 分页各自续抓）。
 *
 * `limit[artists:full-albums]=100` 仍然只作用于 full-albums，与逐 view 请求时一致。
 */
private suspend fun _artistViews(
    artistId: String,
    views: List<String>,
): Map<String, List<Any?>> = coroutineScope {
    val params = buildMap {
        put("platform", "web")
        put("views", views.joinToString(","))
        put("l", _catalogLang())
        put("limit[artists:full-albums]", "100")
    }
    val data = _appleGet("/v1/catalog/$appleMusicStorefront/artists/$artistId", params)
    val artist = _map(_list(data["data"]).firstOrNull())
    val viewMap = _map(artist["views"])
    if (viewMap.isEmpty()) return@coroutineScope emptyMap()

    val pages = views.map { view ->
        async {
            val first = _map(viewMap[view])
            val items = _list(first["data"]).toMutableList()
            var next = _str(first["next"])
            var guard = 0
            while (next.isNotEmpty() && guard < 20) {
                val more = _map(_appleGet(next))
                items.addAll(_list(more["data"]))
                next = _str(more["next"])
                guard++
            }
            view to items.toList()
        }
    }
    pages.awaitAll().toMap()
}

suspend fun getAppleArtistAlbums(artistId: String): List<Album> {
    val releases = getAppleArtistReleases(artistId)
    return releases.singles + releases.albums
}

/**
 * 单曲搜索只有 `artistName`，没有歌手 id；打开歌曲操作面板前补一次详情，
 * 拿到 relationships.artists 后多歌手才能逐个选择、跳转也不再按名字搜索。
 */
suspend fun enrichAppleSong(song: Song): Song {
    if (song.platform != MusicPlatform.apple) return song
    val id = _rawAppleId(song.id)
    if (id.isEmpty()) return song
    return runCatching {
        val data = _appleGet(
            "/v1/catalog/$appleMusicStorefront/songs/$id",
            mapOf(
                "platform" to "web",
                "include" to "artists,albums",
                "l" to _catalogLang(),
            ),
        )
        val item = _map(_list(data["data"]).firstOrNull())
        val rel = _map(item["relationships"])
        val artistData = _list(_map(rel["artists"])["data"]).map { _map(it) }
        val names = artistData.map { _str(_map(it["attributes"])["name"]) }.filter { it.isNotEmpty() }
        val ids = artistData.map { _str(it["id"]) }.filter { it.isNotEmpty() }
        val album = _map(_list(_map(rel["albums"])["data"]).firstOrNull())
        song.copy(
            artistNames = names.ifEmpty { song.artistNames },
            artistIds = ids.ifEmpty { song.artistIds },
            albumId = _str(album["id"]).ifEmpty { song.albumId },
        )
    }.getOrDefault(song)
}

/**
 * 歌手资料的**一次**请求：头像（`editorialArtwork`）与简介（`artistBio` / `plainEditorialNotes`）
 * 都在同一个 `/artists/{id}` 上，只是 `extend` 不同。
 *
 * 详情页会并行调 [fetchAppleArtistAvatar] 与 [fetchAppleArtistBio]，这里用互斥 + 短缓存
 * 把两次调用合并成一次网络请求（后到的那个等前一个抓完再读同一份结果）。
 */
private val _artistProfileMutex = Mutex()
private const val ARTIST_PROFILE_TTL_MS = 60_000L
@Volatile
private var _artistProfileId: String? = null
@Volatile
private var _artistProfileAt: Long = 0L
@Volatile
private var _artistProfilePayload: Map<String, Any?>? = null

private suspend fun _artistProfile(artistId: String): Map<String, Any?> =
    _artistProfileMutex.withLock {
        val now = System.currentTimeMillis()
        val cached = _artistProfilePayload
        if (cached != null && _artistProfileId == artistId && now - _artistProfileAt < ARTIST_PROFILE_TTL_MS) {
            return@withLock cached
        }
        val data = _appleGet(
            "/v1/catalog/$appleMusicStorefront/artists/$artistId",
            mapOf(
                "platform" to "web",
                "extend" to "editorialArtwork,artistBio,plainEditorialNotes",
                "l" to _catalogLang(),
            ),
        )
        _artistProfileId = artistId
        _artistProfileAt = now
        _artistProfilePayload = data
        data
    }

suspend fun fetchAppleArtistAvatar(artistId: String, name: String = ""): String {
    val id = _rawAppleId(artistId)
    if (id.isNotEmpty()) {
        val data = runCatching { _artistProfile(id) }.getOrNull()
        val artist = _map(_list(data?.get("data")).firstOrNull())
        val pic = _artwork(_map(artist["attributes"])["artwork"])
        if (pic.isNotEmpty()) return pic
    }
    return runCatching { searchAppleArtists(name).firstOrNull()?.picture.orEmpty() }.getOrDefault("")
}

suspend fun fetchAppleArtistBio(artistId: String, name: String = ""): String {
    val id = _rawAppleId(artistId)
    if (id.isEmpty()) return ""
    return runCatching {
        val data = _artistProfile(id)
        val attrs = _map(_map(_list(data["data"]).firstOrNull())["attributes"])
        val bio = _str(attrs["artistBio"])
        if (bio.isNotEmpty()) bio else _str(_map(attrs["editorialNotes"])["standard"])
    }.getOrDefault("")
}

// ── 解析 ──

private suspend fun _artistView(artistId: String, view: String): List<Any?> {
    val params = buildMap {
        put("platform", "web")
        put("views", view)
        put("l", _catalogLang())
        if (view == "full-albums") put("limit[artists:full-albums]", "100")
    }
    val data = _appleGet("/v1/catalog/$appleMusicStorefront/artists/$artistId", params)
    val artist = _map(_list(data["data"]).firstOrNull())
    var page = _map(_map(artist["views"])[view])
    val items = _list(page["data"]).toMutableList()
    var next = _str(page["next"])
    var guard = 0
    while (next.isNotEmpty() && guard < 20) {
        val more = _appleGet(next)
        page = _map(more)
        items.addAll(_list(page["data"]))
        next = _str(page["next"])
        guard++
    }
    return items
}

private suspend fun _search(
    type: String,
    resultKey: String,
    query: String,
    page: Int,
    limit: Int,
): List<Any?> {
    val effectiveLimit = limit.coerceAtMost(25)
    val data = _appleGet(
        "/v1/catalog/$appleMusicStorefront/search",
        buildMap {
            put("term", query)
            put("types", type)
            put("limit", effectiveLimit.toString())
            put("offset", ((page - 1) * effectiveLimit).toString())
            put("platform", "web")
            put("with", "serverBubbles")
            put("omit[resource]", "autos")
            put("l", _catalogLang())
            if (type == "artists") put("include", "albums")
        },
    )
    val results = _map(data["results"])
    val node = _map(results[resultKey] ?: results[resultKey + "s"] ?: results[resultKey.removeSuffix("s")])
    return _list(node["data"])
}

private suspend fun _appleGet(path: String, params: Map<String, String?> = emptyMap()): Map<String, Any?> {
    val builder = Uri.parse(APPLE_API + path).buildUpon()
    for ((key, value) in params) {
        if (!value.isNullOrEmpty()) builder.appendQueryParameter(key, value)
    }
    val url = builder.build().toString()
    var token = _developerToken()
    return try {
        httpGetJson(url, _appleHeaders(token))
    } catch (e: Exception) {
        val message = e.message.orEmpty()
        if (message.contains("401") || message.contains("403")) {
            token = _developerToken(forceRefresh = true)
            httpGetJson(url, _appleHeaders(token))
        } else {
            throw e
        }
    }
}

private fun _songOf(raw: Any?): Song? {
    val item = _map(raw)
    val id = _str(item["id"])
    if (id.isEmpty()) return null
    val attrs = _map(item["attributes"])
    val rel = _map(item["relationships"])
    val albumRel = _map(_list(_map(rel["albums"])["data"]).firstOrNull())
    val artistData = _list(_map(rel["artists"])["data"]).map { _map(it) }
    val relNames = artistData.map { _str(_map(it["attributes"])["name"]) }.filter { it.isNotEmpty() }
    val artistNames = relNames.ifEmpty { _splitAppleArtists(_str(attrs["artistName"])) }
    val artistIds = artistData.map { _str(it["id"]) }.filter { it.isNotEmpty() }
    val albumArtist = _str(attrs["albumArtistName"])
    val artist = _str(attrs["artistName"])
        .ifEmpty { albumArtist }
        .ifEmpty { artistNames.joinToString(", ") }
    return Song(
        id = "apple:$id",
        title = _str(attrs["name"]).ifEmpty { "未知歌曲" },
        artist = artist.ifEmpty { "未知歌手" },
        cover = _artwork(attrs["artwork"]),
        album = _str(attrs["albumName"]).ifEmpty { null },
        albumId = _str(albumRel["id"]).ifEmpty { _albumIdFromUrl(_str(attrs["url"])) }.ifEmpty { null },
        duration = _int(attrs["durationInMillis"]) / 1000,
        platform = MusicPlatform.apple,
        qualities = _appleQualities(attrs),
        albumArtist = albumArtist.ifEmpty { artist }.ifEmpty { null },
        trackNumber = _int(attrs["trackNumber"]).takeIf { it > 0 }?.toString(),
        discNumber = _int(attrs["discNumber"]).takeIf { it > 0 }?.toString(),
        releaseDate = _str(attrs["releaseDate"]).ifEmpty { null },
        artistNames = if (artistNames.isNotEmpty()) artistNames else listOfNotNull(artist.ifEmpty { null }),
        artistIds = artistIds,
        composer = _str(attrs["composerName"]).ifEmpty { null },
        genre = _list(attrs["genreNames"]).mapNotNull { it as? String }.joinToString(" / ").ifEmpty { null },
        isrc = _str(attrs["isrc"]).ifEmpty { null },
    )
}

private fun _albumSongOf(raw: Any?): AlbumSong? {
    val item = _map(raw)
    val id = _str(item["id"])
    if (id.isEmpty()) return null
    val attrs = _map(item["attributes"])
    return AlbumSong(
        id = "apple:$id",
        name = _str(attrs["name"]).ifEmpty { "未知歌曲" },
        artistName = _str(attrs["artistName"]).ifEmpty { _str(attrs["albumArtistName"]) },
        duration = _int(attrs["durationInMillis"]) / 1000,
        track = _int(attrs["trackNumber"]).takeIf { it > 0 },
        discNumber = _int(attrs["discNumber"]).takeIf { it > 0 },
        albumArtist = _str(attrs["artistName"]).ifEmpty { null },
        qualities = _appleQualities(attrs),
        artistNames = _splitAppleArtists(_str(attrs["artistName"])),
        composer = _str(attrs["composerName"]).ifEmpty { null },
        genre = _list(attrs["genreNames"]).mapNotNull { it as? String }.joinToString(" / ").ifEmpty { null },
        isrc = _str(attrs["isrc"]).ifEmpty { null },
    )
}

private fun _albumOf(raw: Any?): Album? {
    val item = _map(raw)
    val id = _str(item["id"])
    if (id.isEmpty()) return null
    val attrs = _map(item["attributes"])
    val art = _artwork(attrs["artwork"])
    return Album(
        albumid = id,
        name = _str(attrs["name"]).ifEmpty { "未知专辑" },
        artist = _str(attrs["artistName"]).ifEmpty { "未知歌手" },
        platform = MusicPlatform.apple,
        musiccnt = _int(attrs["trackCount"]).toString(),
        pub = _str(attrs["releaseDate"]),
        pic = art,
        img = art,
        company = _str(attrs["recordLabel"]),
        lang = "",
        vip = "",
    )
}

private fun _artistOf(raw: Any?): Artist? {
    val item = _map(raw)
    val id = _str(item["id"])
    if (id.isEmpty()) return null
    val attrs = _map(item["attributes"])
    val albums = _list(_map(_map(item["relationships"])["albums"])["data"])
    val songCount = albums.sumOf { _int(_map(_map(it)["attributes"])["trackCount"]) }
        .takeIf { it > 0 } ?: albums.size
    return Artist(
        id = id,
        name = _str(attrs["name"]).ifEmpty { "未知歌手" },
        picture = _artwork(attrs["artwork"]),
        musicNum = songCount,
    )
}

private fun _artwork(raw: Any?): String {
    val url = _str(_map(raw)["url"])
    if (url.isEmpty()) return ""
    return url.replace("{w}", "600").replace("{h}", "600")
}

private fun _albumIdFromUrl(url: String): String =
    Regex("""/album/(?:[^/]+/)?(\d+)""").find(url)?.groupValues?.getOrNull(1).orEmpty()

private fun _rawAppleId(id: String): String = id.removePrefix("apple:")

private fun _catalogLang(): String = when (appleMusicStorefront) {
    "cn" -> "zh-Hans"
    "tw", "hk" -> "zh-Hant"
    "jp" -> "ja"
    "kr" -> "ko"
    "de" -> "de-DE"
    "fr" -> "fr-FR"
    "es" -> "es-ES"
    "it" -> "it"
    "br", "pt" -> "pt-BR"
    "ru" -> "ru"
    "us" -> "en-US"
    "gb" -> "en-GB"
    else -> "en-US"
}

private fun _map(value: Any?): Map<String, Any?> = value as? Map<String, Any?> ?: emptyMap()

private fun _list(value: Any?): List<Any?> = value as? List<Any?> ?: emptyList()

private fun _str(value: Any?): String = value as? String ?: ""

private fun _int(value: Any?): Int = (value as? Number)?.toInt() ?: 0

/** Apple 多歌手名通常用 `&` / `/` / `,` 连接；结构化 relationships 缺失时兜底拆分。 */
private fun _splitAppleArtists(raw: String): List<String> =
    raw.split(Regex("""\s*&\s*|\s*/\s*|,\s*"""))
        .map { it.trim() }
        .filter { it.isNotEmpty() }

private fun _appleQualities(attrs: Map<String, Any?>): List<Quality> {
    val traits = _list(attrs["audioTraits"]).mapNotNull { it as? String }
    val result = mutableListOf<Quality>()
    if (traits.contains("atmos")) result += Quality.atmos
    // 超高解析在无损之前：Apple 的这两档是同一编解码器的两个规格，高的是 hi-res
    if (traits.contains("hi-res-lossless")) result += Quality.hires
    if (traits.contains("lossless")) result += Quality.alac
    if (traits.contains("lossy-stereo") || result.isEmpty()) result += Quality.aac256
    return result.distinct()
}



