package com.annieplayer.annie_music.kt.widgets

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.annieplayer.annie_music.kt.data.model.LoopMode
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import kotlin.math.roundToInt

/** 当前播放行底色：`primaryStrong @ 0.08`（0x146366F1，对齐 Dart `withValues(alpha: 0.08)`）。 */
private val QueueSheetCurrentBg = Color(0x146366F1)

/**
 * 队列行高 —— **必须固定**：拖拽排序靠「偏移量 ÷ 行高」算目标位置，
 * 行高一旦随内容变化，拖到哪一格就说不准了。
 */
private val QueueRowHeight = 56.dp

/** 队列行封面边长 */
private val QueueCoverSize = 40.dp

/**
 * 播放队列浮层。
 *
 * 结构：抓手 / 标题 + 首数 / （随机模式提示）/ 行列表；最大高度 = 屏高 60%。
 *
 * 行：`序号或播放指示器 · 封面 · 标题 / 歌手 · 拖拽手柄 · 移除`。
 * - 当前曲：左侧换成 [PlayingIndicator]（主色律动条）+ 主色标题 + 8% 主色底。
 *   不再像旧版那样在标题前拼「正在播放」四个字 —— 指示器已经说明了这件事，
 *   拼字反而把歌名挤到省略号后面。
 * - **长按任意一行即可拖动排序**（手柄只是视觉提示）。拖拽期间整行抬起
 *   （1.02 倍 + 逐行位移动画），松手时一次性提交给 [onMove]。
 *
 * 与播放模式的关系（见 [onMove] 的调用方 `PlayerViewModel.moveInQueue`）：
 * 顺序 / 单曲模式下拖完立即影响「下一首」；随机模式下的「下一首」是引擎掷的，
 * 拖拽只改列表顺序，所以这里会多显示一行提示，避免「拖了却没按顺序走」的困惑。
 *
 * 动画：150ms + easeOutCubic（[AppDuration.sheet] + [AppEasing.easeOutCubic]），遮罩 60% 黑。
 * 常驻组件树：隐藏时不销毁，仅把面板整体位移出屏 + 遮罩停止吃触摸。
 */
