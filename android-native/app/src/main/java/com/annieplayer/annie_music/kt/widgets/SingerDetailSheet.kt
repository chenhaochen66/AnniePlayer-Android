package com.annieplayer.annie_music.kt.widgets

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
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
import com.annieplayer.annie_music.kt.core.state.AlbumDetailData
import com.annieplayer.annie_music.kt.core.state.ColorViewModel
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
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.sources.qobuzQualityLabel
import com.annieplayer.annie_music.kt.data.model.Album
import com.annieplayer.annie_music.kt.data.model.DownloadStatus
import com.annieplayer.annie_music.kt.data.model.DownloadTask
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.network.globalOkHttp
import com.annieplayer.annie_music.kt.data.sources.buildAlbumCoverUrl
import com.annieplayer.annie_music.kt.ui.theme.AppBorder
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.ColorPalette
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * 歌手详情浮层（1:1 移植 Flutter `lib/widgets/singer_detail_sheet.dart`，对齐 RN 版
 * components/SingerDetailOverlay.tsx）。
 *
 * 结构（自上而下）：返回条（多选时含三态全选 + 多选 / 取消 chip）→ 歌手头像 / 名字 / 歌曲数 →
 * （多选时）批量下载条 [MultiSelectDownloadBar] →「歌曲 / 专辑」分段 → 内容区（分页歌曲列表，或 2 列专辑网格）。
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
 * 背景：整屏铺 [CoverPaletteBackdrop]（`sheen = true`，与详情页同一套液态玻璃高光）——封面与色板
 * 都取「正在播放」的歌曲：`PlayerViewModel.currentSong.cover` + AppShell 按当前播放歌曲写入的
 * `ColorViewModel` 取色；面板自身不再有不透明底色。
 *
 * 本文件同时收纳 [detailDownloadSong] 下载助手与本页私有的统一笔重自绘图标；
 * 音质选择器复用共用组件 [QualityPickerSheet]。
 */
@Composable
fun SingerDetailSheet(
    uiViewModel: UiViewModel,
    playerViewModel: PlayerViewModel,
    downloadViewModel: DownloadViewModel,
    modifier: Modifier = Modifier,
    colorViewModel: ColorViewModel = activityViewModel(),
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
            colorViewModel = colorViewModel,
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

    /** 多选模式显式开关（长按行或头部「多选」chip 进入；「取消」/ 批量下载完成后退出）。 */
    var selectMode by mutableStateOf(false)

    var pickerVisible by mutableStateOf(false)
    var pickerSong by mutableStateOf<Song?>(null)
    var downloadingId by mutableStateOf<String?>(null)
    var batchPickerVisible by mutableStateOf(false)
    var batchDownloading by mutableStateOf(false)

    /** 批量下载进度文案（[MultiSelectDownloadBar] 下载中展示，形如 `3/12 歌名`）。 */
    var batchProgress by mutableStateOf("")

    var avatarUrl by mutableStateOf("")
}

