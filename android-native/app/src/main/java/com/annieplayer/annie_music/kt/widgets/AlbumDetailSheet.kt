package com.annieplayer.annie_music.kt.widgets

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.annieplayer.annie_music.kt.core.routing.BackTarget
import com.annieplayer.annie_music.kt.core.routing.OverlayVisibilityFlags
import com.annieplayer.annie_music.kt.core.routing.handleBack
import com.annieplayer.annie_music.kt.core.state.AlbumDetailData
import com.annieplayer.annie_music.kt.core.state.DownloadViewModel
import com.annieplayer.annie_music.kt.core.state.PlayerViewModel
import com.annieplayer.annie_music.kt.core.state.UiState
import com.annieplayer.annie_music.kt.core.state.UiViewModel
import com.annieplayer.annie_music.kt.data.LyricResult
import com.annieplayer.annie_music.kt.data.fetchAlbumSongsByPlatform
import com.annieplayer.annie_music.kt.data.fetchDownloadUrlBySong
import com.annieplayer.annie_music.kt.data.fetchLyric
import com.annieplayer.annie_music.kt.data.fetchPlayUrlBySong
import com.annieplayer.annie_music.kt.data.model.AlbumSong
import com.annieplayer.annie_music.kt.data.model.DownloadStatus
import com.annieplayer.annie_music.kt.data.model.DownloadTask
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 专辑详情浮层（1:1 移植 Flutter `lib/widgets/album_detail_sheet.dart`，对齐 RN 版
 * components/AlbumDetailOverlay.tsx）。
 *
 * 结构：返回条（含「多选 / 全选」）→ 专辑头部（封面 100 + 名称 / 歌手 / 曲目数 + 批量按钮）
 * → 曲目列表（60dp 行，行内展示下载任务状态）。
 *
 * 关键行为：
 * - 打开后**延迟 150ms** 再请求曲目（对齐 RN 行为），失败显示错误态。
 * - 多选模式：头部「全选 / 取消全选」，批量下载支持进度文案。
 * - `AlbumSong` -> `Song` 必须携带 `artistNames` / `artistIds`（⋮「跳歌手」才能秒开），
 *   并带上 `albumArtist` / `trackNumber` / `releaseDate`。
 * - 返回手势：多选模式优先退出；更高层浮层可见时让位（见 core/routing/OverlayStack.kt）。
 */
@Composable
fun AlbumDetailSheet(
    uiViewModel: UiViewModel,
    playerViewModel: PlayerViewModel,
    downloadViewModel: DownloadViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by uiViewModel.state.collectAsState()
    val data = uiState.albumDetailData
    if (!uiState.albumDetailVisible || data == null) return

    key(data.id) {
        AlbumDetailContent(
            data = data,
            uiState = uiState,
            uiViewModel = uiViewModel,
            playerViewModel = playerViewModel,
            downloadViewModel = downloadViewModel,
            modifier = modifier,
        )
    }
}

/** 专辑详情内部可变状态（对齐 Dart `_AlbumDetailViewState` 的字段） */
private class AlbumDetailState {
    var loading by mutableStateOf(true)
    var error by mutableStateOf("")
    var songs by mutableStateOf<List<AlbumSong>>(emptyList())
    var releaseDate by mutableStateOf("")

    var selectMode by mutableStateOf(false)
    val selectedIds = mutableStateListOf<String>()

    var pickerVisible by mutableStateOf(false)
    var pickerSong by mutableStateOf<Song?>(null)
    var downloadingId by mutableStateOf<String?>(null)
    var batchDownloading by mutableStateOf(false)
    var batchProgress by mutableStateOf("")
}

