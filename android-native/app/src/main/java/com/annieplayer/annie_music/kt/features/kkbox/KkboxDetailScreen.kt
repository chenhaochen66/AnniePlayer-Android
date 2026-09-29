package com.annieplayer.annie_music.kt.features.kkbox

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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import com.annieplayer.annie_music.kt.core.compose.activityViewModel
import com.annieplayer.annie_music.kt.core.state.ColorViewModel
import com.annieplayer.annie_music.kt.core.state.DownloadViewModel
import com.annieplayer.annie_music.kt.core.state.KkboxViewModel
import com.annieplayer.annie_music.kt.core.state.PlayerViewModel
import com.annieplayer.annie_music.kt.core.util.enqueueDownload
import com.annieplayer.annie_music.kt.data.DownloadService
import com.annieplayer.annie_music.kt.data.model.KkboxAccount
import com.annieplayer.annie_music.kt.data.model.KkboxSession
import com.annieplayer.annie_music.kt.data.model.KkboxTrack
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.sources.formatNameToQuality
import com.annieplayer.annie_music.kt.data.sources.getDownloadUrl
import com.annieplayer.annie_music.kt.data.sources.getKkboxAlbum
import com.annieplayer.annie_music.kt.data.sources.getPlayUrl
import com.annieplayer.annie_music.kt.data.sources.getSongLyrics
import com.annieplayer.annie_music.kt.data.sources.getSongs
import com.annieplayer.annie_music.kt.data.sources.isDrmFormat
import com.annieplayer.annie_music.kt.data.sources.mapAlbumToKkboxAlbum
import com.annieplayer.annie_music.kt.data.sources.mapSongToKkboxTrack
import com.annieplayer.annie_music.kt.data.sources.qualityExtension
import com.annieplayer.annie_music.kt.data.sources.streamDecryptKkboxAudio
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
import com.annieplayer.annie_music.kt.widgets.SnapshotPaletteAndRestore
import com.annieplayer.annie_music.kt.widgets.clickableNoRipple
import com.annieplayer.annie_music.kt.widgets.detailDownloadSong
import com.annieplayer.annie_music.kt.widgets.glassPanel
import com.annieplayer.annie_music.kt.widgets.playingRowBackground
import com.annieplayer.annie_music.kt.widgets.rememberCoverPalette
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 列表行高（对齐 Dart `_rowHeight = 60`） */
private val KkboxDetailRowHeight = AppSize.detailRowHeight

/** 行 / 头部横向内边距（对齐 Dart `_screenPaddingH = 24`） */
private val KkboxDetailScreenPaddingH = AppSpacing.sp24

/** KKBOX 详情页可变状态（对齐 Dart `_KkboxDetailScreenState` 的字段） */
private class KkboxDetailScreenState {
    var tracks by mutableStateOf<List<KkboxTrack>>(emptyList())
    var cover by mutableStateOf("")
    var title by mutableStateOf("")
    var artist by mutableStateOf("")
    var year by mutableStateOf<String?>(null)
    var loading by mutableStateOf(true)
    var error by mutableStateOf("")
    var loadedSid by mutableStateOf<String?>(null)

    var downloadingId by mutableStateOf<String?>(null)
    var selectMode by mutableStateOf(false)
    val selectedIds = mutableStateListOf<String>()
    var batchDownloading by mutableStateOf(false)
    var batchProgress by mutableStateOf("")
}

/** KKBOX 活跃账号（对齐 Dart `getActiveAccount`：优先 `isActive`，否则首个） */
private fun kkboxDetailActiveAccount(accounts: List<KkboxAccount>): KkboxAccount? =
    accounts.firstOrNull { it.isActive } ?: accounts.firstOrNull()

