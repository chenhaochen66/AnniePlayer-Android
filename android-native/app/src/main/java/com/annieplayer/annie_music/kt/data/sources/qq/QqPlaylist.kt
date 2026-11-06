package com.annieplayer.annie_music.kt.data.sources.qq

import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Playlist
import com.annieplayer.annie_music.kt.data.network.httpGetText
import com.annieplayer.annie_music.kt.data.network.json
import com.annieplayer.annie_music.kt.data.network.jsonElementToAny
import com.annieplayer.annie_music.kt.data.sources.common.asText

// ================================================================================================
// QQ 歌单（`fcg_v8_playlist_cp`，按 200 条分页累加）
// ================================================================================================

private suspend fun _fetchQQPage(disstid: String, begin: Int, pageSize: Int): QqPage? {
    val url =
        "https://c.y.qq.com/v8/fcg-bin/fcg_v8_playlist_cp.fcg?id=$disstid&format=json" +
            "&song_begin=$begin&song_num=$pageSize&loginUin=0&hostUin=0" +
            "&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0"
    try {
        val text = httpGetText(url, qqHeaders)
        val decoded = jsonElementToAny(json.parseToJsonElement(qqStripBom(text)))
        if (decoded !is Map<*, *>) return null
        if (qqNeqZero(decoded["code"])) return null

        val dataField = decoded["data"]
        val cdRaw = if (dataField is Map<*, *>) dataField["cdlist"] else null
        val cd = if (cdRaw is List<*> && cdRaw.isNotEmpty() && cdRaw[0] is Map<*, *>) {
            qqToMap(cdRaw[0])
        } else {
            null
        }
        if (cd == null) return null

        val songlistRaw = cd["songlist"]
        val songlist = if (songlistRaw is List<*>) songlistRaw else emptyList<Any?>()
        val songs = songlist
            .filterIsInstance<Map<*, *>>()
            .map { mapQQSong(qqNormMap(it)) }

        val cur = cd["cur_song_num"]

        return QqPage(
            songs = songs,
            total = if (cur is Number) cur.toInt() else 0,
            name = asText(cd["dissname"]),
            creator = asText(cd["nickname"]),
            cover = asText(cd["logo"]),
        )
    } catch (_: Throwable) {
        return null
    }
}

/** 获取 QQ 音乐歌单（分页 200 条累加）；失败返回 null */
suspend fun getQQPlaylist(playlistId: String): Playlist? {
    val pageSize = 200
    val first = _fetchQQPage(playlistId, 0, pageSize)
    if (first == null || first.songs.isEmpty()) return null

    val total = first.total
    val pages = if (total <= 0) 0 else (total + pageSize - 1) / pageSize

    var allSongs = first.songs
    if (pages > 1) {
        for (i in 0 until pages - 1) {
            val page = _fetchQQPage(playlistId, (i + 1) * pageSize, pageSize)
            if (page != null) allSongs = allSongs + page.songs
        }
    }

    return Playlist(
        id = playlistId,
        name = first.name,
        creator = first.creator,
        cover = first.cover,
        platform = MusicPlatform.qq,
        songs = allSongs,
        songCount = allSongs.size,
    )
}
