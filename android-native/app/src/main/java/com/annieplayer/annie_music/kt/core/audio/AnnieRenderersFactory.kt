package com.annieplayer.annie_music.kt.core.audio

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import com.annieplayer.annie_music.kt.core.audio.dsp.SincResamplerAudioProcessor
import com.annieplayer.annie_music.kt.core.audio.dsp.TuningAudioProcessor
import com.annieplayer.annie_music.kt.core.audio.ffmpeg.FfmpegAudioRenderer

/**
 * 自定义 RenderersFactory：DSP 放在 AudioSink 外层，时钟/位置仍交给 DefaultAudioSink。
 */
@OptIn(UnstableApi::class)
class AnnieRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    val tuning = TuningAudioProcessor()
    val resampler = SincResamplerAudioProcessor()

    init {
        setEnableAudioFloatOutput(true)
        setEnableDecoderFallback(true)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        val silence = SilenceSkippingAudioProcessor().apply { setEnabled(false) }
        val chain = DefaultAudioSink.DefaultAudioProcessorChain(
            arrayOf<androidx.media3.common.audio.AudioProcessor>(),
            silence,
            SonicAudioProcessor(),
        )
        val base = androidx.media3.exoplayer.audio.AudioCapabilities.getCapabilities(context)
        val encodings = buildList {
            add(androidx.media3.common.C.ENCODING_PCM_16BIT)
            if (base.supportsEncoding(androidx.media3.common.C.ENCODING_PCM_FLOAT)) {
                add(androidx.media3.common.C.ENCODING_PCM_FLOAT)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                add(androidx.media3.common.C.ENCODING_PCM_24BIT)
            }
        }.toIntArray()
        val capabilities = androidx.media3.exoplayer.audio.AudioCapabilities(
            encodings,
            base.maxChannelCount.coerceAtLeast(2),
        )
        val delegate = DefaultAudioSink.Builder(context)
            .setAudioCapabilities(capabilities)
            .setEnableFloatOutput(true)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessorChain(chain)
            .build()
        return PcmAudioSink(delegate, tuning, resampler)
    }

    /**
     * FLAC 走自研软解 Renderer（列表第一位），其它格式保持 MediaCodec。
     *
     * `DecoderAudioRenderer` 的格式支持是逐曲判断的：非 FLAC 返回 unsupported，
     * ExoPlayer 会自然落到后面的 `MediaCodecAudioRenderer`，不需要额外分支。
     */
    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: android.os.Handler,
        eventListener: androidx.media3.exoplayer.audio.AudioRendererEventListener,
        out: ArrayList<androidx.media3.exoplayer.Renderer>,
    ) {
        out.add(FfmpegAudioRenderer(eventHandler, eventListener, audioSink))
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out,
        )
    }
}
