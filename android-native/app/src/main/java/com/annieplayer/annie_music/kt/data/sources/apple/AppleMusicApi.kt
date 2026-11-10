package com.annieplayer.annie_music.kt.data.sources.apple

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
import com.annieplayer.annie_music.kt.data.network.CacheService
import com.annieplayer.annie_music.kt.data.network.httpGetJson
import com.annieplayer.annie_music.kt.data.network.httpGetText
import com.annieplayer.annie_music.kt.data.network.retryUntilSuccess
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

    // 浏览页 → 主脚本（约 3MB）→ 正则找 JWT。这条链路是**冷启动最容易抖的一步**，
    // 抓一次失败就整条 Apple 目录（含地区表）全挂，所以第一次失败立刻重来一次。
    // 返回值必须是**脚本内容本身**：这里曾经写成 `scriptUrl to httpGetText(...)`，解构成
    // `(script, _)` 就把 URL 当正文传下去，正则永远扫不到 JWT（报「token 未找到」）。
    val script = retryUntilSuccess(maxRetries = 2, delayMs = 700L) {
        val browse = httpGetText(APPLE_BROWSE, _browseHeaders)
        val scriptPath = _scriptSrc.find(browse)?.groupValues?.getOrNull(1)
            ?: throw IllegalStateException("Apple developer token 脚本未找到（浏览页 ${browse.length} 字符）")
        val scriptUrl = if (scriptPath.startsWith("http")) scriptPath else APPLE_ORIGIN + scriptPath
        httpGetText(scriptUrl, _browseHeaders)
    }

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
        ?: throw IllegalStateException("Apple developer token 未找到（脚本 ${script.length} 字符）")
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

/**
 * `view/top-songs` 的一页。
 *
 * [hasMore] 取自响应里有没有 `next` —— 末页没有；`offset` 越过末尾直接返回 404，
 * 那种情况在这里被吞成「没有更多」。
 */
data class AppleArtistSongsPage(
    val songs: List<Song>,
    val hasMore: Boolean,
)

/**
 * 歌手曲目视图单页上限。**101 就 400**（实测），不是 100 只是个巧合数字。
 */
internal const val AppleArtistSongsPageLimit = 100

/**
 * `fields[songs]=` 投影：只留 [appleSongOf] 真正会读的属性。
 *
 * 实测单页从 125 KB 压到 79.6 KB。**改 [appleSongOf] 读新属性时必须同步加到这里**，
 * 否则该字段会静默变空（`AppleSongFieldsTest` 盯着这件事）。
 */
internal const val AppleSongFields =
    "name,artistName,albumArtistName,albumName,artwork,durationInMillis,trackNumber," +
        "discNumber,releaseDate,composerName,genreNames,isrc,url,audioTraits"

/** 专辑曲目结果。 */
data class AppleAlbumSongs(
    val songs: List<AlbumSong>,
    val pub: String,
)

// ── 目录：搜索 / 详情 ──

/**
 * 地区表（国家/地区选择面板的数据源）。
 *
 * **必须自带重试**：这张表只在「切到 Apple 平台」和「打开地区面板」时拉，一次失败就再也没有
 * 第二次机会 —— 表现就是「国家选择弹出来是空的，切换平台再切回来又好了」。
 * 冷启动时这条请求还会和 token 抓取、首页 feed 抢带宽，一次超时并不稀奇。
 */
suspend fun fetchAppleStorefronts(): List<AppleStorefront> {
    val data = retryUntilSuccess(maxRetries = 3, delayMs = 600L) {
        _appleGet(
            "/v1/storefronts",
            mapOf("limit" to "250", "l" to "zh-Hans"),
        )
    }
    return _list(data["data"]).mapNotNull { raw ->
        val item = _map(raw)
        val code = _str(item["id"])
        if (code.isEmpty()) return@mapNotNull null
        AppleStorefront(code, _str(_map(item["attributes"])["name"]).ifEmpty { code.uppercase() })
    }.sortedBy { it.name.lowercase(Locale.ROOT) }
}

