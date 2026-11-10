package com.annieplayer.annie_music.kt.features.shell

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import android.widget.Toast
import android.util.Log
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.annieplayer.annie_music.kt.core.audio.MediaPlayerBackend
import com.annieplayer.annie_music.kt.core.audio.TuningEngine
import com.annieplayer.annie_music.kt.core.platform.MediaStoreScan
import com.annieplayer.annie_music.kt.core.platform.exitAppCompletely
import com.annieplayer.annie_music.kt.core.platform.isIgnoringBatteryOptimizations
import com.annieplayer.annie_music.kt.core.platform.isManageStorageGranted
import com.annieplayer.annie_music.kt.core.platform.openBatteryOptimizationSettings
import com.annieplayer.annie_music.kt.core.platform.requestIgnoreBatteryOptimizations
import com.annieplayer.annie_music.kt.core.platform.requestManageStorage
import com.annieplayer.annie_music.kt.core.routing.BackTarget
import com.annieplayer.annie_music.kt.core.routing.PageCloseRelay
import com.annieplayer.annie_music.kt.core.routing.handleBack
import com.annieplayer.annie_music.kt.core.state.AppShellViewModel
import com.annieplayer.annie_music.kt.core.state.AlbumRoute
import com.annieplayer.annie_music.kt.core.state.KkboxRoute
import com.annieplayer.annie_music.kt.core.state.PlaylistRoute
import com.annieplayer.annie_music.kt.core.state.QobuzRoute
import com.annieplayer.annie_music.kt.core.state.SingerRoute
import com.annieplayer.annie_music.kt.core.state.toOverlayFlags
import com.annieplayer.annie_music.kt.core.state.AppTab
import com.annieplayer.annie_music.kt.core.state.LocalAlbumRoute
import com.annieplayer.annie_music.kt.core.state.LocalArtistAlbumsRoute
import com.annieplayer.annie_music.kt.core.state.LocalArtistRoute
import com.annieplayer.annie_music.kt.core.state.LocalArtistSongsRoute
import com.annieplayer.annie_music.kt.core.state.LocalFolderRoute
import com.annieplayer.annie_music.kt.core.state.LocalSectionRoute
import com.annieplayer.annie_music.kt.features.library.LocalAlbumDetailScreen
import com.annieplayer.annie_music.kt.features.library.LocalAlbumListScreen
import com.annieplayer.annie_music.kt.features.library.LocalArtistAlbumsScreen
import com.annieplayer.annie_music.kt.features.library.LocalArtistDetailScreen
import com.annieplayer.annie_music.kt.features.library.LocalArtistListScreen
import com.annieplayer.annie_music.kt.features.library.LocalArtistSongsScreen
import com.annieplayer.annie_music.kt.features.library.LocalFolderDetailScreen
import com.annieplayer.annie_music.kt.features.library.LocalFolderListScreen
import com.annieplayer.annie_music.kt.features.library.LocalLibraryDetailHost
import com.annieplayer.annie_music.kt.features.favorites.FavoritesScreen
import com.annieplayer.annie_music.kt.features.library.LocalLibraryScreen
import com.annieplayer.annie_music.kt.features.library.LocalLibraryViewModel
import com.annieplayer.annie_music.kt.features.library.LocalOverlayFrame
import com.annieplayer.annie_music.kt.features.library.LocalRecentListScreen
import com.annieplayer.annie_music.kt.core.state.ColorViewModel
import com.annieplayer.annie_music.kt.core.state.DownloadViewModel
import com.annieplayer.annie_music.kt.core.state.KkboxViewModel
import com.annieplayer.annie_music.kt.core.state.MoreSettings
import com.annieplayer.annie_music.kt.core.state.PlayerViewModel
import com.annieplayer.annie_music.kt.core.state.QobuzViewModel
import com.annieplayer.annie_music.kt.core.state.SearchSelectionViewModel
import com.annieplayer.annie_music.kt.core.state.TranslatePicker
import com.annieplayer.annie_music.kt.core.state.UiViewModel
import com.annieplayer.annie_music.kt.core.util.cancelPending
import com.annieplayer.annie_music.kt.core.util.extractPlayerCoverPalette
import com.annieplayer.annie_music.kt.data.AssetCache
import com.annieplayer.annie_music.kt.data.CacheCategory
import com.annieplayer.annie_music.kt.data.CacheItem
import com.annieplayer.annie_music.kt.data.DownloadDirService
import com.annieplayer.annie_music.kt.data.DownloadService
import com.annieplayer.annie_music.kt.data.local.LocalLibraryStore
import com.annieplayer.annie_music.kt.data.model.LoopMode
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.source.LxPluginRegistry
import com.annieplayer.annie_music.kt.features.kkbox.KkboxDetailScreen
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.playQualityLabelOf
import com.annieplayer.annie_music.kt.data.playQualityTiersOf
import com.annieplayer.annie_music.kt.data.sources.kkbox.kkboxSessionProvider
import com.annieplayer.annie_music.kt.data.sources.kkbox.kkboxSessionUpdater
import com.annieplayer.annie_music.kt.data.sources.kkbox.kkboxSidProvider
import com.annieplayer.annie_music.kt.data.sources.qobuz.qobuzTokenProvider
import com.annieplayer.annie_music.kt.features.playlist.PlaylistParseScreen
import com.annieplayer.annie_music.kt.features.playlist.PlaylistParseScreenState
import com.annieplayer.annie_music.kt.features.player.PlayerScreen
import com.annieplayer.annie_music.kt.features.qobuz.QobuzDetailScreen
import com.annieplayer.annie_music.kt.core.state.HomeShelfRoute
import com.annieplayer.annie_music.kt.core.state.KugouStyleRoute
import com.annieplayer.annie_music.kt.core.state.toRoute
import com.annieplayer.annie_music.kt.features.home.HomeFeedState
import com.annieplayer.annie_music.kt.features.home.HomeScreen
import com.annieplayer.annie_music.kt.features.home.HomeShelfAllSheet
import com.annieplayer.annie_music.kt.features.home.KugouStyleSheet
import com.annieplayer.annie_music.kt.features.search.SearchScreenState
import com.annieplayer.annie_music.kt.features.details.DetailNavigator
import com.annieplayer.annie_music.kt.ui.theme.Annie
import com.annieplayer.annie_music.kt.ui.theme.AnnieTypography
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.ColorPalette
import com.annieplayer.annie_music.kt.ui.theme.LocalAmbientCoverUrl
import com.annieplayer.annie_music.kt.ui.theme.LocalCoverThemePalette
import com.annieplayer.annie_music.kt.widgets.AlbumDetailSheet
import com.annieplayer.annie_music.kt.widgets.CacheClearModal
import com.annieplayer.annie_music.kt.widgets.ContactModal
import com.annieplayer.annie_music.kt.widgets.DetailQualityPickerHost
import com.annieplayer.annie_music.kt.widgets.DownloadQueueSheet
import com.annieplayer.annie_music.kt.widgets.GlobalOverlays
import com.annieplayer.annie_music.kt.widgets.KkboxAccountSheet
import com.annieplayer.annie_music.kt.widgets.LxImportUrlDialog
import com.annieplayer.annie_music.kt.widgets.LxPluginManagerSheet
import com.annieplayer.annie_music.kt.widgets.LocalDetailQualityPickerHost
import com.annieplayer.annie_music.kt.widgets.MenuDrawer
import com.annieplayer.annie_music.kt.widgets.MenuDrawerCacheSize
import com.annieplayer.annie_music.kt.widgets.PlayerOverlay
import com.annieplayer.annie_music.kt.widgets.PlaylistDetailSheet
import com.annieplayer.annie_music.kt.widgets.AppleServiceSheet
import com.annieplayer.annie_music.kt.widgets.QobuzTokenSheet
import com.annieplayer.annie_music.kt.features.downloads.DownloadOrchestrator
import com.annieplayer.annie_music.kt.features.downloads.batchDownloadQualities
import com.annieplayer.annie_music.kt.features.settings.MoreSettingsScreen
import com.annieplayer.annie_music.kt.core.audio.AudioRoute
import com.annieplayer.annie_music.kt.core.audio.AudioDirectProbe
import com.annieplayer.annie_music.kt.core.audio.OutputPath
import com.annieplayer.annie_music.kt.core.audio.UsbAudioDirect
import com.annieplayer.annie_music.kt.core.audio.DowngradeNotice
import com.annieplayer.annie_music.kt.core.audio.DSD_DEFAULT_PCM_RATE
import com.annieplayer.annie_music.kt.core.audio.DsdLevel
import com.annieplayer.annie_music.kt.core.audio.bluetoothSummaryText
import com.annieplayer.annie_music.kt.core.audio.chainText
import com.annieplayer.annie_music.kt.core.audio.dsdMaxPcmRate
import com.annieplayer.annie_music.kt.core.audio.dsdMaxOutputRate
import com.annieplayer.annie_music.kt.core.audio.dsdRateNotice
import com.annieplayer.annie_music.kt.core.audio.DSD_DEFAULT_PCM_RATE
import com.annieplayer.annie_music.kt.core.audio.systemMixRateFor
import com.annieplayer.annie_music.kt.core.audio.dsdSummaryText
import com.annieplayer.annie_music.kt.core.audio.khzText
import com.annieplayer.annie_music.kt.features.settings.DiagnosticsSheet
import com.annieplayer.annie_music.kt.features.settings.OutputCapabilitiesSheet
import com.annieplayer.annie_music.kt.features.settings.TuningSheet
import com.annieplayer.annie_music.kt.features.settings.moreSettingsGroups
import com.annieplayer.annie_music.kt.widgets.QualityPickerSheet
import com.annieplayer.annie_music.kt.widgets.TranslatePickerSheet
import com.annieplayer.annie_music.kt.widgets.LocalDeleteModal
import com.annieplayer.annie_music.kt.widgets.FollowSourceNoticeDialog
import com.annieplayer.annie_music.kt.widgets.OutputDowngradeDialog
import com.annieplayer.annie_music.kt.widgets.DsdOutputSheet
import com.annieplayer.annie_music.kt.widgets.QueueSheet
import com.annieplayer.annie_music.kt.widgets.SearchSelectionOverlay
import com.annieplayer.annie_music.kt.widgets.SingerDetailSheet
import com.annieplayer.annie_music.kt.widgets.SongActionsSheet
import com.annieplayer.annie_music.kt.widgets.SourcePickerSheet
import com.annieplayer.annie_music.kt.widgets.blockTouches
import com.annieplayer.annie_music.kt.core.compose.activityViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * 应用外壳（首页宿主屏 + 覆盖层栈，1:1 移植 Flutter `lib/features/search/home_screen.dart`
 * 的接线部分）。
 *
 * 本 App 不是常规导航栈，而是「单屏 + 覆盖层栈」：本文件就是那个**宿主屏**，负责
 * 1) App 级接线（状态收集、搜索状态容器 `SearchScreenState`、音频引擎接线、存储权限 /
 *    缓存清理 / SAF 目录流程）；
 * 2) 把 `ui/theme` token、状态容器（`core/state`）与既有的自驱动浮层组件接线起来；
 * 3) 在内容列的位置渲染 `features/home/HomeScreen.kt`（主页 + 它的搜索形态；
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
 * - Qobuz / KKBOX 链接解析成功后压入全屏详情页（`QobuzDetailScreen` / `KkboxDetailScreen`，
 *   走 `UiState.pageStack` 的 `QobuzRoute` / `KkboxRoute`）；歌单链接解析压入
 *   `PlaylistDetailSheet` 对应的 `PlaylistRoute`；非法链接弹 SnackBar。
 * - 顶栏右侧头像已使用工程内资源 `res/drawable-nodpi/annie.jpg`（`R.drawable.annie`），
 *   不再是音符占位块。
 */

