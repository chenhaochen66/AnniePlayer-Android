package com.annieplayer.annie_music.kt.widgets

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing

/**
 * 两段式切换胶囊（收藏库的「流媒体 / 本地」）。
 *
 * 为什么不用 Material3 的 `SegmentedButton`：它自带 32dp 的行高与固定内边距，
 * 与本页顶栏那排 44dp 命中区对不上；这里按 `BottomBarTab` 的视觉 token 手写一份，
 * 选中态同样是 `primaryA15` 胶囊 + 纯白字，未选中透明 + `whiteA45`。
 *
 * [options] 目前固定两段，多段也能用（等分）。
 */
@Composable
fun SegmentedPill(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty()) return
    val shape = RoundedCornerShape(AppRadius.pill)
    Row(
        modifier = modifier
            .clip(shape)
            .background(AppColors.whiteA06)
            .padding(AppSpacing.sp3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val bg by animateColorAsState(
                targetValue = if (selected) AppColors.primaryA15 else Color.Transparent,
                animationSpec = tween(durationMillis = AppDuration.sheet),
                label = "segmentedPillBg",
            )
            val fg by animateColorAsState(
                targetValue = if (selected) AppColors.white else AppColors.whiteA45,
                animationSpec = tween(durationMillis = AppDuration.sheet),
                label = "segmentedPillFg",
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(AppRadius.pill))
                    .background(bg)
                    .clickableRipple { if (!selected) onSelect(index) }
                    .padding(horizontal = AppSpacing.sp14, vertical = AppSpacing.sp6),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = fg,
                    fontSize = AppFontSize.size13,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}
