package com.annieplayer.annie_music.kt.widgets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.annieplayer.annie_music.kt.ui.theme.AppBorder
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import kotlinx.coroutines.delay

/**
 * 搜索模式（1:1 移植 Flutter `lib/widgets/mode_dropdown.dart` 的 `SearchMode`；
 * 展示顺序：单曲/专辑/歌手/国外解析）。
 *
 * 「歌单解析」已从搜索模式迁出为独立 tab（见 `AppTab.PlaylistParse`），故此处不再有
 * `playlist` 值；[fromValue] 收到历史字符串 `"playlist"` 时与未知值同策略回退到 [song]。
 *
 * 命名加 `ModeDropdown` 前缀以避免与后续页面级枚举静默冲突。
 */
enum class ModeDropdownMode(val value: String, val label: String) {
    song("song", "单曲搜索"),
    album("album", "专辑搜索"),
    singer("singer", "歌手搜索"),
    qobuz("qobuz", "国外解析"),
    ;

    companion object {
        /** 字符串 → 枚举（未知回退 [song]，对齐 Flutter `SearchMode.fromValue`）。 */
        fun fromValue(value: String): ModeDropdownMode =
            entries.firstOrNull { it.value == value } ?: song
    }
}

/** 面板进场缩放起点（从触发器左上角「长出」）。 */
private const val ModeDropdownEnterScale = 0.92f

/** 面板退场缩放终点。 */
private const val ModeDropdownExitScale = 0.96f

/** 面板关闭动画时长（比进场略短，关闭不拖沓）。 */
private const val ModeDropdownExitMillis = 120

/** 选项错峰进场：第 n 项延迟 n × 25ms（4 项总时长压在 250ms 内）。 */
private const val ModeDropdownItemStaggerMillis = 25

/** 面板相对「触发器下沿」的间距。 */
private val ModeDropdownPanelGap = 4.dp

/**
 * 搜索模式下拉。
 *
 * 触发器（玻璃 pill，与详情页「下载 N 首」同款配方）：高 44dp
 * （[AppSize.detailDownloadBarHeight]）、[AppRadius.pill] 圆角玻璃底（`glassPanel`，白 5%、无描边无投影）、
 * 左右内边距 16dp、文案 13sp/w600 白、右侧箭头 18dp（[AppColors.textSecondary]，随开合旋转 180°）。
 *
 * 面板：宽 140dp（[AppSize.dropdownPanelWidth]）、距触发器下沿 [ModeDropdownPanelGap]、
 * 底色 [AppColors.surface]、圆角 12、边框 `white @0.06`；选项激活底色 `primary @0.12`
 * （[AppColors.primaryA12]）+ 右侧 primary 勾选（16dp），未激活文字 `white @0.6`（[AppColors.white60]）。
 *
 * 交互动画（贴项目 motion token：150ms + [AppEasing.easeInOutQuad]）：
 * - 面板自触发器**左上角**「长出」：fade + scaleIn(0.92, transformOrigin 左上)；
 * - 选项错峰进场（每项 +[ModeDropdownItemStaggerMillis]）——关闭时不反向错峰；
 * - 关闭走 [ModeDropdownExitMillis] 反向 fade/scale；期间 **Popup 保持挂载**，
 *   否则 `open = false` 会立刻卸载 Popup、关闭动画被截断（本文件踩过的坑）。
 *
 * Compose 实现说明：面板用 [Popup]（focusable，点击面板外关闭）替代 Flutter 的
 * `OverlayEntry`，既能溢出父级约束又不撑高无界 Column；定位锚点为触发器左上角，
 * 与 Flutter 用 `findRenderObject().localToGlobal` 取到的原点一致。
 * 调用方若要复刻 Flutter `Align(centerLeft)` 的“占满宽度、左对齐”行为，
 * 传入 `Modifier.fillMaxWidth()` 即可。
 */
