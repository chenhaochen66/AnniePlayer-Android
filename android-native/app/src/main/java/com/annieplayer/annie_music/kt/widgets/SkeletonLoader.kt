package com.annieplayer.annie_music.kt.widgets

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing

/** rgba(255,255,255,0.1)（骨架块底色） —— 对齐 Flutter `_blockColor` */
private val BlockColor = Color(0x1AFFFFFF)

/** rgba(255,255,255,0.04)（左右分隔线） —— 对齐 Flutter `_dividerColor` */
private val DividerColor = Color(0x0AFFFFFF)

/**
 * 搜索结果的骨架屏（源自 Flutter `lib/widgets/skeleton_loader.dart`）。
 *
 * 脉冲节奏对齐 Flutter：**1000ms 往返、easeInOut**，透明度在 **0.3 ↔ 0.7** 之间。
 * 列与列之间有一条 1dp 分隔线（上下各留 8dp）。
 *
 * 两种形态：
 * - 默认（[columns] = 2、[largeRows] = false）：专辑 / 歌手搜索沿用 Flutter 的双列紧凑骨架。
 * - 单曲搜索：[columns] = 1、[largeRows] = true —— 单栏大行（封面 50dp + 三行文本、上下各 20dp），
 *   与 `SongTile` 的真实几何一致，避免结果出来后行高突然跳变。
 *
 * Compose 侧说明：Flutter 用 `CrossAxisAlignment.stretch` 让分隔线自动撑满行高，
 * 这里用 `Modifier.height(IntrinsicSize.Min)` 达到同等效果（真正的自适应，不写死高度）。
 *
 * @param columns 列数（单曲搜索 = 1）
 * @param largeRows 是否画「单栏大行」尺寸的骨架
 */
@Composable
fun SkeletonLoader(
    modifier: Modifier = Modifier,
    columns: Int = 2,
    largeRows: Boolean = false,
) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val opacity by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = AppDuration.skeletonPulse, easing = AppEasing.easeInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeletonOpacity",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(start = 8.dp, end = 8.dp, top = 8.dp),
    ) {
        repeat(columns.coerceAtLeast(1)) { index ->
            if (index > 0) {
                // 分隔线：撑满行高（对应 Flutter 的 CrossAxisAlignment.stretch）
                Box(
                    modifier = Modifier
                        .padding(vertical = 8.dp)
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(DividerColor),
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                SkeletonColumn(opacity = opacity, largeRows = largeRows)
            }
        }
    }
}

/** 单列骨架：标题条 + 若干行（封面块 + 文本条 + 尾部圆点） */
@Composable
private fun SkeletonColumn(opacity: Float, largeRows: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        // 标题条
        Box(modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)) {
            SkeletonBlock(fixedWidth = 60.dp, height = 14.dp, opacity = opacity)
        }
        repeat(if (largeRows) 5 else 4) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 8.dp,
                        end = 8.dp,
                        top = if (largeRows) 6.dp else 8.dp,
                        bottom = if (largeRows) 6.dp else 8.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkeletonBlock(
                    fixedWidth = if (largeRows) 44.dp else 36.dp,
                    height = if (largeRows) 44.dp else 36.dp,
                    radius = if (largeRows) 8.dp else 6.dp,
                    opacity = opacity,
                )
                Spacer(modifier = Modifier.width(if (largeRows) 14.dp else 8.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.Start,
                ) {
                    SkeletonBlock(
                        widthFactor = 0.8f,
                        height = if (largeRows) 17.dp else 12.dp,
                        opacity = opacity,
                    )
                    SkeletonBlock(
                        widthFactor = 0.5f,
                        height = if (largeRows) 13.dp else 10.dp,
                        opacity = opacity,
                    )
                    if (largeRows) {
                        SkeletonBlock(widthFactor = 0.35f, height = 11.dp, opacity = opacity)
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                SkeletonBlock(
                    fixedWidth = if (largeRows) 20.dp else 16.dp,
                    height = if (largeRows) 20.dp else 16.dp,
                    opacity = opacity,
                )
            }
        }
    }
}

/**
 * 骨架块：宽度支持「定值」或「父宽百分比」（对齐 Flutter `_SkeletonBlock` 的
 * `width: number | \`${number}%\``）。
 *
 * [widthFactor] 非空时按父宽比例（左对齐），否则用 [fixedWidth] 定值。
 */
@Composable
private fun SkeletonBlock(
    height: Dp,
    opacity: Float,
    modifier: Modifier = Modifier,
    fixedWidth: Dp? = null,
    widthFactor: Float? = null,
    radius: Dp = 8.dp,
) {
    val shape = RoundedCornerShape(radius)
    if (widthFactor != null) {
        Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(widthFactor)
                    .height(height)
                    .alpha(opacity)
                    .background(BlockColor, shape),
            )
        }
    } else {
        Box(
            modifier = modifier
                .width(fixedWidth ?: 0.dp)
                .height(height)
                .alpha(opacity)
                .background(BlockColor, shape),
        )
    }
}
