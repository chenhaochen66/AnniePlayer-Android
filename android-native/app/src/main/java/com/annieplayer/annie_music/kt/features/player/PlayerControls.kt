package com.annieplayer.annie_music.kt.features.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.data.model.LoopMode
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppShadow
import com.annieplayer.annie_music.kt.ui.theme.AppSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.PauseIcon
import com.annieplayer.annie_music.kt.widgets.clickableNoRipple
import kotlin.math.roundToInt

/** 进度条拇指直径（Flutter：15） */
private val PlayerProgressThumbSize = 15.dp

/** 进度条触控高度（Flutter：26） */
private val PlayerProgressTouchHeight = 26.dp

/** 进度轨高度（Flutter：4） */
private val PlayerProgressTrackHeight = AppSpacing.sp4

/** 音量轨高度（Flutter：5，播放页 `PlayerVolumeSlider`） */
private val PlayerVolumeTrackHeight = 5.dp

/** 音量条触控高度（Flutter：28） */
private val PlayerVolumeTouchHeight = 28.dp

/** 音量图标尺寸（Flutter `Icon(size: 14)`） */
private val PlayerVolumeIconSize = 14.dp

/** 待下载条抓数徽标直径（Flutter `Container(width: 22, height: 22)`） */
private val PlayerPendingBadgeSize = 22.dp

/** `秒 -> m:ss`（对齐 Flutter `_fmt`，补零到两位） */
private fun playerFormatTime(seconds: Double): String {
    val safe = if (seconds > 0.0) seconds else 0.0
    val minutes = (safe / 60).toInt()
    val secs = (safe % 60).toInt()
    return "$minutes:${secs.toString().padStart(2, '0')}"
}

/**
 * 进度条（1:1 移植 Flutter `PlayerProgressBar`）：
 * 触控区 26dp、轨道 4dp（white 0.08）、已播部分主色 + 主色辉光、白色 15dp 拇指；
 * 左右时间 `11sp`（白 0.3 / 白 0.2）等宽数字。
 *
 * 交互：水平拖拽中通过 [onDragging] 上报比例；松手通过 [onSeekEnd] 上报比例
 * （宿主据此执行 `setCurrentTime + seekTo + setDragging(false)`）。
 */
@Composable
fun PlayerProgressBar(
    position: Double,
    duration: Double,
    isDragging: Boolean,
    seekRatio: Double,
    onDragging: (Double) -> Unit,
    onSeekEnd: (Double) -> Unit,
) {
    val progress = (if (isDragging) seekRatio else if (duration > 0) position / duration else 0.0)
        .coerceIn(0.0, 1.0)
    val displayTime = if (isDragging) seekRatio * duration else position
    val density = LocalDensity.current
    val thumbRadiusPx = with(density) { PlayerProgressThumbSize.toPx() / 2f }

    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.screenPaddingH)) {
        Column(modifier = Modifier.fillMaxWidth()) {
            PlayerWidthProvider { widthPx ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(PlayerProgressTouchHeight)
                        .pointerInput(widthPx) {
                            // 同时支持「点击任意位置直接 seek」与「按住拖动实时跟随」。
                            // 之前用 detectHorizontalDragGestures 只能拖动、点按无反应，
                            // 且需要先越过 touch slop 才生效；这里自己处理指针事件。
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var ratio = (down.position.x / widthPx).coerceIn(0f, 1f)
                                down.consume()
                                onDragging(ratio.toDouble())

                                while (true) {
                                    val change = awaitPointerEvent().changes
                                        .firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    val r = (change.position.x / widthPx).coerceIn(0f, 1f)
                                    if (r != ratio) {
                                        ratio = r
                                        onDragging(ratio.toDouble())
                                    }
                                    change.consume()
                                }
                                // 抬手 / 取消都提交一次 seek
                                onSeekEnd(ratio.toDouble())
                            }
                        },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    // 轨道
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(PlayerProgressTrackHeight)
                            // 轨道底色对齐 Flutter `white @0.08`（此前误用 white10=0.10）
                            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(AppRadius.r2)),
                    )
                    // 已播进度（主色 + 辉光）
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.toFloat())
                            .height(PlayerProgressTrackHeight)
                            .shadow(
                                elevation = AppShadow.blurMd,
                                shape = RoundedCornerShape(AppRadius.r2),
                                clip = false,
                                ambientColor = AppColors.primary,
                                spotColor = AppColors.primary,
                            )
                            .background(AppColors.primary, RoundedCornerShape(AppRadius.r2)),
                    )
                    // 拇指
                    Box(
                        modifier = Modifier
                            .offset { IntOffset((progress.toFloat() * widthPx - thumbRadiusPx).roundToInt(), 0) }
                            .size(PlayerProgressThumbSize)
                            .shadow(
                                elevation = AppShadow.blurSm,
                                shape = CircleShape,
                                clip = false,
                                ambientColor = AppColors.black,
                                spotColor = AppColors.black,
                            )
                            .background(AppColors.white, CircleShape),
                    )
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.sp4))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = playerFormatTime(displayTime),
                    style = TextStyle(
                        color = AppColors.white30,
                        fontSize = AppFontSize.xs,
                        fontFeatureSettings = "tnum",
                    ),
                )
                Text(
                    text = playerFormatTime(duration),
                    style = TextStyle(
                        color = AppColors.whiteA20,
                        fontSize = AppFontSize.xs,
                        fontFeatureSettings = "tnum",
                    ),
                )
            }
        }
    }
}

