package com.annieplayer.annie_music.kt.data.sources.qq

import com.annieplayer.annie_music.kt.data.model.Album
import com.annieplayer.annie_music.kt.data.model.ArtistSongsPage
import com.annieplayer.annie_music.kt.data.model.ArtistSongsPageSize
import com.annieplayer.annie_music.kt.data.network.httpGetText
import com.annieplayer.annie_music.kt.data.network.httpPostJson
import com.annieplayer.annie_music.kt.data.network.json
import com.annieplayer.annie_music.kt.data.network.jsonElementToAny
import com.annieplayer.annie_music.kt.data.sources.common.firstTruthy

// ================================================================================================
// QQ 歌手：简介 / 单曲（真分页）/ 专辑（主源 + 兜底）
// ================================================================================================

/** QQ 歌手简介：`music.web_singer_info_svr` 的 `data.singer_brief` */
suspend fun fetchQQArtistBio(singerMid: String): String {
    return try {
        val payload: Map<String, Any?> = mapOf(
            "comm" to mapOf("ct" to 24, "cv" to 0),
            "singer" to mapOf(
                "module" to "music.web_singer_info_svr",
                "method" to "get_singer_detail_info",
                "param" to mapOf("sort" to 5, "singermid" to singerMid, "begin" to 0, "num" to 1),
            ),
        )
        val data = httpPostJson(
            qqMusicuUrl,
            body = payload,
            headers = mapOf(
                "Content-Type" to "application/json",
                "User-Agent" to qqOr(qqHeaders["User-Agent"], ""),
                "Referer" to "https://y.qq.com/",
            ),
        )
        val singer = data["singer"]
        val d = if (singer is Map<*, *>) singer["data"] else null
        if (d is Map<*, *>) (d["singer_brief"] ?: "").toString().trim() else ""
    } catch (_: Throwable) {
        ""
    }
}

/**
 * 获取 QQ 歌手单曲（`musichall.song_list_server` / `GetSingerSongList`，按 `begin`/`num` 真分页）。
 *
 * 模块名取自官网歌手页实际所用；老的 `music.web_singer_info_svr` 虽能返回 60 首，
 * 但 `begin` 被服务端忽略、无法翻页。每条结果为 `{ songInfo: {...} }`，歌曲对象在 `songInfo` 里。
 */
suspend fun getQQArtistSongs(singerMid: String, page: Int = 1): ArtistSongsPage {
    try {
        val payload: Map<String, Any?> = mapOf(
            "comm" to mapOf("ct" to 24, "cv" to 0),
            "songList" to mapOf(
                "module" to "musichall.song_list_server",
                "method" to "GetSingerSongList",
                "param" to mapOf(
                    "singerMid" to singerMid,
                    "order" to 1,
                    "begin" to (page - 1) * ArtistSongsPageSize,
                    "num" to ArtistSongsPageSize,
                ),
            ),
        )
        val data = httpPostJson(
            qqMusicuUrl,
            body = payload,
            headers = mapOf(
                "Content-Type" to "application/json",
                "User-Agent" to qqOr(qqHeaders["User-Agent"], ""),
                "Referer" to "https://y.qq.com/",
            ),
        )
        val node = data["songList"]
        val d = if (node is Map<*, *>) node["data"] else null
        val listRaw = if (d is Map<*, *>) d["songList"] else null
        val list = if (listRaw is List<*>) listRaw else emptyList<Any?>()
        val total = if (d is Map<*, *>) (d["totalNum"] as? Number)?.toInt() ?: 0 else 0
        val songs = list.mapNotNull { item ->
            val info = if (item is Map<*, *>) item["songInfo"] else null
            if (info is Map<*, *>) qqArtistSong(qqNormMap(info)) else null
        }
        return ArtistSongsPage(songs, total)
    } catch (_: Throwable) {
        return ArtistSongsPage(emptyList(), 0)
    }
}

/**
 * QQ 音乐歌手专辑。
 *
 * 主源 `fcg_v8_singer_album`（网页歌手页在用）条目带 `latest_song.song_count`；
 * musicu `GetAlbumList` 的 `totalNum` 恒为 0（卡片曲目数会一直显示 0），仅作兜底。
 */
suspend fun getQQArtistAlbums(singerMid: String, singerName: String): List<Album> {
    if (singerMid.isEmpty()) return emptyList()
    val viaWeb = _fetchQQArtistAlbumsViaWeb(singerMid)
    if (viaWeb.isNotEmpty()) return viaWeb
    return _fetchQQArtistAlbumsViaMusicu(singerMid)
}

/** 歌手专辑主源（网页 `fcg_v8_singer_album`；一页 60，取不满即到底） */
private suspend fun _fetchQQArtistAlbumsViaWeb(singerMid: String): List<Album> {
    val out = mutableListOf<Album>()
    try {
        var begin = 0
        while (begin < 3000) {
            val url =
                "https://c.y.qq.com/v8/fcg-bin/fcg_v8_singer_album.fcg" +
                    "?singermid=$singerMid&format=json&platform=yqq&needNewCode=1" +
                    "&begin=$begin&num=60"
            val text = httpGetText(url, qqHeaders)
            val decoded = jsonElementToAny(json.parseToJsonElement(qqStripBom(text)))
            val data = if (decoded is Map<*, *>) decoded["data"] else null
            val list = if (data is Map<*, *>) data["list"] else null
            val items = if (list is List<*>) list else emptyList<Any?>()
            if (items.isEmpty()) break
            out += qqMapAlbums(items)
            if (items.size < 60) break
            begin += 60
        }
    } catch (_: Throwable) {
        return out
    }
    return out
}

/** 歌手专辑兜底源（musicu `GetAlbumList`；`totalNum` 恒 0，拿不到曲目数） */
private suspend fun _fetchQQArtistAlbumsViaMusicu(singerMid: String): List<Album> {
    val out = mutableListOf<Album>()
    return try {
        var begin = 0
        while (begin < 3000) {
            val payload = mapOf(
                "req_0" to mapOf(
                    "module" to "music.musichallAlbum.AlbumListServer",
                    "method" to "GetAlbumList",
                    "param" to mapOf(
                        "singerMid" to singerMid,
                        "begin" to begin,
                        "num" to 60,
                        "order" to 1,
                    ),
                ),
                "comm" to mapOf("guid" to qqMusicuGuid, "ct" to 19, "cv" to 1914),
            )
            val data = httpPostJson(qqMusicuUrl, qqHeaders, payload)
            val req0 = data["req_0"]
            val d = if (req0 is Map<*, *>) req0["data"] else null
            val list = if (d is Map<*, *>) firstTruthy(listOf(d["albumList"], d["list"])) else null
            val items = if (list is List<*>) list else emptyList<Any?>()
            if (items.isEmpty()) break
            out += qqMapAlbums(items)
            if (items.size < 60) break
            begin += 60
        }
        out
    } catch (_: Throwable) {
        out
    }
}
