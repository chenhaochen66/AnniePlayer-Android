package com.annieplayer.annie_music.kt.features.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.annieplayer.annie_music.kt.core.audio.AnnieAudioHolder
import com.annieplayer.annie_music.kt.core.audio.AudioOutputController
import com.annieplayer.annie_music.kt.core.audio.AudioOutputMode
import com.annieplayer.annie_music.kt.core.audio.AudioOutputStatus
import com.annieplayer.annie_music.kt.core.audio.AudioRoute
import com.annieplayer.annie_music.kt.core.audio.AudioTone
import com.annieplayer.annie_music.kt.core.audio.khzText
import com.annieplayer.annie_music.kt.core.audio.outputPillText
import com.annieplayer.annie_music.kt.core.state.PlaybackProgress
import com.annieplayer.annie_music.kt.core.state.PlayerState
import com.annieplayer.annie_music.kt.core.state.UiState
import com.annieplayer.annie_music.kt.data.model.AudioInfo
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.ui.theme.ColorPalette
import com.annieplayer.annie_music.kt.widgets.MovingText
import com.annieplayer.annie_music.kt.widgets.MusicNoteGlyph
import com.annieplayer.annie_music.kt.widgets.clickableNoRipple
import kotlin.math.max
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 顶栏按钮命中区（Flutter `SizedBox(width: 34, height: 34)`） */
private val PlayerTopBarButtonSize = 34.dp

/** 拖拽条尺寸（Flutter `Container(width: 36, height: 4)`） */
private val PlayerDragBarWidth = 36.dp

/** 封面背景模糊半径（Flutter `ImageFilter.blur(sigmaX: 60, sigmaY: 60)`） */
private val PlayerBackdropBlur = 60.dp

/** 封面色块背景的高斯模糊半径（软化色块边界） */
private val PlayerGridBlur = 48.dp

/** 音频信息胶囊内圆盘图标盒边长 */
private val PlayerAudioInfoIconBox = 22.dp

/**
 * 三段之间的弹性间隔：正常档吃掉剩余高度（三等分）；滚动档没有可分配高度，
 * 退化成固定间距（`weight` 在无限高约束下不成立）。
 */
@Composable
private fun ColumnScope.PlayerElasticSpacer(elastic: Boolean) {
    if (elastic) {
        Spacer(modifier = Modifier.weight(1f))
    } else {
        Spacer(modifier = Modifier.height(AppSpacing.sp12))
    }
}

/**
 * 全屏播放页（1:1 移植 Flutter `lib/features/player/player_screen.dart`）。
 *
 * 结构（自下而上）：
 * 1. 封面模糊背景（60 模糊）+ 45% 黑色遮罩（[AppColors.scrim50]；API<31 时模糊自动降级为原图）。
 * 2. 拖拽条 → 顶栏（收起按钮 + 「歌曲 / 歌词」分段切换）。
 * 3. 待下载条（有选中歌曲时显示，对齐首页 `_PendingDownloadBar`）。
 * 4. 翻页区（0=封面 / 1=歌词，250ms + easeOut，对齐 Flutter `animateToPage`）。
 * 5. 信息行（跑马灯标题 + 译开关 + ⋮）→ 歌手名 → 进度条 → 控制栏 → 音量条。
 * 6. 字号面板（底部浮层，「歌词字号」5 档）。
 *
 * 本组件是**纯展示层**：所有状态迁移（播放 / 拖动 seek / 循环模式切换 / 音量写入）
 * 都由宿主通过回调执行，与 Dart 版各 `ref.read(...).notifier.xxx()` 调用点一一对应。
 */
