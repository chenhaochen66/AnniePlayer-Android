package com.annieplayer.annie_music.kt.features.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.data.local.LocalLibraryStore
import com.annieplayer.annie_music.kt.data.local.LocalTrackMeta
import com.annieplayer.annie_music.kt.data.local.albumGroupKey
import com.annieplayer.annie_music.kt.data.local.localAlbumDateOf
import com.annieplayer.annie_music.kt.data.local.artistGroupKeys
import com.annieplayer.annie_music.kt.data.local.localAlbumType
import com.annieplayer.annie_music.kt.data.local.localAlphabetComparator
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.CoverPaletteBackdrop
import com.annieplayer.annie_music.kt.widgets.rememberCoverPalette
import com.annieplayer.annie_music.kt.widgets.shortScrollbar

/**
 * 歌手详情的两个「全部」子页（歌曲 / 专辑）。
 *
 * 与流媒体歌手详情的 `st.tab == "song" / "album"` 子页对应：overview 只预览一段，
 * 点「全部 ›」压这两个页面中的一页（同一条 `pageStack`，返回逐层退回）。
 *
 * 两页都带排序 + 升降序，且**持久化**（`artist_songs` / `artist_albums`）：
 * 退出再进来仍按上次那套排。分页键不带歌手名 —— 全局一套偏好，不会因为换歌手跳回默认。
 */

// ── 全部歌曲 ──

/** 歌手「全部歌曲」的排序模式（每种都能切升降序） */
private enum class ArtistSongSort(val label: String, val defaultAscending: Boolean) {
    byTitle("标题", true),
    byAlbum("专辑", true),
    byDuration("时长", false),
    ;

    fun next(): ArtistSongSort = entries[(ordinal + 1) % entries.size]
}

private const val PAGE_ARTIST_SONGS = "artist_songs"

/**
 * **全部歌曲**（歌手详情 → 「歌曲」段的「全部 ›」）。
 *
 * 与 overview 同一套行视觉（[LocalSongRow]：序号/等化器、40dp 封面、音质角标、⋮），
 * 差别只有：这里是完整列表 + 排序行。
 */
