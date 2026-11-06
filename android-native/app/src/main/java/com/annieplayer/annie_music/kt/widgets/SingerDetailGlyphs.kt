package com.annieplayer.annie_music.kt.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.ui.theme.AppColors

// ================================================================================================
// 歌手详情页的私有自绘图标（统一笔重语言）
// ================================================================================================
//
// 为什么单独一套、不复用 widgets/DetailGlyphs.kt 与 widgets/Icons.kt：那批图形由各页面按各自
// 场景定的比例描边（0.09f ~ 0.14f），同屏并排时笔重不一致；共用函数又被其它页面引用、不能改动，
// 因此这里重画一套，统一：
//   - 恒定 2dp 等效描边（[SingerDetailIconStroke]，不随图标尺寸缩放）；
//   - 线稿一律 round cap / round join；
//   - 比例坐标（w / h 的 0~1 系数，与图标尺寸解耦）。
// 实心图形（PlayTriangle）与 Material 进度环不涉及笔重，留在 SingerDetailSheet.kt 里。
//
// 从 SingerDetailSheet.kt 拆出来是因为那个文件已经 1900+ 行，图标组是其中最独立的一块
// （只有本页在用、不依赖任何状态），拆走后主文件只剩"装配 + 三个区块"。

/** 本页图标统一笔重：2dp 等效描边（恒定值，保证同屏所有线稿粗细一致）。 */
private val SingerDetailIconStroke = 2.dp

/** chevron_left：返回箭头 / 分页上一页（本页统一 2dp 圆头笔画） */
@Composable
internal fun SingerDetailChevronLeft(
    modifier: Modifier = Modifier,
    tint: Color = AppColors.textSecondary,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = SingerDetailIconStroke.toPx()
        drawLine(tint, Offset(w * 0.62f, h * 0.16f), Offset(w * 0.30f, h * 0.5f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.30f, h * 0.5f), Offset(w * 0.62f, h * 0.84f), stroke, StrokeCap.Round)
    }
}

/** chevron_right：分页下一页（本页统一 2dp 圆头笔画） */
@Composable
internal fun SingerDetailChevronRight(
    modifier: Modifier = Modifier,
    tint: Color = AppColors.textSecondary,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = SingerDetailIconStroke.toPx()
        drawLine(tint, Offset(w * 0.38f, h * 0.16f), Offset(w * 0.70f, h * 0.5f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.70f, h * 0.5f), Offset(w * 0.38f, h * 0.84f), stroke, StrokeCap.Round)
    }
}

/** arrow_upward：专辑「时间正序」（本页统一 2dp 圆头笔画） */
@Composable
internal fun SingerDetailArrowUp(
    modifier: Modifier = Modifier,
    tint: Color = AppColors.whiteA45,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = SingerDetailIconStroke.toPx()
        drawLine(tint, Offset(w * 0.5f, h * 0.86f), Offset(w * 0.5f, h * 0.18f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.18f, h * 0.5f), Offset(w * 0.5f, h * 0.16f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.82f, h * 0.5f), Offset(w * 0.5f, h * 0.16f), stroke, StrokeCap.Round)
    }
}

/** arrow_downward：专辑「时间倒序」（本页统一 2dp 圆头笔画） */
@Composable
internal fun SingerDetailArrowDown(
    modifier: Modifier = Modifier,
    tint: Color = AppColors.whiteA45,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = SingerDetailIconStroke.toPx()
        drawLine(tint, Offset(w * 0.5f, h * 0.14f), Offset(w * 0.5f, h * 0.82f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.18f, h * 0.5f), Offset(w * 0.5f, h * 0.84f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.82f, h * 0.5f), Offset(w * 0.5f, h * 0.84f), stroke, StrokeCap.Round)
    }
}

/** person_outline：头像兜底（头圆 + 肩弧，本页统一 2dp 圆头笔画） */
@Composable
internal fun SingerDetailPersonOutline(
    modifier: Modifier = Modifier,
    tint: Color = AppColors.textDisabled,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = SingerDetailIconStroke.toPx()
        drawCircle(
            color = tint,
            radius = w * 0.17f,
            center = Offset(w * 0.5f, h * 0.31f),
            style = Stroke(width = stroke),
        )
        drawArc(
            color = tint,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.16f, h * 0.50f),
            size = Size(w * 0.68f, h * 0.62f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/** error_outline：加载失败态（描边圆 + 感叹号，本页统一 2dp 圆头笔画） */
@Composable
internal fun SingerDetailErrorOutline(
    modifier: Modifier = Modifier,
    tint: Color = AppColors.textDisabled,
) {
    Canvas(modifier = modifier) {
        val min = size.minDimension
        val stroke = SingerDetailIconStroke.toPx()
        drawCircle(
            color = tint,
            radius = (min - stroke) / 2f,
            style = Stroke(width = stroke),
        )
        drawLine(tint, Offset(min * 0.5f, min * 0.28f), Offset(min * 0.5f, min * 0.55f), stroke, StrokeCap.Round)
        drawCircle(color = tint, radius = min * 0.055f, center = Offset(min * 0.5f, min * 0.72f))
    }
}

/** check：选中态对勾（本页统一 2dp 圆头端点 / 圆角连接） */
@Composable
internal fun SingerDetailCheckMark(modifier: Modifier = Modifier, tint: Color = AppColors.white) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = SingerDetailIconStroke.toPx()
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

/** album：封面兜底（描边圆 + 圆心实点，本页统一 2dp 圆环） */
@Composable
internal fun SingerDetailAlbumGlyph(modifier: Modifier = Modifier, tint: Color = AppColors.white10) {
    Canvas(modifier = modifier) {
        val min = size.minDimension
        val stroke = SingerDetailIconStroke.toPx()
        drawCircle(
            color = tint,
            radius = (min - stroke) / 2f,
            style = Stroke(width = stroke),
        )
        drawCircle(color = tint, radius = min * 0.14f)
    }
}

/** download：行尾下载（箭身 + 描边箭头 + 托盘，本页统一 2dp 圆头笔画） */
@Composable
internal fun SingerDetailDownloadGlyph(modifier: Modifier = Modifier, tint: Color = AppColors.whiteA25) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = SingerDetailIconStroke.toPx()
        // 箭身
        drawLine(tint, Offset(w * 0.5f, h * 0.10f), Offset(w * 0.5f, h * 0.44f), stroke, StrokeCap.Round)
        // 空心箭头（描边三角形）
        val head = Path().apply {
            moveTo(w * 0.20f, h * 0.44f)
            lineTo(w * 0.80f, h * 0.44f)
            lineTo(w * 0.50f, h * 0.74f)
            close()
        }
        drawPath(
            path = head,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        // 托盘
        drawLine(tint, Offset(w * 0.22f, h * 0.90f), Offset(w * 0.78f, h * 0.90f), stroke, StrokeCap.Round)
    }
}

/** music_note：封面 / 专辑行音乐占位（本页统一 2dp 圆头笔画） */
@Composable
internal fun SingerDetailMusicNote(modifier: Modifier = Modifier, tint: Color = AppColors.whiteA15) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = SingerDetailIconStroke.toPx()
        // 符干
        drawLine(tint, Offset(w * 0.64f, h * 0.12f), Offset(w * 0.64f, h * 0.68f), stroke, StrokeCap.Round)
        // 符头
        drawCircle(color = tint, radius = w * 0.20f, center = Offset(w * 0.44f, h * 0.70f))
        // 符尾
        drawLine(tint, Offset(w * 0.64f, h * 0.12f), Offset(w * 0.88f, h * 0.26f), stroke, StrokeCap.Round)
    }
}
