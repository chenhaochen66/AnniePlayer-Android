package com.annieplayer.annie_music.kt.features.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.annieplayer.annie_music.kt.core.audio.OutputPath
import com.annieplayer.annie_music.kt.core.audio.OutputToggles
import com.annieplayer.annie_music.kt.core.audio.TuningEngine
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.playQualityAutoLabelOf
import com.annieplayer.annie_music.kt.data.playQualityLabelOf
import com.annieplayer.annie_music.kt.data.playQualityTiersOf
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.DropdownPopup
import com.annieplayer.annie_music.kt.widgets.DropdownController
import com.annieplayer.annie_music.kt.widgets.DropdownOption
import com.annieplayer.annie_music.kt.widgets.DropdownOptionRow
import com.annieplayer.annie_music.kt.widgets.DropdownSelectRow
import com.annieplayer.annie_music.kt.widgets.DropdownValueWidth
import com.annieplayer.annie_music.kt.widgets.dropdownFontSizeFor
import com.annieplayer.annie_music.kt.widgets.InfoGlyph
import com.annieplayer.annie_music.kt.widgets.rememberDropdownController
import com.annieplayer.annie_music.kt.widgets.SheetBackButton
import com.annieplayer.annie_music.kt.widgets.clickableNoRipple

/** 分组展开后子项错峰进场延迟 */
private const val MoreSettingsItemStaggerMillis = 25

/**
 * 「强制按源采样率输出」开关的标签与说明 —— **同一个开关在两处渲染，文案只写一份**。
 *
 * 蓝牙分支与系统通路（`系统 · AudioTrack`）分支都会出现它（蓝牙下同样生效），
 * 两处文案一旦分叉，用户会以为行为也不同。
 *
 * ## 文案结构：动作 / 优势 / 代价，各一行
 *
 * 三行分开写而不是堆成一句：用户看开关时先要"它做什么"，
 * 再决定"对我有没有用"（优势），最后确认"我要付什么"（代价）。
 * 行高 80dp 容得下三行 `sm` 字，所以敢这么排。
 *
 * **「强制」两个字是刻意的**：它表达"我方**一定**不做预转换"，与「跟随设备」形成对照；
 * 打开后无论系统会不会跟随源率，我们都不插手那次转换。
 *
 * ## 代价那行不是估的
 *
 * 系统 mixer 的重采样滤波器过渡带比本应用宽（本应用 2% × Nyquist，它大约 0.85–0.9 起滚降），
 * 所以通带上限更低、顶端泛音会少一截。本应用的 SRC 实测残留 −117.5 dB、声道串扰为 0、
 * 最小相位无预振铃 —— 换给系统的确是一次降级，只是对"顶端带编码残留"的素材，
 * 那次降级恰好把沙状底噪一起带走，听感反而更好。这个取舍要摊开讲，不能只说好的一面。
 *
 * 「调音照常生效」不再写进副标题（三行已经够长）：调音引擎就在本页，
 * 而且从严没有"开按源率就关 EQ"这回事了。
 */
private const val FollowSourceLabel = "强制按源采样率输出"

/**
 * 副标题：**行为 → 条件 → 适用面**，一句说完。
 *
 * - 「不做预转换、按源率交付」= 这个开关做了什么（无条件成立）；
 * - 「若需重采样则交由 AudioTrack/SRC 完成」= 加「若」是因为它不一定引发转换，
 *   设备能跟随源率时一次都不转，写成无条件句会让人以为必然转一次；
 * - 「仅推荐国产播放器使用本应用时打开此选项」= 使用建议。国产播放器多数做了采样率跟随，
 *   打开即拿到"零转换"且没有代价；手机、蓝牙耳机这类固定主率的设备打开只是换成
 *   系统重采样 —— 有得有失，所以措辞是"仅推荐"，只给建议、不替用户判断
 *   （判据与两面说明见 `FollowSourceNoticeDialog`）。
 *
 * 优势与代价不进副标题（会把那行撑成一段话，而且只说好的一面等于骗人），
 * 全部搬进 `FollowSourceNoticeDialog`，由标题后的「ⓘ」打开。
 * 蓝牙与系统通路两份**一字不差** —— 两面都一样的开关，说法不该有两套。
 */
private const val FollowSourceSubtitle =
    "不做预转换、按源率交付；若需重采样则交由 AudioTrack/SRC 完成" +
        "（仅推荐国产播放器使用本应用时打开此选项）"

/**
 * 分组头与设置项的**统一行高**。
 *
 * 组按钮要和它自己的设置项一样大，所以两者共用这个高度（内容垂直居中，
 * 副标题限一行避免溢出）。以后加设置项只要用同一高度就不会参差不齐。
 */
private val MoreSettingRowHeight = 80.dp

// ── 设置项模型（新增设置只需在这里加一种项 / 在 moreSettingsGroups 里加一行） ──

/** 单个设置项。新增交互形态时加一个子类，渲染处的 `when` 会因不穷尽而编译报错，不会漏改。 */
sealed interface MoreSettingItem {
    /** 稳定标识（用于列表 key） */
    val key: String

