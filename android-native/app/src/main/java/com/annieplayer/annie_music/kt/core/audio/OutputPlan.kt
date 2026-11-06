package com.annieplayer.annie_music.kt.core.audio

import android.media.AudioFormat

/**
 * 输出计划的**解码轴**：压缩帧怎么变成 PCM。
 *
 * 与通路轴（[OutputPath]）正交：同一条解码路径可以配任意通路。
 *
 * ⚠️ 曾经有第三个值 `offloadNoDecode`（压缩帧直通、由 HAL 解码）。**整条已删除**：
 * 它把"数据被谁碰过"交给一条我们既测不到、也管不了的系统通道，收益不可验证，
 * 代价是链路出现第二条"我方没碰数据"的分支，到处都要额外判一次。
 */
enum class DecodeKind(val label: String) {
    /** ffmpeg 软解（默认）：Media3 出压缩帧 → `libavcodec` 解码成 PCM。 */
    ffmpegSoft("软解"),

    /** 本地全链路：ffmpeg 自己解封装 + 解码 + swresample，一步出目标规格的 PCM。 */
    nativeFullChain("全链路"),
}

/**
 * 输出计划的**通路轴**：PCM 走哪条路出去。
 *
 * 这是用户唯一要选的"大项"。**开关是通路的属性**，不是并列的选项 ——
 * 见 `OutputToggles`：系统通路有「直通 / 按源采样率」，AAudio 通路有「Bit Perfect」。
 *
 * 之前这里是 `systemMix / bitPerfectMixer / usbDirect`，其中 `bitPerfectMixer` 是
 * `setPreferredMixerAttributes` 那条路 —— 它需要 `MODIFY_AUDIO_ROUTING`
 * （`signature|privileged`，普通安装拿不到），事实上是死路，已删除。
 * 真正能"原样送出"的只有 AAudio 独占，所以它单独成为一条通路。
 *
 * [implemented] = false 的那一项是**已规划的扩展位**：协议/UI 先占位，实现留到后面。
 * 它不会进入 `resolveOutputPlan` 的有效路径（会安全回退到 [systemMix]），UI 上灰显。
 */
enum class OutputPath(
    val key: String,
    val label: String,
    /** 是否已实现；false = UI 灰显、引擎回退 */
    val implemented: Boolean,
) {
    /** `AudioTrack` → AudioFlinger mixer（含系统 effect 链）。最保险的一档。 */
    systemMix("system-mix", "系统 · AudioTrack", true),

    /**
     * AAudio 独占（EXCLUSIVE + MMAP，内核是 Oboe）：唯一能真正绕开 mixer 的路。
     *
     * 能不能成立取决于 **ROM 策略 + HAL profile**，不是我们能决定的 ——
     * 探测与失败原因见 `AudioDirectProbe` 的矩阵，面板会如实说「本机不支持独占」。
     */
    aaudioExclusive("aaudio-exclusive", "AAudio 独占", true),

    /**
     * USB UAC 直连：直接写端点，绕开 AudioFlinger 与整个 mixer。
     *
     * 唯一能送出 44.1 kHz、352.8/384 kHz 与原生 DSD 的通路。只支持 Android 14+；
     * 未授权 / 无设备 / 版本不够时由 `UacEngine` 返回 null，自动回落 [systemMix]。
     */
    usbDirect("usb-direct", "USB 独占", true),
    ;

    companion object {
        fun fromKey(key: String?): OutputPath =
            entries.firstOrNull { it.key == key } ?: systemMix

        /** UI 用：已实现的通路（未实现的另列灰项） */
        val available: List<OutputPath> get() = entries.filter { it.implemented }

        /** UI 用：规划中、尚未实现的通路 */
        val planned: List<OutputPath> get() = entries.filterNot { it.implemented }
    }
}

/**
 * 输出**落在哪一级** —— 阶梯的唯一结果。
 *
 * 为什么要有它：以前"哪级生效"散在 `decode` / `path` / `bitPerfect` / `directActive` /
 * `didResample` 五个字段里，界面必须自己拼布尔组合，于是出现了
 * 「说直通、却报系统混音率」这种自相矛盾。现在收敛成一个值，播放页只读它。
 *
 * **只报"发生了的事"**：没有 tier 前缀 = 什么都没发生（系统自己转，或根本没转）。
 * 以前那个「直通未生效」的身份前缀没有了 —— 降级链自己会显示落在哪一级，
 * 不需要再抱怨"本该发生却没发生"。
 *
 * @property prefix 贴在**规格前面**的短语（`硬解直通 24 Bit / 48 kHz`）。
 *   不占身份槽：身份说"从哪儿出去"，tier 说"数据是怎么过去的"。
 */
