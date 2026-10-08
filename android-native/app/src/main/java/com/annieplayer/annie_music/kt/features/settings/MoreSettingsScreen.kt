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

/**
 * 分组头与设置项的**统一行高**。
 *
 * 组按钮要和它自己的设置项一样大，所以两者共用这个高度（内容垂直居中，
 * 副标题限一行避免溢出）。以后加设置项只要用同一高度就不会参差不齐。
 */
private val MoreSettingRowHeight = 72.dp

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
): List<MoreSettingGroup> = listOf(
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
            .height(MoreSettingRowHeight)
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
                maxLines = 1,
            )
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp12))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
