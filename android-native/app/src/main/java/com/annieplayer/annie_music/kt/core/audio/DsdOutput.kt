package com.annieplayer.annie_music.kt.core.audio

/**
 * DSD 输出模式的**唯一判定来源**（纯函数，可 JVM 单测）。
 *
 * ## 为什么 DSD 要单独一条轴
 *
 * DSD 是 1 bit 的脉冲密度调制，进 PCM 世界时**采样率不能随便挑**：
 *
 * - DSD 基频 = 44.1 kHz × 64（DSD64 = 2.8224 MHz，DSD128 = 5.6448 MHz…）
 * - 所以 44.1 / 88.2 / 176.4 / 352.8 都是它的 **2 的幂分频** → 纯整数抽取，零重采样
 * - 而 48 / 96 / 192 与 DSD 是 **147 : 160** 的比例 → 必然插入一次非整数比重采样
 *
 * 以前 DSD 的目标率跟着「重采样目标」走，调音模式下就会拿到本机的 48 kHz
 * （比值为 58.8），等于每次听 DSD 都白插一次带限重采样，还顺手丢掉了 176.4 kHz 的信息。
 * 现在 DSD 走自己这条轴：档位只给 44.1 家族，且**优先于**常规重采样目标。
 *
 * ## DoP / Native 为什么现在是灰的
 *
 * - **DoP**：把 DSD 裸流封装进 24 bit PCM 帧（帧内 0x05 / 0xFA 标记）。要求传输**逐字节不变**
 *   —— 只要过一次 AudioFlinger 的 mixer，STREAM_MUSIC 音量就是一次 float 乘法，标记必被改写，
 *   DAC 收到的是乱码（爆音或静音）。所以 DoP 的前提是**独占通路**（AAudio MMAP / USB 直取）。
 * - **Native**：裸 DSD 交给支持 Native DSD 的 DAC。Android 没有这条路：UAC2 的音频端点
 *   是 isochronous，而 `UsbDeviceConnection` 只支持 bulk / interrupt，必须自带 libusb 驱动
 *   （海贝就是这么做的）并配特定型号的 DAC。
 *
 * 两条都还没做，所以灰显并写明原因 —— **不是"设置没做"，是"通路没做"**。
 */
enum class DsdOutputMode(val key: String, val label: String) {
    /** 解成 PCM（当前唯一可用的路径） */
    pcm("pcm", "PCM"),

    /** DSD over PCM：塞进 24 bit 帧，需要独占通路才不被改写 */
    dop("dop", "DoP"),

    /** 裸 DSD 给 DAC：需要 USB 独占 + 支持 Native DSD 的设备 */
    native("native", "Native"),
    ;

    companion object {
        fun fromKey(key: String?): DsdOutputMode = entries.firstOrNull { it.key == key } ?: pcm
    }
}

/**
 * DSD 档位：按 **1 bit 采样率**区分。
 *
 * 与 `LocalHeaderProbe` 里命名档位用的是同一套数（索引存的就是真实 1 bit 率，
 * 不存 ffprobe 报的解码器中间格式）。
 */
enum class DsdLevel(val bitRate: Int, val label: String) {
    dsd64(2_822_400, "DSD64"),
    dsd128(5_644_800, "DSD128"),
    dsd256(11_289_600, "DSD256"),
    dsd512(22_579_200, "DSD512"),
    ;

    /**
     * `dsddec` 解出来的 PCM 网格率。
     *
     * ffmpeg 的 dsddec 一个输入字节出一个 float（`frame->nb_samples = avpkt->size / ch`），
     * 所以**恒等于 `bitRate / 8`**，不是所有档位都落到 352.8k：
     * DSD64 → 352.8k、DSD128 → 705.6k、DSD256 → 1411.2k。
     *
     * 这个值同时是"输出到这么高才有意义"的上界：超过它就是升采样。
     */
    val pcmGridRate: Int get() = bitRate / 8

    companion object {
        fun of(sourceRate: Int): DsdLevel? = entries.firstOrNull { it.bitRate == sourceRate }
    }
}

