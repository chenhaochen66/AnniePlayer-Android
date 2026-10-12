package com.annieplayer.annie_music.kt.features.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.launch

/** 顶栏按钮命中区（Flutter `SizedBox(width: 34, height: 34)`） */
private val PlayerTopBarButtonSize = 34.dp

/** 拖拽条尺寸（Flutter `Container(width: 36, height: 4)`） */
private val PlayerDragBarWidth = 36.dp

/** 封面背景模糊半径（Flutter `ImageFilter.blur(sigmaX: 60, sigmaY: 60)`） */
private val PlayerBackdropBlur = 60.dp

/** 封面色块背景的高斯模糊半径（软化色块边界） */
private val PlayerGridBlur = 48.dp

/** 音频信息胶囊槽位高度（恒定占位，避免探测结果到达前后封面跳动） */
private val PlayerAudioInfoSlotHeight = 30.dp

/** 音频信息胶囊内圆盘图标盒边长 */
private val PlayerAudioInfoIconBox = 22.dp

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

    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    val pageIndex = pagerState.currentPage

    BoxWithConstraints(modifier = modifier.fillMaxSize().background(palette.background)) {
        val screenW = maxWidth
        val screenH = maxHeight
        val coverSize = minOf(screenW * 0.78f, screenH * 0.5f)
        val pagerHeight = screenH * 0.51f

        // ── 封面取色块背景：N×N 平铺，色块位置与封面各区域一一对应；切歌淡入变色 ──
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

        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
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
                        PlayerAudioInfoChip(
                            info = player.audioInfo,
                            maxWidth = screenW / 2 - AppSpacing.screenPaddingH,
                            resetKey = song.id to ui.playerOverlayVisible,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .padding(
                                    start = AppSpacing.screenPaddingH,
                                    end = AppSpacing.screenPaddingH,
                                    bottom = AppSpacing.sp10,
                                ),
                        )
                    }
                } else {
                    PlayerLyricsView(
                        songId = song.id,
                        lyric = song.lyric,
                        tlyric = song.tlyric,
                        wordLyric = song.wordLyric,
                        isPlaying = player.isPlaying,
                        currentTime = player.currentTime,
                        isDragging = ui.isDragging,
                        seekRatio = ui.seekRatio,
                        duration = player.duration,
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.screenPaddingH),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    MovingText(
                        text = song.title,
                        threshold = 20,
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
                        tint = AppColors.white60,
                    )
                }
            }

            // ── 歌手名 ──
            if (song.artist.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.screenPaddingH)) {
                    Text(
                        text = song.artist,
                        color = AppColors.white54,
                        fontSize = AppFontSize.base,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.sp4))

            // ── 进度条 ──
            PlayerProgressBar(
                position = player.currentTime,
                duration = player.duration,
                isDragging = ui.isDragging,
                seekRatio = ui.seekRatio,
                onDragging = onDragging,
                onSeekEnd = onSeekEnd,
            )

            Spacer(modifier = Modifier.height(AppSpacing.sp12))

            // ── 控制按钮 ──
            PlayerControlsBar(
                isPlaying = player.isPlaying,
                loopMode = player.loopMode,
                onTogglePlay = onTogglePlay,
                onPrevious = onPrevious,
                onNext = onNext,
                onLoopMode = onCycleLoopMode,
                onQueue = onOpenQueue,
            )

            Spacer(modifier = Modifier.height(AppSpacing.sp12))

            // ── 音量条 ──
            PlayerVolumeSlider(volume = player.volume, onVolume = onSetVolume)

            Spacer(modifier = Modifier.height(AppSpacing.sp18))
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
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.sp12, vertical = AppSpacing.sp6),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(PlayerTopBarButtonSize).clickableNoRipple { onClose() },
            contentAlignment = Alignment.Center,
        ) {
            PlayerChevronDownGlyph(modifier = Modifier.size(AppIconSize.icon22), tint = AppColors.white70)
        }
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
            PlayerPageToggle(pageIndex = pageIndex, onPageSelected = onPageSelected)
        }
        Spacer(modifier = Modifier.size(PlayerTopBarButtonSize))
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
                tint = AppColors.white24,
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
 * 音频信息胶囊（仅封面页）：圆盘图标 + `编码 · 位深 Bit / 采样率 kHz`。
 *
 * [maxWidth] 限制胶囊最长只到封面水平中线，超出部分省略号截断；无有效信息时槽位仍占位，
 * 避免探测结果到达前后封面位置跳动。
 */
@Composable
private fun PlayerAudioInfoChip(
    info: AudioInfo,
    maxWidth: Dp,
    resetKey: Any,
    modifier: Modifier = Modifier,
) {
    val label = audioInfoLabel(info)
    val labelStyle = TextStyle(
        color = AppColors.white,
        fontSize = AppFontSize.size15,
        fontWeight = AppFontWeight.medium,
    )
    val windowWidth = maxWidth - PlayerAudioInfoIconBox - AppSpacing.sp8 - AppSpacing.sp10 * 2

    Box(
        modifier = modifier.height(PlayerAudioInfoSlotHeight),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (!info.isEmpty) {
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
                        .background(Color.White.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    AudioDiscGlyph(
                        modifier = Modifier.size(AppIconSize.icon15),
                        tint = AppColors.white,
                    )
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
    }
}

/** 圆盘图标：外圈 + 中心点（对齐样式图里的「唱片」标记） */
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
 * 胶囊文案：`编码 · 位深 Bit / 采样率 kHz`；有损格式没有位深时改用比特率
 * （`编码 · 320 kbps / 44.1 kHz`）。采样率整数 kHz 不带小数，带小数最多两位。
 */
private fun audioInfoLabel(info: AudioInfo): String {
    val rateText = when {
        info.sampleRate <= 0 -> ""
        info.sampleRate % 1000 == 0 -> "${info.sampleRate / 1000} kHz"
        info.sampleRate % 100 == 0 -> "${info.sampleRate / 1000}.${(info.sampleRate % 1000) / 100} kHz"
        else -> "${info.sampleRate / 1000}." +
            (info.sampleRate % 1000).toString().padStart(3, '0').trimEnd('0') + " kHz"
    }
    val headText = when {
        info.bitDepth > 0 -> "${info.bitDepth} Bit"
        info.bitrate > 0 -> "${info.bitrate / 1000} kbps"
        else -> ""
    }
    val quality = when {
        headText.isEmpty() -> rateText
        rateText.isEmpty() -> headText
        else -> "$headText / $rateText"
    }
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
    // 6×6 小位图 → 双线性放大铺满：色块间平滑插值，无硬边
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
