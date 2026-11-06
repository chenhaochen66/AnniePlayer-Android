package com.annieplayer.annie_music.kt.core.audio.dsd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * DoP 帧的字节布局是这个文件里唯一不能靠"跑起来看"验的东西：
 * 标记写错和字节序写错的听感完全一样（都是噪声），
 * 所以这里对每一字节做断言。
 */
class DopPackerTest {

    @Test
    fun `一帧装两个 DSD 字节 标记在高位`() {
        // 输入是**字节交错**：L0=0xAA, R0=0xCC, L1=0xBB, R1=0xDD
        // （native 的 rawDsd 分支把两个声道的 block 按字节并排吐出来，见 native-ffmpeg-demux.cpp）
        val dsd = byteArrayOf(
            0xAA.toByte(), 0xCC.toByte(),
            0xBB.toByte(), 0xDD.toByte(),
        )
        val out = ByteArray(DopPacker.BYTES_PER_FRAME)
        val n = DopPacker.pack(dsd, 0, frames = 1, out = out, outOffset = 0, markerHigh = false)

        assertEquals(DopPacker.BYTES_PER_FRAME, n)
        assertArrayEquals(
            // 左：[字节B=L1=0xBB, 字节A=L0=0xAA, 标记=0x05]
            // 右：[字节B=R1=0xDD, 字节A=R0=0xCC, 标记=0x05]
            byteArrayOf(
                0xBB.toByte(), 0xAA.toByte(), 0x05,
                0xDD.toByte(), 0xCC.toByte(), 0x05,
            ),
            out,
        )
    }

    @Test
    fun `左右声道不会互相串位`() {
        // 左全 0x11、右全 0x22：字节交错下 [L,R,L,R]，
        // 这一条专门盯「把 R 的字节写进 L 的帧」这类错位。
        val dsd = byteArrayOf(0x11, 0x22, 0x11, 0x22)
        val out = ByteArray(DopPacker.BYTES_PER_FRAME)
        DopPacker.pack(dsd, 0, frames = 1, out = out, outOffset = 0, markerHigh = false)
        assertEquals(0x11.toByte(), out[0])
        assertEquals(0x11.toByte(), out[1])
        assertEquals(0x22.toByte(), out[3])
        assertEquals(0x22.toByte(), out[4])
    }

    @Test
    fun `标记逐帧交替`() {
        val dsd = ByteArray(8) { 0x11 }
        val out = ByteArray(2 * DopPacker.BYTES_PER_FRAME)
        DopPacker.pack(dsd, 0, frames = 2, out = out, outOffset = 0, markerHigh = false)

        assertEquals(0x05.toByte(), out[2])
        assertEquals(0xFA.toByte(), out[2 + DopPacker.BYTES_PER_FRAME])
        // 两个声道的标记同步
        assertEquals(out[2], out[5])
        assertEquals(out[8], out[11])
    }

    @Test
    fun `markerHigh 起手从 0xFA 开始`() {
        val dsd = ByteArray(4)
        val out = ByteArray(DopPacker.BYTES_PER_FRAME)
        DopPacker.pack(dsd, 0, frames = 1, out = out, outOffset = 0, markerHigh = true)
        assertEquals(0xFA.toByte(), out[2])
        assertEquals(0xFA.toByte(), out[5])
    }

    @Test
    fun `跨块连续时标记序列不能断`() {
        // 第一块 1 帧（用 0x05），第二块接着传 markerHigh=true → 第一个帧必须是 0xFA
        val dsd = ByteArray(8)
        val a = ByteArray(DopPacker.BYTES_PER_FRAME)
        val b = ByteArray(DopPacker.BYTES_PER_FRAME)
        DopPacker.pack(dsd, 0, frames = 1, out = a, outOffset = 0, markerHigh = false)
        DopPacker.pack(dsd, 4, frames = 1, out = b, outOffset = 0, markerHigh = true)
        assertEquals(0x05.toByte(), a[2])
        assertEquals(0xFA.toByte(), b[2])
    }

    @Test
    fun `缓冲区不够时不写并返回负值`() {
        val dsd = ByteArray(4)
        val tooSmall = ByteArray(5)
        assertEquals(-1, DopPacker.pack(dsd, 0, 1, tooSmall, 0, false))

        val ok = ByteArray(DopPacker.BYTES_PER_FRAME)
        assertEquals(-1, DopPacker.pack(ByteArray(2), 0, 1, ok, 0, false))
    }

    @Test
    fun `帧数与帧率换算`() {
        // 4 字节交错 = 一帧（每声道 2 字节）
        assertEquals(1, DopPacker.framesOf(4))
        assertEquals(2, DopPacker.framesOf(8))
        // 尾巴丢掉，不补零
        assertEquals(1, DopPacker.framesOf(7))
        assertEquals(0, DopPacker.framesOf(3))

        assertEquals(176_400, DopPacker.packetRate(2_822_400))   // DSD64
        assertEquals(352_800, DopPacker.packetRate(5_644_800))   // DSD128
        assertEquals(705_600, DopPacker.packetRate(11_289_600))  // DSD256
    }
}
