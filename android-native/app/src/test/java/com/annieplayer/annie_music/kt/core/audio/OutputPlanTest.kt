package com.annieplayer.annie_music.kt.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `OutputPlan` 的规则测试。
 *
 * 这些用例锁住的是**四轴语义**，不是实现细节：
 * - 透明模式永不改数据（不 SRC、不改位深），且 mixer 请求取源率；
 * - 调音模式的 mixer 请求取我方输出率（自洽）；
 * - offload / 全链路不碰 mixer；蓝牙强制 systemMix 且不请求；
 * - 平台建不出的档位要退回自动率并标注 `platformRejected`。
 *
 * 全部是纯 JVM 用例：`canBuildRate` 由测试注入，不碰 `android.media.AudioFormat`。
 */
class OutputPlanTest {

    private val anyRate: (Int) -> Boolean = { true }

    private fun plan(
        mode: AudioOutputMode = AudioOutputMode.transparent,
        route: AudioRoute = AudioRoute.wired,
        nativeRate: Int = 48_000,
        deviceRates: List<Int> = listOf(44_100, 48_000, 96_000),
        codecRate: Int = 0,
        sourceRate: Int = 44_100,
        sourceDepth: Int = 16,
        /** 解码出来的率（全链路时是 native 回报值）；0 = 未知 → 按 [sourceRate] */
        decodedRate: Int = 0,
        /** 源率能否拿到 AAudio 独占（透明模式第二级）；决定要不要重采样 */
        aaudioExclusive: Boolean = false,
        /** 用户开了「按源采样率输出」（意愿，只在系统通路生效，且不关调音） */
        followSourceRate: Boolean = false,
        fullChain: Boolean = false,
        // 默认用 systemMix：这一组用例验的是「率/位深怎么算」，
        // 而 Bit-Perfect 的语义是**原样送出**（不 SRC、不改位深），会把这些断言全盖掉。
        // Bit-Perfect 的规则由专门用例 `tuned bit perfect sends things out as-is` 钉住。
        path: OutputPath = OutputPath.systemMix,
        /** 是否值得请求 bit-perfect（路由 + 权限）；false = 连请求都不发 */
        bitPerfectAttemptable: Boolean = false,
        /** 请求是否被系统接受 */
        bitPerfectGranted: Boolean = false,
        /** DSD 的最终 PCM 目标率（44.1 家族）；0 = 不是 DSD */
        dsdTargetRate: Int = 0,
        canBuild: (Int) -> Boolean = anyRate,
    ): OutputPlan = resolveOutputPlan(
        mode = mode,
        route = route,
        nativeRate = nativeRate,
        deviceRates = deviceRates,
        codecRate = codecRate,
        sourceRate = sourceRate,
        sourceDepth = sourceDepth,
        decodedRate = decodedRate,
        aaudioExclusive = aaudioExclusive,
        followSourceRate = followSourceRate,
        fullChain = fullChain,
        path = path,
        bitPerfectAttemptable = bitPerfectAttemptable,
        bitPerfectGranted = bitPerfectGranted,
        dsdTargetRate = dsdTargetRate,
        canBuildRate = canBuild,
    )

    // ── 轴 1：透明 = 不改数据 ──

    @Test
    fun `非独占透明模式由我方转到系统率 而不是去读档位`() {
        // 透明模式只剩一条策略（非独占降级链），采样率没有用户控件，
        // 目标是"系统实际会用的率"（扬声器/有线 = native）。
        val p = plan(mode = AudioOutputMode.transparent)
        assertEquals(48_000, p.sampleRate)
        assertEquals(OutputTier.ownSrc, p.tier)
        assertTrue(p.didResample)
        // 不请求 mixer：`setPreferredMixerAttributes` 需要系统级权限，普通安装必失败。
        // 请求一个注定失败的规格，只会让状态里出现一个假的目标值。
        assertEquals(0, p.mixerTarget)
        assertFalse(p.isPassthrough)
    }

    @Test
    fun `源率本来就等于系统率时什么都不写`() {
        // 48k 源 + 48k 系统率 → 一次转换都没有。按"谁生效了就用谁"，tier 留空。
        val p = plan(mode = AudioOutputMode.transparent, sourceRate = 48_000)
        assertEquals(48_000, p.sampleRate)
        assertFalse(p.didResample)
        assertNull(p.tier)
    }

    @Test
    fun `透明模式重采样时交出 float 全精度`() {
        // 位深已经没有用户选项了（下拉已删），判据只剩一条：我方**真的在转**。
        // 在转就把 r8brain 的 double 结果全精度交出去，让 sink 做最后一次量化 ——
        // 写 24 Bit 整数等于我们替 sink 量化一次，还会让 mixer 在 16 Bit 出口再抖一次。
        val p = plan(mode = AudioOutputMode.transparent)
        assertEquals(PLAN_FLOAT_DEPTH, p.bitDepth)
        assertEquals(32, p.deliveredDepth)
        // int → float 只是换容器（24 Bit 进 float32 精确），不算位深转换
        assertFalse(p.didConvertDepth)
    }

