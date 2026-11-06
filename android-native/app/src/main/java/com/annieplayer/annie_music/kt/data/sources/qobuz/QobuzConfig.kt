package com.annieplayer.annie_music.kt.data.sources.qobuz

import com.annieplayer.annie_music.kt.data.model.QobuzAlbum
import com.annieplayer.annie_music.kt.data.model.Quality

// ================================================================================================
// Qobuz 常量 / 音质文案 / token 注入点
// ================================================================================================
//
// 常量与 RN 版完全一致，**不要改动**：app_id + app_secret 参与签名，改一个字节所有请求都 401。

/** Qobuz 应用 ID（对齐 RN 版 APP_ID） */
const val qobuzAppId = "312369995"

/** Qobuz 应用密钥（对齐 RN 版 APP_SECRET） */
const val qobuzAppSecret = "e79f8b9be485692b0e5f9dd895826368"

/** Qobuz API 基址（对齐 RN 版 BASE_URL） */
const val qobuzBaseUrl = "https://www.qobuz.com/api.json/0.2/"

/** getFileUrl 的 format_id：27 = FLAC 24bit，6 = FLAC CD，5 = MP3 320 */
const val qobuzHiresFormatId = 27
const val qobuzFlacFormatId = 6
const val qobuzMp3FormatId = 5

/** 活跃 token 提供者，由 AppShell 注入 `QobuzViewModel.getActiveToken()?.token` */
var qobuzTokenProvider: () -> String? = { null }

/** 音质档位在 Qobuz 侧的显示名（下载列表 / 详情页共用）。 */
fun qobuzQualityLabel(q: Quality): String = when (q) {
    Quality.hires -> "FLAC Hires Max"
    Quality.flac -> "FLAC 16bit/44.1kHz"
    Quality.k320k -> "MP3 320"
    Quality.k128k -> "MP3 128"
    else -> q.label
}

/** 专辑规格文案（位深 / 采样率 / Hi-Res）；无数据返回空串 */
fun qobuzQualitySpec(a: QobuzAlbum): String = listOfNotNull(
    a.maxBitDepth?.let { "${it}bit" },
    a.maxSamplingRateKhz?.let { khz ->
        val text = if (khz % 1.0 == 0.0) khz.toInt().toString() else khz.toString()
        "${text}kHz"
    },
    if (a.hires) "Hi-Res" else null,
).joinToString(" / ")

/** 音质档位 → Qobuz 的 `format_id`（getFileUrl 的入参）。 */
fun qobuzQualityFormatId(q: Quality): Int = when (q) {
    Quality.hires -> qobuzHiresFormatId
    Quality.flac -> qobuzFlacFormatId
    else -> qobuzMp3FormatId
}
