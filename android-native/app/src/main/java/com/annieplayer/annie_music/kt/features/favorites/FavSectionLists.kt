package com.annieplayer.annie_music.kt.features.favorites

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.annieplayer.annie_music.kt.data.favorites.FavAlbum
import com.annieplayer.annie_music.kt.data.favorites.FavArtist
import com.annieplayer.annie_music.kt.data.favorites.FavPlaylist
import com.annieplayer.annie_music.kt.data.favorites.FavoriteMode
import com.annieplayer.annie_music.kt.data.favorites.UserPlaylist
import com.annieplayer.annie_music.kt.data.local.LocalTrackMeta
import com.annieplayer.annie_music.kt.data.local.albumGroupKey
import com.annieplayer.annie_music.kt.data.local.artistGroupKeys
import com.annieplayer.annie_music.kt.data.local.localAlbumDateOf
import com.annieplayer.annie_music.kt.data.local.localAlbumType
import com.annieplayer.annie_music.kt.data.local.localAlphabetComparator
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.features.library.LocalAlbumGridCard
import com.annieplayer.annie_music.kt.features.library.LocalChevronRight
import com.annieplayer.annie_music.kt.features.library.LocalOrderToggle
import com.annieplayer.annie_music.kt.features.library.LocalSortRow
import com.annieplayer.annie_music.kt.features.library.rememberSortPreference
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.MusicNoteGlyph
import com.annieplayer.annie_music.kt.widgets.SearchBarField
import com.annieplayer.annie_music.kt.widgets.SegmentedPill
import com.annieplayer.annie_music.kt.widgets.clickableRipple
import com.annieplayer.annie_music.kt.widgets.shortScrollbar

// ================================================================================================
// 收藏库的三个列表子页（艺人 / 专辑 / 播放列表）
// ================================================================================================
//
// 样式照本地曲库的对应页（`LocalArtistListScreen` / `LocalAlbumListScreen`），
// 外壳（返回条 / 背景 / `systemBarsPadding`）在 `FavoriteSectionScreen` 里，这里只做内容。
//
// ## 为什么排序偏好也用 `rememberSortPreference`
// 那套是「页面键 → SharedPreferences」的通用实现，与曲库子页共用同一份存储：
// 用户在这里选过「按收藏时间」，退出再进来还是它。页面键用 `fav_*` 前缀与曲库那几个区分开。

/** 排序模式：按名字（字母序，中文走拼音） */
private const val FAV_SORT_NAME = "name"

/** 排序模式：按收藏时间（自建歌单用创建时间） */
private const val FAV_SORT_ADDED = "added"

/** 排序模式：按发行日期（`yyyy[-MM[-dd]]` 字符串字典序即时间序） */
private const val FAV_SORT_RELEASE = "release"

/** 艺人页的头像边长（与本地曲库歌手列表一致） */
private val ArtistAvatarSize = 48.dp

/** 播放列表行的封面边长（与本地曲库分组行一致） */
private val PlaylistCoverSize = 52.dp

/**
 * **艺人**
 *
 * 副标题分两边：本地能用曲库算出「N 首 · M 张专辑」（一次遍历预计算，不在每行里 distinct），
 * 在线只有平台名可显示 —— 收藏记录里没有曲目数，那是 `FavoriteRefresher` 的事。
 */
