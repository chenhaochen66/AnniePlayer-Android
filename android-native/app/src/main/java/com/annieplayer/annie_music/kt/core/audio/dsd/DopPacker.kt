package com.annieplayer.annie_music.kt.core.audio.dsd

/**
 * DoP（DSD over PCM）打包 —— **线格式的参考实现**。
 *
 * 生产路径已经不在 Kotlin 里打包：`native-ffmpeg-demux.cpp` 的 `packDop()` 做同一件事
 * （拆声道 → 位反转 → 打包一次完成），Kotlin 只转发字节。这里保留一份，是因为
 * 帧布局本身是这个项目里**唯一不能靠听感验**的东西（标记写错与字节序写错，症状都是噪声），
 * `DopPackerTest` 逐字节断言的就是规范。**改这里必须同时改 native 的 `packDop()`。**
 *
 * ## 帧结构
 *
 * 一个 24-bit 帧装 2 个 DSD 字节（16 个 1-bit 样本）：
 *
 * ```
 * 数值（24 bit）= (标记 << 16) | (字节A << 8) | 字节B
 * 存储（小端 3 字节）= [字节B, 字节A, 标记]
 * ```
 *
 * 标记在 `0x05` / `0xFA` 之间**逐帧交替**，DAC 靠这个序列判定"这是 DSD 不是 PCM"。
 * 序列一断设备就退回 PCM —— 那不是音质问题，是满幅噪声，所以这里不能有任何容错分支。
 *
 * ## 帧率
 *
 * `DSD 位率 / 16`：DSD64 → 176 400 Hz，DSD128 → 352 800 Hz，DSD256 → 705 600 Hz。
 * 这个率就是交给 USB 端点的 PCM 采样率。
 *
 * ## 为什么字节是 A/B 而不是 B/A
 *
 * DSD 每个字节里**最高位是最早的样本**（MSB-first），而 DoP 要求 16 位数据字段的
 * 第 15 位是时间上最早的那个样本。两者对齐之后，时间上先出现的 DSD 字节落在高 8 位，
 * 也就是小端存储的**第二个字节**。写反的听感就是噪声 —— 而它和"标记不对"完全同症状，
 * 排查时只能靠对规范逐字核，所以这里把推导写下来。
 *
 * MSB-first 是**输入前提**：DSF 存的是 LSB-first，那一层位反转在
 * `native-ffmpeg-demux.cpp` 的 raw 分支里已经做掉了。
 */
object DopPacker {

    /** 标记 A：帧序号偶数 */
    const val MARKER_A = 0x05

    /** 标记 B：帧序号奇数 */
    const val MARKER_B = 0xFA

    /** 一个 DSD 字节 → 半个 24-bit 帧 */
    private const val DSD_BYTES_PER_FRAME = 2

    /** 每帧输出字节数：2 声道 × 3 字节 */
    const val BYTES_PER_FRAME = 6

    /**
     * 交错的 1-bit 输入（左右交替，每声道每帧 2 字节）→ DoP 帧。
     *
     * @param dsd 交错源数据，`[L0,L1,R0,R1,L2,L3,R2,R3,…]`，与 DSF 的块内布局一致
     * @param frames 帧数；需要 `frames * 4` 字节输入
     * @param out 输出缓冲；需要 `frames * BYTES_PER_FRAME` 字节
     * @param markerHigh 本块第一帧用 `MARKER_B`（配合上一块最后一帧用 A 时传 false）
     * @return 写入的字节数；输入或输出不够时返回 **-1** 且不写
     */
    fun pack(
        dsd: ByteArray,
        dsdOffset: Int,
        frames: Int,
        out: ByteArray,
        outOffset: Int,
        markerHigh: Boolean,
    ): Int {
        val needIn = frames * DSD_BYTES_PER_FRAME * 2
        val needOut = frames * BYTES_PER_FRAME
        if (frames <= 0 || dsdOffset + needIn > dsd.size || outOffset + needOut > out.size) return -1

        var si = dsdOffset
        var oi = outOffset
        var high = markerHigh
        for (n in 0 until frames) {
            out[oi + 2] = if (high) MARKER_B.toByte() else MARKER_A.toByte()
            // 输入是**字节交错** [L0,R0,L1,R1]（见 native-ffmpeg-demux.cpp 的 rawDsd 分支：
            // 它把两个声道的 block 按字节并排吐出来），不是 DSF 文件里的「每声道 2 字节」块。
            //
            // 左声道两字节 = L0(si) / L1(si+2)，时间上先出现的是 L0 → 进高 8 位 →
            // 小端存储的第二个字节；L1 落最低字节。
            out[oi] = dsd[si + 2]
            out[oi + 1] = dsd[si]
            out[oi + 5] = if (high) MARKER_B.toByte() else MARKER_A.toByte()
            // 右声道两字节 = R0(si+1) / R1(si+3)
            out[oi + 3] = dsd[si + 3]
            out[oi + 4] = dsd[si + 1]
            si += 4
            oi += BYTES_PER_FRAME
            high = !high
        }
        return needOut
    }

    /**
     * 输入数据能产生多少帧。
     *
     * 不足一帧的尾巴**丢掉**而不是补零：补零等于在 DSD 流里插入静音样本，
     * 会让整条流与设备时钟错位。
     */
    fun framesOf(dsdBytes: Int): Int =
        if (dsdBytes <= 0) 0 else dsdBytes / (DSD_BYTES_PER_FRAME * 2)

    /** 某个 DSD 档位对应的 DoP 帧率（= 交给端点的 PCM 采样率） */
    fun packetRate(bitRate: Int): Int = bitRate / (DSD_BYTES_PER_FRAME * 8)
}
