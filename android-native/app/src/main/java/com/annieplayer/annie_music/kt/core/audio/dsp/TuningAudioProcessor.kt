package com.annieplayer.annie_music.kt.core.audio.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.annieplayer.annie_music.kt.core.audio.BiquadSpec
import com.annieplayer.annie_music.kt.core.audio.TuningConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class TuningAudioProcessor : BaseAudioProcessor() {

    @Volatile private var _specs: List<BiquadSpec> = emptyList()
    @Volatile private var _preampDb: Float = 0f
    @Volatile private var _limiterEnabled: Boolean = false

    @Volatile private var _nativeHandle: Long = 0L
    @Volatile private var _encoding: Int = C.ENCODING_INVALID
    @Volatile private var _channels: Int = 2
    @Volatile private var _sampleRate: Int = 0

    fun setConfig(config: TuningConfig, active: Boolean) {
        _specs = config.specs()
        _preampDb = config.preampDb() + config.loudnessDb
        _limiterEnabled = config.limiter
        if (_nativeHandle != 0L) applyNativeConfig()
    }

    private fun applyNativeConfig() {
        val handle = _nativeHandle
        if (handle == 0L || _sampleRate <= 0) return
        val count = _specs.size
        NativeDsp.configure(
            handle,
            _sampleRate,
            IntArray(count) { _specs[it].type.ordinal },
            FloatArray(count) { _specs[it].freq },
            FloatArray(count) { _specs[it].q },
            FloatArray(count) { _specs[it].gainDb },
            _preampDb,
            _limiterEnabled,
        )
    }

    /** 真的有处理要做吗（`isActive` 与 `queueInput` 必须**用同一个判据**） */
    private fun hasProcessing(): Boolean =
        _specs.isNotEmpty() || abs(_preampDb) > 0.01f || _limiterEnabled

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT &&
            encoding != C.ENCODING_PCM_24BIT &&
            encoding != C.ENCODING_PCM_FLOAT
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        _encoding = encoding
        _channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        _sampleRate = inputAudioFormat.sampleRate
        if (_nativeHandle != 0L) NativeDsp.release(_nativeHandle)
        try {
            _nativeHandle = if (NativeDsp.isAvailable()) NativeDsp.create(_channels) else 0L
            applyNativeConfig()
        } catch (_: Throwable) {
            _nativeHandle = 0L
        }
        return inputAudioFormat
    }

    /**
     * 只在**真的有处理要做**时才进链路。
     *
     * 这里曾经恒 `true`：于是透明模式（用户要求"原样送出"）也会把每一块 PCM 过一遍
     * `queueInput`（虽然只是拷贝透传），既白花一次拷贝，也让"到底是谁在吞数据"没法判断。
     * 判据与 `queueInput` 里的 `hasProcessing` 保持一致。
     */
    override fun isActive(): Boolean = hasProcessing()

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size <= 0) return
        val channels = _channels
        val bytesPerSample = when (_encoding) {
            C.ENCODING_PCM_FLOAT -> 4
            C.ENCODING_PCM_24BIT -> 3
            else -> 2
        }
        val frames = size / (bytesPerSample * channels)
        if (frames <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        val handle = _nativeHandle
        if (!hasProcessing() || handle == 0L) {
            val out = replaceOutputBuffer(size).order(ByteOrder.LITTLE_ENDIAN)
            out.put(inputBuffer)
            out.flip()
            return
        }

        // ByteArray 路径不依赖 direct buffer：Kotlin 只做一次块拷贝，
        // 解码/滤波/编码全部在 native。
        val inBytes = ByteArray(size)
        inputBuffer.get(inBytes)
        val outBytes = ByteArray(size)
        val written = NativeDsp.processBytes(handle, inBytes, outBytes, frames, _encoding)
        inputBuffer.position(inputBuffer.limit())

        val out = replaceOutputBuffer(size).order(ByteOrder.LITTLE_ENDIAN)
        if (written > 0) {
            out.put(outBytes, 0, written)
        } else {
            // native 调用失败时只做位拷贝，不回到 Kotlin DSP
            out.put(inBytes, 0, size)
        }
        out.flip()
    }

    override fun onFlush() {
        if (_nativeHandle != 0L) NativeDsp.reset(_nativeHandle)
    }

    override fun onReset() {
        if (_nativeHandle != 0L) {
            NativeDsp.release(_nativeHandle)
            _nativeHandle = 0L
        }
    }
}
