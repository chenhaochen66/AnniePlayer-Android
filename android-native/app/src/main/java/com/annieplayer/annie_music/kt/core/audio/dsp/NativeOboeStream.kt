package com.annieplayer.annie_music.kt.core.audio.dsp

/**
 * Oboe 播放流的 JNI 入口（[com.annieplayer.annie_music.kt.core.audio.AaudioSink] 唯一的独占内核）。
 *
 * ## 契约
 *
 * ```
 * open  → 非 0 句柄；失败 0（原因用 lastError 取）
 * write → 返回**写入的帧数**；0 = 这次塞不下（调用方下一轮用同一块重试）；负数 = 流已废
 * framesRead → 设备真正取走的帧数（**静音填充不算**，所以暂停时它会冻结）
 * ```
 *
 * ## 加载隔离
 *
 * 单独一个 `libannieoboe.so`（见 `CMakeLists.txt` 里的 `annieoboe` target）。
 * 它加载失败只意味着"这一级不成立" —— [available] 返回 false，`AaudioSink.configure`
 * 里 `open` 拿到 0 → 直接选系统引擎，声音照常出，不会连带把调音/软解拖下水。
 * 所以这条日志（`AudioDirectProbe` 的「独占内核」自检）必须留着：
 * 没有它，"怎么一点独占都没有"就只能靠猜。
 *
 * ## 三条刻意的语义
 *
 * | 场景 | 行为 | 为什么 |
 * |---|---|---|
 * | 环形缓冲满 | `write` **立刻**返回 0 | 调用方（Media3 的 `handleBuffer`）本来就允许"这块没收下，下一轮重试"，不需要阻塞 |
 * | 暂停 | 回调改出静音，**不从缓冲取数据** | `framesRead` 因此冻结 → 位置冻结，不需要额外状态机；也避开了 `requestPause` 让 HAL 卡在 PAUSING 的坑 |
 * | 静音帧 | **不计入** `framesRead` | 静音不是媒体时间。算进去的话暂停期间进度条会继续跑 |
 * | 设备被抢 | `onErrorAfterClose` 置标志 → 后续 `write` 返回 -1 | 上层按"写失败"走同一条降级通道：`onEngineFailure` → 重建音源 |
 */
internal object NativeOboeStream {

    @Volatile
    private var _loaded: Boolean? = null

    /**
     * 这个后端现在能不能用（库能不能加载）。
     *
     * **只有加载成功才返回 true**：`liboboe.so` 依赖 `libc++_shared.so`，两者任一缺失都会
     * 在 `loadLibrary` 阶段失败。失败时静默回落 raw 后端，而不是让整个播放链报错。
     */
    fun available(): Boolean {
        _loaded?.let { return it }
        val ok = runCatching { System.loadLibrary("annieoboe") }.isSuccess
        _loaded = ok
        return ok
    }

    fun open(sampleRate: Int, depth: Int, channels: Int, exclusive: Boolean): Long {
        if (!available()) return 0L
        return runCatching { nativeOpen(sampleRate, depth, channels, exclusive) }.getOrDefault(0L)
    }

    fun lastError(): Int = if (available()) runCatching { nativeLastError() }.getOrDefault(0) else -1

    /** 写入 PCM（非阻塞）。返回写入帧数；0 = 缓冲满；负数 = 流已断开 */
    fun write(handle: Long, data: ByteArray, byteOffset: Int, byteCount: Int, frameBytes: Int): Int {
        if (handle == 0L || byteCount <= 0 || frameBytes <= 0) return 0
        // 没有 timeout 参数：Oboe 侧由环形缓冲的剩余空间决定能写多少，不需要等
        return runCatching { nativeWrite(handle, data, byteOffset, byteCount, frameBytes) }
            .getOrDefault(-1)
    }

    fun pause(handle: Long) {
        if (handle != 0L) runCatching { nativePause(handle) }
    }

    fun resume(handle: Long) {
        if (handle != 0L) runCatching { nativeResume(handle) }
    }

    /** 软件增益（线性 0..1）。在 native 的回调里施加，所以换音量立刻生效 */
    fun setGain(handle: Long, gain: Float) {
        if (handle == 0L) return
        val q16 = (gain.coerceIn(0f, 1f) * 65536f).toInt()
        runCatching { nativeSetGain(handle, q16) }
    }

    fun flush(handle: Long) {
        if (handle != 0L) runCatching { nativeFlush(handle) }
    }

    fun close(handle: Long) {
        if (handle != 0L) runCatching { nativeClose(handle) }
    }

    /** 硬件时钟：`[已播帧位置, nanoTime]`；null = 拿不到（本机 100% 拿不到） */
    fun timestamp(handle: Long): LongArray? {
        if (handle == 0L) return null
        val out = LongArray(2)
        val ok = runCatching { nativeTimestamp(handle, out) }.getOrDefault(false)
        return if (ok) out else null
    }

    fun framesWritten(handle: Long): Long =
        if (handle == 0L) 0L else runCatching { nativeFramesWritten(handle) }.getOrDefault(0L)

