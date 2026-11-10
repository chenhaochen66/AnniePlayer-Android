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
     * 打开成 **raw DSD**：只解封装，不解码。`read` 吐出的是**已经封装好的线格式字节**
     * （由 [configRawPack] 决定 DoP 6 B/帧还是 Native 8 B/帧）。
     *
     * 独立入口而不是给 [open] 加参数：JNI 签名一旦两边产物不一致，native 会按错位
     * 读参数直接崩 —— 这个坑踩过一次。
     *
     * @param info 回填 `[0]` = **1 bit 采样率**（2822400 / 5644800 …，不是 PCM 网格）、
     *   `[1]` = 声道数、`[2]` = 0（位深对 1-bit 流无意义）、`[3]` = 时长（毫秒）
     */
    external fun openRaw(path: String, info: IntArray): Long

    /** [configRawPack] 的 `mode`：不做封装，`read` 吐裸交错字节 */
    const val PACK_OFF = 0

    /** [configRawPack] 的 `mode`：DoP（24 bit 帧，标记 0x05/0xFA 逐帧交替） */
    const val PACK_DOP = 1

    /** [configRawPack] 的 `mode`：Native DSD（每声道 32 bit word） */
    const val PACK_NATIVE = 2

    /**
     * 设置 raw DSD 的**线格式封装**，`openRaw` 之后、第一次 `read` 之前调用。
     *
     * 封装在 native 里做（拆声道 → 位反转 → 打包一次完成）：这样只是少一次跨语言往返
     * 与一份 Kotlin 位运算；**更重要的是**它把 DoP 标记相位这种"跨块必须严格交替"
     * 的易失状态留在唯一的持有者（demux handle）里，不再有第二个副本。
     *
     * 独立入口而不是给 [openRaw] 加参数：签名一变，老 APK 配新 so 会按错位读参数崩。
     */
    external fun configRawPack(handle: Long, mode: Int, bigEndian: Boolean)

    /**
     * 读一段 PCM（或封装后的 DSD 线格式字节）。
     *
     * @param out 目标缓冲，容量即单次上限
     * @param meta 回填 `[0] = 这段第一个样本的 pts（微秒）`
     * @return 写入字节数；0 = 结束；<0 = 错误
     */
    external fun read(handle: Long, out: ByteArray, meta: LongArray): Int

    external fun seek(handle: Long, positionUs: Long)

    external fun release(handle: Long)
}
