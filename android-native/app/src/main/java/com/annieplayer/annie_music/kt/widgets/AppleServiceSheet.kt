package com.annieplayer.annie_music.kt.widgets

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.data.sources.apple.thirdparty.AppleServiceSettings
import com.annieplayer.annie_music.kt.data.sources.apple.thirdparty.LocalWrapperAppleApi
import com.annieplayer.annie_music.kt.ui.theme.AnnieTypography
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import kotlinx.coroutines.launch

/**
 * 「Apple Music 服务」浮层：把 Apple 的 masterUrl / key 请求切到本机 wrapper-lite。
 *
 * 服务本体由独立的 WrapperLite App 托管（QEMU guest），这里只负责：
 * - 开关：是否使用本机服务（持久化，切换即时生效）
 * - 检测：直接打 `http://127.0.0.1:12340/status`，确认可达并显示账号区域
 */
@Composable
fun AppleServiceSheet(
    visible: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var enabled by remember { mutableStateOf(AppleServiceSettings.useLocal) }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var resultOk by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current

    BackHandler(enabled = visible) { onClose() }

    val scrimAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.linear),
        label = "appleServiceScrim",
    )
    val slide by animateFloatAsState(
        targetValue = if (visible) 0f else 1f,
        animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.linear),
        label = "appleServiceSlide",
    )

    // 完全隐藏时不参与组合，避免全屏遮罩吃掉下层点击
    if (!visible && scrimAlpha == 0f) return

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = scrimAlpha }
                .clickable(
                    enabled = visible,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                )
                .background(AppColors.scrim50),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = slide * size.height }
                .background(AppColors.background)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { }
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            // 标题栏：与 Qobuz Token 管理页完全对齐（top 留白 40、返回箭头、screenTitle 字号）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = AppSpacing.sp24,
                        top = AppSpacing.sp40,
                        end = AppSpacing.sp24,
                        bottom = AppSpacing.sp8,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .clickableNoRipple(onClose)
                        .padding(AppSpacing.sp4),
                ) {
                    DetailChevronLeft(
                        modifier = Modifier.size(AppIconSize.icon22),
                        tint = AppColors.textSecondary,
                    )
                }
                Spacer(modifier = Modifier.size(AppSpacing.sp8))
                Text(
                    text = "Apple Music 服务",
                    modifier = Modifier.weight(1f),
                    style = AnnieTypography.screenTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 开关
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.screenPaddingH, vertical = AppSpacing.sp8),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "使用本机服务",
                        color = AppColors.white,
                        fontSize = AppFontSize.base,
                    )
                    Spacer(Modifier.height(AppSpacing.sp4))
                    Text(
                        text = "密钥模板、m3u8 与歌词都走本机账号派生，不再依赖第三方站点",
                        color = AppColors.white54,
                        fontSize = AppFontSize.sm,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { value ->
                        enabled = value
                        AppleServiceSettings.setUseLocal(value)
                        result = null
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = AppColors.background,
                        checkedTrackColor = AppColors.white,
                        uncheckedThumbColor = AppColors.white54,
                        uncheckedTrackColor = AppColors.whiteA06,
                    ),
                )
            }

            // 地址 + 检测
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.screenPaddingH, vertical = AppSpacing.sp12),
            ) {
                Text(
                    text = LocalWrapperAppleApi.BASE,
                    color = AppColors.white54,
                    fontSize = AppFontSize.sm,
                )
                Spacer(Modifier.height(AppSpacing.sp12))
                Button(
                    onClick = {
                        if (checking) return@Button
                        checking = true
                        result = null
                        scope.launch {
                            val r = LocalWrapperAppleApi.checkStatus()
                            checking = false
                            resultOk = r.isSuccess
                            result = r.fold(
                                onSuccess = { it },
                                onFailure = { e ->
                                    "连接失败：" + (e.message ?: e::class.simpleName ?: "未知错误")
                                },
                            )
                        }
                    },
                    enabled = !checking,
                    shape = RoundedCornerShape(AppRadius.card),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.whiteA06,
                        contentColor = AppColors.white,
                        disabledContainerColor = AppColors.whiteA06,
                        disabledContentColor = AppColors.white54,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    if (checking) {
                        CircularProgressIndicator(
                            color = AppColors.white54,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.height(AppSpacing.sp8))
                        Text(
                            text = "检测中…",
                            fontSize = AppFontSize.base,
                            color = AppColors.white54,
                        )
                    } else {
                        Text(
                            text = "检测服务",
                            fontSize = AppFontSize.base,
                            color = AppColors.white,
                        )
                    }
                }

                result?.let { text ->
                    Spacer(Modifier.height(AppSpacing.sp12))
                    Text(
                        text = text,
                        color = if (resultOk) AppColors.white else AppColors.danger,
                        fontSize = AppFontSize.sm,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // 说明
            Text(
                text = "服务由 WrapperLite 应用托管运行：先在那边登录 Apple ID 并启动服务，" +
                    "这里检测通过后即可使用。App 直连端口是 12341，12340 仅给网页工具用。" +
                    "关闭本开关会回到第三方接口（仅在服务不可用时作为兜底）。",
                color = AppColors.white54,
                fontSize = AppFontSize.sm,
                modifier = Modifier.padding(
                    horizontal = AppSpacing.screenPaddingH,
                    vertical = AppSpacing.sp12,
                ),
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}
