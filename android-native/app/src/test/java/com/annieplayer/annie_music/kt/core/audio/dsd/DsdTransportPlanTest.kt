package com.annieplayer.annie_music.kt.core.audio.dsd

import com.annieplayer.annie_music.kt.core.audio.DsdLevel
import com.annieplayer.annie_music.kt.core.audio.OutputPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DsdTransportPlanTest {

    private fun decide(
        wanted: DsdTransport,
        level: DsdLevel? = DsdLevel.dsd64,
        path: OutputPath = OutputPath.usbDirect,
        routingKnown: Boolean = true,
        caps: UsbDacCaps? = UsbDacCaps(pcmRates = null, hasRawAlt = true),
    ) = decideDsdTransport(wanted, level, path, routingKnown, caps)

    @Test
    fun `帧率换算按各自的分频`() {
        assertEquals(176_400, DsdLevel.dsd64.dopFrameRate)
        assertEquals(352_800, DsdLevel.dsd128.dopFrameRate)
        assertEquals(705_600, DsdLevel.dsd256.dopFrameRate)
        assertEquals(1_411_200, DsdLevel.dsd512.dopFrameRate)

        assertEquals(88_200, DsdLevel.dsd64.nativeWordRate)
        assertEquals(176_400, DsdLevel.dsd128.nativeWordRate)
        assertEquals(352_800, DsdLevel.dsd256.nativeWordRate)
        assertEquals(705_600, DsdLevel.dsd512.nativeWordRate)
    }

    @Test
    fun `不是 DSD 时一律走 PCM`() {
        val d = decide(DsdTransport.native, level = null)
        assertEquals(DsdTransport.pcm, d.transport)
        assertNull(d.reason)
    }

    @Test
    fun `意图是 PCM 时不去碰 1-bit`() {
        val d = decide(DsdTransport.pcm)
        assertEquals(DsdTransport.pcm, d.transport)
        assertFalse(d.isFallback)
    }

    @Test
    fun `通路还没测到时不冒险`() {
        val d = decide(DsdTransport.dop, routingKnown = false)
        assertEquals(DsdTransport.pcm, d.transport)
        assertEquals(DsdDowngradeReason.NotMeasured, d.reason)
    }

    @Test
    fun `系统通路会改写 DoP 帧标记`() {
        val d = decide(DsdTransport.dop, path = OutputPath.systemMix)
        assertEquals(DsdTransport.pcm, d.transport)
        assertEquals(DsdDowngradeReason.PathNotBitExact, d.reason)
    }

    @Test
    fun `AAudio 独占当前不当作保真通路`() {
        val d = decide(DsdTransport.native, path = OutputPath.aaudioExclusive)
        assertEquals(DsdTransport.pcm, d.transport)
        assertEquals(DsdDowngradeReason.PathNotBitExact, d.reason)
    }

    @Test
    fun `设备能力未知时 DoP 仍可试`() {
        // DoP 的帧率由开流时的 SET_CUR 裁决，未知不等于不支持
        val d = decide(DsdTransport.dop, caps = null)
        assertEquals(DsdTransport.dop, d.transport)
        assertNull(d.reason)
    }

    @Test
    fun `设备能力未知时 Native 降 DoP 并说明`() {
        // raw alt 存不存在是硬事实，未知不能猜：猜错会把 PCM 灌进 raw 通道出满幅噪声
        val d = decide(DsdTransport.native, caps = null)
        assertEquals(DsdTransport.dop, d.transport)
        assertEquals(DsdDowngradeReason.DeviceUnknown, d.reason)
    }

    @Test
    fun `DoP 帧率在声明里时成立`() {
        val d = decide(
            DsdTransport.dop,
            caps = UsbDacCaps(pcmRates = setOf(44_100, 176_400)),
        )
        assertEquals(DsdTransport.dop, d.transport)
        assertNull(d.reason)
    }

    @Test
    fun `DoP 帧率不在声明里时退 PCM`() {
        val d = decide(
            DsdTransport.dop,
            level = DsdLevel.dsd256,
            caps = UsbDacCaps(pcmRates = setOf(176_400, 352_800)),
        )
        assertEquals(DsdTransport.pcm, d.transport)
        assertEquals(DsdDowngradeReason.FrameRateUnsupported, d.reason)
    }

    @Test
    fun `设备没声明 PCM 率时按可以试处理`() {
        // 描述符残缺很常见，拿"没声明"代理"做不到"会误伤这类设备
        val d = decide(DsdTransport.dop, caps = UsbDacCaps(pcmRates = null))
        assertEquals(DsdTransport.dop, d.transport)
    }

    @Test
    fun `Native 有 raw 通道且支持该 word 率时成立`() {
        val d = decide(
            DsdTransport.native,
            level = DsdLevel.dsd256,
            caps = UsbDacCaps(hasRawAlt = true, rawRates = setOf(176_400, 352_800)),
        )
        assertEquals(DsdTransport.native, d.transport)
        assertNull(d.reason)
    }

    @Test
    fun `Native 没有 raw 通道时降到 DoP`() {
        val d = decide(
            DsdTransport.native,
            caps = UsbDacCaps(pcmRates = setOf(176_400), hasRawAlt = false),
        )
        assertEquals(DsdTransport.dop, d.transport)
        assertEquals(DsdDowngradeReason.NoRawAlt, d.reason)
    }

    @Test
    fun `Native 没有 raw 通道且 DoP 也不可行时退 PCM`() {
        val d = decide(
            DsdTransport.native,
            level = DsdLevel.dsd512,
            caps = UsbDacCaps(pcmRates = setOf(176_400, 352_800), hasRawAlt = false),
        )
        assertEquals(DsdTransport.pcm, d.transport)
        assertEquals(DsdDowngradeReason.FrameRateUnsupported, d.reason)
    }

    @Test
    fun `Native 的 word 率超出 raw 声明时降到 DoP`() {
        // DSD512 的 word 率是 705.6k，超过很多小尾巴 raw alt 的上限；
        // 但它的 DoP 帧率 1411.2k 在这台设备上可行，所以应该落到 DoP 而不是 PCM
        val d = decide(
            DsdTransport.native,
            level = DsdLevel.dsd512,
            caps = UsbDacCaps(
                pcmRates = setOf(176_400, 1_411_200),
                hasRawAlt = true,
                rawRates = setOf(176_400, 352_800),
            ),
        )
        assertEquals(DsdTransport.dop, d.transport)
        assertEquals(DsdDowngradeReason.NoRawAlt, d.reason)
    }

    @Test
    fun `raw alt 没声明率时按可以试处理`() {
        val d = decide(
            DsdTransport.native,
            caps = UsbDacCaps(hasRawAlt = true, rawRates = emptySet()),
        )
        assertEquals(DsdTransport.native, d.transport)
    }

    @Test
    fun `只往下降不往上升`() {
        // 用户选 DoP，即便设备有 raw 通道也不该升到 Native
        val d = decide(
            DsdTransport.dop,
            caps = UsbDacCaps(pcmRates = setOf(176_400), hasRawAlt = true),
        )
        assertEquals(DsdTransport.dop, d.transport)
        assertTrue(d.transport != DsdTransport.native)
    }

    // ── 降级链：Native 必须经过 DoP 才落到 PCM ──

    private fun chain(
        wanted: DsdTransport,
        level: DsdLevel? = DsdLevel.dsd64,
        path: OutputPath = OutputPath.usbDirect,
        routingKnown: Boolean = true,
        caps: UsbDacCaps? = UsbDacCaps(pcmRates = null, hasRawAlt = true),
        failed: Set<DsdTransport> = emptySet(),
    ) = resolveDsdChain(wanted, level, path, routingKnown, caps) { it in failed }

    @Test
    fun `Native 没有 raw 通道时落到 DoP 而不是 PCM`() {
        val d = chain(
            DsdTransport.native,
            caps = UsbDacCaps(pcmRates = null, hasRawAlt = false),
        )
        assertEquals(DsdTransport.dop, d.transport)
        assertEquals(DsdDowngradeReason.NoRawAlt, d.reason)
    }

    @Test
    fun `Native 这个档位试失败过也落到 DoP`() {
        val d = chain(DsdTransport.native, failed = setOf(DsdTransport.native))
        assertEquals(DsdTransport.dop, d.transport)
        assertEquals(DsdDowngradeReason.TransportFailed, d.reason)
    }

    @Test
    fun `Native 与 DoP 都失败才是 PCM`() {
        val d = chain(
            DsdTransport.native,
            failed = setOf(DsdTransport.native, DsdTransport.dop),
        )
        assertEquals(DsdTransport.pcm, d.transport)
        assertEquals(DsdDowngradeReason.TransportFailed, d.reason)
    }

    @Test
    fun `DoP 帧率设备不收时才落 PCM`() {
        val d = chain(
            DsdTransport.native,
            level = DsdLevel.dsd512,
            caps = UsbDacCaps(pcmRates = setOf(176_400, 352_800), hasRawAlt = false),
        )
        assertEquals(DsdTransport.pcm, d.transport)
        assertEquals(DsdDowngradeReason.FrameRateUnsupported, d.reason)
    }

    @Test
    fun `意图成立时没有降级原因`() {
        val d = chain(DsdTransport.native)
        assertEquals(DsdTransport.native, d.transport)
        assertNull(d.reason)
    }

    @Test
    fun `通路不保真时两条都是 PCM`() {
        // DoP 也要逐字节保真，通路不成立就没得选 —— 但在结论上仍报通路原因
        val d = chain(DsdTransport.native, path = OutputPath.systemMix)
        assertEquals(DsdTransport.pcm, d.transport)
        assertEquals(DsdDowngradeReason.PathNotBitExact, d.reason)
    }
}
