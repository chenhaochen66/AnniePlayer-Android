package com.annieplayer.annie_music.kt.core.audio

/**
 * 重采样的阻带该按多少 Bit 算 —— **按源的实际精度，不按解码器出口的容器格式**。
 *
 * ## 问题从哪来
 *
 * `native-r8b-resampler.cpp` 的 `attenuationForBits()` 用 `6.02 × bits + 40` 定阻带，
 * 本意是"按源的噪声底算"。但处理器能看到的唯一事实是
 * `AudioProcessor.AudioFormat.encoding`：
 *
 * - MediaCodec 解 MP3 / AAC 交出来是 16 Bit → `892` 那档，没问题；
 * - **Apple 链路（ALAC 与 AAC 都走 ffmpeg）一律交 `ENCODING_PCM_FLOAT`** →
 *   被当成 32 Bit → 阻带 206.91 dB → 44.1k→48k 实测 `flt_len = 1621`。
 *
 * 而对**有损**源，这个 32 Bit 是假精度：噪声底由编码决定（−60…−90 dBFS 量级），
 * 比 16 Bit 的 −96 dB 还高。按 32 Bit 算换不来任何东西，只换来更长的滤波器。
 *
 * ## 实测代价（`R8BCONSOLE=printf` 打印上游自己的设计参数，44.1k→48k / 2% / 最小相位）
 *
 * | srcBits | 阻带 | `flt_len` | BlockConvolver 的 `in_len` |
 * |---|---|---|---|
 * | 16 | 136.32 dB | **1081** | 3016 |
 * | 24 | 184.48 dB | 1451 | 2646 |
 * | 32 | 206.91 dB | 1621 | 2476 |
 *
 * ⚠️ 别按抽头比例估算力：r8brain 是 FFT 分块卷积，抽头变短**不会**让 FFT 变小
 * （三档都是 2048/4096），只是每块能多吃几个样本 —— 实际是 `in_len` 从 2476 变 3016，
 * 也就是 **18% 量级**的算力差，不是"砍一半就省一半"。附带收益是脉冲响应更短。
 *
 * ## 为什么 136 dB 就够
 *
 * 任何 DAC 的本底都在 −120…−130 dB 量级。136 dB 的阻带意味着镜像/混叠落在比器材自噪
 * 更低的电平上；再往上加只对"纯数字链路的测量"有意义。这条判据是 `6.02 × bits + 40`
 * 里那个 `40` 的来历（给动态信号留的余量），不是拍脑袋。
 *
 * ## 返回值语义
 *
 * `0` = **让处理器按输入编码自行推断**（不知道编码时保持旧行为）。这不是"猜 16"，
 * 是"这条信息我没有，不做决定"。
 *
 * @param codec 源编码（`AudioInfo.codec`，来自曲库索引或 ffprobe；空 = 不知道）
 * @param declaredDepth 源声明的位深（bit）；0 = 不知道
 */
fun resampleAttenuationBits(codec: String?, declaredDepth: Int): Int {
    val c = codec?.trim()?.lowercase().orEmpty()
    if (c.isEmpty()) return 0
    // 有损：噪声底由编码决定，位深给到 16 已经是往上取了
    if (c in LOSSY_CODECS) return 16
    // 无损：按它自己声明的位深算；声明没了就交回给处理器推断
    if (c in LOSSLESS_CODECS) return declaredDepth.takeIf { it in 16..32 } ?: 0
    // 不认识的编码：不猜（旧行为）
    return 0
}

