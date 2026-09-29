package com.annieplayer.annie_music.kt.features.player

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
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.annieplayer.annie_music.kt.core.util.LyricLine
import com.annieplayer.annie_music.kt.core.util.findCurrentLineIndex
import com.annieplayer.annie_music.kt.core.util.parseLrcWithTranslation
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppLineHeight
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppShadow
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.clickableNoRipple
import kotlin.math.abs
import kotlin.math.roundToInt

/** 字号档位（Flutter `_fontScaleLevels`：0.5 / 0.625 / 0.75 / 0.875 / 1.0） */
private val PlayerFontScaleLevels = listOf(0.5f, 0.625f, 0.75f, 0.875f, 1.0f)

/** 当前行左上角定位系数（Flutter：顶部留白 18% 屏高、当前行停在 -2% 屏高处） */
private const val PlayerLyricTopInsetFraction = 0.18f
private const val PlayerLyricTargetFraction = 0.02f

/** 未测量行的平均高度兜底（Flutter `_averageLineHeight` 无数据时回退 50） */
private const val PlayerLyricFallbackLineHeightDp = 50f

/** 当前行底色（Flutter `${primaryColor}12`，alpha 18/255） */
private const val PlayerLyricActiveBgAlpha = 18f / 255f

/** 行高变化重定位的触发阈值（px）：低于该值不再校正，避免测量-滚动互相触发 */
private const val PlayerLyricRescrollThresholdPx = 8f

/** 字号面板示例字号的基数 / 步进（Flutter：`fontSize: 10 + lv * 14`） */
private const val PlayerFontSampleBaseSp = 10f
private const val PlayerFontSampleStepSp = 14f

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
    currentTime: Double,
    isDragging: Boolean,
    seekRatio: Double,
    duration: Double,
    showTranslation: Boolean,
    fontScale: Float,
    primary: Color,
    viewportHeight: Dp,
    onSeek: (Double) -> Unit,
    onOpenFontScale: () -> Unit,
    /** 当前高亮行索引回写宿主（对齐 Flutter `setCurrentLineIndex`；宿主应接到 `UiViewModel::setCurrentLineIndex`） */
    onLineIndexChange: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val lines = remember(lyric, tlyric) { playerParseLyricLines(lyric, tlyric) }

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

    // 高亮行变化 / 行高测量变化 → 定位（用户拖拽期间及松手后均不自动滚动）
    LaunchedEffect(targetIndex, heightsVersion, lines) {
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
        val index = if (targetIndex < 0) 0 else targetIndex
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

        // 字号按钮（Flutter：右上角 32 圆形，white 0.06 底）
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
            Text(
                text = "Aa",
                color = AppColors.white,
                fontSize = AppFontSize.size13,
                fontWeight = AppFontWeight.bold,
            )
        }
    }
}

/** 32dp（Flutter `Container(width: 32, height: 32)`）：歌词页字号按钮 */
private val PlayerSize32 = 32.dp

/** 44dp（Flutter `_FontScalePanel` 档位按钮 `Container(width: 44, height: 44)`） */
private val PlayerScalePanelButtonSize = 44.dp

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

    val glow = if (isActive) Shadow(color = primary, blurRadius = glowBlurPx) else null
    val lineStyle = TextStyle(
        color = AppColors.white,
        fontSize = AppFontSize.size34 * fontScale,
        lineHeight = AppLineHeight.lyric40 * fontScale,
        fontWeight = if (isActive) AppFontWeight.extraBold else AppFontWeight.medium,
        shadow = glow,
    )
    val translationStyle = TextStyle(
        color = AppColors.white,
        fontSize = AppFontSize.size17 * fontScale,
        lineHeight = AppLineHeight.lyric28 * fontScale,
        fontWeight = if (isActive) AppFontWeight.extraBold else AppFontWeight.medium,
        shadow = glow,
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickableNoRipple { onSeek(line.time) }
            .onGloballyPositioned { coordinates -> onHeight(coordinates.size.height.toFloat()) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.sp40, vertical = AppSpacing.sp10)
                .clip(RoundedCornerShape(if (isActive) AppRadius.card else 0.dp))
                .background(if (isActive) primary.copy(alpha = PlayerLyricActiveBgAlpha) else Color.Transparent),
        ) {
            Text(
                text = line.text,
                style = lineStyle,
                modifier = Modifier.fillMaxWidth().playerAlphaLocal(lineOpacity),
            )
            val translation = line.translation
            if (translation != null) {
                Text(
                    text = translation,
                    style = translationStyle,
                    modifier = Modifier.fillMaxWidth().playerAlphaLocal(lineOpacity * translationFade),
                )
            }
        }

        // 当前行左侧高亮竖线（Flutter：left 24 / top 12 / bottom 12 / 宽 3）
        // 注意：LazyColumn item 主轴约束无界，fillMaxHeight 会解析为 minHeight=0 而塌缩为 0 高；
        // 改用 matchParentSize 按行 Box 的内容高度撑满（等价 Flutter 的 Positioned(top:12,bottom:12)）；
        // 宽度必须用 requiredWidth：matchParentSize 传入的是固定宽度约束，width 会被强制取父宽
        if (isActive) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(start = AppSpacing.sp24, top = AppSpacing.sp12, bottom = AppSpacing.sp12)
                    .requiredWidth(3.dp)
                    .background(primary, RoundedCornerShape(AppRadius.r1_5)),
            )
        }
    }
}

/** 轻量透明度修饰（等价 Flutter `Opacity`，避免为每行引入额外 composable 层级） */
private fun Modifier.playerAlphaLocal(value: Float): Modifier =
    this.graphicsLayer { alpha = value.coerceIn(0f, 1f) }

/**
 * 歌词字号面板（1:1 移植 Flutter `_FontScalePanel`）：
 * 全屏 black54 遮罩 + 底部 surface 面板（顶部圆角 20），5 档字号，选中档主色高亮。
 */
@Composable
fun PlayerFontScalePanel(
    visible: Boolean,
    current: Float,
    onSelect: (Float) -> Unit,
    onClose: () -> Unit,
) {
    if (!visible) return

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AppColors.black54)
                .clickableNoRipple { onClose() },
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = AppRadius.r20, topEnd = AppRadius.r20))
                .background(AppColors.surface)
                .padding(start = AppSpacing.sp20, top = AppSpacing.sp20, end = AppSpacing.sp20, bottom = AppSpacing.sp32),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = "歌词字号",
                color = AppColors.white,
                fontSize = AppFontSize.xl,
                fontWeight = AppFontWeight.bold,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp20))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                PlayerFontScaleLevels.forEach { level ->
                    val selected = level == current
                    Box(
                        modifier = Modifier
                            .size(PlayerScalePanelButtonSize)
                            .clip(RoundedCornerShape(AppRadius.r10))
                            .background(
                                if (selected) AppColors.primary.copy(alpha = 0.2f) else AppColors.whiteA05,
                            )
                            .clickableNoRipple { onSelect(level) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Aa",
                            color = if (selected) AppColors.primary else AppColors.white70,
                            fontSize = (PlayerFontSampleBaseSp + level * PlayerFontSampleStepSp).sp,
                        )
                    }
                }
            }
        }
    }
}

/** 解析歌词并合并翻译；平台占位「暂无歌词」视为空（对齐 Flutter `_parsedLines`） */
private fun playerParseLyricLines(lyric: String?, tlyric: String?): List<LyricLine> {
    if (lyric.isNullOrEmpty()) return emptyList()
    val lines = parseLrcWithTranslation(lyric, tlyric)
    if (lines.size == 1 && lines.first().text == "暂无歌词") return emptyList()
    return lines
}