enum class OutputTier(val prefix: String) {
    /**
     * 系统 mixer 接受了我方的「保持源规格」请求（`MIXER_BEHAVIOR_BIT_PERFECT`），
     * 于是 mixer 跑在源规格上、不做算术。
     *
     * ⚠️ 需要 `MODIFY_AUDIO_ROUTING`（`signature|privileged|role`）：普通安装拿不到，
     * 少数魔改国砖 ROM / priv-app 装法可以。拿不到时这一级**根本不会被请求**
     * （见 `AudioOutputController.isBitPerfectPermitted`），不会伪装成"试过了"。
     */
    bitPerfect("Bit Perfect"),

    /** 我方确实转换过采样率（r8brain 或 native swresample）—— 策略内的一次转换。 */
    ownSrc("我方重采样"),

    /** 调音 + Bit-Perfect：**原样送出没成功**，我方兜底转了。计划外的转换，必须说出来。 */
    ownSrcFallback("我方重采样（兜底）"),
    ;
}

/**
 * 我方交出 **float 全精度**（32 = float）—— 「位深交给 sink」的实现方式。
 *
 * 为什么不是"输出 24 Bit 整数"（直觉做法）：写整数等于**我们在出口替 sink 做了一次量化**，
 * 既把 r8brain 的 double 结果截掉，又会让 mixer 在自己的出口再量化一次（16 Bit 时还会
 * 再抖一次 → 双重抖动 +3 dB）。交出 float 之后，全链只剩 sink 那一次量化、一次抖动，
 * 位置正确、次数正确。AOSP 在 24 Bit 出口本来就不抖（误差在 −144 dB 以下），
 * 所以"我们替它抖"没有意义，只有"我们不替它量化"才有意义。
 */
internal const val PLAN_FLOAT_DEPTH: Int = 32

/**
 * 四轴合成的结果 —— **输出决策的唯一出口**。
 *
 * 为什么要有它：采样率原来在 `AudioOutputTarget` 算一遍、直通在 `_applyOutputPolicy`
 * 算一遍、全链路又在 `_setSource` 算一遍，三处各用一套判据，于是出现
 * 「透明模式 + 接管采样率」时「我方输出 88.2k、却请求系统按 44.1k 开 mixer」这种
 * 自相矛盾的组合。现在所有判断收敛到 [resolveOutputPlan]（纯函数，可单测），
 * 播放层、设置页、播放页胶囊都只读这一份结果。
 *
 * @property sampleRate 我方输出的采样率；**0 = 不做 SRC**（保持源率）
 * @property bitDepth 我方交付的位深；**0 = 完全不动**（上转交给 Media3/mixer），
 *   `32` = 交出 float 全精度（见 [PLAN_FLOAT_DEPTH]）。**不会出现 16 / 24** ——
 *   位深不再是用户选项，我方只决定"要不要接管"，接管就交全精度、量化留给 sink
 * @property mixerTarget 向系统请求的 mixer 率；**0 = 不请求**
 */
internal data class OutputPlan(
    val mode: AudioOutputMode,
    val decode: DecodeKind,
    val path: OutputPath,
    /** 源采样率（Hz）；0 = 未知 */
    val sourceRate: Int,
    /** 源位深（bit）；0 = 未知 */
    val sourceDepth: Int,
    val sampleRate: Int,
    val bitDepth: Int,
    val mixerTarget: Int,
    val fallback: RateFallback,
    /**
     * 落在哪一级（`null` = 不写前缀）。这是**意图**，不是结论：
     * `ownSrc` 的最终确认需要 native 侧的真实输出率。纯函数只能给意图。
     */
    val tier: OutputTier? = null,
    /**
     * DSD 的最终 PCM 目标率（44.1 家族）；0 = 这一首不是 DSD。
     *
     * 存在的意义只有一个：**把"这一首是 DSD"这个事实带出纯函数**。`sampleRate` 已经是
     * 抽取后的率，无法反推；而 `DsdLevel.of(sourceRate)` 永远匹配不上（拿到的是 dsddec 的
     * 中间格式 352800，表里存的是 1-bit 位率 2822400）。
     *
     * 播放层据此判断"我方要不要重采样"和"要不要让 native 保持源率"，
     * 见 `MediaPlayerBackend` 的 `willResample` 与 `FfmpegLocalExtractor(keepSourceRate=)`。
     */
    val dsdTargetRate: Int = 0,
) {
    /** 我方实际发生了采样率转换（UI「重采样」判据）。 */
    val didResample: Boolean
        get() = sampleRate > 0 && sourceRate > 0 && sampleRate != sourceRate

    /**
     * 我方实际改了位深。
     *
     * **int → float 只是换容器，不算改位深**：24 Bit 整数进 float32 是精确的
     * （float32 尾数正好 24 位），既没有丢信息也没有重量化 —— 与文案契约
     * （`AudioText.outputSpecOf` 里 float 容器不画箭头）保持同一个事实。
     */
    val didConvertDepth: Boolean
        get() = bitDepth in 16..24 && sourceDepth > 0 && bitDepth != sourceDepth

    /** 交付给 AudioSink 的采样率（0 = 交给系统按源率处理）。 */
    val deliveredRate: Int
        get() = if (sampleRate > 0) sampleRate else sourceRate

    /** 交付给 AudioSink 的位深。 */
    val deliveredDepth: Int
        get() = if (bitDepth > 0) bitDepth else sourceDepth

    /** 是否走「我方不处理」的透明链路（供 UI 判断要不要提示系统 SRC）。 */
    val isPassthrough: Boolean
        get() = mode == AudioOutputMode.transparent && sampleRate == 0 && bitDepth == 0
}

