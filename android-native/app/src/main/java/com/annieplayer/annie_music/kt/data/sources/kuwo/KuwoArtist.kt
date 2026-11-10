package com.annieplayer.annie_music.kt.data.sources.kuwo

import com.annieplayer.annie_music.kt.data.model.Album
import com.annieplayer.annie_music.kt.data.network.cacheService
import com.annieplayer.annie_music.kt.data.network.httpGetJson
import com.annieplayer.annie_music.kt.data.network.httpGetText
import com.annieplayer.annie_music.kt.data.sources.common.decodeHtmlEntities
import com.annieplayer.annie_music.kt.data.sources.common.firstTruthy
import java.util.concurrent.ConcurrentHashMap

// ================================================================================================
// 酷我歌手：单曲分页 / 全部专辑
// ================================================================================================

/** 获取歌手歌曲（分页 20 条）；失败返回空列表与 total=0 */
suspend fun getArtistSongs(artistId: String, page: Int = 1): KuwoArtistSongs =
    getArtistSongsRange(artistId, rn = 20, pn = page - 1)

/**
 * 按 `rn` / `pn` 直接取一段（「全部单曲」页续拉用，`pn` 是 0 基）。
 *
 * **`rn` 实测可到 1000**（周杰伦 1708 首 = `rn=1000` 两次请求，共 4.2 MB）；
 * `rn=200` 是「单请求 600 KB」那个折中点。续拉用 1000，少一次 RTT。
 */
suspend fun getArtistSongsRange(artistId: String, rn: Int, pn: Int): KuwoArtistSongs {
    // 歌手歌曲接口（对齐 LX kw/artist.js 的 stype=artist2music）：伪 JSON，歌曲在 musiclist，带 MINFO
    // 注意 pn 是 0 基；旧 web 接口 artistMusic 的曲目没有 MINFO → 歌手页音质取不到大小
    val url =
        "http://search.kuwo.cn/r.s?stype=artist2music&artistid=$artistId" +
            "&pn=${pn.coerceAtLeast(0)}&rn=${rn.coerceIn(1, 1000)}&encode=utf8&rformat=json" +
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

// ================================================================================================
// 歌手 id -> 规范名
// ================================================================================================
//
// 酷我的歌手名是合并串、id 是另一串，名字里的分隔符拆不准（见 `kuwoArtistCredit`），
// 按 id 反查是唯一精确的路子。
//
// 用的是 `search.kuwo.cn/r.s?stype=artistinfo&artistid=`（伪 JSON，带 `name` / `aartist` / `desc`）。
// **不是** web 的 `api/www/artist/artistInfo` —— 那条无视 `artistid`，固定返回周杰伦，不可用；
// 历史上「artistInfo 返回空」的结论只对后者成立。
//
// 没有批量形式（逗号 / 重复参数都只认第一个），所以只用于**单个歌曲的歌手补名**，
// 不要放进列表映射（一页 100 首会放大成几十个请求）。名字不会变，进程内缓存足够。

private val _artistNameCache = ConcurrentHashMap<String, String>()

/** 歌手资料（`r.s?stype=artistinfo` 的可用字段子集） */
data class KuwoArtistInfo(
    val name: String,
    val picture: String,
    val bio: String,
)

private val _artistInfoCache = ConcurrentHashMap<String, KuwoArtistInfo>()

/** 按 id 取歌手资料；失败返回 null（不缓存失败）。 */
suspend fun fetchKuwoArtistInfo(artistId: String): KuwoArtistInfo? {
    val id = artistId.trim()
    if (id.isEmpty()) return null
    _artistInfoCache[id]?.let { return it }
    return try {
        val url = "http://search.kuwo.cn/r.s?stype=artistinfo&artistid=$id&rformat=json&encoding=utf8"
        val root = kuwoParseRoot(httpGetText(url, mapOf("User-Agent" to kuwoAndroidUa))) ?: return null
        val name = decodeHtmlEntities(kuwoStrOr(root["name"], "")).replace("\\", "").trim()
        if (name.isEmpty()) return null
        val info = KuwoArtistInfo(
            name = name,
            picture = kuwoStrOr(firstTruthy(listOf(root["hts_pic"], root["pic"])), ""),
            bio = cleanKuwoDesc(kuwoStrOr(root["desc"], "")),
        )
        _artistInfoCache[id] = info
        _artistNameCache[id] = name
        info
    } catch (_: Throwable) {
        null
    }
}

/** 按 id 取规范歌手名；失败返回空串。 */
suspend fun fetchKuwoArtistName(artistId: String): String {
    val id = artistId.trim()
    if (id.isEmpty()) return ""
    _artistNameCache[id]?.let { return it }
    return fetchKuwoArtistInfo(id)?.name.orEmpty()
}

/**
 * 按 id 列表补名字。
 *
 * 任一 id 取不到就整组返回空 —— 名字与 id 必须等长且按下标对应，
 * 半对半错会让「点 A 打开 B」，比不补更糟。
 */
suspend fun resolveKuwoArtistNames(artistIds: List<String>): List<String> {
    if (artistIds.isEmpty()) return emptyList()
    val names = artistIds.map { fetchKuwoArtistName(it) }
    return if (names.all { it.isNotEmpty() }) names else emptyList()
}

/**
 * 酷我歌手头像：先按 id 取 `artistinfo.hts_pic`，取不到才退回按名字搜索。
 *
 * 名字搜索在名字带 `&` / `,` 时会搜错人（搜「Years」第一条是 Seven），所以只能当兜底。
 */
suspend fun fetchKuwoArtistAvatar(artistId: String, name: String = ""): String {
    fetchKuwoArtistInfo(artistId)?.picture?.takeIf { it.isNotEmpty() }?.let { return it }
    return fetchKuwoArtistAvatarByName(name)
}

/** 酷我歌手简介：先按 id 取 `artistinfo.desc`（一次请求同时拿到名字/头像/简介），取不到才按名字搜。 */
suspend fun fetchKuwoArtistBio(artistId: String, name: String = ""): String {
    fetchKuwoArtistInfo(artistId)?.bio?.takeIf { it.isNotEmpty() }?.let { return it }
    return fetchKuwoArtistBioByName(name)
}
