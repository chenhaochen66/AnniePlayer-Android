package com.annieplayer.annie_music.kt.core.audio

/**
 * 「这一首有没有被 DSP 改过」—— **引擎内部的客观事实，不是用户选项**。
 *
 * 以前它是用户选的「模式」（透明 / 调音），所以还带一个 `label` 给界面用。
 * 那个选择已经被「通路 + 开关」取代（见 `OutputMatrix.kt`），`label` 随之删掉 ——
 * 界面上不该再出现这两个词，只剩 `OutputPlan` / 渲染器 / 音量策略内部消费。
 */
enum class AudioOutputMode(val key: String) {
    transparent("transparent"),
    tuned("tuned");

    companion object {
        fun fromKey(key: String?): AudioOutputMode =
            entries.firstOrNull { it.key == key } ?: transparent
    }
}

/** 输出通路（决定徽章颜色与能否宣称 bit-perfect） */
enum class AudioRoute { usb, wired, bluetooth, speaker, other }

/**
 * 输出图标的色调。**这是输出胶囊里那个图标的颜色**，不是文字颜色。
 *
 * 命名按「数据是怎么过去的」定，不按好坏定 —— 四种颜色对应四种事实：
 *
 * | 值 | 颜色 | 事实 |
 * |---|---|---|
 * | [bitPerfect] | 金 | 原样送出（含 raw DSD 逐字节直出） |
 * | [ownSrc] | 红 | 我方转过采样率 |
 * | [systemSrc] | 灰 | 我方没转，也不是原样直出（系统转 / 谁都没转） |
 * | [tuned] | 蓝 | 调音启用（我方改了数据） |
 */
