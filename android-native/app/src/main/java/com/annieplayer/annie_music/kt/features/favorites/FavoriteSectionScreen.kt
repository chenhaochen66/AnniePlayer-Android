package com.annieplayer.annie_music.kt.features.favorites

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.core.compose.activityViewModel
import com.annieplayer.annie_music.kt.core.state.AlbumDetailData
import com.annieplayer.annie_music.kt.core.state.AlbumRoute
import com.annieplayer.annie_music.kt.core.state.FavoriteViewModel
import com.annieplayer.annie_music.kt.core.state.LocalAlbumRoute
import com.annieplayer.annie_music.kt.core.state.LocalArtistRoute
import com.annieplayer.annie_music.kt.core.state.PlayerViewModel
import com.annieplayer.annie_music.kt.core.state.PlaylistDetailData
import com.annieplayer.annie_music.kt.core.state.PlaylistRoute
import com.annieplayer.annie_music.kt.core.state.SingerDetailData
import com.annieplayer.annie_music.kt.core.state.SingerRoute
import com.annieplayer.annie_music.kt.core.state.UiViewModel
import com.annieplayer.annie_music.kt.core.state.UserPlaylistRoute
import com.annieplayer.annie_music.kt.data.PlaylistService
import com.annieplayer.annie_music.kt.data.favorites.FavAlbum
import com.annieplayer.annie_music.kt.data.favorites.FavArtist
import com.annieplayer.annie_music.kt.data.favorites.FavPlaylist
import com.annieplayer.annie_music.kt.data.favorites.FavSection
import com.annieplayer.annie_music.kt.data.favorites.FavoriteMode
import com.annieplayer.annie_music.kt.data.favorites.PlaylistScope
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.features.library.LocalDetailTopBar
import com.annieplayer.annie_music.kt.features.library.LocalStaticBackdrop
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.SearchBarField

/**
 * 收藏库的子页（`FavoriteSectionRoute` 渲染它）。
 *
 * 结构完全照本地曲库的子页（`LocalArtistListScreen` / `LocalAlbumListScreen`）：
 * `LocalDetailTopBar` → 搜索框 → 排序行 → 列表，外层 `systemBarsPadding()`。
 * 少这一层 padding 就会「顶栏顶到状态栏上、整页比别的子页高一截」。
 *
 * 本文件只做**外壳 + 分发**；四个内容各自成块：
 * - 「我喜欢」在本文件（唯一需要搜索框 + 播放胶囊的单曲列表）；
 * - 艺人 / 专辑 / 播放列表在 `FavSectionLists.kt`。
 *
 * 内容页共用的单曲行与播放胶囊在 `FavSongRows.kt`。
 */
