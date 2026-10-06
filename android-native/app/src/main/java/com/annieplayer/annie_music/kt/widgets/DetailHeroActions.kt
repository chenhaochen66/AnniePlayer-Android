package com.annieplayer.annie_music.kt.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing

/**
 * 详情页 Hero 主操作：白底胶囊「播放」。
 *
 * 专辑 / 歌单两个详情浮层共用（同在 `widgets` 包内，故为 internal）。
 * [enabled] 为 false 时置灰且不响应点击（列表还没加载出来）。
 */
@Composable
internal fun DetailPlayAllPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val bg = if (enabled) AppColors.white else AppColors.whiteA15
    val fg = if (enabled) AppColors.background else AppColors.whiteA40
    val clickModifier = if (enabled) Modifier.clickableRipple(onClick) else Modifier

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(bg)
            .then(clickModifier)
            .padding(horizontal = AppSpacing.sp24, vertical = AppSpacing.sp10),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayIcon(modifier = Modifier.size(AppIconSize.icon18), tint = fg)
        Spacer(modifier = Modifier.width(AppSpacing.sp8))
        Text(
            text = "播放",
            color = fg,
            fontSize = AppFontSize.size15,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * 详情页 Hero 次操作：圆形图标按钮。
 *
 * [enabled] 为 false 时整颗置灰且不响应点击（例如下载按钮：未进多选模式时无"已选歌曲"可下）。
 * [content] 收到解析后的 tint，方便内部自绘图标一起变灰。
 */
@Composable
internal fun DetailRoundIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable (Color) -> Unit,
) {
    val tint = if (enabled) AppColors.white else AppColors.whiteA25
    val bg = if (enabled) AppColors.whiteA06 else AppColors.whiteA03
    val clickModifier = if (enabled) Modifier.clickableRipple(onClick) else Modifier

    Box(
        modifier = modifier
            .size(AppIconSize.icon40)
            .clip(CircleShape)
            .background(bg)
            .then(clickModifier),
        contentAlignment = Alignment.Center,
    ) {
        content(tint)
    }
}
