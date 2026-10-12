package com.annieplayer.annie_music.kt.features.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.annieplayer.annie_music.kt.core.util.LyricLine
import com.annieplayer.annie_music.kt.core.util.LyricWord
import com.annieplayer.annie_music.kt.core.util.findCurrentLineIndex
import com.annieplayer.annie_music.kt.core.util.parseLrcWithTranslation
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppLineHeight
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppShadow
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.clickableNoRipple
import kotlin.math.abs
import kotlin.math.roundToInt

/** 当前行左上角定位系数（Flutter：顶部留白 18% 屏高、当前行停在 -2% 屏高处） */
private const val PlayerLyricTopInsetFraction = 0.18f
private const val PlayerLyricTargetFraction = 0.02f

/** 未测量行的平均高度兜底（Flutter `_averageLineHeight` 无数据时回退 50） */
private const val PlayerLyricFallbackLineHeightDp = 50f

/** 当前行底色（Flutter `${primaryColor}12`，alpha 18/255） */
private const val PlayerLyricActiveBgAlpha = 18f / 255f

/** 行高变化重定位的触发阈值（px）：低于该值不再校正，避免测量-滚动互相触发 */
private const val PlayerLyricRescrollThresholdPx = 8f

/**
 * 歌词滚动视图（1:1 移植 Flutter `LyricsView`）。
 *
 * 关键行为：
 * - 逐行高亮 + 400ms 淡入（拖动中即时切换），当前行带主色辉光与左侧 3dp 高亮竖线。
 * - 翻译行 200ms 淡入淡出，透明度 = 行透明度 × 翻译开关。
 * - 自动滚动：行高按索引实测（未测量按已测平均估算），首次定位直接跳转，
 *   之后 550ms + easeOutCubic 平滑滚动；用户手指拖拽期间暂停，松手后停留原地，
 *   直到高亮行真正变化（歌曲推进 / seek）才恢复自动跟随。
 * - 点击任意行 → 跳转到该行时间（onSeek 回调，宿主执行 seek）。
 * - 高亮行变化 → 通过 [onLineIndexChange] 回写宿主（对齐 Flutter `setCurrentLineIndex`）。
 */
