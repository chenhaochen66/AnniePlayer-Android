package com.annieplayer.annie_music.kt.features.shell

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import android.widget.Toast
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import com.annieplayer.annie_music.kt.core.audio.MediaPlayerBackend
import com.annieplayer.annie_music.kt.core.platform.MediaStoreScan
import com.annieplayer.annie_music.kt.core.platform.isManageStorageGranted
import com.annieplayer.annie_music.kt.core.platform.requestManageStorage
import com.annieplayer.annie_music.kt.core.state.AppShellViewModel
import com.annieplayer.annie_music.kt.core.state.AppTab
import com.annieplayer.annie_music.kt.features.library.LocalLibraryScreen
import com.annieplayer.annie_music.kt.core.state.ColorViewModel
import com.annieplayer.annie_music.kt.core.state.DownloadViewModel
import com.annieplayer.annie_music.kt.core.state.KkboxViewModel
import com.annieplayer.annie_music.kt.core.state.PlayerViewModel
import com.annieplayer.annie_music.kt.core.state.QobuzViewModel
import com.annieplayer.annie_music.kt.core.state.SearchSelectionViewModel
import com.annieplayer.annie_music.kt.core.state.UiViewModel
import com.annieplayer.annie_music.kt.core.util.cancelPending
import com.annieplayer.annie_music.kt.core.util.extractPaletteFromCover
import com.annieplayer.annie_music.kt.data.AssetCache
import com.annieplayer.annie_music.kt.data.CacheCategory
import com.annieplayer.annie_music.kt.data.CacheItem
import com.annieplayer.annie_music.kt.data.DownloadDirService
import com.annieplayer.annie_music.kt.data.DownloadService
import com.annieplayer.annie_music.kt.data.model.LoopMode
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.features.kkbox.KkboxDetailScreen
import com.annieplayer.annie_music.kt.features.library.LocalLibraryScreen
import com.annieplayer.annie_music.kt.features.player.PlayerScreen
import com.annieplayer.annie_music.kt.features.qobuz.QobuzDetailScreen
import com.annieplayer.annie_music.kt.features.search.HomeDownloadingToast
import com.annieplayer.annie_music.kt.features.search.SearchScreen
import com.annieplayer.annie_music.kt.features.search.SearchScreenState
import com.annieplayer.annie_music.kt.ui.theme.Annie
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.widgets.AlbumDetailSheet
import com.annieplayer.annie_music.kt.widgets.CacheClearModal
import com.annieplayer.annie_music.kt.widgets.ContactModal
import com.annieplayer.annie_music.kt.widgets.DownloadQueueSheet
import com.annieplayer.annie_music.kt.widgets.GlobalOverlays
import com.annieplayer.annie_music.kt.widgets.KkboxAccountSheet
import com.annieplayer.annie_music.kt.widgets.MenuDrawer
import com.annieplayer.annie_music.kt.widgets.MenuDrawerCacheSize
import com.annieplayer.annie_music.kt.widgets.PlayerOverlay
import com.annieplayer.annie_music.kt.widgets.PlaylistDetailSheet
import com.annieplayer.annie_music.kt.widgets.QobuzTokenSheet
import com.annieplayer.annie_music.kt.widgets.QualityPickerSheet
import com.annieplayer.annie_music.kt.widgets.QueueSheet
import com.annieplayer.annie_music.kt.widgets.SearchSelectionOverlay
import com.annieplayer.annie_music.kt.widgets.SingerDetailSheet
import com.annieplayer.annie_music.kt.widgets.SongActionsSheet
import com.annieplayer.annie_music.kt.widgets.SourcePickerSheet
import com.annieplayer.annie_music.kt.widgets.clickableNoRipple
import com.annieplayer.annie_music.kt.core.compose.activityViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 应用外壳（首页宿主屏 + 覆盖层栈，1:1 移植 Flutter `lib/features/search/home_screen.dart`
 * 的接线部分）。
 *
 * 本 App 不是常规导航栈，而是「单屏 + 覆盖层栈」：本文件就是那个**宿主屏**，负责
 * 1) App 级接线（状态收集、搜索状态容器 `SearchScreenState`、音频引擎接线、存储权限 /
 *    缓存清理 / SAF 目录流程）；
 * 2) 把 `ui/theme` token、状态容器（`core/state`）与既有的自驱动浮层组件接线起来；
 * 3) 在内容列的位置渲染 `features/search/SearchScreen.kt`（搜索内容与 ⇄ 按钮；
 *    搜索状态机见 `SearchScreenState`）。
 *
 * 覆盖层渲染顺序严格对齐 `core/routing/OverlayStack.kt` 的 `Overlay.RENDER_ORDER`
 * （同层靠后者后绘制 → 在上层）：
 * Column → GlobalOverlays → MenuDrawer → **底栏槽位** → PlayerOverlay →
 * SongActionsSheet（内部含 ArtistPickerSheet）→ QualityPicker(下载) →
 * QualityPicker(批量) → QueueSheet → SourcePickerSheet → 下载中 toast。
 * 「底栏槽位」即旧 MiniPlayerBar 的原位，常驻**同一颗胶囊** `AppBottomBar`，只切换内容：
 * - 主页面（无任何浮层 / 详情路由）→ **底栏态**：左「搜索」、中封面盒、右「本地曲库」；
 * - 其它二级页面 → **迷你播放器态**：封面左滑到左侧、左右 tab 淡出、标题/歌手/播放/进度淡入
 *   （反向即原路恢复；由 `isMiniPlayer = !isPrimaryPage` 驱动）。
 * 槽位声明在 MenuDrawer 之后、PlayerOverlay 之前，因此后声明的浮层天然盖住它；
 * （此前挂在 `Scaffold.bottomBar` 槽位会被 Material3 Scaffold 画在内容之上，导致
 * 打开全屏播放页时底栏仍然可见，已修正）。
 * 返回手势优先级因此天然成立（后声明的 BackHandler 先收到返回事件）。
 *
 * 浮层可见性变化（抽屉 / 任意面板 / 任意 sheet）会收起搜索框焦点，避免文本选择
 * 工具条浮在浮层之上（对齐 Dart 的 `FocusManager.instance.primaryFocus?.unfocus()`）。
 *
 * 与 Dart 的对应关系（原 KDoc 中「尚未移植」的模块现均已是已实现状态）：
 * - 「联系我们」弹窗（ContactModal）、Qobuz Token 浮层（QobuzTokenSheet）与 KKBOX
 *   账号浮层（KkboxAccountSheet）均已挂载：抽屉入口置位 `UiState` 可见性布尔，本文件
 *   按布尔渲染对应浮层。
 * - Qobuz / KKBOX 链接解析成功后进入全屏详情页（`QobuzDetailScreen` / `KkboxDetailScreen`，
 *   分别由 `qobuzDetailRoute` / `kkboxDetailRoute` 驱动）；歌单链接解析进入
 *   `PlaylistDetailSheet`；非法链接弹 SnackBar。
 * - 顶栏右侧头像已使用工程内资源 `res/drawable-nodpi/annie.jpg`（`R.drawable.annie`），
 *   不再是音符占位块。
 */
