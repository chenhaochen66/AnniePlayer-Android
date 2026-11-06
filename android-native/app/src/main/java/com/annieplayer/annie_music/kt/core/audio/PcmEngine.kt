package com.annieplayer.annie_music.kt.core.audio

import android.content.Context
import androidx.media3.common.C
import com.annieplayer.annie_music.kt.core.audio.dsp.NativeOboeStream
import com.annieplayer.annie_music.kt.core.audio.dsp.NativeUacStream
import com.annieplayer.annie_music.kt.core.diagnostics.RuntimeLog

/**
 * 直出内核。`AaudioSink` 的位置模型、看门狗、flush 重建都只依赖这组原语，
 * 换内核不改上层。
 *
 * 句柄语义与两个 native 后端一致：`open` 返回 0 表示开不出来，`write` 返回写入帧数
 * （0 = 环满，负数 = 流已废），`framesRead` 只统计真数据。
 */
internal interface PcmEngine {

    val id: String

    /**
     * @param encoding Media3 的 `C.ENCODING_PCM_*`。**不能用位深代替**：
     *   32 位既可能是 float 也可能是 int32，字节解释完全不同。
     * @param wireBits 内容位宽（bit），0 = 不表态。用来选「装得下且最窄」的端点格式
     */
    fun open(sampleRate: Int, encoding: Int, channels: Int, wireBits: Int = 0): Long

    /**
     * 打开成 **raw DSD 通道**（Native DSD）。能做的内核覆盖它，其余返回 0。
     *
     * @param sampleRate **word 率**（DSD64 → 88200、DSD256 → 352800），不是 PCM 采样率
     */
    fun openRaw(sampleRate: Int, channels: Int): Long = 0L

    fun close(handle: Long)

    fun write(handle: Long, data: ByteArray, byteOffset: Int, byteCount: Int, frameBytes: Int): Int

    fun pause(handle: Long)

    fun resume(handle: Long)

    /**
     * @param keepFrames 保留的尾部帧数（0 = 全清）。
     *   切歌时留 ~20 ms 垫住输出断点（见 native 侧 `nativeFlush`）；
     *   seek 必须传 0，否则会先听到旧位置的内容。
     */
    fun flush(handle: Long, keepFrames: Int = 0)

    /**
     * 换规格重建流，**保留设备连接与接口 claim**。
     *
     * 能这么做的内核返回 true，句柄不变；不支持或失败的返回 false，调用方走"拆掉重开"。
     */
    fun reopen(
        handle: Long,
        sampleRate: Int,
        encoding: Int,
        channels: Int,
        wireBits: Int = 0,
    ): Boolean = false

    /**
     * 同 [reopen]，但目标 alt 是 **raw DSD**。
     *
     * raw 与 PCM 是两套互斥的 alt setting，所以这件事必须显式传下去：
     * 只按率+声道重建会拿 PCM alt 去送 32 bit DSD word，听感满幅噪声且不报错。
     */
    fun reopenRaw(handle: Long, sampleRate: Int, channels: Int): Boolean = false

    /**
     * 复用同一条流时同步输入编码。
     *
     * USB 的线上格式由 alt setting 决定，位深逐首变化不需要重建流（重建要放掉接口、
     * DAC 重新锁时钟）；但 4 字节/声道时 float 与 int32 的解释不同，必须同步。
     */
    fun setInputEncoding(handle: Long, encoding: Int) {}

    fun framesRead(handle: Long): Long

    /**
     * 这条流还能不能出声。**默认 true**（AAudio 那边没有 dead 这个概念）。
     *
     * USB 独占的 `dead` 一旦置上就只会送静音，而它**只在拆流重建时清除** ——
     * 复用分支抱着死流不放，结果就是"连普通 PCM 也一起哑，只有换会话才恢复"。
     * 见 `NativeUacStream.streamHealthy`。
     */
    fun isHealthy(handle: Long): Boolean = true

    /** `[已播帧, nanoTime]`；null = 拿不到 */
    fun timestamp(handle: Long): LongArray?