    @Test
    fun `透明模式不重采样时一位都不碰`() {
        // 源率本来就等于系统率 → 没有 SRC，也没有重量化。此时 16→float→int24 的上转
        // 由 Media3/mixer 完成且精确，我们插一手只会多一个出错的地方。
        val p = plan(mode = AudioOutputMode.transparent, sourceRate = 48_000)
        assertEquals(0, p.bitDepth)
        assertEquals(16, p.deliveredDepth)
        // 率也一样：目标率恰好等于源率，我们什么都没改（`didResample` 是判据，
        // 不是 `sampleRate == 0` —— 那是「不指定目标率」的意思）
        assertFalse(p.didResample)
    }

    @Test
    fun `全链路按解码率判断要不要接管位深`() {
        // native 已经吐出目标率 → 我们没有转 → 不接管位深（一个采样都不动）
        val same = plan(
            mode = AudioOutputMode.transparent,
            sourceRate = 176_400,
            decodedRate = 176_400,
            nativeRate = 176_400,
            deviceRates = listOf(176_400),
        )
        assertEquals(0, same.bitDepth)
        assertFalse(same.didResample)
    }

    @Test
    fun `DSD 走自己的目标率 位深交 float 且如实报我方 SRC`() {
        // 新模型：native 只把 1-bit 解成 352800 float（`keepSourceRate`），
        // 2:1 抽取由我方 r8brain 做 —— 不再是 libswresample 的 32 抽头 / 线性相位。
        //
        // 所以 `dsdTargetRate` 必须**压过整条梯子**：任何一条"原样送出"的分支
        // （按源率 / 独占 / bit-perfect）都会把 352800 塞给 AudioTrack，
        // 而 `AudioFormat` 的合法上限是 192000 → 直接建轨失败。
        val p = plan(
            mode = AudioOutputMode.transparent,
            sourceRate = 352_800,       // dsddec 的中间格式（2_822_400 / 8）
            decodedRate = 352_800,
            nativeRate = 48_000,
            deviceRates = listOf(44_100, 48_000),
            dsdTargetRate = 176_400,
        )
        assertEquals(176_400, p.sampleRate)
        assertEquals(176_400, p.dsdTargetRate)
        // 我方真的在转 → 交 float 全精度，末级量化留给下游那一次
        assertEquals(PLAN_FLOAT_DEPTH, p.bitDepth)
        assertTrue(p.didResample)
    }

    @Test
    fun `DSD 目标率压过按源率与独占`() {
        // 按源率打开：对 DSD 必须失效（源率是 352800，不是"能原样送出的率"）
        val follow = plan(
            mode = AudioOutputMode.transparent,
            sourceRate = 352_800,
            decodedRate = 352_800,
            nativeRate = 48_000,
            followSourceRate = true,
            dsdTargetRate = 176_400,
        )
        assertEquals(176_400, follow.sampleRate)

        // AAudio 独占可用：同理，独占流按 352800 开不出来
        val exclusive = plan(
            mode = AudioOutputMode.transparent,
            path = OutputPath.aaudioExclusive,
            aaudioExclusive = true,
            sourceRate = 352_800,
            decodedRate = 352_800,
            nativeRate = 48_000,
            dsdTargetRate = 176_400,
        )
        assertEquals(176_400, exclusive.sampleRate)
    }

    @Test
    fun `DSD 源率超过 AudioFormat 上限时 不许把源率当目标`() {
        // 回归：没有 dsdTargetRate 时会落到 `systemRate`（48k）—— 那是"把 DSD 白转一遍"，
        // 而源率 352800 本身又建不出轨。两条都是错的，所以 DSD 必须自带目标率。
        val withoutTarget = plan(
            mode = AudioOutputMode.transparent,
            sourceRate = 352_800,
            decodedRate = 352_800,
            nativeRate = 48_000,
        )
        assertEquals(48_000, withoutTarget.sampleRate)
        assertEquals(0, withoutTarget.dsdTargetRate)
    }

    @Test
    fun `透明模式拿到 AAudio 独占时不重采样 而是原样送出`() {
        // 这条是"独占了个寂寞"的回归用例：以前 plan 不看独占可用性，
        // 透明模式一律转到 systemRate（44.1→48），于是 44.1 的数据先被我们转成 48
        // 再进独占流 —— 独占等于白做，tier 也如实报成「我方 SRC」。
        //
        // ⚠️ 必须显式传 `path`：新判据要求"独占真的在用"，只看"探测可用"不够 ——
        // 蓝牙下通路会被强制成 systemMix，那时探测照样可用但独占没被使用。
        val p = plan(
            mode = AudioOutputMode.transparent,
            path = OutputPath.aaudioExclusive,
            sourceRate = 44_100,
            nativeRate = 48_000,
            deviceRates = listOf(44_100, 48_000),
            aaudioExclusive = true,
        )
        assertEquals(0, p.sampleRate)              // 不转，交给 AAudio 以源率开流
        assertEquals(OutputTier.bitPerfect, p.tier)
        assertFalse(p.didResample)
        // 不转就没有重量化：处理器不参与，位深保持"不动"
        assertEquals(0, p.bitDepth)
    }

