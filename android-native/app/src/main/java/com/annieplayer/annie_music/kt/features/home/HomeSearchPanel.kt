package com.annieplayer.annie_music.kt.features.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.core.state.AlbumDetailData
import com.annieplayer.annie_music.kt.core.state.AlbumRoute
import com.annieplayer.annie_music.kt.core.state.SearchSelectionViewModel
import com.annieplayer.annie_music.kt.core.state.SelectionState
import com.annieplayer.annie_music.kt.core.state.SingerDetailData
import com.annieplayer.annie_music.kt.core.state.SingerRoute
import com.annieplayer.annie_music.kt.core.state.UiViewModel
import com.annieplayer.annie_music.kt.data.model.DownloadTask
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.sources.kuwo.buildAlbumCoverUrl
import com.annieplayer.annie_music.kt.features.downloads.DownloadOrchestrator
import com.annieplayer.annie_music.kt.features.search.HomeForeignLinkView
import com.annieplayer.annie_music.kt.features.search.HomePendingDownloadBar
import com.annieplayer.annie_music.kt.features.search.SearchScreenState
import com.annieplayer.annie_music.kt.ui.theme.AppSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.AlbumSearchResults
import com.annieplayer.annie_music.kt.widgets.AppleCountryButton
import com.annieplayer.annie_music.kt.widgets.ModeDropdown
import com.annieplayer.annie_music.kt.widgets.ModeDropdownMode
import com.annieplayer.annie_music.kt.widgets.SearchBarField
import com.annieplayer.annie_music.kt.widgets.SingerSearchResults
import com.annieplayer.annie_music.kt.widgets.SongSearchResults

// ================================================================================================
// 主页 · 搜索态面板
// ================================================================================================
//
// 属于「主页的一种形态」，不是独立页面：没有自己的背景、顶栏、返回处理。
// 由 `HomeScreen` 在 `homeSearchExpanded = true` 时淡入，`false` 时淡出并移除。
//
// 内部三块（自上而下）：
//   ① 搜索行   ← 与主页态的「孤立放大镜」共用同一个右内边距 / 行高，见 HomeScreen 的注释
//   ② 待下载条 ← 只在单选模式且已勾选歌曲时出现
//   ③ 结果区   ← 按 [ModeDropdownMode] 分派到四个结果视图
//
// 所有状态都来自 [SearchScreenState]（在 `AppShell` 里 `remember(scope)` 持有），
// 所以「主页 → 搜索 → 主页」来回切不会丢输入内容与结果数据。

/**
 * 主页的搜索态。
 *
 * @param modifier 由宿主传入（铺满内容区）。
 * @param st 搜索态状态机（查询、结果、分页、模式）。
 * @param dl 下载编排（待下载条与行内下载入口）。
 * @param uiViewModel 详情跳转（点专辑 / 歌手时压 `pageStack`）。
 * @param selectionViewModel 多选集合。
 * @param selection 多选状态快照。
 * @param downloadTasks 行内下载状态。
 * @param currentSong 当前播放歌曲（用于结果行的高亮）。
 */
