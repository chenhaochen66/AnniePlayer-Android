package com.annieplayer.annie_music.kt.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DSD 输出轴的规则测试。
 *
 * 锁住的是**声学上的硬约束**，不是实现细节：
 * - DSD→PCM 的目标率只允许 44.1 家族（整数抽取），48 家族必须被挡掉；
 * - 超出平台能力的档位往下退，不许硬塞给 AudioTrack；
 * - DoP / Native 在没有独占通路时必须不可用，并且给出人话原因；
 * - 蓝牙下 DSD 档让位给 codec 率（避免两次转换），codec 率未知时不编造。
 */
class DsdOutputTest {

    @Test
    fun `档位按 1bit 采样率识别`() {
        assertEquals(DsdLevel.dsd64, DsdLevel.of(2_822_400))
        assertEquals(DsdLevel.dsd128, DsdLevel.of(5_644_800))
        assertEquals(DsdLevel.dsd256, DsdLevel.of(11_289_600))
        assertEquals(DsdLevel.dsd512, DsdLevel.of(22_579_200))
        // PCM 的率不能被认成 DSD
        assertNull(DsdLevel.of(44_100))
        assertNull(DsdLevel.of(192_000))
    }

    @Test
    fun `目标率候选只有 44_1 家族`() {
        // 48 家族一个都不许出现在候选里：与 DSD 是 147:160，必然非整数比
        assertTrue(DSD_PCM_RATES.all { it % 44_100 == 0 || it == 44_100 })
        assertFalse(DSD_PCM_RATES.any { it == 48_000 || it == 96_000 || it == 192_000 })
    }

    @Test
    fun `配置了 48 家族时退到 44_1 家族`() {
        // 用户可能从常规重采样那边带来一个 48k 的值，这里必须被纠正
        assertEquals(176_400, dsdPcmRateOrFallback(configured = 48_000, maxBuildRate = 192_000))
        assertEquals(176_400, dsdPcmRateOrFallback(configured = 0, maxBuildRate = 192_000))
    }

    @Test
    fun `超出平台能力往下降档`() {
        // 352.8k 需要平台能建出来；只能到 96k 的机器上要落到 88.2k
        assertEquals(88_200, dsdPcmRateOrFallback(configured = 352_800, maxBuildRate = 96_000))
        assertEquals(176_400, dsdPcmRateOrFallback(configured = 352_800, maxBuildRate = 192_000))
        assertEquals(44_100, dsdPcmRateOrFallback(configured = 176_400, maxBuildRate = 48_000))
        // 705.6k 只在真能建出它的设备上生效，其余一致性降档
        assertEquals(705_600, dsdPcmRateOrFallback(configured = 705_600, maxBuildRate = 705_600))
        assertEquals(352_800, dsdPcmRateOrFallback(configured = 705_600, maxBuildRate = 352_800))
        assertEquals(176_400, dsdPcmRateOrFallback(configured = 705_600, maxBuildRate = 192_000))
    }

    @Test
    fun `DSD 目标率的意图与实际不一致时给出原因`() {
        // 一致 / 还没起播 / 跟随系统：都不该提示
        assertNull(dsdRateNotice(352_800, 352_800, isUsbRoute = true, exclusiveIntent = true))
        assertNull(dsdRateNotice(352_800, 0, isUsbRoute = true, exclusiveIntent = true))
        assertNull(
            dsdRateNotice(
                DSD_RATE_FOLLOW_SYSTEM,
                176_400,
                isUsbRoute = true,
                exclusiveIntent = false,
            ),
        )

        // 拔掉 DAC：先报断电，不谈"系统能不能建"
        val unplugged = dsdRateNotice(352_800, 176_400, isUsbRoute = false, exclusiveIntent = true)
        assertEquals(176_400, unplugged?.actualRate)
        assertTrue(unplugged?.reason.orEmpty().contains("已断开"))

        // 插着 DAC 但走系统通路：AudioFormat 那条 192k 上限是真的
        val systemPath = dsdRateNotice(352_800, 176_400, isUsbRoute = true, exclusiveIntent = false)
        assertTrue(systemPath?.reason.orEmpty().contains("系统通路"))

        // 插着 + 独占却仍落不下来：问题在流本身，不是通路选择
        val exclusive = dsdRateNotice(352_800, 176_400, isUsbRoute = true, exclusiveIntent = true)
        assertTrue(exclusive?.reason.orEmpty().contains("独占流"))
        // 文案里必须有实际率 —— 只说原因等于让用户自己算
        assertTrue(exclusive?.text.orEmpty().contains("176.4"))
    }