@Composable
fun PlayerLyricsView(
    songId: String,
    lyric: String?,
    tlyric: String?,
    wordLyric: String? = null,
    isPlaying: Boolean = true,
    currentTime: Double,
    isDragging: Boolean,
    seekRatio: Double,
    duration: Double,
    showTranslation: Boolean,
    /** 可用译文语言（接口给的 translation_langs；为空则不显示语言箭头） */
    translateLangs: List<String> = emptyList(),
    /** 当前选中的译文语言 */
    translateLang: String? = null,
    onSelectTranslateLang: (String) -> Unit = {},
    fontScale: Float,
    primary: Color,
    viewportHeight: Dp,
    onSeek: (Double) -> Unit,
    onOpenFontScale: () -> Unit,
    onToggleTranslation: () -> Unit = {},
    showWordByWord: Boolean = true,
    onToggleWordByWord: () -> Unit = {},
    /** 当前高亮行索引回写宿主（对齐 Flutter `setCurrentLineIndex`；宿主应接到 `UiViewModel::setCurrentLineIndex`） */
    onLineIndexChange: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val lines = remember(lyric, tlyric, wordLyric) {
        playerParseLyricLines(lyric, tlyric, wordLyric)
    }

    if (lines.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "暂无歌词", color = AppColors.white54, fontSize = AppFontSize.lg)
        }
        return
    }

    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val heights = remember(songId) { mutableMapOf<Int, Float>() }
    var heightsVersion by remember(songId) { mutableIntStateOf(0) }
    var needsInitialJump by remember(songId) { mutableStateOf(true) }

    // 自动跟随抑制标志（对齐 Flutter `_userScrolling`）：手指拖拽时置位；松手后保持，
    // 直到高亮行真正变化才恢复跟随（用户可停留在他滚动到的位置阅读）
    var suppressFollow by remember(songId) { mutableStateOf(false) }
    // 抑制期间锚定的高亮行：与 targetIndex 相同则说明高亮行未变化，继续停留
    var suppressAnchorIndex by remember(songId) { mutableIntStateOf(0) }

    // 仅「手指拖拽」算用户滚动：程序化 animateScrollBy 不会置位（对齐 Flutter dragDetails 判定）
    val isUserDragging by listState.interactionSource.collectIsDraggedAsState()

    // 拖动进度条时用 seekRatio 对应的时间定位（对齐 Flutter `_updateIndex`）
    val effectiveTime = if (isDragging) seekRatio * duration else currentTime
    val targetIndex = remember(lines, effectiveTime) {
        findCurrentLineIndex(lines, effectiveTime)
    }

    val avgLinePx = remember(density) { with(density) { PlayerLyricFallbackLineHeightDp.dp.toPx() } }
    val topInsetPx = remember(density, viewportHeight) {
        with(density) { (viewportHeight * PlayerLyricTopInsetFraction).toPx() }
    }
    val targetInsetPx = remember(density, viewportHeight) {
        with(density) { (viewportHeight * PlayerLyricTargetFraction).toPx() }
    }
    val glowBlurPx = remember(density) { with(density) { AppShadow.blur3xl.toPx() } }

    fun sumBefore(index: Int): Float {
        var sum = 0f
        for (k in 0 until index) sum += heights[k] ?: avgLinePx
        return sum
    }

    fun currentScrollPx(): Float {
        var sum = 0f
        for (k in 0 until listState.firstVisibleItemIndex) sum += heights[k] ?: avgLinePx
        return sum + listState.firstVisibleItemScrollOffset
    }

    // 切歌：复位滚动与测量
    LaunchedEffect(songId) {
        needsInitialJump = true
        heights.clear()
        heightsVersion = 0
        listState.scrollToItem(0, 0)
    }

    // 手指开始拖拽 → 置位抑制并锚定当时的高亮行
    // （collectIsDraggedAsState 仅在手指拖拽时置位，程序化 animateScrollBy 不会触发）
    LaunchedEffect(isUserDragging) {
        if (isUserDragging) {
            suppressFollow = true
            suppressAnchorIndex = targetIndex
        }
    }

    // 高亮行变化 → 回写宿主的当前行索引（对齐 Flutter `_updateIndex` → `setCurrentLineIndex`）
    LaunchedEffect(targetIndex) {
        onLineIndexChange(targetIndex)
    }

    // 预滚动：下一行在 scrollLeadMs 内就要开始时，**提前**把滚动启动。
    // 这样它「开始唱」的那一刻刚好滚到位 —— 高亮仍按真实时间立即点亮（不再出现
    // 「滚到高亮位时前几个字已经唱完」），滚动也依旧是平滑动画、不僵硬。
    val scrollIndex = if (
        targetIndex >= 0 &&
        targetIndex + 1 < lines.size &&
        (lines[targetIndex + 1].time - effectiveTime) * 1000.0 <= LyricScrollLeadMs
    ) {
        targetIndex + 1
    } else {
        targetIndex
    }

    // 高亮行变化 / 行高测量变化 → 定位（用户拖拽期间及松手后均不自动滚动）
    LaunchedEffect(scrollIndex, heightsVersion, lines) {
        if (lines.isEmpty()) return@LaunchedEffect
        // 拖拽中：不滚动，并持续锚定最新高亮行（对齐 Flutter 拖拽中仍更新 _currentLineIndex）
        if (isUserDragging) {
            suppressFollow = true
            suppressAnchorIndex = targetIndex
            return@LaunchedEffect
        }
        // 松手后停留原地：仅当高亮行相对锚点真正变化（歌曲推进 / seek）才恢复跟随
        if (suppressFollow) {
            if (targetIndex == suppressAnchorIndex) return@LaunchedEffect
            suppressFollow = false
        }
        val index = if (scrollIndex < 0) 0 else scrollIndex
        if (index >= lines.size) return@LaunchedEffect
        val targetPx = topInsetPx + sumBefore(index) - targetInsetPx
        if (needsInitialJump) {
            needsInitialJump = false
            listState.scrollToItem(0, targetPx.roundToInt())
            return@LaunchedEffect
        }
        val delta = targetPx - currentScrollPx()
        if (abs(delta) < PlayerLyricRescrollThresholdPx) return@LaunchedEffect
        listState.animateScrollBy(
            value = delta,
            animationSpec = tween(durationMillis = AppDuration.lyricScroll, easing = AppEasing.easeOutCubic),
        )
    }

    // 逐帧时钟：进度事件约 100ms 才来一次，直接用它上色会「一跳一跳」；
    // 以最新进度为锚点逐帧外推，重新来事件时再对齐，动画才丝滑。
    var frameTimeMs by remember { mutableIntStateOf(0) }
    LaunchedEffect(effectiveTime, isDragging, duration, isPlaying) {
        val anchorWall = System.nanoTime()
        val anchorMs = (effectiveTime * 1000).toLong()
        // 暂停时只对齐、不外推：否则逐帧时钟会一路跑到最后一个字
        if (!isPlaying) {
            frameTimeMs = anchorMs.toInt()
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos { now ->
                frameTimeMs = (anchorMs + (now - anchorWall) / 1_000_000L).toInt()
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = viewportHeight * PlayerLyricTopInsetFraction),
        ) {
            itemsIndexed(items = lines, key = { index, _ -> index }) { index, line ->
                PlayerLyricLine(
                    line = line,
                    isActive = index == targetIndex,
                    isPast = index < targetIndex,
                    distance = index - targetIndex,
                    animate = !isDragging,
                    primary = primary,
                    showTranslation = showTranslation,
                    showWordByWord = showWordByWord,
                    currentTimeMs = frameTimeMs,
                    fontScale = fontScale,
                    glowBlurPx = glowBlurPx,
                    onSeek = onSeek,
                    onHeight = { measured ->
                        val old = heights[index]
                        if (old == null || abs(old - measured) > 0.5f) {
                            heights[index] = measured
                            heightsVersion += 1
                        }
                    },
                )
            }
        }

        // 设置按钮（右上角 32 圆形，white 0.06 底）
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = AppSpacing.sp8, end = AppSpacing.sp16)
                .size(PlayerSize32)
                .clip(CircleShape)
                .background(AppColors.whiteA06)
                .clickableNoRipple { onOpenFontScale() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "歌词设置",
                tint = AppColors.white,
                modifier = Modifier.size(AppIconSize.icon20),
            )
        }

        // 翻译：圆形开关 + 右侧语言箭头（仅当接口确实给出译文语言时才显示箭头）
        if (!tlyric.isNullOrEmpty() || translateLangs.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = AppSpacing.sp8, end = AppSpacing.sp16),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(PlayerSize32)
                        .clip(CircleShape)
                        .background(
                            if (showTranslation) Color.White.copy(alpha = 0.18f) else AppColors.whiteA06,
                        )
                        .clickableNoRipple { onToggleTranslation() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "译",
                        color = AppColors.white,
                        fontSize = AppFontSize.size13,
                        fontWeight = AppFontWeight.bold,
                    )
                }
                if (translateLangs.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(AppSpacing.sp6))
                    PlayerTranslateLangDropdown(
                        langs = translateLangs,
                        current = translateLang,
                        onSelect = onSelectTranslateLang,
                    )
                }
            }
        }
    }
}

