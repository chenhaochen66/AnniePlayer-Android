package com.annieplayer.annie_music.kt.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放页 / 设置页音频文案的规则测试。
 *
 * 锁住的是**可读性契约**，不是实现细节：
 * - 位深与采样率永远成对，级内变化才用箭头（`24 Bit / 96→48 kHz`），
 *   级间变化写两份完整规格（`24 Bit / 96 kHz → 32 Bit float / 48 kHz` 由调用方分段）；
 * - 单位固定 `Bit` / `kHz` / `kbps`，且 44.1 kHz 不能显示成 `44 kHz`；
 * - 蓝牙的终点是 **codec 协商位深**，不是 mixer 的 float；
 * - 「正常」不写标签：不许再出现 `系统混音 32 Bit float` 这种把中间产物挂在尾巴上的写法。
 *
 * 全部纯 JVM：`AudioOutputStatus` 是纯 data class。
 */
class AudioTextTest {

    private fun status(
        route: AudioRoute = AudioRoute.speaker,
        mode: AudioOutputMode = AudioOutputMode.transparent,
        sourceRate: Int = 44_100,
        sourceDepth: Int = 16,
        deliveredRate: Int = 0,
        deliveredDepth: Int = 0,
        nativeRate: Int = 48_000,
        nativeDepth: Int = 24,
        nativeFloat: Boolean = false,
        sinkDepth: Int = 0,
        /** [sinkDepth] 那一次实测里 32 是否 float（与 sinkDepth 同源） */
        sinkFloat: Boolean = false,
        bluetoothRate: Int = 0,
        bluetoothDepth: Int = 0,
        bluetoothCodec: String = "",
        bluetoothBitrate: Int = 0,
        pipelineRate: Int = 0,
        pipelineDepth: Int = 0,
        bitPerfect: Boolean = false,
        rateFallback: RateFallback = RateFallback.none,
        eqOn: Boolean = false,
        loudnessOn: Boolean = false,
        decode: DecodeKind = DecodeKind.ffmpegSoft,
        /** 落在哪一级（与证据合成后的结论；`null` = 不写前缀） */
        tier: OutputTier? = null,
        /** 我方这一首**实际**做了采样率转换 */
        didResample: Boolean = false,
        effectivePath: OutputPath = OutputPath.systemMix,
        dsdTransport: com.annieplayer.annie_music.kt.core.audio.dsd.DsdTransport =
            com.annieplayer.annie_music.kt.core.audio.dsd.DsdTransport.pcm,
        dsdTargetRate: Int = 0,
    ): AudioOutputStatus = AudioOutputStatus(
        mode = mode,
        route = route,
        sourceRate = sourceRate,
        sourceDepth = sourceDepth,
        deliveredRate = deliveredRate,
        deliveredDepth = deliveredDepth,
        nativeRate = nativeRate,
        nativeDepth = nativeDepth,
        nativeFloat = nativeFloat,
        sinkDepth = sinkDepth,
        sinkFloat = sinkFloat,
        bluetoothRate = bluetoothRate,
        bluetoothDepth = bluetoothDepth,
        bluetoothCodec = bluetoothCodec,
        bluetoothBitrate = bluetoothBitrate,
        pipelineRate = pipelineRate,
        pipelineDepth = pipelineDepth,
        bitPerfect = bitPerfect,
        rateFallback = rateFallback,
        eqOn = eqOn,
        loudnessOn = loudnessOn,
        decode = decode,
        tier = tier,
        didResample = didResample,
        effectivePath = effectivePath,
        dsdTransport = dsdTransport,
        dsdTargetRate = dsdTargetRate,
    )

    // ── 单位与数字格式 ──────────────────────────────────────────────

    @Test
    fun `采样率整数不带小数`() {
        assertEquals("48 kHz", khzText(48_000))
        assertEquals("96 kHz", khzText(96_000))
        assertEquals("192 kHz", khzText(192_000))
    }