@Composable
private fun AlbumDetailContent(
    data: AlbumDetailData,
    uiState: UiState,
    uiViewModel: UiViewModel,
    playerViewModel: PlayerViewModel,
    downloadViewModel: DownloadViewModel,
    modifier: Modifier = Modifier,
) {
    val st = remember { AlbumDetailState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val tasks by downloadViewModel.state.collectAsState()
    val playerState by playerViewModel.state.collectAsState()

    /** `AlbumSong` -> `Song`（对齐 Dart `_toSong`，必须携带歌手 ids / 名单） */
    fun toSong(s: AlbumSong): Song = Song(
        id = s.id,
        title = s.name,
        artist = s.artistName,
        cover = data.cover,
        album = data.name,
        albumId = data.id,
        duration = s.duration,
        platform = data.platform,
        qualities = listOf(Quality.k128k, Quality.k320k, Quality.flac),
        albumArtist = data.artist,
        artistNames = if (s.artistNames.isNotEmpty()) s.artistNames else listOf(s.artistName),
        artistIds = s.artistIds,
        trackNumber = s.track?.toString(),
        releaseDate = st.releaseDate.ifEmpty { null },
    )

    fun load() {
        st.loading = true
        st.error = ""
        st.songs = emptyList()
        st.selectMode = false
        st.selectedIds.clear()
        st.batchDownloading = false
        st.batchProgress = ""
        st.pickerVisible = false
        st.pickerSong = null
        st.downloadingId = null
        st.releaseDate = ""
    }

    suspend fun play(target: AlbumSong) {
        val converted = st.songs.map { toSong(it) }
        val idx = converted.indexOfFirst { it.id == target.id }
        if (idx < 0) return
        playerViewModel.setQueue(converted, idx)
        try {
            val url = fetchPlayUrlBySong(converted[idx])
            val lyric = fetchLyric(converted[idx])
            playerViewModel.updateSongPlayUrl(target.id, url)
            playerViewModel.updateSongLyric(target.id, lyric.lrc, lyric.tlyric)
        } catch (_: Exception) {
            // 对齐 Dart：拉取失败静默忽略
        }
    }

    fun openPicker(s: AlbumSong) {
        st.pickerSong = toSong(s)
        st.pickerVisible = true
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

    suspend fun downloadWithQuality(quality: Quality) {
        val song = st.pickerSong ?: return
        st.pickerVisible = false
        st.pickerSong = null
        st.downloadingId = song.id
        downloadViewModel.addTask(song, quality)
        try {
            val url = fetchDownloadUrlBySong(song, quality)
            val lyric = if (song.lyric != null && song.tlyric != null) {
                LyricResult(lrc = song.lyric, tlyric = song.tlyric)
            } else {
                fetchLyric(song)
            }
            val idx = st.songs.indexOfFirst { it.id == song.id }
            val trackValue = if (idx >= 0) st.songs[idx].track else null
            val track = if (trackValue != null) trackValue.toString() else "${idx + 1}"
            val filePath = detailDownloadSong(
                context = context,
                songId = song.id,
                title = song.title,
                artist = song.artist,
                coverUrl = song.cover,
                album = song.album,
                downloadUrl = url,
                quality = quality,
                onProgress = { p -> downloadViewModel.updateProgress(song.id, (p * 100).coerceIn(0.0, 100.0)) },
                lyric = lyric.lrc,
                tlyric = lyric.tlyric,
                albumArtist = data.artist,
                trackNumber = track,
                releaseDate = st.releaseDate.ifEmpty { null },
            )
            downloadViewModel.completeTask(song.id, filePath)
            playerViewModel.addDownloadedId(song.id)
            playerViewModel.addToQueue(song)
        } catch (_: Exception) {
            downloadViewModel.failTask(song.id)
        } finally {
            st.downloadingId = null
        }
    }

    suspend fun batchDownload(quality: Quality) {
        st.pickerVisible = false
        st.batchDownloading = true
        val selected = st.songs.filter { st.selectedIds.contains(it.id) }
        for (s in selected) downloadViewModel.addTask(toSong(s), quality)
        var success = 0
        for (i in selected.indices) {
            val s = selected[i]
            val song = toSong(s)
            st.batchProgress = "${i + 1}/${selected.size} ${s.name}"
            downloadViewModel.updateProgress(s.id, 0.0)
            try {
                val url = fetchDownloadUrlBySong(song, quality)
                val lyric = fetchLyric(song)
                val track = if (s.track != null) s.track.toString() else "${i + 1}"
                val filePath = detailDownloadSong(
                    context = context,
                    songId = s.id,
                    title = s.name,
                    artist = s.artistName,
                    coverUrl = data.cover,
                    album = data.name,
                    downloadUrl = url,
                    quality = quality,
                    onProgress = { p -> downloadViewModel.updateProgress(s.id, (p * 100).coerceIn(0.0, 100.0)) },
                    lyric = lyric.lrc,
                    tlyric = lyric.tlyric,
                    albumArtist = data.artist,
                    trackNumber = track,
                    releaseDate = st.releaseDate.ifEmpty { null },
                )
                downloadViewModel.completeTask(s.id, filePath)
                playerViewModel.addDownloadedId(s.id)
                playerViewModel.addToQueue(song)
                success++
            } catch (_: Exception) {
                downloadViewModel.failTask(s.id)
            }
        }
        st.batchDownloading = false
        st.batchProgress = "下载完成 $success/${selected.size}"
        st.selectMode = false
        st.selectedIds.clear()
    }

    LaunchedEffect(Unit) {
        load()
        // RN 版打开后延迟 150ms 再请求
        delay(150)
        try {
            val result = fetchAlbumSongsByPlatform(data.platform, data.id)
            st.songs = result.songs
            st.releaseDate = result.pub
        } catch (e: Exception) {
            val msg = (e.message ?: "").replaceFirst("Exception: ", "")
            st.error = msg.ifEmpty { "加载失败" }
        } finally {
            st.loading = false
        }
    }

    // 返回手势：多选模式优先退出；更高层浮层可见时让位（见 OverlayStack.kt）
    val higherFlags = OverlayVisibilityFlags(
        songActions = uiState.songActionsVisible,
        artistPicker = uiState.artistPickerVisible,
        qualityPicker = uiState.pickerVisible || st.pickerVisible,
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
            uiViewModel.setAlbumDetailVisible(false)
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

        // ── 面板：150ms 从底部滑入 ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AppColors.background)
                .graphicsLayer { translationY = slide.value * size.height },
        ) {
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                AlbumDetailHeader(
                    selectMode = st.selectMode,
                    selectedAll = st.selectedIds.size == st.songs.size,
                    onClose = { uiViewModel.setAlbumDetailVisible(false) },
                    onToggleSelectAll = { toggleSelectAll() },
                    onToggleSelectMode = { toggleSelectMode() },
                )
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    AlbumDetailBody(
                        st = st,
                        data = data,
                        tasks = tasks,
                        onPlay = { scope.launch { play(it) } },
                        onDownload = { openPicker(it) },
                        onToggle = { toggleSelect(it) },
                        onBatch = { st.pickerVisible = true },
                    )
                }
            }
        }

        // ── 音质选择器（复用 QualityPickerSheet）：pickerSong 为空表示本次选择用于批量下载 ──
        QualityPickerSheet(
            visible = st.pickerVisible,
            qualities = emptyList(),
            onSelect = { q ->
                scope.launch {
                    if (st.pickerSong != null) downloadWithQuality(q) else batchDownload(q)
                }
            },
            onCancel = {
                st.pickerVisible = false
                st.pickerSong = null
            },
            avoidMiniPlayer = true,
            miniPlayerVisible = playerState.currentSong != null && !uiState.playerOverlayVisible,
        )

        // ── 「正在下载」轻提示 ──
        val downloadingId = st.downloadingId
        if (downloadingId != null) {
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
                        text = "正在下载 ${albumDetailSongNameOf(st, downloadingId)}",
                        color = AppColors.primary,
                        fontSize = AppFontSize.base,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/** 查找当前列表内歌曲名（对齐 Dart `_songNameOf`） */
private fun albumDetailSongNameOf(st: AlbumDetailState, id: String): String {
    val idx = st.songs.indexOfFirst { it.id == id }
    return if (idx >= 0) st.songs[idx].name else "..."
}

/** 返回条：chevron_left + 「专辑详情」+（多选时）全选 / 取消全选 + 多选 / 取消 */
@Composable
private fun AlbumDetailHeader(
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
                .clickableNoRipple(onClose)
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
        if (selectMode) {
            Box(
                modifier = Modifier
                    .clickableNoRipple(onToggleSelectAll)
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
                .clickableNoRipple(onToggleSelectMode)
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

@Composable
private fun AlbumDetailBody(
    st: AlbumDetailState,
    data: AlbumDetailData,
    tasks: Map<String, DownloadTask>,
    onPlay: (AlbumSong) -> Unit,
    onDownload: (AlbumSong) -> Unit,
    onToggle: (String) -> Unit,
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
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = AppSpacing.listBottomInset),
            ) {
                item {
                    AlbumDetailListHeader(
                        st = st,
                        data = data,
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
                        task = tasks[song.id],
                        onPlay = onPlay,
                        onDownload = onDownload,
                        onToggle = onToggle,
                    )
                }
            }
        }
    }
}

/** 列表头：封面 100（圆角 10）+ 名称 17 w700 + 歌手 13 + 曲目数 11 + 批量按钮 */
@Composable
private fun AlbumDetailListHeader(
    st: AlbumDetailState,
    data: AlbumDetailData,
    onBatch: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(AppSpacing.sp16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(AppSize.coverPlaceholder)
                .clip(RoundedCornerShape(AppRadius.r10))
                .background(AppColors.whiteA03),
            contentAlignment = Alignment.Center,
        ) {
            var coverFailed by remember(data.id) { mutableStateOf(false) }
            if (data.cover.isEmpty() || coverFailed) {
                MusicNoteGlyph(modifier = Modifier.size(AppIconSize.icon28), tint = AppColors.textDisabled)
            } else {
                AsyncImage(
                    model = data.cover,
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
                text = data.name,
                color = AppColors.textPrimary,
                fontSize = AppFontSize.size17,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp4))
            Text(
                text = data.artist,
                color = AppColors.whiteA50,
                fontSize = AppFontSize.size13,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp2))
            Text(
                text = "${st.songs.size} 首歌曲",
                color = AppColors.whiteA25,
                fontSize = AppFontSize.xs,
            )
        }
        if (st.selectMode && st.selectedIds.isNotEmpty()) {
            Spacer(modifier = Modifier.width(AppSpacing.sp8))
            AlbumDetailBatchButton(
                batchDownloading = st.batchDownloading,
                progress = st.batchProgress,
                count = st.selectedIds.size,
                onTap = onBatch,
            )
        }
    }
}