    @Test
    fun `DSD 输出页的上限要同时看设备和通路`() {
        // 插着 DAC + 独占 → 当前档位的解码网格
        assertEquals(
            352_800,
            dsdMaxOutputRate(
                isUsbRoute = true,
                exclusiveIntent = true,
                level = DsdLevel.dsd64,
                probedMaxRate = 0,
            ),
        )
        assertEquals(
            705_600,
            dsdMaxOutputRate(
                isUsbRoute = true,
                exclusiveIntent = true,
                level = DsdLevel.dsd128,
                probedMaxRate = 0,
            ),
        )
        // **拔掉 DAC**：意图还停在独占，但路由已经不是 USB —— 必须掉回系统实测上限。
        // 这是真实出现过的 bug：只看 `exclusiveIntent`，拔线后这一页仍挂着 352.8k。
        assertEquals(
            176_400,
            dsdMaxOutputRate(
                isUsbRoute = false,
                exclusiveIntent = true,
                level = DsdLevel.dsd64,
                probedMaxRate = 0,
            ),
        )
        // 插着 DAC 但走系统通路：上限就是系统建轨实测（`AudioFormat` 那条 192k 是真的）
        assertEquals(
            176_400,
            dsdMaxOutputRate(
                isUsbRoute = true,
                exclusiveIntent = false,
                level = DsdLevel.dsd64,
                probedMaxRate = 192_000,
            ),
        )
        // 没在播 DSD（level = null）时按 DSD64 的网格算，不放宽到 1411.2k
        assertEquals(
            352_800,
            dsdMaxOutputRate(
                isUsbRoute = true,
                exclusiveIntent = true,
                level = null,
                probedMaxRate = 0,
            ),
        )
    }

    @Test
    fun `705_6k 档位只在实测建得出来时解锁`() {
        // 上限来自真建轨（`SystemSinkProbe.highestUsable`），不是产品常量。
        assertEquals(705_600, dsdMaxPcmRate(probedMax = 705_600))
        // 原来这里写的是 `assertEquals(705_600, dsdMaxPcmRate(1_411_200))` —— 那是把
        // 「候选列表最高才到 705.6k」当成了设备上限。1411.2k 是 DSD256 的解码网格，
        // 已经进 `DSD_PCM_RATES`，实测到它就该放开。
        assertEquals(1_411_200, dsdMaxPcmRate(probedMax = 1_411_200))
        assertEquals(1_411_200, dsdMaxPcmRate(probedMax = 3_000_000))
        assertEquals(352_800, dsdMaxPcmRate(probedMax = 384_000))
        assertEquals(352_800, dsdMaxPcmRate(probedMax = 352_800))
        assertEquals(176_400, dsdMaxPcmRate(probedMax = 192_000))
        // 没探到 / 一档都不行 → 保守兜底，绝不假设 705.6k
        assertEquals(176_400, dsdMaxPcmRate(probedMax = 0))
    }

