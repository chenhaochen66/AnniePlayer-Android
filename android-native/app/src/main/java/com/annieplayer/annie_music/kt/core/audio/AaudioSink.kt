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
import com.annieplayer.annie_music.kt.core.audio.dsp.NativeOboeStream
import com.annieplayer.annie_music.kt.core.audio.dsp.NativeUacStream
import com.annieplayer.annie_music.kt.core.diagnostics.RuntimeLog
import com.annieplayer.annie_music.kt.core.state.MoreSettings
import java.nio.ByteBuffer

/**
 * 透明模式第二级（Bit Perfect）的出口：**AAudio 独占流，内核是 Oboe**。
 *
 * ## 结构：两个**互斥**实例，`configure()` 一次性选定
 *
 * Media3 在播放器构建时就拿到一个 `AudioSink` 实例，而"该不该用独占"要看**这一首**的
 * 采样率 —— 所以选择只能在 `configure()` 做：
 *
 * - `configure()` 里先决定引擎（[exclusiveWanted] + 开流结果）；
 * - 选定 AAudio → 用本类的独占实现；选定系统 → **现场新建**一个 `DefaultAudioSink`
 *   （[systemSinkFactory]），并把此前所有 setter（listener / attributes / playbackParameters /
 *   skipSilence / volume / sessionId / auxEffect）**重放**给它；
 * - 之后整首只用其中一个，**绝不中途切换**。
 *
 * 运行中失败（设备被抢 / 拔出）不在这里偷偷切换：[onEngineFailure] 通知上游重建音源，
 * 那时这个率已被标记为不可独占，下一次 `configure()` 会**选中系统引擎**。
 *
 * ## 写入模型：直接塞环形缓冲（**没有写线程**）
 *
 * 内核在 native 侧（`libannieoboe.so`）：Oboe 回调每要一次数据就从环形缓冲拉一段，
 * 不够就填静音。所以这一层只做一件事 —— `handleBuffer` 把 PCM 拷进环形缓冲：
 *
 * - 塞得下 → 推进 `buffer.position`，返回 true；
 * - 塞不下（环满）→ **不推进 position**，返回 false，Media3 下一轮用同一块重试；
 * - 返回负数 → 流已废（`onErrorAfterClose` 之后一直是这个状态），上报后关流。
 *
 * **这里不再有生产线程、队列高水位、"设备不消费"看门狗、时间轴代际号**。它们存在的
 * 唯一理由是把"推模式"（`AAudioStream_write` 阻塞写）的手写时序补全；换成回调拉模式后
 * 这些都不需要：谁要数据、要多少，由 HAL 通过回调决定；流坏了有 `onErrorAfterClose`。
 * 少一层线程 = 少一次调度 + 少一次拷贝 + 少一整个竞态面。
 *
 * ## 位置上报
 *
 * 与 `DefaultAudioSink` 同构：
 *
 * ```
 * 位置 = startMediaTimeUs + framesToUs(min(已播帧, 已写帧))
 * 已播帧 = getTimestamp（硬件时钟）→ getFramesRead（HAL 取走的帧）
 * ```
 *
 * `framesRead` 在 native 侧**只统计真数据**（静音填充不算），所以暂停时它冻结 ——
 * "暂停 = 位置不动"是免费的，不需要额外状态。
 *
 * ## 刻意不做
 *
 * 音量不下发（透明模式不允许我方数字衰减）；不 offload / 不 tunneling（第一级的事）；
 * 不改速（上游 Sonic 处理）。
 */
