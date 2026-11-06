package com.annieplayer.annie_music.kt.data.sources.home

import com.annieplayer.annie_music.kt.data.model.HomeCardStyle
import com.annieplayer.annie_music.kt.data.model.HomeItem
import com.annieplayer.annie_music.kt.data.model.HomeShelf
import com.annieplayer.annie_music.kt.data.model.HomeTarget
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.network.cacheService
import com.annieplayer.annie_music.kt.data.network.httpGetJson
import com.annieplayer.annie_music.kt.data.network.httpGetText
import com.annieplayer.annie_music.kt.data.sources.common.decodeHtmlEntities

// ================================================================================================
// 酷我主页（契约与调度见 HomeFeedApi.kt；feed 的解析在 KuwoHotPage.kt）
// ================================================================================================

/**
 * 酷我首页：**推荐流 + 热门流一起用**，都拿不到才退 web 接口。
 *
 * - 推荐流 `mgxh.s?type=tuijian11`：歌曲行 ×2（各 28 首）+ **44 个**歌单，是首页「推荐」tab 的真实数据源；
 * - 热门流 `mgxh.s?type=hotpage`：编辑精选歌单 + 新歌 + 新碟；
 * - 两条都失败才退到老的 web 接口 `www.kuwo.cn/api/www/rcm/index/playlist`（只给一行推荐歌单）。
 *
 * 这两条**不是备胎关系**：以前热门流只在推荐流拿不到时才用，结果编辑精选 / 新歌 / 新碟三行
 * 平时永远不出现，首页看着比服务端实际给的内容少一大截。现在两块内容合并展示（[mergeKuwoShelves]）。
 *
 * 两条 feed 都**免签名、免登录**（`uid` / `user` / `q36` / `oaid` 可以全不带）。`tuijian11` 唯一
 * 的硬要求是 `notrace=0` —— 少了这个参数服务端只回一个 `rcm_page_end` 空流。解析与映射在
 * `KuwoHotPage.kt`（纯函数，有单测）。
 */
internal suspend fun fetchHomeKuwo(): List<HomeShelf> {
    val tuijian = cachedKuwoFeed(kuwoTuijianUrl, "tuijian")?.let { kuwoShelvesOf(it) }.orEmpty()
    val hotPage = cachedKuwoFeed(kuwoHotPageUrl, "hotpage")?.let { kuwoShelvesOf(it) }.orEmpty()
    val merged = runCatching { mergeKuwoShelves(tuijian, hotPage) }
        .getOrDefault(if (tuijian.isNotEmpty()) tuijian else hotPage)
    if (merged.isNotEmpty()) return merged
    return fetchHomeKuwoWeb()
}

/** 解析失败（服务端改结构 / 半截响应）一律当「没有内容」，交给下一级数据源。 */
private fun kuwoShelvesOf(xml: String): List<HomeShelf> =
    runCatching { kuwoHotShelves(parseKuwoHotPage(xml)) }.getOrDefault(emptyList())

/** 主页 feed 原始 XML（缓存整份文本：映射逻辑改动后旧缓存仍然可用）。 */
private suspend fun cachedKuwoFeed(url: String, keySuffix: String): String? {
    val key = HomeCachePrefix + "kuwo:" + keySuffix
    cacheService.get<String>(key)?.takeIf { it.isNotEmpty() }?.let { return it }
    val fresh = runCatching { httpGetText(url) }.getOrNull() ?: return null
    // 服务端偶尔回一个只有「推荐页结束标记」的短流，那种不缓存也不当数据用
    if (fresh.length < 1024) return null
    cacheService.set(key, fresh, HomeCacheTtlMs)
    return fresh
}

/**
 * 推荐流地址。
 *
 * `notrace=0` 是**必需**项：它告诉服务端「这条不是打点请求」，缺了就直接回空流
 * （`<section type="rcm_page_end">`）。`apiv=20` 与 `platform=ar` 同样不能少。
 * 反过来，登录态与设备指纹都不是必需的 —— 所以这条能进代码。
 */
private const val kuwoTuijianUrl: String =
    "http://homepages.kuwo.cn/mgxh.s?type=tuijian11&page=1&apiv=20&notrace=0&platform=ar"

/**
 * 热门流地址（推荐流的备胎）。
 *
 * `type=hotpage` 只认 `type`，连 `notrace` 都不用带；给的是编辑精选歌单 + 新歌 + 新碟。
 */
private const val kuwoHotPageUrl: String =
    "http://homepages.kuwo.cn/mgxh.s?type=hotpage&start=0&count=30&platform=ar" +
        "&prod=kwplayer_ar_12.0.4.0&corp=kuwo&apiversion=54&uid=0"

/** 酷我 web 端 api/www 系列的兜底：只给一行推荐歌单。 */
private suspend fun fetchHomeKuwoWeb(): List<HomeShelf> {
    val key = HomeCachePrefix + "kuwo:playlist"
    val resp = cacheService.get<Map<String, Any?>>(key) ?: run {
        val fresh = runCatching {
            httpGetJson(
                "https://www.kuwo.cn/api/www/rcm/index/playlist" +
                    "?rn=$HomePlaylistsMax&pn=1&httpsStatus=1&reqId=home" +
                    "&plat=web_www&from=",
                kuwoWebHeaders,
            )
        }.getOrNull()
        if (fresh != null && fresh.isNotEmpty()) cacheService.set(key, fresh, HomeCacheTtlMs)
        fresh
    } ?: return emptyList()

    val list = jsonList(jsonMap(resp["data"])?.get("list"))
    val items = list.mapNotNull { raw ->
        val m = jsonMap(raw) ?: return@mapNotNull null
        val id = jsonStr(m["id"])
        val name = decodeHtmlEntities(jsonStr(m["name"]))
        if (id.isEmpty() || name.isEmpty()) return@mapNotNull null
        // 酷我封面 500/700 都有，这里统一取 500（与其它平台一个档）
        val cover = jsonStr(m["img500"]).ifEmpty { jsonStr(m["img"]) }
        val total = jsonLong(m["total"])
        val listen = jsonLong(m["listencnt"])
        HomeItem(
            key = "kuwo:playlist:$id",
            title = name,
            subtitle = if (total > 0L) "$total 首" else "",
            cover = cover,
            badge = formatCount(listen).takeIf { it.isNotEmpty() },
            style = HomeCardStyle.Playlist,
            target = HomeTarget.Playlist(MusicPlatform.kuwo, id, name, cover),
        )
    }
    if (items.isEmpty()) return emptyList()
    return listOf(
        HomeShelf.Cards(
            id = "kuwo:playlist",
            title = "推荐歌单",
            subtitle = "酷我编辑精选",
            items = items.take(HomePlaylistsMax),
        ),
    )
}

/** 酷我 web 端 api/www 系列接口的固定请求头（Secret + cookie 是项目里已验证的那一对）。 */
private val kuwoWebHeaders: Map<String, String> = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "Accept" to "application/json, text/plain, */*",
    "Secret" to "5420e9d5615d1142b37ce4f778c0fc5466bbbf857cb7f1333cd123c1b5de34e80565f09f",
    "Origin" to "https://www.kuwo.cn",
    "Referer" to "https://www.kuwo.cn/",
    "Cookie" to "Hm_Iuvt_cdb524f42f23cer9b268564v7y735ewrq2324=wpbwpzyimYDf3zMyntNRKMm45QXX3XQd",
)