    @Test
    fun `采样率带小数不丢位`() {
        // 44.1 不能显示成 44 —— 以前 `/1000` 截断过，这正是「格式乱」的典型
        assertEquals("44.1 kHz", khzText(44_100))
        assertEquals("88.2 kHz", khzText(88_200))
        assertEquals("22.05 kHz", khzText(22_050))
        assertEquals("2822.4 kHz", khzText(2_822_400))
    }

    @Test
    fun `拿不到就空串不编数字`() {
        assertEquals("", khzText(0))
        assertEquals("", khzText(-1))
        assertEquals("", depthText(0))
        assertEquals("", depthText(-8))
        assertEquals("", specText(0, 0))
    }

    @Test
    fun `位深大写 Bit 并区分 float`() {
        assertEquals("24 Bit", depthText(24))
        assertEquals("32 Bit float", depthText(32, float = true))
        assertEquals("16 Bit", depthText(16))
    }

    // ── 规格只写一个端点，没有箭头 ──────────────────────────────────

    @Test
    fun `一致时只写一份`() {
        assertEquals("24 Bit / 48 kHz", specText(24, 48_000))
        assertEquals("16 Bit / 44.1 kHz", specText(16, 44_100))
    }

    @Test
    fun `规格不接受两个端点`() {
        // 曾经的 `96→48 kHz` 箭头已删除：转换**由谁做**由输出胶囊的方式段写
        // （`我方重采样` / `系统重采样`），箭头只写得出"变了"、写不出"谁变的"。
        // 这条锁的是"签名里已经没有第二个端点"—— 想加回来会先撞到这里。
        assertEquals("24 Bit / 96 kHz", specText(24, 96_000))
        assertEquals("32 Bit float / 48 kHz", specText(32, 48_000, float = true))
    }

    @Test
    fun `位深与采样率永远成对出现`() {
        // 位深这一栏回答的是"到耳朵是多少位"，源位深由左边的源胶囊负责；
        // 写两遍会让读者以为中间做过一次位深转换（多数情况那只是精确装箱）。
        assertEquals("24 Bit / 48 kHz", specText(24, 48_000))
        assertEquals("16 Bit / 44.1 kHz", specText(16, 44_100))
        // 拿不到的数字不许编
        assertEquals("24 Bit", specText(24, 0))
        assertEquals("48 kHz", specText(0, 48_000))
    }

    // ── 两级端点 ────────────────────────────────────────────────────

    @Test
    fun `蓝牙终点取 codec 位深而不是 mixer float`() {
        // 实测：送 96k/float，LHDC 协商 48k/24 → 到耳朵的是 24 Bit / 48 kHz
        val s = status(
            route = AudioRoute.bluetooth,
            sourceRate = 96_000,
            sourceDepth = 24,
            deliveredRate = 96_000,
            deliveredDepth = 24,
            nativeRate = 48_000,
            nativeFloat = true,
            sinkDepth = 32,
            bluetoothRate = 48_000,
            bluetoothDepth = 24,
            bluetoothCodec = "LHDC",
            bluetoothBitrate = 1_000_000,
        )
        val spec = outputSpecOf(s)
        assertEquals(96_000, spec.inRate)
        assertEquals(24, spec.inDepth)
        assertEquals(48_000, spec.outRate)
        assertEquals(24, spec.outDepth)
        // 位深没变（codec 也是 24），只有采样率变了；规格只写终点，谁转的由方式段说
        assertEquals("24 Bit / 48 kHz", spec.pair)
        assertTrue(s.systemResampling)
    }

