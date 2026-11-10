package com.annieplayer.annie_music.kt.data.sources.home

import com.annieplayer.annie_music.kt.data.model.HomeCardStyle
import com.annieplayer.annie_music.kt.data.model.HomeItem
import com.annieplayer.annie_music.kt.data.model.HomeShelf
import com.annieplayer.annie_music.kt.data.model.HomeTarget
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.network.cacheService
import com.annieplayer.annie_music.kt.data.network.httpGetJson
import com.annieplayer.annie_music.kt.data.sources.common.decodeHtmlEntities
import com.annieplayer.annie_music.kt.data.sources.kugou.enrichKugouQualities
import com.annieplayer.annie_music.kt.data.sources.kugou.getKuGouQualities
import com.annieplayer.annie_music.kt.data.sources.kugou.getKuGouQualitySizes
import com.annieplayer.annie_music.kt.data.sources.kugou.kugouMid
import com.annieplayer.annie_music.kt.data.sources.kugou.kugouPlainHeaders
import com.annieplayer.annie_music.kt.data.sources.kugou.kugouSignedPost

// ================================================================================================
// 酷狗主页 + 「按风格听」二级页（契约与调度见 HomeFeedApi.kt）
// ================================================================================================

/** 一个可选流派（酷狗风格行的一张卡）。 */
data class KugouStyleOption(val tagIds: String, val name: String)

/**
 * 酷狗「按风格听」可选的流派（实测 S1–S20；S21 以上服务端一律回落到华语流行）。
 *
 * S13/S14 与 S1 同为华语流行，去重只留 S1。名称取自接口返回的 `data.title`（实测量过一遍）。
 * 主页只列这些名字，**不预取曲目** —— 点进二级页面才按 tagIds 拉。
 */
val KugouStyleOptions: List<KugouStyleOption> = listOf(
    KugouStyleOption("S1", "华语流行"),
    KugouStyleOption("S2", "国风"),
    KugouStyleOption("S3", "电子"),
    KugouStyleOption("S4", "嘻哈"),
    KugouStyleOption("S5", "民谣"),
    KugouStyleOption("S6", "爵士"),
    KugouStyleOption("S7", "R&B"),
    KugouStyleOption("S8", "乡村"),
    KugouStyleOption("S9", "DJ"),
    KugouStyleOption("S10", "ACG"),
    KugouStyleOption("S11", "摇滚"),
    KugouStyleOption("S12", "古典乐"),
    KugouStyleOption("S15", "粤语流行"),
    KugouStyleOption("S16", "韩语流行"),
    KugouStyleOption("S17", "日语流行"),
    KugouStyleOption("S18", "英语流行"),
    KugouStyleOption("S19", "网络流行"),
    KugouStyleOption("S20", "纯音乐"),
)

/**
 * 某个流派的推荐歌曲（二级页面用）。
 *
 * 复用 `everyday_style_recommend`，只是把 tagids 换成点进去的那个。缓存 key 带 tagids，
 * 所以「华语流行」和「摇滚」各自缓存，来回切不重复请求。
 */
suspend fun fetchKugouStyleSongs(tagIds: String): List<Song> {
    val mid = kugouMid()
    val resp = cachedKugou("style:$tagIds") {
        kugouSignedPost(
            path = "/everydayrec.service/everyday_style_recommend",
            extraParams = mapOf("tagids" to tagIds),
            body = "{}",
            mid = mid,
        )
    } ?: return emptyList()
    return _fillKugouQualities(
        jsonList(jsonMap(resp["data"])?.get("song_list"))
            .mapNotNull { _kugouStyleSong(it) }
            .distinctBy { it.id }
            .take(HomeSongsMax),
    )
}

/**
 * 酷狗首页：风格推荐 / 首页刷歌（两组可播歌曲）+ 新专辑 + 热门歌手。
 *
 * 前两个走 `gateway.kugou.com` 的 **android 签名**（`signParams(..., kugouKeyA, body)`，
 * 与项目里解析专辑曲目用的是同一套）；后两个是 `mobilecdn.kugou.com` 的公开 JSON。
 */
