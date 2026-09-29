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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.annieplayer.annie_music.kt.data.model.DownloadStatus
import com.annieplayer.annie_music.kt.data.model.DownloadTask
import com.annieplayer.annie_music.kt.ui.theme.AppBorder
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import kotlin.math.roundToInt

/** 「下载中 / 已下载」分页。 */
private enum class DownloadQueueTab {
    Downloading,
    Completed,
}

/**
 * 下载队列浮层（1:1 移植 Flutter `lib/widgets/download_queue_sheet.dart`，视觉对齐 RN
 * `DownloadsOverlay`）。
 *
 * - 全屏面板：Header（返回 + 「下载任务」）+ 两段 Tab + 任务列表；底部留白 100（避开迷你条）。
 * - 任务行：封面 44 / 标题 / 歌手 / 音质 / 进度条 / 状态与操作按钮；行高最小 74、底部分隔线。
 * - 动画 150ms + 线性；遮罩 50% 黑（[AppColors.scrim50]，对齐 Dart `Color(0x80000000)`）。
 * - 常驻组件树：隐藏时不移除，面板整体位移出屏 + 遮罩停用点击（等价 Flutter 的
 *   `IgnorePointer(ignoring: !visible)`；Compose 侧沿用 `AppSheet` 已验证的做法）。
 * - 返回手势：`BackHandler(enabled = visible)`。
 *
 * 回调语义（对齐 Dart 的 `cancelDownload/pauseDownload/cancelTask/removeTask` 组合）：
 * - [onPause]：暂停下载并置任务为 paused。
 * - [onCancel]：取消下载并从任务表移除（暂停态 / 下载中转圈态共用）。
 * - [onRemove]：仅移除任务（失败态行）。
 */
@Composable
fun DownloadQueueSheet(
    visible: Boolean,
    tasks: Map<String, DownloadTask>,
    onClose: () -> Unit,
    onPause: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = visible) { onClose() }

    var tab by remember { mutableStateOf(DownloadQueueTab.Downloading) }

    val scrimAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.linear),
        label = "downloadQueueScrim",
    )
    val slide by animateFloatAsState(
        targetValue = if (visible) 0f else 1f,
        animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.linear),
        label = "downloadQueueSlide",
    )

    val all = tasks.values.toList()
    val data = if (tab == DownloadQueueTab.Downloading) {
        all.filter { it.status != DownloadStatus.completed && it.status != DownloadStatus.cancelled }
    } else {
        all.filter { it.status == DownloadStatus.completed }
    }

    // 【关键 · 全屏浮层不得吃掉下层点击】
    // Compose 里 `clickable(enabled = false)` **依然占据命中测试**，会阻止事件传给下层兄弟节点。
    // 这些浮层的遮罩是 fillMaxSize，一旦常驻就会把顶栏抽屉按钮 / 搜索框 / 模式下拉 / ⇄ 按钮
    // 的点击全部挡掉（现象：界面能显示，但所有按钮点不动）。
    // 因此完全隐藏时**整个浮层不参与组合**，等价 Flutter 的 IgnorePointer(ignoring: !visible)。
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

        // ── 全屏面板：自下而上滑入 / 滑出（对齐 Dart FractionalTranslation） ──
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = slide * size.height }
                .background(AppColors.background)
                .statusBarsPadding(),
        ) {
            DownloadQueueHeader(onClose = onClose)
            DownloadQueueTabs(current = tab, onTabChange = { tab = it })
            if (data.isEmpty()) {
                DownloadQueueEmpty(tab = tab, modifier = Modifier.weight(1f).fillMaxWidth())
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    // 底部留白避开迷你播放条（~92dp → token 100）
                    contentPadding = PaddingValues(bottom = AppSpacing.sheetBottomInset),
                ) {
                    items(items = data, key = { it.songId }) { item ->
                        DownloadQueueTaskItem(
                            item = item,
                            onRemove = { onRemove(item.songId) },
                            onPause = { onPause(item.songId) },
                            onCancel = { onCancel(item.songId) },
                        )
                    }
                }
            }
        }
    }
}

