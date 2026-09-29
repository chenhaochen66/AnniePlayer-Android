package com.annieplayer.annie_music.kt.features.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.annieplayer.annie_music.kt.core.state.AppTab
import com.annieplayer.annie_music.kt.ui.theme.AppBorder
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppShadow
import com.annieplayer.annie_music.kt.ui.theme.AppSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.LocalLibraryGlyph
import com.annieplayer.annie_music.kt.widgets.MiniProgressBar
import com.annieplayer.annie_music.kt.widgets.MovingText
import com.annieplayer.annie_music.kt.widgets.SearchGlyph
import com.annieplayer.annie_music.kt.widgets.VinylRecord
import com.annieplayer.annie_music.kt.widgets.clickableNoRipple
import kotlin.math.roundToInt

/** 底栏左右按钮的横向内边距（10dp：贴边但不顶边，胶囊转角处也不会越界） */
private val BottomBarSidePadding = AppSpacing.sp10

/** 迷你态封面左侧起始位置（与 [BottomBarSidePadding] 对齐，左滑终点） */
private val BottomBarMiniCoverStart = AppSpacing.sp10

/** 迷你态封面与右侧文字之间的间距 */
private val BottomBarMiniGap = AppSpacing.sp12

/** 「正在播放」指示点直径 */
private val PlayingDotSize = 12.dp

/** 「正在播放」指示点外环宽度（取页面底色，把点从封面颜色里「抠」出来） */
private val PlayingDotRing = 2.dp

/**
 * 常驻底栏（Android-only 设计，Flutter 基线无对应实现）。
 *
 * ## 一个胶囊、两种内容
 * 底栏始终是**同一颗胶囊**（同宽同高同位置），只有「内容」在两种状态间变形：
 * - **底栏态**（主页面：搜索 / 本地曲库）：左「搜索」、中封面盒、右「本地曲库」；
 * - **迷你播放器态**（其余二级页面：播放页之前的抽屉 / 详情 sheet / 详情路由等）：
 *   封面**左滑**到左侧，左右两个 tab **淡出**，标题 / 歌手 / 播放暂停 / 进度条**淡入**。
 *
 * 切换动画由 [morph]（0=底栏态，1=迷你态）统一驱动，反向切换即原路返回：
 * 封面位移、tab 透明度、迷你元素透明度三者共用同一条 260ms 缓动。
 *
 * ## 几何（关键：玻璃铺到屏幕底边，底部不留缝）
 * 玻璃面板高度 = 内容区 [AppSize.bottomBarHeight] + 传入的 [bottomInset]（系统导航 / 手势条
 * 高度）。**必须由调用方传入与父层底部内缩同源的值**（宿主读 `WindowInsets.systemBars`），
 * 否则补偿量与实际内缩不一致，会在胶囊下方留出一条没被玻璃盖住的缝、下层列表透色。
 * 内容层只有 [AppSize.bottomBarHeight] 高并贴顶 → tab 与内容都在手势条之上。
 *
 * ## 结构（同一父 Box 下的**兄弟节点**，没有嵌套命中区，因此不会双触发）
 * ① 玻璃面板（非全宽 [AppSize.bottomBarWidthFraction]、半透明 + 高光渐变 + 细描边 + 柔和投影，
 * 并作为「触摸屏蔽层」消费空白点击）；② 内容层（含封面、迷你元素、两个 tab）。
 *
 * 毛玻璃说明：Compose 无法对节点「背后的内容」做真实模糊，`Modifier.blur` 只会糊掉自身内容，
 * 因此走 **alpha 合成**：半透明底（surfaceLight @0.82）+ 顶部白色高光渐变 + 1dp 高光描边 +
 * 柔和外投影。不依赖 RenderEffect，**API < 31 不退化**，栏内文字 / 图标始终清晰。
 *
 * @param selectedTab 当前 tab（决定左右按钮选中态）。
 * @param onSelectTab 切换 tab（仅底栏态可点）。
 * @param isMiniPlayer true = 迷你播放器态（二级页面），false = 底栏态（主页面）。
 * @param bottomInset 系统底部内缩（必须与父层内缩同源，用于把玻璃铺到屏幕底边）。
 * @param coverUrl 当前歌曲封面（可为空）。
 * @param fallbackCoverUrl 封面兜底（取色器缓存的本地封面路径）。
 * @param title 当前歌曲标题（空串 = 无歌，迷你元素降级为占位文案）。
 * @param artist 当前歌曲歌手。
 * @param isPlaying 是否正在播放（驱动封面上的「在播」小点）。
 * @param currentTime 当前播放进度（秒）。
 * @param duration 当前歌曲总时长（秒）。
 * @param onOpenPlayer 打开全屏播放页（封面 / 迷你文字区均可触发）。
 */
