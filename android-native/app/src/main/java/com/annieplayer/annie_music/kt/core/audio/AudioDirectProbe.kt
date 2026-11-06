package com.annieplayer.annie_music.kt.core.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.annieplayer.annie_music.kt.core.diagnostics.RuntimeLog
import com.annieplayer.annie_music.kt.core.state.MoreSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 输出能力探测 —— **唯一来源**（PCM 可建率 / sink 位深 / AAudio 独占矩阵）。
 *
 * ## 这里**不再有**直通（offload）探测
 *
 * 以前这份探测的另一半是"本机能不能走 offload 通道"：枚举 `ENCODING_*`、
 * 读 policy 声明的编码、逐个真建 offload 轨。那套**已整条删除** ——
 * 连带 `DirectEncoding` / `failedOffload` / `declaredDirect` / `canOffload` /
 * `mimeEncodingCandidates` 一起。理由：
 *
 * - 它把"数据被谁碰过"交给一条我们既测不到、也管不了的系统通道；
 * - 收益不可验证（offload 是否真的省电、是否真的更好听，应用层拿不到证据），
 *   而代价是整条链路多出一套并行的解码/采样率分支，每加一条通路都要额外判一次。
 *
 * 现在探测只回答三件**能验证**的事：
 * 1. `AudioFormat` 接受 / `AudioTrack` 真建得出的 PCM 档位（[DirectCapabilities.pcmRates] / [sinkRates]）；
 * 2. 设备这一级实际的输出位深（[sinkDepth] / [sinkFloat]）；
 * 3. AAudio EXCLUSIVE 在每个档位上的可用性（[probedExclusive] / [exclusiveVerdict]）。
 */
data class DirectCapabilities(
    /** `AudioFormat` 接受（纯格式校验，**与输出设备无关**）的 PCM 采样率 */
    val pcmRates: List<Int>,
    /**
     * 第 ② 层：**真建轨**通过的采样率（只会是 `> 192k` 的那些）。
     *
     * [pcmRates] 回答"`AudioFormat` 收不收这个数字"，本字段回答"`AudioTrack` 建不建得出轨"。
     * 超过 192k 之后两者会分叉：framework 被厂商放开过（部分国产 DAP）就建得出，
     * 没放开就只能在 [pcmRates] 之外被排除 —— 那时这一档根本进不了候选。
     */
    val sinkRates: List<Int> = emptyList(),
    /**
     * 候选里**被 `AudioFormat` 拒绝**的采样率。
     *
     * 平时是空的 —— 84400/96000 这些值平台一律接受。只有异常时才非空，
     * 用来解释"为什么某个档位没出现在结果里"，而不是把"全过"当成能力展示。
     */
    val rejectedRates: List<Int> = emptyList(),
    /** 系统 mixer 的率（普通 track 的最终输出率） */
    val nativeRate: Int,
    /** 当前输出设备声明的采样率 */
    val deviceRates: List<Int>,
    /**
     * 探测时**绑定**的输出设备描述（名字 + 声明率）。
     *
     * 为什么必须记：探测不指定 `setPreferredDevice`，走的是 policy 的
     * 「当前活跃路由」—— 插不插 USB DAC、插不插耳机，探出来的结果完全不同。
     * 不把设备写进结果里，面板就会让人误读成"这台机器永远能/不能"。
     */
    val deviceLabel: String = "",
)

object AudioDirectProbe {

    /**
     * AAudio 独占出口的总开关 —— **暂时关闭**。
     *
     * 已查明（不是猜，是有对照物的）：
     * 1. 独占流能开、能出声；能力面板显示我方 `pos` 正常增长（例：`written=454656f / 48000 = 9.47s`）；
     * 2. 但 ExoPlayer 侧 `isPlaying=false`、`currentPosition=0` —— 后端推进度那段是
     *    `if (p.isPlaying || pos != lastPositionMs)`，两个条件都不成立，所以界面永远 0；
     * 3. 同一现象解释"播一段就停、seek 后按新位置播一下又停"：播放器始终没进 READY。
     *
     * 结论：缺的是**让渲染器进入就绪态的那条回调**（Media3 的音频渲染器依赖 sink 侧
     * 通知"我已就绪/数据在流动"），不是位置算法。补齐这条之前，这个开关保持 false ——
     * 一个会破坏播放的出口比"没有 Bit Perfect"糟得多。
     *
     * 下一步（已定）：读 `MediaCodecAudioRenderer.processOutputBuffer / onReady` 的就绪判定，
     * 逐条实现它依赖的 sink 回调，再打开这个开关。
     */
    const val AAUDIO_SINK_ENABLED: Boolean = true

    /** 候选采样率：**是待验证的输入，不是结论**。设备 profile 里的率会并进来。 */
    private val CANDIDATE_RATES = intArrayOf(
        44_100, 48_000, 88_200, 96_000, 176_400, 192_000,
    )

    @Volatile
    private var cached: DirectCapabilities? = null

    /** 最近一次探测用的 controller（"重新探测"要用它重跑） */
    @Volatile
    private var lastController: AudioOutputController? = null

