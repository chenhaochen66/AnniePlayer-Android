package com.annieplayer.annie_music.kt.core.audio

import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer

/**
 * **播放器的音频出口** —— Media3 的 `AudioSink` 唯一实现。
 *
 * 它只做三件事：**选后端**（[configure]）、把 Media3 的 setter 转给当前后端、
 * 把后端的状态转回 Media3。写路径、时钟、静音、结束判定全在 [OutputPort] 的实现里：
 *
 * | 后端 | 实现 | 位置/时钟 |
 * |---|---|---|
 * | USB 直连（libusb UAC2） | [ExclusiveOutput] | 我方环形缓冲 + `framesRead` |
 * | AAudio 独占（Oboe） | [ExclusiveOutput] | 同上（HAL 回调拉模式） |
 * | 系统混音（AudioTrack） | [SystemOutput] | `DefaultAudioSink` 自己的 tracker |
 *
 * 命名说明：以前叫 `AaudioSink`，但它管的是三条通路，不只是 AAudio —— 那个名字让
 * "系统通路在别处"成了默认假设，于是每个方法开头都要写一遍 `systemSink?.let { ...; return }`，
 * 写路径与时钟各有两份。现在只剩 [port] 一个入口。
 *
 * ## 绝不中途切换后端
 *
 * 后端在 `configure()` 里一次选定，整首不换。运行中失败（设备被抢 / 拔出）不走"偷偷切到
 * 系统混音"：[onEngineFailure] 通知上游重建音源，那时该率已被标记不可独占，
 * 下一次 `configure()` 自然会选中系统后端。
 *
 * ## raw DSD 不许落到系统通路
 *
 * DoP / Native 送的是帧，`DefaultAudioSink` 内部 24bit → float 的处理器会把 0x05/0xFA
 * 帧标记除成 ~1e-7 的直流，DAC 再也认不出合法序列。所以独占开不出来时
 * [ExclusiveOutput] 直接给 [ExclusiveOpenResult.ForceFail]，这里不回退、只上报失败。
 */