/**
 * 合成输出计划。
 *
 * **降级链只有两级**（「非独占」语义：不独占设备、按能力尽力）：
 *
 * 1. **bit-perfect**：软解成 PCM，请求系统保持源规格。**需要系统级音频路由权限**，
 *    普通安装拿不到 → 这一级根本不会被请求（由调用方传 `bitPerfectAttemptable`）。
 * 2. **我方 SRC**：做不到上一级时，由本应用转到**系统实际会用的率**。
 *    为什么这是主干而不是补救：PCM 一定经过 mixer（Java 的 `AudioTrack` 没有 PCM
 *    直通标志），所以那一级转换**必然发生**，区别只在于是谁的算法在转。
 *
 * 调音模式不变（用户显式选率/位深）；只有「Bit-Perfect」这一档会走第二级兜底。
 *
 * 蓝牙：链路参数由 codec 决定 → 通路强制 systemMix，但**采样率仍归我方接管**
 * （「跟随设备」= SRC 到 codec 协商率，实测 a2dp 线程就跟着协商率走）。
 *
 * 全链路：保持自己的规格（native 侧定），但**最后仍套用第二级** ——
 * 目标率取系统实际率，由 r8brain 补那一次转换（DSD 除外，它有自己的梯子）。
 *
 * @param bitPerfectAttemptable 是否**值得去请求** bit-perfect（路由支持 + 权限已授予）。
 *   false = 连请求都不发，避免每帧策略都用一次 `SecurityException` 试探。
 * @param bitPerfectGranted 上一次请求是否被系统接受（由调用方在定计划**之前**问出来）
 * @param canBuildRate 平台能否建出该采样率（可注入，便于 JVM 单测）
 */
