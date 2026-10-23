package com.annieplayer.annie_music.kt.features.search

import com.annieplayer.annie_music.kt.data.model.MusicPlatform

/**
 * 搜索页的平台约定与分页参数（源自 Flutter `lib/features/search/search_sources.dart`）。
 *
 * 与 Flutter 基线的三处差异（均为需求）：
 * 1. 单曲结果是**单栏**（一行一首、大行），不再是左右双列并排两个源；
 * 2. 单曲 / 专辑 / 歌手三种模式**共用一个平台选择**（见 `SearchScreenState.searchPlatform`）：
 *    互相切换模式不改平台，面板里换平台三种模式一起换；
 * 3. 平台选择**不落盘**：重启回到 [defaultSearchPlatform]。
 */

/**
 * 每页条数：酷我 / 网易 / QQ / 酷狗统一 60 条每页
 * （QQ 接口单次上限正好 60，首屏都只需 1 次请求）。
 */
const val kSearchPageSize: Int = 60

/** Apple Music 搜索单次上限 25（官方 amp-api 的 limit 上限）。 */
const val kAppleSearchPageSize: Int = 25

/**
 * 搜索支持的平台（单曲 / 专辑 / 歌手**共用这一份**，顺序即「搜索选择」面板的行序）。
 *
 * KKBOX 暂不列入：单曲搜索未接入（会返回空列表），专辑 / 歌手也不在搜索结果里给。
 */
val searchPlatforms: List<MusicPlatform> = listOf(
    MusicPlatform.kuwo,
    MusicPlatform.netease,
    MusicPlatform.qq,
    MusicPlatform.kugou,
    MusicPlatform.qobuz,
    MusicPlatform.apple,
)

/** 默认平台（[searchPlatforms] 第一项）；不持久化，重启即回到它。 */
val defaultSearchPlatform: MusicPlatform = searchPlatforms.first()