/**
 * DSD → PCM 允许的目标率：**只有 44.1 家族**（44.1 / 88.2 / 176.4 / 352.8 / 705.6）。
 *
 * 见文件头：这几个都是 DSD 基频的 2 的幂分频。48 家族一个都不给 —— 给了就等于
 * 主动插入一次非整数比重采样，那正是这条轴要消灭的东西。
 *
 * ## 为什么列到 1411.2k 而不是只到 352.8k
 *
 * 每一档都是 44.1k 的 2 的幂倍数，也都是某个 DSD 档位的**解网格本身**
 * （`DsdLevel.pcmGridRate`）：352.8k = DSD64、705.6k = DSD128、1411.2k = DSD256。
 * 选到网格率就是"抽完一个采样都不动"，选低一档才是再来一次 2:1。
 *
 * 它在**普通手机上永远灰着**（`AudioFormat` 的 192k 契约），列出来不是为了"能选"，
 * 而是为了**在有能力的设备上自动解锁** —— 判据是实测：
 * `SystemSinkProbe.highestUsable(DSD_PCM_RATES, …)` 从高往低真建轨，
 * 第一档建得出来的就是上限，然后 `dsdMaxPcmRate()` 把超出的档灰掉。
 *
 * 所以这一行的存在**不会误伤**任何设备：建不出来就是灰的，和没列一样。
 *
 * 注意 768k（48 家族）永远不列 —— 那不是 DSD 的整数分频，选它等于主动插入一次
 * 非整数比重采样。
 */
val DSD_PCM_RATES: List<Int> =
    listOf(44_100, 88_200, 176_400, 352_800, 705_600, 1_411_200)

/** 默认目标率：176.4 kHz（DSD64 时是 352.8k 的 ÷2，整数抽取且保留了绝大多数带宽） */
const val DSD_DEFAULT_PCM_RATE: Int = 176_400

/**
 * 哨兵值：**不抽到固定档位，直接对齐系统率**。
 *
 * 为什么是 `0` 而不是新增一个枚举：`MoreSettings.dsdPcmRates` 是 `Map<DsdLevel, Int>`，
 * 用 0 表示「这一档跟随系统」改动最小。而且 0 在别处（`OutputPlan.sampleRate`）本来就
 * 表示"我方不指定率"，语义不冲突 —— 都是"把决定权交出去"。
 *
 * ## 它解决的场景
 *
 * 只抽到固定档位时，普通手机（主率 48k）的链路是：
 *
 * ```
 * dsddec 352.8k ──r8brain──► 176.4k ──AudioFlinger──► 48k
 *                                ↑ 我方高质量           ↑ 系统，非整数比，简单插值
 * ```
 *
 * 而普通 44.1k 曲目只有一次（我方 44.1→48），AudioFlinger 不再动它 ——
 * **DSD 反而比普通曲目多一次劣质转换**。选「跟随系统」就是让 r8brain 一次转到位。
 *
 * ## 为什么是用户的档位选择，不是自动判断
 *
 * 曾经写成"设备声明里没有这个档位就自动降"，**那是错的**：DAP 的 `devicePcmRates`
 * 常常只声明 48000（port profile 写不全），而它实际能跟随任意率 —— 自动判断会把
 * **质量最好的设备降到 48k**。这与 99.7 里 `sourceRateIsSafe` 犯的错是同一个机制：
 * 拿声明代理能力，必然误伤一部分设备，而用户无法绕过。
 *
 * 所以它做成**每个 DSD 档位独立的一项选择**：DSD64 可以保持 176.4k，DSD512 可以跟随系统，
 * 互不影响。
 */
const val DSD_RATE_FOLLOW_SYSTEM: Int = 0

/**
 * 全链路 DSD 解码出来的 PCM 位深：固定 24 bit。
 *
 * 不跟随「输出位深」设置：DSD 的 1 bit 没有"位深"可言，24 bit 是 native 侧 swresample
 * 出口能给到的最高精度（再往上只能 float，而 float 在本项目里是 mixer 侧的事）。
 */
const val DSD_PCM_DEPTH: Int = 24

