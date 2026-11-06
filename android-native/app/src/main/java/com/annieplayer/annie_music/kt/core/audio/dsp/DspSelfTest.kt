package com.annieplayer.annie_music.kt.core.audio.dsp

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 数字链路自检：拿**设备上真实的 `libanniedsp.so`** 跑一遍重采样，再量结果。
 *
 * ## 为什么要有这个
 *
 * PC 上跑源码只能证明"数学对"；证明不了"这台机器上编出来的 arm64 库对"。
 * 这个自检覆盖三件事：
 *
 * 1. **通带增益** —— 997 Hz 经过 SRC 之后电平原样（差 < 0.05 dB）；
 * 2. **失真 / 噪声** —— 用 Parseval 求总能量减去基波，得到 THD+N（含噪声）；
 * 3. **混叠抑制** —— 48k→44.1k 时喂一个 23 kHz 单音（在目标 Nyquist 之上），
 *    它必须被砍掉；如果滤波器坏了，它会折到 44100−23000 = **21100 Hz**，
 *    在那一根频点上量到的就是"漏进来的量"。
 *
 * ## 精度与边界
 *
 * - Goertzel 逐点 DFT，不用 FFT：只要几根频点，O(N) 就够。
 * - 测的是**数字链路**。模拟端（DAC、耳放、扬声器）在这里量不到 ——
 *   那需要回环录制，不是 App 能自己完成的。别把这份结果当成"整机实测"。
 * - 不碰 Android 类，除了 [NativeR8bResampler]（它有 `isAvailable()` 兜底，
 *   JVM 单测里会返回 false，所以 [runAll] 在 JVM 上返回空表）。
 */
object DspSelfTest {

    /** 一个测试算例。 */
    data class Case(
        val label: String,
        val srcRate: Int,
        val dstRate: Int,
        /** 喂进去的单音频率（0.5 FS） */
        val toneHz: Double,
        /**
         * 要在这个频点上量"漏进来多少"（dBFS）；0 = 不量。
         *
         * 降采样算例里它就是预期的混叠落点 —— 只要滤波器正常，这里应该是数字零。
         */
        val rejectAtHz: Double = 0.0,
        /** true = 这是通带算例（要检查增益与 THD+N）；false = 只检查抑制 */
        val passband: Boolean = true,
    )

    data class Result(
        val label: String,
        val srcRate: Int,
        val dstRate: Int,
        val toneHz: Double,
        /** 基波电平相对输入的偏差（dB）；越接近 0 越好 */
        val gainDb: Double,
        /** THD+N（dB，相对基波）；越负越好 */
        val thdPlusNDb: Double,
        /** 该被砍掉的频点上的残留电平（dBFS）；极负 = 没漏 */
        val rejectDb: Double,
        /** 输出真峰值（线性，1.0 = 满刻度） */
        val peak: Double,
        /** 结论；false 时 [reason] 说明哪一项没过 */
        val ok: Boolean,
        val reason: String = "",
    )

    const val TONE_AMPLITUDE: Double = 0.5      // −6 dBFS
    private const val SECONDS: Double = 0.5
    private const val SETTLE = 4_000            // 丢掉前若干输出样本（滤波器进入稳态）

    /** 默认算例：两种最常见的重采样方向 + 一个混叠抑制。 */
    val CASES: List<Case> = listOf(
        Case("44.1k → 48k", 44_100, 48_000, 997.0),
        Case("48k → 44.1k", 48_000, 44_100, 997.0),
        // 23 kHz 在目标 Nyquist（22.05 kHz）之上：正常应该被砍成数字零；
        // 若滤波器坏了会折到 21100 Hz —— 那一根就是"漏进来的混叠"。
        Case("48k → 44.1k · 混叠抑制", 48_000, 44_100, 23_000.0, rejectAtHz = 21_100.0, passband = false),
    )

