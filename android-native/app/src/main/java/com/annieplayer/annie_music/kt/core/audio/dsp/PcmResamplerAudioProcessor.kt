package com.annieplayer.annie_music.kt.core.audio.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.annieplayer.annie_music.kt.core.audio.PLAN_FLOAT_DEPTH
import com.annieplayer.annie_music.kt.core.audio.shouldDither
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PCM 重采样 + 位深转换（链路里最靠出口的那一级）。
 *
 * ## 引擎
 *
 * 重采样交给 **r8brain-free-src**（MIT，header-only，见 `cpp/thirdparty/r8brain`）：
 * 2× 半带 + 分数延迟 sinc 的两级结构，2% 过渡带（0.98×Nyquist 以下平直）、
 * 阻带按源位深自适应（`6.02×bits+40`，封顶 206.91 dB）、全程 double、
 * 主滤波器**最小相位**（不是线性相位）—— 理由见 `native-r8b-resampler.cpp` 里
 * `kTransBand` 上面那整段：线性相位会在每个瞬态之前长出 6 ms 前置回声，
 * 打击乐密集的曲目听成一层沙状底噪，放宽过渡带只是拿高频换掉它。
 *
 * 之前这里是自研的多相 sinc。换掉的原因不是"参数不够狠"，而是**架构**：
 * 单级多相表要同时满足 0.99 通带 + −180 dB 时滤波器长度会到 2400 taps、
 * 系数表 4.9 MB、2.3 亿次乘加/秒 —— 单级结构在这个档位不成立。
 *
 * ## 职责边界（刻意保持很薄）
 *
 * - **不做**任何滤波兜底实现：native 不可用且确实需要重采样时，本处理器返回
 *   `NOT_SET` 整体旁路，让系统 mixer 去转 —— 宁可退化成"系统重采样"，
 *   也不维护第二套自研滤波器（那会变成两份要对齐的 DSP）。
 * - 做四件事：重采样（r8brain）、位深转换、降位深时的 16bit TPDF dither、
 *   **DSD 链路的输出低通**（[configureFor] 的 `lowpassHz`，见下）。
 *
 * ## 输出低通为什么挂在这里
 *
 * DSD 的噪声整形把噪声整个推到 20 kHz 以上，而我们的抽取只砍到目标 Nyquist。
 * 补那一级只能是"输出端"的低通，而输出端本来就是本处理器负责的 ——
 * 挂在 native 的同一个句柄里，才能共享"低通 + 位深 + 抖动"的同一次遍历，
 * 也才可能在**不需要重采样**（352.8k 档位）时照样生效。
 */
class PcmResamplerAudioProcessor : BaseAudioProcessor() {

    @Volatile private var _enabled: Boolean = false
    @Volatile private var _targetRate: Int = 0
    @Volatile private var _active: Boolean = false

    /**
     * 输出低通截止（Hz）；0 = 不要这一级。**只有 DSD 链路会传非 0**
     * （策略在 `DsdOutput.dsdPcmLowpassHz()`，这里只当 Hz 用）。
     *
     * 它是"有活要干"的第三种理由：以前 `onConfigure` 只认 `doResample || doDepth`，
     * 于是 DSD 的 352.8k 档位（decode 率 == 目标率，位深也不变）整条旁路，
     * 噪声整形的能量一路上到 176.4 kHz 送进 DAC。
     */
    @Volatile private var _targetLowpassHz: Int = 0

    /**
     * 阻带按多少 Bit 算；**0 = 按输入编码推断**。见 [configureFor] 的同名参数。
     */
    @Volatile private var _targetSourceBits: Int = 0

    /**
     * 过渡带（% of Nyquist）；**0 = 用 native 默认的 2.0**。见 [configureFor] 的同名参数。
     */
    @Volatile private var _targetTransBand: Double = 0.0

    /**
     * 输出位深：0 = 跟随输入，16 / 24 / 32（float）。
     *
     * **默认必须是 32（float），不能是 0。** 0 的语义是"跟随输入"，即"我们不接管位深"——
     * 那是**显式配置过**才成立的状态。未配置时按 0 处理等于替下游做了一次它没要求的量化。
     */
    @Volatile private var _targetDepth: Int = PLAN_FLOAT_DEPTH

    /**
     * 本次配置是否要加 TPDF 抖动 —— **由 [onConfigure] 自己算**，外面不用管。
     *
     * 判据只有一个事实：这一次是不是真的在做最后一次重量化（`shouldDither`）。
     * 以前这个值是 `setDither()` 从外面喂进来的，喂的依据是"降位深 / 源是有损编码"；
     * 后者是猜（有损解出来是 float，但"有损 ⇒ 要抖"并不成立），而且在
     * 「跟随源 + 重采样」这条路上**整条漏掉了** —— 重采样必然舍入，却被判成"没降位深"。
     */
    @Volatile private var _dither: Boolean = false