    /**
     * 重新探测（供设置页按钮手动触发）。
     *
     * 清缓存 + 重跑建轨，完成后回主线程回调。
     * 放在后台线程：探测要真建几十次 `AudioTrack`。
     */
    fun reprobeAsync(onDone: () -> Unit) {
        val controller = lastController
        Thread({
            cached = null
            _state.value = "未探测"
            if (controller != null) runCatching { capabilities(controller) }
            runCatching { aaudioLines = probeAaudioSync() }
            android.os.Handler(android.os.Looper.getMainLooper()).post(onDone)
        }, "annie-reprobe").start()
    }

    // ── AAudio 独占探测（Bit Perfect 那一级的**实现者**）────────────────
    //
    // 普通安装唯一能"绕开 AudioFlinger mixer"的途径就是 AAudio 独占（MMAP 直连），
    // 它不需要 `MODIFY_AUDIO_ROUTING`。所以这一组数据回答的是："这台机器上，
    // Bit Perfect 那一级到底能不能真的做到不动数据"。
    //
    // 探测是**真开一次流**再立刻关掉（同 `canBuildOffload` 的"建了就是证据"）：
    // `setSharingMode(EXCLUSIVE)` 只是请求，只有 `getSharingMode()` 回来的值算数。

    /** 探测结果（label→value），未探测时为空 */
    @Volatile
    private var aaudioLines: List<Pair<String, String>> = emptyList()

    /**
     * 每个采样率能否拿到 **AAudio 独占**（key = 采样率）。
     *
     * 这张表是**决策数据**，不只是展示数据：透明模式要不要重采样由它决定 ——
     * 能拿到独占就原样送出（由 AAudio 以源率开流），拿不到才转成系统率。
     * 以前探测结果只进了面板、plan 从来没读过它，于是"探测可用却仍走我方 SRC"。
     */
    private val aaudioExclusiveByRate = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

    /**
     * 探测矩阵：`采样率 → (aaudio_format_t → 能否独占)`。
     *
     * 比 [aaudioExclusiveByRate] 细一层：HAL 的 MMAP profile 往往只声明某些位深
     * （本机 PLQ110 的 `mmap_no_irq_out` 只有整数、没有 float），"这个率能不能独占"
     * 必须连着"用什么格式去要"一起回答。`AaudioSink` 用它做**前置判定**，
     * 避免明知不行还每首歌试一次。
     */
    private val aaudioProbeMatrix =
        java.util.concurrent.ConcurrentHashMap<Int, Map<Int, Boolean>>()

    /**
     * **运行时**失败过的采样率（进程级）。
     *
     * 从 `AaudioSink` 的 companion 迁过来 —— 以前"这个率行不行"有两份真相：
     * 这里的探测结果、和 sink 里的失败黑名单，来源不同、更新时机不同，谁也看不到谁。
     * 现在合成一份：探测给"事前结论"，这里给"事中事实"，都从 [exclusiveVerdict] 出。
     */
    private val runtimeFailedRates = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

    /** 播放层回报：当前是否**真的**在用独占流（没回报 = 没在独占） */
    @Volatile
    private var aaudioActiveNow: Boolean = false

    fun recordAaudioActive(active: Boolean) {
        if (aaudioActiveNow != active) {
            aaudioActiveNow = active
            RuntimeLog.record("AAudio", if (active) "独占流生效" else "独占流结束（回落到系统 mixer）")
        }
    }

    /**
     * 独占路径的**实时计数**（由 `AaudioSink` 在取位置时顺手写入）。
     *
     * 为什么要把这些数摆到面板上：进度条不动/播一会儿卡住这两件事，光看"能播/不能播"
     * 猜不出来 —— 必须看到时间戳给的帧位置、`framesRead`、当前 positionUs 分别是多少。
     * 这几个数放在一起，一眼就能判断是"时间戳撒谎""流停了"还是"位置没上报"。
     */
    @Volatile
    private var aaudioStats: String = "（还没播放过）"

    fun recordAaudioStats(text: String) {
        aaudioStats = text
    }

    /**
     * 最近一次"独占没成立"的原因（面板显示用）。
     *
     * 以前这一行只会说「否（走系统 mixer）」—— 分不出"调音模式本来就不该独占"和
     * "想独占但 HAL 不给"，两种情况的处理完全不同。现在直接带原因。
     */
    @Volatile
    private var lastExclusiveDenial: String? = null

    /** 具体原因，供降级弹窗把「本机不支持独占」补齐成「…（实际 48000 Hz）」 */
    fun lastDenialLabel(): String? = lastExclusiveDenial

    /**
     * 播放层回报"这一首独占没成立" —— **唯一的失败登记入口**。
     *
     * 做三件事，缺一件就会出"看起来能独占其实一直在 mixer"的错位：
     * 1. 把这个率记进运行时黑名单 → 下一次 `configure` 的 [exclusiveVerdict] 直接拦掉，
     *    这就是失败后的自愈回路（否则每一首都重试一次、每一首都卡一下）；
     * 2. 把面板的"独占生效"置否；
     * 3. 留痕：`RuntimeLog` 记事件，`recordPlaybackFailure` 记最近一次原因（面板显示）。
     */
    fun markExclusiveFailure(rate: Int, denial: ExclusiveDenial, detail: String = "") {
        // USB 独占的失败**不能**写进 AAudio 的判定表：`runtimeFailedRates` /
        // `aaudioExclusiveByRate` 是"这台 HAL 在某个率上开不开得出独占"的结论，
        // 拿一次 USB 写失败去污染它，后果是同一个采样率之后连 AAudio 都不试了 ——
        // 表现是"切一次歌之后独占永久不生效，静默退回系统通路"。
        if (rate > 0) {
            runtimeFailedRates.add(rate)
            aaudioExclusiveByRate[rate] = false
        }
        recordAaudioActive(false)
        val why = denial.label + if (detail.isEmpty()) "" else "（$detail）"
        lastExclusiveDenial = why
        RuntimeLog.record("AAudio", "独占未成立：$why · ${fmtRate(rate)}")
        recordPlaybackFailure("AAudio 独占未生效：$why（${fmtRate(rate)}）")
    }

