package com.annieplayer.annie_music.kt.features.album

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.annieplayer.annie_music.kt.core.compose.activityViewModel
import com.annieplayer.annie_music.kt.core.state.AlbumDetailData
import com.annieplayer.annie_music.kt.core.state.ColorViewModel
import com.annieplayer.annie_music.kt.core.state.DownloadViewModel
import com.annieplayer.annie_music.kt.core.state.PlayerViewModel
import com.annieplayer.annie_music.kt.core.util.enqueueDownload
import com.annieplayer.annie_music.kt.data.fetchDownloadUrlBySong
import com.annieplayer.annie_music.kt.data.fetchLyric
import com.annieplayer.annie_music.kt.core.state.UiViewModel
import com.annieplayer.annie_music.kt.data.fetchPlayUrlBySong
import com.annieplayer.annie_music.kt.data.sources.qobuzQualityLabel
import com.annieplayer.annie_music.kt.data.model.AlbumSong
import com.annieplayer.annie_music.kt.data.model.DownloadStatus
import com.annieplayer.annie_music.kt.data.model.DownloadTask
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.sources.getAlbumSongs
import com.annieplayer.annie_music.kt.ui.theme.AppBorder
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.ui.theme.ColorPalette
import com.annieplayer.annie_music.kt.widgets.CoverPaletteBackdrop
import com.annieplayer.annie_music.kt.widgets.DetailCheckBox
import com.annieplayer.annie_music.kt.widgets.DetailChevronLeft
import com.annieplayer.annie_music.kt.widgets.DetailErrorOutline
import com.annieplayer.annie_music.kt.widgets.DownloadIconPlaceholder
import com.annieplayer.annie_music.kt.widgets.SongMoreGlyph
import com.annieplayer.annie_music.kt.widgets.LocalDetailQualityPickerHost
import com.annieplayer.annie_music.kt.widgets.MultiSelectDownloadBar
import com.annieplayer.annie_music.kt.widgets.MusicNoteGlyph
import com.annieplayer.annie_music.kt.widgets.PlayingIndicator
import com.annieplayer.annie_music.kt.widgets.QualityPickerSheet
import com.annieplayer.annie_music.kt.widgets.clickableRipple
import com.annieplayer.annie_music.kt.widgets.detailDownloadSong
import com.annieplayer.annie_music.kt.widgets.glassPanel
import com.annieplayer.annie_music.kt.widgets.playingRowBackground
import com.annieplayer.annie_music.kt.widgets.rememberCoverPalette
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 列表行高（对齐 Dart `_rowHeight = 60`） */
private val AlbumDetailRowHeight = AppSize.detailRowHeight

/** 专辑页列表横向内边距（对齐 Dart `_listPaddingH = 16`） */
private val AlbumDetailListPaddingH = AppSpacing.sp16

/** 专辑详情页可变状态（对齐 Dart `_AlbumDetailScreenState` 的字段） */
private class AlbumDetailScreenState {
    var songs by mutableStateOf<List<AlbumSong>>(emptyList())
    var loading by mutableStateOf(true)
    var error by mutableStateOf("")
    var releaseDate by mutableStateOf("")

    var selectMode by mutableStateOf(false)
    val selectedIds = mutableStateListOf<String>()

    var pickerVisible by mutableStateOf(false)
    var pickerSong by mutableStateOf<AlbumSong?>(null)
    var downloadingId by mutableStateOf<String?>(null)
    var batchDownloading by mutableStateOf(false)
    var batchProgress by mutableStateOf("")
}