    @Test
    fun `透明模式拿不到独占时才落到我方 SRC`() {
        val p = plan(
            mode = AudioOutputMode.transparent,
            sourceRate = 44_100,
            nativeRate = 48_000,
            deviceRates = listOf(44_100, 48_000),
            aaudioExclusive = false,
        )
        assertEquals(48_000, p.sampleRate)
        assertEquals(OutputTier.ownSrc, p.tier)
    }

    @Test
    fun `调音模式不受 AAudio 独占影响`() {
        // 独占只服务透明模式；调音模式的采样率只有「跟随设备」一个来源，不能被独占"接管"掉
        val p = plan(
            mode = AudioOutputMode.tuned,
            nativeRate = 96_000,
            sourceRate = 44_100,
            aaudioExclusive = true,
        )
        assertEquals(96_000, p.sampleRate)
    }

    /**
     * **蓝牙也交 float** —— 这条钉住一个被实测否决的方案。
     *
     * 曾经改成"蓝牙交链路真实位深（24 Bit 整数）"，理由是 a2dp 输出线程是整数的
     * `PCM_24_BIT_PACKED`，float 进去要被 AudioFlinger 再转一次。实测把它推翻了：
     *
     * 1. Media3 1.4.1 的 `setAudioCapabilities` 已废弃且**实测不生效**，
     *    `DefaultAudioSink` 用自己的默认能力（只报 PCM_16）。于是关掉 float 之后
     *    24 被插一层 `ToInt16` —— `audio_flinger` 实测 track 从 `0x5`(FLOAT)
     *    变成 `0x1`(PCM_16)，白掉 8 位。
     * 2. AudioFlinger 内部本来就是 float 混合、出口才 `ditherAndClamp` 到 24 Bit，
     *    所以交 float 零额外损失，交整数反而多一次 int→float 再 clamp。
     *
     * 结论：位深的真实终点由下游（mixer / HAL / codec）决定，我方只保证**不提前量化**。
     */
    @Test
    fun `蓝牙也交 float`() {
        val p = plan(
            route = AudioRoute.bluetooth,
            codecRate = 48_000,
            deviceRates = listOf(44_100, 48_000, 96_000),
            sourceRate = 44_100, // 需要我方 44.1→48，所以必须接管位深
            sourceDepth = 24,
        )
        assertEquals(48_000, p.sampleRate)
        assertEquals(PLAN_FLOAT_DEPTH, p.bitDepth)
        // 只换容器，不算位深转换
        assertFalse(p.didConvertDepth)
    }

    /** 我方不碰数据时不接管位深（上转交给 Media3/mixer，那是精确的） */
    @Test
    fun `什么都不做的时候不接管位深`() {
        val p = plan(
            mode = AudioOutputMode.transparent,
            route = AudioRoute.bluetooth,
            codecRate = 48_000,
            deviceRates = listOf(44_100, 48_000),
            sourceRate = 48_000,
        )
        assertEquals(0, p.bitDepth)
    }

    // ── 轴 4：调音 = 允许改数据，且 mixer 请求自洽 ──

    @Test
    fun `tuned resamples to the device rate and requests the same mixer rate`() {
        // 「自选采样率」删除后，调音模式的目标率只有一个来源：跟随设备（= native）。
        val p = plan(mode = AudioOutputMode.tuned, nativeRate = 96_000)
        assertEquals(96_000, p.sampleRate)
        assertTrue(p.didResample)
        // 关键：不再出现「我方 96k、却请求 44.1k」的矛盾
        assertEquals(p.sampleRate, p.mixerTarget)
    }

    @Test
    fun `tuned follow-source keeps the source rate`() {
        val p = plan(mode = AudioOutputMode.tuned, followSourceRate = true)
        assertEquals(0, p.sampleRate)
        assertNull(p.tier)
        // 用户开「按源采样率输出」= 他不想要我方转换。此时不请求 mixer（请求必失败），
        // 那一次转换就落在系统 mixer 上 —— 规格里会写成 `44.1→48 kHz`，看得见。
        assertEquals(0, p.mixerTarget)
    }

    @Test
    fun `tuned follow-device uses the auto rate`() {
        val p = plan(
            mode = AudioOutputMode.tuned,
            route = AudioRoute.wired,
            nativeRate = 48_000,
        )
        assertEquals(48_000, p.sampleRate)
        assertEquals(48_000, p.mixerTarget)
    }

    @Test
    fun `调音模式下我方永远只换容器 不重量化位深`() {
        // 位深下拉删掉之后，调音模式的落脚点只有一个：交 float 全精度。
        // 判据不是"用户选了几位"，而是"我方碰过数据、末级量化该由 sink 做"。
        // 所以 `didConvertDepth` 恒为 false —— 我们只换容器，从不量化。
        val p = plan(mode = AudioOutputMode.tuned)
        assertEquals(PLAN_FLOAT_DEPTH, p.bitDepth)
        assertEquals(32, p.deliveredDepth)
        assertFalse(p.didConvertDepth)
    }

