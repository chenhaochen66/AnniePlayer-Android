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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.annieplayer.annie_music.kt.core.state.AlbumDetailData
import com.annieplayer.annie_music.kt.core.state.ColorViewModel
import com.annieplayer.annie_music.kt.core.state.DownloadViewModel
import com.annieplayer.annie_music.kt.core.state.MoreSettings
import com.annieplayer.annie_music.kt.core.util.enqueueDownload
import com.annieplayer.annie_music.kt.data.resolveDownloadLyric
import com.annieplayer.annie_music.kt.features.downloads.DownloadMeta
import com.annieplayer.annie_music.kt.features.downloads.batchDownloadQualities
import com.annieplayer.annie_music.kt.features.downloads.downloadOneAndRegister
import com.annieplayer.annie_music.kt.core.state.PlayerViewModel
import com.annieplayer.annie_music.kt.core.state.UiViewModel
import com.annieplayer.annie_music.kt.data.fetchAlbumSongsByPlatform
import com.annieplayer.annie_music.kt.data.fetchDownloadUrlBySong
import com.annieplayer.annie_music.kt.data.fetchLyric
import com.annieplayer.annie_music.kt.data.fetchPlayUrlBySong
import com.annieplayer.annie_music.kt.data.sources.qobuzQualityLabel
import com.annieplayer.annie_music.kt.data.model.AlbumSong
import com.annieplayer.annie_music.kt.data.model.DownloadStatus
import com.annieplayer.annie_music.kt.data.model.DownloadTask
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
import com.annieplayer.annie_music.kt.ui.theme.ColorPalette
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 详情页音质面板宿主状态（专辑 / 歌手 / 歌单详情共用）。
 *
 * 背景：`AppShell` 的覆盖层顺序把「迷你播放条」(`AppBottomBar`) 画在 `GlobalOverlays`
 * （三个详情 sheet）之上，因此若音质面板仍内联在详情 sheet 内部，下半截会被迷你条压住。
 * 为与搜索页观感一致（面板盖住迷你条），详情 sheet 不再内联渲染面板，而是把面板内容交给本
 * 宿主，由 `AppShell` 在迷你条**之后**（对齐 [com.annieplayer.annie_music.kt.core.routing.Overlay]
 * 的 QUALITY_PICKER 层）统一渲染。
 *
 * 每个详情 sheet 持有一个唯一 `owner`（`remember { Any() }`）：仅当自己的面板打开时才抢占
 * 槽位；关闭时不写槽位，保留内容让面板自带的淡出动画自然播完；sheet 退出时清理自己的槽位，
 * 避免面板残留在上层。
 */
class DetailQualityPickerHost {
    /** 当前由 AppShell 顶层渲染的面板内容；由打开的详情 sheet 提供。 */
    var slot by mutableStateOf<(@Composable () -> Unit)?>(null)
        private set

    private var owner: Any? = null

    /** 详情 sheet 的面板打开时调用：抢占槽位。 */
    fun show(owner: Any, content: @Composable () -> Unit) {
        this.owner = owner
        slot = content
    }

    /** 详情 sheet 退出时调用：仅当槽位仍属于自己时清空（避免误清上层详情页的面板）。 */
    fun clear(owner: Any) {
        if (this.owner === owner) {
            this.owner = null
            slot = null
        }
    }
}

/**
 * 由 `AppShell` 提供。未提供时（例如独立整页版 [com.annieplayer.annie_music.kt.features.album.AlbumDetailScreen]
 * 单独渲染）详情页退化为内联渲染，行为与改造前一致。
 */
val LocalDetailQualityPickerHost = staticCompositionLocalOf<DetailQualityPickerHost?> { null }

