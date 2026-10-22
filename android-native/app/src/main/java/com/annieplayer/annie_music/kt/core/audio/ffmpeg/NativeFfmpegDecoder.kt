package com.annieplayer.annie_music.kt.core.audio.ffmpeg

/**
 * ffmpeg 解码 JNI：直接使用项目里 ffmpeg-kit 已带的 `libavcodec.so`。
 *
 * ffmpeg-kit 只暴露 `FFmpegKit.execute`，没有低层 `AVCodec` 头文件；
 * 这里用 `dlopen` + `dlsym` 调 `libavcodec`，不链接、不额外打包第二份 ffmpeg。
 */
internal object NativeFfmpegDecoder {

    private val loaded: Boolean = runCatching {
        System.loadLibrary("anniedsp")
    }.isSuccess

    private val available: Boolean by lazy {
        loaded && runCatching { nativeAvailable() }.getOrDefault(false)
    }

    fun isAvailable(): Boolean = available

    private external fun nativeAvailable(): Boolean

    external fun create(
        codecName: String,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        outputBits: Int,
        extradata: ByteArray,
    ): Long

    external fun decode(handle: Long, input: ByteArray, output: ByteArray): Int

    external fun reset(handle: Long)

    external fun release(handle: Long)
}