@Composable
internal fun ColumnScope.FavArtistsBody(
    mode: FavoriteMode,
    artists: List<FavArtist>,
    onOpenArtist: (FavArtist) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    // 本地模式下顺手算一遍统计；在线模式不碰曲库（否则每进一次收藏库都要扫盘）
    val tracks = if (mode == FavoriteMode.local) rememberLocalTracks() else emptyList()
    val stats = remember(tracks) { localArtistStats(tracks) }

    val pref = rememberSortPreference(
        page = "fav_artists",
        defaultMode = FAV_SORT_NAME,
        defaultAscending = true,
    )
    val byName = pref.mode == FAV_SORT_NAME
    val ordered = remember(artists, query, pref.mode, pref.ascending) {
        val q = query.trim()
        val visible = if (q.isEmpty()) {
            artists
        } else {
            artists.filter { it.name.contains(q, ignoreCase = true) }
        }
        val text = localAlphabetComparator()
        if (byName) {
            visible.sortedWith { a, b ->
                val c = text.compare(a.name, b.name)
                if (pref.ascending) c else -c
            }
        } else {
            visible.sortedWith { a, b ->
                val c = a.addedAt.compareTo(b.addedAt)
                if (pref.ascending) c else -c
            }
        }
    }
    val listState = rememberLazyListState()

    SearchBarField(
        value = query,
        onValueChange = { query = it },
        onSubmit = {},
        hint = "搜索艺人",
        horizontalPadding = AppSpacing.sp16,
        verticalPadding = AppSpacing.sp4,
        height = 44.dp,
    )
    LocalSortRow(
        hint = "共 ${ordered.size} 位",
        sortLabel = if (byName) "名字" else "收藏时间",
        onCycleSort = {
            if (byName) pref.setMode(FAV_SORT_ADDED, false) else pref.setMode(FAV_SORT_NAME, true)
        },
        trailing = { LocalOrderToggle(pref.ascending) { pref.setAscending(!pref.ascending) } },
    )
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.sp12, vertical = AppSpacing.sp8),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().shortScrollbar(listState),
            contentPadding = PaddingValues(
                top = AppSpacing.sp8,
                bottom = AppSpacing.listBottomInset,
            ),
        ) {
            if (ordered.isEmpty()) {
                item(key = "empty") {
                    FavEmptyHint(
                        text = if (artists.isEmpty()) {
                            "还没有收藏的艺人\n在艺人详情页点 ♡ 加进来"
                        } else {
                            "没有匹配的艺人"
                        },
                    )
                }
            }
            items(ordered, key = { "favA:${it.key}" }) { artist ->
                val stat = stats[artist.name]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(AppRadius.r8))
                        .clickableRipple { onOpenArtist(artist) }
                        .padding(horizontal = AppSpacing.sp12, vertical = AppSpacing.sp8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FavAvatar(
                        // 收藏时存的就是完整 Coil model（在线 https / 本地 file://），
                        // 没存过才回落到「从曲库那组里取一张封面」
                        model = artist.picture.ifEmpty {
                            stat?.coverPath?.let { "file://$it" }.orEmpty()
                        },
                        name = artist.name,
                        size = ArtistAvatarSize,
                        circle = true,
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.sp12))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = artist.name.ifEmpty { "未知艺人" },
                            color = AppColors.textPrimary,
                            fontSize = AppFontSize.size15,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // 副标题：在线只有平台名；本地要等曲库读出来才算得清。
                        // 曲库为空时（还没读完 / 本来就没有本地文件）**不下结论** —— 否则第一帧会
                        // 对每位艺人都显示「曲库里已没有这位艺人的歌」。
                        val subtitle = when {
                            mode != FavoriteMode.local -> artist.platform.label
                            stat != null -> "${stat.songs} 首 · ${stat.albums} 张专辑"
                            tracks.isEmpty() -> ""
                            else -> "曲库里已没有这位艺人的歌"
                        }
                        if (subtitle.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(AppSpacing.sp2))
                            Text(
                                text = subtitle,
                                color = AppColors.textTertiary,
                                fontSize = AppFontSize.size13,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    LocalChevronRight(
                        modifier = Modifier.width(AppSpacing.sp18).height(AppSpacing.sp18),
                        tint = AppColors.glyphDim,
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.sp8))
                }
            }
        }
    }
}

/**
 * **专辑**（两列网格）
 *
 * 卡片直接复用 `LocalAlbumGridCard`（四个页面同一套尺寸语言），只是封面现在可能是在线 URL：
 * 那边 `coverPath` 是裸本地路径、会自动补 `file://`，所以这里传 `coverModel`。
 *
 * 第三行是「N首 · 类型 · 发行日期」，两边来源不同：
 * - **本地**：曲目数与日期都由曲库现算（tag 里的 `date`，经 `localAlbumDateOf` 规范成 `yyyy-MM-dd`）；
 * - **流媒体**：用收藏那一刻存下来的 `trackCount` / `releaseDate`。列表**不发任何请求** ——
 *   只有你打开那张专辑的详情页，值才会被校准（见 `FavoriteViewModel.syncAlbumMeta`）。
 */