@Composable
fun PlayerScreen(
    player: PlayerState,
    ui: UiState,
    /**
     * 播放进度流。**刻意不在这里 `.value`**：本组件不直接渲染进度，进度只由
     * [PlayerProgressBarHost]（进度条）和歌词页两个叶子订阅，避免 5 Hz 的重组
     * 蔓延到整个播放页（封面 pager / 控制栏 / 音量条）。
     */
    progress: StateFlow<PlaybackProgress>,
    palette: ColorPalette,
    onClose: () -> Unit,
    onSeek: (Double) -> Unit,
    onDragging: (Double) -> Unit,
    onSeekEnd: (Double) -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onCycleLoopMode: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenSongActions: () -> Unit,
    onSetVolume: (Double) -> Unit,
    onToggleTranslation: () -> Unit,
    onSelectTranslateLang: (String) -> Unit,
    onToggleWordByWord: () -> Unit,
    modifier: Modifier = Modifier,
    coverUrl: String = "",
    fallbackCoverUrl: String = "",
    onCoverChanged: (String) -> Unit = {},
    pendingDownloadCount: Int = 0,
    pendingDownloading: Boolean = false,
    onPendingView: () -> Unit = {},
    onPendingClear: () -> Unit = {},
    onPendingBatch: () -> Unit = {},
    onLineIndexChange: (Int) -> Unit = {},
) {
    val song: Song? = player.currentSong
    if (song == null) {
        Box(
            modifier = modifier.fillMaxSize().background(palette.background),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "暂无播放歌曲", color = AppColors.white38, fontSize = AppFontSize.base)
        }
        return
    }

    // 切歌时通知宿主用该封面地址做动态取色（对齐 Flutter `_loadCover` -> `_applyDynamicColors`）
    val displayCover = coverUrl.ifEmpty { song.cover }
    val displayFallback = fallbackCoverUrl.ifEmpty { song.cover }
    LaunchedEffect(song.id, displayCover) { onCoverChanged(displayCover) }

    // 封面进入动画：切歌时 500ms 淡入 + 轻微放大
    val coverEntry = remember(song.id) { Animatable(0f) }
    LaunchedEffect(song.id) {
        coverEntry.snapTo(0f)
        coverEntry.animateTo(1f, tween(durationMillis = AppDuration.fade, easing = AppEasing.easeOut))
    }

    var coverFailed by remember(song.id) { mutableStateOf(false) }
    var fontScaleVisible by remember { mutableStateOf(false) }
    var fontScale by remember { mutableFloatStateOf(0.75f) }
    var deviceSheetVisible by remember { mutableStateOf(false) }
    var deviceList by remember { mutableStateOf<List<AudioOutputController.DeviceInfo>>(emptyList()) }

    LaunchedEffect(deviceSheetVisible) {
        if (deviceSheetVisible) {
            deviceList = AnnieAudioHolder.audioOutput?.listOutputDevices().orEmpty()
        }
    }

    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    val pageIndex = pagerState.currentPage

    BoxWithConstraints(modifier = modifier.fillMaxSize().background(palette.background)) {
        val screenW = maxWidth
        val screenH = maxHeight
        val density = LocalDensity.current
        // 固定块高度运行期实测（字号放大后文本行会变高），首帧用估值顶上
        val fixedFallback = remember(density.fontScale) { PlayerFixedHeights.estimate(density.fontScale) }
        val blockHeights = remember { mutableStateMapOf<String, Int>() }
        // 系统栏内边距：statusBarsPadding + navigationBarsPadding 两条修饰符的实际耗用
        val barsHeight = with(density) {
            (WindowInsets.statusBars.getTop(this) + WindowInsets.navigationBars.getBottom(this)).toDp()
        }
        fun block(tag: String, fallback: Dp): Dp =
            blockHeights[tag]?.let { with(density) { it.toDp() } } ?: fallback

        val fixed = PlayerFixedHeights(
            top = barsHeight + block("top", fixedFallback.top),
            title = block("title", fixedFallback.title),
            artist = block("artist", fixedFallback.artist),
            progress = block("progress", fixedFallback.progress),
            controls = block("controls", fixedFallback.controls),
            volume = block("volume", fixedFallback.volume),
        )
        // 封面尺寸与分页区高度由 (W,H,固定块) 求解，见 PlayerPageGeometry：
        // 正常机型结果与旧公式一致，只有短屏才压缩分页区 / 收小封面 / 转紧凑档
        val geometry = playerPageGeometry(screenW, screenH, fixed)
        val coverSize = geometry.coverSize
        val pagerHeight = geometry.pagerHeight
        val scrollState = rememberScrollState()

        // ── 封面取色块背景：N×N 平铺，色块位置与封面各区域一一对应；切歌淡入变色 ──
        //
        // 注：播放页刻意**不用**其它页面那套 Aurora Field。
        // 这里要的是「封面的空间结构能被认出来」（大封面 + 满屏氛围 + 网格铺色是
        // 播放页自己的视觉语言），而 Aurora 是刻意抹掉结构的。
        if (palette.coverGridDim > 0 &&
            palette.coverGrid.size >= palette.coverGridDim * palette.coverGridDim
        ) {
            PlayerCoverGridBackdrop(
                grid = palette.coverGrid,
                dim = palette.coverGridDim,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(modifier = Modifier.fillMaxSize().background(palette.primary))
        }
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f)))

        // 窗口小到固定块都排不下时整页改为可滚动：这是「任意尺寸都不叠」的最后一道兜底。
        // 非滚动档保持原来那条 modifier 链（fillMaxSize → padding），改顺序会挪动底部栈的取整位置。
        val columnModifier = if (geometry.scroll) {
            Modifier.fillMaxWidth().verticalScroll(scrollState)
                .statusBarsPadding()
                .navigationBarsPadding()
        } else {
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
        }
        Column(modifier = columnModifier) {
            // 拖拽条 + 顶栏 + 待下载条整块实测：待下载条会临时增高，预算必须跟着它走
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { blockHeights["top"] = it.height },
            ) {
            // ── 拖拽条（对齐 RN FullPlayer dragBar） ──
            Spacer(modifier = Modifier.height(AppSpacing.sp4))
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(width = PlayerDragBarWidth, height = AppSpacing.sp4)
                        .background(AppColors.whiteA25, RoundedCornerShape(AppRadius.r2)),
                )
            }
            Spacer(modifier = Modifier.height(AppSpacing.sp2))

            // ── 顶栏：收起 + 歌曲/歌词切换 ──
            PlayerTopBar(
                pageIndex = pageIndex,
                onPageSelected = { index ->
                    scope.launch {
                        pagerState.animateScrollToPage(
                            page = index,
                            animationSpec = tween(
                                durationMillis = AppDuration.lyricPage,
                                easing = AppEasing.easeOut,
                            ),
                        )
                    }
                },
                deviceName = player.audioStatus?.deviceName.orEmpty(),
                deviceRoute = player.audioStatus?.route ?: AudioRoute.other,
                onOpenDevices = { deviceSheetVisible = true },
                onClose = onClose,
            )

            // ── 待下载条（有选中歌曲时） ──
            if (pendingDownloadCount > 0) {
                PlayerPendingDownloadBar(
                    count = pendingDownloadCount,
                    batchDownloading = pendingDownloading,
                    onView = onPendingView,
                    onClear = onPendingClear,
                    onBatch = onPendingBatch,
                )
            }
            }

            // ── 封面 / 歌词翻页 ──
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().height(pagerHeight),
                // 预构建相邻页，使歌词页在切页前完成测量与定位（对齐 allowImplicitScrolling）
                beyondViewportPageCount = 1,
            ) { page ->
                if (page == 0) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .graphicsLayer {
                                    alpha = coverEntry.value
                                    val s = 0.94f + 0.06f * coverEntry.value
                                    scaleX = s
                                    scaleY = s
                                },
                        ) {
                            PlayerCoverImage(
                                coverUrl = displayCover,
                                fallbackUrl = displayFallback,
                                size = coverSize,
                                failed = coverFailed,
                                onFailed = { coverFailed = true },
                            )
                        }
                        if (geometry.tier != PlayerPageTier.noChip) {
                            PlayerAudioInfoChip(
                                info = player.audioInfo,
                                status = player.audioStatus,
                                maxWidth = screenW - AppSpacing.screenPaddingH * 2,
                                resetKey = song.id to ui.playerOverlayVisible,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth()
                                    .padding(
                                        start = AppSpacing.screenPaddingH,
                                        end = AppSpacing.screenPaddingH,
                                        bottom = geometry.chipBottomInset,
                                    ),
                            )
                        }
                    }
                } else {
                    PlayerLyricsView(
                        songId = song.id,
                        lyric = song.lyric,
                        tlyric = song.tlyric,
                        wordLyric = song.wordLyric,
                        isPlaying = player.isPlaying,
                        progress = progress,
                        isDragging = ui.isDragging,
                        seekRatio = ui.seekRatio,
                        showTranslation = ui.showLyricsTranslation,
                        translateLangs = song.lyricTranslateLangs,
                        translateLang = song.lyricTranslateLang,
                        onSelectTranslateLang = onSelectTranslateLang,
                        fontScale = fontScale,
                        primary = palette.primary,
                        viewportHeight = screenH,
                        onSeek = onSeek,
                        onOpenFontScale = { fontScaleVisible = true },
                        onToggleTranslation = onToggleTranslation,
                        showWordByWord = ui.showWordByWord,
                        onToggleWordByWord = onToggleWordByWord,
                        onLineIndexChange = onLineIndexChange,
                    )
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.sp14))

            // ── 信息行：标题（跑马灯）+ ⋮ ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.screenPaddingH)
                    .onSizeChanged { blockHeights["title"] = it.height },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    MovingText(
                        text = song.title,
                        // 与封面页「采样率 / 位深」胶囊同一套滚动：单份文本往返（pingPong）、90px/s，
                        // 放得下就静止，太长才滚，不会出现「尾部+头部同框」的接缝
                        speed = 90f,
                        pingPong = true,
                        style = TextStyle(
                            color = AppColors.white,
                            fontSize = AppFontSize.size20,
                            fontWeight = AppFontWeight.bold,
                        ),
                    )
                }
                Box(
                    modifier = Modifier
                        .clickableNoRipple { onOpenSongActions() }
                        .padding(AppSpacing.sp8),
                    contentAlignment = Alignment.Center,
                ) {
                    PlayerDotsGlyph(
                        modifier = Modifier.size(AppIconSize.icon26),
                        tint = AppColors.glyphStrong,
                    )
                }
            }

            // ── 歌手名（紧凑档省掉：分屏 / 极小窗时优先保封面与控制栏） ──
            if (song.artist.isNotEmpty() && !geometry.compact) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.screenPaddingH)
                        .onSizeChanged { blockHeights["artist"] = it.height },
                ) {
                    Text(
                        text = song.artist,
                        color = AppColors.white54,
                        fontSize = AppFontSize.base,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.height(AppSpacing.sp4))
            }

            // ── 进度条 ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { blockHeights["progress"] = it.height },
            ) {
                PlayerProgressBarHost(
                    progress = progress,
                    isPlaying = player.isPlaying,
                    isDragging = ui.isDragging,
                    seekRatio = ui.seekRatio,
                    onDragging = onDragging,
                    onSeekEnd = onSeekEnd,
                )
            }

            // 进度条 → 控制栏 → 音量条：三段之间和底部各留一份「最小间距 + 弹性余量」，
            // 余量三等分，所以底部留白和上面两道空隙一样宽，既填掉了空、也不会把音量条贴到底。
            Spacer(modifier = Modifier.height(AppSpacing.sp12))
            PlayerElasticSpacer(elastic = !geometry.scroll)

            // ── 控制按钮 ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { blockHeights["controls"] = it.height },
            ) {
                PlayerControlsBar(
                    isPlaying = player.isPlaying,
                    loopMode = player.loopMode,
                    onTogglePlay = onTogglePlay,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onLoopMode = onCycleLoopMode,
                    onQueue = onOpenQueue,
                )
            }
            PlayerElasticSpacer(elastic = !geometry.scroll)

            // ── 音量条（紧凑档省掉） ──
            // 透明模式：音量由绝对音量（蓝牙 AVRCP / 系统媒体音量）决定，我方不衰减，
            // 所以这里只保留外观、不接交互（详情见 `PlayerVolumeSlider` 的注释）。
            if (!geometry.compact) {
                Spacer(modifier = Modifier.height(AppSpacing.sp16))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { blockHeights["volume"] = it.height },
                ) {
                    PlayerVolumeSlider(
                        volume = player.volume,
                        onVolume = onSetVolume,
                        enabled = player.audioStatus?.mode != AudioOutputMode.transparent,
                    )
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.sp18))
            PlayerElasticSpacer(elastic = !geometry.scroll)
        }

        // ── 歌词字号面板（底部浮层） ──
        PlayerFontScalePanel(
            visible = fontScaleVisible,
            current = fontScale,
            showWordByWord = ui.showWordByWord,
            onSelect = { fontScale = it },
            onToggleWordByWord = onToggleWordByWord,
            onClose = { fontScaleVisible = false },
        )

        // ── 播放设备面板（顶栏右侧入口） ──
        AudioDeviceSheet(
            visible = deviceSheetVisible,
            devices = deviceList,
            currentDeviceId = if (deviceSheetVisible) {
                AnnieAudioHolder.audioOutput?.currentOutputDevice()?.id ?: -1
            } else {
                -1
            },
            onSelect = { device ->
                val controller = AnnieAudioHolder.audioOutput
                if (controller != null) {
                    controller.selectOutputDevice(device.id)
                    AnnieAudioHolder.player?.let { controller.applyOutputDeviceToPlayer(it) }
                }
                deviceSheetVisible = false
            },
            onClose = { deviceSheetVisible = false },
        )
    }
}

