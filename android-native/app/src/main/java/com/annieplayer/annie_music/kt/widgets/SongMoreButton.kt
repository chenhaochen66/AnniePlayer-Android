package com.annieplayer.annie_music.kt.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing

/**
 * 歌曲行尾的共享「⋮」入口：点开全局 `SongActionsSheet`（下载 / 跳转到歌手 / 跳转到专辑…）。
 *
 * 各页面行尾原本各有一个下载按钮 + 各自的音质接线；统一换成本组件后，
 * 单曲相关功能只需改 `SongActionsSheet` 一处。
 */
@Composable
fun SongMoreButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = AppColors.textTertiary,
) {
    Box(
        modifier = modifier
            .clickableNoRipple(onClick)
            .padding(AppSpacing.sp4),
        contentAlignment = Alignment.Center,
    ) {
        SongMoreGlyph(tint = tint)
    }
}

/** 纯图形版（供已有自己命中区/语义的行内直接替换原下载图标） */
@Composable
fun SongMoreGlyph(
    modifier: Modifier = Modifier,
    tint: Color = AppColors.textTertiary,
) {
    Canvas(modifier = modifier.size(AppIconSize.icon20)) {
        val radius = size.width * 0.09f
        val centerX = size.width / 2f
        drawCircle(tint, radius, Offset(centerX, size.height * 0.22f))
        drawCircle(tint, radius, Offset(centerX, size.height * 0.50f))
        drawCircle(tint, radius, Offset(centerX, size.height * 0.78f))
    }
}
