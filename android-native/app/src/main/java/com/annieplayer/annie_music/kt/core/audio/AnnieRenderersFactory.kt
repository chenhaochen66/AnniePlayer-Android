package com.annieplayer.annie_music.kt.core.audio

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import com.annieplayer.annie_music.kt.core.audio.dsp.PcmResamplerAudioProcessor
import com.annieplayer.annie_music.kt.core.audio.dsp.TuningAudioProcessor
import com.annieplayer.annie_music.kt.core.audio.ffmpeg.FfmpegAudioRenderer
import com.annieplayer.annie_music.kt.core.state.MoreSettings

/**
 * 自定义 RenderersFactory：DSP 放在 AudioSink 外层，时钟/位置仍交给 DefaultAudioSink。
 */
@OptIn(UnstableApi::class)
class AnnieRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    val tuning = TuningAudioProcessor()
    val resampler = PcmResamplerAudioProcessor()

    /**
     * 当前输出通路的读取器（由 `MediaPlayerBackend` 提供）。
     *
     * 只用于**状态展示**（哪条通路在响）。以前它还有一个用途：判断"能不能让位给
     * offload 直通"—— 那条功能已整条删除，现在没有"让位"这回事了。
     */
    var routeProvider: (() -> AudioRoute?)? = null

    /**
     * AAudio / USB 独占中途失败时的回调：由播放层重建音源。
     *
     * 第二个参数是**这条流开流时的传输标签**（`"native@2822400"` 这种），由 sink 在
     * `configure()` 那一刻快照下来再原样带回来 —— **不能在失败回调里现读全局量**：
     * 失败往往发生在用户已经切过模式之后，现读的会是新模式的键，
     * 于是"Native 失败"被记成"dop 失败"，用户选的 DoP 下次被当已失败直接降级。
     */
    var onAaudioFailure: ((String, String) -> Unit)? = null

    /**
     * 当前这一首的传输标签（方式@档位）。sink 只在 `configure()` 里读一次并保存，
     * 失败时把**那次的值**回传，用于按方式+档位记失败记忆。
     */
    var dsdTransportTag: (() -> String)? = null

    /** 实际生效通路 / 降级原因 / 端点位深：播放层写进 `AudioOutputStatus` */
    var onSinkRouting: ((OutputPath, DowngradeNotice?, Int) -> Unit)? = null

    /**
     * 当前这首是不是 **DSD 直出**（送的是 DoP / raw 帧，不是 PCM）。
     *
     * 是的话整条处理器链必须旁路：r8brain 会重采样、EQ 会改最低位，
     * 而 DoP 的帧标记就在那些位里。
     */
    var rawDsdActive: (() -> Boolean)? = null

    /**
     * 当前这首是不是 **Native DSD**（裸 1-bit，走设备的 raw alt）。
     *
     * 与 [rawDsdActive] 的区别：后者表示"送的不是 PCM 样本"（DoP 也算），
     * 用来旁路 DSP 链；这个只表示"要选 raw alt"（只有 Native 算）。
     * 混用会让 DoP 被塞进 raw alt，DAC 按 1-bit 解 24 bit 帧 → 不出声。
     */
    var nativeDsdActive: (() -> Boolean)? = null

    /**
     * 播放器此刻**想不想播**（`Player.playWhenReady`）。
     *
     * `AaudioSink` 用它把"用户按了暂停"和"sink 与播放器失步"分开：
     * 前者不许被自愈或故障上报拉起来，后者必须。默认视为想播（自愈保持原行为）。
     */
    var playIntent: (() -> Boolean)? = null

    init {
        setEnableAudioFloatOutput(true)
        setEnableDecoderFallback(true)
    }

    @Suppress("DEPRECATION") // setAudioCapabilities 在 Media3 1.4.1 已废弃但仍在用（见下方说明）
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        /**
         * **float 是这条链上唯一既可行又正确的选择**（结论来自实测，不是偏好）。
         *
         * 曾经试过"蓝牙下关掉 float、改交 24 Bit 整数"，因为直觉上 a2dp 是整数链路
         * （`PCM_24_BIT_PACKED`），float 进去要被 AudioFlinger 再转一次。实测把这个
         * 方案否决了，两个独立原因：
         *
         * 1. **24 Bit 整数到不了 AudioTrack**。Media3 1.4.1 的 `setAudioCapabilities`
         *    已废弃且实测不生效，`DefaultAudioSink` 用自带默认能力（本机只报 PCM_16），
         *    于是 `setEnableFloatOutput(false)` 之后 24 会被插一层 `ToInt16`
         *    —— `dumpsys media.audio_flinger` 实测 track 从 `0x5`(FLOAT) 变成
         *    **`0x1`(PCM_16)**，白掉 8 位。这比 float 差得多。
         * 2. **AudioFlinger 内部本来就是 float 混合**，出口才 `ditherAndClamp` 到
         *    `PCM_24_BIT_PACKED`。所以交 float 是**零额外损失**；交整数反而要多一次
         *    int→float 进混合、再 clamp 回去。float 才是"少转一次"的那条路。
         *
         * 所以这里不再按路由分支 —— 分支要成立，前提是 Media3 允许我们选轨道编码，
         * 而它不给这个入口。`floatOk` 因此恒等于传入值。
         */
        val floatOk = enableFloatOutput

        val silence = SilenceSkippingAudioProcessor().apply { setEnabled(false) }
        val chain = DefaultAudioSink.DefaultAudioProcessorChain(
            arrayOf<androidx.media3.common.audio.AudioProcessor>(),
            silence,
            SonicAudioProcessor(),
        )
        val base = androidx.media3.exoplayer.audio.AudioCapabilities.getCapabilities(context)
        /**
         * 向 Media3 声明本应用**真的能拿到**的编码。
         *
         * 判据分两层，实测优先：
         * 1. `AudioDirectProbe.sinkDepth()` —— 真建一次 float / 24 / 16 的 AudioTrack，
         *    谁建得出来就是谁。本机 `AudioCapabilities` 只报 PCM_16（它是个启发式），
         *    但 float 轨实际建得出来、AudioFlinger 主线程的 processing format 也是
         *    `PCM_FLOAT`；只信它会白白把整条链压到 16 bit。
         * 2. 探针还没跑过（冷启动早期）才退回 `AudioCapabilities` 的自述。
         *
         * 24bit 之所以要 `SDK_INT >= S`：`ENCODING_PCM_24BIT_PACKED` 是 API 31 才有的
         * AudioTrack 编码；声明了却建不出来会直接整首播不了（老坑）。
         */
        // 建 sink 时就把「本机 sink 到底能做到几位」实测出来（只测一次，几毫秒）。
        // 不能等后台探测：这里要拿它决定声明哪些编码，漏了 float 的话，
        // 我们的处理器即便输出 float，Media3 也会插 ToInt16 把链压回 16 bit。
        val probed = AudioDirectProbe.ensureSinkDepth()
        val canFloat = if (probed > 0) {
            probed >= 32
        } else {
            base.supportsEncoding(androidx.media3.common.C.ENCODING_PCM_FLOAT)
        }
        // 24 bit：**探测失败不能成为禁掉它的理由**。
        //
        // `ENCODING_PCM_24BIT_PACKED` 是 API 31 起的标准编码，AOSP 的 HAL 都支持；
        // 而 DSD 转 PCM 的输出正好是 24 bit —— 少了它，`MediaCodecAudioRenderer`
        // 会判定格式不支持、直接**不启用渲染器**，于是数据只进不出：
        // loader 无限预读（实测 0.34 秒读进 216 MB），Java 堆撑爆后崩在
        // ExoPlayer 的位置上报上。这是 DSD 专属崩溃的真正成因。
        val can24 = if (probed > 0) probed >= 24 else true
        val encodings = buildList {
            add(androidx.media3.common.C.ENCODING_PCM_16BIT)
            if (canFloat) add(androidx.media3.common.C.ENCODING_PCM_FLOAT)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && can24) {
                add(androidx.media3.common.C.ENCODING_PCM_24BIT)
            }
        }.toIntArray()
        val capabilities = androidx.media3.exoplayer.audio.AudioCapabilities(
            encodings,
            base.maxChannelCount.coerceAtLeast(2),
        )
        val delegateBuilder = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(floatOk)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessorChain(chain)
        // `setAudioCapabilities` 在 Media3 1.4.1 已 deprecated（编译期 warning），
        // 且实测它并没有让 float 生效 —— 真正决定 track 编码的是上游
        // `Format.pcmEncoding` + `enableFloatOutput`。所以位深由
        // `PcmResamplerAudioProcessor` 声明（蓝牙取 codec 协商值并封顶 24，其余交 float），
        // 这里仍如实上报能力，作为 Media3 选择轨编码时的上界。
        delegateBuilder.setAudioCapabilities(capabilities)
            .setAudioProcessorChain(chain)
        val delegate = delegateBuilder.build()
        // 链路（从外到内）：
        //   PcmAudioSink   SRC/位深 → 调音（**这个顺序见 `PcmAudioSink.processors`**：
        //                  先按目标位深落地，EQ 在更宽的容器里算、不额外丢位）
        //   AaudioSink / DefaultAudioSink   真正的出口
        //
        // 出口按"透明模式 + AAudio 可用"分流：
        //   · 透明 + 能独占 → `AaudioSink`（绕开 AudioFlinger mixer，无需任何系统权限）
        //   · 其余（调音 / 蓝牙 / 老系统 / 开不出独占）→ `DefaultAudioSink`（系统 mixer）
        //
        // 注意两条不变量，缺一条就回到"独占了个寂寞 + 控制迟钝"：
        // 1. 独占成立时 `OutputPlan.sampleRate = 0`（不重采样，由 AAudio 按源率开流）；
        // 2. `AaudioSink` 的写入在**独立线程 + 队列**里，`handleBuffer` 只入队。
        //
        // 直通不在这里做分流：压缩帧是否让位由 `buildAudioRenderers` 里的
        // `yieldForOffload` 决定，交货仍然是 `DefaultAudioSink.setOffloadMode(ENABLED)`。
        if ((com.annieplayer.annie_music.kt.core.audio.AudioDirectProbe.AAUDIO_SINK_ENABLED &&
                com.annieplayer.annie_music.kt.core.audio.dsp.NativeAaudio.isAvailable()) ||
            UsbAudioDirect.platformReady()
        ) {
            val aaudio = AaudioSink(
                systemSinkFactory = { delegate },
                exclusiveWanted = {
                    // 新模型：**看通路 + 看路由**（用户选了「AAudio 独占」、且当前不是蓝牙）。
                    //
                    // 为什么必须排除蓝牙：AAudio 独占是"直接连硬件"，而蓝牙根本没有
                    // mmap 端点 —— 探测矩阵测的是本机扬声器/有线的能力，插上蓝牙耳机后
                    // 照样报"可用"。不排的话每次切歌都要白试一次 open（必然失败），
                    // 再回落系统 sink，纯粹多一次失败与日志。
                    //
                    // 能不能开由 `open()` + 回读校验说了算，不在路由上做**其他**前置猜测
                    // （私有设备类型认不出来时也不会漏掉）。
                    // **只要用户不想走系统通路就该允许试独占**，不是只有选了 AAudio 才试。
                    // 以前这里写的是 `== aaudioExclusive`，于是 usbDirect 开流失败后本可以
                    // 退到 AAudio 独占兜底，却先被这一句判成 Denied，直接掉进系统 mixe r——
                    // 表现就是「选了 USB 独占、DSD 页却说需要 USB 直连」。
                    // 蓝牙仍然排除：a2dp 没有 mmap 端点，试也只会白失败一次。
                    MoreSettings.outputPath.value != OutputPath.systemMix &&
                        routeProvider?.invoke() != AudioRoute.bluetooth
                },
                // 独占中途失败（设备被抢/写不进）→ 由播放层重建音源；
                // 那时该率已被标记不可独占，下一次 configure 会选中系统引擎。
                onEngineFailure = { reason, tag -> onAaudioFailure?.invoke(reason, tag) },
                // USB 独占：拿不到会话就返回 null，`configure()` 退回 AAudio 再退系统通路
                engineSelector = {
                    if (MoreSettings.outputPath.value == OutputPath.usbDirect) {
                        UacEngine.open(context)
                    } else {
                        null
                    }
                },
                // 同规格切歌复用现有流；通路被切走时才重建
                engineStillValid = { MoreSettings.outputPath.value == OutputPath.usbDirect },
                onRouting = { path, notice, depth -> onSinkRouting?.invoke(path, notice, depth) },
                // USB 走不成的原因：本类持有 Context，能真的去看设备列表与授权
                usbUnavailable = {
                    when {
                        routeProvider?.invoke() == AudioRoute.bluetooth ->
                            DowngradeReason.BluetoothTakesOver
                        !UsbAudioDirect.platformReady() -> DowngradeReason.UsbPlatformTooOld
                        UsbAudioDirect.audioDevices(context).isEmpty() ->
                            DowngradeReason.UsbNoDevice
                        UsbAudioDirect.audioDevices(context)
                            .none { UsbAudioDirect.hasPermission(context, it) } ->
                            DowngradeReason.UsbNoPermission
                        else -> DowngradeReason.UsbOpenFailed
                    }
                },
                // 只有 Native DSD 需要设备的 raw alt；DoP 留在 PCM alt（24 bit 帧）
                nativeRawAlt = { nativeDsdActive?.invoke() == true },
                // DoP 与 Native 都不能过 mixer：回落系统通路时由 sink 显式判失败
                rawFlowActive = { rawDsdActive?.invoke() == true },
                // 独占通路的音量：绕开 mixer 后 `setVolume` 没有接收方，只能由我方施加。
                // 它自己处理"非独占 / DSD 直出 → 1f"，这里不做判断。
                exclusiveGain = { ExclusiveVolume.linear },
                // "上游还想不想播"：自愈与故障重建的唯一许可（见 `AaudioSink.playIntent`）
                playIntent = { playIntent?.invoke() == true },
                // 失败时要按"这条流是用什么方式开的"记，所以这里给的是**取值函数**，
                // 由 sink 在 configure 那一刻快照（见 `AaudioSink.openedTransportTag`）
                transportTag = { dsdTransportTag?.invoke().orEmpty() },
            )
            return PcmAudioSink(
                aaudio,
                tuning,
                resampler,
                rawPassthrough = { rawDsdActive?.invoke() == true },
                // 出口用**源**位深去挑端点格式，而不是调音后的 float 容器
                onSourceBits = { aaudio.wireBitsHint = it },
            )
        }
        return PcmAudioSink(delegate, tuning, resampler)
    }

    /**
     * FLAC 走自研软解 Renderer（列表第一位），其它格式保持 MediaCodec。
     *
     * `DecoderAudioRenderer` 的格式支持是逐曲判断的：非 FLAC 返回 unsupported，
     * ExoPlayer 会自然落到后面的 `MediaCodecAudioRenderer`，不需要额外分支。
     *
     * ## 这里**不再有**「让位给 offload」的判定
     *
     * 以前 `FfmpegAudioRenderer` 带一个 `yieldForOffload` 回调：满足条件就把压缩帧交给
     * `MediaCodecAudioRenderer` 走 offload 直通。offload 已整条删除，于是这里只有一条解码路径
     * —— **压缩帧一律由我们自己的 ffmpeg 解**。
     *
     * 这也顺手解决了一个历史问题：以前"同一首歌两次播放表现不一致"，就是因为让位与否
     * 随路由/探测状态变化（比如 MediaCodec 的 flac 解码器在 seek 之后不再出帧）。
     * 现在没有这个变量了。
     */
    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: android.os.Handler,
        eventListener: androidx.media3.exoplayer.audio.AudioRendererEventListener,
        out: ArrayList<androidx.media3.exoplayer.Renderer>,
    ) {
        out.add(FfmpegAudioRenderer(eventHandler, eventListener, audioSink))
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out,
        )
    }
}

/* AAudio 独占出口的总开关见 `AudioDirectProbe.kt` 的 `AAUDIO_SINK_ENABLED`（A/B 定责用）。 */