/**
 * 酷我专辑详情整页（1:1 移植 Flutter `lib/features/album/album_detail_screen.dart`）。
 *
 * 路由参数 [albumId] / [cover] / [name] / [artist] 对应 Dart 的 query / extra；
 * 当 [albumId] 为空且提供了 [fallback]（`uiProvider.albumDetailData`）时整组回退，
 * 与 Dart `_readRouteParams` 的兼容分支一致。
 *
 * 行为对齐点：
 * - 加载：`getAlbumSongs(albumId)`，失败走错误态；缺参显示「缺少参数」。
 * - 播放：先 `setQueue` 再异步补 `playUrl` / 歌词（失败静默）。
 * - 单曲下载：音质选择 flac / 320k / 128k（album 顺序），经 `enqueueDownload` 串行执行。
 * - 批量下载：逐个下载并汇报 `${i+1}/${n} 曲名`，结束显示「下载完成 x/y」。
 * - 返回手势：整页直接回退（对齐 Dart 无 `PopScope` 的页面）。
 * - 视觉：封面取色氛围背景（`CoverPaletteBackdrop`）+ 毛玻璃列表容器（`glassPanel`）；
 *   正在播放行以主色氛围底 + 等化器指示呈现。
 */
@Composable
fun AlbumDetailScreen(
    playerViewModel: PlayerViewModel,
    downloadViewModel: DownloadViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    albumId: String = "",
    cover: String = "",
    name: String = "",
    artist: String = "",
    fallback: AlbumDetailData? = null,
    colorViewModel: ColorViewModel = activityViewModel(),
    uiViewModel: UiViewModel = activityViewModel(),
) {
    val useFallback = albumId.isEmpty() && fallback != null
    val resolvedId = if (useFallback) fallback?.id.orEmpty() else albumId
    val resolvedCover = if (useFallback) fallback?.cover.orEmpty() else cover
    val resolvedName = if (useFallback) fallback?.name.orEmpty() else name
    val resolvedArtist = if (useFallback) fallback?.artist.orEmpty() else artist

    val st = remember { AlbumDetailScreenState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val tasks by downloadViewModel.state.collectAsState()
    val playerState by playerViewModel.state.collectAsState()

    // 封面动态取色氛围（与播放页同一套链路）；进入时把该封面色板灌给全局，离开时恢复原色板
    val palette = rememberCoverPalette(resolvedCover)
    // 不再写入全局色板（原 SnapshotPaletteAndRestore）：详情页只用本地 palette 渲染，
// 全局色板由 AppShell 按「当前播放歌曲」驱动；详情页写入会在退出时覆盖成陈旧值。

    /** `AlbumSong` -> `Song`（对齐 Dart `_toSong`，并携带歌手名单 / 专辑元数据） */
    fun toSong(s: AlbumSong): Song = Song(
        id = s.id,
        title = s.name,
        artist = s.artistName,
        cover = resolvedCover,
        album = resolvedName,
        albumId = resolvedId,
        duration = s.duration,
        platform = MusicPlatform.kuwo,
        qualities = listOf(Quality.k128k, Quality.k320k, Quality.flac),
        albumArtist = resolvedArtist,
        artistNames = if (s.artistNames.isNotEmpty()) s.artistNames else listOf(s.artistName),
        artistIds = s.artistIds,
        trackNumber = s.track?.toString(),
        releaseDate = st.releaseDate.ifEmpty { null },
    )

    fun play(target: AlbumSong) {
        scope.launch {
            val converted = st.songs.map { toSong(it) }
            val idx = converted.indexOfFirst { it.id == target.id }
            if (idx < 0) return@launch
            playerViewModel.setQueue(converted, idx)
            try {
                val url = fetchPlayUrlBySong(converted[idx])
                val lyric = fetchLyric(converted[idx])
                playerViewModel.updateSongPlayUrl(target.id, url)
                playerViewModel.updateSongLyric(target.id, lyric.lrc, lyric.tlyric)
            } catch (_: Exception) {
                // 对齐 Dart：播放链接获取失败静默
            }
        }
    }

    fun startSingleDownload(s: AlbumSong, quality: Quality) {
        val songObj = toSong(s)
        downloadViewModel.addTask(songObj, quality)
        val index = st.songs.indexOfFirst { it.id == s.id }
        val track = if (s.track != null) s.track.toString() else "${index + 1}"
        val releaseDate = st.releaseDate.ifEmpty { null }
        enqueueDownload(songObj.id) {
            withContext(Dispatchers.Main) { st.downloadingId = songObj.id }
            try {
                val url = fetchDownloadUrlBySong(songObj, quality)
                val lyric = fetchLyric(songObj)
                val filePath = detailDownloadSong(
                    context = context,
                    songId = songObj.id,
                    title = songObj.title,
                    artist = songObj.artist,
                    coverUrl = resolvedCover,
                    album = resolvedName,
                    downloadUrl = url,
                    quality = quality,
                    onProgress = { p -> downloadViewModel.updateProgress(songObj.id, (p * 100.0).coerceIn(0.0, 100.0)) },
                    lyric = lyric.lrc,
                    tlyric = lyric.tlyric,
                    albumArtist = resolvedArtist,
                    trackNumber = track,
                    releaseDate = releaseDate,
                )
                downloadViewModel.completeTask(songObj.id, filePath)
                playerViewModel.addDownloadedId(songObj.id)
                playerViewModel.addToQueue(songObj)
            } catch (_: Exception) {
                downloadViewModel.failTask(songObj.id)
            }
            withContext(Dispatchers.Main) { st.downloadingId = null }
        }
    }

    fun startBatchDownload(selected: List<AlbumSong>, quality: Quality) {
        st.batchDownloading = true
        for (s in selected) downloadViewModel.addTask(toSong(s), quality)
        val batchId = "album_batch_${System.currentTimeMillis()}"
        val releaseDate = st.releaseDate.ifEmpty { null }
        enqueueDownload(batchId) {
            var success = 0
            for (i in selected.indices) {
                val s = selected[i]
                val songObj = toSong(s)
                withContext(Dispatchers.Main) { st.batchProgress = "${i + 1}/${selected.size} ${s.name}" }
                downloadViewModel.updateProgress(s.id, 0.0)
                try {
                    val url = fetchDownloadUrlBySong(songObj, quality)
                    val lyric = fetchLyric(songObj)
                    val track = if (s.track != null) s.track.toString() else "${i + 1}"
                    val filePath = detailDownloadSong(
                        context = context,
                        songId = s.id,
                        title = s.name,
                        artist = s.artistName,
                        coverUrl = resolvedCover,
                        album = resolvedName,
                        downloadUrl = url,
                        quality = quality,
                        onProgress = { p -> downloadViewModel.updateProgress(s.id, (p * 100.0).coerceIn(0.0, 100.0)) },
                        lyric = lyric.lrc,
                        tlyric = lyric.tlyric,
                        albumArtist = resolvedArtist,
                        trackNumber = track,
                        releaseDate = releaseDate,
                    )
                    downloadViewModel.completeTask(s.id, filePath)
                    playerViewModel.addDownloadedId(s.id)
                    playerViewModel.addToQueue(songObj)
                    success++
                } catch (_: Exception) {
                    downloadViewModel.failTask(s.id)
                }
            }
            withContext(Dispatchers.Main) {
                st.batchDownloading = false
                st.batchProgress = "下载完成 $success/${selected.size}"
                st.selectMode = false
                st.selectedIds.clear()
            }
        }
    }

    LaunchedEffect(resolvedId) {
        st.loading = true
        st.error = ""
        st.songs = emptyList()
        st.releaseDate = ""
        st.selectMode = false
        st.selectedIds.clear()
        st.pickerVisible = false
        st.pickerSong = null
        st.downloadingId = null
        st.batchDownloading = false
        st.batchProgress = ""
        if (resolvedId.isEmpty()) {
            st.loading = false
            st.error = "缺少参数"
            return@LaunchedEffect
        }
        try {
            val result = getAlbumSongs(resolvedId)
            st.songs = result.songs
            st.releaseDate = result.pub
        } catch (e: Exception) {
            st.error = albumDetailErrText(e)
        }
        st.loading = false
    }

    BackHandler { onBack() }

    Box(modifier = modifier.fillMaxSize()) {
        // 背景：封面模糊 + 取色氛围（替代原来的纯色底）
        CoverPaletteBackdrop(
            coverUrl = resolvedCover,
            palette = palette,
            modifier = Modifier.fillMaxSize(),
        )
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            AlbumDetailHeader(
                selectMode = st.selectMode,
                onBack = onBack,
                onToggleSelectMode = {
                    st.selectMode = !st.selectMode
                    st.selectedIds.clear()
                },
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                AlbumDetailBody(
                    st = st,
                    cover = resolvedCover,
                    name = resolvedName,
                    artist = resolvedArtist,
                    tasks = tasks,
                    palette = palette,
                    currentSongId = playerState.currentSong?.id,
                    onPlay = { play(it) },
                    onDownload = { target ->
                        uiViewModel.setSongActionsVisible(true, toSong(target))
                    },
                    onToggleSelect = { id ->
                        if (!st.selectedIds.remove(id)) st.selectedIds.add(id)
                    },
                    onBatch = {
                        st.pickerSong = null
                        st.pickerVisible = true
                    },
                )
            }
        }

        val downloadingId = st.downloadingId
        if (downloadingId != null) {
            AlbumDetailDownloadingToast(
                name = st.songs.firstOrNull { it.id == downloadingId }?.name ?: "...",
            )
        }

        // ── 音质选择器 ──
        // 面板内容交给 AppShell 在「迷你播放条之上」渲染（见 DetailQualityPickerHost 注释）；
        // 本页当前未接入宿主时退化为内联渲染，行为不变。
        val pickerHost = LocalDetailQualityPickerHost.current
        val pickerOwner = remember { Any() }
        val pickerPanel: @Composable () -> Unit = {
            QualityPickerSheet(
                visible = st.pickerVisible,
                qualities = listOf(Quality.flac, Quality.k320k, Quality.k128k),
                onSelect = { quality ->
                    val target = st.pickerSong
                    st.pickerVisible = false
                    st.pickerSong = null
                    if (target != null) {
                        startSingleDownload(target, quality)
                    } else if (!st.batchDownloading) {
                        val selected = st.songs.filter { st.selectedIds.contains(it.id) }
                        if (selected.isNotEmpty()) startBatchDownload(selected, quality)
                    }
                },
                onCancel = {
                    st.pickerVisible = false
                    st.pickerSong = null
                },
            )
        }
        if (pickerHost == null) {
            pickerPanel()
        } else {
            SideEffect {
                if (st.pickerVisible) pickerHost.show(pickerOwner, pickerPanel)
            }
            DisposableEffect(pickerOwner) {
                onDispose { pickerHost.clear(pickerOwner) }
            }
        }
    }
}