    // ── 轴 2：解码 ──
    //
    // 「offload（不解码直通）」那一整组用例（4 条）已删除 —— 那个 `DecodeKind` 值
    // 与它的 `directPossible` / `blockReason` 一起从模型里拿掉了。
    // 现在解码轴只有两种，锁一条就够：

    @Test
    fun `解码轴只剩软解与全链路`() {
        assertEquals(DecodeKind.ffmpegSoft, plan().decode)
        assertEquals(DecodeKind.nativeFullChain, plan(fullChain = true).decode)
        // 枚举里不该再有第三个值（这条防的是"哪天又加回一条不解码的路"）
        assertEquals(2, DecodeKind.entries.size)
    }

    @Test
    fun `full chain skips the mixer request but still resamples`() {
        val p = plan(fullChain = true, mode = AudioOutputMode.tuned, nativeRate = 96_000)
        assertEquals(DecodeKind.nativeFullChain, p.decode)
        assertEquals(96_000, p.sampleRate)
        assertEquals(0, p.mixerTarget)
    }

    // ── 轴 3：通路 ──

    @Test
    fun `bluetooth forces system mix and no mixer request`() {
        // 蓝牙下即使选了 AAudio 独占，也要被强制回系统通路（链路参数由 codec 决定）
        val p = plan(route = AudioRoute.bluetooth, path = OutputPath.aaudioExclusive)
        assertEquals(OutputPath.systemMix, p.path)
        assertEquals(0, p.mixerTarget)
    }

    @Test
    fun `system mix is kept as chosen`() {
        val p = plan(path = OutputPath.systemMix)
        assertEquals(OutputPath.systemMix, p.path)
    }

    // ── 自动率 ──

    @Test
    fun `auto rate follows the route`() {
        // USB 的「跟随设备」**不再取声明最高率**（那条语义已删）。没有源率可参考时
        // 退到 primary 主率 —— 与有线/扬声器同一条兜底，不再"默认升到最高档"。
        assertEquals(48_000, autoRateFor(AudioRoute.usb, 48_000, listOf(44_100, 48_000, 96_000)))
        assertEquals(48_000, autoRateFor(AudioRoute.wired, 48_000, emptyList()))
        assertEquals(44_100, autoRateFor(AudioRoute.usb, 44_100, emptyList()))
    }

    @Test
    fun `usb 自动率取 mixer 实际率 不跟着声明档走`() {
        val declared = listOf(44_100, 48_000, 96_000, 192_000)
        // 旧的期望是"源率被声明就用源率"。那是建立在"AudioFlinger 会为 USB 切 output
        // thread"这个假设上的，实测不成立：`dumpsys media.audio_flinger` 的输出线程
        // 恒为 48000，插什么 DAC 都一样。按声明档选会把 96k 原样交出去让 mixer 再转。
        assertEquals(
            48_000,
            autoRateFor(AudioRoute.usb, 48_000, declared, sourceRate = 44_100),
        )
        // 声明档里没有源率时同理：目标仍是 mixer 实际率，不是"最近一档"。
        assertEquals(
            48_000,
            autoRateFor(AudioRoute.usb, 48_000, listOf(96_000, 192_000), sourceRate = 44_100),
        )
        // 源率高于全部声明档也照样取 mixer 率 —— 我方降采样，而不是送给 mixer 再转。
        assertEquals(
            48_000,
            autoRateFor(AudioRoute.usb, 48_000, listOf(48_000, 96_000, 192_000), sourceRate = 384_000),
        )
        // 拿不到 mixer 率时才退回声明档（这是兜底，不是判据）。
        assertEquals(
            192_000,
            autoRateFor(AudioRoute.usb, 0, listOf(48_000, 96_000, 192_000), sourceRate = 48_000),
        )
    }

    @Test
    fun `bluetooth auto rate uses the negotiated codec rate`() {
        // 没读到协商率 → 0（不猜）
        assertEquals(0, autoRateFor(AudioRoute.bluetooth, 48_000, listOf(48_000)))
        // 读到了 → 用 codec 率
        assertEquals(
            48_000,
            autoRateFor(AudioRoute.bluetooth, 48_000, listOf(48_000), codecRate = 48_000),
        )
        assertEquals(
            96_000,
            autoRateFor(AudioRoute.bluetooth, 48_000, listOf(48_000, 96_000), codecRate = 96_000),
        )
        // 协商率不在设备声明里 → 0（不把建不出来的值塞给 AudioTrack）
        assertEquals(
            0,
            autoRateFor(AudioRoute.bluetooth, 48_000, listOf(48_000), codecRate = 192_000),
        )
    }

