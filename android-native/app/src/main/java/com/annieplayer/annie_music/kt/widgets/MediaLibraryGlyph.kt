package com.annieplayer.annie_music.kt.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.annieplayer.annie_music.kt.ui.theme.AppColors

/**
 * 「收藏媒体库」glyph（底栏 tab 图标，自绘）。
 *
 * 形状照 Apple Music 的资料库图标：**顶部三页书页 + 圆角书身 + 内嵌八分音符**。
 * 与 [LocalLibraryGlyph]（只有书身 + 音符）刻意区分：那个是「设备上的文件」，
 * 这个是「我收藏的东西」，两者在底栏并排，形状不能撞。
 *
 * 笔画比例用与其它 glyph 一致的 0.085 —— 底栏三颗图标并排时粗细必须一致，
 * 不然选中态一换色就能看出某一颗偏粗。
 */
@Composable
fun MediaLibraryGlyph(modifier: Modifier = Modifier, tint: Color = AppColors.white) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.085f

        // 顶部三页：最左一页缩得最短，越往下越宽（像叠起来的书页）
        drawLine(tint, Offset(w * 0.34f, h * 0.11f), Offset(w * 0.66f, h * 0.11f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.27f, h * 0.21f), Offset(w * 0.73f, h * 0.21f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.20f, h * 0.31f), Offset(w * 0.80f, h * 0.31f), stroke, StrokeCap.Round)

        // 书身
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.13f + stroke / 2f, h * 0.40f + stroke / 2f),
            size = Size(w * 0.74f - stroke, h * 0.50f - stroke),
            cornerRadius = CornerRadius(w * 0.17f, w * 0.17f),
            style = Stroke(width = stroke),
        )

        // 内嵌八分音符：符干 + 符头 + 符尾（略收细，避免与书身糊在一起）
        val noteStroke = stroke * 0.88f
        drawLine(
            color = tint,
            start = Offset(w * 0.57f, h * 0.48f),
            end = Offset(w * 0.57f, h * 0.74f),
            strokeWidth = noteStroke,
            cap = StrokeCap.Round,
        )
        drawCircle(color = tint, radius = w * 0.075f, center = Offset(w * 0.47f, h * 0.74f))
        drawLine(
            color = tint,
            start = Offset(w * 0.57f, h * 0.48f),
            end = Offset(w * 0.67f, h * 0.51f),
            strokeWidth = noteStroke,
            cap = StrokeCap.Round,
        )
    }
}
