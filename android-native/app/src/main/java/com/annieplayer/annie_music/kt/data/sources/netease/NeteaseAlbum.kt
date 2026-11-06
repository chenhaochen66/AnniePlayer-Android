package com.annieplayer.annie_music.kt.data.sources.netease

import com.annieplayer.annie_music.kt.data.model.AlbumSong
import com.annieplayer.annie_music.kt.data.model.AlbumSongsPage
import com.annieplayer.annie_music.kt.data.network.httpGetJson
import com.annieplayer.annie_music.kt.data.sources.common.decodeHtmlEntities
import com.annieplayer.annie_music.kt.data.sources.common.jsTruthy

// ================================================================================================
// 网易云专辑（曲目 / 详情 / 单曲补充信息）
// ================================================================================================
//
// 统一走 **v1** 接口：旧的 `/api/album/{id}` 现在返回 `code:-462「请登录手机号」`，
// 解析不到 `album.songs`，于是专辑页「只有专辑信息、没有歌曲、点了也放不出来」。

/**
 * 获取网易云专辑曲目（`/api/v1/album/{id}`）。
 *
 * v1 接口免登录可用，且歌曲自带 `ar[].id`（歌手 id），所以歌手跳转也不必再补别的接口。
 */
suspend fun getNeteaseAlbumSongs(albumId: String): AlbumSongsPage {
    return try {
        val data = httpGetJson(
            "https://music.163.com/api/v1/album/$albumId",
            neteaseApiHeaders,
        )
        val album = data["album"]
        val albumMap = if (album is Map<*, *>) album else null
        // v1 把歌曲放在**顶层 `songs`**（旧结构在 `album.songs`）—— 两者都兼容
        val songsRaw = data["songs"] ?: albumMap?.get("songs")
        val list = if (songsRaw is List<*>) songsRaw else emptyList<Any?>()
        val albumArtistName = run {
            val a = albumMap?.get("artist")
            val name = if (a is Map<*, *>) (a["name"] ?: "").toString() else ""
            if (name.isNotEmpty()) {
                decodeHtmlEntities(name)
            } else {
                val arr = albumMap?.get("artists")
                val first = if (arr is List<*>) arr.firstOrNull() else null
                if (first is Map<*, *>) decodeHtmlEntities((first["name"] ?: "").toString()) else ""
            }
        }
        val albumGenre = (albumMap?.get("tags") as? List<*>)
            ?.mapNotNull { it?.toString()?.takeIf { s -> s.isNotBlank() } }
            ?.joinToString(" / ")
            .orEmpty()
        val out = ArrayList<AlbumSong>()
        for (m in neteaseListOfMaps(list)) {
            // 注意：`/api/album/{id}` 是旧接口 —— 歌手字段是 `artists`（不是 `ar`）、
            // 时长是 `duration`（不是 `dt`）。这里两个都兼容。
            val arRaw = m["ar"] ?: m["artists"]
            val arList = neteaseListOfMaps(arRaw)
            val artistNames = arList
                .map { decodeHtmlEntities((it["name"] ?: "").toString()) }
                .filter { it.isNotEmpty() }
            val artistIds = arList
                .map { (it["id"] ?: "").toString() }
                .filter { it.isNotEmpty() }
                .takeIf { it.size == artistNames.size } ?: emptyList()
            val dt = m["dt"] ?: m["duration"]
            val no = m["no"]
            val cd = m["cd"] ?: m["disc"]
            out.add(
                AlbumSong(
                    id = "netease:${m["id"]}",
                    name = decodeHtmlEntities((m["name"] ?: "").toString()),
                    artistName = if (artistNames.isEmpty()) "" else artistNames.joinToString(" / "),
                    duration = neteaseMsToSec(dt),
                    track = if (no is Number) no.toInt() else null,
                    discNumber = if (cd is Number) cd.toInt() else cd?.toString()?.toIntOrNull(),
                    albumArtist = albumArtistName.ifEmpty { null },
                    // 专辑接口是按每首歌给音质标记的（hr/sq/h/l），必须带上 ——
                    // 不带的话详情页下载候选永远回退到 flac/320/128，Hi-Res 就丢了。
                    qualities = parseNeteaseQualities(m),
                    qualitySizes = parseNeteaseQualitySizes(m),
                    artistNames = artistNames,
                    artistIds = artistIds,
                    composer = (m["composer"] as? String)?.takeIf { it.isNotBlank() },
                    genre = albumGenre.ifEmpty { null },
                ),
            )
        }
        // 发行时间在 `album.publishTime`（毫秒时间戳）
        AlbumSongsPage(out, neteaseIsoDate(albumMap?.get("publishTime")))
    } catch (_: Throwable) {
        AlbumSongsPage(emptyList())
    }
}

