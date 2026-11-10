package com.annieplayer.annie_music.kt.features.favorites

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.annieplayer.annie_music.kt.data.PlaylistRef
import com.annieplayer.annie_music.kt.data.PlaylistService
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.ui.theme.AnnieTypography
import kotlinx.coroutines.launch

/**
 * 「导入歌单」弹窗（收藏库播放列表页的「+」，以及自建歌单详情的「从链接导入」）。
 *
 * 样式与项目其它确认弹窗一致（`AlertDialog` + `AppColors.surface`），两个按钮：
 * - **查看歌单**：解析出 `平台 + id` 就直接打开歌单详情（`parsePlaylistUrl` 是纯字符串解析，不联网）。
 * - **导入歌单**：解析 → 拉一次歌单详情（要名字 / 封面 / 曲目数）→ 交给宿主落库。
 *
 * ## 为什么要「记住链接」
 * 「查看歌单」会关掉本弹窗（`AlertDialog` 是独立窗口，不关就会盖住详情页，
 * 点上去像没反应）。关掉之后链接与解析结果**不清**，返回列表页再点「+」原样回来 ——
 * 体验上等价于「弹窗没被销毁」。状态由调用方持有（`rememberSaveable`），这里只管展示。
 *
 * ## 两个按钮的落点由宿主决定
 * 收藏库：加入**歌单收藏**；自建歌单详情：写进**当前自建歌单**。
 * 本组件只负责「解析 + 拉数据 + 报错」，不碰 `FavoriteStore`。
 */
@Composable
internal fun PlaylistImportDialog(
    visible: Boolean,
    /** 当前链接（宿主持有，关窗后保留） */
    link: String,
    onLinkChange: (String) -> Unit,
    onDismiss: () -> Unit,
    /** 查看歌单（拿到解析结果后由宿主 push 详情页） */
    onView: (PlaylistRef) -> Unit,
    /**
     * 导入歌单：宿主负责落库。
     *
     * @return `null` = 成功（弹窗自己关掉）；非空字符串 = 失败原因（显示在输入框下面）
     */
    onImport: suspend (PlaylistRef) -> String?,
) {
    if (!visible) return

    var busy by remember(visible) { mutableStateOf(false) }
    var error by remember(visible) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val trimmed = link.trim()
    val parsed = remember(trimmed) { PlaylistService.parsePlaylistUrl(trimmed) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(text = "导入歌单", style = AnnieTypography.dialogTitle) },
        text = {
            Column(modifier = Modifier.imePadding()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppColors.whiteA05, RoundedCornerShape(AppRadius.r8))
                        .padding(AppSpacing.sp12),
                ) {
                    if (trimmed.isEmpty()) {
                        Text(
                            text = "粘贴歌单链接或 ID",
                            color = AppColors.white30,
                            fontSize = AppFontSize.size15,
                        )
                    }
                    BasicTextField(
                        value = link,
                        onValueChange = {
                            onLinkChange(it)
                            error = null
                        },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = AppColors.white,
                            fontSize = AppFontSize.size15,
                        ),
                        cursorBrush = SolidColor(AppColors.primary),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(modifier = Modifier.height(AppSpacing.sp8))
                // 识别成功先给个「认出是哪家」的反馈 —— 两家歌单 id 都是纯数字，
                // 粘错平台时只有这一行能提前暴露问题
                val hint = when {
                    error != null -> error.orEmpty()
                    trimmed.isEmpty() -> null
                    parsed == null -> "未识别到有效的歌单链接或 ID"
                    else -> "已识别：${parsed.platform.label}"
                }
                if (hint != null) {
                    Text(
                        text = hint,
                        color = if (parsed == null && trimmed.isNotEmpty()) AppColors.danger else AppColors.white54,
                        fontSize = AppFontSize.sm,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsed != null && !busy,
                onClick = {
                    val ref = parsed ?: return@TextButton
                    busy = true
                    error = null
                    scope.launch {
                        val failure = runCatching { onImport(ref) }
                            .getOrElse { it.message ?: "导入失败" }
                        busy = false
                        if (failure == null) onDismiss() else error = failure
                    }
                },
            ) {
                Text(
                    text = if (busy) "导入中…" else "导入歌单",
                    color = if (parsed != null && !busy) AppColors.primary else AppColors.textDisabled,
                    fontWeight = AppFontWeight.semiBold,
                )
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sp2)) {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        val ref = parsed
                        if (ref != null) {
                            onView(ref)
                            // 不在这里 onDismiss：宿主 push 详情页后弹窗若还开着会盖住页面，
                            // 但「保留输入」由宿主的状态负责，这里直接关。
                            onDismiss()
                        } else {
                            error = "未识别到有效的歌单链接或 ID"
                        }
                    },
                ) {
                    Text(text = "查看歌单", color = AppColors.textTertiary)
                }
            }
        },
        containerColor = AppColors.surface,
    )
}
