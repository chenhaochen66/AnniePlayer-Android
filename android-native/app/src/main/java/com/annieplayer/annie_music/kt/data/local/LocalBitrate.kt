package com.annieplayer.annie_music.kt.data.local

/**
 * 曲库里的「码率」到底该填什么（纯函数，可 JVM 单测）。
 *
 * ## 为什么需要它
 *
 * ffprobe 在 mp4/m4a 这类容器里**拿不到码率字段时不会留空，而是猜一个**：
 * `sampleRate × bitDepth × channels`。那是**解压后**的速率，不是文件实际码率。
 *
 * 实测（`Born Again (feat. Doja Cat & RAYE) - LISA.m4a`）：
 * ```
 * ALAC 24 bit / 48 kHz / 立体声  → 48000 × 24 × 2 = 2304 kbps   ← ffprobe 报的就是这个
 * 文件实际：55,931,275 字节 / 231 秒 = 1937 kbps
 * ```
 * 界面上显示 `2304K` 是假信息 —— 它描述的是"如果这段音频不压缩会占多少带宽"，
 * 不是这个文件到底存了多少数据。
 *
 * ## 判据为什么用「是否正好等于该公式」而不是 codec 名单
 *
 * - 只有**按公式猜**的那条路会得到精确相等的值；有损编码（MP3 320 / AAC 256）不会；
 * - FLAC 的码率是 ffprobe 按文件大小估的，不会等于公式值 → 原样保留；
 * - `.dsf` 这类**真·未压缩**格式本来就该等于公式值，换成文件平均码率后差别 <1%（只差标签体积）。
 *
 * 因此不需要维护一份"哪些编码会撒谎"的名单，也就不会跟新编码脱节。
 */

/**
 * 文件实际的平均码率（kbps）= `字节数 × 8 ÷ 时长秒 ÷ 1000`。
 *
 * 含标签与内嵌封面的体积（一首 55MB 的歌里封面约 200KB ≈ 0.4%）。
 * 对本地文件这是**唯一可验证**的值：我们手里只有文件本身。
 */
internal fun averageBitrateKbps(sizeBytes: Long, durationSec: Int): Int {
    if (sizeBytes <= 0 || durationSec <= 0) return 0
    return ((sizeBytes * 8L) / durationSec / 1000L).toInt()
}

/**
 * 修正「未压缩速率冒充码率」，规则见文件头。
 *
 * @param reportedKbps 探测层给的码率（ffprobe / TagLib / MediaMetadataRetriever）
 */
internal fun trueBitrateKbps(
    reportedKbps: Int,
    sampleRate: Int,
    bitDepth: Int,
    channels: Int,
    sizeBytes: Long,
    durationSec: Int,
): Int {
    val average = averageBitrateKbps(sizeBytes, durationSec)
    // 探测层一个字都没给 → 文件平均码率比"没有"有用
    if (reportedKbps <= 0) return average
    if (sampleRate <= 0 || bitDepth <= 0 || channels <= 0) return reportedKbps

    val uncompressedKbps = sampleRate.toLong() * bitDepth * channels / 1000L
    if (uncompressedKbps <= 0) return reportedKbps
    // ±1% 容差：整数取整会让两边差个几 kbps
    val looksUncompressed = kotlin.math.abs(reportedKbps - uncompressedKbps) <= uncompressedKbps / 100
    return if (looksUncompressed && average > 0) average else reportedKbps
}

/** 按上面的规则修正一条曲库记录（三条写入路径共用这一个入口） */
internal fun LocalTrackMeta.withTrueBitrate(): LocalTrackMeta = copy(
    bitrate = trueBitrateKbps(
        reportedKbps = bitrate,
        sampleRate = sampleRate,
        bitDepth = bitDepth,
        channels = channels,
        sizeBytes = size,
        durationSec = duration,
    ),
)
