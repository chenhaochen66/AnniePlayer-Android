package com.annieplayer.annie_music.kt.data.sources.netease

import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.sources.common.jsTruthy

// ================================================================================================
// 网易云音质判定（搜索结果 / 详情 / 歌单三条路径共用）
// ================================================================================================

/** 音质顺序（与 models 中 Quality 对齐） */
private val _qualityOrder: List<Quality> = listOf(
    Quality.hires,
    Quality.flac,
    Quality.k320k,
    Quality.k128k,
)

/** 依据搜索/详情返回的歌曲 json 推断可用音质（对齐 RN parseNeteaseQualities） */
fun parseNeteaseQualities(song: Map<String, Any?>): List<Quality> {
    val result = ArrayList<Quality>()
    // 搜索结果用扁平字段 h/m/l/sq/hr；歌手单曲等旧接口用 *Music 对象，两种都要认
    if (jsTruthy(song["hr"]) || jsTruthy(song["hrMusic"])) result.add(Quality.hires)
    if (jsTruthy(song["sq"]) || jsTruthy(song["sqMusic"])) result.add(Quality.flac)
    if (jsTruthy(song["h"]) || jsTruthy(song["hMusic"])) result.add(Quality.k320k)
    if (jsTruthy(song["l"]) || jsTruthy(song["m"]) ||
        jsTruthy(song["lMusic"]) || jsTruthy(song["mMusic"])
    ) {
        result.add(Quality.k128k)
    }
    result.sortBy { _qualityOrder.indexOf(it) }
    return result
}

/** 各档位字节数（网易的 `hr`/`sq`/`h`/`l` 是对象，里面的 `size` 就是字节数） */
fun parseNeteaseQualitySizes(song: Map<String, Any?>): Map<Quality, Long> {
    val out = HashMap<Quality, Long>()

    fun put(q: Quality, vararg keys: String) {
        for (k in keys) {
            val v = song[k] ?: continue
            val size = when (v) {
                is Number -> v.toLong()
                is String -> v.trim().toLongOrNull()
                is Map<*, *> -> (v["size"] as? Number)?.toLong()
                    ?: (v["size"] as? String)?.trim()?.toLongOrNull()
                else -> null
            }
            if (size != null && size > 0L) {
                out[q] = size
                return
            }
        }
    }

    put(Quality.hires, "hr", "hrMusic")
    put(Quality.flac, "sq", "sqMusic")
    put(Quality.k320k, "h", "hMusic")
    put(Quality.k128k, "l", "m", "lMusic", "mMusic")
    return out
}
