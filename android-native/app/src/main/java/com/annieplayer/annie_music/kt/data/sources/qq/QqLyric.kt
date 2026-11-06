package com.annieplayer.annie_music.kt.data.sources.qq

import android.util.Base64
import com.annieplayer.annie_music.kt.core.util.QrcDes
import com.annieplayer.annie_music.kt.core.util.lrcTimeOfMs
import com.annieplayer.annie_music.kt.core.util.mmssOfMs
import com.annieplayer.annie_music.kt.data.network.httpPostJson
import com.annieplayer.annie_music.kt.data.sources.common.jsTruthy
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

// ================================================================================================
// QQ 歌词（QRC 逐字为主，普通 LRC 兜底）
// ================================================================================================

/** QQ 歌词 base64 解码：等价于 RN 版 `decodeURIComponent(escape(atob(x)))` */
private fun _decodeQQBase64(b64: String): String {
    val bytes = Base64.decode(b64, Base64.DEFAULT)
    // Dart: utf8.decode(latin1.decode(bytes).codeUnits) —— 等价于把原始字节按 UTF-8 解码；
    // 非法序列抛异常（由调用方 catch 回退占位歌词）。
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return decoder.decode(ByteBuffer.wrap(bytes)).toString()
}

/** QRC 行：`[起始ms,时长ms]词(起始ms,时长ms)词…`（词起始小于行首时按相对值处理） */
private val _reQrcLine by lazy { Regex("^\\[(\\d+),(\\d+)\\](.+)$") }

/** 词块文本必须**遇换行即停**：`[^(]*` 会跨行吞掉下一行的 `[start,dur]`，拼接后就少了空格 */
private val _reQrcWord by lazy { Regex("\\((\\d+),(\\d+)\\)([^(\\n]*)") }

/** QRC -> 行级 lrc + 统一逐字格式 */
private fun _qrcToLrcAndWords(qrc: String): Pair<String, String?> {
    val lrc = StringBuilder()
    val wl = StringBuilder()
    for (line in qrc.split('\n')) {
        val m = _reQrcLine.find(line.trim()) ?: continue
        val lineStart = m.groupValues[1].toIntOrNull() ?: continue
        val body = m.groupValues[3]
        // QRC 行形如「首字(起始,时长)字(起始,时长)…」：首个标记**之前**还有一段文本（首字/首词），
        // 只取标记之后的文本会丢掉每行第一个字（真机现象：QQ 每行歌词第一个字不显示）。
        // 首个标记之前的文本（首词）**不要 trim**：QRC 里首词与第二个词之间的空格在 `(` 之前，
        // trim 掉就会变成「Helloit's me」（真机 + 下载标签都复现过）。
        val lead = body.substringBefore('(')
        val marks = _reQrcWord.findAll(body).toList()
        if (lead.isBlank() && marks.isEmpty()) continue
        val text = (lead + marks.joinToString("") { it.groupValues[3] }).trim()
        if (text.isEmpty()) continue
        val ts = lrcTimeOfMs(lineStart)
        lrc.append(ts).append(text).append('\n')
        val wordsSb = StringBuilder()
        if (lead.isNotBlank()) {
            wordsSb.append('<').append(mmssOfMs(lineStart)).append('>').append(lead)
        }
        // QRC 的词时间是**相对行首**的（实测 Kill Bill：0/465/930…）；按整行判断，
        // 不要逐词判断「小于行首就当相对」，行首较小时会误判成绝对值。
        val allRelative = marks.all { (it.groupValues[1].toIntOrNull() ?: 0) < lineStart }
        for (w in marks) {
            val rawStart = w.groupValues[1].toIntOrNull() ?: 0
            val abs = if (allRelative) lineStart + rawStart else rawStart
            wordsSb.append('<').append(mmssOfMs(abs)).append('>').append(w.groupValues[3])
        }
        wl.append(ts).append(wordsSb).append('\n')
    }
    return lrc.toString().trimEnd('\n') to (if (wl.isEmpty()) null else wl.toString().trimEnd('\n'))
}

/** QQ `trans` 字段（base64 翻译歌词） */
private fun _qqTrans(d: Map<*, *>?): String? {
    if (d == null || !jsTruthy(d["trans"])) return null
    val b64 = if (d["trans"] is String) d["trans"] as String else return null
    return try {
        _decodeQQBase64(b64)
    } catch (_: Throwable) {
        null
    }
}

/** 获取 QQ 歌词，返回原始 lrc、翻译歌词与逐字歌词；失败返回占位歌词 */
suspend fun fetchQQLyric(songId: String): QqLyric {
    val fallback = QqLyric("[00:00.00]暂无歌词", null)
    val songMid = qqRawId(songId)

    suspend fun requestLyric(qrc: Int): Map<*, *>? = try {
        val payload: Map<String, Any?> = mapOf(
            "request" to mapOf(
                "module" to "music.musichallSong.PlayLyricInfo",
                "method" to "GetPlayLyricInfo",
                "param" to mapOf("songMID" to songMid, "crypt" to 0, "qrc" to qrc, "trans" to 1),
            ),
        )
        val data = httpPostJson(
            qqMusicuUrl,
            body = payload,
            headers = mapOf(
                "User-Agent" to "okhttp/3.14.9",
                "Content-Type" to "application/json; charset=utf-8",
                "Cookie" to "tmeLoginType=-1;",
            ),
        )
        val reqRaw = data["request"]
        qqToMap(if (reqRaw is Map<*, *>) reqRaw["data"] else null)
    } catch (_: Throwable) {
        null
    }

    val dQrc = requestLyric(1)
    val lyricQrc = if (dQrc?.get("lyric") is String) dQrc["lyric"] as String else ""
    val decrypted = QrcDes.decryptToQrc(lyricQrc)
    if (decrypted != null) {
        val parsed = _qrcToLrcAndWords(decrypted)
        if (parsed.first.isNotEmpty()) {
            return QqLyric(lrc = parsed.first, tlyric = _qqTrans(dQrc), wordLyric = parsed.second)
        }
    }

    // QRC 解密失败或该曲无 QRC：退回普通 LRC 再请求一次（保证歌词一定能显示）
    val dPlain = requestLyric(0)
    val lyricPlain = if (dPlain?.get("lyric") is String) dPlain["lyric"] as String else ""
    if (lyricPlain.isEmpty()) return fallback
    return try {
        QqLyric(lrc = _decodeQQBase64(lyricPlain), tlyric = _qqTrans(dPlain), wordLyric = null)
    } catch (_: Throwable) {
        fallback
    }
}
