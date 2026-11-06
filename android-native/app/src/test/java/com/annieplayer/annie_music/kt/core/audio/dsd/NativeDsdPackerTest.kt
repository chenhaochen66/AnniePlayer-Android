package com.annieplayer.annie_music.kt.core.audio.dsd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Native 打包的重排逻辑：把「每样本 1 字节、声道交错」变成「每声道一个 32-bit word、word 交错」。
 * 大端走时间顺序，小端要走反序 —— 两个方向都不能只靠听感验，所以逐字节断言。
 */
class NativeDsdPackerTest {

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `大端 WORD 按时间顺序排列`() {
        // 交错输入：L0..L3 = 11,22,33,44   R0..R3 = 55,66,77,88
        val dsd = bytes(0x11, 0x55, 0x22, 0x66, 0x33, 0x77, 0x44, 0x88)
        val out = ByteArray(NativeDsdPacker.BYTES_PER_PAIR)
        val n = NativeDsdPacker.pack(dsd, 0, words = 1, out = out, outOffset = 0, bigEndian = true)

        assertEquals(NativeDsdPacker.BYTES_PER_PAIR, n)
        // 左 word = [11,22,33,44]；右 word = [55,66,77,88]
        assertArrayEquals(bytes(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88), out)
    }

    @Test
    fun `小端 WORD 反序`() {
        val dsd = bytes(0x11, 0x55, 0x22, 0x66, 0x33, 0x77, 0x44, 0x88)
        val out = ByteArray(NativeDsdPacker.BYTES_PER_PAIR)
        NativeDsdPacker.pack(dsd, 0, words = 1, out = out, outOffset = 0, bigEndian = false)

        assertArrayEquals(bytes(0x44, 0x33, 0x22, 0x11, 0x88, 0x77, 0x66, 0x55), out)
    }

    @Test
    fun `两个 WORD 时左声道不串到右声道`() {
        val dsd = ByteArray(16)
        dsd[0] = 0x01; dsd[2] = 0x02; dsd[4] = 0x03; dsd[6] = 0x04      // L word0
        dsd[1] = 0xA1.toByte(); dsd[3] = 0xA2.toByte()
        dsd[5] = 0xA3.toByte(); dsd[7] = 0xA4.toByte()                  // R word0
        dsd[8] = 0x05; dsd[10] = 0x06; dsd[12] = 0x07; dsd[14] = 0x08    // L word1
        dsd[9] = 0xB1.toByte(); dsd[11] = 0xB2.toByte()
        dsd[13] = 0xB3.toByte(); dsd[15] = 0xB4.toByte()                // R word1

        val out = ByteArray(2 * NativeDsdPacker.BYTES_PER_PAIR)
        NativeDsdPacker.pack(dsd, 0, words = 2, out = out, outOffset = 0, bigEndian = true)

        assertArrayEquals(bytes(0x01, 0x02, 0x03, 0x04), out.copyOfRange(0, 4))
        assertArrayEquals(bytes(0xA1, 0xA2, 0xA3, 0xA4), out.copyOfRange(4, 8))
        assertArrayEquals(bytes(0x05, 0x06, 0x07, 0x08), out.copyOfRange(8, 12))
        assertArrayEquals(bytes(0xB1, 0xB2, 0xB3, 0xB4), out.copyOfRange(12, 16))
    }

    @Test
    fun `缓冲不够返回负值`() {
        val dsd = ByteArray(8)
        assertEquals(-1, NativeDsdPacker.pack(dsd, 0, 1, ByteArray(7), 0, true))
        assertEquals(-1, NativeDsdPacker.pack(ByteArray(4), 0, 1, ByteArray(8), 0, true))
    }

    @Test
    fun `WORD 数与帧率换算`() {
        assertEquals(1, NativeDsdPacker.wordsOf(8))
        assertEquals(0, NativeDsdPacker.wordsOf(7))
        assertEquals(2, NativeDsdPacker.wordsOf(16))

        assertEquals(88_200, NativeDsdPacker.packetRate(2_822_400))    // DSD64
        assertEquals(176_400, NativeDsdPacker.packetRate(5_644_800))   // DSD128
        assertEquals(352_800, NativeDsdPacker.packetRate(11_289_600))  // DSD256
    }
}
