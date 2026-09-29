package com.annieplayer.annie_music.kt.features.search

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import com.annieplayer.annie_music.kt.core.audio.MediaPlayerBackend
import com.annieplayer.annie_music.kt.core.platform.MediaStoreScan
import com.annieplayer.annie_music.kt.core.platform.isManageStorageGranted
import com.annieplayer.annie_music.kt.core.platform.requestManageStorage
import com.annieplayer.annie_music.kt.core.state.AlbumDetailData
import com.annieplayer.annie_music.kt.core.state.ColorViewModel
import com.annieplayer.annie_music.kt.core.state.DownloadViewModel
import com.annieplayer.annie_music.kt.core.state.KkboxViewModel
import com.annieplayer.annie_music.kt.core.state.PlayerViewModel
import com.annieplayer.annie_music.kt.core.state.QobuzViewModel
import com.annieplayer.annie_music.kt.core.state.PlaylistDetailData
import com.annieplayer.annie_music.kt.core.state.SearchSelectionViewModel
import com.annieplayer.annie_music.kt.core.state.SingerDetailData
import com.annieplayer.annie_music.kt.core.state.UiViewModel
import com.annieplayer.annie_music.kt.core.util.enqueueDownload
import com.annieplayer.annie_music.kt.core.util.cancelPending
import com.annieplayer.annie_music.kt.core.util.extractPaletteFromCover
import com.annieplayer.annie_music.kt.data.AssetCache
import com.annieplayer.annie_music.kt.data.DownloadDirService
import com.annieplayer.annie_music.kt.data.DownloadService
import com.annieplayer.annie_music.kt.data.LyricResult
import com.annieplayer.annie_music.kt.data.enrichSong
import com.annieplayer.annie_music.kt.data.fetchDownloadUrlBySong
import com.annieplayer.annie_music.kt.data.fetchLyric
import com.annieplayer.annie_music.kt.data.model.Album
import com.annieplayer.annie_music.kt.widgets.SingerSearchResults
import com.annieplayer.annie_music.kt.widgets.AlbumSearchResults
import com.annieplayer.annie_music.kt.data.model.Artist
import com.annieplayer.annie_music.kt.data.model.LoopMode
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.PlaylistService
import com.annieplayer.annie_music.kt.data.resolveArtist
import com.annieplayer.annie_music.kt.data.searchAlbumByPlatform
import com.annieplayer.annie_music.kt.data.searchArtistByPlatform
import com.annieplayer.annie_music.kt.data.sources.buildAlbumCoverUrl
import com.annieplayer.annie_music.kt.data.sources.parseKkboxUrl
import com.annieplayer.annie_music.kt.data.sources.parseQobuzUrl
import com.annieplayer.annie_music.kt.data.sources.searchKugouMusic
import com.annieplayer.annie_music.kt.data.sources.searchMusic
import com.annieplayer.annie_music.kt.data.sources.searchNeteaseMusic
import com.annieplayer.annie_music.kt.data.sources.searchQQMusic
import com.annieplayer.annie_music.kt.ui.theme.Annie
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppLetterSpacing
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.AlbumDetailSheet
import com.annieplayer.annie_music.kt.widgets.DownloadQueueSheet
import com.annieplayer.annie_music.kt.widgets.GlobalOverlays
import com.annieplayer.annie_music.kt.widgets.MenuDrawer
import com.annieplayer.annie_music.kt.widgets.MenuDrawerButton
import com.annieplayer.annie_music.kt.widgets.MenuDrawerCacheSize
import com.annieplayer.annie_music.kt.widgets.MiniPlayerBar
import com.annieplayer.annie_music.kt.widgets.ModeDropdown
import com.annieplayer.annie_music.kt.widgets.ModeDropdownMode
import com.annieplayer.annie_music.kt.widgets.MusicNoteGlyph
import com.annieplayer.annie_music.kt.widgets.PlayerOverlay
import com.annieplayer.annie_music.kt.widgets.ContactModal
import com.annieplayer.annie_music.kt.widgets.KkboxAccountSheet
import com.annieplayer.annie_music.kt.widgets.QobuzTokenSheet
import com.annieplayer.annie_music.kt.widgets.PlaylistDetailSheet
import com.annieplayer.annie_music.kt.widgets.QualityPickerSheet
import com.annieplayer.annie_music.kt.widgets.QueueSheet
import com.annieplayer.annie_music.kt.widgets.SearchBarField
import com.annieplayer.annie_music.kt.widgets.SearchSelectionOverlay
import com.annieplayer.annie_music.kt.widgets.SingerDetailSheet
import com.annieplayer.annie_music.kt.widgets.SongActionsSheet
import com.annieplayer.annie_music.kt.widgets.SongArtistRef
import com.annieplayer.annie_music.kt.widgets.SongSearchColumn
import com.annieplayer.annie_music.kt.widgets.SongSearchResults
import com.annieplayer.annie_music.kt.widgets.SourcePickerSheet
import com.annieplayer.annie_music.kt.widgets.closeOtherDetailOverlays
import com.annieplayer.annie_music.kt.widgets.clickableNoRipple
import com.annieplayer.annie_music.kt.features.player.PlayerScreen
import com.annieplayer.annie_music.kt.features.kkbox.KkboxDetailScreen
import com.annieplayer.annie_music.kt.features.qobuz.QobuzDetailScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 顶栏头像占位尺寸（对齐 Dart `Image.asset(width: 40, height: 40)`） */
private val HomeHeaderAvatarSize = 40.dp

