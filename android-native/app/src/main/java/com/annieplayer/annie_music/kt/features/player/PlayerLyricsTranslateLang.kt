package com.annieplayer.annie_music.kt.features.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.annieplayer.annie_music.kt.core.util.lyricLangLabel
import com.annieplayer.annie_music.kt.ui.theme.AppBorder
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.clickableNoRipple
import com.annieplayer.annie_music.kt.widgets.clickableRipple
import kotlinx.coroutines.delay

/** 箭头按钮尺寸（比 译 圆形小一号） */
private val PlayerTranslateLangArrowSize = 24.dp

/** 触发器下沿到面板的间距 */
private val PlayerTranslateLangPanelGap = 4.dp

/** 面板退场时长（与搜索模式下拉一致：关闭不拖沓） */
private const val PlayerTranslateLangExitMillis = 120

/** 选项错峰进场步进 */
private const val PlayerTranslateLangStaggerMillis = 25

/**
 * 译文语言下拉（交互对齐搜索模式下拉 [com.annieplayer.annie_music.kt.widgets.ModeDropdown]）。
 *
 * 触发器只有一个自绘小箭头（复用搜索模式下拉的 chevron 线条家族），点击后**向下**弹出面板，
 * 逐项列出**实际能取到译文**的语言（即接口给的 `translation_langs`，不列全量语言），
 * 中文标注、当前项高亮 + 主色勾选。
 */
@Composable
fun PlayerTranslateLangDropdown(
    langs: List<String>,
    current: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    // 退场动画期间保持 Popup 挂载，否则 open=false 会立刻卸载、关闭动画被截断
    var mounted by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (open) {
            mounted = true
        } else {
            delay(PlayerTranslateLangExitMillis.toLong())
            mounted = false
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box {
            PlayerTranslateLangTrigger(open = open, onToggle = { open = !open })
            if (mounted) {
                val topOffset = with(LocalDensity.current) {
                    (PlayerTranslateLangArrowSize + PlayerTranslateLangPanelGap).roundToPx()
                }
                Popup(
                    alignment = Alignment.TopEnd,
                    offset = IntOffset(0, topOffset),
                    onDismissRequest = { if (open) open = false },
                    properties = PopupProperties(focusable = open),
                ) {
                    AnimatedVisibility(
                        visible = open,
                        enter = fadeIn(
                            tween(durationMillis = AppDuration.sheet, easing = AppEasing.easeInOutQuad),
                        ) + scaleIn(
                            initialScale = 0.92f,
                            transformOrigin = TransformOrigin(1f, 0f),
                            animationSpec = tween(
                                durationMillis = AppDuration.sheet,
                                easing = AppEasing.easeInOutQuad,
                            ),
                        ),
                        exit = fadeOut(
                            tween(durationMillis = PlayerTranslateLangExitMillis, easing = AppEasing.easeInOutQuad),
                        ) + scaleOut(
                            targetScale = 0.96f,
                            transformOrigin = TransformOrigin(1f, 0f),
                            animationSpec = tween(
                                durationMillis = PlayerTranslateLangExitMillis,
                                easing = AppEasing.easeInOutQuad,
                            ),
                        ),
                    ) {
                        PlayerTranslateLangPanel(
                            langs = langs,
                            current = current,
                            onSelect = {
                                open = false
                                onSelect(it)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 触发器：单个自绘下拉箭头（开合旋转 180°） */
@Composable
private fun PlayerTranslateLangTrigger(open: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.easeInOutQuad),
        label = "playerTranslateLangChevron",
    )
    Box(
        modifier = Modifier
            .size(PlayerTranslateLangArrowSize)
            .clip(RoundedCornerShape(AppRadius.r6))
            .background(AppColors.whiteA06)
            .clickableNoRipple(onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(AppIconSize.icon13).graphicsLayer { rotationZ = rotation }) {
            val w = size.width
            val h = size.height
            val stroke = w * 0.15f
            drawLine(
                AppColors.textSecondary,
                Offset(w * 0.25f, h * 0.36f),
                Offset(w * 0.50f, h * 0.62f),
                stroke,
                StrokeCap.Round,
            )
            drawLine(
                AppColors.textSecondary,
                Offset(w * 0.75f, h * 0.36f),
                Offset(w * 0.50f, h * 0.62f),
                stroke,
                StrokeCap.Round,
            )
        }
    }
}

/** 面板：语言清单，单列错峰进场，激活项高亮 + 主色勾选 */
@Composable
private fun PlayerTranslateLangPanel(
    langs: List<String>,
    current: String?,
    onSelect: (String) -> Unit,
) {
    var itemsShown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { itemsShown = true }

    Surface(
        color = AppColors.surface,
        shape = RoundedCornerShape(AppRadius.card),
        modifier = Modifier.width(AppSize.dropdownPanelWidth),
    ) {
        Column(
            modifier = Modifier
                .border(
                    width = AppBorder.thin,
                    color = AppColors.whiteA06,
                    shape = RoundedCornerShape(AppRadius.card),
                )
                .padding(vertical = AppSpacing.sp4, horizontal = AppSpacing.sp4),
        ) {
            for ((index, code) in langs.withIndex()) {
                AnimatedVisibility(
                    visible = itemsShown,
                    enter = fadeIn(
                        tween(
                            durationMillis = AppDuration.sheet,
                            delayMillis = index * PlayerTranslateLangStaggerMillis,
                            easing = AppEasing.easeInOutQuad,
                        ),
                    ) + slideInVertically(
                        animationSpec = tween(
                            durationMillis = AppDuration.sheet,
                            delayMillis = index * PlayerTranslateLangStaggerMillis,
                            easing = AppEasing.easeInOutQuad,
                        ),
                    ) { fullHeight -> -fullHeight / 6 },
                    exit = ExitTransition.None,
                ) {
                    PlayerTranslateLangItem(
                        label = lyricLangLabel(code),
                        active = code == current,
                        onTap = { onSelect(code) },
                    )
                }
            }
        }
    }
}

/** 面板选项：激活态主色底 + 白字 + 主色勾选 */
@Composable
private fun PlayerTranslateLangItem(label: String, active: Boolean, onTap: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.r8))
            .background(if (active) AppColors.primaryA12 else Color.Transparent)
            .clickableRipple(onTap)
            .padding(vertical = AppSpacing.sp10, horizontal = AppSpacing.sp12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = if (active) AppColors.white else AppColors.white60,
            fontSize = AppFontSize.base,
            fontWeight = AppFontWeight.medium,
            modifier = Modifier.weight(1f),
        )
        if (active) {
            Spacer(modifier = Modifier.width(AppSpacing.sp8))
            PlayerTranslateLangCheckMark(
                modifier = Modifier.size(AppIconSize.icon16),
                tint = AppColors.primary,
            )
        }
    }
}

/** 勾选（自绘线条，勾形与搜索模式下拉的勾一致） */
@Composable
private fun PlayerTranslateLangCheckMark(modifier: Modifier = Modifier, tint: Color = AppColors.primary) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.22f, h * 0.52f)
            lineTo(w * 0.43f, h * 0.73f)
            lineTo(w * 0.78f, h * 0.28f)
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = w * 0.13f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
