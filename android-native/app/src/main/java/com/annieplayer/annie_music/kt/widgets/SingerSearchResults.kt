package com.annieplayer.annie_music.kt.widgets

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import com.annieplayer.annie_music.kt.data.model.Artist
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize




/**
 * 歌手搜索结果（1:1 移植 Flutter `lib/widgets/singer_search_results.dart`）。
 *
 * 结构：顶部居中平台名（11sp）→ 单列歌手行列表（底部留白 170）。
 * 加载中显示 [SkeletonLoader]；结果为空时：搜索词为空则什么都不显示，否则居中「未找到结果」。
 *
 * 入场动画严格对齐 Flutter：**400ms + easeOutCubic**，透明度 0→1 且同时向上位移
 * 25dp → 0（`TweenAnimationBuilder` + `Transform.translate` 的等价物）。
 *
 * 说明：Dart 用 `FutureBuilder` + 可空 Future 表达三种状态；Kotlin 侧改为显式
 * [isLoading] + 非空列表，语义等价且更易在 Compose 中组合。
 */
@Composable
fun SingerSearchResults(
    artists: List<Artist>,
    query: String,
    source: MusicPlatform,
    onArtistPress: (Artist) -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
) {
    if (isLoading) {
        SkeletonLoader(modifier = modifier)
        return
    }

    // 空结果：搜索词为空 → 不显示；否则占满可用高度居中提示（对齐 Flutter `Center`）
    if (artists.isEmpty()) {
        if (query.isEmpty()) return
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "未找到结果", color = AppColors.white30, fontSize = AppFontSize.base)
        }
        return
    }

    // 入场动画：400ms + easeOutCubic（key = query，搜索词变化时重放）
    val progress = remember { Animatable(0f) }
    LaunchedEffect(query) {
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = AppDuration.listFade, easing = AppEasing.easeOutCubic),
        )
    }
    val density = LocalDensity.current
    val liftPx = with(density) { 25.dp.toPx() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = progress.value
                translationY = liftPx * (1f - progress.value)
            },
    ) {
        // 当前平台（歌手搜索为单源）
        Text(
            text = source.label,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp),
            color = AppColors.white30,
            fontSize = AppFontSize.xs,
            textAlign = TextAlign.Center,
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 0.dp, top = 4.dp, end = 0.dp, bottom = 170.dp),
        ) {
            items(items = artists, key = { it.id }) { artist ->
                ArtistRow(artist = artist, onPress = onArtistPress)
            }
        }
    }
}

/**
 * 歌手行（对齐 RN：头像 56 圆形、名称 15/600、`N 首歌曲` 12/0.35）。
 * 行内边距：垂直 10 / 水平 12；头像与文字间距 14。
 */
@Composable
private fun ArtistRow(artist: Artist, onPress: (Artist) -> Unit) {
    var avatarFailed by remember(artist.id) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickableRipple { onPress(artist) }
            .padding(vertical = 10.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 头像 56dp 圆形
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(AppColors.whiteA03),
            contentAlignment = Alignment.Center,
        ) {
            if (avatarFailed || artist.picture.isEmpty()) {
                // 人像占位改为自绘线条家族（笔重 2dp 等效、圆头端点），替换 Material 实心 Person
                SingerAvatarPlaceholderGlyph(
                    modifier = Modifier.size(28.dp),
                    tint = AppColors.white10,
                )
            } else {
                AsyncImage(
                    model = artist.picture,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(56.dp),
                    onState = { state ->
                        // 加载失败 → 切占位头像（等价 Dart `errorBuilder`；原先漏接）
                        if (state is AsyncImagePainter.State.Error) avatarFailed = true
                    },
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = artist.name,
                color = Color.White,
                fontSize = AppFontSize.size15,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${artist.musicNum} 首歌曲",
                color = AppColors.textTertiary,
                fontSize = AppFontSize.sm,
            )
        }
    }
}

/**
 * 人像占位（头像兜底，自绘线条家族，仅歌手搜索结果）。
 *
 * 几何沿用详情页 [DetailPersonOutline]（头圆 + 肩弧，比例坐标），笔重 `w * 0.072f`
 * 在 28dp 栅格上 ≈ 2dp（2dp 等效）、圆头端点；替换原 `Icons.Default.Person`
 * （Material 实心人像，笔重与自绘家族不一致）。
 */
@Composable
private fun SingerAvatarPlaceholderGlyph(modifier: Modifier = Modifier, tint: Color = AppColors.white10) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.072f
        // 头
        drawCircle(
            color = tint,
            radius = w * 0.17f,
            center = Offset(w * 0.5f, h * 0.31f),
            style = Stroke(width = stroke),
        )
        // 肩
        drawArc(
            color = tint,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.16f, h * 0.50f),
            size = Size(w * 0.68f, h * 0.62f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}