    @Test
    fun `蓝牙胶囊带码率且没有系统混音尾巴`() {
        val s = status(
            route = AudioRoute.bluetooth,
            sourceRate = 96_000,
            sourceDepth = 24,
            deliveredRate = 96_000,
            deliveredDepth = 24,
            bluetoothRate = 48_000,
            bluetoothDepth = 24,
            bluetoothCodec = "LHDC",
            bluetoothBitrate = 1_000_000,
            nativeFloat = true,
            sinkDepth = 32,
        )
        val text = outputPillText(s)
        // 蓝牙上我们没有转，是 a2dp 输出线程按 codec 协商率在转 —— 这就是「系统重采样」
        assertEquals("LHDC 1000 kbps · 系统重采样 · 24 Bit / 48 kHz", text)
        // mixer 的 float 是中间产物，不许出现在终点描写里
        assertFalse(text, text.contains("32 Bit"))
    }

    @Test
    fun `sink 是 float 时位深就写 float`() {
        // 位深跟终点走：sink 实测 32 Bit float，胶囊就写 `32 Bit float`。
        // 只有「链路实况」的「送出」段照实写我方容器（同样是 float），
        // 两边不冲突 —— 它们说的是同一级。
        val s = status(
            sourceRate = 48_000,
            sourceDepth = 24,
            deliveredRate = 48_000,
            deliveredDepth = 24,
            nativeRate = 48_000,
            nativeFloat = true,
            sinkDepth = 32,
            sinkFloat = true,
        )
        assertEquals("32 Bit float / 48 kHz", outputSpecOf(s).pair)
        assertEquals("扬声器 · 32 Bit float / 48 kHz", outputPillText(s))
        assertEquals("32 Bit float / 48 kHz", outputSpecOf(s).outPair)
    }

    @Test
    fun `真的改位深也只写终点`() {
        // SBC 只收 16 bit：胶囊写 `16 Bit`（终点），不再写 `24→16 Bit`。
        // "源是 24" 这件事由左边的源胶囊负责。
        val sbc = status(
            route = AudioRoute.bluetooth,
            sourceRate = 44_100,
            sourceDepth = 24,
            deliveredRate = 44_100,
            deliveredDepth = 24,
            nativeRate = 48_000,
            bluetoothRate = 44_100,
            bluetoothDepth = 16,
            bluetoothCodec = "SBC",
        )
        assertEquals("16 Bit / 44.1 kHz", outputSpecOf(sbc).pair)

        // 16 位源 + LHDC 24：终点 24。用户问的正是这一例：只写 `24 Bit`。
        val lhdc = status(
            route = AudioRoute.bluetooth,
            sourceRate = 48_000,
            sourceDepth = 16,
            deliveredRate = 48_000,
            deliveredDepth = 16,
            nativeRate = 48_000,
            bluetoothRate = 48_000,
            bluetoothDepth = 24,
            bluetoothCodec = "LHDC",
        )
        assertEquals("24 Bit / 48 kHz", outputSpecOf(lhdc).pair)
        assertEquals("LHDC · 24 Bit / 48 kHz", outputPillText(lhdc))
    }

    @Test
    fun `蓝牙读不到协商结果时终点留空而不是借用别的数字`() {
        // 回归用例：曾经有两个"顺手"的兜底 —— 率退回 `nativeRate`（primary/扬声器率），
        // 位深退回 `info.bitDepth`（源位深）。两条都是编的：
        //   · 本机 primary 是 48k，而 SBC/AAC 侧经常协商 44.1k → 会把 44.1→48 说成"无需 SRC"；
        //   · sink 实测值是 32（float），而 a2dp 线程实测是 PCM_24_BIT_PACKED。
        // 读不到就该**不写这一项**，而不是换个数字顶上。
        val s = status(
            route = AudioRoute.bluetooth,
            sourceRate = 48_000,
            sourceDepth = 24,
            deliveredRate = 48_000,
            deliveredDepth = 24,
            nativeRate = 48_000,
            nativeFloat = true,
            sinkDepth = 32,
            // bluetoothRate / bluetoothDepth 都没读到
            bluetoothCodec = "", // 连 codec 名都没有
        )
        val spec = outputSpecOf(s)
        assertEquals(0, spec.outRate)
        assertEquals(0, spec.outDepth)
        // 只剩"我方送出"这一侧，一个箭头、一个 32 Bit 都不许出现
        assertEquals("24 Bit / 48 kHz", spec.pair)
        assertFalse(spec.pair, spec.pair.contains("→"))
        assertFalse(spec.pair, spec.pair.contains("32 Bit"))
        // 摘要也不许拼出规格
        assertEquals("蓝牙", bluetoothSummaryText(s))
    }

