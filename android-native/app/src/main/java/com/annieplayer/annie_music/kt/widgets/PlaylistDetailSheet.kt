package com.annieplayer.annie_music.kt.widgets

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.annieplayer.annie_music.kt.core.compose.activityViewModel
import com.annieplayer.annie_music.kt.core.routing.BackTarget
import com.annieplayer.annie_music.kt.core.routing.OverlayVisibilityFlags
import com.annieplayer.annie_music.kt.core.routing.handleBack
import com.annieplayer.annie_music.kt.core.state.ColorViewModel
import com.annieplayer.annie_music.kt.core.state.DownloadViewModel
import com.annieplayer.annie_music.kt.core.state.PlayerViewModel
import com.annieplayer.annie_music.kt.core.state.PlaylistDetailData
import com.annieplayer.annie_music.kt.core.state.UiState
import com.annieplayer.annie_music.kt.core.state.UiViewModel
import com.annieplayer.annie_music.kt.core.util.bestQuality
import com.annieplayer.annie_music.kt.data.PlaylistService
import com.annieplayer.annie_music.kt.data.enrichSong
import com.annieplayer.annie_music.kt.data.fetchDownloadUrlBySong
import com.annieplayer.annie_music.kt.data.fetchLyric
import com.annieplayer.annie_music.kt.data.fetchPlayUrlBySong
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.DownloadStatus
import com.annieplayer.annie_music.kt.data.model.DownloadTask
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.ui.theme.ColorPalette
import kotlinx.coroutines.launch

/**
 * 歌单详情浮层（1:1 移植 Flutter `lib/widgets/playlist_detail_sheet.dart`，对齐 RN 版
 * components/PlaylistDetailOverlay.tsx，RN zIndex 150）。
 *
 * 结构：返回条（含「多选 / 全选」）→ 歌单头部（封面 100 + 名称 / 创建者 / 曲目数）
 * → 玻璃列表容器（歌曲行 60dp）；多选时底部常驻「多选下载条」（[MultiSelectDownloadBar]）。
 *
 * 视觉（液态玻璃链路）：面板背景 = 封面模糊 + 取色染色氛围（[CoverPaletteBackdrop]）；
 * 列表 = 毛玻璃容器（[glassPanel]，圆角 [AppRadius.panel]）；命中当前歌的行 = 正在播放动效
 * （[playingRowBackground] + [PlayingIndicator]）；进入时把全局色板临时切为该歌单色板、
 * 退出恢复（[SnapshotPaletteAndRestore]，与播放页同一套取色链路）。
 *
 * 关键行为：
 * - 打开时 `fetchPlaylist` 拉取；失败提示「获取歌单失败，请检查链接是否正确」，
 *   网络异常提示「网络请求失败」。
 * - 平台字段为字符串（如 `kuwo` / `kuwo:`），先规范化再解析为 [MusicPlatform]。
 * - QQ / 酷狗歌曲在播放前先解析播放地址并回填队列（对齐 Dart）。
 * - 批量下载用 [bestQuality] 按歌曲实际支持档位降级。
 * - 返回手势：多选模式优先退出；更高层浮层可见时让位（见 core/routing/OverlayStack.kt）。
 */
@Composable
fun PlaylistDetailSheet(
    uiViewModel: UiViewModel,
    playerViewModel: PlayerViewModel,
    downloadViewModel: DownloadViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by uiViewModel.state.collectAsState()
    val data = uiState.playlistDetailData
    if (!uiState.playlistDetailVisible || data == null) return

    key(data.platform, data.id) {
        PlaylistDetailContent(
            data = data,
            uiState = uiState,
            uiViewModel = uiViewModel,
            playerViewModel = playerViewModel,
            downloadViewModel = downloadViewModel,
            modifier = modifier,
        )
    }
}

/** 歌单详情内部可变状态（对齐 Dart `_PlaylistDetailViewState` 的字段） */
private class PlaylistDetailState {
    var loading by mutableStateOf(true)
    var error by mutableStateOf("")
    var hasPlaylist by mutableStateOf(false)
    var name by mutableStateOf("")
    var creator by mutableStateOf("")
    var cover by mutableStateOf("")
    var songs by mutableStateOf<List<Song>>(emptyList())

    var selectMode by mutableStateOf(false)
    val selectedIds = mutableStateListOf<String>()

    var batchPickerVisible by mutableStateOf(false)
    var batchDownloading by mutableStateOf(false)

    /** 批量下载进度文案（`3/12 歌名`；仅本地展示，供多选下载条 [MultiSelectDownloadBar] 使用） */
    var batchProgress by mutableStateOf("")
}

