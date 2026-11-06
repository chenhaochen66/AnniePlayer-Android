package com.annieplayer.annie_music.kt.features.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.ui.theme.AppColors

/**
 * 主页区块标题左侧的图形徽标（替代原来那根 3×16dp 的渐变竖条）。
 *
 * ## 为什么自绘而不是放 SVG
 * 工程里没有 vector asset 目录，全部图形都是 Canvas 自绘（见 `widgets/Icons.kt` /
 * `DetailGlyphs.kt`）。自绘还有一个好处：**颜色能跟着主题走**，不用为深浅两套各出一份 XML。
 *
 * ## 为什么要分四种
 * 四个区块的语义差别很大（新歌 / 歌单 / 专辑 / 歌手），一根竖条表达不出任何信息。
 * 每种图形都控制在 16dp 内能看清的笔画数量，外加一枚 28dp 的渐变徽标底 + 细环，
 * 让它在深色背景上"浮"起来。
 */
internal enum class HomeShelfGlyphKind {
    /** 新歌速递：四角星（新东西、闪一下） */
    Sparkle,

    /** 歌单类：三横线 + 音符头 */
    Playlist,

    /** 专辑类：唱片（外环 + 内芯 + 高光弧） */
    Album,

    /** 歌手类：头 + 肩 */
    Artist,
}

/** 徽标直径。 */
private val GlyphChipSize = 28.dp

/** 图形本体边长（小于徽标，留出内边距）。 */
private val GlyphSize = 16.dp

/** 图形与细环的主色（图标本体用白色，环与底用主色，保证深色背景下有层次）。 */
private val GlyphAccent = AppColors.primary

/**
 * 区块标题前的图形徽标。
 *
 * 结构：渐变圆底（主色 30% → 6%）+ 1dp 主色细环 + 白色自绘图形。
 */
@Composable
internal fun HomeShelfGlyphChip(
    kind: HomeShelfGlyphKind,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(GlyphChipSize)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(
                        GlyphAccent.copy(alpha = 0.30f),
                        GlyphAccent.copy(alpha = 0.06f),
                    ),
                ),
            )
            .drawBehind {
                drawCircle(
                    color = GlyphAccent.copy(alpha = 0.34f),
                    radius = size.minDimension / 2f - 0.6.dp.toPx(),
                    style = Stroke(width = 1.dp.toPx()),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(GlyphSize)) {
            when (kind) {
                HomeShelfGlyphKind.Sparkle -> drawSparkle()
                HomeShelfGlyphKind.Playlist -> drawPlaylistGlyph()
                HomeShelfGlyphKind.Album -> drawAlbumGlyph()
                HomeShelfGlyphKind.Artist -> drawArtistGlyph()
            }
        }
    }
}

/** 四角星：八个顶点长短交替，中间留一个高光点。 */
private fun DrawScope.drawSparkle() {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h / 2f
    val long = w * 0.46f
    val short = w * 0.13f
    val path = Path()
    // 上 右 下 左 四个长角 + 四个短角的对角，构成一个「闪光」
    path.moveTo(cx, cy - long)
    path.lineTo(cx + short, cy - short)
    path.lineTo(cx + long, cy)
    path.lineTo(cx + short, cy + short)
    path.lineTo(cx, cy + long)
    path.lineTo(cx - short, cy + short)
    path.lineTo(cx - long, cy)
    path.lineTo(cx - short, cy - short)
    path.close()
    drawPath(path, color = AppColors.white)
    // 右上角再点一颗小星，避免整块过于对称
    drawCircle(
        color = AppColors.white.copy(alpha = 0.75f),
        radius = w * 0.07f,
        center = Offset(cx + w * 0.30f, cy - h * 0.30f),
    )
}

