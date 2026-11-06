package com.annieplayer.annie_music.kt.data.sources.home

import com.annieplayer.annie_music.kt.data.model.HomeCardStyle
import com.annieplayer.annie_music.kt.data.model.HomeItem
import com.annieplayer.annie_music.kt.data.model.HomeShelf
import com.annieplayer.annie_music.kt.data.model.HomeTarget
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.network.cacheService
import com.annieplayer.annie_music.kt.data.network.httpGetJson
import com.annieplayer.annie_music.kt.data.sources.netease.neteaseArtistSong
import com.annieplayer.annie_music.kt.data.sources.common.decodeHtmlEntities
import com.annieplayer.annie_music.kt.data.sources.netease.neteaseApiHeaders
import com.annieplayer.annie_music.kt.data.sources.netease.neteaseQuery
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

// ================================================================================================
// 网易云主页（契约与调度见 HomeFeedApi.kt）
// ================================================================================================

/**
 * 「分类歌单」行用的标签。
 *
 * 都是官方存在的大类（`/api/playlist/list?cat=` 直接吃中文标签），取不到内容的分类会自动跳过，
 * 所以加错一个名字最多是多一次空请求、不会出现空行。
 */
internal val neteasePlaylistCats: List<String> = listOf("华语", "欧美", "流行", "摇滚", "民谣", "电子")

/** 分类行每行取多少张（UI 每行只画 10 张，多取没有意义）。 */
private const val NeteaseCatPlaylistsMax = 10

/**
 * 网易云首页：新歌 50 首 + 推荐歌单 30 个。
 *
 * 推荐歌单**不用**首页 block（`homepage/block/page` 里 `HOMEPAGE_BLOCK_PLAYLIST_RCMD`
 * 只有固定的 3 个运营位），改用 `personalized/playlist` —— 算法个性化，一次能给 30 个，
 * 而且响应只有几 KB（block 那个整包 388 KB）。
 */
internal suspend fun fetchHomeNetease(): List<HomeShelf> {
    val out = mutableListOf<HomeShelf>()

    // ── 新歌 50 首 ──
    val newsong = cachedNetease("newsong:$HomeSongsMax") {
        httpGetJson(
            "https://music.163.com/api/personalized/newsong?limit=$HomeSongsMax",
            neteaseApiHeaders,
        )
    }
    neteaseSongShelf(
        id = "netease:newsong",
        title = "新歌速递",
        subtitle = "为你推荐",
        raw = jsonList(newsong?.get("result")),
    )?.let { out += it }

    // ── 推荐歌单 30 个 ──
    val playlist = cachedNetease("playlist:$HomePlaylistsMax") {
        httpGetJson(
            "https://music.163.com/api/personalized/playlist?limit=$HomePlaylistsMax&total=true",
            neteaseApiHeaders,
        )
    }
    val playlistItems = neteasePlaylistCards(playlist?.get("result"))
    if (playlistItems.isNotEmpty()) {
        out += HomeShelf.Cards(
            id = "netease:playlist",
            title = "推荐歌单",
            subtitle = "根据你的口味",
            items = playlistItems.take(HomePlaylistsMax),
        )
    }

    // ── 分类歌单（华语 / 欧美 / 流行 / 摇滚 / 民谣 / 电子）──
    // 走 `/api/playlist/list?cat=`（免登录、6~9 KB/行）；六行**并行**取，各自缓存 10 分钟。
    // 以前只有一行「推荐歌单」，所有口味混在一起；分类行让「想听摇滚」的人一眼找到入口。
    val catRows = coroutineScope {
        neteasePlaylistCats.map { cat -> async { cat to neteaseCatPlaylists(cat) } }.awaitAll()
    }
    for ((cat, items) in catRows) {
        if (items.isEmpty()) continue
        out += HomeShelf.Cards(
            id = "netease:cat:$cat",
            title = cat,
            subtitle = "分类歌单",
            items = items,
        )
    }

    // ── 新专辑（`/api/album/new` 自带 `size` 曲目数与 `publishTime`，不用再补请求）──
    val newAlbums = cachedNetease("newalbums:$HomeCardsMax") {
        httpGetJson(
            "https://music.163.com/api/album/new?area=ALL&limit=$HomeCardsMax",
            neteaseApiHeaders,
        )
    }
    val albumItems = jsonList(newAlbums?.get("albums")).mapNotNull { raw ->
        val m = jsonMap(raw) ?: return@mapNotNull null
        val id = jsonStr(m["id"])
        val name = decodeHtmlEntities(jsonStr(m["name"]))
        if (id.isEmpty() || name.isEmpty()) return@mapNotNull null
        val artist = jsonList(m["artists"])
            .mapNotNull { a -> decodeHtmlEntities(jsonStr(jsonMap(a)?.get("name"))).takeIf { it.isNotEmpty() } }
            .joinToString(" / ")
        val cover = _neteaseCover(jsonStr(m["picUrl"]))
        HomeItem(
            key = "netease:album:$id",
            title = name,
            subtitle = artist,
            cover = cover,
            style = HomeCardStyle.Album,
            pub = _epochDate(jsonLong(m["publishTime"])),
            count = jsonLong(m["size"]).toInt(),
            target = HomeTarget.Album(MusicPlatform.netease, id, name, artist, cover),
        )
    }
    if (albumItems.isNotEmpty()) {
        out += HomeShelf.Cards(
            id = "netease:newalbum",
            title = "新专辑",
            subtitle = "刚刚发行",
            items = albumItems.take(HomeCardsMax),
        )
    }

    // ── 热门歌手（`/api/artist/list`，圆形头像卡）──
    val artists = cachedNetease("artists:$HomeCardsMax") {
        httpGetJson(
            "https://music.163.com/api/artist/list?type=1&area=7&initial=&limit=$HomeCardsMax",
            neteaseApiHeaders,
        )
    }
    val artistItems = jsonList(artists?.get("artists")).mapNotNull { raw ->
        val m = jsonMap(raw) ?: return@mapNotNull null
        val id = jsonStr(m["id"])
        val name = decodeHtmlEntities(jsonStr(m["name"]))
        if (id.isEmpty() || name.isEmpty()) return@mapNotNull null
        val cover = _neteaseCover(jsonStr(m["picUrl"]))
        val albumSize = jsonLong(m["albumSize"])
        HomeItem(
            key = "netease:artist:$id",
            title = name,
            subtitle = if (albumSize > 0L) "$albumSize 张专辑" else "",
            cover = cover,
            style = HomeCardStyle.Artist,
            target = HomeTarget.Artist(MusicPlatform.netease, id, name, cover),
        )
    }
    if (artistItems.isNotEmpty()) {
        out += HomeShelf.Cards(
            id = "netease:artist",
            title = "热门歌手",
            subtitle = "大家都在追",
            items = artistItems.take(HomeCardsMax),
        )
    }

    return out
}