/**
 * 顶栏：收起按钮（自绘向下箭头）+ 居中「歌曲 / 歌词」分段切换。
 *
 * 图标说明：`Icons.keyboard_arrow_down` 不在 `material-icons-core` 保证范围内，
 * 故用 Canvas 自绘等价「v」形箭头（对齐 Flutter `Icons.keyboard_arrow_down` 22dp white70）。
 */
@Composable
private fun PlayerTopBar(
    pageIndex: Int,
    onPageSelected: (Int) -> Unit,
    deviceName: String,
    deviceRoute: AudioRoute,
    onOpenDevices: () -> Unit,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.sp12, vertical = AppSpacing.sp6),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(PlayerTopBarButtonSize)
                .clickableNoRipple { onClose() },
            contentAlignment = Alignment.Center,
        ) {
            PlayerChevronDownGlyph(modifier = Modifier.size(AppIconSize.icon22), tint = AppColors.white70)
        }
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.Center,
        ) {
            PlayerPageToggle(pageIndex = pageIndex, onPageSelected = onPageSelected)
        }
        PlayerDeviceButton(
            name = deviceName,
            route = deviceRoute,
            onClick = onOpenDevices,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

/** 顶栏右侧：当前输出设备（图标 + 名称），名称过长时滚动，点击打开设备面板 */
@Composable
private fun PlayerDeviceButton(
    name: String,
    route: AudioRoute,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val labelStyle = TextStyle(
        color = AppColors.white70,
        fontSize = AppFontSize.sm,
        fontWeight = AppFontWeight.medium,
    )
    Row(
        modifier = modifier
            .widthIn(max = 92.dp)
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(Color.White.copy(alpha = 0.08f))
            .clickableNoRipple(onClick)
            .padding(horizontal = AppSpacing.sp8, vertical = AppSpacing.sp4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DeviceTypeGlyph(
            route = route,
            tint = AppColors.white70,
            modifier = Modifier.size(AppIconSize.icon15),
        )
        Spacer(modifier = Modifier.width(AppSpacing.sp4))
        MovingText(
            text = name.ifEmpty { "设备" },
            threshold = Int.MAX_VALUE,
            speed = 60f,
            pingPong = true,
            style = labelStyle,
            modifier = Modifier.widthIn(max = 56.dp),
        )
    }
}

/** 分段切换（Flutter `_SongLyricsToggle`：white 0.08 底、选中段 white 0.15 底、圆角 14） */
@Composable
private fun PlayerPageToggle(pageIndex: Int, onPageSelected: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(AppRadius.panel))
            // 容器底色对齐 Flutter `white @0.08`（无对应 token，white10=0.10 偏亮）
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = AppSpacing.sp4, vertical = AppSpacing.sp3),
    ) {
        PlayerToggleSegment(
            label = "歌曲",
            active = pageIndex == 0,
            onClick = { onPageSelected(0) },
        )
        PlayerToggleSegment(
            label = "歌词",
            active = pageIndex == 1,
            onClick = { onPageSelected(1) },
        )
    }
}