    /** 1 = 真的拿到了 EXCLUSIVE，0 = 只拿到 SHARED，-1 = 句柄无效 */
    fun sharingMode(handle: Long): Int =
        if (handle == 0L) -1 else runCatching { nativeSharingMode(handle) }.getOrDefault(-1)

    /** 流的**真实**采样率；0 = 拿不到 */
    fun sampleRate(handle: Long): Int =
        if (handle == 0L) 0 else runCatching { nativeSampleRate(handle) }.getOrDefault(0)

    /** 流的**真实** `aaudio_format_t`（I16=1 / FLOAT=2 / I24_PACKED=3）；0 = 拿不到 */
    fun format(handle: Long): Int =
        if (handle == 0L) 0 else runCatching { nativeFormat(handle) }.getOrDefault(0)

    /** 设备真正取走的帧数（静音填充不算）—— 位置模型的主要来源之一 */
    fun framesRead(handle: Long): Long =
        if (handle == 0L) 0L else runCatching { nativeFramesRead(handle) }.getOrDefault(0L)

    /** 缓冲容量（帧）；0 = 拿不到。远大于请求值 = HAL 不接受小缓冲 */
    fun bufferCapacity(handle: Long): Int =
        if (handle == 0L) 0 else runCatching { nativeBufferCapacity(handle) }.getOrDefault(0)

    /** 当前生效的缓冲深度（帧）；0 = 拿不到 */
    fun bufferSize(handle: Long): Int =
        if (handle == 0L) 0 else runCatching { nativeBufferSize(handle) }.getOrDefault(0)

    /** 流状态（枚举值与 `aaudio_stream_state_t` 一致）；0 = 拿不到 */
    fun streamState(handle: Long): Int =
        if (handle == 0L) 0 else runCatching { nativeStreamState(handle) }.getOrDefault(0)

    /**
     * 实际后端：1 = AAudio，2 = OpenSL ES，0 = 拿不到。
     *
     * 这条必须能读：**OpenSL ES 没有 EXCLUSIVE 概念**，请求独占也只会拿到 SHARED。
     * 之前 Oboe 用了 OpenSL 却只表现为"独占不生效"，就是缺这一个观测点
     * （原因：预编译 `liboboe.so` 按 api 21 编，默认后端是编译期决定的 → OpenSL）。
     */
    fun audioApi(handle: Long): Int =
        if (handle == 0L) 0 else runCatching { nativeAudioApi(handle) }.getOrDefault(0)

    /**
     * 独占能力探测：三个组合各开一条 EXCLUSIVE 流，回读实际拿到的共享模式。
     *
     * 返回 `[callback+capacity, 无callback+capacity, 无callback+无capacity]`：
     * `1` = EXCLUSIVE、`0` = SHARED、`<= -100` = 开流失败。
     *
     * 存在的原因很具体：RAW 后端（推模式）在这台机器上能拿到独占，Oboe（回调拉模式）
     * 拿不到。两边参数看起来一样，差异只剩 callback 与 bufferCapacity —— 与其反复猜，
     * 不如一次把三个组合都测出来。结果写进运行日志与「输出能力（实测）」面板。
     *
     * 只 open 不 start：不出声，探测完立刻 close。
     */
    fun probeSharingMode(sampleRate: Int, depth: Int, channels: Int): IntArray? {
        if (!available() || sampleRate <= 0 || channels <= 0) return null
        return runCatching { nativeProbeSharingMode(sampleRate, depth, channels) }.getOrNull()
    }

    /** 欠载次数 —— 只用于面板展示，不参与任何判定 */
    fun underrunCount(handle: Long): Long =
        if (handle == 0L) 0L else runCatching { nativeUnderrunCount(handle) }.getOrDefault(0L)

    private external fun nativeOpen(
        sampleRate: Int,
        depth: Int,
        channels: Int,
        exclusive: Boolean,
    ): Long

    private external fun nativeLastError(): Int

    private external fun nativeWrite(
        handle: Long,
        data: ByteArray,
        byteOffset: Int,
        byteCount: Int,
        frameBytes: Int,
    ): Int

    private external fun nativePause(handle: Long)
    private external fun nativeResume(handle: Long)
    private external fun nativeSetGain(handle: Long, gainQ16: Int)
    private external fun nativeFlush(handle: Long)
    private external fun nativeClose(handle: Long)
    private external fun nativeTimestamp(handle: Long, out: LongArray): Boolean
    private external fun nativeFramesWritten(handle: Long): Long
    private external fun nativeSharingMode(handle: Long): Int
    private external fun nativeSampleRate(handle: Long): Int
    private external fun nativeFormat(handle: Long): Int
    private external fun nativeFramesRead(handle: Long): Long
    private external fun nativeBufferCapacity(handle: Long): Int
    private external fun nativeBufferSize(handle: Long): Int
    private external fun nativeStreamState(handle: Long): Int
    private external fun nativeAudioApi(handle: Long): Int
    private external fun nativeProbeSharingMode(
        sampleRate: Int,
        depth: Int,
        channels: Int,
    ): IntArray
    private external fun nativeUnderrunCount(handle: Long): Long
}