    fun underruns(handle: Long): Long

    /**
     * 设备侧硬件音量能力；null = 这条内核没有这条通路（AAudio / Oboe 恒 null）。
     *
     * 有它时衰减发生在设备内部，我方送出去的 PCM 一个字节都不动 ——
     * 这是 bit perfect 下唯一能改音量的途径。
     */
    fun hardwareVolumeCaps(handle: Long): HardwareVolumeCaps? = null

    /** 请求硬件音量（1/256 dB 衰减量）。实现必须是 O(1)，不得在调用线程做控制传输。 */
    fun setHardwareVolume(handle: Long, value: Int) {}

    /**
     * 设置软件增益（线性）。**在 native 的填充阶段施加**，不是写数据时乘。
     *
     * 写数据时乘的话，换音量要等环形缓冲里约 250 ms 的旧增益数据播完才听得到；
     * 放到填充阶段，环里存的是原始样本，延迟退化成在飞 URB 的深度。
     */
    fun setGain(handle: Long, gain: Float) {}

    /** sink 生命结束（不是换曲）时调用 */
    fun release() {}
}

internal object OboeEngine : PcmEngine {

    override val id: String get() = "AAudio"

    override fun open(sampleRate: Int, encoding: Int, channels: Int, wireBits: Int): Long {
        val depth = when (encoding) {
            C.ENCODING_PCM_16BIT -> 16
            C.ENCODING_PCM_24BIT -> 24
            else -> 32
        }
        return NativeOboeStream.open(sampleRate, depth, channels, exclusive = true)
    }

    override fun close(handle: Long) = NativeOboeStream.close(handle)

    override fun write(
        handle: Long,
        data: ByteArray,
        byteOffset: Int,
        byteCount: Int,
        frameBytes: Int,
    ): Int = NativeOboeStream.write(handle, data, byteOffset, byteCount, frameBytes)

    override fun pause(handle: Long) = NativeOboeStream.pause(handle)
    override fun resume(handle: Long) = NativeOboeStream.resume(handle)
    override fun setGain(handle: Long, gain: Float) = NativeOboeStream.setGain(handle, gain)
    override fun flush(handle: Long, keepFrames: Int) = NativeOboeStream.flush(handle)
    override fun framesRead(handle: Long): Long = NativeOboeStream.framesRead(handle)
    override fun timestamp(handle: Long): LongArray? = NativeOboeStream.timestamp(handle)
    override fun underruns(handle: Long): Long = NativeOboeStream.underrunCount(handle)
}

/**
 * USB 独占：会话（`UsbDeviceConnection`）跟着引擎活，流可以随 seek 重建多次。
 *
 * 拿不到授权或没有设备时 [open] 返回 null，调用方回落系统通路 —— 这是唯一的降级点。
 */
