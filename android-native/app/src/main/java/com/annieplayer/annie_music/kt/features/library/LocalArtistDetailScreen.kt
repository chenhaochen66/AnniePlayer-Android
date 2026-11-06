package com.annieplayer.annie_music.kt.features.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.data.local.LocalLibraryStore
import com.annieplayer.annie_music.kt.data.local.LocalTrackMeta
import com.annieplayer.annie_music.kt.data.local.albumGroupKey
import com.annieplayer.annie_music.kt.data.local.albumYearOf
import com.annieplayer.annie_music.kt.data.local.artistGroupKeys
import com.annieplayer.annie_music.kt.data.local.localAlbumType
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.ui.theme.ColorPalette
import com.annieplayer.annie_music.kt.widgets.CoverPaletteBackdrop
import com.annieplayer.annie_music.kt.widgets.rememberCoverPalette
import com.annieplayer.annie_music.kt.widgets.DetailPlayAllPill
import com.annieplayer.annie_music.kt.widgets.shortScrollbar
import com.annieplayer.annie_music.kt.widgets.SongRowPager

/** 歌手详情里「专辑」横滑卡尺寸（照 `SingerDetailAlbumCard`：140×208） */
private val ArtistAlbumCardWidth = 140.dp
private val ArtistAlbumCardHeight = 208.dp

/** overview「歌曲」段一页几行（照流媒体 `SingerDetailPageRows`：一页 5 行，往右滑翻页） */
private const val LocalArtistPageRows = 5

/**
 * overview「歌曲」段最多放几首（= 4 页 × 5 行，照流媒体 `st.songs.take(ArtistSongsPageSize)`）；
 * 超过这个数才给「全部 ›」。
 */
private const val LocalArtistOverviewSongs = 20

/** overview「专辑」段预览的张数（照流媒体 `SingerDetailOverviewAlbumCount`） */
private const val LocalArtistOverviewAlbums = 10

/**
 * **歌手详情页**（点歌手列表里的某位弹出）。
 *
 * 逐项照 `SingerDetailSheet` 的 overview 页：
 * 1. Hero —— 正圆大头像（`fillMaxWidth(0.55f)` + `CircleShape`）+ 名字 **26sp Bold** +
 *    副标 `N 首 · M 张专辑` + 白底「播放」胶囊；
 * 2. 「歌曲」段 —— **和流媒体一样按页横滑**：[LocalArtistPageRows] 行一页、往右翻，
 *    最多 [LocalArtistOverviewSongs] 首（序号 → 等化器、40dp 封面、16sp 标题、
 *    `歌手 · 专辑` 副标题、音质角标、⋮）；只有歌数**超过**这个上限时才给「全部 ›」；
 * 3. 「专辑」段 —— **只预览最近 [LocalArtistOverviewAlbums] 张**（发行时间倒序），
 *    140×208 横滑卡（N 首 · 类型 · 年份）；同样只有张数超过预览数才给「全部 ›」。
 *
 * 「全部」进的是 `LocalArtistSongsEntry` / `LocalArtistAlbumsEntry` 两个页面（内页可排序）。
 * 背景**跟本页歌手的封面取色**（= 该歌手首曲封面）；歌手列表页走的是「跟随正在播放歌曲封面」
 * 的氛围色——两者刻意不同：详情页的颜色应该由「你正在看的是谁」决定，而不是「在放哪首歌」。
 */
