package com.annieplayer.annie_music.kt.data.sources.qobuz

import com.annieplayer.annie_music.kt.core.util.lrcTimeOfMs
import com.annieplayer.annie_music.kt.data.network.httpGetJson
import com.annieplayer.annie_music.kt.data.network.retryUntilSuccess
import com.annieplayer.annie_music.kt.data.sources.common.encodeComponent

// ================================================================================================
// Qobuz 官方歌词（两步法）
// ================================================================================================

/**
 * 拉官方歌词。
 *
 * 第一步 `track/lyricsUrl` 只返回一个带签名的 CloudFront URL（`language` 传空串取原文、
 * 传语言码取该语言译文）；第二步对该 URL 发裸 GET 才拿到正文。
 * 服务端行时间是毫秒，转成 `[mm:ss.mmm]` LRC 以复用现有歌词解析/渲染管线。
 *
 * [translateLang] 为空或不在可用语言清单里时不拉译文（返回 [QobuzLyricResult.tlyric] = null）；
 * [autoPickFirst] 为 true 时改为默认取清单里第一个可用语言（播放页没显式选语言时的默认行为）。
 */
suspend fun fetchQobuzLyrics(
    trackId: String,
    token: String,
    translateLang: String? = null,
    autoPickFirst: Boolean = false,
): QobuzLyricResult {
    val original = _fetchQobuzLyricsBody(trackId, token, "")
    val langs = (original["translation_langs"] as? List<*>)
        ?.mapNotNull { it?.toString() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()
    val lrc = _qobuzLyricsDtoToLrc(original["original"])
    val effective = when {
        !translateLang.isNullOrEmpty() && translateLang in langs -> translateLang
        autoPickFirst -> langs.firstOrNull()
        else -> null
    }
    if (effective == null) {
        return QobuzLyricResult(lrc = lrc, tlyric = null, translateLangs = langs)
    }
    val translated = _fetchQobuzLyricsBody(trackId, token, effective)
    return QobuzLyricResult(
        lrc = lrc,
        tlyric = _qobuzLyricsDtoToLrc(translated["translation"]).ifEmpty { null },
        translateLangs = langs,
        translateLang = effective,
    )
}

/** 取签名 URL 再裸 GET，返回歌词 JSON */
private suspend fun _fetchQobuzLyricsBody(
    trackId: String,
    token: String,
    language: String,
): Map<String, Any?> {
    val s = createSignature("track/lyricsUrl", mapOf("track_id" to trackId, "language" to language))
    val qs = listOf(
        "track_id=${encodeComponent(trackId)}",
        "language=${encodeComponent(language)}",
        "app_id=$qobuzAppId",
        "request_ts=${s.ts}",
        "request_sig=${s.sig}",
    ).joinToString("&")
    val head = retryUntilSuccess { qobuzGetJson("${qobuzBaseUrl}track/lyricsUrl?$qs", token) }
    val url = head["lyrics_url"]?.toString().orEmpty()
    if (url.isEmpty()) throw IllegalStateException("歌词 URL 为空")
    return httpGetJson(url)
}

/** `LyricsDto{ lines:[{line,start,end}] }` -> LRC */
private fun _qobuzLyricsDtoToLrc(dto: Any?): String {
    val lines = (dto as? Map<*, *>)?.get("lines") as? List<*> ?: return ""
    val sb = StringBuilder()
    for (item in lines) {
        val row = item as? Map<*, *> ?: continue
        val text = row["line"]?.toString().orEmpty()
        if (text.isEmpty()) continue
        val startMs = (row["start"] as? Number)?.toInt() ?: 0
        sb.append(lrcTimeOfMs(startMs)).append(text).append('\n')
    }
    return sb.toString()
}