    @Test
    fun `蓝牙只有 codec 名时摘要不编规格`() {
        val s = status(route = AudioRoute.bluetooth, bluetoothCodec = "LHDC V5")
        assertEquals("LHDC V5", bluetoothSummaryText(s))
    }

    @Test
    fun `手机内部同规时一个箭头都没有`() {
        val s = status(
            sourceRate = 48_000,
            sourceDepth = 24,
            deliveredRate = 48_000,
            deliveredDepth = 24,
            nativeRate = 48_000,
            sinkDepth = 24,
        )
        assertEquals("扬声器 · 24 Bit / 48 kHz", outputPillText(s))
    }

    @Test
    fun `全链路以 native 回报为准不用源规格冒充`() {
        val s = status(
            sourceRate = 2_822_400,   // DSD 源
            sourceDepth = 1,
            pipelineRate = 176_400,
            pipelineDepth = 24,
            nativeRate = 176_400,
            sinkDepth = 24,
            decode = DecodeKind.nativeFullChain,
        )
        val spec = outputSpecOf(s)
        assertEquals(176_400, spec.inRate)
        assertEquals("24 Bit / 176.4 kHz", spec.inPair)
    }

    @Test
    fun `Bit Perfect 不虚报 mixer 的 float`() {
        // Bit Perfect：系统接受保持源规格，中间没有 float 轨道，不能写 16→32 Bit float。
        // tier 前缀紧贴规格（身份只说从哪儿出去），这是本轮定的语法。
        val s = status(
            sourceRate = 44_100,
            sourceDepth = 16,
            deliveredRate = 44_100,
            deliveredDepth = 16,
            nativeRate = 48_000,
            nativeFloat = true,
            sinkDepth = 32,
            bitPerfect = true,
            tier = OutputTier.bitPerfect,
        )
        assertEquals("16 Bit / 44.1 kHz", outputSpecOf(s).pair)
        assertEquals("扬声器 · Bit Perfect · 16 Bit / 44.1 kHz", outputPillText(s))
    }

    @Test
    fun `全链路不再占 tier 但仍写输出身份`() {
        // 「全链路」只说了"我不走 MediaCodec"，它不是一个输出层级 —— 本轮把它从
        // tier 前缀里删掉了，DSD 将来有自己的三个 tier。身份（从哪儿出去）必须保留：
        // 同一份 PCM 送到 LHDC 耳机和送到扬声器是两件完全不同的事。
        val bt = status(
            route = AudioRoute.bluetooth,
            sourceRate = 96_000,
            sourceDepth = 24,
            pipelineRate = 48_000,
            pipelineDepth = 24,
            decode = DecodeKind.nativeFullChain,
            bluetoothRate = 48_000,
            bluetoothDepth = 24,
            bluetoothCodec = "LHDC",
        )
        assertEquals("LHDC · 24 Bit / 48 kHz", outputPillText(bt))

        val speaker = status(decode = DecodeKind.nativeFullChain)
        assertTrue(outputPillText(speaker), outputPillText(speaker).startsWith("扬声器"))
        assertFalse(outputPillText(speaker), outputPillText(speaker).contains("全链路"))
    }

    // ── 异常标签 ────────────────────────────────────────────────────