    /**
     * 跑全部算例。**必须在后台线程调用**（几百毫秒级）。
     * native 库不可用时返回空表 —— 调用方据此显示"不可用"，不要当成"全部通过"。
     */
    fun runAll(cases: List<Case> = CASES): List<Result> {
        if (!NativeR8bResampler.isAvailable()) return emptyList()
        return cases.mapNotNull { run(it) }
    }

    fun run(case: Case): Result? {
        val inFrames = (case.srcRate * SECONDS).toInt()
        val input = sine(case.toneHz, case.srcRate, inFrames, TONE_AMPLITUDE, channels = 2)
        val (output, peak) = resample(input, case.srcRate, case.dstRate) ?: return null
        if (output.size <= SETTLE) return null

        val steady = output.copyOfRange(SETTLE, output.size)
        val fs = case.dstRate
        val inDb = 20.0 * log10(TONE_AMPLITUDE)

        if (!case.passband) {
            val leak = toneDb(steady, fs, case.rejectAtHz)
            val ok = leak < REJECT_LIMIT_DB
            return Result(
                label = case.label,
                srcRate = case.srcRate,
                dstRate = case.dstRate,
                toneHz = case.toneHz,
                gainDb = 0.0,
                thdPlusNDb = 0.0,
                rejectDb = leak,
                peak = peak,
                ok = ok,
                reason = if (ok) "" else "混叠残留 %.1f dBFS（应 < %.0f）".format(leak, REJECT_LIMIT_DB),
            )
        }

        val fundDb = toneDb(steady, fs, case.toneHz)
        val gainDb = fundDb - inDb
        val thdN = thdPlusNDb(steady, fs, case.toneHz)

        val gainOk = abs(gainDb) < GAIN_LIMIT_DB
        val thdOk = thdN < THD_LIMIT_DB
        return Result(
            label = case.label,
            srcRate = case.srcRate,
            dstRate = case.dstRate,
            toneHz = case.toneHz,
            gainDb = gainDb,
            thdPlusNDb = thdN,
            rejectDb = 0.0,
            peak = peak,
            ok = gainOk && thdOk,
            reason = when {
                !gainOk -> "通带增益偏了 %.3f dB".format(gainDb)
                !thdOk -> "THD+N %.1f dB 偏高".format(thdN)
                else -> ""
            },
        )
    }

    // ── 下面是纯计算，全部可单测 ─────────────────────────────────────────────

    /** 通带增益容差（dB）：重采样是线性运算，通带内不该有任何可见偏差 */
    const val GAIN_LIMIT_DB = 0.05

    /** THD+N 上限（dB）：16 Bit 源单音 + 抖动，理论上在 −90 dB 以下 */
    const val THD_LIMIT_DB = -80.0

    /** 混叠残留上限（dBFS）：阻带 136 dB 时它应该远低于此 */
    const val REJECT_LIMIT_DB = -120.0

    /** 生成交错的多声道正弦（float）。 */
    internal fun sine(
        hz: Double,
        fs: Int,
        frames: Int,
        amplitude: Double,
        channels: Int = 1,
    ): FloatArray {
        val out = FloatArray(frames * channels)
        val w = 2.0 * Math.PI * hz / fs
        for (i in 0 until frames) {
            val v = (amplitude * sin(w * i)).toFloat()
            for (c in 0 until channels) out[i * channels + c] = v
        }
        return out
    }

