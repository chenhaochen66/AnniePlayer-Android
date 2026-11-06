package com.annieplayer.annie_music.kt.core.audio.dsd

/**
 * Native DSD（UAC2 raw）打包：32 个 1-bit 样本装进一个 32-bit word。
 *
 * ## 为什么必须重排
 *
 * 解封装出来的是**每样本 1 字节、声道交错**：
 *
 * ```
 * L0 R0 L1 R1 L2 R2 L3 R3 …
 * ```
 *
 * 而 raw DSD 端点要的是**每声道一个 32-bit word、word 之间交错**：
 *
 * ```
 * [L0 L1 L2 L3] [R0 R1 R2 R3] [L4 L5 L6 L7] …
 * ```
 *
 * 所以要把每 8 个输入字节重新分组成两个 word，不能直接拷贝。
 *
 * ## 字节序
 *
 * `DSD_U32_BE`（XMOS 系端点，绝大多数小尾巴）要求字节顺序 = 时间顺序，
 * 最早 8 个样本在前；`DSD_U32_LE` 则是每 4 字节反过来的。
 *
 * 写错方向不会报错，只会让 DAC 解出一片噪声，且与"标记不对"完全同症状。
 *
 * ## 输入的前提
 *
 * `dsd` 必须是 **MSB-first**（字节内 bit7 = 最早样本）。DSF 是 LSB-first，
 * 那一层位反转在 `native-ffmpeg-demux.cpp` 的 raw 分支里已经做掉了。
 */
object NativeDsdPacker {

    /** 每个 word 的字节数 */
    const val BYTES_PER_WORD = 4

    /** 一个 word 装 32 个 1-bit，也就是每声道 4 个字节 */
    private const val BITS_PER_WORD = 32

    /** 每输出「一组」（左 word + 右 word）的字节数 */
    const val BYTES_PER_PAIR = BYTES_PER_WORD * 2

    /**
     * @param dsd 交错源数据 `[L0,R0,L1,R1,…]`
     * @param words 每声道的 word 数；需要 `words * 32 * 2` 字节输入
     * @param out 输出缓冲；需要 `words * BYTES_PER_PAIR` 字节
     * @param bigEndian true = `DSD_U32_BE`（字节顺序与时间顺序一致）
     * @return 写入字节数；缓冲不够返回 -1 且不写
     */
    fun pack(
        dsd: ByteArray,
        dsdOffset: Int,
        words: Int,
        out: ByteArray,
        outOffset: Int,
        bigEndian: Boolean,
    ): Int {
        val needIn = words * BYTES_PER_WORD * 2
        val needOut = words * BYTES_PER_PAIR
        if (words <= 0 || dsdOffset + needIn > dsd.size || outOffset + needOut > out.size) return -1

        var si = dsdOffset
        var oi = outOffset
        for (w in 0 until words) {
            // 左 word：从交错流的偶数位连续取 4 个样本
            if (bigEndian) {
                out[oi] = dsd[si]
                out[oi + 1] = dsd[si + 2]
                out[oi + 2] = dsd[si + 4]
                out[oi + 3] = dsd[si + 6]
                out[oi + 4] = dsd[si + 1]
                out[oi + 5] = dsd[si + 3]
                out[oi + 6] = dsd[si + 5]
                out[oi + 7] = dsd[si + 7]
            } else {
                out[oi] = dsd[si + 6]
                out[oi + 1] = dsd[si + 4]
                out[oi + 2] = dsd[si + 2]
                out[oi + 3] = dsd[si]
                out[oi + 4] = dsd[si + 7]
                out[oi + 5] = dsd[si + 5]
                out[oi + 6] = dsd[si + 3]
                out[oi + 7] = dsd[si + 1]
            }
            si += BYTES_PER_WORD * 2
            oi += BYTES_PER_PAIR
        }
        return needOut
    }

    /** 输入字节能产生多少 word（不足一个 word 的尾巴丢掉，不补零） */
    fun wordsOf(dsdBytes: Int): Int =
        if (dsdBytes <= 0) 0 else dsdBytes / (BYTES_PER_WORD * 2)

    /**
     * 某个 DSD 档位的 raw 帧率（= 交给端点的 PCM 采样率）。
     *
     * 32 个 1-bit 一个 word，所以是 DoP 的一半：DSD64 → 88.2 kHz、DSD128 → 176.4 kHz。
     */
    fun packetRate(bitRate: Int): Int = bitRate / BITS_PER_WORD
}
