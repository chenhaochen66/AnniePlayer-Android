package com.annieplayer.annie_music.kt.data.sources.home

import com.annieplayer.annie_music.kt.data.model.HomeCardStyle
import com.annieplayer.annie_music.kt.data.model.HomeItem
import com.annieplayer.annie_music.kt.data.model.HomeShelf
import com.annieplayer.annie_music.kt.data.model.HomeTarget
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.network.cacheService
import com.annieplayer.annie_music.kt.data.network.httpGetJson
import com.annieplayer.annie_music.kt.data.network.httpPostJson
import com.annieplayer.annie_music.kt.data.sources.qq.qqArtistSong
import com.annieplayer.annie_music.kt.data.sources.common.decodeHtmlEntities
import com.annieplayer.annie_music.kt.data.sources.qq.qqHeaders
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

// ================================================================================================
// QQ 音乐主页（契约与调度见 HomeFeedApi.kt）
// ================================================================================================

/**
 * QQ 音乐首页：一次 `musicu.fcg` 请求同时取四个模块。
 *
 * `musicu.fcg` 的协议就是「一个 POST 里塞多个 module」，响应按**键名**原样返回 —— 拆成四次
 * 请求只会多三次 RTT。四个模块互不依赖，任何一块失败只影响它自己那一个区块。
 *
 * 歌单给了两段（不是重复）：`v_hot` 是「大家都在听」的热门推荐（12 个），
 * `PlayListPlazaServer` 是官方歌单广场里的一页（共 736 个，`order=5` 按最新）。
 * 两段来源不同、内容不重叠，主页放两行才不会一眼看到底。
 */