    /** 只记现场，不污染 AAudio 判定表 —— USB 这条通路的失败走这里 */
    fun noteUsbFailure(denial: ExclusiveDenial, detail: String = "") {
        val why = denial.label + if (detail.isEmpty()) "" else "（$detail）"
        lastExclusiveDenial = why
        RuntimeLog.record("USB", "独占未成立：$why")
        recordPlaybackFailure("USB 独占未生效：$why")
    }

    /** 开始尝试一次独占（成功或事前拒绝都会经过这里，用来清掉上一次的旧原因） */
    fun recordExclusiveAttempt() {
        lastExclusiveDenial = null
    }

    /**
     * 事前就判定不试 —— **只更新面板文案，不进运行时黑名单**。
     *
     * 与 [markExclusiveFailure] 的区别很关键：那个登记的是"试了但失败"，
     * 这个只是"没必要试"（非透明模式 / 探测已判不可能）。把后者也写进黑名单，
     * 会让"在调音模式下播过一首"把该采样率永久拉黑，切回透明模式也再也不试。
     */
    fun notePreDenial(denial: ExclusiveDenial) {
        lastExclusiveDenial = denial.label
    }

    /** 该采样率是否在**运行时**失败过（进程级黑名单） */
    fun runtimeFailed(rate: Int): Boolean = rate > 0 && runtimeFailedRates.contains(rate)

    /**
     * 探测矩阵对这一组 (采样率, 格式) 的结论：`true` 能独占 / `false` 拿不到 / `null` = 还没探到。
     *
     * `null` 必须与 `false` 分开：探测是异步的、且绑定当前路由，启动早期或刚插拔设备时
     * 矩阵本来就是空的 —— 把 `null` 当 `false` 会让"插上耳机的第一首永远赶不上独占"。
     */
    fun probedExclusive(rate: Int, pcmFormat: Int): Boolean? =
        aaudioProbeMatrix[rate]?.get(pcmFormat)

    /**
     * 这一首要不要试独占 —— 收敛后的**唯一入口**。
     *
     * 判定本身是纯函数（`decideExclusive`，可单测），这里只负责把三份事实取出来：
     * 通路/路由许可、运行时黑名单、探测矩阵。
     *
     * @param wanted 用户选了「AAudio 独占」通路且当前不是蓝牙（见 `AaudioSink.exclusiveWanted`）
     */
    fun exclusiveVerdict(
        rate: Int,
        pcmFormat: Int,
        wanted: Boolean,
    ): ExclusiveVerdict = decideExclusive(
        wanted = wanted,
        runtimeFailed = runtimeFailed(rate),
        probedExclusive = probedExclusive(rate, pcmFormat),
    )

    /** 该采样率能否拿到独占；`null` = 还没探到（调用方按"不能"处理，不猜） */
    fun aaudioExclusiveAt(rate: Int): Boolean? {
        // A/B 定责期间总开关为 false：让**决策**也一并回到系统通路，
        // 否则会测出"不重采样 + 系统 sink"这种混合配置，结论不可信。
        if (!AAUDIO_SINK_ENABLED) return null
        return if (rate <= 0) null else aaudioExclusiveByRate[rate]
    }

    /** 探这几档够用：流媒体 44.1/48，高解析 88.2/96，极端 192 */
    private val AAUDIO_PROBE_RATES = intArrayOf(44_100, 48_000, 88_200, 96_000, 192_000)

    /**
     * 探测的 PCM 格式（`aaudio_format_t`）。**整数在前**：HAL 的 MMAP profile
     * 更常声明整数位深，float 单独一列作对照（它能区分"格式不支持"和"独占不支持"）。
     */
    private val AAUDIO_PROBE_FORMATS = intArrayOf(
        com.annieplayer.annie_music.kt.core.audio.dsp.NativeAaudio.FORMAT_I24,
        com.annieplayer.annie_music.kt.core.audio.dsp.NativeAaudio.FORMAT_I16,
        com.annieplayer.annie_music.kt.core.audio.dsp.NativeAaudio.FORMAT_I32,
        com.annieplayer.annie_music.kt.core.audio.dsp.NativeAaudio.FORMAT_FLOAT,
    )

    private const val CELL_EXCLUSIVE = "独占"
    private const val CELL_SHARED = "共享"

    /** 后台跑一遍 AAudio 独占探测，完成后回主线程（能力面板打开时调用） */
    fun probeAaudioAsync(onDone: () -> Unit) {
        Thread({
            runCatching { aaudioLines = probeAaudioSync() }
            android.os.Handler(android.os.Looper.getMainLooper()).post(onDone)
        }, "annie-aaudio").start()
    }

