package com.annieplayer.annie_music.kt.features.qobuz

import android.content.Context
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import com.annieplayer.annie_music.kt.core.compose.activityViewModel
import com.annieplayer.annie_music.kt.core.state.ColorViewModel
import com.annieplayer.annie_music.kt.core.state.DownloadViewModel
import com.annieplayer.annie_music.kt.core.state.PlayerViewModel
import com.annieplayer.annie_music.kt.core.compose.activityViewModel
import com.annieplayer.annie_music.kt.core.state.QobuzViewModel
import com.annieplayer.annie_music.kt.core.state.UiViewModel
import com.annieplayer.annie_music.kt.core.util.enqueueDownload
import com.annieplayer.annie_music.kt.data.fetchLyric
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.QobuzToken
import com.annieplayer.annie_music.kt.data.model.QobuzTrack
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.sources.getFileUrl
import com.annieplayer.annie_music.kt.data.sources.qobuzQualityFormatId
import com.annieplayer.annie_music.kt.data.sources.getQobuzAlbum
import com.annieplayer.annie_music.kt.data.sources.qobuzTrackToSong
import com.annieplayer.annie_music.kt.data.sources.getTrack
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
import com.annieplayer.annie_music.kt.widgets.MultiSelectDownloadBar
import com.annieplayer.annie_music.kt.widgets.MusicNoteGlyph
import com.annieplayer.annie_music.kt.widgets.PlayingIndicator
import com.annieplayer.annie_music.kt.widgets.clickableRipple
import com.annieplayer.annie_music.kt.widgets.detailDownloadSong
import com.annieplayer.annie_music.kt.widgets.SongMoreGlyph
import com.annieplayer.annie_music.kt.widgets.glassPanel
import com.annieplayer.annie_music.kt.widgets.playingRowBackground
import com.annieplayer.annie_music.kt.widgets.rememberCoverPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 列表行高（对齐 Dart `_rowHeight = 60`） */
private val QobuzDetailRowHeight = AppSize.detailRowHeight

/** 行 / 头部横向内边距（对齐 Dart `_screenPaddingH = 24`） */
private val QobuzDetailScreenPaddingH = AppSpacing.sp24

/** 播放直链预取 / 下载使用的 Qobuz format_id（对齐 Dart `_qobuzFormatId = 27`） */
private const val QobuzDetailFormatId = 27

/** Qobuz 详情页可变状态（对齐 Dart `_QobuzDetailScreenState` 的字段） */
private class QobuzDetailScreenState {
    var tracks by mutableStateOf<List<QobuzTrack>>(emptyList())
    var cover by mutableStateOf("")
    var title by mutableStateOf("")
    var artist by mutableStateOf("")
    var year by mutableStateOf(0)
    var label by mutableStateOf("")
    var maxBitDepth by mutableStateOf<Int?>(null)
    var maxSamplingRateKhz by mutableStateOf<Double?>(null)
    var hires by mutableStateOf(false)
    var loading by mutableStateOf(true)
    var error by mutableStateOf("")
    var loadedToken by mutableStateOf<String?>(null)

    var downloadingId by mutableStateOf<String?>(null)
    var selectMode by mutableStateOf(false)
    val selectedIds = mutableStateListOf<String>()
    var batchDownloading by mutableStateOf(false)
    var batchProgress by mutableStateOf("")
}

/** Qobuz 活跃 Token（对齐 Dart `getActiveToken`：优先 `isActive`，否则首个） */
private fun qobuzDetailActiveToken(tokens: List<QobuzToken>): QobuzToken? =
    tokens.firstOrNull { it.isActive } ?: tokens.firstOrNull()

private fun qobuzRateText(khz: Double): String =
    if (khz % 1.0 == 0.0) khz.toInt().toString() else khz.toString()

/** `QobuzTrack` -> `Song`（走共享映射；[hires] 决定音质档位） */
private fun qobuzDetailToSong(track: QobuzTrack, hires: Boolean = false): Song =
    qobuzTrackToSong(track, hires)

