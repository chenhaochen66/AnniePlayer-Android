package com.annieplayer.annie_music.kt.data.sources.kuwo

import com.annieplayer.annie_music.kt.core.util.LrcxDes
import com.annieplayer.annie_music.kt.core.util.lrcTimeOfMs
import com.annieplayer.annie_music.kt.core.util.mmssOfMs
import com.annieplayer.annie_music.kt.data.network.httpGetBytes

// ================================================================================================
// 酷我歌词（LRCX 逐字为主，同一接口的普通 LRC 兜底）
// ================================================================================================

/** LRCX 头里的 `[kuwo:NNN]`（八进制）：十位/个位分别作为词起止时间的除数 */
private val _reKuwoOffset by lazy { Regex("\\[kuwo:(\\d+)\\]") }

/** LRCX 行：`[mm:ss.mmm]<o,d>字<o,d>字…` */
private val _reKuwoLrcxLine by lazy {
    Regex("^\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\]((?:<-?\\d+,-?\\d+>[^<]*)+)")
}
private val _reKuwoWord by lazy { Regex("<(-?\\d+),(-?\\d+)>([^<]*)") }
private val _reCjk by lazy { Regex("[\\u4e00-\\u9fff]") }

/**
 * 请求酷我 PC 端歌词接口（LRCX 逐字 / 普通 LRC 同一接口，用 `lrcx` 参数切换）。
 *
 * 响应是「文本头 `tp=content` + CRLFCRLF + zlib 二进制」的混合体，故走 [httpGetBytes]。
 */
private suspend fun _fetchKuwoPcLyric(musicId: String, isLrcx: Boolean): String? = try {
    val params = "user=12345,web,web,web&requester=localhost&req=1&rid=MUSIC_$musicId" +
        if (isLrcx) "&lrcx=1" else ""
    val raw = httpGetBytes(
        "http://newlyric.kuwo.cn/newlyric.lrc?" + LrcxDes.xorBase64(params),
        mapOf("User-Agent" to kuwoMobileUa),
    )
    LrcxDes.decryptResponse(raw, isLrcx)
} catch (_: Throwable) {
    null
}

/**
 * LRCX -> 行级 lrc + 翻译 tlyric + 统一逐字格式。
 *
 * 翻译行判据：所有词标记都是 `<0,0>` 且含中文（原文行一定有非零标记）。
 * 翻译行用**上一句原文的时间戳**输出，保证与 lrc 行的时间戳 key 一致
 * （播放页合并与下载写标签都按这个 key 对齐）。
 */
private fun _parseKuwoLrcx(lrcx: String): KuwoLyric {
    val tag = _reKuwoOffset.find(lrcx)?.groupValues?.get(1)?.toIntOrNull(8) ?: 11
    val d1 = (tag / 10).coerceAtLeast(1)
    val lrc = StringBuilder()
    val tl = StringBuilder()
    val wl = StringBuilder()
    var lastMs = 0
    var lastText: String? = null
    var lastWords = ""

    fun flush() {
        val text = lastText ?: return
        val ts = lrcTimeOfMs(lastMs)
        lrc.append(ts).append(text).append('\n')
        if (lastWords.isNotEmpty()) wl.append(ts).append(lastWords).append('\n')
        lastText = null
        lastWords = ""
    }

    for (line in lrcx.split('\n')) {
        val m = _reKuwoLrcxLine.find(line.trim()) ?: continue
        val ms = m.groupValues[1].toInt() * 60000 +
            m.groupValues[2].toInt() * 1000 +
            m.groupValues[3].toInt()
        val marks = _reKuwoWord.findAll(m.groupValues[4]).toList()
        if (marks.isEmpty()) continue
        val text = marks.joinToString("") { it.groupValues[3] }.trim()
        if (text.isEmpty()) continue
        val allZero = marks.all { it.groupValues[1] == "0" && it.groupValues[2] == "0" }
        if (allZero && _reCjk.containsMatchIn(text)) {
            if (lastText != null) tl.append(lrcTimeOfMs(lastMs)).append(text).append('\n')
            continue
        }
        flush()
        lastMs = ms
        lastText = text
        lastWords = marks.joinToString("") { w ->
            val o = w.groupValues[1].toInt()
            val d = w.groupValues[2].toInt()
            val start = ms + kotlin.math.abs(o + d) / (d1 * 2)
            "<${mmssOfMs(start)}>${w.groupValues[3]}"
        }
    }
    flush()
    return KuwoLyric(
        lrc = if (lrc.isEmpty()) "[00:00.00]暂无歌词" else lrc.toString().trimEnd('\n'),
        tlyric = if (tl.isEmpty()) null else tl.toString().trimEnd('\n'),
        wordLyric = if (wl.isEmpty()) null else wl.toString().trimEnd('\n'),
    )
}

/**
 * 获取酷我歌词：PC 端接口（优先 LRCX 逐字；该曲无 LRCX 时退同一接口的普通 LRC）。
 *
 * 只接受**酷我自己的** musicId。以前这里会替 `qobuz:` / `kkbox:` 的 id 去酷我搜一首
 * 「同名歌」借歌词 —— 那份歌词的归属和实际播放的歌根本不是一回事，已经撤掉保底。
 */
suspend fun fetchKuwoLyric(songId: String): KuwoLyric {
    val musicId = songId
    val lrcx = _fetchKuwoPcLyric(musicId, true)
    if (!lrcx.isNullOrEmpty()) {
        val parsed = _parseKuwoLrcx(lrcx)
        if (parsed.lrc != "[00:00.00]暂无歌词") return parsed
    }
    val plain = _fetchKuwoPcLyric(musicId, false)
    if (!plain.isNullOrEmpty()) return KuwoLyric(plain.trim(), null, null)
    return KuwoLyric("[00:00.00]暂无歌词", null, null)
}
