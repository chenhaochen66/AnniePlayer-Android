package com.annieplayer.annie_music.kt.core.audio.dsp

import com.annieplayer.annie_music.kt.core.audio.HardwareVolumeCaps

/**
 * USB 独占内核的 JNI 入口。
 *
 * 契约与 [NativeOboeStream] 一致：`write` 返回写入帧数（0 = 环满，负数 = 流已废），
 * `framesRead` 只统计真数据（静音填充不算）。区别是这里没有"共享模式"概念 ——
 * 拿到接口就是独占，拿不到就开不了流。
 */
internal object NativeUacStream {

    @Volatile
    private var _loaded: Boolean? = null

    fun available(): Boolean {
        _loaded?.let { return it }
        val ok = runCatching { System.loadLibrary("annieuac") }.isSuccess
        _loaded = ok
        return ok
    }

    /**
     * @param fd `UsbDeviceConnection.fileDescriptor`
     * @param encoding Media3 的 `C.ENCODING_PCM_*`（**不是位深**：32 位有 float 与 int 两种）
     * @param wireBits 内容位宽（bit），0 = 不表态。给定时选「装得下且最窄」的 alt：
     *   只按包长挑会永远落在 32 bit subslot 上，24 bit 素材被塞进 32 bit 容器 ——
     *   位深显示会从 24 跳成 32，而硬件格式其实没变。
     */
    fun open(
        fd: Int,
        sampleRate: Int,
        encoding: Int,
        channels: Int,
        wireBits: Int = 0,
    ): Long {
        if (!available() || fd < 0) return 0L
        return runCatching {
            nativeOpen(fd, sampleRate, encoding, channels, wireBits)
        }.getOrDefault(0L)
    }

    /**
     * 打开成 **raw DSD 通道**（Native DSD）。
     *
     * @param wordRate **word 率**，不是 PCM 采样率：一个 32 bit word 装 32 个 1-bit 样本，
     *   所以 DSD64 → 88200、DSD128 → 176400、DSD256 → 352800、DSD512 → 705600。
     *   设备声明里 `Altset ... SPECIAL DSD_U32_BE` 的 `tSamFreq` 就是这套数。
     */
    fun openRaw(fd: Int, wordRate: Int, channels: Int): Long {
        if (!available() || fd < 0) return 0L
        return runCatching { nativeOpenRaw(fd, wordRate, channels) }.getOrDefault(0L)
    }

    /**
     * 换规格重建流，但**不重开 USB 连接、不释放接口**（true = 成功，句柄不变）。
     *
     * 与 `close + open` 的区别全在设备侧：后者会让设备从系统消失再出现、USB 侧掉电
     * 重初始化、DAC 重锁 PLL；这里只做 `SET_INTERFACE alt 0 → alt N → SET_CUR`，
     * 与内核换率的动作一致。
     */
    fun reopen(
        handle: Long,
        sampleRate: Int,
        encoding: Int,
        channels: Int,
        wireBits: Int = 0,
    ): Boolean {
        if (handle == 0L || sampleRate <= 0 || channels <= 0) return false
        return runCatching {
            nativeReopen(handle, sampleRate, encoding, channels, wireBits)
        }.getOrDefault(false)
    }

    /** 同 [reopen]，但把流重建到 **raw DSD alt**（或从 raw 换回 PCM 时用 [reopen]） */
    fun reopenRaw(handle: Long, wordRate: Int, channels: Int): Boolean {
        if (handle == 0L || wordRate <= 0 || channels <= 0) return false
        return runCatching {
            nativeReopenRaw(handle, wordRate, channels)
        }.getOrDefault(false)
    }

    /** 只在 [open] 的同一线程有意义（native 侧是 thread_local） */
    fun lastError(): Int = if (available()) runCatching { nativeLastError() }.getOrDefault(0) else -1

    fun write(handle: Long, data: ByteArray, byteOffset: Int, byteCount: Int, frameBytes: Int): Int {
        if (handle == 0L || byteCount <= 0 || frameBytes <= 0) return 0
        return runCatching { nativeWrite(handle, data, byteOffset, byteCount, frameBytes) }
            .getOrDefault(-1)
    }

    fun pause(handle: Long) {
        if (handle != 0L) runCatching { nativePause(handle) }
    }

    fun resume(handle: Long) {
        if (handle != 0L) runCatching { nativeResume(handle) }
    }

    /** @param keepFrames 保留尾部帧数（0 = 全清），见 native 侧 `nativeFlush` */
    fun flush(handle: Long, keepFrames: Int = 0) {
        if (handle != 0L) runCatching { nativeFlush(handle, keepFrames) }
    }

    /** 复用同一条流时同步输入编码（USB 线上格式由 alt setting 决定，不随它变） */
    fun setEncoding(handle: Long, encoding: Int) {
        if (handle != 0L) runCatching { nativeSetEncoding(handle, encoding) }
    }

    fun close(handle: Long) {
        if (handle != 0L) runCatching { nativeClose(handle) }
    }

    fun framesRead(handle: Long): Long =
        if (handle == 0L) 0L else runCatching { nativeFramesRead(handle) }.getOrDefault(0L)

    fun framesWritten(handle: Long): Long =
        if (handle == 0L) 0L else runCatching { nativeFramesWritten(handle) }.getOrDefault(0L)

    fun underrunCount(handle: Long): Long =
        if (handle == 0L) 0L else runCatching { nativeUnderrunCount(handle) }.getOrDefault(0L)

    /**
     * 这条流还能不能出声。`false` = 它已经 `dead`（或已停），**复用分支救不回来** ——
     * 只能拆流重建 / 重开会话。见 native 侧 `nativeStreamHealthy` 的注释。
     */
    fun streamHealthy(handle: Long): Boolean =
        handle != 0L && runCatching { nativeStreamHealthy(handle) }.getOrDefault(false)