/**
 * DSD→PCM 输出低通的**标称**截止：**按素材档位取**，不做成用户档位。
 *
 * ## 为什么跟素材档位走，而不是跟设备能力走
 *
 * 噪声整形推上去的噪声，归一化频率是 `f / (bitRate/2)` —— 所以**同一个绝对频率上，
 * DSD 率翻倍，噪声就降一整个量级**（5 阶 ≈ 每倍频程 30 dB）：
 *
 * | 素材 | 40 kHz 处噪声 | 噪声升到 −60 dBFS 的频率 |
 * |---|---|---|
 * | DSD64 | ≈ −90 dB | ≈ 80 kHz |
 * | DSD128 | ≈ −120 dB | ≈ 160 kHz |
 * | DSD256 | ≈ −150 dB | ≈ 320 kHz |
 * | DSD512 | ≈ −180 dB | ≈ 640 kHz |
 *
 * 也就是说：**40 kHz 对 DSD64 是"砍噪声"，对 DSD128 以上是"砍掉一段本来干净、
 * 而且可能有内容的带"**（真 DSD 直录在 40–60 kHz 有厅堂与泛音）。
 * 固定一个值套所有档位是偷懒，所以这里按档位缩放。
 *
 * ## 为什么不是按"设备能不能跑 352.8k / 能不能绕系统 SRC"决定
 *
 * 那两件事决定的是「**谁**做最后一次率转换」，与「信号里还剩多少内容」正交。
 * 一个 bit-perfect 的 352.8k 流，如果信号本身限带 40 kHz，它和 176.4k 那份是同一个信号，
 * 只是样本多一倍。所以设备能力只影响**夹取上限**（`0.90 × Nyquist`），不影响标称值。
 *
 * ## 这个数字决定什么
 *
 * 只决定"平坦区到哪"；真正的过渡带宽由抽头数定（native 侧 127 抽头 / β=8，实测）：
 *
 * | 输出率 | 截止参数 | −3 dB | −80 dB | 20 kHz 偏差 |
 * |---|---|---|---|---|
 * | 88.2k | 39 690 | 39 342 Hz | 41 472 Hz | −0.00016 dB |
 * | 176.4k | 40 000 | 39 305 Hz | 43 558 Hz | −0.00013 dB |
 * | 352.8k | 40 000 | 38 609 Hz | 47 123 Hz | −0.00001 dB |
 *
 * 截止更高时过渡带同比例外移（抽头数固定 ⇒ 过渡带宽 ∝ 输出率）。
 */
fun dsdPcmLowpassNominal(level: DsdLevel?): Int = when (level) {
    DsdLevel.dsd64 -> 40_000
    DsdLevel.dsd128 -> 60_000
    DsdLevel.dsd256 -> 80_000
    DsdLevel.dsd512 -> 100_000
    // 档位不明（理论上走不到：DSD 的解码网格 = bitRate/8，总能反推）时取最保守那档
    null -> 40_000
}

/**
 * 低于这个截止就干脆不做低通（交给 r8brain 的抗混叠）：
 * 再低就不是"削超声噪声"，而是开始咬 20 kHz 以内的内容了。
 */
const val DSD_PCM_LOWPASS_MIN_HZ: Int = 35_000

/** 某个 DSD 模式此刻能不能选；不可用时 [reason] 是给用户看的一句话 */
data class DsdModeAvailability(val enabled: Boolean, val reason: String)

/**
 * 模式可用性判定。
 *
 * ## DoP 的前置条件不是「USB 独占」，而是「逐字节保真的 PCM 通路」
 *
 * DoP 把 DSD 的 1-bit 位流按 8 bit 一组塞进 24 Bit PCM 帧，靠帧头 `0x05 / 0xFA`
 * 让 DAC 认出来。所以它要求的是**链路上一个字节都不许被改** —— 任何 SRC、任何音量缩放、
 * 任何格式转换都会把标记敲掉。
 *
 * 「不许被改」这条要求可以被多种通路满足，USB 独占只是其中一种：
 *
 * - **AAudio EXCLUSIVE 流**：只要 HAL 真给 EXCLUSIVE、且**回读的率/格式与请求一致**，
 *   数据就原样到 HAL。`AaudioSink` 已经在做这个校验（`realRate != sampleRate` /
 *   `realFormat` 对不上都判失败），所以这条路的判据是"回读保真"，不是"是不是 USB"。
 * - **系统 offload / direct 轨**：`AUDIO_OUTPUT_FLAG_DIRECT` 理论上也行，
 *   但 mixer 侧仍可能插手，可信度不如上面两条。
 * - **USB 独占（UAC2 直连）**：最硬的保真，但**尚未实现**。
 *
 * 所以这里的 `exclusiveReady` 语义是「有一条能保证逐字节保真的独占流」，
 * **不是**「USB 独占可用」。将来 USB 直连做出来，它只是又多了一个满足者。
 *
 * ## Native DSD 的前置条件
 *
 * 不是「平台有没有 DSD 格式」的问题 —— 那条路早就清楚了：`AudioFormat` / `AAudio` 的
 * encoding 枚举里没有 DSD，`AudioTrack` 承载不了 1-bit 位流，所以**系统通路永远不行**。
 * 唯一可行的是 USB 直连自己发 iso 传输（`UsbAudioDirect` / `UacEngine`），
 * 由它去选设备的 raw alt。
 *
 * 因此这一项的判据是两条**都成立**：通路逐字节保真 + DAC 真的暴露了 raw alt。
 * 后者读不出来时按"不可用"处理 —— 把 PCM 灌进 raw 通道设备不报错，只当 1-bit 解成满幅噪声。
 *
 * @param exclusiveReady 是否有一条能保证逐字节保真的独占流（**不限于 USB**）
 * @param usbDacReady DAC 是否暴露了 raw DSD 通道（`UsbDacCaps.hasRawAlt`）
 */