internal fun resolveOutputPlan(
    mode: AudioOutputMode,
    route: AudioRoute,
    nativeRate: Int,
    deviceRates: List<Int>,
    /** 蓝牙 codec 协商率（`BluetoothCodec.info.sampleRate`）；0 = 没读到 */
    codecRate: Int = 0,
    sourceRate: Int,
    sourceDepth: Int,
    /**
     * **解码出来的**采样率（全链路时是 native 回报的真实输出率）；0 = 未知，按 [sourceRate] 算。
     *
     * 只用来回答一个问题：**我方到底会不会重采样**。它决定我们该不该接管位深 ——
     * 不转的时候上转是精确的，交给 Media3/mixer 就行，我们多插一手只会多一个出错的地方。
     */
    decodedRate: Int = 0,
    /**
     * **源率能不能拿到 AAudio 独占**（透明模式第二级的实现者）。
     *
     * 这一位是"要不要重采样"的前置条件，不是为了显示：
     * 能拿到独占就**不转**（原样送出，由 AAudio 以源率开流）；
     * 拿不到才转成系统率走 mixer。以前探测结果只进了能力面板，
     * plan 从来没读过它 —— 于是出现"探测显示独占可用，实际仍走我方 SRC"。
     */
    aaudioExclusive: Boolean = false,
    /**
     * 用户开了「按源采样率输出」（**意愿**，不代表一定会生效）。
     *
     * 只在系统通路上有意义（`effectivePath == systemMix`）。生效后我方不做任何预转换：
     * 系统若跟随源率就原样输出，若固定主率则由系统重采样（质量低于本应用）。
     * **不影响调音** —— PEQ 照样工作在源率上。
     */
    followSourceRate: Boolean = false,
    fullChain: Boolean,
    path: OutputPath,
    bitPerfectAttemptable: Boolean = false,
    bitPerfectGranted: Boolean = false,
    /**
     * DSD 的**最终** PCM 目标率（44.1 家族，来自「DSD 输出」页的档位）；0 = 这一首不是 DSD。
     *
     * 有值时它**压过下面整条采样率梯子**，因为梯子里的每一级对 DSD 都不成立：
     * - `followSourceRate`（按源率建轨）会把 352800 塞给 AudioTrack —— `AudioFormat`
     *   的合法上限是 192000，直接建轨失败；
     * - `aaudioExclusive` / `bitPerfect` 请求"原样送出"同理；
     * - 系统率那一级则是"交给系统去转 DSD 的 352800"，那正是要避免的事。
     *
     * DSD 的抽取只有一条正确的路：native 只解成 352800 float（`keepSourceRate`），
     * 2:1 抽取由 r8brain 做（2% 过渡带 / 206 dB / 最小相位）。所以这里的值就是
     * `dsdFullChainRate()` 的结果，别的都不许覆盖它。
     */
    dsdTargetRate: Int = 0,
    canBuildRate: (Int) -> Boolean = ::platformCanBuildRate,
): OutputPlan {
    val tuned = mode == AudioOutputMode.tuned

    // ── 轴 3：通路（必须先算，后面的规则都要问"实际生效的"那一条）──
    //
    // 蓝牙下通路会被强制成 systemMix（链路参数由 codec 决定）；若仍按用户选的去规划，
    // 结果就是"我方不转、系统 mixer 照转" —— 正是「接管 SRC」要消掉的那一次。
    val effectivePath = when {
        // 规划中但尚未实现的通路：安全回退（不允许半成品把音频弄哑）
        !path.implemented -> OutputPath.systemMix
        route == AudioRoute.bluetooth -> OutputPath.systemMix       // 链路由 codec 决定
        else -> path
    }
    val exclusivePath = effectivePath == OutputPath.usbDirect ||
        effectivePath == OutputPath.aaudioExclusive

    // 调音模式的目标率。
    //
    // **独占通路上取源率**，不取 `nativeRate`：`PROPERTY_OUTPUT_SAMPLE_RATE` 描述的是
    // AudioFlinger 的 primary mixer，而独占根本不经过它。把它当目标会得出
    // 「88.2k 的歌转成 48k 再送进独占端点」—— 白转一次、多一层量化，而折返点
    // 完全没必要存在：EQ 的滤波器系数本来就按输入率算，直接在源率上工作即可。
    val autoRate = if (exclusivePath && sourceRate > 0) {
        sourceRate
    } else {
        autoRateFor(route, nativeRate, deviceRates, codecRate, sourceRate)
    }
    // 系统 mixer **实际**会用的率 —— 「替他转」时的目标。拿不到就是 0（不猜）。
    val systemRate = systemMixRateFor(route, nativeRate, deviceRates, codecRate, sourceRate)

    // ── 轴 2：解码 ──
    // 只剩两种：本地全链路（native 一把到底）与 ffmpeg 软解。
    val decode = when {
        fullChain -> DecodeKind.nativeFullChain
        else -> DecodeKind.ffmpegSoft
    }

    // ── 采样率 ──
    //
    // 调音模式下采样率只有一个来源：**跟随设备**（`autoRate`：蓝牙下是 codec 协商率，
    // 非蓝牙是系统实际率）。原来这里还有一个「自选采样率」档位，已删除 ——
    // 它与「按源采样率输出」这个开关语义重叠，两个旋钮管同一件事只会自相矛盾。
    // 平台建不出来的率不能硬塞给 AudioTrack（会直接建轨失败），退回系统实际率。
    val acceptedRate = if (autoRate > 0 && !canBuildRate(autoRate)) systemRate else autoRate
    // ── 采样率 ──
    //
    // 「按源采样率」是**系统通路的开关**。它约束的只有一件事：我方要不要预转换采样率。
    // 与调不调音**正交** —— 打开后 EQ / PEQ 照常工作在源率上（滤波器系数本来就按输入率算），
    // 调音与否由 `tuningEnabled` 派生，与这里无关。
    //
    // 为什么不加"源率 == 主率"这类智能判据：那个判据依赖 `PROPERTY_OUTPUT_SAMPLE_RATE`，
    // 而它是**静态属性**（反映 primary output 的默认率，不是此刻实际率）。做了动态跟随的
    // DAP（飞傲官方明确说第三方 App 也不 SRC）那个属性照样报 48000 —— 智能判据会在
    // 这种设备上**误判成"会被转"**，白白替系统转一遍，反而废掉了设备的能力。
    //
    // 所以这里只做一件事：用户开，就按源率建轨。成败交给系统，风险由文案说明
    // （"若系统重采样，质量低于本应用"）。**不做替用户保守决定的逻辑**，
    // 因为那种逻辑一定会误伤一部分设备，而用户没法绕过它。
    val followSourceRateNow =
        followSourceRateActive(effectivePath, followSourceRate)

    // ── 采样率 ──
    //
    // 四条互斥的去处：
    //   ① 按源采样率（系统通路）→ 0：不做任何预转换（开没开调音都按源率）
    //   ② bit-perfect 成功 → 0：原样送出，一个采样都不动
    //   ③ AAudio 独占成立 → 0：同理（独占流一旦我们先转，那次 SRC 就白做了）
    //   ④ 其余 → 由我方转到「系统实际会用的率」（`systemRate`）
    val sampleRate = when {
        // ⓪ DSD：档位说了算，压过下面每一级（理由见参数注释）。
        //    这条必须在最前面 —— 任何"原样送出"的分支都会把 352800 塞给 AudioTrack。
        dsdTargetRate > 0 -> dsdTargetRate
        followSourceRateNow && sourceRate > 0 -> 0       // ①
        !tuned && bitPerfectGranted -> 0                 // ②（透明，具备路由权限的机型）
        // ③ AAudio 独占成立 → 0：同理（独占流一旦我们先转，那次 SRC 就白做了）
        //
        // 判据里必须带 `effectivePath`，**不能只看"探测可用"**：
        // 蓝牙下通路会被强制成 systemMix（链路参数由 codec 决定），那时独占根本没被使用，
        // 若还按"探测可用"把 `sampleRate` 归零，就是"我方不转、让 a2dp 线程转" ——
        // 白扔一次 r8brain。未实现的通路回退到 systemMix 时同理。
        //
        // 关键：独占流必须按源率开，一旦我们先转成"系统实际率"，
        // 那次 SRC 就白做了 —— 这正是"探测显示独占可用、实际仍报我方 SRC"的成因。
        //
        // USB 独占不需要任何探测位：它能建 44.1 也能建 384，**天生**不该由我方转。
        !tuned && effectivePath == OutputPath.usbDirect -> 0
        !tuned && aaudioExclusive && effectivePath == OutputPath.aaudioExclusive -> 0
        // ④ 目标率
        //    · 透明：系统实际率（`systemRate`，USB 取最接近声明档）
        //    · 调音：用户选的档位（`acceptedRate`）
        !tuned -> systemRate
        else -> acceptedRate
    }

    // ── 位深：**谁做最后一次重量化，谁负责位深** ──
    //
    // 先算"我方到底会不会重采样"：目标率与**解码出来的**率不同才算真的在转。
    //
    // 这里以前有一句 `DsdLevel.of(sourceRate) == null &&`，等于"DSD 不参与"。它从来没起过
    // 作用（`sourceRate` 拿到的是 dsddec 的中间格式 352800，而 `DsdLevel` 表里存的是 1-bit
    // 位率 2822400，永远匹配不上），却让人以为 DSD 被正确排除了。现在 DSD 走
    // `dsdTargetRate` 这条正路：176400 ≠ 352800 → 确实要转，也确实由 r8brain 转。
    val decoded = decodedRate.takeIf { it > 0 } ?: sourceRate
    val resampling = sampleRate > 0 && decoded > 0 && sampleRate != decoded

    // **位深不再是用户选项**，判据只有一个：**我方碰没碰过数据**。
    //
    //   碰过（重采样 或 调音）→ 交出 float 全精度（`PLAN_FLOAT_DEPTH`），末级量化交给下游
    //   没碰                  → 0，完全不动（16→float→int24 由 Media3/mixer 完成，精确）
    //
    // ## 为什么蓝牙也交 float（曾经想改成"交 24 Bit 整数"，实测证明是错的）
    //
    // 直觉是"a2dp 是整数链路（`PCM_24_BIT_PACKED`），交 float 会让 AudioFlinger 多转一次"。
    // 实测把这个直觉推翻了：
    //
    // 1. **交 24 整数根本到不了 AudioTrack**。Media3 1.4.1 的 `setAudioCapabilities`
    //    已废弃且实测不生效，`DefaultAudioSink` 用的是它自己的默认能力（本机只报 PCM_16），
    //    于是一旦用 `setEnableFloatOutput(false)` 试图交整数，24 会被它插一层 `ToInt16`
    //    → 实测 track 从 `0x5`(FLOAT) 变成 **`0x1`(PCM_16)**，白掉 8 位。
    // 2. **AudioFlinger 内部本来就是 float 混合**，出口才 `ditherAndClamp` 到
    //    `PCM_24_BIT_PACKED`。所以交 float 是**零额外损失**；交整数反而要多一次
    //    int→float 进混合、再 clamp 回去。float 这条路才是"少转一次"的那条。
    //
    // 结论：float 是这条链上唯一既可行又正确的选择，对普通手机和蓝牙都一样。
    // 位深的**真实终点**由下游决定（mixer 出口 / HAL / a2dp codec），我们只保证
    // "不提前替它量化"——这正是 `PLAN_FLOAT_DEPTH` 的原始论证。
    val bitDepth = when {
        tuned || resampling -> PLAN_FLOAT_DEPTH
        else -> 0
    }

    // ── mixer 请求率 ──
    // 只有「非独占调音」需要显式请求：请求我方输出的那个率，自洽、系统不再转。
    // 全链路不碰 mixer。
    val mixerTarget = when {
        decode == DecodeKind.nativeFullChain -> 0
        // 蓝牙的 mixer 参数由 codec 协商决定，请求无意义（`applyBitPerfectPassthrough`
        // 也会在路由层拒绝），直接算成"不请求"，避免状态里出现一个假的目标值
        route == AudioRoute.bluetooth -> 0
        tuned && sampleRate > 0 -> sampleRate
        else -> 0
    }

    // ── 落在哪一级（意图；证据在 `AudioOutputStatus` 那边合） ──
    val tier = when {
        // 透明 + AAudio 独占：这就是第二级，而且比"请求系统保持源规格"更硬
        // （独占流绕开 mixer，"保持源规格"只是请求）。
        //
        // 同样必须带 `effectivePath` —— 蓝牙下通路被强制成 systemMix，
        // 若还按"探测可用"报这一级，界面就会在蓝牙耳机上写「Bit Perfect 输出」，
        // 而实际数据走的是 mixer。**这一级是"发生了什么"，不是"能做到什么"**。
        !tuned && effectivePath == OutputPath.usbDirect -> OutputTier.bitPerfect
        !tuned && aaudioExclusive && effectivePath == OutputPath.aaudioExclusive ->
            OutputTier.bitPerfect
        // 透明 / 全链路：只有真的改了率才算「我方 SRC」
        sampleRate > 0 && sourceRate > 0 && sampleRate != sourceRate -> OutputTier.ownSrc
        // 全链路的目标率来自 native，源率在这里不等于解码率 —— 由调用方按真实输出率复核
        decode == DecodeKind.nativeFullChain && sampleRate > 0 -> OutputTier.ownSrc
        else -> null
    }

    // ── 档位没生效（UI 据此提示，不静默） ──
    //
    // 已经没有"用户选的档位"了，`platformRejected` 这一支随之消失 ——
    // 目标率现在恒为「跟随设备」算出来的 `autoRate`，它是本机自己的值，
    // 不存在"用户要求了但平台不认"的情形。
    // 剩下的 `deviceNotDeclared` 仍然有意义：自动算出的率可能没被设备 port 声明过
    // （HAL 能建、profile 没写），那是**事实**，该告诉用户。
    val fallback = when {
        // 透明模式不读档位，没有"请求没生效"这回事（目标率是我们自己算的）
        !tuned -> RateFallback.none
        acceptedRate > 0 && deviceRates.isNotEmpty() &&
            acceptedRate !in deviceRates && acceptedRate != nativeRate ->
            RateFallback.deviceNotDeclared
        else -> RateFallback.none
    }

    return OutputPlan(
        mode = mode,
        decode = decode,
        path = effectivePath,
        sourceRate = sourceRate,
        sourceDepth = sourceDepth,
        sampleRate = sampleRate,
        bitDepth = bitDepth,
        mixerTarget = mixerTarget,
        fallback = fallback,
        tier = tier,
        dsdTargetRate = dsdTargetRate,
    )
}

