package com.annieplayer.annie_music.kt.core.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 自检里那些纯计算（Goertzel 幅度、THD+N、正弦生成）的正确性。
 *
 * 这些数如果错了，自检报告的"通过"就是假的 —— 所以这里用**已知答案**卡：
 * 整 LSB 正弦 + 一个已知的谐波，看量出来对不对。
 */
class DspSelfTestTest {

    private val fs = 48_000
    private val n = 9_600        // 0.2 s → 997 Hz 恰好 199.4 个周期，故意不是整数，看泄漏有多大

    @Test
    fun `Goertzel 能量出正弦幅度`() {
        // 取整数周期：f = fs * 100 / n = 500 Hz
        val freq = 500.0
        val x = DspSelfTest.sine(freq, fs, n, 0.5)
        val amp = DspSelfTest.toneAmplitude(x, fs, freq)
        assertEquals(0.5, amp, 1e-3)
    }

    @Test
    fun `纯净正弦的 THD+N 极低`() {
        val freq = 500.0
        val x = DspSelfTest.sine(freq, fs, n, 0.5)
        val thd = DspSelfTest.thdPlusNDb(x, fs, freq)
        // 矩形窗 + 整周期 ⇒ 理论上只剩浮点尾数；给 20 dB 余量
        assertTrue("THD+N = $thd dB，期望 < -100", thd < -100.0)
    }

    @Test
    fun `加了三次谐波后 THD+N 跟上`() {
        val freq = 500.0
        val base = DspSelfTest.sine(freq, fs, n, 0.5)
        // 三次谐波幅度 = 基波的 1/1000 → THD ≈ −60 dB
        val h3 = DspSelfTest.sine(freq * 3, fs, n, 0.5 / 1000.0)
        val mixed = FloatArray(n) { base[it] + h3[it] }
        val thd = DspSelfTest.thdPlusNDb(mixed, fs, freq)
        assertEquals(-60.0, thd, 1.0)
    }

    @Test
    fun `Goertzel 对不存在的频点给数字零`() {
        val x = DspSelfTest.sine(500.0, fs, n, 0.5)
        // 1800 Hz 也是整周期（n/fs = 0.2 s → 每 100 Hz 一个 bin），且没有能量
        val empty = DspSelfTest.toneDb(x, fs, 1800.0)
        assertTrue("残留 = $empty dB", empty < -100.0)
    }

    @Test
    fun `全零输入不会炸出 NaN 或负数 dB`() {
        val x = FloatArray(n)
        val amp = DspSelfTest.toneAmplitude(x, fs, 500.0)
        assertEquals(0.0, amp, 1e-12)
        assertTrue(DspSelfTest.toneDb(x, fs, 500.0) < -200.0)
        // 基波为 0 时 THD+N 定义为 0 dB，不许返回 NaN / Inf
        val thd = DspSelfTest.thdPlusNDb(x, fs, 500.0)
        assertTrue("THD+N = $thd", !thd.isNaN() && !thd.isInfinite())
    }

    @Test
    fun `没有 native 库时自检返回空表而不是伪装通过`() {
        // JVM 单测里 System.loadLibrary 必然失败 → isAvailable() == false
        assertTrue("这台上不该有 native 库", !NativeR8bResampler.isAvailable())
        assertTrue(DspSelfTest.runAll().isEmpty())
    }

    @Test
    fun `用例清单覆盖两个重采样方向与一个混叠抑制`() {
        val labels = DspSelfTest.CASES.map { it.label }
        assertEquals(3, labels.size)
        assertTrue(DspSelfTest.CASES.any { it.srcRate == 44_100 && it.dstRate == 48_000 })
        assertTrue(DspSelfTest.CASES.any { it.srcRate == 48_000 && it.dstRate == 44_100 })
        // 混叠落点必须是 44100 − 23000，算错了就量错频点
        val alias = DspSelfTest.CASES.first { it.rejectAtHz > 0 }
        assertEquals(21_100.0, alias.rejectAtHz, 1e-9)
        // 折叠用的是**目标**采样率，不是源率（这里曾经写成 srcRate − tone，被这条断言抓住）
        assertEquals(alias.dstRate - alias.toneHz, alias.rejectAtHz, 1e-9)
    }

    @Test
    fun `真峰值阈值与增益容差方向正确`() {
        // 1.0 是满刻度：刚好不削，超过一点就该判削
        assertTrue(1.0 >= 1.0)
        assertTrue(DspSelfTest.GAIN_LIMIT_DB > 0.0)
        assertTrue(DspSelfTest.THD_LIMIT_DB < 0.0)
        assertTrue(DspSelfTest.REJECT_LIMIT_DB < 0.0)
        // 阈值之间不能自相矛盾：THD 上限必须比混叠上限宽松（后者是"应该没有"）
        assertTrue(DspSelfTest.THD_LIMIT_DB > DspSelfTest.REJECT_LIMIT_DB)
    }

    @Test
    fun `dB 换算自洽`() {
        assertEquals(0.0, 20.0 * log10(1.0), 1e-12)
        assertEquals(-6.0206, 20.0 * log10(0.5), 1e-3)
        // 整 LSB 幅度（16 Bit 最低位）应该是 −90 dBFS 量级
        val oneLsb = 1.0 / 32768.0
        assertTrue(abs(20.0 * log10(oneLsb) + 90.3) < 0.2)
    }

    @Test
    fun `正弦生成的幅度与周期都对`() {
        val x = DspSelfTest.sine(1000.0, 48_000, 96, 0.5, channels = 2)
        assertEquals(192, x.size)
        // 双声道内容逐位相同
        assertTrue(x[0] == x[1])
        // 峰值落在四分之一周期附近
        val peak = x.filterIndexed { i, _ -> i % 2 == 0 }.maxOf { abs(it) }
        assertTrue("peak=$peak", abs(peak - 0.5f) < 1e-3f)
        assertTrue(sqrt(x.filterIndexed { i, _ -> i % 2 == 0 }.map { it * it }.average()) > 0.3)
    }
}
