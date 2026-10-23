package com.annieplayer.annie_music.kt.widgets

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import com.annieplayer.annie_music.kt.core.state.AlbumDetailEntry
import com.annieplayer.annie_music.kt.core.state.ColorViewModel
import com.annieplayer.annie_music.kt.core.state.DownloadViewModel
import com.annieplayer.annie_music.kt.core.state.MoreSettings
import com.annieplayer.annie_music.kt.core.util.enqueueDownload
import com.annieplayer.annie_music.kt.data.resolveDownloadLyric
import com.annieplayer.annie_music.kt.features.downloads.DownloadMeta
import com.annieplayer.annie_music.kt.features.downloads.batchDownloadQualities
import com.annieplayer.annie_music.kt.features.downloads.downloadOneAndRegister
import com.annieplayer.annie_music.kt.core.state.PlayerViewModel
import com.annieplayer.annie_music.kt.core.state.SingerDetailData
import com.annieplayer.annie_music.kt.core.state.UiViewModel
import com.annieplayer.annie_music.kt.data.DownloadDirService
import com.annieplayer.annie_music.kt.data.enrichSong
import com.annieplayer.annie_music.kt.data.fetchArtistAlbumsByPlatform
import com.annieplayer.annie_music.kt.data.fetchArtistAvatarByPlatform
import com.annieplayer.annie_music.kt.data.fetchArtistBioByPlatform
import com.annieplayer.annie_music.kt.data.fetchArtistSongsByPlatform
import com.annieplayer.annie_music.kt.data.fetchDownloadUrlBySong
import com.annieplayer.annie_music.kt.data.fetchLyric
import com.annieplayer.annie_music.kt.data.fetchPlayUrlBySong
import com.annieplayer.annie_music.kt.data.playQualityLabelOf
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.sources.getAppleArtistReleases
import com.annieplayer.annie_music.kt.data.model.Album
import com.annieplayer.annie_music.kt.data.model.ArtistSongsPageSize
import com.annieplayer.annie_music.kt.data.model.DownloadStatus
import com.annieplayer.annie_music.kt.data.model.DownloadTask
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.network.globalOkHttp
import com.annieplayer.annie_music.kt.data.sources.buildAlbumCoverUrl
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
import kotlinx.coroutines.coroutineScope
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
    data: SingerDetailData,
    isTop: Boolean,
    onClose: () -> Unit,
    uiViewModel: UiViewModel,
    playerViewModel: PlayerViewModel,
    downloadViewModel: DownloadViewModel,
    modifier: Modifier = Modifier,
    colorViewModel: ColorViewModel = activityViewModel(),
) {
    // 数据与可见性由宿主（详情真栈）传入：不再读 uiState 里的可见性/数据，也没有 early-return。
    key(data.id) {
        SingerDetailContent(
            data = data,
            isTop = isTop,
            onClose = onClose,
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
    var tab by mutableStateOf("overview")

    var songs by mutableStateOf<List<Song>>(emptyList())
    var songTotal by mutableStateOf(0)
    var songPageable by mutableStateOf(true)
    var songPage by mutableStateOf(1)
    var songLoading by mutableStateOf(false)
    var songError by mutableStateOf("")

    var albums by mutableStateOf<List<Album>>(emptyList())
    var albumLoading by mutableStateOf(false)
    var albumSortAsc by mutableStateOf(false)

    /** Apple：专辑与 EP/单曲分开显示 */
    var appleAlbums by mutableStateOf<List<Album>>(emptyList())
    var appleSingles by mutableStateOf<List<Album>>(emptyList())
    var appleReleaseSection by mutableStateOf<AppleReleaseSection?>(null)

    val selectedSongs = mutableStateListOf<Song>()

    /** 多选模式显式开关（长按行或头部「多选」chip 进入；「取消」/ 批量下载完成后退出）。 */
    var selectMode by mutableStateOf(false)

    var batchPickerVisible by mutableStateOf(false)
    var batchDownloading by mutableStateOf(false)

    /** 批量下载进度文案（[MultiSelectDownloadBar] 下载中展示，形如 `3/12 歌名`）。 */
    var batchProgress by mutableStateOf("")

    var avatarUrl by mutableStateOf("")

    /** 歌手简介（各平台从已有的歌手接口顺带取；为空则整段不显示） */
    var bio by mutableStateOf("")
}

@Composable
private fun SingerDetailContent(
    data: SingerDetailData,
    isTop: Boolean,
    onClose: () -> Unit,
    uiViewModel: UiViewModel,
    playerViewModel: PlayerViewModel,
    downloadViewModel: DownloadViewModel,
    colorViewModel: ColorViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by uiViewModel.state.collectAsState()
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
            // 酷我 / 酷狗的「歌手单曲」接口不带结构化歌手字段：用本页歌手补上，
            // 这样行尾 ⋮ 的「跳转到歌手」有确定 id（秒开），也不会把组合名按 & 拆成两个人。
            st.songs = result.songs.map { s ->
                if (s.artistNames.isEmpty()) {
                    s.copy(artistNames = listOf(data.name), artistIds = listOf(data.id))
                } else {
                    s
                }
            }
            st.songTotal = result.total
            st.songPageable = result.pageable
        } catch (e: Exception) {
            st.songError = e.message ?: "加载失败"
        } finally {
            st.songLoading = false
        }
    }

    suspend fun loadAlbums() {
        st.albumLoading = true
        try {
            if (data.platform == MusicPlatform.apple) {
                val releases = getAppleArtistReleases(data.id)
                st.appleSingles = releases.singles
                st.appleAlbums = releases.albums
                st.albums = releases.singles + releases.albums
            } else {
                st.albums = fetchArtistAlbumsByPlatform(data.platform, data.id, artistName = data.name)
            }
        } catch (_: Exception) {
            st.albums = emptyList()
            st.appleSingles = emptyList()
            st.appleAlbums = emptyList()
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

    // 简介必须独立于头像请求：从「跳转到歌手」进来时 data.picture 非空，头像那一支会直接跳过
    suspend fun loadBioIfNeeded() {
        if (st.bio.isNotEmpty()) return
        try {
            val bio = fetchArtistBioByPlatform(data.platform, data.id, data.name)
            if (bio.isNotEmpty()) st.bio = bio
        } catch (_: Exception) {
            // 拿不到就整段不显示
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
                st.appleReleaseSection = null
                scope.launch { loadAlbums() }
            }
    }

    /** Apple overview「全部」：只进对应的 EP/单曲或专辑段，而不是两段一起看。 */
    fun openAppleReleaseSection(section: AppleReleaseSection) {
        st.appleReleaseSection = section
        st.tab = "album"
        st.selectMode = false
        st.selectedSongs.clear()
        if (st.albums.isEmpty()) scope.launch { loadAlbums() }
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

    suspend fun playSong(song: Song) {
        val idx = st.songs.indexOfFirst { it.id == song.id }
        if (idx < 0) return
        playerViewModel.setQueue(st.songs, idx)
        try {
            val url = fetchPlayUrlBySong(song)
            val lyric = fetchLyric(song)
            playerViewModel.updateSongPlayUrl(song.id, url)
                    playerViewModel.updateSongLyric(song.id, lyric.lrc, lyric.tlyric, lyric.wordLyric, lyric.translateLangs)
        } catch (_: Exception) {
            // 对齐 Dart：拉取失败静默忽略
        }
    }

    fun openAlbum(album: Album) {
        // 从歌手页跳专辑：把专辑详情**压栈**（歌手页留在下层、返回可逐层退回），
        // 而不再先关掉自己（旧行为会收起歌手页）。
        uiViewModel.pushDetail(
            AlbumDetailEntry(
                AlbumDetailData(
                    id = album.albumid,
                    cover = buildAlbumCoverUrl(album),
                    name = album.name,
                    artist = album.artist,
                    platform = album.platform,
                ),
            ),
        )
    }

    suspend fun batchDownload(quality: Quality) {
        st.batchPickerVisible = false
        // 不再置 batchDownloading：详情页不展示下载进度，也不把「下载全部」按钮变灰/禁用。
        // （字段保留只为兼容既有引用；按钮任何时候都可点，同一首也可反复下载。）
        val songs = st.selectedSongs.toList()
        for (s in songs) downloadViewModel.addTask(s, quality)
        for ((i, song) in songs.withIndex()) {
            // 只补 UI 进度文案（下载流程本身与原来一致）
            st.batchProgress = "${i + 1}/${songs.size} ${song.title}"
            downloadOneAndRegister(
                downloadViewModel = downloadViewModel,
                playerViewModel = playerViewModel,
                song = song,
                quality = quality,
                meta = DownloadMeta(
                    albumArtist = song.albumArtist ?: artistName,
                    composer = song.composer,
                    genre = song.genre,
                    isrc = song.isrc,
                ),
                onProgress = { p -> downloadViewModel.updateProgress(song.id, (p * 100).coerceIn(0.0, 100.0)) },
            )
        }
        st.batchDownloading = false
        st.batchProgress = ""
        st.selectedSongs.clear()
        st.selectMode = false
    }

    LaunchedEffect(Unit) {
        if (data.platform == MusicPlatform.apple) {
            // Apple 详情涉及歌曲、专辑、EP/单曲、头像、简介多个接口，并行拉取
            coroutineScope {
                launch { loadSongs(1) }
                launch { loadAlbums() }
                launch { loadAvatarIfNeeded() }
                launch { loadBioIfNeeded() }
            }
        } else {
            loadSongs(1)
            // overview 需要同时有「Most popular 前几首」与「Albums 横滑」，进页就把两者都拉上
            loadAlbums()
            loadAvatarIfNeeded()
            loadBioIfNeeded()
        }
    }

    // 返回手势：本页音质选择器优先；更高层浮层可见时让位（见 OverlayStack.kt）
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
    val detailIsTop = isTop &&
        !uiState.downloadQueueVisible &&
        handleBack(higherFlags) == BackTarget.DETAIL

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

    // 子页（全部歌曲 / 全部专辑）的返回先回 overview（不播滑出），overview 才真正关面板
    fun goBack() {
        if (st.tab != "overview") {
            st.tab = "overview"
            st.selectMode = false
            st.selectedSongs.clear()
            // overview 的「最热门」只认第 1 页 → 从完整列表回来时把列表复位
            if (st.songPage != 1) scope.launch { loadSongs(1) }
        } else {
            requestClose()
        }
    }

    BackHandler(enabled = detailIsTop) { goBack() }

    val totalPages = (st.songTotal + PageSize - 1) / PageSize
    val pageRange = singerDetailPageRange(st.songPage, totalPages)
    val showPagination = st.songPageable && totalPages > 1

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
                    // 多选入口只在「全部歌曲」子页出现（专辑子页没有歌曲可选）
                    showSelectEntry = st.tab == "song",
                    selectMode = st.tab == "song" && st.selectMode,
                    selectedAll = st.tab == "song" && st.selectMode &&
                        singerDetailTriState(st.songs, st.selectedSongs) ==
                        SingerDetailTriState.Checked,
                    onClose = { goBack() },
                    onToggleSelectAll = { toggleSelectAll() },
                    onToggleSelectMode = { toggleSelectMode() },
                    title = when (st.tab) {
                        "song" -> "全部单曲"
                        "album" -> "全部专辑"
                        else -> "歌手详情"
                    },
                    titleCount = if (st.tab == "song" && st.songTotal > 0) {
                        "${st.songTotal} 首"
                    } else {
                        null
                    },
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
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    AnimatedContent(
                        targetState = st.tab,
                        transitionSpec = {
                            // 进子页时新页从右侧滑入、返回时从左侧滑入（overview 视为上一层）
                            val enterFromRight = targetState != "overview"
                            val enter = slideInHorizontally(
                                animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.linear),
                            ) { if (enterFromRight) it else -it / 3 }
                            val exit = slideOutHorizontally(
                                animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.linear),
                            ) { if (enterFromRight) -it / 3 else it }
                            (enter + fadeIn(tween(durationMillis = AppDuration.sheet, easing = AppEasing.linear))) togetherWith
                                (exit + fadeOut(tween(durationMillis = AppDuration.sheet, easing = AppEasing.linear)))
                        },
                        label = "singerDetailView",
                    ) { tab ->
                        when (tab) {
                            "overview" -> SingerDetailOverview(
                                st = st,
                                data = data,
                                artistName = artistName,
                                apple = data.platform == MusicPlatform.apple,
                                activeSongId = playerState.currentSong?.id,
                                onPlay = { scope.launch { playSong(it) } },
                                onDownload = { uiViewModel.setSongActionsVisible(true, it) },
                                onOpenAlbum = { openAlbum(it) },
                                onSeeAllSongs = { switchTab("song") },
                                onSeeAllSingles = { openAppleReleaseSection(AppleReleaseSection.singles) },
                                onSeeAllAlbums = {
                                    if (data.platform == MusicPlatform.apple) {
                                        openAppleReleaseSection(AppleReleaseSection.albums)
                                    } else {
                                        switchTab("album")
                                    }
                                },
                            )

                            "song" -> SingerDetailSongs(
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

                            else -> SingerDetailAlbums(
                                st = st,
                                apple = data.platform == MusicPlatform.apple,
                                filter = st.appleReleaseSection,
                                onSortToggle = { st.albumSortAsc = !st.albumSortAsc },
                                onOpenAlbum = { openAlbum(it) },
                            )
                        }
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
                visible = st.batchPickerVisible,
                qualities = batchDownloadQualities(st.selectedSongs.toList()),
                sizeOf = { q ->
                    if (st.selectedSongs.size == 1) st.selectedSongs.first().qualitySizes[q] else null
                },
                labelOf = { q -> playQualityLabelOf(data.platform, q) },
                    onSelect = { q ->
                        // 跑在全局串行队列上（同 AlbumDetailSheet）：弹窗一关作用域就作废
                        enqueueDownload("singerSheet_batch_" + System.currentTimeMillis()) { batchDownload(q) }
                    },
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

/** 分页大小（对齐 Dart `_pageSize = 20`） */
private const val PageSize = ArtistSongsPageSize

/** 当前页歌曲是否全部已选（头部三态全选用；空列表视为未全选）。 */
private fun singerDetailAllSelected(pageSongs: List<Song>, selected: List<Song>): Boolean =
    pageSongs.isNotEmpty() && pageSongs.all { s -> selected.any { it.id == s.id } }

/** 当前页歌曲的三态（全选 / 部分选中 / 未选），供头部「全选 / 取消全选」按钮使用。 */
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
 * 返回条：chevron_left 22 +（多选态）全选 / 取消全选 + 多选 / 取消。
 *
 * 与专辑详情同款：都是纯文字主色按钮（不用玻璃 chip），多选入口只在「全部歌曲」子页出现。
 */
@Composable
private fun SingerDetailHeader(
    selectMode: Boolean,
    selectedAll: Boolean,
    showSelectEntry: Boolean,
    onClose: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onToggleSelectMode: () -> Unit,
    title: String? = null,
    titleCount: String? = null,
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
        if (!title.isNullOrEmpty()) {
            Spacer(modifier = Modifier.width(AppSpacing.sp8))
            Text(
                text = title,
                modifier = Modifier.weight(1f, fill = false),
                color = AppColors.textPrimary,
                fontSize = AppFontSize.size17,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!titleCount.isNullOrEmpty()) {
                Spacer(modifier = Modifier.width(AppSpacing.sp6))
                Text(
                    text = titleCount,
                    color = AppColors.whiteA40,
                    fontSize = AppFontSize.size13,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        if (showSelectEntry && selectMode) {
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
        if (showSelectEntry) {
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

/** overview 横向专辑卡尺寸（高 = 宽 + 文字区，与 [SingerDetailAlbums] 网格同比例） */
private val SingerDetailAlbumCardWidth = 140.dp
private val SingerDetailAlbumCardHeight = 208.dp

/** overview「最热门」每页显示的行数（横向翻页，一页 5 行） */
private const val SingerDetailPageRows = 5

/** overview「专辑」段最多展示的专辑数（按发行时间取最近的若干张） */
private const val SingerDetailOverviewAlbumCount = 10

/**
 * overview 页：大头像 Hero + 动作行 +「Most popular」前几首 +「Albums」横滑。
 *
 * 数据全部来自本页已有的 `st.songs`（第 1 页）与 `st.albums`；
 * 两个 section 右侧的「全部 ›」把 `st.tab` 切到对应子页。
 */
@Composable
private fun SingerDetailOverview(
    st: SingerDetailState,
    data: SingerDetailData,
    artistName: String,
    apple: Boolean,
    activeSongId: String?,
    onPlay: (Song) -> Unit,
    onDownload: (Song) -> Unit,
    onOpenAlbum: (Album) -> Unit,
    onSeeAllSongs: () -> Unit,
    onSeeAllSingles: () -> Unit,
    onSeeAllAlbums: () -> Unit,
) {
    // 「专辑」段只展示最近的若干张（按 pub 倒序，空值排最后）；Apple 两段也各只放预览
    val recentAlbums = remember(st.albums, apple) {
        if (apple) {
            emptyList()
        } else {
            st.albums.sortedByDescending { it.pub }.take(SingerDetailOverviewAlbumCount)
        }
    }
    val appleSingles = remember(st.appleSingles) {
        st.appleSingles.sortedByDescending { it.pub }.take(SingerDetailOverviewAlbumCount)
    }
    val appleAlbums = remember(st.appleAlbums) {
        st.appleAlbums.sortedByDescending { it.pub }.take(SingerDetailOverviewAlbumCount)
    }
    val overviewListState = rememberLazyListState()
    LazyColumn(
        state = overviewListState,
        modifier = Modifier.fillMaxSize().shortScrollbar(overviewListState),
        contentPadding = PaddingValues(bottom = AppSpacing.listBottomInset),
    ) {
        item {
            SingerDetailHero(
                name = artistName,
                avatarUrl = st.avatarUrl.ifEmpty { data.picture },
                avatarKey = data.id,
                bio = st.bio,
                onPlayAll = { st.songs.firstOrNull()?.let(onPlay) },
                playEnabled = st.songs.isNotEmpty(),
            )
        }
        item { SingerDetailSectionHeader(title = "最热门", onSeeAll = onSeeAllSongs) }
        item {
            // 只展示第 1 页的歌曲（st.songs 就是当前页，进 overview 时固定在第 1 页）；
            // 每页 [SingerDetailPageRows] 行，左右滑动翻页。
            val overviewSongs = st.songs.take(ArtistSongsPageSize)
            val pageCount = (overviewSongs.size + SingerDetailPageRows - 1) / SingerDetailPageRows
            if (pageCount > 0) {
                val pagerState = rememberPagerState(pageCount = { pageCount })
                HorizontalPager(
                    state = pagerState,
                    pageSpacing = AppSpacing.sp8,
                    contentPadding = PaddingValues(horizontal = AppSpacing.sp4),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(AppSize.detailRowHeight * SingerDetailPageRows),
                ) { page ->
                    Column(modifier = Modifier.fillMaxSize()) {
                        for (row in 0 until SingerDetailPageRows) {
                            val index = page * SingerDetailPageRows + row
                            if (index >= overviewSongs.size) break
                            val song = overviewSongs[index]
                            SingerDetailSongRow(
                                song = song,
                                index = index,
                                isActive = song.id == activeSongId,
                                isDownloading = false,
                                selected = false,
                                selectionMode = false,
                                onPlay = onPlay,
                                onDownload = onDownload,
                                onToggle = {},
                                onLongSelect = {},
                            )
                        }
                    }
                }
            }
        }
        if (apple) {
            if (appleSingles.isNotEmpty()) {
                item { SingerDetailSectionHeader(title = "单曲 / EP", onSeeAll = onSeeAllSingles) }
                item {
                    SingerDetailReleaseShelf(
                        albums = appleSingles,
                        loading = st.albumLoading,
                        onOpenAlbum = onOpenAlbum,
                    )
                }
            }
            if (appleAlbums.isNotEmpty()) {
                item { SingerDetailSectionHeader(title = "专辑", onSeeAll = onSeeAllAlbums) }
                item {
                    SingerDetailReleaseShelf(
                        albums = appleAlbums,
                        loading = st.albumLoading,
                        onOpenAlbum = onOpenAlbum,
                    )
                }
            }
        } else {
            item { SingerDetailSectionHeader(title = "专辑", onSeeAll = onSeeAllAlbums) }
            item {
                if (st.albumLoading && recentAlbums.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(SingerDetailAlbumCardHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(AppIconSize.icon24),
                            color = AppColors.primary,
                        )
                    }
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = AppSpacing.sp16),
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sp10),
                    ) {
                        items(count = recentAlbums.size) { i ->
                            val album = recentAlbums[i]
                            SingerDetailAlbumCard(
                                album = album,
                                coverUrl = buildAlbumCoverUrl(album),
                                modifier = Modifier
                                    .width(SingerDetailAlbumCardWidth)
                                    .height(SingerDetailAlbumCardHeight),
                                onTap = onOpenAlbum,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SingerDetailReleaseShelf(
    albums: List<Album>,
    loading: Boolean,
    onOpenAlbum: (Album) -> Unit,
) {
    if (loading && albums.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxWidth().height(SingerDetailAlbumCardHeight),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(AppIconSize.icon24),
                color = AppColors.primary,
            )
        }
        return
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = AppSpacing.sp16),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sp10),
    ) {
        items(count = albums.size) { i ->
            val album = albums[i]
            SingerDetailAlbumCard(
                album = album,
                coverUrl = buildAlbumCoverUrl(album),
                modifier = Modifier
                    .width(SingerDetailAlbumCardWidth)
                    .height(SingerDetailAlbumCardHeight),
                onTap = onOpenAlbum,
            )
        }
    }
}

/** overview 头部：正圆大头像（约 55% 屏宽）+ 居中艺人名 +「Artist」+ 播放胶囊 */
@Composable
private fun SingerDetailHero(
    name: String,
    avatarUrl: String,
    avatarKey: String,
    bio: String,
    onPlayAll: () -> Unit,
    playEnabled: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.sp24, vertical = AppSpacing.sp20),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        var avatarFailed by remember(avatarKey, avatarUrl) { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .fillMaxWidth(0.55f)
                .aspectRatio(1f)
                .clip(CircleShape)
                .background(AppColors.whiteA03),
            contentAlignment = Alignment.Center,
        ) {
            if (avatarUrl.isEmpty() || avatarFailed) {
                SingerDetailPersonOutline(
                    modifier = Modifier.size(AppIconSize.icon48),
                    tint = AppColors.textDisabled,
                )
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
        Spacer(modifier = Modifier.height(AppSpacing.sp16))
        Text(
            text = name,
            color = AppColors.textPrimary,
            fontSize = AppFontSize.size26,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(AppSpacing.sp4))
        Text(
            text = "歌手",
            color = AppColors.whiteA50,
            fontSize = AppFontSize.size13,
            textAlign = TextAlign.Center,
        )
        if (bio.isNotEmpty()) {
            Spacer(modifier = Modifier.height(AppSpacing.sp8))
            var bioExpanded by remember(avatarKey) { mutableStateOf(false) }
            Text(
                text = bio,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickableRipple { bioExpanded = !bioExpanded },
                color = AppColors.white70,
                fontSize = AppFontSize.size13,
                textAlign = TextAlign.Center,
                maxLines = if (bioExpanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp2))
            Text(
                text = if (bioExpanded) "收起" else "更多",
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .clickableRipple { bioExpanded = !bioExpanded }
                    .padding(horizontal = AppSpacing.sp12, vertical = AppSpacing.sp2),
                color = AppColors.primary,
                fontSize = AppFontSize.size13,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(modifier = Modifier.height(AppSpacing.sp16))
        DetailPlayAllPill(onClick = onPlayAll, enabled = playEnabled)
    }
}

/** section 标题行：左侧标题 + 右侧「全部 ›」玻璃 pill */
@Composable
private fun SingerDetailSectionHeader(title: String, onSeeAll: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = AppSpacing.sp16, end = AppSpacing.sp16, top = AppSpacing.sp16, bottom = AppSpacing.sp8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            color = AppColors.textPrimary,
            fontSize = AppFontSize.size20,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(AppColors.whiteA06)
                .clickableRipple(onSeeAll)
                .padding(horizontal = AppSpacing.sp12, vertical = AppSpacing.sp6),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "全部", color = AppColors.white70, fontSize = AppFontSize.xs)
            Spacer(modifier = Modifier.width(AppSpacing.sp2))
            SingerDetailChevronRight(
                modifier = Modifier.size(AppIconSize.icon13),
                tint = AppColors.white54,
            )
        }
    }
}

/**
 * 音质角标（歌曲行用）。
 *
 * Apple 只标两类特殊规格，且可以同时出现：高解析无损 → `Hi-Res`、Dolby Atmos → `Dolby`；
 * 两者都没有（普通 ALAC / AAC）就不渲染角标。其它平台沿用原行为：无损类统一标 `Hi-Res`。
 */
private fun SingerDetailQualityBadges(song: Song): List<String> =
    if (song.platform == MusicPlatform.apple) {
        buildList {
            if (Quality.hires in song.qualities) add("Hi-Res")
            if (Quality.atmos in song.qualities) add("Dolby")
        }
    } else {
        if (song.qualities.any {
                it == Quality.hires || it == Quality.atmos || it == Quality.alac
            }
        ) {
            listOf("Hi-Res")
        } else {
            emptyList()
        }
    }

/** 音质角标（单个文案；Apple 的歌曲行最多渲染两个：Hi-Res / Dolby） */
@Composable
private fun SingerDetailQualityBadge(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 25))
            .background(AppColors.primaryA15)
            .padding(horizontal = AppSpacing.sp4, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = AppColors.primary, fontSize = AppFontSize.size10)
    }
}

/** 头部全选控件三态：未选 / 部分选中 / 全选。 */
private enum class SingerDetailTriState { Unchecked, Indeterminate, Checked }

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
            val songsListState = rememberLazyListState()
            LazyColumn(
                state = songsListState,
                modifier = Modifier.fillMaxSize().shortScrollbar(songsListState),
                contentPadding = PaddingValues(bottom = AppSpacing.listBottomInset),
            ) {
                items(count = st.songs.size) { index ->
                    val song = st.songs[index]
                    val task = tasks[song.id]
                    val isDownloading = task?.status == DownloadStatus.downloading ||
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
                    modifier = Modifier.width(AppSpacing.sp28),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${index + 1}",
                        color = if (selected) AppColors.primary else AppColors.white60,
                        fontSize = AppFontSize.size15,
                        fontWeight = FontWeight.Bold,
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
                        fontSize = AppFontSize.lg,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.sp1))
                    Text(
                        text = if (song.album.isNullOrEmpty()) song.artist else "${song.artist} · ${song.album}",
                        color = AppColors.textTertiary,
                        fontSize = AppFontSize.size13,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                for (badge in SingerDetailQualityBadges(song)) {
                    Spacer(modifier = Modifier.width(AppSpacing.sp4))
                    SingerDetailQualityBadge(label = badge)
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

/** Apple 发行物子页筛选：从 overview 的「全部」进入时只看对应一段。 */
private enum class AppleReleaseSection { singles, albums }

@Composable
private fun SingerDetailAlbums(
    st: SingerDetailState,
    apple: Boolean,
    filter: AppleReleaseSection?,
    onSortToggle: () -> Unit,
    onOpenAlbum: (Album) -> Unit,
) {
    if (st.albumLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = AppColors.primary)
        }
        return
    }
    if (apple && filter == AppleReleaseSection.singles && st.appleSingles.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "暂无单曲 / EP", color = AppColors.white30, fontSize = AppFontSize.base)
        }
        return
    }
    if (apple && filter == AppleReleaseSection.albums && st.appleAlbums.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "暂无专辑", color = AppColors.white30, fontSize = AppFontSize.base)
        }
        return
    }
    if (st.albums.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "暂无专辑", color = AppColors.white30, fontSize = AppFontSize.base)
        }
        return
    }

    fun sortedAlbums(list: List<Album>): List<Album> = list.sortedWith(
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
    val sorted = sortedAlbums(st.albums)
    val sortedSingles = sortedAlbums(st.appleSingles)
    val sortedAppleAlbums = sortedAlbums(st.appleAlbums)

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
            val gridState = rememberLazyGridState()
            // 网格按 albumid 做 key 锚定滚动位置：切正/倒序后原顶部那张会跑到末尾，
            // 网格为了保持它可见会一路滚到底 —— 所以排序一变就回到顶部。
            LaunchedEffect(st.albumSortAsc) { gridState.scrollToItem(0) }
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = gridState,
                modifier = Modifier.fillMaxSize().shortScrollbar(gridState),
                contentPadding = PaddingValues(
                    start = AppSpacing.sp8,
                    top = AppSpacing.sp4,
                    end = AppSpacing.sp8,
                    bottom = AppSpacing.listBottomInset,
                ),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sp10),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sp10),
            ) {
                if (apple) {
                    if ((filter == null || filter == AppleReleaseSection.singles) &&
                        sortedSingles.isNotEmpty()
                    ) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SingerDetailReleaseHeader("EP / 单曲")
                        }
                        items(items = sortedSingles, key = { it.albumid }) { album ->
                            SingerDetailAlbumCard(
                                album = album,
                                coverUrl = buildAlbumCoverUrl(album),
                                modifier = Modifier.height(cardHeight),
                                onTap = onOpenAlbum,
                            )
                        }
                    }
                    if ((filter == null || filter == AppleReleaseSection.albums) &&
                        sortedAppleAlbums.isNotEmpty()
                    ) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SingerDetailReleaseHeader("专辑")
                        }
                        items(items = sortedAppleAlbums, key = { it.albumid }) { album ->
                            SingerDetailAlbumCard(
                                album = album,
                                coverUrl = buildAlbumCoverUrl(album),
                                modifier = Modifier.height(cardHeight),
                                onTap = onOpenAlbum,
                            )
                        }
                    }
                } else {
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
}

@Composable
private fun SingerDetailReleaseHeader(title: String) {
    Text(
        text = title,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = AppSpacing.sp8, top = AppSpacing.sp8, bottom = AppSpacing.sp2),
        color = AppColors.textPrimary,
        fontSize = AppFontSize.size15,
        fontWeight = FontWeight.SemiBold,
    )
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
                            color = AppColors.white70,
                            fontSize = AppFontSize.size10,
                            fontWeight = FontWeight.SemiBold,
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

