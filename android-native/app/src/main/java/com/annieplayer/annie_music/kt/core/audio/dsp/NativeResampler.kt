package com.annieplayer.annie_music.kt.core.audio.dsp

import java.nio.ByteBuffer

/** JNI 桥：原生 Kaiser sinc 重采样（48 taps / 512 相）。 */
object NativeResampler {

    private val loaded: Boolean = runCatching {
        System.loadLibrary("anniedsp")
    }.isSuccess

    fun isAvailable(): Boolean = loaded

    external fun create(channels: Int, srcRate: Int, dstRate: Int): Long

    external fun processBytes(
        handle: Long,
        input: ByteArray,
        output: ByteArray,
        inFrames: Int,
        inEncoding: Int,
        outEncoding: Int,
        dither: Boolean,
    ): Int

    external fun reset(handle: Long)

    external fun release(handle: Long)
}