@Composable
fun ModeDropdown(
    mode: ModeDropdownMode,
    open: Boolean,
    onToggle: () -> Unit,
    onSelect: (ModeDropdownMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 关闭动画期间保持 Popup 挂载：`open = false` 时不能立即卸载，否则退出动画看不到。
    var mounted by remember { mutableStateOf(open) }
    LaunchedEffect(open) {
        if (open) {
            mounted = true
        } else {
            delay(ModeDropdownExitMillis.toLong())
            mounted = false
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.CenterStart) {
        // 锚点 Box 恰好包住触发器：Popup 相对它定位，等价 Flutter 的 trigger 全局坐标
        Box {
            ModeDropdownTrigger(label = mode.label, open = open, onToggle = onToggle)
            if (mounted) {
                val topOffset = with(LocalDensity.current) {
                    (AppSize.detailDownloadBarHeight + ModeDropdownPanelGap).roundToPx()
                }
                Popup(
                    alignment = Alignment.TopStart,
                    offset = IntOffset(0, topOffset),
                    // 关闭动画期间不再吃返回/外部点击（focusable 跟随 open）
                    onDismissRequest = { if (open) onToggle() },
                    properties = PopupProperties(focusable = open),
                ) {
                    AnimatedVisibility(
                        visible = open,
                        enter = fadeIn(
                            tween(durationMillis = AppDuration.sheet, easing = AppEasing.easeInOutQuad),
                        ) + scaleIn(
                            initialScale = ModeDropdownEnterScale,
                            transformOrigin = TransformOrigin(0f, 0f),
                            animationSpec = tween(
                                durationMillis = AppDuration.sheet,
                                easing = AppEasing.easeInOutQuad,
                            ),
                        ),
                        exit = fadeOut(
                            tween(durationMillis = ModeDropdownExitMillis, easing = AppEasing.easeInOutQuad),
                        ) + scaleOut(
                            targetScale = ModeDropdownExitScale,
                            transformOrigin = TransformOrigin(0f, 0f),
                            animationSpec = tween(
                                durationMillis = ModeDropdownExitMillis,
                                easing = AppEasing.easeInOutQuad,
                            ),
                        ),
                    ) {
                        ModeDropdownPanel(mode = mode, onSelect = onSelect)
                    }
                }
            }
        }
    }
}

/** 触发器：玻璃 pill + 当前模式文案 + 开合旋转的箭头。 */
@Composable
private fun ModeDropdownTrigger(label: String, open: Boolean, onToggle: () -> Unit) {
    // 箭头随开合旋转 180°（唯一的状态反馈；点击反馈由 clickableRipple 的波纹承担）
    val chevronRotation by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.easeInOutQuad),
        label = "modeDropdownChevron",
    )
    Row(
        modifier = Modifier
            .height(AppSize.detailDownloadBarHeight)
            // 玻璃底与 ⇄ 按钮同一口径（surfaceLight @50%）
            .glassPanel(
                shape = RoundedCornerShape(AppRadius.pill),
                tint = AppColors.surfaceLight.copy(alpha = 0.50f),
            )
            .clickableRipple(onToggle)
            .padding(horizontal = AppSpacing.sp16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = AppColors.white,
            fontSize = AppFontSize.size13,
            fontWeight = AppFontWeight.semiBold,
        )
        Spacer(modifier = Modifier.width(AppSpacing.sp6))
        // 下拉箭头改为自绘线条家族（笔重 2dp 等效、圆头端点），替换 Material 实心 KeyboardArrowDown
        ModeDropdownChevronDown(
            modifier = Modifier.size(AppIconSize.icon18),
            tint = AppColors.textSecondary,
            rotationZ = chevronRotation,
        )
    }
}

/**
 * 下拉箭头（chevron_down，自绘线条家族，仅搜索模式下拉）。
 *
 * 笔重 `w * 0.15f` 在 13dp 栅格上 ≈ 2dp（2dp 等效），端点圆头；替换原
 * `Icons.Default.KeyboardArrowDown`（Material 实心、平头，笔重与同屏自绘图标不一致）。
 */
@Composable
private fun ModeDropdownChevronDown(
    modifier: Modifier = Modifier,
    tint: Color = AppColors.textSecondary,
    rotationZ: Float = 0f,
) {
    Canvas(modifier = modifier.graphicsLayer { this.rotationZ = rotationZ }) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.15f
        val topY = h * 0.36f
        val bottomY = h * 0.62f
        drawLine(tint, Offset(w * 0.25f, topY), Offset(w * 0.50f, bottomY), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.75f, topY), Offset(w * 0.50f, bottomY), stroke, StrokeCap.Round)
    }
}