    val label: String
    val subtitle: String
}

/** 开关项 */
data class MoreSettingSwitch(
    override val key: String,
    override val label: String,
    override val subtitle: String,
    val checked: Boolean,
    val onCheckedChange: (Boolean) -> Unit,
    /**
     * 标题后的说明按钮「ⓘ」；`null` = 不加。
     *
     * 与 [MoreSettingDropdown.info] / [MoreSettingInfo.info] 同一套约定：
     * 规则与取舍进弹窗，副标题只留一行"它做什么"。
     * 用于「这一项有两面，一两句话说不完」—— 例：「强制按源采样率输出」的优势与代价。
     */
    val info: (() -> Unit)? = null,
) : MoreSettingItem

/** 动作项（点一下执行，不带开关） */
data class MoreSettingAction(
    override val key: String,
    override val label: String,
    override val subtitle: String,
    val onClick: () -> Unit,
) : MoreSettingItem

/**
 * 只读信息项：不点、不带开关，只把当前链路的**事实**摆出来。
 *
 * 用于「透明模式实况」：透明模式不给任何处理开关，但必须让用户看到系统那一层
 * 到底有没有转采样率 —— 否则只能靠猜（以前靠一个错标的「源 88.2 kHz」）。
 */
/**
 * 只读行的语气。**只影响颜色，不影响任何行为。**
 *
 * 存在的原因：设置页要能一眼区分"正常""有代价""不生效"三种状态，
 * 而这三者以前全用同一种灰字，等于没说。
 */
enum class MoreSettingTone { Normal, Warn, Alert }

data class MoreSettingInfo(
    override val key: String,
    override val label: String,
    override val subtitle: String,
    val tone: MoreSettingTone = MoreSettingTone.Normal,
    /**
     * 标题后的说明按钮「ⓘ」；`null` = 不加。
     *
     * 与 [MoreSettingDropdown.info] 同一套约定：说明不进副标题，单独弹窗。
     * 用于「这一项背后有一套用户看不见的规则」—— 例：透明模式的非独占降级链。
     */
    val info: (() -> Unit)? = null,
) : MoreSettingItem

/** 下拉选项标签 */
data class MoreSettingOption(
    val label: String,
    val selected: Boolean,
    val onSelect: () -> Unit,
    /** false = 灰显不可选（用于"已规划但未实现"的通路） */
    val enabled: Boolean = true,
)

/** 下拉项：右侧一个「当前值」框，点一下在行下方内联列出候选（不做子分组开合） */
data class MoreSettingDropdown(
    override val key: String,
    override val label: String,
    override val subtitle: String,
    val value: String,
    val options: List<MoreSettingOption>,
    /**
     * 标题后的说明按钮「ⓘ」；`null` = 不加。
     *
     * 用于「这一项有例外」的场景（例：DSD 曲目不走重采样目标 / 输出位深）。
     * 说明不进副标题：那两行副标题本来就长，再追加一句会把行撑成一堵墙。
     */
    val info: (() -> Unit)? = null,
    /**
     * `true` = 整行灰显、不可展开。
     *
     * 用于「这一项在当前条件下根本不生效」：DSD 曲目的目标率在 DSD 页、
     * Bit-Perfect 下本应用不改数据 —— 这两种情况还给用户点，只会让他以为设置没生效。
     */
    val locked: Boolean = false,
) : MoreSettingItem

/** 子组（**不可开合**）：一个小标题 + 若干子项，用于在同一分组下再分类 */
data class MoreSettingSubGroup(
    override val key: String,
    val title: String,
    val items: List<MoreSettingItem>,
) : MoreSettingItem {
    override val label: String get() = title
    override val subtitle: String get() = ""
}

/** 分组：一个可展开 / 收起的标题 + 若干设置项 */
data class MoreSettingGroup(
    val title: String,
    val items: List<MoreSettingItem>,
)

/**
 * 组装「更多设置」全部分组。
 *
 * **以后加设置就在这里加**：新建 [MoreSettingSwitch]（或新的 [MoreSettingItem] 子类）
 * 塞进对应分组即可，页面与宿主都不用改。
 */
