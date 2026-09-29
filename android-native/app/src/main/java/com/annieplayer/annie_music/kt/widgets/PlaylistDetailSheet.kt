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
import com.annieplayer.annie_music.kt.core.state.DownloadViewModel
import com.annieplayer.annie_music.kt.core.state.PlayerViewModel
import com.annieplayer.annie_music.kt.core.state.PlaylistDetailData
import com.annieplayer.annie_music.kt.core.state.UiState
import com.annieplayer.annie_music.kt.core.state.UiViewModel
import com.annieplayer.annie_music.kt.core.util.bestQuality
import com.annieplayer.annie_music.kt.data.LyricResult
import com.annieplayer.annie_music.kt.data.PlaylistService
import com.annieplayer.annie_music.kt.data.enrichSong
import com.annieplayer.annie_music.kt.data.fetchDownloadUrlBySong
import com.annieplayer.annie_music.kt.data.fetchLyric
import com.annieplayer.annie_music.kt.data.fetchPlayUrlBySong
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
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
import kotlinx.coroutines.launch

/**
 * 歌单详情浮层（1:1 移植 Flutter `lib/widgets/playlist_detail_sheet.dart`，对齐 RN 版
 * components/PlaylistDetailOverlay.tsx，RN zIndex 150）。
 *
 * 结构：返回条（含「多选 / 全选」）→ 歌单头部（封面 100 + 名称 / 创建者 / 曲目数 + 批量按钮）
 * → 歌曲列表（60dp 行）。
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

    var pickerVisible by mutableStateOf(false)
    var pickerSong by mutableStateOf<Song?>(null)
    var batchPickerVisible by mutableStateOf(false)
    var batchDownloading by mutableStateOf(false)
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

    /** PlaylistDetailData.platform 为字符串（'kuwo' 或 'kuwo:'），解析为枚举 */
    val platform = remember(data.platform) {
        val raw = data.platform.replace(":", "")
        MusicPlatform.entries.firstOrNull { it.name == raw } ?: MusicPlatform.kuwo
    }

    suspend fun play(song: Song) {
        val idx = st.songs.indexOfFirst { it.id == song.id }
        if (idx < 0) return
        var queue = st.songs
        if (song.platform == MusicPlatform.qq || song.platform == MusicPlatform.kugou) {
            try {
                val url = fetchPlayUrlBySong(song)
                queue = st.songs.map { if (it.id == song.id) it.copyWith(playUrl = url) else it }
            } catch (_: Exception) {
                // 对齐 Dart：解析失败保留原队列
            }
        }
        playerViewModel.setQueue(queue, idx)
        try {
            val lyric = fetchLyric(song)
            playerViewModel.updateSongLyric(song.id, lyric.lrc, lyric.tlyric)
        } catch (_: Exception) {
            // 对齐 Dart：拉取失败静默忽略
        }
    }

    fun openPicker(song: Song) {
        st.pickerSong = song
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
        downloadViewModel.addTask(song, quality)
        try {
            val url = fetchDownloadUrlBySong(song, quality)
            val lyric = if (song.lyric != null && song.tlyric != null) {
                LyricResult(lrc = song.lyric, tlyric = song.tlyric)
            } else {
                fetchLyric(song)
            }
            val extra = enrichSong(song)
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

    suspend fun batchDownload(quality: Quality) {
        st.batchPickerVisible = false
        st.batchDownloading = true
        val songs = st.songs.filter { st.selectedIds.contains(it.id) }
        for (s in songs) downloadViewModel.addTask(s, quality)
        for (song in songs) {
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
        st.selectMode = false
        st.selectedIds.clear()
    }

    LaunchedEffect(Unit) {
        st.loading = true
        st.error = ""
        st.hasPlaylist = false
        st.selectMode = false
        st.selectedIds.clear()
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
        qualityPicker = uiState.pickerVisible || st.pickerVisible || st.batchPickerVisible,
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

        // ── 面板：150ms 从底部滑入 ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AppColors.background)
                .graphicsLayer { translationY = slide.value * size.height },
        ) {
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
                        onPlay = { scope.launch { play(it) } },
                        onDownload = { openPicker(it) },
                        onToggle = { toggleSelect(it) },
                        onBatch = { st.batchPickerVisible = true },
                    )
                }
            }
        }

        // ── 音质选择器（复用 QualityPickerSheet；单曲按歌曲支持档位） ──
        QualityPickerSheet(
            visible = st.pickerVisible,
            qualities = st.pickerSong?.qualities ?: emptyList(),
            onSelect = { q -> scope.launch { downloadWithQuality(q) } },
            onCancel = {
                st.pickerVisible = false
                st.pickerSong = null
            },
            avoidMiniPlayer = true,
            miniPlayerVisible = playerState.currentSong != null && !uiState.playerOverlayVisible,
        )
        // ── 音质选择器（批量：固定三档） ──
        QualityPickerSheet(
            visible = st.batchPickerVisible,
            qualities = listOf(Quality.flac, Quality.k320k, Quality.k128k),
            onSelect = { q -> scope.launch { batchDownload(q) } },
            onCancel = { st.batchPickerVisible = false },
            avoidMiniPlayer = true,
            miniPlayerVisible = playerState.currentSong != null && !uiState.playerOverlayVisible,
        )
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
                .clickableNoRipple(onClose)
                .padding(AppSpacing.sp4),
            contentAlignment = Alignment.Center,
        ) {
            DetailChevronLeft(modifier = Modifier.size(AppIconSize.icon22), tint = AppColors.textSecondary)
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp8))
        Text(
            text = "歌单详情",
            modifier = Modifier.weight(1f),
            color = AppColors.textPrimary,
            fontSize = AppFontSize.lg,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
}

