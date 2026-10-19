package com.annieplayer.annie_music.kt.core.audio.flac

import android.content.Context
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

/**
 * FLAC 软件解码 Renderer：完全绕开 MediaCodec。
 *
 * 这样做的收益是确定的 —— 不再受厂商解码器「内部 16bit」影响，24bit FLAC 以 float
 * 原样送进 AudioSink。代价是 CPU（纯 Kotlin 解码），实测仍远低于 1 个核。
 *
 * 放在 renderer 列表第一位：FLAC 命中时由它处理，其它格式自动落到 MediaCodecAudioRenderer。
 */
@OptIn(UnstableApi::class)
class FlacAudioRenderer(
    eventHandler: Handler?,
    eventListener: AudioRendererEventListener?,
    audioSink: AudioSink,
    private val appContext: Context,
) : DecoderAudioRenderer<FlacDecoder>(eventHandler, eventListener, audioSink) {

    override fun supportsFormatInternal(format: Format): Int {
        if (MimeTypes.AUDIO_FLAC != format.sampleMimeType) return C.FORMAT_UNSUPPORTED_TYPE
        // sink 必须以 float 接住我们输出的 PCM；不支持则回退 MediaCodec
        return try {
            val raw = format.buildUpon()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setPcmEncoding(C.ENCODING_PCM_FLOAT)
                .build()
            if (sinkSupportsFormat(raw)) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_SUBTYPE
        } catch (t: Throwable) {
            C.FORMAT_UNSUPPORTED_SUBTYPE
        }
    }

    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?): FlacDecoder =
        FlacDecoder(appContext).apply { configure(format) }

    override fun getOutputFormat(decoder: FlacDecoder): Format = decoder.outputFormat

    override fun getName(): String = "AnnieFlacRenderer"
}