fun moreSettingsGroups(
    writeQobuzDownloadTranslation: Boolean,
    onToggleWriteQobuzDownloadTranslation: (Boolean) -> Unit,
    playQualityOf: (MusicPlatform) -> Quality?,
    onPickPlayQuality: (MusicPlatform, Quality?) -> Unit,
    writeEnhancedLyrics: Boolean,
    onToggleWriteEnhancedLyrics: (Boolean) -> Unit,
    saveLrcFile: Boolean,
    onToggleSaveLrcFile: (Boolean) -> Unit,
    /** 输出通路（系统 / AAudio 独占 / USB 独占 · 未实现）。**用户唯一要选的"大项"** */
    outputPath: OutputPath = OutputPath.systemMix,
    onPickOutputPath: (OutputPath) -> Unit = {},
    /** 该通路的开关（按源采样率 / Bit Perfect）。开关是**意愿**，不随设备自动关 */
    outputToggles: OutputToggles = OutputToggles(),
    onPickOutputToggles: (OutputToggles) -> Unit = {},
    /**
     * 派生：这一组设置下是否启用 DSP。
     *
     * 由宿主用纯函数 `tuningEnabled(path, toggles)` 算出并传进来 ——
     * 设置页**不自己判断**，否则"能调音"会有第二个判据，早晚和引擎打架。
     */
    tuningEnabled: Boolean = true,
    /**
     * **实际生效**的通路（宿主从 `AudioOutputStatus` 取）。与 [outputPath]（意图）不同即降级。
     *
     * 设置页只拿它改写通路项的副标题；一次性告知交给宿主弹窗，这里不放常驻提示行 ——
     * 常驻行会和设置项抢位置，而且永远是滞后的。
     */
    effectivePath: OutputPath? = null,
    /** 降级原因（一句话）；仅在生效 ≠ 意图时有值 */
    downgradeLabel: String? = null,
    tuningEngine: TuningEngine = TuningEngine.graphic,
    onPickTuningEngine: (TuningEngine) -> Unit = {},
    onOpenTuning: () -> Unit = {},
    tuningSummary: String = "",    loudnessNormalization: Boolean = false,
    onToggleLoudnessNormalization: (Boolean) -> Unit = {},
    /** 响度实况：`本曲 ReplayGain -7.5 dB（已应用）` / `本曲没有 ReplayGain 标签 → 不调整` */
    loudnessSummary: String = "",
    /** 「DSD 输出」入口摘要（`PCM · 176.4 kHz`） */
    dsdSummary: String = "",    /** 打开「DSD 输出」页（宿主渲染，见 `DsdOutputSheet`） */
    onOpenDsdOutput: () -> Unit = {},
    /**
     * 点「强制按源采样率输出」旁的「ⓘ」：那一项的优势与代价（宿主弹窗，本页只转出点击）。
     *
     * 说明**不进副标题** —— 这个开关有两面，写进一行会变成一段话；
     * 只写好处又不诚实。所以副标题留"它做什么"，两面都在弹窗里。
     */
    onShowFollowSourceNotice: () -> Unit = {},
    /** 当前输出是否蓝牙：蓝牙链路由编解码器决定，按源率那几项不适用 */
    isBluetoothOutput: Boolean = false,
    /** 蓝牙链路摘要（`LDAC 96 kHz` 这种），用于替换"跟随设备"文案 */
    bluetoothSummary: String = "",
    /** 当前曲目源采样率；0 = 未知 */
    sourceRate: Int = 0,
    /** 系统**实际**输出率（蓝牙 = codec 协商率；非蓝牙 = mixer 率）；0 = 未知 */
    effectiveRate: Int = 0,
    /** 打开「输出能力（实测）」面板：设备声明率 / PCM 可建档 / AAudio 独占矩阵 */
    onShowCapabilities: () -> Unit = {},
    /** 打开「日志诊断」：运行时状态 / 最近失败 / 建轨写入计数，专供排查问题 */
    onShowDiagnostics: () -> Unit = {},
    outputDetail: String = "",
    /** 已导入的插件数量（0 时提示「暂无已导入插件」） */
    lxPluginCount: Int = 0,
    onManageLxPlugins: () -> Unit = {},
): List<MoreSettingGroup> = listOf(
    MoreSettingGroup(
        title = "播放相关",
        items = listOf(
            MoreSettingSubGroup(
                key = "play-quality",
                title = "播放音质选择",
                items = MusicPlatform.entries
                    .filter { playQualityTiersOf(it).isNotEmpty() }
                    .map { platform ->
                        val current = playQualityOf(platform)
                        val autoLabel = playQualityAutoLabelOf(platform)
                        MoreSettingDropdown(
                            key = "play-quality-${platform.name}",
                            label = platform.label,
                            subtitle = "默认播放音质",
                            value = current?.let { playQualityLabelOf(platform, it) } ?: autoLabel,
                            options = buildList {
                                add(
                                    MoreSettingOption(
                                        label = autoLabel,
                                        selected = current == null,
                                        onSelect = { onPickPlayQuality(platform, null) },
                                    ),
                                )
                                for (tier in playQualityTiersOf(platform)) {
                                    add(
                                        MoreSettingOption(
                                            label = playQualityLabelOf(platform, tier),
                                            selected = current == tier,
                                            onSelect = { onPickPlayQuality(platform, tier) },
                                        ),
                                    )
                                }
                            },
                        )
                    },
            ),
        ),
    ),
    MoreSettingGroup(
        title = "音频输出",
        items = buildList {
            // ── 本机输出能力（实测）──
            // **永远展示、不看模式也不看路由**：它列的是"这台机器能做到什么"，
            // 而"现在走的是哪一级"由上面的输出方式决定。以前它藏在「透明 + 非蓝牙」
            // 里，于是调音用户和蓝牙用户都看不到这台机器的真实上限
            // （包括 AAudio 独占能不能开、framework 放没放开 192k），这属于知情权问题。
            add(
                MoreSettingAction(
                    key = "audio-direct-caps",
                    label = "输出能力（实测）",
                    subtitle = "设备声明率 · PCM 建轨 · AAudio 独占",
                    onClick = onShowCapabilities,
                ),
            )
            // ── DSD 输出：一条独立的轴（模式 / 目标率 / 为什么 DoP 是灰的） ──
            // 单独成页而不是在这里摊开：它和下面「输出方式」长得像，摊开会让人以为是同一件事，
            // 而它恰恰是"DSD 不走下面那些设置"的说明处。
            add(
                MoreSettingAction(
                    key = "audio-dsd-output",
                    label = "DSD 输出",
                    subtitle = dsdSummary.ifEmpty { "PCM / DoP / Native · DSD 转 PCM 目标率" },
                    onClick = onOpenDsdOutput,
                ),
            )
            // ── 轴 3：输出方式 / 通路 ──
            // 蓝牙下**不再藏起通路选择**：链路参数由 codec 决定，选什么都落回系统通路，
            // 但"意图"必须留得住 —— 拔掉蓝牙后立刻按它走，不必回来重选一遍。
            // 落回的事实由副标题与宿主的降级弹窗说清（`OutputDowngrade`）。
            // ── 输出通路：**唯一要选的"大项"** ──
            // 以前用户先选「透明 / 调音」这个抽象模式，再猜它能推出什么；
            // 现在只选"从哪出去"，开关是这条通路的属性，
            // "能不能调音"由宿主用 `tuningEnabled(path, toggles)` 派生出来。
            add(
                MoreSettingDropdown(
                    key = "audio-output-path",
                    label = "输出通路",
                    // 副标题说**这条路怎么走**（静态），不是"现在落在哪一级"（动态）。
                    // 动态结果在下面的「链路实况」里 —— 两者放错位置会互相打架：
                    // 以前这里复用了降级链文案，头一句还是"非 USB 独占"，
                    // 而通路明明已经三选一了。
                    subtitle = when (outputPath) {
                        OutputPath.systemMix ->
                            "经系统 mixer 输出 · 由开关决定是否按源率送出"
                        OutputPath.aaudioExclusive ->
                            "绕开系统 mixer · 不能独占时回落系统通路"
                        OutputPath.usbDirect ->
                            "直接写 USB 端点 · 绕开 AudioFlinger（需 Android 14+，独占期间设备对系统不可见）"
                    }.let { base ->
                        // 生效 ≠ 意图：把"现在实际走哪条"直接写在副标题上，
                        // 常驻提示行由此消失（一次性的告知在宿主的降级弹窗里）。
                        // 蓝牙优先说，因为那时选哪条都一样落回系统通路。
                        when {
                            isBluetoothOutput ->
                                "蓝牙链路由编解码器决定 · 现在一律走系统通路"
                            effectivePath != null && effectivePath != outputPath ->
                                "实际：${effectivePath.label}" +
                                    (downgradeLabel?.let { " · $it" } ?: "")
                            else -> base
                        }
                    },
                    value = outputPath.label,
                    options = buildList {
                        for (path in OutputPath.available) {
                            add(
                                MoreSettingOption(
                                    label = path.label,
                                    selected = path == outputPath,
                                    onSelect = { onPickOutputPath(path) },
                                ),
                            )
                        }
                        for (planned in OutputPath.planned) {
                            add(
                                MoreSettingOption(
                                    label = planned.label + "（未实现）",
                                    selected = false,
                                    enabled = false,
                                    onSelect = {},
                                ),
                            )
                        }
                    },
                    // 「输出通路」的说明按钮已删除：那段文案讲的是「非 USB 独占」三级降级链，
                    // 而 bitPerfectMixer 通路整个枚举值都已移除，内容过时。
                ),
            )
            // 蓝牙链路摘要：codec 协商出来的参数。以前它冒充「输出方式」（整组被藏起来
            // 只好拿一行事实顶上），现在通路可选了，它就只是**链路本身的说明**。
            if (isBluetoothOutput) {
                add(
                    MoreSettingInfo(
                        key = "audio-path-bluetooth",
                        label = "蓝牙链路",
                        subtitle = "由编解码器决定" +
                            if (bluetoothSummary.isEmpty()) "" else " · $bluetoothSummary",
                    ),
                )
            }
            // ── 该通路的开关：只列和当前通路相关的那几个 ──
            // 开关是**意愿**，不因当前设备不支持而自动关掉 ——
            // 换到支持的设备它立刻生效，换回来它还在（提示"当前不生效"）。
            //
            // 蓝牙下按**系统通路**选：那时 a2dp 线程接管一切，`bitPerfect` 没有意义，
            // 而「强制按源采样率」有效，必须露出来。
            when (if (isBluetoothOutput) OutputPath.systemMix else outputPath) {
                OutputPath.systemMix -> {
                    // 蓝牙下这一项**也要显示、也要真的生效**。
                    //
                    // 以前蓝牙时整块不渲染，理由是"a2dp 线程的率由 codec 决定，按源率等于把
                    // 44.1→48 推给 AudioFlinger，纯亏"。用户实测推翻了那个取舍：在固定主率的
                    // 设备上，**交给系统重采样反而没有那层沙状底噪**（记录在
                    // `native-r8b-resampler.cpp` 的 `kTransBand` 注释里）。
                    //
                    // 所以 `followSourceRateActive` 不再排除蓝牙 —— 显示的开关和实际行为
                    // 必须一致，否则用户会以为它坏了。
                    add(
                        MoreSettingSwitch(
                            key = "audio-toggle-follow-source",
                            label = FollowSourceLabel,
                            subtitle = FollowSourceSubtitle,
                            checked = outputToggles.followSourceRate,
                            onCheckedChange = {
                                onPickOutputToggles(outputToggles.copy(followSourceRate = it))
                            },
                            info = onShowFollowSourceNotice,
                        ),
                    )
                }

                OutputPath.aaudioExclusive -> {
                    add(
                        MoreSettingSwitch(
                            key = "audio-toggle-bit-perfect",
                            label = "Bit Perfect",
                            subtitle = "不调音、不改率；关闭后允许调音，但仍走独占流",
                            checked = outputToggles.bitPerfect,
                            onCheckedChange = {
                                onPickOutputToggles(outputToggles.copy(bitPerfect = it))
                            },
                        ),
                    )
                }

                OutputPath.usbDirect -> {
                    add(
                        MoreSettingSwitch(
                            key = "audio-toggle-bit-perfect",
                            label = "Bit Perfect",
                            subtitle = "不调音、不改率；关闭后允许调音，但仍走独占流",
                            checked = outputToggles.bitPerfect,
                            onCheckedChange = {
                                onPickOutputToggles(outputToggles.copy(bitPerfect = it))
                            },
                        ),
                    )
                }
            }
            // ── 调音：**派生**出来的，不是用户选项 ──
            // 判据只有一个（宿主的 `tuningEnabled`），本页不自己判断 ——
            // 否则"能不能调音"会有第二个判据，早晚和引擎打架。
            //
            // ⚠️ 「调音引擎」这一项**不能放进 `if (tuningEnabled)`**：
            // 引擎 = 无 本身就是让 `tuningEnabled` 变 false 的原因，放进去就成了死锁 ——
            // 选了「无」之后整块连带本下拉一起消失，再也切不回来。
            add(
                MoreSettingDropdown(
                    key = "audio-tuning-engine",
                    label = "调音引擎",
                    subtitle = when {
                        tuningEngine == TuningEngine.none ->
                            "不做任何 DSP；换通路也不影响这一项"
                        !tuningEnabled ->
                            "当前通路要求原样送出，调音不生效"
                        else ->
                            "均衡器 / PEQ / MSEB 互斥；切换立即生效"
                    },
                    value = tuningEngine.label,
                    options = TuningEngine.entries.map { engine ->
                        MoreSettingOption(
                            label = engine.label,
                            selected = engine == tuningEngine,
                            onSelect = { onPickTuningEngine(engine) },
                        )
                    },
                ),
            )
            // 下面这些只有「真的要调音」时才有意义 —— 引擎 = 无，或通路要求原样送出，
            // 全部藏起来（留着开关却不起作用，比不显示更糟）。
            if (tuningEnabled) {
                add(
                    MoreSettingAction(
                        key = "audio-tuning-open",
                        label = "调音设置",
                        subtitle = tuningSummary.ifEmpty { "进入均衡器 / PEQ / MSEB 编辑" },
                        onClick = onOpenTuning,
                    ),
                )
                add(
                    MoreSettingSwitch(
                        key = "audio-loudness",
                        label = "响度归一化",
                        subtitle = when {
                            !loudnessNormalization ->
                                "按 ReplayGain 标签调整增益；无标签的歌曲不做处理"
                            loudnessSummary.isNotEmpty() -> loudnessSummary
                            else -> "按 ReplayGain 标签调整增益"
                        },
                        checked = loudnessNormalization,
                        onCheckedChange = onToggleLoudnessNormalization,
                    ),
                )
                // 「自选采样率」（重采样目标下拉）已整块删除。
                // 采样率现在只有一个旋钮 = 上面的「按源采样率输出」开关：
                //   开 → 我方不预转换，按源率建轨；
                //   关 → 我方 SRC 到设备实际会用的率。
                // 两个旋钮管同一件事只会自相矛盾，这也是原来那个下拉存在的唯一理由消失的原因。
            }
        },
    ),
    MoreSettingGroup(
        title = "插件设置",
        items = listOf(
            // 「应用插件」开关已删除：国内四平台的播放/下载都只有插件这一条路
            //（内置直链接口已移除，没有任何降级/回退），没有"要不要用插件"可选项了。
            MoreSettingAction(
                key = "lx-plugin-manage",
                label = "插件管理",
                subtitle = if (lxPluginCount == 0) {
                    "暂无已导入插件 · 国内四平台的下载需要在插件解析"
                } else {
                    "已导入 $lxPluginCount 个插件"
                },
                onClick = onManageLxPlugins,
            ),
        ),
    ),
    MoreSettingGroup(
        title = "下载相关",
        items = listOf(
            MoreSettingSwitch(
                key = "write-enhanced-lyrics",
                label = "下载歌词写入逐字（增强 LRC）",
                subtitle = "把逐字歌词写进歌词文件；该平台没有逐字数据时仍写普通 LRC",
                checked = writeEnhancedLyrics,
                onCheckedChange = onToggleWriteEnhancedLyrics,
            ),
            MoreSettingSwitch(
                key = "save-lrc-file",
                label = "保存 lrc 文件",
                subtitle = "下载时在歌曲同目录另存一份同名 .lrc（内容与内嵌歌词一致）",
                checked = saveLrcFile,
                onCheckedChange = onToggleSaveLrcFile,
            ),
        ),
    ),
    MoreSettingGroup(
        title = "Qobuz 相关",
        items = listOf(
            MoreSettingSwitch(
                key = "qobuz-download-translation",
                label = "下载时写入翻译歌词",
                subtitle = "下载 Qobuz 歌曲时弹出语言选择，选中的翻译写进歌词文件",
                checked = writeQobuzDownloadTranslation,
                onCheckedChange = onToggleWriteQobuzDownloadTranslation,
            ),
        ),
    ),
    MoreSettingGroup(
        title = "诊断",
        items = listOf(
            MoreSettingAction(
                key = "diagnostics",
                label = "日志诊断",
                subtitle = "应用内运行时事件（不经过 logcat），排查问题用",
                onClick = onShowDiagnostics,
            ),
        ),
    ),
)

