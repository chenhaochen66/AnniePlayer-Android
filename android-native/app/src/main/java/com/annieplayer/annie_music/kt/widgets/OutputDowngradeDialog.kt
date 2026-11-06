package com.annieplayer.annie_music.kt.widgets

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties
import com.annieplayer.annie_music.kt.core.audio.DowngradeNotice
import com.annieplayer.annie_music.kt.ui.theme.AnnieTypography
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight

/**
 * 输出降级提示。**样式与「退出安妮音乐盒？」一致**：同一套 `AlertDialog`、
 * 同一组排版（`dialogTitle` + `caption`）、点窗外不关闭。
 *
 * 只回答三件事：选了哪条、实际走了哪条、为什么。最后一句永远是「设置保持不变」——
 * 少了它，用户会以为选择被改掉了，换回支持的设备后还得重新设置一遍。
 *
 * 与退出弹窗的唯一差别在按钮颜色：那个是 `danger`（不可撤销），这里用 `primary`
 * （只是告知，不需要用户做危险决定）。
 */
@Composable
fun OutputDowngradeDialog(notice: DowngradeNotice, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
        containerColor = AppColors.surface,
        title = {
            Text(text = "输出已降级", style = AnnieTypography.dialogTitle)
        },
        text = {
            Text(
                text = buildString {
                    append(notice.transition)
                    append("\n")
                    append(notice.reasonText)
                    if (notice.reason.advice.isNotEmpty()) {
                        append("\n")
                        append(notice.reason.advice)
                    }
                    append("\n设置保持不变")
                },
                style = AnnieTypography.caption,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "知道了",
                    color = AppColors.primary,
                    fontWeight = AppFontWeight.semiBold,
                )
            }
        },
    )
}
