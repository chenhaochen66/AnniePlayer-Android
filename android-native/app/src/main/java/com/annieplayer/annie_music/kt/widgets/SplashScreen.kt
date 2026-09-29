package com.annieplayer.annie_music.kt.widgets

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.R
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppLetterSpacing
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppShadow
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import kotlinx.coroutines.delay

/**
 * 启动开屏（1:1 移植 Flutter `lib/widgets/splash_screen.dart`，
 * 对齐 RN 版 `components/SplashScreen.tsx`）。
 *
 * - 深蓝底（[AppColors.surface] = Dart `Color(0xFF1A1A2E)`）+ 方图 + 七彩「安妮音乐盒」
 * - 时间线（对齐 Dart）：0ms 淡入（500ms，[AppDuration.fade] + [AppEasing.linear]），
 *   1000ms 触发 [onPrepare]（[AppDuration.skeletonPulse]），1500ms 开始淡出
 *   （[AppDuration.splashTotal]），再 500ms 后触发 [onFinish]。
 * - 由宿主持有生命周期：`onFinish` 后应把本组件从树上移除。
 * - 图片资源 `assets/annie.jpg` 尚未进入 Android 工程（同 HomeScreen 既有约定），
 *   暂用音符占位块渲染，待资源入库后替换。
 */
@Composable
fun SplashScreen(
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    onPrepare: (() -> Unit)? = null,
) {
    var fadedIn by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        fadedIn = true
        // 1000ms：准备阶段
        delay(AppDuration.skeletonPulse.toLong())
        onPrepare?.invoke()
        // 到 1500ms：开始淡出
        delay((AppDuration.splashTotal - AppDuration.skeletonPulse).toLong())
        fadedIn = false
        // 淡出 500ms 完成后回调
        delay(AppDuration.fade.toLong())
        onFinish()
    }

    val splashAlpha by animateFloatAsState(
        targetValue = if (fadedIn) 1f else 0f,
        animationSpec = tween(durationMillis = AppDuration.fade, easing = AppEasing.linear),
        label = "splashAlpha",
    )

    // 方图边长：屏宽 45%（对齐 Dart `MediaQuery.of(context).size.width * 0.45`）
    val side = LocalConfiguration.current.screenWidthDp.dp * 0.45f
    val shadowBlurPx = with(LocalDensity.current) { AppShadow.blurLg.toPx() }

    // 七彩字符颜色（对齐 Dart COLORS；token 已收录于 AppColors.splashBubble*）
    val bubbleColors = listOf(
        AppColors.splashBubbleRed,
        AppColors.splashBubbleYellow,
        AppColors.splashBubbleCyan,
        AppColors.splashBubblePink,
        AppColors.splashBubbleBlue,
        AppColors.splashBubblePurple,
    )
    val chars = listOf("安", "妮", "音", "乐", "盒")

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = splashAlpha }
            .background(AppColors.surface),
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Dart: ClipRRect(20) + Image.asset('assets/annie.jpg', fit: BoxFit.cover)
            Image(
                painter = painterResource(R.drawable.annie),
                contentDescription = null,
                modifier = Modifier
                    .size(side)
                    .clip(RoundedCornerShape(AppRadius.r20)),
                contentScale = ContentScale.Crop,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp24))
            Row(verticalAlignment = Alignment.CenterVertically) {
                chars.forEachIndexed { index, char ->
                    Text(
                        text = char,
                        style = TextStyle(
                            color = bubbleColors[index % bubbleColors.size],
                            fontSize = AppFontSize.size32,
                            fontWeight = AppFontWeight.bold,
                            // Dart fontFamily 'Georgia'；Android 无 Georgia，用 serif 族等价（观感最近）
                            fontFamily = FontFamily.Serif,
                            fontStyle = FontStyle.Italic,
                            letterSpacing = AppLetterSpacing.wider,
                            shadow = Shadow(color = AppColors.white30, blurRadius = shadowBlurPx),
                        ),
                    )
                }
            }
        }
    }
}
