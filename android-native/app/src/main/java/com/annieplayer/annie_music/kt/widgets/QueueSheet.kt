package com.annieplayer.annie_music.kt.widgets

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing

/** 当前播放行底色：`primaryStrong @ 0.08`（0x146366F1，对齐 Dart `withValues(alpha: 0.08)`）。 */
private val QueueSheetCurrentBg = Color(0x146366F1)


/**
 * 播放队列浮层（1:1 移植 Flutter `lib/widgets/queue_sheet.dart`，视觉对齐 RN `QueueModal`）。
 *
 * - 底部面板：拖拽条 / 标题「播放队列 (N)」/ 关闭按钮 / 队列列表；最大高度 = 屏高 60%。
 * - 当前曲高亮（主色 + 前缀「正在播放」），点击行 [onPlayIndex] 跳播（宿主负责关闭）。
 * - 队列长度 > 1 时每行提供移除按钮。
 * - 动画：150ms + easeOutCubic（[AppDuration.sheet] + [AppEasing.easeOutCubic]），遮罩 60% 黑。
 *
 * 常驻组件树：隐藏时不销毁，仅把面板整体位移出屏 + 遮罩停止吃触摸（对齐 Flutter
 * `IgnorePointer(ignoring: !visible)` 的效果；Compose 侧沿用 `AppSheet` 已验证的做法：
 * 遮罩的 `clickable` 只在 visible 时启用，面板无指针处理器且被移出屏幕）。
 * 返回手势：`BackHandler(enabled = visible)`，只有可见时才消费。
 */
@Composable
fun QueueSheet(
    visible: Boolean,
    queue: List<Song>,
    currentIndex: Int,
    onClose: () -> Unit,
    onPlayIndex: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = visible) { onClose() }

    val scrimAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.easeOutCubic),
        label = "queueSheetScrim",
    )
    val slide by animateFloatAsState(
        targetValue = if (visible) 0f else 1f,
        animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.easeOutCubic),
        label = "queueSheetSlide",
    )

    // RN panelHeight = winH * 0.6
    val panelMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.6f).dp

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
                .background(AppColors.scrim60),
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
                    .heightIn(max = panelMaxHeight)
                    .padding(top = AppSpacing.sp20, bottom = AppSpacing.sp16),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 拖拽条
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .background(AppColors.whiteA20, RoundedCornerShape(AppRadius.r2)),
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp16))

                // 标题行
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.sp20),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "播放队列 (${queue.size})",
                        modifier = Modifier.weight(1f),
                        color = AppColors.white,
                        fontSize = AppFontSize.size17,
                        fontWeight = FontWeight.Bold,
                    )
                    Box(
                        modifier = Modifier
                            .clickableNoRipple(onClose)
                            .padding(AppSpacing.sp4),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            tint = AppColors.whiteA50,
                            modifier = Modifier.size(AppIconSize.icon20),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(AppSpacing.sp12))

                if (queue.isEmpty()) {
                    QueueSheetEmpty()
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                        contentPadding = PaddingValues(bottom = AppSpacing.sp8),
                    ) {
                        itemsIndexed(items = queue) { index, song ->
                            QueueSheetItem(
                                song = song,
                                index = index,
                                isCurrent = index == currentIndex,
                                canRemove = queue.size > 1,
                                onPlay = { onPlayIndex(index) },
                                onRemove = { onRemove(index) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 空队列提示（对齐 Dart `_empty()`：音符 36 + 「队列为空」）。 */
@Composable
private fun QueueSheetEmpty() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = AppSpacing.sp40),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MusicNoteGlyph(
            modifier = Modifier.size(AppIconSize.icon36),
            tint = AppColors.glass,
        )
        Spacer(modifier = Modifier.height(AppSpacing.sp8))
        Text(
            text = "队列为空",
            color = AppColors.whiteA20,
            fontSize = AppFontSize.base,
        )
    }
}

/** 队列单行（对齐 Dart `_QueueItem` / RN `QueueModal renderItem`）。 */
@Composable
private fun QueueSheetItem(
    song: Song,
    index: Int,
    isCurrent: Boolean,
    canRemove: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickableNoRipple(onPlay)
            .background(if (isCurrent) QueueSheetCurrentBg else Color.Transparent)
            .padding(vertical = AppSpacing.sp8, horizontal = AppSpacing.sp20),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            Text(
                text = "${index + 1}",
                color = if (isCurrent) AppColors.primary else AppColors.whiteA25,
                fontSize = AppFontSize.base,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
            )
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp12))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isCurrent) "正在播放 ${song.title}" else song.title,
                color = if (isCurrent) AppColors.primary else AppColors.white,
                fontSize = AppFontSize.base,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp1))
            Text(
                text = song.artist,
                color = AppColors.white30,
                fontSize = AppFontSize.xs,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (canRemove) {
            Box(
                modifier = Modifier
                    .clickableNoRipple(onRemove)
                    .padding(AppSpacing.sp4),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = AppColors.whiteA20,
                    modifier = Modifier.size(AppIconSize.icon16),
                )
            }
        }
    }
}