@Composable
private fun PlayerToggleSegment(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(AppRadius.r11))
            .background(if (active) AppColors.whiteA15 else Color.Transparent)
            .clickableNoRipple(onClick)
            .padding(horizontal = AppSpacing.sp12, vertical = AppSpacing.sp4),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (active) AppColors.white else AppColors.white54,
            fontSize = AppFontSize.size13,
            fontWeight = AppFontWeight.semiBold,
        )
    }
}

/** 封面：圆角 16；加载失败先尝试远端兜底，再回退为音符占位（对齐 Flutter `_buildCoverImage`） */
@Composable
private fun PlayerCoverImage(
    coverUrl: String,
    fallbackUrl: String,
    size: Dp,
    failed: Boolean,
    onFailed: () -> Unit,
) {
    var useFallback by remember(coverUrl) { mutableStateOf(false) }
    val url = if (useFallback) fallbackUrl else coverUrl

    if (failed || url.isEmpty()) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(AppRadius.r16))
                .background(AppColors.whiteA05),
            contentAlignment = Alignment.Center,
        ) {
            MusicNoteGlyph(
                modifier = Modifier.size(AppSpacing.sp60),
                tint = AppColors.glyphDim,
            )
        }
        return
    }

    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        onError = {
            if (!useFallback && fallbackUrl.isNotEmpty() && fallbackUrl != coverUrl) {
                useFallback = true
            } else {
                onFailed()
            }
        },
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(AppRadius.r16)),
    )
}

