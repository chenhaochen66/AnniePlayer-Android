package com.annieplayer.annie_music.kt.core.audio

/**
 * 音频文案的**唯一来源**（纯函数，可单测）。
 *
 * ## 为什么要有这个文件
 *
 * 以前采样率和位深是各拼各的：胶囊写 `24 Bit / 96→48 kHz`，尾巴再挂一句
 * `系统混音 32 Bit float`。系统混音一介入，同一行里就同时出现两个位深，
 * 读者无法判断哪个位深配哪个采样率 —— `24 Bit` 是我方送出的、`32 Bit float` 是之后的，
 * 但文字把它们并列成「24 Bit / 96→48 kHz · 32 Bit float」，读起来像同一条链上的两个注解。
 *
 * 现在的规则：**每一栏只写一个端点，不画转换箭头**。
 *
 * ```
 * 扬声器 · 系统重采样 · 24 Bit / 48 kHz   ← 胶囊：谁在转写在方式段，规格只写结果
 * 24 Bit / 96 kHz  →  32 Bit float / 48 kHz  ← 链路实况：两个端点各自完整成段
 * ```
 *
 * 曾经用 `96→48 kHz` 的箭头表示"还有人在转采样率"。它被删掉有两个理由：箭头只写得出
 * "变了"，写不出"谁变的"（我方转还是系统转是完全不同的两件事）；而且胶囊现在真的有一段
 * 专说这件事（`我方重采样` / `系统重采样`），同一个事实说两遍就会出现两种口径。
 * 链路实况那边靠**两个完整端点并排**表达同一件事，不需要箭头。
 *
 * 位深也从没写过 `16→24`：源位深由左边的源胶囊负责，写在这里会让人以为中间做过位深转换。
 *
 * ## 单位统一
 *
 * `Bit`（大写 B）、`kHz`、`kbps`。全项目只从这里取，别处不许再自己格式化。
 */

/** 采样率：`48 kHz` / `44.1 kHz` / `22.25 kHz`；非正数返回空串（不猜、不编） */
fun khzText(rate: Int): String {
    if (rate <= 0) return ""
    return when {
        rate % 1000 == 0 -> "${rate / 1000} kHz"
        rate % 100 == 0 -> "${rate / 1000}.${(rate % 1000) / 100} kHz"
        else -> "${rate / 1000}." +
            (rate % 1000).toString().padStart(3, '0').trimEnd('0') + " kHz"
    }
}

/** 位深：`24 Bit` / `32 Bit float`；非正数返回空串 */
fun depthText(depth: Int, float: Boolean = false): String {
    if (depth <= 0) return ""
    return if (float) "$depth Bit float" else "$depth Bit"
}

/**
 * 规格：`{位深} / {采样率}`。
 * **只写一个端点** —— 谁传进来就写谁：
 * - `specText(24, 48_000)` = `24 Bit / 48 kHz`
 * - `specText(32, 48_000, float = true)` = `32 Bit float / 48 kHz`
 *
 * 以前这里还有一个 `96→48` 的箭头，用来暗示"还有人在这之后转采样率"。那个箭头已经
 * 删除：转换**由谁做**现在由输出胶囊的方式段直接写出来（`我方重采样` / `系统重采样`），
 * 一个事实说两遍只会让同一行出现两种口径；而且箭头只写得出"变了"，写不出"谁变的"。
 */
fun specText(depth: Int, rate: Int, float: Boolean = false): String = listOf(
    depthText(depth, float),
    khzText(rate),
)
    .filter { it.isNotEmpty() }
    .joinToString(" / ")

/**
 * 输出链路的两个端点：**我方送出**（`in`）与**设备实际**（`out`）。
 *
 * 播放页的输出胶囊、设置页的链路实况、蓝牙摘要全部读这一份 ——
 * 同一套数字只在一处计算，不会出现「胶囊说 24、实况说 32」。
 */
