package com.annieplayer.annie_music.kt.features.search

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.R
import com.annieplayer.annie_music.kt.core.state.AlbumDetailData
import com.annieplayer.annie_music.kt.core.state.SearchSelectionViewModel
import com.annieplayer.annie_music.kt.core.state.SelectionState
import com.annieplayer.annie_music.kt.core.state.SingerDetailData
import com.annieplayer.annie_music.kt.core.state.UiState
import com.annieplayer.annie_music.kt.core.state.UiViewModel
import com.annieplayer.annie_music.kt.data.model.DownloadTask
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.sources.buildAlbumCoverUrl
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.AlbumSearchResults
import com.annieplayer.annie_music.kt.widgets.AppTopBar
import com.annieplayer.annie_music.kt.widgets.ModeDropdown
import com.annieplayer.annie_music.kt.widgets.ModeDropdownMode
import com.annieplayer.annie_music.kt.widgets.SearchBarField
import com.annieplayer.annie_music.kt.widgets.SingerSearchResults
import com.annieplayer.annie_music.kt.widgets.SongSearchResults

/** 顶栏头像占位尺寸（对齐 Dart `Image.asset(width: 40, height: 40)`） */
private val HomeHeaderAvatarSize = 40.dp

/**
 * 搜索页内容（原 `HomeScreen.kt` 内容列 + ⇄ 按钮的 1:1 搬迁，对应 Flutter
 * `lib/features/search/home_screen.dart` 的 build 内容区）。
 *
 * 职责边界（结构性拆分，零行为改动）：
 * - 本组件只做渲染与事件转发：通过 `with(st)` 读取 `SearchScreenState`（字段与 handler
 *   引用写法与拆分前完全一致），状态所有权全部留在 `features/shell/AppShell.kt`；
 * - 不在此声明任何 `remember` 状态、不创建任何 `LaunchedEffect`，也不新增默认参数，
 *   以便后续底部导航接入时可直接替换 / 并列其它 tab 内容。
 *
 * 渲染顺序：内容列（顶栏 → 模式切换 → 待下载条 → 搜索栏 → 结果区）→ ⇄ 源切换按钮，
 * 与拆分前「根 Box 第一个子节点 → ⇄」的绘制顺序一致。
 */
