package com.annieplayer.annie_music.kt.data.sources.kuwo

import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.sources.common.asText
import com.annieplayer.annie_music.kt.data.sources.common.firstTruthy
import com.annieplayer.annie_music.kt.data.sources.common.jsTruthy

// ================================================================================================
// 酷我音质（MINFO 串是唯一可靠来源）
// ================================================================================================

private val _reLevelKey by lazy { Regex("[=:]") }

/** 解析 MINFO 串中的可用音质（对齐 RN parseQualities） */
fun parseQualities(minfo: String): List<Quality> {
    val parts = minfo.split(";")
    val seen = HashSet<Quality>()
    val result = ArrayList<Quality>()
    for (part in parts) {
        val kv = part.split(",")
        var level: String? = null
        for (k in kv) {
            if (k.startsWith("level=") || k.startsWith("level:")) {
                val seg = k.split(_reLevelKey)
                if (seg.size > 1) level = seg[1]
                break
            }
        }
        if (level == "h") {
            if (seen.add(Quality.k128k)) result.add(Quality.k128k)
        } else if (level == "p" || level == "s") {
            if (seen.add(Quality.k320k)) result.add(Quality.k320k)
        } else if (level == "ff" || level == "zp" || level == "zply") {
            if (seen.add(Quality.flac)) result.add(Quality.flac)
        }
    }
    val order = listOf(Quality.flac, Quality.k320k, Quality.k128k)
    result.sortBy { order.indexOf(it) }
    return result
}

/**
 * 酷我曲目音质判定。
 *
 * **优先按 `MINFO` 解析** —— 专辑(`searchlist.kuwo.cn/r.s?stype=albuminfo`)、歌单、搜索
 * 三个接口的曲目都带 `MINFO`，而 `hasLossless` 只有部分接口有：专辑接口里它**根本不存在**
 * （实测：一张专辑响应 `MINFO` ×22、`hasLossless` ×0），所以此前专辑页音质永远封顶 320k。
 * 拿不到 `MINFO` 时才退回 `hasLossless` 兜底。
 */
internal fun kuwoQualities(item: Map<String, Any?>): List<Quality> {
    val byMinfo = parseQualities(asText(firstTruthy(listOf(item["MINFO"], item["N_MINFO"]))))
    if (byMinfo.isNotEmpty()) return byMinfo
    val fallback = mutableListOf(Quality.k320k, Quality.k128k)
    if (jsTruthy(item["hasLossless"])) fallback.add(0, Quality.flac)
    return fallback
}

/** 各档位字节数（从 `MINFO` 的 `size:32.38Mb` 取）；取不到返回空表 */
internal fun kuwoQualitySizes(item: Map<String, Any?>): Map<Quality, Long> =
    parseQualitySizes(asText(firstTruthy(listOf(item["MINFO"], item["N_MINFO"]))))

/** 解析 MINFO 串里各档位的字节数（`size:32.38Mb`） */
fun parseQualitySizes(minfo: String): Map<Quality, Long> {
    val out = HashMap<Quality, Long>()
    for (part in minfo.split(";")) {
        var level: String? = null
        var sizeBytes = 0L
        for (kv in part.split(",")) {
            when {
                kv.startsWith("level") -> level = kv.substringAfter(':').substringAfter('=')
                kv.startsWith("size") -> sizeBytes = _parseSizeText(kv.substringAfter(':').substringAfter('='))
            }
        }
        val q = when (level) {
            "h" -> Quality.k128k
            "p", "s" -> Quality.k320k
            "ff", "zp", "zply" -> Quality.flac
            else -> null
        } ?: continue
        if (sizeBytes > 0L && q !in out) out[q] = sizeBytes
    }
    return out
}

private fun _parseSizeText(text: String): Long = run {
    val t = text.trim().lowercase()
    val num = t.takeWhile { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return 0L
    when {
        t.endsWith("gb") -> (num * 1024 * 1024 * 1024).toLong()
        t.endsWith("mb") -> (num * 1024 * 1024).toLong()
        t.endsWith("kb") -> (num * 1024).toLong()
        else -> num.toLong()
    }
}
