package com.annieplayer.annie_music.kt.core.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import com.annieplayer.annie_music.kt.core.audio.dsp.NativeOboeStream
import com.annieplayer.annie_music.kt.core.audio.dsp.NativeUacStream
import com.annieplayer.annie_music.kt.core.diagnostics.RuntimeLog
import com.annieplayer.annie_music.kt.core.state.MoreSettings
import java.nio.ByteBuffer

/** [ExclusiveOutput.configure] 的结果 —— 决定上层还能不能退回系统通路 */
internal enum class ExclusiveOpenResult {
    /** 独占流成立（复用 / 重建 / 新开都算） */
    Opened,

    /** 没成立，但这一首**允许**退回系统混音（普通 PCM） */
    FallbackAllowed,

    /**
     * 没成立而且**不许退**：这一首送的是 DoP / raw DSD 帧。
     *
     * 系统通路里那条 24bit → float 的处理器会把 DoP 的 0x05 / 0xFA 标记除成 ~1e-7 的直流，
     * DAC 再也认不出合法帧序列。硬送出去等于装作在播，所以必须显式判失败，
     * 由上层记进失败记忆并按 PCM 重建。
     */
    ForceFail,
}

/**
 * **独占通路**（USB 直连 libusb / AAudio EXCLUSIVE）作为一条 [OutputPort]。
 *
 * 内核全在 native（`native-uac.cpp` / `native-oboe.cpp`）；这一层只做编排：
 * 选引擎 → 开流 → 把 PCM 拷进环形缓冲 → 用 `framesRead` 推时间轴。
 *
 * ## 三个不能抄近路的地方
 *
 * 1. **一次 configure 的意图是一个值**（[request]），不许中途回读上游可变状态 ——
 *    两次读之间翻面会让同一首歌的两半走不同的 alt（PCM 进 raw alt = 没声）。
 * 2. **`play()` 靠不住**：它只挂在 BUFFERING→READY 迁移上，而"播放中切模式"没有那次迁移。
 *    所以每次重建之后一律走 [applyPlayIntentToEngine]（sink 意图 **或** 播放器意图）。
 * 3. **失败只报一次**：每个 buffer 都报会重建几百次音源。
 */
