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
        // 本地常见有损格式也统一交给 ffmpeg：
        // 一是味道/音量与其他格式一致（都过同一条软解链），
        // 二是 MediaCodec 的 MP3 解码器在不同 ROM 上行为不一致（有的是 float 有的是 s16）。
        MimeTypes.AUDIO_MPEG -> "mp3"
        // ⚠️ Opus / Vorbis 已退回 MediaCodec，别再往这里加：
        // 这两者依赖容器给的 extradata（OpusHead / Xiph lacing），走这条软解链实测起不来
        // （直接播放失败）；而 MediaCodec 自带的 opus/vorbis 解码器一直能放，
        // 且有损格式本来就没有「位深被截断」的问题，接 ffmpeg 只是理念上统一、收益为零。
        // WAV / AIFF 这类容器里装的本来就是 PCM：Media3 直接出 `audio/raw`，
        // 没有"解码"这一步，走 AudioSink 直通，天然不截断，也不需要 ffmpeg 参与。
        else -> null
    }

    override fun supportsFormatInternal(format: Format): Int {
        // **本地与流媒体同一条解码路径**：能交给 ffmpeg 的格式一律交给 ffmpeg。
        // 这样本地 FLAC / ALAC / AAC / AC3 也走 24bit 软解，不会落到 MediaCodec 的
        // 16bit 截断；也让「播放页显示的位深」和「实际解码位深」一致。
        //
        // 以前这里第一句是"直通优先"：能交给 HAL 直通时声明不支持、把这一首让给
        // `MediaCodecAudioRenderer`。**offload 直通已整条删除**，所以没有让位这回事，
        // 所有格式都走这一条路。
        //
        // 历史上本地曾被整体挡在 ffmpeg 之外（本地 FLAC 走 native 路径偶发 SIGSEGV，
        // 当时为了不闪退直接禁了整条本地路径，只给 ALAC 开口子）。现已统一放开，
        // 若再出现 native 崩溃，修 [FfmpegAudioDecoder] / native 侧，不要再按平台分流。
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