/**
 * 下拉面板（对齐 Flutter `_ModePanel`）：
 * 宽 140、内边距 4、圆角 12、`surface` 底色 + `white @0.06` 边框，选项单列**错峰进场**
 * （每项 +[ModeDropdownItemStaggerMillis]；整体退场由外层 [AnimatedVisibility] 接管，故此处 exit 置空）。
 */
@Composable
private fun ModeDropdownPanel(mode: ModeDropdownMode, onSelect: (ModeDropdownMode) -> Unit) {
    // 进场开关：组合完成后的下一帧置 true → 触发每项自己的错峰进入动画
    var itemsShown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { itemsShown = true }

    Surface(
        color = AppColors.surface,
        shape = RoundedCornerShape(AppRadius.card),
        modifier = Modifier.width(AppSize.dropdownPanelWidth),
    ) {
        Column(
            modifier = Modifier
                .border(
                    width = AppBorder.thin,
                    color = AppColors.whiteA06,
                    shape = RoundedCornerShape(AppRadius.card),
                )
                .padding(vertical = AppSpacing.sp4, horizontal = AppSpacing.sp4),
            // Flutter Column(mainAxisSize: MainAxisSize.min)：面板高度随内容收紧
            ) {
            for ((index, option) in ModeDropdownMode.entries.withIndex()) {
                AnimatedVisibility(
                    visible = itemsShown,
                    enter = fadeIn(
                        tween(
                            durationMillis = AppDuration.sheet,
                            delayMillis = index * ModeDropdownItemStaggerMillis,
                            easing = AppEasing.easeInOutQuad,
                        ),
                    ) + slideInVertically(
                        animationSpec = tween(
                            durationMillis = AppDuration.sheet,
                            delayMillis = index * ModeDropdownItemStaggerMillis,
                            easing = AppEasing.easeInOutQuad,
                        ),
                    ) { fullHeight -> -fullHeight / 6 },
                    exit = ExitTransition.None,
                ) {
                    ModeDropdownPanelItem(
                        option = option,
                        active = option == mode,
                        onTap = { onSelect(option) },
                    )
                }
            }
        }
    }
}

/** 面板选项（对齐 Flutter `_ModePanelItem`）：激活态高亮底色 + 白字 + 主色勾选。 */
@Composable
private fun ModeDropdownPanelItem(
    option: ModeDropdownMode,
    active: Boolean,
    onTap: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.r8))
            .background(if (active) AppColors.primaryA12 else Color.Transparent)
            .clickableRipple(onTap)
            .padding(vertical = AppSpacing.sp10, horizontal = AppSpacing.sp12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = option.label,
            color = if (active) AppColors.white else AppColors.white60,
            fontSize = AppFontSize.base,
            fontWeight = AppFontWeight.medium,
            modifier = Modifier.weight(1f),
        )
        if (active) {
            Spacer(modifier = Modifier.width(AppSpacing.sp8))
            // 勾选改为自绘线条家族（与 DetailCheckMark / CheckSquareIcon 同一勾形与笔重）
            ModeDropdownCheckMark(
                modifier = Modifier.size(AppIconSize.icon16),
                tint = AppColors.primary,
            )
        }
    }
}

/**
 * 勾选对勾（自绘线条家族，仅搜索模式下拉激活项）。
 *
 * 勾形与 [DetailCheckMark] / [CheckSquareIcon] 完全一致（0.22,0.52 → 0.43,0.73 → 0.78,0.28），
 * 笔重 `w * 0.13f` 在 16dp 栅格上 ≈ 2dp（2dp 等效），圆头端点 + 圆角连接；
 * 替换原 `Icons.Default.Check`（Material 实心勾，笔重与自绘家族不一致）。
 */
@Composable
private fun ModeDropdownCheckMark(modifier: Modifier = Modifier, tint: Color = AppColors.primary) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.13f
        val path = Path().apply {
            moveTo(w * 0.22f, h * 0.52f)
            lineTo(w * 0.43f, h * 0.73f)
            lineTo(w * 0.78f, h * 0.28f)
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
