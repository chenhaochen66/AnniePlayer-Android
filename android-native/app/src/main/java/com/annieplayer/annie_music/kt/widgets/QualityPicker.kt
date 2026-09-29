package com.annieplayer.annie_music.kt.widgets

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing

/** 档位排序（对齐 Dart `_qualityOrder` / RN `QUALITY_ORDER`）。 */
private val QualityPickerOrder = listOf(Quality.hires, Quality.flac, Quality.k320k, Quality.k128k)

/** 每档音质的描述 / 主题色 / 图标形状（对齐 RN `QUALITY_DETAILS`）。 */
private class QualityPickerDetail(
    val desc: String,
    val color: Color,
    val isDisc: Boolean,
)

private val QualityPickerDetails: Map<Quality, QualityPickerDetail> = mapOf(
    Quality.k128k to QualityPickerDetail("MP3 128kbps", AppColors.whiteA50, isDisc = false),
    Quality.k320k to QualityPickerDetail("MP3 320kbps", AppColors.primary, isDisc = false),
    Quality.flac to QualityPickerDetail("FLAC 无损", AppColors.warning, isDisc = true),
    Quality.hires to QualityPickerDetail("Hi-Res 高解析", AppColors.hires, isDisc = true),
)

/**
 * 音质选择浮层（1:1 移植 Flutter `lib/widgets/quality_picker.dart`，视觉对齐 RN `QualityPicker`）。
 *
 * - 遮罩 60% 黑（[AppColors.scrim60]，对齐 Dart `Color(0x99000000)`），面板 surface 顶部圆角 20。
 * - 动画 150ms + 线性淡入淡出（[AppDuration.sheet] + [AppEasing.linear]）。
 * - 排序 hires → flac → 320k → 128k，标签取 [Quality.label]。
 * - 常驻组件树：隐藏时不移除（滑出动画 / 状态保留），遮罩 `clickable` 只在 visible 时启用；
 *   面板隐藏时被移出屏幕，不再参与命中测试（对齐 Flutter `IgnorePointer(ignoring: !visible)`）。
 * - 返回手势：`BackHandler(enabled = visible)` 优先关本弹窗（对齐 Dart `PopScope(canPop: false)`）。
 *
 * @param qualities 可下载音质（为空时兜底 `[Quality.k128k]`，对齐 Dart 的回退）。
 * @param avoidMiniPlayer 当本弹窗挂在「迷你播放条之下」（详情 sheet 内部）时置 true。
 * @param miniPlayerVisible 迷你条当前是否真的可见（宿主传 `有歌在播 && 未打开全屏播放页`）；
 *   为 true 且 [avoidMiniPlayer] 时预留底部 92dp，避免遮住「取消」与最后一档音质。
 */