/**
 * 更多设置整页（单开文件；交互与「下载任务」页 [com.annieplayer.annie_music.kt.widgets.DownloadQueueSheet] 一致）。
 *
 * - 打开 / 关闭：遮罩淡入淡出 + 全屏面板自下而上滑入滑出（150ms，与下载任务页同参数）。
 * - 返回手势：`BackHandler(enabled = visible)` 关页。
 * - 面板自己吃掉点击（否则点空白会穿透到整屏遮罩触发关闭，表现为「一碰就返回」）。
 * - 内容由 [groups] 驱动：组头点击展开 / 收起（chevron 旋转 180° + 子项错峰进场，
 *   交互与搜索模式下拉一致）。**加设置只改 [moreSettingsGroups]，本函数不用动。**
 */
@Composable
fun MoreSettingsScreen(
    visible: Boolean,
    groups: List<MoreSettingGroup>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 记住哪些组是展开的（按组标题）；关页后清空 → 下次进来全是收起的
    var expandedGroups by remember { mutableStateOf(emptySet<String>()) }
    /**
     * 下拉控制器：**打开的是谁** + 防抖 + 返回处理，全在 `DropdownController` 里。
     *
     * 必须提到页面级、且以**对象**形式传给行：状态留在行自己身上时，
     * 页面级的返回处理器不知道有下拉开着（返回会把整页关掉）。
     * 那套踩坑细节见 `widgets/DropdownSelect.kt` 的文件头。
     */
    val dropdown = rememberDropdownController()
    LaunchedEffect(visible) {
        if (!visible) {
            expandedGroups = emptySet()
            dropdown.reset()
        }
    }

    BackHandler(enabled = visible) {
        // 有下拉开着 → 只收下拉；刚被手势误触关掉 → 吃掉这次返回；否则关页
        if (!dropdown.handleBack()) onClose()
    }

    val scrimAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.easeInOutQuad),
        label = "moreSettingsScrim",
    )
    val slide by animateFloatAsState(
        targetValue = if (visible) 0f else 1f,
        animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.easeInOutQuad),
        label = "moreSettingsSlide",
    )

    // 【关键 · 全屏浮层不得吃掉下层点击】完全隐藏时整个浮层不参与组合。
    if (!visible && scrimAlpha == 0f) return

    Box(modifier = modifier.fillMaxSize()) {
        // ── 遮罩：只吞点击，**不关闭**（退出只走返回手势 / 顶栏返回箭头） ──
        // `clickable` 必须保留：吃掉触摸、阻止点击穿透到下层页面。
        val scrimInteraction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = scrimAlpha }
                .clickable(
                    enabled = visible,
                    interactionSource = scrimInteraction,
                    indication = null,
                    onClick = {},
                )
                .background(AppColors.scrim50),
        )

        // ── 全屏面板：自下而上滑入 / 滑出 ──
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = slide * size.height }
                .background(AppColors.background)
                // 面板必须自己吃掉点击：background 不参与命中测试，不吃就会穿透到整屏遮罩 → 一碰就返回
                .clickableNoRipple { }
                .systemBarsPadding(),
        ) {
            MoreSettingsHeader(onClose = onClose)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AppSpacing.sp24),
            ) {
                for (group in groups) {
                    val expanded = group.title in expandedGroups
                    MoreSettingsGroupHeader(
                        title = group.title,
                        expanded = expanded,
                        onToggle = {
                            expandedGroups = if (expanded) {
                                expandedGroups - group.title
                            } else {
                                expandedGroups + group.title
                            }
                        },
                    )
                    MoreSettingsExpandable(visible = expanded) {
                        for (item in group.items) {
                            MoreSettingItemRow(item = item, dropdown = dropdown)
                            Spacer(modifier = Modifier.height(AppSpacing.sp8))
                        }
                    }
                }
                // 底部留白避开迷你播放条（本页声明在底栏之前，迷你条会浮在上方）
                Spacer(modifier = Modifier.height(AppSpacing.sheetBottomInset))
            }
        }
    }
}