/**
 * 专辑详情浮层（1:1 移植 Flutter `lib/widgets/album_detail_sheet.dart`，对齐 RN 版
 * components/AlbumDetailOverlay.tsx）。
 *
 * 结构：返回条（含「多选 / 全选」）→ 专辑头部（封面 100 + 名称 / 歌手 / 曲目数）
 * → 玻璃列表容器（曲目 60dp 行，行内展示下载任务状态）；多选时列表头追加「多选下载条」
 * （[MultiSelectDownloadBar]）。
 *
 * 视觉（液态玻璃链路，与整页版 AlbumDetailScreen 一致）：面板背景 = 封面模糊 + 取色染色氛围
 * （[CoverPaletteBackdrop]）；列表 = 毛玻璃容器（[glassPanel]，圆角 [AppRadius.panel]）；
 * 命中当前播放行 = 正在播放动效（[playingRowBackground] + [PlayingIndicator]）；进入时把全局
 * 色板临时切为该专辑色板、退出恢复（[SnapshotPaletteAndRestore]）。
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
    data: AlbumDetailData,
    isTop: Boolean,
    onClose: () -> Unit,
    uiViewModel: UiViewModel,
    playerViewModel: PlayerViewModel,
    downloadViewModel: DownloadViewModel,
    modifier: Modifier = Modifier,
    colorViewModel: ColorViewModel = activityViewModel(),
) {
    // 数据与可见性由宿主（详情真栈）传入：不再读 uiState 里的可见性/数据，也没有 early-return，
    // 因此栈内每一层详情各自渲染自己那份 data、互不干扰。
    key(data.id) {
        AlbumDetailContent(
            data = data,
            isTop = isTop,
            onClose = onClose,
            uiViewModel = uiViewModel,
            playerViewModel = playerViewModel,
            downloadViewModel = downloadViewModel,
            modifier = modifier,
            colorViewModel = colorViewModel,
        )
    }
}

/** 专辑详情内部可变状态（对齐 Dart `_AlbumDetailViewState` 的字段） */
private class AlbumDetailState {
    var loading by mutableStateOf(true)
    var error by mutableStateOf("")
    var songs by mutableStateOf<List<AlbumSong>>(emptyList())
    var qualitySpec by mutableStateOf("")
    var releaseDate by mutableStateOf("")

    var selectMode by mutableStateOf(false)
    val selectedIds = mutableStateListOf<String>()

    var pickerVisible by mutableStateOf(false)
    var batchDownloading by mutableStateOf(false)
    var batchProgress by mutableStateOf("")
}

