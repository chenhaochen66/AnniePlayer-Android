package com.annieplayer.annie_music.kt.core.audio.apple

import com.annieplayer.annie_music.kt.data.model.AudioInfo
import com.annieplayer.annie_music.kt.data.model.Quality
import java.net.URI

/**
 * Apple Music HLS master 里的一个音频 variant。
 *
 * master 的 `#EXT-X-MEDIA` 提供 `SAMPLE-RATE` / `BIT-DEPTH` / `CHANNELS`，
 * `#EXT-X-STREAM-INF` 提供 `AUDIO` / `CODECS` / 带宽和 media playlist 地址。
 */
data class AppleVariant(
    val groupId: String,
    val codecs: String,
    val uri: String,
    val bandwidth: Long,
    val sampleRate: Int?,
    val bitDepth: Int?,
    val channels: String?,
    val kind: AppleVariantKind,
) {
    val tier: AppleTier?
        get() = kind.tier
}

enum class AppleVariantKind(val tier: AppleTier?) {
    AlacLossless(AppleTier.AlacLossless),
    AlacHiRes(AppleTier.AlacHiRes),
    Aac256(AppleTier.Aac256),
    Aac128(AppleTier.Aac256),
    Aac64(AppleTier.Aac256),
    Atmos(AppleTier.Atmos),
    Ac3(null),
    Binaural(null),
    Downmix(null),
    Other(null),
}

enum class AppleTier(val quality: Quality, val label: String) {
    AlacLossless(Quality.alac, "ALAC 无损"),
    AlacHiRes(Quality.hires, "ALAC 超高解析"),
    Aac256(Quality.aac256, "AAC 256"),
    Atmos(Quality.atmos, "Dolby Atmos"),
    ;

    /**
     * 播放缓存 key 里的档位标识。
     *
     * 必须区分档位：同一首歌可能有 44.1/24 与 176.4/24 两个 ALAC 变体（`/parse/song` 只给
     * master，档位是选出来的），只用 `alac` 会把两档的明文分片混在同一份缓存里。
     */
    val cacheTag: String
        get() = when (this) {
            AlacLossless -> "alac_lossless"
            AlacHiRes -> "alac_hires"
            Aac256 -> "aac256"
            Atmos -> "atmos"
        }
}

/**
 * 播放页「源格式」胶囊用的格式信息。
 *
 * Apple 的编码 / 位深 / 采样率在解析 master m3u8 时就已知（`EXT-X-MEDIA` 的
 * `SAMPLE-RATE` / `BIT-DEPTH`，或 `audio-alac-stereo-<rate>-<depth>` 组名），
 * 不需要再去 ffprobe —— 播放链路喂给 ffprobe 的是本机 `127.0.0.1` 的 m3u8 代理，
 * 探不到真实编码，探出来也只会是空胶囊。
 */
fun AppleVariant.toAudioInfo(): AudioInfo = AudioInfo(
    codec = kind.codecLabel(codecs),
    bitDepth = bitDepth ?: 0,
    sampleRate = sampleRate ?: 0,
    bitrate = bandwidth.toInt().coerceAtLeast(0),
)

private fun AppleVariantKind.codecLabel(codecs: String): String = when (this) {
    AppleVariantKind.AlacLossless, AppleVariantKind.AlacHiRes -> "ALAC"
    AppleVariantKind.Aac256, AppleVariantKind.Aac128, AppleVariantKind.Aac64 -> "AAC"
    AppleVariantKind.Atmos -> "E-AC3"
    AppleVariantKind.Ac3 -> "AC-3"
    AppleVariantKind.Binaural, AppleVariantKind.Downmix, AppleVariantKind.Other -> when {
        codecs.contains("alac", ignoreCase = true) -> "ALAC"
        codecs.contains("ec-3", ignoreCase = true) -> "E-AC3"
        codecs.contains("ac-3", ignoreCase = true) -> "AC-3"
        codecs.contains("mp4a", ignoreCase = true) -> "AAC"
        codecs.contains("flac", ignoreCase = true) -> "FLAC"
        else -> codecs.substringBefore(',').uppercase()
    }
}

private class AppleMediaAttributes(
    val sampleRate: Int?,
    val bitDepth: Int?,
    val channels: String?,
)

private val _attr = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")
private val _alacGroup = Regex("""audio-alac-stereo-(\d+)-(\d+)""", RegexOption.IGNORE_CASE)

/**
 * 解析 master m3u8。返回所有音频 variant，包含 BINAURAL / DOWNMIX 等非主档位。
 */
fun parseAppleMaster(masterUrl: String, text: String): List<AppleVariant> {
    val media = linkedMapOf<String, AppleMediaAttributes>()
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (!line.startsWith("#EXT-X-MEDIA:")) continue
        val attrs = parseAttributes(line.substringAfter(':'))
        val group = attrs["GROUP-ID"] ?: continue
        media[group] = AppleMediaAttributes(
            sampleRate = attrs["SAMPLE-RATE"]?.toIntOrNull(),
            bitDepth = attrs["BIT-DEPTH"]?.toIntOrNull(),
            channels = attrs["CHANNELS"],
        )
    }

    var pendingGroup: String? = null
    var pendingCodecs = ""
    var pendingBandwidth = 0L

    fun resolve(uri: String): String = runCatching {
        URI(masterUrl).resolve(uri).toString()
    }.getOrDefault(uri)

    val result = mutableListOf<AppleVariant>()
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.startsWith("#EXT-X-STREAM-INF:")) {
            val attrs = parseAttributes(line.substringAfter(':'))
            pendingGroup = attrs["AUDIO"]
            pendingCodecs = attrs["CODECS"].orEmpty()
            pendingBandwidth = (attrs["AVERAGE-BANDWIDTH"] ?: attrs["BANDWIDTH"])
                ?.toLongOrNull() ?: 0L
        } else if (line.isNotEmpty() && !line.startsWith("#") && pendingGroup != null) {
            val group = pendingGroup
            val info = media[group]
            val sampleRate = info?.sampleRate ?: sampleRateFromGroup(group)
            result += AppleVariant(
                groupId = group,
                codecs = pendingCodecs,
                uri = resolve(line),
                bandwidth = pendingBandwidth,
                sampleRate = sampleRate,
                bitDepth = info?.bitDepth ?: bitDepthFromGroup(group),
                channels = info?.channels,
                kind = kindOf(group, pendingCodecs, sampleRate),
            )
            pendingGroup = null
        }
    }
    return result
}

