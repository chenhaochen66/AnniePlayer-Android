package com.annieplayer.annie_music.kt.core.audio.ffmpeg

import com.annieplayer.annie_music.kt.core.audio.dsd.DsdTransport

/**
 * 全链路**真实输出规格**的回报通道。
 *
 * 为什么需要它：全链路的采样率/位深由 native 侧决定（`avcodec` 报出来的源率 + 设置层给的
 * 目标率 + 位深选择），这些值只写进了交给 Media3 的 `Format`，播放页拿不到 ——
 * 于是「输出」胶囊只能拿曲库索引里的**源**数值凑，DSD 这种源规格和输出规格完全不同的
 * 文件就会显示成 `8 Bit / 352.8 kHz`（源）冒充输出。
 *
 * 现在 `FfmpegLocalExtractor.init()` 打开成功后把 [Spec] 挂在这里，
 * `MediaPlayerBackend` 按**当前曲目路径**取，取不到才退回设置层推算。
 *
 * 单条播放流，因此只保留最近一次 publish 的结果；并且带 path 校验，切歌瞬间不会张冠李戴。
 */
internal object FfmpegOutputStream {

    /** native 侧实际吐给 Media3 的 PCM 规格 */
    data class Spec(
        /** 输出采样率（Hz） */
        val rate: Int,
        /** 输出位深（16 / 24） */
        val bits: Int,
        /** 声道数（恒 2，下混后的结果） */
        val channels: Int,
        /**
         * DSD 专用的最终目标率（Hz）；0 = 这一首不是 DSD。
         *
         * 有值时 [rate] 是 **native 的中间格式**（DSD64 = 352800），不是最终输出率 ——
         * native 只做 1-bit → PCM 网格，2:1 抽取由下游 r8brain 完成（见 `NativeFfmpegDemux.open`
         * 的 `keepSourceRate`）。所以判断"我方要不要重采样"必须拿这个字段，不能拿 [rate]。
         */
        val dsdTargetRate: Int = 0,
        /**
         * **这条源是按哪种传输方式建的**（PCM / DoP / Native）。
         *
         * 这是"源"对传输方式的唯一声明，必须由**建源的那一方**写下来。
         * 以前 sink 在自己的 `configure()` 里回读全局 `_dsdTransport`，而 extractor 是在
         * `_setSource()` 里按当时的同一个全局量建源 —— 两个读者、两个时刻，中间没有同步点。
         * 一旦翻面（反复切模式 / 失败重建），就会出现"源出 DoP 帧、sink 按 PCM 配"：
         * 帧被当普通 PCM 送去，**没有声音**；反过来是满幅噪声。
         */
        val transport: DsdTransport = DsdTransport.pcm,
    )

    private data class Entry(val path: String, val spec: Spec)

    @Volatile
    private var current: Entry? = null

    /**
     * 新规格到达时的回调（**在播放线程调用**）。
     *
     * 起播那一刻 extractor 还没 `init`，第一次 `_applyOutputPolicy` 拿不到 pipeline；
     * 有了这个回调，规格到了就能重发一次 audioStatus，播放页「输出」胶囊不会一直停在推算值。
     */
    @Volatile
    var onPublished: ((String) -> Unit)? = null

    fun publish(
        path: String,
        rate: Int,
        bits: Int,
        channels: Int,
        dsdTargetRate: Int = 0,
        transport: DsdTransport = DsdTransport.pcm,
    ) {
        if (path.isEmpty() || rate <= 0 || bits <= 0) return
        current = Entry(path, Spec(rate, bits, channels.coerceAtLeast(1), dsdTargetRate, transport))
        onPublished?.invoke(path)
    }

    /**
     * 这条源声明的传输方式；`null` = 不是在**我方全链路**上建的源（流媒体）。
     *
     * sink 用它代替回读全局 `_dsdTransport`：extractor 的 `init()` 必然发生在渲染器
     * `configure()` 之前（否则连 Format 都没有），所以走到 configure 时这里已经有值 ——
     * 那个"两读两写"的时间窗就此消失。
     */
    fun transportOf(path: String): DsdTransport? = specFor(path)?.transport

    /** 该路径当前的真实输出规格；路径不匹配（已切歌）或还没 open 过则返回 null */
    fun specFor(path: String): Spec? {
        if (path.isEmpty()) return null
        val e = current ?: return null
        return if (e.path == path) e.spec else null
    }

    /** 只清自己那条，避免把刚开播的新曲目规格误删 */
    fun clearIf(path: String) {
        val e = current ?: return
        if (e.path == path) current = null
    }
}