@OptIn(UnstableApi::class)
internal class AaudioSink(
    /** 系统播放通路（`DefaultAudioSink`），**每次 configure 现场新建** */
    private val systemSinkFactory: () -> AudioSink,
    /** 现在该不该试独占（透明模式才试） */
    private val exclusiveWanted: () -> Boolean = { true },
    /**
     * 独占中途失败时通知上游（重建音源 → 下次 configure 选中系统引擎）。
     *
     * 第二个参数是这条流**开流时**的传输标签（见 [transportTag]），不是失败那一刻的全局量。
     */
    private val onEngineFailure: (String, String) -> Unit = { _, _ -> },
    /**
     * 当前这一首的传输标签（方式@档位）。只在 `configure()` 里读一次并存进
     * [openedTransportTag] —— 失败回调必须带那次的值，否则一次模式切换就会让
     * "Native 失败"被记成"dop 失败"，用户选的 DoP 下次被当已失败直接降级。
     */
    private val transportTag: () -> String = { "" },
    /** 这一首选哪个直出内核；null 也退到 AAudio */
    private val engineSelector: () -> PcmEngine? = { OboeEngine },
    /** 已经开着的那个引擎现在还算不算数（通路没被切走）—— 决定切歌能不能复用流 */
    private val engineStillValid: () -> Boolean = { false },
    /**
     * 实际生效通路 / 降级原因上报（写进 `AudioOutputStatus`）。
     * 只在**发生变化**时调 —— 它在 `configure()` 里，每次切歌都会经过。
     */
    private val onRouting: (OutputPath, DowngradeNotice?, Int) -> Unit = { _, _, _ -> },
    /** USB 走不成的原因；由宿主按当前设备状态回答（本类拿不到 `Context`） */
    private val usbUnavailable: () -> DowngradeReason = { DowngradeReason.UsbOpenFailed },
    /**
     * 这一首走 **Native DSD**（裸 1-bit，32 bit word）—— 只有它需要设备的 raw alt。
     *
     * ⚠️ 别把 DoP 也算进来。DoP 的帧是**标准 24 bit PCM**，只是内容被当成标记+bit 用；
     * 它必须走 PCM alt。把它送进 raw alt，设备会按 1-bit 去解 24 bit 数据，结果是不出声
     * 或满幅噪声。两者的共同点只有一条：DSP 必须旁路（见 `PcmAudioSink.rawPassthrough`），
     * 而那是另一个开关。
     */
    private val nativeRawAlt: () -> Boolean = { false },

    /**
     * 这一首送的是 DoP / raw DSD 帧（**任何** PCM 转换都会毁掉它）。
     *
     * 与 [nativeRawAlt] 的区别：这个表示"数据不能过 mixer"（DoP 也算），
     * 用来在回落到系统通路时**显式判失败**，而不是把帧硬送出去。
     */
    private val rawFlowActive: () -> Boolean = { false },

    /**
     * 这一首要施加的**线性增益**（独占通路的唯一音量源）。
     *
     * 由 `ExclusiveVolume` 提供：非独占 / DSD 直出时它自己返回 1f。
     * 与 Media3 的 `setVolume()` 无关 —— 那个值在独占通路上没有接收方
     * （`AudioTrack.setVolume` 的音量由 mixer 施加，而我们绕开了 mixer）。
     */
    private val exclusiveGain: () -> Float = { 1f },

    /**
     * 上游此刻**想不想播**（`Player.playWhenReady`）。
     *
     * 与 [playRequested] 不是一回事：那个是"Media3 调过 `play()` 之后没再调 `pause()`"，
     * 会因 focus 抖动与 sink 失步。这个直接读播放器自己的意图。
     *
     * `playRequested` 为 false 时**只有它**能证明"不是用户按了暂停"——见 [handleBuffer] 的
     * 自愈与"流已关闭"的重建上报，两处都必须先问它。
     */
    private val playIntent: () -> Boolean = { true },
) : AudioSink {

    // ── 先记账的 setter（configure 时重放到选中的引擎）──
    private var listener: AudioSink.Listener? = null
    private var playbackParameters: PlaybackParameters = PlaybackParameters.DEFAULT
    private var skipSilence = false
    private var audioAttributes: AudioAttributes = AudioAttributes.DEFAULT
    private var volume = 1f
    private var sessionId = C.AUDIO_SESSION_ID_UNSET
    private var auxEffect = AuxEffectInfo(0, 0f)

    /** 选中的系统引擎；非 null = 这一首走系统通路，本类的独占实现完全不参与 */
    private var systemSink: AudioSink? = null

    /** 这一首的直出内核（AAudio 或 USB），`configure()` 里一次性选定 */
    private var engine: PcmEngine = OboeEngine

    // ── 独占实现的状态 ──
    /** 句柄跨线程读（播放线程取位置、配置线程关闭），必须 volatile */
    @Volatile private var handle: Long = 0L
    private var sampleRate = 0
    private var channelCount = 0
    private var bytesPerFrame = 0
    /** 本流的 PCM 位深（16/24/32），seek 重建流时要用它重开 */
    private var pcmDepth = 32
    /** 本流的 PCM 编码（`C.ENCODING_PCM_*`）——32 位下 float 与 int 必须分开 */
    private var pcmEncoding = C.ENCODING_PCM_16BIT

    /** 上游"想不想播"；与 native 侧的静音开关对应 */
    @Volatile private var playRequested = false

    /**
     * 连续收到"数据还在来、但 `playRequested` 为 false"的块数（见 `handleBuffer` 的自愈）。
     *
     * 真正暂停时 Media3 还会交出手上那一块，所以要求连续两块才认。
     */
    private var stalePauseBuffers = 0

    /** 首次观察到"流已关闭但数据还在来"的时刻；0 = 没在观察（见 `handleBuffer`） */
    private var deadSinceMs = 0L

    /** 最近一次推给 native 的软件增益；只在变化时下发（见 `handleBuffer`） */
    @Volatile private var pushedGain = 1f

    /**
     * 这条流**开流时**的传输标签（`方式@档位`），在 `configure()` 里快照一次。
     *
     * 失败必须按它回报，不能现读全局量：失败往往发生在用户切过模式之后，
     * 现读会把"Native 失败"记成"dop 失败"，用户选的 DoP 下次被判已失败直接降级。
     */
    @Volatile private var openedTransportTag: String = ""

    /** 是否已经真正写出过至少一帧（`DefaultAudioSink` 的 `startMediaTimeUsNeedsInit` 等价物） */
    private var firstWriteDone = false

    /** 上游是否已宣告流结束（`DefaultAudioSink` 的 `handledEndOfStream`） */
    private var handledEndOfStream = false

    /** 一条流只上报一次失败：否则每个 buffer 都会重建一次音源 */
    private var failureReported = false

    /** 上一次上报的生效通路 / 降级原因（只在变化时上报，避免每首歌都推一次状态） */
    private var reportedPath: OutputPath? = null
    private var reportedNotice: DowngradeNotice? = null

    /**
     * 独占流在**端点上**真正跑的位深（USB = alt setting 的 subslot，AAudio = HAL 回读格式）。
     *
     * 与我方交付的容器不同：调音时交 float（32），而端点写的是 `S24_3LE`（24）。
     * 播放页的位深只有一个槽位、约定写终点，所以它必须来自这里而不是 `pcmDepth`。
     */
    private var streamDepth: Int = 0

    /**
     * 当前这条流建在 **raw DSD alt** 上（Native DSD）。
     *
     * 复用/重建流时必须比对它：raw 与 PCM 的 alt 是两套互斥的 alt setting，
     * 率恰好相同的两种 DSD 档位（如 DSD128-DoP 与 DSD256-Native 都是 352800）
     * 会同时命中率+声道，一旦复用就是把 32 bit DSD word 塞进 PCM alt —— 满幅噪声。
     */
    private var streamRaw = false

    /**
     * 这一首**源**位深（由 `PcmAudioSink` 在处理器链之前读出来）。
     *
     * 只在**开流**时用：native 侧按它选一条"装得下"的 alt，而门槛固定是 24 位
     * （16 / 24 bit 内容都能精确落进去）。alt 一经选定整个会话不再变 ——
     * 换曲目位深不会引起重建，否则就要 `alt 0 → alt N`，设备会重建内部通道，
     * 听感就是"切歌时一路先哑一下"。
     */
    var wireBitsHint: Int = 0

    private var positionUs = 0L
    private var lastStatsMs = 0L

    /**
     * 本流**我方实际塞进环形缓冲**的帧数（自己数，不问 native）。
     *
     * `playedFramesNow()` 是"设备取走"的帧数，两者之差就是"还有多少没播"。这个差值
     * 直接驱动 [hasPendingData]，也是位置封顶的依据（位置绝不能超前于已写帧）。
     */
    private var writtenFramesByUs = 0L
    private var timestampBaseFrames = 0L

    /** `getFramesRead` 的基准（flush 后 native 计数不归零，只能靠减法对齐时间轴） */
    private var readBaseFrames = 0L

    /**
     * 停滞判定的两个记账值 —— **只在写不进去时才用得上**。
     *
     * 为什么需要它：`hasPendingData()` 的判据是 `written > played`，而 `played` 来自
     * Oboe 回调累加的 `framesRead`。如果 HAL 只是**停止喂数据**（流仍然活着、
     * `onErrorAfterClose` 也不触发），`played` 就冻结，`written > played` 永久成立
     * ⇒ `isEnded()` 永远 false ⇒ **播到末尾不循环、也不切歌**。
     *
     * 以前这是手写写线程里那个 1.5s 看门狗的职责；改成回调拉模式后删掉了它，
     * 结果把这条唯一的兜底也删了。现在把它压缩成"只在 `write` 返回 0 时检查"，
     * 正常播放完全不参与（没有轮询、没有额外线程）。
     */
    private var lastReadFrames = 0L
    private var lastConsumeMs = 0L

    /**
     * 这道流的第一帧在**媒体时间轴**上的位置（`AudioSink.setOutputStreamOffsetUs`）。
     *
     * ⚠️ 它是 `AudioSink` 的 **default 方法** —— 不实现照样编译通过、运行不报错，
     * 所以之前一直被静默丢弃。后果是致命的：
     * Media3 把 `rendererPositionUs` 换算成媒体时间时要减掉 `startPositionUs`（其中含这个
     * offset），而我们的返回值里没有 offset，两边就差了整整一个 offset。
     *
     * 本机实测 offset ≈ **1e12 µs（= 1e9 ms）**，于是 `player.currentPosition` 变成
     * `我方位置 − 1e12` 的天文负数，一连串连锁反应：
     * - 进度条拿到负数 → clamp 成 0 → "进度条不动"；
     * - `bufferedDurationUs = bufferedPositionUs − periodPositionUs` 被算成无穷大 → 加载停止；
     * - renderer 认为"还没到该输出的时间" → **停止喂数据** → 播放彻底停住。
     *
     * 修法就是照 `DefaultAudioSink` 的做法：存下来，并在对外位置上加回去。
     */
    @Volatile private var outputStreamOffsetUs = 0L

    /**
     * 本流**第一块真正塞进设备**的 PCM 的媒体时间（`DefaultAudioSink.startMediaTimeUs` 的等价物）。
     *
     * 没有它，位置就是"从 0 数帧"，而 Media3 那边是
     * `periodPositionUs = rendererPositionUs − startPositionUs`：seek 之后
     * `startPositionUs` 变成新目标（30s），我们却从 0 报 → 差值 −30s →
     * 进度条跳回原处、`bufferedDuration` 算成异常值 → 随后停止、不切歌。
     *
     * `C.TIME_UNSET` = 还没建立（此时对外报 `CURRENT_POSITION_NOT_SET`）。
     */
    @Volatile private var startMediaTimeUs = C.TIME_UNSET

    // ── AudioSink ────────────────────────────────────────────────────

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
        systemSink?.let { runCatching { it.setListener(listener) } }
    }

    override fun supportsFormat(format: Format): Boolean =
        systemSink?.supportsFormat(format) ?: (getFormatSupport(format) == AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY)

    override fun getFormatSupport(format: Format): Int {
        systemSink?.let { return it.getFormatSupport(format) }
        val supported = when (format.pcmEncoding) {
            C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT -> true
            // 32 bit 有两种含义：Native DSD 的 raw word（一个 word 装 32 个 1-bit 样本），
            // 以及真正的 32 bit 整数 PCM。两者的字节处理是同一件事（`fill()` 里 int32
            // 是恒等 memcpy 到 subslot），所以这里**一律放行**。
            //
            // 曾经写成 `nativeRawAlt()`（判据是"此刻是不是 Native"），理由是"别把普通
            // 32bit PCM 吸进独占路径"。那个理由站不住：走哪条路是 `exclusiveWanted`
            // （输出通路设置）与 verdict 决定的，与格式支持无关。而代价非常实在 ——
            // 它让**格式协商结果取决于一个会异步变动的意图标志**：
            //   `_dsdTransport` 在 `_setSource`（主线程，立即生效）里改写，
            //   而 `getFormatSupport` 在播放线程上、**稍后**才被问。
            // 快速切 DoP ↔ Native 时顺序可能变成：切到 Native（标志= native）→ 又切回
            // DoP（标志= dop）→ 这时才轮到 Native 那条 source 协商 → 32 bit 判为不支持
            // → `MediaCodecAudioRenderer` 不启用该轨 → `configure()` 永不执行 →
            // **整首没声音且不报错**，只能再切一次模式才恢复。
            C.ENCODING_PCM_32BIT -> true
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

    /**
     * 位置 —— 与 `DefaultAudioSink` 同构：
     *
     * ```java
     * if (!initialized || startMediaTimeUsNeedsInit) return CURRENT_POSITION_NOT_SET;
     * return startMediaTimeUs + framesToDuration(已播帧);
     * ```
     *
     * 三条要点（前两条之前违反过，第三条是踩坑后才补上的）：
     * 1. 首次写入之前返回 `CURRENT_POSITION_NOT_SET`；
     * 2. 位置以**已播帧**为准（不能用"已写帧"，那会超前一个缓冲）；
     * 3. **位置以第一块 PCM 的 PTS 为基准**，而不是"从 0 数"。
     */
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        systemSink?.let { return it.getCurrentPositionUs(sourceEnded) }
        if (handle == 0L || startMediaTimeUs == C.TIME_UNSET || !firstWriteDone) {
            return AudioSink.CURRENT_POSITION_NOT_SET
        }
        val playedFrames = minOf(playedFramesNow(), writtenFramesByUs)
        val us = startTimeBasis() + framesToUs(playedFrames)
        if (us > positionUs) positionUs = us
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastStatsMs > 500) {
            lastStatsMs = now
            AudioDirectProbe.recordAaudioStats(
                "pos=${positionUs / 1000}ms " +
                    "pend=${if (hasPendingData()) 1 else 0} play=${if (playRequested) 1 else 0} " +
                    "eos=${if (handledEndOfStream) 1 else 0} sr=$sampleRate ch=$channelCount",
            )
        }
        return positionUs
    }

    private fun framesToUs(frames: Long): Long =
        if (sampleRate > 0) frames * 1_000_000L / sampleRate else 0L

    /**
     * 位置的零点（媒体时间轴）。
     *
     * `startMediaTimeUs` 是 `handleBuffer` 给的 PTS。它是否已含 `outputStreamOffsetUs`
     * 随源而定，这里按"PTS 明显小于 offset 就补上"处理 —— 实测有效。
     *
     * ⚠️ 曾经试图照 `DefaultAudioSink` "不加 offset" 而改成**减去**，结果更糟：
     * 位置小于 `startPositionUs` ⇒ `periodPositionUs` 变负 ⇒ 进度条钉在原点、整条链停摆。
     * `positionTracker` 返回的是内部换算后的值，不能据此推断"对外位置要减 offset"。
     */
    private fun startTimeBasis(): Long =
        startMediaTimeUs + if (startMediaTimeUs < outputStreamOffsetUs) outputStreamOffsetUs else 0L

    /**
     * 设备**真的播出去**多少帧。两级取值：
     *
     * 1. 硬件时间戳 `getTimestamp` —— 最准，含 HAL 侧延迟；
     * 2. `getFramesRead`（native 侧只统计**真数据**，静音填充不计）。
     *
     * 本机时间戳一次都不成功（MMAP 不给），所以第 2 级才是真正在用的那条。
     * 以前的第三级兜底（`已写帧 − bufferSize`）已经删掉：Oboe 后端的 `framesRead`
     * 由我们自己在回调里累加，语义确定，不需要再猜。
     */
    private fun playedFramesNow(): Long {
        engine.timestamp(handle)?.let { stamp ->
            val frames = stamp[0] - timestampBaseFrames
            if (frames > 0) return frames
        }
        val readFrames = engine.framesRead(handle)
        return (readFrames - readBaseFrames).coerceAtLeast(0)
    }

    override fun configure(format: Format, bufferSize: Int, outputChannels: IntArray?) {
        // **这一个 configure 里"要不要 raw alt"只问一次，之后全用这个快照。**
        //
        // 以前是本方法内三次独立读 `nativeRawAlt()`（判 reusable / 决定 reopenStream 的 raw /
        // 决定 openRaw 还是 open）。那个读的是全局量 `_dsdTransport == native`，由主线程在
        // `_setSource` 里改写，与这里（播放线程）没有同步点。两次读之间一旦翻面，
        // 同一首歌的两半就会走不同的 alt：
        //
        //   · PCM 的字节被 `reopenStream(raw = true)` 装进 raw DSD 的 alt
        //     → 设备把 32 bit word 当 1-bit 流解 → 发得出去、进度照走、**没有声音**；
        //   · 反过来 DSD 帧被塞进 PCM alt → 满幅噪声。
        //
        // 触发窗口恰好在「用过 Native / DoP 之后」：那时 DSD 模式收集器、
        // `onSinkRouting` 与失败重建同时在翻 `_dsdTransport`。这就是
        // 「DSD 播完，之后任何 PCM 流都没声音」的成因。
        val wantRaw = nativeRawAlt()
        // 同一个时刻把"这条流是什么传输方式"钉下来：失败回调要用**这一次**的值，
        // 而不是失败时读到的全局量（那时用户可能已经切过模式了）。
        openedTransportTag = runCatching { transportTag() }.getOrDefault("")
        // **死流不许复用。**
        //
        // USB 那条 `dead` 一旦置上（任一 transfer 以非 COMPLETED 状态回来，且不是主动停流），
        // `fill()` 就只送静音、`refill()` 不再提交 —— 流"还在"但永远不会有声音；
        // 而它**只在拆流重建（`startStream`）时清除**。复用分支（reusable / rebuilt）
        // 会一直抱着这条死流，于是**连普通 PCM 也一起哑**，直到某次走"新会话"为止 ——
        // 这正是"快速切模式搞出静音之后，别的格式也没声；切回 DSD 再切一次模式才恢复"。
        //
        // 这里把它降级成"没有流"：下面所有分支都会走新建 → `closeEngine()` +
        // `engine.release()`（重开会话），也就是实测唯一能恢复的那条路。
        if (handle != 0L && systemSink == null && !engine.isHealthy(handle)) {
            RuntimeLog.record("独占", "USB 流已失效（dead）→ 丢弃并重开会话")
            closeEngine()
        }
        // 同规格切歌**不重建流**：USB 这条每次重建都要放掉接口再 claim 回来，DAC 得重新锁时钟，
        // 那段时间输出会短暂异常（听感就是"某一端先没声"）。
        //
        // **位深变化不算换规格**：线上格式由 alt setting 定死（会话级固定成"装得下 24 位"
        // 的那条），转换在每次写入时按实际字节数做，16→24 是精确左移。为了"位深变了"
        // 去重建流只会白付一次设备重锁。
        val reusable = handle != 0L && systemSink == null && engine !== OboeEngine &&
            engineStillValid() &&
            streamRaw == wantRaw &&
            sampleRate == format.sampleRate &&
            channelCount == format.channelCount
        if (reusable) {
            val depth = when (format.pcmEncoding) {
                C.ENCODING_PCM_16BIT -> 16
                C.ENCODING_PCM_24BIT -> 24
                else -> 32
            }
            pcmDepth = depth
            pcmEncoding = format.pcmEncoding
            bytesPerFrame = bytesPerSample(depth) * channelCount
            // 留 20 ms 旧尾部垫住：环清空后 `fill()` 立刻送零，设备要重新对齐通道，
            // 那次对齐偶尔只锁上一个声道。新数据一到就无缝接上，断点不存在。
            engine.flush(handle, keepFrames = sampleRate / 50)
            engine.setInputEncoding(handle, format.pcmEncoding)
            writtenFramesByUs = 0L
            timestampBaseFrames = engine.timestamp(handle)?.get(0) ?: 0L
            readBaseFrames = engine.framesRead(handle)
            resetConsumeWatchdog()
            positionUs = 0
            firstWriteDone = false
            failureReported = false
            handledEndOfStream = false
            startMediaTimeUs = C.TIME_UNSET
            applyPlayIntentToEngine()
            listener?.let { runCatching { it.onPositionDiscontinuity() } }
            publishRouting(OutputPath.usbDirect)
            return
        }

        // 换率/换声道：USB 只重建流，**不重开会话也不放接口**。
        //
        // 重开连接会让设备从系统消失再出现 —— USB 侧掉电重初始化、DAC 重锁 PLL，
        // 那一段正是"换率切歌时某声道暂时没声"的来源。native 侧现在进一步做到：
        // 只要当前 alt 装得下新率，就**连流都不重拆**，只换时钟
        // （`UacStream::reopen` 的无缝路径），URB 一直挂着。
        //
        // 失败**重试一次**再放弃：`reopen` 只会因为"上一条流的 transfer 还没排空"失败，
        // 那是时序问题，隔一下就好；一旦放弃就会落到"重开会话"这条最重的路。
        val rebuilt = handle != 0L && engine !== OboeEngine && systemSink == null &&
            engineStillValid() &&
            run {
                // raw 与 PCM 的 alt 互斥，重建时要把 raw 与否一起交下去。
                // 用本次 configure 的快照，不重读全局量 —— 理由见方法开头。
                val raw = wantRaw
                val wire = wantWireBits()
                var ok = reopenStream(raw, format, wire)
                if (!ok) {
                    Thread.sleep(60)
                    ok = reopenStream(raw, format, wire)
                }
                if (ok) streamRaw = raw
                ok
            }
        if (rebuilt) {
            sampleRate = format.sampleRate
            channelCount = format.channelCount
            val depth = when (format.pcmEncoding) {
                C.ENCODING_PCM_16BIT -> 16
                C.ENCODING_PCM_24BIT -> 24
                else -> 32
            }
            pcmDepth = depth
            pcmEncoding = format.pcmEncoding
            bytesPerFrame = bytesPerSample(depth) * channelCount
            writtenFramesByUs = 0L
            timestampBaseFrames = engine.timestamp(handle)?.get(0) ?: 0L
            readBaseFrames = engine.framesRead(handle)
            resetConsumeWatchdog()
            positionUs = 0
            firstWriteDone = false
            failureReported = false
            handledEndOfStream = false
            startMediaTimeUs = C.TIME_UNSET
            applyPlayIntentToEngine()
            listener?.let { runCatching { it.onPositionDiscontinuity() } }
            publishRouting(OutputPath.usbDirect)
            return
        }

        // 这一首开始：先把上一首的两条通路都收干净，再**一次性**选定引擎
        closeEngine()
        // 换引擎前必须放掉旧的：USB 的会话（`UsbDeviceConnection` / fd）由引擎持有，
        // 只关流不 release 会一路漏 fd，而且新 fd claim 时旧的还占着同一个接口。
        engine.release()
        systemSink?.let { runCatching { it.reset() } }
        systemSink = null
        positionUs = 0
        firstWriteDone = false
        failureReported = false
        deadSinceMs = 0L
        stalePauseBuffers = 0
        handledEndOfStream = false
        writtenFramesByUs = 0L
        timestampBaseFrames = 0L
        readBaseFrames = 0L
        // 时间轴基准要等这一首第一块 PCM 到了才建立
        startMediaTimeUs = C.TIME_UNSET

        val depth = when (format.pcmEncoding) {
            C.ENCODING_PCM_16BIT -> 16
            C.ENCODING_PCM_24BIT -> 24
            else -> 32
        }
        sampleRate = format.sampleRate
        channelCount = format.channelCount
        pcmDepth = depth
        pcmEncoding = format.pcmEncoding
        bytesPerFrame = bytesPerSample(depth) * channelCount

        // 选内核：USB 独占由 `engineSelector` 给出，拿不到（未授权 / 无设备 / SDK 过低）
        // 就退回 AAudio —— 那条路自己还有一层 verdict 与系统兜底。
        engine = engineSelector() ?: OboeEngine
        val oboeEngine = engine === OboeEngine
        val wantedFormat = aaudioFormatOf(depth)
        // 清掉上一次的旧原因：这一首要重新判定，面板不该还挂着上一首的失败理由
        AudioDirectProbe.recordExclusiveAttempt()
        val verdict: ExclusiveVerdict = if (oboeEngine) {
            AudioDirectProbe.exclusiveVerdict(
                rate = sampleRate,
                pcmFormat = wantedFormat,
                wanted = exclusiveWanted(),
            )
        } else {
            ExclusiveVerdict.Possible
        }
        if (verdict is ExclusiveVerdict.Denied) {
            // 事前拒绝（通路不走独占 / 探测已判不可能 / 已在运行时黑名单）。
            // **绝不写进运行时黑名单**：前两者不是"失败"，记进去会让"在系统通路播过一首"
            // 把该率永久拉黑，之后切回独占用也再也不试。第三个本来就在黑名单里。
            AudioDirectProbe.recordAaudioActive(false)
            AudioDirectProbe.notePreDenial(verdict.denial)
        }
        if (verdict is ExclusiveVerdict.Possible) {
            // 只有 **Native DSD** 走 raw alt（DoP 必须留在 PCM alt，见参数注释）。
            // 同样用本次 configure 的快照：这里若与上面 `reusable`/`rebuilt` 用了不同的值，
            // 就会出现"判复用按 PCM、开流按 raw"这种自相矛盾的一半一半。
            val raw = wantRaw
            val wire = wantWireBits()
            val opened = if (raw) {
                engine.openRaw(sampleRate, channelCount)
            } else {
                engine.open(sampleRate, pcmEncoding, channelCount, wire)
            }
            if (opened != 0L) {
                handle = opened
                streamRaw = raw
                // AAudio 才需要回读校验：HAL 可能给一条 EXCLUSIVE 流却把率/格式换掉，
                // 那是"独占着在重采样"。USB 侧的成立条件只有"拿到了接口"，率由引擎自查。
                var realRate = 0
                var realFormat = 0
                val denial = if (oboeEngine) {
                    val realSharing = NativeOboeStream.sharingMode(handle)
                    realRate = NativeOboeStream.sampleRate(handle)
                    realFormat = NativeOboeStream.format(handle)
                    // 预编译的 liboboe 默认后端可能是 OpenSL ES，而它没有 EXCLUSIVE 概念。
                    val realApi = NativeOboeStream.audioApi(handle)
                    when {
                        realApi == 2 -> ExclusiveDenial.OpenSlBackend
                        realSharing != 1 -> ExclusiveDenial.SharedStream
                        realRate != sampleRate -> ExclusiveDenial.RateChanged
                        !formatAcceptable(wantedFormat, realFormat) -> ExclusiveDenial.FormatChanged
                        else -> null
                    }
                } else {
                    null
                }
                if (denial == null) {
                    resetConsumeWatchdog()
                    // 硬件音量：能读到就走设备内部衰减，送出去的 PCM 一个字节都不动
                    // （bit perfect 成立）；读不到自动退回 `PcmGain` 的软件衰减。
                    // writer 只做一次 atomic store，控制传输由 native 事件线程排队发。
                    val hwCaps = engine.hardwareVolumeCaps(handle)
                    ExclusiveVolume.attachHardware(
                        caps = hwCaps,
                        writer = if (hwCaps == null) {
                            null
                        } else {
                            { v -> engine.setHardwareVolume(handle, v) }
                        },
                    )
                    // 新流在 native 侧的增益是 1.0，立刻补上当前值 ——
                    // 否则一次 configure 就把用户调好的音量打回满刻度。
                    // DSD 直出同样强制 1.0（理由见 `handleBuffer` 里那段）。
                    pushedGain = if (rawFlowActive()) 1f else exclusiveGain()
                    engine.setGain(handle, pushedGain)
                    // 独占流成立 → **把优先级给这条线程**。
                    //
                    // 解码 / r8brain / 打包 / ring 拷贝全在调用 `configure` 的这个线程上
                    // （ExoPlayer 的 Playback 线程），而系统通路里同类工作跑在 AudioFlinger
                    // 的 FastMixer 上、天然被 boost。不给这提升级，调度器会把这些突发计算
                    // 丢到小核，表现就是"独占时一个小核占用率特别高、还容易欠载"。
                    // native 的事件线程只是收完成/重提交，**不提优先级**（那边提反而抢这里）。
                    boostPlaybackThread(true)
                    // **必须按既有播放意图恢复写闸门**。
                    //
                    // Media3 只在 renderer 进入 STARTED 时调一次 `play()`；而"换源 / 重定输出策略 /
                    // 设备变化"都会走 `_reloadCurrentSource()` → 新 period → `onEnabled` → 再调一次
                    // `configure()`。此时 renderer 状态没变，`play()` 不会再来 —— 上一版在这里把
                    // 暂停状态置 true 就再没人抬起来：整首不出声、`firstWriteDone` 永远 false →
                    // 位置恒为 NOT_SET → 进度条钉在 0、结尾判不出来也不切歌。
                    applyPlayIntentToEngine()
                    // 两条通路的缓冲观测点不同：AAudio 问流，USB 问环形缓冲。
                    // 混着读就是拿 UAC 的指针去调 Oboe 的接口，绝对不能做。
                    if (oboeEngine) {
                        AudioDirectProbe.recordAaudioActive(true)
                        RuntimeLog.record(
                            "AAudio",
                            "独占流已开：${sampleRate}Hz/${channelCount}ch/${depth}bit · " +
                                "Oboe" + when (NativeOboeStream.audioApi(handle)) {
                                    1 -> "（AAudio）"
                                    2 -> "（OpenSL ES，无独占）"
                                    else -> ""
                                } + " · " +
                                "缓冲 ${NativeOboeStream.bufferSize(handle)}/" +
                                "${NativeOboeStream.bufferCapacity(handle)} 帧",
                        )
                    } else {
                        RuntimeLog.record(
                            "USB",
                            "独占流已开：${sampleRate}Hz/${channelCount}ch/${depth}bit · " +
                                "环 ${NativeUacStream.ringCapacityFrames(handle)} 帧 · " +
                                "上线 ${NativeUacStream.outFrameBytes(handle)} B/帧",
                        )
                    }
                    // **新流 = 新时间轴**：必须通知渲染器"位置不连续"，否则它会沿用上一首的
                    // 基准只做 max(旧值, 新值)，回退方向永远压不回来。
                    listener?.let { runCatching { it.onPositionDiscontinuity() } }
                    // 端点上真正跑的位深：USB 是 alt setting 的 subslot（S24_3LE = 24），
                    // AAudio 是 HAL 回读的编码。我方交的是 float，但到耳朵的是这个。
                    streamDepth = if (oboeEngine) {
                        depthOfAaudioFormat(NativeOboeStream.format(handle))
                    } else {
                        val frameBytes = NativeUacStream.outFrameBytes(handle)
                        if (frameBytes > 0 && channelCount > 0) {
                            (frameBytes / channelCount) * 8
                        } else {
                            0
                        }
                    }
                    publishRouting(
                        if (oboeEngine) OutputPath.aaudioExclusive else OutputPath.usbDirect,
                    )
                    return
                }
                closeEngine()
                AudioDirectProbe.markExclusiveFailure(
                    sampleRate,
                    denial,
                    detail = when (denial) {
                        ExclusiveDenial.RateChanged -> "$realRate Hz"
                        ExclusiveDenial.FormatChanged -> "实际 $realFormat / 请求 $wantedFormat"
                        else -> ""
                    },
                )
            } else if (oboeEngine) {
                AudioDirectProbe.markExclusiveFailure(
                    sampleRate,
                    ExclusiveDenial.OpenFailed,
                    detail = "err=${NativeOboeStream.lastError()}",
                )
            }
        }

        // ── 系统引擎：现场新建 + 重放所有 setter ──
        AudioDirectProbe.recordAaudioActive(false)
        // 落回系统通路 → 复位优先级：这条路上真正干活的是 AudioFlinger 的线程，
        // 我们再占着高优先级只会和它抢 CPU。
        boostPlaybackThread(false)
        // **raw DSD（DoP / Native）绝不能落到系统通路**：`DefaultAudioSink` 内部那条
        // 24bit → float 的处理器会把 DoP 的 0x05 / 0xFA 标记除成 ~1e-7 的直流，
        // DAC 再也认不出合法帧序列 —— 用户听到的就是「选了 DoP，完全没声」。
        // 这里显式判失败，让上层把它记进 `_failedTransports` 并按 PCM 重建，
        // 而不是硬把帧送出去装作在播。
        // 判据用本次 configure 的快照（`wantRaw`），不用全局量：这条分支决定
        // "这一首能不能出声"，绝不能被另一条线程在中间改掉的意图带偏。
        if (wantRaw) {
            handle = 0L
            onEngineFailure("raw DSD 需要独占通路，系统通路会改写帧标记", openedTransportTag)
            // **不要在这里上报 `systemMix`。**
            //
            // `publishRouting` 喂的是 `_effectivePath`，而那个值又被当成**判定依据**用回去：
            //   `_resolveDsdTransport(path = _effectivePath)`（`DsdTransportPlan.kt:105`）
            //   与 `_outputRoute()` / tier / 独占音量判据。
            // 这里根本没有回落到混音（我们是显式判失败），上报 systemMix 等于谎报事实，
            // 代价是下一步 DSD 判定立刻变成 `PathNotBitExact` → PCM；等 PCM 把独占重新
            // 开起来、通路又变回 `usbDirect`，判定又变回 Native，于是「失败 → 降级 → 换路
            // → 再失败」，每一轮都拆一次流。这条自激环的三条腿里它是最直接的一条。
            //
            // 失败本身已经由 `onEngineFailure` 交付（它会重建音源并把这次传输方式记进
            // `_failedTransports`，下一次自动沿降级链往下走），不需要第二份"事实"。
            return
        }
        val system = systemSinkFactory()
        // 顺序照 Media3 的真实调用序（listener → attributes → params → 其余），
        // 漏一条就等于让系统 sink 少一个回调/少一个属性（这正是上一版进度条不动的成因）
        listener?.let { runCatching { system.setListener(it) } }
        runCatching { system.setAudioAttributes(audioAttributes) }
        runCatching { system.setPlaybackParameters(playbackParameters) }
        runCatching { system.setSkipSilenceEnabled(skipSilence) }
        runCatching { system.setVolume(volume) }
        if (sessionId != C.AUDIO_SESSION_ID_UNSET) runCatching { system.setAudioSessionId(sessionId) }
        runCatching { system.setAuxEffectInfo(auxEffect) }
        system.configure(format, bufferSize, outputChannels)
        systemSink = system
        // 新建的 sink 还没被 `play()` 过。若这次 configure 发生在"本来就在播"的
        // 场景（`_reloadCurrentSource()` / 设备变化），renderer 状态没变、不会再调
        // `audioSink.play()` —— 不补这一下，新 sink 会一直停在未启动状态（没声音）。
        if (playRequested) system.play()
        // 走到这里说明意图（独占 / USB）没兑现。原因按意图取，不编「未知错误」；
        // AAudio 的具体项（率被换、格式被换、黑名单…）从探针取，别把它压成一句「不支持」。
        publishRouting(
            OutputPath.systemMix,
            when (MoreSettings.outputPath.value) {
                OutputPath.usbDirect -> usbUnavailable()
                OutputPath.aaudioExclusive -> DowngradeReason.AaudioDenied
                OutputPath.systemMix -> null
            },
            detail = if (MoreSettings.outputPath.value == OutputPath.aaudioExclusive) {
                AudioDirectProbe.lastDenialLabel().orEmpty()
            } else {
                ""
            },
        )
    }

    /**
     * 上报实际生效通路。只在**变化**时推 —— 本方法在 `configure()` 里，每次切歌都会经过。
     */
    private fun publishRouting(
        actual: OutputPath,
        reason: DowngradeReason? = null,
        detail: String = "",
    ) {
        val notice = downgradeNoticeFor(MoreSettings.outputPath.value, actual, reason, detail)
        if (actual == reportedPath && notice == reportedNotice) return
        reportedPath = actual
        reportedNotice = notice
        onRouting(actual, notice, streamDepth)
    }

    override fun play() {
        // **先记账再转发**：`playRequested` 是"上游想不想播"的唯一真相，两条通路都要维护它。
        // 以前系统通路在这里直接 `return`，不记 playRequested —— 于是 `flush()` 里
        // 想按"是否该播"补一次 play() 就没有依据（见 [flush]）。
        playRequested = true
        systemSink?.let { it.play(); return }
        if (handle != 0L) engine.resume(handle)
    }

    override fun handleDiscontinuity() {
        systemSink?.let { it.handleDiscontinuity(); return }
        flush()
    }

    /**
     * 把这一块 PCM 塞进环形缓冲 —— **这里是唯一的写入点**。
     *
     * 返回 `false` 表示"这块没完全收下"，Media3 会用**同一个 buffer** 重试
     * （`AudioSink.handleBuffer` 的契约）。所以我们只在真写进去之后才推进
     * `buffer.position`，绝不提前消费。
     */
    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        systemSink?.let { return it.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount) }
        if (handle == 0L || bytesPerFrame <= 0) {
            // 流已经不在了，但数据还在来 —— 上游不会自己重建音源，`onEngineFailure`
            // 是唯一的入口。以前这里静默吞掉每一块：播放器一直显示 PLAYING、
            // 位置永久冻结、缓冲区填满，除非切回前台重建，否则再也出不了声。
            //
            // 等 1.5 s 再报：`configure` 期间也会短暂出现 handle == 0，
            // 那不是故障，不该触发重建。
            //
            // **暂停中一律不报**（[playIntent]）：用户按了暂停，流闲着不是故障，而重建音源
            // 会把这一首重新 prepare 一遍。`deadSinceMs` 不因暂停清零 —— 恢复播放后
            // 第一块数据就直接超时，立刻走重建。
            if (handle == 0L && systemSink == null && buffer.remaining() > 0 && playIntent()) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (!failureReported) {
                    if (deadSinceMs == 0L) {
                        deadSinceMs = now
                    } else if (now - deadSinceMs > 1_500L) {
                        failureReported = true
                        RuntimeLog.record("独占", "流已关闭但数据仍在到达 —— 请求重建音源")
                        onEngineFailure("独占流已关闭，数据仍在到达", openedTransportTag)
                    }
                }
            }
            // **必须返回 false：这块没被消费。**
            //
            // 返回 true 等于告诉 Media3「这块已经播出去了」——而它信了：渲染器立刻推进
            // 自己的时钟，播放器显示 PLAYING、进度条一路走、缓冲区保持为空，
            // 声音一个样本都没进 DAC。这就是「进度条走但完全没声」最标准的形态，
            // 而且它**把故障伪装成正常播放**，`onPlayerError` 永远不触发。
            //
            // 本项目的本地全链路又把这个伪装做到了极致：`FfmpegLocalExtractor` 只
            // `input.skip(written)` 推进位置、`PositionOnlyDataSource` 不供字节，读位置与
            // 送样本本来就是解耦的 —— 于是没有任何一方能发现"其实什么都没播"。
            //
            // 契约上正确的答复是 false（= 没接收，稍后用同一个 buffer 重试）：Media3
            // 会把这块留在渲染器里、位置停在原地，故障因此立刻可见；超时那条
            // `onEngineFailure` 仍然是唯一的收尾（1.5 s 后重建音源）。
            return false
        }

        deadSinceMs = 0L

        val remaining = buffer.remaining()
        if (remaining < bytesPerFrame) return true

        // ── 与上游播放意图对齐（**两个方向都要**）──
        //
        // 这里是"开机就送静音"这条设计换来的代价：USB 独占没有 mixer、也没有
        // `AudioTrack.pause()` 那种由设备自己停下来的机制，出不出声**只由我方
        // `engine.pause()/resume()` 决定**。所以 `playRequested` 一旦与上游不一致，
        // 结果不是哑就是"该停的时候还在响"——两个方向都要在这里对齐。
        //
        // 正向（sink 停着、上游想播）：`play()` 不是对称调用的，它挂在
        // BUFFERING → READY 的迁移上（见 `flush()` 的注释）。后台切换应用引起一次 audio focus
        // 变化 → Media3 调 `pause()` → 我方停流；focus 回来后 `playWhenReady` 一直是 true、
        // **没有任何状态迁移**，`play()` 再也不来。结果是播放器显示 PLAYING、位置冻结、
        // 看门狗因为 `playRequested` 是 false 而不判失败，除非切回前台重建否则永远不恢复。
        //
        // 反向（sink 在播、上游已暂停）：`pause()` 到 renderer 真正停下之间还会交出几块
        // 管线里的数据。以前这里只看"数据在来"就当成该播，于是**用户按暂停、一秒后自己响了**。
        // 只有 USB 独占会：这一整段本就在 `systemSink == null` 分支里，系统通路第一行就转发出去了。
        //
        // 判据因此是 [playIntent]（= `Player.playWhenReady`）而不是"有没有数据"：
        // 用户暂停 → false，focus 抖动 → 仍是 true，两者才分得开。
        // 正向还要求**连续两块**：真正暂停时 Media3 只会交出手上那一块。
        //
        // **只在"上游想播"这一侧动作，反过来那一侧不碰。** 曾经加过一条对称的
        // "`playRequested` 为真、`playIntent` 为假就立刻 `engine.pause()`"，结果点播放后
        // 第一块数据就被判成已暂停、瞬间又停 —— 停的理由只有 `pause()` 一个（Media3 亲口说的），
        // 意图标志最多是"还没到"，不能拿它去否掉一条明确的 `play()`。
        if (!playRequested) {
            if (playIntent()) {
                stalePauseBuffers++
                if (stalePauseBuffers >= 2) {
                    stalePauseBuffers = 0
                    playRequested = true
                    engine.resume(handle)
                    RuntimeLog.record("独占", "数据仍在到达但流处于暂停 —— 已自行恢复")
                }
            } else {
                stalePauseBuffers = 0
            }
        } else {
            stalePauseBuffers = 0
        }

        // 先记账"设备还在不在消费"：只有 `framesRead` 增长才算。
        // 用**原始**计数（不带 `readBaseFrames`）—— 这里只关心"有没有在涨"。
        val now = android.os.SystemClock.elapsedRealtime()
        val readNow = engine.framesRead(handle)
        if (readNow > lastReadFrames) {
            lastReadFrames = readNow
            lastConsumeMs = now
        }

        // 从 duplicate 读，原 buffer 的 position 留到确认写入后再推进
        val bytes = ByteArray(remaining)
        buffer.duplicate().get(bytes, 0, remaining)

        // 独占通路上没有 mixer，音量只能由我方施加。**施加点在 native 的消费侧**
        // （Oboe 的 `onAudioReady` / USB 的 `fill()`）：环里存的是未衰减的原始样本，
        // 所以换音量只剩在飞数据的延迟（USB 63 ms / AAudio 一个回调周期）。
        //
        // 以前是在这里乘完再写环，于是要等环里那约 250 ms 已算好旧增益的数据播完 ——
        // 手感就是"调了没反应、过一会儿才变"。
        //
        // 用 [exclusiveGain] 而不是 Media3 下发的 `volume`：那个是 mixer 的旋钮，
        // 独占通路没有接收方；两者都乘会得到两条 dB 曲线相乘。
        // **DSD 直出时一律不施加增益。** DoP 的帧标记在最低位、Native 是 1-bit 流，
        // 乘任何系数（哪怕是 0.9）都会把它们毁掉。
        //
        // 这里故意**不看 `ExclusiveVolume` 的 raw 标志**：那个由后端异步更新，一旦滞后
        // （切模式 / 换源的那几块数据），就会拿着 PCM 的音量去衰减 DSD 帧。
        // `rawFlowActive()` 读的是本条流自己的状态，没有这个时序窗口。
        val gain = if (rawFlowActive()) 1f else exclusiveGain()
        if (gain != pushedGain) {
            engine.setGain(handle, gain)
            pushedGain = gain
        }

        val frames = engine.write(handle, bytes, 0, remaining, bytesPerFrame)
        if (frames < 0) {
            // 流已废。**必须立刻关流**：`handle` 不归零的话，Media3 会用同一个 buffer
            // 逐个重试，每个都返回 false → 每秒数百次 `onEngineFailure` → 每次都重建整条
            // 音源与 USB 流，听感是"炸一下然后彻底不出声"。
            // 只在第一块上报一次，并且返回 true 让 Media3 丢掉这块，别再刷。
            if (!failureReported) {
                failureReported = true
                reportFailure(ExclusiveDenial.WriteFailed, "err=$frames")
                closeEngine()
                onEngineFailure("写入失败（err=$frames）", openedTransportTag)
            }
            return true
        }
        if (frames == 0) {
            // 环满 = "我们有数据、设备不收"。偶尔发生是正常背压；持续不发就说明
            // 这条流已经不再消费，必须主动掐掉：
            //   · 不掐 → `written > played` 永久成立 ⇒ `isEnded()` 永远 false
            //     ⇒ 播到末尾不循环、不切歌、进度条卡死（删掉老看门狗后的真实回归）；
            //   · 掐掉后 `handle == 0` ⇒ `hasPendingData()` false ⇒ `isEnded()` 成立，
            //     同时 `onEngineFailure` 让上游重建音源、该采样率下次直接走系统通路。
            //
            // `playRequested` 这个条件是必须的：暂停时回调只出静音、**故意不读环形缓冲**，
            // `framesRead` 本来就不会涨；不加这一条会在暂停时误判成就绪失败。
            if (playRequested && now - lastConsumeMs > STALL_TIMEOUT_MS) {
                reportFailure(
                    ExclusiveDenial.Stalled,
                    "${STALL_TIMEOUT_MS}ms 内已播帧无增长",
                )
                onEngineFailure("设备不消费数据（${STALL_TIMEOUT_MS}ms 内已播帧无增长）", openedTransportTag)
                closeEngine()
                return true
            }
            return false
        }

        buffer.position(buffer.position() + frames * bytesPerFrame)
        writtenFramesByUs += frames
        if (startMediaTimeUs == C.TIME_UNSET) {
            // 时间轴基准 = 这块里**第一个还没写出去**的帧的媒体时间。
            // 部分写入后重试时 position 已经前进，所以这里只在"首次"建立，值是块头 PTS。
            startMediaTimeUs = presentationTimeUs
        }
        if (!firstWriteDone) {
            firstWriteDone = true
            // 真正开始出声的时刻：参数是**媒体时间 µs**（与 AudioSink.Listener 的约定一致）
            listener?.let { runCatching { it.onPositionAdvancing(startTimeBasis()) } }
        }
        return !buffer.hasRemaining()
    }

    override fun playToEndOfStream() {
        systemSink?.let { it.playToEndOfStream(); return }
        // 照 `DefaultAudioSink`：置 handledEndOfStream，之后由 hasPendingData 排空判定
        handledEndOfStream = true
    }

    /**
     * 是否播完 —— 照 `DefaultAudioSink`：
     * ```java
     * isEnded() = !isAudioTrackInitialized() || (handledEndOfStream && !hasPendingData());
     * ```
     */
    override fun isEnded(): Boolean {
        systemSink?.let { return it.isEnded }
        if (handle == 0L) return true
        return handledEndOfStream && !hasPendingData()
    }

    /**
     * 还有没有没播出去的数据 —— `DefaultAudioSink` 的语义：
     *
     * ```java
     * hasPendingData() = isAudioTrackInitialized() && tracker.hasPendingData(getWrittenFrames());
     * ```
     *
     * 即"**设备里还有没播完的帧**"，不是"上游还会再来数据"。
     *
     * 这个判据两头都不能歪：
     * - 恒 `true` → 播放器永远停在 `STATE_READY`，进不了 `STATE_BUFFERING`，
     *   而 READY 下的加载判据是 `totalBufferedDuration < minBufferUs`；
     *   缓冲虚高时它会认为"够用"不再加载 → 与"解码器等数据"互相等待。
     *   而且 `isEnded() = handledEndOfStream && !hasPendingData()` ⇒ 恒 true 时**永远判不出播完**，
     *   也就不会自动切歌。
     * - 过于乐观地报 `false` → `isReady()` 掉下去 ⇒ BUFFERING ⇒ renderer 被 stop ⇒ 解码器停摆。
     *
     * Oboe 后端下这个判据很干净：`writtenFramesByUs` 是"塞进环形缓冲的帧"，
     * `playedFramesNow()` 是"回调取走的**真数据**帧"，差值就是还压在管道里的量。
     */
    override fun hasPendingData(): Boolean {
        systemSink?.let { return it.hasPendingData() }
        if (handle == 0L) return false
        return writtenFramesByUs > playedFramesNow()
    }

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

    override fun getSkipSilenceEnabled(): Boolean =
        systemSink?.skipSilenceEnabled ?: skipSilence

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
        systemSink?.let { runCatching { it.setOutputStreamOffsetUs(offsetUs) } }
    }

    override fun setVolume(volume: Float) {
        this.volume = volume
        // AAudio 路径刻意不下发（透明模式不允许我方数字衰减）；系统路径必须转过去
        systemSink?.let { runCatching { it.setVolume(volume) } }
    }

    /**
     * 暂停 = **让回调输出静音**（`NativeOboeStream.pause`），不 `requestPause` 流本身。
     *
     * 踩过的坑：`AAudioStream_getState` 在这类 HAL 上长期停在 4（PAUSING）不动，
     * 一旦 `requestPause` 就再也回不到 STARTED —— 界面表现是"进度条不动、也不切歌"。
     * 现在的做法对 Oboe 是原生支持的：回调里判断 paused 就只填静音、**不从环形缓冲取数据**，
     * 于是 `framesRead` 冻结 → 位置冻结，恢复时接着读，一个样本都不丢。
     */
    override fun pause() {
        playRequested = false
        stalePauseBuffers = 0
        systemSink?.let { it.pause(); return }
        if (handle != 0L) engine.pause(handle)
    }

    override fun flush() {
        systemSink?.let { sink ->
            sink.flush()
            // ⚠️ **必须在这里补一次 play()** —— 这是"单曲循环回到开头却不出声"的唯一修复。
            //
            // `DefaultAudioSink.flush()` 内部会 `audioTrack.pause()`，而恢复播放只有一条路径：
            // `renderer.start()` → `audioSink.play()` → `audioTrack.play()`。
            // 而 `renderer.start()` 只在 **BUFFERING → READY** 的迁移里被调用。
            //
            // seek 到**已缓冲位置**时（单曲循环 seek 回 0 就是这种情况，实测
            // `buffered position = 32972ms` 覆盖了 0 位置）不会经过 BUFFERING，
            // 于是从头到尾没人调 `play()`：位置停在 0、没有声音，而 `state` 仍是 PLAYING。
            //
            // 在包装层按 `playRequested` 补一次是唯一不碰 Media3 内部的办法。
            // 暂停中 seek 不会误播 —— 那时 `playRequested` 是 false。
            if (playRequested) sink.play()
            return
        }
        if (handle != 0L) {
            // AAudio：seek 重建整条流。证据（MIUI 实测）：`requestFlush` 返回成功但设备里的
            // 旧音频仍在被读走，记账错位 → seek 后播几秒就停。
            //
            // USB：**不重建**。它没有 HAL 那层队列，唯一的中转就是我方环形缓冲，
            // 清掉它就是水位归零；代价只有设备里 ≤12 ms 的已提交数据。而重建要
            // 重新 init/claim/设率/分配 12 个 URB，每次拖进度条要卡 1 秒。
            if (engine !== OboeEngine) {
                engine.flush(handle)
                writtenFramesByUs = 0L
                timestampBaseFrames = engine.timestamp(handle)?.get(0) ?: 0L
                readBaseFrames = engine.framesRead(handle)
            } else {
                val sr = sampleRate
                val ch = channelCount
                val enc = pcmEncoding
                closeEngine()
                val opened = if (sr > 0 && ch > 0) {
                    engine.open(sr, enc, ch)
                } else {
                    0L
                }
                if (opened != 0L) {
                    handle = opened
                    resetConsumeWatchdog()
                    AudioDirectProbe.recordAaudioActive(true)
                }
                writtenFramesByUs = 0L
                timestampBaseFrames = engine.timestamp(handle)?.get(0) ?: 0L
                readBaseFrames = engine.framesRead(handle)
            }
        }
        positionUs = 0
        firstWriteDone = false
        handledEndOfStream = false
        // seek：位置基准必须由**新位置的第一块 PTS** 重建，否则时间轴停在旧位置
        startMediaTimeUs = C.TIME_UNSET
        // **保持播放意图**：本来在播就继续播
        applyPlayIntentToEngine()
        // 必须告诉渲染器"位置不连续"：它那边是 max(旧值, sink 值)，不通知就压不回来
        listener?.let { runCatching { it.onPositionDiscontinuity() } }
    }

    override fun reset() {
        systemSink?.let { it.reset(); return }
        // USB：**不拆流**。这是与内核路径唯一的结构性差别，也是"连点切歌一路没声"的根。
        //
        // `DefaultAudioSink.reset()` 保留 AudioTrack，内核 snd-usb-audio 的 alt setting 与
        // URB 流水一次都不动。我们以前在这里 `closeEngine()`，于是每次切歌都重来一遍
        // claim → alt2 → SET_CUR 设率 → clear_halt → 新建整条流水线；设备必须重锁时钟、
        // 重建内部通道拆分器，重建窗口里只锁上一路就是"某声道暂时没声"。
        // 这也解释了为什么现象与采样率、位深无关 —— 触发条件是重建本身。
        //
        // 拆除只放在 `release()`；换率/换声道由 `configure()` 的非复用分支负责。
        // 静音期间 `paused` 让 `fill()` 送帧对齐的零，与 AudioTrack 喂零等价，设备不会失锁。
        if (handle != 0L && engine !== OboeEngine) {
            engine.pause(handle)
            engine.flush(handle)
        } else {
            closeEngine()
        }
        positionUs = 0
        firstWriteDone = false
        failureReported = false
        handledEndOfStream = false
        writtenFramesByUs = 0L
        timestampBaseFrames = 0L
        readBaseFrames = 0L
        // 重置之后要用新位置的第一块 PTS 重建基准
        startMediaTimeUs = C.TIME_UNSET
    }

    override fun release() {
        // 会话结束：把调用线程的优先级还回去，别让下一条通路继承我们的 boost
        boostPlaybackThread(false)
        systemSink?.let { it.release(); return }
        closeEngine()
        // 会话（USB 的 UsbDeviceConnection）到这里才关：换曲只重建流，不重开会话
        engine.release()
    }

    // ── 内部 ────────────────────────────────────────────────────────

    /**
     * 关流。
     *
     * 顺序上仍然**先把句柄清零再释放**：`getCurrentPositionUs` 与配置线程会同时读它，
     * 清零之后所有读者立刻走 `CURRENT_POSITION_NOT_SET`，不会在已释放的指针上取值
     * （那就是 `SIGSEGV in ExoPlayer:Playb → getCurrentPositionUs` 那条崩溃栈）。
     *
     * native 侧的 `nativeClose` 会 `requestStop` → `close` → 释放对象，其中 `close()`
     * 是阻塞的，会等回调线程退出，所以这里不需要（也没有）自己的写线程要 join。
     */
    /**
     * 失败上报分流：USB 的失败不能写进 AAudio 的运行时黑名单。
     * 那张表记的是"HAL 在某个率上开不开得出独占"，被 USB 的一次写失败污染之后，
     * 同一个采样率连 AAudio 都不再尝试 —— 表现为"切一次歌后独占永久失效"。
     */
    private fun reportFailure(denial: ExclusiveDenial, detail: String) {
        if (engine === OboeEngine) {
            AudioDirectProbe.markExclusiveFailure(sampleRate, denial, detail)
        } else {
            AudioDirectProbe.noteUsbFailure(denial, detail)
        }
    }

    private fun closeEngine() {
        val h = handle
        handle = 0L
        streamRaw = false
        // 新流的 native 增益从 1.0 起算，缓存值必须跟着归位
        pushedGain = 1f
        // 流没了：硬件音量的下发口必须一起撤掉，否则音量键会往一个已关闭的句柄写
        ExclusiveVolume.attachHardware(null, null)
        if (h != 0L) engine.close(h)
    }

    /**
     * 重建 / 复位之后"该不该出声"的**唯一判据**：sink 记的意图 **或** 播放器记的意图。
     *
     * 独占通路没有 mixer，出不出声只由我方 `engine.resume()/pause()` 决定；而恢复只有两条
     * 来路：Media3 调 `play()`，或者这里自己判。`play()` **只挂在 BUFFERING→READY 那一次
     * 迁移上**（见 [flush] 的注释），所以它靠不住 —— 尤其"播放中切 DSD 模式"这类重建：
     *
     * - 切歌会先 `_player.pause()` 再 `play()`，渲染器走一遍迁移，`play()` 一定来；
     * - 播放中切模式走 `_reloadCurrentSource()`，**没有 pause/play 循环**，`play()` 永不来。
     *
     * 以前三处重建分支末尾都写 `if (playRequested) resume else pause`，于是第二种情况
     * 只要 `playRequested` 是 false（上一次 `pause()` 留下的），新建好的流当场被停住：
     * 界面在播、进度在走、DAC 一片安静。这正是"Native 起播没声""播放中切 DoP 没声，
     * 切歌之后同一个 DoP 就有声"的成因。
     *
     * [playIntent] 才是"用户按没按暂停"的真相（= `Player.playWhenReady`）；真暂停时它也是
     * false，所以不会出现"按了暂停又自己响"。resume 时顺手把 `playRequested` 抬成 true，
     * 让这个内部标志与事实收敛，后续分支不再读到过期值。
     */
    private fun applyPlayIntentToEngine() {
        if (handle == 0L) return
        if (playRequested || playIntent()) {
            playRequested = true
            engine.resume(handle)
        } else {
            engine.pause(handle)
        }
    }

    /**
     * 独占成立时把**调用本方法的线程**提到音频优先级，落回系统通路时复原。
     *
     * 调用点是 `configure` / `release`，都由 Media3 的渲染器在 ExoPlayer 的 Playback 线程上
     * 发起 —— 也就是实际跑"解码 → 重采样 → 打包 → 写环"的那条线程。
     *
     * 为什么必须由它来提：系统通路上同类工作跑在 AudioFlinger 的 FastMixer 里，天然被
     * boost；我们绕开 mixer 之后这些活落到普通 app 线程上，调度器会把突发计算丢到小核，
     * 表现就是"独占时一个小核占用率特别高"。native 的事件线程**不提**（那边提反而抢这里）。
     */
    private fun boostPlaybackThread(on: Boolean) {
        if (on == playbackThreadBoosted) return
        playbackThreadBoosted = on
        runCatching {
            android.os.Process.setThreadPriority(
                if (on) {
                    android.os.Process.THREAD_PRIORITY_AUDIO
                } else {
                    android.os.Process.THREAD_PRIORITY_DEFAULT
                },
            )
        }
    }

    /** 独占成立时把优先级给这条线程，落回系统通路时复原 */
    private var playbackThreadBoosted = false

    /**
     * 开流时要向 native 声明的**内容位宽**；raw DSD 一律 0（不表态）。
     *
     * DoP 的 24 bit 帧与 Native 的 32 bit word 都是容器语义，由 alt 过滤条件各自决定，
     * 掺进"按内容挑"会挑错。
     */
    private fun wantWireBits(): Int = if (rawFlowActive()) 0 else wireBitsHint

    /**
     * 把流重建到本首需要的规格（native 那边能只换时钟就不拆流）。
     *
     * 抽出来是为了**重试**：`reopen` 唯一会失败的原因是上一条流的 transfer 还没排空，
     * 那属于时序问题，隔一下重来就好；一旦放弃就会落到"重开会话"这条最重的路。
     */
    private fun reopenStream(raw: Boolean, format: Format, wire: Int): Boolean =
        if (raw) {
            engine.reopenRaw(handle, format.sampleRate, format.channelCount)
        } else {
            engine.reopen(handle, format.sampleRate, format.pcmEncoding, format.channelCount, wire)
        }

    /**
     * 重置停滞判定的基准 —— 每次**新开流之后**必须调用。
     *
     * `framesRead` 是每条流各自从 0 开始累计的；换流后不重置基准，
     * 新流的 0 会小于旧流的读数，于是"没有增长"立刻成立 → 1.5 秒后误判停滞。
     */
    private fun resetConsumeWatchdog() {
        lastReadFrames = if (handle != 0L) engine.framesRead(handle) else 0L
        lastConsumeMs = android.os.SystemClock.elapsedRealtime()
    }

    private fun bytesPerSample(depth: Int): Int = when (depth) {
        16 -> 2
        24 -> 3
        else -> 4
    }

    /**
     * `aaudio_format_t` 常量，必须与 native 侧的映射一字不差：
     * `PCM_I16 = 1`、`PCM_FLOAT = 2`、`PCM_I24_PACKED = 3`、`PCM_I32 = 4`。
     * 用它回读"设备实际接受的格式"，才能判断这条流是不是真的原样。
     */
    private fun aaudioFormatOf(depth: Int): Int = when (depth) {
        16 -> 1
        24 -> 3
        else -> 2
    }

    /**
     * 回读的格式是否算"原样"。
     *
     * 只放宽一种情况：**24 bit 被 HAL 装进 32 位整数容器**（`I24_PACKED` → `I32`）。
     * 那是无损扩展（每样本左移 8 位、容器变宽），数据没丢，很多 HAL 的 MMAP profile
     * 就是这么声明的（本机 PLQ110 的 `mmap_no_irq_out` 同时有 24_PACKED 和 32）。
     *
     * 严格相等会在这种 HAL 上产生**假阴性**：明明拿到了独占，却因"格式不符"回落系统通路。
     * 而真正的失败只有两种 —— **位深被降**（32→16，砍掉有效位）和**浮点/整数互换**
     * （那不是容器变宽，是解释方式变了）。这两种仍然判失败。
     */
    private fun formatAcceptable(wanted: Int, actual: Int): Boolean =
        actual == wanted || (wanted == 3 && actual == 4)

    /** `aaudio_format_t` → 位深，用于上报"端点上真正跑的位深" */
    private fun depthOfAaudioFormat(format: Int): Int = when (format) {
        1 -> 16
        3 -> 24
        4 -> 32
        else -> 0
    }

    private companion object {
        /**
         * 写不进去 + 设备读不走，持续这么久就判定这条流已废。
         *
         * 正常背压最长几百毫秒（环形缓冲 250 ms + 在飞 64 ms）。留到 3 s 是因为
         * 进后台后事件线程会被调度挪开：1.5 s 会把「能自己恢复的卡顿」判成设备死亡，
         * 而这里一旦关流就不可逆（要切歌才重建）。
         */
        const val STALL_TIMEOUT_MS = 3_000L
    }
}