@Composable
internal fun ColumnScope.FavAlbumsBody(
    mode: FavoriteMode,
    albums: List<FavAlbum>,
    onOpenAlbum: (FavAlbum) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val tracks = if (mode == FavoriteMode.local) rememberLocalTracks() else emptyList()
    val groups = remember(tracks) { tracks.groupBy { albumGroupKey(it) } }

    val pref = rememberSortPreference(
        page = "fav_albums",
        defaultMode = FAV_SORT_NAME,
        defaultAscending = true,
    )
    val sort = pref.mode
    val ordered = remember(albums, query, pref.mode, pref.ascending, groups) {
        val q = query.trim()
        val visible = if (q.isEmpty()) {
            albums
        } else {
            albums.filter {
                it.name.contains(q, ignoreCase = true) ||
                    it.artist.contains(q, ignoreCase = true)
            }
        }
        val text = localAlphabetComparator()
        fun dir(c: Int): Int = if (pref.ascending) c else -c
        when (sort) {
            FAV_SORT_ADDED -> visible.sortedWith { a, b -> dir(a.addedAt.compareTo(b.addedAt)) }
            FAV_SORT_RELEASE -> visible.sortedWith { a, b ->
                // 日期是 `yyyy[-MM[-dd]]` 字符串，字典序即时间序；`"2023" < "2023-05-12"`
                val da = releaseDateOf(a, groups[a.key])
                val db = releaseDateOf(b, groups[b.key])
                when {
                    // 没日期的永远排最后（不随升降序翻面）
                    da == null && db == null -> dir(text.compare(a.name, b.name))
                    da == null -> 1
                    db == null -> -1
                    da == db -> dir(text.compare(a.name, b.name))
                    else -> dir(da.compareTo(db))
                }
            }
            else -> visible.sortedWith { a, b -> dir(text.compare(a.name, b.name)) }
        }
    }
    val gridState = rememberLazyGridState()

    SearchBarField(
        value = query,
        onValueChange = { query = it },
        onSubmit = {},
        hint = "搜索专辑、歌手",
        horizontalPadding = AppSpacing.sp16,
        verticalPadding = AppSpacing.sp4,
        height = 44.dp,
    )
    LocalSortRow(
        hint = "共 ${ordered.size} 张",
        sortLabel = when (sort) {
            FAV_SORT_ADDED -> "收藏时间"
            FAV_SORT_RELEASE -> "发行日期"
            else -> "名字"
        },
        onCycleSort = {
            // 换模式时方向落到该模式的默认值：名字→升序，时间类→降序（新→旧）
            val next = when (sort) {
                FAV_SORT_NAME -> FAV_SORT_ADDED
                FAV_SORT_ADDED -> FAV_SORT_RELEASE
                else -> FAV_SORT_NAME
            }
            pref.setMode(next, next == FAV_SORT_NAME)
        },
        trailing = { LocalOrderToggle(pref.ascending) { pref.setAscending(!pref.ascending) } },
    )
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.sp12, vertical = AppSpacing.sp8),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // 与 `LocalAlbumListScreen` 同一套卡片尺寸：宽 = (屏宽 − 16 − 10) / 2，高 = 宽 + 68dp
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
                if (ordered.isEmpty()) {
                    item(key = "empty", span = { GridItemSpan(2) }) {
                        FavEmptyHint(
                            text = if (albums.isEmpty()) {
                                "还没有收藏的专辑\n在专辑详情页点 ♡ 加进来"
                            } else {
                                "没有匹配的专辑"
                            },
                        )
                    }
                }
                items(ordered, key = { "favB:${it.key}" }) { album ->
                    val group = groups[album.key]
                    LocalAlbumGridCard(
                        name = album.name.ifEmpty { "未知专辑" },
                        artist = album.artist,
                        coverPath = null,
                        coverModel = album.cover.takeIf { it.isNotEmpty() },
                        // 本地按曲库算；在线用收藏时存的那份（为 0 时卡片不画这一项）
                        count = group?.size ?: album.trackCount,
                        date = releaseDateOf(album, group),
                        type = group?.let { localAlbumType(it.size) },
                        onClick = { onOpenAlbum(album) },
                        modifier = Modifier.height(cardHeight),
                    )
                }
            }
        }
    }
}