/** Header：返回箭头 + 「下载任务」（对齐 Dart `_header`）。 */
@Composable
private fun DownloadQueueHeader(onClose: () -> Unit) {
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
                imageVector = Icons.Default.KeyboardArrowLeft,
                contentDescription = null,
                tint = AppColors.textSecondary,
                modifier = Modifier.size(AppIconSize.icon22),
            )
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp8))
        Text(
            text = "下载任务",
            color = AppColors.textPrimary,
            fontSize = AppFontSize.size20,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** 两段 Tab 容器（对齐 Dart `_tabs`）。 */
@Composable
private fun DownloadQueueTabs(current: DownloadQueueTab, onTabChange: (DownloadQueueTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = AppSpacing.sp24, top = AppSpacing.sp12, end = AppSpacing.sp24, bottom = AppSpacing.sp8)
            .background(AppColors.whiteA04, RoundedCornerShape(AppRadius.r10))
            .padding(AppSpacing.sp3),
    ) {
        DownloadQueueTabButton(
            label = "下载中",
            active = current == DownloadQueueTab.Downloading,
            modifier = Modifier.weight(1f),
        ) { onTabChange(DownloadQueueTab.Downloading) }
        DownloadQueueTabButton(
            label = "已下载",
            active = current == DownloadQueueTab.Completed,
            modifier = Modifier.weight(1f),
        ) { onTabChange(DownloadQueueTab.Completed) }
    }
}

/** 单个 Tab 按钮（选中：`primary @ 0.15` 底 + 白字）。 */
@Composable
private fun DownloadQueueTabButton(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
) {
    Box(
        modifier = modifier
            .background(if (active) AppColors.primaryA15 else Color.Transparent, RoundedCornerShape(AppRadius.r8))
            .clickableNoRipple(onTap)
            .padding(vertical = AppSpacing.sp8),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (active) AppColors.white else AppColors.whiteA40,
            fontSize = AppFontSize.size13,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 空态（对齐 Dart `_empty`）。 */
@Composable
private fun DownloadQueueEmpty(tab: DownloadQueueTab, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            DownloadIconPlaceholder(
                modifier = Modifier.size(AppIconSize.icon48),
                tint = AppColors.whiteA06,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp12))
            Text(
                text = if (tab == DownloadQueueTab.Downloading) "暂无下载任务" else "暂无已下载歌曲",
                color = AppColors.textDisabled,
                fontSize = AppFontSize.lg,
            )
        }
    }
}

/** 任务行（对齐 Dart `_TaskItem` / RN `TaskItem`）。 */
@Composable
private fun DownloadQueueTaskItem(
    item: DownloadTask,
    onRemove: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit,
) {
    val isActive = item.status == DownloadStatus.downloading || item.status == DownloadStatus.pending
    val isPaused = item.status == DownloadStatus.paused
    var coverFailed by remember(item.songId) { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 用 minHeight 而非固定高度：中文/大字号下内容更高时不会触发溢出
            .heightIn(min = AppSize.downloadItemHeight)
            .drawBehind {
                val stroke = AppBorder.thin.toPx()
                drawLine(
                    color = AppColors.whiteA04,
                    start = Offset(0f, size.height - stroke / 2f),
                    end = Offset(size.width, size.height - stroke / 2f),
                    strokeWidth = stroke,
                )
            }
            .padding(vertical = AppSpacing.sp10, horizontal = AppSpacing.sp24),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ── 封面 44 / 圆角 8 ──
        Box(
            modifier = Modifier
                .size(AppSize.rowThumb)
                .background(AppColors.whiteA03, RoundedCornerShape(AppRadius.r8)),
            contentAlignment = Alignment.Center,
        ) {
            if (item.cover.isEmpty() || coverFailed) {
                MusicNoteGlyph(
                    modifier = Modifier.size(AppIconSize.icon18),
                    tint = AppColors.textDisabled,
                )
            } else {
                AsyncImage(
                    model = item.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    onError = { coverFailed = true },
                )
            }
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp12))
        DownloadQueueTaskInfo(item = item, isPaused = isPaused, modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.width(AppSpacing.sp8))
        DownloadQueueStatus(status = item.status)

        if (isPaused) {
            DownloadQueueIconButton(Icons.Default.Close, AppColors.danger, AppIconSize.icon16, onCancel)
        } else if (isActive) {
            Box(
                modifier = Modifier
                    .clickableNoRipple(onPause)
                    .padding(AppSpacing.sp4),
            ) {
                PauseIcon(modifier = Modifier.size(AppIconSize.icon16), tint = AppColors.warning)
            }
            DownloadQueueIconButton(Icons.Default.Close, AppColors.danger, AppIconSize.icon16, onCancel)
        } else if (item.status == DownloadStatus.error) {
            // 失败态用 14dp（对齐 Dart），其余按钮 16dp
            DownloadQueueIconButton(Icons.Default.Close, AppColors.textDisabled, 14.dp, onRemove)
        }
    }
}

