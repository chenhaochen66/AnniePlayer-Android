package com.annieplayer.annie_music.kt.widgets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppShadow
import com.annieplayer.annie_music.kt.ui.theme.AppSize

/**
 * 迷你播放条（1:1 移植 Flutter `lib/widgets/mini_player.dart`）。
 *
 * 关键视觉与行为：
 * - 外层用页面背景色包一圈（top 10 / bottom 6），内层才是真正的播放条面板。
 * - 面板底色 `surfaceLight` @ 0.94，并带 `black` 向上 4px、blur 16 的投影。
 * - 显示/隐藏用 **200ms** 垂直位移（隐藏时下移 2 倍自身高度）。
 * - 隐藏条件：全屏播放页打开时（对齐 Flutter 的 `!isPlayerScreen && !playerOverlayVisible`）。
 * - 点击整条打开播放页；右侧圆钮切换播放/暂停。
 *
 * 封面：[coverUrl] 为空时用 [fallbackCoverUrl]（对齐 Flutter 用取色器缓存的当前封面兜底）。
 */
@Composable
fun MiniPlayerBar(
    title: String,
    artist: String,
    coverUrl: String,
    fallbackCoverUrl: String,
    isPlaying: Boolean,
    currentTime: Double,
    duration: Double,
    visible: Boolean,
    onOpenPlayer: () -> Unit,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            animationSpec = tween(durationMillis = AppDuration.overlay),
            initialOffsetY = { it * 2 },
        ),
        exit = slideOutVertically(
            animationSpec = tween(durationMillis = AppDuration.overlay),
            targetOffsetY = { it * 2 },
        ),
        modifier = modifier,
    ) {
        val openInteraction = remember { MutableInteractionSource() }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.background)
                .padding(top = 10.dp, bottom = 6.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // 顶部投影：对齐 Flutter `BoxShadow(color: Colors.black, offset: Offset(0, -4), blurRadius: 16)`
                    .dropShadow(
                        shape = RectangleShape,
                        shadow = Shadow(
                            radius = AppShadow.blur2xl,
                            color = AppColors.black,
                            offset = DpOffset(x = 0.dp, y = AppShadow.miniPlayerOffsetY),
                        ),
                    )
                    .background(AppColors.surfaceLight.copy(alpha = 0.94f))
                    .clickable(
                        interactionSource = openInteraction,
                        indication = null,
                        onClick = onOpenPlayer,
                    ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(AppSize.miniPlayerHeight),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(modifier = Modifier.width(16.dp))
                    MiniPlayerCover(url = coverUrl.ifEmpty { fallbackCoverUrl })
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.Start,
                    ) {
                        MovingText(
                            text = title,
                            threshold = 15,
                            style = androidx.compose.ui.text.TextStyle(
                                color = Color.White,
                                fontSize = AppFontSize.size17,                                fontWeight = FontWeight.SemiBold,
                            ),
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = artist,
                            color = AppColors.white38,
                            fontSize = AppFontSize.base,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    MiniPlayPauseButton(isPlaying = isPlaying, onTogglePlay = onTogglePlay)
                    Spacer(modifier = Modifier.width(16.dp))
                }
                MiniProgressBar(position = currentTime, duration = duration)
            }
        }
    }
}

/** 封面：62dp 圆角 14，加载失败显示音符占位（对齐 Flutter `_Cover`） */
@Composable
private fun MiniPlayerCover(url: String) {
    Box(
        modifier = Modifier
            .size(AppSize.miniPlayerHeight)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.04f)),
        contentAlignment = Alignment.Center,
    ) {
        // 占位始终垫底：URL 为空或图片加载失败时可见（对齐 Flutter `errorBuilder` 音符占位）
        MusicNotePlaceholder(modifier = Modifier.size(28.dp))
        if (url.isNotEmpty()) {
            // 图片加载：Coil（对应 Flutter Image.network 的缓存行为）
            NetworkCoverImage(
                url = url,
                modifier = Modifier.size(AppSize.miniPlayerHeight),
            )
        }
    }
}

/** 网络封面图：加载中/失败均显示底色占位（对齐 Flutter `errorBuilder`） */
@Composable
private fun NetworkCoverImage(url: String, modifier: Modifier = Modifier) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

/** 音符占位图标：核心图标集不含 `MusicNote`，自绘一个八分音符剪影 */
@Composable
private fun MusicNotePlaceholder(modifier: Modifier = Modifier, tint: Color = Color.White.copy(alpha = 0.15f)) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.10f
        // 符干
        drawLine(
            color = tint,
            start = androidx.compose.ui.geometry.Offset(w * 0.68f, h * 0.10f),
            end = androidx.compose.ui.geometry.Offset(w * 0.68f, h * 0.70f),
            strokeWidth = stroke,
        )
        // 符头
        drawCircle(color = tint, radius = w * 0.20f, center = androidx.compose.ui.geometry.Offset(w * 0.48f, h * 0.72f))
        // 符尾
        drawLine(
            color = tint,
            start = androidx.compose.ui.geometry.Offset(w * 0.68f, h * 0.10f),
            end = androidx.compose.ui.geometry.Offset(w * 0.94f, h * 0.22f),
            strokeWidth = stroke,
        )
    }
}

/** 播放/暂停圆钮：50dp，white12 底，24dp 图标 */
@Composable
private fun MiniPlayPauseButton(isPlaying: Boolean, onTogglePlay: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(AppSize.playCircle)
            .clip(RoundedCornerShape(percent = 50))
            .background(AppColors.white12)
            .clickable(interactionSource = interaction, indication = null, onClick = onTogglePlay),
        contentAlignment = Alignment.Center,
    ) {
        if (isPlaying) {
            PauseIcon(modifier = Modifier.size(24.dp))
        } else {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** 进度条：4dp 高，左对齐按比例填充（white @ 0.25），轨道透明 */
@Composable
private fun MiniProgressBar(position: Double, duration: Double) {
    val fraction = if (duration > 0) (position / duration).coerceIn(0.0, 1.0) else 0.0
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.toFloat())
                .fillMaxHeight()
                .background(Color.White.copy(alpha = 0.25f)),
        )
    }
}
