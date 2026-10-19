package com.annieplayer.annie_music.kt.core.audio.dsp

import com.annieplayer.annie_music.kt.core.audio.EqBandFrequencies
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * 15 段图示 EQ（RBJ peaking biquad 级联）。
 *
 * 系数按采样率预计算、**不可变**；运行中换预设时整体替换本对象，
 * 音频线程只读引用，因此不需要加锁（对齐 AnnieEngine `Eq.cs` 的原子替换思路）。
 * 状态（z1/z2）由 [TuningAudioProcessor] 持有，换预设时不清零 —— 清零点会听成「咔哒」。
 */
internal class BiquadEq(gainsDb: FloatArray, sampleRate: Int) {

    private val bandCount = EqBandFrequencies.size
    private val b0 = FloatArray(bandCount)
    private val b1 = FloatArray(bandCount)
    private val b2 = FloatArray(bandCount)
    private val a1 = FloatArray(bandCount)
    private val a2 = FloatArray(bandCount)
    private val enabled = BooleanArray(bandCount)

    /** 全 0dB 时整体跳过（旁路），省掉 15 段无效乘加 */
    val isFlat: Boolean

    init {
        var any = false
        val sr = sampleRate.coerceAtLeast(8000).toDouble()
        for (band in 0 until bandCount) {
            val gainDb = gainsDb.getOrElse(band) { 0f }.toDouble()
            if (abs(gainDb) < 0.01) continue
            enabled[band] = true
            any = true

            val freq = EqBandFrequencies[band].toDouble().coerceAtMost(sr * 0.49)
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * freq / sr
            val alpha = sin(w0) / (2.0 * Q)

            val n0 = 1.0 + alpha * a
            val n1 = -2.0 * cos(w0)
            val n2 = 1.0 - alpha * a
            val d0 = 1.0 + alpha / a
            val d1 = -2.0 * cos(w0)
            val d2 = 1.0 - alpha / a

            b0[band] = (n0 / d0).toFloat()
            b1[band] = (n1 / d0).toFloat()
            b2[band] = (n2 / d0).toFloat()
            a1[band] = (d1 / d0).toFloat()
            a2[band] = (d2 / d0).toFloat()
        }
        isFlat = !any
    }

    /**
     * 原地处理 interleaved float 采样（[-1,1]）。[z1]/[z2] 长度必须是 `bandCount * channels`，
     * 索引 = `band * channels + channel`。
     */
    fun process(buffer: FloatArray, frames: Int, channels: Int, z1: FloatArray, z2: FloatArray) {
        if (isFlat) return
        val total = frames * channels
        for (band in 0 until bandCount) {
            if (!enabled[band]) continue
            val cb0 = b0[band]
            val cb1 = b1[band]
            val cb2 = b2[band]
            val ca1 = a1[band]
            val ca2 = a2[band]
            for (i in 0 until total) {
                val channel = i % channels
                val stateIdx = band * channels + channel
                val x = buffer[i]
                val y = cb0 * x + z1[stateIdx]
                z1[stateIdx] = cb1 * x - ca1 * y + z2[stateIdx]
                z2[stateIdx] = cb2 * x - ca2 * y
                buffer[i] = y
            }
        }
    }

    private companion object {
        /** 2/3 倍频程相邻段互相覆盖的常用 Q 值 */
        const val Q = 1.41
    }
}