/**
 * 系统 mixer **实际**会用的采样率 —— 「替他转」时的目标。
 *
 * 与 [autoRateFor] 的区别在语义，不在实现细节：
 * - [autoRateFor] 是**用户档位**（「跟随设备」那一档显示什么），USB 取声明最高率；
 * - 本函数是**对系统行为的推断**，USB 取「与源率最接近的声明档」，因为
 *   外接 DAC 最常见的协商结果就是源率本身（44.1 / 48 基本都被声明），
 *   此时根本不该转；剩下的情况取最近一档，避免 44.1k 被无意义地升到 192k。
 *
 * 拿不到（蓝牙读不到协商率、USB 没有声明）返回 0 —— 不猜，让系统自己决定。
 */
internal fun systemMixRateFor(
    route: AudioRoute,
    nativeRate: Int,
    deviceRates: List<Int>,
    codecRate: Int = 0,
    sourceRate: Int = 0,
): Int = when (route) {
    // 蓝牙：a2dp 输出线程跟随协商率（实测），且只接受设备声明过的值
    AudioRoute.bluetooth -> codecRate.takeIf {
        it > 0 && (deviceRates.isEmpty() || it in deviceRates)
    } ?: 0
    // USB：和有线/扬声器**同属 primary mixer**。
    //
    // port profile 里的 96k/192k 只说明 DAC 能吃什么，不代表 AudioFlinger 会为它切
    // output thread —— 本机实测输出线程恒为 48000（`dumpsys media.audio_flinger`），
    // 与插什么无关。以前这里取"与源率最接近的声明档"，于是 96k 的歌被原样交出去，
    // mixer 在 48k 上再转一次：**只转一次的活变成了两次**，而第二次的质量不如 r8brain。
    // 目标率一律取 mixer 实际率，那一转由我方做。
    AudioRoute.usb -> {
        if( nativeRate > 0 )
        {
            nativeRate
        }
        else
        {
            deviceRates.filter { it in MIN_PLAN_RATE..MAX_PLAN_RATE }
                .minByOrNull { kotlin.math.abs( it - ( sourceRate.takeIf { r -> r > 0 } ?: nativeRate )) }
                ?: 0
        }
    }
    // 有线 / 扬声器 / 认不出的路由：同一条 primary mixer。
    //
    // `PROPERTY_OUTPUT_SAMPLE_RATE` 读不到时**不能返回 0** —— 那会让 `sampleRate = 0`，
    // 结果所有采样率都"我方不转"，把 SRC 悄悄交给系统（听感上就是"只有某个率动了"）。
    // 退到声明档里最高的一档：宁可转错一档，也不要把这一转藏起来。
    else -> if( nativeRate > 0 )
    {
        nativeRate
    }
    else
    {
        deviceRates.filter { it in MIN_PLAN_RATE..MAX_PLAN_RATE }.maxOrNull() ?: 0
    }
}