fun dsdModeAvailability(
    mode: DsdOutputMode,
    exclusiveReady: Boolean = false,
    usbDacReady: Boolean = false,
): DsdModeAvailability = when (mode) {
    DsdOutputMode.pcm -> DsdModeAvailability(true, "")
    DsdOutputMode.dop -> if (exclusiveReady) {
        DsdModeAvailability(true, "")
    } else {
        DsdModeAvailability(
            false,
            "需要不受 mixer 改动的独占流：系统混音会缩放音量、改写 DoP 的帧标记",
        )
    }
    DsdOutputMode.native -> when {
        exclusiveReady && usbDacReady -> DsdModeAvailability(true, "")
        !exclusiveReady -> DsdModeAvailability(
            false,
            "需要 USB 独占通路：系统通路送不出原生 1-bit 流",
        )
        else -> DsdModeAvailability(
            false,
            "设备没有暴露 raw DSD 通道（UAC2 的 DSD_U32 / UAC1 的 FORMAT_TYPE_UNDEFINED）",
        )
    }
}

/**
 * DSD → PCM 可用的**最高**目标率。
 *
 * 固定 176.4 kHz，不看设备声明：
 * - 352.8 kHz 超过 `AudioFormat` 的合法上限（192 kHz），任何设备都建不出来，
 *   所以它永远不出现在可选项中；
 * - 176.4 kHz 是 44.1 家族里 ≤192 kHz 的最高档，`AudioTrack` 普遍建得出
 *   （HAL 会 rate-match）。
 *
 * 之前拿"设备声明 ∪ 探针实测"算这个上限，结果是**不可靠**：本机扬声器的 port
 * profile 只声明 48 kHz，一旦探针缓存没拿到，上限就被压成 48 kHz，176.4 会被
 * 悄悄降到 44.1 kHz —— 那正好是这条轴要消灭的"非整数比"。
 */
/**
 * **探不到时**的回退上限：176.4 kHz。
 *
 * ## 它以前叫 `MAX_DSD_PCM_RATE`，被当成"产品常量"用 —— 那是错的
 *
 * 旧名字和旧注释都暗示「这台机器能做到的最高就是 176.4k」，但那个数字其实是
 * **`AudioFormat.SAMPLE_RATE_HZ_MAX = 192000` 的推论**，而那条推论只对系统通路成立：
 *
 * - 系统 · AudioTrack：受 `AudioFormat` 限制，352.8k 连对象都构造不出来 → 176.4k 是硬上限
 * - framework 被厂商放开（部分国产 DAP 会改 `AudioTrack`）：352.8k 建得出 → 上限就该是 352.8k
 * - 将来 USB 独占（UAC2 直连）：上限来自 DAC 的 `FORMAT_TYPE_I.tSamFreq[]`，与 192k 无关
 *
 * 所以「最高能到几档」是**设备属性**，不是产品常量。真正的上限由 [dsdMaxPcmRate]
 * 从实测结果取（`SystemSinkProbe` 真建一次轨）；本常量只在"还没探过 / 探不到"时兜底。
 *
 * 兜底方向刻意选"能建出来的那个最高档"而不是"最高档"：
 * 宁可少一点带宽，也不要因为建轨失败直接播不出来。
 */
const val DSD_FALLBACK_MAX_PCM_RATE: Int = 176_400

