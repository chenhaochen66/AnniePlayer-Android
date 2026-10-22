package com.annieplayer.annie_music.kt.core.audio

/**
 * 输出模式（方案 1.2.3）。
 *
 * 两者**互斥**：透明模式要求 DSP 全旁路，否则「bit-perfect」就是假宣传；
 * 调音模式主动放弃 1:1，换取 EQ / 响度 / 限幅。
 */
enum class AudioOutputMode(val key: String, val label: String) {
    transparent("transparent", "透明"),
    tuned("tuned", "调音");

    companion object {
        fun fromKey(key: String?): AudioOutputMode =
            entries.firstOrNull { it.key == key } ?: transparent
    }
}

/** 输出通路（决定徽章颜色与能否宣称 bit-perfect） */
enum class AudioRoute { usb, wired, bluetooth, speaker, other }

/** 徽章色调：绿=真直通 / 黄=接近 / 灰=做不到 / 蓝=调音 */
enum class AudioTone { good, warn, muted, tuned }

/**
 * 当前输出的**可验证状态**（播放页徽章的数据源）。
 *
 * 原则（方案 1.2.3）：做不到就如实显示 —— 宁可显示「系统混音」，
 * 也不要给用户一个假的 bit-perfect 标签。
 */
data class AudioOutputStatus(
    val mode: AudioOutputMode = AudioOutputMode.transparent,
    val route: AudioRoute = AudioRoute.other,
    val deviceName: String = "",
    /** 设备原生输出采样率（`PROPERTY_OUTPUT_SAMPLE_RATE`，系统 mixer 的率，通常固定 48k） */
    val nativeRate: Int = 0,
    /** 当前输出设备声明的最高采样率（蓝牙 96k / USB 384k 等）；0 = 未知 */
    val deviceMaxRate: Int = 0,
    /** 当前输出设备声明的采样率列表（用于限制重采样目标下拉） */
    val deviceSampleRates: List<Int> = emptyList(),
    /** 自动策略选出的重采样目标率；0 = 该输出类型不做重采样（蓝牙旁路） */
    val autoTargetRate: Int = 0,
    /** 蓝牙链路：实际 codec 采样率（读不到时退回系统输出线程率） */
    val bluetoothRate: Int = 0,
    /** 蓝牙链路：实际位深（读不到时退回源位深） */
    val bluetoothDepth: Int = 0,
    /** 显式选择的输出位深（16 / 24 / 32）；0 = 跟随源 */
    val outputDepth: Int = 0,
    val sourceRate: Int = 0,
    val sourceDepth: Int = 0,
    /** 我方重采样后的输出采样率；0 = 未重采样 */
    val resampledTo: Int = 0,
    /** 系统接受了 bit-perfect mixer 请求（仅 API 34+ 且外接设备时可能为 true） */
    val bitPerfect: Boolean = false,
    val eqOn: Boolean = false,
    val loudnessOn: Boolean = false,
) {
    val isEmpty: Boolean get() = sourceRate <= 0 && nativeRate <= 0

    val tone: AudioTone
        get() = when {
            mode == AudioOutputMode.tuned -> AudioTone.tuned
            bitPerfect -> AudioTone.good
            route == AudioRoute.usb -> AudioTone.warn
            resampledTo > 0 && resampledTo == nativeRate && sourceRate != nativeRate -> AudioTone.warn
            else -> AudioTone.muted
        }

    /** 播放页前缀：简短说明当前处于哪种模式（胶囊一行放得下） */
    val badge: String
        get() = when {
            mode == AudioOutputMode.tuned -> buildString {
                append("调音")
                val flags = listOfNotNull(
                    "EQ".takeIf { eqOn },
                    "响度".takeIf { loudnessOn },
                )
                if (flags.isNotEmpty()) append(" · ").append(flags.joinToString("+"))
            }
            bitPerfect -> "Bit-Perfect"
            resampledTo > 0 && resampledTo != sourceRate -> "重采样"
            route == AudioRoute.usb -> "USB"
            route == AudioRoute.bluetooth -> "蓝牙"
            route == AudioRoute.wired -> "有线"
            route == AudioRoute.speaker -> "扬声器"
            else -> "系统混音"
        }
}

/** 解码路径（阶段 1 只做决策，真正切换引擎在阶段 2） */
enum class DecodePath {
    /** Media3 + MediaCodec（含 libFLAC extractor） */
    media3,

    /** 需要 `FfmpegPlayerBackend`（ExoPlayer 连容器都不认） */
    ffmpeg,

    /** 先转成临时 flac 再交给 Media3（永不失败的兜底） */
    transcode,
}

/**
 * 格式路由骨架（方案附录 B）。
 *
 * 只按容器的**实际能力**判断，不靠扩展名猜：调用方传入 ffprobe 的 `format_name`
 * 与音频编码名，返回该走哪条解码链。阶段 1 用它记录/上报，阶段 2 据此切引擎。
 */
object AudioFormatRouter {

    /** ffprobe 容器名可能是逗号分隔的候选（如 `mov,mp4,m4a,3gp,3g2,mj2`） */
    fun route(container: String, codec: String): DecodePath {
        val c = container.lowercase().trim()
        val k = codec.lowercase().trim()
        // DSD 与「ExoPlayer 没有 extractor」的容器：即使 ffmpeg 解得了，Media3 也进不去
        if (c.contains("dsf") || c.contains("dff") || c.contains("iff")) return DecodePath.transcode
        if (c.contains("ape") || c.contains("wavpack") || c.contains("tta") || c.contains("tak")) {
            return DecodePath.ffmpeg
        }
        // 容器能进 Media3，但解码器不一定有（典型：ALAC 在无厂商解码器的机型）
        if (k == "alac") return DecodePath.transcode
        if (c.contains("iso") || c.contains("sacd")) return DecodePath.transcode
        return DecodePath.media3
    }
}