@Composable
private fun SingerDetailContent(
    data: SingerDetailData,
    uiState: UiState,
    uiViewModel: UiViewModel,
    playerViewModel: PlayerViewModel,
    downloadViewModel: DownloadViewModel,
    colorViewModel: ColorViewModel,
    modifier: Modifier = Modifier,
) {
    val st = remember { SingerDetailState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val playerState by playerViewModel.state.collectAsState()
    val tasks by downloadViewModel.state.collectAsState()
    // 全局取色（AppShell 按当前播放歌曲写入；本页背景直接复用，保证与播放页同一套色板）
    val colorState by colorViewModel.state.collectAsState()

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
        if (tab == "album") {
                // 切到「专辑」分页时退出多选：该分页没有歌曲可选，留着多选态会出现
                // 「能进多选但没有可选项」的空壳状态（真机反馈）。
                st.selectMode = false
                st.selectedSongs.clear()
                scope.launch { loadAlbums() }
            }
    }

    fun toggleSelect(song: Song) {
        val idx = st.selectedSongs.indexOfFirst { it.id == song.id }
        if (idx >= 0) st.selectedSongs.removeAt(idx) else st.selectedSongs.add(song)
    }

    /** 全选 / 取消全选当前页歌曲（半选态点击同样走「补齐为全选」分支）。 */
    fun toggleSelectAll() {
        if (singerDetailAllSelected(st.songs, st.selectedSongs)) {
            val pageIds = st.songs.map { it.id }.toSet()
            st.selectedSongs.removeAll { it.id in pageIds }
        } else {
            for (s in st.songs) {
                if (st.selectedSongs.none { it.id == s.id }) st.selectedSongs.add(s)
            }
        }
    }

    /** 进入 / 退出多选；退出时连同已选歌曲一并清空（等价原「清空」的观感）。 */
    fun toggleSelectMode() {
        if (st.selectMode) {
            st.selectMode = false
            st.selectedSongs.clear()
        } else {
            st.selectMode = true
        }
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
        for ((i, song) in songs.withIndex()) {
            // 只补 UI 进度文案（下载流程本身与原来一致）
            st.batchProgress = "${i + 1}/${songs.size} ${song.title}"
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
        st.batchProgress = ""
        st.selectedSongs.clear()
        st.selectMode = false
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
        // 面板自身不再铺不透明底色（原 AppColors.background），由底层氛围背景直接可见。
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 吞掉面板空白处的点击：本浮层盖在歌手搜索结果之上，若不吞，未命中可点子元素的
                // 触摸会**漏给下层列表**（真机复现：点「待下载歌曲」那片区域跳到了下一个歌手）。
                // 与专辑详情面板同一套做法（见 AlbumDetailSheet）。
                .clickableNoRipple { }
                .graphicsLayer { translationY = slide.value * size.height },
        ) {
            // ── 背景：液态玻璃质感保留，但底色**不跟随当前播放封面**（用户要求）
            // —— 固定品牌色板 + sheen 高光，coverUrl = null。
            CoverPaletteBackdrop(
                sheen = true,
                coverUrl = null,
                palette = ColorPalette.fallback,
                modifier = Modifier.fillMaxSize(),
            )
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                SingerDetailHeader(
                    // 仅在「歌曲」分页显示多选入口：专辑分页没有歌曲可选（真机反馈）
                    showSelectEntry = st.tab == "song",
                    selectMode = st.tab == "song" && st.selectMode,
                    triState = if (st.tab == "song" && st.selectMode) {
                        singerDetailTriState(st.songs, st.selectedSongs)
                    } else {
                        null
                    },
                    onClose = { uiViewModel.setSingerDetailVisible(false) },
                    onToggleSelectAll = { toggleSelectAll() },
                    onToggleSelectMode = { toggleSelectMode() },
                )
                SingerDetailArtistInfo(
                    name = artistName,
                    songTotal = st.songTotal,
                    avatarUrl = st.avatarUrl.ifEmpty { data.picture },
                    avatarKey = data.id,
                )
                if (st.tab == "song" && st.selectMode) {
                    // 待下载选择条（对齐 Dart `_selectionBar`）：数量徽标 + 「待下载歌曲」+
                    // 清空 + 下载全部。样式与新的视觉语言一致（玻璃条 + 主色动作按钮），
                    // 进度文案沿用 `st.batchProgress`（形如 `3/12 歌名`）。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = AppSpacing.sp16, end = AppSpacing.sp16, bottom = AppSpacing.sp8)
                            .glassPanel(RoundedCornerShape(AppRadius.panel), elevated = false)
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
                                text = "${st.selectedSongs.size}",
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
                                .clickableRipple { st.selectedSongs.clear() }
                                .padding(AppSpacing.sp4),
                        ) {
                            Text(text = "清空", color = AppColors.textTertiary, fontSize = AppFontSize.sm)
                        }
                        Spacer(modifier = Modifier.width(AppSpacing.sp8))
                        Box(
                            modifier = Modifier
                                .graphicsLayer { alpha = if (st.batchDownloading) 0.5f else 1f }
                                .clip(RoundedCornerShape(AppRadius.r10))
                                .background(AppColors.primary)
                                .then(
                                    if (st.batchDownloading) {
                                        Modifier
                                    } else {
                                        Modifier.clickableRipple { st.batchPickerVisible = true }
                                    },
                                )
                                .padding(vertical = AppSpacing.sp8, horizontal = AppSpacing.sp16),
                        ) {
                            Text(
                                text = when {
                                    st.batchDownloading && st.batchProgress.isNotEmpty() -> st.batchProgress
                                    st.batchDownloading -> "下载中..."
                                    else -> "下载全部"
                                },
                                color = AppColors.white,
                                fontSize = AppFontSize.sm,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
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
                            onDownload = { uiViewModel.setSongActionsVisible(true, it) },
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
        // 面板内容交给 AppShell 在「迷你播放条之上」渲染（见 DetailQualityPickerHost 注释）；
        // 无宿主时退化为内联渲染，行为不变。
        val pickerHost = LocalDetailQualityPickerHost.current
        val pickerOwner = remember { Any() }
        val pickerPanel: @Composable () -> Unit = {
            QualityPickerSheet(
                visible = st.pickerVisible,
                qualities = if (data.platform == MusicPlatform.qobuz) {
                    listOf(Quality.hires, Quality.flac, Quality.k320k)
                } else {
                    listOf(Quality.flac, Quality.k320k, Quality.k128k)
                },
                labelOf = { q ->
                    if (data.platform == MusicPlatform.qobuz) qobuzQualityLabel(q) else q.label
                },
                onSelect = { q -> scope.launch { downloadWithQuality(q) } },
                onCancel = {
                    st.pickerVisible = false
                    st.pickerSong = null
                },
            )
            QualityPickerSheet(
                visible = st.batchPickerVisible,
                qualities = if (data.platform == MusicPlatform.qobuz) {
                    listOf(Quality.hires, Quality.flac, Quality.k320k)
                } else {
                    listOf(Quality.flac, Quality.k320k, Quality.k128k)
                },
                labelOf = { q ->
                    if (data.platform == MusicPlatform.qobuz) qobuzQualityLabel(q) else q.label
                },
                onSelect = { q -> scope.launch { batchDownload(q) } },
                onCancel = { st.batchPickerVisible = false },
            )
        }
        if (pickerHost == null) {
            pickerPanel()
        } else {
            // 只在面板打开时抢占槽位；关闭时不写，保留内容让面板自带淡出动画播完。
            SideEffect {
                if (st.pickerVisible || st.batchPickerVisible) {
                    pickerHost.show(pickerOwner, pickerPanel)
                }
            }
            DisposableEffect(pickerOwner) {
                onDispose { pickerHost.clear(pickerOwner) }
            }
        }

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

/** 当前页歌曲是否全部已选（头部三态全选用；空列表视为未全选）。 */
private fun singerDetailAllSelected(pageSongs: List<Song>, selected: List<Song>): Boolean =
    pageSongs.isNotEmpty() && pageSongs.all { s -> selected.any { it.id == s.id } }

/** 当前页歌曲的三态（全选 / 部分选中 / 未选），供头部 [SingerDetailTriStateCheckBox] 使用。 */
private fun singerDetailTriState(pageSongs: List<Song>, selected: List<Song>): SingerDetailTriState = when {
    singerDetailAllSelected(pageSongs, selected) -> SingerDetailTriState.Checked
    pageSongs.any { s -> selected.any { it.id == s.id } } -> SingerDetailTriState.Indeterminate
    else -> SingerDetailTriState.Unchecked
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

/**
 * 返回条：chevron_left 22 + 「歌手详情」16 w600 +（多选时）三态全选 + 多选 / 取消玻璃 chip
 * （对齐 Dart `_header`；控件样式见 [SingerDetailTriStateCheckBox] / [SingerDetailSelectChip]）。
 */
@Composable
private fun SingerDetailHeader(
    selectMode: Boolean,
    triState: SingerDetailTriState?,
    showSelectEntry: Boolean,
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
            SingerDetailChevronLeft(modifier = Modifier.size(AppIconSize.icon22), tint = AppColors.textSecondary)
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
        if (triState != null) {
            SingerDetailTriStateCheckBox(
                state = triState,
                onToggle = onToggleSelectAll,
            )
            Spacer(modifier = Modifier.width(AppSpacing.sp4))
        }
        // 专辑分页没有歌曲可选 → 整颗隐藏多选入口（不是只把它显示成「多选」）
        if (showSelectEntry) {
            SingerDetailSelectChip(
                selectMode = selectMode,
                onToggle = onToggleSelectMode,
            )
        }
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
                SingerDetailPersonOutline(modifier = Modifier.size(AppIconSize.icon36), tint = AppColors.textDisabled)
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

/** 头部全选控件三态：未选 / 部分选中 / 全选。 */
private enum class SingerDetailTriState { Unchecked, Indeterminate, Checked }

/** 三态复选缩放：未选中静置 0.85（与共享 `DetailCheckBox` 的弹一下反馈同规格）。 */
private const val SingerDetailCheckRestScale = 0.85f

/** 三态复选缩放：已选（含半选）静置 1.0。 */
private const val SingerDetailCheckCheckedScale = 1f

/**
 * 头部「多选 / 取消」入口：玻璃描边 chip（自绘图标 + 文案，48dp 命中区）。
 *
 * 视觉：玻璃底（[glassPanel]，无投影）+ 1px [AppColors.primary] 描边 + primary 图标 / 文案
 * —— 与分段选中态同一套「玻璃 + 描边 + 主色文字」语言，不用实心主色块。
 * 未进入多选显示「多选」+ checklist 图标；进入后显示「取消」+ 关闭叉。
 * 交互：点击涟漪（[clickableRipple]）+ 轻触觉（[HapticFeedbackType.TextHandleMove]）。
 */
@Composable
private fun SingerDetailSelectChip(
    selectMode: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(AppRadius.pill)
    Box(
        modifier = modifier
            // 命中区 ≥48dp（与 DetailCheckBox / MultiSelectDownloadBar 同一约定）
            .minimumInteractiveComponentSize()
            .clip(shape)
            .clickableRipple {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onToggle()
            }
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .glassPanel(shape = shape, elevated = false)
                .border(AppBorder.thin, AppColors.primary, shape)
                .padding(horizontal = AppSpacing.sp10, vertical = AppSpacing.sp6),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectMode) {
                SingerDetailCloseGlyph(modifier = Modifier.size(AppIconSize.icon16), tint = AppColors.primary)
            } else {
                SingerDetailMultiSelectGlyph(modifier = Modifier.size(AppIconSize.icon16), tint = AppColors.primary)
            }
            Spacer(modifier = Modifier.width(AppSpacing.sp4))
            Text(
                text = if (selectMode) "取消" else "多选",
                color = AppColors.primary,
                fontSize = AppFontSize.size13,
                fontWeight = AppFontWeight.medium,
            )
        }
    }
}

/**
 * 本页私有三态复选控件（头部全选）：空框 / 半选横杠 / primary 实心对勾。
 *
 * 为什么不复用共享 [DetailCheckBox]：后者只有「未选 / 全选」两态，无法表达部分选中。
 * 按约定不改共享文件，这里以同一视觉规格（18dp 视觉 [AppSize.detailCheckBoxSize]、r4 圆角、
 * 1.5dp 等效描边、primary 实心 + [AppColors.background] 勾 / 横杠、0.85 → 1.0 缩放）实现三态版；
 * 空框与对勾直接复用共享件 [DetailCheckBoxBlank] / [DetailCheckMark]，保证笔重一致。
 *
 * 交互：点击在「全选 ⇄ 清空」间切换（半选视作未全选，点击补齐为全选），
 * 涟漪（[clickableRipple]）+ 轻触觉；命中区由 [minimumInteractiveComponentSize] 预留到 48dp。
 * 无障碍：`contentDescription` 随状态给出（「全选」/「取消全选」），[Role.Checkbox]。
 */
@Composable
private fun SingerDetailTriStateCheckBox(
    state: SingerDetailTriState,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val checked = state != SingerDetailTriState.Unchecked
    val scale by animateFloatAsState(
        targetValue = if (checked) SingerDetailCheckCheckedScale else SingerDetailCheckRestScale,
        animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.easeInOutQuad),
        label = "singerDetailTriStateScale",
    )
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(AppRadius.r8))
            .clickableRipple {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onToggle()
            }
            .semantics {
                contentDescription = if (state == SingerDetailTriState.Checked) "取消全选" else "全选"
                role = Role.Checkbox
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(AppSize.detailCheckBoxSize)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        ) {
            when (state) {
                SingerDetailTriState.Checked -> {
                    Box(Modifier.fillMaxSize().background(AppColors.primary, RoundedCornerShape(AppRadius.r4)))
                    DetailCheckMark(modifier = Modifier.fillMaxSize(), tint = AppColors.background)
                }

                SingerDetailTriState.Indeterminate -> {
                    Box(Modifier.fillMaxSize().background(AppColors.primary, RoundedCornerShape(AppRadius.r4)))
                    SingerDetailDashMark(modifier = Modifier.fillMaxSize(), tint = AppColors.background)
                }

                SingerDetailTriState.Unchecked -> {
                    DetailCheckBoxBlank(modifier = Modifier.fillMaxSize(), tint = AppColors.textTertiary)
                }
            }
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

/**
 * 分段按钮：选中态 = 玻璃底 + 1px primary 描边 + primary 文字（不再用实心主色块）。
 *
 * 切换动效 260ms（借用 [AppDuration.bottomBarMorph] 的值，与底栏两态切换同规格）：
 * 玻璃层透明度 / 描边色 / 文字色三通道同时过渡；点击有涟漪 + 轻触觉。
 */
@Composable
private fun SingerDetailTabButton(
    label: String,
    active: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(AppRadius.r8)
    val glassAlpha by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(durationMillis = AppDuration.bottomBarMorph, easing = AppEasing.easeInOutQuad),
        label = "singerDetailTabGlass",
    )
    val borderColor by animateColorAsState(
        targetValue = if (active) AppColors.primary else Color.Transparent,
        animationSpec = tween(durationMillis = AppDuration.bottomBarMorph, easing = AppEasing.easeInOutQuad),
        label = "singerDetailTabBorder",
    )
    val labelColor by animateColorAsState(
        targetValue = if (active) AppColors.primary else AppColors.textTertiary,
        animationSpec = tween(durationMillis = AppDuration.bottomBarMorph, easing = AppEasing.easeInOutQuad),
        label = "singerDetailTabLabel",
    )
    Box(
        modifier = modifier
            .height(34.dp)
            .clip(shape)
            .background(AppColors.whiteA04)
            .clickableRipple {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onTap()
            },
        contentAlignment = Alignment.Center,
    ) {
        // 选中玻璃层：透明度随切换淡入 / 淡出（无投影，避免与背景氛围叠加发糊）
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { alpha = glassAlpha }
                .glassPanel(shape = shape, elevated = false),
        )
        // 1px primary 描边：只在选中时出现（颜色透明 → primary 过渡）
        Box(
            modifier = Modifier
                .matchParentSize()
                .border(AppBorder.thin, borderColor, shape),
        )
        Text(
            text = label,
            color = labelColor,
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
                SingerDetailErrorOutline(modifier = Modifier.size(AppIconSize.icon36), tint = AppColors.textDisabled)
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
                        selectionMode = st.selectMode,
                        onPlay = onPlay,
                        onDownload = onDownload,
                        onToggle = onToggle,
                        onLongSelect = { longPressed ->
                            // 长按 = 进入多选并选中该行（「已选中不重复触发」逻辑在行内保留）
                            st.selectMode = true
                            onToggle(longPressed)
                        },
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

/** 歌曲行：长按进多选 + 点击播放 + 行尾下载（对齐 Dart `_SingerSongRow`） */
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
    onLongSelect: (Song) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val rowBg = when {
        selected -> AppColors.primaryA08
        isActive -> AppColors.primaryA06
        else -> Color.Transparent
    }

    // 以专辑详情行为基准对齐：右侧预留 sp16（与专辑行 padding 一致），
    // 并让下载按钮在整个行高内垂直居中（原先默认顶对齐，视觉偏上）。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = AppSpacing.sp16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
                            onLongSelect(song)
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
                        SingerDetailCheckMark(modifier = Modifier.size(14.dp), tint = AppColors.white)
                    }
                }
            }
        }

        if (!selected) {
            if (isDownloading) {
                Box(
                    modifier = Modifier.padding(AppSpacing.sp4),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = AppColors.primary,
                        strokeWidth = 2.dp,
                    )
                }
            } else {
                SongMoreButton(onClick = { onDownload(song) })
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
            SingerDetailMusicNote(modifier = Modifier.size(AppIconSize.icon16), tint = AppColors.whiteA15)
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
            SingerDetailChevronLeft(modifier = Modifier.size(14.dp), tint = AppColors.whiteA50)
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
            SingerDetailChevronRight(modifier = Modifier.size(14.dp), tint = AppColors.whiteA50)
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
            .then(if (enabled) Modifier.clickableRipple(onTap) else Modifier),
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
                .clickableRipple(onSortToggle)
                .padding(start = AppSpacing.sp8, end = AppSpacing.sp8, bottom = AppSpacing.sp6),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (st.albumSortAsc) {
                SingerDetailArrowUp(modifier = Modifier.size(AppIconSize.icon13), tint = AppColors.whiteA45)
            } else {
                SingerDetailArrowDown(modifier = Modifier.size(AppIconSize.icon13), tint = AppColors.whiteA45)
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
            .clickableRipple { onTap(album) },
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
                        SingerDetailAlbumGlyph(modifier = Modifier.size(AppIconSize.icon40), tint = AppColors.white10)
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
                    SingerDetailMusicNote(modifier = Modifier.size(10.dp), tint = AppColors.whiteA20)
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

// ================================================================================================
// 本页私有自绘图标（统一笔重语言）
//
// 为什么在本文件重画：本页原先直接复用 widgets/DetailGlyphs.kt 与 widgets/Icons.kt 的共用图形，
// 那批图形由各页面按各自场景定的比例描边（0.09f ~ 0.14f），同屏并排时笔重不一致；共用函数又被
// 其它页面引用、不能改动，因此在本文件内以 private 重画一套，统一：
//   - 恒定 2dp 等效描边（[SingerDetailIconStroke]，不随图标尺寸缩放）；
//   - 线稿一律 round cap / round join；
//   - 比例坐标（w / h 的 0~1 系数，与图标尺寸解耦）。
// 实心图形（[PlayTriangle]）与 Material 进度环不涉及笔重，维持原样。
// ================================================================================================

/** 本页图标统一笔重：2dp 等效描边（恒定值，保证同屏所有线稿粗细一致）。 */
private val SingerDetailIconStroke = 2.dp

/** chevron_left：返回箭头 / 分页上一页（本页统一 2dp 圆头笔画） */
@Composable
private fun SingerDetailChevronLeft(modifier: Modifier = Modifier, tint: Color = AppColors.textSecondary) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = SingerDetailIconStroke.toPx()
        drawLine(tint, Offset(w * 0.62f, h * 0.16f), Offset(w * 0.30f, h * 0.5f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.30f, h * 0.5f), Offset(w * 0.62f, h * 0.84f), stroke, StrokeCap.Round)
    }
}

/** chevron_right：分页下一页（本页统一 2dp 圆头笔画） */
@Composable
private fun SingerDetailChevronRight(modifier: Modifier = Modifier, tint: Color = AppColors.textSecondary) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = SingerDetailIconStroke.toPx()
        drawLine(tint, Offset(w * 0.38f, h * 0.16f), Offset(w * 0.70f, h * 0.5f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.70f, h * 0.5f), Offset(w * 0.38f, h * 0.84f), stroke, StrokeCap.Round)
    }
}

