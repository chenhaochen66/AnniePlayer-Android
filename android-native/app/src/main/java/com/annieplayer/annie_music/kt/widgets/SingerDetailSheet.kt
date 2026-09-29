package com.annieplayer.annie_music.kt.widgets

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
import com.annieplayer.annie_music.kt.core.state.SingerDetailData
import com.annieplayer.annie_music.kt.core.state.UiState
import com.annieplayer.annie_music.kt.core.state.UiViewModel
import com.annieplayer.annie_music.kt.data.DownloadDirService
import com.annieplayer.annie_music.kt.data.LyricResult
import com.annieplayer.annie_music.kt.data.enrichSong
import com.annieplayer.annie_music.kt.data.fetchArtistAlbumsByPlatform
import com.annieplayer.annie_music.kt.data.fetchArtistAvatarByPlatform
import com.annieplayer.annie_music.kt.data.fetchArtistSongsByPlatform
import com.annieplayer.annie_music.kt.data.fetchDownloadUrlBySong
import com.annieplayer.annie_music.kt.data.fetchLyric
import com.annieplayer.annie_music.kt.data.fetchPlayUrlBySong
import com.annieplayer.annie_music.kt.data.model.Album
import com.annieplayer.annie_music.kt.data.model.DownloadStatus
import com.annieplayer.annie_music.kt.data.model.DownloadTask
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.network.globalOkHttp
import com.annieplayer.annie_music.kt.data.sources.buildAlbumCoverUrl
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * 歌手详情浮层（1:1 移植 Flutter `lib/widgets/singer_detail_sheet.dart`，对齐 RN 版
 * components/SingerDetailOverlay.tsx）。
 *
 * 结构（自上而下）：返回条 → 歌手头像 / 名字 / 歌曲数 → （多选时）待下载选择条 →
 * 「歌曲 / 专辑」分段 → 内容区（分页歌曲列表，或 2 列专辑网格）。
 *
 * 关键行为：
 * - 每次以 `data.id` 为 key 重建内部状态（对应 Flutter 的 ValueKey）。
 * - 歌曲每页 20 首，底部渲染分页器（对齐 RN 的页段算法）。
 * - 行点击播放；长按进入多选（带震动反馈）；多选模式下点击切换选中。
 * - 单曲 / 批量下载均先弹出音质选择器（150ms 淡入）。
 * - 返回手势：先关本页音质选择器；更高层浮层（播放页 / 队列 / ⋮ / 多歌手 / 下载队列 /
 *   专辑详情）可见时本页不响应（对齐 Flutter 各 PopScope 的「按层让位」规则，
 *   优先级数据见 core/routing/OverlayStack.kt）。
 * - 头像为空时按平台补拉一次（从播放页 ⋮ 跳进来时 data.picture 是空的）。
 *
 * 本文件同时收纳三个详情浮层共用的自绘图标（material-icons-core 之外的形状）与
 * [detailDownloadSong] 下载助手；音质选择器复用共用组件 [QualityPickerSheet]。
 */
@Composable
fun SingerDetailSheet(
    uiViewModel: UiViewModel,
    playerViewModel: PlayerViewModel,
    downloadViewModel: DownloadViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by uiViewModel.state.collectAsState()
    val data = uiState.singerDetailData
    if (!uiState.singerDetailVisible || data == null) return

    key(data.id) {
        SingerDetailContent(
            data = data,
            uiState = uiState,
            uiViewModel = uiViewModel,
            playerViewModel = playerViewModel,
            downloadViewModel = downloadViewModel,
            modifier = modifier,
        )
    }
}

/** 歌手详情内部可变状态（对齐 Dart `_SingerDetailViewState` 的字段） */
private class SingerDetailState {
    var tab by mutableStateOf("song")

    var songs by mutableStateOf<List<Song>>(emptyList())
    var songTotal by mutableStateOf(0)
    var songPage by mutableStateOf(1)
    var songLoading by mutableStateOf(false)
    var songError by mutableStateOf("")

    var albums by mutableStateOf<List<Album>>(emptyList())
    var albumLoading by mutableStateOf(false)
    var albumSortAsc by mutableStateOf(false)

