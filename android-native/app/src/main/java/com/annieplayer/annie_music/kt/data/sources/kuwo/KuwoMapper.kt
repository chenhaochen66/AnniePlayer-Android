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

/**
 * 酷我的歌手名与歌手 id 是**分开给**的：名字是一个合并串（`A&B`），id 在 `allartistid`。
 *
 * 名字里的 `&` `,` `、` `/` **既可能是分隔符、也可能就是名字本身** —— `Years & Years` 是乐队名，
 * `Tyler, The Creator` 是一个人，`AC/DC` 也是一个人。光看字符串分不出来（实测 551 条样本里
 * 有 34% 属于「只有一个 id、名字里却有分隔符」，按分隔符硬拆会拆出两个假歌手）。
 * 唯一可靠的判据是 **id 的个数**：一个 id ⇒ 整串就是一个歌手名。
 *
 * 段数与 id 数仍对不上时（`Pet Shop Boys&Years & Years` → 3 段 / 2 个 id）**不做猜测**：
 * 整串当一个名字，把 id 原样带出去，由 [resolveKuwoArtistNames] 按 id 补名字。
 * 这种情况下返回的 id 会比名字多 —— 调用方按 `size` 相等才配对即可（`songArtistsOf` 已经这么做了）。
 */
internal fun kuwoArtistCredit(
    rawName: String,
    allArtistIds: String,
    firstArtistId: String = "",
): Pair<List<String>, List<String>> {
    // 伪 JSON 里 `&` 可能被多重转义（`\u0026` / `\\u0026`），decodeHtmlEntities 只吃掉一部分，
    // 残留的反斜杠一律清掉 —— 歌手名里不会有反斜杠，留着只会污染显示
    val name = decodeHtmlEntities(rawName).replace("\\", "").trim().ifEmpty { "未知歌手" }
    val ids = splitArtistIds(allArtistIds.ifEmpty { firstArtistId })
    val names = if (ids.size <= 1) {
        listOf(name)
    } else {
        splitArtistNames(name).takeIf { it.size == ids.size } ?: listOf(name)
    }
    return names to ids
}

/** 将酷我搜索原始条目映射为 [Song]（缓存命中与网络路径共用） */
internal fun kuwoMapSearchSongs(raw: Any?): List<Song> {
    return kuwoMapList(raw).map { item ->
        val rawRid = asText(item["MUSICRID"])
        val rid = rawRid.replaceFirst("MUSIC_", "")
        val minfo = asText(firstTruthy(listOf(item["MINFO"], item["N_MINFO"])))
        val qualities = parseQualities(minfo).toMutableList()
        if (qualities.isEmpty()) qualities.add(Quality.k128k)
        val (artistNames, artistIds) = kuwoArtistCredit(
            rawName = asText(item["ARTIST"]),
            allArtistIds = asText(item["allartistid"]),
            firstArtistId = asText(item["ARTISTID"]),
        )
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
            artistId = asText(item["artistid"]),
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
    val artistRaw = decodeHtmlEntities(kuwoStrOr(item["artist"], "未知歌手"))
    // 与搜索/专辑路径同一条判据（id 个数），否则「Years & Years」会被拆成两个歌手
    val (artistNames, artistIds) = kuwoArtistCredit(
        rawName = artistRaw,
        allArtistIds = asText(item["allartistid"]),
        firstArtistId = asText(item["artistid"]),
    )
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
        artistIds = artistIds,
    )
}