/**
 * Qobuz 详情整页（1:1 移植 Flutter `lib/features/qobuz/qobuz_detail_screen.dart`）。
 *
 * 路由参数 [type]（`track` / `album`）与 [id] 对应 Dart 的 query / extra。
 * 校验活跃 Token（无则提示先配置）；下载 / 播放直链统一走 `getFileUrl(…, 27)`
 * 的 FLAC 24bit 最高档。
 *
 * 行为对齐点：
 * - Token 变化后按 `token.id` 自动重载（对齐 Dart `ref.listen`）。
 * - 播放前并发预取全部曲目直链（对齐 Dart `Future.wait`，单曲失败回退空串）。
 * - 下载 / 批量下载固定 [Quality.flac]。
 * - 返回手势：多选模式优先退出（对齐 Dart `PopScope(canPop: !_selectMode)`）。
 *
 * 视觉分层（行为完全不变）：
 * - 底层 [CoverPaletteBackdrop]：模糊封面 + 取色染色渐变 + 顶部压暗的氛围背景；
 * - 列表用 `glassPanel` 承载（圆角 [AppRadius.panel] 液态玻璃），底部仍保留
 *   [AppSpacing.listBottomInset]（迷你条 + 手势条）预留；
 * - 正在播放行：`playingRowBackground(palette.primary)` + [PlayingIndicator] + 标题主色；
 * - 多选：行内 [DetailCheckBox]，底部浮层 [MultiSelectDownloadBar]（替换旧头部批量按钮）；
 * - 进出页通过 [SnapshotPaletteAndRestore] 灌入 / 恢复全局色板（与播放页同一取色链路）。
 */