    @Test
    fun `没探到时退回 176_4 不再当作产品常量`() {
        // 以前这里叫 `MAX_DSD_PCM_RATE` 并断言它恒为 176400 —— 那是把**设备属性**
        // 错当成产品常量。旧注释里的理由是"352.8k 超过 AudioFormat 上限，永远不给"，
        // 但那只对系统通路成立：framework 被厂商放开过（部分国产 DAP）、
        // 或将来走 USB 独占（UAC2 直连，上限来自 DAC 描述符）时，352.8k 是能用的。
        //
        // 现在的语义：`dsdMaxPcmRate(probedMax)` 按实测取上限，探不到才回退。
        assertEquals(176_400, dsdMaxPcmRate())                    // 0 = 还没探到
        assertEquals(176_400, dsdMaxPcmRate(probedMax = 192_000)) // 实测只到 192k
        assertEquals(DSD_FALLBACK_MAX_PCM_RATE, dsdMaxPcmRate(0))

        // 实测到 352.8k（framework 放开 / 独占通路）→ 上限就该是 352.8k
        assertEquals(352_800, dsdMaxPcmRate(probedMax = 352_800))
        assertEquals(352_800, dsdMaxPcmRate(probedMax = 384_000))

        // 实测值一律夹回 44.1 家族：48 家族一个都不给（否则就是主动插入非整数比重采样）
        assertEquals(176_400, dsdMaxPcmRate(probedMax = 192_000))
        assertEquals(88_200, dsdMaxPcmRate(probedMax = 96_000))
    }

    @Test
    fun `没有独占通路时 DoP 与 Native 不可选且给出原因`() {
        val pcm = dsdModeAvailability(DsdOutputMode.pcm)
        assertTrue(pcm.enabled)

        val dop = dsdModeAvailability(DsdOutputMode.dop)
        assertFalse(dop.enabled)
        assertTrue(dop.reason.isNotEmpty())

        val native = dsdModeAvailability(DsdOutputMode.native)
        assertFalse(native.enabled)
        assertTrue(native.reason.isNotEmpty())

        // 独占就绪后 DoP 放开；Native 还要 DAC 支持
        assertTrue(dsdModeAvailability(DsdOutputMode.dop, exclusiveReady = true).enabled)
        assertFalse(dsdModeAvailability(DsdOutputMode.native, exclusiveReady = true).enabled)
        assertTrue(
            dsdModeAvailability(
                DsdOutputMode.native,
                exclusiveReady = true,
                usbDacReady = true,
            ).enabled,
        )
    }

    @Test
    fun `DSD 曲目优先用自己的档位而不是常规重采样目标`() {
        // 调音模式可能带着一个 48 家族的常规重采样目标过来 —— DSD 这边必须纠正成 44.1 家族，
        // 不许把 2.8224M 直接拉到 48k（那是 147:160，非整数比）
        assertEquals(
            176_400,
            dsdFullChainRate(
                level = DsdLevel.dsd64,
                configuredRate = 48_000,
                route = AudioRoute.speaker,
                codecRate = 0,
                maxBuildRate = 192_000,
            ),
        )
        // 用户给 DSD 档位配了具体值就照它走（44.1 kHz 也是合法档位，不能被"纠正"）
        assertEquals(
            44_100,
            dsdFullChainRate(
                level = DsdLevel.dsd64,
                configuredRate = 44_100,
                route = AudioRoute.speaker,
                codecRate = 0,
                maxBuildRate = 192_000,
            ),
        )
        assertEquals(
            176_400,
            dsdFullChainRate(
                level = DsdLevel.dsd64,
                configuredRate = 176_400,
                route = AudioRoute.usb,
                codecRate = 0,
                maxBuildRate = 192_000,
            ),
        )
    }

