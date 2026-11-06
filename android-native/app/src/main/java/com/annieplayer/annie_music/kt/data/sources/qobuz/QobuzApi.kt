package com.annieplayer.annie_music.kt.data.sources.qobuz

import com.annieplayer.annie_music.kt.data.model.QobuzAlbum
import com.annieplayer.annie_music.kt.data.model.QobuzTrack
import com.annieplayer.annie_music.kt.data.network.retryUntilSuccess
import com.annieplayer.annie_music.kt.data.sources.common.encodeComponent
import com.annieplayer.annie_music.kt.data.sources.common.firstTruthy
import kotlinx.coroutines.withTimeout
import java.io.IOException

// ================================================================================================
// Qobuz 官方接口入口：token / 单曲 / 专辑 / 音频直链 / 链接解析
// ================================================================================================
//
// 1:1 移植 Flutter `lib/data/sources/qobuz_api.dart`，保持函数名、参数与返回语义。
//
// 拆分包结构：
//   QobuzConfig.kt  常量 / 音质文案 / token 注入点
//   QobuzHttp.kt    签名 / 请求头 / GET（非 2xx 还原成 HTTP {code}）
//   QobuzMapper.kt  JSON -> Qobuz* -> Song/Album（纯函数）
//   QobuzSearch.kt  搜索 + 歌手页（免 token）
//   QobuzLyric.kt   官方歌词两步法
//   QobuzTypes.kt   跨文件共用的记录类型
//   本文件          需要 token 的那几条：user/get、track/get、album/get、track/getFileUrl

/** 校验 Token 有效性（对齐 RN 版 checkToken，10 秒超时） */
suspend fun checkToken(token: String): QobuzTokenStatus {
    val params = mapOf("app_id" to qobuzAppId)
    val s = createSignature("user/get", params)
    val url =
        "${qobuzBaseUrl}user/get?app_id=$qobuzAppId&request_ts=${s.ts}&request_sig=${s.sig}"
    return try {
        val data = withTimeout(10_000L) { qobuzGetJson(url, token) }
        val region = firstTruthy(listOf(data["region"], data["country"]))?.toString() ?: ""
        QobuzTokenStatus(region = region, valid = region.isNotEmpty())
    } catch (e: IOException) {
        val status = qobuzHttpStatusFrom(e)
        when {
            status == 401 -> QobuzTokenStatus(region = "", valid = false)
            status != null -> throw IllegalStateException("HTTP $status")
            else -> throw e
        }
    }
}

/** 拉取单曲原始 JSON（对齐 RN 版 getTrack 的网络行为） */
suspend fun fetchTrackJson(trackId: String, token: String): Map<String, Any?> {
    val params = mapOf("app_id" to qobuzAppId, "track_id" to trackId)
    val s = createSignature("track/get", params)
    val url =
        "${qobuzBaseUrl}track/get?app_id=$qobuzAppId&track_id=$trackId" +
            "&request_ts=${s.ts}&request_sig=${s.sig}"
    return retryUntilSuccess { qobuzGetJson(url, token) }
}

/** 拉取专辑原始 JSON（对齐 RN 版 getAlbum 的网络行为） */
suspend fun fetchAlbumJson(albumId: String, token: String): Map<String, Any?> {
    val params = mapOf("app_id" to qobuzAppId, "album_id" to albumId)
    val s = createSignature("album/get", params)
    val url =
        "${qobuzBaseUrl}album/get?app_id=$qobuzAppId&album_id=$albumId" +
            "&request_ts=${s.ts}&request_sig=${s.sig}"
    return retryUntilSuccess { qobuzGetJson(url, token) }
}

/** 获取单曲（返回映射后的 QobuzTrack） */
suspend fun getTrack(trackId: String, token: String): QobuzTrack =
    mapTrackToQobuzTrack(fetchTrackJson(trackId, token))

/** 获取专辑（返回映射后的 QobuzAlbum） */
suspend fun getQobuzAlbum(albumId: String, token: String): QobuzAlbum =
    mapAlbumToQobuzAlbum(fetchAlbumJson(albumId, token))

/**
 * 获取音频直链（对齐 RN 版 getFileUrl）。
 *
 * [qualityId] 对应 Qobuz 的 format_id，默认为 5。
 */
suspend fun getFileUrl(trackId: String, token: String, qualityId: Int = 5): QobuzFileUrl {
    val params = mapOf(
        "app_id" to qobuzAppId,
        "track_id" to trackId,
        "format_id" to qualityId.toString(),
        "intent" to "stream",
        "sample" to "false",
        "user_auth_token" to token,
    )
    val s = createSignature("track/getFileUrl", params)
    val allParams = LinkedHashMap<String, String>(params)
    allParams["request_ts"] = s.ts
    allParams["request_sig"] = s.sig
    val qs = allParams.entries.joinToString("&") { "${it.key}=${encodeComponent(it.value)}" }
    val url = "${qobuzBaseUrl}track/getFileUrl?$qs"
    val data = retryUntilSuccess(maxRetries = 2, delayMs = 500L) { qobuzGetJson(url, token) }
    return QobuzFileUrl(
        url = data["url"]?.toString() ?: "",
        mimeType = firstTruthy(listOf(data["mime_type"]))?.toString() ?: "audio/flac",
    )
}

/** 从文本解析 Qobuz 链接（对齐 RN 版 parseQobuzUrl） */
fun parseQobuzUrl(text: String): QobuzUrlRef? {
    val re by lazy { Regex("https?://open\\.qobuz\\.com/(track|album)/([a-zA-Z0-9]+)") }
    val m = re.find(text) ?: return null
    return QobuzUrlRef(type = m.groupValues[1], id = m.groupValues[2])
}