@Composable
fun AppShell(
    modifier: Modifier = Modifier,
    uiViewModel: UiViewModel = activityViewModel(),
    playerViewModel: PlayerViewModel = activityViewModel(),
    downloadViewModel: DownloadViewModel = activityViewModel(),
    selectionViewModel: SearchSelectionViewModel = activityViewModel(),
    colorViewModel: ColorViewModel = activityViewModel(),
    kkboxViewModel: KkboxViewModel = activityViewModel(),
    qobuzViewModel: QobuzViewModel = activityViewModel(),
    appShellViewModel: AppShellViewModel = activityViewModel(),
) {
    // ── 状态（对齐 Dart Riverpod providers + `_HomeScreenState` 字段） ──
    val uiState by uiViewModel.state.collectAsState()
    val playerState by playerViewModel.state.collectAsState()
    val downloadTasks by downloadViewModel.state.collectAsState()
    val selection by selectionViewModel.state.collectAsState()
    val colorState by colorViewModel.state.collectAsState()
    // 动态色板取「取色结果本身」（ColorViewModel 的 state），**不是** `Annie.palette`：
    // MainActivity 目前是 `AnnieTheme { }`（用默认 palette = fallback），CompositionLocal 里那个
    // `Annie.palette` 永远是 fallback 靛蓝；取色结果一路写进了 colorState，但此前没人消费它，
    // 导致播放页歌词指示条 / 播放页取色永远不变。这里改用 colorState.colors 打通链路。
    val palette = colorState.colors
    // 当前 tab（搜索 / 本地曲库）：Activity 作用域 VM，切 tab 不重建任何 ViewModel
    val selectedTab by appShellViewModel.selectedTab.collectAsState()

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current

    // 首页状态与业务逻辑（搜索 / 播放 / 下载 / 链接解析 / 详情入口）已抽到 SearchScreenState；
    // 下面用 `with(st) { ... }` 渲染，字段与 handler 的引用写法保持不变。
    val st = remember(scope) {
        SearchScreenState(
            scope = scope,
            uiViewModel = uiViewModel,
            playerViewModel = playerViewModel,
            downloadViewModel = downloadViewModel,
            selectionViewModel = selectionViewModel,
            snackbarHostState = snackbarHostState,
        )
    }

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
                st.saveDirPath = path
            }
        }
    }
    var cacheSize by remember { mutableStateOf<MenuDrawerCacheSize>(MenuDrawerCacheSize.Loading) }

    // 「清除缓存」弹窗状态（体积数据异步加载，勾选项由本地维护）
    var cacheItemsLoading by remember { mutableStateOf(false) }
    var cacheClearing by remember { mutableStateOf(false) }
    var cacheItems by remember { mutableStateOf<List<CacheItem>>(emptyList()) }
    var cacheSelected by remember { mutableStateOf<Set<CacheCategory>>(emptySet()) }

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
        uiState.cacheClearVisible ||
        st.sourcePickerVisible ||
        st.batchPickerVisible
    LaunchedEffect(anyOverlayVisible) {
        if (anyOverlayVisible) focusManager.clearFocus()
    }

    val currentSong = playerState.currentSong
    val toastId = st.downloadingId

    // 系统导航 / 手势条底部 inset。注意：直接读取的 WindowInsets 不会被父级 padding
    // 「消费」清零（`asPaddingValues()` 不感知 ModifierLocalConsumedWindowInsets，已对
    // 依赖字节码核实），所以这里是完整值；底栏槽位用它抵消外层 `systemBarsPadding()`
    // 的底部内缩（详见槽位处注释），使玻璃面板仍能贴到屏幕底边。
    // 底栏底部内缩：**必须与父层 Box 的 `systemBarsPadding()` 同源**（用 systemBars，
    // 而不是 navigationBars）——底栏靠这个值把玻璃向下补偿到屏幕底边；若两者不一致，
    // 胶囊下缘会留下一条没被玻璃盖住的缝，下层列表透色。
    val bottomInset = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()

    with(st) {
    // 主页面判定：没有任何浮层 / 抽屉 / sheet（`anyOverlayVisible` 已含 ⇄ 源选择与批量音质
    // 面板），且没有打开 Qobuz / KKBOX 全屏详情页。主页面显示常驻毛玻璃底栏；
    // 其余二级页面一律换成旧迷你播放条（两者互斥、画在同一槽位，不会同时出现）。
    val isPrimaryPage = !anyOverlayVisible && qobuzDetailRoute == null && kkboxDetailRoute == null
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = AppColors.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) {
        // 键盘弹出时不压缩 body，与 Dart `resizeToAvoidBottomInset: false` 一致：迷你条固定屏幕底部。
        // 【inset 责任】这里**故意不再统一内缩**：App 本身是 edge-to-edge（Android 15+ 强制），
        // 各浮层自带 inset（播放页 / 全屏 sheet 用 systemBarsPadding，底部 sheet 用
        // navigationBarsPadding，抽屉自带 statusBarTop）。统一内缩的后果是：① 浮层背景到不了
        // 状态栏后面（状态栏那一条露出纯色底，看着像「没做全屏」）；② 自带 inset 的浮层被缩两次。
        // 注意**不要**改用 safeDrawingPadding：它含 IME 内边距，键盘一弹就会把迷你条和 ⇄ 一起顶上去。
        Box(modifier = Modifier.fillMaxSize()) {
            // tab 返回键：声明在全部浮层之前 → 任何打开的 sheet/播放页仍优先吃掉返回事件。
            // 只有在非默认 tab（搜索）时才拦截，退回搜索。
            BackHandler(enabled = selectedTab != AppTab.Search) {
                appShellViewModel.selectTab(AppTab.Search)
            }

            // ── 基础内容列（Overlay.COLUMN）：按 tab 切换 —— 搜索内容 / 本地曲库（占位页） ──
            when (selectedTab) {
                AppTab.Search -> SearchScreen(
                    modifier = Modifier.fillMaxSize(),
                    st = st,
                    uiViewModel = uiViewModel,
                    selectionViewModel = selectionViewModel,
                    uiState = uiState,
                    selection = selection,
                    downloadTasks = downloadTasks,
                    currentSong = currentSong,
                )

                AppTab.Library -> LocalLibraryScreen(
                    // 顶栏左侧菜单按钮：与搜索页同一个动作（打开菜单抽屉）
                    onOpenMenu = { uiViewModel.setDrawerVisible(true) },
                    modifier = Modifier.fillMaxSize(),
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
                // 从抽屉进入子页面时**不收抽屉**：子页面声明在抽屉之后（z 序更高）会自然盖住抽屉，
                // 返回后仍停在抽屉里，方便连续改多项设置。
                onQobuzToken = { uiViewModel.setQobuzTokenVisible(true) },
                onKkboxAccount = { uiViewModel.setKkboxAccountVisible(true) },
                onDownloadQueue = { uiViewModel.setDownloadQueueVisible(true) },
                cacheSize = cacheSize,
                onClearCache = {
                    // 不再直接清空：先弹窗列出各类缓存体积，由用户勾选后清理
                    uiViewModel.setDrawerVisible(false)
                    uiViewModel.setCacheClearVisible(true)
                    scope.launch {
                        cacheItemsLoading = true
                        val items = try {
                            AssetCache.cacheItems()
                        } catch (_: Exception) {
                            emptyList()
                        }
                        cacheItems = items
                        cacheSelected = items.filter { it.bytes > 0L }.map { it.category }.toSet()
                        cacheItemsLoading = false
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

            // ── 底栏槽位（旧 MiniPlayerBar 原位：MenuDrawer 之后、PlayerOverlay 之前）──
            // 常驻**同一颗胶囊**：主页面（搜索 / 本地曲库）是「底栏态」，其余二级页面
            // （抽屉 / 各类 sheet / 详情页 / 播放页之下）自动 morph 成「迷你播放器态」——
            // 封面左滑、左右 tab 淡出、标题-歌手-播放-进度淡入，反向即原路恢复。
            // 声明在本槽位 → 之后声明的浮层（播放页、各 sheet、详情路由）天然把它盖住；
            // 例外：Qobuz Token / KKBOX 账号两个配置页声明在**本槽位之前**（见上方），
            // 于是迷你播放器浮在它们之上常驻可见（弹窗类 sheet 仍在其后声明、仍盖住它）。
            AppBottomBar(
                selectedTab = selectedTab,
                onSelectTab = { appShellViewModel.selectTab(it) },
                isMiniPlayer = !isPrimaryPage,
                bottomInset = bottomInset,
                coverUrl = currentSong?.cover.orEmpty(),
                fallbackCoverUrl = colorState.currentCoverUrl,
                title = currentSong?.title.orEmpty(),
                artist = currentSong?.artist.orEmpty(),
                isPlaying = playerState.isPlaying,
                currentTime = playerState.currentTime,
                duration = playerState.duration,
                onOpenPlayer = { uiViewModel.setPlayerOverlayVisible(true) },
                modifier = Modifier
                    .align(Alignment.BottomCenter),
            )

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
                    // 歌词高亮行索引回写 UiState（对齐 Flutter `setCurrentLineIndex`）
                    onLineIndexChange = { uiViewModel.setCurrentLineIndex(it) },
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

            ContactModal(
                visible = uiState.contactVisible,
                onClose = { uiViewModel.setContactVisible(false) },
            )
            CacheClearModal(
                visible = uiState.cacheClearVisible,
                loading = cacheItemsLoading,
                clearing = cacheClearing,
                items = cacheItems,
                selected = cacheSelected,
                onToggle = { category ->
                    cacheSelected = if (category in cacheSelected) {
                        cacheSelected - category
                    } else {
                        cacheSelected + category
                    }
                },
                onConfirm = {
                    scope.launch {
                        cacheClearing = true
                        try {
                            // 播放缓存必须走 Media3 的 Cache API（目录正被 SimpleCache 占用）
                            if (CacheCategory.PLAYBACK in cacheSelected) {
                                playerViewModel.clearPlaybackCache()
                            }
                            AssetCache.clearCacheCategories(
                                cacheSelected - CacheCategory.PLAYBACK,
                            )
                        } catch (_: Exception) {
                        }
                        cacheClearing = false
                        uiViewModel.setCacheClearVisible(false)
                        cacheSize = try {
                            MenuDrawerCacheSize.Ready(AssetCache.getFileCacheSize())
                        } catch (_: Exception) {
                            MenuDrawerCacheSize.Error
                        }
                        // 用户要求：清缓存提示用安卓原生 Toast
                        Toast.makeText(ctx, "已清除缓存", Toast.LENGTH_SHORT).show()
                    }
                },
                onClose = { uiViewModel.setCacheClearVisible(false) },
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
}