/**
 * 有损编码的过渡带该放多宽（% of Nyquist）；**0 = 用 native 默认的 2.0（无损那一档）**。
 *
 * ## 为什么有损可以放宽到 5
 *
 * 当年把 `kTransBand` 定在 2.0 的前提是"要保住高频"（2% vs 5% 试过四种组合，
 * 5% 的高频滚降换不来振铃改善）。**有损源没有这个前提**：编码器自己的低通早就在
 * 17.5–21.6 kHz 把带子切了（实测 MP3 128k ≈ 17.5k、MP3 320k ≈ 21.6k、
 * Opus 128k ≈ 21.6k、AAC 128k ≈ 19.5k），再往上多是编码噪声。
 *
 * 5% 在 44.1k 上把 −3 dB 点放在 **20.95 kHz**，换来更短的滤波器：
 *
 * | 过渡带 | 阻带 | `flt_len`（44.1k→48k, 16 bit） | 振铃时长（≈1/过渡带宽） |
 * |---|---|---|---|
 * | 2.0% | 136.32 dB | 1081 | 2.3 ms |
 * | 4.5% | 136.32 dB | 481 | 1.0 ms |
 * | **5.0%** | 136.32 dB | **433** | **0.9 ms** |
 *
 * 对**本来就带噪声的有损源**，振铃落在噪声上、不落在音乐上，这才是留它的理由。
 * 顺带一提：5% 正是当年实听时"沙沙消失"的那一档 —— 只不过那次是配线性相位，
 * 现在配的是最小相位，方向一致。
 *
 * ## ⚠️ 实测：这条**不省 CPU**，反而略慢
 *
 * 第一直觉是"抽头变短就算力变小"，**错了**。r8brain 会把 FFT 块跟着缩小：
 *
 * | 配置 | `flt_len` | BlockConvolver `in_len` | FFT | 30 s 立体声 ×10 次用户态 CPU |
 * |---|---|---|---|---|
 * | 32 Bit / 2.0% | 1621 | 2476 | 2048 | 0.74 s |
 * | 32 Bit / 5.0% | 649 | 1400 | 1024 | ≈0.74 s |
 * | 16 Bit / 2.0% | 1081 | 3016 | 2048 | **0.63 s** |
 * | 16 Bit / 5.0% | 433 | 592 | **512** | ≈0.67 s |
 *
 * `in_len`（每块能吃多少输入样本）从 3016 掉到 592 ⇒ 每秒的 FFT 次数变多，
 * 把"块变小"的好处吃掉还倒亏。所以**省 CPU 的是阻带那条（−15%），不是这条**；
 * 这条买的是"更短的振铃"，不是电。
 *
 * ## 唯一"看得到"的代价，说清楚
 *
 * 全带宽的有损编码（ffmpeg 原生 AAC 在 256k **不切高频**，实测 21.6–22.05 kHz
 * 仍有 −47.9 dBFS，与无损同量级）会被 5% 从 20.95 kHz 起按 −3 dB 滚降 ——
 * 比 4.5% 那一档再低 0.1 kHz、滚降更早一点。那是听阈边缘的 3 dB，本项目接受这个代价。
 * **千万不要**把这个值用到无损上 —— 无损的 2.0 是实听验出来的结论，别推翻。
 *
 * @param codec 源编码（`AudioInfo.codec`；空 = 不知道）
 * @return 过渡带百分比；**0 = 用默认 2.0**
 */
fun resampleTransitionBandPercent(codec: String?): Double {
    val c = codec?.trim()?.lowercase().orEmpty()
    if (c.isEmpty()) return 0.0
    // 判据与阻带那条**必须**一致：有损 / 无损是同一个事实，不能一个按有损算、一个按无损算
    return if (c in LOSSY_CODECS) LOSSY_TRANS_BAND_PERCENT else 0.0
}

/**
 * 有损源的过渡带（% of Nyquist）。**5.0%** 在 44.1k 上是 **−3 dB @ 20.95 kHz**。
 *
 * 这是"不再咬到任何真实内容"的上限附近：MP3 320k 的编码器低通在 ≈21.6 kHz，
 * 5% 会在 20.5 kHz 起就开始轻微下压（那里大约 −1 dB 以内）；再宽就会明显咬进
 * 可闻区了。所以别再往上调。
 */
private const val LOSSY_TRANS_BAND_PERCENT: Double = 5.0

/**
 * 有损编码。判据是"码流里没有原始 PCM"，与容器无关（AAC 在 m4a / adts / HLS 里都一样）。
 *
 * 刻意不收 `pcm_*`：那由 [LOSSLESS_CODECS] 之外的分支处理，且**必须**走无损那条。
 */
private val LOSSY_CODECS: Set<String> = setOf(
    "aac", "mp4a", "he-aac", "heaac", "aac_latm",
    "mp3", "mp2", "mp1", "mpeg",
    "opus",
    "vorbis",
    "ac3", "ac-3",
    "eac3", "ec-3", "e-ac-3",
    "wma", "wmav1", "wmav2", "wmapro",
    "amr", "amr_nb", "amr_wb",
    "cook",
    "atrac", "atrac3", "atrac3p", "atrac9",
    "mpc", "musepack",
    "qdm2", "sipro",
)

/**
 * 无损编码：阻带该按它自己声明的位深算。
 *
 * DSD 那几项（`dsd_msbf` / `dsd_lsbf` / `*_planar`）也要在这里 —— 但它们的"位深"
 * 是 1，落在 16..32 之外，所以照样返回 `0`（= 交给处理器按解码出口推断）。
 * 这是对的：DSD 解码出口是 float，抽取那一级**需要**最深阻带。
 */
private val LOSSLESS_CODECS: Set<String> = setOf(
    "flac",
    "alac",
    "ape",
    "wavpack", "wv",
    "tta",
    "tak",
    "wav", "pcm", "pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_f32le", "pcm_f64le",
    "aiff", "aif",
    "mlp", "truehd",
    "dsd", "dsd_msbf", "dsd_lsbf", "dsd_msbf_planar", "dsd_lsbf_planar", "dsf", "dff",
)
