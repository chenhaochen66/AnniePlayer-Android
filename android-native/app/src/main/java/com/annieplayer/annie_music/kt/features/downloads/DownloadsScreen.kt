package com.annieplayer.annie_music.kt.features.downloads

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.annieplayer.annie_music.kt.core.state.DownloadViewModel
import com.annieplayer.annie_music.kt.core.util.cancelPending
import com.annieplayer.annie_music.kt.data.DownloadService
import com.annieplayer.annie_music.kt.data.model.DownloadStatus
import com.annieplayer.annie_music.kt.data.model.DownloadTask
import com.annieplayer.annie_music.kt.ui.theme.AppBorder
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.DetailErrorOutline
import com.annieplayer.annie_music.kt.widgets.DownloadIconPlaceholder
import com.annieplayer.annie_music.kt.widgets.MusicNoteGlyph
import com.annieplayer.annie_music.kt.widgets.PauseIcon
import com.annieplayer.annie_music.kt.widgets.clickableNoRipple
import kotlin.math.roundToInt

/**
 * 下载任务整页（1:1 移植 Flutter `lib/features/downloads/downloads_screen.dart`，
 * 视觉对齐 RN `app/downloads.tsx`）。
 *
 * 结构：标题（返回 + 「下载任务」）→ 两段 Tab（下载中 / 已下载）→ 任务列表 / 空态。
 *
 * 行为对齐点：
 * - 「下载中」= 非 completed 且非 cancelled；「已下载」= completed。
 * - 任务行 `minHeight 74`（Dart 注释：固定 74 在中文 / 大字号下会溢出）。
 * - 操作：暂停 → `pauseTask`；取消 / 移除 → `cancelPending` + `removeTask`
 *   （Kotlin 侧无进行中的 runtime cancel，与 `detailDownloadSong` 的移植程度一致）。
 * - 返回手势：整页直接回退。
 */