    /**
     * 冷启动/设备变更时**预热**这张表（后台，不影响播放）。
     *
     * 为什么要在起播前探：`_applyOutputPolicy` 是同步的，而开独占流要几毫秒；
     * 在播放线程上现开会让第一次起播卡一下。预热之后 plan 直接读缓存。
     */
    /**
     * 冷启动/设备变更时**预热**这张表（后台，不影响播放）。
     *
     * 为什么要在起播前探：`_applyOutputPolicy` 是同步的，而开独占流要几毫秒；
     * 在播放线程上现开会让第一次起播卡一下。预热之后 plan 直接读缓存。
     *
     * [onDone] 在主线程回调（探完可用性可能刚变成 true，调用方据此重新定计划 ——
     * 不回调的话，插上耳机的**第一首**永远赶不上独占）。
     */
    fun warmUpAaudioAsync(onDone: () -> Unit = {}) {
        // 「独占内核」是**进程级一次**的结论（库能不能加载不会中途变），
        // 放在预热里做：启动 1~2 秒后运行日志里就有一行"独占内核：OBOE/RAW"，
        // 不必等用户播一首歌才知道后端有没有真的起来。
        auditAaudioBackend()
        // 一次就够：每次定计划都重探会让 AAudio 反复开关流，既抢设备又刷日志。
        // 路由/设备变化时由 `invalidate()` 复位。
        if (aaudioWarmed) {
            android.os.Handler(android.os.Looper.getMainLooper()).post(onDone)
            return
        }
        aaudioWarmed = true
        Thread({
            runCatching { probeAaudioSync() }
            android.os.Handler(android.os.Looper.getMainLooper()).post(onDone)
        }, "annie-aaudio-warm").start()
    }

    /** 后端自检只记一次（`describe()` 里已经含"请求 OBOE 但加载失败→已回落"这一种） */
    @Volatile
    private var aaudioBackendAudited = false

    /**
     * 把"这一进程的独占内核到底起没起来"记进运行日志。
     *
     * 为什么值得一行日志：独占内核只剩 Oboe 一种，但**库加载失败是静默的**
     * （`System.loadLibrary` 的异常被 `runCatching` 收敛成 false）——
     * 没有这一行时，"界面说 OBOE、其实全程走系统通路"这种错位只能靠读代码猜。
     * 面板上有同一结论（`aaudioRows()` 的「独占内核」那行）。
     */
    private fun auditAaudioBackend() {
        if (aaudioBackendAudited) return
        aaudioBackendAudited = true
        // 独占内核只剩 Oboe 一种，但"库能不能加载"仍然必须留痕：
        // `libannieoboe.so` 依赖 `libc++_shared.so`，任一缺失都会让**整首歌**退回系统通路
        // （`AaudioSink.configure` 里 open 返回 0 → 直接选系统引擎）。没有这一行，
        // "怎么一点独占都没有"就只能靠猜。
        val loaded = com.annieplayer.annie_music.kt.core.audio.dsp.NativeOboeStream.available()
        RuntimeLog.record(
            "AAudio",
            if (loaded) {
                "独占内核：OBOE（回调拉模式，库已加载）"
            } else {
                "独占内核：OBOE 库加载失败 —— 全程走系统通路"
            },
        )
    }

    @Volatile
    private var aaudioWarmed = false

    /**
     * 独占探测：**每个采样率 × 每种 PCM 格式**各开一条流，回读真实共享模式。
     *
     * ## 为什么要按格式分格，而不是只测一种
     *
     * 以前只用 `32` 去探，而 32 在 AAudio 里被当成 **float**。这台 PLQ110 的
     * `mmap_no_irq_out`（`DIRECT|MMAP_NOIRQ`）profile 里只有整数位深
     * （16 / 8_24 / 24_PACKED / 32），**没有 float** —— 于是五行全报"仅共享"，
     * 看起来像设备不支持独占，实际上是**我们问错了格式**。假阴性比没探测更糟：
     * 它会让人放弃一条本来可行的路。
     *
     * 而 HAL 的 MMAP profile 是厂商写的 XML，各家不同（有的只给 float，有的只给整数），
     * 所以只能实测，不能按"这台机器如何"推广。
     *
     * ## 短路
     *
     * 一个采样率上只要有一种格式拿到独占，该率就算"独占可用"（[aaudioExclusiveByRate]），
     * 但**四种格式仍然都测**：面板要能回答"该请求哪种格式"，只报成功的那一个
     * 等于把"哪种不行"也藏起来了。
     */
    private fun probeAaudioSync(): List<Pair<String, String>> {
        val nativeAaudio = com.annieplayer.annie_music.kt.core.audio.dsp.NativeAaudio
        if (!nativeAaudio.isAvailable()) {
            aaudioExclusiveByRate.clear()
            return listOf("AAudio 独占" to "不可用（libaaudio 未加载）")
        }
        val lines = AAUDIO_PROBE_RATES.map { rate ->
            val cells = AAUDIO_PROBE_FORMATS.map { format ->
                val raw = nativeAaudio.probe(rate, format)
                val parsed = parseAaudioProbe(raw)
                aaudioFormatTag(format) to probeCellLabel(parsed)
            }
            // 两种汇总，两种用途：
            // · 这一行的显示值 = 任一格式成功即算"该率可用"（`aaudioExclusiveByRate`，给定计划用）
            // · 探测矩阵 = 按格式精确记录，给 `AaudioSink` 的前置判定用
            val byFormat = AAUDIO_PROBE_FORMATS.mapIndexed { index, format ->
                format to (cells[index].second == CELL_EXCLUSIVE)
            }.toMap()
            aaudioProbeMatrix[rate] = byFormat
            aaudioExclusiveByRate[rate] = byFormat.values.any { it }
            ("AAudio 独占 ${fmtRate(rate)}" to
                cells.joinToString(" · ") { (tag, label) -> "$tag=$label" })
        }
        RuntimeLog.record(
            "AAudio",
            lines.joinToString("；") { "${it.first} = ${it.second}" },
        )
        return lines + oboeSharingProbeLines()
    }

