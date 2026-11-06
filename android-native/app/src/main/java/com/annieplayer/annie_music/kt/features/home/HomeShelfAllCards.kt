package com.annieplayer.annie_music.kt.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import coil.compose.AsyncImage
import com.annieplayer.annie_music.kt.data.model.HomeItem
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.clickableRipple

// ================================================================================================
// 「全部」页的卡片（首图大卡 / 网格小卡 / 共用封面与角标）
// ================================================================================================
//
// 与页面壳（`HomeShelfAllSheet.kt`）分开放：卡片是纯展示、不碰路由 / 返回 / 滚动状态，
// 单独一个文件便于和主页那套卡片（`HomeFeed.kt`）对照着改样式。
//
// 字体与配色统一对齐歌手详情的专辑卡（`SingerDetailAlbumCard`）：
// 标题 13sp SemiBold / 歌手 xs whiteA40 / 曲目数与发行日期 10sp（white30 + white70 SemiBold）。

/**
 * 首图大卡：整行 16:9，标题压在图上。
 *
 * 用 16:9 而不是 1:1：整行方图高达半屏，会把后面的网格全推出视野，反而失去「一览」的意义。
 */
@Composable
internal fun HomeShelfHeroCard(item: HomeItem, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(AppRadius.r20))
            .background(AppColors.whiteA03)
            .clickableRipple(onClick),
    ) {
        HomeShelfCover(item = item, placeholderSize = AppFontSize.size32)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.38f to Color.Transparent,
                        1f to Color(0xB3000000),
                    ),
                ),
        )
        item.badge?.takeIf { it.isNotEmpty() }?.let { badge ->
            HomeShelfBadge(
                text = badge,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(AppSpacing.sp10),
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(AppSpacing.sp16),
        ) {
            Text(
                text = item.title,
                color = AppColors.white,
                fontSize = AppFontSize.size20,
                fontWeight = AppFontWeight.bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.subtitle.isNotEmpty()) {
                Spacer(modifier = Modifier.height(AppSpacing.sp4))
                Text(
                    text = item.subtitle,
                    color = AppColors.white.copy(alpha = 0.7f),
                    fontSize = AppFontSize.size13,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 网格里的单张卡：方图 + 标题 / 歌手 /（专辑才有）曲目数 · 发行日期。 */
@Composable
internal fun HomeShelfAllTile(item: HomeItem, onClick: () -> Unit) {
    Column(modifier = Modifier.clickableRipple(onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(AppRadius.r16))
                .background(AppColors.whiteA03),
        ) {
            HomeShelfCover(item = item, placeholderSize = AppFontSize.size26)
            // 方图也要压一点底：角标落在浅色封面上时不会糊成一片
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.72f to Color.Transparent,
                            1f to Color(0x40000000),
                        ),
                    ),
            )
            item.badge?.takeIf { it.isNotEmpty() }?.let { badge ->
                HomeShelfBadge(
                    text = badge,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(AppSpacing.sp6),
                )
            }
        }
        Spacer(modifier = Modifier.height(AppSpacing.sp8))
        Text(
            text = item.title,
            color = AppColors.textPrimary,
            fontSize = AppFontSize.size13,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.subtitle.isNotEmpty()) {
            Spacer(modifier = Modifier.height(AppSpacing.sp2))
            Text(
                text = item.subtitle,
                color = AppColors.whiteA40,
                fontSize = AppFontSize.xs,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (item.count > 0 || item.pub.isNotEmpty()) {
            Spacer(modifier = Modifier.height(AppSpacing.sp4))
            HomeShelfMetaRow(item = item)
        }
    }
}

/** 曲目数 / 发行日期那一行（专辑才有；字号与配色对齐歌手详情的专辑卡）。 */
@Composable
private fun HomeShelfMetaRow(item: HomeItem) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (item.count > 0) {
            Text(text = "♪", color = AppColors.whiteA20, fontSize = AppFontSize.size10)
            Spacer(modifier = Modifier.width(AppSpacing.sp4))
            Text(
                text = "${item.count}首",
                color = AppColors.white30,
                fontSize = AppFontSize.size10,
            )
        }
        if (item.pub.isNotEmpty()) {
            if (item.count > 0) Spacer(modifier = Modifier.width(AppSpacing.sp4))
            Text(
                text = item.pub,
                color = AppColors.white70,
                fontSize = AppFontSize.size10,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 封面（含空图 / 失败占位），铺满父容器。 */
@Composable
private fun HomeShelfCover(item: HomeItem, placeholderSize: TextUnit) {
    var failed by remember(item.key) { mutableStateOf(false) }
    if (item.cover.isEmpty() || failed) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "♪", color = AppColors.whiteA15, fontSize = placeholderSize)
        }
    } else {
        AsyncImage(
            model = item.cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onError = { failed = true },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 封面角标（播放量等）。 */
@Composable
private fun HomeShelfBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(Color(0x66000000))
            .padding(horizontal = AppSpacing.sp6, vertical = AppSpacing.sp2),
    ) {
        Text(
            text = text,
            color = AppColors.white,
            fontSize = AppFontSize.size10,
        )
    }
}