internal suspend fun fetchHomeQQ(): List<HomeShelf> {
    val body: Map<String, Any?> = mapOf(
        "comm" to mapOf("ct" to 24, "cv" to 0),
        "recomPlaylist" to mapOf(
            "module" to "playlist.HotRecommendServer",
            "method" to "get_hot_recommend",
            "param" to mapOf("async" to 1, "cmd" to 2),
        ),
        "playlistPlaza" to mapOf(
            "module" to "playlist.PlayListPlazaServer",
            "method" to "get_playlist_by_category",
            "param" to mapOf(
                // 3317 = 官方歌单（其余：59 经典 / 71 情歌 / 3056 网络歌曲 / 64 KTV 热歌）
                "id" to 3317,
                "curPage" to 1,
                "size" to HomePlaylistsMax,
                "order" to 5,
                "titleid" to 3317,
            ),
        ),
        "new_album" to mapOf(
            "module" to "newalbum.NewAlbumServer",
            "method" to "get_new_album_info",
            "param" to mapOf("area" to 1, "sin" to 0, "num" to HomeCardsMax),
        ),
        "new_song" to mapOf(
            "module" to "newsong.NewSongServer",
            "method" to "get_new_song_info",
            "param" to mapOf("type" to 5),
        ),
        "singerList" to mapOf(
            "module" to "Music.SingerListServer",
            "method" to "get_singer_list",
            // -100 = 全部（地区 / 性别 / 流派 / 索引都不筛）
            "param" to mapOf(
                "area" to -100,
                "sex" to -100,
                "genre" to -100,
                "index" to -100,
                "sin" to 0,
                "cur_page" to 1,
            ),
        ),
        // 同一份歌单广场再取两个分类：71 情歌 / 59 经典老歌
        "plazaLove" to qqPlazaModule(71),
        "plazaClassic" to qqPlazaModule(59),
    )

    val key = HomeCachePrefix + "qq"
    val resp = cacheService.get<Map<String, Any?>>(key) ?: run {
        val fresh = runCatching {
            httpPostJson("https://u.y.qq.com/cgi-bin/musicu.fcg", qqHeaders, body)
        }.getOrNull()
        if (fresh != null && fresh.isNotEmpty()) cacheService.set(key, fresh, HomeCacheTtlMs)
        fresh
    } ?: return emptyList()

    val out = mutableListOf<HomeShelf>()

    // ── 新歌（接口固定 40 首，有多少显示多少）──
    val newSongs = jsonList(jsonMap(resp["new_song"])?.let { jsonMap(it["data"]) }?.get("songlist"))
    val songs = newSongs.mapNotNull { raw ->
        val m = jsonMap(raw) ?: return@mapNotNull null
        if (jsonStr(m["mid"]).isEmpty()) return@mapNotNull null
        qqArtistSong(m)
    }.distinctBy { it.id }.take(HomeSongsMax)
    if (songs.isNotEmpty()) {
        out += HomeShelf.Songs(
            id = "qq:newsong",
            title = "新歌速递",
            subtitle = "本周上架",
            songs = songs,
        )
    }

    // ── 推荐歌单（v_hot，热门推荐）──
    val hot = jsonList(jsonMap(resp["recomPlaylist"])?.let { jsonMap(it["data"]) }?.get("v_hot"))
    val hotItems = hot.mapNotNull { raw ->
        val m = jsonMap(raw) ?: return@mapNotNull null
        val id = jsonStr(m["content_id"])
        val title = decodeHtmlEntities(jsonStr(m["title"]))
        if (id.isEmpty() || title.isEmpty()) return@mapNotNull null
        // qpic 只认 150/300/600（400/500/800 一律 400 错误），168dp 卡在 3x 屏要 504px → 600
        val cover = jsonStr(m["cover"]).replace("/300?", "/600?")
        val listenNum = jsonLong(m["listen_num"])
        HomeItem(
            key = "qq:hot:$id",
            title = title,
            subtitle = playlistSubtitle(listenNum, 0),
            cover = cover,
            badge = formatCount(listenNum).takeIf { it.isNotEmpty() },
            style = HomeCardStyle.Playlist,
            target = HomeTarget.Playlist(MusicPlatform.qq, id, title, cover),
        )
    }
    if (hotItems.isNotEmpty()) {
        out += HomeShelf.Cards(
            id = "qq:hot",
            title = "大家都在听",
            subtitle = "热门歌单",
            items = hotItems.take(HomePlaylistsMax),
        )
    }

    // ── 官方歌单广场（PlayListPlazaServer）：官方 / 情歌 / 经典老歌 三个分类 ──
    qqPlazaShelf(resp, "playlistPlaza", "qq:plaza", "官方精选", "每周更新")?.let { out += it }
    qqPlazaShelf(resp, "plazaLove", "qq:plazaLove", "情歌", "把心事唱给谁听")?.let { out += it }
    qqPlazaShelf(resp, "plazaClassic", "qq:plazaClassic", "经典老歌", "越听越有味")?.let { out += it }

    // ── 热门歌手（圆形头像卡）──
    val singers = jsonList(jsonMap(resp["singerList"])?.let { jsonMap(it["data"]) }?.get("singerlist"))
    val singerItems = singers.mapNotNull { raw ->
        val m = jsonMap(raw) ?: return@mapNotNull null
        // 歌手详情（GetSingerSongList / fcg_v8_singer_album）只认 singerMid，数字 id 打不开
        val mid = jsonStr(m["singer_mid"])
        val name = decodeHtmlEntities(jsonStr(m["singer_name"]))
        if (mid.isEmpty() || name.isEmpty()) return@mapNotNull null
        val cover = jsonStr(m["singer_pic"])
        HomeItem(
            key = "qq:artist:$mid",
            title = name,
            subtitle = "",
            cover = cover,
            style = HomeCardStyle.Artist,
            target = HomeTarget.Artist(MusicPlatform.qq, mid, name, cover),
        )
    }
    if (singerItems.isNotEmpty()) {
        out += HomeShelf.Cards(
            id = "qq:artist",
            title = "热门歌手",
            subtitle = "大家都在追",
            items = singerItems.take(HomeCardsMax),
        )
    }

    // ── 新专辑 ──
    val albums = jsonList(jsonMap(resp["new_album"])?.let { jsonMap(it["data"]) }?.get("albums"))
    val albumRaw = albums.mapNotNull { raw ->
        val m = jsonMap(raw) ?: return@mapNotNull null
        // ⚠️ 专辑详情（fcg_v8_album_info_cp）只认 albummid，数字 id 打不开
        val mid = jsonStr(m["mid"])
        val name = decodeHtmlEntities(jsonStr(m["name"]))
        if (mid.isEmpty() || name.isEmpty()) return@mapNotNull null
        val singers = jsonList(m["singers"]).mapNotNull { s ->
            decodeHtmlEntities(jsonStr(jsonMap(s)?.get("name"))).takeIf { it.isNotEmpty() }
        }
        val artist = singers.joinToString(" / ")
        // 注意：这个接口的 `photo` 不是 URL，而是 `{ pic_mid, has_photo, ... }` 对象。
        // 封面按 albummid 拼即可（500 已实测可用；800 单张 305KB，主页 20 张太重）。
        val cover = "https://y.gtimg.cn/music/photo_new/T002R500x500M000$mid.jpg"
        // 这个接口**不给曲目数**（`ex.track_nums` 恒为 0），发行日期给的是 `release_time`
        mid to HomeItem(
            key = "qq:album:$mid",
            title = name,
            subtitle = artist,
            cover = cover,
            badge = null,
            style = HomeCardStyle.Album,
            pub = jsonStr(m["release_time"]),
            target = HomeTarget.Album(MusicPlatform.qq, mid, name, artist, cover),
        )
    }
    if (albumRaw.isNotEmpty()) {
        // 曲目数只能逐张补：只补「会上卡面」的前 HomeVisibleAlbumCount 张，2.5s 内拿不到就留空
        val counts = withTimeoutOrNull(2500L) {
            coroutineScope {
                albumRaw.take(HomeVisibleAlbumCount).map { (mid, _) ->
                    async { mid to qqAlbumTrackCount(mid) }
                }.awaitAll()
            }
        }.orEmpty().toMap()
        out += HomeShelf.Cards(
            id = "qq:newalbum",
            title = "新专辑",
            subtitle = "刚刚发行",
            items = albumRaw
                .map { (mid, item) -> item.copy(count = counts[mid] ?: 0) }
                .take(HomeCardsMax),
        )
    }

    return out
}