/** arrow_upward：专辑「时间正序」（本页统一 2dp 圆头笔画） */
@Composable
private fun SingerDetailArrowUp(modifier: Modifier = Modifier, tint: Color = AppColors.whiteA45) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = SingerDetailIconStroke.toPx()
        drawLine(tint, Offset(w * 0.5f, h * 0.86f), Offset(w * 0.5f, h * 0.18f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.18f, h * 0.5f), Offset(w * 0.5f, h * 0.16f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.82f, h * 0.5f), Offset(w * 0.5f, h * 0.16f), stroke, StrokeCap.Round)
    }
}

/** arrow_downward：专辑「时间倒序」（本页统一 2dp 圆头笔画） */
@Composable
private fun SingerDetailArrowDown(modifier: Modifier = Modifier, tint: Color = AppColors.whiteA45) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = SingerDetailIconStroke.toPx()
        drawLine(tint, Offset(w * 0.5f, h * 0.14f), Offset(w * 0.5f, h * 0.82f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.18f, h * 0.5f), Offset(w * 0.5f, h * 0.84f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.82f, h * 0.5f), Offset(w * 0.5f, h * 0.84f), stroke, StrokeCap.Round)
    }
}

/** person_outline：头像兜底（头圆 + 肩弧，本页统一 2dp 圆头笔画） */
@Composable
private fun SingerDetailPersonOutline(modifier: Modifier = Modifier, tint: Color = AppColors.textDisabled) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = SingerDetailIconStroke.toPx()
        drawCircle(
            color = tint,
            radius = w * 0.17f,
            center = Offset(w * 0.5f, h * 0.31f),
            style = Stroke(width = stroke),
        )
        drawArc(
            color = tint,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.16f, h * 0.50f),
            size = Size(w * 0.68f, h * 0.62f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/** error_outline：加载失败态（描边圆 + 感叹号，本页统一 2dp 圆头笔画） */
@Composable
private fun SingerDetailErrorOutline(modifier: Modifier = Modifier, tint: Color = AppColors.textDisabled) {
    Canvas(modifier = modifier) {
        val min = size.minDimension
        val stroke = SingerDetailIconStroke.toPx()
        drawCircle(
            color = tint,
            radius = (min - stroke) / 2f,
            style = Stroke(width = stroke),
        )
        drawLine(tint, Offset(min * 0.5f, min * 0.28f), Offset(min * 0.5f, min * 0.55f), stroke, StrokeCap.Round)
        drawCircle(color = tint, radius = min * 0.055f, center = Offset(min * 0.5f, min * 0.72f))
    }
}

/** check：选中态对勾（本页统一 2dp 圆头端点 / 圆角连接） */
@Composable
private fun SingerDetailCheckMark(modifier: Modifier = Modifier, tint: Color = AppColors.white) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = SingerDetailIconStroke.toPx()
        val path = Path().apply {
            moveTo(w * 0.22f, h * 0.52f)
            lineTo(w * 0.43f, h * 0.73f)
            lineTo(w * 0.78f, h * 0.28f)
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** album：封面兜底（描边圆 + 圆心实点，本页统一 2dp 圆环） */
@Composable
private fun SingerDetailAlbumGlyph(modifier: Modifier = Modifier, tint: Color = AppColors.white10) {
    Canvas(modifier = modifier) {
        val min = size.minDimension
        val stroke = SingerDetailIconStroke.toPx()
        drawCircle(
            color = tint,
            radius = (min - stroke) / 2f,
            style = Stroke(width = stroke),
        )
        drawCircle(color = tint, radius = min * 0.14f)
    }
}

/** download：行尾下载（箭身 + 描边箭头 + 托盘，本页统一 2dp 圆头笔画） */
@Composable
private fun SingerDetailDownloadGlyph(modifier: Modifier = Modifier, tint: Color = AppColors.whiteA25) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = SingerDetailIconStroke.toPx()
        // 箭身
        drawLine(tint, Offset(w * 0.5f, h * 0.10f), Offset(w * 0.5f, h * 0.44f), stroke, StrokeCap.Round)
        // 空心箭头（描边三角形）
        val head = Path().apply {
            moveTo(w * 0.20f, h * 0.44f)
            lineTo(w * 0.80f, h * 0.44f)
            lineTo(w * 0.50f, h * 0.74f)
            close()
        }
        drawPath(
            path = head,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        // 托盘
        drawLine(tint, Offset(w * 0.22f, h * 0.90f), Offset(w * 0.78f, h * 0.90f), stroke, StrokeCap.Round)
    }
}

/** music_note：封面 / 专辑行音乐占位（本页统一 2dp 圆头笔画） */
@Composable
private fun SingerDetailMusicNote(modifier: Modifier = Modifier, tint: Color = AppColors.whiteA15) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = SingerDetailIconStroke.toPx()
        // 符干
        drawLine(tint, Offset(w * 0.64f, h * 0.12f), Offset(w * 0.64f, h * 0.68f), stroke, StrokeCap.Round)
        // 符头
        drawCircle(color = tint, radius = w * 0.20f, center = Offset(w * 0.44f, h * 0.70f))
        // 符尾
        drawLine(tint, Offset(w * 0.64f, h * 0.12f), Offset(w * 0.88f, h * 0.26f), stroke, StrokeCap.Round)
    }
}

