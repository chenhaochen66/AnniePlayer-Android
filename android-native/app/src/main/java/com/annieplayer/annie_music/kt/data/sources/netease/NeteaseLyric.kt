package com.annieplayer.annie_music.kt.data.sources.netease

import com.annieplayer.annie_music.kt.core.util.lrcTimeOfMs
import com.annieplayer.annie_music.kt.core.util.mmssOfMs
import com.annieplayer.annie_music.kt.data.network.httpPostJson

// ================================================================================================
// 网易云歌词（原文 / 翻译 / 逐字 yrc）
// ================================================================================================

/** 网易 `yrc` 行：`[行起始ms,行时长ms](词起始ms,词时长ms,0)词…`（词时间是绝对毫秒） */
private val _reYrcLine by lazy { Regex("^\\[(\\d+),\\d+\\]((?:\\(\\d+,\\d+,\\d+\\)[^(]*)+)") }
private val _reYrcWord by lazy { Regex("\\((\\d+),(\\d+),(\\d+)\\)([^(]*)") }

/** 网易 `yrc` -> 统一逐字格式 `[mm:ss.mmm]<起始ms,时长ms>词…` */
private fun _neteaseYrcToWordLyric(yrc: String): String? {
    if (yrc.isEmpty()) return null
    val out = StringBuilder()
    for (line in yrc.split('\n')) {
        val m = _reYrcLine.find(line.trim()) ?: continue
        val lineStart = m.groupValues[1].toIntOrNull() ?: continue
        val sb = StringBuilder()
        for (w in _reYrcWord.findAll(m.groupValues[2])) {
            val text = w.groupValues[4]
            if (text.isEmpty()) continue
            sb.append('<').append(mmssOfMs(w.groupValues[1].toIntOrNull() ?: 0))
                .append('>').append(text)
        }
        if (sb.isEmpty()) continue
        out.append(lrcTimeOfMs(lineStart)).append(sb).append('\n')
    }
    return if (out.isEmpty()) null else out.toString().trimEnd('\n')
}

/** 获取网易云歌词，返回原始 lrc、翻译歌词与逐字歌词 */
suspend fun fetchNeteaseLyric(songId: String): NeteaseLyric {
    val rawId = neteaseRawId(songId)
    val body = neteaseQuery(
        listOf("id" to rawId, "lv" to "-1", "kv" to "0", "tv" to "-1", "yv" to "-1"),
    )

    val data = httpPostJson(
        "https://interface3.music.163.com/api/song/lyric",
        headers = neteaseApiHeaders,
        body = body,
    )

    if (!neteaseIsOk(data["code"], 200)) throw IllegalStateException("netease lyric api error")

    val lrcRaw = data["lrc"]
    val tlRaw = data["tlyric"]
    val yrcRaw = data["yrc"]
    val lrc = (if (lrcRaw is Map<*, *>) lrcRaw["lyric"] else null)?.toString() ?: ""
    val tl = (if (tlRaw is Map<*, *>) tlRaw["lyric"] else null)?.toString() ?: ""
    val yrc = (if (yrcRaw is Map<*, *>) yrcRaw["lyric"] else null)?.toString() ?: ""

    return NeteaseLyric(
        lrc = if (lrc.isEmpty()) "[00:00.00]暂无歌词" else lrc,
        tlyric = if (tl.isEmpty()) null else tl,
        wordLyric = _neteaseYrcToWordLyric(yrc),
    )
}
