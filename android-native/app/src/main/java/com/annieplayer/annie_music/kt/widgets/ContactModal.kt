package com.annieplayer.annie_music.kt.widgets

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.annieplayer.annie_music.kt.R
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppLineHeight
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.ui.theme.AnnieTypography

/**
 * 联系我们弹窗（1:1 移植 Flutter `lib/widgets/contact_modal.dart`，
 * 视觉对齐 RN 版 `components/ContactModal.tsx`）。
 *
 * - 自驱动浮层：`visible` 为 true 才渲染（对齐 Dart `if (!visible) return SizedBox.shrink()`）
 * - 居中卡片：标题「联系我们」+ 两张二维码 + 关闭按钮 + 免责声明 / 鸣谢
 * - 返回键：先关大图预览，再关弹窗
 *
 * 资源说明：Dart 用 `Image.asset('assets/group1.jpg' / 'group2.jpg')`。这两个资源尚未
 * 进入 Android 工程（同 HomeScreen 对 `annie.jpg` 的既有约定），故这里直接渲染 Flutter
 * `errorBuilder` 的降级占位（白色 0.06 圆角块 + 破图图标），待资源入库后再替换为图片。
 */

/** 预览关闭按钮的顶部偏移（Dart `top: 50`，无对应刻度，用 44 + 6 组合） */
private val ContactModalPreviewCloseTop = AppSpacing.sp44 + AppSpacing.sp6

/** 免责声明文案（与 Dart 完全一致） */
private const val ContactModalDisclaimer =
    "声明：本软件仅供学习交流使用，完全免费，不涉及任何商业用途。所有资源请在下载后24小时内删除。"

/** 鸣谢文案（与 Dart 完全一致） */
private const val ContactModalThanks = "鸣谢：IKUN MUSIC提供的公益网易解析源"

@Composable
fun ContactModal(
    visible: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    // 当前放大预览的二维码标签（null 表示未预览）
    var previewLabel by remember { mutableStateOf<String?>(null) }

    // 返回键：先关大图预览，再关弹窗（对齐 Dart PopScope）
    BackHandler {
        if (previewLabel != null) {
            previewLabel = null
        } else {
            onClose()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 遮罩（对齐 Dart rgba(0,0,0,0.6)）
        // 遮罩：只吞点击，**不关闭**（退出只走返回手势 / 卡片里的「关闭」按钮）
        // `clickableNoRipple` 必须保留：吃掉触摸、阻止点击穿透到下层页面。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AppColors.scrim60)
                .clickableNoRipple { },
        )

        // 居中卡片（吞掉卡片内空白点击，避免误触遮罩关闭）
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.88f)
                .background(AppColors.modalPanel, RoundedCornerShape(AppRadius.r20))
                .clickableNoRipple {}
                .padding(horizontal = AppSpacing.sp28, vertical = AppSpacing.sp40),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = "联系我们", style = AnnieTypography.sheetTitle)
                Spacer(modifier = Modifier.height(AppSpacing.sp28))
                ContactModalQrRow(label = "联系我们①", imageRes = R.drawable.group1, onOpenPreview = { previewLabel = "联系我们①" })
                Spacer(modifier = Modifier.height(AppSpacing.sp16))
                ContactModalQrRow(label = "联系我们②", imageRes = R.drawable.group2, onOpenPreview = { previewLabel = "联系我们②" })
                Spacer(modifier = Modifier.height(AppSpacing.sp28))
                Box(
                    modifier = Modifier
                        .clickableNoRipple(onClose)
                        .background(AppColors.glass, RoundedCornerShape(AppRadius.card))
                        .padding(horizontal = AppSpacing.sp32, vertical = AppSpacing.sp10),
                ) {
                    Text(
                        text = "关闭",
                        color = AppColors.white,
                        fontSize = AppFontSize.size15,
                        fontWeight = AppFontWeight.semiBold,
                    )
                }
                Spacer(modifier = Modifier.height(AppSpacing.sp16))
                Text(
                    text = ContactModalDisclaimer,
                    textAlign = TextAlign.Center,
                    color = AppColors.white30,
                    fontSize = AppFontSize.xs,
                    // 行高对齐 Dart `height: 16 / 11`
                    lineHeight = AppLineHeight.body16,
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp6))
                Text(
                    text = ContactModalThanks,
                    textAlign = TextAlign.Center,
                    color = AppColors.white30,
                    fontSize = AppFontSize.xs,
                    // 行高对齐 Dart `height: 16 / 11`
                    lineHeight = AppLineHeight.body16,
                )
            }
        }

        // 大图预览（对齐 Dart rgba(0,0,0,0.92)）
        previewLabel?.let { label ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppColors.scrim92)
                    .clickableNoRipple { previewLabel = null },
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(0.9f)
                        .fillMaxHeight(0.7f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // 大图预览：仅显示实际二维码图片（对齐 Dart Image.asset 大图，无底部文案）
                        Image(
                            painter = painterResource(
                                if (label == "联系我们①") R.drawable.group1 else R.drawable.group2,
                            ),
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = ContactModalPreviewCloseTop, end = AppSpacing.sp20)
                        .clickableNoRipple { previewLabel = null }
                        .padding(AppSpacing.sp8),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        tint = AppColors.white,
                        modifier = Modifier.size(AppIconSize.icon28),
                    )
                }
            }
        }
    }
}

/** 二维码行：80x80 缩略图 + 文案（点击缩略图看大图，对齐 Dart `_qrRow`） */
@Composable
private fun ContactModalQrRow(
    label: String,
    imageRes: Int,
    onOpenPreview: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .clickableNoRipple(onOpenPreview)
                .size(AppSize.modalInnerBox)
                .clip(RoundedCornerShape(AppRadius.r10))
                .background(AppColors.whiteA06),
            contentAlignment = Alignment.Center,
        ) {
            // Dart: ClipRRect + Image.asset('assets/group1.jpg' / 'group2.jpg')（已入库 drawable-nodpi）
            // 缩放模式对齐 Dart `BoxFit.contain`
            Image(
                painter = painterResource(imageRes),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp12))
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = AppColors.white70,
            fontSize = AppFontSize.base,
            fontWeight = AppFontWeight.medium,
        )
    }
}

/**
 * 破图占位图标：圆角方框 + 裂缝 + 太阳点。
 * 形状对齐 Flutter `Icons.broken_image_outlined`（errorBuilder 的占位语义）。
 */
@Composable
private fun ContactModalBrokenImageGlyph(
    modifier: Modifier = Modifier,
    tint: Color = AppColors.whiteA20,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        drawRoundRect(
            color = tint,
            cornerRadius = CornerRadius(w * 0.12f, w * 0.12f),
            style = Stroke(width = stroke),
        )
        val crack = Path().apply {
            moveTo(w * 0.58f, h * 0.08f)
            lineTo(w * 0.44f, h * 0.44f)
            lineTo(w * 0.60f, h * 0.52f)
            lineTo(w * 0.46f, h * 0.92f)
        }
        drawPath(path = crack, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round))
        drawCircle(color = tint, radius = w * 0.07f, center = Offset(w * 0.30f, h * 0.30f))
    }
}
