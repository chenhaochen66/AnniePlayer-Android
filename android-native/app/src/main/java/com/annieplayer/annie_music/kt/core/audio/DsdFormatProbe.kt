package com.annieplayer.annie_music.kt.core.audio

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/**
 * 试建**厂商扩展的 DSD 编码**轨。
 *
 * 为什么需要：部分 DAP（实测飞傲 JM21）的 HAL 在 `audio_policy` 里声明了
 * `AUDIO_FORMAT_DSD_16_BIT` / `AUDIO_FORMAT_DSD_32_BIT`，绑在
 * `AUDIO_OUTPUT_FLAG_DIRECT` 端口（`direct_pcm`）上，`maxOpenCount = 1`。
 * 那意味着系统 API 可能直接送 DSD，不必自己接管 USB 端点 ——
 * 但能不能用取决于两件事：Java 层放不放行这个编码值、HAL 认不认。
 *
 * ## 绕过 `AudioFormat.Builder`
 *
 * `Builder.setEncoding()` 走 `isEncodingValid()` 白名单，厂商扩展值直接抛
 * `IllegalArgumentException`。`AudioFormat` 的公开构造器不校验，从那里进。
 *
 * ## 读数怎么解释
 *
 * - **拒绝**：framework 层就挡了，这条路在本机不存在
 * - **可建**：Java/native 都放行；是否真的落到 `direct_pcm` 线程要看
 *   `dumpsys media.audio_flinger`（App 侧拿不到线程归属）
 * - 正在播 DSD 时 `direct_pcm` 的 `maxOpenCount = 1` 已被占用，建轨会失败 ——
 *   那不是"不支持"，所以探测要在空闲时做
 */
object DsdFormatProbe {

    /** 飞傲的取值；别家可能不同，所以只作候选而不是结论 */
    private const val ENCODING_DSD_16_BIT = 0x1a000001
    private const val ENCODING_DSD_32_BIT = 0x1a000002

    private val attempts: List<Triple<String, Int, Int>> = listOf(
        // 16 位容器 ≈ DoP 的封装：DSD64 需要 176.4k 的帧率
        Triple("DSD_16_BIT @ 176.4k", ENCODING_DSD_16_BIT, 176_400),
        // 32 位容器 ≈ raw DSD：32 个 1-bit 装一个 word，DSD64 只要 88.2k
        Triple("DSD_32_BIT @ 88.2k", ENCODING_DSD_32_BIT, 88_200),
        Triple("DSD_16_BIT @ 352.8k", ENCODING_DSD_16_BIT, 352_800),
        Triple("DSD_32_BIT @ 176.4k", ENCODING_DSD_32_BIT, 176_400),
        // 对照：PCM 高率能不能走同一条 direct 通道
        Triple("PCM_32 @ 352.8k", AudioFormat.ENCODING_PCM_32BIT, 352_800),
    )

    @Volatile
    private var cache: String? = null

    /** 「重新探测」要能重跑：端口占用状态会变 */
    fun invalidate() {
        cache = null
    }

    fun report(): String {
        cache?.let { return it }
        val lines = attempts.map { (label, encoding, rate) ->
            "$label · ${tryBuild(encoding, rate)}"
        }
        // 建轨结果与端口占用有关，所以只缓存一次渲染内的一致性，不做跨进程持久化
        val text = lines.joinToString("\n")
        cache = text
        return text
    }

    /**
     * 用 **deprecated 的 `AudioTrack` 构造器**建轨，它直接收 `int audioFormat`。
     *
     * 这是唯一能测到"native 认不认这个编码"的路径：
     * - `AudioFormat.Builder` 在 `setEncoding()` 就按 `isEncodingValid()` 拒了，测不到 native
     * - `AudioFormat` 的四参构造器在 compileSdk 与运行时都不可达（反射拿到
     *   `NoSuchMethodException`，连 PCM 都过不去）
     * - 这个构造器把 int 原样交给 `native_setup`，校验发生在 `audio_is_valid_format()`
     *
     * 所以这里失败就是**真正的失败**，不是被 Java 白名单挡住。
     */
    @Suppress("DEPRECATION")
    private fun tryBuild(encoding: Int, rate: Int): String = try {
        // 非法 encoding 时它返回 ERROR_BAD_VALUE(-2)，所以必须兜底
        val minBytes = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, encoding)
        // 0.2 秒的 8 字节帧；只为了拿到一个能建的缓冲，不打算写数据
        val bufferBytes = if (minBytes > 0) minBytes else rate / 5 * 8
        val track = AudioTrack(
            AudioManager.STREAM_MUSIC,
            rate,
            AudioFormat.CHANNEL_OUT_STEREO,
            encoding,
            bufferBytes,
            AudioTrack.MODE_STREAM,
        )
        try {
            val gotRate = track.sampleRate
            val frames = track.bufferSizeInFrames
            val mismatch = if (gotRate != rate) " · 回读 ${gotRate}Hz" else ""
            "可建 · $frames 帧$mismatch"
        } finally {
            runCatching { track.release() }
        }
    } catch (t: Throwable) {
        val why = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
        "被拒 · $why"
    }
}