@Composable
fun FavoriteSectionScreen(
    section: FavSection,
    isTop: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    favoriteViewModel: FavoriteViewModel = activityViewModel(),
    playerViewModel: PlayerViewModel = activityViewModel(),
    uiViewModel: UiViewModel = activityViewModel(),
) {
    val snapshot by favoriteViewModel.snapshot.collectAsState()
    val mode = snapshot.mode

    val likedSongs = remember(snapshot, mode) { snapshot.songsIn(mode).map { it.song } }
    val artists = remember(snapshot, mode) { snapshot.artistsIn(mode) }
    val albums = remember(snapshot, mode) { snapshot.albumsIn(mode) }
    val userPlaylists = remember(snapshot, mode) { snapshot.userPlaylistsIn(mode) }
    val savedPlaylists = remember(snapshot, mode) { snapshot.playlistsIn(mode) }

    // 播放列表页的两个本地状态：当前 tab（决定顶栏「+」的行为）与导入弹窗的链接。
    // 用 `rememberSaveable`：点「查看歌单」会关掉弹窗去详情页，返回后链接与 tab 都还在 ——
    // 这就是「弹窗没被销毁」的等价效果（`AlertDialog` 是独立窗口，不关会盖住详情页）。
    var playlistTab by rememberSaveable(section) { mutableIntStateOf(0) }
    var importVisible by rememberSaveable(section) { mutableStateOf(false) }
    var importLink by rememberSaveable(section) { mutableStateOf("") }
    var newPlaylistVisible by rememberSaveable(section) { mutableStateOf(false) }

    // 本地模式没有「收藏的在线歌单」，分段整块不渲染，tab 固定走自建那一半。
    // 切模式时强制复位：否则从流媒体的「收藏」切过来会停在一个永远空的列表上。
    val isLocalMode = mode == FavoriteMode.local
    LaunchedEffect(isLocalMode) {
        if (isLocalMode) playlistTab = 1
    }

    Box(modifier = modifier.fillMaxSize()) {
        LocalStaticBackdrop(modifier = Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding(),
        ) {
            LocalDetailTopBar(
                title = when (section) {
                    FavSection.liked -> "我喜欢"
                    FavSection.artists -> "艺人"
                    FavSection.albums -> "专辑"
                    FavSection.playlists -> "播放列表"
                },
                count = when (section) {
                    FavSection.liked -> "${likedSongs.size} 首"
                    FavSection.artists -> "${artists.size} 位"
                    FavSection.albums -> "${albums.size} 张"
                    // 自建 + 收藏的在线歌单：与首页那一行的计数口径一致。
                    // 本地模式只有自建那一侧（收藏恒空），计数也就只数自建。
                    FavSection.playlists -> if (isLocalMode) {
                        "${userPlaylists.size} 个"
                    } else {
                        "${userPlaylists.size + savedPlaylists.size} 个"
                    }
                },
                onClose = onClose,
                // 「+」只在播放列表页：收藏 tab → 链接导入弹窗；自建 tab → 新建页
                onAdd = if (section == FavSection.playlists) {
                    {
                        // 本地没有链接导入（没有收藏这一侧），「+」只可能是新建
                        if (!isLocalMode && playlistTab == 0) {
                            importVisible = true
                        } else {
                            newPlaylistVisible = true
                        }
                    }
                } else {
                    null
                },
            )
            when (section) {
                FavSection.liked -> LikedSongsBody(likedSongs, playerViewModel, uiViewModel)
                FavSection.artists -> FavArtistsBody(mode, artists) { openArtist(uiViewModel, it) }
                FavSection.albums -> FavAlbumsBody(mode, albums) { openAlbum(uiViewModel, it) }
                FavSection.playlists -> FavPlaylistsBody(
                    showTabs = !isLocalMode,
                    tab = playlistTab,
                    onTabChange = { playlistTab = it },
                    userPlaylists = userPlaylists,
                    savedPlaylists = savedPlaylists,
                    onOpenUserPlaylist = { uiViewModel.pushRoute(UserPlaylistRoute(it.id)) },
                    onOpenSavedPlaylist = { openPlaylist(uiViewModel, it) },
                    coverOf = { id -> favoriteViewModel.playlistCover(id) },
                )
            }
        }

        // 新建自建歌单：创建完成 → 直接压进它的详情页
        NewPlaylistDialog(
            visible = newPlaylistVisible,
            scope = scopeOf(mode),
            onDismiss = { newPlaylistVisible = false },
            onCreated = { id ->
                newPlaylistVisible = false
                uiViewModel.pushRoute(UserPlaylistRoute(id))
            },
        )

        // 链接导入弹窗：解析成功才「查看歌单 / 导入歌单」可用
        PlaylistImportDialog(
            visible = importVisible,
            link = importLink,
            onLinkChange = { importLink = it },
            onDismiss = { importVisible = false },
            onView = { ref ->
                uiViewModel.pushRoute(
                    PlaylistRoute(
                        PlaylistDetailData(id = ref.id, platform = ref.platform.name),
                    ),
                )
            },
            onImport = { ref ->
                val result = PlaylistService.fetchPlaylist(ref.platform, ref.id)
                    ?: return@PlaylistImportDialog "获取歌单失败，检查链接或网络"
                favoriteViewModel.addPlaylist(
                    platform = ref.platform,
                    id = ref.id,
                    name = result.name,
                    cover = result.cover,
                    creator = result.creator,
                    trackCount = result.songs.size,
                )
                // 已经收藏过也算成功（幂等），不再提示
                null
            },
        )
    }
}