@Composable
fun LocalArtistDetailScreen(
    artistName: String,
    tracks: List<LocalTrackMeta>,
    currentSongId: String?,
    isTop: Boolean,
    onClose: () -> Unit,
    onPlayAt: (List<LocalTrackMeta>, Int) -> Unit,
    onMore: (LocalTrackMeta) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenAllSongs: () -> Unit,
    onOpenAllAlbums: () -> Unit,
    modifier: Modifier = Modifier,
    /** 让宿主补抽封面（这些歌可能从没在主页滚到过，磁盘上还没有图） */
    onRequestCover: (String) -> Unit = {},
) {
    val members = remember(tracks, artistName) {
        tracks.filter { artistName in artistGroupKeys(it) }
    }
    val ordered = remember(members) { sortArtistTracks(members) }
    // 专辑按发行时间倒序（没年份的排最后），与专辑列表默认排序一致
    val albums = remember(ordered) {
        ordered.groupBy { albumGroupKey(it) }.entries
            .map { (key, list) -> key to list }
            .sortedWith { a, b ->
                val ya = albumYearOf(a.second)
                val yb = albumYearOf(b.second)
                when {
                    ya == null && yb == null -> a.first.compareTo(b.first)
                    ya == null -> 1
                    yb == null -> -1
                    else -> yb.compareTo(ya)
                }
            }
    }
    val heroCover = remember(ordered) {
        ordered.firstNotNullOfOrNull { t -> t.coverPath ?: LocalLibraryStore.existingCover(t.path) }
    }
    // 打开歌手页时补抽封面（限 80 首，避免几千首的歌手一次排满队列）
    LaunchedEffect(ordered) { ordered.take(80).forEach { onRequestCover(it.path) } }
    // overview 只预览一小部分，「全部」按钮只在装不下时才出现
    val overviewSongs = remember(ordered) { ordered.take(LocalArtistOverviewSongs) }
    val overviewAlbums = remember(albums) { albums.take(LocalArtistOverviewAlbums) }

    // 出栈由 AppShell 的全局 PAGE 处理器统一做

    Box(modifier = modifier.fillMaxSize()) {
        // ── 背景：跟随**本页这位歌手的封面**取色（LunaBeat 规则），不跟正在播放的歌曲。
        // 本地曲库没有歌手头像资源，所以「歌手封面」= 该歌手首曲封面（就是上面 Hero 头像
        // 用的那张 heroCover），侧滑 / 点进点出都稳定，不会因为切歌而变化。
        CoverPaletteBackdrop(
            coverUrl = heroCover,
            palette = rememberCoverPalette(heroCover),
            modifier = Modifier.fillMaxSize(),
        )
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            LocalDetailTopBar(
                title = "歌手详情",
                count = "${ordered.size} 首",
                onClose = onClose,
            )

            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().shortScrollbar(listState),
                contentPadding = PaddingValues(bottom = AppSpacing.listBottomInset),
            ) {
                // ── Hero：正圆头像 55% + 名字 26sp + 统计 + 播放胶囊 ──
                item(key = "hero") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AppSpacing.sp24, vertical = AppSpacing.sp20),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        LocalArtistAvatar(
                            name = artistName,
                            coverPath = heroCover,
                            size = 200.dp,
                            letterSize = AppFontSize.size34,
                            modifier = Modifier.fillMaxWidth(0.55f).aspectRatio(1f),
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.sp16))
                        Text(
                            text = artistName,
                            color = AppColors.textPrimary,
                            fontSize = AppFontSize.size26,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.sp4))
                        Text(
                            text = "${albums.size} 张专辑 · ${ordered.size} 首",
                            color = AppColors.whiteA50,
                            fontSize = AppFontSize.size13,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.sp16))
                        DetailPlayAllPill(
                            onClick = { if (ordered.isNotEmpty()) onPlayAt(ordered, 0) },
                            enabled = ordered.isNotEmpty(),
                        )
                    }
                }

                // ── 歌曲（预览一段，「全部」只在装不下时出现）──
                item(key = "songs-header") {
                    LocalSectionHeader(
                        title = "歌曲",
                        onSeeAll = if (ordered.size > LocalArtistOverviewSongs) onOpenAllSongs else null,
                    )
                }
                // ── 歌曲：和流媒体**同一个**翻页容器（页宽 / 页间距 / 页码指示都在那边）──
                item(key = "songs-pager") {
                    SongRowPager(
                        items = overviewSongs,
                        rowsPerPage = LocalArtistPageRows,
                        minHeightRows = minOf(LocalArtistPageRows, overviewSongs.size),
                        // 本页 hero 是 24dp，列表跟着对齐（容器 12 + 行内 12）
                        startInset = 12.dp,
                    ) { t, index ->
                        LocalSongRow(
                            meta = t,
                            index = index,
                            isActive = currentSongId == t.id,
                            // 单栏长列表：正在播放用中性灰（与流媒体搜索列表 `SongTile` 一致）
                            playingTint = AppColors.playingGray,
                            onPlay = {
                                onPlayAt(
                                    ordered,
                                    ordered.indexOfFirst { it.path == t.path }.coerceAtLeast(0),
                                )
                            },
                            onMore = { onMore(t) },
                            // 与流媒体 `SingerDetailSongRow` 一致：副标题是「歌手 · 专辑」
                            showAlbum = true,
                            // 「最热门」这一段是横滑分页、每页 5 行 —— 序号在这里是噪声
                            showIndex = false,
                            // ⋮ 贴到页右边界：页宽已比屏幕窄，再留 16dp 会显得缩在中间
                            trailingSpace = 0.dp,
                        )
                    }
                }

                // ── 专辑（横滑预览，「全部」只在装不下时出现）──
                if (overviewAlbums.isNotEmpty()) {
                    item(key = "albums-header") {
                        LocalSectionHeader(
                            title = "专辑",
                            onSeeAll = if (albums.size > LocalArtistOverviewAlbums) onOpenAllAlbums else null,
                        )
                    }
                    item(key = "albums-shelf") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = AppSpacing.sp16),
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sp10),
                        ) {
                            items(overviewAlbums, key = { "al:${it.first}" }) { (key, list) ->
                                LocalAlbumGridCard(
                                    name = key.substringBefore(" · "),
                                    artist = list.first().artistDisplay,
                                    coverPath = list.firstNotNullOfOrNull { it.coverPath },
                                    count = list.size,
                                    onClick = { onOpenAlbum(key) },
                                    modifier = Modifier
                                        .width(ArtistAlbumCardWidth)
                                        .height(ArtistAlbumCardHeight),
                                    year = albumYearOf(list),
                                    type = localAlbumType(list.size),
                                )
                            }
                        }
                    }
                }
                item(key = "bottom-space") { Spacer(modifier = Modifier.height(AppSpacing.sp24)) }
            }
        }
    }
}