    val selectedSongs = mutableStateListOf<Song>()

    var pickerVisible by mutableStateOf(false)
    var pickerSong by mutableStateOf<Song?>(null)
    var downloadingId by mutableStateOf<String?>(null)
    var batchPickerVisible by mutableStateOf(false)
    var batchDownloading by mutableStateOf(false)

    var avatarUrl by mutableStateOf("")
}

@Composable
private fun SingerDetailContent(
    data: SingerDetailData,
    uiState: UiState,
    uiViewModel: UiViewModel,
    playerViewModel: PlayerViewModel,
    downloadViewModel: DownloadViewModel,
    modifier: Modifier = Modifier,
) {
    val st = remember { SingerDetailState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val playerState by playerViewModel.state.collectAsState()
    val tasks by downloadViewModel.state.collectAsState()

    val artistName = data.name

    suspend fun loadSongs(page: Int) {
        st.songPage = page
        st.songs = emptyList()
        st.songLoading = true
        st.songError = ""
        try {
            val result = fetchArtistSongsByPlatform(data.platform, data.id, page)
            st.songs = result.songs
            st.songTotal = result.total
        } catch (e: Exception) {
            st.songError = e.message ?: "加载失败"
        } finally {
            st.songLoading = false
        }
    }

    suspend fun loadAlbums() {
        st.albumLoading = true
        try {
            val result = fetchArtistAlbumsByPlatform(data.platform, data.id, artistName = data.name)
            st.albums = result
        } catch (_: Exception) {
            st.albums = emptyList()
        } finally {
            st.albumLoading = false
        }
    }

    suspend fun loadAvatarIfNeeded() {
        if (data.picture.isNotEmpty()) return
        try {
            val url = fetchArtistAvatarByPlatform(data.platform, data.id, data.name)
            if (url.isNotEmpty()) st.avatarUrl = url
        } catch (_: Exception) {
            // 对齐 Dart：补头像失败保持占位
        }
    }

    fun switchTab(tab: String) {
        if (st.tab == tab) return
        st.tab = tab
        if (tab == "album") scope.launch { loadAlbums() }
    }

    fun toggleSelect(song: Song) {
        val idx = st.selectedSongs.indexOfFirst { it.id == song.id }
        if (idx >= 0) st.selectedSongs.removeAt(idx) else st.selectedSongs.add(song)
    }

    fun openPicker(song: Song) {
        st.pickerSong = song
        st.pickerVisible = true
    }

    suspend fun playSong(song: Song) {
        val idx = st.songs.indexOfFirst { it.id == song.id }
        if (idx < 0) return
        playerViewModel.setQueue(st.songs, idx)
        try {
            val url = fetchPlayUrlBySong(song)
            val lyric = fetchLyric(song)
            playerViewModel.updateSongPlayUrl(song.id, url)
            playerViewModel.updateSongLyric(song.id, lyric.lrc, lyric.tlyric)
        } catch (_: Exception) {
            // 对齐 Dart：拉取失败静默忽略
        }
    }

    fun openAlbum(album: Album) {
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
                albumArtist = song.albumArtist ?: artistName,
                trackNumber = song.trackNumber ?: extra["trackNumber"] as? String,
                discNumber = song.discNumber,
                releaseDate = song.releaseDate ?: extra["releaseDate"] as? String,
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
        st.batchPickerVisible = false
        st.batchDownloading = true
        val songs = st.selectedSongs.toList()
        for (s in songs) downloadViewModel.addTask(s, quality)
        for (song in songs) {
            try {
                val url = fetchDownloadUrlBySong(song, quality)
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
                    quality = quality,
                    onProgress = { p -> downloadViewModel.updateProgress(song.id, (p * 100).coerceIn(0.0, 100.0)) },
                    lyric = lyric.lrc,
                    tlyric = lyric.tlyric,
                    albumArtist = song.albumArtist ?: artistName,
                    trackNumber = song.trackNumber ?: extra["trackNumber"] as? String,
                    discNumber = song.discNumber,
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
        st.selectedSongs.clear()
    }

    LaunchedEffect(Unit) {
        loadSongs(1)
        loadAvatarIfNeeded()
    }

    // 返回手势：本页音质选择器优先；更高层浮层可见时让位（见 OverlayStack.kt）
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
    val detailIsTop = handleBack(higherFlags) == BackTarget.DETAIL &&
        !uiState.albumDetailVisible &&
        !uiState.downloadQueueVisible
    BackHandler(enabled = detailIsTop) {
        uiViewModel.setSingerDetailVisible(false)
    }

    // 进出场：150ms + 线性（AppDuration.sheet + AppEasing.linear，与其它浮层同规格）
    val slide = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        slide.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.linear),
        )
    }

    val totalPages = (st.songTotal + PageSize - 1) / PageSize
    val pageRange = singerDetailPageRange(st.songPage, totalPages)
    val showPagination = totalPages > 1

    Box(modifier = modifier.fillMaxSize()) {
        // ── 遮罩（Flutter: Color(0x80000000)，点击不关闭） ──
        Box(modifier = Modifier.fillMaxSize().background(AppColors.scrim50))

        // ── 面板：150ms 从底部滑入（对齐 TweenAnimationBuilder 的 Offset(0, t)） ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AppColors.background)
                .graphicsLayer { translationY = slide.value * size.height },
        ) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                SingerDetailHeader(onClose = { uiViewModel.setSingerDetailVisible(false) })
                SingerDetailArtistInfo(
                    name = artistName,
                    songTotal = st.songTotal,
                    avatarUrl = st.avatarUrl.ifEmpty { data.picture },
                    avatarKey = data.id,
                )
                if (st.tab == "song" && st.selectedSongs.isNotEmpty()) {
                    SingerDetailSelectionBar(
                        count = st.selectedSongs.size,
                        batchDownloading = st.batchDownloading,
                        onClear = { st.selectedSongs.clear() },
                        onBatch = { st.batchPickerVisible = true },
                    )
                }
                SingerDetailTabBar(tab = st.tab, onSwitch = { switchTab(it) })
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (st.tab == "song") {
                        SingerDetailSongs(
                            st = st,
                            tasks = tasks,
                            activeSongId = playerState.currentSong?.id,
                            totalPages = totalPages,
                            pageRange = pageRange,
                            showPagination = showPagination,
                            onPlay = { scope.launch { playSong(it) } },
                            onDownload = { openPicker(it) },
                            onToggle = { toggleSelect(it) },
                            onGoToPage = { scope.launch { loadSongs(it) } },
                        )
                    } else {
                        SingerDetailAlbums(
                            st = st,
                            onSortToggle = { st.albumSortAsc = !st.albumSortAsc },
                            onOpenAlbum = { openAlbum(it) },
                        )
                    }
                }
            }
        }

        // ── 音质选择器（单曲 / 批量，复用 QualityPickerSheet） ──
        QualityPickerSheet(
            visible = st.pickerVisible,
            qualities = listOf(Quality.flac, Quality.k320k, Quality.k128k),
            onSelect = { q -> scope.launch { downloadWithQuality(q) } },
            onCancel = {
                st.pickerVisible = false
                st.pickerSong = null
            },
            avoidMiniPlayer = true,
            miniPlayerVisible = playerState.currentSong != null && !uiState.playerOverlayVisible,
        )
        QualityPickerSheet(
            visible = st.batchPickerVisible,
            qualities = listOf(Quality.flac, Quality.k320k, Quality.k128k),
            onSelect = { q -> scope.launch { batchDownload(q) } },
            onCancel = { st.batchPickerVisible = false },
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
                        text = "正在下载 ${singerDetailSongTitleOf(st, downloadingId)}",
                        color = AppColors.primary,
                        fontSize = AppFontSize.base,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/** 分页大小（对齐 Dart `_pageSize = 20`） */
private const val PageSize = 20

/** 查找当前页内歌曲标题（对齐 Dart `_songTitleOf`） */
private fun singerDetailSongTitleOf(st: SingerDetailState, id: String): String {
    val idx = st.songs.indexOfFirst { it.id == id }
    return if (idx >= 0) st.songs[idx].title else "..."
}

/** 页段算法（对齐 Dart `_pageRange` / RN getPageRange） */
private fun singerDetailPageRange(current: Int, total: Int): List<Any> {
    if (total <= 7) return (1..total).toList()
    val result = mutableListOf<Any>()
    if (current <= 3) {
        val max = if (total < 4) total else 4
        for (i in 1..max) result.add(i)
        if (total > 4) {
            result.add("ellipsis")
            result.add(total)
        }
    } else if (current >= total - 2) {
        result.add(1)
        result.add("ellipsis")
        for (i in (total - 3)..total) result.add(i)
    } else {
        result.add(1)
        result.add("ellipsis")
        result.add(current - 1)
        result.add(current)
        result.add(current + 1)
        result.add("ellipsis")
        result.add(total)
    }
    return result
}

// ================================================================================================
// 头部与信息区
// ================================================================================================

/** 返回条：chevron_left 22 + 「歌手详情」16 w600（对齐 Dart `_header`） */
@Composable
private fun SingerDetailHeader(onClose: () -> Unit) {
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
            text = "歌手详情",
            modifier = Modifier.weight(1f),
            color = AppColors.textPrimary,
            fontSize = AppFontSize.lg,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 歌手头像（100 圆形）+ 名字 20 w700 + 「歌曲 N」13（对齐 Dart `_artistInfo`） */
@Composable
private fun SingerDetailArtistInfo(
    name: String,
    songTotal: Int,
    avatarUrl: String,
    avatarKey: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppSpacing.sp20),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        var avatarFailed by remember(avatarKey, avatarUrl) { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .size(AppSize.coverPlaceholder)
                .clip(CircleShape)
                .background(AppColors.whiteA03),
            contentAlignment = Alignment.Center,
        ) {
            if (avatarUrl.isEmpty() || avatarFailed) {
                DetailPersonOutline(modifier = Modifier.size(AppIconSize.icon36), tint = AppColors.textDisabled)
            } else {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onError = { avatarFailed = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(modifier = Modifier.height(AppSpacing.sp12))
        Text(
            text = name,
            color = AppColors.textPrimary,
            fontSize = AppFontSize.size20,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(AppSpacing.sp4))
        Text(
            text = "歌曲 $songTotal",
            color = AppColors.textTertiary,
            fontSize = AppFontSize.size13,
        )
    }
}

/** 待下载选择条（对齐 Dart `_selectionBar`） */
@Composable
private fun SingerDetailSelectionBar(
    count: Int,
    batchDownloading: Boolean,
    onClear: () -> Unit,
    onBatch: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = AppSpacing.sp16, end = AppSpacing.sp16, bottom = AppSpacing.sp8)
            .clip(RoundedCornerShape(AppRadius.panel))
            .background(AppColors.primaryA08)
            .padding(vertical = AppSpacing.sp10, horizontal = AppSpacing.sp14),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(AppSpacing.sp22)
                .clip(CircleShape)
                .background(AppColors.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "$count",
                color = AppColors.white,
                fontSize = AppFontSize.xs,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp6))
        Text(
            text = "待下载歌曲",
            modifier = Modifier.weight(1f),
            color = AppColors.white70,
            fontSize = AppFontSize.size13,
            fontWeight = FontWeight.Medium,
        )
        Box(
            modifier = Modifier
                .clickableNoRipple(onClear)
                .padding(AppSpacing.sp4),
        ) {
            Text(text = "清空", color = AppColors.textTertiary, fontSize = AppFontSize.sm)
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp8))
        Box(
            modifier = Modifier
                .graphicsLayer { alpha = if (batchDownloading) 0.5f else 1f }
                .clip(RoundedCornerShape(AppRadius.r10))
                .background(AppColors.primary)
                .then(if (batchDownloading) Modifier else Modifier.clickableNoRipple(onBatch))
                .padding(vertical = AppSpacing.sp8, horizontal = AppSpacing.sp16),
        ) {
            Text(
                text = if (batchDownloading) "下载中..." else "下载全部",
                color = AppColors.white,
                fontSize = AppFontSize.sm,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** 「歌曲 / 专辑」分段（对齐 Dart `_tabBar` / `_tabButton`） */
@Composable
private fun SingerDetailTabBar(tab: String, onSwitch: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = AppSpacing.sp40, end = AppSpacing.sp40, bottom = AppSpacing.sp10),
    ) {
        SingerDetailTabButton(
            label = "歌曲",
            active = tab == "song",
            modifier = Modifier.weight(1f),
            onTap = { onSwitch("song") },
        )
        Spacer(modifier = Modifier.width(AppSpacing.sp8))
        SingerDetailTabButton(
            label = "专辑",
            active = tab == "album",
            modifier = Modifier.weight(1f),
            onTap = { onSwitch("album") },
        )
    }
}

@Composable
private fun SingerDetailTabButton(
    label: String,
    active: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(AppRadius.r8))
            .background(if (active) AppColors.primary else AppColors.whiteA04)
            .clickableNoRipple(onTap),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (active) AppColors.white else AppColors.textTertiary,
            fontSize = AppFontSize.size13,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ================================================================================================
// 歌曲 Tab
// ================================================================================================

@Composable
private fun SingerDetailSongs(
    st: SingerDetailState,
    tasks: Map<String, DownloadTask>,
    activeSongId: String?,
    totalPages: Int,
    pageRange: List<Any>,
    showPagination: Boolean,
    onPlay: (Song) -> Unit,
    onDownload: (Song) -> Unit,
    onToggle: (Song) -> Unit,
    onGoToPage: (Int) -> Unit,
) {
    when {
        st.songLoading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AppColors.primary)
            }
        }

        st.songError.isNotEmpty() -> {
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
                    text = st.songError,
                    color = AppColors.whiteA40,
                    fontSize = AppFontSize.base,
                    textAlign = TextAlign.Center,
                )
            }
        }

        st.songs.isEmpty() -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "暂无歌曲", color = AppColors.white30, fontSize = AppFontSize.base)
            }
        }

        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = AppSpacing.listBottomInset),
            ) {
                items(count = st.songs.size) { index ->
                    val song = st.songs[index]
                    val task = tasks[song.id]
                    val isDownloading = st.downloadingId == song.id ||
                        task?.status == DownloadStatus.downloading ||
                        task?.status == DownloadStatus.pending
                    SingerDetailSongRow(
                        song = song,
                        index = index,
                        isActive = activeSongId == song.id,
                        isDownloading = isDownloading,
                        selected = st.selectedSongs.any { it.id == song.id },
                        selectionMode = st.selectedSongs.isNotEmpty(),
                        onPlay = onPlay,
                        onDownload = onDownload,
                        onToggle = onToggle,
                    )
                }
                if (showPagination) {
                    item {
                        SingerDetailPagination(
                            current = st.songPage,
                            totalPages = totalPages,
                            range = pageRange,
                            onGoTo = onGoToPage,
                        )
                    }
                }
            }
        }
    }
}

