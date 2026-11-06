package com.annieplayer.annie_music.kt.core.audio.ffmpeg

/**
 * ffmpeg 解封装 + 解码 + 重采样 JNI 门面（本地全链路专用）。
 *
 * 和 [NativeFfmpegDecoder]（只解码、容器仍由 Media3 解）是两套东西：
 * 这里让 ffmpeg 从文件头开始读（`avformat`），因此能吃 APE / WavPack / TTA / TAK / DSD
 * 这些 Media3 连 demux 都不会的容器。
 *
 * 符号同样来自 ffmpeg-kit 自带的 `libavformat` / `libavcodec` / `libswresample`（dlopen，
 * 不额外打包第二份 ffmpeg），实现见 `cpp/native-ffmpeg-demux.cpp`。
 */
internal object NativeFfmpegDemux {

    /** 与 [NativeFfmpegDecoder] 共用同一个 JNI 库（libanniedsp.so） */
    private val loaded: Boolean = runCatching {
        System.loadLibrary("anniedsp")
    }.isSuccess

    private val available: Boolean by lazy {
        loaded && runCatching { nativeAvailable() }.getOrDefault(false)
    }

    fun isAvailable(): Boolean = available

    /** 输出的位深：优先 24bit（设备不支持时上层传 16） */
    const val BITS_24 = 24
    const val BITS_16 = 16

    private external fun nativeAvailable(): Boolean

    external fun open(
        path: String,
        targetSampleRate: Int,
        outBits: Int,
        info: IntArray,
        keepSourceRate: Boolean,
    ): Long

    /**
     * 打开成 **raw DSD**：只解封装，不解码。`read` 吐出的是**按字节交错**的 1-bit 流
     * （每 2 字节 = 一个立体声时间采样），交给 DoP / Native 的 packer 再封装。
     *
     * 独立入口而不是给 [open] 加参数：JNI 签名一旦两边产物不一致，native 会按错位
     * 读参数直接崩 —— 这个坑踩过一次。
     *
     * @param info 回填 `[0]` = **1 bit 采样率**（2822400 / 5644800 …，不是 PCM 网格）、
     *   `[1]` = 声道数、`[2]` = 0（位深对 1-bit 流无意义）、`[3]` = 时长（毫秒）
     */
    external fun openRaw(path: String, info: IntArray): Long

    /**
     * 读一段 PCM（交错小端）。
     *
     * @param out 目标缓冲，容量即单次上限
     * @param meta 回填 `[0] = 这段第一个样本的 pts（微秒）`
     * @return 写入字节数；0 = 结束；<0 = 错误
     */
    external fun read(handle: Long, out: ByteArray, meta: LongArray): Int

    external fun seek(handle: Long, positionUs: Long)

    external fun release(handle: Long)
}
