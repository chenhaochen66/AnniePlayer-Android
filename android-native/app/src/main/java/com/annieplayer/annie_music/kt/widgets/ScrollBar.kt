package com.annieplayer.annie_music.kt.widgets

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import kotlinx.coroutines.delay

/** 滑条厚度（对标设计稿：屏宽 411dp 上约 5dp）。 */
private val ScrollBarThickness = 5.dp

/** 滑条距内容右边缘的留白。 */
private val ScrollBarEndInset = 2.dp

/** 滑条最短长度（内容极长时也不至于细成一根线）。 */
private val ScrollBarMinLength = 32.dp

/**
 * 滑条最长占内容区高度的比例。
 *
 * 需求「滑条可以短一点」：不画满整条内容高度、也不按真实比例拉长，**封顶 40%**，
 * 与设计稿一致（截图里的滑条约占内容区 1/3）。
 */
private const val ScrollBarMaxFraction = 0.40f

/** 滚动中的不透明度（设计稿：白 ≈ 45%）。 */
private const val ScrollBarAlphaActive = 0.52f

/** 停手后多久开始淡出（太短会闪，太长又像没消失）。 */
private const val ScrollBarHideDelayMillis = 600L

/** 淡入 / 淡出时长（要看得见，200ms 在长列表上偏「硬切」）。 */
private const val ScrollBarFadeMillis = 280

// ================================================================================================
// 右侧短滑条（Android-only：Flutter / RN 基线只有 Scrollbar 默认样式，没有这条）
// ================================================================================================

/**
 * 右侧短滑条：给 `LazyColumn` 用。
 *
 * 用法（一行即可，不需要外层 Box）：
 * ```
 * LazyColumn(state = listState, modifier = Modifier.fillMaxSize().shortScrollbar(listState)) { … }
 * ```
 *
 * 实现要点：
 * - 用 `drawBehind` 画在滚动容器自身上（**不用** `VerticalScrollbar` 组件：那套还要求另配
 *   `ScrollbarAdapter` 与一个 padding 占位，且默认铺满可用高度）；位置贴右边缘、宽度固定。
 * - 滚动位置/可见比例都在 **draw 阶段**读 `layoutInfo`（Compose 的「状态读取下沉到绘制」优化），
 *   滚动时只重绘、不重组。
 * - 可见比例按「实测平均行高 × 总条目数」估算总高度得出（**不用**可见项数，
 *   那个数会随首尾项半露出在 N / N±1 间跳动，滑条会跟着抖）。
 * - 内容不足以滚动（所有条目都可见）时不画。
 * - 滚动中滑条更实一点（透明度过渡 [AppDuration.overlay]），松手后回落。
 */
@Composable
fun Modifier.shortScrollbar(state: LazyListState, tint: Color = AppColors.white): Modifier {
    val alpha = rememberScrollBarAlpha(state.isScrollInProgress)
    return this.drawBehind {
        val info = state.layoutInfo
        val items = info.visibleItemsInfo
        val total = info.totalItemsCount
        if (total <= 1 || items.isEmpty()) return@drawBehind
        val viewport = size.height
        if (viewport <= 0f) return@drawBehind
        // 平均行高（实测可见项）→ 把「条目」连续地换算成像素。
        // **不能**用 visibleItemsInfo.size 当分母：首尾项半露出时它会在 N / N±1 之间跳，
        // 分母一跳长度和位置就跟着跳（现象：拖动列表时右侧滑条一抖一抖）。
        var sumPx = 0L
        for (it in items) sumPx += it.size.toLong()
        val avgItem = (sumPx.toFloat() / items.size).coerceAtLeast(1f)
        val totalPx = avgItem * total
        if (totalPx <= viewport) return@drawBehind
        val first = items.first()
        // 首项的未显示部分（offset ≤ 0）折算成「已滚过多少项」，比只按 index 平滑
        val consumed = first.index + (-first.offset).toFloat() / first.size.coerceAtLeast(1)
        drawScrollBar(
            fraction = consumed * avgItem / (totalPx - viewport),
            visibleFraction = viewport / totalPx,
            tint = tint,
            alpha = alpha,
        )
    }
}