    @Test
    fun `独占通路的上限是该档位的解码网格 不受系统建轨能力限制`() {
        // `maxBuildRate = 0` 表示"系统通路一档都建不出来"（`AudioFormat` 的 192k 上限）。
        // UAC2 直连不经 AudioTrack，这个结论对它不成立 —— 拿它去卡，就会把 DAC
        // 明明支持的 352.8k 判死，表现是"独占开了也永远只到 176.4k"。
        assertEquals(
            352_800,
            dsdFullChainRate(
                level = DsdLevel.dsd64,
                configuredRate = 352_800,
                route = AudioRoute.usb,
                codecRate = 0,
                maxBuildRate = 0,
                exclusive = true,
            ),
        )
        // **档位越高网格越高**：`dsddec` 一个输入字节出一个 float，所以网格恒等于
        // `bitRate / 8`。DSD128 的 705.6k 是它的网格本身，不是升采样。
        assertEquals(
            705_600,
            dsdFullChainRate(
                level = DsdLevel.dsd128,
                configuredRate = 705_600,
                route = AudioRoute.usb,
                codecRate = 0,
                maxBuildRate = 0,
                exclusive = true,
            ),
        )
        assertEquals(
            1_411_200,
            dsdFullChainRate(
                level = DsdLevel.dsd256,
                configuredRate = 1_411_200,
                route = AudioRoute.usb,
                codecRate = 0,
                maxBuildRate = 0,
                exclusive = true,
            ),
        )
        // 网格之上是升采样，没有内容可送 —— DSD64 选 705.6k 被压回 352.8k
        assertEquals(
            352_800,
            dsdFullChainRate(
                level = DsdLevel.dsd64,
                configuredRate = 705_600,
                route = AudioRoute.usb,
                codecRate = 0,
                maxBuildRate = 0,
                exclusive = true,
            ),
        )
        // 同一组入参在非独占下必须仍被压回保守值 —— 那条 192k 上限是真的。
        // `176_400` 就是 `dsdMaxPcmRate(0)` 的兜底输出（系统探不到时的值）。
        assertEquals(
            176_400,
            dsdFullChainRate(
                level = DsdLevel.dsd64,
                configuredRate = 352_800,
                route = AudioRoute.usb,
                codecRate = 0,
                maxBuildRate = 176_400,
            ),
        )
    }

    @Test
    fun `蓝牙下 DSD 档让位给 codec 率`() {
        assertEquals(
            48_000,
            dsdFullChainRate(
                level = DsdLevel.dsd64,
                configuredRate = 176_400,
                route = AudioRoute.bluetooth,
                codecRate = 48_000,
                maxBuildRate = 192_000,
            ),
        )
        // codec 率读不到 → null（退回常规逻辑），不编一个率
        assertNull(
            dsdFullChainRate(
                level = DsdLevel.dsd64,
                configuredRate = 176_400,
                route = AudioRoute.bluetooth,
                codecRate = 0,
                maxBuildRate = 192_000,
            ),
        )
    }

    @Test
    fun `选固定档位时不看系统率`() {
        // 默认是固定档位（176.4k）：先按档位整数抽取。
        // 这是**保守方向** —— 能跟随源率的设备（多数 DAP）就这一次转换，
        // 而固定主率的设备顶多多一次系统 SRC（比把 DAP 降到 48k 好）。
        assertEquals(
            176_400,
            dsdFullChainRate(
                level = DsdLevel.dsd64,
                configuredRate = 176_400,
                route = AudioRoute.speaker,
                codecRate = 0,
                maxBuildRate = 192_000,
                // 系统率给着也不用
                systemRate = 48_000,
            ),
        )
    }

