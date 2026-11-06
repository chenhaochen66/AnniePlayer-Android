package com.annieplayer.annie_music.kt.data.sources.qq

import com.annieplayer.annie_music.kt.data.model.Album
import com.annieplayer.annie_music.kt.data.model.Artist
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.sources.common.asText
import com.annieplayer.annie_music.kt.data.sources.common.decodeHtmlEntities
import com.annieplayer.annie_music.kt.data.sources.common.firstTruthy

// ================================================================================================
// QQ 映射（纯函数：JSON → Song / Album / Artist）
// ================================================================================================
//
// 多歌手来自 `singer[].name` + `singer[].mid`，同时填充 artistNames / artistIds（不取首歌手）。

/**
 * 将 QQ 歌曲 json 映射为统一 [Song]（sizeflac→flac, size320→320k ...）。
 */
fun mapQQSong(s: Map<String, Any?>): Song {
    val singerRaw = s["singer"]
    val singerList = if (singerRaw is List<*>) {
        singerRaw.filterIsInstance<Map<*, *>>()
    } else {
        emptyList()
    }
    val artistNames = singerList
        .map { decodeHtmlEntities(asText(it["name"])) }
        .filter { it.isNotEmpty() }
    val artistIds = singerList
        .map { asText(it["mid"]) }
        .filter { it.isNotEmpty() }
        .takeIf { it.size == artistNames.size } ?: emptyList()
    val singer = artistNames.joinToString(" / ")

    val qual = qqQualities(s)

    val albumMid = asText(firstTruthy(listOf(s["albummid"], qqDeep(s, "album", "mid"))))
    val coverUrl = if (albumMid.isNotEmpty()) {
        "https://y.gtimg.cn/music/photo_new/T002R800x800M000$albumMid.jpg"
    } else {
        ""
    }
    val artist = decodeHtmlEntities(singer)

    // songmid / songname / albumname：新接口对应 `mid` / `name` / `album.name`
    val songmid = asText(firstTruthy(listOf(s["songmid"], s["mid"])))
    val interval = s["interval"]

    return Song(
        id = "qq:$songmid",
        title = decodeHtmlEntities(qqOr(firstTruthy(listOf(s["songname"], s["name"])), "未知歌曲")),
        artist = artist,
        cover = coverUrl,
        album = decodeHtmlEntities(
            asText(firstTruthy(listOf(s["albumname"], qqDeep(s, "album", "name")))),
        ),
        albumId = if (albumMid.isNotEmpty()) albumMid else null,
        duration = if (interval is Number) interval.toInt() else 0,
        platform = MusicPlatform.qq,
        playUrl = "",
        lyric = null,
        tlyric = null,
        qualities = qual,
        qualitySizes = qqQualitySizes(s),
        albumArtist = artist,
        artistNames = artistNames,
        artistIds = artistIds,
        mediaMid = qqMediaMid(s),
    )
}

/**
 * QQ 歌手单曲条目 → [Song]（musicu `songInfo` 的形状：`mid` / `name` / `album{mid,name}`）。
 *
 * `internal` 是为了让主页（`sources.home.QqHome`）复用同一份映射。
 */
internal fun qqArtistSong(m: Map<String, Any?>): Song {
    val mid = asText(m["mid"])
    val singerRaw = m["singer"]
    val singerList = if (singerRaw is List<*>) {
        singerRaw.filterIsInstance<Map<*, *>>()
    } else {
        emptyList()
    }
    val artistNames = singerList
        .map { decodeHtmlEntities(asText(it["name"])) }
        .filter { it.isNotEmpty() }
    val artistIds = singerList
        .map { asText(it["mid"]) }
        .filter { it.isNotEmpty() }
        .takeIf { it.size == artistNames.size } ?: emptyList()
    val artist = artistNames.joinToString(" / ")
    val albumRaw = m["album"]
    val album = qqToMap(albumRaw) ?: emptyMap()
    val albumMid = asText(album["mid"])
    val interval = m["interval"]
    return Song(
        id = "qq:$mid",
        title = decodeHtmlEntities(asText(m["name"])),
        artist = decodeHtmlEntities(artist),
        cover = if (albumMid.isEmpty()) {
            ""
        } else {
            "https://y.gtimg.cn/music/photo_new/T002R800x800M000$albumMid.jpg"
        },
        album = decodeHtmlEntities(asText(album["name"])),
        albumId = albumMid,
        duration = if (interval is Number) interval.toInt() else 0,
        platform = MusicPlatform.qq,
        // 之前漏了这两项：歌手详情的歌 qualities 为空 → 音质选择器只剩 128k、
        // 插件 types 为空 → 播放/下载无损双双失败（搜索/歌单有填，所以只有这一页坏）
        qualities = qqQualities(m),
        qualitySizes = qqQualitySizes(m),
        albumArtist = decodeHtmlEntities(artist),
        artistNames = artistNames,
        artistIds = artistIds,
        mediaMid = qqMediaMid(m),
    )
}