/**
 * 一张收藏专辑的发行日期：本地从曲库 tag 算，在线用收藏记录里存的那份。
 *
 * 本地专辑的 `FavAlbum.releaseDate` 恒为空 —— 收藏时没传，也不需要传（曲库是本地事实来源）。
 */
private fun releaseDateOf(album: FavAlbum, group: List<LocalTrackMeta>?): String? =
    if (album.platform == MusicPlatform.local) {
        group?.let { localAlbumDateOf(it) }
    } else {
        album.releaseDate.takeIf { it.isNotEmpty() }
    }

/**
 * **播放列表**
 *
 * 顶部分「收藏 / 自建」两个 tab（分段在搜索框左侧），列表只显示当前 tab：
 * - **收藏**（`FavPlaylist`）：别人的在线歌单，只在流媒体模式下有；
 * - **自建**（`UserPlaylist`）：用户自己建的，`scope` 决定它在哪一侧出现（本地 / 流媒体）。
 *
 * 一行的差别只有副标题（自建 `N 首` / 收藏的 `创建者 · N 首 · 平台`）与点进去的目标页。
 * 右上角的「+」在宿主（`FavoriteSectionScreen`）里：收藏 → 导入弹窗，自建 → 新建页。
 */
@Composable
internal fun ColumnScope.FavPlaylistsBody(
    /**
     * 是否显示「收藏 / 自建」分段。
     *
     * 本地模式**不显示**：本地没有「收藏的在线歌单」这个概念（`playlistsIn(local)` 恒空），
     * 那一半永远是空列表。这时 [tab] 恒为 1，列表直接是本地自建歌单。
     */
    showTabs: Boolean = true,
    /** 0 = 收藏，1 = 自建（状态由宿主持有，因为它同时决定顶栏「+」的行为） */
    tab: Int,
    onTabChange: (Int) -> Unit,
    userPlaylists: List<UserPlaylist>,
    savedPlaylists: List<FavPlaylist>,
    onOpenUserPlaylist: (UserPlaylist) -> Unit,
    onOpenSavedPlaylist: (FavPlaylist) -> Unit,
    /** 自建歌单的自选封面字节（存 SQLite，按 id 取；没有返回 null 就画占位） */
    coverOf: (String) -> ByteArray? = { null },
) {
    var query by remember { mutableStateOf("") }
    val pref = rememberSortPreference(
        page = "fav_playlists",
        defaultMode = FAV_SORT_NAME,
        defaultAscending = true,
    )
    val byName = pref.mode == FAV_SORT_NAME
    // 本地模式不分段，恒走自建那一半（`tab` 的复位是异步的，别让第一帧闪一个空列表）
    val showingSaved = showTabs && tab == 0
    val ordered = remember(userPlaylists, savedPlaylists, tab, query, pref.mode, pref.ascending) {
        // 只投影当前 tab 的那一半：两个 tab 的数据本来就分开存，混在一起再筛是白做功
        val all = if (showingSaved) {
            savedPlaylists.map { PlaylistEntry.Saved(it) }
        } else {
            userPlaylists.map { PlaylistEntry.Mine(it) }
        }
        val q = query.trim()
        val visible = if (q.isEmpty()) all else all.filter { it.name.contains(q, ignoreCase = true) }
        val text = localAlphabetComparator()
        if (byName) {
            visible.sortedWith { a, b ->
                val c = text.compare(a.name, b.name)
                if (pref.ascending) c else -c
            }
        } else {
            visible.sortedWith { a, b ->
                val c = a.addedAt.compareTo(b.addedAt)
                if (pref.ascending) c else -c
            }
        }
    }
    val listState = rememberLazyListState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.sp16, vertical = AppSpacing.sp4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showTabs) {
            SegmentedPill(
                options = listOf("收藏", "自建"),
                selectedIndex = tab,
                onSelect = onTabChange,
            )
            Spacer(modifier = Modifier.width(AppSpacing.sp8))
        }
        SearchBarField(
            value = query,
            onValueChange = { query = it },
            onSubmit = {},
            hint = "搜索播放列表",
            modifier = Modifier.weight(1f),
            horizontalPadding = 0.dp,
            verticalPadding = 0.dp,
            height = 40.dp,
        )
    }
    LocalSortRow(
        hint = "共 ${ordered.size} 个",
        sortLabel = if (byName) "名字" else "收藏时间",
        onCycleSort = {
            if (byName) pref.setMode(FAV_SORT_ADDED, false) else pref.setMode(FAV_SORT_NAME, true)
        },
        trailing = { LocalOrderToggle(pref.ascending) { pref.setAscending(!pref.ascending) } },
    )
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.sp12, vertical = AppSpacing.sp8),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().shortScrollbar(listState),
            contentPadding = PaddingValues(
                top = AppSpacing.sp8,
                bottom = AppSpacing.listBottomInset,
            ),
        ) {
            if (ordered.isEmpty()) {
                item(key = "empty") {
                    FavEmptyHint(
                        text = when {
                            query.isNotBlank() -> "没有匹配的播放列表"
                            !showTabs -> "还没有本地播放列表\n点右上角 + 新建一个"
                            showingSaved -> "还没有收藏的歌单\n点右上角 + 粘贴链接导入"
                            else -> "还没有自建播放列表\n点右上角 + 新建一个"
                        },
                    )
                }
            }
            items(ordered, key = { "favP:${it.key}" }) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(AppRadius.r8))
                        .clickableRipple {
                            when (entry) {
                                is PlaylistEntry.Mine -> onOpenUserPlaylist(entry.playlist)
                                is PlaylistEntry.Saved -> onOpenSavedPlaylist(entry.fav)
                            }
                        }
                        .padding(horizontal = AppSpacing.sp12, vertical = AppSpacing.sp8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 自建歌单的自选封面存在 SQLite 里（BLOB），按 id 取；收藏的在线歌单用 URL
                    val coverBytes = if (entry is PlaylistEntry.Mine) {
                        remember(entry.key) { coverOf(entry.playlist.id) }
                    } else {
                        null
                    }
                    Box(
                        modifier = Modifier
                            .size(PlaylistCoverSize)
                            .clip(RoundedCornerShape(AppRadius.r8))
                            .background(AppColors.whiteA05),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            coverBytes != null -> AsyncImage(
                                model = coverBytes,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )

                            entry.cover.isNotEmpty() -> AsyncImage(
                                model = entry.cover,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )

                            else -> MusicNoteGlyph(
                                modifier = Modifier.size(AppIconSize.icon20),
                                tint = AppColors.whiteA15,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(AppSpacing.sp12))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.name.ifEmpty { "未命名播放列表" },
                            color = AppColors.textPrimary,
                            fontSize = AppFontSize.size15,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.sp2))
                        Text(
                            text = entry.subtitle,
                            color = AppColors.textTertiary,
                            fontSize = AppFontSize.size13,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    LocalChevronRight(
                        modifier = Modifier.width(AppSpacing.sp18).height(AppSpacing.sp18),
                        tint = AppColors.glyphDim,
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.sp8))
                }
            }
        }
    }
}