/** 批量下载按钮（80dp 最小宽；下载中显示 16dp 环 + 进度文案） */
@Composable
private fun AlbumDetailBatchButton(
    batchDownloading: Boolean,
    progress: String,
    count: Int,
    onTap: () -> Unit,
) {
    Box(
        modifier = Modifier
            .graphicsLayer { alpha = if (batchDownloading) 0.5f else 1f }
            .widthIn(min = 80.dp)
            .clip(RoundedCornerShape(AppRadius.r10))
            .background(AppColors.primary)
            .then(if (batchDownloading) Modifier else Modifier.clickableNoRipple(onTap))
            .padding(vertical = AppSpacing.sp10, horizontal = AppSpacing.sp14),
        contentAlignment = Alignment.Center,
    ) {
        if (batchDownloading) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(
                    modifier = Modifier.size(AppIconSize.icon16),
                    color = AppColors.white,
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp2))
                Text(
                    text = progress,
                    color = AppColors.white,
                    fontSize = AppFontSize.size10,
                    fontWeight = FontWeight.Medium,
                )
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                DetailDownloadSolid(modifier = Modifier.size(AppIconSize.icon20), tint = AppColors.white)
                Spacer(modifier = Modifier.height(AppSpacing.sp2))
                Text(
                    text = "$count",
                    color = AppColors.white,
                    fontSize = AppFontSize.xs,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** 曲目行：多选框 + 序号 + 名称 / 歌手 +（任务徽标 | 下载按钮） */
@Composable
private fun AlbumDetailSongRow(
    song: AlbumSong,
    index: Int,
    selectMode: Boolean,
    selected: Boolean,
    task: DownloadTask?,
    onPlay: (AlbumSong) -> Unit,
    onDownload: (AlbumSong) -> Unit,
    onToggle: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(AppSize.detailRowHeight)
            .background(if (selected) AppColors.primaryA08 else Color.Transparent)
            .clickableNoRipple { if (selectMode) onToggle(song.id) else onPlay(song) }
            .padding(horizontal = AppSpacing.sp16, vertical = AppSpacing.sp10),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectMode) {
            if (selected) {
                CheckSquareIcon(modifier = Modifier.size(AppSpacing.sp20))
            } else {
                DetailCheckBoxBlank(modifier = Modifier.size(AppSpacing.sp20), tint = AppColors.whiteA25)
            }
            Spacer(modifier = Modifier.width(AppSpacing.sp10))
        }
        Box(
            modifier = Modifier.width(AppSpacing.sp24),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "${index + 1}",
                color = AppColors.whiteA25,
                fontSize = AppFontSize.sm,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp10))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = song.name,
                color = AppColors.textPrimary,
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
                    .clickableNoRipple { onDownload(song) }
                    .padding(AppSpacing.sp4),
                contentAlignment = Alignment.Center,
            ) {
                DownloadIconPlaceholder(
                    modifier = Modifier.size(AppIconSize.icon16),
                    tint = AppColors.textDisabled,
                )
            }
        }
    }
}

/** 下载任务状态徽标（对齐 Dart `_TaskBadge`） */
@Composable
private fun AlbumDetailTaskBadge(task: DownloadTask) {
    when (task.status) {
        DownloadStatus.completed -> Unit

        DownloadStatus.error -> {
            DetailErrorSolid(modifier = Modifier.size(AppIconSize.icon16), tint = AppColors.danger)
        }

        DownloadStatus.downloading -> {
            Column(
                modifier = Modifier.width(36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = AppColors.primary,
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp1))
                Text(
                    text = "${task.progress.roundToInt()}%",
                    color = AppColors.primary,
                    fontSize = AppFontSize.xxs,
                )
            }
        }

        // pending / paused / cancelled 均按 RN 的「等待中」呈现
        else -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .border(width = 2.dp, color = AppColors.whiteA15, shape = CircleShape),
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
