package com.annieplayer.annie_music.kt.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import com.annieplayer.annie_music.kt.ui.theme.AppColors

/**
 * 爱心 glyph（收藏按钮 / 收藏库入口，自绘）。
 *
 * 形状与 `LocalCategoryGlyph` 那族同一条语言：同一个描边比例 + 圆头端点 + 圆角连接；
 * 描边态（未收藏）与填充态（已收藏）共用同一条路径，只换 style —— 切换时形状不跳。
 *
 * 路径按「两片近圆的叶 + 收成尖以及中凹」构造，而不用一条宽扁的贝塞尔：
 * 前者在 20~28dp 下外轮廓的曲率是均匀的，缩小时不会像鼓起来的气球；
 * 填充态再叠一层极轻的竖向渐变（同色压暗 18%），免得大面积纯色看起来像贴纸。
 */
@Composable
fun HeartGlyph(
    modifier: Modifier = Modifier,
    tint: Color = AppColors.white,
    filled: Boolean = false,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            // 尖端在下，两片近圆的叶子在上；中凹落在 0.50 的竖轴上，深 0.19（约等于叶高的一半，
            // 24dp 缩小后仍能读出「两瓣 + 一尖」，不会糊成一个圆）
            moveTo(w * 0.50f, h * 0.925f)
            cubicTo(w * 0.415f, h * 0.85f, w * 0.075f, h * 0.625f, w * 0.075f, h * 0.365f)
            cubicTo(w * 0.075f, h * 0.185f, w * 0.215f, h * 0.075f, w * 0.365f, h * 0.075f)
            cubicTo(w * 0.44f, h * 0.075f, w * 0.487f, h * 0.15f, w * 0.50f, h * 0.265f)
            cubicTo(w * 0.513f, h * 0.15f, w * 0.56f, h * 0.075f, w * 0.635f, h * 0.075f)
            cubicTo(w * 0.785f, h * 0.075f, w * 0.925f, h * 0.185f, w * 0.925f, h * 0.365f)
            cubicTo(w * 0.925f, h * 0.625f, w * 0.585f, h * 0.85f, w * 0.50f, h * 0.925f)
            close()
        }
        if (filled) {
            drawPath(
                path = path,
                brush = Brush.verticalGradient(
                    colors = listOf(tint, lerp(tint, Color.Black, 0.18f)),
                ),
            )
        } else {
            drawPath(
                path = path,
                color = tint,
                style = Stroke(
                    width = w * 0.085f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }
    }
}