@Composable
fun LocalArtistSongsScreen(
    artistName: String,
    tracks: List<LocalTrackMeta>,
    currentSongId: String?,
    isTop: Boolean,
    onClose: () -> Unit,
    onPlayAt: (List<LocalTrackMeta>, Int) -> Unit,
    onMore: (LocalTrackMeta) -> Unit,
    modifier: Modifier = Modifier,
    onRequestCover: (String) -> Unit = {},
) {
    val members = remember(tracks, artistName) {
        tracks.filter { artistName in artistGroupKeys(it) }
    }
    val pref = rememberSortPreference(
        page = PAGE_ARTIST_SONGS,
        defaultMode = ArtistSongSort.byTitle.name,
        defaultAscending = ArtistSongSort.byTitle.defaultAscending,
    )
    val sort = ArtistSongSort.entries.firstOrNull { it.name == pref.mode } ?: ArtistSongSort.byTitle
    val ordered = remember(members, pref.mode, pref.ascending) {
        val text = localAlphabetComparator()
        /** 主键方向：降序取反（次级键不翻面） */
        fun dir(c: Int): Int = if (pref.ascending) c else -c
        when (sort) {
            ArtistSongSort.byTitle -> members.sortedWith { a, b -> dir(text.compare(a.title, b.title)) }
            ArtistSongSort.byAlbum -> members.sortedWith(
                Comparator<LocalTrackMeta> { a, b -> dir(text.compare(a.album, b.album)) }
                    .thenBy { it.disc ?: 1 }
                    .thenBy { it.track ?: Int.MAX_VALUE },
            )
            ArtistSongSort.byDuration ->
                members.sortedWith { a, b -> dir(a.duration.compareTo(b.duration)) }
        }
    }
    LaunchedEffect(ordered) { ordered.take(80).forEach { onRequestCover(it.path) } }
    // 「歌手封面」：本地没有歌手头像资源，取该歌手首曲封面（与歌手详情主页面同一取法）
    val heroCover = remember(ordered) {
        ordered.firstNotNullOfOrNull { t -> t.coverPath ?: LocalLibraryStore.existingCover(t.path) }
    }

    // 出栈由 AppShell 的全局 PAGE 处理器统一做

    Box(modifier = modifier.fillMaxSize()) {
        // 背景跟这位歌手的封面取色，与歌手详情主页面一致（不跟正在播放的歌曲）
        CoverPaletteBackdrop(
            coverUrl = heroCover,
            palette = rememberCoverPalette(heroCover),
            modifier = Modifier.fillMaxSize(),
        )
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            LocalDetailTopBar(
                title = "全部歌曲",
                count = "${ordered.size} 首",
                onClose = onClose,
            )
            LocalSortRow(
                hint = "共 ${ordered.size} 首 · $artistName",
                sortLabel = sort.label,
                onCycleSort = {
                    val next = sort.next()
                    pref.setMode(next.name, next.defaultAscending)
                },
                trailing = {
                    LocalOrderToggle(pref.ascending) { pref.setAscending(!pref.ascending) }
                },
            )
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().shortScrollbar(listState),
                contentPadding = PaddingValues(
                    top = AppSpacing.sp4,
                    bottom = AppSpacing.listBottomInset,
                ),
            ) {
                itemsIndexed(ordered, key = { _, t -> "s:${t.path}" }) { index, t ->
                    LocalSongRow(
                        meta = t,
                        index = index,
                        isActive = currentSongId == t.id,
                        // 单栏长列表：正在播放用中性灰（与流媒体搜索列表一致）
                        playingTint = AppColors.playingGray,
                        onPlay = { onPlayAt(ordered, index) },
                        onMore = { onMore(t) },
                        // 与流媒体 `SingerDetailSongRow` 一致：副标题是「歌手 · 专辑」
                        showAlbum = true,
                    )
                }
                item(key = "bottom-space") { Spacer(modifier = Modifier.height(AppSpacing.sp24)) }
            }
        }
    }
}

// ── 全部专辑 ──

/** 歌手「全部专辑」的排序模式（每种都能切升降序） */
private enum class ArtistAlbumSort(val label: String, val defaultAscending: Boolean) {
    released("发行时间", false),
    byName("专辑名", true),
    songCount("歌曲数", false),
    ;

    fun next(): ArtistAlbumSort = entries[(ordinal + 1) % entries.size]
}

private const val PAGE_ARTIST_ALBUMS = "artist_albums"

/** 一位歌手的一张专辑（分组结果，供「全部专辑」页排序/渲染） */
private data class ArtistAlbum(
    val key: String,
    val title: String,
    val artist: String,
    val tracks: List<LocalTrackMeta>,
    /** 发行日期（`yyyy[-MM[-dd]]`）；没有日期为 null */
    val date: String?,
) {
    val count: Int get() = tracks.size

    val coverPath: String? get() = tracks.firstNotNullOfOrNull { it.coverPath }
}

/**
 * **全部专辑**（歌手详情 → 「专辑」段的「全部 ›」）。
 *
 * 2 列网格，卡片与专辑列表页 / overview 横滑架同一个 [LocalAlbumGridCard]
 * （N 首 · 类型 · 发行日期）；排序行支持 发行时间 / 专辑名 / 歌曲数 + 升降序。
 */
