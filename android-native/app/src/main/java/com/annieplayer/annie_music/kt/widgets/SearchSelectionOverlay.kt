package com.annieplayer.annie_music.kt.widgets

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing

/**
 * 搜索多选浮层（1:1 移植 Flutter `lib/widgets/search_selection_overlay.dart`）。
 *
 * 关键行为（必须保留）：
 * - 隐藏时**整棵子树移除**（Flutter 返回 `SizedBox.shrink()`，无退场动画）。
 * - 遮罩 `Colors.black54`（[AppColors.black54]），点击关闭。
 * - 底部面板：`surface` 底色、顶部圆角 20（[AppRadius.r20]）、最大高度 400dp、
 *   底部内边距 100dp（[AppSpacing.sheetBottomInset]，避开迷你播放条）。
 * - 抓手 36x4（`white @0.15`、圆角 2）；头部文案 `待下载歌曲 (n)` 16sp/bold 白 + 关闭图标 22dp white70。
 * - 空态固定高 120dp（[AppSize.selectionListHeight]）显示 `暂无选中歌曲`；
 *   非空为可滚动列表（行高 60dp：封面 40 + 标题/歌手 + 右移移除按钮）。
 *
 * 状态一律由调用方以显式参数传入（对应 Flutter 的 `searchSelectionProvider`：
 * `overlayVisible` → [visible]、`selectedSongs` → [songs]、`removeSong(id)` → [onRemove]）。
 * 本组件用 `Box(fillMaxSize)` 覆盖层实现（等价 Flutter 在 Stack 里的 `Positioned.fill`），
 * 在父级 Stack 中应最后绘制（最顶层）。
 */
@Composable
fun SearchSelectionOverlay(
    visible: Boolean,
    songs: List<Song>,
    onClose: () -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Flutter: if (!visible) return SizedBox.shrink()
    if (!visible) return

    Box(modifier = modifier.fillMaxSize()) {
        // ── 遮罩 ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AppColors.black54)
                .clickableNoRipple(onClose),
        )

        // ── 底部面板 ──
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // Flutter: BoxConstraints(maxHeight: 400)
                .heightIn(max = 400.dp)
                .background(
                    color = AppColors.surface,
                    shape = RoundedCornerShape(topStart = AppRadius.r20, topEnd = AppRadius.r20),
                )
                .padding(bottom = AppSpacing.sheetBottomInset),
            // Flutter Column(mainAxisSize: MainAxisSize.min)：面板随内容收紧，最多 400dp
            ) {
            // 抓手
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = AppSpacing.sp12)
                    .size(width = 36.dp, height = 4.dp)
                    .background(AppColors.whiteA15, RoundedCornerShape(AppRadius.r2)),
            )
            // 头部
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.sp16, vertical = AppSpacing.sp12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "待下载歌曲 (${songs.size})",
                    color = AppColors.white,
                    fontSize = AppFontSize.lg,
                    fontWeight = AppFontWeight.bold,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .clickableNoRipple(onClose)
                        .padding(AppSpacing.sp4),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        tint = AppColors.white70,
                        modifier = Modifier.size(AppIconSize.icon22),
                    )
                }
            }
            // 列表 / 空态
            if (songs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(AppSize.selectionListHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "暂无选中歌曲",
                        color = AppColors.white30,
                        fontSize = AppFontSize.base,
                    )
                }
            } else {
                LazyColumn(
                    // Flexible（loose）：内容少时面板收紧，内容多时最多占满剩余高度
                    modifier = Modifier.weight(weight = 1f, fill = false),
                    contentPadding = PaddingValues(bottom = AppSpacing.sp16),
                ) {
                    items(items = songs, key = { it.id }) { song ->
                        SearchSelectionSongRow(song = song, onRemove = onRemove)
                    }
                }
            }
        }
    }
}

/** 已选歌曲行（对齐 Flutter `_SongRow`）：高 60dp、封面 40dp 圆角 6、标题 14sp/500、歌手 11sp。 */
@Composable
private fun SearchSelectionSongRow(song: Song, onRemove: (String) -> Unit) {
    // 封面加载失败的歌曲 id（避免列表复用后误用失败态）
    var coverFailed by remember(song.id) { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(AppSize.detailRowHeight)
            .padding(horizontal = AppSpacing.sp16, vertical = AppSpacing.sp10),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 封面 40dp / 圆角 6（Flutter: ClipRRect 40x40）
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(AppRadius.r6))
                .background(AppColors.whiteA03),
            contentAlignment = Alignment.Center,
        ) {
            if (coverFailed || song.cover.isEmpty()) {
                MusicNoteGlyph(
                    modifier = Modifier.size(AppIconSize.icon16),
                    tint = AppColors.white24,
                )
            } else {
                AsyncImage(
                    model = song.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    onError = { coverFailed = true },
                )
            }
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp10))
        Column(
            modifier = Modifier.weight(1f),
            // Flutter: mainAxisAlignment.center
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = song.title,
                color = AppColors.white,
                fontSize = AppFontSize.base,
                fontWeight = AppFontWeight.medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artist,
                color = AppColors.white38,
                fontSize = AppFontSize.xs,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .clickableNoRipple { onRemove(song.id) }
                .padding(AppSpacing.sp4),
            contentAlignment = Alignment.Center,
        ) {
            SearchSelectionCancelIcon(
                modifier = Modifier.size(AppIconSize.icon20),
                color = AppColors.white30,
            )
        }
    }
}

/**
 * 取消图标（对齐 Flutter `Icons.cancel` 的实心圆 + 镂空叉；该图标不在核心图标集）。
 * 叉口用 `BlendMode.Clear` 挖空，与 `SearchBar.kt` 的 `ClearCircleIcon` 同一手法。
 */
@Composable
private fun SearchSelectionCancelIcon(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier = modifier) {
        val r = size.minDimension / 2f
        drawCircle(color = color, radius = r)
        val inset = r * 0.40f
        val strokeW = r * 0.18f
        drawLine(
            color = Color.Black,
            start = Offset(center.x - r + inset, center.y - r + inset),
            end = Offset(center.x + r - inset, center.y + r - inset),
            strokeWidth = strokeW,
            blendMode = androidx.compose.ui.graphics.BlendMode.Clear,
        )
        drawLine(
            color = Color.Black,
            start = Offset(center.x + r - inset, center.y - r + inset),
            end = Offset(center.x - r + inset, center.y + r - inset),
            strokeWidth = strokeW,
            blendMode = androidx.compose.ui.graphics.BlendMode.Clear,
        )
    }
}