@Composable
private fun AlbumDetailContent(
    data: AlbumDetailData,
    isTop: Boolean,
    onClose: () -> Unit,
    uiViewModel: UiViewModel,
    playerViewModel: PlayerViewModel,
    downloadViewModel: DownloadViewModel,
    modifier: Modifier = Modifier,
    colorViewModel: ColorViewModel,
) {
    val uiState by uiViewModel.state.collectAsState()
    val st = remember { AlbumDetailState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val tasks by downloadViewModel.state.collectAsState()
    val playerState by playerViewModel.state.collectAsState()

    /** 封面动态色板（与播放页同一取色链路；同 url 进程级缓存见 core/util/ColorExtractor.kt） */
    val palette = rememberCoverPalette(data.cover)

    // 进入浮层把全局色板临时切为该专辑色板，退出恢复进入前的色板（与整页版同一套取色链路）
    // 不再写入全局色板（原 SnapshotPaletteAndRestore）：详情页只用本地 palette 渲染，
// 全局色板由 AppShell 按「当前播放歌曲」驱动；详情页写入会在退出时覆盖成陈旧值。

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
        // 必须带上专辑接口给的真实音质（否则详情页下载候选永远回退 flac/320/128，Hi-Res 丢失）
        qualities = s.qualities.ifEmpty { listOf(Quality.flac, Quality.k320k, Quality.k128k) },
        qualitySizes = s.qualitySizes,
        albumArtist = data.artist,
        artistNames = if (s.artistNames.isNotEmpty()) s.artistNames else listOf(s.artistName),
        artistIds = s.artistIds,
        trackNumber = s.track?.toString(),
        discNumber = s.discNumber?.toString(),
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
                    playerViewModel.updateSongLyric(target.id, lyric.lrc, lyric.tlyric, lyric.wordLyric, lyric.translateLangs)
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
            val ok = downloadOneAndRegister(
                downloadViewModel = downloadViewModel,
                playerViewModel = playerViewModel,
                song = song,
                quality = quality,
                meta = DownloadMeta(
                    coverUrl = data.cover,
                    album = data.name,
                    albumArtist = data.artist,
                    trackNumber = if (s.track != null) s.track.toString() else "${i + 1}",
                    releaseDate = st.releaseDate.ifEmpty { null },
                ),
                onProgress = { p -> downloadViewModel.updateProgress(s.id, (p * 100).coerceIn(0.0, 100.0)) },
            )
            if (ok) success++
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
            st.qualitySpec = result.qualitySpec
            st.songs = result.songs.sortedWith(
                compareBy({ it.discNumber ?: 1 }, { it.track ?: Int.MAX_VALUE }),
            )
            st.releaseDate = result.pub
        } catch (e: Exception) {
            val msg = (e.message ?: "").replaceFirst("Exception: ", "")
            st.error = msg.ifEmpty { "加载失败" }
        } finally {
            st.loading = false
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

    // 关闭入口：先反向滑出（与滑入完全对称），动画结束后才真正出栈；动画期间重入忽略。
    // slide 是 remember，closing 也 remember，生命周期对齐。
    var closing by remember { mutableStateOf(false) }
    fun requestClose() {
        if (closing) return
        closing = true
        scope.launch {
            slide.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.linear),
            )
            onClose()
        }
    }

    // 返回手势：多选模式优先退出（子页返回不播滑出）；仅栈顶详情消费，更高层浮层（音质 / 队列 /
    // 播放页 / ⋮ / 多歌手 / 下载队列）可见时让位（见 OverlayStack.kt）。是否栈顶由宿主传入的 isTop 决定。
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
    val detailIsTop = isTop &&
        !uiState.downloadQueueVisible &&
        handleBack(higherFlags) == BackTarget.DETAIL
    BackHandler(enabled = st.selectMode || detailIsTop) {
        if (st.selectMode) {
            toggleSelectMode()
        } else {
            requestClose()
        }
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
                coverUrl = data.cover,
                palette = palette,
                modifier = Modifier.fillMaxSize(),
            )
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                AlbumDetailHeader(
                    selectMode = st.selectMode,
                    selectedAll = st.selectedIds.size == st.songs.size,
                    onClose = { requestClose() },
                    onToggleSelectAll = { toggleSelectAll() },
                    onToggleSelectMode = { toggleSelectMode() },
                )
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    AlbumDetailBody(
                        st = st,
                        data = data,
                        tasks = tasks,
                        palette = palette,
                        currentSongId = playerState.currentSong?.id,
                        onPlay = { scope.launch { play(it) } },
                        onDownload = { uiViewModel.setSongActionsVisible(true, toSong(it)) },
                        onToggle = { toggleSelect(it) },
                        onBatch = { st.pickerVisible = true },
                    )
                }
            }
        }

        // ── 音质选择器（批量下载，复用 QualityPickerSheet） ──
        // 面板内容交给 AppShell 在「迷你播放条之上」渲染（见 DetailQualityPickerHost 注释）；
        // 无宿主（独立整页版）时退化为内联渲染，行为不变。
        val pickerHost = LocalDetailQualityPickerHost.current
        val pickerOwner = remember { Any() }
        val pickerPanel: @Composable () -> Unit = {
            val pickerSongs = st.songs.filter { st.selectedIds.contains(it.id) }.map { toSong(it) }
            QualityPickerSheet(
                visible = st.pickerVisible,
                // 与曲目实际可用音质一致（Qobuz 专辑含 Hi-Res，其余三档）
                qualities = batchDownloadQualities(pickerSongs),
                sizeOf = { q -> if (pickerSongs.size == 1) pickerSongs.first().qualitySizes[q] else null },
                labelOf = { q ->
                    if (data.platform == MusicPlatform.qobuz) qobuzQualityLabel(q) else q.label
                },
                    onSelect = { q ->
                        // 跑在全局串行队列上：本弹窗的作用域一关就作废，
                        // 若用 scope.launch，弹窗一关剩下的歌会全部抛 ForgottenCoroutineScopeException
                        enqueueDownload("albumSheet_batch_" + System.currentTimeMillis()) { batchDownload(q) }
                    },
                onCancel = { st.pickerVisible = false },
            )
        }
        if (pickerHost == null) {
            pickerPanel()
        } else {
            // 只在面板打开时抢占槽位；关闭时不写，保留内容让面板自带淡出动画播完。
            SideEffect {
                if (st.pickerVisible) pickerHost.show(pickerOwner, pickerPanel)
            }
            DisposableEffect(pickerOwner) {
                onDispose { pickerHost.clear(pickerOwner) }
            }
        }
    }
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
                .clickableRipple(onClose)
                .padding(AppSpacing.sp4),
            contentAlignment = Alignment.Center,
        ) {
            DetailChevronLeft(modifier = Modifier.size(AppIconSize.icon22), tint = AppColors.textSecondary)
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp8))
        Text(
            text = "专辑详情",
            color = AppColors.textPrimary,
            fontSize = AppFontSize.size17,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.weight(1f))
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

