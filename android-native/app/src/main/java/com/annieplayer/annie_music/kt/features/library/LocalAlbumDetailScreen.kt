package com.annieplayer.annie_music.kt.features.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import com.annieplayer.annie_music.kt.core.compose.activityViewModel
import com.annieplayer.annie_music.kt.core.state.FavoriteViewModel
import com.annieplayer.annie_music.kt.data.local.LocalLibraryStore
import com.annieplayer.annie_music.kt.data.local.LocalTrackMeta
import com.annieplayer.annie_music.kt.data.local.albumGroupKey
import com.annieplayer.annie_music.kt.data.local.localAlbumDateOf
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.CoverPaletteBackdrop
import com.annieplayer.annie_music.kt.widgets.DetailHeartButton
import com.annieplayer.annie_music.kt.widgets.DetailPlayAllPill
import com.annieplayer.annie_music.kt.widgets.MusicNoteGlyph
import com.annieplayer.annie_music.kt.widgets.clickableRipple
import com.annieplayer.annie_music.kt.widgets.rememberCoverPalette
import com.annieplayer.annie_music.kt.widgets.shortScrollbar
import java.util.Locale

/**
 * **专辑详情页**（点专辑列表 / 歌手详情里的专辑进入）。
 *
 * 逐项照 `AlbumDetailSheet`：
 * - 外层：遮罩 + 满屏面板；背景 [CoverPaletteBackdrop] **跟封面取色**
 *   （专辑作品页跟封面走，这一点和"歌手/专辑列表不变色"是刻意区分）；
 * - 返回条：`SingerDetailHeader` 同款（chevron 22 + 标题 17sp + 右侧动作）；
 * - Hero：封面 `fillMaxWidth(0.58f)` 方图 + 标题 **20sp Bold** + 艺术家 15sp `whiteA50`
 *   （可点，压一层歌手详情）+ 元信息 13sp `whiteA40` + 白底「播放」胶囊；
 * - 列表：直接铺在封面取色背景上（无玻璃底）+ 多碟插「CD n」+ 曲目行（序号列、40dp 封面、⋮）。
 *
 * 排序按用户要求：`CD 号 → 曲目号 → 标题`；**没有曲目号的排在最后、按标题字母序**。
 */