    /**
     * 「直通未确认」那条标签**已随功能一起删除**。
     *
     * 它当年用来解释"判定该直通、轨也建了、系统却没回报 offload"这个沉默状态。
     * offload 没了，这个状态就不会出现 —— 所以这里锁的是**反向事实**：
     * 任何 status 都不该再冒出这个词。
     */
    @Test
    fun `不再出现直通未确认`() {
        val cases = listOf(
            status(),
            status(decode = DecodeKind.nativeFullChain),
            status(bitPerfect = true, tier = OutputTier.bitPerfect),
            status(route = AudioRoute.bluetooth, bluetoothRate = 48_000, bluetoothCodec = "LDAC"),
        )
        for (s in cases) {
            val text = outputPillText(s)
            assertFalse("不该再有直通相关标签：$text", text.contains("直通"))
            assertFalse(text.contains("未确认"))
        }
    }

    // 「档位被平台拒绝要标出来」这条测试已删除：它测的状态（`RateFallback.platformRejected`
    // → 显示「本机不支持该档」）随「自选采样率」一起消失。目标率现在恒为「跟随设备」算出来的
    // `autoRate`，是本机自己的值，不存在"用户选了但平台不认"。枚举值也一并删了，
    // 所以这里不是放宽断言，是**被测的状态不存在了**。

    // ── 链路实况：解码 / 源 / 送出 / 设备 / 落在哪一级 ────────────

    @Test
    fun `链路实况把落在哪一级并进同一行`() {
        // 原来「落在哪一级」单独占一行（`ladderSummaryText`），那行的前缀描述的是
        // 输出矩阵重构后已经不存在的「非 USB 独占」策略。现在并进 `chainText`：
        // 一行说完"是什么"和"怎么过去的"，也少一个要同步维护的文案函数。
        val withTier = chainText(status(tier = OutputTier.ownSrc))
        assertTrue("应含当前 tier：$withTier", withTier.endsWith("当前 我方重采样"))

        // tier 为 null（什么都没发生）时不许编一个"当前 xxx"出来
        val withoutTier = chainText(status(tier = null))
        assertFalse("tier 为空时不该出现「当前」：$withoutTier", withoutTier.contains("当前"))
    }

    @Test
    fun `方式段与规格各自独立成段`() {
        // 我方转过之后**交付率就等于系统率**，所以规格里没有箭头 —— 那条箭头是留给
        // "还有别人在转"的情况的。这条不变式值得记住：
        //   · 有箭头 `44.1→48 kHz` = 转换发生在别处（系统 mixer）
        //   · 没箭头 + `我方重采样` = 我们已经转好了，后面没人再动
        val s = status(
            sourceRate = 44_100,
            sourceDepth = 24,
            deliveredRate = 48_000,
            deliveredDepth = 24,
            nativeRate = 48_000,
            tier = OutputTier.ownSrc,
            didResample = true,
        )
        assertEquals("扬声器 · 我方重采样 · 24 Bit / 48 kHz", outputPillText(s))
        assertEquals(AudioTone.ownSrc, s.tone)
    }

    @Test
    fun `系统在转时方式段写系统重采样并显灰`() {
        // 我方交付 44.1k、系统 mixer 是 48k → 箭头出现，且**必须明说这是系统在转**。
        // 以前只靠箭头暗示，用户看不出"这一次不是我方造成的"。
        val s = status(
            sourceRate = 44_100,
            sourceDepth = 24,
            deliveredRate = 44_100,
            deliveredDepth = 24,
            nativeRate = 48_000,
            didResample = false,
            tier = null,
        )
        assertEquals("扬声器 · 系统重采样 · 24 Bit / 48 kHz", outputPillText(s))
        assertEquals(AudioTone.systemSrc, s.tone)
    }

    @Test
    fun `独占通路上不会冒出系统重采样`() {
        // 独占直接写端点，中间没有 mixer 可转；箭头不存在，也不许凭空写方式段
        val s = status(
            sourceRate = 44_100,
            sourceDepth = 24,
            deliveredRate = 44_100,
            deliveredDepth = 24,
            nativeRate = 48_000,
            effectivePath = OutputPath.usbDirect,
            tier = OutputTier.bitPerfect,
        )
        assertEquals("扬声器 · Bit Perfect · 24 Bit / 44.1 kHz", outputPillText(s))
        assertFalse(s.systemResampling)
    }