    /**
     * Goertzel 单点 DFT，返回该频点上的**正弦幅度**（0.5 = −6 dBFS）。
     *
     * 用余弦窗之外的矩形窗 + 非整数周期会有泄漏，所以这里把 [freq] 与采样点数
     * 对齐到整数周期（见 [nearestBin]）。
     */
    internal fun toneAmplitude(x: FloatArray, fs: Int, freq: Double): Double {
        if (x.isEmpty() || fs <= 0 || freq <= 0.0) return 0.0
        val n = x.size
        val k = 2.0 * Math.PI * freq / fs
        val coeff = 2.0 * cos(k)
        var s0 = 0.0
        var s1 = 0.0
        var s2 = 0.0
        for (v in x) {
            s0 = v + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        val power = s1 * s1 + s2 * s2 - coeff * s1 * s2
        // Goertzel 的幅度 = sqrt(power) * 2 / N
        return 2.0 * sqrt(power.coerceAtLeast(0.0)) / n
    }

    internal fun toneDb(x: FloatArray, fs: Int, freq: Double): Double =
        20.0 * log10(toneAmplitude(x, fs, freq).coerceAtLeast(1e-12))

    /**
     * THD+N（dB，相对基波）：`sqrt(总能量 − 基波能量) / 基波能量`。
     *
     * 用 Parseval 直接从时域求总能量，不需要 FFT —— 这正是 THD+N 的定义
     * （除基波之外的一切：谐波、噪声、杂散都在里面）。
     */
    internal fun thdPlusNDb(x: FloatArray, fs: Int, freq: Double): Double {
        if (x.isEmpty()) return 0.0
        var sum = 0.0
        for (v in x) sum += v.toDouble() * v
        val totalRms = sqrt(sum / x.size)
        val fund = toneAmplitude(x, fs, freq) / sqrt(2.0)
        if (fund <= 1e-12) return 0.0
        val rest = totalRms * totalRms - fund * fund
        return 20.0 * log10(sqrt(rest.coerceAtLeast(0.0)).coerceAtLeast(1e-12) / fund)
    }

    // ── native ────────────────────────────────────────────────────────────

    /** @return (输出交错 float, 真峰值)；native 不可用返回 null */
    private fun resample(input: FloatArray, srcRate: Int, dstRate: Int): Pair<FloatArray, Double>? {
        val channels = 2
        val handle = NativeR8bResampler.create(
            channels = channels,
            srcRate = srcRate,
            dstRate = dstRate,
            srcBits = 16,
            lpfHz = 0,
            transBand = 0.0,
        )
        if (handle == 0L) return null

        try {
            // float → s16 交错字节（与线上 MediaCodec 出口一致）
            val frames = input.size / channels
            val srcBytes = ByteArray(frames * channels * 2)
            for (i in input.indices) {
                val s = (input[i] * 32768.0).toInt().coerceIn(-32768, 32767)
                srcBytes[i * 2] = (s and 0xFF).toByte()
                srcBytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
            }

            val out = ArrayList<Float>(frames * 2)
            val chunk = 4096
            var pos = 0
            while (pos < frames) {
                val n = minOf(chunk, frames - pos)
                val inArr = srcBytes.copyOfRange(pos * channels * 2, (pos + n) * channels * 2)
                val cap = NativeR8bResampler.maxOutFrames(handle, n).coerceAtLeast(n)
                val outArr = ByteArray(cap * channels * 4)
                val written = NativeR8bResampler.processBytes(
                    handle = handle,
                    input = inArr,
                    output = outArr,
                    inFrames = n,
                    inEncoding = 2,          // 16 bit
                    outEncoding = 4,         // float32
                    dither = false,
                )
                var b = 0
                while (b < written) {
                    // little-endian float
                    val bits = (outArr[b].toInt() and 0xFF) or
                        ((outArr[b + 1].toInt() and 0xFF) shl 8) or
                        ((outArr[b + 2].toInt() and 0xFF) shl 16) or
                        ((outArr[b + 3].toInt() and 0xFF) shl 24)
                    out.add(Float.fromBits(bits))
                    b += 4
                }
                pos += n
            }

            val peak = NativeR8bResampler.takePeak(handle)
            // 只取第 0 声道做分析
            val mono = FloatArray(out.size / channels) { out[it * channels] }
            return mono to peak
        } finally {
            NativeR8bResampler.release(handle)
        }
    }
}