/**
 * 这条通路上能建出的最高 DSD→PCM 率。
 *
 * @param probedMax 实测能建出的最高率（`SystemSinkProbe.highestUsable(...)` 的结果）；
 *   **0 = 还没探到 / 一档都不行** → 退回 [DSD_FALLBACK_MAX_PCM_RATE]。
 *
 * 实测值会被**夹回 44.1 家族**：DSD 的抽取只允许基频的 2 的幂分频，
 * 48 家族一个都不给（给了就等于主动插入一次非整数比重采样，那正是这条轴要消灭的东西）。
 * 所以设备实测到 192000 时候选只剩 176.4k，实测到 352800 时才会放开到 352.8k。
 */
fun dsdMaxPcmRate(probedMax: Int = 0): Int {
    if (probedMax <= 0) return DSD_FALLBACK_MAX_PCM_RATE
    return DSD_PCM_RATES.filter { it <= probedMax }.maxOrNull()
        ?: DSD_FALLBACK_MAX_PCM_RATE
}

/**
 * 「DSD 输出」页要把档位灰到哪一档。
 *
 * 三个输入缺一不可，少一个就会复现已经踩过的两种错：
 *
 * - **只看 `exclusiveIntent`**（设置里的意图）：拔掉 DAC 之后它还挂着「最高 352.8k」，
 *   因为意图是常驻的，不随设备走。
 * - **只看 `probedMaxRate`**（系统建轨实测）：独占下 352.8k 永远灰着，
 *   因为 `AudioFormat` 的 192k 上限与 UAC2 端点无关。
 *
 * @param isUsbRoute 此刻**真的**在 USB 音频路由上（拔线后为 false）
 * @param exclusiveIntent 设置里的通路是 USB 独占
 * @param level 当前在播曲目的 DSD 档位；null（没在播 DSD）= 按 DSD64 的网格算
 */
fun dsdMaxOutputRate(
    isUsbRoute: Boolean,
    exclusiveIntent: Boolean,
    level: DsdLevel?,
    probedMaxRate: Int,
): Int {
    if (isUsbRoute && exclusiveIntent) return (level ?: DsdLevel.dsd64).pcmGridRate
    return dsdMaxPcmRate(probedMaxRate)
}

/**
 * DSD 目标率的「意图 → 实际」说明；`null` = 没有差异，不该提示。
 *
 * 档位的选中值是**意图**（持久化，不随设备变），实际输出由 [dsdPcmRateOrFallback]
 * 按当下能力夹取。两者不一致时用户会看到「选着 352.8k，实际却在 176.4k」——
 * 不说清楚就只是"设置好像坏了"。这里给的是一句原因，不是第二条设置。
 *
 * @param wanted 档位选择；[DSD_RATE_FOLLOW_SYSTEM] 交给系统决定，不比对
 * @param actual 这一首**实际**的输出率；0 = 还没起播
 */
data class DsdRateNotice(val actualRate: Int, val reason: String) {
    val text: String get() = "实际 ${khzText(actualRate)} · $reason"
}

fun dsdRateNotice(
    wanted: Int,
    actual: Int,
    isUsbRoute: Boolean,
    exclusiveIntent: Boolean,
): DsdRateNotice? {
    if (wanted == DSD_RATE_FOLLOW_SYSTEM || actual <= 0 || wanted == actual) return null
    val reason = when {
        // 断电先于一切：那时谈"系统能不能建"没有意义
        !isUsbRoute -> "USB DAC 已断开，当前走系统通路"
        !exclusiveIntent -> "系统通路建不出这个率（最高 ${khzText(DSD_FALLBACK_MAX_PCM_RATE)}）"
        else -> "这条独占流接不住这个率"
    }
    return DsdRateNotice(actual, reason)
}

/**
 * 目标率的落档：配置值不可用（超平台能力 / 不是 44.1 家族）时退到**能建出来的最高档**。
 *
 * 退档方向刻意是"往下"：宁可少一点带宽，也不要因为建轨失败直接播不出来。
 */
fun dsdPcmRateOrFallback(configured: Int, maxBuildRate: Int): Int {
    val allowed = DSD_PCM_RATES.filter { it <= maxBuildRate }
    // 连 44.1k 都建不出来时返回它，是**已知的最后一级**，不再往下退。
    //
    // 为什么没有"退到设备率（48 家族）"这一级：那会破坏整数抽取 —— DSD 的基频是
    // 2.8224 MHz 的倍数，抽到 48k 家族必须做一次非整数比重采样，等于把这条轴存在的理由
    // 自己取消掉。而且 44.1k 是 `AudioFormat` 允许的最低 DSD 家族率，能播 PCM 的设备
    // 都建得出；真建不出来说明这条通路整条不可用（此时该整首不播，而不是换个率硬塞）。
    if (allowed.isEmpty()) return DSD_PCM_RATES.first()
    val wanted = configured.takeIf { it in DSD_PCM_RATES } ?: DSD_DEFAULT_PCM_RATE
    return if (wanted in allowed) wanted else allowed.last()
}