    /**
     * r8brain 句柄；0 = 同率（位深直通）或 native 不可用（整体旁路）。
     *
     * `@Volatile`：它在音频线程的 `onConfigure` 里写，而 `takeOutputPeak()` 可能从
     * 别的线程读。不加的话那个线程可能读到过期值（0）→ 峰值统计永远是"不知道"。
     */
    @Volatile private var _handle: Long = 0L

    private var _srcRate: Int = 0
    private var _dstRate: Int = 0
    private var _channels: Int = 2
    private var _inputEncoding: Int = C.ENCODING_INVALID
    private var _outputEncoding: Int = C.ENCODING_INVALID
    private var _inBytesPerSample: Int = 2
    private var _outBytesPerSample: Int = 2

    private var _inArray = ByteArray(0)
    private var _outArray = ByteArray(0)

    /**
     * 一次配好四个参数 —— **必须原子**。
     *
     * 以前这里是两个独立方法（`setEnabled(...)` 再 `setOutputDepth(...)`），
     * 播放层的两次调用之间有窗口，而 `onConfigure` 跑在音频线程上。实测后果很实在：
     *
     * ```
     * setEnabled(true, 48000)    ← _targetRate 有了，_targetDepth 还是旧值
     *     [音频线程 onConfigure 恰好落在这里]
     *                                 → targetEncoding = 输入编码（16 Bit）
     *                                 → doDepth = false、doResample = true
     *                                 → shouldDither(16, true) = **true**
     *                                 → 16 Bit 输出 + TPDF 抖动
     * setOutputDepth(32)         ← 太晚了，本次 configure 已经按 16 Bit 跑完
     * ```
     *
     * 症状：**需要重采样的曲目（44.1k 源 → 48k 链路）起播有量化底噪**；
     * 不需要 SRC 时 `doResample/doDepth` 全 false → 不抖 → 干净。
     * 先把位深写进去，最后才写 `_enabled`，让"启用"永远是配置完成的信号。
     *
     * @param lowpassHz 输出低通截止（Hz）；0 = 不要这一级。**只有 DSD 链路会传非 0**
     *   （`MediaPlayerBackend` 用 `DsdOutput.dsdPcmLowpassHz()` 算，本处理器不猜是不是 DSD）。
     * @param sourceBits 阻带按多少 Bit 算；**0 = 按输入编码自行推断**（旧行为）。
     *   非 0 时用调用方给的值 —— 语义是「**源的实际精度**」，不是解码器出口的容器格式。
     *   Apple 链路（ALAC/AAC 都走 ffmpeg）解出一律是 float，若按 32 Bit 算就会拿到
     *   206.91 dB / 1621 抽头的滤波器，而有损源的噪声底根本用不到。推导与实测见
     *   `core/audio/ResampleDepth.kt`。
     * @param transBand 过渡带（% of Nyquist）；**0 = 用 native 默认的 2.0**（无损那一档）。
     *   有损源传 4.5：编码器自己的低通早就在 17.5–21.6 kHz 切了带，把过渡带放宽
     *   不损失任何东西，只换来更短的滤波器（44.1k→48k / 136 dB：1081 → 约 480 抽头）。
     *   判据同样在 `core/audio/ResampleDepth.kt`，与 `sourceBits` **必须是同一个事实**。
     *
     * 参数变化不会热重配音频链路，下一次 `prepare` / 切歌自动生效。
     */
    fun configureFor(
        enabled: Boolean,
        targetRate: Int,
        targetDepth: Int,
        lowpassHz: Int = 0,
        sourceBits: Int = 0,
        transBand: Double = 0.0,
    ) {
        _targetTransBand = transBand
        _targetSourceBits = sourceBits
        _targetLowpassHz = lowpassHz
        _targetDepth = targetDepth
        _targetRate = targetRate
        // **不要在这里改 `_active`。**
        //
        // 曾经有过一句 `if (!enabled) _active = false`，理由是"切到 USB 独占后要立刻停止
        // SRC，不能等 `onConfigure`"。但它制造的窗口比它解决的问题更严重：
        // `configureFor` 是从播放层（主线程）调的，而出口此刻**仍然是上一首的格式**。
        // 处理器先停下来、出口还按"已转换"配置着 → 那块数据被原样送出去 → 位深 / 采样率
        // 全错 → 满幅噪声。典型触发：从需要重采样的一首（44.1k → 48k）切到本身就等于
        // 链路率的一首（48k）。
        //
        // 现在 `_active` 只由 `onConfigure` 改（那一次配置和出口的格式是同一件事），
        // 过渡窗口由 `PcmAudioSink.handleBuffer` 的契约校验兜住。
        // `_enabled = false` 照旧：它保证**下一次** `configure` 一定旁路。
        _enabled = enabled
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        _active = false
        _dither = false
        releaseHandle()

        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT &&
            encoding != C.ENCODING_PCM_24BIT &&
            encoding != C.ENCODING_PCM_FLOAT
        ) {
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
        // 低通的截止必须真的在输出 Nyquist 以下。策略（40 kHz / 夹取 / 什么时候不做）
        // 已经在 `DsdOutput.dsdPcmLowpassHz()` 里定了，这里只做最后一道安全判定。
        val outRate = if (doResample) _targetRate else inputAudioFormat.sampleRate
        val doLowpass = _targetLowpassHz > 0 && _targetLowpassHz * 2 < outRate
        // 抖动 = 「真的会舍入」× 「舍到 16 bit」。两个事实都在这里，判定在纯函数
        // `shouldDither` 里（可单测）：重采样必然舍入，改编码位深也必然舍入，
        // 低通同样要重新量化一次。
        _dither = shouldDither(depthOf(targetEncoding), doResample || doDepth || doLowpass)
        if (!doResample && !doDepth && !doLowpass) return AudioProcessor.AudioFormat.NOT_SET

        _srcRate = inputAudioFormat.sampleRate
        _dstRate = outRate
        _channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        _inputEncoding = encoding
        _outputEncoding = targetEncoding
        _inBytesPerSample = bytesOf(encoding)
        _outBytesPerSample = bytesOf(targetEncoding)

        // 只要有活干就建句柄：**同率 + 只有低通**也要建（`rs` 会留空，native 认这条）。
        if (doResample || doLowpass) {
            // 阻带目标按**源的实际精度**算（16 bit 源不需要 200 dB 的滤波器 —— 那只会更长更慢）。
            //
            // 优先用调用方给的 `sourceBits`（它知道编码/位深，能识破"float 容器 + 有损内容"
            // 这种假精度）；调用方不知道时退回"按解码器出口的编码推断"，也就是旧行为。
            val srcBits = if (_targetSourceBits > 0) {
                _targetSourceBits
            } else {
                when (encoding) {
                    C.ENCODING_PCM_FLOAT -> 32
                    C.ENCODING_PCM_24BIT -> 24
                    else -> 16
                }
            }
            _handle = if (NativeR8bResampler.isAvailable()) {
                NativeR8bResampler.create(
                    channels = _channels,
                    srcRate = _srcRate,
                    dstRate = _dstRate,
                    srcBits = srcBits,
                    lpfHz = if (doLowpass) _targetLowpassHz else 0,
                    transBand = _targetTransBand,
                )
            } else {
                0L
            }
            // native 不可用 → 整体旁路（不维护第二套自研滤波器）
            if (_handle == 0L) return AudioProcessor.AudioFormat.NOT_SET
        }

        _active = true
        return AudioProcessor.AudioFormat(_dstRate, _channels, targetEncoding)
    }