/** 行内信息（标题 / 歌手 / 音质 / 进度）。 */
@Composable
private fun DownloadQueueTaskInfo(item: DownloadTask, isPaused: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = item.title,
            color = AppColors.textPrimary,
            fontSize = AppFontSize.base,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(AppSpacing.sp1))
        Text(
            text = item.artist,
            color = AppColors.textTertiary,
            fontSize = AppFontSize.sm,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(AppSpacing.sp2))
        Text(
            text = item.quality.label,
            color = AppColors.textDisabled,
            fontSize = AppFontSize.xs,
        )
        if (item.status == DownloadStatus.downloading || isPaused) {
            Spacer(modifier = Modifier.height(AppSpacing.sp4))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(3.dp)
                        .background(AppColors.whiteA06),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((item.progress / 100.0).coerceIn(0.0, 1.0).toFloat())
                            .fillMaxHeight()
                            .background(AppColors.primary),
                    )
                }
                Spacer(modifier = Modifier.width(AppSpacing.sp8))
                Text(
                    text = "${item.progress.roundToInt()}%",
                    color = AppColors.white30,
                    fontSize = AppFontSize.xs,
                )
            }
        }
    }
}

/** 状态区（对齐 Dart `_status`）。 */
@Composable
private fun DownloadQueueStatus(status: DownloadStatus) {
    when (status) {
        DownloadStatus.pending -> CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            color = AppColors.primary,
            strokeWidth = AppBorder.progressStroke,
        )

        DownloadStatus.downloading -> Text(
            text = "下载中",
            color = AppColors.primary,
            fontSize = AppFontSize.xs,
            fontWeight = FontWeight.SemiBold,
        )

        DownloadStatus.paused -> Text(
            text = "已暂停",
            color = AppColors.warning,
            fontSize = AppFontSize.xs,
            fontWeight = FontWeight.SemiBold,
        )

        DownloadStatus.completed -> Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = AppColors.success,
            modifier = Modifier.size(AppIconSize.icon18),
        )

        DownloadStatus.error -> DownloadQueueGlyphBadge(
            glyph = { ErrorCircleGlyph(color = AppColors.danger, markColor = AppColors.surface) },
            label = "失败",
            color = AppColors.danger,
        )

        DownloadStatus.cancelled -> DownloadQueueGlyphBadge(
            glyph = { CancelCircleGlyph(color = AppColors.textDisabled, markColor = AppColors.surface) },
            label = "已取消",
            color = AppColors.textDisabled,
        )
    }
}

/** 双行状态角标（自绘圆形图标 + 文案，10sp；对齐 Flutter `Icons.error` / `Icons.cancel`）。 */
@Composable
private fun DownloadQueueGlyphBadge(glyph: @Composable () -> Unit, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.size(AppIconSize.icon18), contentAlignment = Alignment.Center) {
            glyph()
        }
        Spacer(modifier = Modifier.height(AppSpacing.sp1))
        Text(text = label, color = color, fontSize = AppFontSize.size10)
    }
}

/** 双行状态角标（图标 + 文案，10sp）。 */
@Composable
private fun DownloadQueueStatusBadge(icon: ImageVector, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(AppIconSize.icon18),
        )
        Spacer(modifier = Modifier.height(AppSpacing.sp1))
        Text(text = label, color = color, fontSize = AppFontSize.size10)
    }
}

/** 行内小图标按钮（无涟漪，4dp 热区）。 */
@Composable
private fun DownloadQueueIconButton(
    icon: ImageVector,
    tint: Color,
    iconSize: Dp,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clickableNoRipple(onClick)
            .padding(AppSpacing.sp4),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}
