package com.annieplayer.annie_music.kt.core.util

import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song

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

/**
 * 按「这首歌真实有的档位」夹取请求档位（**播放与下载共用**）。
 *
 * 为什么必须有：用户给平台配的是「默认要无损」，但**单曲**不一定有无损。不夹档就会把 `flac`
 * 原样发给取链方（落雪插件 → 平台接口），对方对"不存在的档位"通常**不报错**：
 * 酷我回加密包 `format:"mgg"`、QQ 跨源回酷我 `.mflac`，插件也可能干脆回一个低质直链 ——
 * 表现就是「听/下到的东西和设置里选的不一样」，而且不报错。
 */
fun clampQuality(platform: MusicPlatform, qualities: List<Quality>, want: Quality): Quality {
    // 两条不夹档的例外：
    // - Apple：档位体系不同（atmos / alac / aac256 不在四级台阶里），由
    //   `resolveAppleVariantWithFallback` 自己降级。
    // - qualities 为空（来源没给档位 / 老队列项）：夹档会当成"什么都没有"降到 128k，那是凭空的退步。
    if (platform == MusicPlatform.apple) return want
    if (qualities.isEmpty()) return want
    return bestQuality(want, qualities)
}

/** [clampQuality] 的 Song 版本 */
fun clampQualityToSong(song: Song, want: Quality): Quality =
    clampQuality(song.platform, song.qualities, want)

/**
 * 批量下载里「这首歌实际该请求的档位」—— 与 [clampQuality] 同一套规则。
 *
 * 下载多一层理由：批量音质面板给的是**整批的并集**，用户点了无损之后，这一批里
 * 「只有 320k」的那些歌必须被夹回去，否则落盘的会是加密包/跨源文件。
 */
fun downloadQualityFor(song: Song, want: Quality): Quality = clampQualityToSong(song, want)

/**
 * 这首歌**真实有的最高档**（只认四级台阶 `hires / flac / 320k / 128k`）。
 *
 * Apple 的 `atmos` / `alac` / `aac256` 不在这条台阶上，一律忽略；认不出来返回 null（未知）。
 */
fun highestQualityOf(qualities: List<Quality>): Quality? =
    qualities
        .filter { it in _qualityOrder }
        .minByOrNull { _qualityOrder.indexOf(it) }

/**
 * **本次实际会请求的档位**（「自动」档的解析器）。
 *
 * 国内四平台的「自动」= **这首歌自己的最高档**：一首有无损的歌在自动档下就该拿无损，
 * 而不是固定 320k。档位未知（`qualities` 为空）时才退回 320k（旧行为）。
 *
 * 这个值同时是**播放缓存的分区**（见 `MediaPlayerBackend._cacheKeyOf`）—— 两者必须用同一个
 * 函数算，否则「自动」与「手动 320k」会共用同一份分片缓存，播到别的档位的字节。
 */
fun effectivePlayQuality(platform: MusicPlatform, qualities: List<Quality>, configured: Quality?): Quality {
    if (configured != null) return clampQuality(platform, qualities, configured)
    return highestQualityOf(qualities) ?: Quality.k320k
}
