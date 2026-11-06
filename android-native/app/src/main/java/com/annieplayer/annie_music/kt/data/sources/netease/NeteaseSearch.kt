package com.annieplayer.annie_music.kt.data.sources.netease

import com.annieplayer.annie_music.kt.data.model.Album
import com.annieplayer.annie_music.kt.data.model.Artist
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.network.httpPostJson

// ================================================================================================
// 网易云搜索（cloudsearch：一个 POST 端点，用 `type` 区分歌曲 / 专辑 / 歌手）
// ================================================================================================

/**
 * 搜索网易云歌曲，返回统一 [Song]（platform: netease）。
 *
 * [page] 从 1 开始；[pageSize] 每页条数（网易单页上限 100）。
 */
suspend fun searchNeteaseMusic(
    query: String,
    page: Int = 1,
    pageSize: Int = 100,
): List<Song> {
    val body = neteaseQuery(
        listOf(
            "s" to query,
            "type" to "1",
            "limit" to pageSize.toString(),
            "offset" to ((page - 1) * pageSize).toString(),
        ),
    )

    val data = httpPostJson(
        "https://music.163.com/api/cloudsearch/pc",
        headers = neteaseApiHeaders,
        body = body,
    )

    val result = data["result"]
    val rawList = if (result is Map<*, *>) result["songs"] else null
    val list = if (rawList is List<*>) rawList else emptyList<Any?>()
    return neteaseMapSearchSongs(list)
}

/** 搜索网易云专辑（cloudsearch `type=10`） */
suspend fun searchNeteaseAlbum(
    query: String,
    page: Int = 1,
    pageSize: Int = 30,
): List<Album> {
    val body = neteaseQuery(
        listOf(
            "s" to query,
            "type" to "10",
            "limit" to pageSize.toString(),
            "offset" to ((page - 1) * pageSize).toString(),
        ),
    )

    val data = httpPostJson(
        "https://music.163.com/api/cloudsearch/pc",
        headers = neteaseApiHeaders,
        body = body,
    )
    val result = data["result"]
    val rawList = if (result is Map<*, *>) result["albums"] else null
    val list = if (rawList is List<*>) rawList else emptyList<Any?>()
    return neteaseMapAlbums(list)
}

/** 搜索网易云歌手（cloudsearch `type=100`） */
suspend fun searchNeteaseArtist(query: String): List<Artist> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()

    val body = neteaseQuery(
        listOf("s" to q, "type" to "100", "limit" to "30", "offset" to "0"),
    )
    val data = httpPostJson(
        "https://music.163.com/api/cloudsearch/pc",
        headers = neteaseApiHeaders,
        body = body,
    )
    val result = data["result"]
    val rawList = if (result is Map<*, *>) result["artists"] else null
    val list = if (rawList is List<*>) rawList else emptyList<Any?>()
    return neteaseMapArtists(list)
}