/** 32dp（Flutter `Container(width: 32, height: 32)`）：歌词页设置按钮 */
private val PlayerSize32 = 32.dp

/**
 * 单行歌词：当前行高亮（底色 + 左竖线 + 主色辉光）、翻译淡入、点击跳转。
 *
 * 不透明度严格对齐 Flutter：
 * `isPast ? 0.12 : isActive ? 1 : max(0.12, 0.45 - (distance - 1) * 0.2)`。
 */
@Composable
private fun PlayerLyricLine(
    line: LyricLine,
    isActive: Boolean,
    isPast: Boolean,
    distance: Int,
    animate: Boolean,
    primary: Color,
    showTranslation: Boolean,
    showWordByWord: Boolean,
    currentTimeMs: Int,
    fontScale: Float,
    glowBlurPx: Float,
    onSeek: (Double) -> Unit,
    onHeight: (Float) -> Unit,
) {
    val targetOpacity = when {
        isPast -> 0.12f
        isActive -> 1f
        else -> maxOf(0.12f, 0.45f - (distance - 1) * 0.2f)
    }
    val fadeDuration = if (animate) AppDuration.lyricLineFade else 0
    val lineOpacity by animateFloatAsState(
        targetValue = targetOpacity,
        animationSpec = tween(durationMillis = fadeDuration, easing = AppEasing.easeInOut),
        label = "playerLyricOpacity",
    )
    val translationFade by animateFloatAsState(
        targetValue = if (showTranslation) 1f else 0f,
        animationSpec = tween(durationMillis = AppDuration.lyricTranslationFade, easing = AppEasing.linear),
        label = "playerLyricTranslationFade",
    )

    // 逐字上色（AnnotatedString）：天然跟随自动换行，不像「横向裁切」那样在两行文本上被一刀切。
    // 只有当前行做，其它行用纯文本，避免无谓开销。
    val karaoke = showWordByWord && isActive && line.words.isNotEmpty()
    val lineStyle = TextStyle(
        color = AppColors.white,
        fontSize = AppFontSize.size34 * fontScale,
        lineHeight = AppLineHeight.lyric40 * fontScale,
        fontWeight = if (isActive) AppFontWeight.extraBold else AppFontWeight.medium,
    )
    val translationStyle = TextStyle(
        color = AppColors.white,
        fontSize = AppFontSize.size17 * fontScale,
        lineHeight = AppLineHeight.lyric28 * fontScale,
        fontWeight = if (isActive) AppFontWeight.extraBold else AppFontWeight.medium,
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickableNoRipple { onSeek(line.time) }
            .onGloballyPositioned { coordinates -> onHeight(coordinates.size.height.toFloat()) }
            .drawBehind {
                // 当前行左侧高亮竖线（Flutter：Positioned(left:24, top:12, bottom:12, width:3)）。
                // 直接在本行 Box 上绘制：Box 的边界就是 Flutter Stack 的「行盒」，
                // 既避开 LazyColumn item 主轴无界导致 fillMaxHeight 塌成 0 高，
                // 也避开 matchParentSize 的固定约束把 3dp 宽居中（那样竖线会跑到文字中间）。
                if (isActive) {
                    val top = AppSpacing.sp12.toPx()
                    val bottom = AppSpacing.sp12.toPx()
                    drawRoundRect(
                        color = primary,
                        topLeft = Offset(AppSpacing.sp24.toPx(), top),
                        size = Size(3.dp.toPx(), (size.height - top - bottom).coerceAtLeast(0f)),
                        cornerRadius = CornerRadius(AppRadius.r1_5.toPx()),
                    )
                }
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.sp40, vertical = AppSpacing.sp10),
        ) {
            if (karaoke) {
                // 未唱部分要恒为「45% 白」：行透明度还在 0.45→1 淡入时，若不补偿，
                // 0.45 × 0.45 会瞬间暗到 20%（真机：刚变高亮时暗一下）。
                val dimAlpha = (0.45f / lineOpacity.coerceAtLeast(0.45f)).coerceIn(0f, 1f)
                KaraokeText(
                    text = line.words.joinToString("") { it.text },
                    words = line.words,
                    currentTimeMs = currentTimeMs,
                    style = lineStyle,
                    dimColor = AppColors.white.copy(alpha = dimAlpha),
                    modifier = Modifier.fillMaxWidth().playerAlphaLocal(lineOpacity),
                )
            } else {
                Text(
                    text = line.text,
                    style = lineStyle,
                    modifier = Modifier.fillMaxWidth().playerAlphaLocal(lineOpacity),
                )
            }
            val translation = line.translation
            if (translation != null) {
                Text(
                    text = translation,
                    style = translationStyle,
                    modifier = Modifier.fillMaxWidth().playerAlphaLocal(lineOpacity * translationFade),
                )
            }
        }

    }
}

