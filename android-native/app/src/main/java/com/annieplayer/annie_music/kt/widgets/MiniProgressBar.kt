package com.annieplayer.annie_music.kt.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 迷你播放条进度条：左对齐按比例填充（white @ 0.25），轨道透明。
 *
 * 使用者是常驻底栏 `features/shell/AppBottomBar.kt` 的迷你态。
 *
 * > 原 `widgets/MiniPlayer.kt` 已删除：里面的 `MiniPlayerBar` / `MiniPlayerCover` /
 * > `MiniPlayPauseButton` / `MusicNotePlaceholder` / `NetworkCoverImage` 在底栏重写成
 * > 「一颗胶囊两种内容」之后全部失去调用点，只留下这一个进度条还在用。
 *
 * @param position 当前进度（秒）。
 * @param duration 总时长（秒）；≤ 0 时按 0 处理。
 * @param height 条高，默认 4dp。
 */
@Composable
fun MiniProgressBar(
    position: Double,
    duration: Double,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
) {
    val fraction = if (duration > 0) (position / duration).coerceIn(0.0, 1.0) else 0.0
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.toFloat())
                .fillMaxHeight()
                .background(Color.White.copy(alpha = 0.25f)),
        )
    }
}
