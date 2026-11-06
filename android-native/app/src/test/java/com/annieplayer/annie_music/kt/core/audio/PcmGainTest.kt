package com.annieplayer.annie_music.kt.core.audio

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 独占通路的数字音量。
 *
 * 这条路径上没有 mixer，`setVolume` 的接收方只有本类 —— 算错就是"音量滑块无效"或
 * "爆音/静音"，而两者都能过编译，所以钉在这里。
 */
class PcmGainTest {

    private fun floats(vararg v: Float): ByteArray {
        val out = ByteArray(v.size * 4)
        for ((i, f) in v.withIndex()) {
            val bits = f.toRawBits()
            out[i * 4] = bits.toByte()
            out[i * 4 + 1] = (bits shr 8).toByte()
            out[i * 4 + 2] = (bits shr 16).toByte()
            out[i * 4 + 3] = (bits shr 24).toByte()
        }
        return out
    }

    private fun read(bytes: ByteArray, i: Int): Float {
        val bits = (bytes[i * 4].toInt() and 0xFF) or
            ((bytes[i * 4 + 1].toInt() and 0xFF) shl 8) or
            ((bytes[i * 4 + 2].toInt() and 0xFF) shl 16) or
            ((bytes[i * 4 + 3].toInt() and 0xFF) shl 24)
        return Float.fromBits(bits)
    }

    @Test
    fun `float 按增益缩放`() {
        val b = floats(1f, -1f, 0.5f, 0f)
        PcmGain.apply(b, b.size, C.ENCODING_PCM_FLOAT, 0.5f)
        assertEquals(0.5f, read(b, 0), 0f)
        assertEquals(-0.5f, read(b, 1), 0f)
        assertEquals(0.25f, read(b, 2), 0f)
        assertEquals(0f, read(b, 3), 0f)
    }

    @Test
    fun `增益为 1 时一个字节都不改`() {
        val src = floats(0.123f, -0.987f)
        val copy = src.copyOf()
        PcmGain.apply(src, src.size, C.ENCODING_PCM_FLOAT, 1f)
        assert(src.contentEquals(copy)) { "增益 1 不应触碰数据" }
    }

    @Test
    fun `16 bit 按增益缩放并带 1 LSB 抖动`() {
        // 原先断言的是精确 8192 —— 加入 TPDF 抖动后不再成立，改为容差 1 LSB。
        // 抖动是刻意的：衰减就是一次重量化，不抖动的舍入误差与信号相关。
        val b = byteArrayOf(0x00, 0x40, 0x00, 0xC0.toByte())
        PcmGain.apply(b, b.size, C.ENCODING_PCM_16BIT, 0.5f)
        val v0 = ((b[0].toInt() and 0xFF) or (b[1].toInt() shl 8)).toShort().toInt()
        val v1 = ((b[2].toInt() and 0xFF) or (b[3].toInt() shl 8)).toShort().toInt()
        assert(Math.abs(v0 - 8192) <= 1) { "v0=$v0" }
        assert(Math.abs(v1 + 8192) <= 1) { "v1=$v1" }
    }

    @Test
    fun `24 bit 抖动幅度不超过一个 LSB`() {
        val src = ByteArray(3 * 64)
        for (i in 0 until 64) {
            val v = (i - 32) * 12345
            src[i * 3] = v.toByte()
            src[i * 3 + 1] = (v shr 8).toByte()
            src[i * 3 + 2] = (v shr 16).toByte()
        }
        val out = src.copyOf()
        PcmGain.apply(out, out.size, C.ENCODING_PCM_24BIT, 0.5f)
        for (i in 0 until 64) {
            val v = (out[i * 3].toInt() and 0xFF) or
                ((out[i * 3 + 1].toInt() and 0xFF) shl 8) or
                (out[i * 3 + 2].toInt() shl 16)
            val expected = (((i - 32) * 12345) * 0.5).toInt()
            assert(Math.abs(v - expected) <= 1) { "样本 $i: $v vs $expected" }
        }
    }

    @Test
    fun `抖动每次都不同`() {
        fun sample(): ByteArray {
            val b = ByteArray(3 * 32)
            for (i in 0 until 32) {
                val v = (i - 16) * 5000
                b[i * 3] = v.toByte()
                b[i * 3 + 1] = (v shr 8).toByte()
                b[i * 3 + 2] = (v shr 16).toByte()
            }
            return b
        }
        val a = sample()
        val b = sample()
        PcmGain.apply(a, a.size, C.ENCODING_PCM_24BIT, 0.5f)
        PcmGain.apply(b, b.size, C.ENCODING_PCM_24BIT, 0.5f)
        assert(!a.contentEquals(b)) { "两次衰减应产生不同的抖动序列" }
    }

    @Test
    fun `24 bit 反号位不外溢`() {
        // 0x7FFFFF（满幅正）乘 2 要夹住，不能翻成负
        val b = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x7F)
        PcmGain.apply(b, b.size, C.ENCODING_PCM_24BIT, 2f)
        assertEquals(0xFF.toByte(), b[0])
        assertEquals(0xFF.toByte(), b[1])
        assertEquals(0x7F.toByte(), b[2])

        // 0x800000（满幅负）乘 2 同理
        val n = byteArrayOf(0x00, 0x00, 0x80.toByte())
        PcmGain.apply(n, n.size, C.ENCODING_PCM_24BIT, 2f)
        assertEquals(0x00, n[0].toInt() and 0xFF)
        assertEquals(0x00, n[1].toInt() and 0xFF)
        assertEquals(0x80.toByte(), n[2])
    }

    @Test
    fun `不足一个样本的尾巴不动`() {
        // 3 字节 float 不是完整样本 → 一个字节都不该改（否则会把下一块的头弄坏）
        val b = byteArrayOf(0x01, 0x02, 0x03)
        PcmGain.apply(b, b.size, C.ENCODING_PCM_FLOAT, 0.5f)
        assertEquals(0x01.toByte(), b[0])
        assertEquals(0x02.toByte(), b[1])
        assertEquals(0x03.toByte(), b[2])
    }

    @Test
    fun `零长度不越界`() {
        val b = ByteArray(0)
        PcmGain.apply(b, 0, C.ENCODING_PCM_FLOAT, 0.5f)
    }
}
