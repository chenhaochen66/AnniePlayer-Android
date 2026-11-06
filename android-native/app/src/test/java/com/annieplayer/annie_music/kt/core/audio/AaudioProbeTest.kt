package com.annieplayer.annie_music.kt.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AAudio 独占探测回包的解析与文案（纯 JVM）。
 *
 * 为什么值得测：这几行是**唯一**能说明"Bit Perfect 那一级到底能不能绕开 mixer"的依据。
 * 解析错了就等于把"只拿到共享"当成"独占可用"—— 那是这一整套设计里最不能犯的错。
 */
class AaudioProbeTest {

    @Test
    fun `拿到独占要认出三个字段`() {
        val r = parseAaudioProbe("EXCLUSIVE/48000/32/LOW_LATENCY")
        assertEquals(true, r?.exclusive)
        assertEquals(48_000, r?.sampleRate)
        assertEquals(32, r?.depth)
        assertEquals(true, r?.lowLatency)
        assertNull(r?.errorTag)
    }

    @Test
    fun `回来共享就不算独占`() {
        // HAL 可以无视 setSharingMode(EXCLUSIVE) —— 这一行必须判成"没拿到"
        val r = parseAaudioProbe("SHARED/44100/16/NONE")
        assertEquals(false, r?.exclusive)
        assertEquals(44_100, r?.sampleRate)
        assertEquals(16, r?.depth)
        assertEquals(false, r?.lowLatency)
    }

    @Test
    fun `错误标记原样保留 不归一成 -1`() {
        // 归一成 -1 会让"缺符号"和"HAL 拒绝"看起来一模一样 —— 真机实测直接踩到过，
        // 面板五行全是 err:-1，完全无法定位是 dlopen 失败还是开流失败。
        assertEquals("-899", parseAaudioProbe("err:-899")?.errorTag)
        assertEquals("builder", parseAaudioProbe("err:builder")?.errorTag)
        assertEquals("nolib", parseAaudioProbe("err:nolib")?.errorTag)
        assertEquals("symbol", parseAaudioProbe("err:symbol")?.errorTag)
    }

    @Test
    fun `认不出的回包一律 null 而不是猜成功`() {
        assertNull(parseAaudioProbe(null))
        assertNull(parseAaudioProbe(""))
        assertNull(parseAaudioProbe("EXCLUSIVE/48000"))       // 缺字段
        assertNull(parseAaudioProbe("EXCLUSIVE/xx/32/NONE"))  // 率不是数字
    }

    @Test
    fun `文案分三态`() {
        assertEquals(
            "独占可用 · 32 Bit float / 48 kHz · 低延迟",
            aaudioProbeLabel("EXCLUSIVE/48000/32/LOW_LATENCY"),
        )
        assertEquals("独占可用 · 24 Bit / 96 kHz", aaudioProbeLabel("EXCLUSIVE/96000/24/NONE"))
        // 没拿到独占：必须说清"仍会过 mixer"，不能含糊成"不支持"
        assertEquals("仅共享（HAL 未给独占）", aaudioProbeLabel("SHARED/48000/32/NONE"))
        assertEquals("不可用（err:-899）", aaudioProbeLabel("err:-899"))
        assertEquals("未知", aaudioProbeLabel("garbage"))
    }

    @Test
    fun `独占可用时不会误报成共享`() {
        val ok = parseAaudioProbe("EXCLUSIVE/44100/16/NONE")
        assertTrue(ok?.exclusive == true)
        assertFalse(ok?.lowLatency == true)
    }
}
