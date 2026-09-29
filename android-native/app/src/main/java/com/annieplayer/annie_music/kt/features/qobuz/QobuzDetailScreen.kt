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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.annieplayer.annie_music.kt.core.state.DownloadViewModel
import com.annieplayer.annie_music.kt.core.state.PlayerViewModel
import com.annieplayer.annie_music.kt.core.state.QobuzViewModel
import com.annieplayer.annie_music.kt.core.util.enqueueDownload
import com.annieplayer.annie_music.kt.data.fetchLyric
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.QobuzToken
import com.annieplayer.annie_music.kt.data.model.QobuzTrack
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.sources.getFileUrl
import com.annieplayer.annie_music.kt.data.sources.getQobuzAlbum
import com.annieplayer.annie_music.kt.data.sources.getTrack
import com.annieplayer.annie_music.kt.ui.theme.AppBorder
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.CheckSquareIcon
import com.annieplayer.annie_music.kt.widgets.DetailCheckBoxBlank
import com.annieplayer.annie_music.kt.widgets.DetailChevronLeft
import com.annieplayer.annie_music.kt.widgets.DetailDownloadSolid
import com.annieplayer.annie_music.kt.widgets.DetailErrorOutline
import com.annieplayer.annie_music.kt.widgets.DownloadIconPlaceholder
import com.annieplayer.annie_music.kt.widgets.MusicNoteGlyph
import com.annieplayer.annie_music.kt.widgets.clickableNoRipple
import com.annieplayer.annie_music.kt.widgets.detailDownloadSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

/** `QobuzTrack` -> `Song`（对齐 Dart `_toSong`，id 前缀 `qobuz:`） */
private fun qobuzDetailToSong(track: QobuzTrack): Song = Song(
    id = "qobuz:${track.id}",
    title = track.title,
    artist = track.artist,
    cover = track.cover,
    album = track.album,
    duration = track.duration,
    platform = MusicPlatform.qobuz,
    albumArtist = track.albumArtist,
    trackNumber = if (track.trackNumber > 0) track.trackNumber.toString() else null,
    discNumber = track.discNumber?.toString(),
    releaseDate = if (track.year > 0) track.year.toString() else null,
)

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
 */
@Composable
fun QobuzDetailScreen(
    qobuzViewModel: QobuzViewModel,
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
        val song = qobuzDetailToSong(track)
        st.downloadingId = track.id
        downloadViewModel.addTask(song, Quality.flac)
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
        for (t in selected) downloadViewModel.addTask(qobuzDetailToSong(t), Quality.flac)
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

    Box(modifier = modifier.fillMaxSize().background(AppColors.background)) {
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
                    onPlay = { play(it) },
                    onDownload = { downloadTrack(it) },
                    onToggleSelect = { tid ->
                        if (!st.selectedIds.remove(tid)) st.selectedIds.add(tid)
                    },
                    onBatch = { batchDownload() },
                )
            }
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
                .clickableNoRipple(onBack)
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
        if (type == "album" && trackCount > 1) {
            Box(
                modifier = Modifier
                    .clickableNoRipple(onToggleSelectMode)
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

/** 主体：加载中 / 错误态 / 列表（头部 + 曲目行） */
@Composable
private fun QobuzDetailBody(
    st: QobuzDetailScreenState,
    onPlay: (QobuzTrack) -> Unit,
    onDownload: (QobuzTrack) -> Unit,
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
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = AppSpacing.listBottomInset),
            ) {
                item {
                    QobuzDetailListHeader(st = st, onBatch = onBatch)
                }
                items(count = st.tracks.size) { index ->
                    val track = st.tracks[index]
                    QobuzDetailTrackRow(
                        track = track,
                        index = index,
                        selectMode = st.selectMode,
                        selected = st.selectedIds.contains(track.id),
                        downloading = st.downloadingId == track.id,
                        onPlay = onPlay,
                        onDownload = onDownload,
                        onToggle = onToggleSelect,
                    )
                }
            }
        }
    }
}

/** 列表头：封面 100（圆角 10）+ 标题 17 / 歌手 13 /（年份 · 厂牌 · 曲目数）11 + 批量下载按钮 */
@Composable
private fun QobuzDetailListHeader(st: QobuzDetailScreenState, onBatch: () -> Unit) {
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
                .background(AppColors.whiteA03),
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
        if (st.selectMode && st.selectedIds.isNotEmpty()) {
            Spacer(modifier = Modifier.width(AppSpacing.sp8))
            QobuzDetailBatchButton(
                batchDownloading = st.batchDownloading,
                progress = st.batchProgress,
                count = st.selectedIds.size,
                onTap = onBatch,
            )
        }
    }
}

/** 批量下载按钮（最小宽 80；下载中显示 16dp 环 + 进度文案） */
@Composable
private fun QobuzDetailBatchButton(
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
                    strokeWidth = AppBorder.thick,
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp2))
                Text(
                    text = progress,
                    modifier = Modifier.width(76.dp),
                    color = AppColors.white,
                    fontSize = AppFontSize.size10,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
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

/** 曲目行：多选框 + 序号 + 标题 +（下载中环 | 下载按钮）；行底 1dp 分隔线 */
@Composable
private fun QobuzDetailTrackRow(
    track: QobuzTrack,
    index: Int,
    selectMode: Boolean,
    selected: Boolean,
    downloading: Boolean,
    onPlay: (QobuzTrack) -> Unit,
    onDownload: (QobuzTrack) -> Unit,
    onToggle: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(QobuzDetailRowHeight)
            .background(if (selected) AppColors.primaryA08 else Color.Transparent)
            .drawBehind {
                val stroke = AppBorder.thin.toPx()
                drawLine(
                    color = AppColors.whiteA04,
                    start = Offset(0f, size.height - stroke / 2f),
                    end = Offset(size.width, size.height - stroke / 2f),
                    strokeWidth = stroke,
                )
            }
            .clickableNoRipple { if (selectMode) onToggle(track.id) else onPlay(track) }
            .padding(horizontal = QobuzDetailScreenPaddingH, vertical = AppSpacing.sp10),
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
        Box(modifier = Modifier.width(AppSpacing.sp24), contentAlignment = Alignment.Center) {
            Text(
                text = "${index + 1}",
                color = AppColors.whiteA25,
                fontSize = AppFontSize.sm,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp10))
        Text(
            text = track.title,
            modifier = Modifier.weight(1f),
            color = AppColors.textPrimary,
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
                    .clickableNoRipple { onDownload(track) }
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

/** 下载单曲 FLAC（format_id 27），返回最终文件路径（对齐 Dart `_downloadQobuzTrack`） */
private suspend fun qobuzDetailDownloadTrack(
    context: Context,
    track: QobuzTrack,
    songId: String,
    token: String,
    pageArtist: String,
    updateProgress: (Double) -> Unit,
): String {
    val info = getFileUrl(track.id, token, qualityId = QobuzDetailFormatId)
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
        quality = Quality.flac,
        onProgress = { p -> updateProgress(p * 100.0) },
        lyric = lyricResult.lrc,
        tlyric = lyricResult.tlyric,
        albumArtist = track.albumArtist ?: pageArtist,
        trackNumber = if (track.trackNumber > 0) track.trackNumber.toString() else null,
        discNumber = track.discNumber?.toString(),
        releaseDate = if (track.year > 0) track.year.toString() else null,
    )
}

/** 去掉 `Exception: ` 前缀的错误文案（对齐 Dart `_errText`） */
private fun qobuzDetailErrText(e: Throwable): String =
    (e.message ?: "").replaceFirst("Exception: ", "")

