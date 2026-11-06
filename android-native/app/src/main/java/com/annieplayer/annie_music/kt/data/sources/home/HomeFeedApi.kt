package com.annieplayer.annie_music.kt.data.sources.home

import com.annieplayer.annie_music.kt.data.model.HomeShelf
import com.annieplayer.annie_music.kt.data.model.MusicPlatform

// ================================================================================================
// 流媒体主页：契约 + 注册表 + 各平台共用的搬运工具
// ================================================================================================
//
// ## 为什么「一个用途一个文件夹、一个平台一个文件」
// 主页是**一个用途**（给每个平台的 tab 出 HomeShelf），但每家的取数与映射完全不同：
// QQ 一次 `musicu.fcg` 请求里塞五个模块、酷我解析 XML feed、酷狗要 android 签名、网易云是
// 三个独立接口。所以这里只留**契约与调度**，实现分到：
//
//   NeteaseHome.kt / QqHome.kt / KuwoHome.kt / KugouHome.kt
//   KuwoHotPage.kt   ← 酷我 feed 的纯解析（无网络，有单测）
//
// 这与 `lyric/` 是同一个形状：`LyricSource.kt` 放契约与注册表，每平台一个实现文件。
// 判据是「实现只借平台已导出的函数、不碰平台内部件」—— 主页满足（只调
// `_mapQQArtistSong` / `_mapNeteaseArtistSong` 这类已 open 的 mapper），所以用途文件夹成立。
//
// ## 为什么不复用各平台文件里的映射函数（重写一份）
// 歌曲必须复用（`_mapQQArtistSong` / `_mapNeteaseArtistSong` 已 open 为 internal）：它们是唯一
// 知道「音质档位 / media_mid / 封面 800」这些会影响播放与下载的地方，另写一份必然漏字段
// （历史上 `qualities` 漏填导致音质只剩 128k、插件解析全挂）。
// 专辑 / 歌单只做**展示映射**（标题 / 副标题 / 封面 + 详情 id），不需要构造 Album / Playlist
// 这类重对象 —— 主页列表里多存几十个只是浪费内存。
//
// ## 缓存
// 缓存的是**原始响应**（JSON Map 或 XML 文本，不是映射后的对象）：映射逻辑改动后旧缓存仍然
// 可用，且 `CacheService` 存的就是 JSON 形状，形状天然吻合。TTL 10 分钟。
// key 前缀 `home:v3:` + 平台名，由各平台文件里的 `cachedXxx` 负责 —— **前缀不要改**
// （改了就白丢一次缓存）。

/** 歌曲区块最多展示的歌曲数：**有 50 就 50，没有再有多少显示多少**（调用方 take）。 */
const val HomeSongsMax: Int = 50

/** 歌单区块最多展示的卡片数（网易云 `personalized/playlist` 一次就能给 30）。 */
internal const val HomePlaylistsMax: Int = 30

/**
 * 酷我歌单墙的上限：它**一页就给 44 个**（`r10_songlist_tab`），套用 [HomePlaylistsMax]=30
 * 会白丢 14 个。UI 端每行只画 10 张、其余进「全部」页，所以放宽不会把首屏拉长。
 */
internal const val KuwoPlaylistsMax: Int = 60

/** 其它卡片区块（专辑 / 歌手）的上限。 */
internal const val HomeCardsMax: Int = 20

/** 主页缓存 TTL：10 分钟（首页内容更新频率低，切来切去不该反复打接口）。 */
internal const val HomeCacheTtlMs: Long = 10 * 60 * 1000L

/**
 * 专辑曲目数的缓存 TTL：24 小时。
 *
 * 比主页内容长得多 —— 一张专辑的曲目数几乎不会变，而补它是要**逐张**发请求的。
 */
internal const val HomeAlbumCountTtlMs: Long = 24 * 60 * 60 * 1000L

/**
 * 会出现在卡面上的专辑张数（与 `HomeFeed.kt` 的 `HomeVisibleCards` 保持一致）。
 *
 * 只给这么多张补曲目数：看不见的没必要请求，也不能让首屏等 20 个请求。
 */
internal const val HomeVisibleAlbumCount: Int = 10

internal const val HomeCachePrefix = "home:v3:"

/**
 * 拉取某个平台的主页区块。
 *
 * @param kugouDiscoverToken 酷狗「为你挑选」的刷新代数，只用于区分缓存
 */
suspend fun fetchHomeShelves(
    platform: MusicPlatform,
    kugouDiscoverToken: Int = 0,
): List<HomeShelf> = when (platform) {
    MusicPlatform.netease -> fetchHomeNetease()
    MusicPlatform.qq -> fetchHomeQQ()
    MusicPlatform.kuwo -> fetchHomeKuwo()
    MusicPlatform.kugou -> fetchHomeKugou(kugouDiscoverToken)
    MusicPlatform.qobuz,
    MusicPlatform.apple,
    MusicPlatform.kkbox,
    MusicPlatform.local,
    -> emptyList()
}

// ================================================================================================
// 各平台响应都是「Map / List 套 Map」的形状，统一走这四个
// ================================================================================================

/** JSON 对象 → `Map`；不是对象返回 null（调用方用 `?: return` 决定丢不丢这一条）。 */
internal fun jsonMap(value: Any?): Map<String, Any?>? = when (value) {
    is Map<*, *> -> value.entries
        .mapNotNull { e -> (e.key as? String)?.let { it to e.value } }
        .toMap()
    else -> null
}

/** JSON 数组 → `List`；不是数组返回空表。 */
internal fun jsonList(value: Any?): List<Any?> = value as? List<Any?> ?: emptyList()

/** 取字符串并 **trim**（主页所有标题 / id 都走它：接口偶尔带首尾空白）。 */
internal fun jsonStr(value: Any?): String = value?.toString()?.trim() ?: ""

/** 取数字（数字与数字字符串都认，其余 0）。 */
internal fun jsonLong(value: Any?): Long = when (value) {
    is Number -> value.toLong()
    is String -> value.trim().toLongOrNull() ?: 0L
    else -> 0L
}

/** 歌单卡的第二行：`5027万播放 · 284 首`（两项都没有就空串）。 */
internal fun playlistSubtitle(playCount: Long, trackCount: Long): String {
    val play = formatCount(playCount)
    val tracks = if (trackCount > 0L) "$trackCount 首" else ""
    return listOf(play + "播放", tracks)
        .filter { it.isNotBlank() && !it.startsWith("播放") }
        .joinToString(" · ")
}

/** 播放量：万 / 亿；超过 100 个单位后去掉小数（`5027万` 而不是 `5027.2万`，卡片角标放不下）。 */
internal fun formatCount(n: Long): String = when {
    n <= 0L -> ""
    n >= 100_000_000L -> "${_trim1(n / 100_000_000.0)}亿"
    n >= 10_000L -> "${_trim1(n / 10_000.0)}万"
    else -> n.toString()
}

private fun _trim1(value: Double): String {
    val rounded = kotlin.math.floor(value * 10.0) / 10.0
    if (rounded >= 100.0) return rounded.toLong().toString()
    return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
}