@OptIn(UnstableApi::class)
internal class ExclusiveOutput(
    /** 现在该不该试独占（透明模式才试） */
    private val exclusiveWanted: () -> Boolean = { true },
    /** 独占中途失败时通知上游（重建音源 → 下次 configure 选中系统引擎） */
    private val onEngineFailure: (String, String) -> Unit = { _, _ -> },
    /** 这一首选哪个直出内核；null 也退到 AAudio */
    private val engineSelector: () -> PcmEngine? = { OboeEngine },
    /** 已经开着的那个引擎现在还算不算数（通路没被切走） */
    private val engineStillValid: () -> Boolean = { false },
    /** 实际生效通路 / 降级原因上报 */
    private val onRouting: (OutputPath, DowngradeNotice?, Int) -> Unit = { _, _, _ -> },
    /** USB 走不成的原因；由宿主按当前设备状态回答（本类拿不到 Context） */
    private val usbUnavailable: () -> DowngradeReason = { DowngradeReason.UsbOpenFailed },
    /** 这一次 configure 要什么（见 [OutputRequest]） */
    private val request: () -> OutputRequest = { OutputRequest.Conservative },
    /** 独占通路的线性增益（`ExclusiveVolume`） */
    private val exclusiveGain: () -> Float = { 1f },
    /** 上游此刻想不想播（`Player.playWhenReady`） */
    private val playIntent: () -> Boolean = { true },
    /** sink 的 listener：上报"位置不连续 / 开始推进" */
    private val listenerProvider: () -> AudioSink.Listener? = { null },
) : OutputPort {

    override val path: OutputPath
        get() = if (engine === OboeEngine) OutputPath.aaudioExclusive else OutputPath.usbDirect

    // ── 流与规格 ──
    private var engine: PcmEngine = OboeEngine

    @Volatile private var handle: Long = 0L
    private var sampleRate = 0
    private var channelCount = 0
    private var bytesPerFrame = 0
    private var pcmDepth = 32
    private var pcmEncoding = C.ENCODING_PCM_16BIT

    /** 这一首**源**位深（`PcmAudioSink` 在处理器链之前读出）；只在开流时用来挑 alt */
    var wireBitsHint: Int = 0

    /** 端点上真正跑的位深（USB = alt 的 subslot，AAudio = HAL 回读格式） */
    var endpointDepth: Int = 0
        private set

    /** 这条流建在 raw DSD alt 上（只有 Native）。raw 与 PCM 的 alt 互斥，复用必须比对 */
    private var streamRaw = false

    /** 这条流上跑的不是 PCM 样本（DoP / Native）—— configure 时定死，增益与 DSP 的唯一判据 */
    @Volatile private var streamRawFrames = false

    /** 开流时的传输标签（`方式@档位`）；失败按它回报，不能现读全局量 */
    @Volatile private var openedTransportTag: String = ""

    // ── 播放意图 ──
    @Volatile private var playRequested = false
    private var stalePauseBuffers = 0
    private var deadSinceMs = 0L
    @Volatile private var pushedGain = 1f

    // ── 时间轴与记账 ──
    private var firstWriteDone = false
    private var handledEndOfStream = false
    private var failureReported = false
    private var positionUs = 0L
    private var lastStatsMs = 0L
    private var writtenFramesByUs = 0L
    private var timestampBaseFrames = 0L
    private var readBaseFrames = 0L
    private var lastReadFrames = 0L
    private var lastConsumeMs = 0L
    @Volatile private var outputStreamOffsetUs = 0L
    @Volatile private var startMediaTimeUs = C.TIME_UNSET
    private var playbackThreadBoosted = false

    /** 上一次上报的通路 / 降级原因（只在变化时推，避免每首歌都推一次状态） */
    private var reportedPath: OutputPath? = null
    private var reportedNotice: DowngradeNotice? = null

    // ── 对外 ──

    /** 上游是否想播（sink 自己的 `playRequested`，全局只有这一份） */
    val wantsPlay: Boolean get() = playRequested

    /** `AudioSink.setOutputStreamOffsetUs`：位置零点要把它加回去 */
    fun setOutputStreamOffsetUs(offsetUs: Long) {
        outputStreamOffsetUs = offsetUs
    }

    /** 当前这条流是否还活着；`false` = 只有拆流重建能救（`dead` 只在 startStream 清） */
    override fun isHealthy(): Boolean = handle == 0L || engine.isHealthy(handle)

    /**
     * 打开 / 复用 / 重建这条独占流。
     *
     * @return 见 [ExclusiveOpenResult]；只有 [ExclusiveOpenResult.Opened] 时本对象才可写入
     */
    fun configure(format: Format): ExclusiveOpenResult {
        // 本方法内**只读一次**上游意图（类注释第 1 条）
        val req = runCatching { request() }.getOrDefault(OutputRequest.Conservative)
        val wantRaw = req.rawAlt
        openedTransportTag = req.transportTag
        streamRawFrames = req.rawFrames

        // 死流不许复用：一直抱着它会让**连普通 PCM 也一起哑**，直到某次走"新会话"
        if (handle != 0L && !engine.isHealthy(handle)) {
            RuntimeLog.record("独占", "流已失效（dead）→ 丢弃并重开会话")
            closeEngine()
        }

        // 同规格切歌不重建流：每次重建都要放掉接口再 claim，DAC 要重锁时钟
        val reusable = handle != 0L && engine !== OboeEngine && engineStillValid() &&
            streamRaw == wantRaw &&
            sampleRate == format.sampleRate &&
            channelCount == format.channelCount
        if (reusable) {
            val depth = depthOf(format.pcmEncoding)
            pcmDepth = depth
            pcmEncoding = format.pcmEncoding
            bytesPerFrame = bytesPerSample(depth) * channelCount
            // 留 20 ms 旧尾部垫住：环清空后 `fill()` 立刻送零，设备要重新对齐通道
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
            listenerProvider()?.let { runCatching { it.onPositionDiscontinuity() } }
            publishRouting(path)
            return ExclusiveOpenResult.Opened
        }

        // 换率 / 换声道：只重建流，不重开会话。失败重试一次再放弃（是时序问题）
        val rebuilt = handle != 0L && engine !== OboeEngine && engineStillValid() &&
            run {
                val raw = wantRaw
                val wire = wantWireBits()
                // 退避重试 3 次（60 / 180 / 420 ms）。`reopen` 失败的唯一原因是上一条流的
                // transfer 还没排空，而那是**几十毫秒到几百毫秒**量级：一次 60 ms 常常不够，
                // 一放弃就落到"重开会话"这条最重的路（设备掉电重初始化、DAC 重锁 PLL）。
                var ok = false
                var delayMs = 60L
                for (attempt in 0 until 3) {
                    ok = reopenStream(raw, format, wire)
                    if (ok) break
                    Thread.sleep(delayMs)
                    delayMs = (delayMs * 3).coerceAtMost(500L)
                }
                if (ok) streamRaw = raw
                // 重开"成功"不等于流还活着：换率时端点可能在这里错一次 transfer
                ok && engine.isHealthy(handle)
            }
        if (rebuilt) {
            sampleRate = format.sampleRate
            channelCount = format.channelCount
            val depth = depthOf(format.pcmEncoding)
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
            listenerProvider()?.let { runCatching { it.onPositionDiscontinuity() } }
            publishRouting(path)
            RuntimeLog.record(
                "USB",
                "换规格重建：${format.sampleRate}Hz/${pcmDepth}bit/${channelCount}ch " +
                    "rawAlt=$streamRaw rawFrames=$streamRawFrames",
            )
            return ExclusiveOpenResult.Opened
        }

        // 这一首重新开始：先收干净旧流，再一次性选定引擎
        closeEngine()
        engine.release()
        positionUs = 0
        firstWriteDone = false
        failureReported = false
        deadSinceMs = 0L
        stalePauseBuffers = 0
        handledEndOfStream = false
        writtenFramesByUs = 0L
        timestampBaseFrames = 0L
        readBaseFrames = 0L
        startMediaTimeUs = C.TIME_UNSET

        val depth = depthOf(format.pcmEncoding)
        sampleRate = format.sampleRate
        channelCount = format.channelCount
        pcmDepth = depth
        pcmEncoding = format.pcmEncoding
        bytesPerFrame = bytesPerSample(depth) * channelCount

        engine = engineSelector() ?: OboeEngine
        val oboeEngine = engine === OboeEngine
        val wantedFormat = aaudioFormatOf(depth)
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
            // 事前拒绝**不写进运行时黑名单**：那不是失败，记进去会让"在系统通路播过一首"
            // 把该率永久拉黑（见 `ExclusivePolicyTest`）
            AudioDirectProbe.recordAaudioActive(false)
            AudioDirectProbe.notePreDenial(verdict.denial)
        }
        if (verdict is ExclusiveVerdict.Possible) {
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
                var realRate = 0
                var realFormat = 0
                // AAudio 才需要回读校验：HAL 可能给一条 EXCLUSIVE 流却把率/格式换掉
                val denial = if (oboeEngine) {
                    val realSharing = NativeOboeStream.sharingMode(handle)
                    realRate = NativeOboeStream.sampleRate(handle)
                    realFormat = NativeOboeStream.format(handle)
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
                    // 硬件音量优先：衰减在 DAC 内部，送出去的 PCM 一个字节都不动
                    val hwCaps = engine.hardwareVolumeCaps(handle)
                    ExclusiveVolume.attachHardware(
                        caps = hwCaps,
                        writer = if (hwCaps == null) {
                            null
                        } else {
                            { v -> engine.setHardwareVolume(handle, v) }
                        },
                    )
                    pushedGain = if (streamRawFrames) 1f else exclusiveGain()
                    engine.setGain(handle, pushedGain)
                    boostPlaybackThread(true)
                    applyPlayIntentToEngine()
                    if (oboeEngine) {
                        AudioDirectProbe.recordAaudioActive(true)
                        RuntimeLog.record(
                            "AAudio",
                            "独占流已开：${sampleRate}Hz/${channelCount}ch/${depth}bit · " +
                                "Oboe" + when (NativeOboeStream.audioApi(handle)) {
                                    1 -> "（AAudio）"
                                    2 -> "（OpenSL ES，无独占）"
                                    else -> ""
                                } + " · 缓冲 ${NativeOboeStream.bufferSize(handle)}/" +
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
                    listenerProvider()?.let { runCatching { it.onPositionDiscontinuity() } }
                    endpointDepth = if (oboeEngine) {
                        depthOfAaudioFormat(NativeOboeStream.format(handle))
                    } else {
                        val frameBytes = NativeUacStream.outFrameBytes(handle)
                        if (frameBytes > 0 && channelCount > 0) {
                            (frameBytes / channelCount) * 8
                        } else {
                            0
                        }
                    }
                    publishRouting(path)
                    return ExclusiveOpenResult.Opened
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

        // 走到这里说明独占没成立
        AudioDirectProbe.recordAaudioActive(false)
        boostPlaybackThread(false)
        if (wantRaw) {
            // **不要在这里上报 systemMix**：`_effectivePath` 会被当成判定依据用回去，
            // 谎报事实会让 DSD 判定自激（失败→降级→换路→再失败，每轮拆一次流）。
            handle = 0L
            onEngineFailure("raw DSD 需要独占通路，系统通路会改写帧标记", openedTransportTag)
            return ExclusiveOpenResult.ForceFail
        }
        return ExclusiveOpenResult.FallbackAllowed
    }

    /** 退回系统通路时该报的降级原因（按用户意图取，不编"未知错误"） */
    fun fallbackReason(): Pair<DowngradeReason?, String> = when (MoreSettings.outputPath.value) {
        OutputPath.usbDirect -> usbUnavailable() to ""
        OutputPath.aaudioExclusive -> DowngradeReason.AaudioDenied to
            AudioDirectProbe.lastDenialLabel().orEmpty()
        OutputPath.systemMix -> null to ""
    }

    /** 由 facade 在系统通路成立后调用，把"实际生效 systemMix"上报出去 */
    fun publishSystemFallback() {
        val (reason, detail) = fallbackReason()
        publishRouting(OutputPath.systemMix, reason, detail)
    }

    // ── 写入 ──

    override fun write(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        if (handle == 0L || bytesPerFrame <= 0) {
            // 流不在了但数据还在来：等 1.5 s 再报（configure 期间也会短暂出现 handle == 0）。
            // **必须返回 false**：返回 true 等于告诉 Media3"这块播出去了"，
            // 故障会被伪装成正常播放（进度条走、没声、onPlayerError 不触发）。
            // 暂停中一律不报：用户按了暂停，流闲着不是故障。
            if (handle == 0L && buffer.remaining() > 0 && playIntent()) {
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
            return false
        }

        deadSinceMs = 0L

        // 写路径是唯一每块都会经过的地方：在这里发现 dead 立刻重建，
        // 不等"环满且设备不消费 3 秒"的看门狗（它还要 playRequested 成立）。
        // 不写进 AudioDirectProbe 的失败记忆 —— 瞬时故障不该把该率永久降级。
        if (!engine.isHealthy(handle)) {
            if (!failureReported) {
                failureReported = true
                RuntimeLog.record("独占", "写入前发现流已失效（dead）→ 丢弃并重建")
                logNativeFacts("写前失活")
                closeEngine()
                onEngineFailure("独占流已失效", openedTransportTag)
            }
            return true
        }

        val remaining = buffer.remaining()
        if (remaining < bytesPerFrame) return true

        // 与上游播放意图对齐（只动"想播"这一侧）：`play()` 只挂在 BUFFERING→READY
        // 迁移上，focus 抖动回来没有任何迁移，靠它抬不起闸门。
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

        // 设备还在不在消费：只有 framesRead 增长才算（用原始计数，不带基准）
        val now = android.os.SystemClock.elapsedRealtime()
        val readNow = engine.framesRead(handle)
        if (readNow > lastReadFrames) {
            lastReadFrames = readNow
            lastConsumeMs = now
        }

        // 从 duplicate 读，原 buffer 的 position 留到确认写入后再推进
        val bytes = ByteArray(remaining)
        buffer.duplicate().get(bytes, 0, remaining)

        // 增益在 native 的消费侧施加（环里存未衰减样本），换音量只剩在飞数据的延迟。
        // **DSD 直出一律 1.0**：DoP 的标记在最低位，乘任何系数都会毁掉它。
        val gain = if (streamRawFrames) 1f else exclusiveGain()
        if (gain != pushedGain) {
            engine.setGain(handle, gain)
            pushedGain = gain
        }

        val frames = engine.write(handle, bytes, 0, remaining, bytesPerFrame)
        if (frames < 0) {
            // 流已废。必须立刻关流：handle 不归零的话 Media3 会用同一块反复重试，
            // 每秒数百次 onEngineFailure → 每次都重建整条音源。
            if (!failureReported) {
                failureReported = true
                reportFailure(ExclusiveDenial.WriteFailed, "err=$frames")
                logNativeFacts("写入失败 err=$frames")
                closeEngine()
                onEngineFailure("写入失败（err=$frames）", openedTransportTag)
            }
            return true
        }
        if (frames == 0) {
            // 环满 = 有数据、设备不收。偶尔是正常背压；持续不发说明这条流不再消费，
            // 必须主动掐掉，否则 `written > played` 永久成立 ⇒ isEnded 永远 false。
            // `playRequested` 这个条件必须有：暂停时回调只出静音、本来就不读环。
            if (playRequested && now - lastConsumeMs > STALL_TIMEOUT_MS) {
                reportFailure(ExclusiveDenial.Stalled, "${STALL_TIMEOUT_MS}ms 内已播帧无增长")
                logNativeFacts("停滞 ${STALL_TIMEOUT_MS}ms")
                onEngineFailure(
                    "设备不消费数据（${STALL_TIMEOUT_MS}ms 内已播帧无增长）",
                    openedTransportTag,
                )
                closeEngine()
                return true
            }
            return false
        }

        buffer.position(buffer.position() + frames * bytesPerFrame)
        writtenFramesByUs += frames
        if (startMediaTimeUs == C.TIME_UNSET) {
            // 时间轴基准 = 这块里第一个还没写出去的帧的媒体时间
            startMediaTimeUs = presentationTimeUs
        }
        if (!firstWriteDone) {
            firstWriteDone = true
            listenerProvider()?.let { runCatching { it.onPositionAdvancing(startTimeBasis()) } }
        }
        return !buffer.hasRemaining()
    }

    // ── 时钟 ──

    override fun positionUs(sourceEnded: Boolean): Long {
        if (handle == 0L || startMediaTimeUs == C.TIME_UNSET || !firstWriteDone) {
            return C.TIME_UNSET
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
     * 位置零点（媒体时间轴）。`startMediaTimeUs` 是 `write` 给的 PTS，是否已含 offset 随源而定，
     * 所以按"PTS 明显小于 offset 就补上"处理。
     * ⚠️ 别改成"减去"：那会让位置小于 startPositionUs ⇒ periodPositionUs 变负 ⇒ 进度条钉在原点。
     */
    private fun startTimeBasis(): Long =
        startMediaTimeUs + if (startMediaTimeUs < outputStreamOffsetUs) outputStreamOffsetUs else 0L

    /** 设备真的播出去多少帧：硬件时间戳优先，退回 `framesRead`（静音填充不计） */
    private fun playedFramesNow(): Long {
        engine.timestamp(handle)?.let { stamp ->
            val frames = stamp[0] - timestampBaseFrames
            if (frames > 0) return frames
        }
        val readFrames = engine.framesRead(handle)
        return (readFrames - readBaseFrames).coerceAtLeast(0)
    }

    // ── 生命周期 ──

    override fun play() {
        playRequested = true
        if (handle != 0L) engine.resume(handle)
    }

    /**
     * 暂停 = 让回调输出静音，不 `requestPause` 流本身。
     * `AAudioStream_getState` 在这类 HAL 上会长期停在 PAUSING，真 pause 之后再也回不到 STARTED。
     */
    override fun pause() {
        playRequested = false
        stalePauseBuffers = 0
        if (handle != 0L) engine.pause(handle)
    }

    override fun flush(playRequested: Boolean) {
        if (handle != 0L) {
            if (engine !== OboeEngine) {
                // USB 不重建：唯一的中转就是我方环形缓冲，清掉它就是水位归零
                engine.flush(handle)
                writtenFramesByUs = 0L
                timestampBaseFrames = engine.timestamp(handle)?.get(0) ?: 0L
                readBaseFrames = engine.framesRead(handle)
            } else {
                // AAudio：seek 必须重建整条流（`requestFlush` 返回成功但旧音频仍被读走）
                val sr = sampleRate
                val ch = channelCount
                val enc = pcmEncoding
                closeEngine()
                val opened = if (sr > 0 && ch > 0) engine.open(sr, enc, ch) else 0L
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
        startMediaTimeUs = C.TIME_UNSET
        applyPlayIntentToEngine()
        listenerProvider()?.let { runCatching { it.onPositionDiscontinuity() } }
    }

    override fun reset() {
        // USB 不拆流：拆一次就要 claim + 设率 + 重建流水线，设备重锁时钟那一段
        // 就是"某声道暂时没声"的来源。拆除只放在 release()。
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
        startMediaTimeUs = C.TIME_UNSET
    }

    override fun release() {
        boostPlaybackThread(false)
        closeEngine()
        // 会话（USB 的 UsbDeviceConnection）到这里才关：换曲只重建流
        engine.release()
    }

    override fun playToEndOfStream() {
        handledEndOfStream = true
    }

    override fun isEnded(): Boolean {
        if (handle == 0L) return true
        return handledEndOfStream && !hasPendingData()
    }

    /**
     * 还有没有没播出去的数据 = 已写帧 > 已播帧。
     *
     * 这个判据两头都不能歪：恒 true 会让播放器永远停在 READY、判不出播完；
     * 过于乐观地报 false 会掉进 BUFFERING 把 renderer 停掉。
     */
    override fun hasPendingData(): Boolean {
        if (handle == 0L) return false
        return writtenFramesByUs > playedFramesNow()
    }

    // ── 内部 ──

    /** 上报实际生效通路。只在**变化**时推 —— 本方法每次切歌都会经过 */
    private fun publishRouting(
        actual: OutputPath,
        reason: DowngradeReason? = null,
        detail: String = "",
    ) {
        val notice = downgradeNoticeFor(MoreSettings.outputPath.value, actual, reason, detail)
        if (actual == reportedPath && notice == reportedNotice) return
        reportedPath = actual
        reportedNotice = notice
        onRouting(actual, notice, endpointDepth)
    }

    /**
     * 失败上报分流：USB 的失败不能写进 AAudio 的运行时黑名单 ——
     * 那张表记的是"HAL 在某个率上开不开得出独占"，被 USB 写失败污染后，
     * 同一个采样率连 AAudio 都不再尝试。
     */
    private fun reportFailure(denial: ExclusiveDenial, detail: String) {
        if (engine === OboeEngine) {
            AudioDirectProbe.markExclusiveFailure(sampleRate, denial, detail)
        } else {
            AudioDirectProbe.noteUsbFailure(denial, detail)
        }
    }

    /**
     * 把 native 侧的**事实**打成一条日志（`日志诊断` 页可见）。
     *
     * 只在**故障路径**调用 —— 正常播放不产生任何日志。存在的理由：这台 ROM 丢 App 日志，
     * native 里发生了什么（transfer 失败 / 欠载 / 换率被拒 / 置 dead）在上层完全不可见，
     * 于是每个这类问题都只能靠猜或抓包。快照 + 事件环是唯一能让它开口的通道。
     *
     * **必须在 `closeEngine()` 之前调用**：句柄一清零，快照就取不到了。
     */
    private fun logNativeFacts(reason: String) {
        if (engine === OboeEngine) return
        val h = handle
        if (h == 0L) return
        val s = runCatching { NativeUacStream.snapshot(h) }.getOrNull() ?: return
        val events = runCatching { NativeUacStream.drainEvents(h) }.getOrDefault(IntArray(0))
        val ev = buildString {
            var i = (events.size / 3 - 6).coerceAtLeast(0)
            while (i * 3 + 2 < events.size) {
                if (isNotEmpty()) append(' ')
                append(events[i * 3]).append(':').append(events[i * 3 + 1])
                    .append('/').append(events[i * 3 + 2])
                i++
            }
        }
        RuntimeLog.record(
            "USB",
            "$reason · dead=${s.getOrElse(0) { -1 }} paused=${s.getOrElse(1) { -1 }} " +
                "stop=${s.getOrElse(2) { -1 }} rate=${s.getOrElse(3) { 0 }} " +
                "ch=${s.getOrElse(4) { 0 }} frame=${s.getOrElse(5) { 0 }}B " +
                "subslot=${s.getOrElse(6) { 0 }} alt=${s.getOrElse(7) { -1 }} " +
                "read=${s.getOrElse(8) { 0 }} written=${s.getOrElse(9) { 0 }} " +
                "under=${s.getOrElse(10) { 0 }} err=${s.getOrElse(12) { 0 }}" +
                if (ev.isEmpty()) "" else " ev=[$ev]",
        )
    }

    private fun closeEngine() {
        val h = handle
        // 先清零再释放：位置查询与配置线程会同时读它
        handle = 0L
        streamRaw = false
        pushedGain = 1f
        // 流没了：硬件音量下发口必须一起撤掉，否则音量键会往已关闭的句柄写
        ExclusiveVolume.attachHardware(null, null)
        if (h != 0L) engine.close(h)
    }

    /**
     * 重建 / 复位之后"该不该出声"的**唯一判据**：sink 记的意图 **或** 播放器记的意图。
     * 播放中切模式走 `_reloadCurrentSource()`，没有 pause/play 循环，`play()` 永不来。
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

    /** 独占成立时把**调用线程**（ExoPlayer 的 Playback 线程）提到音频优先级 */
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

    /** 开流时要向 native 声明的内容位宽；raw DSD 一律 0（不表态，由 alt 过滤条件决定） */
    private fun wantWireBits(): Int = if (streamRawFrames) 0 else wireBitsHint

    private fun reopenStream(raw: Boolean, format: Format, wire: Int): Boolean =
        if (raw) {
            engine.reopenRaw(handle, format.sampleRate, format.channelCount)
        } else {
            engine.reopen(handle, format.sampleRate, format.pcmEncoding, format.channelCount, wire)
        }

    /** 每次新开流之后必须重置：`framesRead` 每条流从 0 开始，不重置会误判停滞 */
    private fun resetConsumeWatchdog() {
        lastReadFrames = if (handle != 0L) engine.framesRead(handle) else 0L
        lastConsumeMs = android.os.SystemClock.elapsedRealtime()
    }

    private fun depthOf(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_16BIT -> 16
        C.ENCODING_PCM_24BIT -> 24
        else -> 32
    }

    private fun bytesPerSample(depth: Int): Int = when (depth) {
        16 -> 2
        24 -> 3
        else -> 4
    }

    /** `aaudio_format_t`：PCM_I16 = 1、PCM_FLOAT = 2、PCM_I24_PACKED = 3、PCM_I32 = 4 */
    private fun aaudioFormatOf(depth: Int): Int = when (depth) {
        16 -> 1
        24 -> 3
        else -> 2
    }

    /** 回读格式是否算"原样"：只放宽 24 bit 被 HAL 装进 32 位容器这一种（无损扩展） */
    private fun formatAcceptable(wanted: Int, actual: Int): Boolean =
        actual == wanted || (wanted == 3 && actual == 4)

    private fun depthOfAaudioFormat(format: Int): Int = when (format) {
        1 -> 16
        3 -> 24
        4 -> 32
        else -> 0
    }

    private companion object {
        /** 写不进去 + 设备读不走持续这么久就判这条流已废（正常背压最长几百毫秒） */
        const val STALL_TIMEOUT_MS = 3_000L
    }
}
