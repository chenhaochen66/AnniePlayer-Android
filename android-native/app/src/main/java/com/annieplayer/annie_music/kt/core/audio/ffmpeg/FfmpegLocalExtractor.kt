package com.annieplayer.annie_music.kt.core.audio.ffmpeg

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import com.annieplayer.annie_music.kt.core.audio.dsd.DopPacker
import com.annieplayer.annie_music.kt.core.audio.dsd.DsdTransport
import com.annieplayer.annie_music.kt.core.audio.dsd.NativeDsdPacker
import java.io.IOException

/**
 * 本地全链路的 Media3 入口：把 [NativeFfmpegDemux] 解出来的 PCM 当成 `AUDIO_RAW` 样本喂给 Media3。
 *
 * 为什么用 Extractor 而不是自己写 MediaSource：Extractor 是 Media3 里"往上层供样本"的最小契约，
 * 接进来之后**时间轴、seek 校验、缓冲、AudioSink、MediaSession 全部仍然由 Media3 负责**，
 * 我们只替换"样本从哪来"。这比另起一个播放引擎（计划附录 B 提到的第二个 backend）风险小得多。
 *
 * seek 的坐标约定（关键）：`SeekMap` 返回的 `position` 单位就是**微秒**，
 * `read()` 里拿到的 `seekPosition.position` 直接当微秒丢给 native，不做任何换算。
 *
 * @param path 原始文件绝对路径（不经转码）
 * @param targetSampleRate native 的输出采样率；0 = 保持源率
 * @param outBits 16 或 24
 * @param keepSourceRate **native 不做重采样**（DSD 专用）。此时 [targetSampleRate] 被忽略，
 *   native 保持源率（DSD64 = 352800）交给下游 r8brain 抽取，见 `NativeFfmpegDemux.open`。
 * @param dsdTargetRate DSD 的**最终**目标率；0 = 不是 DSD。只用于回报
 *   （`FfmpegOutputStream.Spec.dsdTargetRate` 决定"要不要我方重采样"）。
 * @param dsdTransport DSD 的传输方式。非 [DsdTransport.pcm] 时本类**不解码**，
 *   直接把原始 1-bit 字节打包成 DoP / raw 帧交给 Media3 —— Media3 看到的是普通
 *   `AUDIO_RAW` 样本，时间轴 / seek / 缓冲 / MediaSession 全部照旧由它负责。
 */
