package com.annieplayer.annie_music.kt.features.favorites

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.annieplayer.annie_music.kt.core.audio.apple.ApplePrefetch
import com.annieplayer.annie_music.kt.core.compose.activityViewModel
import com.annieplayer.annie_music.kt.core.state.DownloadViewModel
import com.annieplayer.annie_music.kt.core.state.FavoriteViewModel
import com.annieplayer.annie_music.kt.core.state.PlayerViewModel
import com.annieplayer.annie_music.kt.core.state.PlaylistDetailData
import com.annieplayer.annie_music.kt.core.state.PlaylistRoute
import com.annieplayer.annie_music.kt.core.state.UiViewModel
import com.annieplayer.annie_music.kt.core.util.downloadQualityFor
import com.annieplayer.annie_music.kt.core.util.enqueueDownload
import com.annieplayer.annie_music.kt.data.PlaylistService
import com.annieplayer.annie_music.kt.data.favorites.FavoriteKeys
import com.annieplayer.annie_music.kt.data.favorites.PlaylistScope
import com.annieplayer.annie_music.kt.data.favorites.UserPlaylist
import com.annieplayer.annie_music.kt.data.favorites.mergePlaylistSongs
import com.annieplayer.annie_music.kt.data.local.LocalTrackMeta
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.playQualityLabelOf
import com.annieplayer.annie_music.kt.features.downloads.batchDownloadQualities
import com.annieplayer.annie_music.kt.features.downloads.downloadOneAndRegister
import com.annieplayer.annie_music.kt.features.library.LocalDetailTopBar
import com.annieplayer.annie_music.kt.features.library.LocalSongRow
import com.annieplayer.annie_music.kt.features.library.LocalStaticBackdrop
import com.annieplayer.annie_music.kt.ui.theme.AnnieTypography
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.DetailPlayAllPill
import com.annieplayer.annie_music.kt.widgets.DetailRoundIconButton
import com.annieplayer.annie_music.kt.widgets.DownloadIconPlaceholder
import com.annieplayer.annie_music.kt.widgets.LocalDetailQualityPickerHost
import com.annieplayer.annie_music.kt.widgets.MultiSelectDownloadBar
import com.annieplayer.annie_music.kt.widgets.MusicNoteGlyph
import com.annieplayer.annie_music.kt.widgets.QualityPickerSheet
import com.annieplayer.annie_music.kt.widgets.SongTile
import com.annieplayer.annie_music.kt.widgets.clickableRipple
import com.annieplayer.annie_music.kt.widgets.shortScrollbar
import kotlinx.coroutines.launch

/** 封面 hero 边长 = 屏宽的六成，与在线歌单详情同一比例 */
private const val HeroCoverFraction = 0.58f

/**
 * **自建播放列表详情**（`UserPlaylistRoute` 渲染它）。
 *
 * 布局与「收藏的在线歌单」详情对齐：大封面 hero + 名称 + `N 首` + 动作行 + 曲目列表。
 * 差别只有四处：
 * 1. 没有爱心，换成**删除**（删整个歌单，二次确认）；
 * 2. 右上角是 `+` → 链接导入（只往**这个歌单**里写，不再走"加入收藏"）；
 * 3. 多选 + 批量下载（音质面板交给宿主，见下）；
 * 4. 曲目行的 ⋮ 里将来会多一项「从本歌单移除」。
 *
 * ## 为什么收 id 不收对象
 * 歌单是可编辑的：路由里存一份快照，导入之后返回上一层再进来看到的就是旧的。
 * 这里每次从 `FavoriteViewModel.snapshot` 现取。
 *
 * ## 音质面板必须交给宿主
 * 页面内联渲染会被常驻迷你播放条压住（AGENTS.md §7.7）。所以这里只在组合期读一次
 * 可见性、由 `SideEffect` 把面板写进 `LocalDetailQualityPickerHost` 的槽位。
 */
