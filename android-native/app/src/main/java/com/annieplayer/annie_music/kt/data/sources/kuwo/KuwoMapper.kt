package com.annieplayer.annie_music.kt.data.sources.kuwo

import com.annieplayer.annie_music.kt.data.model.Album
import com.annieplayer.annie_music.kt.data.model.Artist
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.sources.common.asText
import com.annieplayer.annie_music.kt.data.sources.common.decodeHtmlEntities
import com.annieplayer.annie_music.kt.data.sources.common.firstTruthy
import com.annieplayer.annie_music.kt.data.sources.common.jsTruthy
import com.annieplayer.annie_music.kt.data.sources.splitArtistIds
import com.annieplayer.annie_music.kt.data.sources.splitArtistNames

// ================================================================================================
// 酷我映射（纯函数：JSON → Song / Album / Artist）
// ================================================================================================

/** 将酷我搜索原始条目映射为 [Song]（缓存命中与网络路径共用） */
internal fun kuwoMapSearchSongs(raw: Any?): List<Song> {
    return kuwoMapList(raw).map { item ->
        val rawRid = asText(item["MUSICRID"])
        val rid = rawRid.replaceFirst("MUSIC_", "")
        val minfo = asText(firstTruthy(listOf(item["MINFO"], item["N_MINFO"])))
        val qualities = parseQualities(minfo).toMutableList()
        if (qualities.isEmpty()) qualities.add(Quality.k128k)
        // 酷我：ARTIST 用 & 分隔多歌手；allartistid 是与之对应的 id 串（& 分隔）
        val artistNames = decodeHtmlEntities(kuwoStrOr(item["ARTIST"], "未知歌手"))
            .replace(Regex("\\\\+u0026"), "&")
            .split("&")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val artistIds = asText(item["allartistid"])
            // 酷我返回的 & 可能被转义成 \u0026 / \\u0026（伪 JSON 不走 jsonDecode），统一还原
            .replace(Regex("\\\\+u0026"), "&")
            .replace("\\", "")
            .split("&")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "0" }
            .takeIf { it.size == artistNames.size } ?: emptyList()
        Song(
            id = rid,
            title = decodeHtmlEntities(
                kuwoStrOr(firstTruthy(listOf(item["SONGNAME"], item["NAME"])), "未知歌曲"),
            ),
            // 保持原样：直接用 ARTIST 原串（不做 & 拆分/重排）
            artist = decodeHtmlEntities(kuwoStrOr(item["ARTIST"], "未知歌手")),
            cover = buildCoverUrl(item),
            album = decodeHtmlEntities(asText(item["ALBUM"])),
            albumId = if (jsTruthy(item["ALBUMID"])) asText(item["ALBUMID"]) else null,
            duration = kuwoInt(item["DURATION"]),
            platform = MusicPlatform.kuwo,
            playUrl = "",
            lyric = null,
            tlyric = null,
            qualities = qualities,
            qualitySizes = parseQualitySizes(minfo),
            albumArtist = if (jsTruthy(item["AARTIST"])) {
                decodeHtmlEntities(asText(item["AARTIST"]))
            } else {
                null
            },
            artistNames = artistNames,
            artistIds = artistIds,
        )
    }
}

/** 将酷我歌手原始条目映射为 [Artist]（缓存命中与网络路径共用） */
internal fun kuwoMapArtists(raw: Any?): List<Artist> {
    return kuwoMapList(raw).map { item ->
        Artist(
            id = asText(item["id"]),
            name = decodeHtmlEntities(kuwoStrOr(item["name"], "未知歌手")),
            picture = kuwoStrOr(firstTruthy(listOf(item["pic300"], item["pic"])), ""),
            musicNum = kuwoInt(item["musicNum"]),
        )
    }
}

/** 酷我专辑条目 -> [Album]（缓存命中与网络路径共用；两源字段名不同，故取并集） */
internal fun kuwoMapAlbums(raw: Any?): List<Album> {
    return kuwoMapList(raw).map { item ->
        Album(
            albumid = asText(item["albumid"]),
            name = decodeHtmlEntities(
                kuwoStrOr(firstTruthy(listOf(item["name"], item["NAME"], item["album"])), "未知专辑"),
            ),
            artist = decodeHtmlEntities(kuwoStrOr(item["artist"], "")),
            platform = MusicPlatform.kuwo,
            musiccnt = kuwoStrOr(
                firstTruthy(listOf(item["musiccnt"], item["MUSICCNT"])),
                "0",
            ),
            pub = kuwoStrOr(
                firstTruthy(listOf(item["pub"], item["PUB"], item["releaseDate"])),
                "",
            ),
            pic = kuwoStrOr(item["pic"], "").replace(Regex("/(120|240|500)/"), "/800/"),
            img = kuwoStrOr(item["img"], ""),
            htsImg = kuwoStrOr(item["hts_img"], ""),
            company = kuwoStrOr(item["company"], ""),
            lang = kuwoStrOr(item["lang"], ""),
            vip = kuwoStrOr(item["vip"], ""),
            playcnt = kuwoStrOr(item["PLAYCNT"], "0"),
        )
    }
}

/** 歌单曲目 -> [Song]（主页的酷我 feed 解析也复用它）。 */
internal fun kuwoMapSong(item: Map<String, Any?>): Song {
    val coverUrl = buildCoverUrl(item).ifEmpty {
        kuwoStrOr(firstTruthy(listOf(item["pic"], item["albumpic"], item["musicPic"])), "")
    }.replaceFirst("/120/", "/800/")
    val qualities = kuwoQualities(item)
    // 与搜索/专辑路径一致：拆多歌手名并与 id 按下标配对，否则 UI 拿不到多歌手、跳转退化成按名字搜索
    val artistRaw = decodeHtmlEntities(kuwoStrOr(item["artist"], "未知歌手"))
    val artistNames = splitArtistNames(artistRaw).ifEmpty { listOf(artistRaw) }
    val artistIds = splitArtistIds(asText(item["allartistid"]).ifEmpty { asText(item["artistid"]) })
    return Song(
        id = asText(firstTruthy(listOf(item["id"], item["rid"], item["musicrid"])))
            .replaceFirst("MUSIC_", ""),
        title = decodeHtmlEntities(kuwoStrOr(item["name"], "未知歌曲")),
        artist = artistRaw,
        cover = coverUrl,
        album = decodeHtmlEntities(kuwoStrOr(item["album"], "")),
        albumId = if (jsTruthy(item["albumid"])) asText(item["albumid"]) else null,
        duration = kuwoInt(item["duration"]),
        platform = MusicPlatform.kuwo,
        playUrl = "",
        lyric = null,
        tlyric = null,
        qualities = qualities,
        qualitySizes = kuwoQualitySizes(item),
        artistNames = artistNames,
        // 数量不一致时宁可不给 id（退回按名字搜索），也不要把 id 配错歌手
        artistIds = if (artistIds.size == artistNames.size) artistIds else emptyList(),
    )
}