/** 右侧短滑条：给 `LazyVerticalGrid` 用（同样按平均行高换算，避免可见项数抖动）。 */
@Composable
fun Modifier.shortScrollbar(state: LazyGridState, tint: Color = AppColors.white): Modifier {
    val alpha = rememberScrollBarAlpha(state.isScrollInProgress)
    return this.drawBehind {
        val info = state.layoutInfo
        val items = info.visibleItemsInfo
        val total = info.totalItemsCount
        if (total <= 1 || items.isEmpty()) return@drawBehind
        val viewport = size.height
        if (viewport <= 0f) return@drawBehind
        var sumPx = 0L
        for (it in items) sumPx += it.size.height.toLong()
        val avgItem = (sumPx.toFloat() / items.size).coerceAtLeast(1f)
        val totalPx = avgItem * total
        if (totalPx <= viewport) return@drawBehind
        val first = items.first()
        val consumed =
            first.index + (-first.offset.y).toFloat() / first.size.height.coerceAtLeast(1)
        drawScrollBar(
            fraction = consumed * avgItem / (totalPx - viewport),
            visibleFraction = viewport / totalPx,
            tint = tint,
            alpha = alpha,
        )
    }
}

/** 右侧短滑条：给 `Modifier.verticalScroll(rememberScrollState())` 用。 */
@Composable
fun Modifier.shortScrollbar(state: ScrollState, tint: Color = AppColors.white): Modifier {
    val alpha = rememberScrollBarAlpha(state.isScrollInProgress)
    return this.drawBehind {
        val max = state.maxValue
        val viewport = size.height
        if (max <= 0 || viewport <= 0f) return@drawBehind
        drawScrollBar(
            fraction = state.value.toFloat() / max,
            visibleFraction = viewport / (viewport + max),
            tint = tint,
            alpha = alpha,
        )
    }
}

/**
 * 滑条透明度：**只在滚动时出现**，停手 [ScrollBarHideDelayMillis] 后淡出。
 *
 * 需求「滑条不滑动就消失 + 出现/消失加淡入淡出」：
 * - 开始滚动 → 立刻 0 → [ScrollBarAlphaActive] 淡入（[ScrollBarFadeMillis]）；
 * - 停手 → 等 [ScrollBarHideDelayMillis]（吃掉惯性滚动间隙）→ 淡出到 0；
 * - 到 0 后 [DrawScope.drawScrollBar] 直接不画。
 */
@Composable
private fun rememberScrollBarAlpha(active: Boolean): Float {
    var shown by remember { mutableStateOf(active) }
    LaunchedEffect(active) {
        if (active) {
            shown = true
        } else {
            delay(ScrollBarHideDelayMillis)
            shown = false
        }
    }
    val value by animateFloatAsState(
        targetValue = if (shown) ScrollBarAlphaActive else 0f,
        animationSpec = tween(durationMillis = ScrollBarFadeMillis, easing = AppEasing.easeInOut),
        label = "scrollBarAlpha",
    )
    return value
}

/**
 * 画一根圆头短滑条：贴右边缘、宽 [ScrollBarThickness]、长度 = 可见比例 × 高度，
 * 但封顶 [ScrollBarMaxFraction]（「短一点」）、保底 [ScrollBarMinLength]。
 */
private fun DrawScope.drawScrollBar(
    fraction: Float,
    visibleFraction: Float,
    tint: Color,
    alpha: Float,
) {
    if (alpha <= 0.01f) return
    val viewport = size.height
    if (viewport <= 0f) return
    val width = ScrollBarThickness.toPx()
    val inset = ScrollBarEndInset.toPx()
    val length = (viewport * visibleFraction.coerceIn(0f, 1f))
        .coerceAtMost(viewport * ScrollBarMaxFraction)
        .coerceAtLeast(ScrollBarMinLength.toPx().coerceAtMost(viewport))
    val travel = (viewport - length).coerceAtLeast(0f)
    drawRoundRect(
        color = tint.copy(alpha = alpha),
        topLeft = Offset(size.width - inset - width, travel * fraction.coerceIn(0f, 1f)),
        size = Size(width, length),
        cornerRadius = CornerRadius(width / 2f, width / 2f),
    )
}