    @Test
    fun `bluetooth follow-device resamples to the codec rate`() {
        val p = plan(
            mode = AudioOutputMode.tuned,
            route = AudioRoute.bluetooth,
            codecRate = 48_000,
            deviceRates = listOf(44_100, 48_000, 96_000),
        )
        assertEquals(48_000, p.sampleRate)
        assertTrue(p.didResample)
        // 蓝牙不请求 mixer（末端是 a2dp 编码器，不由 mixer 决定）
        assertEquals(0, p.mixerTarget)
    }

    @Test
    fun `bluetooth follow-source keeps the source rate`() {
        val p = plan(
            mode = AudioOutputMode.tuned,
            route = AudioRoute.bluetooth,
            followSourceRate = true,
            codecRate = 48_000,
        )
        assertEquals(0, p.sampleRate)
        assertFalse(p.didResample)
    }

    @Test
    fun `unknown source rate does not fabricate a resample`() {
        val p = plan(mode = AudioOutputMode.tuned, nativeRate = 96_000, sourceRate = 0)
        assertEquals(96_000, p.sampleRate)
        // 源率未知时不能声称"发生了重采样"（那会是一个编造的事实）
        assertFalse(p.didResample)
    }

    // ── AAudio 独占：原样送出，不许偷偷改数据 ──────────────────────────

    @Test
    fun `AAudio 独占成立时目标率为 0（原样送出）`() {
        val p = plan(path = OutputPath.aaudioExclusive, aaudioExclusive = true)
        assertEquals(0, p.sampleRate)
        assertEquals(OutputTier.bitPerfect, p.tier)
    }

    @Test
    fun `AAudio 独占不成立时退回系统率`() {
        val p = plan(path = OutputPath.aaudioExclusive, aaudioExclusive = false)
        assertEquals(48_000, p.sampleRate)
        assertTrue(p.tier != OutputTier.bitPerfect)
    }

    // ── 按源采样率输出：不做预转换，成败交给系统 ──────────────────────────

    /**
     * 开关打开 → **一律按源率送出**（`sampleRate = 0`），不看系统主率。
     *
     * 曾经加过"源率 == 主率才生效"的智能判据，但它会误伤 DAP：
     * `PROPERTY_OUTPUT_SAMPLE_RATE` 是静态属性，做了动态跟随的设备照样报 48000，
     * 于是"系统本来能原样"被判成"会被转"，替系统白转一遍。
     * 而 App 层事后测不到系统是否 SRC，所以"先试再兜底"也不成立。
     * 结论：把选择权和风险一起交给用户。
     */
    @Test
    fun `按源率输出 一律不预转换`() {
        val p = plan(
            followSourceRate = true,
            nativeRate = 48_000,
            sourceRate = 44_100,
        )
        assertEquals(0, p.sampleRate)
        assertFalse(p.didResample)
    }

    @Test
    fun `按源率输出 主率与源率一致时同样不预转换`() {
        val p = plan(
            followSourceRate = true,
            nativeRate = 44_100,
            sourceRate = 44_100,
        )
        assertEquals(0, p.sampleRate)
        assertFalse(p.didResample)
    }

    /** 源率未知时不能声称"按源率送出"（那等于凭空造一个规格） */
    @Test
    fun `按源率输出 源率未知时不生效`() {
        val p = plan(
            followSourceRate = true,
            nativeRate = 48_000,
            sourceRate = 0,
        )
        assertEquals(48_000, p.sampleRate) // 落回 systemRate
    }

    /**
     * **调音 + 按源率可以同时成立** —— 这两件事正交。
     *
     * PEQ 的滤波器系数本来就按输入率算，工作在源率上完全有效；
     * 「按源率」禁止的是**我方预转换**，不是禁止我方处理数据。
     * 以前 `tuningEnabled` 把两者写在一起，等于"想要原率就得先放弃均衡器"。
     */
    @Test
    fun `调音与按源率可以同时成立`() {
        val p = plan(
            mode = AudioOutputMode.tuned,
            followSourceRate = true,
            nativeRate = 48_000,
            sourceRate = 44_100,
        )
        assertEquals(0, p.sampleRate)
        assertFalse(p.didResample)
        // DSP 仍在跑（调音），位深照样接管 → 交 float 全精度给 sink 量化
        assertEquals(PLAN_FLOAT_DEPTH, p.bitDepth)
        // sampleRate = 0 → 不请求 mixer；那一次转换（若有）落在系统侧，看得见
        assertEquals(0, p.mixerTarget)
    }

    /**
     * 「按源采样率」是**系统通路的开关**，别的通路上不生效。
     *
     * 开关是持久的意愿（换设备、换通路都不重置），所以用户可能带着它切到 AAudio 通路；
     * 那时它必须让位给该通路自己的规则，否则会凭空取消用户在那个通路上选的档位。
     */
    @Test
    fun `独占通路的调音工作在源率上 不用 nativeRate`() {
        // 旧期望是 96000（`nativeRate`）。错在把 AudioFlinger 的 primary mixer 率
        // 当成了独占流的目标率 —— 独占根本不经过 mixer，那个数字与本通路无关。
        // 把它当目标会得到「88.2k 的歌转成 48k 再送进端点」：白转一次、多一层量化，
        // 而 EQ 的滤波器系数本来就按输入率算，在源率上工作即可。
        val p = plan(
            mode = AudioOutputMode.tuned,
            path = OutputPath.aaudioExclusive,
            followSourceRate = true,
            nativeRate = 96_000,
            sourceRate = 44_100,
        )
        assertEquals(44_100, p.sampleRate)
        assertFalse(p.didResample)
        // 调音仍交 float 全精度：端点只收整数，那次落地由 `push()` 做（24 位内精确）
        assertEquals(PLAN_FLOAT_DEPTH, p.bitDepth)
    }