@Composable
fun SearchScreen(
    modifier: Modifier = Modifier,
    st: SearchScreenState,
    uiViewModel: UiViewModel,
    selectionViewModel: SearchSelectionViewModel,
    uiState: UiState,
    selection: SelectionState,
    downloadTasks: Map<String, DownloadTask>,
    currentSong: Song?,
) {
    with(st) {
        // 外层 Box 与拆分前的根 Box 内容区同尺寸（宿主传入 fillMaxSize）：
        //  ⇄ 的 BottomEnd 定位与「内容列 → ⇄」的绘制顺序逐像素保持不变。
        // inset 由本页自己补（宿主已不再统一内缩），页面底色由 Scaffold 铺满整窗 → 视觉不变。
        Box(modifier = modifier.systemBarsPadding()) {
            // ── 基础内容列（Overlay.COLUMN） ──
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶栏：菜单按钮 + 大标题（与本地曲库页共用 AppTopBar，标题字号 / 字重 / 字距同源）
                // 右侧头像走 trailing 插槽，位置与旧写法（标题 weight(1f) 顶到头）一致。
                AppTopBar(
                    title = "安妮音乐盒",
                    onOpenMenu = { uiViewModel.setDrawerVisible(true) },
                    trailing = {
                        // Dart: Image.asset('assets/annie.jpg')（资源已在 res/drawable-nodpi/annie.jpg）
                        Image(
                            painter = painterResource(R.drawable.annie),
                            contentDescription = null,
                            modifier = Modifier
                                .size(HomeHeaderAvatarSize)
                                .clip(RoundedCornerShape(AppRadius.r10)),
                            contentScale = ContentScale.Crop,
                        )
                    },
                )
                // 模式切换
                Box(
                    modifier = Modifier.padding(
                        start = AppSpacing.sp16,
                        end = AppSpacing.sp16,
                        bottom = AppSpacing.sp8,
                    ),
                ) {
                    ModeDropdown(
                        mode = mode,
                        open = modePickerVisible,
                        onToggle = { modePickerVisible = !modePickerVisible },
                        onSelect = { handleModeSelect(it) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // 待下载条
                if (mode == ModeDropdownMode.song && selection.selectedSongs.isNotEmpty()) {
                    HomePendingDownloadBar(
                        count = selection.selectedSongs.size,
                        batchDownloading = batchDownloading,
                        onView = { selectionViewModel.setOverlayVisible(true) },
                        onClear = { selectionViewModel.clearAll() },
                        onBatch = { batchPickerVisible = true },
                    )
                }
                // 搜索栏（歌单解析 / 国外解析模式隐藏）
                if (mode != ModeDropdownMode.qobuz && mode != ModeDropdownMode.playlist) {
                    SearchBarField(
                        value = queryText,
                        onValueChange = { queryText = it },
                        onSubmit = { handleSearch() },
                    )
                }
                // 内容区
                Box(modifier = Modifier.weight(1f)) {
                    when (mode) {
                        ModeDropdownMode.song -> SongSearchResults(
                            columns = songColumns,
                            query = songSubmitted,
                            // 每次提交搜索 / 换源分组自增：同词同源重搜也能重置分页状态（修复旧分页串页）
                            searchToken = songGeneration,
                            onPlay = { song, songs -> handlePlaySong(song, songs) },
                            onDownload = { handleDownload(it) },
                            onToggleSelect = { handleToggleSelect(it) },
                            onLoadMore = { source, page -> searchBySource(source, songSubmitted, page) },
                            activeSongId = currentSong?.id,
                            selectedSongs = selection.selectedSongs,
                            downloadStatusOf = { song -> downloadTasks[song.id]?.status },
                        )
                        ModeDropdownMode.album -> AlbumSearchResults(
                            albums = albums,
                            query = albumSubmitted,
                            source = albumPlatform,
                            onAlbumPress = { album ->
                                uiViewModel.setAlbumDetailVisible(
                                    true,
                                    AlbumDetailData(
                                        id = album.albumid,
                                        cover = buildAlbumCoverUrl(album),
                                        name = album.name,
                                        artist = album.artist,
                                        platform = album.platform,
                                    ),
                                )
                            },
                            isLoading = albumsLoading,
                        )
                        ModeDropdownMode.singer -> SingerSearchResults(
                            artists = artists,
                            query = artistSubmitted,
                            source = artistPlatform,
                            onArtistPress = { artist ->
                                uiViewModel.setSingerDetailVisible(
                                    true,
                                    SingerDetailData(
                                        id = artist.id,
                                        name = artist.name,
                                        picture = artist.picture,
                                        platform = artistPlatform,
                                    ),
                                )
                            },
                            isLoading = artistsLoading,
                        )
                        ModeDropdownMode.qobuz -> HomeForeignLinkView(
                            onQobuzSubmit = { handleQobuzSubmit(it) },
                            onKkboxSubmit = { handleKkboxSubmit(it) },
                        )
                        ModeDropdownMode.playlist -> HomePlaylistLinkView(
                            onSubmit = { handlePlaylistSubmit(it) },
                        )
                    }
                }
            }

            // ⇄ 呼出「搜索选择」面板（歌单/国外解析模式隐藏；抽屉/播放页/详情页打开时让位）
            if (mode != ModeDropdownMode.playlist &&
                mode != ModeDropdownMode.qobuz &&
                !uiState.drawerVisible &&
                !uiState.playerOverlayVisible &&
                qobuzDetailRoute == null &&
                kkboxDetailRoute == null
            ) {
                HomeSourceSwitchButton(
                    onTap = { sourcePickerVisible = true },
                )
            }
        }
    }
}
