package com.annieplayer.annie_music.kt.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

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
) {
    if (text.isEmpty()) return

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
    val totalMs = holdMs + moveMs + holdMs

    var elapsedMs by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(text, distance, moveMs) {
        elapsedMs = 0f
        var lastNs = 0L
        while (true) {
            withFrameNanos { nowNs ->
                if (lastNs != 0L) {
                    elapsedMs = (elapsedMs + (nowNs - lastNs) / 1_000_000f) % totalMs
                }
                lastNs = nowNs
            }
        }
    }

    val offsetX: Float = when {
        elapsedMs < holdMs -> 0f
        elapsedMs < holdMs + moveMs -> -distance * ((elapsedMs - holdMs) / moveMs)
        else -> -distance
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
                .graphicsLayer { translationX = offsetX },
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