/**
 * 氛围主题色切歌补间时长（毫秒）。
 *
 * 取 420ms：比播放页封面淡入（500ms）略快一点，让人感觉「背景先跟上、封面再落定」，
 * 又不至于慢到切歌时糊成一片。
 */
private const val CoverThemeFadeMillis = 420

/**
 * 降级弹窗的稳定期。
 *
 * 覆盖"插上 USB DAC 的第一拍"那种假降级（`UsbManager` 比 `AudioManager` 慢一拍，
 * 我方会先回落系统通路、几百毫秒后自动恢复）。超过这个时长仍不恢复，才值得告诉用户。
 */
private const val DOWNGRADE_SETTLE_MS = 1_500L

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
    // 本地曲库：详情栈条目渲染时要读它的扫描结果（曲目列表）
    localLibraryViewModel: com.annieplayer.annie_music.kt.features.library.LocalLibraryViewModel =
        activityViewModel(),
    // 收藏：「加入到歌单」的候选列表与写入都要它
    favoriteViewModel: com.annieplayer.annie_music.kt.core.state.FavoriteViewModel = activityViewModel(),
) {
    // ── 状态（对齐 Dart Riverpod providers + `_HomeScreenState` 字段） ──
    val uiState by uiViewModel.state.collectAsState()
    val playerState by playerViewModel.state.collectAsState()
    val localLibraryState by localLibraryViewModel.state.collectAsState()
    val downloadTasks by downloadViewModel.state.collectAsState()
    val selection by selectionViewModel.state.collectAsState()
    val favoriteSnapshot by favoriteViewModel.snapshot.collectAsState()
val writeQobuzDownloadTranslation by MoreSettings.writeQobuzDownloadTranslation.collectAsState()
val writeEnhancedLyrics by MoreSettings.writeEnhancedLyrics.collectAsState()
val saveLrcFile by MoreSettings.saveLrcFile.collectAsState()
val outputPath by MoreSettings.outputPath.collectAsState()
val outputToggles by MoreSettings.outputToggles.collectAsState()
// 引擎要在 `tuningOn` 之前读：`tuningEnabled` 需要它（选了「无」= 一票否决）
val tuningEngine by MoreSettings.tuningEngine.collectAsState()
// 派生：这一组设置下是否启用 DSP（唯一判据 `tuningEnabled`，不在各处自己算）。
// 蓝牙下通路开关都不参与 → 通路这一维恒启用（蓝牙只接管链路参数，不接管调音），
// 所以这里必须把路由也喂进去，否则"戴着蓝牙耳机看不到调音"。
// **引擎也必须喂**：选了「无」就是一票否决，与通路无关。
val tuningOn = com.annieplayer.annie_music.kt.core.audio.tuningEnabled(
    outputPath,
    outputToggles,
    bluetooth = playerState.audioStatus?.route == AudioRoute.bluetooth,
    engine = tuningEngine,
)

val loudnessNormalization by MoreSettings.loudnessNormalization.collectAsState()
val dsdMode by MoreSettings.dsdMode.collectAsState()
val dsdPcmRates by MoreSettings.dsdPcmRates.collectAsState()
val playQualityMap by MoreSettings.playQuality.collectAsState()
val lxPlugins by LxPluginRegistry.plugins.collectAsState()
val translatePickerVisible by TranslatePicker.visible.collectAsState()
val translatePickerLangs by TranslatePicker.langs.collectAsState()
val translatePickerSongTitle by TranslatePicker.songTitle.collectAsState()
    val colorState by colorViewModel.state.collectAsState()
    // 动态色板取「取色结果本身」（ColorViewModel 的 state），**不是** `Annie.palette`：
    // MainActivity 目前是 `AnnieTheme { }`（用默认 palette = fallback），CompositionLocal 里那个
    // `Annie.palette` 永远是 fallback 靛蓝；取色结果一路写进了 colorState，但此前没人消费它，
    // 导致播放页歌词指示条 / 播放页取色永远不变。这里改用 colorState.colors 打通链路。
    val palette = colorState.colors

    // ── 全站取色（与播放页同一套）：跟「当前播放歌曲封面」，不跟是否点开播放页 ──
    // 冷启动时 PlayerState.init 已从 PlayerResumeStore 恢复出 currentSong（暂停态），
    // 所以这里直接就能拿到"上一次播放的那首歌"的封面；只有首次安装 / 清过应用数据
    // 才为 null，此时退回 ColorPalette.fallback（原来的深色底）。
    //
    // 为什么不能留在 PlayerScreen 里：那个组件只在播放浮层打开时才组合
    // （PlayerOverlay(visible = uiState.playerOverlayVisible)），不点进播放页就永远不取色，
    // 主页 / 曲库只能是死蓝底。取色触发点必须提到这一层。
    //
    // 这里写入的是 `ColorState.colors`（全局唯一色板，含 4×4 网格）：
    // 播放页拿它的网格，其它页面拿它的 primary / secondary / accent。
    val themeCoverUrl = playerState.currentSong?.cover.orEmpty()
    LaunchedEffect(themeCoverUrl) {
        if (themeCoverUrl.isEmpty()) return@LaunchedEffect
        val extracted = try {
            extractPlayerCoverPalette(themeCoverUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return@LaunchedEffect
        val current = colorViewModel.state.value.colors
        colorViewModel.setColors(
            current.copyWith(
                primary = extracted.primary,
                secondary = extracted.secondary,
                accent = extracted.accent,
                coverGrid = extracted.coverGrid,
                coverGridDim = extracted.coverGridDim,
            ),
        )
    }

    // 切歌渐变：色板参与补间，避免整屏背景硬跳色。
    // 只补间有意义的三个通道；background / surface 恒为 fallback 的深色底，不需要动。
    val themeTarget = colorState.colors
    val themePrimary by animateColorAsState(
        targetValue = themeTarget.primary,
        animationSpec = tween(durationMillis = CoverThemeFadeMillis),
        label = "coverThemePrimary",
    )
    val themeSecondary by animateColorAsState(
        targetValue = themeTarget.secondary,
        animationSpec = tween(durationMillis = CoverThemeFadeMillis),
        label = "coverThemeSecondary",
    )
    val themeAccent by animateColorAsState(
        targetValue = themeTarget.accent,
        animationSpec = tween(durationMillis = CoverThemeFadeMillis),
        label = "coverThemeAccent",
    )
    // 三个颜色通道参与补间，但 **4×4 网格必须原样带过去**：
    // 页面背景（`CoverPaletteBackdrop`）需要网格来聚类色彩家族、算明度，
    // 一旦在这里把 coverGrid 丢掉，下游就只能拿到空的网格 → 走兜底 → 底色恒为纯黑，
    // 明度/比例相关的所有逻辑全部失效（表现就是「改了没生效、页面永远是黑的」）。
    // 网格不做补间：它换歌时是原子替换，颜色补间由上面三个 animateColorAsState 负责。
    val coverThemePalette = remember(
        themePrimary,
        themeSecondary,
        themeAccent,
        themeTarget.coverGrid,
        themeTarget.coverGridDim,
    ) {
        ColorPalette.fallback.copyWith(
            primary = themePrimary,
            secondary = themeSecondary,
            accent = themeAccent,
            coverGrid = themeTarget.coverGrid,
            coverGridDim = themeTarget.coverGridDim,
        )
    }

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
            playerViewModel = playerViewModel,
            downloadViewModel = downloadViewModel,
            selectionViewModel = selectionViewModel,
            snackbarHostState = snackbarHostState,
            // 链接解析出的 Qobuz / KKBOX 详情页统一压到全局页面栈
            onOpenRoute = { uiViewModel.pushRoute(it) },
        )
    }

    // 详情跳转（歌手 / 专辑）：从 SearchScreenState 拆出，供全局 ⋮ 面板使用
    val detailNavigator = remember(scope) {
        DetailNavigator(
            scope = scope,
            uiViewModel = uiViewModel,
            playerViewModel = playerViewModel,
            snackbarHostState = snackbarHostState,
        )
    }

    // 下载编排（⋮ 入口 / 单曲 / 批量）—— 从 SearchScreenState 搬出（见 DownloadOrchestrator 注释）
    val dl = remember(scope) {
        DownloadOrchestrator(
            scope = scope,
            uiViewModel = uiViewModel,
            playerViewModel = playerViewModel,
            downloadViewModel = downloadViewModel,
        )
    }

    // 歌单解析页状态（从 SearchScreenState 拆出；该功能已迁为底栏 tab，不再是搜索模式）。
    // 复用宿主同一个 SnackbarHostState，失败提示与其它页面同一出口。
    val playlistParseSt = remember(scope) {
        PlaylistParseScreenState(
            scope = scope,
            uiViewModel = uiViewModel,
            snackbarHostState = snackbarHostState,
        )
    }

    // 主页内容状态：与 st 同层持有 —— 切 tab / 切「主页 ↔ 搜索」形态都不重建，
    // 回到首页时不会重新请求一遍。平台由 `st.searchPlatform` 驱动（见 HomeScreen）。
    val homeFeedSt = remember(scope) { HomeFeedState() }

    // 「所有文件访问」权限状态：抽屉打开时重新检测（对齐 Flutter `_ManageStorageRow._granted`）
    val ctx = LocalContext.current
    // USB 访问权限：`USB_DEVICE_ATTACHED` 只是把本应用列进系统的设备选择器，
    // **不等于授权** —— 不走一遍 `requestPermission`，`openDevice` 永远返回 null，
    // 表现就是"插了 DAC 但应用说检测不到"。启动时补一次，插拔后由 ON_RESUME 再补。
    LaunchedEffect(Unit) {
        com.annieplayer.annie_music.kt.core.audio.UsbAudioDirect.ensurePermissions(ctx)
    }
    var storageGranted by remember { mutableStateOf<Boolean?>(null) }
    // 「耗电优化」白名单状态：抽屉打开时 + 从系统设置页回来（ON_RESUME）时重新检测
    var batteryExempt by remember { mutableStateOf<Boolean?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryExempt = isIgnoringBatteryOptimizations(ctx)
                storageGranted = isManageStorageGranted(ctx)
                // 插上 DAC 后回到前台补一次授权（已有权限时是空操作，不会重复弹窗）
                com.annieplayer.annie_music.kt.core.audio.UsbAudioDirect.ensurePermissions(ctx)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(uiState.drawerVisible) {
        if (uiState.drawerVisible) {
            storageGranted = isManageStorageGranted(ctx)
            batteryExempt = isIgnoringBatteryOptimizations(ctx)
        }
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

    // 落雪插件：本地导入（SAF 选 .js）+ 在线导入（URL 弹窗）
    val lxLocalPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val plugin = LxPluginRegistry.importLocal(uri)
                    Toast.makeText(ctx, "已导入：${plugin.name}", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.w("LxPlugin", "本地导入失败", e)
                    Toast.makeText(ctx, "导入失败：${e.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    var lxImportUrlVisible by remember { mutableStateOf(false) }
    // 「插件管理」行右侧「+」弹出的导入方式选择（在线 / 本地）
    var lxImportChooserVisible by remember { mutableStateOf(false) }
    var tuningVisible by remember { mutableStateOf(false) }
    var capabilitiesVisible by remember { mutableStateOf(false) }
    var diagnosticsVisible by remember { mutableStateOf(false) }
    var lxImporting by remember { mutableStateOf(false) }

    // 数据层的提示（如"未添加落雪插件"）用安卓原生 Toast 告知
    LaunchedEffect(Unit) {
        LxPluginRegistry.notices.collect { message ->
            Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 启动即预热本地曲库，不等用户点进「本地曲库」页。
     *
     * 为什么值得做：扫描本身不慢（实测 5000 首冷启动 3.4 s），但它原来是
     * 页面驱动的 —— 用户的体感是"点进去之后才开始转"。放到启动阶段后，
     * 等真点进去时结果已经在了。
     *
     * 延迟 800 ms：避开首屏渲染、播放器初始化和网络预热，别和它们抢 I/O。
     * `refresh()` 是**增量**的（按 mtime/size 判断哪些文件变了），
     * 且内部有 `_job?.isActive` 守卫，用户手动点「重扫」时不会并发跑两份。
     */
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(800)
        localLibraryViewModel.refresh(ctx)
    }

    var cacheSize by remember { mutableStateOf<MenuDrawerCacheSize>(MenuDrawerCacheSize.Loading) }

    // 「清除缓存」弹窗状态（体积数据异步加载，勾选项由本地维护）
    var cacheItemsLoading by remember { mutableStateOf(false) }
    var cacheClearing by remember { mutableStateOf(false) }
    var cacheItems by remember { mutableStateOf<List<CacheItem>>(emptyList()) }
    var cacheSelected by remember { mutableStateOf<Set<CacheCategory>>(emptySet()) }

    // 抽屉里的缓存大小：**每次打开都重算 + 打开期间每 3s 刷新**。原来 `LaunchedEffect(Unit)`
    // 只在启动时算一次 → 表现就是「重启才更新」；播放缓存边听边涨，必须实时反映。
    LaunchedEffect(uiState.drawerVisible) {
        if (!uiState.drawerVisible) return@LaunchedEffect
        while (true) {
            cacheSize = try {
                MenuDrawerCacheSize.Ready(AssetCache.getFileCacheSize())
            } catch (_: Exception) {
                MenuDrawerCacheSize.Error
            }
            delay(3_000L)
        }
    }

    // 音频引擎接线（对齐 Flutter `main.dart`：`backend.setup()` 后把 backend 注入播放 provider）。
    // 不接线时 `PlayerViewModel._backend` 恒为 null，next/previous/play/seek/volume 全部空转、无音频。
    LaunchedEffect(Unit) {
        val backend = MediaPlayerBackend(ctx.applicationContext)
        backend.setup()
        playerViewModel.setBackend(backend)
        // KKBOX 歌词/播放/下载全部走官方接口，需要活跃账号的完整会话（sid + kkid + 内容密钥）
        playerViewModel.kkboxSidProvider = { kkboxViewModel.getActiveAccount()?.session?.sid }
        kkboxSidProvider = { kkboxViewModel.getActiveAccount()?.session?.sid }
        kkboxSessionProvider = { kkboxViewModel.getActiveAccount()?.session }
        // 票据接口续期 sid 后写回账号，否则下次请求还带旧 sid（会一路 -1 重试到失败）
        kkboxSessionUpdater = updater@{ renewed ->
            val account = kkboxViewModel.getActiveAccount() ?: return@updater
            kkboxViewModel.updateAccountSession(account.id, renewed)
        }
        qobuzTokenProvider = { qobuzViewModel.getActiveToken()?.token }
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
        uiState.pageStack.isNotEmpty() ||
        uiState.downloadQueueVisible ||
        uiState.qobuzTokenVisible ||
        uiState.kkboxAccountVisible ||
        uiState.contactVisible ||
        uiState.cacheClearVisible ||
        uiState.lxPluginManagerVisible ||
        uiState.dsdOutputVisible ||
        st.sourcePickerVisible ||
                dl.batchPickerVisible
    LaunchedEffect(anyOverlayVisible) {
        if (anyOverlayVisible) focusManager.clearFocus()
    }

    val currentSong = playerState.currentSong
                val toastId = dl.downloadingId

    // 系统导航 / 手势条底部 inset。注意：直接读取的 WindowInsets 不会被父级 padding
    // 「消费」清零（`asPaddingValues()` 不感知 ModifierLocalConsumedWindowInsets，已对
    // 依赖字节码核实），所以这里是完整值；底栏槽位用它抵消外层 `systemBarsPadding()`
    // 的底部内缩（详见槽位处注释），使玻璃面板仍能贴到屏幕底边。
    // 底栏底部内缩：**必须与父层 Box 的 `systemBarsPadding()` 同源**（用 systemBars，
    // 而不是 navigationBars）——底栏靠这个值把玻璃向下补偿到屏幕底边；若两者不一致，
    // 胶囊下缘会留下一条没被玻璃盖住的缝，下层列表透色。
    val bottomInset = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()

    // 详情页音质面板宿主：三个详情 sheet 把面板内容挂到这里，由本文件在「迷你播放条之后」
    // 统一渲染（对齐 Overlay.RENDER_ORDER 的 QUALITY_PICKER 层），从而面板盖住迷你条，
    // 观感与搜索页一致。此前面板内联在详情 sheet 内，必被后声明的 AppBottomBar 压住下半截。
    val detailPickerHost = remember { DetailQualityPickerHost() }

    // 返回手势退出确认：第一次返回弹窗，弹窗还开着再按一次返回（或点「退出」）才真退出
    var exitConfirm by remember { mutableStateOf(false) }

    /** 「强制按源采样率输出」的说明弹窗（优势与代价）；false = 关着 */
    var followSourceNotice by remember { mutableStateOf(false) }

    // tab 页面状态保存器：让每个 tab 的 `rememberSaveable`（例如 `LazyListState`）跨切换存活。
    // 没有它的话，「曲库滑到一半 → 切到别处 → 切回来」会回到列表顶部 —— 因为 `when (tab)`
    // 会把切走的页面整个移出组合树，页面内部的 remember 随之销毁。
    val tabStateHolder = rememberSaveableStateHolder()

    with(st) {
    // 主页面判定：没有任何浮层 / 抽屉 / sheet，也没有子页面压在根页之上
    // （`anyOverlayVisible` 已同时包含 `pageStack.isNotEmpty()`、⇄ 源选择与批量音质面板）。
    // 主页面显示常驻底栏的「底栏态」；其余页面一律切到迷你播放态
    // （两者互斥、画在同一槽位，不会同时出现）。
    val isPrimaryPage = !anyOverlayVisible
    // 菜单抽屉及其子页面（设置 / 下载 / 缓存 / 插件 / 账号）一律不显示底部胶囊（含迷你播放态）
    val hideBottomBar = uiState.drawerVisible ||
        uiState.moreSettingsVisible ||
        uiState.lxPluginManagerVisible ||
        uiState.dsdOutputVisible ||
        uiState.downloadQueueVisible ||
        uiState.qobuzTokenVisible ||
        uiState.kkboxAccountVisible ||
        uiState.cacheClearVisible ||
        uiState.contactVisible
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
        // 氛围背景广播给整棵页面树（LunaBeat 风格）：
        // - LocalAmbientCoverUrl：模糊图源（当前播放封面），背景的**主体**
        // - LocalCoverThemePalette：兜底底色（切歌时 360ms 补间，图没到之前垫着）
        // 主页 / 本地曲库各子页通过这两个 Local 渲染；各详情页各自用自己的封面。
        // 播放页不读它们 —— 播放页走 AnnieTokens.palette + 自己的背景。
        CompositionLocalProvider(
            LocalCoverThemePalette provides coverThemePalette,
            LocalAmbientCoverUrl provides themeCoverUrl,
        ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 覆盖层可见性快照：**在这里算一次**，三个返回裁决点共用同一份。
            //
            // 以前每处各写 `uiState.toOverlayFlags()`，而有两个面板的可见性不在 `UiState` 里
            // （翻译选择器 / 音源选择器），于是那两处永远读到 `false`：
            // `handleBack` 会以为「没有浮层」，把 PAGE / SEARCH 处理器放行 → 页面里的页内返回
            // 逻辑在面板背后仍然武装，面板自己也因此可能被当成不存在。
            val overlayFlags = uiState.toOverlayFlags(
                translatePicker = translatePickerVisible,
                sourcePicker = st.sourcePickerVisible,
                searchSelectionOverlay = selection.overlayVisible,
                searchSelection = selection.selectedSongs.isNotEmpty(),
            )

            // 根返回：**声明在最前面 → 优先级最低**，所有浮层 / 播放页 / tab 切换都先吃返回事件，
            // 只有回到「搜索页主界面」时才落到这里 —— 弹一次确认窗。
            // 确认窗开着时再按一次返回由弹窗自己的 dismissOnBackPress 接住（见文件末尾），
            // 于是「两次返回手势 = 退出」。
            BackHandler(enabled = !exitConfirm) { exitConfirm = true }

            // tab 返回键：声明在全部浮层之前 → 任何打开的 sheet/播放页仍优先吃掉返回事件。
            // 只有在非默认 tab（主页）时才拦截，退回主页。
            BackHandler(enabled = selectedTab != AppTab.Home) {
                appShellViewModel.selectTab(AppTab.Home)
            }

            // 搜索态返回：把主页的搜索形态收回去。
            // 位置在 tab 返回之后 → 优先级更高；但仍在所有浮层 / 子页面之前 →
            // 详情页开着时返回先关详情，详情关了才轮到这一条（见 BACK_RULES 的 SEARCH）。
            BackHandler(enabled = handleBack(overlayFlags) == BackTarget.SEARCH) {
                uiViewModel.setHomeSearchExpanded(false)
            }

            // ── 页面栈的返回：**只在这里裁决** ──
            //
            // 以前每个栈内页面都自己声明 BackHandler，并各自判断「我该不该吃返回」：
            // 传裸 `isTop`（我在栈顶）还是 `localTop`（栈顶且没有更高层浮层）全靠调用方自觉，
            // 传错就出现「⋮ 面板开着时，页面在面板背后把自己弹掉」。
            //
            // 现在只有这一处判断；具体「怎么关」交给页面自己：页面挂载时把 `requestClose`
            // 注册到 `PageCloseRelay`（见 `RememberPageClose`），于是返回键也能播滑出动画；
            // 没有注册者的页面（理论上不存在）退化为直接 popRoute。
            BackHandler(enabled = handleBack(overlayFlags) == BackTarget.PAGE) {
                if (!PageCloseRelay.requestCloseTop()) {
                    uiViewModel.popRoute()
                }
            }

            // 搜索页「待下载」面板：返回先收面板（它的关闭按钮是唯一可见出口，别逼用户去找）。
            BackHandler(enabled = handleBack(overlayFlags) == BackTarget.SEARCH_SELECTION_OVERLAY) {
                selectionViewModel.setOverlayVisible(false)
            }

            // 搜索页多选态：返回先退多选（清空勾选），而不是把搜索态 / 整个 App 收掉。
            // 与详情页的多选同一条规则：**多选是一种形态，返回第一下永远只退这一层**。
            BackHandler(enabled = handleBack(overlayFlags) == BackTarget.SEARCH_SELECTION) {
                selectionViewModel.clearAll()
            }

            // ── 基础内容列（Overlay.COLUMN）：按 tab 切换 ──
            //
            // 外面套 SaveableStateHolder：`when` 会把切走的页面移出组合树，
            // 页面内部的 `rememberLazyListState`（内部是 rememberSaveable）随之销毁，
            // 于是「曲库滑到一半 → 切走 → 切回来」会回到顶部。SaveableStateProvider 按
            // tab 保存 / 恢复这些 saveable 状态，滚动位置、展开状态都能原样回来。
            // 注意它只能保住 `rememberSaveable` 的东西；ViewModel 里的数据本来就跨 tab 存活。
            tabStateHolder.SaveableStateProvider(selectedTab) {
                when (selectedTab) {
                    AppTab.Home -> HomeScreen(
                        modifier = Modifier.fillMaxSize(),
                        st = st,
                        homeFeed = homeFeedSt,
                        dl = dl,
                        uiViewModel = uiViewModel,
                        selectionViewModel = selectionViewModel,
                        uiState = uiState,
                        selection = selection,
                        downloadTasks = downloadTasks,
                        currentSong = currentSong,
                    )

                    AppTab.Favorites -> FavoritesScreen(
                        // 顶栏左侧菜单按钮：与主页 / 曲库页同一个动作（打开菜单抽屉）
                        onOpenMenu = { uiViewModel.setDrawerVisible(true) },
                        modifier = Modifier.fillMaxSize(),
                    )

                    AppTab.Library -> LocalLibraryScreen(
                        // 顶栏左侧菜单按钮：与主页同一个动作（打开菜单抽屉）
                        onOpenMenu = { uiViewModel.setDrawerVisible(true) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            // ── 详情浮层（RN zIndex 150） ──
            // 注入「详情页音质面板宿主」：详情 sheet 不再内联渲染音质面板，改由本文件在
            // AppBottomBar 之后统一渲染（见下方槽位），保证面板盖在迷你播放条之上。
            CompositionLocalProvider(LocalDetailQualityPickerHost provides detailPickerHost) {
            GlobalOverlays(
                searchSelectionOverlay = {
                    SearchSelectionOverlay(
                        visible = selection.overlayVisible,
                        songs = selection.selectedSongs,
                        onClose = { selectionViewModel.setOverlayVisible(false) },
                        onRemove = { id -> selectionViewModel.removeSong(id) },
                    )
                },
                pageStack = {
                    // 详情真栈：按栈底→栈顶顺序渲染，后声明者绘制在上层、其 BackHandler 也先消费返回。
                    // key(entry.key) 用条目唯一键维持每个详情在重组中的身份（同类型多层互不干扰）。
                    val lastIndex = uiState.pageStack.lastIndex
                    // 只有「栈顶 且 没有任何更高层浮层可见」时，详情层才吃返回键。
                    // 与 AlbumDetailSheet 里的 pageIsTop 同源 —— 本地页面也走这一条，
                    // 否则播放页 / 队列 / ⋮ / 设置页 / 账号面板开着时回到本地页面会「穿透」
                    // 把下层页面关掉。这里复用上面算好的 `overlayFlags`（含两个不在 UiState 的开关）。
                    val pageCanConsumeBack = handleBack(overlayFlags) == BackTarget.PAGE
                    uiState.pageStack.forEachIndexed { index, entry ->
                        key(entry.key) {
                            // L1 每层的指针拦截只在这里做一次，各页面壳子不再各写一份：
                            // scrim / 背景只画、不参与命中测试，页面里没有控件的空白处会把触摸漏给
                            // 下层 —— 实测症状是「歌单详情页里还能按到一级栈的菜单按钮」，以及
                            // 栈深 ≥2 时点透到下一层页面的列表。
                            // 本节点在同一个 key 组内先声明 → 视觉上更靠下：页面里的真控件仍先命中，
                            // 只有没人消费的触摸才落到这里被吃掉。
                            Box(modifier = Modifier.fillMaxSize().blockTouches())
                            val isTop = index == lastIndex
                            val localTop = isTop && pageCanConsumeBack
                            // 三个流媒体详情页**也传 localTop**（与下面「全部」页 / 本地页面一致）。
                            // 曾经这里传的是裸 `isTop`，而三页内部都拿 `isTop` 当返回键开关，
                            // 于是「栈顶且 ⋮ 面板 / 播放页开着」时页面仍会吃返回，在浮层背后把自己关掉。
                            when (entry) {
                                is AlbumRoute -> AlbumDetailSheet(
                                    data = entry.data,
                                    isTop = localTop,
                                    onClose = { uiViewModel.popRoute() },
                                    uiViewModel = uiViewModel,
                                    playerViewModel = playerViewModel,
                                    downloadViewModel = downloadViewModel,
                                )
                                is SingerRoute -> SingerDetailSheet(
                                    data = entry.data,
                                    isTop = localTop,
                                    onClose = { uiViewModel.popRoute() },
                                    uiViewModel = uiViewModel,
                                    playerViewModel = playerViewModel,
                                    downloadViewModel = downloadViewModel,
                                    // overview 的两个「全部 ›」压栈（不再切页内 tab）：
                                    // 子页是独立一层，返回逐层退回，与本地曲库那套对称。
                                    onOpenSongs = {
                                        uiViewModel.pushRoute(
                                            com.annieplayer.annie_music.kt.core.state
                                                .SingerSongsRoute(entry.data),
                                        )
                                    },
                                    onOpenAlbums = { section ->
                                        uiViewModel.pushRoute(
                                            com.annieplayer.annie_music.kt.core.state
                                                .SingerAlbumsRoute(entry.data, section),
                                        )
                                    },
                                )

                                // 歌手的「全部单曲 / 全部专辑」：与 overview 同一个组件、同一份缓存，
                                // 只是 `page` 固定成对应那一页。
                                is com.annieplayer.annie_music.kt.core.state.SingerSongsRoute ->
                                    SingerDetailSheet(
                                        data = entry.data,
                                        isTop = localTop,
                                        onClose = { uiViewModel.popRoute() },
                                        uiViewModel = uiViewModel,
                                        playerViewModel = playerViewModel,
                                        downloadViewModel = downloadViewModel,
                                        page = com.annieplayer.annie_music.kt.widgets
                                            .SingerDetailPage.songs,
                                    )

                                is com.annieplayer.annie_music.kt.core.state.SingerAlbumsRoute ->
                                    SingerDetailSheet(
                                        data = entry.data,
                                        isTop = localTop,
                                        onClose = { uiViewModel.popRoute() },
                                        uiViewModel = uiViewModel,
                                        playerViewModel = playerViewModel,
                                        downloadViewModel = downloadViewModel,
                                        page = com.annieplayer.annie_music.kt.widgets
                                            .SingerDetailPage.albums,
                                        albumSection = entry.section,
                                    )
                                is PlaylistRoute -> PlaylistDetailSheet(
                                    data = entry.data,
                                    isTop = localTop,
                                    onClose = { uiViewModel.popRoute() },
                                    uiViewModel = uiViewModel,
                                    playerViewModel = playerViewModel,
                                    downloadViewModel = downloadViewModel,
                                )

                                // 主页区块的「全部」页：栈里的一页（返回裁决 / 顶栏 / 背景与本地曲库子页同源），
                                // 条目已在 HomeFeedState 里，不再请求；从它点卡片继续往栈里压详情。
                                is HomeShelfRoute -> HomeShelfAllSheet(
                                    data = entry.data,
                                    // 必须传 localTop（= 栈顶且没有更高层浮层），不能传裸 isTop：
                                    // ⋮ 面板 / 播放页开着时页面若还吃返回，会把页面自己在面板背后弹掉
                                    isTop = localTop,
                                    onClose = { uiViewModel.popRoute() },
                                    onOpenTarget = { target ->
                                        uiViewModel.pushRoute(target.toRoute())
                                    },
                                )

                                // 酷狗「按风格听」的流派二级页：点谁拉谁（主页只放卡，不发请求）
                                is KugouStyleRoute -> KugouStyleSheet(
                                    tagIds = entry.tagIds,
                                    title = entry.title,
                                    // 同上：只有「栈顶 + 没有更高层浮层」时才吃返回
                                    isTop = localTop,
                                    currentSongId = currentSong?.id,
                                    onClose = { uiViewModel.popRoute() },
                                    onPlay = { song, queue -> with(st) { handlePlaySong(song, queue) } },
                                    onMore = { song -> dl.openActions(song) },
                                )

                                // ── 收藏库的子页面：同样并进这条 pageStack ──
                                is com.annieplayer.annie_music.kt.core.state.FavoriteSectionRoute ->
                                    LocalLibraryDetailHost(
                                        isTop = localTop,
                                        onClose = { uiViewModel.popRoute() },
                                    ) { requestClose ->
                                        com.annieplayer.annie_music.kt.features.favorites.FavoriteSectionScreen(
                                            section = entry.section,
                                            isTop = localTop,
                                            onClose = requestClose,
                                        )
                                    }

                                // 收藏库的自建播放列表详情：收 id、页面自己从 snapshot 现取
                                is com.annieplayer.annie_music.kt.core.state.UserPlaylistRoute ->
                                    LocalLibraryDetailHost(
                                        isTop = localTop,
                                        onClose = { uiViewModel.popRoute() },
                                    ) { requestClose ->
                                        com.annieplayer.annie_music.kt.features.favorites.UserPlaylistScreen(
                                            playlistId = entry.playlistId,
                                            isTop = localTop,
                                            onClose = requestClose,
                                        )
                                    }

                                // 新建自建播放列表已经改成弹窗（`NewPlaylistDialog`），不再占栈位

                                // ── 本地曲库的子页面：与流媒体共用同一条 pageStack ──
                                // 套 LocalOverlayFrame 拿到和详情浮层一样的「遮罩 + 150ms 滑入/滑出」；
                                // 返回裁决走同一条 handleBack（播放页在上层时它先消费）。
                                is LocalSectionRoute -> LocalLibraryDetailHost(
                                    isTop = localTop,
                                    onClose = { uiViewModel.popRoute() },
                                ) { requestClose ->
                                    val localSection = entry.section
                                    when (localSection) {
                                        com.annieplayer.annie_music.kt.data.local.LocalSection.artists ->
                                            LocalArtistListScreen(
                                                tracks = localLibraryState.tracks,
                                                isTop = localTop,
                                                onClose = requestClose,
                                                onOpenArtist = { uiViewModel.pushRoute(LocalArtistRoute(it)) },
                                            )
                                        com.annieplayer.annie_music.kt.data.local.LocalSection.albums ->
                                            LocalAlbumListScreen(
                                                tracks = localLibraryState.tracks,
                                                isTop = localTop,
                                                onClose = requestClose,
                                                onOpenAlbum = { uiViewModel.pushRoute(LocalAlbumRoute(it)) },
                                            )
                                        com.annieplayer.annie_music.kt.data.local.LocalSection.folders ->
                                            LocalFolderListScreen(
                                                tracks = localLibraryState.tracks,
                                                isTop = localTop,
                                                onClose = requestClose,
                                                onOpenFolder = { uiViewModel.pushRoute(LocalFolderRoute(it)) },
                                            )
                                        else -> LocalRecentListScreen(
                                            tracks = localLibraryState.tracks,
                                            currentSongId = playerState.currentSong?.id,
                                            isTop = localTop,
                                            onClose = requestClose,
                                            onPlayAt = { list, idx ->
                                                playerViewModel.setQueue(list.map { it.toSong() }, idx)
                                            },
                                            onMore = { uiViewModel.setSongActionsVisible(true, it.toSong()) },
                                        )
                                    }
                                }

                                is LocalAlbumRoute -> LocalLibraryDetailHost(
                                    isTop = localTop,
                                    onClose = { uiViewModel.popRoute() },
                                ) { requestClose ->
                                    LocalAlbumDetailScreen(
                                        albumKey = entry.albumKey,
                                        tracks = localLibraryState.tracks,
                                        currentSongId = playerState.currentSong?.id,
                                        isTop = localTop,
                                        onClose = requestClose,
                                        onPlayAt = { list, idx ->
                                            playerViewModel.setQueue(list.map { it.toSong() }, idx)
                                        },
                                        onMore = { uiViewModel.setSongActionsVisible(true, it.toSong()) },
                                        onOpenArtist = { uiViewModel.pushRoute(LocalArtistRoute(it)) },
                                        onRequestCover = { localLibraryViewModel.requestCover(it) },
                                    )
                                }

                                is LocalArtistRoute -> LocalLibraryDetailHost(
                                    isTop = localTop,
                                    onClose = { uiViewModel.popRoute() },
                                ) { requestClose ->
                                    LocalArtistDetailScreen(
                                        artistName = entry.artistName,
                                        tracks = localLibraryState.tracks,
                                        currentSongId = playerState.currentSong?.id,
                                        isTop = localTop,
                                        onClose = requestClose,
                                        onPlayAt = { list, idx ->
                                            playerViewModel.setQueue(list.map { it.toSong() }, idx)
                                        },
                                        onMore = { uiViewModel.setSongActionsVisible(true, it.toSong()) },
                                        onOpenAlbum = { uiViewModel.pushRoute(LocalAlbumRoute(it)) },
                                        // overview 只预览一段，装不下时「全部 ›」压对应子页
                                        onOpenAllSongs = {
                                            uiViewModel.pushRoute(LocalArtistSongsRoute(entry.artistName))
                                        },
                                        onOpenAllAlbums = {
                                            uiViewModel.pushRoute(LocalArtistAlbumsRoute(entry.artistName))
                                        },
                                        onRequestCover = { localLibraryViewModel.requestCover(it) },
                                    )
                                }

                                is LocalArtistSongsRoute -> LocalLibraryDetailHost(
                                    isTop = localTop,
                                    onClose = { uiViewModel.popRoute() },
                                ) { requestClose ->
                                    LocalArtistSongsScreen(
                                        artistName = entry.artistName,
                                        tracks = localLibraryState.tracks,
                                        currentSongId = playerState.currentSong?.id,
                                        isTop = localTop,
                                        onClose = requestClose,
                                        onPlayAt = { list, idx ->
                                            playerViewModel.setQueue(list.map { it.toSong() }, idx)
                                        },
                                        onMore = { uiViewModel.setSongActionsVisible(true, it.toSong()) },
                                        onRequestCover = { localLibraryViewModel.requestCover(it) },
                                    )
                                }

                                is LocalArtistAlbumsRoute -> LocalLibraryDetailHost(
                                    isTop = localTop,
                                    onClose = { uiViewModel.popRoute() },
                                ) { requestClose ->
                                    LocalArtistAlbumsScreen(
                                        artistName = entry.artistName,
                                        tracks = localLibraryState.tracks,
                                        isTop = localTop,
                                        onClose = requestClose,
                                        onOpenAlbum = { uiViewModel.pushRoute(LocalAlbumRoute(it)) },
                                    )
                                }

                                is LocalFolderRoute -> LocalLibraryDetailHost(
                                    isTop = localTop,
                                    onClose = { uiViewModel.popRoute() },
                                ) { requestClose ->
                                    LocalFolderDetailScreen(
                                        folderName = entry.folderName,
                                        tracks = localLibraryState.tracks,
                                        currentSongId = playerState.currentSong?.id,
                                        isTop = localTop,
                                        onClose = requestClose,
                                        onPlayAt = { list, idx ->
                                            playerViewModel.setQueue(list.map { it.toSong() }, idx)
                                        },
                                        onMore = { uiViewModel.setSongActionsVisible(true, it.toSong()) },
                                    )
                                }

                                // ── 外站全屏详情：链接解析进入 ──
                                // 以前是 `SearchScreenState` 上的一对局部 var，绕过整条栈；
                                // 现在压进 pageStack，返回键 / 层级 / 被播放页盖住全部自动生效。
                                //
                                // 和其它栈内页面一样套 `LocalOverlayFrame`：
                                // - 它提供 scrim + 150ms 滑入/滑出 + `closing` 防重入；
                                // - 它把自己的 `requestClose` 注册进 `PageCloseRelay`，于是返回键
                                //   和顶栏箭头走**同一条**关闭路径（先滑出、再 popRoute）。
                                // 这两页以前自己 `BackHandler { popRoute() }`，等于第二条独立出栈路径，
                                // 会在 `QobuzTokenSheet` / `KkboxAccountSheet`（在 `GlobalOverlays` 里
                                // 排在 `pageStack` 之前注册）打开时抢返回，把整页关掉。
                                is QobuzRoute -> LocalOverlayFrame(
                                    onClose = { uiViewModel.popRoute() },
                                ) { requestClose ->
                                    QobuzDetailScreen(
                                        qobuzViewModel = qobuzViewModel,
                                        playerViewModel = playerViewModel,
                                        downloadViewModel = downloadViewModel,
                                        onBack = requestClose,
                                        isTop = localTop,
                                        type = entry.type,
                                        id = entry.id,
                                    )
                                }

                                is KkboxRoute -> LocalOverlayFrame(
                                    onClose = { uiViewModel.popRoute() },
                                ) { requestClose ->
                                    KkboxDetailScreen(
                                        kkboxViewModel = kkboxViewModel,
                                        playerViewModel = playerViewModel,
                                        downloadViewModel = downloadViewModel,
                                        onBack = requestClose,
                                        isTop = localTop,
                                        type = entry.type,
                                        id = entry.id,
                                    )
                                }
                            }
                        }
                    }
                },
            )
            }

            // ── 菜单抽屉（RN zIndex 200） ──
            MenuDrawer(
                visible = uiState.drawerVisible,
                onClose = { uiViewModel.setDrawerVisible(false) },
                // 从抽屉进入子页面时**不收抽屉**：子页面声明在抽屉之后（z 序更高）会自然盖住抽屉，
                // 返回后仍停在抽屉里，方便连续改多项设置。
                onQobuzToken = { uiViewModel.setQobuzTokenVisible(true) },
                onAppleService = { uiViewModel.setAppleServiceVisible(true) },
                onKkboxAccount = { uiViewModel.setKkboxAccountVisible(true) },
                onDownloadQueue = { uiViewModel.setDownloadQueueVisible(true) },
                onMoreSettings = { uiViewModel.setMoreSettingsVisible(true) },
                cacheSize = cacheSize,                onClearCache = {
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
                batteryExempt = batteryExempt,
                onToggleBattery = { target ->
                    if (target) {
                        requestIgnoreBatteryOptimizations(ctx)
                    } else {
                        openBatteryOptimizationSettings(ctx)
                    }
                },
                saveDirPath = saveDirPath,
                saveDirLoading = false,
                onPickSaveDir = { dirPicker.launch(null) },
                onClearSaveDir = {
                    DownloadDirService.clearDownloadDir()
                    saveDirPath = null
                },
                onContact = { uiViewModel.setContactVisible(true) },
            )

            // ── 下载任务整页（声明在抽屉之后 → 盖住抽屉；抽屉不收，返回先关本页、再关抽屉） ──
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
                    // 仅移除任务行（失败态）—— 不能打"取消"标记，
                    // 否则以后重新下载同一首会被 consumeCancelledSong 永久跳过
                    downloadViewModel.removeTask(id)
                },
            )

            // ── Qobuz Token / KKBOX 账号管理浮层（抽屉入口；之前仅置位布尔、未挂载） ──
            AppleServiceSheet(
                visible = uiState.appleServiceVisible,
                onClose = { uiViewModel.setAppleServiceVisible(false) },
            )
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

            // ── 更多设置整页（抽屉入口；打开关闭交互与下载任务页一致） ──
            MoreSettingsScreen(
                visible = uiState.moreSettingsVisible,
                groups = moreSettingsGroups(
                    writeQobuzDownloadTranslation = writeQobuzDownloadTranslation,
                    onToggleWriteQobuzDownloadTranslation = {
                        MoreSettings.setWriteQobuzDownloadTranslation(it)
                    },
                    playQualityOf = { platform -> playQualityMap[platform] },
                    onPickPlayQuality = { platform, quality ->
                        MoreSettings.setPlayQuality(platform, quality)
                    },
                    writeEnhancedLyrics = writeEnhancedLyrics,
                    onToggleWriteEnhancedLyrics = { MoreSettings.setWriteEnhancedLyrics(it) },
                    saveLrcFile = saveLrcFile,
                    onToggleSaveLrcFile = { MoreSettings.setSaveLrcFile(it) },
                    // 输出矩阵：通路 + 该通路的开关（"能不能调音"由它们派生，不是用户选项）
                    outputPath = outputPath,
                    onPickOutputPath = { path ->
                        MoreSettings.setOutputPath(path)
                        // 选 USB 独占就趁这一刻要授权：等到播放时才申请，那一首会先落回系统通路
                        if (path == OutputPath.usbDirect) {
                            UsbAudioDirect.audioDevices(ctx).firstOrNull()?.let { device ->
                                UsbAudioDirect.requestPermission(ctx, device) {}
                            }
                        }
                    },
                    outputToggles = outputToggles,
                    onPickOutputToggles = { MoreSettings.setOutputToggles(it) },
                    tuningEnabled = tuningOn,
                    tuningEngine = tuningEngine,
                    onPickTuningEngine = { MoreSettings.setTuningEngine(it) },
                    onOpenTuning = { tuningVisible = true },
                    tuningSummary = when (tuningEngine) {
                        TuningEngine.none -> "不做任何 DSP"
                        TuningEngine.graphic -> "均衡器 · 10 段"
                        TuningEngine.parametric -> "PEQ · 32 段"
                        TuningEngine.mseb -> "MSEB · 9 滑块"
                    },
                    loudnessNormalization = loudnessNormalization,
                    onToggleLoudnessNormalization = { MoreSettings.setLoudnessNormalization(it) },
                    // 打开但没有标签时要说清"不调整" —— 否则用户只会觉得开关坏了
                    loudnessSummary = run {
                        val db = playerState.audioStatus?.replayGainDb ?: 0f
                        when {
                            !loudnessNormalization -> ""
                            db != 0f -> "本曲 ReplayGain %+.1f dB（已应用）".format(db)
                            else -> "本曲没有 ReplayGain 标签 → 不调整"
                        }
                    },
                    // DSD 入口：摘要只报「模式 + 目标率」，细节进独立页面
                    dsdSummary = dsdSummaryText(
                        dsdMode,
                        dsdPcmRates[DsdLevel.dsd64] ?: DSD_DEFAULT_PCM_RATE,
                    ),
                    onOpenDsdOutput = { uiViewModel.setDsdOutputVisible(true) },
                    effectivePath = playerState.audioStatus?.effectivePath,
                    downgradeLabel = playerState.audioStatus?.downgrade?.reason?.label,
                    onShowFollowSourceNotice = { followSourceNotice = true },
                    isBluetoothOutput = playerState.audioStatus?.route == AudioRoute.bluetooth,
                    bluetoothSummary = playerState.audioStatus?.let { s ->
                        bluetoothSummaryText(s)
                    }.orEmpty(),
                    sourceRate = playerState.audioStatus?.sourceRate ?: 0,
                    effectiveRate = playerState.audioStatus?.effectiveRate ?: 0,
                    onShowCapabilities = { capabilitiesVisible = true },
                    onShowDiagnostics = { diagnosticsVisible = true },
                    outputDetail = playerState.audioStatus?.let { s ->
                        buildList {
                            if (s.deviceName.isNotEmpty()) add(s.deviceName)
                            // 用 khzText 而不是 `/1000`：44.1 kHz 除下来会变成「44 kHz」
                            if (s.deviceMaxRate > 0) {
                                add("设备最高 ${khzText(s.deviceMaxRate)}")
                            } else if (s.nativeRate > 0) {
                                add("系统 ${khzText(s.nativeRate)}")
                            }
                            // **声明与实测并列**。这两个数经常不一致，而且不一致本身就说明问题：
                            // DAC 常在 profile 里声明 384k/768k，而 AudioTrack 受 AudioFormat
                            // 的 192k 上限约束，实际建不出来。只报一个数会让用户以为"支持却不用"。
                            //
                            // 只在**实测真的低于声明**时补这一句：相等是常态、更高的声明没探过
                            // 也不该说成"做不到"（懒探测还没轮到它）。
                            if (s.probedMaxRate > 0 && s.probedMaxRate < s.deviceMaxRate) {
                                add("实测最高 ${khzText(s.probedMaxRate)}")
                            }
                            if (s.sourceRate > 0) add("源 ${khzText(s.sourceRate)}")
                        }.joinToString(" · ")
                    }.orEmpty(),
                    lxPluginCount = lxPlugins.size,
                    onManageLxPlugins = { uiViewModel.setLxPluginManagerVisible(true) },
                ),
                onClose = { uiViewModel.setMoreSettingsVisible(false) },
            )

            TuningSheet(
                visible = tuningVisible,
                onClose = { tuningVisible = false },
            )

            OutputCapabilitiesSheet(
                visible = capabilitiesVisible,
                onClose = { capabilitiesVisible = false },
            )

            // 通用日志面板：应用内环形缓冲，不经过 logcat（本 ROM 会按配额丢应用日志）
            DiagnosticsSheet(
                visible = diagnosticsVisible,
                onClose = { diagnosticsVisible = false },
            )

            // ── 落雪插件管理页（声明在更多设置之后 → 盖住它；返回先关本页、再关设置页） ──
            LxPluginManagerSheet(
                visible = uiState.lxPluginManagerVisible,
                plugins = lxPlugins,
                emptyWarning = LxPluginRegistry.noUsablePlugin(),
                onClose = { uiViewModel.setLxPluginManagerVisible(false) },
                onSelect = { LxPluginRegistry.setActive(it) },
                onDelete = { id -> LxPluginRegistry.delete(id) },
                onImportClick = { lxImportChooserVisible = true },
            )

            // ── DSD 输出页（更多设置的子页）：声明在更多设置之后 → 盖住它，
            //    返回时先关本页再关设置页（`BACK_RULES` 里 DSD_OUTPUT 也排在 MORE_SETTINGS 之前）──
            DsdOutputSheet(
                visible = uiState.dsdOutputVisible,
                mode = dsdMode,
                rates = dsdPcmRates,
                // 上限是**设备属性**：用系统通路实测过的最高率（`probedMaxRate`，只读缓存）。
                // 还没探到（没播过 DSD）就是 0 → `dsdMaxPcmRate` 退回 176.4k 的保守值。
                // 首播一首 DSD 之后 `MediaPlayerBackend._dsdFullChainRate` 会探一次，
                // 缓存里有了值，这一页下次打开才可能看到 352.8k 可选。
                //
                // 判据必须包含**实测设备**，不能只看设置里的意图：`outputPath` 是常驻的，
                // 拔掉 DAC 它也不会变，光看它会让这一页一直挂着"最高 352.8k"。
                maxPcmRate = dsdMaxOutputRate(
                    isUsbRoute = playerState.audioStatus?.route == AudioRoute.usb,
                    exclusiveIntent = outputPath == OutputPath.usbDirect ||
                        playerState.audioStatus?.effectivePath == OutputPath.usbDirect,
                    level = DsdLevel.of(playerState.audioStatus?.sourceRate ?: 0),
                    probedMaxRate = playerState.audioStatus?.probedMaxRate ?: 0,
                ),
                // 当前在播的到底是哪一级 DSD（非 DSD 时页面会明说"本页设置不参与"）
                currentLevel = DsdLevel.of(playerState.audioStatus?.sourceRate ?: 0),
                // 档位是**意图**，实际率由 `dsdPcmRateOrFallback` 夹取 —— 不一致时
                // 必须在那一行说清，否则用户只看到"选着 352.8k，声音却不在那一档"。
                currentRateNotice = playerState.audioStatus?.let { s ->
                    DsdLevel.of(s.sourceRate)?.let { level ->
                        dsdRateNotice(
                            wanted = MoreSettings.dsdPcmRateOf(level),
                            actual = s.dsdTargetRate,
                            isUsbRoute = s.route == AudioRoute.usb,
                            exclusiveIntent = outputPath == OutputPath.usbDirect ||
                                s.effectivePath == OutputPath.usbDirect,
                        )?.text
                    }
                },
                isBluetooth = playerState.audioStatus?.route == AudioRoute.bluetooth,
                // 「跟随系统」那一项显示的目标率（`systemMixRateFor` 的实际推断值）
                systemRate = playerState.audioStatus?.let { s ->
                    systemMixRateFor(
                        route = s.route,
                        nativeRate = s.nativeRate,
                        deviceRates = s.deviceSampleRates,
                        codecRate = s.bluetoothRate,
                        sourceRate = s.sourceRate,
                    )
                } ?: 0,
                currentChain = playerState.audioStatus?.let { s -> chainText(s) }.orEmpty(),
                // DoP / Native 的硬前提：通路必须逐字节保真（mixer 会改写帧标记）。
                // 这里用**实测**通路，不用设置里的意图 —— 降级后意图仍是"意愿"。
                exclusiveReady = playerState.audioStatus?.effectivePath == OutputPath.usbDirect,
                usbDacReady = playerState.audioStatus?.dsdRawAlt == true,
                onPickMode = { MoreSettings.setDsdMode(it) },
                onPickRate = { level, rate -> MoreSettings.setDsdPcmRate(level, rate) },
                onClose = { uiViewModel.setDsdOutputVisible(false) },
            )

            // 「重采样目标 / 输出位深」上的 ⓘ（DSD 例外说明）已随「自选采样率」一起删除：
            // 那段文案解释的是"为什么 DSD 不走这个档位"，档位本身不存在了，
            // 留着只会是一个永远打不开的弹窗。

            // 「强制按源采样率输出」旁的 ⓘ：那一项的优势与代价。
            // 用 Dialog（自带返回处理），所以不必再往 `BACK_RULES` 里加一条。
            if (followSourceNotice) {
                FollowSourceNoticeDialog(onDismiss = { followSourceNotice = false })
            }

            // 输出降级：只在**稳定成立**时弹一次。
            //
            // 必须带稳定期：插上 USB DAC 的第一拍 `AudioManager` 已经报了设备、而
            // `UsbManager` 还没更新，`UacEngine.open` 会读到"没有设备"并回落 AudioTrack，
            // 那一刻降级是真的（确实在系统通路上出声），但几百毫秒后就自动恢复了 ——
            // 为它弹窗只会让人以为插坏了。恢复时 `dedupeKey` 变 null，本 effect 被取消，
            // 弹窗也就不会出现。需要用户点授权（> 稳定期）的情况仍会弹，那正是该弹的。
            val downgrade = playerState.audioStatus?.downgrade
            var shownDowngrade by remember { mutableStateOf<DowngradeNotice?>(null) }
            var shownKey by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(downgrade?.dedupeKey) {
                val key = downgrade?.dedupeKey
                if (key == null) {
                    shownKey = null
                    return@LaunchedEffect
                }
                if (key == shownKey) return@LaunchedEffect
                delay(DOWNGRADE_SETTLE_MS)
                shownKey = key
                shownDowngrade = downgrade
            }
            shownDowngrade?.let { notice ->
                OutputDowngradeDialog(notice = notice, onDismiss = { shownDowngrade = null })
            }

            // ── 底栏槽位（旧 MiniPlayerBar 原位：MenuDrawer 之后、PlayerOverlay 之前）──
            // 常驻**同一颗胶囊**：主页面（搜索 / 本地曲库）是「底栏态」，其余二级页面
            // （抽屉 / 各类 sheet / 详情页 / 播放页之下）自动 morph 成「迷你播放器态」——
            // 封面左滑、左右 tab 淡出、标题-歌手-播放-进度淡入，反向即原路恢复。
            // 声明在本槽位 → 之后声明的浮层（播放页、各 sheet、详情路由）天然把它盖住；
            // 例外：Qobuz Token / KKBOX 账号两个配置页声明在**本槽位之前**（见上方），
            // 于是迷你播放器浮在它们之上常驻可见（弹窗类 sheet 仍在其后声明、仍盖住它）。
            if (!hideBottomBar) {
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
                    // 传流不传值：本函数（1598 行）绝不读 progress 的 value，
                    // 否则 5 Hz 的进度会把整棵树的重组全部拖起来（见 PlaybackProgress 的 KDoc）
                    progress = playerViewModel.progress,
                    onOpenPlayer = { uiViewModel.setPlayerOverlayVisible(true) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter),
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
                    progress = playerViewModel.progress,
                    palette = palette,
                    fallbackCoverUrl = colorState.currentCoverUrl,
                    onClose = { uiViewModel.setPlayerOverlayVisible(false) },
                    onSeek = { seconds -> playerViewModel.seekToSeconds(seconds) },
                    onDragging = { ratio -> uiViewModel.setDragging(true, seekRatio = ratio) },
                    onSeekEnd = { ratio ->
                        // 事件回调里读一次 value 不建立组合依赖（与组合期的读取不同）
                        playerViewModel.seekToSeconds(ratio * playerViewModel.progress.value.duration)
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
                    onOpenSongActions = { currentSong?.let { dl.openActions(it) } },
                    onSetVolume = { volume -> playerViewModel.setVolume(volume) },
                    onToggleTranslation = {
                        uiViewModel.setShowLyricsTranslation(!uiState.showLyricsTranslation)
                    },
                    onSelectTranslateLang = { lang ->
                        uiViewModel.setShowLyricsTranslation(true)
                        playerViewModel.setSongTranslateLang(lang)
                    },
                    onToggleWordByWord = {
                        uiViewModel.setShowWordByWord(!uiState.showWordByWord)
                    },
                    // 歌词高亮行索引回写 UiState（对齐 Flutter `setCurrentLineIndex`）
                    onLineIndexChange = { uiViewModel.setCurrentLineIndex(it) },
                    // 封面变化：只记地址；色板由本文件的 LaunchedEffect(themeCoverUrl) 统一算，
                    // 一处在算、一份结果，不再有「进过播放页才有色」的时序问题。
                    onCoverChanged = { url ->
                        colorViewModel.setCurrentCoverUrl(url)
                    },
                )
            }

            // ── 歌曲操作面板（⋮，内部含多歌手选择） ──
            SongActionsSheet(
                visible = uiState.songActionsVisible,
                song = uiState.songActionsSong ?: currentSong,
                onClose = { uiViewModel.setSongActionsVisible(false) },
                // 「加入到歌单」的候选：**按歌的平台分边** —— 在线歌只列 online 歌单，
                // 本地歌只列 local 歌单（混边在数据层就不允许），并预计算「这首歌在不在里面」。
                // 一次 HashSet，不在每行里现算 key（20 个歌单 × 上千首时那是几万次字符串拼接）。
                playlistOptions = remember(favoriteSnapshot, uiState.songActionsSong) {
                    val target = uiState.songActionsSong ?: currentSong
                    if (target == null) {
                        emptyList()
                    } else {
                        val isLocal =
                            target.platform == com.annieplayer.annie_music.kt.data.model.MusicPlatform.local
                        val wantedScope = if (isLocal) {
                            com.annieplayer.annie_music.kt.data.favorites.PlaylistScope.local
                        } else {
                            com.annieplayer.annie_music.kt.data.favorites.PlaylistScope.online
                        }
                        val songKey =
                            com.annieplayer.annie_music.kt.data.favorites.FavoriteKeys.song(target)
                        favoriteSnapshot.userPlaylists
                            .filter { it.scope == wantedScope }
                            .map { pl ->
                                com.annieplayer.annie_music.kt.widgets.SongPlaylistOption(
                                    playlistId = pl.id,
                                    name = pl.name,
                                    trackCount = pl.songs.size,
                                    alreadyContains = pl.songs.any {
                                        com.annieplayer.annie_music.kt.data.favorites
                                            .FavoriteKeys.song(it) == songKey
                                    },
                                )
                            }
                    }
                },
                // 加入后面板**不关**：上面那份 options 会随 snapshot 重组，该行变成「已加入」
                onAddToPlaylist = { playlistId ->
                    (uiState.songActionsSong ?: currentSong)?.let { song ->
                        favoriteViewModel.addSongToUserPlaylist(playlistId, song)
                    }
                },
                // 「我喜欢」与歌单共用同一个二级面板：这里给的是「是否已收藏 + 那一边共几首」。
                // 按 `Song.platform` 分边，与 `playlistOptions` 同一条判据（本地歌进本地喜欢）。
                favoriteSelected = remember(favoriteSnapshot, uiState.songActionsSong) {
                    val target = uiState.songActionsSong ?: currentSong
                    target != null && favoriteSnapshot.songs.any {
                        it.key == com.annieplayer.annie_music.kt.data.favorites
                            .FavoriteKeys.song(target)
                    }
                },
                favoriteCount = remember(favoriteSnapshot, uiState.songActionsSong) {
                    val target = uiState.songActionsSong ?: currentSong
                    val isLocal =
                        target?.platform == com.annieplayer.annie_music.kt.data.model.MusicPlatform.local
                    favoriteSnapshot.songsIn(
                        if (isLocal) {
                            com.annieplayer.annie_music.kt.data.favorites.FavoriteMode.local
                        } else {
                            com.annieplayer.annie_music.kt.data.favorites.FavoriteMode.online
                        },
                    ).size
                },
                // 这是一个开关：未收藏 → 收藏，已收藏（行显示「已收藏」）再点 → 取消收藏
                onAddToFavorites = {
                    (uiState.songActionsSong ?: currentSong)?.let { favoriteViewModel.toggleSong(it) }
                },
                // 「从本歌单移除」只在从自建歌单详情页打开面板时才有
                onRemoveFromPlaylist = uiState.songActionsFromUserPlaylistId?.let { playlistId ->
                    {
                        val song = uiState.songActionsSong ?: currentSong
                        if (song != null) {
                            favoriteViewModel.removeSongFromUserPlaylist(playlistId, song)
                        }
                    }
                },
                onDownload = { song -> uiViewModel.setPicker(true, song = song) },
                onOpenArtist = { artist ->
                    val shown = uiState.songActionsSong ?: currentSong
                    if (shown?.platform == com.annieplayer.annie_music.kt.data.model.MusicPlatform.local) {
                        // 本地歌没有平台 id，也没有云端歌手页：直接压本地歌手详情。
                        // **必须先收起播放页**（与云端分支的 DetailNavigator 一致），
                        // 否则从播放页点 ⋮ 跳转会新页面被播放页整屏挡住。
                        uiViewModel.setSongActionsVisible(false)
                        uiViewModel.setPlayerOverlayVisible(false)
                        uiViewModel.pushRoute(LocalArtistRoute(artist.name))
                    } else {
                        detailNavigator.openArtistDetail(artist)
                    }
                },
                // 本地歌曲「删除」：⋮ 面板已经自己收起了，这里只把目标交给 UiState，
                // 由紧跟其后的 LocalDeleteModal 弹确认框（面板内部弹会被一起收掉）。
                onDelete = { song -> uiViewModel.setLocalDeleteTarget(song) },
                onOpenAlbum = { song ->
                    if (song.platform == com.annieplayer.annie_music.kt.data.model.MusicPlatform.local) {                        // 本地专辑的键是「专辑 · 专辑艺术家」，与 albumGroupKey 同源
                        // 同样先收起播放页，再压栈
                        uiViewModel.setSongActionsVisible(false)
                        uiViewModel.setPlayerOverlayVisible(false)
                        uiViewModel.pushRoute(
                            LocalAlbumRoute(
                                com.annieplayer.annie_music.kt.data.local.albumGroupKey(
                                    com.annieplayer.annie_music.kt.data.local.LocalTrackMeta(
                                        path = song.id.removePrefix("local:"),
                                        title = song.title,
                                        artist = song.artist,
                                        album = song.album ?: "",
                                        albumArtist = song.albumArtist,
                                    ),
                                ),
                            ),
                        )
                    } else {
                        detailNavigator.openAlbumDetail(song)
                    }
                },
                // 「跳转到歌单」只对「确实属于那个歌单的歌」出现 —— 三个条件同时成立：
                //   1. 队列有来源歌单（`PlayerState.queuePlaylist`，从歌单开播时写入、可跨重启恢复）；
                //   2. 被点的这首歌不是本地文件（本地曲库没有歌单语义）；
                //   3. 这首歌**真的在这份队列里** —— 队列就是那个歌单的曲目，所以在播放页点当前歌、
                //      或在歌单里点任一首都命中；而从搜索结果 / 专辑 / 歌手点别的歌不会命中。
                // 之前只判了第 1 条，于是重启恢复队列后，**所有**歌（含本地歌）的 ⋮ 都会冒出这一行。
                playlistTarget = playerState.queuePlaylist?.takeIf { playlist ->
                    val shown = uiState.songActionsSong ?: currentSong
                    shown != null &&
                        shown.platform != com.annieplayer.annie_music.kt.data.model.MusicPlatform.local &&
                        playerState.queue.any { it.id == shown.id } &&
                        playlist.id.isNotEmpty()
                },
                onOpenPlaylist = { playlist ->
                    uiViewModel.setSongActionsVisible(false)
                    uiViewModel.setPlayerOverlayVisible(false)
                    val top = uiState.pageStack.lastOrNull()
                    val alreadyThere = top is PlaylistRoute && top.data.id == playlist.id
                    if (!alreadyThere) {
                        uiViewModel.pushRoute(PlaylistRoute(playlist))
                    }
                },
            )

            // ── 本地歌曲删除确认（⋮ →「删除」，此时 ⋮ 面板已收起） ──
            // 必须在 SongActionsSheet **之后**声明：注册逆序派发 → 它先吃返回。
            //
            // 两处刻意的写法：
            // 1) 组件**常驻组合**（不写进 `if (target != null)`）：目标置空的同一帧若把组件卸载，
            //    160ms 的退场动画根本没机会播，看起来就是"啪一下消失"；
            // 2) 数据取「当前值 ?: 上一次的值」：退场那一小段 `UiState` 里已经是 null 了，
            //    卡片还得按原内容把动画演完。
            val deleteTarget = uiState.localDeleteTarget
            val deleteSnapshot = remember { mutableStateOf<com.annieplayer.annie_music.kt.data.model.Song?>(null) }
            LaunchedEffect(deleteTarget) {
                if (deleteTarget != null) deleteSnapshot.value = deleteTarget
            }
            val modalSong = deleteTarget ?: deleteSnapshot.value
            if (modalSong != null) {
                val deletePath = modalSong.id.removePrefix("local:")
                // 标题 / 歌手 / 专辑 / 体积优先用曲库索引里的值（Song 可能是很久以前构造的快照）；
                // 有没有文件是磁盘事实，每次打开 stat 一次。
                val deleteMeta = remember(deletePath) {
                    if (deletePath.isEmpty()) null else LocalLibraryStore.trackOf(deletePath)
                }
                val deleteFileExists = remember(deletePath) {
                    deletePath.isNotEmpty() && File(deletePath).isFile
                }
                LocalDeleteModal(
                    visible = deleteTarget != null,
                    songTitle = deleteMeta?.title?.ifBlank { null } ?: modalSong.title,
                    songSubtitle = listOfNotNull(
                        (deleteMeta?.artist?.ifBlank { null } ?: modalSong.artist)
                            .takeIf { it.isNotBlank() },
                        deleteMeta?.album?.ifBlank { null } ?: modalSong.album,
                    ).joinToString(" · "),
                    filePath = deletePath.ifEmpty { "—" },
                    fileSizeText = deleteMeta?.sizeText.orEmpty(),
                    fileExists = deleteFileExists,
                    onConfirm = { withFile ->
                        // 队列里还挂着它就整条摘掉。三件事都在
                        // `removeSongsFromQueue` 里一起做：App 队列 / 引擎自管队列 / 落盘现场。
                        // 正在播的那首会先自动切下一首；队列里只剩它一首则清空并停播。
                        playerViewModel.removeSongsFromQueue(listOf(modalSong.id))
                        localLibraryViewModel.deleteTrack(deletePath, withFile)
                        uiViewModel.setLocalDeleteTarget(null)
                    },
                    onClose = { uiViewModel.setLocalDeleteTarget(null) },
                )
            }

            // ── 下载音质选择（单曲） ──
            QualityPickerSheet(
                visible = uiState.pickerVisible,
                        qualities = uiState.pickerSong?.qualities ?: listOf(Quality.k128k),
                        sizeOf = { q -> uiState.pickerSong?.qualitySizes?.get(q) },
                        labelOf = { q ->
                            uiState.pickerSong?.platform?.let { playQualityLabelOf(it, q) } ?: q.label
                        },
                onSelect = { quality ->
                    uiViewModel.setPicker(false)
                    uiState.pickerSong?.let { dl.downloadSingle(it, quality) }
                },
                onCancel = { uiViewModel.setPicker(false) },
            )

            // ── 下载音质选择（批量） ──
            QualityPickerSheet(
                        visible = dl.batchPickerVisible,
                        qualities = batchDownloadQualities(selection.selectedSongs),
                        sizeOf = { q ->
                            if (selection.selectedSongs.size == 1) {
                                selection.selectedSongs.first().qualitySizes[q]
                            } else {
                                null
                            }
                        },
                        labelOf = { q ->
                            val platforms = selection.selectedSongs.map { it.platform }.distinct()
                            if (platforms.size == 1) {
                                playQualityLabelOf(platforms.first(), q)
                            } else {
                                q.label
                            }
                        },
                onSelect = { quality ->
                    dl.downloadBatch(selection.selectedSongs, quality) { selectionViewModel.clearAll() }
                },
                onCancel = { dl.closeBatchPicker() },
            )

            // ── 详情页音质选择（专辑 / 歌手 / 歌单 sheet 提供内容） ──
            // 声明在 AppBottomBar 之后 → 面板盖住迷你播放条，效果与搜索页一致（对齐 RENDER_ORDER）。
            detailPickerHost.slot?.invoke()

            // ── 播放队列浮层 ──
            QueueSheet(
                visible = uiState.queueVisible,
                queue = playerState.queue,
                currentIndex = playerState.currentIndex,
                loopMode = playerState.loopMode,
                onClose = { uiViewModel.setQueueVisible(false) },
                onPlayIndex = { index ->
                    playerViewModel.playIndex(index)
                    uiViewModel.setQueueVisible(false)
                },
                onRemove = { index -> playerViewModel.removeFromQueue(index) },
                // 拖拽排序：只重排队列 + 修下标，不重建播放器队列（当前曲不中断）
                onMove = { from, to -> playerViewModel.moveInQueue(from, to) },
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
                            // 播放缓存分两条路清：Media3 那份必须走 Cache API（目录连索引被 SimpleCache
                            // 占着），KKBOX 解密目录由 AssetCache 删；对用户是同一项。
                            if (CacheCategory.PLAYBACK in cacheSelected) {
                                playerViewModel.clearPlaybackCache()
                            }
                            AssetCache.clearCacheCategories(cacheSelected)
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

            // ── 下载开始提示：安卓原生 Toast（约 2 秒自动消失，不再像以前那样常驻到下载结束） ──
            LaunchedEffect(toastId) {
                if (toastId != null) {
                    val title = downloadTasks[toastId]?.title ?: "歌曲"
                    Toast.makeText(ctx, "正在下载 $title…", Toast.LENGTH_SHORT).show()
                }
            }

            // Qobuz / KKBOX 全屏详情页已并入 `pageStack`（见上方 GlobalOverlays 的
            // pageStack 槽位里 `is QobuzRoute` / `is KkboxRoute` 两个分支），
            // 不再在这里单独声明 —— 那样会绕过返回裁决与层级判断。

            // ── 下载译文语言选择（Qobuz 单曲下载第二步） ──
            // 【必须声明在最上层】：下载可能从播放页 / ⋮ 面板 / 详情页发起，
            // 若被这些浮层盖住，用户看到的仍是下层界面，点到的是本层看不见的遮罩 →
            // 触发「不写入翻译」并让下载继续，表现为「没选就把流程跑完了」。
            TranslatePickerSheet(
                visible = translatePickerVisible,
                langs = translatePickerLangs,
                songTitle = translatePickerSongTitle,
                onSelect = { TranslatePicker.resolve(it) },
                onCancel = { TranslatePicker.resolve(null) },
            )

            // 「插件管理」行右侧「+」：先选导入方式（在线 / 本地），再走各自流程
            if (lxImportChooserVisible) {
                AlertDialog(
                    onDismissRequest = { lxImportChooserVisible = false },
                    containerColor = AppColors.surface,
                    title = { Text(text = "导入插件", style = AnnieTypography.dialogTitle) },
                    text = {
                        Text(
                            text = "在线导入：粘贴 http/https 直链；本地导入：从文件管理器选 .js 文件。",
                            style = AnnieTypography.caption,
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                lxImportChooserVisible = false
                                lxImportUrlVisible = true
                            },
                        ) {
                            Text(text = "在线导入", color = AppColors.primary)
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                lxImportChooserVisible = false
                                lxLocalPicker.launch(arrayOf("*/*"))
                            },
                        ) {
                            Text(text = "本地导入", color = AppColors.textSecondary)
                        }
                    },
                )
            }

            LxImportUrlDialog(
                visible = lxImportUrlVisible,
                importing = lxImporting,
                onDismiss = { lxImportUrlVisible = false },
                onConfirm = { url ->
                    scope.launch {
                        lxImporting = true
                        try {
                            val plugin = LxPluginRegistry.importOnline(url)
                            Toast.makeText(ctx, "已导入：${plugin.name}", Toast.LENGTH_SHORT).show()
                            lxImportUrlVisible = false
                        } catch (e: Exception) {
                            Log.w("LxPlugin", "在线导入失败", e)
                            Toast.makeText(ctx, "导入失败：${e.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
                        } finally {
                            lxImporting = false
                        }
                    }
                },
            )

            // ── 退出确认（第一次返回叫出弹窗） ──
            // 弹窗是 Dialog，返回键由它自己的 `onDismissRequest` 接住。
            //
            // **语义：返回 = 取消**。之前这里写成「返回就直接退出」，于是「第一次返回弹窗、第二次
            // 返回秒退」—— 用户根本没机会点「取消」，等于弹窗白弹。现在：
            //   · 返回键 → onDismissRequest → 只关窗（取消）
            //   · 点窗外 → 已禁用（误触代价太大）
            //   · 只有点「退出」才真的退出
            if (exitConfirm) {
                AlertDialog(
                    onDismissRequest = { exitConfirm = false },
                    properties = DialogProperties(dismissOnClickOutside = false),
                    containerColor = AppColors.surface,
                    title = {
                        Text(text = "退出安妮音乐盒？", style = AnnieTypography.dialogTitle)
                    },
                    text = {
                        Text(
                            text = "退出会停止播放与下载，并结束全部后台进程（通知栏的播放卡片也会消失）。",
                            style = AnnieTypography.caption,
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                exitConfirm = false
                                exitAppCompletely(ctx)
                            },
                        ) {
                            Text(text = "退出", color = AppColors.danger, fontWeight = AppFontWeight.semiBold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { exitConfirm = false }) {
                            Text(text = "取消", color = AppColors.textTertiary)
                        }
                    },
                )
            }
        }
        }   // CompositionLocalProvider（氛围主题色）闭合
    }
    }
}
