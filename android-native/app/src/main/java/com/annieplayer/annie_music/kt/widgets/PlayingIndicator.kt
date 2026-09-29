package com.annieplayer.annie_music.kt.widgets

import android.provider.Settings
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSize

// ================================================================================================
// 详情页共享：正在播放动效 + 正在播放行背景
// ================================================================================================

/** 竖条数量（等化器固定三条）。 */
private const val PlayingBarCount = 3

/** 竖条最小比例（高度 6dp / 基准 16dp）。 */
private val PlayingBarMinScale = AppSize.playingBarMinHeight / AppSize.playingBarMaxHeight

/**
 * 系统动画关闭时的静止比例：取伸缩区间中点（(6 + 16) / 2 = 11dp）。
 *
 * 用户把系统动画缩放设为 0（「关闭动画」）时，指示器必须静止在中间态而不是停在极限位。
 */
private val PlayingBarStillScale =
    (AppSize.playingBarMinHeight + AppSize.playingBarMaxHeight) / 2 / AppSize.playingBarMaxHeight

/** 「正在播放」行底色的主色比例（语义对齐 [AppColors.primaryA08] 的 0.078）。 */
private const val PlayingRowTintAlpha = 0.08f

/**
 * 「正在播放」等化器指示器（详情页 / 队列行共用）。
 *
 * - 三条 2.5dp 宽圆角竖条，以 `graphicsLayer.scaleY` 在 6dp ⇄ 16dp 之间伸缩；
 *   **只做 transform 缩放，不动布局尺寸**（避免每帧 relayout）。
 * - 三条竖条共用 [AppDuration.playingBarPulse] 周期、依次延迟 1/3 周期 → 错相位「呼吸」。
 * - 系统动画缩放为 0 时静止在中间态（见 [rememberSystemAnimationsEnabled]）。
 * - 纯装饰元素：不产生无障碍语义节点（语义由所在行的文本 / 点击承载）。
 *
 * @param tint 竖条颜色（通常传 `Annie.palette.primary`）。
 */
@Composable
fun PlayingIndicator(tint: Color, modifier: Modifier = Modifier) {
    val animationsEnabled = rememberSystemAnimationsEnabled()

    val barScales: List<Float> = if (animationsEnabled) {
        val transition = rememberInfiniteTransition(label = "playingIndicator")
        listOf(
            transition.playingBarScale(delayMillis = 0, label = "playingBar0"),
            transition.playingBarScale(
                delayMillis = AppDuration.playingBarPulse / PlayingBarCount,
                label = "playingBar1",
            ),
            transition.playingBarScale(
                delayMillis = AppDuration.playingBarPulse * 2 / PlayingBarCount,
                label = "playingBar2",
            ),
        )
    } else {
        List(PlayingBarCount) { PlayingBarStillScale }
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(AppSize.playingBarGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        barScales.forEach { barScale ->
            Box(
                modifier = Modifier
                    .width(AppSize.playingBarWidth)
                    .height(AppSize.playingBarMaxHeight)
                    .graphicsLayer {
                        scaleY = barScale
                        // 以底边为基准伸缩，三条竖条像立在同一条基线上
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .background(tint, RoundedCornerShape(percent = 50)),
            )
        }
    }
}

/** 单条竖条的无限往复伸缩（最短 ⇄ 最长，Reverse 乒乓）。 */
@Composable
private fun InfiniteTransition.playingBarScale(delayMillis: Int, label: String): Float {
    val scale by animateFloat(
        initialValue = PlayingBarMinScale,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = AppDuration.playingBarPulse,
                delayMillis = delayMillis,
                easing = AppEasing.easeInOut,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = label,
    )
    return scale
}

/**
 * 「正在播放」行背景：`primary@8%` 圆角底 + 左侧 3dp 主色圆角条。
 *
 * 用 `drawBehind` 在行节点自身上绘制（**不用** `Modifier.matchParentSize()`：它会给子节点
 * 固定约束、被父布局居中 —— 见 skill `android-compose-ui` §7；也不用 LazyColumn item 里的
 * `fillMaxHeight()`，其主轴无界会塌成 0）。
 *
 * @param tint 主色（通常传 `Annie.palette.primary`）。
 */
@Composable
fun Modifier.playingRowBackground(tint: Color): Modifier = this.drawBehind {
    val cornerPx = AppRadius.r8.toPx()
    // primary @8% 圆角底
    drawRoundRect(
        color = tint.copy(alpha = PlayingRowTintAlpha),
        cornerRadius = CornerRadius(cornerPx, cornerPx),
    )
    // 左侧 3dp 主色圆角条（全高、两端圆头）
    val barWidthPx = AppSize.playingRowBarWidth.toPx()
    drawRoundRect(
        color = tint,
        topLeft = Offset.Zero,
        size = Size(barWidthPx, size.height),
        cornerRadius = CornerRadius(barWidthPx / 2f, barWidthPx / 2f),
    )
}

/**
 * 系统动画缩放是否开启（`Settings.Global.ANIMATOR_DURATION_SCALE > 0`）。
 *
 * 部分 ROM / 开发者选项 / 无障碍设置会把全局动画缩放置 0（关闭所有动画）。
 * 此时 [PlayingIndicator] 若继续跑 `rememberInfiniteTransition` 就会不停闪烁，
 * 与用户「关闭动画」的预期相悖，故在此加私有 helper 做降级判断（项目内此前没有同类判断）。
 */
@Composable
private fun rememberSystemAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) > 0f
    }
}
