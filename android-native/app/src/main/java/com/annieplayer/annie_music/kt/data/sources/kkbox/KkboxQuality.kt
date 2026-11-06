package com.annieplayer.annie_music.kt.data.sources.kkbox

// ================================================================================================
// KKBOX 音质：档位 ↔ 接口 format_name、扩展名、DRM 判定
// ================================================================================================

/** 音质 key -> KKBOX 的 format_name */
internal val kkboxQualityFormatMap: Map<String, String> = mapOf(
    "128k" to "mp3_128k_chromecast",
    "192k" to "mp3_192k_kkdrm1",
    "320k" to "aac_320k_m4a_kkdrm1",
    "hifi" to "flac_16_download_kkdrm",
    "hires" to "flac_24_download_kkdrm",
)

/** 高到低的档位顺序（下载直链降级用） */
internal val kkboxQualityOrder: List<String> = listOf("hires", "hifi", "320k", "192k", "128k")

private val _formatToQuality: Map<String, String> =
    kkboxQualityFormatMap.entries.associate { (k, v) -> v to k }

/** 格式名 → 音质 key（未知默认 128k） */
fun formatNameToQuality(formatName: String): String =
    _formatToQuality[formatName] ?: "128k"

/** 是否 DRM 格式（非 mp3_128k_chromecast 即 DRM，需解密） */
fun isDrmFormat(formatName: String): Boolean =
    formatName != "mp3_128k_chromecast"

/** 音质 → 文件扩展名 */
fun qualityExtension(quality: String): String {
    if (quality == "hifi" || quality == "hires") return ".flac"
    if (quality == "320k") return ".m4a"
    return ".mp3"
}

/** 可用音质列表（缺省 ['128k']） */
fun getAvailableQualities(song: Map<String, Any?>): List<String> {
    val q = song["audio_quality"]
    if (q is List<*>) return q.map { it.toString() }
    return listOf("128k")
}
