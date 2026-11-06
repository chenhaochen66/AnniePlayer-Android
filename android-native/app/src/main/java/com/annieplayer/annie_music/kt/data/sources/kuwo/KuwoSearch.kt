package com.annieplayer.annie_music.kt.data.sources.kuwo

import com.annieplayer.annie_music.kt.data.model.Album
import com.annieplayer.annie_music.kt.data.model.Artist
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.network.httpGetJson
import com.annieplayer.annie_music.kt.data.network.httpGetText
import com.annieplayer.annie_music.kt.data.sources.common.asText
import com.annieplayer.annie_music.kt.data.sources.common.decodeHtmlEntities
import com.annieplayer.annie_music.kt.data.sources.common.encodeComponent
import com.annieplayer.annie_music.kt.data.sources.common.firstTruthy
import kotlin.random.Random

// ================================================================================================
// 酷我搜索：歌曲 / 专辑 / 歌手 / 歌手头像与简介
// ================================================================================================

/** 生成请求 id（对齐 RN generateReqId 的 UUID 变体） */
internal fun kuwoReqId(): String {
    val hex = "0123456789abcdef"
    val buf = StringBuilder()
    for (i in 0 until 36) {
        when {
            i == 8 || i == 13 || i == 18 || i == 23 -> buf.append('-')
            i == 14 -> buf.append('1')
            i == 19 -> buf.append(hex[(Random.nextDouble() * 4).toInt() + 8])
            else -> buf.append(hex[(Random.nextDouble() * 16).toInt()])
        }
    }
    return buf.toString()
}

/**
 * 搜索酷我歌曲，返回统一 [Song]（platform: kuwo）。
 *
 * 保持原接口形态（`rn=100`，且第 1 页不带 `pn`）。
 * 注意：酷我的 `pn` 是 **0 基**（`pn=0` 才是第一页），所以 [page] > 1 时传 `pn=page-1`。
 */
suspend fun searchMusic(query: String, page: Int = 1): List<Song> {
    val pnParam = if (page > 1) "&pn=${page - 1}" else ""
    val url =
        "http://search.kuwo.cn/r.s?cluster=0&strategy=2012&vermerge=1&stype=name" +
            "&show_copyright_off=1&rn=100$pnParam" +
            "&all=${encodeComponent(query)}" +
            "&encoding=utf8&rformat=json&vipver=MUSIC_8.7.7.0_BCS37&ft=music" +
            "&newver=1&client=kt&_=${System.currentTimeMillis()}"

    val text = httpGetText(url, mapOf("User-Agent" to kuwoAndroidUa))
    val list = kuwoParseList(text, "abslist|list")
    return kuwoMapSearchSongs(list)
}

/** 搜索专辑，返回 [Album] 列表（对齐 RN searchAlbum 返回结构） */
suspend fun searchAlbum(keyword: String, page: Int = 1, pageSize: Int = 50): List<Album> {
    // 酷我专辑搜索的 rn 上限为 15：rn >= 20 时会返回空的 abslist（实测 rn=20/25/30/50 全空）
    val rn = if (pageSize > 15) 15 else pageSize
    val url =
        "http://search.kuwo.cn/r.s?ft=album&all=${encodeComponent(keyword)}" +
            "&pn=$page&rn=$rn&rformat=json&client=kt&vipver=1&encoding=utf8" +
            "&cluster=0&strategy=2012"

    val text = httpGetText(url, mapOf("User-Agent" to kuwoAndroidUa))
    val list = kuwoParseList(text, "albumlist|list")

    return list.map { item ->
        Album(
            albumid = asText(item["albumid"]),
            name = decodeHtmlEntities(
                kuwoStrOr(firstTruthy(listOf(item["name"], item["NAME"])), "未知专辑"),
            ),
            artist = decodeHtmlEntities(
                kuwoStrOr(firstTruthy(listOf(item["artist"], item["ARTIST"])), "未知歌手"),
            ),
            platform = MusicPlatform.kuwo,
            musiccnt = kuwoStrOr(
                firstTruthy(listOf(item["musiccnt"], item["MUSICCNT"])),
                "0",
            ),
            pub = kuwoStrOr(firstTruthy(listOf(item["pub"], item["PUB"])), ""),
            pic = kuwoStrOr(firstTruthy(listOf(item["pic"], item["PIC"])), ""),
            img = kuwoStrOr(firstTruthy(listOf(item["img"], item["IMG"])), ""),
            htsImg = kuwoStrOr(firstTruthy(listOf(item["hts_img"], item["HTS_IMG"])), ""),
            company = kuwoStrOr(firstTruthy(listOf(item["company"], item["COMPANY"])), ""),
            lang = kuwoStrOr(firstTruthy(listOf(item["lang"], item["LANG"])), ""),
            vip = kuwoStrOr(firstTruthy(listOf(item["vip"], item["VIP"])), ""),
            playcnt = kuwoStrOr(item["PLAYCNT"], "0"),
        )
    }
}

