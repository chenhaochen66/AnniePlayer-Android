package com.annieplayer.annie_music.kt.core.audio.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * 淡入 —— 消掉"起播/切歌/seek 那一下"的咔哒。
 *
 * 两种时长，按触发点区分：
 *
 * | 触发 | 时长 | 为什么是这个数 |
 * |---|---|---|
 * | 换曲（`onConfigure`） | **30 ms** | 只够盖住波形不连续那一下，听不出"渐入" |
 * | seek（`flush`） | **80 ms** | 跳转点两侧波形无关，太短会听见"哒" |
 *
 * ## 为什么只有淡入，没有淡出
 *
 * 淡出需要"暂停之后还继续出声"—— 而 Media3 一旦 `pause()` 就不再交数据，
 * 处理器链上已经没有任何可乘的样本。真正做淡出必须在下游留一个 T 毫秒的预读并延迟静音，
 * 那是 sink 层的活（独占通路的 `fill()` 也是同一类问题）。这里**不假装能做**。
 *
 * ## raw DSD 不经过本类
 *
 * DoP / Native 的字节是帧，乘任何系数都会毁掉帧标记。旁路是在 `PcmAudioSink.configure`
 * 里做的（`rawPassthrough()` 为真时整条处理器链不启用），所以这里不需要再判一次。
 */
class FadeAudioProcessor : BaseAudioProcessor() {

    private companion object {
        const val TRACK_FADE_MS = 30
        const val SEEK_FADE_MS = 80
    }

    /** 剩余待淡入的帧数；0 = 已到满幅 */
    private var remaining = 0
    private var total = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        total = framesOf(TRACK_FADE_MS, inputAudioFormat.sampleRate)
        remaining = total
        return inputAudioFormat
    }

    /**
     * seek 之后重新起淡入（80 ms）。
     *
     * 为什么是独立入口而不是重写 `flush()`：`BaseAudioProcessor.flush()` 是 final，
     * 而且 Media3 在 `flush()` 之后**不会**再调 `configure()`（格式没变），
     * 所以只能在 `PcmAudioSink.flush()` 里显式喊一声。
     */
    fun armSeekFade() {
        val rate = inputAudioFormat.sampleRate
        if (rate <= 0) return
        total = framesOf(SEEK_FADE_MS, rate)
        remaining = total
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        val bytesPerSample = bytesPerSample(inputAudioFormat.encoding)
        val channels = inputAudioFormat.channelCount
        if (bytesPerSample == 0 || channels == 0) {
            // 未知编码：原样透传（宁可没有淡入，也不能把样本解释错）
            out.put(inputBuffer)
            out.flip()
            return
        }
        val bytesPerFrame = bytesPerSample * channels
        while (inputBuffer.remaining() >= bytesPerFrame) {
            val gain = if (remaining > 0) {
                // 从 0 线性升到 1：线性包络在 30–80 ms 这个量级上听感足够，
                // 用等功率曲线反而会让开头更"软"
                1f - remaining.toFloat() / total
            } else {
                1f
            }
            for (c in 0 until channels) {
                writeGain(out, inputBuffer, bytesPerSample, inputAudioFormat.encoding, gain)
            }
            if (remaining > 0) remaining--
        }
        // 不足一帧的尾巴原样搬过去（不应该出现，但绝不能丢字节）
        while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
        out.flip()
    }

    private fun framesOf(ms: Int, sampleRate: Int): Int {
        if (sampleRate <= 0) return 0
        return (sampleRate.toLong() * ms / 1000L).toInt().coerceAtLeast(1)
    }

    private fun bytesPerSample(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
        else -> 0
    }

    /** 读一个样本、乘增益、写回去（`in` 与 `out` 是两块不同的缓冲） */
    private fun writeGain(
        out: ByteBuffer,
        input: ByteBuffer,
        bytesPerSample: Int,
        encoding: Int,
        gain: Float,
    ) {
        when (encoding) {
            C.ENCODING_PCM_FLOAT -> {
                val v = input.float
                out.putFloat(v * gain)
            }
            C.ENCODING_PCM_16BIT -> {
                val v = input.short.toInt()
                out.putShort((v * gain).toInt().toShort())
            }
            C.ENCODING_PCM_24BIT -> {
                // 3 字节小端
                val b0 = input.get().toInt() and 0xFF
                val b1 = input.get().toInt() and 0xFF
                val b2 = input.get().toInt()
                var v = (b0 or (b1 shl 8) or (b2 shl 16))
                // 24 位符号扩展
                if (v and 0x800000 != 0) v = v or 0xFF000000.toInt()
                val scaled = (v * gain).toInt()
                out.put((scaled and 0xFF).toByte())
                out.put((scaled shr 8 and 0xFF).toByte())
                out.put((scaled shr 16 and 0xFF).toByte())
            }
            else -> {
                val v = input.int
                out.putInt((v.toDouble() * gain).toInt())
            }
        }
    }
}
