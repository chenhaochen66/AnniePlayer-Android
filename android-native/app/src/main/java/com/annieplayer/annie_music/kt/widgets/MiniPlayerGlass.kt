package com.annieplayer.annie_music.kt.widgets

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import kotlin.math.max
import kotlin.math.roundToInt

// ================================================================================================
// 迷你播放条 / 底栏的「玻璃水杯」玻璃
// ================================================================================================
//
// 移植自 LunaBeat `ui/components/GlobalMiniPlayerBarKt.kt`：
//   jadx  `D:\AnnieMusic\_ref\lunabeat_src\sources\com\example\LyricBox\ui\components\GlobalMiniPlayerBarKt.java`
//   smali `D:\AnnieMusic\_ref\lunabeat_smali\smali_classes3\com\example\LyricBox\ui\components\GlobalMiniPlayerBarKt.smali`
//
// 它由**两层完全不同的东西**组成，缺一层都不像：
//
// ## 第一层：封面 → 盒式模糊（不是高斯）
//
// `createMiniPlayerStaticBlurBackgroundBitmap`（jadx 第 749 行）：
// ```java
// int w = coerceIn(bitmap.getWidth(), 40, 80);          // 宽先夹到 40..80
// Bitmap scaled = Bitmap.createScaledBitmap(bitmap, w, round(h * (w / width)), true);
// return blurMiniPlayerBitmapFast(scaled, 12);          // 再盒式模糊，半径 12
// ```
//
// `blurMiniPlayerBitmapFast` 是**可分离盒式模糊**（先横后纵，滑动窗口累加，半径 clamp 1..25），
// 不是 `RenderEffect` 的高斯。盒式模糊的核是恒定权重：边缘有硬台阶、放大后能看出方形颗粒；
// 高斯是「羽状、无结构」。这就是它看起来比高斯更"实"的原因。
//
// 之所以既不糊也不清晰：位图只有 40~80 像素宽，放大到 ~360dp 的条栏是 10~20 倍，
// 双线性插值本身就贡献了大量柔化，盒式模糊只是把插值产生的结构再打散一层。
//
// ## 第二层：边缘 SDF 折射（AGSL，API 33+）
//
// `GlobalMiniPlayerBarKt$lambda$41$lambda$40`（jadx 第 263 行）挂在一个 `graphicsLayer` 上：
//
// 1. 先设 `Modifier.blur(6.dp)` → `RenderEffect.createBlurEffect(6dp, 6dp, TileMode.Clamp)`
// 2. 若 API ≥ 33：**把 blur 减掉 24dp**（`6dp - 24dp` 夹到 0）→ 高斯模糊被完全取消，
//    同时挂上 `RuntimeShader("Refraction")`
//
// 也就是说：在支持 AGSL 的设备上，LunaBeat 的迷你条**根本不做模糊**，
// 边缘那圈「玻璃感」完全由折射产生。
//
// 折射只在距边缘 `refractionHeight`（24dp）以内生效，中心原样输出；边缘处按
// `circleMap(x) = 1 - sqrt(1 - x²)` 曲线向内偏移采样坐标，越靠边偏移越大（最大 24dp）。
// 视觉上就是「透过玻璃杯壁看内容」—— 内容被轻微挤压变形，但一点都没糊。
//
// 下面的 GLSL 逐字照抄（`depthEffect` 原实现传 0）。

// ================================================================================================
// 参数（数值全部来自 LunaBeat，改任何一个都会让观感跑偏）
// ================================================================================================

/** 条栏玻璃的全部数值参数。 */
internal object BarGlassSpec {

    /** 背景位图宽度下限 / 上限（`coerceIn(width, 40, 80)`）。 */
    const val BACKDROP_MIN_WIDTH = 40
    const val BACKDROP_MAX_WIDTH = 80

    /** 盒式模糊半径（`blurMiniPlayerBitmapFast(scaled, 12)`）。 */
    const val BACKDROP_BLUR_RADIUS = 12

    /**
     * 背景封面图层不透明度。
     *
     * LunaBeat 原值 0.86（`drawImage(..., alpha = 0.86f)`）。实测在本工程偏实——
     * 封面细节透得太清楚，玻璃读成了「一张压暗的封面照」而不是「透过去的颜色」。
     * 0.50 之后封面只提供色相与大致明暗，具体形状交给盒式模糊 + 压黑层糊掉。
     */
    const val COVER_ALPHA = 0.50f

    /** 折射带宽度（`refractionHeight = 24.dp`）。 */
    const val REFRACTION_HEIGHT_DP = 24f

    /** 折射强度（`refractionAmount = -24.dp`，负值 = 向内偏移）。 */
    const val REFRACTION_AMOUNT_DP = 24f

    /** 有封面时压的黑（`Black.copy(alpha = 0.32f)`，smali line 158）。 */
    const val SCRIM_ALPHA = 0.32f

    /**
     * 背景渐变 ①：`[White 0.035, Transparent, Black 0.07]`（smali line 161-163）。
     *
     * 只给画面加一点「上受光、下压暗」的方向感，非常淡。
     */
    const val OVERLAY_TOP_WHITE = 0.035f
    const val OVERLAY_BOTTOM_BLACK = 0.07f

    /**
     * 背景渐变 ②（取色染色）：`[Black 0.18, 取色 0.22, Black 0.24]`（smali line 173-175）。
     *
     * 和 LunaBeat 页面背景那套 `[Black 0.14, 取色 0.22, Black 0.28]` 是同一个设计语言 ——
     * 取色永远出现在**中间那个 0.22 的停靠点**上。
     */
    const val TINT_TOP_BLACK = 0.18f
    const val TINT_MIDDLE = 0.22f
    const val TINT_BOTTOM_BLACK = 0.24f