@Composable
fun QobuzDetailScreen(
    qobuzViewModel: QobuzViewModel,
    uiViewModel: UiViewModel = activityViewModel(),
    playerViewModel: PlayerViewModel,
    downloadViewModel: DownloadViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    type: String = "",
    id: String = "",
) {
    val st = remember { QobuzDetailScreenState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val tokens by qobuzViewModel.state.collectAsState()

    // 全局色板（Activity 作用域，与 AppShell / 播放页同一实例）：本页进出时快照 / 恢复
    val colorViewModel: ColorViewModel = activityViewModel()
    // 当前播放曲目 id：只对「换歌」敏感（时间 / 缓冲进度 tick 不触发本页重组）
    val playingSongId by remember(playerViewModel) {
        playerViewModel.state.map { it.currentSong?.id }.distinctUntilChanged()
    }.collectAsState(initial = playerViewModel.state.value.currentSong?.id)
    // 封面动态取色（与播放页同一条链路）：封面未到时回退 ColorPalette.fallback
    val palette = rememberCoverPalette(st.cover.takeIf { it.isNotEmpty() })
    // 进入时把详情页色板写入全局（AppShell → AnnieTheme），离开组合时恢复进入前的色板
    // 不再写入全局色板（原 SnapshotPaletteAndRestore）：详情页只用本地 palette 渲染，
// 全局色板由 AppShell 按「当前播放歌曲」驱动；详情页写入会在退出时覆盖成陈旧值。

    suspend fun load() {
        val token = qobuzViewModel.getActiveToken()
        if (token == null) {
            st.loading = false
            st.error = "请先在设置中配置并选择 Qobuz Token"
            return
        }
        if (type.isEmpty() || id.isEmpty()) {
            st.loading = false
            st.error = "缺少参数"
            return
        }
        st.loading = true
        st.error = ""
        try {
            if (type == "track") {
                val track = getTrack(id, token.token)
                st.tracks = listOf(track)
                st.cover = track.cover
                st.title = track.title
                st.artist = track.artist
                st.year = track.year
                st.label = track.label
            } else {
                val album = getQobuzAlbum(id, token.token)
                st.tracks = album.tracks
                st.cover = album.cover
                st.title = album.title
                st.artist = album.artist
                st.year = album.year
                st.label = album.label
                st.maxBitDepth = album.maxBitDepth
                st.maxSamplingRateKhz = album.maxSamplingRateKhz
                st.hires = album.hires
            }
            st.loadedToken = token.id
        } catch (e: Exception) {
            st.error = qobuzDetailErrText(e)
        }
        st.loading = false
    }

    fun play(track: QobuzTrack) {
        scope.launch {
            val token = qobuzViewModel.getActiveToken() ?: return@launch
            val allTracks = if (st.tracks.size > 1) st.tracks else listOf(track)
            val index = allTracks.indexOfFirst { it.id == track.id }
            // 预取各曲目直链（对齐 Dart `Future.wait` 的并发语义，单曲失败回退空串）
            val urls = coroutineScope {
                allTracks.map { t ->
                    async {
                        try {
                            getFileUrl(t.id, token.token, qualityId = QobuzDetailFormatId).url
                        } catch (_: Exception) {
                            ""
                        }
                    }
                }.awaitAll()
            }
            val songs = List(allTracks.size) { i ->
                val t = allTracks[i]
                Song(
                    id = "qobuz:${t.id}",
                    title = t.title,
                    artist = t.artist,
                    cover = t.cover,
                    album = t.album,
                    duration = t.duration,
                    platform = MusicPlatform.qobuz,
                    playUrl = urls[i],
                    albumArtist = t.albumArtist,
                    trackNumber = if (t.trackNumber > 0) t.trackNumber.toString() else null,
                    discNumber = t.discNumber?.toString(),
                    releaseDate = if (t.year > 0) t.year.toString() else null,
                )
            }
            playerViewModel.setQueue(songs, if (index < 0) 0 else index)
        }
    }

    fun downloadTrack(track: QobuzTrack) {
        val token = qobuzViewModel.getActiveToken() ?: return
        val songId = "qobuz:${track.id}"
        val song = qobuzDetailToSong(track, hires = st.hires)
        val quality = if (st.hires) Quality.hires else Quality.flac
        st.downloadingId = track.id
        downloadViewModel.addTask(song, quality)
        enqueueDownload(songId) {
            try {
                val filePath = qobuzDetailDownloadTrack(
                    context = context,
                    track = track,
                    songId = songId,
                    token = token.token,
                    pageArtist = st.artist,
                    updateProgress = { v ->
                        downloadViewModel.updateProgress(songId, v.coerceIn(0.0, 100.0))
                    },
                    quality = quality,
                )
                downloadViewModel.completeTask(songId, filePath)
                playerViewModel.addDownloadedId(songId)
                playerViewModel.addToQueue(song)
            } catch (_: Exception) {
                downloadViewModel.failTask(songId)
            }
            withContext(Dispatchers.Main) { st.downloadingId = null }
        }
    }

    fun batchDownload() {
        if (st.batchDownloading) return
        val token = qobuzViewModel.getActiveToken() ?: return
        val selected = st.tracks.filter { st.selectedIds.contains(it.id) }
        if (selected.isEmpty()) return
        st.batchDownloading = true
        for (t in selected) downloadViewModel.addTask(qobuzDetailToSong(t, hires = st.hires), Quality.flac)
        val batchId = "qobuz_batch_${System.currentTimeMillis()}"
        enqueueDownload(batchId) {
            var success = 0
            for (i in selected.indices) {
                val t = selected[i]
                val songId = "qobuz:${t.id}"
                val song = qobuzDetailToSong(t)
                withContext(Dispatchers.Main) { st.batchProgress = "${i + 1}/${selected.size} ${t.title}" }
                downloadViewModel.updateProgress(songId, 0.0)
                try {
                    val filePath = qobuzDetailDownloadTrack(
                        context = context,
                        track = t,
                        songId = songId,
                        token = token.token,
                        pageArtist = st.artist,
                        updateProgress = { v ->
                            downloadViewModel.updateProgress(songId, v.coerceIn(0.0, 100.0))
                        },
                    )
                    downloadViewModel.completeTask(songId, filePath)
                    playerViewModel.addDownloadedId(songId)
                    playerViewModel.addToQueue(song)
                    success++
                } catch (_: Exception) {
                    downloadViewModel.failTask(songId)
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

    LaunchedEffect(type, id) { load() }

    // Token 变化后自动重载（对齐 Dart `ref.listen(qobuzStoreProvider)`；跳过首帧初值）
    val activeTokenId = qobuzDetailActiveToken(tokens)?.id
    var skipFirstTokenWatch by remember { mutableStateOf(true) }
    LaunchedEffect(activeTokenId) {
        if (skipFirstTokenWatch) {
            skipFirstTokenWatch = false
            return@LaunchedEffect
        }
        if (activeTokenId != null && activeTokenId != st.loadedToken && !st.loading) load()
    }

    // 返回手势：多选模式优先退出（对齐 Dart `PopScope(canPop: !_selectMode)`）
    BackHandler {
        if (st.selectMode) {
            st.selectMode = false
        } else {
            onBack()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 底层：封面动态取色氛围背景（模糊封面 + 主色渐变 + 顶部压暗）
        CoverPaletteBackdrop(
            coverUrl = st.cover.takeIf { it.isNotEmpty() },
            palette = palette,
            modifier = Modifier.fillMaxSize(),
        )
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            QobuzDetailHeader(
                type = type,
                trackCount = st.tracks.size,
                selectMode = st.selectMode,
                selectedAll = st.selectedIds.size == st.tracks.size,
                onBack = onBack,
                onToggleSelectAll = {
                    if (st.selectedIds.size == st.tracks.size) {
                        st.selectedIds.clear()
                    } else {
                        st.selectedIds.clear()
                        st.selectedIds.addAll(st.tracks.map { it.id })
                    }
                },
                onToggleSelectMode = {
                    st.selectMode = !st.selectMode
                    st.selectedIds.clear()
                },
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                QobuzDetailBody(
                    st = st,
                    palette = palette,
                    playingSongId = playingSongId,
                    onPlay = { play(it) },
                            onDownload = {
                                uiViewModel.setSongActionsVisible(
                                    true,
                                    qobuzDetailToSong(it, hires = st.hires),
                                )
                            },
                    onToggleSelect = { tid ->
                        if (!st.selectedIds.remove(tid)) st.selectedIds.add(tid)
                    },
                )
            }
        }

        // 顶层：多选批量下载条（玻璃 pill，仅多选模式出现；计数 / 进度复用既有状态字段）
        if (st.selectMode) {
            MultiSelectDownloadBar(
                selectedCount = st.selectedIds.size,
                total = st.tracks.size,
                downloading = st.batchDownloading,
                progressText = st.batchProgress,
                onDownload = { batchDownload() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = AppSpacing.sp20),
            )
        }
    }
}

/** 返回条：chevron_left + 标题 +（多选时）全选 +（专辑且多曲时）选择 / 取消 */
@Composable
private fun QobuzDetailHeader(
    type: String,
    trackCount: Int,
    selectMode: Boolean,
    selectedAll: Boolean,
    onBack: () -> Unit,
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
                .clickableRipple(onBack)
                .padding(AppSpacing.sp4),
            contentAlignment = Alignment.Center,
        ) {
            DetailChevronLeft(modifier = Modifier.size(AppIconSize.icon22), tint = AppColors.textSecondary)
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp8))
        Text(
            text = if (type == "album") "Qobuz专辑详情" else "Qobuz单曲详情",
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
        if (type == "album" && trackCount > 1) {
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
}

/** 主体：加载中 / 错误态 / 列表（玻璃容器 + 头部 + 曲目行） */
@Composable
private fun QobuzDetailBody(
    st: QobuzDetailScreenState,
    palette: ColorPalette,
    playingSongId: String?,
    onPlay: (QobuzTrack) -> Unit,
    onDownload: (QobuzTrack) -> Unit,
    onToggleSelect: (String) -> Unit,
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
            // 玻璃承载列表：圆角 panel + 侧边留白，让取色氛围背景从玻璃边缘透出；
            // 底部内容预留不变（迷你条 + 手势条）
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = AppSpacing.sp12)
                    .glassPanel(shape = RoundedCornerShape(AppRadius.panel)),
                contentPadding = PaddingValues(bottom = AppSpacing.listBottomInset),
            ) {
                item {
                    QobuzDetailListHeader(st = st)
                }
                items(count = st.tracks.size) { index ->
                    val track = st.tracks[index]
                    QobuzDetailTrackRow(
                        track = track,
                        index = index,
                        selectMode = st.selectMode,
                        selected = st.selectedIds.contains(track.id),
                        playing = playingSongId == "qobuz:${track.id}",
                        downloading = st.downloadingId == track.id,
                        palette = palette,
                        onPlay = onPlay,
                        onDownload = onDownload,
                        onToggle = onToggleSelect,
                    )
                }
            }
        }
    }
}

/** 列表头：封面 100（圆角 10）+ 标题 17 / 歌手 13 /（年份 · 厂牌 · 曲目数）11；批量入口已移至底部条 */
@Composable
private fun QobuzDetailListHeader(st: QobuzDetailScreenState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QobuzDetailScreenPaddingH, vertical = AppSpacing.sp16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(AppSize.coverPlaceholder)
                .clip(RoundedCornerShape(AppRadius.r10))
                    .background(Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            var coverFailed by remember(st.cover) { mutableStateOf(false) }
            if (st.cover.isEmpty() || coverFailed) {
                MusicNoteGlyph(modifier = Modifier.size(AppIconSize.icon28), tint = AppColors.white24)
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
                text = st.title,
                color = AppColors.textPrimary,
                fontSize = AppFontSize.size17,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp4))
            Text(
                text = st.artist,
                color = AppColors.whiteA50,
                fontSize = AppFontSize.size13,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp2))
            Row {
                val specText = listOfNotNull(
                    st.maxBitDepth?.let { "${it}bit" },
                    st.maxSamplingRateKhz?.let { "${qobuzRateText(it)}kHz" },
                    if (st.hires) "Hi-Res" else null,
                ).joinToString(" / ")
                if (specText.isNotEmpty()) {
                    Text(
                        text = specText,
                        modifier = Modifier.padding(end = AppSpacing.sp8),
                        color = AppColors.whiteA50,
                        fontSize = AppFontSize.xs,
                    )
                }
                if (st.year > 0) {
                    Text(
                        text = "${st.year}",
                        modifier = Modifier.padding(end = AppSpacing.sp8),
                        color = AppColors.whiteA25,
                        fontSize = AppFontSize.xs,
                    )
                }
                if (st.label.isNotEmpty()) {
                    Text(
                        text = st.label,
                        modifier = Modifier.padding(end = AppSpacing.sp8),
                        color = AppColors.whiteA25,
                        fontSize = AppFontSize.xs,
                    )
                }
                Text(
                    text = "${st.tracks.size} 首",
                    color = AppColors.whiteA25,
                    fontSize = AppFontSize.xs,
                )
            }
        }
    }
}

