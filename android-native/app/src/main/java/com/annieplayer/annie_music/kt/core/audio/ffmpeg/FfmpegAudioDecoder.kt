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

    override fun release() {
        releaseNative()
        super.release()
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

        val frame = ByteArray(size)
        data.get(frame)

        if (handle == 0L) {
            return FfmpegDecoderException("ffmpeg $codecName 解码器未初始化")
        }
        if (reset) NativeFfmpegDecoder.reset(handle)

        val written = try {
            NativeFfmpegDecoder.decode(handle, frame, pcm)
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

    private fun releaseNative() {
        if (handle != 0L) {
            NativeFfmpegDecoder.release(handle)
            handle = 0L
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

    /** AC-3 / E-AC-3（含 Atmos JOC）：extradata 可选，声道数从 Format 取。 */
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