// ── 内部：条目投影 / 统计 / 零件 ──

/**
 * 播放列表页的一行（自建 / 收藏的在线歌单）。
 *
 * 为什么不直接把两个 list 拼起来排序：两者的「时间」字段不同名（`createdAt` / `addedAt`）、
 * 副标题来源也不同，投影成同一个形状之后排序与渲染各只有一条分支。
 */
private sealed interface PlaylistEntry {
    val key: String
    val name: String
    val cover: String
    val subtitle: String

    /** 排序用的时间（自建歌单没有「收藏时间」，用创建时间） */
    val addedAt: Long

    class Mine(val playlist: UserPlaylist) : PlaylistEntry {
        override val key: String get() = "u:${playlist.id}"
        override val name: String get() = playlist.name
        override val cover: String get() = playlist.cover
        override val subtitle: String get() = "${playlist.songs.size} 首"
        override val addedAt: Long get() = playlist.createdAt
    }

    class Saved(val fav: FavPlaylist) : PlaylistEntry {
        override val key: String get() = "f:${fav.key}"
        override val name: String get() = fav.name
        override val cover: String get() = fav.cover

        /**
         * `创建者 · N 首 · 平台`。
         *
         * 三项都可能缺：创建者 / 曲目数是收藏那一刻详情页给的（之后打开歌单详情会校准），
         * 老记录还没有这两项，缺哪项就省哪项 —— 剩下的至少还有平台名，不会出现空白行。
         */
        override val subtitle: String
            get() = listOfNotNull(
                fav.creator.takeIf { it.isNotBlank() },
                fav.trackCount.takeIf { it > 0 }?.let { "$it 首" },
                fav.platform.label,
            ).joinToString(" · ")