@Composable
fun AppBottomBar(
    selectedTab: AppTab,
    onSelectTab: (AppTab) -> Unit,
    isMiniPlayer: Boolean,
    bottomInset: Dp,
    coverUrl: String,
    fallbackCoverUrl: String,
    title: String,
    artist: String,
    isPlaying: Boolean,
    currentTime: Double,
    duration: Double,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val barHeight = AppSize.bottomBarHeight
    val barShape = RoundedCornerShape(percent = 50)
    val coverSize = AppSize.bottomBarCoverSize
    val panelHeight = barHeight + bottomInset

    val effectiveCover = coverUrl.ifEmpty { fallbackCoverUrl }
    val hasSong = title.isNotEmpty()

    // 0 = 底栏态；1 = 迷你播放器态。三者（封面位移 / tab 淡出 / 迷你元素淡入）共用这一条进度。
    val morph by animateFloatAsState(
        targetValue = if (isMiniPlayer) 1f else 0f,
        animationSpec = tween(
            durationMillis = AppDuration.bottomBarMorph,
            easing = AppEasing.easeInOutQuad,
        ),
        label = "bottomBarMorph",
    )

    Box(
        modifier = modifier
            .fillMaxWidth(AppSize.bottomBarWidthFraction)
            // 整块高度 = 内容区 + 底部内缩：玻璃一直铺到屏幕底边（消除底部缝隙）
            .requiredHeight(panelHeight),
    ) {
        // ── ① 玻璃面板（铺满整块，含底部 inset 带） ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .dropShadow(
                    shape = barShape,
                    shadow = Shadow(
                        radius = AppShadow.blur2xl,
                        color = AppColors.black.copy(alpha = 0.55f),
                        offset = DpOffset(x = 0.dp, y = AppShadow.miniPlayerOffsetY),
                    ),
                )
                .clip(barShape)
                // 半透明底：下层内容透过玻璃可见（毛玻璃的「透」）
                .background(AppColors.surfaceLight.copy(alpha = 0.82f))
                // 顶部白色高光渐变：模拟玻璃受光面（毛玻璃的「毛」）
                .background(Brush.verticalGradient(listOf(AppColors.glass, Color.Transparent)))
                // 极细高光描边：玻璃边缘
                .border(AppBorder.thin, AppColors.glassBorder, barShape)
                // 触摸屏蔽：空白区域消费点击，避免穿透到下层列表（空实现、无点击语义）
                .clickableNoRipple { },
        )

        // ── ② 内容层：只有内容区那么高、贴顶 → 内容天然避开手势条 ──
        BoxWithConstraints(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(barHeight),
        ) {
            val width = maxWidth
            val density = LocalDensity.current

            // 封面横向位置：底栏态居中 → 迷你态贴左（由 morph 插值，即「左滑」）
            val coverLeft = (width - coverSize) / 2 * (1f - morph) + BottomBarMiniCoverStart * morph

            // 迷你态文字 / 进度条的横向占用（避开左侧封面；右侧已无播放钮 → 直接贴右内边距）
            val miniTextStart = BottomBarMiniCoverStart + coverSize + BottomBarMiniGap
            val miniTextEnd = BottomBarSidePadding

            // ── ②-a 迷你播放器元素（淡入） ──
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = miniTextStart, end = miniTextEnd)
                    .alpha(morph)
                    .clickableNoRipple { if (isMiniPlayer) onOpenPlayer() },
                verticalArrangement = Arrangement.Center,
            ) {
                MovingText(
                    text = if (hasSong) title else "暂无播放",
                    threshold = 15,
                    style = TextStyle(
                        color = Color.White,
                        fontSize = AppFontSize.size17,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp4))
                Text(
                    text = if (hasSong) artist else "搜索并播放一首歌",
                    color = AppColors.white38,
                    fontSize = AppFontSize.base,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = miniTextStart, end = miniTextEnd)
                    .alpha(morph),
            ) {
                MiniProgressBar(
                    position = currentTime,
                    duration = duration,
                    height = AppSize.bottomBarMiniProgressHeight,
                )
            }

            // ── ②-b 左右 tab（淡出；迷你态禁点，避免透明热区截走点击） ──
            BottomBarTab(
                selected = selectedTab == AppTab.Search,
                enabled = !isMiniPlayer,
                onClick = { onSelectTab(AppTab.Search) },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = BottomBarSidePadding)
                    .alpha(1f - morph),
                icon = { tint -> SearchGlyph(modifier = Modifier.size(AppIconSize.icon22), tint = tint) },
            )
            BottomBarTab(
                selected = selectedTab == AppTab.Library,
                enabled = !isMiniPlayer,
                onClick = { onSelectTab(AppTab.Library) },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = BottomBarSidePadding)
                    .alpha(1f - morph),
                icon = { tint -> LocalLibraryGlyph(modifier = Modifier.size(AppIconSize.icon22), tint = tint) },
            )

            // ── ②-c 封面（两态共用**同一个**元素，靠位移实现「左滑」，保证视觉连续） ──
            BottomBarCover(
                coverUrl = effectiveCover,
                isPlaying = isPlaying,
                onOpenPlayer = onOpenPlayer,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset {
                        IntOffset(with(density) { coverLeft.toPx() }.roundToInt(), 0)
                    },
            )
        }
    }
}