/**
 * `BoxWithConstraints` 的轻量替身：只需要宽度（px），用于把触摸位置换算成比例。
 *
 * 说明：为避免在每帧更新比例时触发重组，这里直接把约束宽度交给内容 lambda。
 */
@Composable
private fun PlayerWidthProvider(content: @Composable (widthPx: Float) -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
    ) {
        content(constraints.maxWidth.toFloat().coerceAtLeast(1f))
    }
}

/**
 * 控制栏（1:1 移植 Flutter `PlayerControls`）：
 * 循环模式（单曲在右上角叠加「1」角标）/ 上一首 / 白色圆形播放暂停（52dp）/ 下一首 / 队列。
 */
@Composable
fun PlayerControlsBar(
    isPlaying: Boolean,
    loopMode: LoopMode,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onLoopMode: () -> Unit,
    onQueue: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.screenPaddingH),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 循环模式：顺序 -> repeat，单曲 -> repeat + 「1」，随机 -> shuffle（对齐 Flutter 三态三图标）
        Box(
            modifier = Modifier.clickableNoRipple { onLoopMode() },
        ) {
            if (loopMode == LoopMode.shuffle) {
                PlayerShuffleGlyph(
                    modifier = Modifier.size(AppIconSize.icon24),
                    tint = AppColors.white70,
                )
            } else {
                PlayerRepeatGlyph(
                    modifier = Modifier.size(AppIconSize.icon24),
                    tint = AppColors.white70,
                )
                if (loopMode == LoopMode.single) {
                    Text(
                        text = "1",
                        // Flutter `Positioned(top:1, right:1)` 是「向内缩 1」，故 x 负、y 正
                        modifier = Modifier.align(Alignment.TopEnd).offset(x = (-1).dp, y = 1.dp),
                        color = AppColors.white70,
                        fontSize = AppFontSize.size9,
                        fontWeight = AppFontWeight.extraBold,
                    )
                }
            }
        }

        // 上一首
        Box(modifier = Modifier.clickableNoRipple { onPrevious() }) {
            PlayerSkipGlyph(
                forward = false,
                modifier = Modifier.size(AppIconSize.icon24),
                tint = AppColors.white70,
            )
        }

        // 播放 / 暂停（白色圆形大按钮）
        Box(
            modifier = Modifier
                .size(AppSize.bigActionButton)
                .shadow(
                    elevation = AppShadow.blurXl,
                    shape = CircleShape,
                    clip = false,
                    ambientColor = AppColors.white,
                    spotColor = AppColors.white,
                )
                .background(AppColors.white, CircleShape)
                .clickableNoRipple { onTogglePlay() },
            contentAlignment = Alignment.Center,
        ) {
            if (isPlaying) {
                PauseIcon(
                    modifier = Modifier.size(AppIconSize.icon26),
                    tint = AppColors.surface,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = AppColors.surface,
                    modifier = Modifier.size(AppIconSize.icon26),
                )
            }
        }

        // 下一首
        Box(modifier = Modifier.clickableNoRipple { onNext() }) {
            PlayerSkipGlyph(
                forward = true,
                modifier = Modifier.size(AppIconSize.icon24),
                tint = AppColors.white70,
            )
        }

        // 播放队列
        Box(modifier = Modifier.clickableNoRipple { onQueue() }) {
            PlayerQueueGlyph(
                modifier = Modifier.size(AppIconSize.icon24),
                tint = AppColors.white70,
            )
        }
    }
}

