package com.annieplayer.annie_music.kt.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「文件实际码率」的规则测试。
 *
 * 锁住的是一个**实测出来的假数据**：ffprobe 在 mp4/m4a 里拿不到码率时会按
 * `采样率 × 位深 × 声道` 猜一个填进去，那是解压后的速率。
 *
 * 真值取自本机曲库里的一首（`Born Again (feat. Doja Cat & RAYE) - LISA.m4a`）：
 * ```
 * size      = 55,931,275 字节
 * duration  = 231 秒
 * 索引记的  = 2304 kbps（= 48000 × 24 × 2）
 * 实际平均  = 1937 kbps
 * ```
 */
class LocalBitrateTest {

    /** 上面那首 24/48 ALAC 的真实参数 */
    private val bornAgainSize = 55_931_275L
    private val bornAgainSec = 231

    @Test
    fun `未压缩速率冒充码率时要换成文件平均码率`() {
        assertEquals(
            1937,
            trueBitrateKbps(
                reportedKbps = 2304,     // 48000 × 24 × 2 / 1000，ffprobe 的猜测值
                sampleRate = 48_000,
                bitDepth = 24,
                channels = 2,
                sizeBytes = bornAgainSize,
                durationSec = bornAgainSec,
            ),
        )
    }

    @Test
    fun `有损编码的真实码率不能被改`() {
        // MP3 320：320 != 44100×16×2/1000(1411) → 保留
        assertEquals(
            320,
            trueBitrateKbps(
                reportedKbps = 320,
                sampleRate = 44_100,
                bitDepth = 16,
                channels = 2,
                sizeBytes = 9_600_000L,
                durationSec = 240,
            ),
        )
        // AAC 256：同理
        assertEquals(
            256,
            trueBitrateKbps(
                reportedKbps = 256,
                sampleRate = 44_100,
                bitDepth = 16,
                channels = 2,
                sizeBytes = 7_680_000L,
                durationSec = 240,
            ),
        )
    }

    @Test
    fun `FLAC 按文件大小估的码率本来就对 不要动`() {
        // 900 != 1411 → 保留（ffprobe 对 FLAC 是按文件大小估的，本来就接近真值）
        assertEquals(
            900,
            trueBitrateKbps(
                reportedKbps = 900,
                sampleRate = 44_100,
                bitDepth = 16,
                channels = 2,
                sizeBytes = 27_000_000L,
                durationSec = 240,
            ),
        )
    }

    @Test
    fun `探测层没给码率时用文件平均码率兜底`() {
        assertEquals(
            160,
            trueBitrateKbps(
                reportedKbps = 0,
                sampleRate = 44_100,
                bitDepth = 16,
                channels = 2,
                sizeBytes = 4_800_000L,
                durationSec = 240,
            ),
        )
    }

    @Test
    fun `缺参数时不乱改`() {
        // 位深未知（有损编码会被清成 0）→ 无法判断是否未压缩速率 → 原样返回
        assertEquals(
            2304,
            trueBitrateKbps(
                reportedKbps = 2304,
                sampleRate = 48_000,
                bitDepth = 0,
                channels = 2,
                sizeBytes = bornAgainSize,
                durationSec = bornAgainSec,
            ),
        )
    }

    @Test
    fun `拿不到文件体积或时长时保持原值`() {
        assertEquals(
            2304,
            trueBitrateKbps(
                reportedKbps = 2304,
                sampleRate = 48_000,
                bitDepth = 24,
                channels = 2,
                sizeBytes = 0,
                durationSec = bornAgainSec,
            ),
        )
        assertEquals(
            2304,
            trueBitrateKbps(
                reportedKbps = 2304,
                sampleRate = 48_000,
                bitDepth = 24,
                channels = 2,
                sizeBytes = bornAgainSize,
                durationSec = 0,
            ),
        )
    }

    @Test
    fun `平均码率按字节数折算`() {
        // 55,931,275 × 8 / 231 / 1000 = 1937.0
        assertEquals(1937, averageBitrateKbps(bornAgainSize, bornAgainSec))
        // 1 MB / 8 秒 = 1000 kbps
        assertEquals(1000, averageBitrateKbps(1_000_000L, 8))
        // 非法输入返回 0（调用方据此决定不改动）
        assertEquals(0, averageBitrateKbps(0, 100))
        assertEquals(0, averageBitrateKbps(1_000_000L, 0))
    }
}
