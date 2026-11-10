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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import com.annieplayer.annie_music.kt.data.model.DownloadStatus
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing

/**
 * 三行文本的**紧凑行高**：一屏要塞下 8 条，行盒不能按 1.2× 字号撑（会多出 ~8dp）。
 *
 * 与 [SongTile] 里的字号成对出现：标题 17sp / 第二行 13sp / 元信息 11sp。
 */
private val TitleLineHeight = 19.sp
private val ArtistLineHeight = 15.sp
private val MetaLineHeight = 13.sp

/**
 * 歌曲行（单曲搜索的单栏行）。
 *
 * 交互：
 * - 点击 → 多选模式下切换选中，否则播放。
 * - 长按 → 进入多选并选中该行（未选中时）。
 * - 右侧 ⋮：点开全局单曲操作菜单（下载 / 跳转歌手…）；下载中显示旋转环。
 *   **多选态整块换成 [SelectCheckBox]**（勾在框里、无底色），⋮ 不渲染。
 *
 * 视觉（**单栏**）：
 * - 封面 [AppSize.searchTileCover] = 44dp / 圆角 8；三行文本 **17sp / 13sp / 11sp**
 *   （标题 w600、第二行 `歌手 · 专辑`、第三行元信息）。
 * - 行高 ≈ 62dp =「文本块 ≈50 + 上下各 6dp」；363dp×800dp 的机器上列表可视区 ≈532dp，
 *   一屏能落 8 条（行距是按这个约束倒推的，改字号/留白请一起算）。
 * - 正在播放 → 与专辑详情列表同一套行样式（[playingRowBackground]：8% 圆角底 + 左侧 3dp 竖条
 *   + 标题着色 + 封面上的 [PlayingIndicator] 等化器），但**颜色是中性灰**而不是封面取色主色，
 *   避免单栏长列表整屏被彩色化。
 * - 多选选中 → `primary @ 8%` 底色（与专辑详情一致）；标记**只在行尾**的勾选框里，
 *   封面前不再插图标（插进去会让整行文字横向跳一格）。
 *
 * 勾选框用 [SelectCheckBox]（与专辑 / 歌单 / 本地曲库同一个组件），不再自带一套实心方图标。
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

    Row(
        modifier = modifier
            .fillMaxWidth()
            // 高亮底画在**整行**上（含行尾 ⋮ 区），与专辑详情「整行高亮」一致
            .clip(RoundedCornerShape(AppRadius.r8))
            .then(
                when {
                    isActive -> Modifier.playingRowBackground(AppColors.playingGray)
                    selected -> Modifier.background(AppColors.primaryA08)
                    else -> Modifier
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ── 卡片主体（点击播放 / 长按多选） ──
        SongTileCard(
            modifier = Modifier.weight(1f),
            song = song,
            selected = selected,
            isActive = isActive,
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

        // ── 行尾槽位：多选态 = 勾选框；常态 = ⋮（下载中显示旋转环） ──
        val toggle = onToggleSelect
        if (selectionMode && toggle != null) {
            Box(modifier = Modifier.padding(horizontal = AppSpacing.sp10)) {
                SelectCheckBox(checked = selected, onToggle = { toggle(song) })
            }
        } else {
            Box(
                modifier = Modifier
                    // 命中区与勾选框同位同宽（48dp）：切多选时文字区不横向跳一格
                    .minimumInteractiveComponentSize()
                    .clickableRipple { onDownload(song) }
                    .padding(AppSpacing.sp10),
                contentAlignment = Alignment.Center,
            ) {
                if (isDownloading) {
                    DownloadRing()
                } else {
                    SongMoreGlyph()
                }
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
    coverFailed: Boolean,
    onCoverError: () -> Unit,
    sourceLabel: String?,
    onTap: () -> Unit,
    onLongPress: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .searchTileCombinedClickableRipple(onTap = onTap, onLongClick = onLongPress)
            .padding(
                start = AppSpacing.sp12,
                end = AppSpacing.sp10,
                top = AppSpacing.sp6,
                bottom = AppSpacing.sp6,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ── 封面 50dp / 圆角 8 ──
        Box(
            modifier = Modifier
                .size(AppSize.searchTileCover)
                .clip(RoundedCornerShape(AppRadius.r8))
                .background(AppColors.whiteA03),
            contentAlignment = Alignment.Center,
        ) {
            if (coverFailed || song.cover.isEmpty()) {
                MusicNoteGlyph(
                    modifier = Modifier.size(20.dp),
                    tint = AppColors.whiteA15,
                )
            } else {
                AsyncImage(
                    model = song.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = if (isActive) 0.5f else 1f,
                    modifier = Modifier.fillMaxSize(),
                    onState = { state ->
                        // 加载失败 → 切占位音符（等价 Dart `errorBuilder`）
                        if (state is AsyncImagePainter.State.Error) onCoverError()
                    },
                )
            }
            if (isActive) {
                // 与专辑详情列表一致：正在播放的等化器指示器（这里叠在封面上，行内没有序号位）
                PlayingIndicator(tint = AppColors.playingGray)
            }
        }

        Spacer(modifier = Modifier.width(AppSpacing.sp14))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sp2),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = song.title,
                color = if (isActive) AppColors.playingGray else Color.White,
                fontSize = AppFontSize.size17,
                lineHeight = TitleLineHeight,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // 歌手 + 专辑：专辑跟在歌手右边（如「周杰伦 · 太阳之子」）；没有专辑名就只显示歌手
                text = listOfNotNull(
                    song.artist.takeIf { it.isNotBlank() },
                    song.album?.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                color = AppColors.textTertiary,
                fontSize = AppFontSize.size13,
                lineHeight = ArtistLineHeight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            SongTileMetaLine(song = song, sourceLabel = sourceLabel)
        }
    }
}

/**
 * 第三行 meta：`时长 · 音质标 · 源名`（如 `3:34 · FLAC · 酷我`）。
 *
 * 颜色与第二行（`歌手 · 专辑`）**完全一致**（`textTertiary`）：这两行是同一块副文本，
 * 之前给音质标单独上靛蓝、分隔符用 `primaryA60`，整行读起来像三个不同层级的信息。
 * 音质仍保留 `SemiBold` —— 权重区分够了，不需要再换色。
 */
