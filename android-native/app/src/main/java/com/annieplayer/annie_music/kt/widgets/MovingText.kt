package com.annieplayer.annie_music.kt.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

/** 跑马灯位移的刷新间隔：33ms（30fps）。文字速度只有 40px/s，30fps 看不出台阶。 */
private const val MarqueeFrameMs = 33L

/**
 * 用定时器推进一次线性平移：从 [from] 走到 [to] 用 [moveMs]，每隔 [MarqueeFrameMs]
 * 把插值交给 [setOffset]。
 *
 * **不用帧时钟**：`withFrameNanos` 每帧都会唤醒，而 Compose 的任一 state 变化都会把
 * 整个 AndroidComposeView 标脏 —— 一条滚动标题就能让整窗持续满帧重绘，且切到桌面后
 * 照跑（动画不感知可见性）。骁龙 680 上实测：暂停 + 退到桌面后仍有 44 fps。
 */
private suspend fun slideFrames(
    moveMs: Float,
    from: Float,
    to: Float,
    setOffset: (Float) -> Unit,
) {
    val startNs = System.nanoTime()
    while (true) {
        val f = (System.nanoTime() - startNs) / 1_000_000f / moveMs
        if (f >= 1f) break
        setOffset(from + (to - from) * f)
        delay(MarqueeFrameMs)
    }
    setOffset(to)
}

/**
 * 滚动标题（1:1 移植 Flutter `lib/widgets/moving_text.dart`）。
 *
 * 行为对齐点：
 * - 超过 [threshold] 个字符**或**实际渲染宽度溢出时启用滚动，否则静态省略显示。
 * - 循环节奏严格对齐 Flutter 的 `TweenSequence`：**停留 → 线性平移 → 停留**；
 *   hold 恒为 1200ms，move = `max(600ms, 距离/speed*1000)`，总时长 = hold+move+hold。
 *   末尾画面与起始一致（位移停在 -distance），因此循环无跳变。
 * - 滚动距离 = 文本宽度 + [gap]；滚动时并排两份文本并在中间留 [gap]。
 * - [speed] 单位：像素/秒。
 */
@Composable
fun MovingText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    threshold: Int = 15,
    gap: Float = 60f,
    speed: Float = 40f,
    pingPong: Boolean = false,
) {
    if (text.isEmpty()) return

    // 往返模式：单份文本、滚到尾部再原路滚回（无「尾部+头部同框」接缝），见 [PingPongText]
    if (pingPong) {
        PingPongText(text = text, modifier = modifier, style = style, speed = speed)
        return
    }

    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()

    // 与 Flutter 相同的度量方式（单行、不换行）
    val measured = remember(text, style) { measurer.measure(text = text, style = style, maxLines = 1) }
    val textWidthPx = measured.size.width
    val textHeightPx = measured.size.height

    var containerWidthPx by remember { mutableIntStateOf(0) }
    val overflows = containerWidthPx > 0 && textWidthPx > containerWidthPx
    val scrolling = containerWidthPx > 0 && (text.length > threshold || overflows)

    // ── 不滚动：静态省略 ──
    if (!scrolling) {
        Box(modifier = modifier.onSizeChanged { containerWidthPx = it.width }) {
            Text(
                text = text,
                style = style,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
            )
        }
        return
    }

    // ── 滚动：三段相位 ──
    val distance = textWidthPx.toFloat() + gap
    val holdMs = 1200f
    val moveMs = max(600, (distance / speed * 1000f).roundToInt()).toFloat()

    // 位移只被 `graphicsLayer` 的 block 读取（绘制阶段），组合期一次都不读 ——
    // 否则每帧重组整个节点，且整屏（极光那十几层全屏混合）会跟着重画一遍。
    val offsetPx = remember(distance, moveMs) { mutableFloatStateOf(0f) }
    LaunchedEffect(text, distance, moveMs) {
        val setOffset: (Float) -> Unit = { v ->
            if (offsetPx.floatValue != v) offsetPx.floatValue = v
        }
        while (true) {
            setOffset(0f)
            // 两段 hold 用 delay 而不是帧时钟：静止期间不产出任何一帧
            delay(holdMs.toLong())
            slideFrames(moveMs, 0f, -distance, setOffset)
            delay(holdMs.toLong())
        }
    }

    val gapDp = with(density) { gap.toDp() }
    val heightDp = with(density) { textHeightPx.toDp() }

    Box(
        modifier = modifier
            .onSizeChanged { containerWidthPx = it.width }
            .clipToBounds()
            .height(heightDp),
    ) {
        Layout(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = offsetPx.floatValue },
            content = {
                Text(text = text, style = style, maxLines = 1, softWrap = false)
                Box(modifier = Modifier.width(gapDp))
                Text(text = text, style = style, maxLines = 1, softWrap = false)
            },
        ) { measurables, _ ->
            val placeables = measurables.map { it.measure(androidx.compose.ui.unit.Constraints()) }
            val width = placeables.sumOf { it.width }
            val height = placeables.maxOfOrNull { it.height } ?: 0
            layout(width = width, height = height) {
                var x = 0
                placeables.forEach { p ->
                    p.placeRelative(x, 0)
                    x += p.width
                }
            }
        }
    }
}

