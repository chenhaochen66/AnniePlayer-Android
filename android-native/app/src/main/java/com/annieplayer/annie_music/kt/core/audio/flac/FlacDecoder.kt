package com.annieplayer.annie_music.kt.core.audio.flac

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.jflac.FLACDecoder
import org.jflac.PCMProcessor
import org.jflac.metadata.StreamInfo
import org.jflac.util.ByteData

class FlacDecoderException(message: String, cause: Throwable? = null) :
    DecoderException(message, cause)

/**
 * FLAC 软解：解码交给现成的 **jFLAC**，本类只做「Media3 帧 → jFLAC 流 → float PCM」的桥接。
 *
 * 日志同时写 `files/flac_debug.log`，因为 ColorOS 会把第三方 App 的 logcat 吞掉。
 */
class FlacDecoder(private val appContext: Context?) :
    SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, FlacDecoderException>(
        arrayOfNulls(16),
        arrayOfNulls(16),
    ) {

    private var streamHeader: ByteArray = ByteArray(0)
    private var bitsPerSample: Int = 16
    private var channels: Int = 2
    private var frameCount = 0

    private val logFile: File? = appContext?.let { File(it.filesDir, "flac_debug.log") }

    var outputFormat: Format = Format.Builder().build()
        private set

    fun configure(format: Format) {
        val info = format.initializationData.firstOrNull()
        val raw = normalizeStreamInfo(info)
        streamHeader = buildHeader(raw)

        channels = format.channelCount.coerceAtLeast(1)
        bitsPerSample = when (format.pcmEncoding) {
            C.ENCODING_PCM_24BIT -> 24
            C.ENCODING_PCM_32BIT -> 32
            C.ENCODING_PCM_16BIT -> 16
            else -> readBits(raw) ?: 16
        }
        outputFormat = format.buildUpon()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_FLOAT)
            .setSampleRate(format.sampleRate)
            .setChannelCount(channels)
            .build()
        log(
            "configure rate=${format.sampleRate} ch=$channels bits=$bitsPerSample " +
                "initSize=${info?.size ?: 0} formatPcm=${format.pcmEncoding}",
        )
        log("raw=" + (raw?.joinToString("") { "%02X".format(it) } ?: "null"))
        log("header=" + streamHeader.joinToString("") { "%02X".format(it) })
    }

    override fun getName(): String = "annie-flac"

    override fun createInputBuffer(): DecoderInputBuffer =
        DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)

    override fun createOutputBuffer(): SimpleDecoderOutputBuffer =
        SimpleDecoderOutputBuffer { releaseOutputBuffer(it) }

    override fun createUnexpectedDecodeException(error: Throwable): FlacDecoderException =
        FlacDecoderException("FLAC 解码异常", error)

    override fun decode(
        inputBuffer: DecoderInputBuffer,
        outputBuffer: SimpleDecoderOutputBuffer,
        reset: Boolean,
    ): FlacDecoderException? {
        val data = inputBuffer.data ?: return null
        val size = data.remaining()
        if (size <= 0) {
            outputBuffer.init(inputBuffer.timeUs, 0)
            return null
        }
        val frame = ByteArray(size)
        data.get(frame)

        val pcm = try {
            decodeFrame(frame, size)
        } catch (t: Throwable) {
            log("decode failed size=$size: ${t.message}")
            null
        }
        if (pcm == null || pcm.isEmpty()) {
            outputBuffer.init(inputBuffer.timeUs, 0)
            outputBuffer.shouldBeSkipped = true
            return null
        }

        val buffer: ByteBuffer = outputBuffer.init(inputBuffer.timeUs, pcm.size * 4)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        for (sample in pcm) buffer.putFloat(sample)
        buffer.flip()
        return null
    }

    private fun decodeFrame(frame: ByteArray, size: Int): FloatArray? {
        val stream = ByteArray(streamHeader.size + frame.size)
        System.arraycopy(streamHeader, 0, stream, 0, streamHeader.size)
        System.arraycopy(frame, 0, stream, streamHeader.size, frame.size)

        val decoder = FLACDecoder(ByteArrayInputStream(stream))
        var result: FloatArray? = null
        decoder.addPCMProcessor(object : PCMProcessor {
            override fun processStreamInfo(info: StreamInfo) {
                // 以 Media3 的 Format 为准；这里只做 sanity check，不要让错误值覆盖
                if (channels <= 0 && info.channels in 1..8) channels = info.channels
                if (bitsPerSample <= 0 && info.bitsPerSample in 4..32) bitsPerSample = info.bitsPerSample
            }

            override fun processPCM(pcm: ByteData) {
                result = toFloat(pcm)
            }
        })
        val ret = try {
            decoder.decode()
            "ok"
        } catch (_: EOFException) {
            "eof"
        } catch (t: Throwable) {
            "${t.javaClass.simpleName}:${t.message}"
        }
        if (frameCount < 5) {
            frameCount++
            val head = frame.take(4).joinToString("") { "%02X".format(it) }
            log(
                "frame#$frameCount size=$size head=$head ret=$ret eof=${decoder.isEOF} " +
                    "samples=${decoder.samplesDecoded} bad=${decoder.badFrames} " +
                    "pcm=${result?.size ?: -1} bits=$bitsPerSample",
            )
        }
        return result
    }

    private fun toFloat(pcm: ByteData): FloatArray? {
        val data = pcm.data ?: return null
        val length = pcm.len
        val bytesPerSample = bitsPerSample / 8
        if (bytesPerSample !in 1..4) return null
        val count = length / bytesPerSample
        if (count <= 0) return null
        val out = FloatArray(count)
        when (bytesPerSample) {
            3 -> {
                for (i in 0 until count) {
                    val b0 = data[i * 3].toInt() and 0xFF
                    val b1 = data[i * 3 + 1].toInt() and 0xFF
                    val b2 = data[i * 3 + 2].toInt()
                    out[i] = ((b2 shl 16) or (b1 shl 8) or b0) / 8_388_608f
                }
            }
            4 -> {
                for (i in 0 until count) {
                    val b0 = data[i * 4].toInt() and 0xFF
                    val b1 = data[i * 4 + 1].toInt() and 0xFF
                    val b2 = data[i * 4 + 2].toInt() and 0xFF
                    val b3 = data[i * 4 + 3].toInt()
                    out[i] = ((b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0) / 2_147_483_648f
                }
            }
            else -> {
                for (i in 0 until count) {
                    val b0 = data[i * 2].toInt() and 0xFF
                    val b1 = data[i * 2 + 1].toInt()
                    out[i] = ((b1 shl 8) or b0) / 32_768f
                }
            }
        }
        return out
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

    private fun buildHeader(streamInfo: ByteArray?): ByteArray {
        val body = streamInfo ?: syntheticStreamInfo()
        val out = ByteArrayOutputStream(4 + 4 + body.size)
        out.write('f'.code)
        out.write('L'.code)
        out.write('a'.code)
        out.write('C'.code)
        out.write(0x80)
        out.write((body.size shr 16) and 0xFF)
        out.write((body.size shr 8) and 0xFF)
        out.write(body.size and 0xFF)
        out.write(body)
        return out.toByteArray()
    }

    private fun syntheticStreamInfo(): ByteArray {
        val body = ByteArray(34)
        body[0] = 0x10; body[1] = 0x00
        body[2] = 0x10; body[3] = 0x00
        return body
    }

    private fun readBits(streamInfo: ByteArray?): Int? {
        if (streamInfo == null || streamInfo.size < 34) return null
        val b12 = streamInfo[12].toInt() and 0xFF
        val b13 = streamInfo[13].toInt() and 0xFF
        return (((b12 and 0x01) shl 4) or (b13 shr 4)) + 1
    }

    private fun log(message: String) {
        Log.i(LogTag, message)
        val file = logFile ?: return
        try {
            file.appendText(message + "\n")
        } catch (_: Throwable) {
        }
    }

    private companion object {
        const val LogTag = "AnnieFlac"
    }
}
