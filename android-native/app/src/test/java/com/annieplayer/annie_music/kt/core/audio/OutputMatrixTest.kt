package com.annieplayer.annie_music.kt.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输出矩阵：**开关 → 派生结果**。
 *
 * 这一层存在的意义就是"能不能调音"只有一个判据（`tuningEnabled`），
 * 所以这里锁的是**开关与调音的正交关系**，不是某个调用点的行为。
 *
 * ⚠️ 「直通解码（offload）」这个开关**已整条删除**。它当年是系统通路上唯一能关掉
 * 调音的东西，所以那几条用例（"直通会关掉调音"）随之删除 —— 现在系统通路上
 * **恒可调音**，要"不做 DSP"请用「调音引擎 = 无」。
 */
class OutputMatrixTest {

    /**
     * **DSD 直出时不调音，但"能不能选"不受影响。**
     *
     * 两件事必须分开：
     * - 设置页问的是「这组配置意味着什么」——**不传 `rawDsd`**（默认 false），
     *   所以播 DSD 时均衡器 / PEQ / MSEB / 开关照样能看能改能存；
     * - 音频路径问的是「这一首要不要真的跑 DSP」——传 `rawDsd`，直出时判 false。
     *
     * 谁要是把 `rawDsd` 喂进设置页那个调用点，用户就会在放 DSD 时发现调音面板消失。
     */
    @Test
    fun `DSD 直出不调音 但不影响能不能选`() {
        val toggles = OutputToggles(bitPerfect = false)
        // 设置页 / 可选性：与此刻在放什么无关
        assertTrue(tuningEnabled(OutputPath.usbDirect, toggles))
        // 音频路径：直出时没有可处理的对象
        assertFalse(tuningEnabled(OutputPath.usbDirect, toggles, rawDsd = true))
        // 非 DSD 曲目完全不受影响
        assertTrue(tuningEnabled(OutputPath.usbDirect, toggles, rawDsd = false))
        // 引擎「无」仍然是最强的一票否决，与直出无关
        assertFalse(
            tuningEnabled(
                OutputPath.usbDirect,
                toggles,
                engine = TuningEngine.none,
                rawDsd = true,
            ),
        )
    }

    @Test
    fun `DSD 直出下蓝牙也不会因此打开调音`() {
        // 蓝牙恒定可调音，但直出仍然一票否决 —— 顺序必须是 rawDsd 先判
        assertTrue(tuningEnabled(OutputPath.systemMix, OutputToggles(), bluetooth = true))
        assertFalse(
            tuningEnabled(OutputPath.systemMix, OutputToggles(), bluetooth = true, rawDsd = true),
        )
    }

    /**
     * **系统通路恒可调音** —— 删掉直通开关之后的新基线。
     *
     * 单独锁一条的理由：这是"删掉一个开关"带来的语义变化，不是随手可改的实现细节。
     * 谁要是以后想在系统通路上再加一个"能关掉调音"的开关，会先撞到这条。
     */
    @Test
    fun `系统通路恒可调音 不再有直通开关`() {
        assertTrue(tuningEnabled(OutputPath.systemMix, OutputToggles()))
        assertTrue(
            tuningEnabled(
                OutputPath.systemMix,
                OutputToggles(followSourceRate = true),
                engine = TuningEngine.graphic,
            ),
        )
    }

    /**
     * **按源采样率不关调音** —— 回归用例。
     *
     * 这两件事正交：按源率禁止的只是"我方预转换采样率"，而 PEQ 的系数本来就按输入率算，
     * 工作在源率上完全有效。以前写成 `!offload && !followSourceRate`，
     * 结果变成"想要原样输出就得先放弃均衡器" —— 而这恰恰是最常见的组合。
     */
    @Test
    fun `按源采样率不关调音`() {
        val toggles = OutputToggles(followSourceRate = true)
        assertTrue(tuningEnabled(OutputPath.systemMix, toggles))
    }

    @Test
    fun `AAudio 通路只看 Bit Perfect`() {
        assertFalse(tuningEnabled(OutputPath.aaudioExclusive, OutputToggles(bitPerfect = true)))
        assertTrue(tuningEnabled(OutputPath.aaudioExclusive, OutputToggles(bitPerfect = false)))
        // 系统通路的开关在 AAudio 上不参与（开关是意愿，跟通路走）
        assertFalse(
            tuningEnabled(
                OutputPath.aaudioExclusive,
                OutputToggles(followSourceRate = true, bitPerfect = true),
            ),
        )
    }

    @Test
    fun `USB 独占与 AAudio 一样由 Bit Perfect 决定能不能调音`() {
        // 关掉 Bit Perfect = 允许调音但仍走独占流（绕开 mixer 的重采样）
        assertTrue(
            tuningEnabled(
                OutputPath.usbDirect,
                OutputToggles(followSourceRate = false, bitPerfect = false),
            ),
        )
        assertFalse(
            tuningEnabled(
                OutputPath.usbDirect,
                OutputToggles(followSourceRate = false, bitPerfect = true),
            ),
        )
    }