    @Test
    fun `调音的兜底要用兜底两个字`() {
        // 「兜底」= 计划外的转换（用户选了 Bit-Perfect 却没成功）；「输出」= 策略内的一次转换。
        // 一个词的差别，省掉一整句解释。
        val s = status(
            mode = AudioOutputMode.tuned,
            sourceRate = 44_100,
            sourceDepth = 24,
            deliveredRate = 48_000,
            deliveredDepth = 24,
            nativeRate = 48_000,
            tier = OutputTier.ownSrcFallback,
            didResample = true,
        )
        assertEquals(
            "调音 · 扬声器 · 我方重采样（兜底） · 24 Bit / 48 kHz",
            outputPillText(s),
        )
    }

    @Test
    fun `usb 目标率未证实要标出来`() {
        val s = status(
            route = AudioRoute.usb,
            sourceRate = 44_100,
            deliveredRate = 48_000,
            nativeRate = 48_000,
            tier = OutputTier.ownSrc,
            didResample = true,
        )
        assertTrue(outputPillText(s).contains("USB 目标率按设备声明"))

        // 扬声器上不该出现这条（primary 率是确定的）
        val speaker = status(
            sourceRate = 44_100,
            deliveredRate = 48_000,
            nativeRate = 48_000,
            tier = OutputTier.ownSrc,
            didResample = true,
        )
        assertFalse(outputPillText(speaker).contains("USB"))
    }

    // ── 调音只说「调音」 ────────────────────────────────────────────

    @Test
    fun `调音只说调音 不写 EQ 不写响度`() {
        // EQ / 响度是"我方改了数据"的同义反复 —— 「调音」两个字已经涵盖。
        // 细节留给设置页的「链路实况」，播放页只回答"这一路是什么状态"。
        val s = status(
            mode = AudioOutputMode.tuned,
            sourceRate = 44_100,
            deliveredRate = 48_000,
            eqOn = true,
            loudnessOn = true,
        )
        val text = outputPillText(s)
        assertEquals("调音 · 扬声器 · 16 Bit / 48 kHz", text)
        assertFalse("不该再出现 EQ：$text", text.contains("EQ"))
        assertFalse("不该再出现响度：$text", text.contains("响度"))
    }

    @Test
    fun `调音模式也写输出身份`() {
        // 调音只说了"我改了数据"，改了之后从哪儿出去是另一件事：
        // 蓝牙 / 扬声器 / USB 的上限完全不同，缺了身份这一行就有手段没终点
        val bt = status(
            mode = AudioOutputMode.tuned,
            route = AudioRoute.bluetooth,
            sourceRate = 96_000,
            sourceDepth = 24,
            deliveredRate = 48_000,
            deliveredDepth = 24,
            bluetoothRate = 48_000,
            bluetoothDepth = 24,
            bluetoothCodec = "LHDC",
            bluetoothBitrate = 1_000_000,
            eqOn = true,
        )
        // 顺序：调音 → 身份（含码率）→ [DSD] → [方式] → 规格
        assertEquals(
            "调音 · LHDC 1000 kbps · 24 Bit / 48 kHz",
            outputPillText(bt),
        )

        val usb = status(mode = AudioOutputMode.tuned, route = AudioRoute.usb)
        assertTrue(outputPillText(usb), outputPillText(usb).startsWith("调音 · USB"))
    }

    // ── DSD 三个模式：紧跟路由之后 ──────────────────────────────────