    /**
     * `bitPerfectMixer` 需要 `MODIFY_AUDIO_ROUTING`（signature|privileged），
     * 普通安装拿不到，事实上是死路，整个枚举值已删。
     * 真正能"原样送出"的只有 AAudio 独占，上面两条测的就是它。
     */

    @Test
    fun `调音时重采样目标照常生效 位深恒交 float`() {
        // 位深不再是用户选项：我方只要碰过数据（这里是重采样），就交出 float 全精度，
        // 量化与抖动留给 sink 那一次 —— 见 `PLAN_FLOAT_DEPTH` 的推导。
        val p = plan(
            mode = AudioOutputMode.tuned,
            nativeRate = 96_000,
            path = OutputPath.systemMix,
        )
        assertEquals(96_000, p.sampleRate)
        assertEquals(PLAN_FLOAT_DEPTH, p.bitDepth)
        assertEquals(96_000, p.mixerTarget)
    }

    @Test
    fun `bluetooth forces system mix so bit-perfect cannot switch the SRC off`() {
        // 回归用例（真会踩到）：存储里是 Bit-Perfect，插上蓝牙耳机后通路被**强制**成
        // systemMix。若「原样送出」仍按用户选的通路判定，我方就既不 SRC、也不请求 mixer，
        // 那一次 44.1→48 就白交给 AudioFlinger 的 a2dp 线程去做 —— 正是要接管的那一次。
        // 实测依据（本机 LHDC V5）：44100/PCM_16 的第三方轨挂在 48000 的 a2dp 线程上时，
        // 转换发生在 mixer；我方交付 48000 的轨则不转。
        val p = plan(
            mode = AudioOutputMode.tuned,
            route = AudioRoute.bluetooth,
            codecRate = 48_000,
            deviceRates = listOf(44_100, 48_000, 96_000),
            sourceRate = 44_100,
            sourceDepth = 16,
            path = OutputPath.aaudioExclusive,
        )
        assertEquals(OutputPath.systemMix, p.path) // 通路被强制成 systemMix
        assertEquals(48_000, p.sampleRate)         // 但 SRC 仍归我方
        assertTrue(p.didResample)
        assertEquals(0, p.mixerTarget)             // 蓝牙不请求 mixer（不由它决定）
        // 位深交 float，末级量化交给 a2dp/HAL 那一次（理由见 `蓝牙也交 float`）
        assertEquals(PLAN_FLOAT_DEPTH, p.bitDepth)
    }

    /**
     * **蓝牙下「独占可用」不能压掉我方转率** —— "忽然连上蓝牙"这条路径上的坑。
     *
     * 通路已被强制成 systemMix，独占根本没被使用。若还按探测结果把 `sampleRate` 归零，
     * 我方就不转了，a2dp 线程只好自己把 44.1 转到 codec 协商的 48 —— 白扔一次 r8brain；
     * 而 `tier` 还会在蓝牙耳机上写「Bit Perfect 输出」。
     *
     * 判据因此落在 `effectivePath` 上，不是"探测可用"。
     */
    @Test
    fun `蓝牙下独占探测不再生效 转率归我方 且不报 Bit Perfect`() {
        val p = plan(
            mode = AudioOutputMode.transparent,
            route = AudioRoute.bluetooth,
            codecRate = 48_000,
            deviceRates = listOf(44_100, 48_000),
            sourceRate = 44_100,
            aaudioExclusive = true,
            path = OutputPath.aaudioExclusive,
        )
        assertEquals(OutputPath.systemMix, p.path) // 通路被强制回退
        assertEquals(48_000, p.sampleRate)         // 转到 codec 协商率，而不是"不转"
        assertTrue(p.didResample)
        // tier 必须如实报"我方 SRC"：我们真的转了，不许声称 Bit Perfect
        assertEquals(OutputTier.ownSrc, p.tier)
    }