/**
 * 跟随设备的自动率。
 *
 * - **蓝牙** → codec 协商率。实测（本机 + LHDC 48k）a2dp output thread 跟随 codec 协商率，
 *   所以「跟随设备」= 我方 SRC 到 codec 率 → mixer 不转、a2dp 不转，**省掉 codec 侧那次 SRC**。
 *   读不到协商率时返回 0（不猜，让系统自己决定）。
 * - USB → 与源率最接近的声明档（见下）
 * - 有线 / 扬声器 → 系统 primary output 率
 *
 * ## USB 曾经取「声明最高率」—— 已删除
 *
 * 旧语义是「跟随设备 = 设备能做到的最高率」，于是 44.1k 的曲目会被无条件升到
 * 96k / 192k / 384k。那条路是错的，理由与 [systemMixRateFor] 里写的一样：
 *
 * - **升采样不产生信息**：44.1k/16 是带限到 22.05k 的信号，插到 384k 的网格上带内一位不变。
 * - **它必须过一级 FIR**，就是我方那级 2% 过渡带、206 dB 的最小相位滤波器 ——
 *   18 ms 后置振铃会在每个瞬态上长一遍（见 `native-r8b-resampler.cpp` 的 `kTransBand`）。
 *   也就是说，这是唯一一种「转了却什么都没换来、只多一层振铃」的转换。
 * - 高率的 alt-setting 未必被声明（很多小尾巴只到 96k），192k/24 要 9.2 Mbps，
 *   full-speed USB 的 12 Mbps 余量很小 —— 升得越高，建流失败与 underrun 的机会越多。
 *
 * 唯一讲得通的理论收益是「把 DAC 内部的过采样滤波外包给我们」，但那要求证明我们的
 * 滤波器比 DAC 的好（本机无法验证），而代价是确定的。**不做这种交换。**
 *
 * 现在的判据与 [systemMixRateFor] 一致：源率被声明就用源率（那时一次转换都不做），
 * 否则取最近一档 —— 因为「最近」才是「少转一点」，而「最高」永远是「多转很多」。
 *
 * 与 [systemMixRateFor] 的唯一区别是**未知时给什么**：这里是「用户档位的取值来源」，
 * 拿不到设备声明就退到 primary 主率（`nativeRate`）；那边是对系统行为的推断，
 * 拿不到就返回 0（不猜）。
 */
