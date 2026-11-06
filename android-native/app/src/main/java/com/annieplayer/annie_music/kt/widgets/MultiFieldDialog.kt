package com.annieplayer.annie_music.kt.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.ui.theme.AnnieTypography

/**
 * 通用「多字段输入」弹窗 —— 三处列表页（Qobuz Token / KKBOX 账号 / Apple Wrapper 地址）
 * 的新增与编辑**共用这一个组件**，交互与视觉完全一致：
 *
 * - 点标题栏「+」→ 打开（新增）；点条目上的铅笔 → 打开（编辑，字段预填）。
 * - 校验失败**不关窗**：把原因画在输入框下面（`error` 由调用方给），用户改一下就能再存。
 * - 密码字段自带「显示 / 隐藏」眼睛按钮（KKBOX 用）。
 *
 * 为什么统一成弹窗而不是页内表单：这些页都是「列表 + 增删改」，页内表单会把列表往下推，
 * 打开/关闭时列表抖动，而且键盘弹出后表单常被顶出可视区；弹窗没有这些问题。
 */
data class MultiFieldDialogField(
    /** 占位文字，兼作字段说明 */
    val hint: String,
    val initial: String = "",
    val keyboardType: KeyboardType = KeyboardType.Text,
    /** 是否按密码处理（默认打码，带眼睛按钮切换） */
    val password: Boolean = false,
    val imeAction: ImeAction = ImeAction.Next,
)

@Composable
fun MultiFieldDialog(
    visible: Boolean,
    title: String,
    fields: List<MultiFieldDialogField>,
    error: String?,
    onDismiss: () -> Unit,
    /** @return true = 已保存（关窗）；false = 校验不过（保持打开，展示 [error]） */
    onConfirm: (List<String>) -> Boolean,
    confirmLabel: String = "保存",
    /** 输入框下方的固定说明（非错误态时显示） */
    tip: String? = null,
) {
    if (!visible) return

    // 打开时按初始值填一次；换条目 / 重新打开都会重算（key 里带初值）
    val initials = fields.map { it.initial }
    var values by remember(visible, initials) { mutableStateOf(initials) }
    var showPassword by remember(visible) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = AnnieTypography.dialogTitle) },
        text = {
            Column(modifier = Modifier.imePadding()) {
                fields.forEachIndexed { index, field ->
                    if (index > 0) Spacer(modifier = Modifier.height(AppSpacing.sp8))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AppColors.whiteA05, RoundedCornerShape(AppRadius.r8))
                            .padding(AppSpacing.sp12),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            BasicTextField(
                                value = values.getOrElse(index) { "" },
                                onValueChange = { text ->
                                    values = values.toMutableList().also { it[index] = text }
                                },
                                singleLine = true,
                                textStyle = TextStyle(
                                    color = AppColors.white,
                                    fontSize = AppFontSize.size15,
                                ),
                                cursorBrush = SolidColor(AppColors.primary),
                                visualTransformation = if (field.password && !showPassword) {
                                    PasswordVisualTransformation()
                                } else {
                                    VisualTransformation.None
                                },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = if (field.password) {
                                        KeyboardType.Password
                                    } else {
                                        field.keyboardType
                                    },
                                    imeAction = field.imeAction,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (values.getOrElse(index) { "" }.isEmpty()) {
                                Text(
                                    text = field.hint,
                                    color = AppColors.white30,
                                    fontSize = AppFontSize.size15,
                                )
                            }
                        }
                        if (field.password) {
                            Box(
                                modifier = Modifier
                                    .clickable { showPassword = !showPassword }
                                    .padding(AppSpacing.sp8),
                            ) {
                                PasswordEyeGlyph(
                                    off = showPassword,
                                    modifier = Modifier.size(AppIconSize.icon20),
                                    tint = AppColors.glyphMid,
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(AppSpacing.sp8))
                val note = error ?: tip
                if (note != null) {
                    Text(
                        text = note,
                        color = if (error != null) AppColors.danger else AppColors.white54,
                        fontSize = AppFontSize.sm,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(values.map { it.trim() }) },
                enabled = values.any { it.isNotBlank() },
            ) {
                Text(
                    text = confirmLabel,
                    color = AppColors.primary,
                    fontWeight = AppFontWeight.semiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消", color = AppColors.textTertiary)
            }
        },
        containerColor = AppColors.surface,
    )
}

/**
 * 密码显隐图标：眼睛轮廓 + 瞳孔，[off] 时叠加斜杠。
 *
 * 核心图标集没有 `Visibility`，自绘等价剪影（与 KKBOX 账号页原来那个完全一样，
 * 现在三处共用这一份）。
 */
@Composable
internal fun PasswordEyeGlyph(
    off: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = AppColors.textTertiary,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        val eye = Path().apply {
            moveTo(w * 0.08f, h * 0.5f)
            cubicTo(w * 0.30f, h * 0.16f, w * 0.70f, h * 0.16f, w * 0.92f, h * 0.5f)
            cubicTo(w * 0.70f, h * 0.84f, w * 0.30f, h * 0.84f, w * 0.08f, h * 0.5f)
            close()
        }
        drawPath(path = eye, color = tint, style = Stroke(width = stroke))
        drawCircle(color = tint, radius = w * 0.12f, center = Offset(w * 0.5f, h * 0.5f))
        if (off) {
            drawLine(
                color = tint,
                start = Offset(w * 0.15f, h * 0.85f),
                end = Offset(w * 0.85f, h * 0.15f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
                pathEffect = null,
            )
        }
    }
}