/** 获取网易云专辑详情；失败返回 null（对齐 RN 版） */
suspend fun getNeteaseAlbumDetail(albumId: String): NeteaseAlbumDetail? {
    return try {
        // 与 getNeteaseAlbumSongs 同因：旧 `/api/album/{id}` 已需登录，改用免登录的 v1 接口
        val data = httpGetJson(
            "https://music.163.com/api/v1/album/$albumId",
            neteaseApiHeaders,
        )

        val albumRaw = data["album"]
        if (albumRaw !is Map<*, *>) return null
        val album = neteaseAsMap(albumRaw) ?: return null

        val artistRaw = album["artist"]
        val artistsRaw = album["artists"]
        val artistName = (if (artistRaw is Map<*, *>) artistRaw["name"] else null)
            ?: (
                if (artistsRaw is List<*> && artistsRaw.isNotEmpty() && artistsRaw.first() is Map<*, *>) {
                    (artistsRaw.first() as Map<*, *>)["name"]
                } else {
                    null
                }
                )
            ?: ""

        // v1 把歌曲放在顶层 `songs`
        val songsRaw = data["songs"] ?: album["songs"]
        val songsList = if (songsRaw is List<*>) songsRaw else emptyList<Any?>()
        val songs = neteaseListOfMaps(songsList).map { sm ->
            // v1 歌曲的歌手字段是 `ar`（旧接口是 `artists`）
            val sArtistList = neteaseListOfMaps(sm["ar"] ?: sm["artists"])
            val no = sm["no"]
            NeteaseAlbumSong(
                id = "netease:${sm["id"]}",
                name = decodeHtmlEntities((sm["name"] ?: "").toString()),
                no = if (no is Number) no.toInt() else 0,
                disc = (sm["disc"] ?: sm["cd"] ?: "1").toString(),
                artist = decodeHtmlEntities(
                    sArtistList.map { (it["name"] ?: "").toString() }.joinToString(" / "),
                ),
            )
        }

        NeteaseAlbumDetail(
            name = decodeHtmlEntities((album["name"] ?: "").toString()),
            artist = decodeHtmlEntities(artistName.toString()),
            publishTime = if (jsTruthy(album["publishTime"])) {
                neteaseIsoDate(album["publishTime"])
            } else {
                ""
            },
            company = (album["company"] ?: "").toString(),
            songs = songs,
        )
    } catch (_: Throwable) {
        null
    }
}

/** 单曲补充信息：从专辑详情里找出这一首，拿曲目号 / CD 号 / 发行时间 / 专辑歌手 */
suspend fun enrichNeteaseSong(songId: String, albumId: String): NeteaseEnrich {
    val empty = NeteaseEnrich(null, null, null, null)
    if (albumId.isEmpty()) return empty
    return try {
        val album = getNeteaseAlbumDetail(albumId) ?: return empty
        val matched = album.songs.firstOrNull { it.id == songId }
        NeteaseEnrich(
            trackNumber = matched?.no?.takeIf { it > 0 }?.toString(),
            discNumber = matched?.disc?.takeIf { it.isNotEmpty() && it != "0" },
            releaseDate = album.publishTime.ifEmpty { null },
            albumArtist = album.artist.ifEmpty { null },
        )
    } catch (_: Throwable) {
        empty
    }
}