/** 从 CompositionLocal 的 Context 向上找到 [ViewModelStoreOwner]（Activity）。 */
private tailrec fun Context?.findViewModelStoreOwner(): ViewModelStoreOwner? = when (this) {
    null -> null
    is ViewModelStoreOwner -> this
    is ContextWrapper -> baseContext.findViewModelStoreOwner()
    else -> null
}

/**
 * 以 Activity 为作用域获取 ViewModel（等价 `androidx.lifecycle.viewmodel.compose.viewModel()`）。
 *
 * HomeScreen 是单 Activity 架构的宿主屏：用 Activity 作用域可让状态（播放队列 / UI / 选择等）
 * 在**屏幕旋转 / Activity 重建**后存活；而 `remember { XxxViewModel() }` 会随组合销毁而丢状态。
 * 这里不直接依赖 `lifecycle-viewmodel-compose`（本工程离线构建缓存中无该 artifact）。
 */
@Composable
private inline fun <reified VM : ViewModel> activityViewModel(): VM {
    val owner = LocalContext.current.findViewModelStoreOwner()
        ?: error("未找到 ViewModelStoreOwner，activityViewModel() 需在 Activity 宿主下使用")
    return remember(owner) { ViewModelProvider(owner).get(VM::class.java) }
}

/**
 * 首页（1:1 移植 Flutter `lib/features/search/home_screen.dart`）。
 *
 * 本 App 不是常规导航栈，而是「单屏 + 覆盖层栈」：本文件就是那个**宿主屏**，负责
 * 1) 搜索状态机（单曲双列 / 专辑 / 歌手 / 歌单解析 / 国外解析）；
 * 2) 把 `ui/theme` token、状态容器（`core/state`）与既有的自驱动浮层组件接线起来。
 *
 * 覆盖层渲染顺序严格对齐 `core/routing/OverlayStack.kt` 的 `Overlay.RENDER_ORDER`
 * （同层靠后者后绘制 → 在上层）：
 * Column → GlobalOverlays → MenuDrawer → MiniPlayer → PlayerOverlay →
 * SongActionsSheet（内部含 ArtistPickerSheet）→ QualityPicker(下载) →
 * QualityPicker(批量) → QueueSheet → SourcePickerSheet → 下载中 toast。
 * 返回手势优先级因此天然成立（后声明的 BackHandler 先收到返回事件）。
 *
 * 浮层可见性变化（抽屉 / 任意面板 / 任意 sheet）会收起搜索框焦点，避免文本选择
 * 工具条浮在浮层之上（对齐 Dart 的 `FocusManager.instance.primaryFocus?.unfocus()`）。
 *
 * 与 Dart 的已知差异（未移植模块，均为结构性缺位而非行为改写）：
 * - 「联系我们」弹窗（ContactModal）与 Qobuz Token / KKBOX 账号浮层尚未移植；
 *   抽屉对应入口仍会置位 `UiState` 的两个可见性布尔，等对应浮层落地后即可挂载。
 * - Qobuz / KKBOX / 歌单链接解析：Dart 成功后 `context.push` 详情路由；Kotlin 侧
 *   详情页正在并行移植，当前仅完成「解析 + 非法链接 SnackBar」。
 * - 顶栏右侧的 `assets/annie.jpg` 资源尚未进入 Android 工程，暂以音符占位块渲染。
 */
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    uiViewModel: UiViewModel = activityViewModel(),
    playerViewModel: PlayerViewModel = activityViewModel(),
    downloadViewModel: DownloadViewModel = activityViewModel(),
    selectionViewModel: SearchSelectionViewModel = activityViewModel(),
    colorViewModel: ColorViewModel = activityViewModel(),
    kkboxViewModel: KkboxViewModel = activityViewModel(),
    qobuzViewModel: QobuzViewModel = activityViewModel(),
) {
    // ── 状态（对齐 Dart Riverpod providers + `_HomeScreenState` 字段） ──
    val uiState by uiViewModel.state.collectAsState()
    val playerState by playerViewModel.state.collectAsState()
    val downloadTasks by downloadViewModel.state.collectAsState()
    val selection by selectionViewModel.state.collectAsState()
    val colorState by colorViewModel.state.collectAsState()
    val palette = Annie.palette

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current

    var mode by remember { mutableStateOf(ModeDropdownMode.song) }
    var modePickerVisible by remember { mutableStateOf(false) }
    var queryText by remember { mutableStateOf("") }
    var songSubmitted by remember { mutableStateOf("") }
    var albumSubmitted by remember { mutableStateOf("") }
    var artistSubmitted by remember { mutableStateOf("") }

    /** 单曲双列数据（左、右），随源分组切换整列替换 */
    var songColumns by remember { mutableStateOf<List<SongSearchColumn>>(emptyList()) }
    var songGeneration by remember { mutableIntStateOf(0) }
    var sourceGroupIndex by remember { mutableIntStateOf(0) }

    var albumPlatform by remember { mutableStateOf(MusicPlatform.kuwo) }
    var albums by remember { mutableStateOf<List<Album>>(emptyList()) }
    var albumsLoading by remember { mutableStateOf(false) }
    var albumGeneration by remember { mutableIntStateOf(0) }

    var artistPlatform by remember { mutableStateOf(MusicPlatform.kuwo) }
    var artists by remember { mutableStateOf<List<Artist>>(emptyList()) }
    var artistsLoading by remember { mutableStateOf(false) }
    var artistGeneration by remember { mutableIntStateOf(0) }

    var sourcePickerVisible by remember { mutableStateOf(false) }
    var batchPickerVisible by remember { mutableStateOf(false) }
    var batchDownloading by remember { mutableStateOf(false) }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var saveDirPath by remember { mutableStateOf<String?>(DownloadDirService.getDownloadDirPath()) }

    // Qobuz / KKBOX 详情页路由参数（对齐 Flutter `context.push('/xxx-detail?type=&id=')`）
    var qobuzDetailRoute by remember { mutableStateOf<Pair<String, String>?>(null) }
    var kkboxDetailRoute by remember { mutableStateOf<Pair<String, String>?>(null) }

    // 「所有文件访问」权限状态：抽屉打开时重新检测（对齐 Flutter `_ManageStorageRow._granted`）
    val ctx = LocalContext.current
    var storageGranted by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(uiState.drawerVisible) {
        if (uiState.drawerVisible) storageGranted = isManageStorageGranted(ctx)
    }

    // SAF 选择下载目录（对齐 Flutter `menu_drawer.dart` 的 `pickDownloadDir()` 入口；
    // 之前 `onPickSaveDir = {}` 为空实现，导致用户无法自定义下载目录）
    val dirPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (_: Exception) {
                // 忽略：有 MANAGE_EXTERNAL_STORAGE 时无需持久化授权也可按路径访问
            }
            val path = MediaStoreScan.resolveTreeToPath(uri)
            if (path != null) {
                DownloadDirService.setDownloadDirPath(path)
                saveDirPath = path
            }
        }
    }
    var cacheSize by remember { mutableStateOf<MenuDrawerCacheSize>(MenuDrawerCacheSize.Loading) }

    // 抽屉里的缓存大小（对齐 Dart `cacheSizeProvider` 首次计算）
    LaunchedEffect(Unit) {
        cacheSize = try {
            MenuDrawerCacheSize.Ready(AssetCache.getFileCacheSize())
        } catch (_: Exception) {
            MenuDrawerCacheSize.Error
        }
    }

    // 音频引擎接线（对齐 Flutter `main.dart`：`backend.setup()` 后把 backend 注入播放 provider）。
    // 不接线时 `PlayerViewModel._backend` 恒为 null，next/previous/play/seek/volume 全部空转、无音频。
    LaunchedEffect(Unit) {
        val backend = MediaPlayerBackend(ctx.applicationContext)
        backend.setup()
        playerViewModel.setBackend(backend)
        // KKBOX 歌词/播放票据需要活跃账号的会话 sid（对齐 Flutter 从 kkbox store 读取）
        playerViewModel.kkboxSidProvider = { kkboxViewModel.getActiveAccount()?.session?.sid }
    }

    // 封面本地缓存 + 动态取色（对齐 Flutter：详情页/播放页切歌时 fetchAndCacheCover，并把本地
    // 缓存路径写入 colorProvider.currentCoverUrl）。以 currentSong.id 为 key，覆盖首页点歌 /
    // 专辑 / KKBOX / Qobuz 详情播放、队列选歌、自动切歌等全部路径，且不会串到上一首。
    LaunchedEffect(playerState.currentSong?.id) {
        val song = playerState.currentSong ?: return@LaunchedEffect
        val cover = song.cover
        if (cover.isEmpty()) return@LaunchedEffect
        // 先用远端 URL 占位（当前歌），随后异步替换为本地缓存路径
        colorViewModel.setCurrentCoverUrl(cover)
        val cached = try {
            AssetCache.fetchAndCacheCover(cover)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ""
        }
        if (cached.isNotEmpty() && playerViewModel.state.value.currentSong?.id == song.id) {
            colorViewModel.setCurrentCoverUrl(cached)
        }
    }

    // 任意浮层 / 抽屉 / sheet 打开 → 收起搜索框的文本选择与键盘
    val anyOverlayVisible = uiState.drawerVisible ||
        uiState.songActionsVisible ||
        uiState.artistPickerVisible ||
        uiState.playerOverlayVisible ||
        uiState.queueVisible ||
        uiState.pickerVisible ||
        uiState.albumDetailVisible ||
        uiState.singerDetailVisible ||
        uiState.playlistDetailVisible ||
        uiState.downloadQueueVisible ||
        uiState.qobuzTokenVisible ||
        uiState.kkboxAccountVisible ||
        uiState.contactVisible ||
        sourcePickerVisible ||
        batchPickerVisible
    LaunchedEffect(anyOverlayVisible) {
        if (anyOverlayVisible) focusManager.clearFocus()
    }

    // ── 搜索：单源分发（后续补 qobuz / kkbox 单曲搜索只在这里加一行） ──
    suspend fun searchBySource(source: SearchSource, query: String, page: Int): List<Song> =
        when (source) {
            SearchSource.kuwo -> searchMusic(query, page = page)
            SearchSource.netease -> searchNeteaseMusic(query, page = page, pageSize = kSearchPageSize)
            SearchSource.qq -> searchQQMusic(query, page = page, pageSize = kSearchPageSize)
            SearchSource.kugou -> searchKugouMusic(query, page = page, pageSize = kSearchPageSize)
        }

    /** 按当前源分组发起双列搜索（两列各自独立返回） */
    fun startSongSearch(query: String) {
        val group = sourceGroups[sourceGroupIndex]
        songGeneration += 1
        val generation = songGeneration
        songColumns = listOf(
            SongSearchColumn(source = group.left, songs = emptyList(), isLoading = true),
            SongSearchColumn(source = group.right, songs = emptyList(), isLoading = true),
        )
        scope.launch {
            val result = try {
                searchBySource(group.left, query, 1)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                emptyList()
            }
            if (generation != songGeneration) return@launch
            songColumns = songColumns.map {
                if (it.source == group.left) SongSearchColumn(group.left, result, false) else it
            }
        }
        scope.launch {
            val result = try {
                searchBySource(group.right, query, 1)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                emptyList()
            }
            if (generation != songGeneration) return@launch
            songColumns = songColumns.map {
                if (it.source == group.right) SongSearchColumn(group.right, result, false) else it
            }
        }
    }

    /** 专辑搜索（单源；空关键词不发请求，视图侧等同「无结果」） */
    fun startAlbumSearch(query: String) {
        albumGeneration += 1
        val generation = albumGeneration
        val platform = albumPlatform
        if (query.isEmpty()) {
            albums = emptyList()
            albumsLoading = false
            return
        }
        albumsLoading = true
        scope.launch {
            val result = try {
                searchAlbumByPlatform(platform, query, page = 1, pageSize = 30)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                emptyList()
            }
            if (generation != albumGeneration) return@launch
            albums = result
            albumsLoading = false
        }
    }

    /** 歌手搜索（单源） */
    fun startArtistSearch(query: String) {
        artistGeneration += 1
        val generation = artistGeneration
        val platform = artistPlatform
        if (query.isEmpty()) {
            artists = emptyList()
            artistsLoading = false
            return
        }
        artistsLoading = true
        scope.launch {
            val result = try {
                searchArtistByPlatform(platform, query)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                emptyList()
            }
            if (generation != artistGeneration) return@launch
            artists = result
            artistsLoading = false
        }
    }

    fun handleSearch() {
        val query = queryText.trim()
        when (mode) {
            ModeDropdownMode.song -> {
                songSubmitted = query
                startSongSearch(query)
            }
            ModeDropdownMode.album -> {
                albumSubmitted = query
                startAlbumSearch(query)
            }
            ModeDropdownMode.singer -> {
                artistSubmitted = query
                startArtistSearch(query)
            }
            ModeDropdownMode.playlist, ModeDropdownMode.qobuz -> Unit
        }
    }

    fun handleModeSelect(target: ModeDropdownMode) {
        mode = target
        modePickerVisible = false
    }

    /** 单平台模式（专辑 / 歌手都是单源） */
    val singleSourceMode = mode == ModeDropdownMode.album || mode == ModeDropdownMode.singer

    /** 「搜索选择」面板选项（单曲=源分组；专辑/歌手=单平台） */
    val pickerOptions = if (singleSourceMode) {
        albumSearchPlatforms.map { it.label }
    } else {
        sourceGroups.map { it.label }
    }

    /** 当前生效的面板下标 */
    val pickerIndex = when (mode) {
        ModeDropdownMode.singer -> albumSearchPlatforms.indexOf(artistPlatform)
        ModeDropdownMode.album -> albumSearchPlatforms.indexOf(albumPlatform)
        else -> sourceGroupIndex
    }

    /** 面板选中某项：单曲=切源分组，专辑/歌手=切平台；均保留关键词自动重搜 */
    fun handleSourceSelect(index: Int) {
        sourcePickerVisible = false
        if (singleSourceMode) {
            if (index < 0 || index >= albumSearchPlatforms.size) return
            if (mode == ModeDropdownMode.singer) {
                artistPlatform = albumSearchPlatforms[index]
                startArtistSearch(artistSubmitted)
            } else {
                albumPlatform = albumSearchPlatforms[index]
                startAlbumSearch(albumSubmitted)
            }
        } else {
            if (index < 0 || index >= sourceGroups.size) return
            sourceGroupIndex = index
            startSongSearch(songSubmitted)
        }
    }

    fun handlePlaySong(song: Song, songs: List<Song>) {
        val index = songs.indexOfFirst { it.id == song.id }
        playerViewModel.setQueue(songs, startIndex = if (index < 0) 0 else index)
        colorViewModel.setCurrentCoverUrl(song.cover)
    }

    fun handleDownload(song: Song) {
        uiViewModel.setPicker(true, song = song)
    }

    fun handleToggleSelect(song: Song) {
        selectionViewModel.toggleSong(song)
    }

    fun handleDownloadWithQuality(quality: Quality) {
        val song = uiState.pickerSong ?: return
        uiViewModel.setPicker(false)
        downloadViewModel.addTask(song, quality)
        val songId = song.id
        downloadingId = songId
        enqueueDownload(songId) {
            try {
                val url = fetchDownloadUrlBySong(song, quality)
                val lyricResult = if (song.lyric != null && song.tlyric != null) {
                    LyricResult(lrc = song.lyric, tlyric = song.tlyric)
                } else {
                    fetchLyric(song)
                }
                val extra = enrichSong(song)
                DownloadService.downloadSong(
                    songId = songId,
                    title = song.title,
                    artist = song.artist,
                    coverUrl = song.cover,
                    album = song.album,
                    downloadUrl = url,
                    quality = quality,
                    onProgress = { p -> downloadViewModel.updateProgress(songId, p * 100) },
                    lyric = lyricResult.lrc,
                    tlyric = lyricResult.tlyric,
                    albumArtist = song.albumArtist ?: extra["albumArtist"]?.toString(),
                    trackNumber = song.trackNumber ?: extra["trackNumber"]?.toString(),
                    discNumber = song.discNumber,
                    releaseDate = song.releaseDate ?: extra["releaseDate"]?.toString(),
                )
                downloadViewModel.completeTask(songId)
                playerViewModel.addDownloadedId(songId)
                playerViewModel.addToQueue(song)
            } catch (_: Exception) {
                downloadViewModel.failTask(songId)
            }
            downloadingId = null
        }
    }

    fun handleBatchDownload(quality: Quality) {
        batchPickerVisible = false
        val songs = selection.selectedSongs
        if (songs.isEmpty()) return
        batchDownloading = true
        for (song in songs) {
            downloadViewModel.addTask(song, quality)
        }
        val batchId = "search_batch_" + System.currentTimeMillis()
        enqueueDownload(batchId) {
            for (song in songs) {
                downloadViewModel.updateProgress(song.id, 0.0)
                try {
                    val url = fetchDownloadUrlBySong(song, quality)
                    val lyricResult = fetchLyric(song)
                    val extra = enrichSong(song)
                    DownloadService.downloadSong(
                        songId = song.id,
                        title = song.title,
                        artist = song.artist,
                        coverUrl = song.cover,
                        album = song.album,
                        downloadUrl = url,
                        quality = quality,
                        onProgress = { p -> downloadViewModel.updateProgress(song.id, p * 100) },
                        lyric = lyricResult.lrc,
                        tlyric = lyricResult.tlyric,
                        albumArtist = song.albumArtist ?: extra["albumArtist"]?.toString(),
                        trackNumber = song.trackNumber ?: extra["trackNumber"]?.toString(),
                        discNumber = song.discNumber,
                        releaseDate = song.releaseDate ?: extra["releaseDate"]?.toString(),
                    )
                    downloadViewModel.completeTask(song.id)
                    playerViewModel.addDownloadedId(song.id)
                    playerViewModel.addToQueue(song)
                } catch (_: Exception) {
                    downloadViewModel.failTask(song.id)
                }
            }
            selectionViewModel.clearAll()
            batchDownloading = false
        }
    }

    fun handleQobuzSubmit(text: String) {
        val parsed = parseQobuzUrl(text)
        if (parsed == null) {
            scope.launch { snackbarHostState.showSnackbar("未识别到有效 Qobuz 链接") }
            return
        }
        // 对齐 Dart `context.push('/qobuz-detail?type=&id=')`：进入全屏详情页
        qobuzDetailRoute = parsed.type to parsed.id
    }

    fun handleKkboxSubmit(text: String) {
        val parsed = parseKkboxUrl(text)
        if (parsed == null) {
            scope.launch { snackbarHostState.showSnackbar("未识别到有效 KKBOX 链接") }
            return
        }
        // 对齐 Dart `context.push('/kkbox-detail?type=&id=')`：进入全屏详情页
        kkboxDetailRoute = parsed.type to parsed.id
    }

    fun handlePlaylistSubmit(text: String) {
        val parsed = PlaylistService.parsePlaylistUrl(text)
        if (parsed == null) {
            scope.launch { snackbarHostState.showSnackbar("未识别到有效的歌单链接或ID") }
            return
        }
        uiViewModel.setPlaylistDetailVisible(
            true,
            PlaylistDetailData(id = parsed.id, platform = parsed.platform.name),
        )
    }

    /** ⋮ → 歌手：有 id 直接开；没有则按名字搜索一次（对齐 Dart `openArtistDetail`） */
    fun openArtistDetail(artist: SongArtistRef) {
        val song = playerState.currentSong ?: return
        scope.launch {
            val resolved = resolveArtist(
                song.platform,
                artist.name,
                knownId = if (artist.id.isEmpty()) null else artist.id,
            )
            if (resolved == null || resolved.id.isEmpty()) {
                snackbarHostState.showSnackbar("未找到歌手「${artist.name}」")
                return@launch
            }
            // 详情浮层在播放页之下：先收起播放页，再关其它详情，最后开歌手详情
            uiViewModel.setPlayerOverlayVisible(false)
            closeOtherDetailOverlays(
                setAlbumDetailVisible = { uiViewModel.setAlbumDetailVisible(it) },
                setSingerDetailVisible = { uiViewModel.setSingerDetailVisible(it) },
                setPlaylistDetailVisible = { uiViewModel.setPlaylistDetailVisible(it) },
            )
            uiViewModel.setSingerDetailVisible(
                true,
                SingerDetailData(
                    id = resolved.id,
                    name = resolved.name,
                    picture = resolved.picture,
                    platform = song.platform,
                ),
            )
        }
    }

    /** ⋮ → 专辑：先收起播放页、关其它详情，再置专辑详情数据 */
    fun openAlbumDetail(song: Song) {
        uiViewModel.setPlayerOverlayVisible(false)
        closeOtherDetailOverlays(
            setAlbumDetailVisible = { uiViewModel.setAlbumDetailVisible(it) },
            setSingerDetailVisible = { uiViewModel.setSingerDetailVisible(it) },
            setPlaylistDetailVisible = { uiViewModel.setPlaylistDetailVisible(it) },
        )
        uiViewModel.setAlbumDetailVisible(
            true,
            AlbumDetailData(
                id = song.albumId ?: "",
                cover = song.cover,
                name = song.album ?: "",
                artist = song.albumArtist ?: song.artist,
                platform = song.platform,
            ),
        )
    }

    val currentSong = playerState.currentSong
    val toastId = downloadingId

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = AppColors.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) {
        // 键盘弹出时不压缩 body（Scaffold/Compose 默认不消费 IME inset），
        // 与 Dart `resizeToAvoidBottomInset: false` 一致：迷你条固定屏幕底部。
        Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            // ── 基础内容列（Overlay.COLUMN） ──
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶栏
                Row(
                    modifier = Modifier.padding(
                        start = AppSpacing.sp16,
                        top = AppSpacing.sp40,
                        end = AppSpacing.sp16,
                        bottom = AppSpacing.sp20,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MenuDrawerButton(
                        onOpen = { uiViewModel.setDrawerVisible(true) },
                        modifier = Modifier.size(AppSize.headerActionHeight),
                    )
                    Text(
                        text = "安妮音乐盒",
                        modifier = Modifier.weight(1f),
                        color = AppColors.white,
                        fontSize = AppFontSize.size26,
                        fontWeight = AppFontWeight.bold,
                        letterSpacing = AppLetterSpacing.tight,
                    )
                    // Dart 为 Image.asset('assets/annie.jpg')；该资源尚未入库，暂用音符占位块
                    Box(
                        modifier = Modifier
                            .size(HomeHeaderAvatarSize)
                            .clip(RoundedCornerShape(AppRadius.r10))
                            .background(AppColors.whiteA03),
                        contentAlignment = Alignment.Center,
                    ) {
                        MusicNoteGlyph(
                            modifier = Modifier.size(AppIconSize.icon22),
                            tint = AppColors.white10,
                        )
                    }
                }
                // 模式切换
                Box(
                    modifier = Modifier.padding(
                        start = AppSpacing.sp16,
                        end = AppSpacing.sp16,
                        bottom = AppSpacing.sp8,
                    ),
                ) {
                    ModeDropdown(
                        mode = mode,
                        open = modePickerVisible,
                        onToggle = { modePickerVisible = !modePickerVisible },
                        onSelect = { handleModeSelect(it) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // 待下载条
                if (mode == ModeDropdownMode.song && selection.selectedSongs.isNotEmpty()) {
                    HomePendingDownloadBar(
                        count = selection.selectedSongs.size,
                        batchDownloading = batchDownloading,
                        onView = { selectionViewModel.setOverlayVisible(true) },
                        onClear = { selectionViewModel.clearAll() },
                        onBatch = { batchPickerVisible = true },
                    )
                }
                // 搜索栏（歌单解析 / 国外解析模式隐藏）
                if (mode != ModeDropdownMode.qobuz && mode != ModeDropdownMode.playlist) {
                    SearchBarField(
                        value = queryText,
                        onValueChange = { queryText = it },
                        onSubmit = { handleSearch() },
                    )
                }
                // 内容区
                Box(modifier = Modifier.weight(1f)) {
                    when (mode) {
                        ModeDropdownMode.song -> SongSearchResults(
                            columns = songColumns,
                            query = songSubmitted,
                            onPlay = { song, songs -> handlePlaySong(song, songs) },
                            onDownload = { handleDownload(it) },
                            onToggleSelect = { handleToggleSelect(it) },
                            onLoadMore = { source, page -> searchBySource(source, songSubmitted, page) },
                            activeSongId = currentSong?.id,
                            selectedSongs = selection.selectedSongs,
                            downloadStatusOf = { song -> downloadTasks[song.id]?.status },
                        )
                        ModeDropdownMode.album -> AlbumSearchResults(
                            albums = albums,
                            query = albumSubmitted,
                            source = albumPlatform,
                            onAlbumPress = { album ->
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
                            },
                            isLoading = albumsLoading,
                        )
                        ModeDropdownMode.singer -> SingerSearchResults(
                            artists = artists,
                            query = artistSubmitted,
                            source = artistPlatform,
                            onArtistPress = { artist ->
                                uiViewModel.setSingerDetailVisible(
                                    true,
                                    SingerDetailData(
                                        id = artist.id,
                                        name = artist.name,
                                        picture = artist.picture,
                                        platform = artistPlatform,
                                    ),
                                )
                            },
                            isLoading = artistsLoading,
                        )
                        ModeDropdownMode.qobuz -> HomeForeignLinkView(
                            onQobuzSubmit = { handleQobuzSubmit(it) },
                            onKkboxSubmit = { handleKkboxSubmit(it) },
                        )
                        ModeDropdownMode.playlist -> HomePlaylistLinkView(
                            onSubmit = { handlePlaylistSubmit(it) },
                        )
                    }
                }
            }

            // ⇄ 呼出「搜索选择」面板（歌单/国外解析模式隐藏；抽屉/播放页/详情页打开时让位）
            if (mode != ModeDropdownMode.playlist &&
                mode != ModeDropdownMode.qobuz &&
                !uiState.drawerVisible &&
                !uiState.playerOverlayVisible &&
                qobuzDetailRoute == null &&
                kkboxDetailRoute == null
            ) {
                HomeSourceSwitchButton(
                    onTap = { sourcePickerVisible = true },
                )
            }

            // ── 详情浮层（RN zIndex 150） ──
            GlobalOverlays(
                downloadQueueSheet = {
                    DownloadQueueSheet(
                        visible = uiState.downloadQueueVisible,
                        tasks = downloadTasks,
                        onClose = { uiViewModel.setDownloadQueueVisible(false) },
                        onPause = { id ->
                            downloadViewModel.pauseTask(id)
                            DownloadService.pauseDownload(id)
                        },
                        onCancel = { id ->
                            // 对齐 Flutter `_remove`：cancelDownload + cancelPending + removeTask
                            DownloadService.cancelDownload(id)
                            cancelPending(id)
                            downloadViewModel.removeTask(id)
                        },
                        onRemove = { id ->
                            DownloadService.cancelDownload(id)
                            cancelPending(id)
                            downloadViewModel.removeTask(id)
                        },
                    )
                },
                searchSelectionOverlay = {
                    SearchSelectionOverlay(
                        visible = selection.overlayVisible,
                        songs = selection.selectedSongs,
                        onClose = { selectionViewModel.setOverlayVisible(false) },
                        onRemove = { id -> selectionViewModel.removeSong(id) },
                    )
                },
                playlistDetailSheet = {
                    PlaylistDetailSheet(
                        uiViewModel = uiViewModel,
                        playerViewModel = playerViewModel,
                        downloadViewModel = downloadViewModel,
                    )
                },
                singerDetailSheet = {
                    SingerDetailSheet(
                        uiViewModel = uiViewModel,
                        playerViewModel = playerViewModel,
                        downloadViewModel = downloadViewModel,
                    )
                },
                albumDetailSheet = {
                    AlbumDetailSheet(
                        uiViewModel = uiViewModel,
                        playerViewModel = playerViewModel,
                        downloadViewModel = downloadViewModel,
                    )
                },
            )

            // ── 菜单抽屉（RN zIndex 200） ──
            MenuDrawer(
                visible = uiState.drawerVisible,
                onClose = { uiViewModel.setDrawerVisible(false) },
                onQobuzToken = {
                    uiViewModel.setDrawerVisible(false)
                    uiViewModel.setQobuzTokenVisible(true)
                },
                onKkboxAccount = {
                    uiViewModel.setDrawerVisible(false)
                    uiViewModel.setKkboxAccountVisible(true)
                },
                onDownloadQueue = {
                    uiViewModel.setDrawerVisible(false)
                    uiViewModel.setDownloadQueueVisible(true)
                },
                cacheSize = cacheSize,
                onClearCache = {
                    uiViewModel.setDrawerVisible(false)
                    scope.launch {
                        AssetCache.clearAllCache()
                        AssetCache.clearAssetCache()
                        cacheSize = try {
                            MenuDrawerCacheSize.Ready(AssetCache.getFileCacheSize())
                        } catch (_: Exception) {
                            MenuDrawerCacheSize.Error
                        }
                        snackbarHostState.showSnackbar("已清除缓存")
                    }
                },
                // 「所有文件访问」权限流程尚未移植：保持未授权文案，点击暂为空操作
                storageGranted = storageGranted,
                onRequestStorage = { requestManageStorage(ctx) },
                saveDirPath = saveDirPath,
                saveDirLoading = false,
                onPickSaveDir = { dirPicker.launch(null) },
                onClearSaveDir = {
                    DownloadDirService.clearDownloadDir()
                    saveDirPath = null
                },
                onContact = { uiViewModel.setContactVisible(true) },
            )

            // ── 迷你播放条（置于详情浮层之上） ──
            if (currentSong != null) {
                MiniPlayerBar(
                    title = currentSong.title,
                    artist = currentSong.artist,
                    coverUrl = currentSong.cover,
                    fallbackCoverUrl = colorState.currentCoverUrl,
                    isPlaying = playerState.isPlaying,
                    currentTime = playerState.currentTime,
                    duration = playerState.duration,
                    visible = !uiState.playerOverlayVisible,
                    onOpenPlayer = { uiViewModel.setPlayerOverlayVisible(true) },
                    onTogglePlay = { playerViewModel.togglePlay() },
                    // 关键：必须贴底居中；否则在根 Box 里会默认落到左上角（压住顶栏）
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }

            // ── 全屏播放页浮层（RN zIndex 300） ──
            PlayerOverlay(
                visible = uiState.playerOverlayVisible,
                onClose = { uiViewModel.setPlayerOverlayVisible(false) },
            ) {
                PlayerScreen(
                    player = playerState,
                    ui = uiState,
                    palette = palette,
                    fallbackCoverUrl = colorState.currentCoverUrl,
                    onClose = { uiViewModel.setPlayerOverlayVisible(false) },
                    onSeek = { seconds -> playerViewModel.seekToSeconds(seconds) },
                    onDragging = { ratio -> uiViewModel.setDragging(true, seekRatio = ratio) },
                    onSeekEnd = { ratio ->
                        playerViewModel.seekToSeconds(ratio * playerState.duration)
                        uiViewModel.setDragging(false)
                    },
                    onTogglePlay = { playerViewModel.togglePlay() },
                    onPrevious = { playerViewModel.previous() },
                    onNext = { playerViewModel.next() },
                    onCycleLoopMode = {
                        val next = when (playerState.loopMode) {
                            LoopMode.sequential -> LoopMode.single
                            LoopMode.single -> LoopMode.shuffle
                            LoopMode.shuffle -> LoopMode.sequential
                        }
                        playerViewModel.setLoopMode(next)
                    },
                    onOpenQueue = { uiViewModel.setQueueVisible(true) },
                    onOpenSongActions = { uiViewModel.setSongActionsVisible(true) },
                    onSetVolume = { volume -> playerViewModel.setVolume(volume) },
                    onToggleTranslation = {
                        uiViewModel.setShowLyricsTranslation(!uiState.showLyricsTranslation)
                    },
                    onCoverChanged = { url ->
                        colorViewModel.setCurrentCoverUrl(url)
                        scope.launch {
                            val extracted = try {
                                extractPaletteFromCover(url)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                null
                            }
                            if (extracted != null) {
                                val current = colorViewModel.state.value.colors
                                colorViewModel.setColors(
                                    current.copyWith(
                                        primary = extracted.primary,
                                        secondary = extracted.secondary,
                                    ),
                                )
                            }
                        }
                    },
                )
            }

            // ── 歌曲操作面板（⋮，内部含多歌手选择） ──
            SongActionsSheet(
                visible = uiState.songActionsVisible,
                song = currentSong,
                onClose = { uiViewModel.setSongActionsVisible(false) },
                onDownload = { song -> uiViewModel.setPicker(true, song = song) },
                onOpenArtist = { artist -> openArtistDetail(artist) },
                onOpenAlbum = { song -> openAlbumDetail(song) },
            )

            // ── 下载音质选择（单曲） ──
            QualityPickerSheet(
                visible = uiState.pickerVisible,
                qualities = uiState.pickerSong?.qualities ?: listOf(Quality.k128k),
                onSelect = { quality -> handleDownloadWithQuality(quality) },
                onCancel = { uiViewModel.setPicker(false) },
            )

            // ── 下载音质选择（批量） ──
            QualityPickerSheet(
                visible = batchPickerVisible,
                qualities = listOf(Quality.flac, Quality.k320k, Quality.k128k),
                onSelect = { quality -> handleBatchDownload(quality) },
                onCancel = { batchPickerVisible = false },
            )

            // ── 播放队列浮层 ──
            QueueSheet(
                visible = uiState.queueVisible,
                queue = playerState.queue,
                currentIndex = playerState.currentIndex,
                onClose = { uiViewModel.setQueueVisible(false) },
                onPlayIndex = { index ->
                    playerViewModel.playIndex(index)
                    uiViewModel.setQueueVisible(false)
                },
                onRemove = { index -> playerViewModel.removeFromQueue(index) },
            )

            // ── 搜索源选择面板（⇄ 呼出） ──
            SourcePickerSheet(
                visible = sourcePickerVisible,
                options = pickerOptions,
                currentIndex = pickerIndex,
                onSelect = { index -> handleSourceSelect(index) },
                onClose = { sourcePickerVisible = false },
            )

            // ── Qobuz Token / KKBOX 账号管理浮层（抽屉入口；之前仅置位布尔、未挂载） ──
            QobuzTokenSheet(
                visible = uiState.qobuzTokenVisible,
                qobuzViewModel = qobuzViewModel,
                onClose = { uiViewModel.setQobuzTokenVisible(false) },
            )
            KkboxAccountSheet(
                visible = uiState.kkboxAccountVisible,
                kkboxViewModel = kkboxViewModel,
                onClose = { uiViewModel.setKkboxAccountVisible(false) },
            )
            ContactModal(
                visible = uiState.contactVisible,
                onClose = { uiViewModel.setContactVisible(false) },
            )

            // ── 「下载中」轻提示（根级最上层） ──
            if (toastId != null) {
                HomeDownloadingToast(title = downloadTasks[toastId]?.title ?: "歌曲")
            }

            // ── Qobuz / KKBOX 全屏详情页（首页粘贴链接后进入；置于最上层覆盖全部浮层） ──
            // 外层 Box 消费触摸：详情页本身只做视觉覆盖，空区域点击会穿透到下层首页。
            qobuzDetailRoute?.let { (type, id) ->
                Box(modifier = Modifier.fillMaxSize().clickableNoRipple { }) {
                    QobuzDetailScreen(
                        qobuzViewModel = qobuzViewModel,
                        playerViewModel = playerViewModel,
                        downloadViewModel = downloadViewModel,
                        onBack = { qobuzDetailRoute = null },
                        type = type,
                        id = id,
                    )
                }
            }
            kkboxDetailRoute?.let { (type, id) ->
                Box(modifier = Modifier.fillMaxSize().clickableNoRipple { }) {
                    KkboxDetailScreen(
                        kkboxViewModel = kkboxViewModel,
                        playerViewModel = playerViewModel,
                        downloadViewModel = downloadViewModel,
                        onBack = { kkboxDetailRoute = null },
                        type = type,
                        id = id,
                    )
                }
            }
        }
    }
}
