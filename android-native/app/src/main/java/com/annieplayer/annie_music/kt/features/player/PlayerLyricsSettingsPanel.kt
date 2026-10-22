package com.annieplayer.annie_music.kt.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.AppSheet
import com.annieplayer.annie_music.kt.widgets.clickableNoRipple

/** 字号档位（Flutter `_fontScaleLevels`：0.5 / 0.625 / 0.75 / 0.875 / 1.0） */
private val PlayerFontScaleLevels = listOf(0.5f, 0.625f, 0.75f, 0.875f, 1.0f)

/** 字号面板示例字号的基数 / 步进（Flutter：`fontSize: 10 + lv * 14`） */
private const val PlayerFontSampleBaseSp = 10f
private const val PlayerFontSampleStepSp = 14f

/** 44dp（Flutter `_FontScalePanel` 档位按钮 `Container(width: 44, height: 44)`） */
private val PlayerScalePanelButtonSize = 44.dp

/**
 * 歌词设置面板：字号档位 + 逐字歌词开关。
 *
 * 直接复用 [AppSheet]（歌曲操作页同款）：遮罩淡入 + 面板自下而上滑入 / 滑出，
 * 150ms 线性，且关闭动画播完才卸载 —— 呼出 / 关闭动画与歌曲操作页逐帧一致。
 */
@Composable
fun PlayerFontScalePanel(
    visible: Boolean,
    current: Float,
    showWordByWord: Boolean,
    onSelect: (Float) -> Unit,
    onToggleWordByWord: () -> Unit,
    onClose: () -> Unit,
) {
    AppSheet(
        visible = visible,
        title = "歌词设置",
        onClose = onClose,
    ) {
        Text(
            text = "字号选择",
            color = AppColors.white,
            fontSize = AppFontSize.size15,
        )
        Spacer(modifier = Modifier.height(AppSpacing.sp12))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            PlayerFontScaleLevels.forEach { level ->
                val selected = level == current
                Box(
                    modifier = Modifier
                        .size(PlayerScalePanelButtonSize)
                        .clip(RoundedCornerShape(AppRadius.r10))
                        .background(
                            if (selected) AppColors.primary.copy(alpha = 0.2f) else AppColors.whiteA05,
                        )
                        .clickableNoRipple { onSelect(level) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Aa",
                        color = if (selected) AppColors.primary else AppColors.white70,
                        fontSize = (PlayerFontSampleBaseSp + level * PlayerFontSampleStepSp).sp,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(AppSpacing.sp20))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "逐字歌词",
                modifier = Modifier.weight(1f),
                color = AppColors.white,
                fontSize = AppFontSize.size15,
            )
            Switch(
                checked = showWordByWord,
                onCheckedChange = { onToggleWordByWord() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = AppColors.background,
                    checkedTrackColor = AppColors.white,
                    uncheckedThumbColor = AppColors.white54,
                    uncheckedTrackColor = AppColors.whiteA06,
                ),
            )
        }
    }
}
