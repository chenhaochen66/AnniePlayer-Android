package com.annieplayer.annie_music.kt.data.sources.kugou

import com.annieplayer.annie_music.kt.data.model.Album
import com.annieplayer.annie_music.kt.data.model.Artist
import com.annieplayer.annie_music.kt.data.model.ArtistSongsPage
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.network.cacheService
import com.annieplayer.annie_music.kt.data.network.httpGetJson
import com.annieplayer.annie_music.kt.data.sources.common.decodeHtmlEntities
import com.annieplayer.annie_music.kt.data.sources.common.encodeComponent
import com.annieplayer.annie_music.kt.data.sources.common.firstTruthy

// ================================================================================================
// 酷狗搜索：单曲 / 专辑 / 歌手
// ================================================================================================

/**
 * 搜索酷狗单曲。
 *
 * 走 web 搜索接口 song_search_v2：条目自带
 * FileHash / SQFileHash / HQFileHash，这些 hash 就是落雪插件解析直链时用的
 * `musicInfo.hash`（本项目不再自己取链）。
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

    val url =
        "https://songsearch.kugou.com/song_search_v2" +
            "?keyword=${encodeComponent(q)}&page=$page&pagesize=$pageSize" +
            "&userid=-1&clientver=&platform=WebFilter&filter=2" +
            "&iscorrection=1&privilege_filter=0"

    val data = httpGetJson(
        url,
        headers = mapOf("User-Agent" to kugouWebUa),
    )

    val d = data["data"]
    val listRaw = if (d is Map<*, *>) d["lists"] else null
    val list = if (listRaw is List<*>) listRaw.map { it } else emptyList<Any?>()

    return mapKugouSearchSongs(list)
}

/** 将酷狗搜索原始条目映射为 [Song]（字段名与歌单接口不同，需单独映射） */
internal fun mapKugouSearchSongs(raw: Any?): List<Song> {
    val list = if (raw is List<*>) raw else emptyList<Any?>()
    val out = ArrayList<Song>()

    for (item in list) {
        if (item !is Map<*, *>) continue
        val m = item

        val fileHash = kugouFirstNonEmpty(listOf(m["FileHash"], m["ResFileHash"]))
        if (fileHash.isEmpty()) continue

        // 无版权（Privilege = 0）直接跳过
        val privilege = m["Privilege"]
        if (privilege is Number && privilege.toDouble() == 0.0) continue

        // 统一走 getKuGouQualities：搜索接口字段名（`ResFileHash`/`SQFileHash`/`HQFileHash`）
        // 与签名接口不同，散在这里写会漏掉 Hi-Res
        val qualities = getKuGouQualities(m)

        val durationRaw = firstTruthy(
            listOf(m["Duration"], m["HQDuration"], m["SQDuration"]),
        )
        val duration = if (durationRaw is Number) {
            durationRaw.toInt()
        } else {
            durationRaw?.toString()?.toIntOrNull() ?: 0
        }

        // 酷狗封面 Image 字段带 {size} 占位符（统一 800，与其它平台一致）
        val cover = kugouFirstNonEmpty(listOf(m["Image"], m["UnionCover"]))
            .replace("{size}", "800")
        // SingerName 用「、」分隔多歌手，SingerId 是按下标配对的 id 数组
        val rawSinger = decodeHtmlEntities(
            kugouFirstNonEmpty(listOf(m["SingerName"], "未知歌手")),
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
            rawSinger.split(kugouReArtistSep).map { it.trim() }.filter { it.isNotEmpty() }
        }
        val artistIds = ids.takeIf { it.size == artistNames.size } ?: emptyList()

        out.add(
            Song(
                id = "kugou:$fileHash",
                title = decodeHtmlEntities(
                    kugouFirstNonEmpty(listOf(m["SongName"], "未知歌曲")),
                ),
                artist = if (artistNames.isEmpty()) {
                    "未知歌手"
                } else {
                    artistNames.joinToString(" / ")
                },
                cover = cover,
                album = decodeHtmlEntities(
                    kugouFirstNonEmpty(listOf(m["AlbumName"])),
                ),
                albumId = kugouFirstNonEmpty(listOf(m["AlbumID"], m["AlbumId"])),
                duration = duration,
                platform = MusicPlatform.kugou,
                playUrl = "",
                qualities = qualities,
                qualitySizes = getKuGouQualitySizes(m),
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

/** 搜索酷狗专辑（mobilecdn search/album） */
suspend fun searchKugouAlbum(
    query: String,
    page: Int = 1,
    pageSize: Int = 30,
): List<Album> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()

    val url =
        "http://mobilecdn.kugou.com/api/v3/search/album" +
            "?format=json&keyword=${encodeComponent(q)}" +
            "&page=$page&pagesize=$pageSize"
    val data = httpGetJson(url, headers = mapOf("User-Agent" to kugouWebUa))
    val d = data["data"]
    val listRaw = if (d is Map<*, *>) d["info"] else null
    val list = if (listRaw is List<*>) listRaw.map { it } else emptyList<Any?>()
    return mapKugouAlbums(list)
}

/** 酷狗专辑原始条目 → [Album] */
internal fun mapKugouAlbums(raw: Any?): List<Album> {
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
                pic = (m["imgurl"] ?: m["img"] ?: "").toString().replace("{size}", "800"),
            ),
        )
    }
    return out
}

