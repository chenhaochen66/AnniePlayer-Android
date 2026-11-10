package com.annieplayer.annie_music.kt.data.sources.kkbox

import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.network.anyToJsonElement
import com.annieplayer.annie_music.kt.core.state.MoreSettings
import kotlinx.coroutines.delay

// ================================================================================================
// KKBOX 播放票据 / 播放直链 / 下载直链
// ================================================================================================
//
// KKBOX 的取链**只走官方票据接口**（`v1/ticket`）。历史上的「KKBOX 借酷我直链」已经删除：
// KKBOX 的 track id 在酷我根本不存在，那条路是死路，产出的只会是 404 或别的歌。
//
// 会话（sid + kkid [+ licContentKey]）由 `kkboxSessionProvider()` 注入；未登录直接失败。

/**
 * 官方播放直链（用注入的当前会话 + 当前播放档位）。
 *
 * 128k 走 `mp3_128k_chromecast`（唯一非 DRM，能直接流播）；320k / hifi / hires 走
 * `*_download_kkdrm` 先解密成本地文件再播 —— 票据里的 `*_hls_cenc` 是 CENC 加密，
 * 需要 Widevine，我们拿不到。解密与缓存见 [KkboxPlayCache]。
 */
suspend fun fetchKkboxPlayUrl(song: Song): String {
    val session = kkboxSessionProvider()
        ?: throw IllegalStateException("KKBOX 需要先登录账号才能播放")
    val configured = MoreSettings.qualityFor(MusicPlatform.kkbox)
    return KkboxPlayCache.playUrl(song, kkboxPlayQuality(song, configured), session)
}

/** 官方下载票据（含 DRM 格式名与可能被续期的 sid）；供 `KkboxDownloader` 用 */
suspend fun fetchKkboxDownloadTicket(songId: String, quality: String = "hires"): KkboxDownloadResult {
    val rawId = songId.removePrefix("kkbox:")
    val session = kkboxSessionProvider()
        ?: throw IllegalStateException("KKBOX 需要先登录账号才能下载")
    val dl = getDownloadUrl(session.sid, rawId, session.kkid, quality)
    if (dl.sid != session.sid) kkboxSessionUpdater(session.copy(sid = dl.sid))
    if (dl.url.isEmpty()) {
        throw IllegalStateException("KKBOX 未返回下载地址（可能是会员权限或地区限制）")
    }
    return dl
}

/** 获取播放票据，含 -1/-4/2/-2 状态码重试与续期逻辑 */
suspend fun getTicket(
    sid: String,
    songId: String,
    kkid: String,
    playMode: String? = null,
    retried: Boolean = false,
): KkboxTicket {
    val timestamp = System.currentTimeMillis() / 1000
    val payload = anyToJsonElement(
        mapOf<String, Any?>(
            "sid" to sid,
            "song_id" to songId,
            "ver" to "06120082",
            "os" to "android",
            "osver" to "13",
            "kkid" to kkid,
            "dist" to "0021",
            "dist2" to "0021",
            "timestamp" to timestamp,
            "play_mode" to playMode,
        ),
    ).toString()
    val resp = kkboxApiCall("ticket", "v1/ticket", params = mapOf("sid" to sid), payload = payload)

    val status = kkboxInt(resp["status"])
    if (status != 1) {
        if (status == -1) {
            val renewed = renewSession(sid)
            return getTicket(renewed.sid, songId, kkid, playMode)
        }
        if (status == -4) {
            authDevice(sid, kkid)
            return getTicket(sid, songId, kkid, playMode)
        }
        if (status == 2) {
            delay(500L)
            return getTicket(sid, songId, kkid, playMode)
        }
        if (status == -2) {
            if (retried) {
                val js = anyToJsonElement(resp).toString()
                throw IllegalStateException(
                    "获取播放票据失败(status=-2): " +
                        (if (js.length > 200) js.substring(0, 200) else js),
                )
            }
            return getTicket(sid, songId, kkid, playMode, true)
        }
        if (retried) throw IllegalStateException("获取播放票据失败(status=$status)")
        return getTicket(sid, songId, kkid, playMode, true)
    }

    return KkboxTicket(uris = kkboxMapList(resp["uris"]), sid = sid)
}

private fun _firstUri(
    uris: List<Map<String, Any?>>,
    test: (Map<String, Any?>) -> Boolean,
): Map<String, Any?>? {
    for (u in uris) {
        if (test(u)) return u
    }
    return null
}

private fun _uriUrl(u: Map<String, Any?>?): String =
    if (u == null) "" else (u["url"] ?: "").toString()

private fun _uriName(u: Map<String, Any?>?): String =
    if (u == null) "" else (u["name"] ?: "").toString()

/**
 * 获取播放直链 —— **只认 `mp3_128k_chromecast` 这一档明文**。
 *
 * 很多曲目的票据里**根本没有**这一档（只有 kkdrm / hls_cenc），所以拿不到就返回空 url，
 * 由调用方转到「解密播放」。**不能退而取 `uris.first()`**：第一个往往是 `aac_128_hls_cenc`
 * 这类 CENC 加密流，播放器能解析出 m3u8、播一小段才因为拿不到 key 失败 —— 表现正是
 * 「播一分钟就停」。
 */
suspend fun getPlayUrl(sid: String, songId: String, kkid: String): KkboxPlayResult {
    val ticket = getTicket(sid, songId, kkid, "chromecast")
    val uris = ticket.uris
    val found = _firstUri(uris) { _uriName(it) == "mp3_128k_chromecast" }
    return KkboxPlayResult(url = _uriUrl(found), sid = ticket.sid)
}

/**
 * 下载/解密可用的格式名，按优先级排列（高 → 低）。
 *
 * 含 `mp3_128k_kkdrm1`（128k 的 DRM 版）：曲目没有明文 chromcast 档时，128k 也只能靠它。
 */
private val _downloadFormatOrder: List<String> = listOf(
    "flac_24_download_kkdrm",
    "flac_16_download_kkdrm",
    "aac_320k_m4a_kkdrm1",
    "mp3_192k_kkdrm1",
    "mp3_128k_chromecast",
    "mp3_128k_kkdrm1",
)

/** 获取下载直链（按 quality 请求，缺失时按 [_downloadFormatOrder] 降级） */
suspend fun getDownloadUrl(
    sid: String,
    songId: String,
    kkid: String,
    quality: String,
): KkboxDownloadResult {
    val requestedFormat = kkboxQualityFormatMap[quality] ?: "flac_24_download_kkdrm"
    val playMode = if (requestedFormat == "mp3_128k_chromecast") "chromecast" else null
    val ticket = getTicket(sid, songId, kkid, playMode)
    val uris = ticket.uris

    val found = _firstUri(uris) { _uriName(it) == requestedFormat }
    if (found != null) {
        return KkboxDownloadResult(
            url = _uriUrl(found),
            formatName = _uriName(found),
            sid = ticket.sid,
        )
    }
    for (format in _downloadFormatOrder) {
        if (format == requestedFormat) continue
        val f = _firstUri(uris) { _uriName(it) == format }
        if (f != null) {
            return KkboxDownloadResult(
                url = _uriUrl(f),
                formatName = _uriName(f),
                sid = ticket.sid,
            )
        }
    }
    return KkboxDownloadResult(url = "", formatName = "", sid = ticket.sid)
}
