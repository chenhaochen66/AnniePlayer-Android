package com.annieplayer.annie_music.kt.core.audio

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.annieplayer.annie_music.kt.core.audio.dsp.SincResamplerAudioProcessor
import com.annieplayer.annie_music.kt.core.audio.dsp.TuningAudioProcessor
import java.nio.ByteBuffer

/**
 * 调音/重采样放在 [AudioSink] 之前，下游仍用 Media3 的 DefaultAudioSink：
 *
 * - 时钟、位置、暂停、seek、切歌全部复用 DefaultAudioSink 的 AudioTrackPositionTracker；
 * - 下游配置成 `enableFloatOutput=true`，24bit/float 会被转成 float 输出，不会走 ToInt16；
 * - 自定义处理器链不再交给 DefaultAudioSink（它的 float 分支会跳过）。
 */
@OptIn(UnstableApi::class)
class PcmAudioSink(
    delegate: AudioSink,
    private val tuning: TuningAudioProcessor,
    private val resampler: SincResamplerAudioProcessor,
) : ForwardingAudioSink(delegate) {

    private val processors = arrayOf<AudioProcessor>(tuning, resampler)
    private var dspEnabled = false

    /** 已处理、但下游还没完全接收的数据（下游 buffer 满时保留，避免丢样本） */
    private var pending: ByteBuffer? = null
    private var pendingPtsUs = 0L
    private var pendingCount = 0

    override fun configure(format: Format, bufferSize: Int, outputChannels: IntArray?) {
        pending = null
        dspEnabled = format.sampleMimeType == MimeTypes.AUDIO_RAW
        if (!dspEnabled) {
            processors.forEach { it.reset() }
            super.configure(format, bufferSize, outputChannels)
            return
        }

        var pcm = AudioProcessor.AudioFormat(
            format.sampleRate,
            format.channelCount,
            format.pcmEncoding,
        )
        try {
            for (processor in processors) {
                val out = processor.configure(pcm)
                if (out != AudioProcessor.AudioFormat.NOT_SET) pcm = out
            }
        } catch (_: Throwable) {
            dspEnabled = false
            processors.forEach { it.reset() }
            super.configure(format, bufferSize, outputChannels)
            return
        }

        val processed = format.buildUpon()
            .setPcmEncoding(pcm.encoding)
            .setSampleRate(pcm.sampleRate)
            .setChannelCount(pcm.channelCount)
            .build()
        super.configure(processed, bufferSize, outputChannels)
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        if (!dspEnabled) {
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }

        // 先尽量把上次的尾巴交给下游
        pending?.let { tail ->
            if (!super.handleBuffer(tail, pendingPtsUs, pendingCount)) return false
            pending = null
        }

        // 下游还没接收完，先把当前输入留着（Media3 会用同一个 buffer 重试）
        if (!buffer.hasRemaining()) return true

        var current = buffer
        for (processor in processors) {
            if (!processor.isActive) continue
            processor.queueInput(current)
            current = processor.output
        }
        if (!current.hasRemaining()) return true

        if (!super.handleBuffer(current, presentationTimeUs, encodedAccessUnitCount)) {
            pending = current
            pendingPtsUs = presentationTimeUs
            pendingCount = encodedAccessUnitCount
            return false
        }
        return true
    }

    override fun playToEndOfStream() {
        pending?.let {
            super.handleBuffer(it, pendingPtsUs, pendingCount)
            pending = null
        }
        super.playToEndOfStream()
    }

    override fun flush() {
        pending = null
        processors.forEach { it.flush() }
        super.flush()
    }

    override fun reset() {
        pending = null
        processors.forEach { it.reset() }
        super.reset()
    }

    override fun release() {
        pending = null
        super.release()
    }
}