/** 设置项渲染：新加 [MoreSettingItem] 子类时此 `when` 不穷尽会编译失败，从而不会漏实现 */
@Composable
private fun MoreSettingItemRow(
    item: MoreSettingItem,
    /** 页面级的下拉控制器（见 `MoreSettingsScreen`；必须传**对象**，不能传解构后的值） */
    dropdown: DropdownController,
) {
    when (item) {
        is MoreSettingSwitch -> MoreSettingsSwitchRow(
            label = item.label,
            subtitle = item.subtitle,
            checked = item.checked,
            onCheckedChange = item.onCheckedChange,
            info = item.info,
        )

        is MoreSettingAction -> MoreSettingsActionRow(
            label = item.label,
            subtitle = item.subtitle,
            onClick = item.onClick,
        )

        // 下拉行直接交给通用组件（和 DSD 页的档位选择是同一份实现）
        is MoreSettingDropdown -> DropdownSelectRow(
            selectKey = item.key,
            label = item.label,
            value = item.value,
            subtitle = item.subtitle,
            info = item.info,
            locked = item.locked,
            options = item.options.map { option ->
                DropdownOption(
                    label = option.label,
                    selected = option.selected,
                    enabled = option.enabled,
                    onSelect = option.onSelect,
                )
            },
            controller = dropdown,
        )

        is MoreSettingInfo -> MoreSettingsInfoRow(
            label = item.label,
            subtitle = item.subtitle,
            tone = item.tone,
            info = item.info,
        )

        is MoreSettingSubGroup -> MoreSettingsSubGroupBlock(item, dropdown)
    }
}