    /**
     * **「按源采样率」只在系统通路上生效，但蓝牙不再是排除项。**
     *
     * 这条测试改写自原来的「按源率在蓝牙下不生效」。原来那条的理由是：
     * a2dp 线程的率由 codec 协商决定，按源率建轨等于把 44.1→48 从我方 r8brain
     * 推给 AudioFlinger，纯亏。
     *
     * 用户实测推翻了这个取舍：在固定主率的设备上，**交给系统重采样反而没有那层
     * 沙状底噪**（记录在 `native-r8b-resampler.cpp` 的 `kTransBand` 注释里）。
     * 判据只剩"通路"这一条轴 —— 否则会出现「开关显示着却是死的」，那比不给开关更糟。
     */
    @Test
    fun `按源率是系统通路的开关 与蓝牙无关`() {
        assertTrue(followSourceRateActive(OutputPath.systemMix, true))
        assertFalse(followSourceRateActive(OutputPath.systemMix, false))
    }

    @Test
    fun `按源率不跨越到别的通路`() {
        assertFalse(followSourceRateActive(OutputPath.aaudioExclusive, true))
        assertFalse(followSourceRateActive(OutputPath.usbDirect, true))
    }

    /**
     * **蓝牙下 DSP 恒启用** —— 通路开关在蓝牙下全都不参与，因此不该由它们决定调音。
     *
     * "启用"只是"允许处理"：EQ 全平时 `TuningAudioProcessor.isActive()` 为 false，
     * 数据一个字节都不改 —— 所以恒启用不等于恒修改数据。
     */
    @Test
    fun `蓝牙下调音恒启用 不受通路开关影响`() {
        val cases = listOf(
            OutputToggles(),
            OutputToggles(followSourceRate = true),
            OutputToggles(bitPerfect = true),
            OutputToggles(followSourceRate = true, bitPerfect = true),
        )
        for (path in OutputPath.entries) {
            for (t in cases) {
                assertTrue(
                    "蓝牙下 $path $t 应允许调音",
                    tuningEnabled(path, t, bluetooth = true),
                )
                assertEquals(AudioOutputMode.tuned, audioModeOf(path, t, bluetooth = true))
            }
        }
    }

    /**
     * **引擎选「无」= 一票否决，与通路、与通路开关都无关。**
     *
     * 这一档存在的原因：以前"我不想要任何 DSP"只能靠通路开关表达，而那些开关讲的是
     * 别的事（Bit Perfect = 独占流里一个采样都别动），换个通路就没法表达了。
     * 删掉直通之后，**这一票成了唯一的表达方式**，所以它必须压过通路这一维。
     */
    @Test
    fun `引擎选无 任何通路都不调音`() {
        val cases = listOf(
            OutputToggles(),
            OutputToggles(followSourceRate = true),
            OutputToggles(followSourceRate = false, bitPerfect = false),
        )
        for (path in OutputPath.entries) {
            for (t in cases) {
                for (bluetooth in listOf(false, true)) {
                    assertFalse(
                        "引擎=无时 $path $t bt=$bluetooth 不该允许调音",
                        tuningEnabled(path, t, bluetooth, TuningEngine.none),
                    )
                    assertEquals(
                        AudioOutputMode.transparent,
                        audioModeOf(path, t, bluetooth, TuningEngine.none),
                    )
                }
            }
        }
    }

    /** 引擎不是「无」时，通路开关照旧说了算（这一票不改变原有关系）。 */
    @Test
    fun `引擎有值时通路开关照旧生效`() {
        // 系统通路：恒可调音
        assertTrue(
            tuningEnabled(OutputPath.systemMix, OutputToggles(), engine = TuningEngine.graphic),
        )
        assertTrue(
            tuningEnabled(OutputPath.systemMix, OutputToggles(), engine = TuningEngine.mseb),
        )
        // AAudio 下只看 Bit Perfect
        assertTrue(
            tuningEnabled(
                OutputPath.aaudioExclusive,
                OutputToggles(bitPerfect = false),
                engine = TuningEngine.parametric,
            ),
        )
        assertFalse(
            tuningEnabled(
                OutputPath.aaudioExclusive,
                OutputToggles(bitPerfect = true),
                engine = TuningEngine.parametric,
            ),
        )
    }

    @Test
    fun `audioModeOf 与 tuningEnabled 必须一致`() {
        val all = OutputToggles(followSourceRate = true, bitPerfect = false)
        for (path in OutputPath.entries) {
            for (engine in listOf(TuningEngine.none, TuningEngine.graphic)) {
                assertEquals(
                    if (tuningEnabled(path, all, engine = engine)) {
                        AudioOutputMode.tuned
                    } else {
                        AudioOutputMode.transparent
                    },
                    audioModeOf(path, all, engine = engine),
                )
            }
        }
    }

    @Test
    fun `老存档迁移 只决定通路 开关取保守值`() {
        // 老的「透明 / 调音」都落到系统通路；"要不要 DSP"现在由**引擎**表达
        // （`MoreSettingsState` 里把老 transparent 迁成引擎「无」）。
        for (legacy in listOf(AudioOutputMode.transparent.key, AudioOutputMode.tuned.key)) {
            val (path, toggles) = migrateLegacySettings(legacy, null)
            assertEquals(OutputPath.systemMix, path)
            assertFalse("按源率是新开关，老用户没有这个概念", toggles.followSourceRate)
            assertTrue("Bit Perfect 取保守值（开）", toggles.bitPerfect)
        }
    }
}