data class OutputSpec(
    val inDepth: Int,
    val inRate: Int,
    val outDepth: Int,
    val outRate: Int,
    val outFloat: Boolean,
) {
    /**
     * 播放页 / 胶囊用的写法：**只写最终输出**（`24 Bit / 48 kHz`）。
     *
     * 不画箭头 —— 转换**由谁做**交给输出胶囊的方式段（`我方重采样` / `系统重采样`），
     * 并且与它写同一套口径：我方转过之后交付率就等于系统率，两边都是"一个数"，
     * 读者不需要再拿箭头去反推。
     *
     * 终点**完全读不到**时（蓝牙 codec 协商结果拿不到是最常见的一种）退回"我方送出"那一级：
     * 那不是我方编的数字，是当下唯一握在手里的事实；留空则整个胶囊只剩一个设备名。
     */
    val pair: String
        get() = if (outDepth > 0 || outRate > 0) {
            specText(outDepth, outRate, outFloat)
        } else {
            specText(inDepth, inRate)
        }

    /** 我方送出这一级的绝对规格：`24 Bit / 96 kHz` */
    val inPair: String get() = specText(inDepth, inRate)

    /**
     * 设备实际这一级的绝对规格：`32 Bit float / 48 kHz`。
     *
     * 与 [pair] 不同，这里**照实写 float** —— 「链路实况」的职责就是逐级列出容器，
     * 少写一层等于让用户少看一个可比较的对象。
     */
    val outPair: String get() = specText(outDepth, outRate, outFloat)
}

/**
 * 从状态里取出两级端点。
 *
 * 优先级是「越接近设备越优先」，因为界面要回答的是**到耳朵的到底是什么**：
 * - 送出：全链路 native 回报 > plan 交付值 > 源规格
 * - 设备：直通/bit-perfect 成功时就是我方那个率（有明确证据）> 蓝牙 codec 协商率
 *   > 系统 mixer 率 > 退回送出值
 *
 * 蓝牙的位深取 **codec 协商位深**，不是 mixer 的 float —— 耳机收到的就是 codec 那一级，
 * mixer 的 float 只是中间产物，写出来会让人以为耳机收到 32 Bit float。
 */
fun outputSpecOf(s: AudioOutputStatus): OutputSpec {
    // **raw DSD 直出：报告 DSD 自己的规格，不报 PCM 容器。**
    //
    // DoP / Native 送出去的是 1-bit 位流：DoP 把它塞进 24 bit 帧、Native 塞进 32 bit word。
    // 那个 24 / 32 是**容器宽度**，不是位深 —— 一个 32 bit word 里装的是 32 个 1-bit 样本。
    // 拿它当「输出位深 32 bit」显示，等于告诉用户我们在转 PCM，恰好否定了这条链路的全部意义。
    // 所以这一档只说一件事：源是什么 DSD，就原样送什么 DSD。
    if (s.dsdTransport.isRaw && s.sourceRate > 0) {
        return OutputSpec(
            inDepth = 1,
            inRate = s.sourceRate,
            outDepth = 1,
            outRate = s.sourceRate,
            outFloat = false,
        )
    }
    // 「送出」= 我们真的交给 `AudioSink` 的那个率（`deliveredRate`），不是解码器出口。
    // 两者在 DSD 上必然不同：`dsddec` 吐的是 352.8k 网格，我方抽到 176.4k 才交付。
    // 曾经 `pipelineRate` 优先，于是面板只写「352.8 → 设备」，中间的抽取整级消失，
    // 用户选 176.4k 看起来像没生效（实测 AudioTrack 就是 176400）。
    val inRate = when {
        s.deliveredRate > 0 -> s.deliveredRate
        s.pipelineRate > 0 -> s.pipelineRate
        else -> s.sourceRate
    }
    // 「送出」这一级**照实写容器**（交出 float 就写 `32 Bit float`）：`inDepth` 只服务
    // 链路实况的逐级罗列（`inPair`）。胶囊里的位深取终点，见 `pair`。
    val inDepth = when {
        s.pipelineDepth > 0 -> s.pipelineDepth
        s.deliveredDepth > 0 -> s.deliveredDepth
        else -> s.sourceDepth
    }
    val bluetooth = s.route == AudioRoute.bluetooth
    // 独占通路不过 AudioFlinger：`nativeRate`（`PROPERTY_OUTPUT_SAMPLE_RATE`）描述的是
    // mixer，与本通路无关。用它当"设备率"会把 88.2k 的独占流显示成 `88.2→48`——
    // 看起来像被系统降了一级，实际端点收到的就是我方交付的那个率。
    val exclusive = s.effectivePath == OutputPath.usbDirect ||
        s.effectivePath == OutputPath.aaudioExclusive
    val outRate = when {
        s.bitPerfect -> inRate
        exclusive -> inRate
        // 蓝牙的设备率**只有** codec 协商结果能给。读不到就留 0（这一项不写），
        // 不许退回 `nativeRate` —— 那是 primary（扬声器）输出率，不是 a2dp 输出线程率，
        // 顶上去会凭空写出一次 `44.1→48 kHz`，把一次系统 SRC 说成没有。
        bluetooth -> s.bluetoothRate
        s.nativeRate > 0 -> s.nativeRate
        else -> inRate
    }
    val outDepth = when {
        // Bit Perfect：系统接受保持源规格，中间没有 mixer 的 float 格式，
        // 这一级的位深就是源位深 —— 写 32 Bit float 会凭空虚报一层转换
        s.bitPerfect -> inDepth
        // 独占：位深由端点/流决定（USB 的 subslot、AAudio 的 HAL 格式），
        // 不是我方交付的 float 容器 —— 那一层只是中间产物，写它会让人以为
        // DAC 收到 32 位。读不到才退回交付值。
        exclusive -> s.streamDepth.takeIf { it > 0 } ?: inDepth
        // 蓝牙同理：mixer / sink 的 float 只是中间产物（实测 a2dp 线程是
        // `PCM_24_BIT_PACKED`），写进终点描写会让人以为耳机收到 32 Bit float。
        bluetooth -> s.bluetoothDepth
        s.sinkDepth > 0 -> s.sinkDepth
        else -> inDepth
    }
    // 位深取**实测** sink 值（`sinkDepth`/`sinkFloat` 同源）。以前 float 标志取自
    // `AudioCapabilities`，而本机它只报 PCM_16 → `outFloat=false`，界面就把 float 轨
    // 写成 `32 Bit`（整数），凭空多出一个不存在的位深转换。
    val outFloat = !bluetooth && !s.bitPerfect && s.sinkFloat && outDepth >= 32
    return OutputSpec(inDepth, inRate, outDepth, outRate, outFloat)
}