/** 只读行：无 chevron、不可点（承载「透明模式实况」这类纯展示信息） */
@Composable
private fun MoreSettingsInfoRow(
    label: String,
    subtitle: String,
    /** 只影响颜色：正常 / 有代价 / 不生效 */
    tone: MoreSettingTone = MoreSettingTone.Normal,
    /** 标题后的「ⓘ」；`null` = 不加 */
    info: (() -> Unit)? = null,
) {
    val labelColor = when (tone) {
        MoreSettingTone.Normal -> AppColors.textPrimary
        MoreSettingTone.Warn -> AppColors.warning
        MoreSettingTone.Alert -> AppColors.danger
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MoreSettingRowHeight)
            .background(AppColors.whiteA05, RoundedCornerShape(AppRadius.card))
            .padding(AppSpacing.sp16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    color = labelColor,
                    fontSize = AppFontSize.size15,
                    fontWeight = FontWeight.Medium,
                )
                info?.let { onInfo ->
                    Spacer(modifier = Modifier.width(AppSpacing.sp6))
                    Box(
                        modifier = Modifier
                            .clickableNoRipple(onInfo)
                            .padding(AppSpacing.sp2),
                    ) {
                        InfoGlyph(
                            tint = AppColors.glyphMid,
                            modifier = Modifier.size(AppIconSize.icon13),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(AppSpacing.sp1))
            Text(
                text = subtitle,
                color = AppColors.textTertiary,
                fontSize = AppFontSize.sm,
            )
        }
    }
}