/**
 * 音量条（1:1 移植 Flutter `PlayerVolumeSlider`）：
 * 左右 14dp 喇叭图标（white 0.25）、中间 5dp 轨道（white 0.08 / 已音量 white 0.3）。
 * 点击与水平拖拽均更新音量。
 */
@Composable
fun PlayerVolumeSlider(
    volume: Double,
    onVolume: (Double) -> Unit,
) {
    val clamped = volume.coerceIn(0.0, 1.0)

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.screenPaddingH),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerSpeakerGlyph(
            modifier = Modifier.size(PlayerVolumeIconSize),
            tint = AppColors.whiteA25,
            loud = false,
        )
        Spacer(modifier = Modifier.width(AppSpacing.sp10))
        Box(modifier = Modifier.weight(1f)) {
            PlayerWidthProvider { widthPx ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(PlayerVolumeTouchHeight)
                        .pointerInput(widthPx) {
                            detectTapGestures { tap ->
                                onVolume((tap.x / widthPx).coerceIn(0f, 1f).toDouble())
                            }
                        }
                        .pointerInput(widthPx) {
                            detectHorizontalDragGestures(
                                onDragStart = { start ->
                                    onVolume((start.x / widthPx).coerceIn(0f, 1f).toDouble())
                                },
                                onHorizontalDrag = { change, _ ->
                                    onVolume((change.position.x / widthPx).coerceIn(0f, 1f).toDouble())
                                },
                            )
                        },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(PlayerVolumeTrackHeight)
                            // 轨道底色对齐 Flutter `white @0.08`（此前误用 white10=0.10）
                            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(AppRadius.r2_5)),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(clamped.toFloat())
                            .height(PlayerVolumeTrackHeight)
                            .background(AppColors.white30, RoundedCornerShape(AppRadius.r2_5)),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp10))
        PlayerSpeakerGlyph(
            modifier = Modifier.size(PlayerVolumeIconSize),
            tint = AppColors.whiteA25,
            loud = true,
        )
    }
}

/**
 * 待下载条（对齐 Flutter 首页 `_PendingDownloadBar`）：
 * 主色 0.08 底、圆角 14；左侧数量圆标 + 「待下载歌曲」，右侧「清空」与「下载全部」。
 * 播放页复用同一视觉，用于批量下载入口。
 */
@Composable
fun PlayerPendingDownloadBar(
    count: Int,
    batchDownloading: Boolean,
    onView: () -> Unit,
    onClear: () -> Unit,
    onBatch: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = AppSpacing.sp16, end = AppSpacing.sp16, bottom = AppSpacing.sp8)
            .clip(RoundedCornerShape(AppRadius.panel))
            .background(AppColors.primaryA08)
            .padding(horizontal = AppSpacing.sp14, vertical = AppSpacing.sp10),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).clickableNoRipple { onView() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(PlayerPendingBadgeSize)
                    .background(AppColors.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$count",
                    color = AppColors.white,
                    fontSize = AppFontSize.xs,
                    fontWeight = AppFontWeight.bold,
                )
            }
            Spacer(modifier = Modifier.width(AppSpacing.sp6))
            Text(
                text = "待下载歌曲",
                color = AppColors.white70,
                fontSize = AppFontSize.size13,
                fontWeight = AppFontWeight.medium,
            )
        }
        Box(
            modifier = Modifier.clickableNoRipple { onClear() }.padding(AppSpacing.sp4),
        ) {
            Text(
                text = "清空",
                color = AppColors.white38,
                fontSize = AppFontSize.sm,
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(AppRadius.r10))
                .background(AppColors.primary)
                .clickableNoRipple { if (!batchDownloading) onBatch() }
                .padding(horizontal = AppSpacing.sp16, vertical = AppSpacing.sp8),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (batchDownloading) "下载中..." else "下载全部",
                color = AppColors.white,
                fontSize = AppFontSize.size13,
                fontWeight = AppFontWeight.semiBold,
            )
        }
    }
}

