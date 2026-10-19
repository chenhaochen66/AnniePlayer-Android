package com.annieplayer.annie_music.kt.core.audio.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.tanh

/**
 * 调音链：15 段 EQ → 前级补偿 → 响度增益 → 软限幅。
 *
 * 挂在 Media3 的 `DefaultAudioSink` 处理器链里，输入输出格式保持不变
 * （采样率变化交给 [SincResamplerAudioProcessor]）。
 *
 * **透明模式必须让它整体旁路**：`isActive()` 返回 false 时 AudioSink 会跳过本处理器，
 * 这才是「DSP 一个都不碰」的硬保证，而不是靠增益填 0 假装。
 */
class TuningAudioProcessor : BaseAudioProcessor() {

    @Volatile private var _eqEnabled: Boolean = false
    @Volatile private var _gainsDb: FloatArray = FloatArray(0)
    @Volatile private var _loudnessLinear: Float = 1f
    @Volatile private var _limiterEnabled: Boolean = false
    @Volatile private var _preampLinear: Float = 1f

    @Volatile private var _eq: BiquadEq? = null
    @Volatile private var _configured: Boolean = false
    @Volatile private var _encoding: Int = C.ENCODING_INVALID
    @Volatile private var _channels: Int = 2
    @Volatile private var _sampleRate: Int = 0

    private var _z1 = FloatArray(0)
    private var _z2 = FloatArray(0)
    private var _scratch = FloatArray(0)

    /** 播放中可随时改参数（系数整体替换，音频线程无锁读取） */
    fun setParams(
        eqEnabled: Boolean,
        gainsDb: FloatArray,
        loudnessDb: Float,
        limiterEnabled: Boolean,
    ) {
        _eqEnabled = eqEnabled
        _gainsDb = gainsDb.copyOf()
        _loudnessLinear = dbToLinear(loudnessDb)
        _limiterEnabled = limiterEnabled
        // EQ 抬高某段后很容易削波，用最大正增益的 60% 做前级补偿
        val peak = gainsDb.filter { it > 0f }.maxOrNull() ?: 0f
        _preampLinear = dbToLinear(-peak * 0.6f)
        rebuildEq()
    }

    private fun rebuildEq() {
        if (!_configured || _sampleRate <= 0) return
        _eq = BiquadEq(_gainsDb, _sampleRate)
        val need = EqBandCount * _channels
        if (_z1.size != need) {
            _z1 = FloatArray(need)
            _z2 = FloatArray(need)
        }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            _configured = false
            return AudioProcessor.AudioFormat.NOT_SET
        }
        _configured = true
        _encoding = encoding
        _channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        _sampleRate = inputAudioFormat.sampleRate
        _z1 = FloatArray(EqBandCount * _channels)
        _z2 = FloatArray(EqBandCount * _channels)
        rebuildEq()
        return inputAudioFormat
    }

    override fun isActive(): Boolean {
        if (!_configured) return false
        val eqActive = _eqEnabled && _eq?.isFlat == false
        return eqActive || _loudnessLinear != 1f || _limiterEnabled
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size <= 0) return
        val channels = _channels
        val bytesPerSample = if (_encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val frames = size / (bytesPerSample * channels)
        val total = frames * channels

        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        ensureScratch(total)

        when (_encoding) {
            C.ENCODING_PCM_FLOAT -> {
                for (i in 0 until total) {
                    _scratch[i] = inputBuffer.float
                }
                inputBuffer.position(inputBuffer.limit())
            }
            else -> {
                for (i in 0 until total) {
                    _scratch[i] = inputBuffer.getShort() / 32768f
                }
            }
        }

        val eq = _eq
        if (_eqEnabled && eq != null) eq.process(_scratch, frames, channels, _z1, _z2)

        val gain = _preampLinear * _loudnessLinear
        val limiter = _limiterEnabled
        if (gain != 1f || limiter) {
            for (i in 0 until total) {
                var v = _scratch[i] * gain
                if (limiter) v = softLimit(v)
                _scratch[i] = v
            }
        }

        val out = replaceOutputBuffer(size).order(ByteOrder.LITTLE_ENDIAN)
        if (_encoding == C.ENCODING_PCM_FLOAT) {
            for (i in 0 until total) out.putFloat(_scratch[i])
        } else {
            for (i in 0 until total) {
                out.putShort((_scratch[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            }
        }
        out.flip()
    }

    override fun onFlush() {
        _z1.fill(0f)
        _z2.fill(0f)
    }

    override fun onReset() {
        _z1 = FloatArray(0)
        _z2 = FloatArray(0)
        _scratch = FloatArray(0)
        _eq = null
        _configured = false
    }

    private fun ensureScratch(size: Int) {
        if (_scratch.size < size) _scratch = FloatArray(max(size, 4096))
    }

    private companion object {
        const val EqBandCount = 15

        fun dbToLinear(db: Float): Float =
            if (db == 0f) 1f else 10f.pow(db / 20f)

        /**
         * 软限幅：0.7 以下完全线性，超过后平滑压到 ±1。
         * 只在过载时介入，正常电平下不会染色（透明的底线）。
         */
        fun softLimit(x: Float): Float {
            val ax = abs(x)
            if (ax <= 0.7f) return x
            val sign = if (x < 0f) -1f else 1f
            return sign * (0.7f + 0.3f * tanh((ax - 0.7f) / 0.3f))
        }
    }
}
