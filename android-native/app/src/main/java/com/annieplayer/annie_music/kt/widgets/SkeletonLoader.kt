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
 * 双列骨架屏（1:1 移植 Flutter `lib/widgets/skeleton_loader.dart`）。
 *
 * 脉冲节奏对齐 Flutter：**1000ms 往返、easeInOut**，透明度在 **0.3 ↔ 0.7** 之间。
 * 左右两列之间有一条 1dp 分隔线（上下各留 8dp），左右内容各占一半。
 *
 * Compose 侧说明：Flutter 用 `CrossAxisAlignment.stretch` 让分隔线自动撑满行高，
 * 这里用 `Modifier.height(IntrinsicSize.Min)` 达到同等效果（真正的自适应，不写死高度）。
 */
@Composable
fun SkeletonLoader(modifier: Modifier = Modifier) {
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
        Box(modifier = Modifier.weight(1f)) { SkeletonColumn(opacity = opacity) }
        // 分隔线：撑满行高（对应 Flutter 的 CrossAxisAlignment.stretch）
        Box(
            modifier = Modifier
                .padding(vertical = 8.dp)
                .width(1.dp)
                .fillMaxHeight()
                .background(DividerColor),
        )
        Box(modifier = Modifier.weight(1f)) { SkeletonColumn(opacity = opacity) }
    }
}

/** 单列骨架：标题条 + 4 行（封面块 + 两行文字 + 尾部圆点） */
@Composable
private fun SkeletonColumn(opacity: Float) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        // 标题条
        Box(modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)) {
            SkeletonBlock(fixedWidth = 60.dp, height = 14.dp, opacity = opacity)
        }
        repeat(4) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkeletonBlock(fixedWidth = 36.dp, height = 36.dp, radius = 6.dp, opacity = opacity)
                Spacer(modifier = Modifier.width(8.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.Start,
                ) {
                    SkeletonBlock(widthFactor = 0.8f, height = 12.dp, opacity = opacity)
                    SkeletonBlock(widthFactor = 0.5f, height = 10.dp, opacity = opacity)
                }
                Spacer(modifier = Modifier.width(8.dp))
                SkeletonBlock(fixedWidth = 16.dp, height = 16.dp, opacity = opacity)
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