    /** 面板里格式那一列的名字：`16` / `24` / `32i`（整数）/ `f`（浮点） */
    private fun aaudioFormatTag(format: Int): String = when (format) {
        com.annieplayer.annie_music.kt.core.audio.dsp.NativeAaudio.FORMAT_I16 -> "16"
        com.annieplayer.annie_music.kt.core.audio.dsp.NativeAaudio.FORMAT_I24 -> "24"
        com.annieplayer.annie_music.kt.core.audio.dsp.NativeAaudio.FORMAT_I32 -> "32i"
        else -> "f"
    }

    /** 单元格文案：越短越好（一行要塞四格） */
    private fun probeCellLabel(result: AaudioProbeResult?): String = when {
        result == null -> "?"
        result.errorTag != null -> "err${result.errorTag}"
        result.exclusive -> CELL_EXCLUSIVE
        else -> CELL_SHARED
    }

    /**
     * Oboe 后端能不能拿到 EXCLUSIVE —— 参数组合实测。
     *
     * 起因是一组实测矛盾：这台机器上 **RAW 曾被判成独占，OBOE 拿不到**。追下去发现
     * RAW 那个"独占"是假的（`AAUDIO_SHARING_MODE_EXCLUSIVE` 被写成 1，请求与判定
     * 用同一个错值互相抵消），真实情况是两个后端都拿不到。于是这里把"到底哪些变量
     * 影响 HAL 给不给"逐个测出来：
     *
     * ```
     * [0] 低延迟 + callback + capacity   ← 播放路径当时用的组合
     * [1] 低延迟 + 推 + capacity
     * [2] 低延迟 + 推 + 不设 capacity
     * [3] NONE + 推 + 不设 capacity      ← 关键假设：MIUI 剥的是 ULL 路由标志
     * [4] NONE + callback + 不设 capacity
     * ```
     *
     * 第 3/4 组是本轮的重点：MIUI 那条 `forbiding ull track route` 剥的是 **ULL**
     * （超低延迟）路由上的 `RAW` / `MMAP_NOIRQ` 标志，而 `LOW_LATENCY` 正是 ULL 的触发器。
     * 音乐播放不需要低延迟，只需要"不转换" —— 如果 NONE 能绕过剥离，这一级就成立。
     *
     * 返回值里还编进了"实际后端"（+10 = OpenSL ES），用来排除
     * "Oboe 悄悄走了 OpenSL，所以必然共享"这种误判。
     *
     * 只 open 不 start，不出声。结论同时进运行日志和「输出能力（实测）」面板。
     */
    private fun oboeSharingProbeLines(): List<Pair<String, String>> {
        // 独占内核现在只有 Oboe 一种，探测无条件跑。48 kHz 是最通用的档：
        // HAL 对它的独占支持通常最完整，测出来的差异最能说明问题。
        val probe = com.annieplayer.annie_music.kt.core.audio.dsp.NativeOboeStream
            .probeSharingMode(48_000, 32, 2) ?: return listOf("Oboe 独占探测" to "未执行（库不可用）")
        fun label(v: Int): String = when {
            v == 11 -> "OpenSL独占"
            v == 10 -> "OpenSL共享"
            v == 1 -> "独占"
            v == 0 -> "共享"
            else -> "失败(${v + 100})"
        }
        fun at(index: Int): Int = probe.getOrElse(index) { 0 }
        val lowLatency = "callback+cap=${label(at(0))} · 推+cap=${label(at(1))} · " +
            "推默认=${label(at(2))}"
        val none = "推=${label(at(3))} · callback=${label(at(4))}"
        RuntimeLog.record("AAudio", "Oboe 独占探测（48 kHz，低延迟）：$lowLatency")
        RuntimeLog.record("AAudio", "Oboe 独占探测（48 kHz，NONE）：$none")
        return listOf(
            "Oboe 探测·低延迟" to lowLatency,
            "Oboe 探测·NONE" to none,
        )
    }