@OptIn(UnstableApi::class)
internal class FfmpegLocalExtractor(
    private val path: String,
    private val targetSampleRate: Int,
    private val outBits: Int,
    private val keepSourceRate: Boolean = false,
    private val dsdTargetRate: Int = 0,
    private val dsdTransport: DsdTransport = DsdTransport.pcm,
    /**
     * raw 模式下 raw DSD alt 的字节序。`false` = `DSD_U32_BE`（字节顺序 = 时间顺序，
     * 不换位），`true` = `DSD_U32_LE`（每 4 字节反转）。
     *
     * **默认 BE**：XMOS 系（iFi iDSD / DIYINHK / 绝大多数小尾巴）的 native DSD 端点是
     * `DSD_U32_BE` —— ALSA 早年把它们标成 `DSD_U32_LE` 是个 bug，后来专门修过
     * （Jussi Laako, `Add big-endian DSD sample formats and fix XMOS DSD sample format`）。
     * 标错的症状正是满幅噪声。真遇到 LE 设备再把这里改 `true`。
     */
    private val nativeLittleEndian: Boolean = false,
) : Extractor {

    private var handle: Long = 0L
    private var trackOutput: TrackOutput? = null
    private val pcm = ByteArray(SAMPLE_BUFFER_BYTES)
    private val meta = LongArray(1)

    /** raw 模式的输出：native 已经封装好的 DoP / Native 线格式帧 */
    private val rawPacked = ByteArray(SAMPLE_BUFFER_BYTES * 2)

    private var durationUs: Long = 0L

    override fun sniff(input: ExtractorInput): Boolean = true

    override fun init(output: ExtractorOutput) {
        val info = IntArray(4)
        // raw DSD（DoP / Native）走独立 JNI 入口：只解封装、不解码。
        // 不能给 `open` 加参数 —— JNI 签名一旦两边产物不一致，native 会按错位读参数直接崩。
        val h = if (dsdTransport.isRaw) {
            NativeFfmpegDemux.openRaw(path, info)
        } else {
            NativeFfmpegDemux.open(path, targetSampleRate, outBits, info, keepSourceRate)
        }
        if (h == 0L) throw IOException("ffmpeg demux 打不开: $path")
        handle = h
        // 线格式封装在 native 里做：拆声道 → 位反转 → 打 DoP / Native 帧一次完成。
        // 封装后的字节流才是 Media3 眼里的"样本"，所以必须在这里就定下来。
        if (dsdTransport.isRaw) {
            NativeFfmpegDemux.configRawPack(
                h,
                if (dsdTransport == DsdTransport.dop) {
                    NativeFfmpegDemux.PACK_DOP
                } else {
                    NativeFfmpegDemux.PACK_NATIVE
                },
                // `bmFormats` 的 bit31 只标"raw data"，不区分字节序，所以按设备品类定（见 nativeLittleEndian）
                !nativeLittleEndian,
            )
        }

        val channels = info[1].takeIf { it > 0 } ?: 2
        // `info[0]` 的含义随入口而变：PCM 路径是输出网格率，raw 路径是 **1 bit 采样率**。
        val rawBitRate = info[0].takeIf { it > 0 } ?: 2_822_400
        val sampleRate: Int
        val bits: Int
        when (dsdTransport) {
            DsdTransport.dop -> {
                // DoP 交给端点的就是 PCM 采样率：每个 24 bit 帧装 16 个 1-bit 样本
                sampleRate = DopPacker.packetRate(rawBitRate)
                bits = NativeFfmpegDemux.BITS_24
            }
            DsdTransport.native -> {
                // 一个 32 bit word 装 32 个 1-bit 样本，端点看到的是 word 率。
                // 海贝的映射表（`unk_119BB4`）同样是 bitRate/32：DSD64 → 88200 一路到 DSD512 → 705600。
                sampleRate = NativeDsdPacker.packetRate(rawBitRate)
                bits = 32
            }
            DsdTransport.pcm -> {
                sampleRate = info[0].takeIf { it > 0 } ?: 44100
                bits = info[2]
            }
        }
        durationUs = info[3].coerceAtLeast(0).toLong() * 1000L
        // 真实输出规格回报给播放页（「输出」胶囊据此显示，而不是拿源字段冒充）。
        // DSD 时 `sampleRate` 是**中间格式**（352800），最终率在 `dsdTargetRate` 里 ——
        // 两者一起交出去，"要不要我方重采样"由后者判（见 `Spec` 的注释）。
        FfmpegOutputStream.publish(path, sampleRate, bits, channels, dsdTargetRate, dsdTransport)

        val track = output.track(0, C.TRACK_TYPE_AUDIO)
        track.format(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setPcmEncoding(
                    when {
                        bits >= 32 -> C.ENCODING_PCM_32BIT
                        bits == NativeFfmpegDemux.BITS_24 -> C.ENCODING_PCM_24BIT
                        else -> C.ENCODING_PCM_16BIT
                    },
                )
                .setSampleRate(sampleRate)
                .setChannelCount(channels)
                // 码率决定 `LoadControl` 的目标缓冲字节数。缺了它就只剩时间维度，
                // 2.1 MB/s 的 DSD 会按 maxBufferMs 目标缓冲几十秒的样本。
                .setAverageBitrate(bitrateOf(sampleRate, channels, bits))
                .setMaxInputSize(SAMPLE_BUFFER_BYTES)
                .build(),
        )
        output.endTracks()
        output.seekMap(simpleSeekMap())
        trackOutput = track
    }

    private fun simpleSeekMap(): SeekMap = object : SeekMap {
        override fun isSeekable(): Boolean = true

        /**
         * 必须写 `this@FfmpegLocalExtractor.durationUs`。
         *
         * `SeekMap` 是 Java 接口，Kotlin 把它的 `getDurationUs()` 合成了一个属性
         * `durationUs`；匿名对象里裸写 `durationUs` 会解析到自己身上（= 调 `getDurationUs()`），
         * 直接无限递归 → `StackOverflowError` 崩溃。
         */
        override fun getDurationUs(): Long = this@FfmpegLocalExtractor.durationUs

        override fun getSeekPoints(positionUs: Long): SeekMap.SeekPoints =
            SeekMap.SeekPoints(SeekPoint(positionUs, positionUs))
    }

    /**
     * raw DSD：native 交来的**已经是线格式字节**（DoP 6 B/帧 或 Native 8 B/帧），
     * 这里只做转发，不做任何位运算。
     *
     * 封装在 native 的 demux 里做（拆声道 → 位反转 → 打包一次完成），原因有两条：
     * 1. 封装后的字节流才是 Media3 看到的样本，帧长决定时长与位置，必须在上游定死；
     * 2. DoP 标记相位是"跨块必须严格交替"的易失状态，只该有一个持有者 —— 现在它是
     *    demux handle 自己，Kotlin 侧不再有副本。
     *
     * 这条路径**不产生 PCM 样本**，所以处理器链必须旁路（见 `PcmAudioSink.rawPassthrough`）
     * —— r8brain 会重采样、EQ 会改最低位，而 DoP 的标记就在那些位里。
     */
    private fun readRaw(input: ExtractorInput): Int {
        val written = NativeFfmpegDemux.read(handle, rawPacked, meta)
        if (written == 0) return Extractor.RESULT_END_OF_INPUT
        if (written < 0) throw IOException("ffmpeg raw 读取失败: $written")

        val track = trackOutput ?: return Extractor.RESULT_END_OF_INPUT
        track.sampleData(ParsableByteArray(rawPacked, written), written)
        track.sampleMetadata(
            meta[0].coerceAtLeast(0L),
            C.BUFFER_FLAG_KEY_FRAME,
            written,
            0,
            null,
        )
        input.skip(written)
        return Extractor.RESULT_CONTINUE
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (dsdTransport.isRaw) return readRaw(input)

        val written = NativeFfmpegDemux.read(handle, pcm, meta)
        if (written == 0) return Extractor.RESULT_END_OF_INPUT
        if (written < 0) throw IOException("ffmpeg demux 读取失败: $written")

        val track = trackOutput ?: return Extractor.RESULT_END_OF_INPUT
        // TrackOutput 只接受 ParsableByteArray 形态的样本数据
        track.sampleData(ParsableByteArray(pcm, written), written)
        track.sampleMetadata(
            meta[0].coerceAtLeast(0L),
            C.BUFFER_FLAG_KEY_FRAME,
            written,
            0,
            null,
        )
        // **必须让 input 的位置前进**：`ProgressiveMediaPeriod` 每越过 50 KB 就关一次加载闸门、
        // 把控制权交回播放线程；位置不动就再也不交回，loader 会连读到 native EOF
        // （DSD64 的 PCM 是源的 6 倍，实测 0.34 秒 216 MB 撑爆堆，且永远准备不完）。
        // 配合 `PositionOnlyDataSource`，这一步是纯位置推进、零 IO。
        input.skip(written)
        return Extractor.RESULT_CONTINUE
    }

    /**
     * Media3 通知"要跳到这里"，这是**唯一**的 seek 入口。
     *
     * position 单位是微秒（见 `SeekMap` 约定），直接丢给 native。
     * 不要试图在 [read] 里比较 `PositionHolder.position` 自己判 seek ——
     * `ExtractingLoadable` 每轮都会把它改写成 `input` 的当前位置，两者坐标系不同。
     */
    override fun seek(position: Long, timeUs: Long) {
        if (handle != 0L) NativeFfmpegDemux.seek(handle, position)
    }

    override fun release() {
        if (handle != 0L) {
            NativeFfmpegDemux.release(handle)
            handle = 0L
        }
        FfmpegOutputStream.clearIf(path)
        trackOutput = null
    }

    /** 每秒字节数 × 8 = bps；Media3 的缓冲估算全靠这个数 */
    private fun bitrateOf(sampleRate: Int, channels: Int, bits: Int): Int {
        val bytesPerSample = when {
            bits <= 16 -> 2
            bits <= 24 -> 3
            else -> 4
        }
        return sampleRate * channels * bytesPerSample * 8
    }

    private companion object {
        /**
         * 单次取样上限（字节）。
         *
         * 24bit 立体声 256KB ≈ 18,000 帧 ≈ 0.4 秒 @44.1k —— 一次读满能显著减少 JNI 往返，
         * 又不会让 seek 的响应粒度变粗太多。
         *
         * 对 DSD（352.8k/24bit = 2.1 MB/s）这个块只有 ~0.12 秒，仍然合适。
         */
        const val SAMPLE_BUFFER_BYTES = 256 * 1024
    }
}
