package com.annieplayer.annie_music.kt.data.sources.kuwo

import com.annieplayer.annie_music.kt.data.model.Album
import com.annieplayer.annie_music.kt.data.network.cacheService
import com.annieplayer.annie_music.kt.data.network.httpGetJson
import com.annieplayer.annie_music.kt.data.network.httpGetText

// ================================================================================================
// 酷我歌手：单曲分页 / 全部专辑
// ================================================================================================

/** 获取歌手歌曲（分页 20 条）；失败返回空列表与 total=0 */
suspend fun getArtistSongs(artistId: String, page: Int = 1): KuwoArtistSongs {
    // 歌手歌曲接口（对齐 LX kw/artist.js 的 stype=artist2music）：伪 JSON，歌曲在 musiclist，带 MINFO
    // 注意 pn 是 0 基；旧 web 接口 artistMusic 的曲目没有 MINFO → 歌手页音质取不到大小
    val url =
        "http://search.kuwo.cn/r.s?stype=artist2music&artistid=$artistId" +
            "&pn=${page - 1}&rn=20&encode=utf8&rformat=json" +
            "&vipver=MUSIC_8.7.7.0_BCS37&newver=1&client=kt"
    return try {
        val root = kuwoParseRoot(httpGetText(url, mapOf("User-Agent" to kuwoAndroidUa)))
            ?: return KuwoArtistSongs(emptyList(), 0)
        val total = kuwoInt(root["total"])
        val songs = kuwoMapList(root["musiclist"]).map { kuwoMapSong(it) }
        KuwoArtistSongs(songs = songs, total = total)
    } catch (_: Throwable) {
        KuwoArtistSongs(emptyList(), 0)
    }
}

/**
 * 获取歌手全部专辑；失败返回空。
 *
 * 主源 `stype=albumlist` 的条目带 `musiccnt`；web `artist/artistAlbum` 没有曲目数字段
 * （用它卡片会恒显示 0 首），仅作主源失效时的兜底。
 */
suspend fun getArtistAlbums(artistId: String): List<Album> {
    // v2：v1 缓存的是无曲目数的旧结构，命中会继续显示 0 首
    val cacheKey = "artist:albums:v2:$artistId"
    val cached = cacheService.get<List<Any?>>(cacheKey)
    if (cached != null) return kuwoMapAlbums(cached)

    var rawList = _fetchArtistAlbumsRaw(artistId)
    if (rawList.isEmpty()) rawList = _fetchArtistAlbumsRawFallback(artistId)
    if (rawList.isEmpty()) return emptyList()
    cacheService.set(cacheKey, rawList, kuwoArtistCacheTtl)
    return kuwoMapAlbums(rawList)
}

/** 歌手专辑主源（`stype=albumlist`；一页 100，取不满即到底） */
private suspend fun _fetchArtistAlbumsRaw(artistId: String): List<Map<String, Any?>> {
    val out = ArrayList<Map<String, Any?>>()
    try {
        var page = 0
        while (page < 20) {
            val url =
                "http://search.kuwo.cn/r.s?stype=albumlist&artistid=$artistId" +
                    "&pn=$page&rn=100&rformat=json&encoding=utf8" +
                    "&vipver=MUSIC_8.7.7.0_BCS37&newver=1&client=kt&pcmp4=1"
            val items = kuwoParseList(
                httpGetText(url, mapOf("User-Agent" to kuwoDesktopUa)),
                "albumlist",
            )
            out += items
            if (items.size < 100) break
            page += 1
        }
    } catch (_: Throwable) {
        return out
    }
    return out
}

/** 歌手专辑兜底源（web `artist/artistAlbum`；无曲目数） */
private suspend fun _fetchArtistAlbumsRawFallback(artistId: String): List<Map<String, Any?>> {
    return try {
        val reqId = kuwoReqId()
        val url =
            "$kuwoApiBaseAlbum/artist/artistAlbum?artistid=$artistId&pn=1" +
                "&rn=100000&httpsStatus=1&reqId=$reqId&plat=web_www&from="
        val resp = httpGetJson(
            url,
            mapOf(
                "User-Agent" to kuwoDesktopUa,
                "Connection" to "Keep-Alive",
                "Accept" to "application/json, text/plain, */*",
                "Accept-Encoding" to "identity",
                "Cache-Control" to "no-cache, no-store, must-revalidate",
                "Origin" to "https://www.kuwo.cn",
                "Pragma" to "no-cache",
                "Range" to "bytes=0-",
                "Referer" to "https://www.kuwo.cn/",
                "sec-ch-ua" to "\"Android WebView\";v=\"149\", \"Chromium\";v=\"149\", \"Not)A;Brand\";v=\"24\"",
                "sec-ch-ua-mobile" to "?1",
                "sec-ch-ua-platform" to "\"Android\"",
            ),
        )
        if (kuwoInt(resp["code"]) != 200) return emptyList()
        val dataRaw = resp["data"]
        val data = if (dataRaw is Map<*, *>) dataRaw else emptyMap<String, Any?>()
        kuwoMapList(data["albumList"])
    } catch (_: Throwable) {
        emptyList()
    }
}