@Composable
private fun SongTileMetaLine(song: Song, sourceLabel: String?) {
    val parts = remember(song.duration, song.qualities, song.platform, sourceLabel) {
        buildList<Pair<String, Boolean>> {
            if (song.duration > 0) add(formatDuration(song.duration) to false)
            for (label in qualityLabels(song)) add(label to true)
            if (!sourceLabel.isNullOrEmpty()) add(sourceLabel to false)
        }
    }
    if (parts.isEmpty()) return

    Text(
        // 必须显式给 fontSize / lineHeight：否则行盒会按默认样式（16sp / 1.2 倍）撑高
        fontSize = AppFontSize.xs,
        lineHeight = MetaLineHeight,
        text = buildAnnotatedString {
            parts.forEachIndexed { i, (label, isQuality) ->
                if (i > 0) {
                    withStyle(SpanStyle(color = AppColors.textTertiary, fontSize = AppFontSize.xs)) {
                        append(" · ")
                    }
                }
                if (isQuality) {
                    withStyle(
                        SpanStyle(
                            color = AppColors.textTertiary,
                            fontSize = AppFontSize.xs,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    ) { append(label) }
                } else {
                    withStyle(SpanStyle(color = AppColors.textTertiary, fontSize = AppFontSize.xs)) {
                        append(label)
                    }
                }
            }
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * `秒 -> m:ss`（对齐 Flutter `_formatDuration`）。
 *
 * **超过 100000 秒（27 小时）当毫秒处理**：历史数据里真的混进过毫秒 ——
 * 酷狗聚合接口的 `timelength_320` 是毫秒，早先几个版本直接当秒写进了 `Song.duration`，
 * 于是 `4:03` 的歌渲染成 `4052:53`。映射层已经修了，但**已收藏的 `Song` 是快照**，
 * 不会自己变；这里兜一次，老收藏就当场恢复正常，不用重新收藏。
 */
private fun formatDuration(seconds: Int): String {
    val total = if (seconds > 100_000) seconds / 1000 else seconds
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}

/**
 * 音质短标签（可多个）。
 *
 * Apple：按档位逐个列出（同一条目可能同时具备多档），顺序固定为
 * `Hi-Res → Dolby → ALAC → AAC 256`；一个都没有才不标。
 *
 * 其它平台仍然只显示「最高那一档」。
 */
private fun qualityLabels(song: Song): List<String> =
    if (song.platform == MusicPlatform.apple) {
        buildList {
            if (Quality.hires in song.qualities) add("Hi-Res")
            if (Quality.atmos in song.qualities) add("Dolby")
            if (Quality.alac in song.qualities) add("ALAC")
            if (Quality.aac256 in song.qualities) add("AAC 256")
        }
    } else {
        listOfNotNull(topQualityLabel(song.qualities))
    }

/** 取最高音质短标签（HiRes / ALAC / 320K / 128K）—— 对齐 Flutter `_topQualityLabel` */
private fun topQualityLabel(qualities: List<Quality>): String? {
    if (qualities.isEmpty()) return null
    // Atmos → ALAC 超高解析 → ALAC 无损 → AAC 256 → …：与音质选择弹窗同一套次序
    val order = listOf(
        Quality.atmos,
        Quality.hires,
        Quality.alac,
        Quality.aac256,
        Quality.flac,
        Quality.k320k,
        Quality.k128k,
    )
    for (q in order) {
        if (q !in qualities) continue
        return when (q) {
            Quality.atmos -> "ATMOS"
            Quality.alac -> "ALAC"
            Quality.aac256 -> "AAC 256"
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
