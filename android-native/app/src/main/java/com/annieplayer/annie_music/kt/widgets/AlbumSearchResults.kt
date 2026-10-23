package com.annieplayer.annie_music.kt.widgets

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import com.annieplayer.annie_music.kt.data.model.Album
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.sources.buildAlbumCoverUrl
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing

/** 入场动画上移距离（对齐 Flutter `Transform.translate(offset: Offset(0, 25 * (1 - t)))`） */
private val AlbumSearchLiftDistance = 25.dp

/** 专辑卡片信息区固定高度（RN AlbumCard：`CARD_HEIGHT = COVER_SIZE + 80`） */
private val AlbumSearchCardInfoHeight = 80.dp

/** 封面容器内边距（RN AlbumCard：`padding: EdgeInsets.fromLTRB(6, 6, 6, 0)`） */
private val AlbumSearchCoverGutter = 6.dp

/**
 * 专辑搜索结果（1:1 移植 Flutter `lib/widgets/album_search_results.dart`，卡片对齐 RN AlbumCard）。
 *
 * 结构：顶部居中当前平台名（11sp）-> 双列网格专辑卡片（底部留白 120）。
 * 加载中显示 [SkeletonLoader]；结果为空时：搜索词为空则什么都不显示，否则居中「未找到结果」。
 *
 * 入场动画严格对齐 Flutter：**400ms + easeOutCubic**，透明度 0->1 且同时向上位移
 * 25dp -> 0（`TweenAnimationBuilder` + `Transform.translate` 的等价物），搜索词变化时重放。
 *
 * 卡片几何对齐 RN：
 * - `CARD_WIDTH = (屏宽 - 8*2 - 10) / 2`（用 BoxWithConstraints 的实际可用宽替代 MediaQuery）
 * - `CARD_HEIGHT = COVER_SIZE + 80`，`COVER_SIZE = CARD_WIDTH - 12`
 * - 网格 `childAspectRatio = CARD_WIDTH / CARD_HEIGHT`（Compose 侧用卡片自身 aspectRatio 表达）
 *
 * 说明：Dart 用 `FutureBuilder` + 可空 Future 表达三种状态；Kotlin 侧改为显式
 * [isLoading] + 非空列表（与 [SingerSearchResults] 的约定一致）。
 */
@Composable
fun AlbumSearchResults(
    albums: List<Album>,
    query: String,
    source: MusicPlatform,
    onAlbumPress: (Album) -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
) {
    if (isLoading) {
        SkeletonLoader(modifier = modifier)
        return
    }

    // 空结果：搜索词为空 -> 不显示；否则提示
    if (albums.isEmpty()) {
        if (query.isEmpty()) return
        AlbumSearchEmptyHint(modifier = modifier)
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
    val liftPx = with(LocalDensity.current) { AlbumSearchLiftDistance.toPx() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = progress.value
                translationY = liftPx * (1f - progress.value)
            },
    ) {
        // 当前平台（专辑搜索为单源，标出来更清楚）
        Text(
            text = source.label,
            modifier = Modifier.fillMaxWidth().padding(top = AppSpacing.sp2, bottom = AppSpacing.sp2),
            color = AppColors.whiteA20,
            fontSize = AppFontSize.xs,
            textAlign = TextAlign.Center,
        )

        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
        ) {
            val gap = AppSpacing.sp10
            val edge = AppSpacing.sp8
            val cardWidth = (maxWidth - edge * 2 - gap) / 2
            val cardHeight = cardWidth - AlbumSearchCoverGutter * 2 + AlbumSearchCardInfoHeight
            val cardRatio = cardWidth / cardHeight

            val gridState = rememberLazyGridState()
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = gridState,
                modifier = Modifier.fillMaxSize().shortScrollbar(gridState),
                contentPadding = PaddingValues(
                    start = edge,
                    top = AppSpacing.sp4,
                    end = edge,
                    bottom = AppSpacing.listBottomInset,
                ),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                items(items = albums, key = { it.albumid }) { album ->
                    AlbumSearchCard(album = album, cardRatio = cardRatio, onPress = onAlbumPress)
                }
            }
        }
    }
}

/**
 * 专辑卡片（对齐 RN AlbumCard：圆角 12、方形封面、名称 / 歌手 / 曲目数 / 发行时间）。
 *
 * 封面加载失败或地址为空时显示音符占位（对齐 Flutter `_imgFailed` 切 errorBuilder 的行为）。
 */
@Composable
private fun AlbumSearchCard(
    album: Album,
    cardRatio: Float,
    onPress: (Album) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 封面加载失败标记（按专辑 id 记忆，避免网格复用后误用失败态）
    var coverFailed by remember(album.albumid) { mutableStateOf(false) }
    val coverUrl = buildAlbumCoverUrl(album)
    val showFallback = coverFailed || coverUrl.isEmpty()

    Column(
        modifier = modifier
            .aspectRatio(cardRatio)
            .clip(RoundedCornerShape(AppRadius.card))
            .background(AppColors.whiteA03)
            .clickableRipple { onPress(album) },
    ) {
        // ── 封面：1:1、圆角 8、占位底色 ──
        Box(
            modifier = Modifier
                .padding(
                    start = AlbumSearchCoverGutter,
                    top = AlbumSearchCoverGutter,
                    end = AlbumSearchCoverGutter,
                )
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(AppRadius.r8))
                .background(AppColors.whiteA03),
            contentAlignment = Alignment.Center,
        ) {
            if (showFallback) {
                MusicNoteGlyph(modifier = Modifier.size(40.dp), tint = AppColors.white10)
            } else {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    onState = { state ->
                        // 加载失败 -> 显示占位音符（延后由状态驱动重绘，等价 Dart errorBuilder 分支）
                        if (state is AsyncImagePainter.State.Error) coverFailed = true
                    },
                )
            }
        }

        // ── 信息区：名称 / 歌手 / 曲目数 + 发行时间 ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.sp8, vertical = AppSpacing.sp8),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = album.name,
                color = AppColors.white,
                fontSize = AppFontSize.size13,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp2))
            Text(
                text = album.artist,
                color = AppColors.whiteA40,
                fontSize = AppFontSize.xs,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp4))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MusicNoteGlyph(modifier = Modifier.size(10.dp), tint = AppColors.whiteA20)
                Spacer(modifier = Modifier.width(AppSpacing.sp4))
                Text(
                    text = "${album.musiccnt}首",
                    color = AppColors.white30,
                    fontSize = AppFontSize.size10,
                )
                if (album.pub.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(AppSpacing.sp8))
                    Text(
                        text = album.pub,
                        modifier = Modifier.weight(1f),
                        color = AppColors.white70,
                        fontSize = AppFontSize.size10,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 空结果提示（RN: flex 居中，0.3 白 14px） */
@Composable
private fun AlbumSearchEmptyHint(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "未找到结果", color = AppColors.white30, fontSize = AppFontSize.base)
    }
}