internal class UacEngine private constructor(
    private val session: UsbAudioDirect.Session,
) : PcmEngine {

    override val id: String get() = "USB"

    override fun open(sampleRate: Int, encoding: Int, channels: Int, wireBits: Int): Long {
        // 不在这里回读设备率：`nativeDeviceRate` 是一次同步控制传输，而 libusb 的同步等待
        // 会**在调用线程上**派发已完成的 URB 回调 —— 播放线程会因此跑进 `fill()`，
        // 与事件线程并发改相位累加器。率是否正确由 native 侧 `open()` 自己判定。
        UsbAudioDirect.markSelfChange()
        val handle = NativeUacStream.open(session.fd, sampleRate, encoding, channels, wireBits)
        if (handle == 0L) {
            RuntimeLog.record("USB", "开流失败 err=${NativeUacStream.lastError()}")
            return 0L
        }
        publishDescribe(handle)
        return handle
    }

    override fun openRaw(sampleRate: Int, channels: Int): Long {
        UsbAudioDirect.markSelfChange()
        val handle = NativeUacStream.openRaw(session.fd, sampleRate, channels)
        if (handle == 0L) {
            RuntimeLog.record("USB", "raw DSD 开流失败 err=${NativeUacStream.lastError()}")
            return 0L
        }
        publishDescribe(handle)
        return handle
    }

    /**
     * 把 native 的开流描述搬进运行日志，并**单独**把硬件音量那一行留给能力面板。
     *
     * `open` 与 `openRaw` 都要走：第一首就直接放 DSD 时不会有普通 `open`，
     * 少了这一处面板上就永远是空的。
     */
    private fun publishDescribe(handle: Long) {
        val desc = NativeUacStream.describe(handle).trim()
        if (desc.isEmpty()) return
        RuntimeLog.record("USB", desc)
        // 开流参数可能在多次 reopen 里累积，取最后一条
        desc.lineSequence().lastOrNull { it.contains("流 raw=") }?.trim()?.let {
            AudioDirectProbe.recordUsbStream(it)
        }
    }

    override fun close(handle: Long) {
        UsbAudioDirect.markSelfChange()
        NativeUacStream.close(handle)
    }

    override fun reopen(
        handle: Long,
        sampleRate: Int,
        encoding: Int,
        channels: Int,
        wireBits: Int,
    ): Boolean {
        UsbAudioDirect.markSelfChange()
        if (!NativeUacStream.reopen(handle, sampleRate, encoding, channels, wireBits)) {
            RuntimeLog.record("USB", "换率重建流失败 err=${NativeUacStream.lastError()}")
            return false
        }
        // 重建会选新的 alt：面板必须跟着更新，否则显示的是开流时那一次的快照
        publishDescribe(handle)
        return true
    }

    override fun reopenRaw(handle: Long, sampleRate: Int, channels: Int): Boolean {
        UsbAudioDirect.markSelfChange()
        if (!NativeUacStream.reopenRaw(handle, sampleRate, channels)) {
            RuntimeLog.record("USB", "raw DSD 重建流失败 err=${NativeUacStream.lastError()}")
            return false
        }
        publishDescribe(handle)
        return true
    }

    override fun write(
        handle: Long,
        data: ByteArray,
        byteOffset: Int,
        byteCount: Int,
        frameBytes: Int,
    ): Int = NativeUacStream.write(handle, data, byteOffset, byteCount, frameBytes)

    override fun pause(handle: Long) = NativeUacStream.pause(handle)
    override fun resume(handle: Long) = NativeUacStream.resume(handle)
    override fun flush(handle: Long, keepFrames: Int) =
        NativeUacStream.flush(handle, keepFrames)
    override fun setInputEncoding(handle: Long, encoding: Int) =
        NativeUacStream.setEncoding(handle, encoding)

    override fun framesRead(handle: Long): Long = NativeUacStream.framesRead(handle)

    override fun isHealthy(handle: Long): Boolean = NativeUacStream.streamHealthy(handle)

    /** USB 侧没有硬件时间戳 → 位置靠 `framesRead`（与 AAudio 在本机的实际路径一致） */
    override fun timestamp(handle: Long): LongArray? = null

    override fun underruns(handle: Long): Long = NativeUacStream.underrunCount(handle)

    override fun hardwareVolumeCaps(handle: Long): HardwareVolumeCaps? =
        NativeUacStream.volumeCaps(handle)

    override fun setHardwareVolume(handle: Long, value: Int) =
        NativeUacStream.setHardwareVolume(handle, value)

    override fun setGain(handle: Long, gain: Float) = NativeUacStream.setGain(handle, gain)

    override fun release() {
        UsbAudioDirect.markSelfChange()
        session.close()
    }

    companion object {
        fun open(context: Context): UacEngine? {
            if (!UsbAudioDirect.platformReady() || !NativeUacStream.available()) return null
            val device = UsbAudioDirect.audioDevices(context).firstOrNull() ?: return null
            if (!UsbAudioDirect.hasPermission(context, device)) return null
            val session = UsbAudioDirect.open(context, device) ?: return null
            return UacEngine(session)
        }
    }
}