/** 歌曲行：右滑多选 + 点击播放 + 行尾下载（对齐 Dart `_SingerSongRow`） */
@Composable
private fun SingerDetailSongRow(
    song: Song,
    index: Int,
    isActive: Boolean,
    isDownloading: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    onPlay: (Song) -> Unit,
    onDownload: (Song) -> Unit,
    onToggle: (Song) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val rowBg = when {
        selected -> AppColors.primaryA08
        isActive -> AppColors.primaryA06
        else -> Color.Transparent
    }

    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(AppSize.detailRowHeight)
                .background(rowBg)
                .combinedClickableNoRipple(
                    onTap = { if (selectionMode) onToggle(song) else onPlay(song) },
                    onLongClick = {
                        // 对齐 Dart：已选中时长按不再触发（不重复震动 / 不取消选中）
                        if (!selected) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onToggle(song)
                        }
                    },
                )
                .padding(horizontal = AppSpacing.sp12, vertical = AppSpacing.sp8),
        ) {
            Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.width(AppSpacing.sp22),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${index + 1}",
                        color = if (selected) AppColors.primary else AppColors.textDisabled,
                        fontSize = AppFontSize.sm,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(modifier = Modifier.width(AppSpacing.sp8))
                SingerDetailCover(song = song, isActive = isActive)
                Spacer(modifier = Modifier.width(AppSpacing.sp10))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.Start,
                ) {
                    Text(
                        text = song.title,
                        color = if (isActive) AppColors.primary else AppColors.textPrimary,
                        fontSize = AppFontSize.base,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.sp1))
                    Text(
                        text = if (song.album.isNullOrEmpty()) song.artist else "${song.artist} · ${song.album}",
                        color = AppColors.textTertiary,
                        fontSize = AppFontSize.xs,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (selected) {
                    Spacer(modifier = Modifier.width(AppSpacing.sp4))
                    Box(
                        modifier = Modifier
                            .size(AppSpacing.sp20)
                            .clip(CircleShape)
                            .background(AppColors.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        DetailCheckMark(modifier = Modifier.size(14.dp), tint = AppColors.white)
                    }
                }
            }
        }

        if (!selected) {
            Box(
                modifier = Modifier
                    .clickableNoRipple { onDownload(song) }
                    .padding(AppSpacing.sp6),
                contentAlignment = Alignment.Center,
            ) {
                if (isDownloading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = AppColors.primary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    DownloadIconPlaceholder(
                        modifier = Modifier.size(AppIconSize.icon16),
                        tint = AppColors.whiteA25,
                    )
                }
            }
        }
    }
}

