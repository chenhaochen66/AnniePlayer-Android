package com.annieplayer.annie_music.kt.core.audio.ffmpeg

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import java.io.ByteArrayOutputStream

class FfmpegDecoderException(message: String, cause: Throwable? = null) :
    DecoderException(message, cause)

/**
 * Media3 帧 → ffmpeg `libavcodec` native 解码。
 *
 * 流媒体和本地共用同一个解码器；网络、缓存、容器解析仍由 Media3 负责。
 * 目前支持 FLAC / ALAC，后续格式只需要在 [FfmpegAudioRenderer] 里加 MIME 映射。
 */
class FfmpegAudioDecoder(
    private val codecName: String,
    private val prefer24: Boolean = true,
) : SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, FfmpegDecoderException>(
    arrayOfNulls(16),
    arrayOfNulls(16),
) {

    private var handle: Long = 0L
    private var channels: Int = 2
    private var outputBits: Int = 16
    private var pcm: ByteArray = ByteArray(0)

    /**
     * 压缩帧暂存。**跨帧复用**：FLAC / ALAC 的帧长在稳态下恒定，`size` 不变就是零分配。
     *
     * 之前每帧 `ByteArray(size)`：FLAC 24bit/192k 一帧约 32 KB、每秒约 11 帧，
     * 也就是每秒 350 KB 的短命对象。量不算大，但它落在**解码线程**上，
     * 一次 GC 停顿就可能让 AAudio 写线程欠载 —— 低端机上换来的是一次爆音。
     */
    private var inputScratch: ByteArray = ByteArray(0)

    /**
     * 保护 [handle] 的锁：`decode()` 与 [releaseNative] 互斥。
     *
     * `SimpleDecoder.release()` 只是请求解码线程停下，并不保证调用返回时那一帧已经解完；
     * 而 `decode()` 跑在独立线程上。没有这把锁，`release()` 可能在
     * `NativeFfmpegDecoder.decode()` 执行到一半时把 native 上下文 free 掉。
     */
    private val nativeLock = Any()

    /** 连续解码失败帧数（成功一帧就清零）；超过阈值才判定解码器不可用 */
    private var decodeFailures: Int = 0

    var outputFormat: Format = Format.Builder().build()
        private set

    fun configure(format: Format) {
        val sampleRate = format.sampleRate.coerceAtLeast(1)
        val (bits, extra) = when (codecName) {
            "flac" -> flacConfig(format, sampleRate)
            "alac" -> alacConfig(format, sampleRate)
            "aac" -> aacConfig(format)
            "ac3", "eac3" -> compressedConfig(format)
            // 有损常见格式：mp3 无 extradata 也能解
            "mp3" -> compressedConfig(format)
            else -> throw FfmpegDecoderException("未支持的 ffmpeg 解码器: $codecName")
        }
        outputBits = if (bits > 16 && prefer24) 24 else 16

        releaseNative()
        handle = NativeFfmpegDecoder.create(
            codecName,
            sampleRate,
            channels,
            bits,
            outputBits,
            extra,
        )
        if (handle == 0L) throw FfmpegDecoderException("ffmpeg $codecName 解码器初始化失败")

        pcm = ByteArray(MAX_BLOCK_SIZE * channels * (outputBits / 8))
        outputFormat = format.buildUpon()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(if (outputBits == 24) C.ENCODING_PCM_24BIT else C.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelCount(channels)
            .build()
    }

    override fun getName(): String = "annie-ffmpeg-$codecName"

    override fun createInputBuffer(): DecoderInputBuffer =
        DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)

    override fun createOutputBuffer(): SimpleDecoderOutputBuffer =
        SimpleDecoderOutputBuffer { releaseOutputBuffer(it) }

    override fun createUnexpectedDecodeException(error: Throwable): FfmpegDecoderException =
        FfmpegDecoderException("$codecName 解码异常", error)

    /**
     * **先停解码线程，再释放 native 上下文。** 顺序不能反。
     *
     * [SimpleDecoder] 的 `decode()` 跑在自己的 `ExoPlayer:…SimpleDecoder` 线程上，
     * 是异步的；而 [release] 由播放线程调用。若先 `releaseNative()`，
     * 解码线程可能正卡在 `NativeFfmpegDecoder.decode()` 里、native 侧还有一帧在解，
     * 此时 `avcodec_free_context()` 之后继续用 `AVCodecContext*` 就是 use-after-free。
     *
     * 真机表现（PLQ110 / Android 16，本地 FLAC）：
     * ```
     * F libc: Fatal signal 11 (SIGSEGV), fault addr 0x63, tid … (ExoPlayer:Simpl…)
     * F DEBUG: #05 … libavcodec.so (avcodec_send_packet+164)
     * F DEBUG: #06 … libanniedsp.so (NativeFfmpegDecoder_decode+276)
     * ```
     * `avctx` 指向已释放的块，内部字段被读成垃圾小整数再解引用 → fault addr 0x63。
     *
     * `super.release()` 内部会中断并 join 解码线程，返回时 `decode()` 保证已退出。
     */
    override fun release() {
        super.release()
        releaseNative()
    }

    override fun decode(
        inputBuffer: DecoderInputBuffer,
        outputBuffer: SimpleDecoderOutputBuffer,
        reset: Boolean,
    ): FfmpegDecoderException? {
        val data = inputBuffer.data ?: return null
        val size = data.remaining()
        if (size <= 0) {
            outputBuffer.init(inputBuffer.timeUs, 0)
            return null
        }

        // 复用压缩帧缓冲（见 [inputScratch] 的说明）。**长度必须精确等于 size**：
        // native 侧用 `GetArrayLength(input)` 决定读多少字节，留余量会把上一帧的
        // 残字节当成本帧数据喂进 libavcodec。
        if (inputScratch.size != size) inputScratch = ByteArray(size)
        data.get(inputScratch, 0, size)

        if (handle == 0L) {
            return FfmpegDecoderException("ffmpeg $codecName 解码器未初始化")
        }

        val written = try {
            synchronized(nativeLock) {
                val active = handle
                if (active == 0L) {
                    // 解锁后到拿锁之间被 release 了：直接丢帧，不碰 native
                    return FfmpegDecoderException("ffmpeg $codecName 解码器已释放")
                }
                if (reset) NativeFfmpegDecoder.reset(active)
                NativeFfmpegDecoder.decode(active, inputScratch, pcm)
            }
        } catch (t: Throwable) {
            return FfmpegDecoderException("$codecName 解码异常", t)
        }
        if (written < 0) {
            // Apple 的部分母带里会夹杂 ffmpeg 不支持的帧（返回 AVERROR_PATCHWELCOME /
            // INVALIDDATA，例如未压缩/escape 帧）。单帧失败不该让整首歌报
            // ERROR_CODE_DECODING_FAILED：丢掉这一帧（约 20~90ms）继续播，
            // 只有连续大量失败才说明解码器真的不可用。
            decodeFailures++
            if (decodeFailures > MAX_CONSECUTIVE_FAILURES) {
                return FfmpegDecoderException("ffmpeg $codecName 解码失败: $written")
            }
            outputBuffer.init(inputBuffer.timeUs, 0)
            outputBuffer.shouldBeSkipped = true
            return null
        }
        decodeFailures = 0
        if (written == 0) {
            outputBuffer.init(inputBuffer.timeUs, 0)
            outputBuffer.shouldBeSkipped = true
            return null
        }

        val buffer = outputBuffer.init(inputBuffer.timeUs, written)
        buffer.put(pcm, 0, written)
        buffer.flip()
        return null
    }

    /**
     * 释放 native 上下文。与 [decode] 同锁：若此刻有一帧正在 native 里解，会等它解完。
     */
    private fun releaseNative() {
        synchronized(nativeLock) {
            if (handle != 0L) {
                NativeFfmpegDecoder.release(handle)
                handle = 0L
            }
        }
    }

    private fun flacConfig(format: Format, sampleRate: Int): Pair<Int, ByteArray> {
        val info = normalizeStreamInfo(format.initializationData.firstOrNull())
        channels = format.channelCount.coerceAtLeast(1)
        val bits = readBits(info) ?: when (format.pcmEncoding) {
            C.ENCODING_PCM_24BIT -> 24
            C.ENCODING_PCM_32BIT -> 32
            C.ENCODING_PCM_16BIT -> 16
            else -> 16
        }
        val extra = buildFlacHeader(
            info ?: syntheticStreamInfo(sampleRate, channels, bits),
        )
        return bits to extra
    }

    private fun alacConfig(format: Format, sampleRate: Int): Pair<Int, ByteArray> {
        val config = normalizeAlacConfig(format.initializationData.firstOrNull())
            ?: throw FfmpegDecoderException("ALAC 缺少 magic cookie")

        val configChannels = config[9].toInt() and 0xFF
        val configBits = config[5].toInt() and 0xFF
        channels = format.channelCount.takeIf { it > 0 } ?: configChannels.coerceAtLeast(1)
        val bits = configBits.takeIf { it in 16..32 } ?: when (format.pcmEncoding) {
            C.ENCODING_PCM_24BIT -> 24
            C.ENCODING_PCM_32BIT -> 32
            else -> 16
        }
        return bits to wrapAlacCookie(config)
    }

    /**
     * 把裸 ALACSpecificConfig（24B）包成 mp4 的 `alac` atom（36B）。
     *
     * ffmpeg 7.x 的 alac 解码器只接受「盒子形态」的 extradata：
     * 裸 24 字节会被判成 `extradata is too small`（INVALIDDATA）而 `avcodec_open2` 失败，
     * 表现就是 ALAC 起播即 `decoder init failed`。
     */
    private fun wrapAlacCookie(config: ByteArray): ByteArray {
        val atom = ByteArray(12 + config.size)
        val size = atom.size
        atom[0] = (size ushr 24).toByte()
        atom[1] = (size ushr 16).toByte()
        atom[2] = (size ushr 8).toByte()
        atom[3] = size.toByte()
        "alac".toByteArray(Charsets.US_ASCII).copyInto(atom, 4)
        // atom[8..11] = version/flags，全 0
        config.copyInto(atom, 12)
        return atom
    }

    /** AAC-LC / HE-AAC：Media3 从 esds 取出的 AudioSpecificConfig 直接给 ffmpeg。 */
    private fun aacConfig(format: Format): Pair<Int, ByteArray> {
        channels = format.channelCount.coerceAtLeast(1)
        val extra = format.initializationData.firstOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: ByteArray(0)
        return 16 to extra
    }

    /**
     * `AC-3 / E-AC-3 / MP3 / Opus / Vorbis`：extradata 可选或由容器给（OpusHead、Xiph lacing），
     * 声道数从 `Format` 取；这些格式解出来按 16bit 交付即可（本身是有损的）。
     */
    private fun compressedConfig(format: Format): Pair<Int, ByteArray> {
        channels = format.channelCount.coerceAtLeast(1)
        val extra = format.initializationData.firstOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: ByteArray(0)
        return 16 to extra
    }

    private fun normalizeAlacConfig(data: ByteArray?): ByteArray? {
        if (data == null || data.size < 24) return null
        // Media3 可能给 ALACSpecificConfig（24B），也可能给 alac box（36B）；统一取末尾 24B。
        return data.copyOfRange(data.size - 24, data.size)
    }

    private fun normalizeStreamInfo(info: ByteArray?): ByteArray? {
        if (info == null || info.size < 34) return null
        if (info.size == 34) return info
        // Media3 的 FlacStreamMetadata.getFormat() 传进来的就是「fLaC + metadata header + STREAMINFO」（42 字节）
        if (info.size >= 42 && info[0].toInt() == 'f'.code && info[1].toInt() == 'L'.code) {
            return info.copyOfRange(8, 42)
        }
        // 只有 4 字节 metadata header + STREAMINFO 的形态
        val headerLength = ((info[1].toInt() and 0xFF) shl 16) or
            ((info[2].toInt() and 0xFF) shl 8) or (info[3].toInt() and 0xFF)
        if (info.size >= 38 && (info[0].toInt() and 0x7F) == 0 && headerLength == 34) {
            return info.copyOfRange(4, 38)
        }
        // 兜底：取末尾 34 字节（STREAMINFO 固定长度）
        return if (info.size > 34) info.copyOfRange(info.size - 34, info.size) else info
    }

    private fun buildFlacHeader(streamInfo: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(4 + 4 + streamInfo.size)
        out.write('f'.code)
        out.write('L'.code)
        out.write('a'.code)
        out.write('C'.code)
        out.write(0x80)
        out.write((streamInfo.size shr 16) and 0xFF)
        out.write((streamInfo.size shr 8) and 0xFF)
        out.write(streamInfo.size and 0xFF)
        out.write(streamInfo)
        return out.toByteArray()
    }

    private fun syntheticStreamInfo(
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
    ): ByteArray {
        val body = ByteArray(34)
        body[0] = 0x10
        body[1] = 0x00
        body[2] = 0xFF.toByte()
        body[3] = 0xFF.toByte()

        var packed = (sampleRate.toLong() and 0xFFFFF) shl 44
        packed = packed or ((channels - 1).toLong() and 0x7 shl 41)
        packed = packed or ((bitsPerSample - 1).toLong() and 0x1F shl 36)
        for (i in 0 until 8) {
            body[10 + i] = (packed ushr (56 - i * 8)).toByte()
        }
        return body
    }

    private fun readBits(streamInfo: ByteArray?): Int? {
        if (streamInfo == null || streamInfo.size < 34) return null
        val b12 = streamInfo[12].toInt() and 0xFF
        val b13 = streamInfo[13].toInt() and 0xFF
        return (((b12 and 0x01) shl 4) or (b13 shr 4)) + 1
    }

    private companion object {
        const val MAX_BLOCK_SIZE = 65535

        /**
         * 容忍的连续解码失败帧数。
         *
         * 一帧 ALAC 约 4096 sample（44.1k 下 ~93ms），50 帧≈4.6 秒。只丢几帧听感上几乎
         * 察觉不到；若连续 50 帧都解不出来，说明是 extradata/格式层面的问题，应当让
         * 上层走「重试一次 → 跳下一首」而不是假装在播。
         */
        const val MAX_CONSECUTIVE_FAILURES = 50
    }
}