suspend fun searchAppleMusic(query: String, page: Int = 1, limit: Int = 30): List<Song> =
    _search("songs", "song", query, page, limit).mapNotNull(::appleSongOf)

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
            "include" to "tracks,artists",
            // 曲目自己的 relationships 不被顶层 include 覆盖，要单独用**嵌套 include** 点名。
            // 少了它，专辑页每首歌只有合并后的 `artistName`，多歌手就没法逐个选。
            "include[albums:tracks]" to "artists",
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

/** Apple 歌单（catalog playlist）的元数据 + 曲目。 */
data class ApplePlaylist(
    val name: String,
    val curator: String,
    val cover: String,
    val songs: List<Song>,
)

/**
 * 歌单详情（`/v1/catalog/{sf}/playlists/{id}`）。
 *
 * 只覆盖**公开的 catalog 歌单**（`pl.xxx`）；用户自己的资料库歌单要登录，catalog 里没有。
 * 曲目用 [_paged] 翻完 —— 歌单动辄上百首，一页默认 100 条。
 */
suspend fun getApplePlaylist(playlistId: String): ApplePlaylist {
    val id = _rawAppleId(playlistId)
    val data = _appleGet(
        "/v1/catalog/$appleMusicStorefront/playlists/$id",
        mapOf(
            "platform" to "web",
            "include" to "tracks",
            // 与专辑同理：曲目自己的 relationships 不被顶层 include 覆盖，要嵌套点名
            "include[playlists:tracks]" to "artists",
            "limit[playlists:tracks]" to "100",
            "l" to _catalogLang(),
        ),
    )
    val item = _map(_list(data["data"]).firstOrNull())
    val attrs = _map(item["attributes"])
    val tracks = _paged(_map(_map(item["relationships"])["tracks"]))
    return ApplePlaylist(
        name = _str(attrs["name"]),
        curator = _str(attrs["curatorName"]),
        cover = _artwork(attrs["artwork"]),
        songs = tracks.mapNotNull(::appleSongOf),
    )
}

/**
 * 专辑级版权声明（`albums/{id}.attributes.copyright`）。
 *
 * Apple 的**歌曲**对象里没有 copyright —— 它挂在专辑上。所以下载一首歌想写版权，就得问一次专辑。
 * 按 albumId 缓存 24 小时：一张专辑通常十几首，等于整张只多一次请求。
 *
 * @return 「℗ 2024 xxx」这类原文；拿不到返回 null（宁愿不写，也不写错）
 */
suspend fun fetchAppleAlbumCopyright(albumId: String?): String? {
    val id = _rawAppleId(albumId.orEmpty())
    if (id.isEmpty()) return null
    val cacheKey = "apple:albumcopyright:$id"
    CacheService.get<String>(cacheKey)?.takeIf { it.isNotEmpty() }?.let { return it }
    return runCatching {
        val data = _appleGet(
            "/v1/catalog/$appleMusicStorefront/albums/$id",
            mapOf("platform" to "web", "l" to _catalogLang()),
        )
        val attrs = _map(_map(_list(data["data"]).firstOrNull())["attributes"])
        _str(attrs["copyright"]).takeIf { it.isNotEmpty() }
    }.getOrNull()?.also { CacheService.set(cacheKey, it, ALBUM_COPYRIGHT_TTL_MS) }
}

private const val ALBUM_COPYRIGHT_TTL_MS = 24 * 60 * 60 * 1000L

/**
 * 歌手曲目分页（`/v1/catalog/{sf}/artists/{id}/view/top-songs`）。
 *
 * 旧的嵌入形态 `artists/{id}?views=top-songs&limit[artists:top-songs]=50` **上限 50**；
 * 独立视图形态支持 `limit`（硬上限 [AppleArtistSongsPageLimit]）+ `offset`，能一路翻到该歌手的
 * 全部曲目 —— 实测 Taylor Swift 678 首（7 页）、Canozan 75 首（1 页）。
 *
 * 三个必须知道的边界：
 * - `offset` 越过末尾返回 **404**（不是空数组），这里吞成「没有更多」；
 * - 末页响应**不带 `next`**，`hasMore` 就靠它判断；
 * - 没有 `total` 字段，想要「共 N 首」只能一直翻到底。
 *
 * 不加 `relate[songs]=artists`：它只给 id 不给 name（+17 KB/页），多歌手仍由
 * [_fillAppleSongArtists] 补 —— 那一支走 `songs?ids=…&include=artists` 才拿得到名字。
 */