    /** 封面 → 条栏背景位图。 */
    fun backdrop(source: Bitmap): Bitmap = createBarBackdrop(source)
}

// ================================================================================================
// 背景位图
// ================================================================================================

/**
 * 封面 → 条栏背景位图（LunaBeat `createMiniPlayerStaticBlurBackgroundBitmap`）。
 *
 * 宽夹到 [BarGlassSpec.BACKDROP_MIN_WIDTH]..[BarGlassSpec.BACKDROP_MAX_WIDTH]，
 * 等比缩放（`filter = true`），再做一次半径 [BarGlassSpec.BACKDROP_BLUR_RADIUS] 的盒式模糊。
 */
private fun createBarBackdrop(source: Bitmap): Bitmap {
    if (source.width <= 0 || source.height <= 0) return source
    val targetWidth = source.width.coerceIn(
        BarGlassSpec.BACKDROP_MIN_WIDTH,
        BarGlassSpec.BACKDROP_MAX_WIDTH,
    )
    val targetHeight = max(
        ((source.height.toFloat() * targetWidth) / max(source.width, 1)).roundToInt(),
        1,
    )
    val scaled = Bitmap.createScaledBitmap(source, max(targetWidth, 1), targetHeight, true)
    return boxBlur(scaled, BarGlassSpec.BACKDROP_BLUR_RADIUS)
}

/**
 * 可分离盒式模糊（LunaBeat `blurMiniPlayerBitmapFast`）。
 *
 * 两趟滑动窗口：先横向写进 `horizontal`，再纵向写进结果。
 * 每行/列先用 `-r..r` 建窗口，此后每步「进一个、出一个」，所以每个像素只做 2 次加减。
 */
private fun boxBlur(source: Bitmap, radius: Int): Bitmap {
    val width = source.width
    val height = source.height
    if (width <= 1 || height <= 1) return source
    val r = radius.coerceIn(1, 25)
    val span = r * 2 + 1

    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    val horizontal = IntArray(width * height)
    val result = IntArray(width * height)

    for (y in 0 until height) {
        val row = y * width
        var alpha = 0
        var red = 0
        var green = 0
        var blue = 0
        for (dx in -r..r) {
            val px = pixels[row + dx.coerceIn(0, width - 1)]
            alpha += (px ushr 24) and 0xFF
            red += (px ushr 16) and 0xFF
            green += (px ushr 8) and 0xFF
            blue += px and 0xFF
        }
        for (x in 0 until width) {
            horizontal[row + x] = ((alpha / span) shl 24) or
                ((red / span) shl 16) or
                ((green / span) shl 8) or
                (blue / span)
            val leaving = pixels[row + (x - r).coerceIn(0, width - 1)]
            val entering = pixels[row + (x + r + 1).coerceIn(0, width - 1)]
            alpha += ((entering ushr 24) and 0xFF) - ((leaving ushr 24) and 0xFF)
            red += ((entering ushr 16) and 0xFF) - ((leaving ushr 16) and 0xFF)
            green += ((entering ushr 8) and 0xFF) - ((leaving ushr 8) and 0xFF)
            blue += (entering and 0xFF) - (leaving and 0xFF)
        }
    }

    for (x in 0 until width) {
        var alpha = 0
        var red = 0
        var green = 0
        var blue = 0
        for (dy in -r..r) {
            val px = horizontal[dy.coerceIn(0, height - 1) * width + x]
            alpha += (px ushr 24) and 0xFF
            red += (px ushr 16) and 0xFF
            green += (px ushr 8) and 0xFF
            blue += px and 0xFF
        }
        for (y in 0 until height) {
            result[y * width + x] = ((alpha / span) shl 24) or
                ((red / span) shl 16) or
                ((green / span) shl 8) or
                (blue / span)
            val leaving = horizontal[(y - r).coerceIn(0, height - 1) * width + x]
            val entering = horizontal[(y + r + 1).coerceIn(0, height - 1) * width + x]
            alpha += ((entering ushr 24) and 0xFF) - ((leaving ushr 24) and 0xFF)
            red += ((entering ushr 16) and 0xFF) - ((leaving ushr 16) and 0xFF)
            green += ((entering ushr 8) and 0xFF) - ((leaving ushr 8) and 0xFF)
            blue += (entering and 0xFF) - (leaving and 0xFF)
        }
    }

    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    out.setPixels(result, 0, width, 0, 0, width, height)
    return out
}

// ================================================================================================
// 玻璃裁剪
// ================================================================================================

/**
 * 玻璃形状裁剪。
 *
 * 这里原本挂的是 LunaBeat 的 `Refraction` AGSL：整层内容过一次 RuntimeShader，
 * 只在距边 [height] 以内把采样坐标向内偏移，做出「玻璃杯壁」的挤压感。
 * **已全局移除** —— 它要求把整层先渲进离屏纹理再跑一遍着色器，单层就是 1–2 ms/帧，
 * 而收益只有边缘 24dp 内的一点点变形。保留裁剪，观感退化为「盒式模糊封面 + 半透明遮罩」。
 *
 * @param shape 玻璃形状。
 * @param height 历史参数，已不再使用。
 * @param amount 历史参数，已不再使用。
 */
@Composable
@Suppress("UNUSED_PARAMETER")
internal fun Modifier.refractionGlass(
    shape: Shape,
    height: Dp,
    amount: Dp,
): Modifier = this.graphicsLayer {
    this.shape = shape
    clip = true
}
