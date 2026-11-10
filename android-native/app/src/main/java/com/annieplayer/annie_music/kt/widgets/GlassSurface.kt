package com.annieplayer.annie_music.kt.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppRadius

/**
 * 液态玻璃容器（详情页 / 浮层共用）。
 *
 * ## 为什么不能真模糊（关键设计约束）
 * Compose 没有「模糊节点背后的内容」的能力：`Modifier.blur` 只会把**节点自身的内容**
 * 糊掉（且 API < 31 是 no-op），无法像 CSS `backdrop-filter` 一样对下层做背景模糊。
 * 因此这里用**层叠伪造**毛玻璃（与 `AppBottomBar` / 迷你播放器同一套路，见 skill
 * `android-compose-ui` §5）：
 * 1. `clip(shape)` + 半透明底色 [AppColors.glassPanel]（下层封面取色透过玻璃可见 = 「透」）；
 * 2. 顶部白色高光垂直渐变 [AppColors.glass] → 透明（模拟玻璃受光面 = 「毛」）；
 * 3. （已按用户要求移除）原 1dp 高光描边 —— 在真机上仍会被看成一圈「框」；
 * 4. （已全局移除）原第 4 层是带偏移的 `dropShadow` 投影。
 *
 * 文字 / 图标始终清晰，不依赖 RenderEffect，API < 31 不退化。
 *
 * ## 投影为什么不画了
 * `dropShadow` 是 `RenderEffect`：每次都要把内容渲进离屏目标、模糊、再 resolve 回来，
 * 单层就能吃掉 1–2 ms/帧（骁龙 680 上实测）。它只负责轮廓外一圈很淡的暗边，
 * 而层次本来靠「极淡底色 + 顶部受光边」就分得开 —— 这笔账在续航面前不划算。
 *
 * @param shape 玻璃形状（默认卡片圆角；pill 传 `RoundedCornerShape(AppRadius.pill)`）。
 * @param tint 半透明底色（默认 [AppColors.glassPanel] = 白 5%，保证下层取色透得出来）。
 * @param elevated 历史参数，**已不再影响绘制**（投影已全局移除）；保留只为不动调用点。
 */
@Suppress("UNUSED_PARAMETER")
fun Modifier.glassPanel(
    shape: Shape,
    tint: Color = AppColors.glassPanel,
    elevated: Boolean = true,
): Modifier {
    return this
        .clip(shape)
        // 半透明底：下层内容透过玻璃可见（毛玻璃的「透」）
        .background(tint)
        // 玻璃「受光边」：只占顶部约 12% 高。
        // 原实现是「13% 白 → 透明」铺满整个高度，面板一高就变成一整块发亮的板（真机反馈），
        // 现在收到顶部一小段，只留一道受光边。
        .background(
            Brush.verticalGradient(
                0f to AppColors.glass,
                0.12f to Color.Transparent,
            ),
        )
        // 边框已按用户要求移除：详情页的玻璃只靠「极淡底色 + 顶部受光边 + 模糊取色背景」表达，
        // 不再有描边（原 1px 白描边在真机上仍能被看成一圈「框」）。
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
 * @param tint 半透明底色，默认 [AppColors.glassPanel]。
 * @param elevated 是否带悬浮投影，默认 true。
 * @param content 面板内容（[BoxScope]，可用 `align` 摆放子元素）。
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(AppRadius.card),
    tint: Color = AppColors.glassPanel,
    elevated: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.glassPanel(shape = shape, tint = tint, elevated = elevated),
        content = content,
    )
}