    @Test
    fun `选跟随系统时一次转到位`() {
        // 普通手机：扬声器/有线主率 48k。选「跟随系统」后由 r8brain 一次转 352.8k → 48k，
        // 避免 AudioFlinger 再做一次 176.4→48 的非整数比转换（它自己的简单插值）。
        assertEquals(
            48_000,
            dsdFullChainRate(
                level = DsdLevel.dsd64,
                configuredRate = DSD_RATE_FOLLOW_SYSTEM,
                route = AudioRoute.speaker,
                codecRate = 0,
                maxBuildRate = 192_000,
                systemRate = 48_000,
            ),
        )
        // 设备主率正好是 44.1k 时更好：8:1 整数抽取
        assertEquals(
            44_100,
            dsdFullChainRate(
                level = DsdLevel.dsd64,
                configuredRate = DSD_RATE_FOLLOW_SYSTEM,
                route = AudioRoute.wired,
                codecRate = 0,
                maxBuildRate = 192_000,
                systemRate = 44_100,
            ),
        )
        // 系统率读不到（0）时退回档位默认值 —— 不猜
        assertEquals(
            176_400,
            dsdFullChainRate(
                level = DsdLevel.dsd64,
                configuredRate = DSD_RATE_FOLLOW_SYSTEM,
                route = AudioRoute.speaker,
                codecRate = 0,
                maxBuildRate = 192_000,
                systemRate = 0,
            ),
        )
    }

    @Test
    fun `每个 DSD 档位可以独立选跟随系统`() {
        // 哨兵值是**按档位**存的，不是一个全局开关：
        // DSD64 保持 176.4k，DSD512 选跟随系统，互不影响。
        assertEquals(
            176_400,
            dsdFullChainRate(
                level = DsdLevel.dsd64,
                configuredRate = 176_400,
                route = AudioRoute.usb,
                codecRate = 0,
                maxBuildRate = 192_000,
                systemRate = 48_000,
            ),
        )
        assertEquals(
            48_000,
            dsdFullChainRate(
                level = DsdLevel.dsd512,
                configuredRate = DSD_RATE_FOLLOW_SYSTEM,
                route = AudioRoute.usb,
                codecRate = 0,
                maxBuildRate = 192_000,
                systemRate = 48_000,
            ),
        )
    }

    @Test
    fun `蓝牙分支优先于跟随系统`() {
        // 蓝牙末端是 codec，不是系统 mixer —— 必须在哨兵值判断之前返回
        assertEquals(
            48_000,
            dsdFullChainRate(
                level = DsdLevel.dsd64,
                configuredRate = DSD_RATE_FOLLOW_SYSTEM,
                route = AudioRoute.bluetooth,
                codecRate = 48_000,
                maxBuildRate = 192_000,
                systemRate = 44_100,
            ),
        )
    }

    @Test
    fun `不是 DSD 就不接管`() {
        assertNull(
            dsdFullChainRate(
                level = null,
                configuredRate = 176_400,
                route = AudioRoute.speaker,
                codecRate = 0,
                maxBuildRate = 192_000,
            ),
        )
    }

    @Test
    fun `入口摘要不把 DoP 与 Native 写成未实现`() {
        assertEquals("PCM · 176.4 kHz", dsdSummaryText(DsdOutputMode.pcm, 176_400))
        assertEquals("PCM · 44.1 kHz", dsdSummaryText(DsdOutputMode.pcm, 44_100))
        // 「跟随系统」不是 Hz 数：`khzText(0)` 返回空串，直接拼会得到「PCM · 」——
        // 实测就是这个断尾巴的文案，所以这里必须单列一条
        assertEquals("PCM · 跟随系统", dsdSummaryText(DsdOutputMode.pcm, DSD_RATE_FOLLOW_SYSTEM))
        // 脏值也不许留半截
        assertEquals("PCM · 未知", dsdSummaryText(DsdOutputMode.pcm, -1))
        // 两条通路都实现了：能不能生效是运行时事实（通路 + 设备），不该写进入口摘要
        assertEquals("DoP", dsdSummaryText(DsdOutputMode.dop, 176_400))
        assertEquals("Native", dsdSummaryText(DsdOutputMode.native, 176_400))
    }