/** 返回条：chevron_left + 「专辑详情」+（右侧）选择 / 取消 */
@Composable
private fun AlbumDetailHeader(
    selectMode: Boolean,
    onBack: () -> Unit,
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
                .clickableRipple(onBack)
                .padding(AppSpacing.sp4),
            contentAlignment = Alignment.Center,
        ) {
            DetailChevronLeft(modifier = Modifier.size(AppIconSize.icon22), tint = AppColors.textSecondary)
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp8))
        Text(
            text = "专辑详情",
            modifier = Modifier.weight(1f),
            color = AppColors.textPrimary,
            fontSize = AppFontSize.lg,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            modifier = Modifier
                .clickableRipple(onToggleSelectMode)
                .padding(AppSpacing.sp4),
        ) {
            Text(
                text = if (selectMode) "取消" else "选择",
                color = AppColors.primary,
                fontSize = AppFontSize.size13,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** 主体：加载中 / 错误态 / 列表（头部 + 曲目行） */
@Composable
private fun AlbumDetailBody(
    st: AlbumDetailScreenState,
    cover: String,
    name: String,
    artist: String,
    tasks: Map<String, DownloadTask>,
    palette: ColorPalette,
    currentSongId: String?,
    onPlay: (AlbumSong) -> Unit,
    onDownload: (AlbumSong) -> Unit,
    onToggleSelect: (String) -> Unit,
    onBatch: () -> Unit,
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
            // 毛玻璃列表容器：整块列表（含头部）压在封面取色背景之上
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = AppSpacing.sp12, vertical = AppSpacing.sp8)
                    .glassPanel(shape = RoundedCornerShape(AppRadius.panel)),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = AppSpacing.listBottomInset),
                ) {
                    item {
                        AlbumDetailListHeader(
                            songCount = st.songs.size,
                            cover = cover,
                            name = name,
                            artist = artist,
                            selectMode = st.selectMode,
                            selectedCount = st.selectedIds.size,
                            batchDownloading = st.batchDownloading,
                            batchProgress = st.batchProgress,
                            onBatch = onBatch,
                        )
                    }
                    items(count = st.songs.size) { index ->
                        val song = st.songs[index]
                        AlbumDetailSongRow(
                            song = song,
                            index = index,
                            selectMode = st.selectMode,
                            selected = st.selectedIds.contains(song.id),
                            isPlaying = song.id == currentSongId,
                            playingTint = palette.readablePrimary(),
                            task = tasks[song.id],
                            onPlay = onPlay,
                            onDownload = onDownload,
                            onToggle = onToggleSelect,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 列表头：封面 100（圆角 10）+ 名称 17 / 歌手 13 / 曲目数 11；
 * 多选时在其下追加 [MultiSelectDownloadBar] 作为批量下载入口。
 */
@Composable
private fun AlbumDetailListHeader(
    songCount: Int,
    cover: String,
    name: String,
    artist: String,
    selectMode: Boolean,
    selectedCount: Int,
    batchDownloading: Boolean,
    batchProgress: String,
    onBatch: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AlbumDetailListPaddingH, vertical = AppSpacing.sp16),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(AppSize.coverPlaceholder)
                    .clip(RoundedCornerShape(AppRadius.r10))
                    .background(Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                var coverFailed by remember(cover) { mutableStateOf(false) }
                if (cover.isEmpty() || coverFailed) {
                    MusicNoteGlyph(modifier = Modifier.size(AppIconSize.icon28), tint = AppColors.white24)
                } else {
                    AsyncImage(
                        model = cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        onError = { coverFailed = true },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(modifier = Modifier.width(AppSpacing.sp14))
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = name,
                    color = AppColors.textPrimary,
                    fontSize = AppFontSize.size17,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp4))
                Text(
                    text = artist,
                    color = AppColors.whiteA50,
                    fontSize = AppFontSize.size13,
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp2))
                Text(
                    text = "$songCount 首歌曲",
                    color = AppColors.whiteA25,
                    fontSize = AppFontSize.xs,
                )
            }
        }
        if (selectMode) {
            Spacer(modifier = Modifier.height(AppSpacing.sp12))
            MultiSelectDownloadBar(
                selectedCount = selectedCount,
                total = songCount,
                downloading = batchDownloading,
                progressText = batchProgress,
                onDownload = onBatch,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

/** 曲目行：多选框 + 序号 / 播放指示 + 名称 / 歌手 +（任务徽标 | 下载按钮） */
@Composable
private fun AlbumDetailSongRow(
    song: AlbumSong,
    index: Int,
    selectMode: Boolean,
    selected: Boolean,
    isPlaying: Boolean,
    playingTint: Color,
    task: DownloadTask?,
    onPlay: (AlbumSong) -> Unit,
    onDownload: (AlbumSong) -> Unit,
    onToggle: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(AlbumDetailRowHeight)
            // 正在播放 → 主色氛围底 + 左侧主色条；否则选中态浅高亮
            .then(
                when {
                    isPlaying -> Modifier.playingRowBackground(playingTint)
                    selected -> Modifier.background(AppColors.primaryA08)
                    else -> Modifier
                },
            )
            .clickableRipple { if (selectMode) onToggle(song.id) else onPlay(song) }
            .padding(horizontal = AlbumDetailListPaddingH, vertical = AppSpacing.sp10),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectMode) {
            DetailCheckBox(
                checked = selected,
                onToggle = { onToggle(song.id) },
            )
            Spacer(modifier = Modifier.width(AppSpacing.sp10))
        }
        Box(modifier = Modifier.width(AppSpacing.sp24), contentAlignment = Alignment.Center) {
            if (isPlaying) {
                PlayingIndicator(tint = playingTint)
            } else {
                Text(
                    text = "${index + 1}",
                    color = AppColors.whiteA25,
                    fontSize = AppFontSize.sm,
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
                text = song.name,
                color = if (isPlaying) playingTint else AppColors.textPrimary,
                fontSize = AppFontSize.base,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp1))
            Text(
                text = song.artistName,
                color = AppColors.textTertiary,
                fontSize = AppFontSize.xs,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (task != null && task.status != DownloadStatus.completed) {
            AlbumDetailTaskBadge(task = task)
        } else if (!selectMode) {
            Box(
                modifier = Modifier
                    .clickableRipple { onDownload(song) }
                    .padding(AppSpacing.sp4),
                contentAlignment = Alignment.Center,
            ) {
                SongMoreGlyph(
                    modifier = Modifier.size(AppIconSize.icon16),
                    tint = AppColors.textDisabled,
                )
            }
        }
    }
}

/** 下载任务状态徽标（对齐 Dart `_downloadIndicator`） */
@Composable
private fun AlbumDetailTaskBadge(task: DownloadTask) {
    when (task.status) {
        DownloadStatus.completed -> Unit

        DownloadStatus.error -> {
            DetailErrorOutline(modifier = Modifier.size(AppIconSize.icon16), tint = AppColors.danger)
        }

        DownloadStatus.downloading -> {
            Column(
                modifier = Modifier.width(36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AlbumDetailProgressRing(modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.height(AppSpacing.sp1))
                Text(
                    text = "${task.progress.roundToInt()}%",
                    color = AppColors.primary,
                    fontSize = AppFontSize.xxs,
                )
            }
        }

        // pending / paused / cancelled 均按 Dart 的「等待中」呈现
        else -> {
            Row(
                modifier = Modifier.padding(end = AppSpacing.sp4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .border(width = AppBorder.thick, color = AppColors.whiteA15, shape = CircleShape),
                )
                Spacer(modifier = Modifier.width(AppSpacing.sp2))
                Text(
                    text = "等待中",
                    color = AppColors.whiteA25,
                    fontSize = AppFontSize.size10,
                )
            }
        }
    }
}

/** 14dp 进度环：轨道 `primaryStrong @0.2` + 主色旋转弧（对齐 Dart 的 `backgroundColor` 写法） */
@Composable
private fun AlbumDetailProgressRing(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = AppBorder.thick.toPx()
            drawCircle(
                color = AppColors.primaryStrongA20,
                radius = (size.minDimension - stroke) / 2f,
                style = Stroke(width = stroke),
            )
        }
        CircularProgressIndicator(
            modifier = Modifier.fillMaxSize(),
            color = AppColors.primary,
            strokeWidth = AppBorder.thick,
        )
    }
}

/** 「正在下载 xxx」轻提示（底部 100，避开迷你播放条） */
@Composable
private fun AlbumDetailDownloadingToast(name: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = AppSpacing.sp20, end = AppSpacing.sp20, bottom = AppSpacing.sheetBottomInset),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(AppRadius.card))
                .background(AppColors.surface95)
                .padding(vertical = AppSpacing.sp10, horizontal = AppSpacing.sp20),
        ) {
            Text(
                text = "正在下载 $name",
                color = AppColors.primary,
                fontSize = AppFontSize.base,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** 去掉 `Exception: ` 前缀的错误文案（对齐 Dart `_errText`） */
private fun albumDetailErrText(e: Throwable): String =
    (e.message ?: "").replaceFirst("Exception: ", "")