/**
 * 卡拉OK文字（统一进度条式扫过）。
 *
 * 做法：先量出整段的**换行边界**，再把每个视觉行**拆成独立的 Text** 各自渲染。
 * 这样每行都是「单行文本」，扫过位置就是本行内的 x —— 从根上避免
 * `getHorizontalPosition` 在换行时的坐标系坑（真机现象：扫到第二行时第一行倒回去重画）。
 */
@Composable
private fun KaraokeText(
    text: String,
    words: List<LyricWord>,
    currentTimeMs: Int,
    style: TextStyle,
    dimColor: Color,
    modifier: Modifier = Modifier,
) {
    var fullLayout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    val sungIndex = karaokeSungIndex(words, currentTimeMs)
    val layout = fullLayout

    Column(modifier = modifier.fillMaxWidth()) {
        if (layout == null) {
            // 第一帧还没有布局：先按普通文本画，并在回调里量出换行边界
            Text(
                text = text,
                style = style.copy(color = dimColor),
                onTextLayout = { fullLayout = it },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            for (row in 0 until layout.lineCount) {
                val start = layout.getLineStart(row)
                val end = layout.getLineEnd(row)
                // layout 与当前 text 可能短暂不同步（复用/换行变化），越界一律跳过或裁剪
                if (start >= text.length || end <= start) continue
                val slice = text.substring(start, end.coerceAtMost(text.length))
                val localSung = (sungIndex - start).coerceIn(0f, slice.length.toFloat())
                KaraokeRowText(
                    text = slice,
                    sungIndex = localSung,
                    style = style,
                    dimColor = dimColor,
                )
            }
        }
    }
}

/** 单个视觉行的卡拉OK：单行文本 → 扫过位置就是本行内的 x，绝不会串行 */
@Composable
private fun KaraokeRowText(
    text: String,
    sungIndex: Float,
    style: TextStyle,
    dimColor: Color,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Box(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = style.copy(color = dimColor),
            onTextLayout = { layout = it },
            modifier = Modifier.fillMaxWidth(),
        )
        if (sungIndex > 0f) {
            Text(
                text = text,
                style = style,
                modifier = Modifier
                    .fillMaxWidth()
                    // DstIn 的作用目标是「当前图层」：不隔离会把整行背景也擦成黑块
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                    .drawWithContent {
                        val l = layout ?: return@drawWithContent drawContent()
                        drawContent()
                        val width = l.size.width.toFloat()
                        if (width <= 0f || text.isEmpty()) return@drawWithContent
                        val i = sungIndex.toInt().coerceIn(0, text.length - 1)
                        val right = if (sungIndex >= text.length) {
                            width
                        } else {
                            val x0 = l.getHorizontalPosition(i, true).toFloat()
                            val x1 = if (i + 1 <= text.length) {
                                l.getHorizontalPosition(i + 1, true).toFloat()
                            } else {
                                x0
                            }
                            x0 + (x1 - x0) * (sungIndex - i)
                        }
                        val e = (right / width).coerceIn(0f, 1f)
                        val fade = (SoftEdgePx / width).coerceIn(0f, 1f)
                        val brush = when {
                            e <= 0f -> SolidColor(Color.Transparent)
                            e >= 1f -> SolidColor(Color.White)
                            else -> Brush.horizontalGradient(
                                0f to Color.White,
                                (e - fade).coerceIn(0f, 1f) to Color.White,
                                e to Color.Transparent,
                                1f to Color.Transparent,
                            )
                        }
                        drawRect(
                            brush = brush,
                            topLeft = Offset.Zero,
                            size = Size(width, size.height),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            )
        }
    }
}

/** 逐字上色版（多行时用）：每个字按自己的时长从暗到亮 */
private fun perCharWordText(words: List<LyricWord>, currentTimeMs: Int): AnnotatedString =
    buildAnnotatedString {
        for (w in words) {
            val n = w.text.length
            for (i in 0 until n) {
                val charStart = w.startMs + if (n > 0) w.durMs * i / n else 0
                val charDur = if (n > 0) w.durMs.toFloat() / n else 0f
                val t = if (charDur > 0f) {
                    ((currentTimeMs - charStart) / charDur).coerceIn(0f, 1f)
                } else {
                    1f
                }
                withStyle(SpanStyle(color = lerp(AppColors.whiteA45, AppColors.white, t))) {
                    append(w.text[i])
                }
            }
        }
    }

/** 预滚动的提前量（毫秒）：下一行还有这么久要开始时就把滚动启动 */
private const val LyricScrollLeadMs = 200

/** 卡拉OK扫过的软边带宽（px）：越大越柔和，越小越利落 */
private const val SoftEdgePx = 50f

/** 已唱到第几个字符（含小数，按词时间线性插值；供卡拉OK定位扫过边界） */
private fun karaokeSungIndex(words: List<LyricWord>, currentTimeMs: Int): Float {
    var idx = 0f
    for (w in words) {
        val n = w.text.length
        val end = w.startMs + w.durMs
        if (currentTimeMs >= end) {
            idx += n
            continue
        }
        if (currentTimeMs >= w.startMs) {
            val f = if (w.durMs > 0) (currentTimeMs - w.startMs).toFloat() / w.durMs else 1f
            idx += n * f.coerceIn(0f, 1f)
        }
        break
    }
    return idx
}

/** 轻量透明度修饰（等价 Flutter `Opacity`，避免为每行引入额外 composable 层级） */
private fun Modifier.playerAlphaLocal(value: Float): Modifier =
    this.graphicsLayer { alpha = value.coerceIn(0f, 1f) }

/** 解析歌词并合并翻译；平台占位「暂无歌词」视为空（对齐 Flutter `_parsedLines`） */
private fun playerParseLyricLines(
    lyric: String?,
    tlyric: String?,
    wordLyric: String? = null,
): List<LyricLine> {
    if (lyric.isNullOrEmpty()) return emptyList()
    val lines = parseLrcWithTranslation(lyric, tlyric, wordLyric)
    if (lines.size == 1 && lines.first().text == "暂无歌词") return emptyList()
    return lines
}
