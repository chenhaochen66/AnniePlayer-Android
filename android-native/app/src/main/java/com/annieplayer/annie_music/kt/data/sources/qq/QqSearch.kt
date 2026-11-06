package com.annieplayer.annie_music.kt.data.sources.qq

import com.annieplayer.annie_music.kt.data.model.Album
import com.annieplayer.annie_music.kt.data.model.Artist
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.network.httpPostJson

// ================================================================================================
// QQ 搜索（musicu `DoSearchForQQMusicMobile`，一个端点用 search_type 区分三类）
// ================================================================================================

/**
 * 拉取 QQ 搜索单页原始条目（[pageSize] ≤ 60）。
 *
 * musicu 三个坑：`grp` 必须 "1"（否则 item_song 为空）；`page_num` 必须是数字
 * （字符串会被忽略、永远第 1 页）；结果在 `body.item_song`，直接是数组、无 `.list`。
 */
private suspend fun _fetchQQSearchRawPage(
    query: String,
    page: Int,
    pageSize: Int,
    searchType: Int = 0,
    bodyKey: String = "item_song",
): List<Any?> {
    val payload = mapOf(
        "req_0" to mapOf(
            "module" to "music.search.SearchCgiService",
            "method" to "DoSearchForQQMusicMobile",
            "param" to mapOf(
                "query" to query,
                // 整数枚举：0 歌曲 / 1 歌手 / 2 专辑
                "search_type" to searchType,
                "num_per_page" to pageSize,
                // 必须是数字：字符串会被忽略（永远第 1 页）
                "page_num" to page,
                // 必须保留：去掉后 code=0 但 item_song 为空
                "grp" to "1",
            ),
        ),
        "comm" to mapOf(
            "guid" to qqMusicuGuid,
            "ct" to 19,
            "cv" to 1914,
        ),
    )
    return try {
        val data = httpPostJson(qqMusicuUrl, qqHeaders, payload)
        val req0 = data["req_0"]
        val body = if (req0 is Map<*, *>) {
            val d = req0["data"]
            if (d is Map<*, *>) d["body"] else null
        } else {
            null
        }
        val list = if (body is Map<*, *>) body[bodyKey] else null
        if (list is List<*>) list.toList() else emptyList()
    } catch (_: Throwable) {
        emptyList()
    }
}

/**
 * 搜索 QQ 音乐单曲。
 *
 * 搜索接口的条目字段与歌单接口完全一致（songmid / songname / singer[0].name / albummid /
 * albumname / interval / size128·320·flac），因此直接复用 [mapQQSong]。
 *
 * [page] 从 1 开始；[pageSize] 每页条数（单次请求上限 60，超出分多次拉取再切片）。
 */
suspend fun searchQQMusic(query: String, page: Int = 1, pageSize: Int = 100): List<Song> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()

    val perRequest = 60
    val start = (page - 1) * pageSize
    val skip = start % perRequest
    val need = skip + pageSize

    val collected = mutableListOf<Any?>()
    var qqPage = start / perRequest + 1
    while (collected.size < need) {
        val batch = _fetchQQSearchRawPage(q, qqPage, perRequest)
        if (batch.isEmpty()) break
        collected.addAll(batch)
        qqPage++
        if (batch.size < perRequest) break
    }

    val list = collected.drop(skip).take(pageSize)
    return qqMapSearchSongs(list)
}

/** 搜索 QQ 音乐专辑 */
suspend fun searchQQAlbum(query: String, page: Int = 1, pageSize: Int = 30): List<Album> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()

    val list = _fetchQQSearchRawPage(q, page, pageSize, searchType = 2, bodyKey = "item_album")
    if (list.isEmpty()) return emptyList()
    return qqMapAlbums(list)
}

/** 搜索 QQ 音乐歌手（条目带 `singerPic`） */
suspend fun searchQQArtist(query: String): List<Artist> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()

    val list = _fetchQQSearchRawPage(q, 1, 30, searchType = 1, bodyKey = "singer")
    if (list.isEmpty()) return emptyList()
    return qqMapArtists(list)
}
