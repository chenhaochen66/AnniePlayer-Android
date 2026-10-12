package com.annieplayer.annie_music.kt.core.util

import kotlin.math.roundToInt

/**
 * 歌词解析（1:1 移植 Flutter `core/utils/lrc_parser.dart`，对齐 RN 版 utils/lrcParser.ts）。
 */

/** 逐字词块（统一逐字格式：`<mm:ss.mmm>词`，时间是**词绝对起始**） */
data class LyricWord(
    /** 词绝对起始（毫秒） */
    val startMs: Int,
    /** 词时长（毫秒）；源格式不给时长，由「下一个词的起点」推出（见 [_parseWordLyric]） */
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

/** 统一逐字行正则：`[mm:ss.mmm]<mm:ss.mmm>词…`（行首是行时间，词标记是词的**绝对起始**） */
private val _wordLineRegex by lazy {
    Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\]((?:<\\d{2}:\\d{2}\\.\\d{2,3}>[^<\\n]*)+)")
}
/**
 * 统一逐字词块正则：`<mm:ss.mmm>词`（末尾那个无文本的标记用于收尾，故文本允许为空）。
 *
 * 文本必须**遇换行即停**：`[^<]*` 会跨行吃进下一行的 `[mm:ss.mmm]` 标签，
 * 于是每行最后一个词带着下一行的时间标签一起渲染出来（真机现象：开逐字后
 * 每行前面多出 `[00:01.640]`）。
 */
private val _wordRegex by lazy { Regex("<(\\d{2}):(\\d{2})\\.(\\d{2,3})>([^<\\n]*)") }

/** `<mm:ss.mmm>` / `<mm:ss.cc>` → 毫秒 */
private fun _tagToMs(m: MatchResult): Int {
    val msStr = m.groupValues[3]
    val ms = if (msStr.length == 2) msStr.toInt() * 10 else msStr.toInt()
    return m.groupValues[1].toInt() * 60000 + m.groupValues[2].toInt() * 1000 + ms
}

/** 翻译行时间戳的匹配容差（毫秒）：各平台行起点与翻译时间戳的精度不同（厘秒 / 毫秒） */
private const val TlyricToleranceMs = 80

/** 取时间上最接近 [key] 的翻译（超出容差返回 null） */
private fun _nearestTrans(map: Map<Int, String>, key: Int): String? {
    var bestKey: Int? = null
    var bestDiff = Int.MAX_VALUE
    for (k in map.keys) {
        val d = kotlin.math.abs(k - key)
        if (d < bestDiff) {
            bestDiff = d
            bestKey = k
        }
    }
    return if (bestKey != null && bestDiff <= TlyricToleranceMs) map[bestKey] else null
}

/** 一行逐字数据（时间轴可能与该平台的普通 lrc 不同，见 [parseLrcWithTranslation]） */
private data class WordLine(val timeMs: Int, val words: List<LyricWord>)