/**
 * 底栏中键封面盒：底栏态居中、迷你态左滑到左侧（同一元素），点击打开全屏播放页。
 * 无歌 / 封面加载失败时显示自绘黑胶 [VinylRecord]。
 */
@Composable
private fun BottomBarCover(
    coverUrl: String,
    isPlaying: Boolean,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val coverShape = RoundedCornerShape(AppRadius.r16)
    // 封面加载失败 → 回退黑胶占位；换 URL 后自动复位（key = 封面地址）
    var coverFailed by remember(coverUrl) { mutableStateOf(false) }
    val showVinyl = coverUrl.isEmpty() || coverFailed

    Box(
        modifier = modifier
            .size(AppSize.bottomBarCoverSize)
            .dropShadow(
                shape = coverShape,
                shadow = Shadow(
                    radius = AppShadow.blurXl,
                    color = AppColors.black.copy(alpha = 0.50f),
                    offset = DpOffset(x = 0.dp, y = AppShadow.headerOffsetY),
                ),
            )
            .clip(coverShape)
            .background(AppColors.whiteA03)
            .border(AppBorder.thin, AppColors.whiteA15, coverShape)
            .clickableNoRipple(onOpenPlayer),
        contentAlignment = Alignment.Center,
    ) {
        when {
            // 无歌 / 封面加载失败：黑胶占位（内缩 4dp，盘体不贴框边）
            showVinyl -> VinylRecord(
                modifier = Modifier.fillMaxSize().padding(AppSpacing.sp4),
            )
            else -> AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { coverFailed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 「正在播放」小点：只在有真实封面时出现（黑胶态本就没有在播的歌）
        if (isPlaying && !showVinyl) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = AppSpacing.sp4, bottom = AppSpacing.sp4)
                    .size(PlayingDotSize)
                    .background(AppColors.primary, CircleShape)
                    .border(PlayingDotRing, AppColors.background, CircleShape),
            )
        }
    }
}

/**
 * 底栏 tab 按钮：48dp 命中区（外层，唯一 clickable）+ 40dp 选中胶囊（内层，纯视觉）。
 * 选中态用「主色 15% 胶囊 + 纯白图标」，未选中用「透明 + whiteA45 图标」，不额外加文字。
 * [enabled] 为 false 时（迷你播放器态）不可点，避免淡出后的透明热区截走点击。
 */
@Composable
private fun BottomBarTab(
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (Color) -> Unit,
) {
    Box(
        modifier = modifier
            .size(AppSize.bottomBarTabHitSize)
            .clickableNoRipple { if (enabled) onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(AppSize.bottomBarTabPillSize)
                .background(
                    color = if (selected) AppColors.primaryA15 else Color.Transparent,
                    shape = RoundedCornerShape(AppRadius.r10),
                ),
            contentAlignment = Alignment.Center,
        ) {
            icon(if (selected) AppColors.white else AppColors.whiteA45)
        }
    }
}