/** 40dp 封面（圆角 6；播放中压暗 0.6 且叠加白色播放三角） */
@Composable
private fun SingerDetailCover(song: Song, isActive: Boolean) {
    Box(
        modifier = Modifier
            .size(AppSpacing.sp40)
            .clip(RoundedCornerShape(AppRadius.r6))
            .background(AppColors.whiteA03),
        contentAlignment = Alignment.Center,
    ) {
        var coverFailed by remember(song.id) { mutableStateOf(false) }
        if (song.cover.isEmpty() || coverFailed) {
            MusicNoteGlyph(modifier = Modifier.size(AppIconSize.icon16), tint = AppColors.whiteA15)
        } else {
            AsyncImage(
                model = song.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = if (isActive) 0.6f else 1f,
                onError = { coverFailed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (isActive) {
            PlayTriangle(modifier = Modifier.size(AppIconSize.icon15), tint = AppColors.white)
        }
    }
}

/** 分页器（对齐 Dart `_pagination` / `_pageButton`） */
@Composable
private fun SingerDetailPagination(
    current: Int,
    totalPages: Int,
    range: List<Any>,
    onGoTo: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.sp12, vertical = AppSpacing.sp16),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SingerDetailPageButton(
            enabled = current > 1,
            onTap = { onGoTo(current - 1) },
        ) {
            DetailChevronLeft(modifier = Modifier.size(14.dp), tint = AppColors.whiteA50)
        }
        for (p in range) {
            Spacer(modifier = Modifier.width(AppSpacing.sp4))
            if (p == "ellipsis") {
                Text(
                    text = "...",
                    modifier = Modifier.padding(horizontal = AppSpacing.sp2),
                    color = AppColors.whiteA25,
                    fontSize = AppFontSize.sm,
                )
            } else {
                val page = p as Int
                SingerDetailPageButton(
                    active = current == page,
                    onTap = { onGoTo(page) },
                ) {
                    Text(
                        text = "$page",
                        color = if (current == page) AppColors.white else AppColors.whiteA50,
                        fontSize = AppFontSize.size13,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp4))
        SingerDetailPageButton(
            enabled = current < totalPages,
            onTap = { onGoTo(current + 1) },
        ) {
            DetailChevronRight(modifier = Modifier.size(14.dp), tint = AppColors.whiteA50)
        }
    }
}

@Composable
private fun SingerDetailPageButton(
    enabled: Boolean = true,
    active: Boolean = false,
    onTap: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(AppSpacing.sp32)
            .graphicsLayer { alpha = if (enabled) 1f else 0.3f }
            .clip(RoundedCornerShape(AppRadius.r8))
            .background(if (active) AppColors.primary else AppColors.whiteA05)
            .then(if (enabled) Modifier.clickableNoRipple(onTap) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

// ================================================================================================
// 专辑 Tab
// ================================================================================================

@Composable
private fun SingerDetailAlbums(
    st: SingerDetailState,
    onSortToggle: () -> Unit,
    onOpenAlbum: (Album) -> Unit,
) {
    if (st.albumLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = AppColors.primary)
        }
        return
    }
    if (st.albums.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "暂无专辑", color = AppColors.white30, fontSize = AppFontSize.base)
        }
        return
    }

    val sorted = st.albums.sortedWith(
        Comparator { a, b ->
            val da = a.pub
            val db = b.pub
            when {
                da.isEmpty() && db.isEmpty() -> 0
                da.isEmpty() -> 1
                db.isEmpty() -> -1
                st.albumSortAsc -> da.compareTo(db)
                else -> db.compareTo(da)
            }
        },
    )

    Column(modifier = Modifier.fillMaxSize()) {
        // 时间正序 / 倒序切换
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickableNoRipple(onSortToggle)
                .padding(start = AppSpacing.sp8, end = AppSpacing.sp8, bottom = AppSpacing.sp6),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (st.albumSortAsc) {
                DetailArrowUpward(modifier = Modifier.size(AppIconSize.icon13), tint = AppColors.whiteA45)
            } else {
                DetailArrowDownward(modifier = Modifier.size(AppIconSize.icon13), tint = AppColors.whiteA45)
            }
            Spacer(modifier = Modifier.width(AppSpacing.sp4))
            Text(
                text = if (st.albumSortAsc) "时间正序" else "时间倒序",
                color = AppColors.whiteA45,
                fontSize = AppFontSize.sm,
            )
        }

        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val cardWidth = (maxWidth - AppSpacing.sp16 - AppSpacing.sp10) / 2
            val cardHeight = cardWidth + 68.dp
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = AppSpacing.sp8,
                    top = AppSpacing.sp4,
                    end = AppSpacing.sp8,
                    bottom = AppSpacing.listBottomInset,
                ),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sp10),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sp10),
            ) {
                items(items = sorted, key = { it.albumid }) { album ->
                    SingerDetailAlbumCard(
                        album = album,
                        coverUrl = buildAlbumCoverUrl(album),
                        modifier = Modifier.height(cardHeight),
                        onTap = onOpenAlbum,
                    )
                }
            }
        }
    }
}

