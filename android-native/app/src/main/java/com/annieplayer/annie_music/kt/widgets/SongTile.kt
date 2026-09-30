package com.annieplayer.annie_music.kt.widgets

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import com.annieplayer.annie_music.kt.data.model.DownloadStatus
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppSize

// ---- RN 原值色（AppColors 未覆盖的低透明白叠加，按 RN rgba 原值换算）----









/**
 * 歌曲行（1:1 移植 Flutter `lib/widgets/song_tile.dart`）。
 *
 * 交互：
 * - 点击 → 多选模式下切换选中，否则播放。
 * - 长按 → 进入多选并选中该行（未选中时）。
 * - 右侧下载按钮：下载中显示 900ms 旋转环，否则显示下载图标。
 *
 * 视觉：
 * - 选中底色 `primary @ 0.08`；播放中底色 `primary @ 0.06`，标题变主色。
 * - 播放中封面压暗到 0.6 并叠加白色播放三角。
 * - 第三行 meta：`时长 · 最高音质 · 源名`（音质标签提亮）。
 *
 * 说明：Compose 无 Material 的 `Icons.Default.CheckBox` 轻量等价物（在 extended 里），
 * 这里用 `CheckSquareIcon` 自绘，形状对齐 Flutter `Icons.check_box`（圆角方块 + 勾）。
 */
