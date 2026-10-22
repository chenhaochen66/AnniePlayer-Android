package com.annieplayer.annie_music.kt.features.search

import com.annieplayer.annie_music.kt.data.model.MusicPlatform

/**
 * 单曲搜索源与双列源分组（1:1 移植 Flutter
 * `lib/features/search/` 下的 `search_sources.dart`）。
 *
 * 目前接入 4 个源（酷我 / 网易 / QQ / 酷狗）。后续补 qobuz / kkbox 单曲搜索时，
 * 只需在 [SearchSource] 加一项、在 [sourceGroups] 加一组即可。
 */

/** 可搜索的单曲源。 */
enum class SearchSource(
    /** 展示名（卡片 meta 行 / 面板按钮）。 */
    val label: String,
) {
    kuwo("酷我"),
    netease("网易"),
    qq("QQ"),
    kugou("酷狗"),
    qobuz("Qobuz"),
    kkbox("KKBOX"),
    apple("Apple Music"),
    ;
}

/** 双列源分组：[left] 为左列，[right] 为右列；[right] 为 null 时只显示一列。 */
class SourceGroup(
    val left: SearchSource,
    val right: SearchSource? = null,
) {
    /** 面板按钮文案，如 `酷我 & 网易`；单列源直接显示源名。 */
    val label: String
        get() = if (right == null) left.label else "${left.label} & ${right.label}"

    fun contains(source: SearchSource): Boolean = left == source || right == source
}

/**
 * 每页条数：四个源统一 60 条每页
 * （QQ 接口单次上限正好 60，这样四个源首屏都只需 1 次请求）。
 */
const val kSearchPageSize: Int = 60

/** Apple Music 搜索单次上限 25（官方 amp-api 的 limit 上限）。 */
const val kAppleSearchPageSize: Int = 25

/**
 * 专辑搜索支持的平台（单源选择；专辑结果是网格，所以一次只展示一个源）。
 *
 * 歌手搜索在 Flutter 侧复用同一份列表，见 [artistSearchPlatforms]。
 */
val albumSearchPlatforms: List<MusicPlatform> = listOf(
    MusicPlatform.kuwo,
    MusicPlatform.netease,
    MusicPlatform.qq,
    MusicPlatform.kugou,
    MusicPlatform.qobuz,
    MusicPlatform.apple,
)

/** 歌手搜索支持的平台，与 [albumSearchPlatforms] 同一份列表（对齐 Flutter 复用行为）。 */
val artistSearchPlatforms: List<MusicPlatform> = albumSearchPlatforms

/** 「搜索选择」面板里可选的分组（只保留两组常用搭配 + Qobuz/KBOX 一组，KKBOX 列暂空）。 */
val sourceGroups: List<SourceGroup> = listOf(
    SourceGroup(SearchSource.kuwo, SearchSource.netease),
    SourceGroup(SearchSource.qq, SearchSource.kugou),
    SourceGroup(SearchSource.qobuz, SearchSource.kkbox),
    SourceGroup(SearchSource.apple),
)