/** 专辑卡片（对齐 Dart `_SingerAlbumCard`）：封面 = 卡宽 - 12，圆角 12 / 封面圆角 8 */
@Composable
private fun SingerDetailAlbumCard(
    album: Album,
    coverUrl: String,
    modifier: Modifier = Modifier,
    onTap: (Album) -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.card))
            .background(AppColors.whiteA03)
            .clickableNoRipple { onTap(album) },
    ) {
        val coverSize = maxWidth - AppSpacing.sp12
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.padding(start = AppSpacing.sp6, top = AppSpacing.sp6, end = AppSpacing.sp6)) {
                Box(
                    modifier = Modifier
                        .size(coverSize)
                        .clip(RoundedCornerShape(AppRadius.r8))
                        .background(AppColors.whiteA03),
                    contentAlignment = Alignment.Center,
                ) {
                    var coverFailed by remember(album.albumid) { mutableStateOf(false) }
                    if (coverUrl.isEmpty() || coverFailed) {
                        DetailAlbumGlyph(modifier = Modifier.size(AppIconSize.icon40), tint = AppColors.white10)
                    } else {
                        AsyncImage(
                            model = coverUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            onError = { coverFailed = true },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            Column(modifier = Modifier.padding(AppSpacing.sp8)) {
                Text(
                    text = album.name,
                    color = AppColors.textPrimary,
                    fontSize = AppFontSize.size13,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp2))
                Text(
                    text = album.artist,
                    color = AppColors.whiteA40,
                    fontSize = AppFontSize.xs,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp4))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MusicNoteGlyph(modifier = Modifier.size(10.dp), tint = AppColors.whiteA20)
                    Spacer(modifier = Modifier.width(AppSpacing.sp4))
                    Text(
                        text = "${album.musiccnt}首",
                        color = AppColors.white30,
                        fontSize = AppFontSize.size10,
                    )
                    if (album.pub.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(AppSpacing.sp4))
                        Text(
                            text = album.pub,
                            modifier = Modifier.weight(1f, fill = false),
                            color = AppColors.whiteA20,
                            fontSize = AppFontSize.size10,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