@Composable
fun LocalAlbumDetailScreen(
    albumKey: String,
    tracks: List<LocalTrackMeta>,
    currentSongId: String?,
    isTop: Boolean,
    onClose: () -> Unit,
    onPlayAt: (List<LocalTrackMeta>, Int) -> Unit,
    onMore: (LocalTrackMeta) -> Unit,
    onOpenArtist: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** 让宿主补抽封面（这些歌可能从没在主页滚到过，磁盘上还没有图） */
    onRequestCover: (String) -> Unit = {},
) {
    val members = remember(tracks, albumKey) { tracks.filter { albumGroupKey(it) == albumKey } }
    val ordered = remember(members) { sortAlbumTracks(members) }
    val heroCover = remember(ordered) {
        // 与 groupTracks 同一套兜底：记录的 coverPath 可能是 null（按需抽），磁盘上其实已有图
        ordered.firstNotNullOfOrNull { t -> t.coverPath ?: LocalLibraryStore.existingCover(t.path) }
    }
    // 详情页打开时把这张专辑的封面补抽一遍（限 80 首，后台 2 并发，不会卡）
    LaunchedEffect(ordered) { ordered.take(80).forEach { onRequestCover(it.path) } }
    val title = remember(albumKey) { albumKey.substringBefore(" · ") }
    val subtitle = remember(ordered) { ordered.firstOrNull()?.artistDisplay.orEmpty() }
    val metaLine = remember(ordered) { albumMetaLine(ordered) }
    // 与流媒体 `AlbumDetailSheet` 完全一致：整页配色（背景染色 + 正在播放主色）都跟随专辑封面取色
    val heroCoverUrl = heroCover?.let { "file://$it" }
    val palette = rememberCoverPalette(heroCoverUrl)

    // 收藏：键直接用曲库算出来的 albumKey（`albumGroupKey` 用的是 albumArtist，
    // 与 `FavoriteKeys.album` 拼的 artistDisplay 不一定相同，不能自己重算）
    val favoriteViewModel: FavoriteViewModel = activityViewModel()
    val favoriteSnapshot by favoriteViewModel.snapshot.collectAsState()
    val favorited = remember(favoriteSnapshot, albumKey) {
        favoriteViewModel.isAlbumFavorite(albumKey)
    }

    // 出栈由 AppShell 的全局 PAGE 处理器统一做

    Box(modifier = modifier.fillMaxSize()) {
        // 专辑作品页跟封面取色（与流媒体专辑详情一致）；渲染走 LunaBeat 那套（无 sheen）
        CoverPaletteBackdrop(
            coverUrl = heroCoverUrl,
            palette = palette,
            modifier = Modifier.fillMaxSize(),
        )
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            LocalDetailTopBar(
                title = "专辑详情",
                count = "${ordered.size} 首",
                onClose = onClose,
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = AppSpacing.sp12, vertical = AppSpacing.sp8),
            ) {
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().shortScrollbar(listState),
                    contentPadding = PaddingValues(bottom = AppSpacing.listBottomInset),
                ) {
                    // ── Hero ──
                    item(key = "hero") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = AppSpacing.sp24, vertical = AppSpacing.sp20),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.58f)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(AppRadius.panel)),
                                contentAlignment = Alignment.Center,
                            ) {
                                var coverFailed by remember(heroCover) { mutableStateOf(false) }
                                if (heroCover == null || coverFailed) {
                                    MusicNoteGlyph(
                                        modifier = Modifier.size(AppIconSize.icon36),
                                        tint = AppColors.textDisabled,
                                    )
                                } else {
                                    AsyncImage(
                                        model = "file://$heroCover",
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        onError = { coverFailed = true },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(AppSpacing.sp16))
                            Text(
                                text = title,
                                color = AppColors.textPrimary,
                                fontSize = AppFontSize.size20,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (subtitle.isNotBlank()) {
                                Spacer(modifier = Modifier.height(AppSpacing.sp4))
                                Text(
                                    text = subtitle,
                                    color = AppColors.whiteA50,
                                    fontSize = AppFontSize.size15,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(AppRadius.r6))
                                        .clickableRipple { onOpenArtist(subtitle) }
                                        .padding(horizontal = AppSpacing.sp8, vertical = AppSpacing.sp2),
                                )
                            }
                            if (metaLine.isNotBlank()) {
                                Spacer(modifier = Modifier.height(AppSpacing.sp4))
                                Text(
                                    text = metaLine,
                                    color = AppColors.whiteA40,
                                    fontSize = AppFontSize.size13,
                                    textAlign = TextAlign.Center,
                                )
                            }
                            Spacer(modifier = Modifier.height(AppSpacing.sp16))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                DetailHeartButton(
                                    selected = favorited,
                                    onClick = {
                                        favoriteViewModel.toggleAlbum(
                                            platform = MusicPlatform.local,
                                            albumId = null,
                                            name = title,
                                            artist = subtitle,
                                            cover = heroCoverUrl.orEmpty(),
                                            paths = ordered.map { it.path },
                                            key = albumKey,
                                        )
                                    },
                                )
                                Spacer(modifier = Modifier.width(AppSpacing.sp16))
                                DetailPlayAllPill(
                                    onClick = { if (ordered.isNotEmpty()) onPlayAt(ordered, 0) },
                                    enabled = ordered.isNotEmpty(),
                                )
                            }
                        }
                    }

                    // ── 曲目：多碟插「CD n」（照 AlbumDetailBody）──
                    val multiDisc = ordered.mapNotNull { it.disc }.distinct().size > 1
                    itemsIndexed(ordered, key = { _, t -> "t:${t.path}" }) { index, t ->
                        val prev = ordered.getOrNull(index - 1)
                        if (multiDisc && (prev == null || prev.disc != t.disc)) {
                            Text(
                                text = "CD ${t.disc ?: 1}",
                                color = AppColors.textTertiary,
                                fontSize = AppFontSize.size13,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(
                                    horizontal = AppSpacing.sp16,
                                    vertical = AppSpacing.sp10,
                                ),
                            )
                        }
                        LocalSongRow(
                            meta = t,
                            index = index,
                            isActive = currentSongId == t.id,
                            // 专辑详情：正在播放用**封面取色的主色**（与流媒体 AlbumDetailSongRow 的
                            // palette.readablePrimary() 一致），而不是单栏列表那种中性灰
                            playingTint = palette.readablePrimary(),
                            onPlay = { onPlayAt(ordered, index) },
                            onMore = { onMore(t) },
                            // 有曲目号就显示曲目号，没有就退化成行号
                            trackLabel = t.track?.toString().orEmpty(),
                            // 专辑页不放行内封面（与流媒体 AlbumDetailSongRow 一致）
                            showCover = false,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Hero 元信息：`2024-03-08 · 12 首 · 3.4 GB · FLAC 24bit 96kHz`（照 `albumDetailMetaLine`）。
 *
 * 位深/采样率取该专辑里**最高的一首**（同专辑大多同规格，取最高的那首最有代表性）。
 */private fun albumMetaLine(tracks: List<LocalTrackMeta>): String {
    if (tracks.isEmpty()) return ""
    val parts = ArrayList<String>(4)
    // 发行日期（不只是年）：与专辑卡、专辑列表排序同一条规范化规则（`normalizeReleaseDate`）
    localAlbumDateOf(tracks)?.let { parts += it }
    parts += "${tracks.size} 首"
    val bytes = tracks.sumOf { it.size }
    if (bytes > 0) {
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        parts += if (mb >= 1024) String.format(Locale.US, "%.1f GB", mb / 1024.0)
        else String.format(Locale.US, "%.0f MB", mb)
    }
    val best = tracks.maxByOrNull { it.bitDepth * 1_000_000L + it.sampleRate }
    if (best != null && (best.bitDepth > 0 || best.sampleRate > 0)) {
        val spec = listOfNotNull(
            best.qualityChip.takeIf { best.lossless },
            best.formatText.takeIf { it.isNotEmpty() },
        ).joinToString(" ")
        if (spec.isNotBlank()) parts += spec
    }
    return parts.joinToString(" · ")
}