internal fun autoRateFor(
    route: AudioRoute,
    nativeRate: Int,
    deviceRates: List<Int>,
    codecRate: Int = 0,
    sourceRate: Int = 0,
): Int = when (route) {
    AudioRoute.bluetooth -> {
        // 只接受设备声明过的率（避免送进一个 a2dp output 建不出来的值）
        val usable = codecRate.takeIf {
            it > 0 && (deviceRates.isEmpty() || it in deviceRates)
        }
        usable ?: 0
    }
    AudioRoute.usb -> {
        // 与 [systemMixRateFor] 同一条理由：走系统通路时 USB 也在 primary mixer 上跑，
        // 目标率取 mixer 实际率。拿 DAC 的声明档当目标是"以为它会切线程"的旧假设。
        if( nativeRate > 0 )
        {
            nativeRate
        }
        else
        {
            deviceRates.filter { it in MIN_PLAN_RATE..MAX_PLAN_RATE }.maxOrNull() ?: 0
        }
    }
    else -> nativeRate
}

/**
 * **设备这一级的位深由谁决定** —— 只服务"链路实况"的显示，不再参与 `OutputPlan`。
 *
 * （它以前是「跟随设备」那个位深档位的取值来源；位深下拉删掉后，这里只剩"如实上报
 * 耳机/链路到底几位"这一个职责。）
 *
 * - **蓝牙** → codec 协商位深（LHDC 24 / LDAC 32 / SBC / AAC 16）。
 *   为什么不能用 mixer 值：`AudioCapabilities` 只认识本机 mixer 的 PCM 编码
 *   （本机实测只报 16，而堆芯是 `PCM_24_BIT_PACKED`），对耳机侧的 codec 一无所知。
 *   拿它当蓝牙位深，界面就会显示 32 Bit float 而耳机实际收到 24 Bit。
 *   codec 读不到时（offload 机型 `getCodecStatus` 返回 NONE）才退回 mixer 值。
 * - 非蓝牙 → mixer / sink 的能力值（float 统一记成 32，与本应用 `32 = float` 的约定一致）。
 *
 * 拿不到返回 0（未知），界面不标注。
 */