/**
 * DSD 曲目的全链路目标率；**null = 这一首不是 DSD**，调用方继续走常规 `OutputPlan.sampleRate`。
 *
 * 三条规则（第 3 条是补的，见下）：
 *
 * 1. **DSD 档优先**。即使调音模式选了一个 48 家族的目标率，DSD 也先按自己的档位抽成
 *    44.1 家族 —— 至少第一次抽取是干净的。
 * 2. **蓝牙例外**。末端是 codec（只吃 48 家族），先抽到 176.4k 再让 codec 转 48k 是两次转换，
 *    所以蓝牙下直接对齐 codec 协商率，一次到位。codec 率读不到时返回 null 退回常规逻辑
 *    —— 宁可少一次优化，也不编一个率出来。
 * 3. **设备不声明这个档位时，对齐系统率** —— 见下面的长注释。
 *
 * ## 为什么需要第 3 条（普通手机上的两次转换）
 *
 * 只做第 1 条时，普通手机（主率 48k）上 DSD 的链路是：
 *
 * ```
 * dsddec 352.8k ──r8brain──► 176.4k ──AudioFlinger──► 48k
 *                                ↑ 我方高质量           ↑ 系统，非整数比，简单插值
 * ```
 *
 * 而普通 44.1k 曲目只有一次（我方 44.1→48），AudioFlinger 不再动它。
 * 也就是说 **DSD 在普通手机上比普通曲目多一次劣质转换**，且那一次是 44.1→48 家族
 * 的非整数比 —— 正是这条轴一开始要消灭的东西。既然 AudioFlinger 必然要转，
 * 不如由 r8brain 一次转到位（206 dB / 最小相位）。
 *
 * ## 为什么不自动判断「设备能不能跟随」
 *
 * 曾经写成「设备声明里没有这个档位就自动降」，**那是错的**：DAP 的 `devicePcmRates`
 * 常常只声明 48000（port profile 写不全），而它实际能跟随任意率 —— 自动判断会把
 * **质量最好的设备降到 48k**。这与 99.7 里 `sourceRateIsSafe` 犯的错是同一个机制：
 * 拿声明代理能力，必然误伤一部分设备，而用户无法绕过。
 *
 * 所以这一层交给**用户**：档位选择里的「跟随系统（[DSD_RATE_FOLLOW_SYSTEM]）」。
 * - 选固定档位（默认 176.4k）→ DAP 与愿意接高率的 DAC 走这条，可能一次都不多转
 * - 选跟随系统 → 普通手机上少一次低质量的非整数比转换
 *
 * @param systemRate 系统 mixer **实际**会用的率（`systemMixRateFor` 的结果）；0 = 未知。
 *   只在档位选「跟随系统」时用；读不到就退回档位默认值（不猜）。
 */
fun dsdFullChainRate(
    level: DsdLevel?,
    configuredRate: Int,
    route: AudioRoute,
    codecRate: Int,
    maxBuildRate: Int,
    systemRate: Int = 0,
    exclusive: Boolean = false,
): Int? {
    if (level == null) return null
    if (route == AudioRoute.bluetooth) {
        return codecRate.takeIf { it > 0 }
    }
    // 「跟随系统」：一次转到位。系统率读不到时**不猜**，退回档位默认值。
    if (configuredRate == DSD_RATE_FOLLOW_SYSTEM && systemRate > 0) return systemRate
    // 独占通路的上限与 `AudioFormat` 无关：UAC2 直连能跑到几档，只由 DAC 的
    // `tSamFreq[]` 决定。拿系统建轨探针去卡它，等于用一条通路的限制否掉另一条 ——
    // 表现就是「独占明明能跑 352.8k，却永远只到 176.4k」。真跑不了由建流报错降级。
    //
    // 封顶是**这个档位自己的解码网格**（`pcmGridRate`），不是某个常数：
    // DSD64 到 352.8k、DSD128 到 705.6k、DSD256 到 1411.2k。再往上是升采样，
    // 只会白花带宽；而这个上限在每个档位上都恰好是"抽完一个采样都不动"。
    val cap = if (exclusive) level.pcmGridRate else maxBuildRate
    return dsdPcmRateOrFallback(configuredRate, cap)
}