@Composable
fun SongTile(
    song: Song,
    onPlay: (Song) -> Unit,
    onDownload: (Song) -> Unit,
    modifier: Modifier = Modifier,
    onToggleSelect: ((Song) -> Unit)? = null,
    selected: Boolean = false,
    isActive: Boolean = false,
    sourceLabel: String? = null,
    selectionMode: Boolean = false,
    downloadStatus: DownloadStatus? = null,
) {
    // 封面加载失败的歌曲 id（避免列表复用后误用失败态）
    var failedCoverId by remember { mutableStateOf<String?>(null) }

    // 长按触感（对齐 Flutter `HapticFeedback.mediumImpact()`）
    val haptic = LocalHapticFeedback.current

    val isDownloading = downloadStatus == DownloadStatus.downloading ||
        downloadStatus == DownloadStatus.pending

    val cardColor = when {
        selected -> AppColors.primaryA08
        isActive -> AppColors.primaryA06
        else -> Color.Transparent
    }

    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ── 卡片主体（点击播放 / 长按多选） ──
        SongTileCard(
            modifier = Modifier.weight(1f),
            song = song,
            selected = selected,
            isActive = isActive,
            cardColor = cardColor,
            coverFailed = failedCoverId == song.id,
            onCoverError = { failedCoverId = song.id },
            sourceLabel = sourceLabel,
            onTap = { if (selectionMode) onToggleSelect?.invoke(song) else onPlay(song) },
            onLongPress = if (onToggleSelect == null || selected) {
                null
            } else {
                {
                    // 长按进入多选前先触发触感（对齐 Flutter `HapticFeedback.mediumImpact()`）
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggleSelect.invoke(song)
                }
            },
        )

        // ── 下载按钮 ──
        Box(
            modifier = Modifier
                .clickableRipple { onDownload(song) }
                .padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isDownloading) {
                DownloadRing()
            } else {
                DownloadIconPlaceholder(modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** 卡片主体：勾选框 + 封面 + 三行文本 */
@Composable
private fun SongTileCard(
    song: Song,
    selected: Boolean,
    isActive: Boolean,
    cardColor: Color,
    coverFailed: Boolean,
    onCoverError: () -> Unit,
    sourceLabel: String?,
    onTap: () -> Unit,
    onLongPress: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(cardColor)
            .searchTileCombinedClickableRipple(onTap = onTap, onLongClick = onLongPress)
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            CheckSquareIcon(modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
        }

        // ── 封面 44dp / 圆角 8 ──
        Box(
            modifier = Modifier
                .size(AppSize.rowThumb)
                .clip(RoundedCornerShape(8.dp))
                .background(AppColors.whiteA03),
            contentAlignment = Alignment.Center,
        ) {
            if (coverFailed || song.cover.isEmpty()) {
                MusicNoteGlyph(
                    modifier = Modifier.size(18.dp),
                    tint = AppColors.whiteA15,
                )
            } else {
                AsyncImage(
                    model = song.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = if (isActive) 0.6f else 1f,
                    modifier = Modifier.fillMaxSize(),
                    onState = { state ->
                        // 加载失败 → 切占位音符（等价 Dart `errorBuilder`；原先漏接，坏图会显示空白）
                        if (state is AsyncImagePainter.State.Error) onCoverError()
                    },
                )
            }
            if (isActive) {
                PlayTriangle(modifier = Modifier.size(16.dp))
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = song.title,
                color = if (isActive) AppColors.primary else Color.White,
                fontSize = AppFontSize.base,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artist,
                color = AppColors.textTertiary,
                fontSize = AppFontSize.sm,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            SongTileMetaLine(song = song, sourceLabel = sourceLabel)
        }

        Spacer(modifier = Modifier.width(4.dp))
    }
}

/** 第三行 meta：`时长 · 最高音质 · 源名`（如 `3:34 · FLAC · 酷我`） */
@Composable
private fun SongTileMetaLine(song: Song, sourceLabel: String?) {
    val parts = remember(song.duration, song.qualities, sourceLabel) {
        buildList<Pair<String, Boolean>> {
            if (song.duration > 0) add(formatDuration(song.duration) to false)
            topQualityLabel(song.qualities)?.let { add(it to true) }
            if (!sourceLabel.isNullOrEmpty()) add(sourceLabel to false)
        }
    }
    if (parts.isEmpty()) return

    Text(
        // 必须显式给 fontSize：否则行高会从默认样式继承（16sp），比 Flutter 的 10sp 高
        fontSize = AppFontSize.size10,
        text = buildAnnotatedString {
            parts.forEachIndexed { i, (label, isQuality) ->
                if (i > 0) {
                    withStyle(SpanStyle(color = AppColors.primaryA60, fontSize = AppFontSize.size10)) {
                        append(" · ")
                    }
                }
                if (isQuality) {
                    withStyle(
                        SpanStyle(
                            color = AppColors.indigo300A95,
                            fontSize = AppFontSize.size10,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    ) { append(label) }
                } else {
                    withStyle(SpanStyle(color = AppColors.primaryA60, fontSize = AppFontSize.size10)) {
                        append(label)
                    }
                }
            }
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** `秒 -> m:ss`（对齐 Flutter `_formatDuration`） */
private fun formatDuration(seconds: Int): String =
    "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

/** 取最高音质短标签（HiRes / FLAC / 320K / 128K）—— 对齐 Flutter `_topQualityLabel` */
private fun topQualityLabel(qualities: List<Quality>): String? {
    if (qualities.isEmpty()) return null
    val order = listOf(Quality.hires, Quality.flac, Quality.k320k, Quality.k128k)
    for (q in order) {
        if (q !in qualities) continue
        return when (q) {
            Quality.hires -> "HiRes"
            Quality.flac -> "FLAC"
            Quality.k320k -> "320K"
            Quality.k128k -> "128K"
        }
    }
    return null
}

/**
 * 卡片主体点击：与 `clickableRipple` 同一套反馈（白 24% 涟漪，经外层 `clip` 贴合圆角），
 * 但保留「点击 + 长按」双语义（长按进入多选是搜索页不能退化的交互，故不能直接用单击版）。
 * 替换原 `combinedClickableNoRipple`：原先点击无任何反馈，与页面其它点击不一致。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.searchTileCombinedClickableRipple(
    onTap: () -> Unit,
    onLongClick: (() -> Unit)?,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this.combinedClickable(
        interactionSource = interaction,
        indication = ripple(color = AppColors.white24),
        onClick = onTap,
        onLongClick = onLongClick,
    )
}