/**
 * 按用户选择的主档位挑具体 variant；Atmos 不参与 ALAC/AAC 降级。
 */
fun resolveAppleVariant(
    variants: List<AppleVariant>,
    tier: AppleTier,
): AppleVariant? = when (tier) {
    AppleTier.AlacLossless -> variants
        .filter { it.kind == AppleVariantKind.AlacLossless }
        .bestAlac()
        ?: variants.bestAlac()
        ?: variants.bestAac()

    AppleTier.AlacHiRes -> variants
        .filter { it.kind == AppleVariantKind.AlacHiRes }
        .bestAlac()
        ?: variants.bestAlac()
        ?: variants.bestAac()

    AppleTier.Aac256 -> variants.bestAac()

    AppleTier.Atmos -> variants
        .filter { it.kind == AppleVariantKind.Atmos }
        .sortedWith(compareByDescending<AppleVariant> { it.bandwidth }.thenBy { it.groupId })
        .firstOrNull()
}

/**
 * 按用户选中的档位解析 variant；**目标档位缺失时按高低顺序降级**。
 *
 * 播放与下载共用这一条链，避免出现「播放会降级、下载直接报错」的不一致：
 * ```
 * 目标档位缺失 → ALAC 超高解析 → ALAC 无损 → AAC 256
 * ```
 * 顺序与音质列表一致（超高解析在无损之上）；**Atmos 不参与这条链**——只有用户明确选了
 * Atmos 才去找 Atmos，选了 Atmos 而该曲没有，则按上面的链下降。
 */
fun resolveAppleVariantWithFallback(
    variants: List<AppleVariant>,
    tier: AppleTier,
): AppleVariant? =
    resolveAppleVariant(variants, tier)
        ?: FALLBACK_TIERS.firstNotNullOfOrNull { resolveAppleVariant(variants, it) }

/** 降级顺序（高 → 低）；不含 Atmos，选无损/超高解析时不该被顶到空间音频 */
private val FALLBACK_TIERS = listOf(
    AppleTier.AlacHiRes,
    AppleTier.AlacLossless,
    AppleTier.Aac256,
)

private fun List<AppleVariant>.bestAlac(): AppleVariant? =
    filter { it.kind == AppleVariantKind.AlacLossless || it.kind == AppleVariantKind.AlacHiRes }
        .sortedWith(
            compareByDescending<AppleVariant> { it.sampleRate ?: 0 }
                .thenByDescending { it.bandwidth }
                .thenBy { it.groupId }
        )
        .firstOrNull()

private fun List<AppleVariant>.bestAac(): AppleVariant? =
    listOf(AppleVariantKind.Aac256, AppleVariantKind.Aac128, AppleVariantKind.Aac64)
        .firstNotNullOfOrNull { kind ->
            filter { it.kind == kind }
                .sortedWith(compareByDescending<AppleVariant> { it.bandwidth }.thenBy { it.groupId })
                .firstOrNull()
        }

private fun kindOf(group: String, codecs: String, sampleRate: Int?): AppleVariantKind {
    val g = group.lowercase()
    val c = codecs.lowercase()
    return when {
        g.contains("binaural") -> AppleVariantKind.Binaural
        g.contains("downmix") -> AppleVariantKind.Downmix
        g.contains("alac") -> if ((sampleRate ?: 0) > 48000) {
            AppleVariantKind.AlacHiRes
        } else {
            AppleVariantKind.AlacLossless
        }
        g.contains("atmos") || c == "ec-3" -> AppleVariantKind.Atmos
        g.contains("stereo-256") -> AppleVariantKind.Aac256
        g.contains("stereo-128") -> AppleVariantKind.Aac128
        g.contains("he-stereo") -> AppleVariantKind.Aac64
        g.contains("ac3") || c == "ac-3" -> AppleVariantKind.Ac3
        c == "mp4a.40.2" && g.contains("256") -> AppleVariantKind.Aac256
        c == "mp4a.40.2" && g.contains("128") -> AppleVariantKind.Aac128
        c == "mp4a.40.5" -> AppleVariantKind.Aac64
        else -> AppleVariantKind.Other
    }
}

private fun parseAttributes(text: String): Map<String, String> =
    _attr.findAll(text).associate { match ->
        val key = match.groupValues[1].uppercase()
        val value = match.groupValues[2].trim().trim('"')
        key to value
    }

private fun sampleRateFromGroup(group: String): Int? =
    _alacGroup.find(group)?.groupValues?.getOrNull(1)?.toIntOrNull()

private fun bitDepthFromGroup(group: String): Int? =
    _alacGroup.find(group)?.groupValues?.getOrNull(2)?.toIntOrNull()