@Composable
fun QueueSheet(
    visible: Boolean,
    queue: List<Song>,
    currentIndex: Int,
    onClose: () -> Unit,
    onPlayIndex: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    loopMode: LoopMode = LoopMode.sequential,
) {
    BackHandler(enabled = visible) { onClose() }

    val scrimAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.easeOutCubic),
        label = "queueSheetScrim",
    )
    val slide by animateFloatAsState(
        targetValue = if (visible) 0f else 1f,
        animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.easeOutCubic),
        label = "queueSheetSlide",
    )

    // RN panelHeight = winH * 0.6
    val panelMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.6f).dp

    // ── 拖拽排序 ──
    // localQueue 只在「拖拽进行中」与 queue 不同：拖拽是纯 UI 重排，
    // 松手才提交一次。边拖边提交会让「拖一半又放回原处」也产生一串 move 调用，
    // 而且每次提交都会重排 ExoPlayer timeline。
    var localQueue by remember(queue) { mutableStateOf(queue) }
    var dragFrom by remember { mutableStateOf(-1) }
    var dragStart by remember { mutableStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowHeightPx = with(LocalDensity.current) { QueueRowHeight.toPx() }
    val dragEnabled = localQueue.size > 1

    // 当前曲按 **id** 认，不按 index：拖拽过程中下标一直在变，用 index 会认错行
    val currentSongId = queue.getOrNull(currentIndex)?.id

    fun applyDrag(dy: Float) {
        if (dragFrom < 0) return
        dragOffset += dy
        // 每跨过一整行就重排一次，并把已消费的位移从偏移里扣掉 ——
        // 行在列表里的位置因此始终跟着手指，不会出现「手指走了、行还在原地」。
        val steps = (dragOffset / rowHeightPx).roundToInt()
        if (steps == 0) return
        val from = dragFrom
        val to = (from + steps).coerceIn(0, localQueue.size - 1)
        if (to == from) return
        localQueue = localQueue.toMutableList().apply { add(to, removeAt(from)) }
        dragFrom = to
        dragOffset -= (to - from) * rowHeightPx
    }

    fun finishDrag() {
        val from = dragStart
        val to = dragFrom
        if (from >= 0 && to >= 0 && from != to) onMove(from, to)
        dragFrom = -1
        dragStart = -1
        dragOffset = 0f
    }

    // 【关键 · 全屏浮层不得吃掉下层点击】
    // Compose 里 `clickable(enabled = false)` **依然占据命中测试**，会阻止事件传给下层兄弟节点。
    // 这些浮层的遮罩是 fillMaxSize，一旦常驻就会把顶栏抽屉按钮 / 搜索框 / 模式下拉 / ⇄ 按钮
    // 的点击全部挡掉（现象：界面能显示，但所有按钮点不动）。
    // 因此完全隐藏时**整个浮层不参与组合**，等价 Flutter 的 IgnorePointer(ignoring: !visible)。
    if (!visible && scrimAlpha == 0f) return
    Box(modifier = modifier.fillMaxSize()) {
        // ── 遮罩：只吞点击，**不关闭**（退出只走返回手势 / 标题栏 ✕） ──
        // `clickable` 必须保留：它是吃掉触摸、阻止点击穿透到下层页面的唯一手段。
        val scrimInteraction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = scrimAlpha }
                .clickable(
                    enabled = visible,
                    interactionSource = scrimInteraction,
                    indication = null,
                    onClick = {},
                )
                .background(AppColors.scrim60),
        )

        // ── 底部面板：自下而上滑入 / 滑出 ──
        Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { translationY = slide * size.height }
                    .background(
                        color = AppColors.surface,
                        shape = RoundedCornerShape(topStart = AppRadius.r20, topEnd = AppRadius.r20),
                    )
                    .heightIn(max = panelMaxHeight)
                    .padding(top = AppSpacing.sp20, bottom = AppSpacing.sp16),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 拖拽条
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .background(AppColors.whiteA20, RoundedCornerShape(AppRadius.r2)),
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp16))

                // 标题行：标题 + 首数胶囊 + 关闭
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.sp20),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "播放队列",
                        color = AppColors.white,
                        fontSize = AppFontSize.size17,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.sp8))
                    // 首数做成胶囊：与大标题同级的信息，但地位是「计数」不是「标题」
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(AppRadius.pill))
                            .background(AppColors.whiteA05)
                            .padding(horizontal = AppSpacing.sp8, vertical = 1.dp),
                    ) {
                        Text(
                            text = "${queue.size}",
                            color = AppColors.white60,
                            fontSize = AppFontSize.xs,
                            fontWeight = AppFontWeight.medium,
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    SheetCloseButton(onClose = onClose)
                }

                // 随机模式提示：拖拽只改列表顺序，不改「下一首」的掷骰结果
                if (loopMode == LoopMode.shuffle && localQueue.size > 1) {
                    Text(
                        text = "随机播放中 · 拖动只调整列表顺序",
                        color = AppColors.white38,
                        fontSize = AppFontSize.xs,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = AppSpacing.sp20, end = AppSpacing.sp20, top = AppSpacing.sp6),
                    )
                }
                Spacer(modifier = Modifier.height(AppSpacing.sp10))

                if (queue.isEmpty()) {
                    QueueSheetEmpty()
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                        contentPadding = PaddingValues(
                            start = AppSpacing.sp8,
                            end = AppSpacing.sp8,
                            bottom = AppSpacing.sp8,
                        ),
                    ) {
                        itemsIndexed(items = localQueue, key = { _, song -> song.id }) { index, song ->
                            val isDragging = index == dragFrom
                            // 【关键】pointerInput 的 block 只在 key 变化时重建，闭包里捕获的
                            // `index` 会一直停在首次组合那一刻。拖过一轮之后列表重排过，
                            // 再长按同一行拿到的就是旧下标 —— 表现为「一拖就跳到别的位置」。
                            // 用 rememberUpdatedState 让手势回调始终读到当前 index。
                            val currentIndexState = rememberUpdatedState(index)
                            QueueSheetItem(
                                song = song,
                                index = index,
                                isCurrent = song.id == currentSongId,
                                canRemove = localQueue.size > 1,
                                dragEnabled = dragEnabled,
                                onPlay = { onPlayIndex(index) },
                                onRemove = { onRemove(index) },
                                modifier = Modifier
                                    // 拖动中的行必须画在其它行之上，否则会被下一行盖住
                                    .zIndex(if (isDragging) 1f else 0f)
                                    // 位移读在 graphicsLayer 的 lambda 里 → 每帧只失效 draw，
                                    // 不会因为拖拽触发整个列表重组
                                    .graphicsLayer {
                                        translationY = if (isDragging) dragOffset else 0f
                                        val s = if (isDragging) 1.02f else 1f
                                        scaleX = s
                                        scaleY = s
                                    }
                                    // 被拖的那行不加位移动画，否则「跟随手指」会和动画打架
                                    .then(if (isDragging) Modifier else Modifier.animateItem())
                                    .pointerInput(dragEnabled) {
                                        if (!dragEnabled) return@pointerInput
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                val at = currentIndexState.value
                                                dragStart = at
                                                dragFrom = at
                                                dragOffset = 0f
                                            },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                applyDrag(amount.y)
                                            },
                                            onDragEnd = { finishDrag() },
                                            onDragCancel = { finishDrag() },
                                        )
                                    },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 空队列提示（对齐 Dart `_empty()`：音符 36 + 「队列为空」）。 */