internal suspend fun fetchHomeKugou(discoverToken: Int): List<HomeShelf> {
    val out = mutableListOf<HomeShelf>()
    val mid = kugouMid()

    // ① 按风格听：只列流派（**不发请求**）。点某张卡进二级页面才按 tagids 拉 30 首，
    //    否则主页一进来就要发 18 个风格的请求。
    out += HomeShelf.Cards(
        id = "kugou:styles",
        title = "按风格听",
        subtitle = "选一个流派",
        items = KugouStyleOptions.map { option ->
            HomeItem(
                key = "kugou:style:${option.tagIds}",
                title = option.name,
                style = HomeCardStyle.Style,
                target = HomeTarget.Style(MusicPlatform.kugou, option.tagIds, option.name),
            )
        },
    )

    // ② 首页刷歌（沉浸式推荐流，pagesize 直接给 50）。`discoverToken` 只进缓存 key，
    //    点「换一批」时 +1 → 绕过旧缓存拿新的一批
    val discover = cachedKugou("discover:$discoverToken") {
        kugouSignedPost(
            path = "/homediscoverrec/v1/client/home_discover_rec",
            extraParams = mapOf(
                "module_key" to "home_discover_rec",
                "module_id" to "1",
                "area_code" to "1",
                "platform" to "android",
                "userid" to "0",
                "clientver" to "20809",
            ),
            body = "{\"support\":\"only_song\",\"userid\":0,\"recall_type\":\"song\"," +
                "\"today_play_num\":0,\"pagesize\":$HomeSongsMax," +
                "\"go_ky_extra\":[{\"key\":\"network\",\"val\":\"2\"}," +
                "{\"key\":\"play_mode\",\"val\":\"2\"},{\"key\":\"no_mv_ret\",\"val\":\"1\"}]}",
            mid = mid,
        )
    }
    val discoverSongs = _fillKugouQualities(
        jsonList(jsonMap(discover?.get("data"))?.get("items"))
            .mapNotNull { raw -> _kugouDiscoveredSong(jsonMap(raw)?.get("song_info")) }
            .distinctBy { it.id }
            .take(HomeSongsMax),
    )
    if (discoverSongs.isNotEmpty()) {
        out += HomeShelf.Songs(
            id = "kugou:discover",
            title = "为你挑选",
            subtitle = "刷一刷换一批",
            songs = discoverSongs,
        )
    }

    // ③ 新专辑（公开 JSON，`imgurl` 带 {size} 占位）
    val albums = cachedKugouPlain("albums") {
        httpGetJson(
            "http://mobilecdn.kugou.com/api/v3/album/list?plat=0&version=9108&page=1&pagesize=$HomeCardsMax",
            kugouPlainHeaders,
        )
    }
    val albumItems = jsonList(jsonMap(albums?.get("data"))?.get("info")).mapNotNull { raw ->
        val m = jsonMap(raw) ?: return@mapNotNull null
        val id = jsonStr(m["albumid"])
        val name = decodeHtmlEntities(jsonStr(m["albumname"]))
        if (id.isEmpty() || name.isEmpty()) return@mapNotNull null
        val artist = decodeHtmlEntities(jsonStr(m["singername"]))
        val cover = jsonStr(m["imgurl"]).replace("{size}", "400")
        HomeItem(
            key = "kugou:album:$id",
            title = name,
            subtitle = artist,
            cover = cover,
            style = HomeCardStyle.Album,
            // 接口给的 "2026-10-23 00:00:00"，只留日期
            pub = jsonStr(m["publishtime"]).take(10),
            target = HomeTarget.Album(MusicPlatform.kugou, id, name, artist, cover),
        )
    }
    if (albumItems.isNotEmpty()) {
        out += HomeShelf.Cards(
            id = "kugou:newalbum",
            title = "新专辑",
            subtitle = "刚刚发行",
            items = albumItems.take(HomeCardsMax),
        )
    }

    // ④ 热门歌手
    val singers = cachedKugouPlain("singers") {
        httpGetJson(
            "http://mobilecdn.kugou.com/api/v3/singer/list?plat=0&version=9108&page=1&pagesize=$HomeCardsMax",
            kugouPlainHeaders,
        )
    }
    val singerItems = jsonList(jsonMap(singers?.get("data"))?.get("info")).mapNotNull { raw ->
        val m = jsonMap(raw) ?: return@mapNotNull null
        val id = jsonStr(m["singerid"])
        val name = decodeHtmlEntities(jsonStr(m["singername"]))
        if (id.isEmpty() || name.isEmpty()) return@mapNotNull null
        val cover = jsonStr(m["imgurl"]).replace("{size}", "240")
        val fans = jsonLong(m["fanscount"])
        HomeItem(
            key = "kugou:artist:$id",
            title = name,
            subtitle = if (fans > 0L) "${formatCount(fans)} 粉丝" else "",
            cover = cover,
            style = HomeCardStyle.Artist,
            target = HomeTarget.Artist(MusicPlatform.kugou, id, name, cover),
        )
    }
    if (singerItems.isNotEmpty()) {
        out += HomeShelf.Cards(
            id = "kugou:artist",
            title = "热门歌手",
            subtitle = "大家都在追",
            items = singerItems.take(HomeCardsMax),
        )
    }

    return out
}

private suspend fun cachedKugou(suffix: String, fetch: suspend () -> Map<String, Any?>): Map<String, Any?>? =
    cachedKugouKey("signed:$suffix", fetch)

private suspend fun cachedKugouPlain(suffix: String, fetch: suspend () -> Map<String, Any?>): Map<String, Any?>? =
    cachedKugouKey("plain:$suffix", fetch)