        override val addedAt: Long get() = fav.addedAt
    }
}

/** 本地艺人统计（曲目数 / 专辑数 / 一张可用封面路径） */
internal class ArtistStat(val songs: Int, val albums: Int, val coverPath: String?)

/**
 * 一次遍历算出每位本地艺人的统计。
 *
 * 键必须走 `artistGroupKeys`（多歌手拆成 `A / B` 之后逐个入表），与曲库的歌手分组、
 * 以及收藏时用的键（`FavoriteKeys.localArtist(artistName)`）同源 —— 用 `artistDisplay`
 * 拼整串会让 `周杰伦 / 费玉清` 这种合唱曲对不上两张单独的收藏。
 */
internal fun localArtistStats(tracks: List<LocalTrackMeta>): Map<String, ArtistStat> {
    if (tracks.isEmpty()) return emptyMap()
    val songCount = HashMap<String, Int>()
    val albumKeys = HashMap<String, HashSet<String>>()
    val covers = HashMap<String, String>()
    for (t in tracks) {
        val albumKey = albumGroupKey(t)
        for (name in artistGroupKeys(t)) {
            songCount[name] = (songCount[name] ?: 0) + 1
            albumKeys.getOrPut(name) { HashSet() }.add(albumKey)
            val cover = t.coverPath
            if (cover != null && name !in covers) covers[name] = cover
        }
    }
    return songCount.mapValues { (name, count) ->
        ArtistStat(
            songs = count,
            albums = albumKeys[name]?.size ?: 0,
            coverPath = covers[name],
        )
    }
}

/** 圆形 / 圆角封面位：有图放图，没图放名字首字（照 `LocalArtistAvatar`，但 model 是完整 URL） */
@Composable
private fun FavAvatar(
    model: String,
    name: String,
    size: Dp,
    circle: Boolean,
) {
    var failed by remember(model) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(size)
            .clip(if (circle) CircleShape else RoundedCornerShape(AppRadius.r8))
            .background(AppColors.whiteA03),
        contentAlignment = Alignment.Center,
    ) {
        when {
            model.isNotEmpty() && !failed -> AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )

            name.isNotBlank() -> Text(
                text = name.take(1).uppercase(),
                color = AppColors.primary,
                fontSize = AppFontSize.size20,
                fontWeight = FontWeight.Bold,
            )

            else -> MusicNoteGlyph(
                modifier = Modifier.size(AppIconSize.icon20),
                tint = AppColors.whiteA15,
            )
        }
    }
}

/** 四个子页共用的空态 / 无匹配提示 */
@Composable
internal fun FavEmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.sp24, vertical = AppSpacing.sp40),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = AppColors.white38,
            fontSize = AppFontSize.base,
            textAlign = TextAlign.Center,
        )
    }
}
