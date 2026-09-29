package com.annieplayer.annie_music.kt.widgets

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing

/**
 * 搜索源选择面板（1:1 移植 Flutter `lib/widgets/source_picker_sheet.dart`）。
 *
 * 通用：单曲 = 源分组（如 `酷我 & 网易`），专辑 = 单平台。标题「搜索选择」+
 * 选项网格（当前项主色高亮），点选即回调 [onSelect]（宿主负责切换并保留关键词重搜）。
 *
 * - 动画：**150ms + 线性**（[AppDuration.sheet] + [AppEasing.linear]），遮罩与面板同速。
 * - 常驻组件树（否则滑出动画无法播放）；隐藏时面板被移出屏幕、遮罩停止吃触摸
 *   （等价 Flutter `IgnorePointer(ignoring: !visible)`，Compose 侧沿用 `AppSheet`
 *   已验证的「clickable enabled 门控 + 面板位移出屏」做法）。
 * - 返回手势：`BackHandler(enabled = visible)`（对齐 Dart `PopScope(canPop: !visible)`）。
 *
 * @param options 可选项文案（单曲：`酷我 & 网易`；专辑：`酷我`），每行 2 个。
 * @param currentIndex 当前生效的下标（主色高亮）。
 */
@Composable
fun SourcePickerSheet(
    visible: Boolean,
    options: List<String>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = visible) { onClose() }

    val scrimAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.linear),
        label = "sourcePickerScrim",
    )
    val slide by animateFloatAsState(
        targetValue = if (visible) 0f else 1f,
        animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.linear),
        label = "sourcePickerSlide",
    )

    // 【关键 · 全屏浮层不得吃掉下层点击】
    // Compose 里 `clickable(enabled = false)` **依然占据命中测试**，会阻止事件传给下层兄弟节点。
    // 这些浮层的遮罩是 fillMaxSize，一旦常驻就会把顶栏抽屉按钮 / 搜索框 / 模式下拉 / ⇄ 按钮
    // 的点击全部挡掉（现象：界面能显示，但所有按钮点不动）。
    // 因此完全隐藏时**整个浮层不参与组合**，等价 Flutter 的 IgnorePointer(ignoring: !visible)。
    if (!visible && scrimAlpha == 0f) return
    Box(modifier = modifier.fillMaxSize()) {
        // ── 遮罩：点击关闭（隐藏时不启用 → 不吃触摸） ──
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
                .background(AppColors.black54),
        )

        // ── 底部面板：自下而上滑入 / 滑出 ──
        Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { translationY = slide * size.height }
                    .background(
                        color = AppColors.surface,
                        shape = RoundedCornerShape(topStart = AppRadius.r20, topEnd = AppRadius.r20),
                    )
                    // 底部留白避开迷你播放条（~92px → token 100）
                    .padding(bottom = AppSpacing.sheetBottomInset),
            ) {
                // 抓手
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = AppSpacing.sp12)
                        .size(width = 36.dp, height = 4.dp)
                        .background(AppColors.whiteA15, RoundedCornerShape(AppRadius.r2)),
                )
                // 标题
                Text(
                    text = "搜索选择",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = AppSpacing.sp16, top = AppSpacing.sp4, end = AppSpacing.sp16, bottom = AppSpacing.sp16),
                    color = AppColors.white,
                    fontSize = AppFontSize.xl,
                    fontWeight = FontWeight.Bold,
                )
                // 类型标签
                Text(
                    text = "搜索音乐",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = AppSpacing.sp16, end = AppSpacing.sp16, bottom = AppSpacing.sp10),
                    color = AppColors.white54,
                    fontSize = AppFontSize.size13,
                )
                // 选项网格（每行 2 个）
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = AppSpacing.sp16, end = AppSpacing.sp16, bottom = AppSpacing.sp8),
                ) {
                    options.chunked(2).forEachIndexed { rowIndex, pair ->
                        val firstIndex = rowIndex * 2
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = AppSpacing.sp10),
                        ) {
                            SourcePickerGroupButton(
                                label = pair[0],
                                active = firstIndex == currentIndex,
                                modifier = Modifier.weight(1f),
                            ) { onSelect(firstIndex) }
                            Spacer(modifier = Modifier.width(AppSpacing.sp10))
                            if (pair.size > 1) {
                                SourcePickerGroupButton(
                                    label = pair[1],
                                    active = firstIndex + 1 == currentIndex,
                                    modifier = Modifier.weight(1f),
                                ) { onSelect(firstIndex + 1) }
                            } else {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 分组按钮（选中：主色实底；未选中：白 5% 底）。
 *
 * 底色过渡 150ms + 线性（对齐 Dart `AnimatedContainer`），高度 46、圆角 12；
 * 点击无涟漪（对齐 Flutter `GestureDetector`）。
 */
@Composable
private fun SourcePickerGroupButton(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
) {
    val background by animateColorAsState(
        targetValue = if (active) AppColors.primary else AppColors.whiteA05,
        animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.linear),
        label = "sourcePickerGroupBg",
    )

    Box(
        modifier = modifier
            .height(AppSize.sourceItemHeight)
            .background(background, RoundedCornerShape(AppRadius.card))
            .clickableNoRipple(onTap),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (active) AppColors.white else AppColors.white54,
            fontSize = AppFontSize.base,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
