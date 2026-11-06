package com.annieplayer.annie_music.kt.data.sources.apple.wrapper

import java.util.Locale

// ================================================================================================
// TTML → LRC / 逐字 / 翻译
// ================================================================================================
//
// wrapper 的 `/lyrics` 返回 Apple 的 TTML（`syllable=1` 时带逐字 <span>）。
// 这里只做纯文本变换，不碰网络、不碰 Android 之外的东西（`Html.fromHtml` 除外，用于实体解码）。
// 逐字输出格式与全项目一致：`[mm:ss.mmm]<绝对起始ms,时长ms>词…`。

private data class TtmlLine(
    val key: String?,
    val beginMs: Int,
    val endMs: Int,
    val body: String,
)

private data class TtmlWord(
    val startMs: Int,
    val endMs: Int,
    val text: String,
)

private val _ttmlLine = Regex("""<p\b([^>]*)>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
private val _ttmlSpan = Regex("""<span\b([^>]*)>(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
private val _ttmlText = Regex("""<text\b([^>]*)>(.*?)</text>""", RegexOption.DOT_MATCHES_ALL)
private val _ttmlTranslationTag = Regex("""<translation\b([^>]*)>(.*?)</translation>""", RegexOption.DOT_MATCHES_ALL)
private val _ttmlTranslations = Regex("""<translations\b[^>]*>(.*?)</translations>""", RegexOption.DOT_MATCHES_ALL)
private val _ttmlBegin = Regex("""\bbegin="([^"]+)"""")
private val _ttmlEnd = Regex("""\bend="([^"]+)"""")
private val _ttmlKey = Regex("""\bitunes:key="([^"]+)"""")
private val _ttmlFor = Regex("""\bfor="([^"]+)"""")
private val _ttmlLang = Regex("""\bxml:lang="([^"]+)"""")
private val _ttmlTag = Regex("""<[^>]+>""")
private val _ttmlSpace = Regex("""\s+""")

private fun ttmlLines(ttml: String): List<TtmlLine> =
    _ttmlLine.findAll(ttml).mapNotNull { match ->
        val attrs = match.groupValues[1]
        val begin = _ttmlBegin.find(attrs)?.groupValues?.getOrNull(1) ?: return@mapNotNull null
        val beginMs = _ttmlSeconds(begin)?.let { (it * 1000).toInt() } ?: return@mapNotNull null
        val endMs = _ttmlEnd.find(attrs)?.groupValues?.getOrNull(1)
            ?.let { _ttmlSeconds(it)?.let { s -> (s * 1000).toInt() } }
            ?: beginMs
        TtmlLine(
            key = _ttmlKey.find(attrs)?.groupValues?.getOrNull(1),
            beginMs = beginMs,
            endMs = endMs,
            body = match.groupValues[2],
        )
    }.toList()

internal fun ttmlToLrc(ttml: String): String {
    return ttmlLines(ttml).mapNotNull { line ->
        val text = cleanTtml(line.body)
        if (text.isEmpty()) return@mapNotNull null
        "[${lrcTime(line.beginMs / 1000.0)}]$text"
    }.joinToString("\n")
}

internal fun ttmlToWordLyric(ttml: String): String? {
    val out = mutableListOf<String>()
    for (line in ttmlLines(ttml)) {
        val words = mutableListOf<TtmlWord>()
        var cursor = 0
        for (span in _ttmlSpan.findAll(line.body)) {
            val attrs = span.groupValues[1]
            val text = cleanTtml(span.groupValues[2])
            if (text.isEmpty()) {
                cursor = span.range.last + 1
                continue
            }
            val gap = line.body.substring(cursor.coerceAtMost(span.range.first), span.range.first)
            val prefix = if (words.isNotEmpty() && gap.any { it.isWhitespace() }) " " else ""
            val start = _ttmlBegin.find(attrs)?.groupValues?.getOrNull(1)
                ?.let { _ttmlSeconds(it)?.let { s -> (s * 1000).toInt() } }
                ?: line.beginMs
            val end = _ttmlEnd.find(attrs)?.groupValues?.getOrNull(1)
                ?.let { _ttmlSeconds(it)?.let { s -> (s * 1000).toInt() } }
                ?: start
            words += TtmlWord(start, end, prefix + text)
            cursor = span.range.last + 1
        }
        if (words.isEmpty()) continue
        val sb = StringBuilder()
        sb.append('[').append(mmssMs(line.beginMs)).append(']')
        for (word in words) {
            sb.append('<').append(mmssMs(word.startMs)).append('>').append(word.text)
        }
        val lastEnd = words.last().endMs.coerceAtLeast(words.last().startMs)
        sb.append('<').append(mmssMs(lastEnd)).append('>')
        out += sb.toString()
    }
    return out.joinToString("\n").ifEmpty { null }
}

internal fun ttmlTranslation(ttml: String, language: String): String? {
    val block = _ttmlTranslations.find(ttml)?.groupValues?.getOrNull(1) ?: return null
    val all = _ttmlTranslationTag.findAll(block).toList()
    if (all.isEmpty()) return null
    val wanted = language.substringBefore('-').lowercase(Locale.ROOT)
    val picked = all.firstOrNull {
        val lang = _ttmlLang.find(it.groupValues[1])?.groupValues?.getOrNull(1).orEmpty()
        lang.lowercase(Locale.ROOT).startsWith(wanted)
    } ?: all.first()
    val texts = _ttmlText.findAll(picked.groupValues[2]).associate { text ->
        val key = _ttmlFor.find(text.groupValues[1])?.groupValues?.getOrNull(1).orEmpty()
        key to cleanTtml(text.groupValues[2])
    }.filterValues { it.isNotEmpty() }
    if (texts.isEmpty()) return null
    return ttmlLines(ttml).mapNotNull { line ->
        val text = line.key?.let { texts[it] } ?: return@mapNotNull null
        "[${lrcTime(line.beginMs / 1000.0)}]$text"
    }.joinToString("\n").ifEmpty { null }
}

private fun cleanTtml(raw: String): String {
    val noTags = _ttmlTag.replace(raw, "")
    val decoded = runCatching {
        android.text.Html.fromHtml(noTags, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
    }.getOrDefault(noTags)
    return _ttmlSpace.replace(decoded, " ").trim()
}

private fun _ttmlSeconds(raw: String): Double? {
    val parts = raw.trim().split(":")
    return when (parts.size) {
        1 -> parts[0].toDoubleOrNull()
        2 -> parts[0].toDoubleOrNull()?.times(60)?.plus(parts[1].toDoubleOrNull() ?: return null)
        3 -> parts[0].toDoubleOrNull()?.times(3600)?.plus(
            (parts[1].toDoubleOrNull() ?: return null) * 60 +
                (parts[2].toDoubleOrNull() ?: return null),
        )
        else -> null
    }
}

private fun lrcTime(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0)
    val minutes = (total / 60).toInt()
    val sec = total - minutes * 60
    return String.format(Locale.US, "%02d:%05.2f", minutes, sec)
}

private fun mmssMs(ms: Int): String {
    val safe = ms.coerceAtLeast(0)
    val minutes = safe / 60000
    val seconds = (safe % 60000) / 1000
    val millis = safe % 1000
    return String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, millis)
}