/**
 * KKBOX 详情整页（1:1 移植 Flutter `lib/features/kkbox/kkbox_detail_screen.dart`）。
 *
 * 路由参数 [type]（`song` / `album`）与 [id] 对应 Dart 的 query / extra。
 * 无活跃账号（或未登录）时展示提示；播放走 `getPlayUrl`（chromecast 免 DRM），
 * 下载走 `getDownloadUrl` + RC4 流式解密（Kotlin 无 ffmpeg，跳过元数据打标）。
 *
 * 行为对齐点：
 * - 账号 / 会话变化后按 `session.sid` 自动重载（对齐 Dart `ref.listen`）。
 * - `_load` 的错误分支：含 session / login 的报错转为「会话已过期…」。
 * - 播放 / 下载过程中会话被续期时，回写 `updateAccountSession`。
 * - 下载：hires 固定音质；批量逐个下载并汇报进度。
 * - 视觉：封面动态取色氛围背景（[CoverPaletteBackdrop]）+ 玻璃列表容器（[glassPanel]）；
 *   进入时把本页色板写入全局、离开时恢复（[SnapshotPaletteAndRestore]）；
 *   命中当前曲的行显示「正在播放」动效（[PlayingIndicator]）。
 * - 返回手势：多选模式优先退出（对齐 Dart `PopScope(canPop: !_selectMode)`）。
 */
