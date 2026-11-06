package com.annieplayer.annie_music.kt.core.audio

/**
 * 输出矩阵：**通路（唯一可选）+ 该通路的开关 + 派生结果**。
 *
 * ## 为什么要有这一层
 *
 * 以前用户要理解一个叫「模式」的枚举（透明 / 调音），而那个枚举实际上把三件正交的事
 * 挤在一起：要不要做 DSP、要不要尝试直通/独占、采样率由谁定。用户被迫先选一个抽象概念，
 * 再看它能推出什么 —— 顺序反了。
 *
 * 现在反过来：
 *
 * ```
 * 通路（系统 / AAudio / USB）         ← 用户选的就是"从哪出去"
 *   └─ 开关（按源采样率 / Bit Perfect）
 * 调音引擎（无 / 均衡器 / PEQ / MSEB）  ← 用户选的是"要不要 DSP"
 *   └─ 派生：要不要调音、采样率谁定
 * ```
 *
 * **"能不能调音"是派生结果，不是用户选项。** 这条是整次重构的核心。
 *
 * 两个来源的优先级：**引擎 = 无 一票否决**（与通路无关），否则看通路上那几个
 * 「要求原样送出」的开关。详见 [tuningEnabled]。
 *
 * ## 开关 = 意愿（持久），不随设备变
 *
 * 开关只表达"我想这样"，**不因为当前设备不支持就自动关掉**。所以换到支持的设备时
 * 它立刻生效，换回不支持的设备时它仍然开着（只是提示"当前不生效"）。
 * 频繁插拔不同设备的人因此只需要设置一次。
 */
data class OutputToggles(
    /**
     * 系统通路：用曲目**原始采样率**建 AudioTrack，不预先重采样。
     *
     * 成败取决于本机：系统若跟随源率（部分 DAP / 定制 ROM）就实现原样输出；
     * 若固定主率（多数手机）则由系统做一次重采样，**质量低于本应用的 r8brain**。
     * 所以默认关 —— 保守的一档永远是"我方转"，因为那次的算法在我们手里。
     *
     * **它只管采样率，不管调音**：打开后 EQ / PEQ / MSEB 照常生效（工作在源率上），
     * 关掉的只有我方那次 SRC。
     */
    val followSourceRate: Boolean = false,

    /**
     * AAudio 通路：原样送出（不调音、不改率）。
     *
     * 关掉它 = 允许调音，但数据仍走独占流 —— **绕开系统 mixer 的重采样**，
     * 这是系统通路做不到的。所以这个开关不是冗余，关掉它也有独特价值。
     */
    val bitPerfect: Boolean = true,
)

/**
 * 这一组设置下，DSP 链该不该启用 —— **唯一判据**。
 *
 * 所有"要不要调音"的地方都必须问它（设置页的显隐、引擎的 processor 开关、
 * 音量下发策略）。以前这个判断散在 `audioMode == tuned` 的十几处调用点里，
 * 每加一条通路就要改一遍。
 *
 * ## 两个来源，顺序不能反
 *
 * 1. **调音引擎 = 无** → 一律不调音，与通路无关。这是用户对"调音"本身的表态，
 *    优先级最高：它压过通路上那些开关（否则"我选了无，但因为没开直通，结果还在调音"）。
 * 2. 否则看通路：**只有要求"我方不要碰数据"的开关**才能关掉它
 *    （AAudio 的「Bit Perfect」）。
 *    「按源采样率」不许进来 —— 它约束的只是采样率，与调音正交（见下）。
 *
 * ## 蓝牙：通路开关全部让位，但「无」照样管用
 *
 * 蓝牙下链路参数由耳机 codec 决定，`effectivePath` 被强制成 systemMix，
 * 于是通路上那个开关**不起作用**（Bit Perfect 的独占被回退）。
 * 既然不起作用，就不该由它决定调音 —— 否则用户插上蓝牙耳机后，
 * 会看到一个自己从没动过、也毫无意义的开关把均衡器关掉了。
 *
 * 「无」不在此列：它不依赖任何通路参数，蓝牙下同样是「我不调音」。
 *
 * **这不等于"一定改数据"**：DSP 要不要动手由 `TuningAudioProcessor.isActive()` 自己判 ——
 * EQ / PEQ / MSEB 全平、前级 0、限幅关时它根本不进链路，数据一个字节不改。
 * 换句话说，"启用 DSP" 是"允许处理"，不是"处理"。
 *
 * ## 两个问题，不许混成一句
 *
 * 这个函数回答的是**"这一次要不要真的跑 DSP"**，不是"用户能不能选调音"。
 * 后者是配置问题，永远可以（引擎、EQ 参数、开关照常可改可存）；前者是运行时问题。
 * 所以 [rawDsd] 只有**音频路径**传，**设置页 / 显隐那个调用点不传**（默认 false）——
 * 否则用户放着 DSD 会发现调音面板整个消失，而不是"处理器暂时不参与"。
 */
