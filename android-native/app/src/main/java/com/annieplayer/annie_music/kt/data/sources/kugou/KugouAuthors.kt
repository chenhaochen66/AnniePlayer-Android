package com.annieplayer.annie_music.kt.data.sources.kugou

import com.annieplayer.annie_music.kt.data.network.cacheService
import com.annieplayer.annie_music.kt.data.network.httpGetJson
import com.annieplayer.annie_music.kt.data.sources.common.decodeHtmlEntities

// ================================================================================================
// 酷狗逐曲歌手（供「跳转歌手」用）
// ================================================================================================
//
// 背景：酷狗的**专辑接口与歌单接口都不返回任何歌手 id**（专辑响应里连歌手字段都没有，
// `v3/album_audio/audio` 也只给 `author_name`）。实测可用的是移动端单曲信息接口：
//
// `http://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash=<歌曲hash>`
// → `authors:[{author_id, author_name, avatar, ...}]`（**无需签名/key，纯 GET**）
//
// 该接口**只支持单曲**（不能批量），因此实现上按需调用：只在用户点击「跳转歌手」时查一次，
// 结果按 hash 落盘缓存，之后同一首歌永久秒开。

suspend fun fetchKugouSongAuthors(songHash: String): List<KugouAuthor> {
    if (songHash.isEmpty()) return emptyList()
    val cacheKey = "kugou:authors:v1:$songHash"
    cacheService.get<List<Any?>>(cacheKey)?.let { return _mapKugouAuthors(it) }
    return try {
        val data = httpGetJson(
            "http://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash=$songHash",
            headers = mapOf(
                "User-Agent" to kugouWebUa,
                "Referer" to "http://m.kugou.com/",
            ),
        )
        val raw = data["authors"]
        val list: List<Any?> = if (raw is List<*>) raw.map { it } else emptyList()
        if (list.isNotEmpty()) cacheService.set(cacheKey, list, kugouAuthorsCacheTtl)
        _mapKugouAuthors(list)
    } catch (_: Throwable) {
        emptyList()
    }
}

private fun _mapKugouAuthors(raw: Any?): List<KugouAuthor> {
    val list = if (raw is List<*>) raw else return emptyList()
    val out = ArrayList<KugouAuthor>()
    for (item in list) {
        if (item !is Map<*, *>) continue
        val id = (item["author_id"] ?: "").toString().trim()
        val name = decodeHtmlEntities((item["author_name"] ?: "").toString().trim())
        if (id.isNotEmpty() && name.isNotEmpty()) out.add(KugouAuthor(id, name))
    }
    return out
}