@Composable
fun UserPlaylistScreen(
    playlistId: String,
    isTop: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    favoriteViewModel: FavoriteViewModel = activityViewModel(),
    playerViewModel: PlayerViewModel = activityViewModel(),
    uiViewModel: UiViewModel = activityViewModel(),
    downloadViewModel: DownloadViewModel = activityViewModel(),
) {
    val snapshot by favoriteViewModel.snapshot.collectAsState()
    val playlist = remember(snapshot, playlistId) {
        snapshot.userPlaylists.firstOrNull { it.id == playlistId }
    }
    val songs = playlist?.songs.orEmpty()
    // 队列用曲库的实时对象（自建歌单里可能有本地歌，理由同「我喜欢」）
    val liveSongs = rememberLiveSongs(songs)
    val playback by playerViewModel.state.collectAsState()
    val tasks by downloadViewModel.state.collectAsState()

    var selectMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }
    var batchPickerVisible by remember { mutableStateOf(false) }
    var batchDownloading by remember { mutableStateOf(false) }
    var batchProgress by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var importVisible by remember { mutableStateOf(false) }
    var importLink by remember { mutableStateOf("") }

    // 面板收起时把多选状态复位，避免删完歌单又打开另一个还是多选态
    LaunchedEffect(playlistId) {
        selectMode = false
        selectedIds.clear()
    }

    // 页内状态回退只到「退出多选」这一层；出栈由 AppShell 的统一裁决做（§7.3）
    BackHandler(enabled = isTop && selectMode) {
        selectMode = false
        selectedIds.clear()
    }

    fun toggleSelect(id: String) {
        if (!selectedIds.remove(id)) selectedIds.add(id)
    }

    suspend fun batchDownload(quality: Quality) {
        batchPickerVisible = false
        batchDownloading = true
        val target = songs.filter { selectedIds.contains(it.id) }
        for (s in target) downloadViewModel.addTask(s, downloadQualityFor(s, quality))
        var index = 0
        for (song in target) {
            index++
            batchProgress = "$index/${target.size} ${song.title}"
            // 前置预备：把后面几首的清单请求提前打掉（Apple session 串行，只能靠重叠）
            ApplePrefetch.ahead(target.drop(index), quality)
            downloadOneAndRegister(
                downloadViewModel = downloadViewModel,
                playerViewModel = playerViewModel,
                song = song,
                quality = downloadQualityFor(song, quality),
                onProgress = { p ->
                    downloadViewModel.updateProgress(song.id, (p * 100).coerceIn(0.0, 100.0))
                },
            )
        }
        // 批量结束：丢掉没开始的预热（正在跑的那首随它跑完，Apple session 不能中途打断）
        ApplePrefetch.drain()
        batchDownloading = false
        batchProgress = ""
        selectMode = false
        selectedIds.clear()
    }

    Box(modifier = modifier.fillMaxSize()) {
        LocalStaticBackdrop(modifier = Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding(),
        ) {
            LocalDetailTopBar(
                title = playlist?.name?.takeIf { it.isNotBlank() } ?: "播放列表",
                count = "${songs.size} 首",
                onClose = onClose,
                // 多选态：左「全选」右「完成」；普通态：右「多选」+「+」
                actionSecondary = if (selectMode) {
                    if (songs.isNotEmpty() && selectedIds.size == songs.size) "取消全选" else "全选"
                } else {
                    null
                },
                onActionSecondary = {
                    if (selectedIds.size == songs.size) {
                        selectedIds.clear()
                    } else {
                        selectedIds.clear()
                        selectedIds.addAll(songs.map { it.id })
                    }
                },
                // 多选态：左「全选」右「完成」；普通态：右「多选」+「+」。
                // 两种 scope 都有多选，但用途不同：在线 = 批量下载，本地 = 批量移除。
                action = if (selectMode) "完成" else "多选",
                onAction = {
                    selectMode = !selectMode
                    selectedIds.clear()
                },
                // 「+」只在能真正导入时给出：本地自建歌单（scope=local）灌在线歌单就是混边
                onAdd = if (!selectMode && playlist?.scope == PlaylistScope.online) {
                    { importVisible = true }
                } else {
                    null
                },
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (playlist == null) {
                    FavEmptyHint(text = "这个播放列表已经不在了")
                    return@Box
                }
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().shortScrollbar(listState),
                    contentPadding = PaddingValues(
                        bottom = if (selectMode) {
                            // 多选下载条要压在底栏之上，列表末项别被它盖住
                            AppSpacing.listBottomInset + 56.dp
                        } else {
                            AppSpacing.listBottomInset
                        },
                    ),
                ) {
                    item(key = "hero") {
                        UserPlaylistHero(
                            playlist = playlist,
                            // 自选封面存在 SQLite 里（BLOB），不走 URL
                            coverBytes = remember(snapshot, playlist.id) {
                                favoriteViewModel.playlistCover(playlist.id)
                            },
                            onPlayAll = {
                                if (liveSongs.isNotEmpty()) {
                                    playerViewModel.setQueue(liveSongs, 0)
                                }
                            },
                            onDelete = { confirmDelete = true },
                            // 本地歌单没有「下载」：文件本来就在盘上
                            onDownload = if (playlist.scope == PlaylistScope.online) {
                                { batchPickerVisible = true }
                            } else {
                                null
                            },
                            downloadEnabled = selectMode && selectedIds.isNotEmpty(),
                        )
                    }
                    if (songs.isEmpty()) {
                        item(key = "empty") {
                            FavEmptyHint(
                                text = if (playlist.scope == PlaylistScope.online) {
                                    "这个播放列表还是空的\n点右上角 + 从链接导入"
                                } else {
                                    "这个播放列表还是空的"
                                },
                            )
                        }
                    }
                    itemsIndexed(songs, key = { _, s -> "${s.platform}:${s.id}" }) { index, song ->
                        val localMeta = if (song.platform == MusicPlatform.local) {
                            localLibraryMetaOf(song)
                        } else {
                            null
                        }
                        if (localMeta != null) {
                            // 本地歌走曲库行；多选态下序号列变勾选框，点击整行 = 切换选中
                            LocalSongRow(
                                meta = localMeta,
                                index = index,
                                isActive = playback.currentSong?.id == localMeta.id,
                                playingTint = AppColors.playingGray,
                                onPlay = { playerViewModel.setQueue(liveSongs, index) },
                                onMore = { uiViewModel.setSongActionsVisible(true, localMeta.toSong(), playlistId) },
                                selectionMode = selectMode,
                                selected = selectedIds.contains(localMeta.id),
                                onToggleSelect = { toggleSelect(localMeta.id) },
                            )
                        } else {
                            SongTile(
                                song = song,
                                sourceLabel = song.platform.label,
                                selectionMode = selectMode,
                                selected = selectedIds.contains(song.id),
                                onToggleSelect = { toggleSelect(it.id) },
                                onPlay = { playerViewModel.setQueue(liveSongs, index) },
                                // 带上 playlistId：面板据此多出「从本歌单移除」
                                onDownload = { uiViewModel.setSongActionsVisible(true, it, playlistId) },
                                isActive = playback.currentSong?.id == song.id &&
                                    playback.currentSong?.platform == song.platform,
                                downloadStatus = tasks[song.id]?.status,
                            )
                        }
                    }
                }
                if (selectMode) {
                    // 在线歌单：批量下载；本地歌单：批量移除（本地文件下载没意义）
                    if (playlist?.scope == PlaylistScope.online) {
                        MultiSelectDownloadBar(
                            selectedCount = selectedIds.size,
                            total = songs.size,
                            downloading = batchDownloading,
                            progressText = batchProgress,
                            onDownload = { if (selectedIds.isNotEmpty()) batchPickerVisible = true },
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    } else {
                        MultiSelectRemoveBar(
                            selectedCount = selectedIds.size,
                            onRemove = {
                                val picked = songs.filter { selectedIds.contains(it.id) }
                                if (picked.isNotEmpty()) {
                                    favoriteViewModel.removeSongsFromUserPlaylist(playlistId, picked)
                                    selectMode = false
                                    selectedIds.clear()
                                }
                            },
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }
        }

        // ── 批量音质面板：交给宿主渲染（迷你播放条之上） ──
        val pickerHost = LocalDetailQualityPickerHost.current
        val pickerOwner = remember { Any() }
        val selectedSongs = songs.filter { selectedIds.contains(it.id) }
        val pickerPanel: @Composable () -> Unit = {
            QualityPickerSheet(
                visible = batchPickerVisible,
                qualities = batchDownloadQualities(selectedSongs),
                sizeOf = { q ->
                    if (selectedSongs.size == 1) selectedSongs.first().qualitySizes[q] else null
                },
                labelOf = { q ->
                    val platforms = selectedSongs.map { it.platform }.distinct()
                    if (platforms.size == 1) playQualityLabelOf(platforms.first(), q) else q.label
                },
                onSelect = { q ->
                    // 跑在全局串行队列上：弹窗一关作用域就作废
                    enqueueDownload("userPlaylist_batch_" + System.currentTimeMillis()) { batchDownload(q) }
                },
                onCancel = { batchPickerVisible = false },
            )
        }
        if (pickerHost == null) {
            pickerPanel()
        } else {
            // 可见性必须在组合期读一次（`SideEffect` 里的读取不建立组合依赖）
            val pickerOpen = batchPickerVisible
            SideEffect { if (pickerOpen) pickerHost.show(pickerOwner, pickerPanel) }
            DisposableEffect(pickerOwner) {
                onDispose { pickerHost.clear(pickerOwner) }
            }
        }

        // ── 链接导入：只往这个歌单里写 ──
        PlaylistImportDialog(
            visible = importVisible,
            link = importLink,
            onLinkChange = { importLink = it },
            onDismiss = { importVisible = false },
            onView = { ref ->
                uiViewModel.pushRoute(
                    PlaylistRoute(PlaylistDetailData(id = ref.id, platform = ref.platform.name)),
                )
            },
            onImport = { ref ->
                val result = PlaylistService.fetchPlaylist(ref.platform, ref.id)
                when {
                    result == null -> "获取歌单失败，检查链接或网络"
                    playlist == null -> "播放列表已不存在"
                    else -> {
                        // 整份算好再写一次库（一个事务）：逐首 append 会是 800 次写 + 800 次 reload
                        val merged = mergePlaylistSongs(playlist.songs, result.songs)
                        favoriteViewModel.saveUserPlaylist(playlist.copy(songs = merged))
                        null
                    }
                }
            },
        )

        // ── 删除确认 ──
        if (confirmDelete && playlist != null) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text(text = "删除播放列表？", style = AnnieTypography.dialogTitle) },
                text = {
                    Text(
                        text = "「${playlist.name}」将从收藏库移除，里面的歌曲不会被删除。",
                        color = AppColors.white70,
                        fontSize = AppFontSize.base,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = false
                        favoriteViewModel.deleteUserPlaylist(playlist.id)
                        onClose()
                    }) {
                        Text(
                            text = "删除",
                            color = AppColors.danger,
                            fontWeight = AppFontWeight.semiBold,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = false }) {
                        Text(text = "取消", color = AppColors.textTertiary)
                    }
                },
                containerColor = AppColors.surface,
            )
        }
    }
}