@Composable
private fun AlbumDetailBody(
    st: AlbumDetailState,
    data: AlbumDetailData,
    tasks: Map<String, DownloadTask>,
    palette: ColorPalette,
    currentSongId: String?,
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
            // 毛玻璃列表容器：整块列表（含头部）压在封面取色背景之上
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = AppSpacing.sp12, vertical = AppSpacing.sp8)
                    .glassPanel(shape = RoundedCornerShape(AppRadius.panel)),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // 底部内边距保留「迷你条 + 手势条」空间
                    contentPadding = PaddingValues(bottom = AppSpacing.listBottomInset),
                ) {
                    item {
                        AlbumDetailHero(
                            st = st,
                            data = data,
                            onPlayAll = { st.songs.firstOrNull()?.let(onPlay) },
                            onBatch = onBatch,
                        )
                    }
                    val multiDisc = st.songs.mapNotNull { it.discNumber }.distinct().size > 1
                    items(count = st.songs.size) { index ->
                        val song = st.songs[index]
                        val newDisc = multiDisc &&
                            (index == 0 || st.songs[index - 1].discNumber != song.discNumber)
                        Column {
                            if (newDisc) {
                                Text(
                                    text = "CD ${song.discNumber ?: 1}",
                                    color = AppColors.textTertiary,
                                    fontSize = AppFontSize.size13,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(
                                        horizontal = AppSpacing.sp16,
                                        vertical = AppSpacing.sp10,
                                    ),
                                )
                            }
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
                                onToggle = onToggle,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 头部 Hero：大封面居中 + 居中标题/歌手/元信息 + 动作行（播放全部 / 下载）。
 *
 * 元信息行 =「年份 · N 首」，Qobuz 专辑再追加最高位深·采样率（[AlbumDetailState.qualitySpec]）；
 * 缺哪项自动省略、不留悬空分隔符。多选时在其下追加 [MultiSelectDownloadBar]（行为与原来一致）。
 */
@Composable
private fun AlbumDetailHero(
    st: AlbumDetailState,
    data: AlbumDetailData,
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
            var coverFailed by remember(data.id) { mutableStateOf(false) }
            if (data.cover.isEmpty() || coverFailed) {
                MusicNoteGlyph(modifier = Modifier.size(AppIconSize.icon36), tint = AppColors.textDisabled)
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
        Spacer(modifier = Modifier.height(AppSpacing.sp16))
        Text(
            text = data.name,
            color = AppColors.textPrimary,
            fontSize = AppFontSize.size20,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(AppSpacing.sp4))
        Text(
            text = data.artist,
            color = AppColors.whiteA50,
            fontSize = AppFontSize.size15,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val meta = albumDetailMetaLine(st)
        if (meta.isNotEmpty()) {
            Spacer(modifier = Modifier.height(AppSpacing.sp4))
            Text(
                text = meta,
                color = AppColors.whiteA40,
                fontSize = AppFontSize.size13,
                textAlign = TextAlign.Center,
            )
        }
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

/** Hero 元信息行：「年份 · N 首 · 最高位深/采样率」；缺项自动省略 */
private fun albumDetailMetaLine(st: AlbumDetailState): String {
    val parts = mutableListOf<String>()
    val year = st.releaseDate.trim().take(4)
    if (year.length == 4 && year.all { it.isDigit() }) parts.add(year)
    parts.add("${st.songs.size} 首")
    if (st.qualitySpec.isNotEmpty()) parts.add(st.qualitySpec)
    return parts.joinToString(" · ")
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
            .height(AppSize.detailRowHeight)
            // 正在播放 → 主色氛围底 + 左侧主色条；否则选中态浅高亮
            .then(
                when {
                    isPlaying -> Modifier.playingRowBackground(playingTint)
                    selected -> Modifier.background(AppColors.primaryA08)
                    else -> Modifier
                },
            )
            .clickableNoRipple { if (selectMode) onToggle(song.id) else onPlay(song) }
            .padding(horizontal = AppSpacing.sp16, vertical = AppSpacing.sp10),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectMode) {
            // 多选对勾（替换原自绘勾选方框；点击对勾与点击行都切换选中）
            DetailCheckBox(
                checked = selected,
                onToggle = { onToggle(song.id) },
            )
            Spacer(modifier = Modifier.width(AppSpacing.sp10))
        }
        Box(
            modifier = Modifier.width(AppSpacing.sp28),
            contentAlignment = Alignment.Center,
        ) {
            if (isPlaying) {
                PlayingIndicator(tint = playingTint)
            } else {
                Text(
                    text = song.track?.toString() ?: "${index + 1}",
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
                text = song.name,
                color = if (isPlaying) playingTint else AppColors.textPrimary,
                fontSize = AppFontSize.lg,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp1))
            Text(
                text = song.artistName,
                color = AppColors.textTertiary,
                fontSize = AppFontSize.size13,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (task != null && task.status != DownloadStatus.completed) {
            AlbumDetailTaskBadge(task = task)
        } else if (!selectMode) {
            Box(modifier = Modifier.semantics { contentDescription = "更多操作 ${song.name}" }) {
                SongMoreButton(onClick = { onDownload(song) })
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