@Composable
fun DownloadsScreen(
    downloadViewModel: DownloadViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var completedTab by remember { mutableStateOf(false) }
    val tasks by downloadViewModel.state.collectAsState()

    val all = tasks.values.toList()
    val downloading = all.filter {
        it.status != DownloadStatus.completed && it.status != DownloadStatus.cancelled
    }
    val completed = all.filter { it.status == DownloadStatus.completed }
    val current = if (completedTab) completed else downloading

    fun removeTask(id: String) {
        // 对齐 Dart cancelDownload + cancelPending + removeTask：必须真正中断进行中的下载，
        // 否则移除只是删任务记录，后台 HTTP 仍会继续写文件。
        DownloadService.cancelDownload(id)
        cancelPending(id)
        downloadViewModel.removeTask(id)
    }

    BackHandler { onBack() }

    Box(modifier = modifier.fillMaxSize().background(AppColors.background)) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            DownloadsHeader(onBack = onBack)
            DownloadsTabs(
                completed = completedTab,
                onSelect = { completedTab = it },
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (current.isEmpty()) {
                    DownloadsEmpty(completed = completedTab)
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(items = current, key = { it.songId }) { task ->
                            DownloadsTaskRow(
                                task = task,
                                onRemove = { removeTask(task.songId) },
                                onPause = {
                                    // 对齐 Dart pauseDownload + pauseTask：先置「已暂停」，再取消真实下载流
                                    downloadViewModel.pauseTask(task.songId)
                                    DownloadService.pauseDownload(task.songId)
                                },
                                onCancel = { removeTask(task.songId) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 标题：返回箭头 + 「下载任务」20 w700（对齐 Dart `Colors.white70` 箭头） */
@Composable
private fun DownloadsHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = AppSpacing.sp24, top = AppSpacing.sp40, end = AppSpacing.sp24, bottom = AppSpacing.sp8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clickableNoRipple(onBack)
                .padding(AppSpacing.sp4),
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowLeft,
                contentDescription = null,
                tint = AppColors.white70,
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

/** 两段 Tab 容器（white 0.04 底、圆角 10、内边距 3） */
@Composable
private fun DownloadsTabs(completed: Boolean, onSelect: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = AppSpacing.sp24, top = AppSpacing.sp12, end = AppSpacing.sp24, bottom = AppSpacing.sp8)
            .background(AppColors.whiteA04, RoundedCornerShape(AppRadius.r10))
            .padding(AppSpacing.sp3),
    ) {
        DownloadsTabButton(
            label = "下载中",
            active = !completed,
            modifier = Modifier.weight(1f),
        ) { onSelect(false) }
        DownloadsTabButton(
            label = "已下载",
            active = completed,
            modifier = Modifier.weight(1f),
        ) { onSelect(true) }
    }
}

/** 单个 Tab 按钮（选中：primary @0.15 底 + 白字；未选中 white38） */
@Composable
private fun DownloadsTabButton(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
) {
    Box(
        modifier = modifier
            .background(
                color = if (active) AppColors.primaryA15 else Color.Transparent,
                shape = RoundedCornerShape(AppRadius.r8),
            )
            .clickableNoRipple(onTap)
            .padding(vertical = AppSpacing.sp8),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (active) AppColors.white else AppColors.white38,
            fontSize = AppFontSize.size13,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 空态：下载图标 48（white10）+ 文案 16（white24） */
@Composable
private fun DownloadsEmpty(completed: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            DownloadIconPlaceholder(modifier = Modifier.size(AppIconSize.icon48), tint = AppColors.white10)
            Spacer(modifier = Modifier.height(AppSpacing.sp12))
            Text(
                text = if (completed) "暂无已下载歌曲" else "暂无下载任务",
                color = AppColors.white24,
                fontSize = AppFontSize.lg,
            )
        }
    }
}

/** 任务行：封面 44 / 标题 / 歌手 / 音质 / 进度条 / 状态与操作按钮（minHeight 74，行底分隔线） */
@Composable
private fun DownloadsTaskRow(
    task: DownloadTask,
    onRemove: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit,
) {
    val isActive = task.status == DownloadStatus.downloading || task.status == DownloadStatus.pending
    val isPaused = task.status == DownloadStatus.paused
    var coverFailed by remember(task.songId) { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Dart 注释：用 minHeight 而非固定 74，避免中文 / 大字号下内容溢出
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
            .padding(horizontal = AppSpacing.sp24, vertical = AppSpacing.sp8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(AppSize.rowThumb)
                .clip(RoundedCornerShape(AppRadius.r8))
                .background(AppColors.white10),
            contentAlignment = Alignment.Center,
        ) {
            if (task.cover.isEmpty() || coverFailed) {
                MusicNoteGlyph(modifier = Modifier.size(AppIconSize.icon24), tint = AppColors.white24)
            } else {
                AsyncImage(
                    model = task.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onError = { coverFailed = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp12))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.title,
                color = AppColors.textPrimary,
                fontSize = AppFontSize.base,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp1))
            Text(
                text = task.artist,
                color = AppColors.white38,
                fontSize = AppFontSize.sm,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = task.quality.label,
                color = AppColors.white24,
                fontSize = AppFontSize.xs,
            )
            if (task.status == DownloadStatus.downloading || isPaused) {
                Spacer(modifier = Modifier.height(AppSpacing.sp4))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(3.dp)
                            .clip(RoundedCornerShape(AppRadius.r1_5))
                            .background(AppColors.white10),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth((task.progress / 100.0).coerceIn(0.0, 1.0).toFloat())
                                .fillMaxHeight()
                                .background(AppColors.primary),
                        )
                    }
                    Spacer(modifier = Modifier.width(AppSpacing.sp8))
                    Text(
                        text = "${task.progress.roundToInt()}%",
                        color = AppColors.white30,
                        fontSize = AppFontSize.xs,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp8))
        DownloadsStatusIndicator(status = task.status)
        if (isPaused) {
            DownloadsIconAction(onTap = onRemove) {
                DownloadsCloseIcon(tint = AppColors.danger)
            }
        }
        if (isActive) {
            Row {
                DownloadsIconAction(onTap = onPause) {
                    PauseIcon(modifier = Modifier.size(AppIconSize.icon16), tint = AppColors.warning)
                }
                DownloadsIconAction(onTap = onCancel) {
                    DownloadsCloseIcon(tint = AppColors.danger)
                }
            }
        }
        if (task.status == DownloadStatus.error) {
            DownloadsIconAction(onTap = onRemove) {
                DownloadsCloseIcon(tint = AppColors.white24)
            }
        }
    }
}

/** 状态指示（pending 圆点 / 下载中 / 已暂停 / 完成 / 失败 / 已取消） */
@Composable
private fun DownloadsStatusIndicator(status: DownloadStatus) {
    when (status) {
        DownloadStatus.pending -> Box(
            modifier = Modifier
                .size(14.dp)
                .border(
                    width = AppBorder.thick,
                    color = AppColors.primary.copy(alpha = 0.2f),
                    shape = CircleShape,
                ),
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

        DownloadStatus.error -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            DetailErrorOutline(modifier = Modifier.size(AppIconSize.icon18), tint = AppColors.danger)
            Text(text = "失败", color = AppColors.danger, fontSize = AppFontSize.size10)
        }

        DownloadStatus.cancelled -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null,
                tint = AppColors.white24,
                modifier = Modifier.size(AppIconSize.icon18),
            )
            Text(text = "已取消", color = AppColors.white24, fontSize = AppFontSize.size10)
        }
    }
}

/** 关闭图标（16dp；`Icons.Default.Close` 为 core 图标集内可用的最接近形状） */
@Composable
private fun DownloadsCloseIcon(tint: Color) {
    Icon(
        imageVector = Icons.Default.Close,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(AppIconSize.icon16),
    )
}

/** 操作按钮（4dp 热区，无涟漪） */
@Composable
private fun DownloadsIconAction(onTap: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .clickableNoRipple(onTap)
            .padding(AppSpacing.sp4),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