/** 搜索酷狗歌手（签名 web 搜索接口） */
suspend fun searchKugouArtist(query: String): List<Artist> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()

    val data = kugouWebSignGet(
        "https://gateway.kugou.com/complexsearch/v1/search/author",
        listOf("keyword=$q", "searchsong=1"),
        1,
    )
    val raw = data["data"]
    val list = if (raw is Map<*, *>) raw["lists"] else null
    val items = if (list is List<*>) list.toList() else emptyList<Any?>()
    if (items.isEmpty()) return emptyList()
    return mapKugouArtists(items)
}

/** 酷狗歌手原始条目 → [Artist]（签名搜索自带 AuthorId/AuthorName/Avatar/AudioCount，无需再补 singer/info） */
internal fun mapKugouArtists(raw: Any?): List<Artist> {
    val list = if (raw is List<*>) raw else emptyList<Any?>()
    val out = ArrayList<Artist>()
    for (item in list) {
        if (item !is Map<*, *>) continue
        val m = item
        val cnt = m["AudioCount"] ?: m["songcount"]
        out.add(
            Artist(
                id = (m["AuthorId"] ?: m["singerid"] ?: "").toString(),
                name = decodeHtmlEntities((m["AuthorName"] ?: m["singername"] ?: "").toString()),
                picture = (m["Avatar"] ?: m["imgurl"] ?: "").toString(),
                musicNum = if (cnt is Number) cnt.toInt() else 0,
            ),
        )
    }
    return out
}

/** 酷狗歌手信息（头像 + 歌曲数 + 简介），带 30 分钟缓存 */
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
            headers = mapOf("User-Agent" to kugouWebUa),
        )
        val d = data["data"]
        if (d is Map<*, *>) {
            // imgurl 带 {size} 占位符
            avatar = (d["imgurl"] ?: "").toString().replace("{size}", "240")
            val cnt = d["songcount"]
            songCount = if (cnt is Number) cnt.toInt() else 0
            // intro 为短简介；长简介在 mix_intro
            intro = kugouFirstNonEmpty(listOf(d["intro"], d["mix_intro"])).trim()
        }
    } catch (_: Throwable) {
        // 忽略：头像/歌曲数缺失不影响主流程
    }
    // 只在拿到有效数据时缓存：避免把「限流/失败」的空结果缓存 30 分钟
    if (avatar.isNotEmpty() || songCount > 0 || intro.isNotEmpty()) {
        cacheService.set(
            cacheKey,
            mapOf("avatar" to avatar, "songCount" to songCount, "intro" to intro),
            kugouArtistInfoTtl,
        )
    }
    return KugouArtistInfo(avatar, songCount, intro)
}