/**
 * 音频信息胶囊（仅封面页）：左侧是**源格式**（`编码 · 位深 Bit / 采样率 kHz`），
 * 右侧是**输出链路**（`身份 · 位深 / 采样率`，两级不同时才出现箭头）。两个胶囊同一套样式，
 * 也同一套单位与配对规则 —— 左边和右边一比，就是这一首到底被改了多少。
 *
 * 无有效信息时槽位仍占位，避免探测结果到达前后封面位置跳动。
 */
@Composable
private fun PlayerAudioInfoChip(
    info: AudioInfo,
    status: AudioOutputStatus?,
    maxWidth: Dp,
    resetKey: Any,
    modifier: Modifier = Modifier,
) {
    val formatLabel = audioInfoLabel(info)
    val outputLabel = status?.let { outputPillText(it) }.orEmpty()
    // 输出胶囊**图标**的颜色（不是文字颜色）：金=原样直出 / 红=我方重采样 /
    // 灰=我方没转（系统转或谁都没转）/ 蓝=调音。判据全在 `AudioOutputStatus.tone`。
    val toneColor = when (status?.tone) {
        AudioTone.bitPerfect -> Color(0xFFF5C451)
        AudioTone.ownSrc -> Color(0xFFF87171)
        AudioTone.tuned -> Color(0xFF60A5FA)
        else -> Color(0xFF9CA3AF)
    }
    val gap = AppSpacing.sp8
    val pillMaxWidth = ((maxWidth - gap) / 2).coerceAtLeast(0.dp)

    Box(
        modifier = modifier.height(PlayerChipSlotHeight),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (formatLabel.isNotEmpty()) {
                AudioInfoPill(
                    label = formatLabel,
                    tone = AppColors.white,
                    maxWidth = pillMaxWidth,
                    resetKey = resetKey to "format",
                    glyph = AudioPillGlyph.source,
                )
            }
            if (formatLabel.isNotEmpty() && outputLabel.isNotEmpty()) {
                Spacer(modifier = Modifier.width(gap))
            }
            if (outputLabel.isNotEmpty()) {
                AudioInfoPill(
                    label = outputLabel,
                    tone = toneColor,
                    maxWidth = pillMaxWidth,
                    resetKey = resetKey to "output",
                    glyph = AudioPillGlyph.output,
                )
            }
        }
    }
}

