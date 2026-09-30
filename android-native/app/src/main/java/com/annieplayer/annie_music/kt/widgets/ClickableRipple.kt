package com.annieplayer.annie_music.kt.widgets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.annieplayer.annie_music.kt.ui.theme.AppColors

/**
 * 带 Material 涟漪（点击拟态波纹）的点击修饰符 —— **仅详情页使用**。
 *
 * 与同包的 `clickableNoRipple` 相对：本工程默认的点击反馈是「无涟漪」（克制、对齐 Flutter
 * `GestureDetector` 的手感），但专辑 / 歌单 / KKBOX / Qobuz 四个详情页用户明确要求**点击后
 * 有波纹反馈**，因此单独提供一份，只在详情页替换使用，不影响其它界面。
 *
 * 涟漪颜色取 [AppColors.white24]：在「彩色封面氛围背景 + 液态玻璃面板」之上是一层克制的白光，
 * 既看得见又不会盖住下层取色。涟漪范围由节点自身裁剪，配合调用处已有的 `clip(shape)` 即贴合圆角。
 *
 * @param onClick 点击回调（行为与原 `clickableNoRipple` 完全一致，仅多出视觉反馈）。
 */
@Composable
fun Modifier.clickableRipple(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = interaction,
        indication = ripple(color = AppColors.white24),
        onClick = onClick,
    )
}
