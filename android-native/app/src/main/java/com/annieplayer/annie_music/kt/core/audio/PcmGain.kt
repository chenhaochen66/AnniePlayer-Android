package com.annieplayer.annie_music.kt.core.audio

import androidx.media3.common.C

/**
 * 在**我方**这一侧做数字音量。
 *
 * ## 为什么必须有它
 *
 * 独占通路（`AaudioSink` 的 AAudio 与 USB 两个引擎）直接把样本写进设备环形缓冲，
 * 完全绕开 AudioFlinger —— 而 `AudioTrack.setVolume` 的音量正是由 mixer 施加的。
 * 于是 `AudioSink.setVolume()` 在独占通路上**没有任何接收方**：调音模式下用户拖动
 * 音量滑块，数据一个字节都不变，输出恒定满幅。
 *
 * ## 什么时候允许
 *
 * 只在「调音启用」时。透明 / Bit Perfect 的契约是"我方不动数据"，那时音量交给
 * 系统与设备；raw DSD（DoP / Native）更是绝对不能碰 —— 乘一次增益会把 1-bit 流
 * 连同 DoP 标记一起毁掉。
 *
 * ## 精度
 *
 * float 是这条链上唯一会走到这里的编码（`OutputPlan.bitDepth` 在"我方碰过数据"时
 * 恒为 `PLAN_FLOAT_DEPTH`），所以主路径是一次 float 乘法，与 mixer 内部做的事完全等价。
 * 其余宽度只在防御性路径上出现，按整数缩放并就近取整。
 */
internal object PcmGain {

    /**
     * 抖动状态（xorshift32）。
     *
     * 衰减本身就是一次**重新量化**：样本乘完增益再截断，舍入误差与信号高度相关，
     * 低电平时会听成沙哑 / 毛刺。加 ±1 LSB 的 TPDF 抖动把那层误差变成与信号无关的
     * 白噪声 —— 这是数字衰减的标准做法，不是调音偏好，所以不设开关。
     *
     * 只在真的衰减时才用（`gain == 1f` 时函数直接返回，恒等映射不能被破坏）。
     */
    private var dither = 0x9E3779B9.toInt()

    /**
     * 三角分布随机数，单位是 **1 个 LSB**（范围 ±1）。
     *
     * TPDF = 两个独立均匀分布之差；RPDF（单均匀）会把噪声调制到信号上，不能用。
     * 每个样本只推进一次状态：xorshift 的高位混得够开，取两组 8 位就足够独立，
     * 而这条路径每秒要跑几十万到几百万个样本，省下的指令是实打实的。
     */
    private fun nextTpdf(): Double {
        var x = dither
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        dither = x
        val a = (x ushr 24) and 0xFF
        val b = (x ushr 16) and 0xFF
        return (a - b) / 256.0
    }

    /** 就地缩放 [size] 字节。`encoding` 用 Media3 的 `C.ENCODING_PCM_*`。 */
    fun apply(bytes: ByteArray, size: Int, encoding: Int, gain: Float) {
        if (gain == 1f || size <= 0) return
        val usable = when (encoding) {
            C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_32BIT -> size - size % 4
            C.ENCODING_PCM_24BIT -> size - size % 3
            else -> size - size % 2
        }
        if (usable <= 0) return
        when (encoding) {
            // float / int32 是中间容器：量化发生在下游（写设备那一步），这里不需要抖动
            C.ENCODING_PCM_FLOAT -> {
                var i = 0
                while (i < usable) {
                    val bits = (bytes[i].toInt() and 0xFF) or
                        ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                        ((bytes[i + 2].toInt() and 0xFF) shl 16) or
                        ((bytes[i + 3].toInt() and 0xFF) shl 24)
                    val v = Float.fromBits(bits) * gain
                    val out = v.toRawBits()
                    bytes[i] = out.toByte()
                    bytes[i + 1] = (out shr 8).toByte()
                    bytes[i + 2] = (out shr 16).toByte()
                    bytes[i + 3] = (out shr 24).toByte()
                    i += 4
                }
            }
            C.ENCODING_PCM_24BIT -> {
                var i = 0
                while (i < usable) {
                    val v = (bytes[i].toInt() and 0xFF) or
                        ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                        (bytes[i + 2].toInt() shl 16)
                    val scaled = clamp24((v.toDouble() * gain + nextTpdf()).toInt())
                    bytes[i] = scaled.toByte()
                    bytes[i + 1] = (scaled shr 8).toByte()
                    bytes[i + 2] = (scaled shr 16).toByte()
                    i += 3
                }
            }
            C.ENCODING_PCM_32BIT -> {
                var i = 0
                while (i < usable) {
                    val v = (bytes[i].toInt() and 0xFF) or
                        ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                        ((bytes[i + 2].toInt() and 0xFF) shl 16) or
                        ((bytes[i + 3].toInt() and 0xFF) shl 24)
                    val scaled = (v.toDouble() * gain).toLong()
                        .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                    bytes[i] = scaled.toByte()
                    bytes[i + 1] = (scaled shr 8).toByte()
                    bytes[i + 2] = (scaled shr 16).toByte()
                    bytes[i + 3] = (scaled shr 24).toByte()
                    i += 4
                }
            }
            else -> {
                var i = 0
                while (i < usable) {
                    val v = ((bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)).toShort()
                    val scaled = (v.toDouble() * gain + nextTpdf()).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    bytes[i] = scaled.toByte()
                    bytes[i + 1] = (scaled.toInt() shr 8).toByte()
                    i += 2
                }
            }
        }
    }

    private fun clamp24(v: Int): Int =
        if (v > 0x7FFFFF) 0x7FFFFF else if (v < -0x800000) -0x800000 else v
}
