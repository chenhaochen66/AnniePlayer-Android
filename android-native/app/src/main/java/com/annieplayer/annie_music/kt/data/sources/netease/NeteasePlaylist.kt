package com.annieplayer.annie_music.kt.data.sources.netease

import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Playlist
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.network.anyToJsonElement
import com.annieplayer.annie_music.kt.data.network.httpPostJson
import com.annieplayer.annie_music.kt.data.sources.common.decodeHtmlEntities
import com.annieplayer.annie_music.kt.data.sources.common.jsTruthy

// ================================================================================================
// 网易云歌单（详情 200 条一档批量补全）
// ================================================================================================

private suspend fun _fetchNeteaseSongDetails(chunk: List<Map<String, Any?>>): List<Song> {
    return try {
        val data = httpPostJson(
            "https://music.163.com/api/v3/song/detail",
            headers = neteaseApiHeaders,
            body = neteaseQuery(listOf("c" to anyToJsonElement(chunk).toString())),
        )

        val songsRaw = data["songs"]
        val list = if (songsRaw is List<*>) songsRaw else emptyList<Any?>()

        neteaseListOfMaps(list).map { sm ->
            val al = neteaseAsMap(sm["al"]) ?: emptyMap()
            val cover = "${al["picUrl"] ?: ""}?param=800y800"

            // 歌手名与 id **必须同样取自 `ar`**：歌单这条路径没有第二处来源。
            // 只取 name 的后果是收藏后点不进歌手页（`ar[].id` 接口一直都在，
            // 实测 `POST /api/v3/song/detail` 的 `ar[0].id = 185858`）。
            val arList = neteaseListOfMaps(sm["ar"])
            val artistNames = arList
                .map { decodeHtmlEntities((it["name"] ?: "").toString()) }
                .filter { it.isNotEmpty() }
            val artistIds = arList
                .map { (it["id"] ?: "").toString() }
                .filter { it.isNotEmpty() }
                .takeIf { it.size == artistNames.size } ?: emptyList()

            val qualities = ArrayList<Quality>()
            if (jsTruthy(sm["hr"])) qualities.add(Quality.hires)
            if (jsTruthy(sm["sq"])) qualities.add(Quality.flac)
            if (jsTruthy(sm["h"])) qualities.add(Quality.k320k)
            if (jsTruthy(sm["l"]) || jsTruthy(sm["m"])) qualities.add(Quality.k128k)

            val dt = sm["dt"]

            Song(
                id = "netease:${sm["id"]}",
                title = decodeHtmlEntities((sm["name"] ?: "").toString()),
                artist = artistNames.joinToString(" / "),
                cover = cover,
                album = decodeHtmlEntities((al["name"] ?: "").toString()),
                albumId = if (jsTruthy(al["id"])) al["id"].toString() else null,
                duration = neteaseMsToSec(dt),
                platform = MusicPlatform.netease,
                playUrl = "",
                lyric = null,
                tlyric = null,
                qualities = if (qualities.isNotEmpty()) qualities else listOf(Quality.k128k),
                qualitySizes = parseNeteaseQualitySizes(sm),
                artistNames = artistNames,
                artistIds = artistIds,
            )
        }
    } catch (_: Throwable) {
        emptyList()
    }
}

/** 获取网易云歌单（含 200 条分块批量拉取逻辑）；失败返回 null */
suspend fun getNeteasePlaylist(playlistId: String): Playlist? {
    return try {
        val data = httpPostJson(
            "https://music.163.com/api/v6/playlist/detail",
            headers = neteaseApiHeaders,
            body = neteaseQuery(listOf("id" to playlistId, "n" to "100000")),
        )

        if (!neteaseIsOk(data["code"], 200) || data["playlist"] !is Map<*, *>) return null
        val pl = neteaseAsMap(data["playlist"]) ?: return null

        val trackIds = neteaseListOfMaps(pl["trackIds"])

        val chunkSize = 200
        val chunks = ArrayList<List<Map<String, Any?>>>()
        var i = 0
        while (i < trackIds.size) {
            val end = if (i + chunkSize < trackIds.size) i + chunkSize else trackIds.size
            chunks.add(
                trackIds.subList(i, end).map { t ->
                    val id = t["id"]
                    val v = t["v"]
                    mapOf<String, Any?>(
                        "id" to if (id is Number) id.toInt() else 0,
                        "v" to if (v is Number) v.toInt() else 0,
                    )
                },
            )
            i += chunkSize
        }

        val results = chunks.map { _fetchNeteaseSongDetails(it) }
        val songs = results.flatten()

        val creatorRaw = pl["creator"]
        val cover = (pl["coverImgUrl"] ?: "").toString()

        Playlist(
            id = playlistId,
            name = (pl["name"] ?: "").toString(),
            creator = if (creatorRaw is Map<*, *>) {
                (creatorRaw["nickname"] ?: "").toString()
            } else {
                ""
            },
            cover = "$cover?param=800y800",
            platform = MusicPlatform.netease,
            songs = songs,
            songCount = songs.size,
        )
    } catch (_: Throwable) {
        null
    }
}