@Composable
private fun PlaylistDetailBody(
    st: PlaylistDetailState,
    onPlay: (Song) -> Unit,
    onDownload: (Song) -> Unit,
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
                    PlaylistDetailListHeader(
                        st = st,
                        onBatch = onBatch,
                    )
                }
                items(count = st.songs.size) { index ->
                    val song = st.songs[index]
                    PlaylistDetailSongRow(
                        song = song,
                        index = index,
                        selectMode = st.selectMode,
                        selected = st.selectedIds.contains(song.id),
                        onPlay = onPlay,
                        onDownload = onDownload,
                        onToggle = onToggle,
                    )
                }
            }
        }
    }
}

/** 列表头：封面 100（圆角 10）+ 名称 17 w700 + 创建者 13 + 曲目数 11 + 批量按钮 */
@Composable
private fun PlaylistDetailListHeader(
    st: PlaylistDetailState,
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
            var coverFailed by remember(st.cover) { mutableStateOf(false) }
            if (st.cover.isEmpty() || coverFailed) {
                MusicNoteGlyph(modifier = Modifier.size(AppIconSize.icon28), tint = AppColors.textDisabled)
            } else {
                AsyncImage(
                    model = st.cover,
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
                text = st.name,
                color = AppColors.textPrimary,
                fontSize = AppFontSize.size17,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp4))
            Text(
                text = st.creator,
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
            PlaylistDetailBatchButton(
                batchDownloading = st.batchDownloading,
                count = st.selectedIds.size,
                onTap = onBatch,
            )
        }
    }
}

/** 批量下载按钮（80dp 最小宽；下载中只显示 16dp 环） */
@Composable
private fun PlaylistDetailBatchButton(
    batchDownloading: Boolean,
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
            CircularProgressIndicator(
                modifier = Modifier.size(AppIconSize.icon16),
                color = AppColors.white,
                strokeWidth = 2.dp,
            )
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

/** 歌曲行：多选框 + 序号 + 名称 /「歌手 · 专辑」+ 下载按钮 */
@Composable
private fun PlaylistDetailSongRow(
    song: Song,
    index: Int,
    selectMode: Boolean,
    selected: Boolean,
    onPlay: (Song) -> Unit,
    onDownload: (Song) -> Unit,
    onToggle: (String) -> Unit,
) {
    val subtitle = if (song.album.isNullOrEmpty()) song.artist else "${song.artist} · ${song.album}"

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
                text = song.title,
                color = AppColors.textPrimary,
                fontSize = AppFontSize.base,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp1))
            Text(
                text = subtitle,
                color = AppColors.textTertiary,
                fontSize = AppFontSize.xs,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!selectMode) {
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
