package com.annieplayer.annie_music.kt.core.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 阻带位深的判定：`resampleAttenuationBits()`。
 *
 * 核心事实只有一条：**float 只表示"我们没在这里降位深"，不表示"源有 32 位的精度"**。
 * 有损源的噪声底由编码决定，按 32 Bit 算只会换来更长更慢的滤波器。
 */
class ResampleDepthTest {

    @Test
    fun `有损源把过渡带放宽到 5 百分比`() {
        // 有损编码器自己的低通在 17.5–21.6 kHz，21.6 kHz 以上没有信号可保 ——
        // 2% 那条"保高频"的理由不成立，放宽只赚（抽头更短、振铃更短）。
        // 5% 在 44.1k 上是 −3 dB @ 20.95 kHz（实测 flt_len 433 vs 2% 的 1081）。
        assertEquals(5.0, resampleTransitionBandPercent("AAC"), 1e-9)
        assertEquals(5.0, resampleTransitionBandPercent("mp3"), 1e-9)
        assertEquals(5.0, resampleTransitionBandPercent("  OPUS "), 1e-9)
        // 无损 / 不认识 → 0 = 保持 native 默认的 2.0。
        // ⚠️ 无损那个 2.0 是四种组合实听验出来的结论，别被这条改动顺手推翻。
        assertEquals(0.0, resampleTransitionBandPercent("flac"), 1e-9)
        assertEquals(0.0, resampleTransitionBandPercent("alac"), 1e-9)
        assertEquals(0.0, resampleTransitionBandPercent("dsd_msbf"), 1e-9)
        assertEquals(0.0, resampleTransitionBandPercent("xyz"), 1e-9)
        assertEquals(0.0, resampleTransitionBandPercent(null), 1e-9)
    }

    @Test
    fun `阻带与过渡带必须用同一个有损判据`() {
        // 两个都要按"有损"处理，不能一个按有损算、一个按无损算 ——
        // 否则会出现"阻带按 16 Bit（短）+ 过渡带按 2%（长）"这种自相矛盾的组合，
        // 而且没人能从代码里看出为什么不一致。
        for (codec in listOf("aac", "mp3", "opus", "vorbis", "eac3")) {
            assertEquals(
                "有损判据不一致：$codec",
                16,
                resampleAttenuationBits(codec, 0),
            )
            assertEquals(
                "有损判据不一致：$codec",
                5.0,
                resampleTransitionBandPercent(codec),
                1e-9,
            )
        }
        for (codec in listOf("flac", "alac", "wav", "dsf")) {
            assertEquals(0.0, resampleTransitionBandPercent(codec), 1e-9)
        }
    }

    @Test
    fun `有损编码一律按 16 Bit 算`() {
        // Apple 的 AAC 走 ffmpeg → float；如果按容器算就是 206.91 dB / 1621 抽头
        assertEquals(16, resampleAttenuationBits("AAC", 0))
        assertEquals(16, resampleAttenuationBits("aac", 32))
        assertEquals(16, resampleAttenuationBits("mp4a", 0))
        assertEquals(16, resampleAttenuationBits("MP3", 0))
        assertEquals(16, resampleAttenuationBits("opus", 0))
        assertEquals(16, resampleAttenuationBits("vorbis", 0))
        assertEquals(16, resampleAttenuationBits("eac3", 0))
        // 大小写与空白都不该影响判定（曲库索引给的是 uppercase，ffprobe 给的是小写）
        assertEquals(16, resampleAttenuationBits("  Aac  ", 0))
    }

    @Test
    fun `无损按它自己声明的位深算`() {
        assertEquals(24, resampleAttenuationBits("FLAC", 24))
        assertEquals(16, resampleAttenuationBits("flac", 16))
        assertEquals(24, resampleAttenuationBits("ALAC", 24))
        assertEquals(32, resampleAttenuationBits("wav", 32))
    }

    @Test
    fun `不知道就不决定`() {
        // 声明位深缺失 → 交回处理器按解码出口推断，而不是猜 16
        assertEquals(0, resampleAttenuationBits("FLAC", 0))
        // 位深是脏数据（0 / 1 / 8 / 64）同样不决定
        assertEquals(0, resampleAttenuationBits("flac", 1))
        assertEquals(0, resampleAttenuationBits("flac", 8))
        assertEquals(0, resampleAttenuationBits("flac", 64))
        // 不认识的编码
        assertEquals(0, resampleAttenuationBits("xyz", 24))
        assertEquals(0, resampleAttenuationBits("", 24))
        assertEquals(0, resampleAttenuationBits(null, 24))
    }

    @Test
    fun `DSD 不能被当成低精度`() {
        // DSD 的 "位深" 是 1，落不进 16..32 —— 必须返回 0（交给推断），
        // 因为 DSD 解码出口是 float，抽取那一级**需要**最深阻带。
        assertEquals(0, resampleAttenuationBits("dsd_msbf", 1))
        assertEquals(0, resampleAttenuationBits("dsd_lsbf_planar", 1))
        assertEquals(0, resampleAttenuationBits("dsf", 1))
    }
}
