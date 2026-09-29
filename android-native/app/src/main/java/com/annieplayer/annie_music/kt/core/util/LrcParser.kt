package com.annieplayer.annie_music.kt.core.util

import kotlin.math.roundToInt

/**
 * 歌词解析（1:1 移植 Flutter `core/utils/lrc_parser.dart`，对齐 RN 版 utils/lrcParser.ts）。
 */

/** 单行歌词（对齐 Dart `LyricLine`） */
data class LyricLine(
    /** 时间（秒） */
    val time: Double,
    val text: String,
    val translation: String? = null,
)

/**
 * LRC 时间标签正则（与 Dart 完全一致）：
 * `[mm:ss.xx]` 或 `[mm:ss.xxx]`；多时间戳行按「首个标签 + 余下整段文本」匹配（与 Dart 行为一致）。
 */
private val _lrcRegex by lazy { Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\](.*)") }
/** 解析 LRC 文本为歌词行列表（按时间升序） */
fun parseLrc(lrc: String): List<LyricLine> {
    val lines = mutableListOf<LyricLine>()
    for (m in _lrcRegex.findAll(lrc)) {
        val min = m.groupValues[1].toInt()
        val sec = m.groupValues[2].toInt()
        val msStr = m.groupValues[3]
        val ms = msStr.toInt()
        val time = min * 60 + sec + ms / 1000.0
        val text = m.groupValues[4].trim()
        if (text.isNotEmpty()) lines.add(LyricLine(time = time, text = text))
    }
    lines.sortBy { it.time }
    return lines
}

/** 解析翻译歌词为「毫秒时间戳 -> 文本」映射 */
private fun _parseTlyric(tlyric: String): Map<Int, String> {
    val map = mutableMapOf<Int, String>()
    for (m in _lrcRegex.findAll(tlyric)) {
        val min = m.groupValues[1].toInt()
        val sec = m.groupValues[2].toInt()
        val msStr = m.groupValues[3]
        val ms = msStr.toInt()
        val time = ((min * 60 + sec + ms / 1000.0) * 1000).roundToInt()
        val text = m.groupValues[4].trim()
        if (text.isNotEmpty()) map[time] = text
    }
    return map
}

/** 解析 LRC 并合并翻译歌词（按毫秒时间戳对齐；未匹配的行保持原样） */
fun parseLrcWithTranslation(lrc: String, tlyric: String?): List<LyricLine> {
    val lines = parseLrc(lrc).toMutableList()
    if (tlyric == null || tlyric.isEmpty()) return lines
    val tmap = _parseTlyric(tlyric)
    for (i in lines.indices) {
        val line = lines[i]
        val key = (line.time * 1000).roundToInt()
        val trans = tmap[key]
        if (trans != null) {
            lines[i] = LyricLine(time = line.time, text = line.text, translation = trans)
        }
    }
    return lines
}

/** 二分查找当前歌词行索引（时间未到首行时返回 -1） */
fun findCurrentLineIndex(lines: List<LyricLine>, currentTime: Double): Int {
    var lo = 0
    var hi = lines.size - 1
    while (lo <= hi) {
        val mid = (lo + hi) shr 1
        if (lines[mid].time <= currentTime) {
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return hi
}
