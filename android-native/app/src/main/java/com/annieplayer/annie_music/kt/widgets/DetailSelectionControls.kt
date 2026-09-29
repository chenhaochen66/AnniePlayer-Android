package com.annieplayer.annie_music.kt.widgets

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing

// ================================================================================================
// 详情页共享：多选对勾 + 多选下载条
// ================================================================================================

/** 勾选缩放：未选中静置 0.85 → 选中 1.0（状态切换的「弹一下」反馈）。 */
private const val DetailCheckRestScale = 0.85f

/** 勾选缩放：选中态 1.0。 */
private const val DetailCheckCheckedScale = 1f

/**
 * 详情页多选勾选框（专辑 / 歌单 / Qobuz / KKBOX 详情行尾共用）。
 *
 * 视觉 18dp（[AppSize.detailCheckBoxSize]）：
 * - 未选中：1.5dp 等效描边圆角方（[DetailCheckBoxBlank]，r4 等效圆角，色 [AppColors.textTertiary]）；
 * - 选中：[AppColors.primary] 实心圆角方 + [DetailCheckMark] 对勾（色 [AppColors.background]）；
 * - 禁用：描边 / 实心底统一 [AppColors.textDisabled]。
 *
 * 交互：状态切换时 0.85 → 1.0 缩放（`animateFloatAsState`）+ 轻触觉
 * （[HapticFeedbackType.TextHandleMove]）；命中区由 [minimumInteractiveComponentSize] 预留到
 * Material 建议的 48dp（实际触摸还会在输入层自动外扩），视觉元素居中不变。
 *
 * 无障碍：自绘可交互元素，显式给出 `contentDescription`（「选择」/「已选择」）与
 * [Role.Checkbox]；对勾本身是装饰（由父级语义承载）。
 *
 * @param checked 是否已选中。
 * @param onToggle 点击切换回调。
 * @param enabled 是否可交互（禁用时不响应点击、语义标记 disabled）。
 */
@Composable
fun DetailCheckBox(
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val scale by animateFloatAsState(
        targetValue = if (checked) DetailCheckCheckedScale else DetailCheckRestScale,
        animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.easeInOutQuad),
        label = "detailCheckBoxScale",
    )
    val boxColor = if (enabled) AppColors.primary else AppColors.textDisabled
    val outlineColor = if (enabled) AppColors.textTertiary else AppColors.textDisabled

    Box(
        modifier = modifier
            // 命中区 ≥48dp：本修饰符只负责预留布局空间，触摸外扩由输入层自动完成
            .minimumInteractiveComponentSize()
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onToggle()
            }
            .semantics {
                contentDescription = if (checked) "已选择" else "选择"
                role = Role.Checkbox
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(AppSize.detailCheckBoxSize)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        ) {
            if (checked) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(boxColor, RoundedCornerShape(AppRadius.r4)),
                )
                DetailCheckMark(
                    modifier = Modifier.fillMaxSize(),
                    tint = AppColors.background,
                )
            } else {
                DetailCheckBoxBlank(
                    modifier = Modifier.fillMaxSize(),
                    tint = outlineColor,
                )
            }
        }
    }
}

/**
 * 多选批量下载条（玻璃 pill，详情页底部 / 头部浮层共用）。
 *
 * 文案三态（[downloading] / [selectedCount] 驱动）：
 * - 未选中：`下载`（禁用观感 38% = [AppColors.white38]，不可点）；
 * - 已选：`下载 N 首`（全亮可点）；
 * - 下载中：[progressText]（如 `3/12 歌名`，全亮但不可重复点击）。
 *
 * 右端带小号计数徽标（`N/总数`，10sp），仅在有选中时出现。
 * 视觉高度 44dp（[AppSize.detailDownloadBarHeight]），命中区由
 * [minimumInteractiveComponentSize] 预留到 48dp；点击反馈同 [DetailCheckBox]。
 *
 * 无障碍：整体是按钮（[Role.Button]），文案由子级 `Text` 合并承载；
 * 左侧下载图标为装饰（无独立语义）。
 *
 * @param selectedCount 当前选中歌曲数。
 * @param total 页面总歌曲数（计数徽标分母）。
 * @param downloading 是否正在批量下载（true 时主文案换成 [progressText]）。
 * @param progressText 下载中进度文案（由调用方拼接，如 `3/12 歌名`）。
 * @param onDownload 点击下载回调（仅「已选且非下载中」时触发）。
 */
@Composable
fun MultiSelectDownloadBar(
    selectedCount: Int,
    total: Int,
    downloading: Boolean,
    progressText: String,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val hasSelection = selectedCount > 0
    val clickEnabled = hasSelection && !downloading
    // 未选中 = 禁用观感（38%）；已选 / 下载中 = 全亮
    val contentColor = if (hasSelection || downloading) AppColors.white else AppColors.white38
    val label = when {
        downloading -> progressText
        hasSelection -> "下载 $selectedCount 首"
        else -> "下载"
    }

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clickable(
                enabled = clickEnabled,
                interactionSource = interactionSource,
                indication = null,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onDownload()
            }
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(AppSize.detailDownloadBarHeight)
                .glassPanel(RoundedCornerShape(AppRadius.pill))
                .padding(horizontal = AppSpacing.sp16),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DetailDownloadSolid(
                modifier = Modifier.size(AppIconSize.icon18),
                tint = contentColor,
            )
            Spacer(modifier = Modifier.width(AppSpacing.sp8))
            Text(
                text = label,
                color = contentColor,
                fontSize = AppFontSize.base,
                fontWeight = AppFontWeight.medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (hasSelection) {
                Spacer(modifier = Modifier.width(AppSpacing.sp8))
                // 右端小号计数徽标（选中 / 总数）
                Box(
                    modifier = Modifier
                        .background(AppColors.whiteA06, RoundedCornerShape(AppRadius.r4))
                        .padding(horizontal = AppSpacing.sp6, vertical = AppSpacing.sp2),
                ) {
                    Text(
                        text = "$selectedCount/$total",
                        color = AppColors.textTertiary,
                        fontSize = AppFontSize.size10,
                        fontWeight = AppFontWeight.semiBold,
                    )
                }
            }
        }
    }
}