@Composable
private fun PlaylistDetailContent(
    data: PlaylistDetailData,
    uiState: UiState,
    uiViewModel: UiViewModel,
    playerViewModel: PlayerViewModel,
    downloadViewModel: DownloadViewModel,
    modifier: Modifier = Modifier,
) {
    val st = remember { PlaylistDetailState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val playerState by playerViewModel.state.collectAsState()
    val tasks by downloadViewModel.state.collectAsState()

    /** 全局颜色状态（`activityViewModel()` 与 AppShell 同款宿主查找 → 同一 Activity 作用域实例） */
    val colorViewModel: ColorViewModel = activityViewModel()

    /** 歌单封面：优先已拉取数据，加载完成前回退入参 `data.cover`（全程空则背景仅纯色 + 染色） */
    val coverUrl = st.cover.ifEmpty { data.cover.orEmpty() }

    /** 封面动态色板（与播放页同一取色链路；同 url 进程级缓存见 core/util/ColorExtractor.kt） */
    val palette = rememberCoverPalette(coverUrl.ifEmpty { null })

    // 进入详情把全局色板临时切为该歌单色板，退出恢复进入前的色板（与播放页同一套取色链路）
    // 不再写入全局色板（原 SnapshotPaletteAndRestore）：详情页只用本地 palette 渲染，
// 全局色板由 AppShell 按「当前播放歌曲」驱动；详情页写入会在退出时覆盖成陈旧值。

    /** PlaylistDetailData.platform 为字符串（'kuwo' 或 'kuwo:'），解析为枚举 */
    val platform = remember(data.platform) {
        val raw = data.platform.replace(":", "")
        MusicPlatform.entries.firstOrNull { it.name == raw } ?: MusicPlatform.kuwo
    }

    suspend fun play(song: Song) {
        val idx = st.songs.indexOfFirst { it.id == song.id }
        if (idx < 0) return
        // 先入队立刻开播（与专辑详情一致），音源地址随后异步补
        playerViewModel.setQueue(st.songs, idx)
        try {
            val url = fetchPlayUrlBySong(song)
            val lyric = fetchLyric(song)
            playerViewModel.updateSongPlayUrl(song.id, url)
            playerViewModel.updateSongLyric(song.id, lyric.lrc, lyric.tlyric, lyric.wordLyric)
        } catch (_: Exception) {
            // 对齐 Dart：拉取失败静默忽略
        }
    }

    fun toggleSelect(id: String) {
        if (!st.selectedIds.remove(id)) st.selectedIds.add(id)
    }

    fun toggleSelectAll() {
        if (st.selectedIds.size == st.songs.size) {
            st.selectedIds.clear()
        } else {
            st.selectedIds.clear()
            st.selectedIds.addAll(st.songs.map { it.id })
        }
    }

    fun toggleSelectMode() {
        st.selectMode = !st.selectMode
        st.selectedIds.clear()
    }

    suspend fun batchDownload(quality: Quality) {
        st.batchPickerVisible = false
        st.batchDownloading = true
        val songs = st.songs.filter { st.selectedIds.contains(it.id) }
        for (s in songs) downloadViewModel.addTask(s, quality)
        var index = 0
        for (song in songs) {
            // 多选下载条进度文案（仅展示；串行下载顺序与既有逻辑不变）
            index++
            st.batchProgress = "$index/${songs.size} ${song.title}"
            val actual = bestQuality(quality, song.qualities)
            try {
                val url = fetchDownloadUrlBySong(song, actual)
                val lyric = fetchLyric(song)
                val extra = enrichSong(song)
                val filePath = detailDownloadSong(
                    context = context,
                    songId = song.id,
                    title = song.title,
                    artist = song.artist,
                    coverUrl = song.cover,
                    album = song.album,
                    downloadUrl = url,
                    quality = actual,
                    onProgress = { p -> downloadViewModel.updateProgress(song.id, (p * 100).coerceIn(0.0, 100.0)) },
                    lyric = lyric.lrc,
                    tlyric = lyric.tlyric,
                    albumArtist = song.albumArtist,
                    trackNumber = song.trackNumber ?: extra["trackNumber"] as? String,
                    discNumber = song.discNumber ?: extra["discNumber"] as? String,
                    releaseDate = song.releaseDate ?: extra["releaseDate"] as? String,
                )
                downloadViewModel.completeTask(song.id, filePath)
                playerViewModel.addDownloadedId(song.id)
                playerViewModel.addToQueue(song)
            } catch (_: Exception) {
                downloadViewModel.failTask(song.id)
            }
        }
        st.batchDownloading = false
        st.batchProgress = ""
        st.selectMode = false
        st.selectedIds.clear()
    }

    LaunchedEffect(Unit) {
        st.loading = true
        st.error = ""
        st.hasPlaylist = false
        st.selectMode = false
        st.selectedIds.clear()
        st.batchProgress = ""
        try {
            val result = PlaylistService.fetchPlaylist(platform, data.id)
            if (result != null) {
                st.name = result.name
                st.creator = result.creator
                st.cover = result.cover
                st.songs = result.songs
                st.hasPlaylist = true
            } else {
                st.error = "获取歌单失败，请检查链接是否正确"
            }
        } catch (_: Exception) {
            st.error = "网络请求失败"
        } finally {
            st.loading = false
        }
    }

    // 返回手势：多选模式优先退出；更高层浮层可见时让位（见 OverlayStack.kt）
    val higherFlags = OverlayVisibilityFlags(
        songActions = uiState.songActionsVisible,
        artistPicker = uiState.artistPickerVisible,
        qualityPicker = uiState.pickerVisible || st.batchPickerVisible,
        queue = uiState.queueVisible,
        playerOverlay = uiState.playerOverlayVisible,
        detail = true,
        downloadQueue = uiState.downloadQueueVisible,
        menuDrawer = uiState.drawerVisible,
    )
    val detailIsTop = handleBack(higherFlags) == BackTarget.DETAIL && !uiState.downloadQueueVisible
    BackHandler(enabled = st.selectMode || detailIsTop) {
        if (st.selectMode) {
            toggleSelectMode()
        } else {
            uiViewModel.setPlaylistDetailVisible(false)
        }
    }

    // 进出场：150ms + 线性（AppDuration.sheet + AppEasing.linear，与其它浮层同规格）
    val slide = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        slide.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.linear),
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        // ── 遮罩（Flutter: Color(0x80000000)，点击不关闭） ──
        Box(modifier = Modifier.fillMaxSize().background(AppColors.scrim50))

        // ── 面板：150ms 从底部滑入；背景 = 封面模糊 + 取色染色氛围 ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = slide.value * size.height },
        ) {
            // 封面取色氛围背景（含纯色底兜底；API < 31 时 blur 自动降级为原图，不崩）
            CoverPaletteBackdrop(
                coverUrl = coverUrl.ifEmpty { null },
                palette = palette,
                modifier = Modifier.fillMaxSize(),
            )
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                PlaylistDetailHeader(
                    loading = st.loading,
                    hasPlaylist = st.hasPlaylist,
                    selectMode = st.selectMode,
                    selectedAll = st.selectedIds.size == st.songs.size,
                    onClose = { uiViewModel.setPlaylistDetailVisible(false) },
                    onToggleSelectAll = { toggleSelectAll() },
                    onToggleSelectMode = { toggleSelectMode() },
                )
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    PlaylistDetailBody(
                        st = st,
                        tasks = tasks,
                        palette = palette,
                        playingId = playerState.currentSong?.id,
                        onPlay = { scope.launch { play(it) } },
                        onDownload = { uiViewModel.setSongActionsVisible(true, it) },
                        onToggle = { toggleSelect(it) },
                    )
                }
            }
        }

        // ── 音质选择器（复用 QualityPickerSheet） ──
        // 面板内容交给 AppShell 在「迷你播放条之上」渲染（见 DetailQualityPickerHost 注释）；
        // 无宿主时退化为内联渲染，行为不变。
        val pickerHost = LocalDetailQualityPickerHost.current
        val pickerOwner = remember { Any() }
        val pickerPanel: @Composable () -> Unit = {
            // 批量（固定三档）
            QualityPickerSheet(
                visible = st.batchPickerVisible,
                qualities = listOf(Quality.flac, Quality.k320k, Quality.k128k),
                onSelect = { q -> scope.launch { batchDownload(q) } },
                onCancel = { st.batchPickerVisible = false },
            )
        }
        if (pickerHost == null) {
            pickerPanel()
        } else {
            // 只在面板打开时抢占槽位；关闭时不写，保留内容让面板自带淡出动画播完。
            SideEffect {
                if (st.batchPickerVisible) {
                    pickerHost.show(pickerOwner, pickerPanel)
                }
            }
            DisposableEffect(pickerOwner) {
                onDispose { pickerHost.clear(pickerOwner) }
            }
        }
    }
}

