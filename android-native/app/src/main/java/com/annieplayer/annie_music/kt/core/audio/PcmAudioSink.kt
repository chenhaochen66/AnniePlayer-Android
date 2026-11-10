package com.annieplayer.annie_music.kt.core.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.annieplayer.annie_music.kt.core.audio.dsp.FadeAudioProcessor
import com.annieplayer.annie_music.kt.core.audio.dsp.PcmResamplerAudioProcessor
import com.annieplayer.annie_music.kt.core.audio.dsp.TuningAudioProcessor
import com.annieplayer.annie_music.kt.core.diagnostics.RuntimeLog
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
    private val resampler: PcmResamplerAudioProcessor,
    /**
     * 淡入：换曲 30 ms、seek 80 ms。放在链的**最后**——它必须作用在最终样本上，
     * 否则前面的 EQ / 重采样会把它算进滤波器状态里（那会让开头那 30 ms 的频响失准）。
     */
    private val fade: FadeAudioProcessor = FadeAudioProcessor(),
    /**
     * DSD 直出：进来的「PCM」其实是已经打包好的 DoP / raw DSD 帧。
     *
     * 此时**任何**处理都会毁掉数据 —— r8brain 会重采样、EQ 会改最低位，
     * 而 DoP 的帧标记就在这些位里，坏一位 DAC 就退回 PCM 出噪声。
     * 所以这条通路上处理器链必须完全不参与，连 `configure` 都不调用（避免它按
     * 「176.4k → 目标率」插一次转换）。
     */
    private val rawPassthrough: () -> Boolean = { false },
    /**
     * 把**源**位深（处理器链之前那个格式的位深）报给出口。
     *
     * 出口要用它选「装得下且最窄」的端点格式：调音时我方交 float（32），但内容可能只有
     * 24 bit —— 按交付容器挑就会永远落在 32 bit subslot 上，位深显示随之从 24 跳成 32。
     */
    private val onSourceBits: (Int) -> Unit = {},
) : ForwardingAudioSink(delegate) {

    /**
     * 处理器顺序：**先 [resampler]（率 / 位深），后 [tuning]（EQ）**。
     *
     * 这个顺序不是随手排的 —— 反过来会**每次处理都掉一次量化**：
     * `TuningAudioProcessor` 的输入输出是**同一种编码**（native 侧 `processBytes` 只收一个
     * `encoding`），所以它在 16 Bit 输入上做完滤波要把结果写回 16 Bit，再上转 float 交出去。
     * 那一次 16 Bit 舍入没有抖动、又不该发生 —— 解码器给的 16 Bit 明明是精确可逆地
     * 放进 float 的。先转 float 之后，EQ 全程落在 float 域，链上只剩 sink 出口那一次量化。
     *
     * 数学上也更对：PEQ 是线性时不变系统，与 SRC 可交换；放在转率之后，
     * 系数直接按最终率算，不用在中间率上近似。
     */
    private val processors = arrayOf<AudioProcessor>(resampler, tuning, fade)
    private var dspEnabled = false

    /**
     * 这一次 `configure` 里**出口被声明的格式**，以及**进来的原始格式**。
     *
     * 两者的差异就是"必须有人转换"的判据。为什么必须在 `configure` 里记下来、而不是在
     * `handleBuffer` 里现问 `processor.isActive`：`configureFor()` 是播放层从主线程调的，
     * 它会在 `onConfigure` 之前就把 `_active` 改掉（`PcmResamplerAudioProcessor` 里有
     * 明确注释）。于是存在一个窗口 —— 出口按"已转换"配置，处理器却已经不再转换。
     * 那一刻把原始字节送下去就是位深 / 采样率全错，听感是**满幅噪声**。
     *
     * 典型触发：从需要重采样的一首（44.1k → 48k 链路）切到本身就等于链路率的一首（48k），
     * 两首的目标率相同，最容易踩到那一下。
     */
    private var advertisedRate = 0
    private var advertisedEncoding = C.ENCODING_INVALID
    private var inputRate = 0
    private var inputEncoding = C.ENCODING_INVALID

    private var convertDropLogged = false

    /** 已处理、但下游还没完全接收的数据（下游 buffer 满时保留，避免丢样本） */
    private var pending: ByteBuffer? = null
    private var pendingPtsUs = 0L
    private var pendingCount = 0

    override fun configure(format: Format, bufferSize: Int, outputChannels: IntArray?) {
        pending = null
        // 源位深必须在处理器配置**之前**读，之后就只剩我方交付的容器宽度了。
        // 非 PCM 一律归零：留上一次的值会让出口按错误的位宽挑 alt。
        onSourceBits(
            if (format.sampleMimeType == MimeTypes.AUDIO_RAW) bitsOf(format.pcmEncoding) else 0,
        )
        // DSD 直出优先：这条判断必须在处理器配置之前，两条通路互斥
        dspEnabled = format.sampleMimeType == MimeTypes.AUDIO_RAW && !rawPassthrough()
        convertDropLogged = false
        inputRate = format.sampleRate
        inputEncoding = format.pcmEncoding
        advertisedRate = format.sampleRate
        advertisedEncoding = format.pcmEncoding
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

        advertisedRate = pcm.sampleRate
        advertisedEncoding = pcm.encoding
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
        // **直通（offload）已整条删除**：这里当年有一句"offload + AAC 补 ADTS 头"，
        // 因为 `AudioTrack` 的 `ENCODING_AAC_LC` offload 通道按 ADTS 解析、而 fMP4/HLS
        // 里的 AAC 是 raw access unit。现在我们从不请求 offload（
        // `TrackSelectionParameters.AudioOffloadPreferences` 保持默认 DISABLED），
        // 压缩帧一律由自研 ffmpeg 解码，`handleBuffer` 只会收到 PCM —— 那段补头逻辑没有入口了。
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
        // 出口声明的格式与进来的格式不同 ⇒ 必须有重采样器产出，且它这一轮的产出契约
        // 必须与出口声明一致。任一不成立就不能把字节送下去：出口会按错的位深 / 率
        // 去解释 —— 那是满幅噪声，不是"音质变差"。宁可丢这一块（短暂静音）。
        val mustConvert = advertisedRate != inputRate || advertisedEncoding != inputEncoding
        // 契约 = "它这一轮会产出什么"，只有激活时才有意义
        val contractOk = resampler.isActive &&
            resampler.configuredOutputRate == advertisedRate &&
            resampler.configuredOutputEncoding == advertisedEncoding
        if (mustConvert && !contractOk) {
            if (!convertDropLogged) {
                convertDropLogged = true
                RuntimeLog.record(
                    "SRC",
                    "出口已宣告转换但处理器契约不符（" +
                        "active=${resampler.isActive} " +
                        "产出=${resampler.configuredOutputRate}Hz/" +
                        "${resampler.configuredOutputEncoding} " +
                        "声明=${advertisedRate}Hz/$advertisedEncoding），丢弃缓冲以免送噪声",
                )
            }
            return true
        }
        if (contractOk) {
            // 契约一致就跑。注意 `mustConvert == false` 也可能是对的 —— 只做低通、
            // 格式不变的那一档就是这样，它照样必须跑。
            resampler.queueInput(current)
            current = resampler.output
        }
        // 反过来：`mustConvert == false` 且契约不符，说明重采样器是上一首留下的状态，
        // 此刻出口要的正是**原始字节** —— 不跑它才是对的。
        //
        // 调音处理器不改变格式，所以它不受这道闸门约束 —— 挡掉它等于"不需要转率时
        // EQ 就失效"，那是另一类回归。
        if (tuning.isActive) {
            tuning.queueInput(current)
            current = tuning.output
        }
        // 淡入放在最后：它作用在最终样本上（raw 通路在 `configure` 里整链不启用）
        if (fade.isActive) {
            fade.queueInput(current)
            current = fade.output
        }
        // 处理器吃掉输入但产出为空：向 Media3 报"已消费"（输入确实被消费了）。
        // 正常不会发生 —— 处理器 onConfigure 返回 NOT_SET 时本就走不到这里。
        if (!current.hasRemaining()) return true

        if (!super.handleBuffer(current, presentationTimeUs, encodedAccessUnitCount)) {
            // **存引用是安全的**，不需要拷贝 —— 这条我专门验证过两次：
            //
            // Media3 的契约是「返回 false = 没接收，调用方用**同一个 buffer** 重试」，
            // 重试期间**不会再调用 `queueInput`**，所以 `current`（处理器的 outputBuffer）
            // 在那段时间里不会被覆盖。之前我以为它会，加了拷贝，结果两次都直接把播放搞成
            // `ERROR position=69`（第一块就崩）—— 拷贝本身引入了新问题，得不偿失。
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
        // seek 之后补一次 80 ms 淡入（`flush()` 之后 Media3 不会再调 configure）
        fade.armSeekFade()
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

/**
 * Media3 的 PCM 编码 → 位深。除 float 外都是整数容器。
 *
 * 未知编码返回 0（= 不表态），让出口退回按包长挑 —— 猜错位深会真的截断数据。
 */
internal fun bitsOf(encoding: Int): Int = when (encoding) {
    C.ENCODING_PCM_16BIT -> 16
    C.ENCODING_PCM_24BIT -> 24
    C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
    else -> 0
}

/**
 * 把 raw AAC access unit 包成 ADTS 帧（7 字节头 + 原数据）。
 *
 * 返回 `null` 表示**不需要/不能包**，调用方应原样透传：
 * - 已经是 ADTS（前两字节是 `0xFFF` 同步字）→ 不重复加
 * - 采样率不在 ADTS 频率表里 → 无法表达，放弃包装
 *
 * `buffer` 不会被消费（用 `duplicate()` 拷贝），所以下游拒收重试时仍然安全。
 */
private fun withAdts(buffer: ByteBuffer, sampleRate: Int, channels: Int): ByteBuffer? {
    val len = buffer.remaining()
    if (len <= 1) return null
    val pos = buffer.position()
    val b0 = buffer.get(pos).toInt() and 0xFF
    val b1 = buffer.get(pos + 1).toInt() and 0xFF
    // 已有 ADTS 同步字（12 位全 1）→ 本来就是 ADTS，别加第二层
    if (b0 == 0xFF && (b1 and 0xF0) == 0xF0) return null

    val freqIdx = ADTS_FREQ_INDEX.indexOf(sampleRate)
    if (freqIdx < 0) return null
    val ch = channels.coerceIn(1, 7)
    val frameLen = len + 7
    val header = ByteArray(7)
    header[0] = 0xFF.toByte()
    header[1] = 0xF1.toByte()                                     // MPEG-4 / layer 0 / no CRC
    header[2] = (((AAC_LC_PROFILE - 1) shl 6) or (freqIdx shl 2) or (ch shr 2)) .toByte()
    header[3] = (((ch and 0x3) shl 6) or ((frameLen shr 11) and 0x3)).toByte()
    header[4] = ((frameLen shr 3) and 0xFF).toByte()
    header[5] = (((frameLen and 0x7) shl 5) or 0x1F).toByte()      // buffer fullness = 0x7FF
    header[6] = 0xFC.toByte()                                      // fullness 低位 + blocks=0

    val out = ByteBuffer.allocateDirect(frameLen).order(buffer.order())
    out.put(header)
    out.put(buffer.duplicate())
    out.flip()
    return out
}

/** ADTS `samplingFrequencyIndex` 表（0~12） */
private val ADTS_FREQ_INDEX = intArrayOf(
    96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000,
    22_050, 16_000, 12_000, 11_025, 8_000, 7_350,
)

/** AAC-LC 的 audioObjectType = 2（ADTS 里存的是它减 1） */
private const val AAC_LC_PROFILE = 2