/** 收藏库模式的哪一侧 → 自建歌单的 `scope`（两套枚举一一对应，创建时定死） */
private fun scopeOf(mode: FavoriteMode): PlaylistScope =
    if (mode == FavoriteMode.local) PlaylistScope.local else PlaylistScope.online

/** 「我喜欢」：搜索框 + 播放/随机播放 + 单曲行 */
@Composable
private fun ColumnScope.LikedSongsBody(
    songs: List<Song>,
    playerViewModel: PlayerViewModel,
    uiViewModel: UiViewModel,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(songs, query) {
        if (query.isBlank()) {
            songs
        } else {
            val q = query.trim().lowercase()
            songs.filter {
                it.title.lowercase().contains(q) ||
                    it.artist.lowercase().contains(q) ||
                    it.album.orEmpty().lowercase().contains(q)
            }
        }
    }
    val playback by playerViewModel.state.collectAsState()
    // 队列必须用曲库的**实时**对象：收藏里存的是收藏那一刻的 `Song` 快照，
    // 本地 tag（标题/艺术家/封面）改过之后，快照与列表行显示的内容会不一致。
    val liveSongs = rememberLiveSongs(filtered)

    SearchBarField(
        value = query,
        onValueChange = { query = it },
        onSubmit = {},
        hint = "在收藏中搜索",
        horizontalPadding = AppSpacing.sp16,
        verticalPadding = AppSpacing.sp4,
        height = 44.dp,
    )
    // 队列 = 屏幕上这份（搜索过滤之后），不是全量收藏
    FavPlayPills(liveSongs) { playerViewModel.setQueue(it, 0) }

    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
    ) {
        if (filtered.isEmpty()) {
            FavEmptyHint(
                text = if (songs.isEmpty()) {
                    "还没有收藏的歌曲\n在详情页或播放页点 ♡ 加进来"
                } else {
                    "没有匹配的歌曲"
                },
            )
            return@Box
        }
        FavSongList(
            songs = filtered,
            currentSong = playback.currentSong,
            onPlayAt = { playerViewModel.setQueue(liveSongs, it) },
            onMore = { uiViewModel.setSongActionsVisible(true, it) },
        )
    }
}

// ── 跳转：各平台数据完整度不同，这里只做「有 id 才跳」的裁决 ──

/**
 * 本地条目走 `LocalAlbumRoute` / `LocalArtistRoute`（键就是曲库的分组键，收藏时逐字存下来的）；
 * 在线条目必须有平台 id —— 没有 id 的旧记录点了不动，也不会跳到一个空详情页。
 */
private fun openAlbum(ui: UiViewModel, album: FavAlbum) {
    if (album.platform == MusicPlatform.local) {
        ui.pushRoute(LocalAlbumRoute(album.key))
        return
    }
    if (album.id.isEmpty()) return
    ui.pushRoute(
        AlbumRoute(
            AlbumDetailData(
                id = album.id,
                cover = album.cover,
                name = album.name,
                artist = album.artist,
                platform = album.platform,
                // 收藏那一刻存下的歌手 id：有了它，专辑详情里点歌手是秒开，不再按名字搜
                artistId = album.artistId,
            ),
        ),
    )
}

private fun openArtist(ui: UiViewModel, artist: FavArtist) {
    if (artist.platform == MusicPlatform.local) {
        ui.pushRoute(LocalArtistRoute(artist.name))
        return
    }
    if (artist.id.isEmpty()) return
    ui.pushRoute(
        SingerRoute(
            SingerDetailData(
                id = artist.id,
                name = artist.name,
                picture = artist.picture,
                platform = artist.platform,
            ),
        ),
    )
}

private fun openPlaylist(ui: UiViewModel, playlist: FavPlaylist) {
    if (playlist.id.isEmpty()) return
    ui.pushRoute(
        PlaylistRoute(
            PlaylistDetailData(
                id = playlist.id,
                platform = playlist.platform.name,
                name = playlist.name,
                cover = playlist.cover,
            ),
        ),
    )
}
