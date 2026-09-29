package com.annieplayer.annie_music.kt.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box as LayoutBox
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration

/**
 * 本文件收纳 UI 层需要用到的**自绘图标与小部件**。
 *
 * 为什么不直接用 Material 图标：`material-icons-core` 只含少量基础图标，
 * `CheckBox` / `Download` / `MusicNote` / `PlayArrow(带描边)` / `MoreVert` 等
 * 属于 `material-icons-extended`。为几个图标引入整包扩展集不划算（体积可观），
 * 因此这里按 Flutter 的图标栅格自绘等价形状。
 */

/** 无涟漪点击（对齐 Flutter `GestureDetector`，不带 Material 水波纹） */
@Composable
fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = interaction,
        indication = null,
        onClick = onClick,
    )
}

/** 无涟漪点击 + 长按（对齐 Flutter `GestureDetector` 的 `onTap` / `onLongPress`） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.combinedClickableNoRipple(onTap: () -> Unit, onLongClick: (() -> Unit)?): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this.combinedClickable(
        interactionSource = interaction,
        indication = null,
        onClick = onTap,
        onLongClick = onLongClick,
    )
}

/**
 * 勾选框图标：圆角方块 + 勾。
 * 形状对齐 Flutter `Icons.check_box`（实心主色方块、白色勾）。
 */
@Composable
fun CheckSquareIcon(
    modifier: Modifier = Modifier,
    color: Color = AppColors.primary,
    checkColor: Color = Color.White,
) {
    Canvas(modifier = modifier) {
        val side = size.minDimension
        val r = side * 0.16f
        drawRoundRect(
            color = color,
            cornerRadius = CornerRadius(r, r),
            size = Size(side, side),
        )
        val p = Path().apply {
            moveTo(side * 0.26f, side * 0.52f)
            lineTo(side * 0.44f, side * 0.70f)
            lineTo(side * 0.76f, side * 0.30f)
        }
        drawPath(
            path = p,
            color = checkColor,
            style = Stroke(width = side * 0.12f, cap = StrokeCap.Round),
        )
    }
}

/**
 * 下载图标占位：向下箭头 + 底部横线。
 * 形状对齐 Flutter `Icons.download_outlined`（描边风格）。
 */
@Composable
fun DownloadIconPlaceholder(
    modifier: Modifier = Modifier,
    tint: Color = Color(0x80FFFFFF),
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.11f
        // 竖线（到箭头三角形上沿为止）
        drawLine(
            color = tint,
            start = Offset(w * 0.5f, h * 0.10f),
            end = Offset(w * 0.5f, h * 0.44f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        // 空心箭头（描边三角形，对齐 Flutter Icons.download_outlined）
        val head = Path().apply {
            moveTo(w * 0.20f, h * 0.44f)
            lineTo(w * 0.80f, h * 0.44f)
            lineTo(w * 0.50f, h * 0.74f)
            close()
        }
        drawPath(
            path = head,
            color = tint,
            style = Stroke(width = stroke, join = androidx.compose.ui.graphics.StrokeJoin.Round),
        )
        // 底部横线
        drawLine(
            color = tint,
            start = Offset(w * 0.22f, h * 0.90f),
            end = Offset(w * 0.78f, h * 0.90f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * 音符旗标占位（对齐 Flutter `Icons.music_note` 的八分音符剪影）。
 * 用于封面缺失/加载失败的占位。
 */
@Composable
fun MusicNoteGlyph(
    modifier: Modifier = Modifier,
    tint: Color = Color(0x26FFFFFF),
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.12f
        // 符干
        drawLine(
            color = tint,
            start = Offset(w * 0.66f, h * 0.10f),
            end = Offset(w * 0.66f, h * 0.70f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        // 符头
        drawCircle(color = tint, radius = w * 0.21f, center = Offset(w * 0.45f, h * 0.72f))
        // 符尾
        drawLine(
            color = tint,
            start = Offset(w * 0.66f, h * 0.10f),
            end = Offset(w * 0.94f, h * 0.24f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** 播放三角（实心，用于"当前播放"封面叠加） */
@Composable
fun PlayTriangle(
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val p = Path().apply {
            moveTo(w * 0.24f, h * 0.14f)
            lineTo(w * 0.86f, h * 0.5f)
            lineTo(w * 0.24f, h * 0.86f)
            close()
        }
        drawPath(path = p, color = tint)
    }
}

/**
 * 下载中环形指示器（对齐 Flutter `_DownloadRing`）：
 * 16dp 圆环、2dp 描边、顶部主色其余为轨道色，**900ms 线性旋转**。
 */
@Composable
fun DownloadRing(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "downloadRing")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = AppDuration.downloadRingSpin, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "downloadRingAngle",
    )
    val ringTrack = Color(0x336366F1)

    LayoutBox(modifier = modifier.size(16.dp)) {
        Canvas(modifier = Modifier.size(16.dp).rotate(angle)) {
            val stroke = 2.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            // 轨道（整圈，除顶部）
            drawArc(
                color = ringTrack,
                startAngle = -90f + 360f * (1f / 4f),
                sweepAngle = 360f * (3f / 4f),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke),
            )
            // 顶部主色弧
            drawArc(
                color = AppColors.primary,
                startAngle = -90f,
                sweepAngle = 360f / 4f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke),
            )
        }
    }
}

/**
 * 圆形实心「!」图标（对齐 Flutter `Icons.error`：实心圆 + 感叹号镂空）。
 * [markColor] 为感叹号颜色（应取所在背景色，模拟镂空）。
 */
@Composable
fun ErrorCircleGlyph(color: Color, markColor: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val d = minOf(size.width, size.height)
        drawCircle(color = color, radius = d / 2f)
        val barW = d * 0.14f
        drawRoundRect(
            color = markColor,
            topLeft = Offset(d * 0.43f, d * 0.24f),
            size = Size(barW, d * 0.40f),
            cornerRadius = CornerRadius(barW / 2f, barW / 2f),
        )
        drawCircle(color = markColor, radius = d * 0.075f, center = Offset(d * 0.5f, d * 0.76f))
    }
}

/**
 * 圆形实心「×」图标（对齐 Flutter `Icons.cancel`：实心圆 + 叉号镂空）。
 * [markColor] 为叉号颜色（应取所在背景色，模拟镂空）。
 */
@Composable
fun CancelCircleGlyph(color: Color, markColor: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val d = minOf(size.width, size.height)
        drawCircle(color = color, radius = d / 2f)
        val stroke = d * 0.12f
        val inset = d * 0.30f
        drawLine(markColor, Offset(inset, inset), Offset(d - inset, d - inset), stroke, StrokeCap.Round)
        drawLine(markColor, Offset(d - inset, inset), Offset(inset, d - inset), stroke, StrokeCap.Round)
    }
}

/** 文本占位（给后续页面复用的极简行，避免各页面重复写 `Text` 样板） */
@Composable
fun PlaceholderText(text: String, modifier: Modifier = Modifier) {
    LayoutBox(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(text = text)
    }
}
