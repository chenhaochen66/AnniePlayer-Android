package com.annieplayer.annie_music.kt.core.audio.dsp

import java.nio.ByteBuffer

/** JNI 桥：原生 biquad 调音链（NEON / -O3）。 */
object NativeDsp {

    private val loaded: Boolean = runCatching {
        System.loadLibrary("anniedsp")
    }.isSuccess

    fun isAvailable(): Boolean = loaded

    external fun create(channels: Int): Long

    external fun configure(
        handle: Long,
        sampleRate: Int,
        types: IntArray,
        freqs: FloatArray,
        qs: FloatArray,
        gains: FloatArray,
        preampDb: Float,
        limiter: Boolean,
    )

    external fun processBytes(
        handle: Long,
        input: ByteArray,
        output: ByteArray,
        frames: Int,
        encoding: Int,
    ): Int

    external fun reset(handle: Long)

    external fun release(handle: Long)
}