/**
 * 胶囊左侧的「这是哪一侧」标记。
 *
 * 两个胶囊文字结构一样（都是 `身份 · 位深 / 采样率`），只有图标能一眼分开：
 * 圆盘 = 素材（源），喇叭 = 从这儿出去（输出）。以前两边都是圆盘，读起来像同一个东西写了两遍。
 */
private enum class AudioPillGlyph { source, output }

/** 单个信息胶囊：标记图标 + 跑马灯文本（源格式与输出链路共用同一套样式） */
@Composable
private fun AudioInfoPill(
    label: String,
    tone: Color,
    maxWidth: Dp,
    resetKey: Any,
    glyph: AudioPillGlyph,
) {
    val labelStyle = TextStyle(
        color = AppColors.white,
        fontSize = AppFontSize.size15,
        fontWeight = AppFontWeight.medium,
    )
    val windowWidth =
        (maxWidth - PlayerAudioInfoIconBox - AppSpacing.sp8 - AppSpacing.sp10 * 2).coerceAtLeast(0.dp)

    Row(
        modifier = Modifier
            .widthIn(max = maxWidth)
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(Color.White.copy(alpha = 0.14f))
            .padding(horizontal = AppSpacing.sp10, vertical = AppSpacing.sp4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(PlayerAudioInfoIconBox)
                .clip(RoundedCornerShape(AppRadius.r4))
                .background(tone.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            when (glyph) {
                AudioPillGlyph.source -> AudioDiscGlyph(
                    modifier = Modifier.size(AppIconSize.icon15),
                    tint = tone,
                )
                AudioPillGlyph.output -> AudioSpeakerGlyph(
                    modifier = Modifier.size(AppIconSize.icon15),
                    tint = tone,
                )
            }
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp8))
        key(resetKey) {
            MovingText(
                text = label,
                threshold = Int.MAX_VALUE,
                speed = 90f,
                pingPong = true,
                style = labelStyle,
                modifier = Modifier.widthIn(max = windowWidth),
            )
        }
    }
}