/**
 * 单张 QQ 专辑的曲目数（`fcg_v8_album_info_cp` 的 `data.total_song_num`）。
 *
 * `new_album` 模块本身不带曲目数，只能逐张补。做法：
 * - **只补卡面上会出现的那几张**（[HomeVisibleAlbumCount]），不是全部 20 张；
 * - 并发 + 2.5s 总超时（见调用点），拿不到就留 0（卡片少画一段，不会卡住首屏）；
 * - 单张结果按 albummid 缓存 24 小时（专辑曲目数几乎不变）。
 */
private suspend fun qqAlbumTrackCount(albumMid: String): Int {
    val key = HomeCachePrefix + "qq:albumcount:$albumMid"
    (cacheService.get<Number>(key))?.toInt()?.takeIf { it > 0 }?.let { return it }
    val count = runCatching {
        val data = httpGetJson(
            "https://c.y.qq.com/v8/fcg-bin/fcg_v8_album_info_cp.fcg" +
                "?albummid=$albumMid&format=json&inCharset=utf8&outCharset=utf-8" +
                "&notice=0&platform=yqq.json&needNewCode=0",
            qqHeaders,
        )
        jsonLong(jsonMap(data["data"])?.get("total_song_num")).toInt()
    }.getOrDefault(0)
    if (count > 0) cacheService.set(key, count, HomeAlbumCountTtlMs)
    return count
}

/**
 * QQ 歌单广场（`playlist.PlayListPlazaServer`）的一个分类模块。
 *
 * 分类 id 是 QQ 侧写死的（`3317` 官方 / `59` 经典 / `71` 情歌 / `3056` 网络歌曲 / `64` KTV 热歌），
 * 每个分类共 700+ 个歌单，这里只取第一页。
 */
private fun qqPlazaModule(categoryId: Int): Map<String, Any?> = mapOf(
    "module" to "playlist.PlayListPlazaServer",
    "method" to "get_playlist_by_category",
    "param" to mapOf(
        "id" to categoryId,
        "curPage" to 1,
        "size" to HomePlaylistsMax,
        "order" to 5,
        "titleid" to categoryId,
    ),
)

/** 把一个歌单广场模块的响应映射成一个卡片区块；没内容返回 null。 */
private fun qqPlazaShelf(
    resp: Map<String, Any?>,
    moduleKey: String,
    shelfId: String,
    title: String,
    subtitle: String,
): HomeShelf.Cards? {
    val plaza = jsonList(jsonMap(resp[moduleKey])?.let { jsonMap(it["data"]) }?.get("v_playlist"))
    val items = plaza.mapNotNull { raw ->
        val m = jsonMap(raw) ?: return@mapNotNull null
        // ⚠️ 这个模块的 id 字段叫 `tid`（不是 content_id），歌单详情同样吃它
        val id = jsonStr(m["tid"])
        val name = decodeHtmlEntities(jsonStr(m["title"]))
        if (id.isEmpty() || name.isEmpty()) return@mapNotNull null
        val cover = jsonStr(m["cover_url_big"]).replace("/300?", "/600?")
        val accessNum = jsonLong(m["access_num"])
        HomeItem(
            key = "$shelfId:$id",
            title = name,
            subtitle = playlistSubtitle(accessNum, 0),
            cover = cover,
            badge = formatCount(accessNum).takeIf { it.isNotEmpty() },
            style = HomeCardStyle.Playlist,
            target = HomeTarget.Playlist(MusicPlatform.qq, id, name, cover),
        )
    }
    if (items.isEmpty()) return null
    return HomeShelf.Cards(
        id = shelfId,
        title = title,
        subtitle = subtitle,
        items = items.take(HomePlaylistsMax),
    )
}