/**
 * 播放页输出胶囊的**分段结果**（纯数据，文案由 [text] 统一拼）。
 *
 * ```
 * [调音] · 路由 · [DSD 模式] · [方式] · 规格
 *   1        2        3          4       5
 * ```
 *
 * 按段拆开而不是直接拼字符串，是因为每一段都是独立事实、各有各的成立条件：
 * `扬声器 · 我方重采样 · 16 Bit / 48 kHz` 里中间两段都可能不存在。
 */
data class OutputHeadline(
    /** 段 1：调音启用。**永远在最前** —— 它说的是"我方动过手"，比"从哪儿出去"更靠前。 */
    val tuning: String?,
    /** 段 2：物理出口（`扬声器` / `USB` / `LHDC 1000 kbps`…） */
    val route: String,
    /** 段 3：DSD 模式（`DoP` / `Native DSD` / `DSD 转 PCM`）；非 DSD 为 null */
    val dsd: String?,
    /** 段 4：数据怎么过去的（`Bit Perfect` / `我方重采样` / `系统重采样`）；无转换且非直出为 null */
    val way: String?,
    /** 段 5：规格（`24 Bit / 96→48 kHz`） */
    val spec: String,
    /** 输出图标颜色 */
    val tone: AudioTone,
) {
    val text: String
        get() = listOfNotNull(tuning, route, dsd, way, spec)
            .filter { it.isNotEmpty() }
            .joinToString(" · ")
}

/**
 * 五段的取值规则。**只有这里决定顺序与用词**，界面不许自己拼。
 *
 * 段 4 是本轮唯一新增的判据「系统重采样」：`tier` 只描述我方做了什么，
 * 而"我方没转、系统在转"过去只能靠规格里的箭头去猜。
 */
fun outputHeadline(s: AudioOutputStatus): OutputHeadline {
    val spec = outputSpecOf(s)
    val way = when {
        // raw DSD 逐字节直出，没有"方式"这回事（规格已经写成 1 Bit）
        s.dsdTransport.isRaw -> null
        s.tier == OutputTier.bitPerfect -> OutputTier.bitPerfect.prefix
        // 「兜底」是更具体的事实（计划外的那次转换），必须先判 —— 否则 `didResample`
        // 会把它吞掉，两者就分不开了
        s.tier == OutputTier.ownSrcFallback -> OutputTier.ownSrcFallback.prefix
        // `didResample` 是实测事实，`tier` 是结论；任一成立都说明那次转换是我方做的。
        // DSD 转 PCM 的抽取也走这条 —— 它就是一次我方重采样，不必另起一个中性词。
        s.didResample || s.tier == OutputTier.ownSrc -> OutputTier.ownSrc.prefix
        s.systemResampling -> "系统重采样"
        else -> null
    }
    return OutputHeadline(
        tuning = "调音".takeIf { s.tone == AudioTone.tuned },
        route = s.routeBadge,
        dsd = s.dsdLabel,
        way = way,
        spec = spec.pair,
        tone = s.tone,
    )
}

