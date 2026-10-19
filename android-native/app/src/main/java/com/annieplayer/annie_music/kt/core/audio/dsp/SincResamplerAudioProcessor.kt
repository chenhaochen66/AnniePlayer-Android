package com.annieplayer.annie_music.kt.core.audio.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 一次性高质量重采样（Kaiser 窗 sinc，多相查表）。
 *
 * 目的（方案 1.2.2）：把源采样率对齐到**设备原生率**，让 AudioTrack 不再被
 * AudioFlinger 二次重采样。系统那层 SRC 的质量不可控，自己用可控的 sinc 做一次，
 * 比「44.1k 直出、mixer 偷偷拉到 48k」更可解释。
 *
 * ⚠️ 只在用户打开「无 SRC 输出」时启用；源率已等于原生率时整体旁路。
 * 这是纯 Kotlin 实现（项目里的 libsoxr 只存在于 ffmpeg filter 内部，没有 JNI 绑定），
 * 参数与 soxr 同档（Kaiser β=8，48 taps），后续可无缝替换成 soxr。
 */
class SincResamplerAudioProcessor : BaseAudioProcessor() {

    @Volatile private var _enabled: Boolean = false
    @Volatile private var _targetRate: Int = 0
    @Volatile private var _active: Boolean = false

    @Volatile private var _targetDepth: Int = 0

    private var _srcRate: Int = 0
    private var _dstRate: Int = 0
    private var _ratio: Double = 1.0
    private var _channels: Int = 2
    private var _inputEncoding: Int = C.ENCODING_INVALID
    private var _outputEncoding: Int = C.ENCODING_INVALID
    private var _inBytesPerSample: Int = 2
    private var _outBytesPerSample: Int = 2

    private var _ring = FloatArray(0)
    private var _ringFrames: Int = 0
    private var _ringStart: Long = 0
    private var _totalIn: Long = 0
    private var _srcPos: Double = 0.0

    private var _coeffs: Array<FloatArray> = emptyArray()
    private var _scratch = FloatArray(0)

    /**
     * [enabled] 关闭或目标率无效时本次配置直接旁路。
     * 开关变化不会热重配音频链路，下一次 `prepare`/切歌自动生效。
     */
    fun setEnabled(enabled: Boolean, targetRate: Int) {
        _enabled = enabled
        _targetRate = targetRate
    }

    /** 输出位深：0 = 跟随输入，16 / 32（float） */
    fun setOutputDepth(depth: Int) {
        _targetDepth = depth
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        _active = false
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        if (!_enabled) return AudioProcessor.AudioFormat.NOT_SET

        val targetEncoding = when (_targetDepth) {
            16 -> C.ENCODING_PCM_16BIT
            24 -> C.ENCODING_PCM_24BIT
            32 -> C.ENCODING_PCM_FLOAT
            else -> encoding
        }
        val doResample = _targetRate > 0 && _targetRate != inputAudioFormat.sampleRate
        val doDepth = targetEncoding != encoding
        if (!doResample && !doDepth) return AudioProcessor.AudioFormat.NOT_SET

        _srcRate = inputAudioFormat.sampleRate
        _dstRate = if (doResample) _targetRate else inputAudioFormat.sampleRate
        _ratio = _srcRate.toDouble() / _dstRate
        _channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        _inputEncoding = encoding
        _outputEncoding = targetEncoding
        _inBytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        _outBytesPerSample = when (targetEncoding) {
            C.ENCODING_PCM_FLOAT -> 4
            C.ENCODING_PCM_24BIT -> 3
            else -> 2
        }
        _ring = FloatArray(RingFrames * _channels)
        _ringFrames = 0
        _ringStart = 0
        _totalIn = 0
        _srcPos = 0.0
        buildCoeffs()
        _active = true
        return AudioProcessor.AudioFormat(_dstRate, _channels, targetEncoding)
    }

    override fun isActive(): Boolean = _active