@Composable
fun KkboxDetailScreen(
    kkboxViewModel: KkboxViewModel,
    playerViewModel: PlayerViewModel,
    downloadViewModel: DownloadViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    type: String = "",
    id: String = "",
) {
    val st = remember { KkboxDetailScreenState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val accounts by kkboxViewModel.state.collectAsState()
    val playerState by playerViewModel.state.collectAsState()

    // 封面动态取色（与播放页同一套链路）：封面就绪后色板刷新，未就绪回退 fallback
    val coverUrl = st.cover.takeIf { it.isNotEmpty() }
    val palette = rememberCoverPalette(coverUrl)
    // 进入时把本页色板灌入全局（单 Activity 架构：取到与 AppShell 相同的 Activity 作用域实例），
    // 离开时恢复进入前的色板，避免详情页配色残留到主界面 / 播放页
    val colorViewModel: ColorViewModel = activityViewModel()
    SnapshotPaletteAndRestore(colorViewModel = colorViewModel, palette = palette)

    /** `KkboxTrack` -> `Song`（对齐 Dart 的 `kkbox:${t.id}` 构造） */
    fun toSong(track: KkboxTrack): Song = Song(
        id = "kkbox:${track.id}",
        title = track.title,
        artist = track.artist,
        cover = track.cover,
        album = track.album,
        duration = track.duration,
        platform = MusicPlatform.kkbox,
        albumArtist = track.albumArtist,
        trackNumber = if (track.trackNumber > 0) track.trackNumber.toString() else null,
        releaseDate = track.releaseDate,
    )

    suspend fun load() {
        val account = kkboxViewModel.getActiveAccount()
        val session = account?.session
        if (session == null || !session.loggedIn) {
            st.loading = false
            st.error = if (type.isEmpty() || id.isEmpty()) "缺少参数" else "请先在设置中配置并登录 KKBOX"
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
            if (type == "song") {
                val rawSongs = getSongs(session.sid, listOf(id))
                if (rawSongs.isEmpty()) throw IllegalStateException("歌曲不存在")
                val track = mapSongToKkboxTrack(rawSongs.first())
                st.tracks = listOf(track)
                st.cover = track.cover
                st.title = track.title
                st.artist = track.artist
                st.year = null
            } else {
                val data = getKkboxAlbum(session.sid, id)
                val album = mapAlbumToKkboxAlbum(data.album, data.songs)
                st.tracks = album.tracks
                st.cover = album.cover
                st.title = album.title
                st.artist = if (album.artist.isNotEmpty()) {
                    album.artist
                } else {
                    album.tracks.firstOrNull()?.artist ?: ""
                }
                st.year = album.year.ifEmpty { null }
            }
            st.loadedSid = session.sid
        } catch (e: Exception) {
            val message = kkboxDetailErrText(e)
            st.error = if (message.contains("session") || message.contains("login")) {
                "会话已过期，请在设置中重新登录"
            } else {
                message
            }
        }
        st.loading = false
    }

    fun play(track: KkboxTrack) {
        scope.launch {
            val account = kkboxViewModel.getActiveAccount()
            val session = account?.session
            if (account == null || session == null || !session.loggedIn) {
                st.error = "未登录"
                return@launch
            }
            try {
                val result = getPlayUrl(session.sid, track.id, session.kkid)
                if (result.url.isEmpty()) return@launch
                if (result.sid != session.sid) {
                    kkboxViewModel.updateAccountSession(
                        account.id,
                        KkboxSession(
                            sid = result.sid,
                            licContentKey = session.licContentKey,
                            highQuality = session.highQuality,
                            kkid = session.kkid,
                            email = session.email,
                            loggedIn = true,
                        ),
                    )
                }
                val allTracks = if (st.tracks.size > 1) st.tracks else listOf(track)
                val index = allTracks.indexOfFirst { it.id == track.id }
                val songs = allTracks.map { t ->
                    Song(
                        id = "kkbox:${t.id}",
                        title = t.title,
                        artist = t.artist,
                        cover = t.cover,
                        album = t.album,
                        duration = t.duration,
                        platform = MusicPlatform.kkbox,
                        playUrl = result.url,
                        albumArtist = t.albumArtist,
                        trackNumber = if (t.trackNumber > 0) t.trackNumber.toString() else null,
                        releaseDate = t.releaseDate,
                    )
                }
                playerViewModel.setQueue(songs, if (index < 0) 0 else index)
            } catch (e: Exception) {
                st.error = kkboxDetailErrText(e)
            }
        }
    }

    fun downloadTrack(track: KkboxTrack) {
        val account = kkboxViewModel.getActiveAccount()
        val session = account?.session
        if (account == null || session == null || !session.loggedIn) return
        val songId = "kkbox:${track.id}"
        val song = toSong(track)
        st.downloadingId = track.id
        downloadViewModel.addTask(song, Quality.hires)
        enqueueDownload(songId) {
            try {
                val filePath = kkboxDetailDownloadTrack(
                    context = context,
                    track = track,
                    songId = songId,
                    kkboxViewModel = kkboxViewModel,
                    accountId = account.id,
                    session = session,
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
        val account = kkboxViewModel.getActiveAccount()
        val session = account?.session
        if (account == null || session == null || !session.loggedIn) return
        val selected = st.tracks.filter { st.selectedIds.contains(it.id) }
        if (selected.isEmpty()) return
        st.batchDownloading = true
        for (t in selected) downloadViewModel.addTask(toSong(t), Quality.hires)
        val batchId = "kkbox_batch_${System.currentTimeMillis()}"
        enqueueDownload(batchId) {
            var success = 0
            for (i in selected.indices) {
                val t = selected[i]
                val songId = "kkbox:${t.id}"
                val song = toSong(t)
                withContext(Dispatchers.Main) { st.batchProgress = "${i + 1}/${selected.size} ${t.title}" }
                downloadViewModel.updateProgress(songId, 0.0)
                try {
                    val filePath = kkboxDetailDownloadTrack(
                        context = context,
                        track = t,
                        songId = songId,
                        kkboxViewModel = kkboxViewModel,
                        accountId = account.id,
                        session = session,
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

    // 账号 / 会话变化后自动重载（对齐 Dart `ref.listen(kkboxStoreProvider)`；跳过首帧初值）
    val sessionSid = kkboxDetailActiveAccount(accounts)?.session?.sid
    var skipFirstSessionWatch by remember { mutableStateOf(true) }
    LaunchedEffect(sessionSid) {
        if (skipFirstSessionWatch) {
            skipFirstSessionWatch = false
            return@LaunchedEffect
        }
        if (sessionSid != null && sessionSid != st.loadedSid && !st.loading) load()
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
        // 液态玻璃底：封面模糊 + 动态取色氛围（无封面时退化为纯色底 + 染色）
        CoverPaletteBackdrop(
            coverUrl = coverUrl,
            palette = palette,
            modifier = Modifier.fillMaxSize(),
        )
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            KkboxDetailHeader(
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
                KkboxDetailBody(
                    st = st,
                    palette = palette,
                    currentSongId = playerState.currentSong?.id,
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
private fun KkboxDetailHeader(
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
            text = if (type == "album") "KKBOX专辑详情" else "KKBOX单曲详情",
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

/** 主体：加载中 / 错误态 / 玻璃列表（头部 + 曲目行 + 多选下载条） */
@Composable
private fun KkboxDetailBody(
    st: KkboxDetailScreenState,
    palette: ColorPalette,
    currentSongId: String?,
    onPlay: (KkboxTrack) -> Unit,
    onDownload: (KkboxTrack) -> Unit,
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
            Box(modifier = Modifier.fillMaxSize().padding(horizontal = AppSpacing.sp12)) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        // 液态玻璃承载列表：半透明底 + 顶部高光 + 高光描边（层叠伪造毛玻璃）
                        .glassPanel(shape = RoundedCornerShape(AppRadius.panel)),
                    // 底部为常驻底栏 / 迷你条 + 手势条预留空间（沿用既有 120dp 值）
                    contentPadding = PaddingValues(bottom = AppSpacing.listBottomInset),
                ) {
                    item {
                        KkboxDetailListHeader(st = st)
                    }
                    items(count = st.tracks.size) { index ->
                        val track = st.tracks[index]
                        KkboxDetailTrackRow(
                            track = track,
                            index = index,
                            selectMode = st.selectMode,
                            selected = st.selectedIds.contains(track.id),
                            playing = currentSongId == "kkbox:${track.id}",
                            downloading = st.downloadingId == track.id,
                            palette = palette,
                            onPlay = onPlay,
                            onDownload = onDownload,
                            onToggle = onToggleSelect,
                        )
                    }
                }
                // 多选批量下载入口：玻璃 pill 浮于列表底部（选择态出现；下载中展示批次进度）
                if (st.selectMode) {
                    MultiSelectDownloadBar(
                        selectedCount = st.selectedIds.size,
                        total = st.tracks.size,
                        downloading = st.batchDownloading,
                        progressText = st.batchProgress,
                        onDownload = onBatch,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = AppSpacing.sp16),
                    )
                }
            }
        }
    }
}

/** 列表头：封面 100（圆角 10）+ 标题 17 / 歌手 13 /（年份 · 曲目数）11 */
@Composable
private fun KkboxDetailListHeader(st: KkboxDetailScreenState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KkboxDetailScreenPaddingH, vertical = AppSpacing.sp16),
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
                val year = st.year
                if (year != null) {
                    Text(
                        text = year,
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
 * 曲目行：多选框 + 序号（命中时为等化器指示器）+ 标题 +（下载中环 | 下载按钮）；行底 1dp 分隔线。
 *
 * 正在播放（[playing]，按 `kkbox:{id}` 命中当前曲）时：
 * - 行底换 [playingRowBackground]（主色 8% 圆角底 + 左侧 3dp 主色条，自绘于行节点）；
 * - 序号列换 [PlayingIndicator] 等化器动效（纯装饰，语义由行承载）；
 * - 标题转主色 `palette.primary`。
 *
 * 点击行为保持原样：多选模式切换选中，否则走 [onPlay]。
 */
@Composable
private fun KkboxDetailTrackRow(
    track: KkboxTrack,
    index: Int,
    selectMode: Boolean,
    selected: Boolean,
    playing: Boolean,
    downloading: Boolean,
    palette: ColorPalette,
    onPlay: (KkboxTrack) -> Unit,
    onDownload: (KkboxTrack) -> Unit,
    onToggle: (String) -> Unit,
) {
    val rowBase = Modifier
        .fillMaxWidth()
        .height(KkboxDetailRowHeight)
    val rowBackground = when {
        playing -> rowBase.playingRowBackground(palette.primary)
        selected -> rowBase.background(AppColors.primaryA08)
        else -> rowBase
    }
    Row(
        modifier = rowBackground
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
            .semantics {
                contentDescription = if (selectMode) "选择 ${track.title}" else "播放 ${track.title}"
            }
            .padding(horizontal = KkboxDetailScreenPaddingH, vertical = AppSpacing.sp10),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectMode) {
            DetailCheckBox(checked = selected, onToggle = { onToggle(track.id) })
            Spacer(modifier = Modifier.width(AppSpacing.sp10))
        }
        Box(modifier = Modifier.width(AppSpacing.sp24), contentAlignment = Alignment.Center) {
            if (playing) {
                PlayingIndicator(tint = palette.primary)
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
            color = if (playing) palette.primary else AppColors.textPrimary,
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
                    .semantics {
                        contentDescription = "下载 ${track.title}"
                        role = Role.Button
                    }
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

// ================================================================================================
// 下载助手：DRM 走 RC4 流式解密（Kotlin 无 ffmpeg，跳过元数据打标），非 DRM 走 detailDownloadSong
// ================================================================================================

/**
 * 下载单曲（DRM 解密；非 DRM 走 [detailDownloadSong]），返回最终文件路径。
 *
 * 对齐 Dart `_downloadKkboxTrack`；差异：解密后经 [DownloadService.tagAudioFile] 走纯 Kotlin
 * 打标（MP3/FLAC 可写，其余格式保留原文件），DRM 文件固定落盘到 `cacheDir/downloads`。
 */
private suspend fun kkboxDetailDownloadTrack(
    context: Context,
    track: KkboxTrack,
    songId: String,
    kkboxViewModel: KkboxViewModel,
    accountId: String,
    session: KkboxSession,
    updateProgress: (Double) -> Unit,
): String {
    val dl = getDownloadUrl(session.sid, track.id, session.kkid, "hires")
    if (dl.sid != session.sid) {
        kkboxViewModel.updateAccountSession(
            accountId,
            KkboxSession(
                sid = dl.sid,
                licContentKey = session.licContentKey,
                highQuality = session.highQuality,
                kkid = session.kkid,
                email = session.email,
                loggedIn = true,
            ),
        )
    }
    val qualityKey = formatNameToQuality(dl.formatName)
    val lyricResult = getSongLyrics(dl.sid, track.id)

    if (isDrmFormat(dl.formatName)) {
        val safeId = songId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val tmpFile = File(context.cacheDir, "kkbox_drm_$safeId${qualityExtension(qualityKey)}")
        streamDecryptKkboxAudio(dl.url, session.licContentKey, tmpFile.absolutePath) { p ->
            updateProgress(p * 90.0)
        }
        updateProgress(92.0)
        val safeName = "${track.title} - ${track.artist}${qualityExtension(qualityKey)}"
            .replace(Regex("""[/\\?%*:|"<>]"""), "_")
        // 对齐 Flutter：DRM 文件固定写入应用私有 cacheDir/downloads/，忽略自定义下载目录
        val downloadsDir = File(context.cacheDir, "downloads")
        if (!downloadsDir.exists()) downloadsDir.mkdirs()
        val finalFile = File(downloadsDir, safeName)
        if (finalFile.exists()) finalFile.delete()
        val renamed = tmpFile.renameTo(finalFile)
        val out = if (renamed) {
            finalFile
        } else {
            tmpFile.copyTo(finalFile, overwrite = true)
            tmpFile.delete()
            finalFile
        }
        // 元数据打标（对齐 Flutter `_tagDrmAudio`；复用 DownloadService 的纯 Kotlin 写入器）
        try {
            DownloadService.tagAudioFile(
                inputPath = out.absolutePath,
                title = track.title,
                artist = track.artist,
                album = track.album,
                coverUrl = track.cover,
                // 对齐 Flutter：DRM 打标只写原始歌词（不合并翻译）
                lyric = lyricResult.lrc,
                albumArtist = track.albumArtist,
                trackNumber = if (track.trackNumber > 0) track.trackNumber.toString() else null,
                releaseDate = track.releaseDate,
                coverCacheKey = songId,
            )
        } catch (_: Throwable) {
            // 打标失败保留原文件（对齐 Dart：失败则直接用原始文件）
        }
        updateProgress(100.0)
        return out.absolutePath
    }

    return detailDownloadSong(
        context = context,
        songId = songId,
        title = track.title,
        artist = track.artist,
        coverUrl = track.cover,
        album = track.album,
        downloadUrl = dl.url,
        quality = kkboxDetailQualityFromKey(qualityKey),
        onProgress = { p -> updateProgress(p * 100.0) },
        lyric = lyricResult.lrc,
        tlyric = lyricResult.tlyric,
        albumArtist = track.albumArtist,
        trackNumber = if (track.trackNumber > 0) track.trackNumber.toString() else null,
        releaseDate = track.releaseDate,
    )
}

/** KKBOX 平台音质 key -> [Quality]（对齐 Dart `_qualityFromKey`） */
private fun kkboxDetailQualityFromKey(key: String): Quality = when (key) {
    "hires" -> Quality.hires
    "hifi" -> Quality.flac
    "320k", "192k" -> Quality.k320k
    else -> Quality.k128k
}

/** 去掉 `Exception: ` 前缀的错误文案（对齐 Dart `_errText`） */
private fun kkboxDetailErrText(e: Throwable): String =
    (e.message ?: "").replaceFirst("Exception: ", "")