suspend fun getAppleArtistSongsPage(
    artistId: String,
    offset: Int,
    limit: Int = AppleArtistSongsPageLimit,
): AppleArtistSongsPage {
    val id = _rawAppleId(artistId)
    val resp = runCatching {
        _appleGet(
            "/v1/catalog/$appleMusicStorefront/artists/$id/view/top-songs",
            mapOf(
                "limit" to limit.coerceIn(1, AppleArtistSongsPageLimit).toString(),
                "offset" to offset.coerceAtLeast(0).toString(),
                "fields[songs]" to AppleSongFields,
            ),
        )
    }.getOrNull() ?: return AppleArtistSongsPage(emptyList(), false)
    val songs = _list(resp["data"]).mapNotNull(::appleSongOf)
    return AppleArtistSongsPage(songs, hasMore = _str(resp["next"]).isNotEmpty())
}

/**
 * 歌手「热门曲目」首屏（一页 100 首）。
 *
 * 这里**不报 `total`**：`view/top-songs` 没有总数，而 `artists/{id}` 的 `meta.total` 是
 * 全站曲目数（会显示「共 300 首」却只能翻 50 首）。所以 `total` 就是本页拿到的条数，
 * 「还有更多」由 [getAppleArtistSongsPage] 的 `hasMore` 表达。
 */
suspend fun getAppleArtistSongs(artistId: String): AppleArtistSongs {
    val page = getAppleArtistSongsPage(artistId, offset = 0)
    val songs = _fillAppleSongArtists(page.songs)
    return AppleArtistSongs(songs, songs.size)
}

/**
 * 给「歌手热门歌曲」补多歌手结构。
 *
 * `top-songs` 这一路**不返回 `relationships.artists`**（实测带
 * `include[artists:top-songs]=artists` 也一样），曲目只有合并后的 `artistName`：
 * 以 Tyla 为例，50 首里有 20 首是 `Tyla, Gunna & Skillibeng` 这种串。
 * 拿不到 id 的后果是点歌手只能整串去搜索，跳不到 Gunna / Skillibeng。
 *
 * 补法是一次批量：`/v1/catalog/{sf}/songs?ids=a,b,c…&include=artists`
 * （实测 3 首一次请求就能拿到 `1552925732=Tyla, 1236267297=Gunna, 1036011142=Skillibeng`）。
 * 一页 100 个 id 的 URL 约 1100 字符，一个 RTT；`fields[songs]=id` + `fields[artists]=name`
 * 把这条响应从 457 KB 压到 255 KB（实测）。失败就原样返回 —— 不能因为这步让歌手页开不出来。
 *
 * 只对**首屏那一页**调用：后面续拉的页各自再补一次就是每页多一个 RTT，不值；
 * 那些行拿不到结构化歌手时由 `DetailNavigator.openArtistDetail` 的 `enrichAppleSong` 按需补。
 */
private suspend fun _fillAppleSongArtists(songs: List<Song>): List<Song> {
    if (songs.isEmpty()) return songs
    val ids = songs.joinToString(",") { it.id.removePrefix("apple:") }
    val resp = runCatching {
        _appleGet(
            "/v1/catalog/$appleMusicStorefront/songs",
            mapOf(
                "ids" to ids,
                "include" to "artists",
                "fields[songs]" to "id",
                "fields[artists]" to "name",
                "platform" to "web",
                "l" to _catalogLang(),
            ),
        )
    }.getOrNull() ?: return songs

    val resolved = HashMap<String, Pair<List<String>, List<String>>>(songs.size)
    for (raw in _list(resp["data"])) {
        val item = _map(raw)
        val sid = _str(item["id"])
        if (sid.isEmpty()) continue
        val pairs = _list(_map(_map(item["relationships"])["artists"])["data"])
            .map { _map(it) }
            .map { _str(it["id"]) to _str(_map(it["attributes"])["name"]) }
            .filter { (artistId, artistName) -> artistId.isNotEmpty() && artistName.isNotEmpty() }
        if (pairs.isEmpty()) continue
        resolved[sid] = pairs.map { it.second } to pairs.map { it.first }
    }
    if (resolved.isEmpty()) return songs
    return songs.map { song ->
        val pair = resolved[song.id.removePrefix("apple:")] ?: return@map song
        song.copy(artistNames = pair.first, artistIds = pair.second)
    }
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
    return _paged(_map(_map(artist["views"])[view]))
}