    /** 设备/路由变化时调用，下次重新探测（单点实测结果保留：offload 能力取决于 HAL，不随路由变） */
    fun invalidate() {
        cached = null
        // 路由/设备变了，AAudio 独占能力要重新实测。
        // **矩阵与运行时黑名单一起清**：MMAP profile 是按设备类型分别声明的 ——
        // 内置扬声器拿不到不代表插上 USB DAC 也拿不到；留着旧结论会把新路由永久拦在门外。
        aaudioWarmed = false
        aaudioProbeMatrix.clear()
        runtimeFailedRates.clear()
        aaudioExclusiveByRate.clear()
    }

    /** 只读缓存快照：**不触发探测**（供播放路径/UI 用，避免在那里建轨） */
    fun cachedOrNull(): DirectCapabilities? = cached

    /**
     * 探测状态：`未探测` / `探测中…` / `完成` / `失败：…`。
     *
     * 用 StateFlow 而不是普通字段：一次完整探测要真建几十条轨道，
     * 耗时几百毫秒。普通 `@Volatile var` 只能在打开面板那一刻取一次快照 ——
     * 用户点「重新探测」后只会盯着一段不动的文字，看不见进行到哪一步。
     */
    private val _state = MutableStateFlow("未探测")
    val state: StateFlow<String> = _state.asStateFlow()

    /**
     * 最近一次播放失败的原始原因。
     *
     * 为什么要显示而不是只打日志：这台 ROM 会按进程配额丢弃 App 日志
     * （`Log.w` 一条都收不到），而"失败后自动跳下一首"会把错误立刻冲掉，
     * 导致现场完全不可见。这里保留最后一次失败，供设置页面板直接读。
     */
    @Volatile
    var lastPlaybackFailure: String = "无"
        private set

    fun recordPlaybackFailure(text: String) {
        lastPlaybackFailure = text
        RuntimeLog.record("播放失败", text)
    }

    /**
     * 最近一次开流的实际参数（native 侧 `startStream` 选中的 alt）。
     *
     * 为什么必须在面板上：`raw=1` 却没声与 `raw=0` 却没声是两条完全不同的故障路径，
     * 而两者在听感上一模一样。这台 ROM 会丢应用日志，这一行是唯一的现场。
     */
    @Volatile
    var usbStreamLine: String = ""
        private set

    fun recordUsbStream(line: String) {
        usbStreamLine = line
    }

    /** 系统 mixer 的位深（16 / 24 / 32）与是否 float，由播放层在探测时写入 */
    @Volatile
    private var mixerDepth: Int = 0

    @Volatile
    private var mixerFloat: Boolean = false

    fun recordMixerDepth(depth: Int, isFloat: Boolean) {
        mixerDepth = depth
        mixerFloat = isFloat
    }

    /**
     * 「设备这一级」的位深；0 = 还没实测到。
     *
     * 为什么不直接用 [mixerDepth]（它是 `AudioCapabilities` 的结论）：
     * 本机 `AudioCapabilities.getCapabilities()` **只报 PCM_16**，而实际
     * AudioFlinger 主线程的 processing format 就是 `PCM_FLOAT`（`dumpsys media.audio_flinger`
     * 可查），`PCM_FLOAT` 的 AudioTrack 也建得出来。据此把「跟随设备」钉在 16 bit
     * 会让用户在无损曲目上白白损失 8 位 —— 所以这里以**真建一次 track 的实测结果**为准，
     * `AudioCapabilities` 只当兜底。
     */
    fun sinkDepth(): Int = probedSinkDepth

    /**
     * [sinkDepth] 那一次实测里，32 是不是 **float**（而不是 32 位整数）。
     *
     * 必须和 [sinkDepth] 同源：界面以前"深度取这里、float 取 `AudioCapabilities`"，
     * 而本机 `AudioCapabilities` 只报 `PCM_16` → 显示成 `24→32 Bit`（整数），
     * 实际交付的却是 float 轨。同一事实两个来源，必然打架。
     */
    fun sinkFloat(): Boolean = probedSinkFloat

    /**
     * 保证「sink 位深」已经实测过，返回它；0 = 连 16 bit 都建不出来（异常情况）。
     *
     * 为什么要暴露"按需补测"：`AnnieRenderersFactory.buildAudioSink()` 要拿这个值
     * 决定向 Media3 声明哪些编码，而它可能早于后台探测跑完 —— 那时声明里没有 float，
     * 我们的处理器就算输出 float，Media3 也会插一个 ToInt16 把整条链压回 16 bit。
     * 代价只有一次 AudioTrack 建轨（几毫秒），而且只在**还没测过**时发生。
     */
    @Synchronized
    fun ensureSinkDepth(sampleRate: Int = 48_000): Int {
        if (probedSinkDepth <= 0) probeSinkDepth(sampleRate)
        return probedSinkDepth
    }

    @Volatile
    private var probedSinkDepth: Int = 0

    @Volatile
    private var probedSinkFloat: Boolean = false