/** 圆盘图标：外圈 + 中心点（对齐样式图里的「唱片」标记）→ 表示**素材** */
@Composable
private fun AudioDiscGlyph(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.10f
        drawCircle(
            color = tint,
            radius = size.minDimension * 0.32f,
            center = center,
            style = Stroke(width = stroke),
        )
        drawCircle(color = tint, radius = size.minDimension * 0.08f, center = center)
    }
}

/**
 * 输出图标：喇叭 + 两道声波 → 表示**从这儿出去**。
 *
 * 为什么不是耳机：输出不一定是耳机（扬声器 / 有线 / USB / 蓝牙都是出口），
 * 画耳机只在蓝牙时对，其余路由就成了假信息。喇叭是「声音从这一侧离开设备」的通用记号，
 * 而且与「源」的圆盘正好构成一对（素材 → 出口）。
 *
 * 声波用两道圆弧而不是三道：图标只有 15dp，三道在低密度屏上会糊成一块。
 */
@Composable
private fun AudioSpeakerGlyph(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = size.minDimension * 0.085f
        val line = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // 箱子 + 锥面，一条闭合路径：底边在左、开口朝右
        val cone = Path().apply {
            moveTo(w * 0.07f, h * 0.37f)
            lineTo(w * 0.20f, h * 0.37f)
            lineTo(w * 0.44f, h * 0.19f)
            lineTo(w * 0.44f, h * 0.81f)
            lineTo(w * 0.20f, h * 0.63f)
            lineTo(w * 0.07f, h * 0.63f)
            close()
        }
        drawPath(cone, color = tint, style = line)
        for (r in floatArrayOf(0.15f, 0.29f)) {
            val radius = w * r
            drawArc(
                color = tint,
                startAngle = -52f,
                sweepAngle = 104f,
                useCenter = false,
                topLeft = Offset(w * 0.44f - radius, h * 0.5f - radius),
                size = Size(radius * 2f, radius * 2f),
                style = line,
            )
        }
    }
}

/**
 * 胶囊文案：`编码 · 位深 Bit / 采样率 kHz`；有损格式没有位深时改用比特率
 * （`编码 · 320 kbps / 44.1 kHz`）。
 *
 * 位深与采样率成对、单位统一（`Bit` / `kHz` / `kbps`）的规则全部由
 * [com.annieplayer.annie_music.kt.core.audio.specText] 与 [khzText] 提供 ——
 * 这里只负责决定「用位深还是用码率」，不再自己拼数字。
 */
private fun audioInfoLabel(info: AudioInfo): String {
    val rateText = khzText(info.sampleRate)
    val quality = when {
        info.bitDepth > 0 -> listOf("${info.bitDepth} Bit", rateText)
        info.bitrate > 0 -> listOf("${info.bitrate / 1000} kbps", rateText)
        else -> listOf(rateText)
    }.filter { it.isNotEmpty() }.joinToString(" / ")
    return listOf(info.codec, quality).filter { it.isNotEmpty() }.joinToString(" · ")
}