/** 取完一个带 `next` 的资源列表（`data` + 连续翻页，上限 20 页） */
private suspend fun _paged(node: Map<String, Any?>): List<Any?> {
    val items = _list(node["data"]).toMutableList()
    var next = _str(node["next"])
    var guard = 0
    while (next.isNotEmpty() && guard < 20) {
        val page = _appleGet(next)
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
            // 搜索结果的歌曲默认**只有合并后的 `artistName`**。多歌手在合并串里是拿不回来的：
            // `Lucas & Steve & Blackstreet` 里 `&` 既是连接符、又是乐队名的一部分，
            // 按分隔符拆只会拆出三个假歌手。要结构就得让它把 relationships 一起带来。
            if (type == "songs") put("include[songs]", "artists")
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

internal fun appleSongOf(raw: Any?): Song? {
    val item = _map(raw)
    val id = _str(item["id"])
    if (id.isEmpty()) return null
    val attrs = _map(item["attributes"])
    val rel = _map(item["relationships"])
    val albumRel = _map(_list(_map(rel["albums"])["data"]).firstOrNull())
    val artistData = _list(_map(rel["artists"])["data"]).map { _map(it) }
    // id 与 name 必须**成对**过滤：有的歌手资源没有本地化 name（实测 `FIFA Sound`
    // 在 `Tyla, Gunna & Skillibeng` 之外的那首里 id 非空、name 为空串）。
    // 两边各过滤一次会让数量错位，下游「数量对不上就整组丢掉」直接把多歌手变成没有。
    val artistPairs = artistData
        .map { _str(it["id"]) to _str(_map(it["attributes"])["name"]) }
        .filter { (artistId, artistName) -> artistId.isNotEmpty() && artistName.isNotEmpty() }
    val relNames = artistPairs.map { it.second }
    val artistIds = artistPairs.map { it.first }
    val albumArtist = _str(attrs["albumArtistName"])
    val artist = _str(attrs["artistName"]).ifEmpty { albumArtist }
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
        // **只有 relationships 有值时才给列表**，绝不再按分隔符硬拆 `artistName`：
        // 「Lucas & Steve」本身就是带 `&` 的乐队名，拆出来就是假的。
        // 拿不到结构时留空，由 [enrichAppleSong] 在真正需要逐个选歌手/写标签时补一次。
        artistNames = relNames,
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
    // 曲目自己的 relationships.artists 靠 `include[albums:tracks]=artists` 带过来
    val artistData = _list(_map(_map(item["relationships"])["artists"])["data"]).map { _map(it) }
    val pairs = artistData
        .map { _str(it["id"]) to _str(_map(it["attributes"])["name"]) }
        .filter { (artistId, artistName) -> artistId.isNotEmpty() && artistName.isNotEmpty() }
    val names = pairs.map { it.second }
    val ids = pairs.map { it.first }
    return AlbumSong(
        id = "apple:$id",
        name = _str(attrs["name"]).ifEmpty { "未知歌曲" },
        artistName = _str(attrs["artistName"]).ifEmpty { _str(attrs["albumArtistName"]) },
        duration = _int(attrs["durationInMillis"]) / 1000,
        track = _int(attrs["trackNumber"]).takeIf { it > 0 },
        discNumber = _int(attrs["discNumber"]).takeIf { it > 0 },
        albumArtist = _str(attrs["artistName"]).ifEmpty { null },
        qualities = _appleQualities(attrs),
        artistNames = names,
        artistIds = ids,
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