    /**
     * **蓝牙下「按源采样率」生效**（本条断言在本次改动里被**反转**过，这里写清为什么）。
     *
     * 原来这条测的是"蓝牙下按源率让位、转率归我方"，理由是 a2dp 线程的率由 codec 协商，
     * 按源率建轨等于把 44.1→48 从我方 r8brain 推给 AudioFlinger，纯亏。
     *
     * 用户实测推翻了那个取舍：在固定主率的设备上，**交给系统重采样反而没有那层沙状底噪**
     * （完整记录在 `native-r8b-resampler.cpp` 的 `kTransBand` 注释里）。而且原来那条行为
     * 会导致「开关显示着却是死的」—— 比不给开关更糟。所以现在蓝牙下按源率**真的生效**：
     * `sampleRate = 0`，我方不预转换，那一次 44.1→48 落在 AudioFlinger 的 a2dp 线程上。
     */
    @Test
    fun `蓝牙下按源率同样生效 我方不预转换`() {
        val p = plan(
            mode = AudioOutputMode.transparent,
            route = AudioRoute.bluetooth,
            codecRate = 48_000,
            deviceRates = listOf(44_100, 48_000),
            sourceRate = 44_100,
            followSourceRate = true,
        )
        assertEquals(0, p.sampleRate)
        assertFalse(p.didResample)
    }

    @Test
    fun `所有通路都已实现 没有需要回退的占位项`() {
        // USB 独占接上之后 `implemented` 全为 true。留这条守卫：再出现 false 的占位项时，
        // 用户会看到灰掉的选项 —— 那时必须同时想清楚它回退到哪里。
        assertTrue(OutputPath.entries.all { it.implemented })
    }

    @Test
    fun `USB 独占生效时不做我方 SRC`() {
        val p = plan(
            mode = AudioOutputMode.transparent,
            nativeRate = 96_000,
            path = OutputPath.usbDirect,
            sourceRate = 44_100,
        )
        assertEquals(OutputPath.usbDirect, p.path)
        assertEquals(0, p.sampleRate)
        assertEquals(0, p.bitDepth)
        assertFalse(p.didResample)
    }

    @Test
    fun `直通已整条删除 计划里没有任何直通字段`() {
        // 这条是"删除干净"的守卫：`directPossible` / `blockReason` 曾经挂在
        // `OutputPlan` 上，删掉它们之后不该有人偷偷加回来。
        val names = OutputPlan::class.java.declaredFields.map { it.name }
        assertTrue(
            "OutputPlan 里还有直通字段：$names",
            names.none { it.contains("direct", ignoreCase = true) },
        )
    }

    // ── 设备这一级的位深：蓝牙问 codec，其余问 mixer（只服务链路实况显示） ──

    @Test
    fun `bluetooth device depth comes from the codec not the mixer`() {
        // 本机实测的坑：mixer 能力报 float（→32），而 LHDC 协商的是 24。
        // 用 mixer 值就会显示「跟随设备（32 Bit float）」，耳机实际收 24 Bit。
        assertEquals(
            24,
            resolveDeviceDepth(
                route = AudioRoute.bluetooth,
                codecDepth = 24,
                mixerDepth = 16,
                mixerFloat = true,
            ),
        )
    }

    @Test
    fun `bluetooth device depth falls back to the mixer when codec is unknown`() {
        // offload 机型 getCodecStatus 返回 NONE → codecDepth = 0，此时退回本机侧近似值
        assertEquals(
            32,
            resolveDeviceDepth(
                route = AudioRoute.bluetooth,
                codecDepth = 0,
                mixerDepth = 16,
                mixerFloat = true,
            ),
        )
        assertEquals(
            16,
            resolveDeviceDepth(
                route = AudioRoute.bluetooth,
                codecDepth = 0,
                mixerDepth = 16,
                mixerFloat = false,
            ),
        )
    }

    @Test
    fun `non bluetooth device depth ignores the codec and uses the mixer`() {
        // 扬声器 / 有线 / USB：codec 值无意义（调用方传 0，这里再传非 0 也不许采信）
        assertEquals(
            32,
            resolveDeviceDepth(
                route = AudioRoute.speaker,
                codecDepth = 24,
                mixerDepth = 16,
                mixerFloat = true,
            ),
        )
        assertEquals(
            24,
            resolveDeviceDepth(
                route = AudioRoute.usb,
                codecDepth = 32,
                mixerDepth = 24,
                mixerFloat = false,
            ),
        )
    }

    // ── 系统实际率：我方「替他转」时的目标 ────────────────────────────

    @Test
    fun `usb 目标率取 mixer 实际率 不取声明档`() {
        // 旧期望是"源率被声明时不用转"。它假设 AudioFlinger 会为 USB 切 output thread，
        // 而实测输出线程恒为 48000（`dumpsys media.audio_flinger`）。按声明档选会让
        // 44.1k/96k 原样交给 mixer，那一次 SRC 落回系统 —— 质量不如我方的 r8brain。
        assertEquals(
            48_000,
            systemMixRateFor(
                AudioRoute.usb, 48_000, listOf(44_100, 48_000, 96_000), sourceRate = 44_100,
            ),
        )
        assertEquals(
            48_000,
            systemMixRateFor(
                AudioRoute.usb, 48_000, listOf(48_000, 96_000, 192_000), sourceRate = 96_000,
            ),
        )
        // mixer 率读不到时退回声明档里最近的一档（兜底）。
        assertEquals(
            48_000,
            systemMixRateFor(AudioRoute.usb, 0, listOf(44_100, 48_000, 96_000), sourceRate = 50_000),
        )
        // 什么都没有 → 0（不猜）。
        assertEquals(0, systemMixRateFor(AudioRoute.usb, 0, emptyList(), sourceRate = 44_100))
    }