/** 返回条：chevron_left + 「歌单详情」+ 加载指示 /（多选时）全选 + 多选 */
@Composable
private fun PlaylistDetailHeader(
    loading: Boolean,
    hasPlaylist: Boolean,
    selectMode: Boolean,
    selectedAll: Boolean,
    onClose: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onToggleSelectMode: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = AppSpacing.sp12, end = AppSpacing.sp12, top = AppSpacing.sp40, bottom = AppSpacing.sp4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clickableRipple(onClose)
                .padding(AppSpacing.sp4),
            contentAlignment = Alignment.Center,
        ) {
            DetailChevronLeft(modifier = Modifier.size(AppIconSize.icon22), tint = AppColors.textSecondary)
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp8))
        Text(
            text = "歌单详情",
            color = AppColors.textPrimary,
            fontSize = AppFontSize.size17,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.weight(1f))
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(AppIconSize.icon16),
                color = AppColors.primary,
                strokeWidth = 2.dp,
            )
        } else if (hasPlaylist) {
            if (selectMode) {
                Box(
                    modifier = Modifier
                        .clickableRipple(onToggleSelectAll)
                        .padding(AppSpacing.sp4),
                ) {
                    Text(
                        text = if (selectedAll) "取消全选" else "全选",
                        color = AppColors.primary,
                        fontSize = AppFontSize.size13,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .clickableRipple(onToggleSelectMode)
                    .padding(AppSpacing.sp4),
            ) {
                Text(
                    text = if (selectMode) "取消" else "多选",
                    color = AppColors.primary,
                    fontSize = AppFontSize.size13,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun PlaylistDetailBody(
    st: PlaylistDetailState,
    tasks: Map<String, DownloadTask>,
    palette: ColorPalette,
    playingId: String?,
    onPlay: (Song) -> Unit,
    onDownload: (Song) -> Unit,
    onToggle: (String) -> Unit,
) {
    when {
        st.loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AppColors.primary)
            }
        }

        st.error.isNotEmpty() -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = AppSpacing.sp24),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                DetailErrorOutline(modifier = Modifier.size(AppIconSize.icon36), tint = AppColors.textDisabled)
                Spacer(modifier = Modifier.height(AppSpacing.sp12))
                Text(
                    text = st.error,
                    color = AppColors.whiteA40,
                    fontSize = AppFontSize.base,
                    textAlign = TextAlign.Center,
                )
            }
        }

        else -> {
            // 玻璃列表容器（圆角面板承载滚动列表）；底部内边距保留「迷你条 + 手势条」空间
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = AppSpacing.sp12),
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .glassPanel(shape = RoundedCornerShape(AppRadius.panel)),
                    contentPadding = PaddingValues(bottom = AppSpacing.listBottomInset),
                ) {
                    item {
                        PlaylistDetailHero(
                            st = st,
                            onPlayAll = { st.songs.firstOrNull()?.let(onPlay) },
                            onBatch = { st.batchPickerVisible = true },
                        )
                    }
                    items(count = st.songs.size) { index ->
                        val song = st.songs[index]
                        PlaylistDetailSongRow(
                            song = song,
                            index = index,
                            downloadStatus = tasks[song.id]?.status,
                            selectMode = st.selectMode,
                            selected = st.selectedIds.contains(song.id),
                            playing = song.id == playingId,
                            palette = palette,
                            onPlay = onPlay,
                            onDownload = onDownload,
                            onToggle = onToggle,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 头部 Hero：大封面居中 + 居中歌单名/创建者/曲目数 + 动作行（播放全部 / 下载）。
 *
 * 歌单没有专辑那样的位深·采样率信息，元信息行只显示曲目数。
 * 多选时在其下追加 [MultiSelectDownloadBar]（行为与原来一致）。
 */
@Composable
private fun PlaylistDetailHero(
    st: PlaylistDetailState,
    onPlayAll: () -> Unit,
    onBatch: () -> Unit,
) {
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
                .clip(RoundedCornerShape(AppRadius.panel))
                .background(Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            var coverFailed by remember(st.cover) { mutableStateOf(false) }
            if (st.cover.isEmpty() || coverFailed) {
                MusicNoteGlyph(modifier = Modifier.size(AppIconSize.icon36), tint = AppColors.textDisabled)
            } else {
                AsyncImage(
                    model = st.cover,
                    contentDescription = "歌单封面",
                    contentScale = ContentScale.Crop,
                    onError = { coverFailed = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(modifier = Modifier.height(AppSpacing.sp16))
        Text(
            text = st.name,
            color = AppColors.textPrimary,
            fontSize = AppFontSize.size20,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(AppSpacing.sp4))
        Text(
            text = st.creator,
            color = AppColors.whiteA50,
            fontSize = AppFontSize.size15,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(AppSpacing.sp4))
        Text(
            text = "${st.songs.size} 首",
            color = AppColors.whiteA40,
            fontSize = AppFontSize.size13,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(AppSpacing.sp16))
        Row(verticalAlignment = Alignment.CenterVertically) {
            DetailPlayAllPill(
                onClick = onPlayAll,
                enabled = st.songs.isNotEmpty(),
            )
            Spacer(modifier = Modifier.width(AppSpacing.sp16))
            DetailRoundIconButton(
                onClick = onBatch,
                enabled = st.selectMode && st.selectedIds.isNotEmpty(),
            ) { tint ->
                DownloadIconPlaceholder(
                    modifier = Modifier.size(AppIconSize.icon20),
                    tint = tint,
                )
            }
        }
        if (st.selectMode) {
            Spacer(modifier = Modifier.height(AppSpacing.sp8))
            Text(
                text = "已选 ${st.selectedIds.size} 首",
                color = AppColors.whiteA50,
                fontSize = AppFontSize.size13,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 歌曲行：多选框 + 序号 / 正在播放动效 + 名称 /「歌手 · 专辑」+ 下载按钮。
 *
 * - 多选：左侧用 [DetailCheckBox]（替换原自绘对勾；点击对勾与点击行都切换选中）；
 * - 命中当前歌（[playing]）：行底 [playingRowBackground]（主色 8% 圆角底 + 左侧 3dp 主色条）、
 *   序号列换 [PlayingIndicator]、标题用 `palette.primary`；
 * - 点行播放 / 点下载按钮的逻辑与原来完全一致（selectMode 下点行只切换选中）。
 *
 * @param playing 该行是否命中当前播放歌（由调用方按 `currentSong.id` 判定）。
 * @param palette 封面动态色板（正在播放动效的取色来源）。
 */
@Composable
private fun PlaylistDetailSongRow(
    song: Song,
    index: Int,
    downloadStatus: DownloadStatus?,
    selectMode: Boolean,
    selected: Boolean,
    playing: Boolean,
    palette: ColorPalette,
    onPlay: (Song) -> Unit,
    onDownload: (Song) -> Unit,
    onToggle: (String) -> Unit,
) {
    val subtitle = if (song.album.isNullOrEmpty()) song.artist else "${song.artist} · ${song.album}"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(AppSize.detailRowHeight)
            .then(
                when {
                    // 命中当前歌：主色 8% 圆角底 + 左侧 3dp 圆头主色条（与队列 / 其它详情页同款）
                    playing -> Modifier.playingRowBackground(palette.readablePrimary())
                    selected -> Modifier.background(AppColors.primaryA08)
                    else -> Modifier
                },
            )
            .clickableNoRipple { if (selectMode) onToggle(song.id) else onPlay(song) }
            .padding(horizontal = AppSpacing.sp16, vertical = AppSpacing.sp10),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectMode) {
            DetailCheckBox(checked = selected, onToggle = { onToggle(song.id) })
            Spacer(modifier = Modifier.width(AppSpacing.sp10))
        }
        Box(
            modifier = Modifier.width(AppSpacing.sp28),
            contentAlignment = Alignment.Center,
        ) {
            if (playing) {
                PlayingIndicator(tint = palette.readablePrimary())
            } else {
                Text(
                    text = "${index + 1}",
                    color = AppColors.white60,
                    fontSize = AppFontSize.size15,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp10))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = song.title,
                color = if (playing) palette.readablePrimary() else AppColors.textPrimary,
                fontSize = AppFontSize.lg,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp1))
            Text(
                text = subtitle,
                color = AppColors.textTertiary,
                fontSize = AppFontSize.size13,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!selectMode) {
            Box(
                modifier = Modifier
                    // 命中区 ≥48dp：本修饰符只预留布局空间（行高 60dp 内不撑高），触摸外扩由输入层完成
                    .minimumInteractiveComponentSize()
                    .clickableNoRipple { onDownload(song) }
                    .semantics { contentDescription = "下载 ${song.title}" }
                    .padding(AppSpacing.sp4),
                contentAlignment = Alignment.Center,
            ) {
                SongMoreGlyph(
                    modifier = Modifier.size(AppIconSize.icon16),
                    tint = AppColors.textDisabled,
                    downloading = downloadStatus == DownloadStatus.downloading ||
                        downloadStatus == DownloadStatus.pending,
                )
            }
        }
    }
}
