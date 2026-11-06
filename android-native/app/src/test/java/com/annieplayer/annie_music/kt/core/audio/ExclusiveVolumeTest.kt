package com.annieplayer.annie_music.kt.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExclusiveVolumeTest {

    private val saved = mutableMapOf<String, Float>()

    @Before
    fun setUp() {
        ExclusiveVolume.resetForTest()
        saved.clear()
        ExclusiveVolume.init(object : ExclusiveVolume.Store {
            override fun get(key: String): Float? = saved[key]
            override fun put(key: String, db: Float) {
                saved[key] = db
            }
        })
    }

    @Test
    fun `非独占通路不消费按键`() {
        ExclusiveVolume.setRoute("systemMix", available = false, raw = false)
        assertFalse(ExclusiveVolume.step(up = true))
        assertEquals(ExclusiveVolume.MAX_DB, ExclusiveVolume.state.value.db, 0f)
    }

    @Test
    fun `独占通路消费按键并逐 dB 步进`() {
        ExclusiveVolume.setRoute("usbDirect", available = true, raw = false)
        assertTrue(ExclusiveVolume.step(up = false))
        assertEquals(-1f, ExclusiveVolume.state.value.db, 0f)
        assertEquals(1L, ExclusiveVolume.state.value.tick)
        assertTrue(ExclusiveVolume.step(up = true))
        assertEquals(0f, ExclusiveVolume.state.value.db, 0f)
    }

    @Test
    fun `步进夹在两端口之间`() {
        ExclusiveVolume.setRoute("usbDirect", available = true, raw = false)
        repeat(10) { ExclusiveVolume.step(up = true) }
        assertEquals(ExclusiveVolume.MAX_DB, ExclusiveVolume.state.value.db, 0f)
        repeat(200) { ExclusiveVolume.step(up = false) }
        assertEquals(ExclusiveVolume.MIN_DB, ExclusiveVolume.state.value.db, 0f)
    }

    @Test
    fun `DSD 直出仍消费按键但不改增益`() {
        ExclusiveVolume.setRoute("usbDirect", available = true, raw = true)
        assertTrue(ExclusiveVolume.step(up = false))
        assertEquals(ExclusiveVolume.MAX_DB, ExclusiveVolume.state.value.db, 0f)
        assertEquals(1f, ExclusiveVolume.linear, 0f)
        assertTrue(ExclusiveVolume.state.value.locked)
    }

    @Test
    fun `线性增益由 dB 换算且非独占时恒为一`() {
        ExclusiveVolume.setRoute("usbDirect", available = true, raw = false)
        assertEquals(1f, ExclusiveVolume.linear, 1e-6f)
        // setLinear 不量化：0.5 存成 −6.0206 dB，换回来仍是 0.5
        ExclusiveVolume.setLinear(0.5f)
        assertEquals(0.5f, ExclusiveVolume.linear, 1e-3f)
        ExclusiveVolume.setRoute("systemMix", available = false, raw = false)
        assertEquals(1f, ExclusiveVolume.linear, 0f)
    }

    @Test
    fun `换通路回读存档 同通路不回读`() {
        ExclusiveVolume.setRoute("usbDirect", available = true, raw = false)
        repeat(6) { ExclusiveVolume.step(up = false) }
        assertEquals(-6f, ExclusiveVolume.state.value.db, 0f)
        // 同一条通路重新 configure 不能把用户刚调的值打回旧值
        ExclusiveVolume.setRoute("usbDirect", available = true, raw = false)
        assertEquals(-6f, ExclusiveVolume.state.value.db, 0f)

        ExclusiveVolume.setRoute("aaudioExclusive", available = true, raw = false)
        assertEquals(ExclusiveVolume.MAX_DB, ExclusiveVolume.state.value.db, 0f)
        ExclusiveVolume.setRoute("usbDirect", available = true, raw = false)
        assertEquals(-6f, ExclusiveVolume.state.value.db, 0f)
    }

    @Test
    fun `线性设定写回存档`() {
        ExclusiveVolume.setRoute("usbDirect", available = true, raw = false)
        ExclusiveVolume.setLinear(0.25f)
        assertEquals(0.25f, linearOf(saved.getValue("exclusive-vol:usbDirect")), 1e-3f)
    }

    @Test
    fun `静音档换算`() {
        assertEquals(0.001f, linearOf(ExclusiveVolume.MIN_DB), 1e-5f)
        assertEquals(ExclusiveVolume.MIN_DB, dbOf(0f), 0f)
        assertEquals(-6.0206f, dbOf(0.5f), 1e-3f)
    }

    @Test
    fun `硬件音量优先 且不施加软件衰减`() {
        ExclusiveVolume.setRoute("usbDirect", available = true, raw = false)
        var written = Int.MIN_VALUE
        ExclusiveVolume.attachHardware(
            HardwareVolumeCaps(min = -32768, max = 0, step = 256, value = 0),
        ) { written = it }

        assertTrue(ExclusiveVolume.step(up = false))
        assertEquals(-256, written)
        assertEquals(-1f, ExclusiveVolume.state.value.effectiveDb, 1e-3f)
        // 硬件衰减发生在 DAC 内部：数据一个字节都不动
        assertEquals(1f, ExclusiveVolume.linear, 0f)
    }

    @Test
    fun `硬件音量到顶不再下发`() {
        ExclusiveVolume.setRoute("usbDirect", available = true, raw = false)
        var calls = 0
        ExclusiveVolume.attachHardware(
            HardwareVolumeCaps(min = -512, max = 0, step = 256, value = 0),
        ) { calls++ }

        assertTrue(ExclusiveVolume.step(up = true))
        assertEquals(0, calls)
        assertTrue(ExclusiveVolume.step(up = false))
        assertEquals(1, calls)
        assertTrue(ExclusiveVolume.step(up = false))
        assertEquals(2, calls)
        // 到达 min 之后不再下发
        assertTrue(ExclusiveVolume.step(up = false))
        assertEquals(2, calls)
    }

    @Test
    fun `硬件音量在 DSD 直出下依然可用`() {
        ExclusiveVolume.setRoute("usbDirect", available = true, raw = true)
        var written = Int.MIN_VALUE
        ExclusiveVolume.attachHardware(
            HardwareVolumeCaps(min = -2560, max = 0, step = 256, value = 0),
        ) { written = it }

        assertFalse(ExclusiveVolume.state.value.locked)
        assertTrue(ExclusiveVolume.step(up = false))
        assertEquals(-256, written)
    }

    @Test
    fun `线性滑块摊到硬件档位区间`() {
        ExclusiveVolume.setRoute("usbDirect", available = true, raw = false)
        var written = Int.MIN_VALUE
        ExclusiveVolume.attachHardware(
            HardwareVolumeCaps(min = -1000, max = 0, step = 10, value = 0),
        ) { written = it }

        ExclusiveVolume.setLinear(0.5f)
        assertEquals(-500, written)
        assertEquals(1f, ExclusiveVolume.linear, 0f)
    }

    @Test
    fun `等效位深随衰减下降`() {        assertEquals(24, effectiveBits(24, 0f))
        assertEquals(23, effectiveBits(24, -6f))
        assertEquals(20, effectiveBits(24, -24f))
        assertEquals(16, effectiveBits(16, 0f))
        // 未知源位深不猜
        assertEquals(0, effectiveBits(0, -12f))
        // 16 bit 在 −60 dB 只丢 10 bit（60/6.02），剩 6 位 —— 不会退化成 0
        assertEquals(6, effectiveBits(16, -60f))
    }
}
