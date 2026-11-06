package com.annieplayer.annie_music.kt.data.sources.qq

import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.sources.common.asText
import com.annieplayer.annie_music.kt.data.sources.common.firstTruthy
import com.annieplayer.annie_music.kt.data.sources.common.jsTruthy

// ================================================================================================
// QQ 音质判定（下游唯一依据）
// ================================================================================================
//
// 这张表是下游的唯一依据：单曲下载的音质选择器（`QualityPicker` 见空列表只会给 128k）、
// 播放档位夹取（`bestAvailableAtOrBelow`）、落雪插件的 `types`/`_types` 全读它 ——
// 漏填就表现为「这首歌不能下载无损」「插件解析失败放不出来」。

private fun _numOrNull(v: Any?): Long? = when (v) {
    is Number -> v.toLong()
    is String -> v.trim().toLongOrNull()
    else -> null
}

/**
 * QQ 条目的 `file.media_mid`（媒体 mid）。
 *
 * 它和 songmid **不是一回事**（《晴天》`songmid=0039MnYb0qxYhV` / `media_mid=003Qui1q2u1Zho`）。
 * 内置直链接口按 songmid 就能解析，但落雪插件普遍直接读 `info.musicInfo.strMediaMid`，
 * 拿 songmid 顶替会指向不存在的文件 → 插件解析失败、放不出声。取不到时返回 null。
 */
internal fun qqMediaMid(s: Map<String, Any?>): String? =
    asText(firstTruthy(listOf(qqDeep(s, "file", "media_mid"), s["media_mid"])))
        .takeIf { it.isNotEmpty() }

/** 各档位字节数（QQ 的 `sizeflac`/`size320`/`size128` 值就是字节数；`file.*` 为备用字段） */
internal fun qqQualitySizes(s: Map<*, *>): Map<Quality, Long> {
    val file = s["file"] as? Map<*, *>
    val out = HashMap<Quality, Long>()

    fun take(q: Quality, vararg keys: String) {
        for (k in keys) {
            val n = _numOrNull(s[k]) ?: continue
            if (n > 0L) {
                out[q] = n
                return
            }
        }
    }

    take(Quality.flac, "sizeflac", "size_flac")
    take(Quality.k320k, "size320", "size_320mp3")
    take(Quality.k128k, "size128", "size_128mp3")
    if (file != null) {
        fun fileTake(q: Quality, key: String) {
            if (q in out) return
            val n = _numOrNull(file[key]) ?: return
            if (n > 0L) out[q] = n
        }
        fileTake(Quality.flac, "size_flac")
        fileTake(Quality.k320k, "size_320mp3")
        fileTake(Quality.k128k, "size_128mp3")
    }
    return out
}

/**
 * 该条目**真实可用档位**（高 → 低，末位兜底 128k）。
 *
 * 旧接口（歌单 / 搜索）把 `sizeflac`/`size320`/`size128` 平铺在顶层；musicu 的
 * `songInfo`（歌手单曲）收进 `file.*`，两种都要认。
 */
internal fun qqQualities(s: Map<String, Any?>): List<Quality> {
    val out = mutableListOf<Quality>()
    if (jsTruthy(firstTruthy(listOf(s["sizeflac"], qqDeep(s, "file", "size_flac"))))) {
        out.add(Quality.flac)
    }
    if (jsTruthy(firstTruthy(listOf(s["size320"], qqDeep(s, "file", "size_320mp3"))))) {
        out.add(Quality.k320k)
    }
    if (jsTruthy(firstTruthy(listOf(s["size128"], qqDeep(s, "file", "size_128mp3"))))) {
        out.add(Quality.k128k)
    }
    if (out.isEmpty()) out.add(Quality.k128k)
    return out
}