private suspend fun cachedNetease(
    suffix: String,
    fetch: suspend () -> Map<String, Any?>,
): Map<String, Any?>? {
    val key = HomeCachePrefix + "netease:" + suffix
    cacheService.get<Map<String, Any?>>(key)?.let { return it }
    val fresh = runCatching { fetch() }.getOrNull() ?: return null
    if (fresh.isNotEmpty()) cacheService.set(key, fresh, HomeCacheTtlMs)
    return fresh
}

/**
 * 某个分类下的热门歌单（`/api/playlist/list?cat=`，免登录）。
 *
 * 注意与 `personalized/playlist` 的**字段差异**：分类接口给的是 `coverImgUrl`，
 * 个性化接口给的是 `picUrl` —— 两个都要认，否则分类行的封面全空。
 */
private suspend fun neteaseCatPlaylists(cat: String): List<HomeItem> {
    val query = neteaseQuery(
        listOf(
            "cat" to cat,
            "order" to "hot",
            "offset" to "0",
            "limit" to NeteaseCatPlaylistsMax.toString(),
            "total" to "true",
        ),
    )
    val resp = cachedNetease("cat:$cat") {
        httpGetJson("https://music.163.com/api/playlist/list?$query", neteaseApiHeaders)
    } ?: return emptyList()
    return neteasePlaylistCards(resp["playlists"]).take(NeteaseCatPlaylistsMax)
}

/**
 * 歌单原始条目 → 主页卡片（推荐行与分类行共用；两处字段名不同，故都认）。
 *
 * 纯函数，可单测 —— 封面 500 规整、播放量角标、副标题「x 播放 · y 首」都在这里定。
 */
internal fun neteasePlaylistCards(raw: Any?): List<HomeItem> =
    jsonList(raw).mapNotNull { item ->
        val m = jsonMap(item) ?: return@mapNotNull null
        val id = jsonStr(m["id"])
        val title = decodeHtmlEntities(jsonStr(m["name"]))
        if (id.isEmpty() || title.isEmpty()) return@mapNotNull null
        // `personalized/playlist` 用 picUrl，`playlist/list` 用 coverImgUrl
        val cover = _neteaseCover(jsonStr(m["picUrl"]).ifEmpty { jsonStr(m["coverImgUrl"]) })
        val playCount = jsonLong(m["playCount"])
        val trackCount = jsonLong(m["trackCount"])
        HomeItem(
            key = "netease:playlist:$id",
            title = title,
            subtitle = playlistSubtitle(playCount, trackCount),
            cover = cover,
            badge = formatCount(playCount).takeIf { it.isNotEmpty() },
            style = HomeCardStyle.Playlist,
            target = HomeTarget.Playlist(MusicPlatform.netease, id, title, cover),
        )
    }

/** 网易云歌曲条目（`personalized/newsong` 的 `result[]`，或首页 block 的 `resourceExtInfo.song`）。 */
private fun neteaseSongShelf(
    id: String,
    title: String,
    subtitle: String,
    raw: List<Any?>,
): HomeShelf.Songs? {
    val songs = raw.mapNotNull { item ->
        val m = jsonMap(item) ?: return@mapNotNull null
        // `personalized/newsong` 外面还包一层 { id, name, picUrl, song: {...} }
        val inner = jsonMap(m["song"]) ?: m
        // id / name 必须真实存在，否则会生成 `netease:null` 这种点进去必然失败的条目
        if (jsonStr(inner["id"]).isEmpty()) return@mapNotNull null
        if (jsonStr(inner["name"]).isEmpty()) return@mapNotNull null
        neteaseArtistSong(inner)
    }.distinctBy { it.id }.take(HomeSongsMax)
    if (songs.isEmpty()) return null
    return HomeShelf.Songs(id = id, title = title, subtitle = subtitle, songs = songs)
}

/** 网易云封面统一提到 500×500（列表里最大 168dp，500 足够且比 800 省流量）。 */
private fun _neteaseCover(url: String): String {
    if (url.isEmpty()) return ""
    return if (url.contains("?")) "${url}&param=500y500" else "$url?param=500y500"
}

/**
 * 毫秒时间戳 → `yyyy-MM-dd`（网易云的 `publishTime` 是 epoch 毫秒）。
 *
 * 固定东八区：曲库的发行日期按国内口径显示，不希望跟随系统时区在午夜前后跳一天。
 */
private fun _epochDate(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    return runCatching {
        java.time.Instant.ofEpochMilli(epochMs)
            .atZone(java.time.ZoneOffset.ofHours(8))
            .toLocalDate()
            .toString()
    }.getOrDefault("")
}