/** 将 QQ 搜索原始条目映射为 [Song]（缓存命中与网络路径共用） */
internal fun qqMapSearchSongs(raw: Any?): List<Song> {
    val list = if (raw is List<*>) raw else emptyList<Any?>()
    return list
        .filterIsInstance<Map<*, *>>()
        .map { mapQQSong(qqNormMap(it)) }
}

/** QQ 专辑原始条目 → [Album] */
internal fun qqMapAlbums(raw: Any?): List<Album> {
    val list = if (raw is List<*>) raw else emptyList<Any?>()
    val out = mutableListOf<Album>()
    for (item in list) {
        val m = qqToMap(item) ?: continue
        val mid = asText(
            firstTruthy(listOf(m["albumMID"], m["albumMid"], m["albummid"], m["mid"])),
        )
        val singerArr = m["singer"]
        val singerName = if (
            singerArr is List<*> && singerArr.isNotEmpty() && singerArr.first() is Map<*, *>
        ) {
            val s0 = singerArr.first() as Map<*, *>
            firstTruthy(listOf(s0["name"], s0["title"]))
        } else {
            null
        }
        val picRaw = asText(m["pic"])
        // 曲目数两条链路位置不同：专辑搜索在 song_count，歌手专辑在 latest_song.song_count
        val latestSong = qqToMap(m["latest_song"])
        out.add(
            Album(
                albumid = mid,
                name = decodeHtmlEntities(asText(firstTruthy(listOf(m["albumName"], m["name"])))),
                artist = decodeHtmlEntities(
                    asText(firstTruthy(listOf(m["singerName"], singerName))),
                ),
                platform = MusicPlatform.qq,
                musiccnt = qqOr(
                    firstTruthy(
                        listOf(
                            m["song_count"],
                            m["songnum"],
                            m["song_num"],
                            latestSong?.get("song_count"),
                        ),
                    ),
                    "0",
                ),
                pub = asText(
                    firstTruthy(
                        listOf(
                            m["publicTime"],
                            m["public_time"],
                            m["publishDate"],
                            m["publish_date"],
                            m["pubTime"],
                        ),
                    ),
                ),
                pic = if (mid.isNotEmpty()) {
                    "https://y.gtimg.cn/music/photo_new/T002R800x800M000$mid.jpg"
                } else {
                    picRaw
                },
            ),
        )
    }
    return out
}

/** QQ 歌手原始条目 → [Artist] */
internal fun qqMapArtists(raw: Any?): List<Artist> {
    val list = if (raw is List<*>) raw else emptyList<Any?>()
    val out = mutableListOf<Artist>()
    for (item in list) {
        val m = qqToMap(item) ?: continue
        val numSongs = firstTruthy(listOf(m["songNum"], m["songnum"]))
        out.add(
            Artist(
                id = asText(firstTruthy(listOf(m["singerMID"], m["mid"]))),
                name = decodeHtmlEntities(asText(firstTruthy(listOf(m["singerName"], m["name"])))),
                picture = asText(firstTruthy(listOf(m["singerPic"], m["pic"]))),
                musicNum = if (numSongs is Number) numSongs.toInt() else 0,
            ),
        )
    }
    return out
}