@Composable
fun LocalArtistAlbumsScreen(
    artistName: String,
    tracks: List<LocalTrackMeta>,
    isTop: Boolean,
    onClose: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val albums = remember(tracks, artistName) {
        tracks
            .filter { artistName in artistGroupKeys(it) }
            .groupBy { albumGroupKey(it) }
            .entries
            .map { (key, list) ->
                ArtistAlbum(
                    key = key,
                    title = key.substringBefore(" · "),
                    artist = key.substringAfter(" · ", list.first().artistDisplay),
                    tracks = list,
                    date = localAlbumDateOf(list),
                )
            }
    }
    val pref = rememberSortPreference(
        page = PAGE_ARTIST_ALBUMS,
        defaultMode = ArtistAlbumSort.released.name,
        defaultAscending = ArtistAlbumSort.released.defaultAscending,
    )
    val sort = ArtistAlbumSort.entries.firstOrNull { it.name == pref.mode } ?: ArtistAlbumSort.released
    val ordered = remember(albums, pref.mode, pref.ascending) {
        val text = localAlphabetComparator()
        fun dir(c: Int): Int = if (pref.ascending) c else -c
        when (sort) {
            ArtistAlbumSort.byName -> albums.sortedWith { a, b -> dir(text.compare(a.title, b.title)) }
            ArtistAlbumSort.songCount -> albums.sortedWith { a, b -> dir(a.count.compareTo(b.count)) }
            ArtistAlbumSort.released -> albums.sortedWith { a, b ->
                // 日期字符串字典序即时间序；`"2023" < "2023-05-12"`，只有年的视为更旧
                val da = a.date
                val db = b.date
                when {
                    // 没日期的永远排最后，不随升降序翻面
                    da == null && db == null -> dir(text.compare(a.title, b.title))
                    da == null -> 1
                    db == null -> -1
                    da == db -> dir(text.compare(a.title, b.title))
                    else -> dir(da.compareTo(db))
                }
            }
        }
    }
    val gridState = rememberLazyGridState()
    // 「歌手封面」：取该歌手任一专辑里第一首有封面的歌（与歌手详情主页面同一取法）
    val heroCover = remember(ordered) {
        ordered.firstNotNullOfOrNull { album ->
            album.tracks.firstNotNullOfOrNull { t ->
                t.coverPath ?: LocalLibraryStore.existingCover(t.path)
            }
        }
    }

    // 出栈由 AppShell 的全局 PAGE 处理器统一做

    Box(modifier = modifier.fillMaxSize()) {
        // 背景跟这位歌手的封面取色，与歌手详情主页面一致（不跟正在播放的歌曲）
        CoverPaletteBackdrop(
            coverUrl = heroCover,
            palette = rememberCoverPalette(heroCover),
            modifier = Modifier.fillMaxSize(),
        )
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            LocalDetailTopBar(
                title = "全部专辑",
                count = "${ordered.size} 张",
                onClose = onClose,
            )
            LocalSortRow(
                hint = "共 ${ordered.size} 张 · $artistName",
                sortLabel = sort.label,
                onCycleSort = {
                    val next = sort.next()
                    pref.setMode(next.name, next.defaultAscending)
                },
                trailing = {
                    LocalOrderToggle(pref.ascending) { pref.setAscending(!pref.ascending) }
                },
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = AppSpacing.sp12, vertical = AppSpacing.sp8),
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val cardWidth = (maxWidth - AppSpacing.sp16 - AppSpacing.sp10) / 2
                    val cardHeight = cardWidth + 68.dp
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        state = gridState,
                        modifier = Modifier.fillMaxSize().shortScrollbar(gridState),
                        contentPadding = PaddingValues(
                            start = AppSpacing.sp8,
                            top = AppSpacing.sp4,
                            end = AppSpacing.sp8,
                            bottom = AppSpacing.listBottomInset,
                        ),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.sp10),
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sp10),
                    ) {
                        items(ordered, key = { it.key }) { album ->
                            LocalAlbumGridCard(
                                name = album.title,
                                artist = album.artist,
                                coverPath = album.coverPath,
                                count = album.count,
                                onClick = { onOpenAlbum(album.key) },
                                modifier = Modifier.height(cardHeight),
                                date = album.date,
                                type = localAlbumType(album.count),
                            )
                        }
                    }
                }
            }
        }
    }
}