@Composable
fun QualityPickerSheet(
    visible: Boolean,
    qualities: List<Quality>,
    onSelect: (Quality) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    avoidMiniPlayer: Boolean = false,
    miniPlayerVisible: Boolean = false,
) {
    BackHandler(enabled = visible) { onCancel() }

    val fade by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.linear),
        label = "qualityPickerFade",
    )

    val source = if (qualities.isEmpty()) listOf(Quality.k128k) else qualities
    val sorted = remember(source) { source.sortedBy { QualityPickerOrder.indexOf(it) } }
    val reserve = if (avoidMiniPlayer && miniPlayerVisible) AppSize.miniPlayerReserve else 0.dp
    val bottomInset = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()

    // 【关键 · 全屏浮层不得吃掉下层点击】
    // Compose 里 `clickable(enabled = false)` **依然占据命中测试**，会阻止事件传给下层兄弟节点。
    // 本浮层遮罩是 fillMaxSize，常驻就会把顶栏抽屉按钮 / 搜索框 / 模式下拉 / ⇄ 按钮的点击全部挡掉
    // （现象：界面能显示，但所有按钮点不动）。完全隐藏时整个浮层不参与组合，
    // 等价 Flutter 的 IgnorePointer(ignoring: !visible)；进出动画不受影响（fade 为 0 时才跳过）。
    if (!visible && fade == 0f) return

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade },
    ) {
        // ── 遮罩：点击取消（隐藏时整条 clickable 都不挂 → 不吃触摸也不挡下层） ──
        // 注意不能用 clickable(enabled = false)：Compose 里 disabled 的 clickable
        // 依然占据命中测试，会把下层（顶栏/搜索框/迷你条）的点击全部挡掉。
        val scrimInteraction = remember { MutableInteractionSource() }
        val scrimClickModifier = if (visible) {
            Modifier.clickable(
                interactionSource = scrimInteraction,
                indication = null,
                onClick = onCancel,
            )
        } else {
            Modifier
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(scrimClickModifier)
                .background(AppColors.scrim60),
        )

        // ── 底部面板 ──
        Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            val panelInteraction = remember { MutableInteractionSource() }
            // 吞掉面板内的点击，避免穿透到遮罩触发取消（对齐 Dart 的空 onTap）；
            // 隐藏时整条 clickable 都不挂：面板只靠 alpha=0 隐形、**没有移出屏幕**，
            // 若保留 disabled 的 clickable，会在屏幕底部留下一片隐形热区。
            val panelClickModifier = if (visible) {
                Modifier.clickable(
                    interactionSource = panelInteraction,
                    indication = null,
                    onClick = { },
                )
            } else {
                Modifier
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(panelClickModifier)
                    .background(
                        color = AppColors.surface,
                        shape = RoundedCornerShape(topStart = AppRadius.r20, topEnd = AppRadius.r20),
                    )
                    .padding(
                        start = AppSpacing.sp20,
                        end = AppSpacing.sp20,
                        top = AppSpacing.sp20,
                        bottom = bottomInset + AppSpacing.sp16 + reserve,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 抓手
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .background(AppColors.whiteA15, RoundedCornerShape(AppRadius.r2)),
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp20))

                Text(
                    text = "选择音质",
                    modifier = Modifier.fillMaxWidth(),
                    color = AppColors.textPrimary,
                    fontSize = AppFontSize.xl,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp4))
                Text(
                    text = "请选择要下载的音质格式",
                    modifier = Modifier.fillMaxWidth(),
                    color = AppColors.textTertiary,
                    fontSize = AppFontSize.size13,
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp20))

                for (quality in sorted) {
                    QualityPickerOptionRow(
                        quality = quality,
                        enabled = visible,
                        onTap = { onSelect(quality) },
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.sp8))
                }
                Spacer(modifier = Modifier.height(AppSpacing.sp4))

                // 取消
                val cancelInteraction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppColors.whiteA05, RoundedCornerShape(AppRadius.card))
                        .clickable(
                            enabled = visible,
                            interactionSource = cancelInteraction,
                            indication = null,
                            onClick = onCancel,
                        )
                        .padding(vertical = AppSpacing.sp14),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "取消",
                        color = AppColors.whiteA40,
                        fontSize = AppFontSize.size15,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/** 音质选项行（对齐 Dart `_OptionRow`：图标 + 标签 / 描述 + 右侧下载角标）。 */
@Composable
private fun QualityPickerOptionRow(quality: Quality, enabled: Boolean, onTap: () -> Unit) {
    val detail = QualityPickerDetails.getValue(quality)
    val interaction = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.whiteA03, RoundedCornerShape(AppRadius.card))
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onTap,
            )
            .padding(AppSpacing.sp16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (detail.isDisc) {
            QualityPickerDiscGlyph(
                modifier = Modifier.size(AppIconSize.icon22),
                tint = detail.color,
            )
        } else {
            MusicNoteGlyph(
                modifier = Modifier.size(AppIconSize.icon22),
                tint = detail.color,
            )
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp14))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = quality.label,
                color = AppColors.textPrimary,
                fontSize = AppFontSize.lg,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp1))
            Text(
                text = detail.desc,
                color = detail.color,
                fontSize = AppFontSize.sm,
            )
        }
        DownloadIconPlaceholder(
            modifier = Modifier.size(AppIconSize.icon18),
            tint = AppColors.whiteA25,
        )
    }
}

/**
 * 碟片图标（自绘：描边圆环 + 中心圆点，对齐 Flutter `Icons.album`）。
 * `material-icons-core` 不含 Album 图标，故用 Canvas 等价绘制。
 */
@Composable
private fun QualityPickerDiscGlyph(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier = modifier) {
        val side = size.minDimension
        val stroke = side * 0.09f
        drawCircle(color = tint, radius = (side - stroke) / 2f, style = Stroke(width = stroke))
        drawCircle(color = tint, radius = side * 0.14f)
    }
}