fun tuningEnabled(
    path: OutputPath,
    toggles: OutputToggles,
    bluetooth: Boolean = false,
    /**
     * 当前调的引擎。**调用方必须传真实值** —— 默认值只是为了让老调用点与测试
     * 不至于一次性全红；新写的调用点漏传，就会出现"选了『无』却仍在调音"。
     */
    engine: TuningEngine = TuningEngine.graphic,
    /**
     * 这一首是不是 **DSD 直出**（DoP / Native，送的是 1-bit 位流）。
     *
     * 这条压过一切：DSP 处理的输入是 PCM 样本，而这条通路上根本没有 PCM 样本 ——
     * 24 bit 帧里装的是 16 个 1-bit 采样、32 bit word 里装的是 32 个 1-bit 采样。
     * 任何滤波、增益、重采样都会连同 DoP 的 `0x05`/`0xFA` 标记一起改掉，
     * 设备立刻退回当普通 PCM 解 —— 那是满幅噪声，不是"音质变差"。
     *
     * 所以这里不去"尽力处理"，而是**明确判为不调音**：DSP 不配置、音量交设备与系统，
     * 界面也不该继续显示「调音」。
     */
    rawDsd: Boolean = false,
): Boolean {
    // 直出 1-bit：没有可处理的对象，任何处理都是破坏
    if (rawDsd) return false
    // 「无」是最高优先级：它与通路、与任何通路开关都无关
    if (engine == TuningEngine.none) return false
    if (bluetooth) return true
    return when (path) {
        // 系统通路：调音**总是允许**。
        //
        // 以前这里会被「直通解码」那个开关关掉（直通要求压缩帧一个字节都不碰），
        // 那条功能已整条删除，于是这里没有"要不要问开关"的问题了 ——
        // 系统通路上能不能调音，只由上面的「引擎 = 无」与 `TuningAudioProcessor.isActive()`
        // 决定。
        //
        // 「按源采样率」不在此列 —— 它约束的**只是采样率**（我方不预转换），
        // 与调音正交：PEQ 在源率上照样工作（滤波器系数本来就按输入率算）。
        // 曾经把两者写在一起，等于"开了按源率就把均衡器一起没收"，
        // 而用户想要的是「原率 + 我调过的音」，两者本来可以同时成立。
        OutputPath.systemMix -> true
        // 与 AAudio 独占同义：Bit Perfect = 一个采样都不动
        OutputPath.aaudioExclusive -> !toggles.bitPerfect
        OutputPath.usbDirect -> !toggles.bitPerfect
    }
}

/**
 * 派生出引擎内部用的 [AudioOutputMode]。
 *
 * **`AudioOutputMode` 并没有被删掉** —— 它表述的是"这一首有没有被 DSP 改过"
 * 这个客观事实，`resolveOutputPlan` 与渲染器需要它。只是**用户不再直接选它**，
 * 而是由「引擎 + 通路 + 开关」推出来。这样引擎侧三十多处 `mode == tuned` 的判断一行都不用动。
 */
fun audioModeOf(
    path: OutputPath,
    toggles: OutputToggles,
    bluetooth: Boolean = false,
    engine: TuningEngine = TuningEngine.graphic,
): AudioOutputMode =
    if (tuningEnabled(path, toggles, bluetooth, engine)) {
        AudioOutputMode.tuned
    } else {
        AudioOutputMode.transparent
    }