/** 循环模式图标：圆角回环 + 双向箭头（对齐 Flutter `Icons.repeat`） */
@Composable
private fun PlayerRepeatGlyph(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.10f
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.16f, h * 0.24f),
            size = Size(w * 0.68f, h * 0.52f),
            cornerRadius = CornerRadius(w * 0.16f, w * 0.16f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        val right = Path().apply {
            moveTo(w * 0.60f, h * 0.10f)
            lineTo(w * 0.60f, h * 0.38f)
            lineTo(w * 0.88f, h * 0.24f)
            close()
        }
        drawPath(path = right, color = tint)
        val left = Path().apply {
            moveTo(w * 0.40f, h * 0.62f)
            lineTo(w * 0.40f, h * 0.90f)
            lineTo(w * 0.12f, h * 0.76f)
            close()
        }
        drawPath(path = left, color = tint)
    }
}

/** 随机播放图标：交叉双向箭头（对齐 Flutter `Icons.shuffle`） */
@Composable
private fun PlayerShuffleGlyph(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.10f
        drawLine(tint, Offset(w * 0.12f, h * 0.24f), Offset(w * 0.60f, h * 0.74f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.12f, h * 0.76f), Offset(w * 0.60f, h * 0.26f), stroke, StrokeCap.Round)
        val arrowTop = Path().apply {
            moveTo(w * 0.56f, h * 0.10f)
            lineTo(w * 0.92f, h * 0.10f)
            lineTo(w * 0.92f, h * 0.46f)
            lineTo(w * 0.70f, h * 0.28f)
            close()
        }
        drawPath(path = arrowTop, color = tint)
        val arrowBottom = Path().apply {
            moveTo(w * 0.56f, h * 0.90f)
            lineTo(w * 0.92f, h * 0.90f)
            lineTo(w * 0.92f, h * 0.54f)
            lineTo(w * 0.70f, h * 0.72f)
            close()
        }
        drawPath(path = arrowBottom, color = tint)
    }
}

/** 上一首 / 下一首图标（对齐 Flutter `Icons.skip_previous` / `Icons.skip_next`） */
@Composable
private fun PlayerSkipGlyph(forward: Boolean, modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val triangle = Path().apply {
            if (forward) {
                moveTo(w * 0.16f, h * 0.18f)
                lineTo(w * 0.68f, h * 0.50f)
                lineTo(w * 0.16f, h * 0.82f)
            } else {
                moveTo(w * 0.84f, h * 0.18f)
                lineTo(w * 0.32f, h * 0.50f)
                lineTo(w * 0.84f, h * 0.82f)
            }
            close()
        }
        drawPath(path = triangle, color = tint)
        val barX = if (forward) w * 0.76f else w * 0.16f
        drawRoundRect(
            color = tint,
            topLeft = Offset(barX, h * 0.18f),
            size = Size(w * 0.08f, h * 0.64f),
            cornerRadius = CornerRadius(w * 0.04f, w * 0.04f),
        )
    }
}

/** 队列图标：三条圆角横线（对齐 Flutter `Icons.list`） */
@Composable
private fun PlayerQueueGlyph(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.10f
        val ys = listOf(0.28f, 0.50f, 0.72f)
        for (y in ys) {
            drawLine(
                color = tint,
                start = Offset(w * 0.14f, h * y),
                end = Offset(w * 0.86f, h * y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** 喇叭音量图标：填充喇叭 + 1~2 道声波弧线（对齐 Flutter `Icons.volume_down` / `volume_up`） */
@Composable
private fun PlayerSpeakerGlyph(modifier: Modifier = Modifier, tint: Color, loud: Boolean) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val body = Path().apply {
            moveTo(w * 0.08f, h * 0.36f)
            lineTo(w * 0.28f, h * 0.36f)
            lineTo(w * 0.52f, h * 0.14f)
            lineTo(w * 0.52f, h * 0.86f)
            lineTo(w * 0.28f, h * 0.64f)
            lineTo(w * 0.08f, h * 0.64f)
            close()
        }
        drawPath(path = body, color = tint)
        val stroke = w * 0.10f
        drawArc(
            color = tint,
            startAngle = -50f,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = Offset(w * 0.44f, h * 0.30f),
            size = Size(w * 0.28f, h * 0.40f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        if (loud) {
            drawArc(
                color = tint,
                startAngle = -50f,
                sweepAngle = 100f,
                useCenter = false,
                topLeft = Offset(w * 0.44f, h * 0.12f),
                size = Size(w * 0.50f, h * 0.76f),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}
