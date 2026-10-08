package com.annieplayer.annie_music.kt.widgets

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.core.util.lyricLangLabel
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing

/**
 * 下载译文语言选择浮层（Qobuz 歌曲专用）。
 *
 * 视觉与 [QualityPickerSheet] 完全同款（遮罩 `scrim60`、surface 顶部圆角 20、
 * 150ms 线性淡入淡出、抓手 + 标题 + 取消行），保证同一流程里两步弹窗观感一致。
 * 返回手势：`BackHandler(enabled = visible)` 优先关本弹窗，等价于点「不写入翻译」。
 *
 * @param langs 接口返回的可用译文语言（`translation_langs`，不全量列出）。
 * @param avoidMiniPlayer 当本弹窗挂在「迷你播放条之上」（详情 sheet 内部）时置 true。
 * @param miniPlayerVisible 迷你条当前是否可见（宿主传 `有歌在播 && 未打开全屏播放页`）。
 */
@Composable
fun TranslatePickerSheet(
    visible: Boolean,
    langs: List<String>,
    onSelect: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    songTitle: String = "",
    avoidMiniPlayer: Boolean = false,
    miniPlayerVisible: Boolean = false,
) {
    BackHandler(enabled = visible) { onCancel() }

    val fade by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.linear),
        label = "translatePickerFade",
    )

    val reserve = if (avoidMiniPlayer && miniPlayerVisible) AppSize.miniPlayerReserve else 0.dp
    val bottomInset = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()

    // 【关键 · 全屏浮层不得吃掉下层点击】
    // Compose 里 `clickable(enabled = false)` **依然占据命中测试**，会阻止事件传给下层兄弟节点。
    // 本浮层遮罩是 fillMaxSize，常驻就会把顶栏抽屉按钮 / 搜索框 / 模式下拉的点击全部挡掉
    // （现象：界面能显示，但所有按钮点不动）。因此完全隐藏时整个浮层不参与组合。
    if (!visible && fade == 0f) return

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade },
    ) {
        // ── 遮罩：吞掉点击但**不取消** ──
        // 必须显式选一门语言或点「不写入翻译」才算作答：点遮罩/误触就当作答会让下载
        // 带着「不写入翻译」直接跑掉，表现为「没选就开始下载了」。
        // 隐藏时整条 clickable 都不挂 → 不吃触摸也不挡下层。
        val scrimInteraction = remember { MutableInteractionSource() }
        val scrimClickModifier = if (visible) {
            Modifier.clickable(
                interactionSource = scrimInteraction,
                indication = null,
                onClick = { },
            )
        } else {
            Modifier
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(scrimClickModifier)
                .background(AppColors.scrim60),
        )

        // ── 底部面板 ──
        Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            val panelInteraction = remember { MutableInteractionSource() }
            // 吞掉面板内的点击，避免穿透到遮罩触发取消；隐藏时整条 clickable 都不挂，
            // 否则会在屏幕底部留下一片隐形热区。
            val panelClickModifier = if (visible) {
                Modifier.clickable(
                    interactionSource = panelInteraction,
                    indication = null,
                    onClick = { },
                )
            } else {
                Modifier
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(panelClickModifier)
                    .background(
                        color = AppColors.surface,
                        shape = RoundedCornerShape(topStart = AppRadius.r20, topEnd = AppRadius.r20),
                    )
                    .padding(
                        start = AppSpacing.sp20,
                        end = AppSpacing.sp20,
                        top = AppSpacing.sp20,
                        bottom = bottomInset + AppSpacing.sp16 + reserve,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .background(AppColors.whiteA15, RoundedCornerShape(AppRadius.r2)),
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp20))

                Text(
                    text = "选择翻译语言",
                    modifier = Modifier.fillMaxWidth(),
                    color = AppColors.textPrimary,
                    fontSize = AppFontSize.xl,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp4))
                Text(
                    text = if (songTitle.isEmpty()) {
                        "下载时把该语言的翻译写进歌词文件"
                    } else {
                        "《$songTitle》下载时写入该语言的翻译"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    color = AppColors.textTertiary,
                    fontSize = AppFontSize.size13,
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp20))

                for (lang in langs) {
                    TranslatePickerOptionRow(
                        lang = lang,
                        enabled = visible,
                        onTap = { onSelect(lang) },
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.sp8))
                }
                Spacer(modifier = Modifier.height(AppSpacing.sp4))

                val cancelInteraction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppColors.whiteA05, RoundedCornerShape(AppRadius.card))
                        .clickable(
                            enabled = visible,
                            interactionSource = cancelInteraction,
                            indication = null,
                            onClick = onCancel,
                        )
                        .padding(vertical = AppSpacing.sp14),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "不写入翻译",
                        color = AppColors.whiteA40,
                        fontSize = AppFontSize.size15,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/** 语言选项行：中文名 + 右侧语言码 */
@Composable
private fun TranslatePickerOptionRow(lang: String, enabled: Boolean, onTap: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.whiteA03, RoundedCornerShape(AppRadius.card))
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onTap,
            )
            .padding(AppSpacing.sp16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = lyricLangLabel(lang),
            modifier = Modifier.weight(1f),
            color = AppColors.textPrimary,
            fontSize = AppFontSize.lg,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = lang.uppercase(),
            color = AppColors.textTertiary,
            fontSize = AppFontSize.sm,
        )
    }
}