/** 动作行：标题 + 副标题 + 右侧 chevron（整行可点） */
@Composable
private fun MoreSettingsActionRow(
    label: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MoreSettingRowHeight)
            .background(AppColors.whiteA05, RoundedCornerShape(AppRadius.card))
            .clickableNoRipple(onClick)
            .padding(AppSpacing.sp16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = AppColors.textPrimary,
                fontSize = AppFontSize.size15,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp1))
            Text(
                text = subtitle,
                color = AppColors.textTertiary,
                fontSize = AppFontSize.sm,
            )
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp12))
        Canvas(modifier = Modifier.size(AppIconSize.icon16)) {
            val w = size.width
            val h = size.height
            val stroke = w * 0.15f
            drawLine(AppColors.textSecondary, Offset(w * 0.36f, h * 0.25f), Offset(w * 0.62f, h * 0.50f), stroke, StrokeCap.Round)
            drawLine(AppColors.textSecondary, Offset(w * 0.36f, h * 0.75f), Offset(w * 0.62f, h * 0.50f), stroke, StrokeCap.Round)
        }
    }
}

/** 子组：一个小标题 + 子项（**不可开合**） */
@Composable
private fun MoreSettingsSubGroupBlock(group: MoreSettingSubGroup, dropdown: DropdownController) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = group.title,
            modifier = Modifier.padding(top = AppSpacing.sp4, bottom = AppSpacing.sp8),
            color = AppColors.textSecondary,
            fontSize = AppFontSize.size13,
            fontWeight = FontWeight.Medium,
        )
        for (child in group.items) {
            MoreSettingItemRow(child, dropdown)
            Spacer(modifier = Modifier.height(AppSpacing.sp8))
        }
    }
}

