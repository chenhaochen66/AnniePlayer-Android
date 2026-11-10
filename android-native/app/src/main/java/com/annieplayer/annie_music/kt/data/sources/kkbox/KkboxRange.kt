package com.annieplayer.annie_music.kt.data.sources.kkbox

// ================================================================================================
// 本地播放代理的 Range 语义（纯函数，可单测）
// ================================================================================================
//
// Media3 的 `DefaultHttpDataSource` 行为：
//   - 打开时发 `Range: bytes=<pos>-`（pos 通常是 0），用响应码判断能不能 seek
//   - 拖进度条时换成 `bytes=<pos>-`
//   - 少数解析器会先发 `bytes=0-<n>` 或后缀式 `bytes=-<n>`
//
// 只要请求里带 Range，就必须回 **206 + Content-Range**：回 200 等于告诉播放器
// 「这个源不支持区间」，之后每次 seek 都会退化成重开连接从头读（表现：一拖就卡）。

/** 请求区间（[satisfiable] = false 时应回 416） */
internal data class KkboxRangeSpec(
    val hasRange: Boolean,
    val start: Long,
    val end: Long,
    val satisfiable: Boolean,
)

/** 响应头三件套（[contentRange] 为空表示不写该头） */
internal data class KkboxPlayHead(
    val code: Int,
    val contentRange: String,
    val length: Long,
)

private val _rangeRe = Regex("bytes=(\\d*)-(\\d*)")

internal fun parseKkboxRangeHeader(value: String?, total: Long): KkboxRangeSpec {
    fun whole(): KkboxRangeSpec = KkboxRangeSpec(false, 0L, total - 1, total > 0)
    if (value.isNullOrBlank()) return whole()
    val m = _rangeRe.find(value) ?: return whole()
    val a = m.groupValues[1]
    val b = m.groupValues[2]
    if (a.isEmpty() && b.isEmpty()) return whole()
    if (total <= 0) return KkboxRangeSpec(true, 0L, -1L, false)

    var start: Long
    var end: Long
    if (a.isEmpty()) {
        val suffix = b.toLongOrNull() ?: 0L
        if (suffix <= 0) return KkboxRangeSpec(true, 0L, total - 1, false)
        start = (total - suffix).coerceAtLeast(0L)
        end = total - 1
    } else {
        start = a.toLongOrNull() ?: return whole()
        end = if (b.isEmpty()) total - 1 else (b.toLongOrNull() ?: (total - 1))
    }
    if (start >= total) return KkboxRangeSpec(true, start, end, false)
    if (end < start) end = total - 1
    if (end > total - 1) end = total - 1
    return KkboxRangeSpec(true, start, end, true)
}

internal fun kkboxPlayHead(spec: KkboxRangeSpec, total: Long): KkboxPlayHead {
    if (!spec.satisfiable) return KkboxPlayHead(416, "bytes */$total", 0L)
    val end = spec.end.coerceAtMost(total - 1).coerceAtLeast(spec.start)
    val length = end - spec.start + 1
    return if (spec.hasRange) {
        KkboxPlayHead(206, "bytes ${spec.start}-$end/$total", length)
    } else {
        KkboxPlayHead(200, "", length)
    }
}

// ── 缓存分块（本机代理判断「这段在不在磁盘上」） ─────────────────────────────────────────────
//
// 回源时把起点对齐到块首，所以「块已标记」永远等于「整块 256KB 都有效」。
// 边界差一位的后果是播放器读到零字节（静音 / 解码错误），所以这几条单独抽出来测。

internal fun kkboxChunkIndexOf(pos: Long, chunk: Long): Int = (pos / chunk).toInt()

/** 第一个**完整**落在 [from] 之后的块（[from] 不在块边界时，它自己那块不算） */
internal fun kkboxChunkFirstFull(from: Long, chunk: Long): Int =
    ((from + chunk - 1) / chunk).toInt()

/** 最后一个**完整**落在 [to] 之内的块 */
internal fun kkboxChunkLastFull(to: Long, chunk: Long): Int =
    (((to + 1) / chunk) - 1).toInt()

/**
 * 从 [pos] 起，到下一个「已缓存块」之前为止的最大回源区间末尾。
 *
 * [chunks] 为 null（还没准备好）时整段都算未缓存。
 */
internal fun kkboxUncachedSpanEnd(pos: Long, end: Long, chunk: Long, chunks: BooleanArray?): Long {
    if (chunks == null) return end
    var i = kkboxChunkIndexOf(pos, chunk)
    while (i < chunks.size) {
        if (chunks[i]) {
            val before = i.toLong() * chunk - 1
            return maxOf(pos - 1, minOf(end, before))
        }
        val blockEnd = (i + 1).toLong() * chunk - 1
        if (blockEnd >= end) return end
        i++
    }
    return end
}