/**
 * 播放页「输出」胶囊的文案：
 *
 * ```
 * [调音 · ]路由[ · DSD 模式][ · 方式] · 规格[ · 异常标签]
 * ```
 *
 * **只有异常才追加标签** —— 正常链路一个字都不多写，因为「正常」本身不需要解释。
 */
fun outputPillText(s: AudioOutputStatus): String {
    val head = outputHeadline(s).text
    if (head.isEmpty()) return ""
    return buildString {
        append(head)
        for (note in outputNotes(s)) append(" · ").append(note)
    }
}

/**
 * 输出链路的异常标签（正常时为空表）。
 *
 * 曾经有一条「直通未确认」：判定该直通、轨也建了、系统却没回报 offload。
 * **直通功能已整条删除**，这条也就没有了 —— 现在不存在"本该发生却没发生"的状态。
 */
fun outputNotes(s: AudioOutputStatus): List<String> = buildList {
    // 原来的「本机不支持该档」随 `RateFallback.platformRejected` 一起删除：
    // 目标率不再由用户选，也就没有"选了但平台不认"这种状态。
    //
    // USB 是唯一"系统实际率读不到"的通路：`PROPERTY_OUTPUT_SAMPLE_RATE` 描述的是
    // primary（扬声器/有线），USB 有自己的 output thread，应用层没有任何 API 能读它
    // —— 连 `getPreferredMixerAttributes` 也在 `MODIFY_AUDIO_ROUTING` 权限后面。
    // 所以第三级的目标率只能按设备声明推：推对了没事，推错了系统会再转一次，必须说清。
    //
    // ⚠️ 这条只对**系统通路 + USB DAC** 成立。独占生效后 `route` 同样是 `usb`，
    // 但它直接写端点、根本不经过 mixer —— 不加这个排除，界面会在一台正在原样直出的
    // 设备上写「系统可能再转一次」，把没有问题说成有问题。
    val exclusive = s.effectivePath == OutputPath.usbDirect ||
        s.effectivePath == OutputPath.aaudioExclusive
    // DSD 的目标率是用户选的，不是"按设备声明推出来的"，这条提醒对它不成立
    if (s.dsdTargetRate == 0 && !exclusive && s.route == AudioRoute.usb &&
        s.tier == OutputTier.ownSrc && s.didResample
    ) {
        add("USB 目标率按设备声明，系统可能再转一次")
    }
}

/**
 * 设置页「链路实况」：三段各自完整，用 ` · ` 分段（`→` 只留给级内变化，不混用）。
 *
 * ```
 * 解码 软解 · 源 24 Bit / 96 kHz · 送出 24 Bit / 96 kHz · 设备 32 Bit float / 48 kHz
 * ```
 *
 * 为什么三段都写全、不省略相同的段：这一步的作用就是让用户**数一次 SRC**
 * —— 少了哪一段，就少了一个可比较的对象。
 */
fun chainText(s: AudioOutputStatus): String {
    val spec = outputSpecOf(s)
    val decode = when (s.decode) {
        DecodeKind.nativeFullChain -> "全链路"
        DecodeKind.ffmpegSoft -> "软解"
    }
    return buildList {
        add("解码 $decode")
        val source = specText(s.sourceDepth, s.sourceRate)
        if (source.isNotEmpty()) add("源 $source")
        if (spec.inPair.isNotEmpty()) add("送出 ${spec.inPair}")
        if (spec.outPair.isNotEmpty()) add("设备 ${spec.outPair}")
        // 「落在哪一级」是用户唯一真正在意的**结果**：前四段说的是"是什么"，
        // 这一段说"数据是怎么过去的"（直通 / 原样 / 我方转换）。
        //
        // 以前它单独占一行（`ladderSummaryText`），而那行的前缀描述的是已经不存在的
        // 「非 USB 独占」策略 —— 那条在输出矩阵重构后已经废了。现在并进来，
        // 一行说完，也省掉一个要同步维护的文案函数。
        s.tier?.let { add("当前 ${it.prefix}") }
    }.joinToString(" · ")
}

/**
 * 蓝牙链路摘要：`LHDC · 24 Bit / 48 kHz`（读不到 codec 时退回 `蓝牙`）。
 *
 * 只用于设置页的说明文字；播放页用 [outputPillText]（它还带码率和异常标签）。
 */
fun bluetoothSummaryText(s: AudioOutputStatus): String {
    val spec = specText(s.bluetoothDepth, s.bluetoothRate)
    val codec = s.bluetoothCodec.takeIf { it.isNotEmpty() && !it.startsWith("未知") }
        ?: "蓝牙"
    return if (spec.isEmpty()) codec else "$codec · $spec"
}