    override fun isActive(): Boolean = _active

    /**
     * 处理器**这一轮**承诺产出的格式；只有 [isActive] 为真时才有意义。
     *
     * 给 `PcmAudioSink` 做契约校验用：出口是按某个格式配置的，而这份配置只有在
     * 处理器确实按同一份契约产出时才成立。两者不一致时继续送字节，出口会按错的
     * 位深 / 率去解释 —— 听感就是满幅噪声（不是"音质差"，是噪声）。
     */
    val configuredOutputRate: Int get() = _dstRate
    val configuredOutputEncoding: Int get() = _outputEncoding
    /**
     * 取走「自上次调用以来」的输出真峰值与超满刻度样本数。
     *
     * `peak` 是线性值：**1.0 = 满刻度**，> 1.0 就是重采样之后超出了满刻度
     * （互样本过冲）。`clips` > 0 说明这些样本已经被夹掉了 —— 也就是**真的削波了**。
     *
     * 与音频线程并发安全：native 侧在句柄锁里取，调用方（播放层）按曲目累计。
     * 句柄为 0（整链旁路 / 处理器未激活）时返回 `0.0 to 0`，也就是"不知道"，
     * 不要当成"没有削波"。
     */
    fun takeOutputPeak(): Pair<Double, Int> {
        val h = _handle
        if (h == 0L) return 0.0 to 0
        val peak = NativeR8bResampler.takePeak(h)
        val clips = NativeR8bResampler.takeClipCount(h)
        return peak to clips
    }

