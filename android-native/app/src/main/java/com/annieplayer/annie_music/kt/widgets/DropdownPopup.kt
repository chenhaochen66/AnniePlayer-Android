package com.annieplayer.annie_music.kt.widgets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.annieplayer.annie_music.kt.ui.theme.AppBorder
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import kotlinx.coroutines.delay

/** 关闭动画时长（比进场略短，关闭不拖沓）。 */
private const val DropdownExitMillis = 130

/**
 * **锚定浮层**：给任意触发器挂一个不占布局的下拉面板。
 *
 * ## 三个必须踩对的点（都踩过）
 *
 * ### 1. 不能 `focusable = true`
 *
 * `PopupProperties(focusable = true)` 会让 Popup 独占输入焦点 —— 弹出来的那一刻，
 * **整个页面的手势都被这个窗口接住**，用户滚不动列表、点不动别处。
 * 这就是「弹出之后不能滑动页面」的直接原因。
 *
 * 改成 `focusable = false`：Popup 的窗口只覆盖面板本身（wrap-content），
 * 面板以外的区域仍然属于下面的页面，滚动照常。
 * 代价是「点外部关闭」收不到了 —— 所以关闭走：点选项 / 再点一次触发行 / 返回键。
 *
 * ### 2. 进场动画不能靠 `AnimatedVisibility(visible = expanded)`
 *
 * 首次组合时 `expanded` 已经是 `true`，对 `AnimatedVisibility` 来说那是
 * "初始即可见"，**不会播放 enter 动画** —— 表现为"啪一下出现"，
 * 和 `ModeDropdown` 那种从左上角长出来的效果完全不是一回事。
 *
 * 正确做法是 [MutableTransitionState]：初始 `currentState = false`，
 * 挂载后再把 `targetState` 推向 `true`，这样才是真正"从无到有"的一次过渡。
 *
 * ### 3. 关闭动画期间必须保持挂载
 *
 * `expanded = false` 时立刻卸载 Popup，退场动画会被截断。所以多挂
 * [DropdownExitMillis] 毫秒再卸。
 *
 * @param anchorHeightPx 触发器高度（px）：面板从它的下沿开始
 * @param panelWidth 面板宽度。设置页用行宽（选项文字长）、搜索页用固定 140dp
 * @param gap 与触发器下沿的间距。**默认 0** —— 面板要"贴着"触发框，
 *   中间留一道缝会显得像两个不相干的东西
 * @param content 面板内容，已带好底色、圆角、阴影、顶线与内边距
 */
@Composable
internal fun DropdownPopup(
    expanded: Boolean,
    onDismiss: () -> Unit,
    anchorHeightPx: Int,
    panelWidth: Dp,
    gap: Dp = 0.dp,
    /** 面板相对触发器**左边缘**的横向偏移（px）。默认 0 = 左对齐触发器 */
    anchorStartPx: Int = 0,
    content: @Composable ColumnScope.() -> Unit,
) {
    var mounted by remember { mutableStateOf(expanded) }
    LaunchedEffect(expanded) {
        if (expanded) {
            mounted = true
        } else {
            delay(DropdownExitMillis.toLong())
            mounted = false
        }
    }
    if (!mounted) return

    // 真正的进场：先承认"不可见"，下一帧再推向可见 —— 否则没有动画
    val visibility = remember { MutableTransitionState(false) }
    LaunchedEffect(expanded) { visibility.targetState = expanded }

    val gapPx = with(LocalDensity.current) { gap.roundToPx() }
    // 返回键**不在这里接**：调用方（设置页）在页面级统一处理"有下拉就先收下拉"。
    // 这里再注册一个会和页面那个抢派发顺序，两套逻辑谁先收不好推理 —— 只留一处。
    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(anchorStartPx, anchorHeightPx + gapPx),
        // **必须是 false**：true 会把整个页面的手势都吃掉（见文档第 1 条）
        properties = PopupProperties(focusable = false),
        // `focusable = false` 时窗口本不该关闭自己，但实测它在面板出现后仍会回调一次
        // `onDismissRequest` —— 调方（`DropdownController.dismiss`）于是把刚打开的状态
        // 又清成 null，表现就是"每次点都是重新打开、永远收不回去"。
        // 关闭只走：点选项 / 再点一次触发行 / 返回键，这里不接。
        onDismissRequest = { /* 故意为空 */ },
    ) {
        AnimatedVisibility(
            visibleState = visibility,
            enter = fadeIn(
                tween(durationMillis = AppDuration.sheet, easing = AppEasing.easeInOutQuad),
            ) + slideInVertically(
                // **下拉感**：从触发器下沿"滑出来"，起点在面板上方约 40% 高度处。
                // 之前用 scaleIn 是"长出来"，视觉上是原地放大，没有"往下拉"的方向感。
                initialOffsetY = { height -> -height * 4 / 10 },
                animationSpec = tween(
                    durationMillis = AppDuration.sheet,
                    easing = AppEasing.easeInOutQuad,
                ),
            ) + scaleIn(
                initialScale = 0.97f,
                transformOrigin = TransformOrigin(0.5f, 0f),
                animationSpec = tween(
                    durationMillis = AppDuration.sheet,
                    easing = AppEasing.easeInOutQuad,
                ),
            ),
            exit = fadeOut(
                tween(durationMillis = DropdownExitMillis, easing = AppEasing.easeInOutQuad),
            ) + slideOutVertically(
                targetOffsetY = { height -> -height * 3 / 10 },
                animationSpec = tween(
                    durationMillis = DropdownExitMillis,
                    easing = AppEasing.easeInOutQuad,
                ),
            ) + scaleOut(
                targetScale = 0.98f,
                transformOrigin = TransformOrigin(0.5f, 0f),
                animationSpec = tween(
                    durationMillis = DropdownExitMillis,
                    easing = AppEasing.easeInOutQuad,
                ),
            ),
        ) {
            DropdownPanel(panelWidth) { content() }
        }
    }
}

/**
 * 面板外观：**必须自己画实心底**，不能只靠 `Surface(color = …)`。
 *
 * 用 `Surface` 时实测出现过「面板半透明、下面的行透上来」—— Popup 的窗口是独立的一层，
 * 底色一旦没铺满就会透出下面的内容。这里改成 `background(shape)` 显式铺满，
 * 再加一圈描边和顶部渐变线，层次才立得住。
 *
 * - 底色 `modalPanel`（0xFF1E1E3A，完全不透明），比页面的 `0A0A1A` 亮两档；
 * - 描边 `white @0.15`：没有它，面板和背景只差一点点亮度，看着还是"糊"；
 * - 顶部 1.5dp 主色渐变线：项目里模态的统一记号（`FollowSourceNoticeDialog` 同款）。
 *
 * 投影不做：Popup 的窗口紧贴内容，`shadow` 会被窗口边界裁掉，加了也看不见，
 * 反而要多留一圈 padding 把定位搞乱。层次靠"底色 + 描边"就够了。
 */
@Composable
private fun DropdownPanel(panelWidth: Dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(AppRadius.r16)
    Column(
        modifier = Modifier
            .width(panelWidth)
            .background(AppColors.modalPanel, shape)
            .border(width = AppBorder.thin, color = AppColors.whiteA15, shape = shape),
    ) {
        // 顶线：从主色淡出到透明，只做一层"有光"的提示，不抢内容
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.5.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            AppColors.primary.copy(alpha = 0.55f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        Column(
            modifier = Modifier.padding(
                vertical = AppSpacing.sp6,
                horizontal = AppSpacing.sp6,
            ),
        ) {
            content()
        }
    }
}