@OptIn(UnstableApi::class)
internal class AnnieAudioSink(
    /** 系统播放通路（`DefaultAudioSink`），**每次 configure 现场新建** */
    private val systemSinkFactory: () -> AudioSink,
    /** 现在该不该试独占（透明模式才试） */
    exclusiveWanted: () -> Boolean = { true },
    /** 独占中途失败 → 播放层重建音源 */
    onEngineFailure: (String, String) -> Unit = { _, _ -> },
    /** 这一首选哪个直出内核；null 也退到 AAudio */
    engineSelector: () -> PcmEngine? = { OboeEngine },
    /** 已经开着的引擎还算不算数（通路没被切走） */
    engineStillValid: () -> Boolean = { false },
    /** 实际生效通路 / 降级原因 / 端点位深 */
    onRouting: (OutputPath, DowngradeNotice?, Int) -> Unit = { _, _, _ -> },
    /** USB 走不成的原因 */
    private val usbUnavailable: () -> DowngradeReason = { DowngradeReason.UsbOpenFailed },
    /** 这一次 configure 要什么 */
    private val request: () -> OutputRequest = { OutputRequest.Conservative },
    /** 独占通路的线性增益 */
    private val exclusiveGain: () -> Float = { 1f },
    /** 上游此刻想不想播 */
    private val playIntent: () -> Boolean = { true },
) : AudioSink {

    private var listener: AudioSink.Listener? = null

    /** 当前后端。`null` = 这一首既没开成独占、也没建系统 sink（raw 判失败） */
    private var port: OutputPort? = null

    /** 系统后端持有的 Media3 sink；只用来转发 setter，不参与数据路径 */
    private var systemSink: AudioSink? = null

    private val exclusive = ExclusiveOutput(
        exclusiveWanted = exclusiveWanted,
        onEngineFailure = onEngineFailure,
        engineSelector = engineSelector,
        engineStillValid = engineStillValid,
        onRouting = onRouting,
        usbUnavailable = usbUnavailable,
        request = request,
        exclusiveGain = exclusiveGain,
        playIntent = playIntent,
        listenerProvider = { listener },
    )

    // ── 先记账的 setter（configure 时重放给系统 sink）──
    private var playbackParameters: PlaybackParameters = PlaybackParameters.DEFAULT
    private var skipSilence = false
    private var audioAttributes: AudioAttributes = AudioAttributes.DEFAULT
    private var volume = 1f
    private var sessionId = C.AUDIO_SESSION_ID_UNSET
    private var auxEffect = AuxEffectInfo(0, 0f)
    private var outputStreamOffsetUs = 0L

    /** Media3 是否要求过"开始播放"。系统后端的 `flush()` 要靠它补一次 `play()` */
    private var playRequested = false

    /** 这一首的**源**位深（由 `PcmAudioSink` 在处理器链之前读出来），开流时用来挑 alt */
    var wireBitsHint: Int
        get() = exclusive.wireBitsHint
        set(value) {
            exclusive.wireBitsHint = value
        }

    // ── AudioSink ──

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
        systemSink?.let { runCatching { it.setListener(listener) } }
    }

    override fun supportsFormat(format: Format): Boolean =
        systemSink?.supportsFormat(format)
            ?: (getFormatSupport(format) == AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY)

    override fun getFormatSupport(format: Format): Int {
        systemSink?.let { return it.getFormatSupport(format) }
        // 独占通路里 32 bit 有两种含义：Native DSD 的 raw word，以及真正的 32 bit 整数 PCM。
        // 两者的字节处理是同一件事（`push()` 里 int32 恒等 memcpy），所以一律放行。
        //
        // ⚠️ 曾经按"此刻是不是 Native"判定 —— 于是格式协商结果取决于一个会异步变动的
        // 意图标志：切模式瞬间可能把 32 bit 判为不支持 → 渲染器不启用该轨 →
        // `configure()` 永不执行 → **整首没声且不报错**。
        val supported = when (format.pcmEncoding) {
            C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_32BIT,
            -> true
            else -> false
        }
        return if (supported && format.sampleRate > 0 && format.channelCount in 1..8) {
            AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        } else {
            AudioSink.SINK_FORMAT_UNSUPPORTED
        }
    }

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
        systemSink?.getFormatOffloadSupport(format) ?: AudioOffloadSupport.DEFAULT_UNSUPPORTED

    override fun configure(format: Format, bufferSize: Int, outputChannels: IntArray?) {
        // 先收干净上一首的两条路
        port = null
        systemSink?.let { runCatching { it.reset() } }
        systemSink = null

        when (exclusive.configure(format)) {
            ExclusiveOpenResult.Opened -> {
                port = exclusive
                return
            }
            ExclusiveOpenResult.ForceFail -> {
                // raw 帧不许进系统通路：失败已经由 onEngineFailure 交付，这里不再造第二份事实
                port = null
                return
            }
            ExclusiveOpenResult.FallbackAllowed -> Unit
        }

        val system = systemSinkFactory()
        // 顺序照 Media3 的真实调用序（listener → attributes → params → 其余），
        // 漏一条就等于让系统 sink 少一个回调/少一个属性
        listener?.let { runCatching { system.setListener(it) } }
        runCatching { system.setAudioAttributes(audioAttributes) }
        runCatching { system.setPlaybackParameters(playbackParameters) }
        runCatching { system.setSkipSilenceEnabled(skipSilence) }
        runCatching { system.setVolume(volume) }
        if (sessionId != C.AUDIO_SESSION_ID_UNSET) runCatching { system.setAudioSessionId(sessionId) }
        runCatching { system.setAuxEffectInfo(auxEffect) }
        system.configure(format, bufferSize, outputChannels)
        systemSink = system
        port = SystemOutput(system)
        // 新建的 sink 还没被 `play()` 过。若这次 configure 发生在"本来就在播"的场景
        // （`_reloadCurrentSource()` / 设备变化），renderer 状态没变、不会再调 `play()`
        if (playRequested) system.play()
        exclusive.publishSystemFallback()
    }

    override fun play() {
        playRequested = true
        port?.play()
    }

    override fun handleDiscontinuity() {
        // 系统 sink 有自己的不连续处理（重置 tracker 与 AudioTrack 记账），必须原样转过去；
        // 独占通路的不连续就是"清环 + 重建时间轴基准"
        systemSink?.let {
            runCatching { it.handleDiscontinuity() }
            return
        }
        port?.flush(playRequested)
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean = port?.write(buffer, presentationTimeUs, encodedAccessUnitCount) ?: false

    override fun playToEndOfStream() {
        port?.playToEndOfStream()
    }

    override fun isEnded(): Boolean = port?.isEnded() ?: true

    override fun hasPendingData(): Boolean = port?.hasPendingData() ?: false

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long =
        port?.positionUs(sourceEnded) ?: AudioSink.CURRENT_POSITION_NOT_SET

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        this.playbackParameters = playbackParameters
        systemSink?.let { runCatching { it.setPlaybackParameters(playbackParameters) } }
    }

    override fun getPlaybackParameters(): PlaybackParameters =
        systemSink?.playbackParameters ?: playbackParameters

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        skipSilence = skipSilenceEnabled
        systemSink?.let { runCatching { it.setSkipSilenceEnabled(skipSilenceEnabled) } }
    }

    override fun getSkipSilenceEnabled(): Boolean = systemSink?.skipSilenceEnabled ?: skipSilence

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        this.audioAttributes = audioAttributes
        systemSink?.let { runCatching { it.setAudioAttributes(audioAttributes) } }
    }

    override fun getAudioAttributes(): AudioAttributes =
        systemSink?.audioAttributes ?: audioAttributes

    override fun setAudioSessionId(audioSessionId: Int) {
        sessionId = audioSessionId
        systemSink?.let { runCatching { it.setAudioSessionId(audioSessionId) } }
    }

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {
        auxEffect = auxEffectInfo
        systemSink?.let { runCatching { it.setAuxEffectInfo(auxEffectInfo) } }
    }

    override fun enableTunnelingV21() {
        systemSink?.let { runCatching { it.enableTunnelingV21() } }
    }

    override fun disableTunneling() {
        systemSink?.let { runCatching { it.disableTunneling() } }
    }

    override fun setOutputStreamOffsetUs(offsetUs: Long) {
        outputStreamOffsetUs = offsetUs
        exclusive.setOutputStreamOffsetUs(offsetUs)
        systemSink?.let { runCatching { it.setOutputStreamOffsetUs(offsetUs) } }
    }

    override fun setVolume(volume: Float) {
        this.volume = volume
        // 独占通路刻意不下发：透明模式不允许我方数字衰减（音量走 `ExclusiveVolume`）
        systemSink?.let { runCatching { it.setVolume(volume) } }
    }

    override fun pause() {
        playRequested = false
        port?.pause()
    }

    override fun flush() {
        // 系统后端在 `SystemOutput.flush` 里已经按 playRequested 补过 play()；
        // 独占后端自己走 `applyPlayIntentToEngine()`。这里不重复补。
        port?.flush(playRequested)
    }

    override fun reset() {
        port?.reset()
    }

    override fun release() {
        port?.release()
        port = null
        systemSink?.let { runCatching { it.release() } }
        systemSink = null
    }

    /** 仅供诊断：当前生效通路的位深（0 = 未知） */
    val endpointDepth: Int get() = exclusive.endpointDepth
}