    @Test
    fun `DSD 直出报 1 Bit 且方式段不出现`() {
        // 逐字节直出，没有"方式"这回事 —— 规格自己已经写成 1 Bit / 2822.4 kHz
        val dop = status(
            route = AudioRoute.usb,
            sourceRate = 2_822_400,
            sourceDepth = 1,
            deliveredRate = 176_400,
            deliveredDepth = 24,
            effectivePath = OutputPath.usbDirect,
            dsdTransport = com.annieplayer.annie_music.kt.core.audio.dsd.DsdTransport.dop,
        )
        assertEquals("USB · DoP · 1 Bit / 2822.4 kHz", outputPillText(dop))
        // 原样直出 = 金
        assertEquals(AudioTone.bitPerfect, dop.tone)

        val native = status(
            route = AudioRoute.usb,
            sourceRate = 2_822_400,
            sourceDepth = 1,
            effectivePath = OutputPath.usbDirect,
            dsdTransport = com.annieplayer.annie_music.kt.core.audio.dsd.DsdTransport.native,
        )
        assertEquals("USB · Native DSD · 1 Bit / 2822.4 kHz", outputPillText(native))
        assertEquals(AudioTone.bitPerfect, native.tone)
    }

    @Test
    fun `DSD 转 PCM 的模式段与方式段各说各的`() {
        // 模式段说"1-bit 变成了 PCM"，方式段说"这次转换是我方做的" —— 两件事，分开写
        val s = status(
            route = AudioRoute.usb,
            sourceRate = 2_822_400,
            sourceDepth = 1,
            deliveredRate = 176_400,
            deliveredDepth = 24,
            effectivePath = OutputPath.usbDirect,
            dsdTransport = com.annieplayer.annie_music.kt.core.audio.dsd.DsdTransport.pcm,
            dsdTargetRate = 176_400,
            tier = OutputTier.ownSrc,
            didResample = true,
        )
        assertEquals(
            "USB · DSD 转 PCM · 我方重采样 · 24 Bit / 176.4 kHz",
            outputPillText(s),
        )
        assertEquals(AudioTone.ownSrc, s.tone)
    }

    @Test
    fun `调音在 DSD 模式之前 模式在路由之后`() {
        // 顺序由 `OutputHeadline` 单点决定：调音 → 路由 → DSD → 方式 → 规格
        val s = status(
            route = AudioRoute.usb,
            mode = AudioOutputMode.tuned,
            sourceRate = 2_822_400,
            sourceDepth = 1,
            deliveredRate = 176_400,
            deliveredDepth = 24,
            effectivePath = OutputPath.usbDirect,
            dsdTransport = com.annieplayer.annie_music.kt.core.audio.dsd.DsdTransport.pcm,
            dsdTargetRate = 176_400,
            tier = OutputTier.ownSrc,
            didResample = true,
        )
        assertEquals(
            "调音 · USB · DSD 转 PCM · 我方重采样 · 24 Bit / 176.4 kHz",
            outputPillText(s),
        )
    }

    // ── 链路实况 ────────────────────────────────────────────────────

    @Test
    fun `链路实况三段都写全`() {
        val s = status(
            sourceRate = 96_000,
            sourceDepth = 24,
            deliveredRate = 96_000,
            deliveredDepth = 24,
            nativeRate = 48_000,
            nativeFloat = true,
            sinkDepth = 32,
            sinkFloat = true,
        )
        assertEquals(
            "解码 软解 · 源 24 Bit / 96 kHz · 送出 24 Bit / 96 kHz · 设备 32 Bit float / 48 kHz",
            chainText(s),
        )
    }

    @Test
    fun `蓝牙摘要用同一套配对规则`() {
        val s = status(
            route = AudioRoute.bluetooth,
            bluetoothCodec = "LDAC",
            bluetoothRate = 96_000,
            bluetoothDepth = 32,
        )
        assertEquals("LDAC · 32 Bit / 96 kHz", bluetoothSummaryText(s))
    }

    @Test
    fun `蓝牙读不到 codec 时退回蓝牙二字`() {
        val s = status(route = AudioRoute.bluetooth, bluetoothCodec = "未知（offload）")
        assertEquals("蓝牙", bluetoothSummaryText(s))
    }
}