    @Test
    fun `扬声器与有线用 primary 率 蓝牙只认协商率`() {
        assertEquals(48_000, systemMixRateFor(AudioRoute.speaker, 48_000, listOf(44_100, 48_000)))
        assertEquals(44_100, systemMixRateFor(AudioRoute.wired, 44_100, emptyList()))
        assertEquals(
            48_000,
            systemMixRateFor(AudioRoute.bluetooth, 48_000, listOf(48_000), codecRate = 48_000),
        )
        // 蓝牙读不到协商率 → 0。**不许拿 primary 顶**（那是扬声器的率，不是 a2dp 线程的）
        assertEquals(0, systemMixRateFor(AudioRoute.bluetooth, 48_000, listOf(48_000)))
    }

    @Test
    fun `透明 USB 也要我方转 不把 SRC 留给系统`() {
        // 旧期望是"设备声明了源率 → 目标率 == 源率 → 我方不转"。错在前提：
        // port profile 只说明 DAC 能吃什么，AudioFlinger 的输出线程并不跟着切（实测恒 48k），
        // 于是"我方不转"等于把那一次 SRC 交给 mixer 做 —— 质量更低，还多一次量化。
        // 现在的规则：系统通路 + USB 一律由我方转到 mixer 实际率。
        val p = plan(
            mode = AudioOutputMode.transparent,
            route = AudioRoute.usb,
            nativeRate = 48_000,
            deviceRates = listOf(44_100, 48_000, 96_000),
            sourceRate = 44_100,
        )
        assertEquals(48_000, p.sampleRate)
        assertTrue(p.didResample)
        assertEquals(OutputTier.ownSrc, p.tier)
        // 源率恰好等于 mixer 率时仍然不转 —— "少转一次"的原则没变。
        val same = plan(
            mode = AudioOutputMode.transparent,
            route = AudioRoute.usb,
            nativeRate = 48_000,
            deviceRates = listOf(44_100, 48_000, 96_000),
            sourceRate = 48_000,
        )
        assertEquals(48_000, same.sampleRate)
        assertFalse(same.didResample)
        assertNull(same.tier)
    }

    // ── 默认值：全新安装落在哪 ──────────────────────────────────────

    @Test
    fun `全新安装默认透明模式`() {
        // 第一次装完就该是「非独占」那条降级链：没有任何 EQ / 响度参与，用户先听到原样。
        // `fromKey(null)` 正是"prefs 里没有这个键"的路径 —— 全新安装走的就是它。
        // 钉住它是因为踩过一次：`init()` 里那段「接管采样率」迁移无标记地每次启动都跑，
        // 会把用户选的透明改写回调音（连没重启、只重建 Activity 都会触发）。
        assertEquals(AudioOutputMode.transparent, AudioOutputMode.fromKey(null))
        assertEquals(AudioOutputMode.transparent, AudioOutputMode.fromKey("garbage"))
        assertEquals(AudioOutputMode.transparent, AudioOutputMode.fromKey("transparent"))
        assertEquals(AudioOutputMode.tuned, AudioOutputMode.fromKey("tuned"))
    }

    // ── 抖动判定：唯一判据是「这一次会不会真的舍入」 ────────────────

    @Test
    fun `真的会舍入且落到 16 位就要抖动`() {
        // 重采样必然舍入（滤波输出落在格点之间）—— 这条以前整条漏掉了：
        // 老判据是"降位深 / 源是有损编码"，于是「跟随源 + 44.1→48」被判成"没降位深"，
        // 结果是**重采样后重量化却不抖**，比不干预还差。
        assertTrue(shouldDither(outDepth = 16, rounds = true))
    }

    @Test
    fun `不舍入就不许抖动`() {
        // 直通（不重采样、不改位深）= 一个采样都不动，抖了就是凭空加噪
        assertFalse(shouldDither(outDepth = 16, rounds = false))
        // 24 Bit 及以上：量化误差在 −144 dB 以下，抖了只会白抬噪声底
        assertFalse(shouldDither(outDepth = 24, rounds = true))
        assertFalse(shouldDither(outDepth = 32, rounds = true))
        // 0 = 不动
        assertFalse(shouldDither(outDepth = 0, rounds = true))
    }

    @Test
    fun `有损源不再单独成为抖动理由`() {
        // 老实现把「源是有损编码」当成抖动条件，那是**猜**：有损解码确实输出 float，
        // 但"有损 ⇒ 要抖"并不成立，成立的是"要舍入 ⇒ 必须抖"。
        // 现在判定只看舍入：同一份有损源，不重采样、不改位深就一个采样都不动，不抖。
        assertFalse(shouldDither(outDepth = 16, rounds = false))
        assertTrue(shouldDither(outDepth = 16, rounds = true))
        // 24 位出口抖不抖也没有意义（阈值在 16..23 之外）
        assertFalse(shouldDither(outDepth = 24, rounds = true))
    }
}