/**
 * 「按源采样率输出」此刻是否生效 —— **唯一判据**。
 *
 * 两处要用它，必须一致：
 * - `resolveOutputPlan`：生效时 `sampleRate = 0`（我方不预转换）；
 * - 设置页：用它决定副标题怎么描述（生效时那次转换落在系统侧）。
 *
 * **只排除非系统通路**：它约束的是系统通路那条"要不要预转换"的轴，
 * AAudio / USB 有自己的规则（见各自的开关），别拿这个去改它们。
 *
 * ## 蓝牙下现在**也生效**（这条改过，理由变了）
 *
 * 以前这里排除蓝牙，理由写的是"a2dp 线程的率由 codec 协商决定，按源率建轨
 * 等于把 44.1→48 从我方 r8brain 推给 AudioFlinger，纯亏"。
 *
 * 用户实测推翻了那个取舍：在那台设备上，**交给系统重采样反而没有那层沙状底噪**
 * （详见 `native-r8b-resampler.cpp` 里 `kTransBand` 的整段记录）。
 * 也就是说"纯亏"这个判断只在指标层面成立，听感层面是反的。
 * 既然按源率在蓝牙下是一个**用户真的会想用**的选项，就不该由我们替他禁掉 ——
 * 显示的开关和实际行为必须一致，否则用户会以为它坏了。
 *
 * 设备侧仍然不做任何判断 —— 源率会不会被系统跟随，应用层**测不到**
 * （见下面那段"刻意没有 sourceRateIsSafe"）。
 */
fun followSourceRateActive(
    path: OutputPath,
    followSourceRate: Boolean,
): Boolean = path == OutputPath.systemMix && followSourceRate

/*
 * ⚠️ 这里**刻意没有**"源率能不能安全原样送出"的判定函数（曾经有一个 `sourceRateIsSafe`）。
 *
 * 试过的判据是 `源率 == PROPERTY_OUTPUT_SAMPLE_RATE`，但它会**误伤 DAP**：
 * 那个属性是 `AudioManager` 的静态值（primary output 的默认率），不是此刻实际率，
 * 做了动态跟随的设备（飞傲官方明确说第三方 App 也不 SRC）照样报 48000 ——
 * 智能判据会把"系统本来能原样"判成"会被转"，替系统白转一遍。
 *
 * 而 App 层**事后测不到**系统是否 SRC：`AudioTrack.getTimestamp().framePosition`
 * 按 track 自己的率计帧，与硬件实际率无关。所以"先试再兜底"也不成立。
 *
 * **结论：不做替用户保守决定的逻辑。** 开关打开就按源率建轨，
 * 风险（系统若重采样，质量低于本应用）由文案说明，选择权归用户。
 * 凡是"替用户判断设备能力"的智能逻辑，都会误伤一部分设备，而用户无法绕过。
 */

/**
 * 老存档迁移：把「透明 / 调音」翻译成「通路 + 开关」。
 *
 * 原则是**升级后行为不变**（用户不该发现音质或功能变了）：
 * - `transparent` / `tuned` 都落到系统通路；调音与否由**引擎**决定
 *   （透明 → 引擎「无」；调音 → 引擎 `graphic`）。
 *
 * **引擎固定迁成 `graphic`**（`fromKey(null)` 的默认值）：
 * 老存档里没有"引擎"这个概念，`graphic` 是最保守的那一档（参数全平 = 不改数据）。
 *
 * 老的 `outputPath = bitPerfectMixer` **不迁移**：那条路需要 `MODIFY_AUDIO_ROUTING`，
 * 普通安装从来没用上过，迁移过来只会让用户以为自己选了独占。
 */
fun migrateLegacySettings(
    legacyModeKey: String?,
    legacyPathKey: String?,
): Pair<OutputPath, OutputToggles> {
    // 只认"已实现"的通路：老的 `bit-perfect`（bitPerfectMixer）直接落到系统通路 ——
    // 那条路需要系统级权限，普通安装从来没生效过，迁过来只会让人以为自己选了独占。
    val path = OutputPath.entries
        .firstOrNull { it.key == legacyPathKey && it.implemented }
        ?: OutputPath.systemMix
    return path to OutputToggles(
        followSourceRate = false, // 这是新开关，老用户没有这个概念，取保守值
        bitPerfect = true,
    )
}