/** 「⋮」：MoreVert 的可用性在核心图标集中不确定，自绘三个圆点（对齐 26dp white60） */
@Composable
internal fun PlayerDotsGlyph(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier = modifier) {
        val r = size.width * 0.085f
        val cx = size.width / 2f
        drawCircle(color = tint, radius = r, center = Offset(cx, size.height * 0.22f))
        drawCircle(color = tint, radius = r, center = Offset(cx, size.height * 0.5f))
        drawCircle(color = tint, radius = r, center = Offset(cx, size.height * 0.78f))
    }
}

/** 向下箭头「v」：自绘（对齐 Flutter `Icons.keyboard_arrow_down`） */
@Composable
private fun PlayerChevronDownGlyph(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier = modifier) {
        val stroke = size.width * 0.10f
        val p = Path().apply {
            moveTo(size.width * 0.25f, size.height * 0.38f)
            lineTo(size.width * 0.5f, size.height * 0.64f)
            lineTo(size.width * 0.75f, size.height * 0.38f)
        }
        drawPath(path = p, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round))
    }
}

/**
 * 播放页封面取色块背景（带切歌淡入）。
 *
 * [grid] 为封面 N×N 网格平均色（行优先），按同位置平铺；封面切换时新色块 600ms 淡入盖住旧色块。
 */
@Composable
private fun PlayerCoverGridBackdrop(
    grid: List<Color>,
    dim: Int,
    modifier: Modifier = Modifier,
) {
    val prev = remember { mutableStateOf<Pair<Int, List<Color>>?>(null) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(grid, dim) {
        val p = prev.value
        if (p != null && p.second.isNotEmpty() && p.second != grid && grid.isNotEmpty()) {
            fade.snapTo(0f)
            fade.animateTo(1f, tween(durationMillis = 600, easing = AppEasing.easeOut))
        } else {
            fade.snapTo(1f)
        }
        prev.value = dim to grid
    }
    Box(modifier = modifier) {
        val p = prev.value
        if (p != null && p.second.isNotEmpty() && p.second != grid) {
            PlayerGridCanvas(p.second, p.first)
        }
        PlayerGridCanvas(grid, dim, alpha = fade.value)
    }
}

@Composable
private fun PlayerGridCanvas(grid: List<Color>, dim: Int, alpha: Float = 1f) {
    if (dim <= 0 || grid.size < dim * dim) return
    // N×N 小位图 → 双线性放大铺满：色块间平滑插值，无硬边
    val bmp = remember(grid, dim) {
        val b = android.graphics.Bitmap.createBitmap(dim, dim, android.graphics.Bitmap.Config.ARGB_8888)
        val px = IntArray(dim * dim) { i -> grid[i].toArgb() }
        b.setPixels(px, 0, dim, 0, 0, dim, dim)
        b.asImageBitmap()
    }
    Canvas(modifier = Modifier.fillMaxSize().alpha(alpha)) {
        drawImage(
            image = bmp,
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            filterQuality = FilterQuality.Low,
        )
    }
}

/**
 * 进度条的**唯一**订阅者。
 *
 * `PlayerScreen` 是个大组件（封面 pager + 控制栏 + 音量条 + 信息胶囊），而进度 5 Hz 变一次。
 * 若在 `PlayerScreen` 体内 `collectAsState()`，整页每秒重组 5 次 —— 包括 `HorizontalPager`
 * 的页内容、`PlayerCoverImage` 的 Coil 请求与封面进入动画。让这个小函数单独订阅，
 * 重组就只落在进度条本身。
 *
 * 纪律：**别把 `collectAsState()` 挪回 `PlayerScreen` 体内**。
 */
@Composable
private fun PlayerProgressBarHost(
    progress: StateFlow<PlaybackProgress>,
    isPlaying: Boolean,
    isDragging: Boolean,
    seekRatio: Double,
    onDragging: (Double) -> Unit,
    onSeekEnd: (Double) -> Unit,
) {
    val p by progress.collectAsState()
    PlayerProgressBar(
        position = p.currentTime,
        duration = p.duration,
        isPlaying = isPlaying,
        isDragging = isDragging,
        seekRatio = seekRatio,
        onDragging = onDragging,
        onSeekEnd = onSeekEnd,
    )
}
