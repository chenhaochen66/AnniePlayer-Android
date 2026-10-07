package com.annieplayer.annie_music.kt.core.util

import kotlin.math.roundToInt

/**
 * 歌词解析（1:1 移植 Flutter `core/utils/lrc_parser.dart`，对齐 RN 版 utils/lrcParser.ts）。
 */

/** 逐字词块（统一逐字格式：`<绝对起始ms,时长ms>词`） */
data class LyricWord(
    /** 词绝对起始（毫秒） */
    val startMs: Int,
    /** 词时长（毫秒） */
    val durMs: Int,
    val text: String,
)

/** 单行歌词（对齐 Dart `LyricLine`） */
data class LyricLine(
    /** 时间（秒） */
    val time: Double,
    val text: String,
    val translation: String? = null,
    /** 逐字词块（无逐字数据时为空） */
    val words: List<LyricWord> = emptyList(),
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
        // 两位是「厘秒」（[mm:ss.cc]，酷狗 fmtTime 就是这种）→ 必须 ×10，否则时间轴差 10 倍
        val ms = if (msStr.length == 2) msStr.toInt() * 10 else msStr.toInt()
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
        val ms = if (msStr.length == 2) msStr.toInt() * 10 else msStr.toInt()
        val time = ((min * 60 + sec + ms / 1000.0) * 1000).roundToInt()
        val text = m.groupValues[4].trim()
        if (text.isNotEmpty()) map[time] = text
    }
    return map
}

/** 统一逐字行正则：`[mm:ss.mmm]<起始ms,时长ms>词…` */
private val _wordLineRegex by lazy {
    Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\]((?:<\\d+,\\d+>[^<\\n]*)+)")
}
/**
 * 统一逐字词块正则（要求三个数字，避免把歌词里的普通括号当标记）。
 *
 * 文本必须**遇换行即停**：`[^<]*` 会跨行吃进下一行的 `[mm:ss.mmm]` 标签，
 * 于是每行最后一个词带着下一行的时间标签一起渲染出来（真机现象：开逐字后
 * 每行前面多出 `[00:01.640]`）。
 */
private val _wordRegex by lazy { Regex("<(\\d+),(\\d+)>([^<\\n]*)") }

/** 一行逐字数据（时间轴可能与该平台的普通 lrc 不同，见 [parseLrcWithTranslation]） */
private data class WordLine(val timeMs: Int, val words: List<LyricWord>)

/** 解析统一逐字文本为按时间升序的逐字行列表 */
private fun _parseWordLyric(wordLyric: String): List<WordLine> {
    val out = ArrayList<WordLine>()
    for (m in _wordLineRegex.findAll(wordLyric)) {
        val min = m.groupValues[1].toInt()
        val sec = m.groupValues[2].toInt()
        val msStr = m.groupValues[3]
        val ms = if (msStr.length == 2) msStr.toInt() * 10 else msStr.toInt()
        val time = ((min * 60 + sec + ms / 1000.0) * 1000).roundToInt()
        val words = _wordRegex.findAll(m.groupValues[4]).map {
            LyricWord(
                startMs = it.groupValues[1].toInt(),
                durMs = it.groupValues[2].toInt(),
                text = it.groupValues[3],
            )
        }.filter { it.text.isNotEmpty() }.toList()
        if (words.isNotEmpty()) out.add(WordLine(time, words))
    }
    out.sortBy { it.timeMs }
    // 时长为 0/负 的词（不少平台的末段不给时长）→ 用「下一个词的起点」补齐；
    // 一行最后一个词 → 用「下一行的起点」补齐。否则那个字会瞬间闪亮（"最后一个字嗖一下"）。
    for (li in out.indices) {
        val words = out[li].words.toMutableList()
        val nextLineMs = out.getOrNull(li + 1)?.timeMs
        for (i in words.indices) {
            if (words[i].durMs > 0) continue
            val nextStart = words.getOrNull(i + 1)?.startMs ?: nextLineMs
            if (nextStart != null && nextStart > words[i].startMs) {
                words[i] = words[i].copy(durMs = nextStart - words[i].startMs)
            }
        }
        out[li] = WordLine(out[li].timeMs, words)
    }
    return out
}

/**
 * 解析 LRC 并合并翻译与逐字。
 *
 * 翻译按毫秒时间戳对齐（同源，能对上）。
 *
 * 逐字**不能**按时间戳对齐：实测网易 `yrc` 与 `lrc` 是两套时间轴（《A Moment Apart》
 * 同一句 lrc 33460ms / yrc 41760ms，相差约 8.3s），酷狗两位厘秒也会整体错位。
 * 因此这里取「时间最接近」的逐字行，并把它的词时间**平移到本行时间轴**上，
 * 保证卡拉OK 进度跟着音频（lrc 时间轴）走。
 */
fun parseLrcWithTranslation(
    lrc: String,
    tlyric: String?,
    wordLyric: String? = null,
): List<LyricLine> {
    val lines = parseLrc(lrc).toMutableList()
    val hasTrans = !tlyric.isNullOrEmpty()
    val hasWords = !wordLyric.isNullOrEmpty()
    if (!hasTrans && !hasWords) return lines
    val tmap = if (hasTrans) _parseTlyric(tlyric!!) else emptyMap()
    val wordLines = if (hasWords) _parseWordLyric(wordLyric!!) else emptyList()
    var cursor = 0
    for (i in lines.indices) {
        val line = lines[i]
        val key = (line.time * 1000).roundToInt()
        val trans = tmap[key]
        var words: List<LyricWord> = emptyList()
        if (wordLines.isNotEmpty()) {
            while (cursor + 1 < wordLines.size && wordLines[cursor + 1].timeMs <= key) cursor++
            var best = cursor
            if (best + 1 < wordLines.size &&
                kotlin.math.abs(wordLines[best + 1].timeMs - key) <
                kotlin.math.abs(wordLines[best].timeMs - key)
            ) {
                best++
            }
            val wl = wordLines[best]
            val delta = key - wl.timeMs
            words = if (delta == 0) wl.words else wl.words.map { it.copy(startMs = it.startMs + delta) }
        }
        if (trans != null || words.isNotEmpty()) {
            lines[i] = LyricLine(
                time = line.time,
                text = line.text,
                translation = trans ?: line.translation,
                words = words,
            )
        }
    }
    return lines
}

/** 毫秒 -> `[mm:ss.mmm]`（毫秒补 3 位，保证与 lrc 行的时间戳 key 一致） */
fun lrcTimeOfMs(ms: Int): String {
    val safe = if (ms < 0) 0 else ms
    return "[" + (safe / 60000).toString().padStart(2, '0') + ":" +
        ((safe % 60000) / 1000).toString().padStart(2, '0') + "." +
        (safe % 1000).toString().padStart(3, '0') + "]"
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