enum class AudioTone { bitPerfect, ownSrc, systemSrc, tuned }

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
    /**
     * 系统 mixer 的位深（16 / 24 / 32）；0 = 拿不到。
     *
     * 来源是 `AudioCapabilities.supportedEncodings` 里设备**真的**支持的最高精度 PCM 编码 ——
     * Android 没有 `PROPERTY_OUTPUT_BIT_DEPTH` 这样的属性，只能这么问。
     */
    val nativeDepth: Int = 0,
    /** mixer 位深是否为 float（32 bit float 与 32 bit int 要分开显示） */
    val nativeFloat: Boolean = false,
    /** 当前输出设备声明的最高采样率（蓝牙 96k / USB 384k 等）；0 = 未知 */
    val deviceMaxRate: Int = 0,
    /** 当前输出设备声明的采样率列表（用于限制重采样目标下拉） */
    val deviceSampleRates: List<Int> = emptyList(),
    /**
     * 系统**实际**会用的输出率（用于「链路实况」等只读展示）。
     *
     * - 蓝牙 → codec 协商率（实测与 a2dp output thread 一致）
     * - 非蓝牙 → primary mixer 率
     * - bit-perfect 请求成功 → 当时请求的那个率
     *
     * 0 = 未知（不标注，不猜）。
     */
    val effectiveRate: Int = 0,
    /**
     * **设备这一级**的输出位深（32 表示 float）；0 = 未知。
     *
     * - 蓝牙 → codec 协商位深（LHDC 24 / LDAC 32 / SBC 16）。**不是** mixer 值：
     *   `AudioCapabilities` 只认识本机 mixer 的 PCM 编码，对耳机侧 codec 一无所知，
     *   拿它当蓝牙位深会显示成 32 Bit 而耳机收到 24 Bit。
     * - 非蓝牙 → **实测**探针值（`AudioDirectProbe.sinkDepth`，真建一次 float/24/16 的
     *   AudioTrack）。为什么不用 `AudioCapabilities`：本机它只报 `PCM_16`，而实际
     *   交付轨就是 float —— 照它显示会把 32 Bit float 写成 16 Bit，凭空丢 8 位。
     *
     * 界面的「链路实况」（到耳朵是多少位）读它。判定在 [resolveDeviceDepth]（纯函数）。
     * 它**不再参与 `OutputPlan`**：位深已不是用户选项，我方只决定"要不要接管"，
     * 接管就交 float 全精度、量化留给 sink（见 `PLAN_FLOAT_DEPTH`）。
     */
    val sinkDepth: Int = 0,
    /**
     * [sinkDepth] 那一次实测里，32 是不是 float。
     *
     * **必须与 [sinkDepth] 同源**。以前"深度取实测、float 取 `AudioCapabilities`"，
     * 结果本机显示成 `24→32 Bit`（整数）—— 而实际交付的是 float 轨。同一个事实
     * 从两个来源取，必然在某个机型上互相打架。
     */
    val sinkFloat: Boolean = false,
    /** 自动策略选出的重采样目标率；0 = 该输出类型不做重采样（蓝牙旁路） */
    val autoTargetRate: Int = 0,
    /** 蓝牙链路：实际 codec 采样率（读不到时退回系统输出线程率） */
    val bluetoothRate: Int = 0,
    /** 蓝牙链路：实际 codec 位深（读不到时退回源位深） */
    val bluetoothDepth: Int = 0,
    /** 蓝牙链路 codec 名（SBC / AAC / LDAC / LHDC…）；空 = 未知 */
    val bluetoothCodec: String = "",
    /**
     * 蓝牙 codec 码率（bit/s）；0 = 读不到（界面不显示，不猜）。
     *
     * 只有把 bps 写进 `codecSpecific1` 的实现才读得到：LDAC 一定行（AOSP 约定），
     * 部分厂商 codec 也行。**本机 LHDC V5 读不到** —— 它那个字段存的是标志位 32777，
     * 真正的传输码率（实测 400 kbps / ABR 动态）在厂商的 `A2dpCodecStatus` 私有状态里，
     * 只出现在 `dumpsys bluetooth_manager`，第三方 App 无法访问。
     */
    val bluetoothBitrate: Int = 0,
    val sourceRate: Int = 0,
    val sourceDepth: Int = 0,
    /**
     * 全链路（ffmpeg 直解）**实际输出**的采样率（native 侧回报）；0 = 不是全链路 / 还没拿到。
     *
     * 非 0 时它就是最终输出率（swresample 一步对齐到 `OutputPlan.sampleRate`），
     * 此时 [sourceRate] 只是容器的源规格，两者可以完全不同（DSD 2.8224MHz → 48/96/192k）。
     */
    val pipelineRate: Int = 0,
    /** 全链路实际输出的位深（16 / 24）；0 = 未知 */
    val pipelineDepth: Int = 0,
    /**
     * 这一首是 DSD 时抽成的 PCM 目标率；**0 = 不是 DSD**。
     *
     * 界面靠它区分两种"我方转换"：
     * - 普通 PCM 的重采样 = 本可避免的那一次 → 「我方 SRC 输出」
     * - DSD 的抽取 = 1-bit 转 PCM 的必经环节 → 「DSD 抽取」
     *
     * 两者在 `plan.tier` 上都是 `ownSrc`，但含义完全不同，混用会让人以为
     * 普通通路的 SRC 判据漏进了 DSD。
     */
    val dsdTargetRate: Int = 0,
    /**
     * 这一首 DSD **实际**用的传输方式。`pcm` 既表示"用户选的就是 PCM"，也表示
     * "DoP / Native 降级了" —— 两种情况靠 [dsdDowngradeReason] 区分。
     */
    val dsdTransport: com.annieplayer.annie_music.kt.core.audio.dsd.DsdTransport =
        com.annieplayer.annie_music.kt.core.audio.dsd.DsdTransport.pcm,
    /** DoP / Native 没走成的原因；null = 意图成立，或本来就不是 DSD */
    val dsdDowngradeReason:
        com.annieplayer.annie_music.kt.core.audio.dsd.DsdDowngradeReason? = null,
    /** DAC 是否暴露了 raw DSD 通道 —— Native 的硬前提，界面据此决定那一项能不能点 */
    val dsdRawAlt: Boolean = false,
    /** 目标率没按用户请求生效的原因（`platformRejected` 时 [deliveredRate] 走自动率） */
    val rateFallback: RateFallback = RateFallback.none,
    /**
     * 系统通路**实测**能建出的最高采样率；0 = 还没探到。
     *
     * 与 [deviceMaxRate]（设备**声明**的最高率）分开报，因为两者经常不一致：
     * 外接 DAC 常在 profile 里声明 384k/768k，而 `AudioTrack` 受 `AudioFormat`
     * 的 192k 上限约束，实际建不出来。把两个数字并列摆出来，用户一眼就能看出
     * "它说自己能，实际不行" —— 这比只报一个数好，也符合"摆事实不做判断"。
     *
     * 填充方式：`SystemSinkProbe.knownHighest(...)`，**只读缓存不建轨** ——
     * 状态上报每首歌都会走一次，不能让它变成建轨探测。
     */
    val probedMaxRate: Int = 0,
    /** 系统接受了 bit-perfect mixer 请求（仅 API 34+ 且外接设备时可能为 true） */
    val bitPerfect: Boolean = false,
    val eqOn: Boolean = false,
    val loudnessOn: Boolean = false,
    /**
     * 当前曲目的 ReplayGain 曲目增益（dB）；**0 = 没有标签 / 拿不到**。
     *
     * 用途：响度归一化开关打开但这一首没有标签时，界面必须说清"不调整"，
     * 否则用户只会觉得开关坏了（实测就是这么被误判的）。
     */
    val replayGainDb: Float = 0f,
    /** 轴 2：这一首走的是哪条解码路径（`OutputPlan.decode`） */
    val decode: DecodeKind = DecodeKind.ffmpegSoft,
    /** 轴 3：PCM 走哪条通路（`OutputPlan.path`）；蓝牙会被强制成 systemMix */
    val outputPath: OutputPath = OutputPath.systemMix,
    /** 这一首**实际**开了哪条通路；与 [outputPath]（意图）不同即降级 */
    val effectivePath: OutputPath = OutputPath.systemMix,
    /** 意图未被满足时的说明；null = 意图已达成，或本来就走系统通路 */
    val downgrade: DowngradeNotice? = null,
    /**
     * 独占端点上**真正**跑的位深（USB = alt setting 的 subslot，AAudio = HAL 回读格式）。
     *
     * 与我方交付的容器不同：调音时交 float（32），端点写的是 `S24_3LE`（24）。
     * 播放页的位深只有一个槽位、约定写终点，所以它读这里。0 = 未知 / 非独占通路。
     */
    val streamDepth: Int = 0,
    /** 我方这一首**实际**做了采样率转换（不是"能不能"，是"有没有"） */
    val didResample: Boolean = false,
    /** 我方这一首**实际**改了位深 */
    val didConvertDepth: Boolean = false,
    /** 我方交付给 AudioSink 的采样率（0 = 未知） */
    val deliveredRate: Int = 0,
    /** 我方交付给 AudioSink 的位深（0 = 未知） */
    val deliveredDepth: Int = 0,
    /** 向系统请求的 mixer 率（0 = 没请求） */
    val mixerTarget: Int = 0,
    /**
     * 落在哪一级 —— **与证据合成后的结论**（`null` = 不写前缀）。
     *
     * 与 `OutputPlan.tier`（意图）的区别就在这里定：
     * - `bitPerfect` 意图要等系统真的接受 mixer 请求才算数；
     * - `ownSrc` 意图要等 native 侧的真实输出率才能确认（全链路）。
     */
    val tier: OutputTier? = null,
) {
    val isEmpty: Boolean get() = sourceRate <= 0 && nativeRate <= 0

    /**
     * 我方没转、系统却在转 —— 「系统重采样」的唯一判据。
     *
     * 证据就是规格里的那个箭头：我方交付的率（[AudioText] 的 `inRate`）与设备实际用的率
     * 不同，且这一次差异不是我方造成的。三条例外：
     * - Bit Perfect：双方同一个率，不会有箭头；
     * - 我方转过：`didResample` 为真，那次差异是我方造成的，已经由「我方重采样」表达；
     * - 独占通路：只写端点，中间没有 mixer 可转。
     */
    val systemResampling: Boolean
        get() {
            if (tier == OutputTier.bitPerfect) return false
            if (didResample) return false
            if (effectivePath == OutputPath.usbDirect ||
                effectivePath == OutputPath.aaudioExclusive
            ) {
                return false
            }
            val spec = outputSpecOf(this)
            return spec.inRate > 0 && spec.outRate > 0 && spec.inRate != spec.outRate
        }

    /** 输出图标颜色。顺序即优先级，与文案分段一致（调音永远第一）。 */
    val tone: AudioTone
        get() = when {
            mode == AudioOutputMode.tuned -> AudioTone.tuned
            // raw DSD 逐字节直出：没有任何转换，本质与 Bit Perfect 同类
            dsdTransport.isRaw -> AudioTone.bitPerfect
            tier == OutputTier.bitPerfect -> AudioTone.bitPerfect
            tier == OutputTier.ownSrc || tier == OutputTier.ownSrcFallback -> AudioTone.ownSrc
            // 我方没转、也不是原样直出：系统在转，或者谁都没转（两件事同一种灰）
            else -> AudioTone.systemSrc
        }

    /**
     * 输出**身份**：这一路物理上从哪儿出去（`LHDC 1000 kbps` / `扬声器` / `USB` / `有线`）。
     *
     * 码率并进身份、不单独占一段：只有 LDAC / LHDC 读得到码率，它描述的本来就是 codec
     * 本身（`LHDC 1000 kbps` 是一个整体名词）。单独一段会让「调音 · LHDC」这种组合被
     * 码率从中间剪断。
     */
    val routeBadge: String
        get() {
            val base = when (route) {
                AudioRoute.bluetooth ->
                    bluetoothCodec.takeIf { it.isNotEmpty() && !it.startsWith("未知") } ?: "蓝牙"
                AudioRoute.usb -> "USB"
                AudioRoute.wired -> "有线"
                AudioRoute.speaker -> "扬声器"
                else -> "系统混音"
            }
            val kbps = if (route == AudioRoute.bluetooth && bluetoothBitrate > 0) {
                " ${bluetoothBitrate / 1000} kbps"
            } else {
                ""
            }
            return base + kbps
        }

    /**
     * 这一首是不是 DSD，以及怎么送的；非 DSD 为 `null`。
     *
     * 播放页的第三段读它。`DoP` / `Native DSD` 靠 [dsdTransport] 判，`DSD 转 PCM`
     * 靠 [dsdTargetRate]（0 = 不是 DSD）—— 后者是唯一证据，因为 `pcm` 这个值
     * 同时表示"本来就不是 DSD"，两者只能靠它分开。
     */
    val dsdLabel: String?
        get() = when {
            dsdTransport.isRaw -> dsdTransport.label
            dsdTargetRate > 0 -> dsdTransport.label
            else -> null
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