/**
 * DSD→PCM 这条链路的输出低通截止：**按素材档位取标称值，再按目标率夹取**。
 *
 * ## 为什么需要这一级
 *
 * DSD 的噪声整形把量化噪声整个推到 20 kHz 以上。我们的抽取只砍到
 * `min(源, 目标)` 的 Nyquist —— DSD64 抽到 176.4k 时是 88.2 kHz，也就是说
 * **20 k–86.4 kHz 这一大片噪声原样送给了 DAC**（5 阶整形粗算：40 kHz 处约 −90 dBFS、
 * 80 kHz 处约 −60 dBFS、160 kHz 处约 −30 dBFS）。而这之前选 352.8k 档位是**更糟**的：
 * `srcRate == dstRate` 时 r8brain 整条旁路，177 kHz 以内一片不筛。
 *
 * 这一级就是补那个顶。
 *
 * ## 标称值为什么按档位变
 *
 * 见 [dsdPcmLowpassNominal] 的表：DSD 率翻倍，同一频率上的噪声降 30 dB（5 阶）。
 * 所以 40 kHz 对 DSD64 正好（那里已是 −90 dB），对 DSD128 以上则过保守 ——
 * 那里 40–60 kHz 还是干净的，而且可能有真实内容。标称值因此是 40 / 60 / 80 / 100 kHz。
 *
 * ## 不做成用户档位（仍然）
 *
 * 三个以上候选值的差别不是"听感不同"，而是"取舍不同"，而这种取舍不该让用户
 * 拿耳朵去解一道没有正确答案的题。判据是**我们确知的事实**（素材是 DSD64 还是 DSD128），
 * 不是对设备能力的猜测 —— 后者才需要开关。
 *
 * ## 夹取规则（两条，都不是选项）
 *
 * ```
 * nominal = 40 / 60 / 80 / 100 kHz          （DSD64 / 128 / 256 / 512）
 * hz      = min(nominal, 0.90 × dstRate/2)  （留得出 127 抽头的过渡带）
 * hz < 35 kHz → 0                           （干脆不做）
 * ```
 *
 * 44.1k 目标会落进"不做"：r8brain 的抗混叠已经砍在 21.6 kHz，再加一级只会咬到 20 kHz。
 * 88.2k 目标（Nyquist 44.1k）下上限是 39.69 kHz —— 所以那一档无论素材是 DSD64 还是
 * DSD512，都被压到同一个 39.69 kHz，这是目标率的限制，不是档位没用。
 *
 * @param level 素材的 DSD 档位；null（档位不明）= 按最保守的 DSD64 处理
 * @param dstRate 这条链路的**输出**率（不是源率）
 * @return 低通截止 Hz；0 = 不做这一级
 */
fun dsdPcmLowpassHz(level: DsdLevel?, dstRate: Int): Int {
    if (dstRate <= 0) return 0
    val cap = dstRate / 2 * 9 / 10
    val hz = minOf(dsdPcmLowpassNominal(level), cap)
    return if (hz >= DSD_PCM_LOWPASS_MIN_HZ) hz else 0
}

/**
 * 设置页入口行的摘要：`PCM · 176.4 kHz` / `PCM · 跟随系统` / `DoP` / `Native DSD`
 *
 * 目标率有三种"不是普通 Hz 数"的情况，**都不能直接交给 `khzText`**（它对 `<=0` 返回空串）：
 * - [DSD_RATE_FOLLOW_SYSTEM]（0）→ 拼出来会是「PCM · 」这种断了尾巴的文案；
 * - 其它非法值同理。所以这里显式兜底，摘要永远不留一个空的右半截。
 *
 * DoP / Native **不再挂「未实现」**：两条都在，能不能生效取决于通路与设备，
 * 那属于运行时事实，写进入口摘要会变成永久的谎。
 */
fun dsdSummaryText(mode: DsdOutputMode, configuredRate: Int): String = when (mode) {
    DsdOutputMode.pcm -> mode.label + " · " + when {
        configuredRate == DSD_RATE_FOLLOW_SYSTEM -> "跟随系统"
        else -> khzText(configuredRate).ifEmpty { "未知" }
    }
    DsdOutputMode.dop, DsdOutputMode.native -> mode.label
}