@Composable
internal fun HomeSearchPanel(
    st: SearchScreenState,
    dl: DownloadOrchestrator,
    uiViewModel: UiViewModel,
    selectionViewModel: SearchSelectionViewModel,
    selection: SelectionState,
    downloadTasks: Map<String, DownloadTask>,
    currentSong: Song?,
    modifier: Modifier = Modifier,
) {
    // 冷启动时平台可能**已经是** Apple（上次退出时选的）：那条路径从来没人触发过地区表加载
    // （以前只有「在面板里切平台」才拉），于是国家选择弹出来是空的，只能靠切平台碰运气。
    // 这里按「Apple 平台可见」重试一次；`loadAppleStorefronts()` 自带幂等（有列表 / 正在加载就直接返回）。
    LaunchedEffect(st.showAppleCountry) {
        if (st.showAppleCountry) st.loadAppleStorefronts()
    }

    with(st) {
        Column(modifier = modifier.fillMaxSize()) {
            // ── ① 搜索行：模式按钮 + 搜索框（国外解析模式没有搜索框，只剩按钮） ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = AppSpacing.sp16,
                        end = AppSpacing.sp16,
                        bottom = AppSpacing.sp8,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModeDropdown(
                    mode = mode,
                    open = modePickerVisible,
                    onToggle = { modePickerVisible = !modePickerVisible },
                    onSelect = { handleModeSelect(it) },
                )
                Spacer(modifier = Modifier.width(AppSpacing.sp8))
                if (mode != ModeDropdownMode.qobuz) {
                    SearchBarField(
                        value = queryText,
                        onValueChange = { queryText = it },
                        onSubmit = { handleSearch() },
                        modifier = Modifier.weight(1f),
                        horizontalPadding = 0.dp,
                        verticalPadding = 0.dp,
                        height = AppSize.detailDownloadBarHeight,
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                if (showAppleCountry) {
                    Spacer(modifier = Modifier.width(AppSpacing.sp8))
                    AppleCountryButton(
                        code = appleCountry,
                        storefronts = appleStorefronts,
                        loading = appleStorefrontsLoading,
                        error = appleStorefrontsError,
                        onSelect = { selectAppleCountry(it) },
                        onOpen = { loadAppleStorefronts() },
                        onRetry = { loadAppleStorefronts(force = true) },
                    )
                }
            }

            // ── ② 待下载条 ──
            if (mode == ModeDropdownMode.song && selection.selectedSongs.isNotEmpty()) {
                HomePendingDownloadBar(
                    count = selection.selectedSongs.size,
                    batchDownloading = dl.batchDownloading,
                    onView = { selectionViewModel.setOverlayVisible(true) },
                    onClear = { selectionViewModel.clearAll() },
                    onBatch = { dl.openBatchPicker() },
                )
            }

            // ── ③ 结果区 ──
            Box(modifier = Modifier.weight(1f)) {
                when (mode) {
                    ModeDropdownMode.song -> SongSearchResults(
                        column = songColumn,
                        query = songSubmitted,
                        // 每次提交搜索 / 换源自增：同词同源重搜也能重置分页状态
                        searchToken = songGeneration,
                        onPlay = { song, songs -> handlePlaySong(song, songs) },
                        onDownload = { dl.openActions(it) },
                        onToggleSelect = { handleToggleSelect(it) },
                        onLoadMore = { page ->
                            searchSongs(searchPlatform, songSubmitted, page)
                        },
                        activeSongId = currentSong?.id,
                        selectedSongs = selection.selectedSongs,
                        downloadStatusOf = { song -> downloadTasks[song.id]?.status },
                    )

                    ModeDropdownMode.album -> AlbumSearchResults(
                        albums = albums,
                        query = albumSubmitted,
                        onAlbumPress = { album ->
                            uiViewModel.pushRoute(
                                AlbumRoute(
                                    AlbumDetailData(
                                        id = album.albumid,
                                        cover = buildAlbumCoverUrl(album),
                                        name = album.name,
                                        artist = album.artist,
                                        platform = album.platform,
                                        artistId = album.artistId,
                                    ),
                                ),
                            )
                        },
                        isLoading = albumsLoading,
                    )

                    ModeDropdownMode.singer -> SingerSearchResults(
                        artists = artists,
                        query = artistSubmitted,
                        onArtistPress = { artist ->
                            uiViewModel.pushRoute(
                                SingerRoute(
                                    SingerDetailData(
                                        id = artist.id,
                                        name = artist.name,
                                        picture = artist.picture,
                                        platform = searchPlatform,
                                    ),
                                ),
                            )
                        },
                        isLoading = artistsLoading,
                    )

                    ModeDropdownMode.qobuz -> HomeForeignLinkView(
                        onQobuzSubmit = { handleQobuzSubmit(it) },
                        onKkboxSubmit = { handleKkboxSubmit(it) },
                    )
                }
            }
        }
    }
}