/** checklist：头部「多选」入口图标（两行「勾 + 线」，本页统一 2dp 圆头笔画） */
@Composable
private fun SingerDetailMultiSelectGlyph(modifier: Modifier = Modifier, tint: Color = AppColors.primary) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = SingerDetailIconStroke.toPx()
        // 上排：勾 + 线
        drawLine(tint, Offset(w * 0.08f, h * 0.24f), Offset(w * 0.20f, h * 0.36f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.20f, h * 0.36f), Offset(w * 0.40f, h * 0.12f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.52f, h * 0.26f), Offset(w * 0.92f, h * 0.26f), stroke, StrokeCap.Round)
        // 下排：勾 + 线
        drawLine(tint, Offset(w * 0.08f, h * 0.70f), Offset(w * 0.20f, h * 0.82f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.20f, h * 0.82f), Offset(w * 0.40f, h * 0.58f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.52f, h * 0.74f), Offset(w * 0.92f, h * 0.74f), stroke, StrokeCap.Round)
    }
}

/** close：头部「取消」态图标（×，本页统一 2dp 圆头笔画） */
@Composable
private fun SingerDetailCloseGlyph(modifier: Modifier = Modifier, tint: Color = AppColors.primary) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = SingerDetailIconStroke.toPx()
        drawLine(tint, Offset(w * 0.24f, h * 0.24f), Offset(w * 0.76f, h * 0.76f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.76f, h * 0.24f), Offset(w * 0.24f, h * 0.76f), stroke, StrokeCap.Round)
    }
}

/** remove：三态半选横杠（本页统一 2dp 圆头笔画） */
@Composable
private fun SingerDetailDashMark(modifier: Modifier = Modifier, tint: Color = AppColors.background) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = SingerDetailIconStroke.toPx()
        drawLine(tint, Offset(w * 0.26f, h * 0.5f), Offset(w * 0.74f, h * 0.5f), stroke, StrokeCap.Round)
    }
}

