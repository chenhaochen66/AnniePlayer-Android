package com.annieplayer.annie_music.kt.data.sources.kugou

import android.util.Base64
import com.annieplayer.annie_music.kt.core.util.KrcDes
import com.annieplayer.annie_music.kt.core.util.lrcTimeOfMs
import com.annieplayer.annie_music.kt.core.util.mmssOfMs
import com.annieplayer.annie_music.kt.data.network.httpGetJson
import com.annieplayer.annie_music.kt.data.network.json
import com.annieplayer.annie_music.kt.data.network.jsonElementToAny
import com.annieplayer.annie_music.kt.data.sources.common.encodeComponent
import kotlin.math.floor

// ================================================================================================
// 酷狗歌词（KRC 逐字 + 翻译；没有 KRC 时退 base64 的普通 LRC）
// ================================================================================================

private val _reKrcInline by lazy { Regex("<\\d+,\\d+,\\d+>") }
private val _reKrcLine by lazy { Regex("^\\[(\\d+),(\\d+)\\]") }
private val _reKrcLang by lazy { Regex("\\[language:([\\w=+/]+)\\]") }

/** krc 词块标记：`<offset,duration,0>`（酷狗是相对行首的毫秒，无需酷我那种除数换算） */
private val _reKrcWord by lazy { Regex("<(-?\\d+),(-?\\d+),\\d+>([^<]*)") }

private fun parseKrcLines(krcText: String): List<KugouKrcLine> {
    val result = ArrayList<KugouKrcLine>()
    for (line in krcText.split("\n")) {
        val m = _reKrcLine.find(line) ?: continue
        val startMs = m.groupValues[1].toIntOrNull() ?: 0
        val rest = line.substring(m.value.length)
        val text = _reKrcInline.replace(rest, "").trim()
        if (text.isNotEmpty()) result.add(KugouKrcLine(startMs, text))
    }
    return result
}

private fun parseKrcTranslation(krcText: String): List<KugouKrcLine> {
    val m = _reKrcLang.find(krcText) ?: return emptyList()
    try {
        val decoded = String(
            Base64.decode(m.groupValues[1], Base64.DEFAULT),
            Charsets.UTF_8,
        )
        val data = jsonElementToAny(json.parseToJsonElement(decoded))
        if (data !is Map<*, *>) return emptyList()
        val content = data["content"]
        if (content !is List<*>) return emptyList()
        var tlyric: List<*>? = null
        for (item in content) {
            if (item is Map<*, *> && kugouNumEq(item["type"], 1)) {
                val lc = item["lyricContent"]
                if (lc is List<*>) tlyric = lc
                break
            }
        }
        val tl = tlyric ?: return emptyList()
        val out = ArrayList<KugouKrcLine>()
        for (entry in tl) {
            val text = if (entry is List<*>) {
                (entry.firstOrNull() ?: "").toString()
            } else {
                entry.toString()
            }
            out.add(KugouKrcLine(0, text))
        }
        return out
    } catch (_: Throwable) {
        return emptyList()
    }
}

/** KRC -> 统一逐字格式 `[mm:ss.mmm]<绝对起始ms,时长ms>词…` */
private fun kugouKrcToWordLyric(krcText: String): String? {
    val out = StringBuilder()
    for (line in krcText.split("\n")) {
        val m = _reKrcLine.find(line) ?: continue
        val startMs = m.groupValues[1].toIntOrNull() ?: continue
        val marks = _reKrcWord.findAll(line.substring(m.value.length)).toList()
        if (marks.isEmpty()) continue
        val text = marks.joinToString("") { it.groupValues[3] }.trim()
        if (text.isEmpty()) continue
        out.append(lrcTimeOfMs(startMs)).append(
            marks.joinToString("") { w ->
                val off = w.groupValues[1].toIntOrNull() ?: 0
                "<${mmssOfMs(startMs + off)}>${w.groupValues[3]}"
            },
        ).append('\n')
    }
    return if (out.isEmpty()) null else out.toString().trimEnd('\n')
}

private fun fmtTime(ms: Int): String {
    val sec = ms / 1000.0
    val min = floor(sec / 60).toInt()
    val s = floor(sec % 60).toInt()
    val cs = floor((sec - floor(sec)) * 100).toInt()
    val mm = min.toString().padStart(2, '0')
    val ss = s.toString().padStart(2, '0')
    val ccs = cs.toString().padStart(2, '0')
    return "[$mm:$ss.$ccs]"
}

suspend fun fetchKugouLyric(
    songId: String,
    keyword: String = "",
): KugouLyricResult {
    val hash = songId.replaceFirst(Regex("^kugou:"), "")
    try {
        val searchUrl =
            "http://lyrics.kugou.com/search?ver=1&man=yes&client=pc" +
                "&keyword=${encodeComponent(keyword)}&hash=$hash&timelength=254"
        val searchData = httpGetJson(
            searchUrl,
            headers = mapOf(
                "KG-RC" to "1",
                "KG-THash" to "expand_search_manager.cpp:852736169:451",
                "User-Agent" to "KuGou2012-9020-ExpandSearchManager",
            ),
        )
        val candidatesRaw = searchData["candidates"]
        val candidates = if (candidatesRaw is List<*>) {
            candidatesRaw
        } else {
            emptyList<Any?>()
        }
        if (candidates.isEmpty()) {
            return KugouLyricResult("[00:00.00]暂无歌词", null)
        }

        val info = candidates.first() as? Map<*, *>
        if (info == null) return KugouLyricResult("[00:00.00]暂无歌词", null)
        val idVal = info["id"]
        val accessKey = info["accesskey"]
        val fmt =
            if (kugouNumEq(info["krctype"], 1) && !kugouNumEq(info["contenttype"], 1)) {
                "krc"
            } else {
                "lrc"
            }

        val dlData = httpGetJson(
            "http://lyrics.kugou.com/download?ver=1&client=pc&id=$idVal" +
                "&accesskey=$accessKey&fmt=$fmt&charset=utf8",
            headers = mapOf(
                "KG-RC" to "1",
                "KG-THash" to "expand_search_manager.cpp:852736169:451",
                "User-Agent" to "KuGou2012-9020-ExpandSearchManager",
            ),
        )
        val content = (dlData["content"] ?: "").toString()
        if (fmt == "lrc") {
            val decoded = String(
                Base64.decode(content, Base64.DEFAULT),
                Charsets.UTF_8,
            )
            return KugouLyricResult(decoded, null)
        }
        val krcText = KrcDes.decrypt(content)
        val lyricLines = parseKrcLines(krcText)
        val transLines = parseKrcTranslation(krcText)

        val lrc = lyricLines.joinToString("\n") { fmtTime(it.time) + it.text }
        val tlyric = if (transLines.isNotEmpty()) {
            val parts = ArrayList<String>()
            for (i in lyricLines.indices) {
                val t = if (i < transLines.size) transLines[i].text else ""
                parts.add(fmtTime(lyricLines[i].time) + t)
            }
            parts.joinToString("\n")
        } else {
            null
        }

        return KugouLyricResult(lrc, tlyric, kugouKrcToWordLyric(krcText))
    } catch (_: Throwable) {
        return KugouLyricResult("[00:00.00]暂无歌词", null)
    }
}