private suspend fun cachedKugouKey(
    suffix: String,
    fetch: suspend () -> Map<String, Any?>,
): Map<String, Any?>? {
    val key = HomeCachePrefix + "kugou:" + suffix
    cacheService.get<Map<String, Any?>>(key)?.let { return it }
    val fresh = runCatching { fetch() }.getOrNull() ?: return null
    if (fresh.isNotEmpty()) cacheService.set(key, fresh, HomeCacheTtlMs)
    return fresh
}

// 设备 MID / 公开 JSON 的 UA / 签名 POST 都在 `data.sources.kugou.KugouHttp.kt`
// （用途包不自己实现传输层，只调用平台包）。

/** 风格推荐的 `song_list[]` → [Song]（字段名与首页刷歌不同，各写一个 mapper）。 */
private fun _kugouStyleSong(raw: Any?): Song? {
    val m = jsonMap(raw) ?: return null
    val hash = jsonStr(m["hash"]).ifEmpty { jsonStr(m["hash_320"]) }
    val name = decodeHtmlEntities(jsonStr(m["songname"]))
    if (hash.isEmpty() || name.isEmpty()) return null
    val singers = jsonList(m["singerinfo"])
    val artistNames = singers.mapNotNull { s ->
        decodeHtmlEntities(jsonStr(jsonMap(s)?.get("name"))).takeIf { it.isNotEmpty() }
    }
    val artistIds = singers.mapNotNull { s ->
        jsonStr(jsonMap(s)?.get("id")).takeIf { it.isNotEmpty() }
    }
    val albumId = jsonStr(m["album_id"])
    return Song(
        id = "kugou:$hash",
        title = name,
        artist = artistNames.joinToString(" / "),
        cover = jsonStr(m["sizable_cover"]).replace("{size}", "400"),
        album = decodeHtmlEntities(jsonStr(m["album_name"])),
        albumId = albumId.takeIf { it.isNotEmpty() && it != "0" },
        duration = _kugouTimelengthSeconds(m["timelength_320"]),
        platform = MusicPlatform.kugou,
        qualities = getKuGouQualities(m),
        qualitySizes = getKuGouQualitySizes(m),
        albumArtist = artistNames.firstOrNull(),
        artistNames = artistNames,
        artistIds = if (artistIds.size == artistNames.size) artistIds else emptyList(),
    )
}

/** 首页刷歌 `items[].song_info` → [Song]（**没有歌手 id**，靠 `DetailNavigator` 的 hash 兜底）。 */
private fun _kugouDiscoveredSong(raw: Any?): Song? {
    val m = jsonMap(raw) ?: return null
    val hash = jsonStr(m["hash_320"]).ifEmpty { jsonStr(m["hash_128"]) }
    val name = decodeHtmlEntities(jsonStr(m["songname"]))
    if (hash.isEmpty() || name.isEmpty()) return null
    val albumId = jsonStr(m["ori_album_id"])
    val artist = decodeHtmlEntities(jsonStr(m["author_name"]))
    return Song(
        id = "kugou:$hash",
        title = name,
        artist = artist,
        cover = jsonStr(m["album_cover"]).replace("{size}", "400"),
        album = decodeHtmlEntities(jsonStr(m["album_name"])),
        albumId = albumId.takeIf { it.isNotEmpty() && it != "0" },
        duration = _kugouTimelengthSeconds(m["timelength_320"]),
        platform = MusicPlatform.kugou,
        qualities = getKuGouQualities(m),
        qualitySizes = getKuGouQualitySizes(m),
        albumArtist = artist,
    )
}

/**
 * 酷狗聚合接口的 `timelength_320` 是**毫秒**（4 分钟的歌是 240000 左右）。
 *
 * 直接当秒用会渲染成 `4000:00` 这种，同一个列表里就出现「有的歌是分秒、有的是天文数字」。
 * `< 10000` 视作已经是秒，防的是少数字段给的是秒、或者字段本身是异常小值。
 */
private fun _kugouTimelengthSeconds(raw: Any?): Int {
    val value = jsonLong(raw)
    if (value <= 0L) return 0
    return if (value < 10_000L) value.toInt() else (value / 1000L).toInt()
}

/**
 * 首页歌曲补齐档位 / 大小。
 *
 * 聚合接口（刷歌、风格推荐）给的 size 字段跟签名接口不是一套，**自带就信任、缺了才补**：
 * 一首都没带 size 时批量查一次 `audio_info`（50 首 = 1 批、~100ms），拿到真实的
 * `hash_high`/`filesize_high`；补不到返回原列表，展示与播放都不受影响。
 */
private suspend fun _fillKugouQualities(songs: List<Song>): List<Song> =
    if (songs.all { it.qualitySizes.isEmpty() }) enrichKugouQualities(songs) else songs