/** 解析统一逐字文本为按时间升序的逐字行列表 */
private fun _parseWordLyric(wordLyric: String): List<WordLine> {
    val out = ArrayList<WordLine>()
    for (m in _wordLineRegex.findAll(wordLyric)) {
        val time = _tagToMs(m)
        // 原始序列**含空文本标记**：行首 `<00:00.000>` 与**行尾收尾标记**都是空文本，
        // 行尾那个决定最后一个词的结束时间 —— 先留着推时长，最后再过滤空词
        val raw = _wordRegex.findAll(m.groupValues[4])
            .map { LyricWord(startMs = _tagToMs(it), durMs = 0, text = it.groupValues[4]) }
            .toList()
        if (raw.none { it.text.isNotEmpty() }) continue
        out.add(WordLine(time, raw))
    }
    out.sortBy { it.timeMs }
    val result = ArrayList<WordLine>()
    for (li in out.indices) {
        val raw = out[li].words
        val nextLineMs = out.getOrNull(li + 1)?.timeMs
        // 时长一律取「原始序列里下一个标记的起点」（含空标记）；没有下一个才用下一行的起点。
        // 否则最后一个词会被拉长到下一行，或那个字瞬间闪亮（"最后一个字嗖一下"）。
        val words = raw.mapIndexedNotNull { i, w ->
            if (w.text.isEmpty()) return@mapIndexedNotNull null
            val nextStart = raw.getOrNull(i + 1)?.startMs ?: nextLineMs
            val dur = if (nextStart != null && nextStart > w.startMs) nextStart - w.startMs else 0
            w.copy(durMs = dur)
        }
        if (words.isNotEmpty()) result.add(WordLine(out[li].timeMs, words))
    }
    return result
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
        // 精确查不到就取最近的一条（容差见 [TlyricToleranceMs]）：QQ 的 QRC 行起点不是整厘秒，
        // 而 trans 只精确到厘秒，直接精确查表会整句丢翻译（实测 13065ms vs 13060ms）。
        val trans = tmap[key] ?: _nearestTrans(tmap, key)
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
fun lrcTimeOfMs(ms: Int): String = "[" + mmssOfMs(ms) + "]"

/** 毫秒 -> `mm:ss.mmm`（**不带方括号**）—— 逐字词标记 `<…>` 里用的是这个，别用 [lrcTimeOfMs] */
fun mmssOfMs(ms: Int): String {
    val safe = if (ms < 0) 0 else ms
    return (safe / 60000).toString().padStart(2, '0') + ":" +
        ((safe % 60000) / 1000).toString().padStart(2, '0') + "." +
        (safe % 1000).toString().padStart(3, '0')
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

/** 单文件歌词里的一行；[wordLine] 非空 = 带 `<…>` 词标记的增强行 */
private data class _EmbeddedEntry(val timeMs: Int, val text: String, val wordLine: String?)

/**
 * 把**单个歌词文件**（普通 LRC / 增强 LRC，原文与翻译**交错**或**原文全在上、翻译全在下**）
 * 拆成 [parseLrcWithTranslation] 需要的那三份 —— 本地曲库读「内嵌歌词 / 同名 .lrc」用。
 *
 * 判定规则：
 * - 带 `<mm:ss.mmm>` 词标记的行 = 原文（增强行）；
 * - 其余 `[mm:ss.xx]文本` 行：能对上一条原文 → 翻译，否则自己也是原文。
 *
 * 对上原文分两档：**时间完全相同**必算（普通 LRC 的原文/翻译是同一个标签）；
 * 只有整份含增强行时才放宽到 [TlyricToleranceMs] —— 增强行标签是**首个词**的时间
 * （QQ 实测 `[00:10.004]`）而翻译是厘秒（`[00:10.00]`），不放宽会整份翻译都当原文；
 * 但纯普通 LRC 不能放宽，否则快歌里相邻两句会被误并成一句。
 *
 * @return `(lrc, tlyric, wordLyric)`；后两者无内容时为 `null`。
 */
fun splitEmbeddedLyric(raw: String): Triple<String, String?, String?> {
    val entries = ArrayList<_EmbeddedEntry>()
    for (line in raw.split('\n')) {
        val t = line.trim()
        val w = _wordLineRegex.find(t)
        if (w != null) {
            val text = _wordRegex.findAll(w.groupValues[4]).joinToString("") { it.groupValues[4] }
            if (text.isNotEmpty()) entries.add(_EmbeddedEntry(_tagToMs(w), text, t))
            continue
        }
        val m = _lrcRegex.find(t) ?: continue
        val text = m.groupValues[4].trim()
        if (text.isNotEmpty()) entries.add(_EmbeddedEntry(_tagToMs(m), text, null))
    }
    if (entries.isEmpty()) return Triple("", null, null)

    val hasWords = entries.any { it.wordLine != null }
    val originals = ArrayList<_EmbeddedEntry>()
    val translations = ArrayList<_EmbeddedEntry>()
    for (e in entries) {
        if (e.wordLine != null) {
            originals.add(e)
            continue
        }
        val owner = originals.minByOrNull { kotlin.math.abs(it.timeMs - e.timeMs) }
        val isTranslation = owner != null &&
            (owner.timeMs == e.timeMs ||
                (hasWords && kotlin.math.abs(owner.timeMs - e.timeMs) <= TlyricToleranceMs))
        if (isTranslation) translations.add(e) else originals.add(e)
    }

    val lrc = StringBuilder()
    val wordLyric = StringBuilder()
    for (o in originals) {
        lrc.append(lrcTimeOfMs(o.timeMs)).append(o.text).append('\n')
        o.wordLine?.let { wordLyric.append(it).append('\n') }
    }
    val tlyric = StringBuilder()
    for (t in translations) {
        tlyric.append(lrcTimeOfMs(t.timeMs)).append(t.text).append('\n')
    }
    return Triple(
        lrc.toString().trimEnd('\n'),
        tlyric.toString().trimEnd('\n').ifEmpty { null },
        wordLyric.toString().trimEnd('\n').ifEmpty { null },
    )
}

/** 本地曲库入口：读「内嵌歌词 / 同名 .lrc」的原文，直接拿到可渲染的行（原文在上、翻译在下） */
fun parseEmbeddedLyric(raw: String): List<LyricLine> {
    if (raw.isBlank()) return emptyList()
    val (lrc, tlyric, wordLyric) = splitEmbeddedLyric(raw)
    if (lrc.isEmpty()) return emptyList()
    return parseLrcWithTranslation(lrc, tlyric, wordLyric)
}