/**
 * 曲目行：多选框 + 序号 + 标题 +（下载中环 | 下载按钮）；行底 1dp 分隔线。
 *
 * 「正在播放」行（[playing]）：主色行底（`playingRowBackground`，左侧 3dp 主色条 + 8% 圆角底）
 * + 序号列换 [PlayingIndicator] + 标题主色；点击行为与普通行完全一致
 * （多选模式切换选中，否则播放）。
 */
@Composable
private fun QobuzDetailTrackRow(
    track: QobuzTrack,
    index: Int,
    selectMode: Boolean,
    selected: Boolean,
    playing: Boolean,
    downloading: Boolean,
    palette: ColorPalette,
    onPlay: (QobuzTrack) -> Unit,
    onDownload: (QobuzTrack) -> Unit,
    onToggle: (String) -> Unit,
) {
    // 正在播放行优先用主色行底；其余按既有多选态给淡主色底
    val rowBackground = if (playing) {
        Modifier.playingRowBackground(palette.readablePrimary())
    } else {
        Modifier.background(if (selected) AppColors.primaryA08 else Color.Transparent)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(QobuzDetailRowHeight)
            .then(rowBackground)
            .drawBehind {
                val stroke = AppBorder.thin.toPx()
                drawLine(
                    color = AppColors.whiteA04,
                    start = Offset(0f, size.height - stroke / 2f),
                    end = Offset(size.width, size.height - stroke / 2f),
                    strokeWidth = stroke,
                )
            }
            .clickableRipple { if (selectMode) onToggle(track.id) else onPlay(track) }
            .padding(horizontal = QobuzDetailScreenPaddingH, vertical = AppSpacing.sp10),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectMode) {
            DetailCheckBox(
                checked = selected,
                onToggle = { onToggle(track.id) },
            )
            Spacer(modifier = Modifier.width(AppSpacing.sp10))
        }
        Box(modifier = Modifier.width(AppSpacing.sp24), contentAlignment = Alignment.Center) {
            if (playing) {
                PlayingIndicator(tint = palette.readablePrimary())
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
        Text(
            text = track.title,
            modifier = Modifier.weight(1f),
            color = if (playing) palette.readablePrimary() else AppColors.textPrimary,
            fontSize = AppFontSize.base,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (downloading) {
            CircularProgressIndicator(
                modifier = Modifier.size(AppIconSize.icon20),
                color = AppColors.primary,
                strokeWidth = AppBorder.thick,
            )
        } else if (!selectMode) {
            Box(
                modifier = Modifier
                    .clickableRipple { onDownload(track) }
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

/** 下载单曲（[quality] 决定 format_id），返回最终文件路径 */
private suspend fun qobuzDetailDownloadTrack(
    context: Context,
    track: QobuzTrack,
    songId: String,
    token: String,
    pageArtist: String,
    updateProgress: (Double) -> Unit,
    quality: Quality = Quality.flac,
): String {
    val info = getFileUrl(track.id, token, qualityId = qobuzQualityFormatId(quality))
    val songObj = qobuzDetailToSong(track)
    val lyricResult = fetchLyric(songObj)
    return detailDownloadSong(
        context = context,
        songId = songId,
        title = track.title,
        artist = track.artist,
        coverUrl = track.cover,
        album = track.album,
        downloadUrl = info.url,
        quality = quality,
        onProgress = { p -> updateProgress(p * 100.0) },
        lyric = lyricResult.lrc,
        tlyric = lyricResult.tlyric,
        albumArtist = track.albumArtist ?: pageArtist,
        trackNumber = if (track.trackNumber > 0) track.trackNumber.toString() else null,
        discNumber = track.discNumber?.toString(),
        releaseDate = track.releaseDateOriginal
            ?: if (track.year > 0) track.year.toString() else null,
        isrc = track.isrc,
        version = track.version,
        copyright = track.copyright,
    )
}

/** 去掉 `Exception: ` 前缀的错误文案（对齐 Dart `_errText`） */
private fun qobuzDetailErrText(e: Throwable): String =
    (e.message ?: "").replaceFirst("Exception: ", "")