/** 本地歌单的底部条：只做「批量移除」（本地文件没有下载这回事） */
@Composable
private fun MultiSelectRemoveBar(
    selectedCount: Int,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = selectedCount > 0
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.sp16, vertical = AppSpacing.sp8)
            .clip(RoundedCornerShape(percent = 50))
            .background(AppColors.surface95)
            .padding(horizontal = AppSpacing.sp16, vertical = AppSpacing.sp10),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (enabled) "已选 $selectedCount 首" else "选择要移除的歌曲",
            color = if (enabled) AppColors.white else AppColors.white38,
            fontSize = AppFontSize.size13,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .then(if (enabled) Modifier.clickableRipple(onRemove) else Modifier)
                .clip(RoundedCornerShape(percent = 50))
                .background(if (enabled) AppColors.danger else AppColors.whiteA05)
                .padding(horizontal = AppSpacing.sp16, vertical = AppSpacing.sp6),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "移除",
                color = if (enabled) AppColors.white else AppColors.textDisabled,
                fontSize = AppFontSize.size13,
                fontWeight = AppFontWeight.semiBold,
            )
        }
    }
}

/** hero：封面 + 名称 + `N 首` + 动作行（删除 / 播放全部 / 下载） */
@Composable
private fun UserPlaylistHero(
    playlist: UserPlaylist,
    coverBytes: ByteArray?,
    onPlayAll: () -> Unit,
    onDelete: () -> Unit,
    /** null = 不画下载按钮（本地自建歌单） */
    onDownload: (() -> Unit)?,
    downloadEnabled: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.sp24, vertical = AppSpacing.sp20),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(HeroCoverFraction)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(AppRadius.panel))
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

                playlist.cover.isNotEmpty() -> AsyncImage(
                    model = playlist.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )

                else -> MusicNoteGlyph(
                    modifier = Modifier.size(AppIconSize.icon40),
                    tint = AppColors.whiteA15,
                )
            }
        }
        Spacer(modifier = Modifier.height(AppSpacing.sp16))
        Text(
            text = playlist.name.ifBlank { "未命名播放列表" },
            color = AppColors.textPrimary,
            fontSize = AppFontSize.size20,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(AppSpacing.sp4))
        Text(
            text = "${playlist.songs.size} 首",
            color = AppColors.whiteA40,
            fontSize = AppFontSize.size13,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(AppSpacing.sp16))
        Row(verticalAlignment = Alignment.CenterVertically) {
            DetailRoundIconButton(
                onClick = onDelete,
                enabled = true,
            ) { tint ->
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "删除播放列表",
                    tint = if (tint == AppColors.white) AppColors.danger else tint,
                    modifier = Modifier.size(AppIconSize.icon20),
                )
            }
            Spacer(modifier = Modifier.width(AppSpacing.sp16))
            DetailPlayAllPill(
                onClick = onPlayAll,
                enabled = playlist.songs.isNotEmpty(),
            )
            if (onDownload != null) {
                Spacer(modifier = Modifier.width(AppSpacing.sp16))
                DetailRoundIconButton(
                    onClick = onDownload,
                    enabled = downloadEnabled,
                ) { tint ->
                    DownloadIconPlaceholder(
                        modifier = Modifier.size(AppIconSize.icon20),
                        tint = tint,
                    )
                }
            }
        }
    }
}

/** 本地歌的曲库元数据（自建歌单里可能有本地歌；查不到返回 null 走流媒体行） */
@Composable
private fun localLibraryMetaOf(song: Song): LocalTrackMeta? {
    val metaByPath = rememberLocalMetaByPath()
    return remember(song.id, metaByPath) {
        metaByPath[song.id.removePrefix("local:")]
    }
}
