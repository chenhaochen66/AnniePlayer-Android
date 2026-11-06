package com.annieplayer.annie_music.kt.core.audio.dsp

/**
 * JNI 桥：r8brain-free-src 重采样（MIT，见 `cpp/thirdparty/r8brain`）。
 *
 * 一个句柄内部持有**每声道一个** `r8b::CDSPResampler`（上游要求），
 * 交错 ↔ 解交错、格式互转都在 native 侧完成，这里只做参数转发。
 *
 * 句柄为 0 表示"不需要重采样"（源率 == 目标率）或创建失败 ——
 * 调用方据此走位深直通或整体旁路，**不会**有第二套滤波实现兜底。
 */
object NativeR8bResampler {

    private val loaded: Boolean = runCatching {
        System.loadLibrary("anniedsp")
    }.isSuccess

    fun isAvailable(): Boolean = loaded

    /**
     * @param srcBits 源位深（16 / 24 / 32）；用于按 `6.02×bits+40` 定阻带目标
     * @param lpfHz 输出端低通截止（Hz）；0 = 不要这一级。**只有 DSD 链路会传非 0**
     *   （策略在 `DsdOutput.dsdPcmLowpassHz()`）。非 0 时**即使 `srcRate == dstRate`**
     *   也会建出一个句柄 —— 那时 `rs` 为空，只剩最小相位低通在干活。
     * @param transBand 过渡带（% of Nyquist）；**0 = 用 native 的默认值（2.0，无损那一档）**。
     *   有损源传 4.5（判据见 `ResampleDepth.resampleTransitionBandPercent()`）：
     *   有损编码在 21 kHz 以上没有信号，把过渡带放宽只赚不亏 —— 滤波器更短、振铃更短。
     * @return 句柄；0 = 同率且无低通（真的没事干）或创建失败
     */
    external fun create(
        channels: Int,
        srcRate: Int,
        dstRate: Int,
        srcBits: Int,
        lpfHz: Int,
        transBand: Double,
    ): Long

    /**
     * 本次调用最多会产出多少帧（上游 `getMaxOutLen()`，按构造时的 MaxInLen 给的保守上界）。
     *
     * 不要用"输入帧数 × 比率"估：r8brain 的输出样本数是可变的（暖机期可能为 0），
     * 按比例算会漏掉内部各级滤波器的延迟余量。
     */
    external fun maxOutFrames(handle: Long, inFrames: Int): Int

    /**
     * @return 写入 [output] 的字节数；0 = 还需要更多输入（暖机期）
     */
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

    /**
     * 取走「自上次调用以来」的最大 `|样本|`（**1.0 = 满刻度**），并归零。
     *
     * 这是真峰值监测：重采样会改变波形，0 dBFS 的母带转完之后可能超过满刻度
     * （互样本过冲，典型 +0.3…1 dB）。以前 `writeSample` 只是悄悄夹住 ——
     * 削了多少次、削了多深，外面一无所知。
     */
    external fun takePeak(handle: Long): Double

    /** 超满刻度的样本数（自上次调用以来），并归零。 */
    external fun takeClipCount(handle: Long): Int
}