internal fun resolveDeviceDepth(
    route: AudioRoute,
    codecDepth: Int,
    mixerDepth: Int,
    mixerFloat: Boolean,
): Int {
    val sinkDepth = if (mixerFloat) 32 else mixerDepth
    if (route != AudioRoute.bluetooth) return sinkDepth
    return codecDepth.takeIf { it > 0 } ?: sinkDepth
}

/**
 * 量化到整数位深之前要不要加抖动（TPDF）。
 *
 * ## 唯一判据：我们是不是真的在做**最后一次重量化**
 *
 * 抖动的作用是对抗"量化误差与信号相关"（听感是颗粒感、毛刺、低电平时发闷）。
 * 所以它只在**真的舍入**时才有意义：
 *
 * - **不重量化却抖** = 白抬噪声底；如果出口还是 16 Bit，`AudioMixer` 会在末级再抖一次
 *   （`ditherAndClamp`），那就是双重抖动，噪声再抬 3 dB —— 比不抖更差。
 * - **重量化却不抖** = 比完全不干预更差（误差与信号相关，从白噪声变成失真）。
 *
 * 因此判据被收敛成两个事实：`rounds`（这次会不会舍入）+ `outDepth`（舍到几位）。
 * 以前这里用的是"降位深 / 源是有损编码"两个代理指标 —— 后者是**猜**：
 * 有损源解出来确实是 float，但"有损 ⇒ 必须抖"并不成立，成立的是"要舍入 ⇒ 必须抖"。
 *
 * @param outDepth 我方要交付的编码位深（`32` = float，不量化 → 不抖；
 *   `24` 也在 16..23 之外 → 不抖，那个量化误差在 −144 dB 以下）
 * @param rounds 这一次是否真的会舍入：**重采样必然舍入**（滤波输出落在格点之间），
 *   改编码位深也必然舍入；两者都不是 = 直通，一个采样都不动
 */
internal fun shouldDither(outDepth: Int, rounds: Boolean): Boolean =
    rounds && outDepth in 16..23

/** 平台能不能建出这个采样率（一次 `AudioFormat` 校验，微秒级）。 */internal fun platformCanBuildRate(rate: Int): Boolean = runCatching {
    AudioFormat.Builder()
        .setSampleRate(rate)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
        .build()
}.isSuccess

internal const val MIN_PLAN_RATE: Int = 8_000

/**
 * `AudioFormat` 的采样率合法上限。
 *
 * AOSP 里叫 `AudioFormat.SAMPLE_RATE_HZ_MAX`，但它是 **@hide**，只能用数值 ——
 * 这个数字硬编码在 `framework.jar` 的 `AudioFormat.Builder.build()` 校验里：
 * 超过它连 `AudioFormat` 对象都构造不出来，更谈不上建轨。厂商想让设备跑 352.8k/384k，
 * 只能改这个常量（部分国产 DAP 确实改过）；改了之后我们的代码不用动就能用。
 */
internal const val AUDIO_FORMAT_MAX_RATE: Int = 192_000

/** 设备声明率的合法上限（再高就不是 AudioFormat 能表达的了）。 */
internal const val MAX_PLAN_RATE: Int = 768_000
