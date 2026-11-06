package com.annieplayer.annie_music.kt.widgets

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 细滑条（Android 侧 Compose 没有内置 `VerticalScrollbar`，那个是 desktop artifact）。
 *
 * 只做指示、不做拖动：拖动仍由 `verticalScroll` 处理，滑条按 `scroll.value / maxValue` 跟着走。
 * `maxValue <= 0`（内容没超过可视高度）时整条不画。
 *
 * 放在 widgets 里给多个面板共用（能力页、日志页都要）。
 */
@Composable
fun ThinScrollbar(scroll: ScrollState, modifier: Modifier = Modifier) {
    val max = scroll.maxValue
    if (max <= 0) return
    BoxWithConstraints(modifier) {
        val track = maxHeight
        val viewport = scroll.viewportSize
        val total = (max + viewport).toFloat().coerceAtLeast(1f)
        val fraction = (viewport / total).coerceIn(0.05f, 1f)
        val thumb = (track * fraction).coerceAtLeast(28.dp)
        val offset = (track - thumb) * (scroll.value.toFloat() / max).coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .offset(y = offset)
                .width(4.dp)
                .height(thumb)
                .background(Color.White.copy(alpha = 0.28f), RoundedCornerShape(2.dp)),
        )
    }
}