/** 歌单：三条长度递减的横线 + 右下角音符头（一个实心点 + 一根小尾巴）。 */
private fun DrawScope.drawPlaylistGlyph() {
    val w = size.width
    val h = size.height
    val stroke = w * 0.11f
    val left = w * 0.10f
    fun line(y: Float, right: Float) {
        drawLine(
            color = AppColors.white,
            start = Offset(left, y),
            end = Offset(right, y),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
    line(h * 0.20f, w * 0.90f)
    line(h * 0.44f, w * 0.72f)
    line(h * 0.68f, w * 0.56f)
    // 音符头
    drawCircle(
        color = AppColors.white,
        radius = w * 0.10f,
        center = Offset(w * 0.80f, h * 0.80f),
    )
    drawLine(
        color = AppColors.white,
        start = Offset(w * 0.89f, h * 0.80f),
        end = Offset(w * 0.89f, h * 0.50f),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
}

/** 专辑：唱片 —— 外环 + 内芯 + 左上一道高光弧。 */
private fun DrawScope.drawAlbumGlyph() {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h / 2f
    drawCircle(
        color = AppColors.white,
        radius = w * 0.42f,
        center = Offset(cx, cy),
        style = Stroke(width = w * 0.10f),
    )
    drawCircle(color = AppColors.white, radius = w * 0.15f, center = Offset(cx, cy))
    // 高光弧：从 200° 到 290°
    val arcPath = Path().apply {
        val r = w * 0.28f
        moveTo(cx - r * 0.94f, cy - r * 0.34f)
        quadraticTo(cx, cy - r * 1.05f, cx + r * 0.94f, cy - r * 0.34f)
    }
    drawPath(
        path = arcPath,
        color = AppColors.white.copy(alpha = 0.55f),
        style = Stroke(width = w * 0.075f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/** 歌手：一个头 + 一条肩弧。 */
private fun DrawScope.drawArtistGlyph() {
    val w = size.width
    val h = size.height
    val stroke = w * 0.11f
    drawCircle(
        color = AppColors.white,
        radius = w * 0.20f,
        center = Offset(w / 2f, h * 0.30f),
        style = Stroke(width = stroke),
    )
    val shoulder = Path().apply {
        moveTo(w * 0.16f, h * 0.86f)
        quadraticTo(w / 2f, h * 0.46f, w * 0.84f, h * 0.86f)
    }
    drawPath(
        path = shoulder,
        color = AppColors.white,
        style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/** 兜底：只是一个圆点，给以后新增、还没配图形的区块用。 */
@Suppress("unused")
private fun DrawScope.drawDotGlyph() {
    drawCircle(color = Color.White, radius = size.minDimension * 0.3f)
}

// ================================================================================================
// 区块右侧的操作按钮（换风格 / 换一批）
// ================================================================================================

/**
 * 区块标题右端的操作图形。
 *
 * 两个刻意画成不同形状，不能混：
 * - [CycleStyle]：**调色盘**（圆环 + 环内三颗色点）—— 语义是「换一个风格」；
 * - [RefreshPicks]：**环形箭头**（一段圆弧 + 箭头尖）—— 语义是「重新挑一批」。
 */
internal enum class HomeActionGlyph {
    CycleStyle,
    RefreshPicks,
}

/**
 * 区块操作按钮：44dp 命中区（Material 最小触摸尺寸）+ 16dp 图形，无底无边。
 *
 * 不铺背景是刻意的：它和标题在同一行，铺了会把 `[图形徽标][标题][副标题]` 那条基线压断；
 * 靠 50% 白 + 按下时提亮来提示可点。
 */
@Composable
internal fun HomeShelfActionButton(
    glyph: HomeActionGlyph,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val tint = if (pressed) AppColors.white else AppColors.glyphMid
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(16.dp)) {
            when (glyph) {
                HomeActionGlyph.CycleStyle -> drawPaletteGlyph(tint)
                HomeActionGlyph.RefreshPicks -> drawRefreshGlyph(tint)
            }
        }
    }
}

/** 调色盘：细圆环 + 环内三颗实心色点（120° 均布）。 */
private fun DrawScope.drawPaletteGlyph(tint: Color) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h / 2f
    val radius = w * 0.40f
    drawCircle(
        color = tint,
        radius = radius,
        center = Offset(cx, cy),
        style = Stroke(width = w * 0.09f),
    )
    val dotRadius = w * 0.075f
    val orbit = radius * 0.48f
    for (i in 0 until 3) {
        val angle = (-90.0 + i * 120.0) * Math.PI / 180.0
        drawCircle(
            color = tint,
            radius = dotRadius,
            center = Offset(
                cx + (orbit * kotlin.math.cos(angle)).toFloat(),
                cy + (orbit * kotlin.math.sin(angle)).toFloat(),
            ),
        )
    }
}

/** 环形箭头：约 300° 的圆弧 + 缺口处一个箭头尖。 */
private fun DrawScope.drawRefreshGlyph(tint: Color) {
    val w = size.width
    val h = size.height
    val stroke = w * 0.10f
    val radius = w * 0.40f
    val topLeft = Offset(w / 2f - radius, h / 2f - radius)
    val side = radius * 2f
    // 从 -60° 起、逆时针留 60° 缺口
    drawArc(
        color = tint,
        startAngle = -55f,
        sweepAngle = 300f,
        useCenter = false,
        topLeft = topLeft,
        size = androidx.compose.ui.geometry.Size(side, side),
        style = Stroke(width = stroke, cap = StrokeCap.Round),
    )
    // 缺口右上的箭头尖（两条短线组成一个「<」）
    val tipX = w / 2f + radius * 0.72f
    val tipY = h / 2f - radius * 0.72f
    val arm = w * 0.16f
    drawLine(
        color = tint,
        start = Offset(tipX - arm, tipY - arm * 0.1f),
        end = Offset(tipX, tipY),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = tint,
        start = Offset(tipX - arm * 0.1f, tipY - arm),
        end = Offset(tipX, tipY),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
}
