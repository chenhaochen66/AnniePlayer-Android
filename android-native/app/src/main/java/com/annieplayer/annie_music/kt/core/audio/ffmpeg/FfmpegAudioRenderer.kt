package com.annieplayer.annie_music.kt.core.audio.ffmpeg

import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.CryptoConfig
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DecoderAudioRenderer
import com.annieplayer.annie_music.kt.core.audio.BluetoothCodec

/**
 * ffmpeg 软解 Renderer：Media3 Extractor 出压缩帧，native `libavcodec` 解码。
 *
 * 放在 renderer 列表第一位：命中的格式由它处理，其它格式自动落到 MediaCodecAudioRenderer。
 */
@OptIn(UnstableApi::class)
class FfmpegAudioRenderer(
    eventHandler: Handler?,
    eventListener: AudioRendererEventListener?,
    audioSink: AudioSink,
) : DecoderAudioRenderer<FfmpegAudioDecoder>(eventHandler, eventListener, audioSink) {

    private fun codecNameOf(format: Format): String? = when (format.sampleMimeType) {
        MimeTypes.AUDIO_FLAC -> "flac"
        "audio/alac", MimeTypes.AUDIO_ALAC -> "alac"
        MimeTypes.AUDIO_AAC -> "aac"
        MimeTypes.AUDIO_AC3 -> "ac3"
        MimeTypes.AUDIO_E_AC3, "audio/eac3-joc" -> "eac3"
        else -> null
    }

    override fun supportsFormatInternal(format: Format): Int {
        if (codecNameOf(format) == null) return C.FORMAT_UNSUPPORTED_TYPE
        if (!NativeFfmpegDecoder.isAvailable()) return C.FORMAT_UNSUPPORTED_SUBTYPE
        return try {
            val supported = sinkSupportsFormat(rawFormat(format, C.ENCODING_PCM_16BIT)) ||
                sinkSupportsFormat(rawFormat(format, C.ENCODING_PCM_24BIT))
            if (supported) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_SUBTYPE
        } catch (t: Throwable) {
            C.FORMAT_UNSUPPORTED_SUBTYPE
        }
    }

    private fun rawFormat(format: Format, encoding: Int): Format =
        format.buildUpon()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(encoding)
            .build()

    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?): FfmpegAudioDecoder {
        val codecName = codecNameOf(format)
            ?: throw FfmpegDecoderException("没有 ffmpeg 解码器: ${format.sampleMimeType}")
        val prefer24 = try {
            // 蓝牙链路物理上就是 16bit（SBC/AAC/LDAC 也不例外地按 16bit 送 HAL），
            // 申请 24bit 会被 AudioTrack 以「Unknown native audio format」拒绝并反复
            // start/pause（真机现象：播到某个分片边界就停在 BUFFERING）。
            // 这里对蓝牙直接按 16bit 解码输出，避免整条输出链重建。
            if (BluetoothCodec.info.value != null) {
                false
            } else {
                sinkSupportsFormat(rawFormat(format, C.ENCODING_PCM_24BIT))
            }
        } catch (t: Throwable) {
            false
        }
        return FfmpegAudioDecoder(codecName, prefer24).apply { configure(format) }
    }

    override fun getOutputFormat(decoder: FfmpegAudioDecoder): Format = decoder.outputFormat

    override fun getName(): String = "AnnieFfmpegRenderer"
}