/** 酷狗歌手单曲（签名接口 `openapi/kmr/v1/author/audios`，带歌手 id） */
suspend fun getKugouArtistSongs(singerId: String, page: Int = 1): ArtistSongsPage {
    try {
        val data = kugouWebSignGet(
            "https://gateway.kugou.com/openapi/kmr/v1/author/audios",
            listOf("author_id=$singerId"),
            page,
        )
        val d = data["data"]
        val list = if (d is Map<*, *>) d["songs"] else null
        val total = if (d is Map<*, *>) (d["total"] as? Number)?.toInt() ?: 0 else 0
        return ArtistSongsPage(mapKugouArtistSongs(list), total)
    } catch (_: Throwable) {
        return ArtistSongsPage(emptyList(), 0)
    }
}

/** 酷狗歌手单曲条目 → [Song]（签名接口：`authors[].base` 带 author_id，`audio_info` 带 hash/大小） */
internal fun mapKugouArtistSongs(raw: Any?): List<Song> {
    val list = if (raw is List<*>) raw else emptyList<Any?>()
    val out = ArrayList<Song>()
    for (item in list) {
        if (item !is Map<*, *>) continue
        val m = item
        val ai = m["audio_info"] as? Map<*, *> ?: emptyMap<String, Any?>()
        val hash = kugouFirstNonEmpty(listOf(ai["hash"], m["hash"]))
        if (hash.isEmpty()) continue

        val authors = (m["authors"] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()
        val bases = authors.mapNotNull { it["base"] as? Map<*, *> }
        val artistNames = bases
            .map { decodeHtmlEntities((it["author_name"] ?: "").toString()) }
            .filter { it.isNotEmpty() }
        val artistIds = bases.map { (it["author_id"] ?: "").toString() }.filter { it.isNotEmpty() }

        val albumInfo = m["album_info"] as? Map<*, *> ?: emptyMap<String, Any?>()
        val dur = ai["duration"]
        val seconds = (when (dur) {
            is Number -> dur.toLong()
            else -> dur?.toString()?.toLongOrNull() ?: 0L
        } / 1000).toInt()

        out.add(
            Song(
                id = "kugou:$hash",
                title = decodeHtmlEntities((m["audio_name"] ?: "").toString()),
                artist = artistNames.joinToString(" / "),
                cover = (albumInfo["cover"] ?: "").toString().replace("{size}", "800"),
                album = decodeHtmlEntities((albumInfo["album_name"] ?: "").toString()),
                albumId = (m["album_id"] ?: "").toString().ifEmpty { null },
                duration = seconds,
                platform = MusicPlatform.kugou,
                qualities = getKuGouQualities(ai),
                qualitySizes = getKuGouQualitySizes(ai),
                albumArtist = artistNames.firstOrNull(),
                artistNames = artistNames,
                artistIds = if (artistIds.size == artistNames.size) artistIds else emptyList(),
            ),
        )
    }
    return out
}

/** 酷狗歌手专辑（singer/album） */
suspend fun getKugouArtistAlbums(singerId: String): List<Album> {
    val out = mutableListOf<Album>()
    return try {
        // 该接口每页固定只回 50 条（pagesize 传 100/1000 同样是 50），必须翻页取全
        var page = 1
        while (page <= 100) {
            val url =
                "http://mobilecdn.kugou.com/api/v3/singer/album" +
                    "?singerid=$singerId&page=$page&pagesize=50&version=9108&plat=0"
            val data = httpGetJson(url, headers = mapOf("User-Agent" to kugouWebUa))
            val d = data["data"]
            val listRaw = if (d is Map<*, *>) d["info"] else null
            val list = if (listRaw is List<*>) listRaw else emptyList<Any?>()
            if (list.isEmpty()) break
            out += mapKugouAlbums(list)
            if (list.size < 50) break
            page++
        }
        out
    } catch (_: Throwable) {
        out
    }
}