/** 搜索歌手，返回 [Artist] 列表；失败返回空 */
suspend fun searchArtist(keyword: String): List<Artist> {
    val reqId = kuwoReqId()
    val encodedKey = encodeComponent(keyword)
    val url =
        "$kuwoApiBase/search/searchArtistBykeyWord" +
            "?key=$encodedKey&pn=1&rn=30&httpsStatus=1" +
            "&reqId=$reqId&plat=web_www&from="
    return try {
        val resp = httpGetJson(
            url,
            mapOf(
                "User-Agent" to kuwoDesktopUa,
                "Accept" to "application/json, text/plain, */*",
                "sec-ch-ua-platform" to "\"Android\"",
                "Secret" to "5420e9d5615d1142b37ce4f778c0fc5466bbbf857cb7f1333cd123c1b5de34e80565f09f",
                "sec-ch-ua" to "\"Android WebView\";v=\"149\", \"Chromium\";v=\"149\", \"Not)A;Brand\";v=\"24\"",
                "sec-ch-ua-mobile" to "?1",
                "X-Requested-With" to "idm.internet.download.manager.plus",
                "Sec-Fetch-Site" to "same-origin",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Dest" to "empty",
                "Referer" to "https://www.kuwo.cn/search/singers?key=$encodedKey",
                "Accept-Language" to "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7",
                "Cookie" to "Hm_Iuvt_cdb524f42f23cer9b268564v7y735ewrq2324=wpbwpzyimYDf3zMyntNRKMm45QXX3XQd",
            ),
        )
        if (kuwoInt(resp["code"]) != 200) return emptyList()
        val data = resp["data"]
        val listRaw = if (data is Map<*, *>) {
            firstTruthy(listOf(data["artistList"], data["list"]))
        } else {
            null
        }
        val rawList = kuwoMapList(listRaw)
        kuwoMapArtists(rawList)
    } catch (_: Throwable) {
        emptyList()
    }
}

/** 酷我歌手头像：用歌手搜索按名字取（酷我 artistInfo 接口返回空，不可用） */
suspend fun fetchKuwoArtistAvatar(name: String): String {
    val q = name.trim()
    if (q.isEmpty()) return ""
    return try {
        val list = searchArtist(q)
        for (a in list) {
            if (a.name == q && a.picture.isNotEmpty()) return a.picture
        }
        if (list.isNotEmpty()) list.first().picture else ""
    } catch (_: Throwable) {
        ""
    }
}

/**
 * 酷我歌手简介：走经典 `r.s` 歌手搜索的 `abslist[].desc`。
 *
 * 注意：[searchArtist] 走的是 `/api/www/search/searchArtistBykeyWord`，那条响应**不带** `desc`，
 * 所以简介必须用这个经典接口单独取一次。
 */
suspend fun fetchKuwoArtistBio(name: String): String {
    val q = name.trim()
    if (q.isEmpty()) return ""
    return try {
        val url =
            "http://search.kuwo.cn/r.s?ft=artist&all=${encodeComponent(q)}" +
                "&rn=5&pn=1&rformat=json&encoding=utf8"
        val text = httpGetText(url, mapOf("User-Agent" to kuwoDesktopUa))
        val list = kuwoParseList(text, "abslist|list")
        // 优先精确同名，否则取第一条
        val hit = list.firstOrNull { asText(it["ARTIST"]) == q } ?: list.firstOrNull()
        val raw = (hit?.get("desc") ?: "").toString()
        // 伪 JSON 里换行是转义的 `\n`（可能被多重转义），统一还原成空格并清掉残留下划线
        decodeHtmlEntities(
            raw.replace(Regex("\\\\+n"), " ")
                .replace(Regex("\\\\+"), "")
                .replace(Regex("\\s+"), " ")
                .trim(),
        )
    } catch (_: Throwable) {
        ""
    }
}