    override fun getDurationAfterProcessorApplied(inputDurationUs: Long): Long =
        if (_active && _srcRate > 0) inputDurationUs * _dstRate / _srcRate else inputDurationUs

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!_active) return
        val size = inputBuffer.remaining()
        if (size <= 0) return
        val frames = size / (_inBytesPerSample * _channels)
        if (frames <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        if (_handle == 0L) {
            // 源率 == 目标率：只换位深，不需要滤波
            convertOnly(inputBuffer, frames)
            return
        }

        if (_inArray.size < size) _inArray = ByteArray(size)
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN).get(_inArray, 0, size)
        inputBuffer.position(inputBuffer.limit())

        // 输出缓冲上界**只能信 r8brain 自己的 `getMaxOutLen`**。
        //
        // 曾经改成"按比例算"（frames × dst/src + 余量）想省掉 285 KB 的分配，
        // 结果播到一分半钟就 `PlaybackState ERROR: Unexpected runtime error` ——
        // 说明那个比例上界在某些块长下**取小了**：JNI 侧只能把 `got` 钳到 `outFrameCap`，
        // 而 `process()` 已经把输入吃进内部状态，被钳掉的输出就是**永久丢失**，
        // 累积到一定程度 Media3 与 AudioTrack 的位置彻底对不上。
        //
        // `getMaxOutLen` 确实过于保守（实测恒返回 35667，与实际输入无关），
        // 但**保守是这里唯一安全的方向**：宁可多拷贝，不能少分配。
        // 想省内存要另想办法（例如让 native 侧自己对输出切片），不是在这里猜上界。
        val maxOutFrames = NativeR8bResampler.maxOutFrames(_handle, frames).coerceAtLeast(frames)
        val needBytes = maxOutFrames * _outBytesPerSample * _channels
        if (_outArray.size < needBytes) _outArray = ByteArray(needBytes)

        val written = NativeR8bResampler.processBytes(
            handle = _handle,
            input = _inArray,
            output = _outArray,
            inFrames = frames,
            inEncoding = _inputEncoding,
            outEncoding = _outputEncoding,
            dither = _dither,
        )

        val n = if (written > 0) written else 0
        val out = replaceOutputBuffer(n).order(ByteOrder.LITTLE_ENDIAN)
        if (n > 0) out.put(_outArray, 0, n)
        out.flip()
    }

    override fun onFlush() {
        if (_handle != 0L) NativeR8bResampler.reset(_handle)
    }

    override fun onReset() {
        _active = false
        releaseHandle()
    }

    private fun releaseHandle() {
        if (_handle != 0L) {
            NativeR8bResampler.release(_handle)
            _handle = 0L
        }
    }

    private fun bytesOf(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_FLOAT -> 4
        C.ENCODING_PCM_24BIT -> 3
        else -> 2
    }

    private fun depthOf(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_16BIT -> 16
        C.ENCODING_PCM_24BIT -> 24
        C.ENCODING_PCM_FLOAT -> 32
        else -> 0
    }

    /** 同率、只换 encoding 的直通换算（不涉及滤波，所以不属于"第二套重采样"） */
    private fun convertOnly(inputBuffer: ByteBuffer, frames: Int) {
        val channels = _channels
        val total = frames * channels
        val out = replaceOutputBuffer(total * _outBytesPerSample)
            .order(ByteOrder.LITTLE_ENDIAN)
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until total) {
            val v = readSample(inputBuffer)
            when (_outputEncoding) {
                C.ENCODING_PCM_FLOAT -> out.putFloat(v)
                C.ENCODING_PCM_24BIT -> {
                    // 比例与 `readSample` 的 ÷8388608 一致（读侧也是 8388608f）
                    val s = Math.round(v * 8388608.0).toInt().coerceIn(-8388608, 8388607)
                    out.put((s and 0xFF).toByte())
                    out.put((s shr 8 and 0xFF).toByte())
                    out.put((s shr 16 and 0xFF).toByte())
                }
                else -> out.putShort(
                    Math.round(v * 32768.0).toInt().coerceIn(-32768, 32767).toShort(),
                )
            }
        }
        out.flip()
        inputBuffer.position(inputBuffer.limit())
    }

    private fun readSample(inputBuffer: ByteBuffer): Float = when (_inputEncoding) {
        C.ENCODING_PCM_FLOAT -> inputBuffer.float
        C.ENCODING_PCM_24BIT -> {
            val b0 = inputBuffer.get().toInt() and 0xFF
            val b1 = inputBuffer.get().toInt() and 0xFF
            val b2 = inputBuffer.get().toInt() and 0xFF
            var v = b0 or (b1 shl 8) or (b2 shl 16)
            if (v and 0x800000 != 0) v = v or -0x1000000
            v / 8_388_608f
        }
        else -> inputBuffer.short / 32768f
    }
}
