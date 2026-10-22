package com.annieplayer.annie_music.kt.features.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.core.audio.AudioOutputMode
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
import com.annieplayer.annie_music.kt.widgets.clickableNoRipple

/** 分组展开后子项错峰进场延迟 */
private const val MoreSettingsItemStaggerMillis = 25

/** 重采样可选目标率（0 = 跟随设备原生率） */
private val ResampleRates: List<Int> = listOf(
    0, 44_100, 48_000, 88_200, 96_000, 176_400, 192_000, 352_800, 384_000, 705_600, 768_000,
)

private fun formatRateKhz(rate: Int): String =
    if (rate % 1000 == 0) "${rate / 1000} kHz" else "%.1f kHz".format(rate / 1000f)

private fun resampleRateLabel(rate: Int, autoTargetRate: Int = 0): String {
    if (rate > 0) return formatRateKhz(rate)
    return if (autoTargetRate > 0) {
        "跟随设备（${formatRateKhz(autoTargetRate)}）"
    } else {
        "跟随设备（蓝牙旁路）"
    }
}

private fun depthLabel(depth: Int): String = when (depth) {
    16 -> "16 Bit"
    24 -> "24 Bit"
    32 -> "32 Bit (Float)"
    else -> "跟随源"
}

/** 只列出当前设备声明支持的档位；0 = 跟随设备。设备列表为空时退回完整候选。 */
private fun resampleOptions(deviceSampleRates: List<Int>, current: Int): List<Int> {
    val supported = deviceSampleRates
        .filter { it in 32_000..768_000 }
        .distinct()
        .sorted()
    if (supported.isEmpty()) return ResampleRates
    val result = mutableListOf(0)
    result.addAll(supported)
    // 设备切换后当前选中档可能已不支持，仍列出来方便用户改回去
    if (current > 0 && current !in result) result.add(current)
    return result.distinct().sorted()
}

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
) : MoreSettingItem

/** 动作项（点一下执行，不带开关） */
data class MoreSettingAction(
    override val key: String,
    override val label: String,
    override val subtitle: String,
    val onClick: () -> Unit,
) : MoreSettingItem

/** 下拉候选里的一个选项 */
data class MoreSettingOption(
    val label: String,
    val selected: Boolean,
    val onSelect: () -> Unit,
)