    override fun getDurationAfterProcessorApplied(inputDurationUs: Long): Long =
        if (_active && _srcRate > 0) inputDurationUs * _dstRate / _srcRate else inputDurationUs

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!_active) return
        val size = inputBuffer.remaining()
        if (size <= 0) return
        val channels = _channels
        val bytesPerFrame = _inBytesPerSample * channels
        val inFrames = size / bytesPerFrame
        if (inFrames <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        // 只改位深、不改采样率时走直通转换（避免 sinc 插值引入额外滤波）
        if (_ratio == 1.0) {
            queuePassthrough(inputBuffer, inFrames)
            return
        }

        ensureSpace(inFrames)
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val base = _ringFrames * channels

        when (_inputEncoding) {
            C.ENCODING_PCM_FLOAT -> {
                ensureScratch(inFrames * channels)
                for (i in 0 until inFrames * channels) {
                    _scratch[i] = inputBuffer.float
                }
                System.arraycopy(_scratch, 0, _ring, base, inFrames * channels)
                inputBuffer.position(inputBuffer.limit())
            }
            else -> {
                for (i in 0 until inFrames * channels) {
                    _ring[base + i] = inputBuffer.getShort() / 32768f
                }
            }
        }
        _ringFrames += inFrames
        _totalIn += inFrames

        // 上界：本次输入按比例最多产出的帧数（+2 吸收上次剩余的相位误差）
        val maxOutFrames = (inFrames / _ratio).toInt() + 2
        val out = replaceOutputBuffer(maxOutFrames * _outBytesPerSample * channels)
            .order(ByteOrder.LITTLE_ENDIAN)
        var produced = 0
        while (produced < maxOutFrames && _srcPos + HalfTaps < _totalIn) {
            emit(out)
            produced++
            _srcPos += _ratio
        }
        out.flip()
        inputBuffer.position(inputBuffer.limit())
    }

    override fun onFlush() {
        _ringFrames = 0
        _ringStart = 0
        _totalIn = 0
        _srcPos = 0.0
        _ring.fill(0f)
    }

    override fun onReset() {
        _active = false
        _ring = FloatArray(0)
        _ringFrames = 0
        _ringStart = 0
        _totalIn = 0
        _srcPos = 0.0
        _coeffs = emptyArray()
        _scratch = FloatArray(0)
    }

    // ── 内部 ──

    /** 采样率不变、只换 encoding 的直通路径 */
    private fun queuePassthrough(inputBuffer: ByteBuffer, frames: Int) {
        val channels = _channels
        val out = replaceOutputBuffer(frames * _outBytesPerSample * channels)
            .order(ByteOrder.LITTLE_ENDIAN)
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames * channels) {
            val value = when (_inputEncoding) {
                C.ENCODING_PCM_FLOAT -> inputBuffer.float
                else -> inputBuffer.getShort() / 32768f
            }
            writeSample(out, value)
        }
        out.flip()
        inputBuffer.position(inputBuffer.limit())
    }

    private fun writeSample(out: ByteBuffer, value: Float) {
        when (_outputEncoding) {
            C.ENCODING_PCM_FLOAT -> out.putFloat(value.coerceIn(-1f, 1f))
            C.ENCODING_PCM_24BIT -> {
                val v = (value * 8_388_607f).toInt().coerceIn(-8_388_608, 8_388_607)
                out.put((v and 0xFF).toByte())
                out.put((v shr 8 and 0xFF).toByte())
                out.put((v shr 16 and 0xFF).toByte())
            }
            else -> out.putShort((value * 32767f).toInt().coerceIn(-32768, 32767).toShort())
        }
    }

    private fun emit(out: ByteBuffer) {
        val center = floor(_srcPos).toLong()
        val frac = _srcPos - center
        val phase = (frac * Phases).toInt().coerceIn(0, Phases - 1)
        val coeff = _coeffs[phase]
        val channels = _channels
        val firstFrame = center - HalfTaps + 1
        val ringEnd = _ringFrames * channels

        for (c in 0 until channels) {
            var acc = 0f
            var rel = (firstFrame - _ringStart).toInt() * channels + c
            for (j in 0 until Taps) {
                val v = if (rel < 0 || rel >= ringEnd) 0f else _ring[rel]
                acc += v * coeff[j]
                rel += channels
            }
            writeSample(out, acc)
        }
    }

    private fun ensureSpace(extraFrames: Int) {
        val channels = _channels
        val capacityFrames = _ring.size / channels
        if (_ringFrames + extraFrames <= capacityFrames) return

        // 只保留滤波器窗口还需要的最左侧样本
        val keepFrom = floor(_srcPos).toLong() - HalfTaps
        val drop = (keepFrom - _ringStart).toInt().coerceIn(0, _ringFrames)
        if (drop > 0) {
            val remain = _ringFrames - drop
            System.arraycopy(_ring, drop * channels, _ring, 0, remain * channels)
            _ringStart += drop
            _ringFrames = remain
        }
        if (_ringFrames + extraFrames > capacityFrames) {
            val grown = maxOf(capacityFrames, _ringFrames + extraFrames)
            val next = FloatArray(grown * channels)
            System.arraycopy(_ring, 0, next, 0, _ringFrames * channels)
            _ring = next
        }
    }

    private fun ensureScratch(size: Int) {
        if (_scratch.size < size) _scratch = FloatArray(size)
    }

    private fun buildCoeffs() {
        val coeffs = Array(Phases) { FloatArray(Taps) }
        // 截止频率跟随较慢的一侧，留 6% 过渡带避免混叠
        val fc = 0.5 * min(1.0, _dstRate.toDouble() / _srcRate) * 0.94
        val beta = 8.0
        val i0Beta = besselI0(beta)
        for (phase in 0 until Phases) {
            val frac = phase.toDouble() / Phases
            val arr = coeffs[phase]
            var sum = 0.0
            for (j in 0 until Taps) {
                val d = (j - (HalfTaps - 1)) - frac
                val arg = 2.0 * fc * d
                val sinc = if (abs(arg) < 1e-9) 1.0 else sin(PI * arg) / (PI * arg)
                val r = d / HalfTaps
                val window = if (abs(r) >= 1.0) 0.0 else besselI0(beta * sqrt(1.0 - r * r)) / i0Beta
                val v = 2.0 * fc * sinc * window
                arr[j] = v.toFloat()
                sum += v
            }
            if (sum != 0.0) {
                for (j in 0 until Taps) arr[j] = (arr[j] / sum).toFloat()
            }
        }
        _coeffs = coeffs
    }

    private companion object {
        /** 每声道保留的 Taps 数；48 taps / Kaiser β=8 ≈ -80dB 阻带 */
        const val Taps = 48
        const val HalfTaps = Taps / 2
        const val Phases = 512
        /** 环形缓冲容量（帧）；足够覆盖任意一次 AudioSink 输入块 + 滤波器窗口 */
        const val RingFrames = 65_536

        /** 零阶修正贝塞尔函数（Kaiser 窗用），级数展开足够收敛 */
        fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val halfX = x / 2.0
            var k = 1
            while (k < 32) {
                term *= (halfX / k) * (halfX / k)
                sum += term
                if (term < 1e-12) break
                k++
            }
            return sum
        }
    }
}
