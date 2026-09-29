package com.annieplayer.annie_music.kt.widgets

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing

/**
 * 全屏播放页浮层（1:1 移植 Flutter `lib/widgets/player_overlay.dart`）。
 *
 * - 从底部滑入 / 滑出：**200ms + easeOutCubic**（[AppDuration.overlay] + [AppEasing.easeOutCubic]，
 *   对齐 Dart `AnimationController(200ms)` + `Curves.easeOutCubic`）。
 * - **常驻组件树**：隐藏时不销毁（状态保留），整体位移出屏；隐藏时不再参与命中测试
 *   （等价 Flutter `IgnorePointer(ignoring: !visible)`，Compose 侧沿用 `AppSheet` 已验证的
 *   「面板位移出屏 + 遮罩 clickable 门控」做法）。
 * - 返回手势优先级：Dart 在 `PopScope` 里手写「先关队列 / 音质 / ⋮ 面板」。Compose 侧
 *   无需手写：本浮层位于根 Stack 下游（`Overlay.RENDER_ORDER` 中 `PLAYER_OVERLAY`
 *   早于 `SONG_ACTIONS_SHEET` / `QUEUE_SHEET` 等），后声明的浮层后注册 `BackHandler`，
 *   因此只有当它们都不可见（其 `BackHandler` 均禁用）时本浮层才会收到返回事件，
 *   天然满足 songActions/artistPicker → qualityPicker → queue → playerOverlay 的优先级。
 *
 * @param onClose 宿主置 `playerOverlayVisible = false`。
 * @param content 播放页内容（对齐 Dart 的 `PlayerScreen(onClose = ...)`）。
 */
@Composable
fun PlayerOverlay(
    visible: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BackHandler(enabled = visible) { onClose() }

    val slide by animateFloatAsState(
        targetValue = if (visible) 0f else 1f,
        animationSpec = tween(durationMillis = AppDuration.overlay, easing = AppEasing.easeOutCubic),
        label = "playerOverlaySlide",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            // 浮层现在铺满整个窗口（宿主不再统一内缩）→ 按自身高度下滑即可完全移出屏幕。
            .graphicsLayer { translationY = slide * size.height }
            // 视觉覆盖必须同时消费触摸：否则 Compose 会把空白区域的点击穿透到下层首页控件
            // （典型症状：播放页右下角点一下，会误触首页看不见的 ⇄「搜索选择」按钮）。
            // 子组件优先命中，故不影响播放页自身交互。
            .then(if (visible) Modifier.clickableNoRipple {} else Modifier),
    ) {
        content()
    }
}