    /** 设备 `GET_CUR` 回读的采样率；0 = 拿不到。与请求值不等 = 率没设进去 */
    fun deviceRate(handle: Long): Int =
        if (handle == 0L) 0 else runCatching { nativeDeviceRate(handle) }.getOrDefault(0)

    fun ringCapacityFrames(handle: Long): Int =
        if (handle == 0L) 0 else runCatching { nativeBufferSize(handle) }.getOrDefault(0)

    /** 线上每帧字节数（subslot × 声道）；0 = 拿不到 */
    fun outFrameBytes(handle: Long): Int =
        if (handle == 0L) 0 else runCatching { nativeOutFrameBytes(handle) }.getOrDefault(0)

    fun describe(handle: Long): String =
        if (handle == 0L) "" else runCatching { nativeDescribe(handle) }.getOrDefault("")

    /** 只读描述符（不 claim、不设率），用于能力面板 */
    fun describeFd(fd: Int): String =
        if (fd < 0) "" else runCatching { nativeDescribeFd(fd) }.getOrDefault("")

    /**
     * 硬件音量能力。`null` = 设备没有 Feature Unit 的 Volume 控制（或只有一个档位）。
     *
     * 单位 1/256 dB 且**数值是衰减量**：0 = 不衰减，负值 = 衰减多少 dB。
     * 这条路上衰减发生在 DAC 内部，我方送出去的 PCM 一个字节都不动 ——
     * 是 bit perfect 下唯一能改音量的途径。
     */
    fun volumeCaps(handle: Long): HardwareVolumeCaps? {
        if (handle == 0L) return null
        val raw = runCatching { nativeVolumeCaps(handle) }.getOrNull() ?: return null
        if (raw.size < 5 || raw[0] == 0) return null
        val min = raw[1]
        val max = raw[2]
        if (max <= min) return null
        return HardwareVolumeCaps(
            min = min,
            max = max,
            step = raw[3].coerceAtLeast(1),
            value = raw[4].coerceIn(min, max),
        )
    }

    /** 请求硬件音量（1/256 dB 衰减量）。O(1)：只写一个 atomic，由 native 事件线程下发 */
    fun setHardwareVolume(handle: Long, value: Int) {
        if (handle != 0L) runCatching { nativeSetHardwareVolume(handle, value) }
    }

    /**
     * 设置软件增益（线性，0..1）。**在 native 的 `fill()` 里施加**。
     *
     * 不在 Kotlin 侧乘完再写环：环里压着约 250 ms 已提交数据，那样换音量要等它播完才生效。
     */
    fun setGain(handle: Long, gain: Float) {
        if (handle == 0L) return
        val q16 = (gain.coerceIn(0f, 1f) * 65536f).toInt()
        runCatching { nativeSetGain(handle, q16) }
    }

    /**
     * 只读设备能力（同样不 claim 接口）：是否存在 raw DSD alt、两侧各自声明了哪些率。
     *
     * @return `null` = 读不出来（fd 无效 / libusb 起不来）
     */
    fun capabilities(fd: Int): com.annieplayer.annie_music.kt.core.audio.dsd.UsbDacCaps? {
        if (fd < 0) return null
        val raw = runCatching { nativeCapabilities(fd) }.getOrNull() ?: return null
        if (raw.size < 2) return null
        val hasRaw = raw[0] != 0
        val pcmCount = raw[1].coerceAtLeast(0)
        if (raw.size < 2 + pcmCount + 1) return null
        val pcm = (0 until pcmCount).map { raw[2 + it] }.toSet()
        val rawCount = raw[2 + pcmCount]
        val rawRates = (0 until rawCount)
            .map { raw[3 + pcmCount + it] }
            .toSet()
        return com.annieplayer.annie_music.kt.core.audio.dsd.UsbDacCaps(
            // 空集 = 设备没声明（UAC2 的速率在 Clock Source 里），按"可以试"处理
            pcmRates = pcm.ifEmpty { null },
            hasRawAlt = hasRaw,
            rawRates = rawRates,
        )
    }

    private external fun nativeSetGain(handle: Long, gainQ16: Int)
    private external fun nativeVolumeCaps(handle: Long): IntArray?
    private external fun nativeSetHardwareVolume(handle: Long, value: Int)
    private external fun nativeCapabilities(fd: Int): IntArray?

    private external fun nativeOpenRaw(fd: Int, wordRate: Int, channels: Int): Long

    private external fun nativeOpen(
        fd: Int,
        sampleRate: Int,
        encoding: Int,
        channels: Int,
        wireBits: Int,
    ): Long
    private external fun nativeReopen(
        handle: Long,
        sampleRate: Int,
        encoding: Int,
        channels: Int,
        wireBits: Int,
    ): Boolean
    private external fun nativeReopenRaw(handle: Long, wordRate: Int, channels: Int): Boolean
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
    private external fun nativeFlush(handle: Long, keepFrames: Int)
    private external fun nativeSetEncoding(handle: Long, encoding: Int)
    private external fun nativeClose(handle: Long)
    private external fun nativeFramesRead(handle: Long): Long
    private external fun nativeFramesWritten(handle: Long): Long
    private external fun nativeUnderrunCount(handle: Long): Long
    private external fun nativeStreamHealthy(handle: Long): Boolean
    private external fun nativeDeviceRate(handle: Long): Int
    private external fun nativeBufferSize(handle: Long): Int
    private external fun nativeOutFrameBytes(handle: Long): Int
    private external fun nativeDescribe(handle: Long): String
    private external fun nativeDescribeFd(fd: Int): String
}