/** Header：返回箭头 + 「更多设置」 */
@Composable
private fun MoreSettingsHeader(onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = AppSpacing.sp24, top = AppSpacing.sp40, end = AppSpacing.sp24, bottom = AppSpacing.sp8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 统一的返回按钮（见 `SheetBackButton`）
        SheetBackButton(onClose = onClose)
        Spacer(modifier = Modifier.width(AppSpacing.sp8))
        Text(
            text = "更多设置",
            color = AppColors.textPrimary,
            fontSize = AppFontSize.size20,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** 分组头（可展开 / 收起；chevron 随开合旋转 180°）。
 *  卡片规格与菜单抽屉行 [com.annieplayer.annie_music.kt.widgets.MenuDrawer] 的 `MenuDrawerRow` 一致：
 *  `whiteA03` 底 + 圆角 12 + 内边距 16 + 标题 15sp/w600/白。 */
@Composable
private fun MoreSettingsGroupHeader(title: String, expanded: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.easeInOutQuad),
        label = "moreSettingsChevron",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = AppSpacing.sp12)
            .height(MoreSettingRowHeight)
            .clip(RoundedCornerShape(AppRadius.card))
            .background(AppColors.whiteA03)
            .clickableNoRipple(onToggle)
            .padding(AppSpacing.sp16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            color = AppColors.white,
            fontSize = AppFontSize.size15,
            fontWeight = AppFontWeight.semiBold,
        )
        Spacer(modifier = Modifier.width(AppSpacing.sp10))
        Canvas(modifier = Modifier.size(AppIconSize.icon16).graphicsLayer { rotationZ = rotation }) {
            val w = size.width
            val h = size.height
            val stroke = w * 0.15f
            drawLine(AppColors.textSecondary, Offset(w * 0.25f, h * 0.36f), Offset(w * 0.50f, h * 0.62f), stroke, StrokeCap.Round)
            drawLine(AppColors.textSecondary, Offset(w * 0.75f, h * 0.36f), Offset(w * 0.50f, h * 0.62f), stroke, StrokeCap.Round)
        }
    }
}

/** 分组展开容器：纵向展开 / 收起 + 子项错峰进场（对齐搜索模式下拉的展开收起手感） */
@Composable
private fun MoreSettingsExpandable(visible: Boolean, content: @Composable () -> Unit) {
    var itemsShown by remember { mutableStateOf(false) }
    LaunchedEffect(visible) { itemsShown = visible }

    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(
            animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.easeInOutQuad),
        ) + fadeIn(
            animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.easeInOutQuad),
        ),
        exit = shrinkVertically(
            animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.easeInOutQuad),
        ) + fadeOut(
            animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.easeInOutQuad),
        ),
    ) {
        AnimatedVisibility(
            visible = itemsShown,
            enter = fadeIn(
                tween(
                    durationMillis = AppDuration.sheet,
                    delayMillis = MoreSettingsItemStaggerMillis,
                    easing = AppEasing.easeInOutQuad,
                ),
            ) + slideInVertically(
                animationSpec = tween(
                    durationMillis = AppDuration.sheet,
                    delayMillis = MoreSettingsItemStaggerMillis,
                    easing = AppEasing.easeInOutQuad,
                ),
            ) { fullHeight -> -fullHeight / 6 },
            exit = ExitTransition.None,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) { content() }
        }
    }
}

/** 开关行：标题（可带「ⓘ」）+ 副标题 + 右侧 Switch（整行可点，点标题等同切换） */
@Composable
private fun MoreSettingsSwitchRow(
    label: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    /** 标题后的「ⓘ」；`null` = 不加 */
    info: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MoreSettingRowHeight)
            .background(AppColors.whiteA05, RoundedCornerShape(AppRadius.card))
            .clickableNoRipple { onCheckedChange(!checked) }
            .padding(AppSpacing.sp16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    color = AppColors.textPrimary,
                    fontSize = AppFontSize.size15,
                    fontWeight = FontWeight.Medium,
                )
                // 「ⓘ」必须自己吞掉点击：整行是"点哪儿都切换开关"，
                // 不 `clickableNoRipple` 的话点它会把开关一起翻过去（比没有按钮更糟）。
                info?.let { onInfo ->
                    Spacer(modifier = Modifier.width(AppSpacing.sp6))
                    Box(
                        modifier = Modifier
                            .clickableNoRipple(onInfo)
                            .padding(AppSpacing.sp2),
                    ) {
                        InfoGlyph(
                            tint = AppColors.glyphMid,
                            modifier = Modifier.size(AppIconSize.icon13),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(AppSpacing.sp1))
            Text(
                text = subtitle,
                color = AppColors.textTertiary,
                fontSize = AppFontSize.sm,
            )
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp12))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AppColors.background,
                checkedTrackColor = AppColors.white,
                uncheckedThumbColor = AppColors.white54,
                uncheckedTrackColor = AppColors.whiteA06,
            ),
        )
    }
}