    /**
     * 实测一次 sink 位深：依次尝试 float / 24 bit / 16 bit 建 AudioTrack，第一个建成功的就是它。
     *
     * 与 [canBuildOffload] 同一套思路（建轨即证据，不做降级猜测），只是这里建的是普通 PCM 轨。
     * 探测失败一律记 0，调用方退回 `AudioCapabilities` 的结论。
     */
    private fun probeSinkDepth(sampleRate: Int) {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val candidates = buildList {
            add(AudioFormat.ENCODING_PCM_FLOAT to true)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                add(AudioFormat.ENCODING_PCM_24BIT_PACKED to false)
            }
            add(AudioFormat.ENCODING_PCM_16BIT to false)
        }
        for ((encoding, isFloat) in candidates) {
            val ok = runCatching {
                val format = AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(encoding)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
                val track = AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(format)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(sampleRate / 2 * 2 * 4)
                    .build()
                val initialized = track.state == AudioTrack.STATE_INITIALIZED
                track.release()
                initialized
            }.getOrDefault(false)
            if (ok) {
                probedSinkDepth = if (isFloat) 32 else if (encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED) 24 else 16
                probedSinkFloat = isFloat
                return
            }
        }
        probedSinkDepth = 0
        probedSinkFloat = false
    }

    /** 设备支持的输出位深选项（含 0 = 跟随源）；空 = 还没探测到 */
    @Volatile
    private var supportedDepthsCache: List<Int> = emptyList()

    fun recordSupportedDepths(depths: List<Int>) {
        supportedDepthsCache = depths
    }

    fun supportedDepths(): List<Int> = supportedDepthsCache

    /** 给界面用的位深文案；拿不到就返回空串（不猜、不编） */
    fun mixerDepthLabel(): String =
        if (mixerFloat) depthText(32, float = true) else depthText(mixerDepth)

    /**
     * 诊断报告：**逐条列出**，供设置页弹框展示。
     */
    fun report(): List<Pair<String, String>> {
        val caps = cachedOrNull() ?: return buildList {
            add("探测状态" to state.value)
            add("最近播放失败" to lastPlaybackFailure)
            // 无缓存时不能只回一行 —— 蓝牙启动后切回本机、或刚换过设备时，
            // 探测还没跑完，面板必须说明"为什么空"以及怎么让它出结果。
            add(
                "说明" to
                    "输出能力还没探到。切回扬声器/有线后会自动重探，" +
                    "也可以点上方「重新探测」。",
            )
            add("应用→mixer" to (mixerDepthLabel().ifEmpty { "未知" }))
            addAll(aaudioRows())
        }
        return buildList<Pair<String, String>> {
            add("探测状态" to state.value)
            add("最近播放失败" to lastPlaybackFailure)
            // 这行的位深是「本应用往 mixer 送什么」（`AudioCapabilities` 能建哪些编码），
            // **不是 HAL 出门的位深** —— 后者在下面单独一行，需要 root 才能读。
            add(
                "应用→mixer" to run {
                    val depth = mixerDepthLabel()
                    val rate = fmtRate(caps.nativeRate).ifEmpty { "" }
                    when {
                        depth.isNotEmpty() && rate.isNotEmpty() -> "$depth / $rate"
                        depth.isNotEmpty() -> depth
                        rate.isNotEmpty() -> rate
                        else -> "未知"
                    }
                },
            )
            add("当前输出设备" to caps.deviceLabel.ifEmpty { "未知" })
            if (usbStreamLine.isNotEmpty()) {
                add("USB 流参数" to usbStreamLine)
            }
            add("设备声明采样率" to caps.deviceRates.joinToString(" / ") { fmtRate(it) }.ifEmpty { "无" })
            // 第 ② 层：真建轨。三态分开写，别让用户把"没探"读成"不支持"。
            run {
                val claimedHigh = caps.deviceRates.filter { it > AUDIO_FORMAT_MAX_RATE }
                when {
                    caps.sinkRates.isNotEmpty() ->
                        add(
                            "系统建轨实测（>192k）" to
                                caps.sinkRates.joinToString(" / ") { fmtRate(it) },
                        )
                    claimedHigh.isNotEmpty() ->
                        add(
                            "系统建轨实测（>192k）" to
                                "${claimedHigh.joinToString(" / ") { fmtRate(it) }} 声明了但建不出轨" +
                                "（AudioFormat 上限 192k，除非 framework 放开）",
                        )
                    // 设备没声明高率 → 不写这一行（walk 到 192k 就到顶了，写出来是噪音）
                }
            }
            // 平台拒绝的率：**只有真的被拒才出现**。
            // 看不见这行 = 格式层没拦过；看见它才是"平台明确不接受这个数字"。
            // 早先无条件列「平台可建采样率」，等于把"全过"当成能力展示，纯误导。
            if (caps.rejectedRates.isNotEmpty()) {
                add(
                    "平台拒绝的采样率（格式层，与设备无关）" to
                        caps.rejectedRates.joinToString(" / ") { fmtRate(it) },
                )
            }
            addAll(aaudioRows())
        }
    }

    /** AAudio 那几行；还没探过就明说"未探测"，不占位假数据 */
    private fun aaudioRows(): List<Pair<String, String>> =
        aaudioLines.ifEmpty {
            listOf("AAudio 独占" to "未探测（打开本面板时自动探测）")
        } + listOf(
            // 内核只剩一种了，但"实际走的是 AAudio 还是 OpenSL"要看运行日志那条
            // 「独占流已开：… Oboe（AAudio/OpenSL ES）」，这里只说明用的是哪套封装。
            "独占内核" to "OBOE（回调拉模式）",
            "AAudio 播放状态" to aaudioStats,
            "AAudio 独占生效" to if (aaudioActiveNow) {
                "是（绕开 mixer）"
            } else {
                // 带上原因：分不清"本来不该独占"和"想独占但拿不到"，这一行等于没说
                "否 · " + (lastExclusiveDenial ?: "本次还没尝试")
            },
        )

    /** 取不到就是 0 —— 不写"未知"以外的东西，更不猜一个默认档 */
    private fun fmtRate(rate: Int): String = if (rate <= 0) "0" else khzText(rate)

    /**
     * 懒探测（结果只存内存，不落盘）。耗时几十到几百毫秒，**不要在主线程调**。
     */
    fun capabilities(
        controller: AudioOutputController?,
    ): DirectCapabilities {
        cached?.let { return it }
        lastController = controller
        _state.value = "探测中…"
        RuntimeLog.record("输出探测", "开始（${deviceRatesLog(controller)}）")
        val result = try {
            // 先实测一次「本机 sink 能做到几位」：这是「跟随设备」的判据，
            // 也是 `AnnieRenderersFactory` 给 Media3 声明能力的依据（见 `sinkDepth()`）。
            probeSinkDepth(controller?.nativeSampleRate() ?: 0)
            runProbe(controller)
        } catch (t: Throwable) {
            _state.value = "失败：${t::class.java.simpleName}${t.message?.let { " $it" } ?: ""}"
            RuntimeLog.record("输出探测", "失败：${_state.value}")
            throw t
        }
        cached = result
        _state.value = "完成"
        RuntimeLog.record(
            "输出探测",
            "完成：PCM 可建 ${result.pcmRates.size} 档 / 实测建轨 ${result.sinkRates.size} 档",
        )
        return result
    }

    /** 探测起点的一句话（只看路由和声明率，不做建轨） */
    private fun deviceRatesLog(controller: AudioOutputController?): String {
        val rates = controller?.currentOutputDevice()?.sampleRates.orEmpty()
        val native = controller?.nativeSampleRate() ?: 0
        return "native=$native 声明=${rates.joinToString("/").ifEmpty { "无" }}"
    }

    /**
     * 第 ② 层：**真建一次 `AudioTrack`**，只探 `> 192k` 的档位。
     *
     * `pcmRates` 那一层只证明「`AudioFormat` 收这个数字」，超过 192k 之后还有一步：
     * `AudioTrack` 真的建得出来、HAL 真的接。这一步 API 契约管不到，只能实测。
     *
     * 只探高率的原因：
     * - 192k 以内 AOSP 的 HAL 都支持，建轨纯属浪费（一次 1~3 ms，档位还多）；
     * - 高位深也不探：探的是"这个**率**"，用兼容性最好的 16 Bit 才有意义。
     *
     * 结果同时进了 [SystemSinkProbe] 的缓存 —— 播放路径的 `canBuildRate` 复用同一份，
     * 不会因为"面板探过"和"播放要用"各建一次轨。
     */
    private fun probeSinkRates(
        controller: AudioOutputController?,
        deviceRates: List<Int>,
    ): List<Int> {
        val high = (deviceRates + SystemSinkProbe.PROBE_CANDIDATES)
            .filter { it > AUDIO_FORMAT_MAX_RATE && it in MIN_PLAN_RATE..MAX_PLAN_RATE }
            .distinct()
            .sorted()
        if (high.isEmpty()) return emptyList()
        val deviceId = controller?.currentOutputDevice()?.id ?: 0
        val route = controller?.currentRoute() ?: AudioRoute.other
        return high.filter { SystemSinkProbe.canBuild(deviceId, route, it, 16) }
    }

    private fun runProbe(
        controller: AudioOutputController?,
    ): DirectCapabilities {
        val nativeRate = controller?.nativeSampleRate() ?: 0
        val deviceRates = controller?.currentOutputDevice()?.sampleRates.orEmpty()
        val rates = (deviceRates + CANDIDATE_RATES.toList())
            .filter { it in 8_000..768_000 }
            .distinct()
            .sorted()

        // PCM 原始档位：`AudioFormat` 建得出来就算通过。**这只是格式校验边界，
        // 跟输出设备能出什么率毫无关系** —— 设备能力看 `deviceRates`。
        val (pcmRates, rejectedRates) = rates.partition { canBuildPcm(it) }

        return DirectCapabilities(
            pcmRates = pcmRates,
            rejectedRates = rejectedRates,
            nativeRate = nativeRate,
            deviceRates = deviceRates,
            sinkRates = probeSinkRates(controller, deviceRates),
            deviceLabel = buildString {
                val device = controller?.currentOutputDevice()
                if (device == null) {
                    append("未知")
                } else {
                    append(device.name.ifEmpty { "设备 #${device.id}" })
                    if (device.isUsb) append("（USB）")
                    val rates = deviceRates.joinToString(" / ") { fmtRate(it) }
                    if (rates.isNotEmpty()) append(" · 声明 $rates")
                }
            },
        )
    }

    private fun canBuildPcm(rate: Int): Boolean = canBuildFormat(rate, AudioFormat.ENCODING_PCM_16BIT)

    private fun canBuildFormat(rate: Int, encoding: Int): Boolean = runCatching {
        AudioFormat.Builder()
            .setSampleRate(rate)
            .setEncoding(encoding)
            .build()
    }.isSuccess
}
