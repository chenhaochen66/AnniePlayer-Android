package com.annieplayer.annie_music.kt.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.ui.theme.AppBorder
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppShadow

/**
 * 液态玻璃容器（详情页 / 浮层共用）。
 *
 * ## 为什么不能真模糊（关键设计约束）
 * Compose 没有「模糊节点背后的内容」的能力：`Modifier.blur` 只会把**节点自身的内容**
 * 糊掉（且 API < 31 是 no-op），无法像 CSS `backdrop-filter` 一样对下层做背景模糊。
 * 因此这里用**层叠伪造**毛玻璃（与 `AppBottomBar` / 迷你播放器同一套路，见 skill
 * `android-compose-ui` §5）：
 * 1. `clip(shape)` + 半透明底色 [AppColors.surface95]（下层的封面 / 背景透过玻璃可见 = 「透」）；
 * 2. 顶部白色高光垂直渐变 [AppColors.glass] → 透明（模拟玻璃受光面 = 「毛」）；
 * 3. 1dp 高光描边 [AppColors.glassBorder]（玻璃边缘）；
 * 4. [elevated] 时补一层带偏移的 `dropShadow`（悬浮感）。
 *
 * 文字 / 图标始终清晰，不依赖 RenderEffect，API < 31 不退化。
 *
 * ## 阴影的声明顺序（务必保持）
 * `dropShadow` 是 DrawModifierNode：它**先画阴影、再画后续链上的内容**。因此它必须声明在
 * `clip` / `background` / `border` **之前**（即链首），阴影才能垫在玻璃面板底下、只在
 * 形状外缘露出；若放到链尾，阴影会盖在底色之上、且被 `clip` 裁进形状内部（视觉上整块发脏）。
 * 这与本工程 `AppBottomBar` / `BottomBarCover` 的既有写法一致。
 *
 * @param shape 玻璃形状（默认卡片圆角；pill 传 `RoundedCornerShape(AppRadius.pill)`）。
 * @param tint 半透明底色（默认 [AppColors.surface95] = surface @95%）。
 * @param elevated 是否带悬浮投影（列表内嵌的小容器可传 false，避免层层投影糊成一片）。
 */
fun Modifier.glassPanel(
    shape: Shape,
    tint: Color = AppColors.surface95,
    elevated: Boolean = true,
): Modifier {
    var chain = this
    if (elevated) {
        chain = chain.dropShadow(
            shape = shape,
            shadow = Shadow(
                radius = AppShadow.blur2xl,
                color = AppColors.blackA40,
                offset = DpOffset(x = 0.dp, y = AppShadow.miniPlayerOffsetY),
            ),
        )
    }
    return chain
        .clip(shape)
        // 半透明底：下层内容透过玻璃可见（毛玻璃的「透」）
        .background(tint)
        // 顶部白色高光渐变：模拟玻璃受光面（毛玻璃的「毛」）
        .background(Brush.verticalGradient(listOf(AppColors.glass, Color.Transparent)))
        // 极细高光描边：玻璃边缘
        .border(AppBorder.thin, AppColors.glassBorder, shape)
}

/**
 * 液态玻璃容器组合（[glassPanel] 的盒式封装）。
 *
 * 用法：
 * ```kotlin
 * GlassSurface(shape = RoundedCornerShape(AppRadius.panel)) {
 *     Column { ... } // 玻璃面板内容
 * }
 * ```
 *
 * @param modifier 外部修饰符（尺寸 / 对齐由调用方决定）。
 * @param shape 玻璃形状，默认 [AppRadius.card] 卡片圆角。
 * @param tint 半透明底色，默认 [AppColors.surface95]。
 * @param elevated 是否带悬浮投影，默认 true。
 * @param content 面板内容（[BoxScope]，可用 `align` 摆放子元素）。
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(AppRadius.card),
    tint: Color = AppColors.surface95,
    elevated: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.glassPanel(shape = shape, tint = tint, elevated = elevated),
        content = content,
    )
}
