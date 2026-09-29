package com.annieplayer.annie_music.kt.core.util

import com.annieplayer.annie_music.kt.data.model.Quality

/**
 * 音质选择工具（1:1 移植 Flutter `core/utils/quality_util.dart`，对齐 RN 版 utils/quality.ts）。
 */

/** 音质从高到低排序（对齐 Dart `_qualityOrder`，覆盖全部四档） */
private val _qualityOrder: List<Quality> = listOf(
    Quality.hires,
    Quality.flac,
    Quality.k320k,
    Quality.k128k,
)

/**
 * 期望音质不可用时降级到更低音质；全部不可用则取最高可用。
 *
 * Dart 的 `Quality` 共四档（k128k / k320k / flac / hires），此处逐档覆盖。
 */
fun bestQuality(desired: Quality, available: List<Quality>): Quality {
    val desiredIdx = _qualityOrder.indexOf(desired)
    if (desiredIdx == -1) return if (available.isNotEmpty()) available.first() else Quality.k128k

    for (i in desiredIdx until _qualityOrder.size) {
        if (available.contains(_qualityOrder[i])) return _qualityOrder[i]
    }
    for (q in _qualityOrder) {
        if (available.contains(q)) return q
    }
    return Quality.k128k
}