/** 下拉项：右侧一个「当前值」框，点一下在行下方内联列出候选（不做子分组开合） */
data class MoreSettingDropdown(
    override val key: String,
    override val label: String,
    override val subtitle: String,
    val value: String,
    val options: List<MoreSettingOption>,
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
    audioMode: AudioOutputMode = AudioOutputMode.transparent,
    onPickAudioMode: (AudioOutputMode) -> Unit = {},
    tuningEngine: TuningEngine = TuningEngine.graphic,
    onPickTuningEngine: (TuningEngine) -> Unit = {},
    onOpenTuning: () -> Unit = {},
    tuningSummary: String = "",    loudnessNormalization: Boolean = false,
    onToggleLoudnessNormalization: (Boolean) -> Unit = {},
    forceNativeRate: Boolean = false,
    onToggleForceNativeRate: (Boolean) -> Unit = {},
    resampleRate: Int = 0,
    onPickResampleRate: (Int) -> Unit = {},
    resampleDepth: Int = 0,
    onPickResampleDepth: (Int) -> Unit = {},
    autoTargetRate: Int = 0,
    deviceSampleRates: List<Int> = emptyList(),
    outputDetail: String = "",
    lxPluginEnabled: Boolean = false,
    lxPluginCount: Int = 0,
    onToggleLxPlugin: (Boolean) -> Unit = {},
    onImportLxPluginLocal: () -> Unit = {},
    onImportLxPluginOnline: () -> Unit = {},
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
            add(
                MoreSettingDropdown(
                    key = "audio-output-mode",
                    label = "输出模式",
                    subtitle = outputDetail.ifEmpty { "透明 = DSP 全旁路；调音 = 均衡器 / PEQ / MSEB + 响度 + 限幅" },
                    value = audioMode.label,
                    options = AudioOutputMode.entries.map { mode ->
                        MoreSettingOption(
                            label = mode.label,
                            selected = mode == audioMode,
                            onSelect = { onPickAudioMode(mode) },
                        )
                    },
                ),
            )
            // 透明模式下 DSP 整体旁路，这些选项放出来只会误导（改了也听不出来）
            if (audioMode == AudioOutputMode.tuned) {
                add(
                    MoreSettingDropdown(
                        key = "audio-tuning-engine",
                        label = "调音引擎",
                        subtitle = "均衡器 / PEQ / MSEB 互斥；切换立即生效",
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
                        subtitle = "按 ReplayGain 标签调整增益；无标签的歌曲不做处理",
                        checked = loudnessNormalization,
                        onCheckedChange = onToggleLoudnessNormalization,
                    ),
                )
            }
            add(
                MoreSettingSwitch(
                    key = "audio-force-native-rate",
                    label = "无 SRC 输出",
                    subtitle = "输出前先统一采样率，避免系统混音器再转换一次",
                    checked = forceNativeRate,
                    onCheckedChange = onToggleForceNativeRate,
                ),
            )
            if (forceNativeRate) {
                add(
                    MoreSettingDropdown(
                        key = "audio-resample-rate",
                        label = "重采样目标",
                        subtitle = "跟随设备会按 USB / 有线 / 蓝牙自动选择，也可以手动指定固定档位",
                        value = resampleRateLabel(resampleRate, autoTargetRate),
                        options = resampleOptions(deviceSampleRates, resampleRate).map { rate ->
                            MoreSettingOption(
                                label = if (rate == 0) {
                                    resampleRateLabel(0, autoTargetRate)
                                } else {
                                    formatRateKhz(rate)
                                },
                                selected = rate == resampleRate,
                                onSelect = { onPickResampleRate(rate) },
                            )
                        },
                    ),
                )
                add(
                    MoreSettingDropdown(
                        key = "audio-resample-depth",
                        label = "输出位深",
                        subtitle = "跟随源 = 保持解码位深；32 Bit 走 float，精度最高",
                        value = depthLabel(resampleDepth),
                        options = listOf(0, 16, 24, 32).map { depth ->
                            MoreSettingOption(
                                label = depthLabel(depth),
                                selected = depth == resampleDepth,
                                onSelect = { onPickResampleDepth(depth) },
                            )
                        },
                    ),
                )
            }
        },
    ),
    MoreSettingGroup(
        title = "落雪音源",
        items = listOf(
            MoreSettingSwitch(
                key = "lx-plugin-enabled",
                label = "启用落雪插件",
                subtitle = "国内四平台由落雪插件解析；无可用插件时仍走内置并提示",
                checked = lxPluginEnabled,
                onCheckedChange = onToggleLxPlugin,
            ),
            MoreSettingAction(
                key = "lx-plugin-import-local",
                label = "本地导入插件",
                subtitle = "选择一个 .js 插件文件",
                onClick = onImportLxPluginLocal,
            ),
            MoreSettingAction(
                key = "lx-plugin-import-online",
                label = "在线导入插件",
                subtitle = "粘贴 http/https 直链",
                onClick = onImportLxPluginOnline,
            ),
            MoreSettingAction(
                key = "lx-plugin-manage",
                label = "插件管理",
                subtitle = if (lxPluginCount == 0) "暂无已导入插件" else "已导入 $lxPluginCount 个插件",
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
    BackHandler(enabled = visible) { onClose() }

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

    // 记住哪些组是展开的（按组标题）；关页后清空 → 下次进来全是收起的
    var expandedGroups by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(visible) { if (!visible) expandedGroups = emptySet() }

    // 【关键 · 全屏浮层不得吃掉下层点击】完全隐藏时整个浮层不参与组合。
    if (!visible && scrimAlpha == 0f) return

    Box(modifier = modifier.fillMaxSize()) {
        // ── 遮罩（隐藏时不启用 → 不吃触摸） ──
        val scrimInteraction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = scrimAlpha }
                .clickable(
                    enabled = visible,
                    interactionSource = scrimInteraction,
                    indication = null,
                    onClick = onClose,
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
                            MoreSettingItemRow(item)
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
private fun MoreSettingItemRow(item: MoreSettingItem) {
    when (item) {
        is MoreSettingSwitch -> MoreSettingsSwitchRow(
            label = item.label,
            subtitle = item.subtitle,
            checked = item.checked,
            onCheckedChange = item.onCheckedChange,
        )

        is MoreSettingAction -> MoreSettingsActionRow(
            label = item.label,
            subtitle = item.subtitle,
            onClick = item.onClick,
        )

        is MoreSettingDropdown -> MoreSettingsDropdownRow(item)

        is MoreSettingSubGroup -> MoreSettingsSubGroupBlock(item)
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
private fun MoreSettingsSubGroupBlock(group: MoreSettingSubGroup) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = group.title,
            modifier = Modifier.padding(top = AppSpacing.sp4, bottom = AppSpacing.sp8),
            color = AppColors.textSecondary,
            fontSize = AppFontSize.size13,
            fontWeight = FontWeight.Medium,
        )
        for (child in group.items) {
            MoreSettingItemRow(child)
            Spacer(modifier = Modifier.height(AppSpacing.sp8))
        }
    }
}

/** 下拉行：标题(+副标题) + 右侧「当前值」框；点一下在下方内联展开候选（不做子分组开合） */
@Composable
private fun MoreSettingsDropdownRow(item: MoreSettingDropdown) {
    var expanded by remember(item.key) { mutableStateOf(false) }
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.easeInOutQuad),
        label = "moreSettingsDropdownChevron",
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MoreSettingRowHeight)
                .background(AppColors.whiteA05, RoundedCornerShape(AppRadius.card))
                .clickableNoRipple { expanded = !expanded }
                .padding(AppSpacing.sp16),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.label,
                    color = AppColors.textPrimary,
                    fontSize = AppFontSize.size15,
                    fontWeight = FontWeight.Medium,
                )
                if (item.subtitle.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(AppSpacing.sp1))
                    Text(
                        text = item.subtitle,
                        color = AppColors.textTertiary,
                        fontSize = AppFontSize.sm,
                    )
                }
            }
            Spacer(modifier = Modifier.width(AppSpacing.sp12))
            Text(
                text = item.value,
                color = AppColors.textPrimary,
                fontSize = AppFontSize.size13,
                modifier = Modifier
                    .background(AppColors.whiteA15, RoundedCornerShape(AppRadius.r8))
                    .padding(horizontal = AppSpacing.sp10, vertical = AppSpacing.sp6),
            )
            Spacer(modifier = Modifier.width(AppSpacing.sp8))
            Canvas(modifier = Modifier.size(AppIconSize.icon16).graphicsLayer { rotationZ = chevron }) {
                val w = size.width
                val h = size.height
                val stroke = w * 0.15f
                drawLine(AppColors.textSecondary, Offset(w * 0.25f, h * 0.36f), Offset(w * 0.50f, h * 0.62f), stroke, StrokeCap.Round)
                drawLine(AppColors.textSecondary, Offset(w * 0.75f, h * 0.36f), Offset(w * 0.50f, h * 0.62f), stroke, StrokeCap.Round)
            }
        }

        AnimatedVisibility(
            visible = expanded,
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = AppSpacing.sp8)
                    .clip(RoundedCornerShape(AppRadius.r8))
                    .background(AppColors.whiteA03)
                    .padding(vertical = AppSpacing.sp4),
            ) {
                for (option in item.options) {
                    MoreSettingOptionRow(option) { expanded = false }
                }
            }
        }
    }
}

/** 下拉里的一个候选行；选中项用主色高亮 */
@Composable
private fun MoreSettingOptionRow(option: MoreSettingOption, onPicked: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickableNoRipple {
                option.onSelect()
                onPicked()
            }
            .padding(horizontal = AppSpacing.sp16, vertical = AppSpacing.sp12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = option.label,
            color = if (option.selected) AppColors.primary else AppColors.textSecondary,
            fontSize = AppFontSize.size15,
            fontWeight = if (option.selected) FontWeight.SemiBold else FontWeight.Normal,
        )
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
        Box(
            modifier = Modifier
                .clickableNoRipple(onClose)
                .padding(AppSpacing.sp4),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "返回",
                tint = AppColors.textSecondary,
                modifier = Modifier.size(AppIconSize.icon22),
            )
        }
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

/** 开关行：标题 + 副标题 + 右侧 Switch（整行可点，点标题等同切换） */
@Composable
private fun MoreSettingsSwitchRow(
    label: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
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