/**
 * 往返跑马灯（[MovingText] 的 `pingPong` 模式）：单份文本，滚到「尾部露出窗口」后**原路滚回**，
 * 因此不会出现「上一份尾部 + 下一份头部」同框的接缝。
 *
 * 关键：文字宽度取**实际测量出来的 `Text` 宽度**（`wrapContentWidth(unbounded)` + `onSizeChanged`），
 * 不用 `TextMeasurer` —— 后者量出的宽度比实际窄，会让滚动距离偏短、尾部永远露不出来。
 * 窗口宽度由 [BoxWithConstraints] 在 composition 阶段直接取得，两者同坐标系、精确且稳定。
 */
@Composable
private fun PingPongText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    speed: Float = 40f,
) {
    var textPx by remember(text) { mutableIntStateOf(0) }
    var windowPx by remember { mutableIntStateOf(0) }
    val distancePx = (textPx - windowPx).coerceAtLeast(0)

    val holdMs = 1500f
    val moveMs = max(600f, distancePx / speed * 1000f)

    // 与 [MovingText] 同一条原则：位移只在绘制阶段读，两段 hold 用 delay 不产帧。
    val offsetPx = remember(distancePx, moveMs) { mutableFloatStateOf(0f) }
    LaunchedEffect(text, distancePx, moveMs) {
        if (distancePx <= 0) {
            offsetPx.floatValue = 0f
            return@LaunchedEffect
        }
        val setOffset: (Float) -> Unit = { v ->
            if (offsetPx.floatValue != v) offsetPx.floatValue = v
        }
        val back = -distancePx.toFloat()
        while (true) {
            setOffset(0f)
            delay(holdMs.toLong())
            slideFrames(moveMs, 0f, back, setOffset)
            delay(holdMs.toLong())
            slideFrames(moveMs, back, 0f, setOffset)
        }
    }

    BoxWithConstraints(modifier = modifier) {
        Box(
            modifier = Modifier
                .width(maxWidth)
                .onSizeChanged { if (windowPx != it.width) windowPx = it.width }
                .clipToBounds(),
        ) {
            Text(
                text = text,
                style = style,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .wrapContentWidth(unbounded = true, align = Alignment.Start)
                    .onSizeChanged { if (textPx != it.width) textPx = it.width }
                    .graphicsLayer { translationX = offsetPx.floatValue },
            )
        }
    }
}

/**
 * 暂停图标：两根圆角竖条。
 *
 * 自绘原因：`Icons.Default.Pause` 属于 `material-icons-extended`，为单个图标引入
 * 整包扩展集不划算（`Cancel` / `MusicNote` 同理，见 `SearchBar.kt` / `MiniPlayer.kt`）。
 */
@Composable
fun PauseIcon(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier) {
        val barW = size.width * 0.28f
        val gapW = size.width * 0.20f
        val startX = (size.width - (barW * 2 + gapW)) / 2f
        val corner = androidx.compose.ui.geometry.CornerRadius(barW * 0.15f)
        drawRoundRect(
            color = tint,
            topLeft = androidx.compose.ui.geometry.Offset(startX, size.height * 0.1f),
            size = androidx.compose.ui.geometry.Size(barW, size.height * 0.8f),
            cornerRadius = corner,
        )
        drawRoundRect(
            color = tint,
            topLeft = androidx.compose.ui.geometry.Offset(startX + barW + gapW, size.height * 0.1f),
            size = androidx.compose.ui.geometry.Size(barW, size.height * 0.8f),
            cornerRadius = corner,
        )
    }
}

/**
 * 播放图标：实心**加粗**三角，与 [PauseIcon] 的笔画重量统一。
 *
 * 自绘原因与 [PauseIcon] 相同：`Icons.Default.PlayArrow` 属于 `material-icons-extended`，
 * 且其线框偏细，和暂停条的视觉重量不一致（用户反馈「播放/暂停样式不统一」）。
 */
@Composable
fun PlayIcon(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        // 三角重心偏左，整体右移一点以获得视觉居中
        val path = Path().apply {
            moveTo(w * 0.28f, h * 0.13f)
            lineTo(w * 0.87f, h * 0.50f)
            lineTo(w * 0.28f, h * 0.87f)
            close()
        }
        drawPath(path = path, color = tint)
    }
}
