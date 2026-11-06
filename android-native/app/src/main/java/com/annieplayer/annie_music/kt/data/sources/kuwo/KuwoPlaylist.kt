package com.annieplayer.annie_music.kt.data.sources.kuwo

import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Playlist
import com.annieplayer.annie_music.kt.data.network.httpGetJson

// ================================================================================================
// 酷我歌单（`pl.svc`，按 100 条分页累加）
// ================================================================================================

private suspend fun _fetchKuwoPage(playlistId: String, pn: Int, pageSize: Int): KuwoPage? {
    // 官方歌单接口（对齐 LX kw/songList.js）：返回标准 JSON，顶层 result=="ok"，歌曲在 musiclist
    // 注意 pn 是 0 基；旧版 m.kuwo.cn/newh5app/wapi 那条接口已废弃（无 code、字段为 musicList）
    val url =
        "http://nplserver.kuwo.cn/pl.svc?op=getlistinfo&pid=$playlistId" +
            "&pn=${pn - 1}&rn=$pageSize&encode=utf8&keyset=pl2012" +
            "&identity=kuwo&pcmp4=1&vipver=MUSIC_9.0.5.0_W1&newver=1"
    return try {
        val resp = httpGetJson(url, mapOf("User-Agent" to kuwoDesktopUa))
        if (kuwoStrOr(resp["result"], "") != "ok") return null
        KuwoPage(
            songs = kuwoMapList(resp["musiclist"]).map { kuwoMapSong(it) },
            total = kuwoInt(resp["total"]),
            name = kuwoStrOr(resp["title"], ""),
            creator = kuwoStrOr(resp["uname"], ""),
            cover = kuwoStrOr(resp["pic"], ""),
        )
    } catch (_: Throwable) {
        null
    }
}

/** 获取酷我歌单（分页 100 条累加）；失败返回 null */
suspend fun getKuwoPlaylist(playlistId: String): Playlist? {
    val pageSize = 100
    val first = _fetchKuwoPage(playlistId, 1, pageSize)
    if (first == null || first.songs.isEmpty()) return null

    val total = first.total
    val pages = if (total <= 0) 0 else (total + pageSize - 1) / pageSize

    var allSongs = first.songs
    if (pages > 1) {
        for (i in 0 until pages - 1) {
            val page = _fetchKuwoPage(playlistId, i + 2, pageSize)
            if (page != null) allSongs = allSongs + page.songs
        }
    }

    return Playlist(
        id = playlistId,
        name = first.name,
        creator = first.creator,
        cover = first.cover.replaceFirst("/120/", "/800/"),
        platform = MusicPlatform.kuwo,
        songs = allSongs,
        songCount = allSongs.size,
    )
}