    @Test
    fun `输出低通按素材档位取标称值`() {
        // 噪声整形的归一化频率是 f/(bitRate/2)：率翻倍，同频噪声降 30 dB（5 阶）。
        // 所以标称值跟着档位走，不是「一个值套所有」。
        assertEquals(40_000, dsdPcmLowpassNominal(DsdLevel.dsd64))
        assertEquals(60_000, dsdPcmLowpassNominal(DsdLevel.dsd128))
        assertEquals(80_000, dsdPcmLowpassNominal(DsdLevel.dsd256))
        assertEquals(100_000, dsdPcmLowpassNominal(DsdLevel.dsd512))
        // 档位不明时取最保守那档（理论上走不到：解码网格 = bitRate/8，总能反推）
        assertEquals(40_000, dsdPcmLowpassNominal(null))
    }

    @Test
    fun `输出低通按目标率夹取`() {
        // 176.4k / 352.8k：Nyquist 88.2k / 176.4k，DSD64 与 DSD128 都留得出过渡带
        assertEquals(40_000, dsdPcmLowpassHz(DsdLevel.dsd64, 176_400))
        assertEquals(60_000, dsdPcmLowpassHz(DsdLevel.dsd128, 176_400))
        assertEquals(40_000, dsdPcmLowpassHz(DsdLevel.dsd64, 352_800))
        assertEquals(60_000, dsdPcmLowpassHz(DsdLevel.dsd128, 352_800))
        assertEquals(80_000, dsdPcmLowpassHz(DsdLevel.dsd256, 352_800))
        assertEquals(100_000, dsdPcmLowpassHz(DsdLevel.dsd512, 352_800))
        // 176.4k 目标装不下 80/100 kHz：夹到 0.90×88200 = 79.38 kHz
        assertEquals(79_380, dsdPcmLowpassHz(DsdLevel.dsd256, 176_400))
        assertEquals(79_380, dsdPcmLowpassHz(DsdLevel.dsd512, 176_400))
        // 88.2k：Nyquist 只有 44.1k → 一律夹到 39.69k，与档位无关
        assertEquals(39_690, dsdPcmLowpassHz(DsdLevel.dsd64, 88_200))
        assertEquals(39_690, dsdPcmLowpassHz(DsdLevel.dsd512, 88_200))
        // 44.1k：0.90×22050 = 19.8k，低于 35k 门槛 → 干脆不做。
        // r8brain 的抗混叠已经砍在 21.6k，再加一级只会咬到 20 kHz 以内的内容。
        assertEquals(0, dsdPcmLowpassHz(DsdLevel.dsd64, 44_100))
        assertEquals(0, dsdPcmLowpassHz(DsdLevel.dsd512, 44_100))
        // 脏值
        assertEquals(0, dsdPcmLowpassHz(DsdLevel.dsd64, 0))
        assertEquals(0, dsdPcmLowpassHz(DsdLevel.dsd64, -1))
    }

    @Test
    fun `DSD 档位可以从解码网格反推`() {
        // dsddec 一个输入字节出一个 float ⇒ 解码率恒为 bitRate/8。
        // 低通的标称值靠这条反推拿档位（`MediaPlayerBackend` 里 decodedRate * 8）。
        assertEquals(DsdLevel.dsd64, DsdLevel.of(352_800 * 8))
        assertEquals(DsdLevel.dsd128, DsdLevel.of(705_600 * 8))
        assertEquals(DsdLevel.dsd256, DsdLevel.of(1_411_200 * 8))
        assertEquals(DsdLevel.dsd512, DsdLevel.of(2_822_400 * 8))
        // 384000（48 家族）反推不出任何 DSD 档位 —— DSD 只有 44.1 家族
        assertNull(DsdLevel.of(384_000 * 8))
    }

    @Test
    fun `模式 key 往返`() {
        assertEquals(DsdOutputMode.dop, DsdOutputMode.fromKey("dop"))
        assertEquals(DsdOutputMode.native, DsdOutputMode.fromKey("native"))
        // 脏数据 / 老版本没有这个键 → 落回 PCM
        assertEquals(DsdOutputMode.pcm, DsdOutputMode.fromKey(null))
        assertEquals(DsdOutputMode.pcm, DsdOutputMode.fromKey("garbage"))
    }
}