@Composable
private fun QueueSheetEmpty() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = AppSpacing.sp40),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MusicNoteGlyph(
            modifier = Modifier.size(AppIconSize.icon36),
            tint = AppColors.glass,
        )
        Spacer(modifier = Modifier.height(AppSpacing.sp8))
        Text(
            text = "队列为空",
            color = AppColors.whiteA20,
            fontSize = AppFontSize.base,
        )
    }
}

/**
 * 队列单行：序号 / 播放指示器 · 封面 · 标题 + 歌手 · 拖拽手柄 · 移除。
 *
 * 高度固定 [QueueRowHeight]（拖拽排序要按行高换算目标位置）。
 * 手柄与移除按钮各自 36dp 命中区，靠 `clip` + 内边距把视觉收到 24dp。
 */
@Composable
private fun QueueSheetItem(
    song: Song,
    index: Int,
    isCurrent: Boolean,
    canRemove: Boolean,
    dragEnabled: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(QueueRowHeight)
            .clip(RoundedCornerShape(AppRadius.r10))
            .background(if (isCurrent) QueueSheetCurrentBg else Color.Transparent)
            .clickableNoRipple(onPlay)
            .padding(horizontal = AppSpacing.sp8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 序号槽：当前曲换成律动条（固定宽度，切换时不会让整行左右跳）
        Box(modifier = Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            if (isCurrent) {
                PlayingIndicator(tint = AppColors.primary)
            } else {
                Text(
                    text = "${index + 1}",
                    color = AppColors.whiteA25,
                    fontSize = AppFontSize.xs,
                    fontWeight = AppFontWeight.medium,
                )
            }
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp8))

        Box(
            modifier = Modifier
                .size(QueueCoverSize)
                .clip(RoundedCornerShape(AppRadius.r8))
                .background(AppColors.whiteA05),
            contentAlignment = Alignment.Center,
        ) {
            if (song.cover.isNotBlank()) {
                AsyncImage(
                    model = song.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                MusicNoteGlyph(
                    modifier = Modifier.size(AppIconSize.icon18),
                    tint = AppColors.whiteA15,
                )
            }
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp12))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                color = if (isCurrent) AppColors.primary else AppColors.white,
                fontSize = AppFontSize.size15,
                fontWeight = if (isCurrent) AppFontWeight.semiBold else AppFontWeight.medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp1))
            Text(
                text = song.artist,
                color = AppColors.white38,
                fontSize = AppFontSize.xs,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (dragEnabled) {
            // 手柄只是「可以拖」的视觉提示，真正的拖拽区是整行（长按）
            Box(modifier = Modifier.size(AppIconSize.icon24), contentAlignment = Alignment.Center) {
                QueueDragHandleGlyph(
                    modifier = Modifier.size(width = 10.dp, height = 16.dp),
                    tint = AppColors.glyphDim,
                )
            }
            Spacer(modifier = Modifier.width(AppSpacing.sp4))
        }

        if (canRemove) {
            Box(
                modifier = Modifier
                    .clickableNoRipple(onRemove)
                    .size(AppIconSize.icon24 + AppSpacing.sp12),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "从队列移除",
                    tint = AppColors.glyphDim,
                    modifier = Modifier.size(AppIconSize.icon16),
                )
            }
        }
    }
}

/**
 * 拖拽手柄：2 列 × 3 行的圆点（`material-icons-core` 里没有 drag_handle 的等价物，
 * 且系统 `Reorder` 图标是 24 栅格的横线，与这里的点阵风格不统一）。
 */
@Composable
private fun QueueDragHandleGlyph(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier = modifier) {
        val dot = size.minDimension * 0.11f
        val gapX = size.width * 0.42f
        val gapY = size.height * 0.31f
        for (row in 0..2) {
            for (col in 0..1) {
                drawCircle(
                    color = tint,
                    radius = dot,
                    center = Offset(
                        x = size.width / 2f + (col - 0.5f) * gapX,
                        y = size.height / 2f + (row - 1f) * gapY,
                    ),
                )
            }
        }
    }
}
