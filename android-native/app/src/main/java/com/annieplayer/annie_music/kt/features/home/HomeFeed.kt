package com.annieplayer.annie_music.kt.features.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.annieplayer.annie_music.kt.data.model.HomeCardStyle
import com.annieplayer.annie_music.kt.data.model.HomeItem
import com.annieplayer.annie_music.kt.data.model.HomeShelf
import com.annieplayer.annie_music.kt.data.model.HomeTarget
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.PlayingIndicator
import com.annieplayer.annie_music.kt.widgets.SkeletonLoader
import com.annieplayer.annie_music.kt.widgets.SongMoreButton
import com.annieplayer.annie_music.kt.widgets.SongRowPager
import com.annieplayer.annie_music.kt.widgets.clickableRipple
import com.annieplayer.annie_music.kt.widgets.playingRowBackground
import com.annieplayer.annie_music.kt.widgets.shortScrollbar

// ================================================================================================
// 主页内容区（`AppTab.Home` 的浏览态）
// ================================================================================================
//
// ## 数据从哪来
// [HomeFeedState]（宿主 `AppShell` 持有）→ `fetchHomeShelves(platform)`。
// 源与搜索共用 `SearchScreenState.searchPlatform`（右下角 ⇄ 按钮切换），
// 所以「搜索选网易、主页也是网易」是同一个状态，不会错位。
//
// ## 三种区块形态
// | 形态 | 组件 | 观感 |
// |---|---|---|
// | `HomeShelf.Songs` | [SongRowPager] | 与歌手详情「最热门」一致：5 行/页，左右翻页，最多 50 首 |
// | `Cards` + [HomeCardStyle.Playlist] | `HomePlaylistCard` | 封面即海报：168dp 方图，标题压在封面下缘 |
// | `Cards` + [HomeCardStyle.Album] | `HomeAlbumCard` | 横向「唱片条」：左方图 + 右侧 ALBUM 微标签 |
// | `Cards` + [HomeCardStyle.Artist] | `HomeArtistCard` | 圆形头像（留给歌手区块） |
//
// 横向卡片**最多展示 10 个**；超出的进「全部」页（`HomeShelfRoute`），入口是区块标题右端的
// 「全部」文字按钮 —— 与歌手详情「最热门」右端的「全部」同一套交互。
//
// ## 三条硬约束
// 1. **本层不铺任何不透明底色**：主页和搜索态共用 `HomeScreen` 最底层的 `CoverPaletteBackdrop`
//    （正在播放封面取色），铺了就把它闷死。卡片自己的圆角底 + 渐变遮罩不受此限。
// 2. **Pager 高度必须固定**（[SongRowPager] 已封装），`LazyColumn` 里不能让它自己量。
// 3. **区块不做进入动画**。本层是 `LazyColumn`，滑出视口的区块会被回收、滚回来重新组合，
//    任何「按 index 错开的淡入 + 位移」都会重播一遍，观感就是「滑一下就消失再重建」。
//    同理也不要对区块做尺寸动画：尺寸变化触发每帧重新布局，滚动时直接掉帧。

/** 横向卡片一屏放的展示上限；多出来的进「全部」页。 */
private const val HomeVisibleCards = 10

/** 歌单卡边长（封面即海报，比专辑条更"重"）。 */
private val PlaylistCardSize = 168.dp

/** 专辑「唱片条」尺寸：左侧方图 + 右侧三行文字。 */
private val AlbumCardWidth = 236.dp
private val AlbumCardHeight = 96.dp
private val AlbumCoverSize = 80.dp

/** 歌手圆形头像直径。 */
private val ArtistCardSize = 104.dp

/** 卡片按下时的缩放（视觉回弹，不改变布局尺寸）。 */
private const val CardPressedScale = 0.94f

/** 骨架块底色（与 `SkeletonLoader` 内部同值，避免两处不一致）。 */
private val SkeletonColor = Color(0x1AFFFFFF)

/**
 * 主页内容区。
 *
 * @param state 数据状态机（宿主持有，跨形态切换不丢）
 * @param currentSongId 正在播放的歌曲 id（用于行内等化器）
 * @param onPlay 点某一行 → 以该区块的整段歌曲作为播放队列
 * @param onMore 行右侧 ⋮ → 单曲操作面板（与搜索结果那套同一个入口）
 * @param onOpenTarget 点卡片 → 压入详情栈（专辑 / 歌手 / 歌单）
 * @param onSeeAll 点区块「全部」→ 压入该区块的完整列表页
 */
@Composable
internal fun HomeFeed(
    state: HomeFeedState,
    currentSongId: String?,
    onPlay: (Song, List<Song>) -> Unit,
    onMore: (Song) -> Unit,
    onOpenTarget: (HomeTarget) -> Unit,
    onSeeAll: (HomeShelf.Cards) -> Unit,
    onShelfAction: (HomeShelfAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shelves = state.shelves
    when {
        shelves.isNotEmpty() -> {
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = modifier
                    .fillMaxSize()
                    .shortScrollbar(listState),
                contentPadding = PaddingValues(
                    start = AppSpacing.sp16,
                    end = AppSpacing.sp16,
                    top = AppSpacing.sp12,
                    // 与搜索结果页同一个底部预留：右下角 ⇄ 源切换按钮顶边在 148dp，
                    // 用 listBottomInset(120) 会让最后一行区块被按钮压住。
                    bottom = AppSpacing.searchListBottomInset,
                ),
                // 区块间距比原来（24）再放一档：卡片变大之后 24 会显得挤
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sp28),
            ) {
                items(shelves, key = { shelf -> shelf.id }) { shelf ->
                    HomeShelfBlock(
                        shelf = shelf,
                        currentSongId = currentSongId,
                        onPlay = onPlay,
                        onMore = onMore,
                        onOpenTarget = onOpenTarget,
                        onSeeAll = onSeeAll,
                        onShelfAction = onShelfAction,
                    )
                }
            }
        }

        state.loading || !state.loaded -> HomeFeedSkeleton(modifier = modifier)

        else -> HomeFeedEmpty(modifier = modifier)
    }
}

// ================================================================================================
// 区块
// ================================================================================================

/**
 * 一个区块：标题行 + 内容。
 *
 * **刻意没有进入动画**：本函数是 `LazyColumn` 的 item 内容，滑出视口就被回收，
 * 滚回来重新组合时 `remember` 里的进度会从 0 重来 —— 上一版在这里做了「按 index 错开的
 * 淡入 + 上移」，结果滚回去一片一片重新淡入，观感就是「滑动时区块消失再重建」。
 * 一旦渲染出来，它就应该原地不动。
 */
@Composable
private fun HomeShelfBlock(
    shelf: HomeShelf,
    currentSongId: String?,
    onPlay: (Song, List<Song>) -> Unit,
    onMore: (Song) -> Unit,
    onOpenTarget: (HomeTarget) -> Unit,
    onSeeAll: (HomeShelf.Cards) -> Unit,
    onShelfAction: (HomeShelfAction) -> Unit,
) {
    val cards = shelf as? HomeShelf.Cards
    val showSeeAll = cards != null && cards.items.size > HomeVisibleCards
    // 标题前的图形徽标：按区块语义取（新歌=星、歌单=列表、专辑=唱片、歌手=头像）
    val glyphKind = when (shelf) {
        is HomeShelf.Songs -> HomeShelfGlyphKind.Sparkle
        is HomeShelf.Cards -> when (shelf.items.firstOrNull()?.style) {
            HomeCardStyle.Album -> HomeShelfGlyphKind.Album
            HomeCardStyle.Artist -> HomeShelfGlyphKind.Artist
            else -> HomeShelfGlyphKind.Playlist
        }
    }

    // 区块右上角的操作：目前只有「为你挑选」有（换一批）。
    // 「按风格听」不再需要按钮 —— 它自己就是一行可选的流派卡。
    val actionGlyph: HomeActionGlyph? = when (shelf.id) {
        "kugou:discover" -> HomeActionGlyph.RefreshPicks
        else -> null
    }
    val action: HomeShelfAction? = when (actionGlyph) {
        HomeActionGlyph.RefreshPicks -> HomeShelfAction.RefreshPicks
        HomeActionGlyph.CycleStyle -> HomeShelfAction.CycleStyle
        null -> null
    }

    Column {
        HomeShelfHeader(
            title = shelf.title,
            subtitle = shelf.subtitle,
            glyph = glyphKind,
            actionGlyph = actionGlyph,
            onAction = if (action != null) ({ onShelfAction(action) }) else null,
            onSeeAll = if (showSeeAll) ({ onSeeAll(cards!!) }) else null,
        )
        Spacer(modifier = Modifier.height(AppSpacing.sp12))
        when (shelf) {
            is HomeShelf.Songs -> SongRowPager(items = shelf.songs) { song, songIndex ->
                HomeSongRow(
                    song = song,
                    index = songIndex,
                    isActive = song.id == currentSongId,
                    onPlay = { onPlay(song, shelf.songs) },
                    onMore = { onMore(song) },
                    // 「歌曲」货架是横滑分页、每页 5 行 —— 序号在这里是噪声
                    showIndex = false,
                    // ⋮ 贴到页右边界：页宽已比屏幕窄，再留 16dp 会显得缩在中间
                    trailingSpace = 0.dp,
                )
            }

            is HomeShelf.Cards -> if (shelf.items.firstOrNull()?.style == HomeCardStyle.Hero) {
                HomeHeroRow(items = shelf.items, onOpenTarget = onOpenTarget)
            } else {
                HomeCardRow(items = shelf.items, onOpenTarget = onOpenTarget)
            }
        }
    }
}

/**
 * 区块标题：左侧主色渐变竖条 + 主标题 + 灰色副标题 + 右端「全部」。
 *
 * ## 为什么副标题要吃满权重、「全部」不吃
 * 之前副标题用 `weight(1f, fill = false)`，同时又给「全部」前面挂了一个 `Spacer(weight(1f))`：
 * 两个带权重的子项会**平分**剩余空间，副标题只画自己需要的那点宽度，但它占下的槽位不会
 * 让给后面 —— 于是「全部」被卡在中间，三个区块的副标题长度不同，右缘就一个比一个靠左
 * （实测右缘 1093 / 1063 / 1033 px）。现在只留副标题一个权重（默认 `fill = true`）吃满，
 * 「全部」自然被顶到内容区最右边。
 *
 * ## 右端为什么不留内边距
 * 内容区已经有 `sp16` 边距（LazyColumn 的 contentPadding），再留就会比主页第二行那个
 * 进搜索态的 🔍（命中区右缘同样在 sp16 处）缩进去。`end = 0` 才是与它「同一条右边线」。
 */
@Composable
private fun HomeShelfHeader(
    title: String,
    subtitle: String,
    glyph: HomeShelfGlyphKind,
    actionGlyph: HomeActionGlyph?,
    onAction: (() -> Unit)?,
    onSeeAll: (() -> Unit)?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // 图形徽标（替代原来那根渐变竖条）：28dp 渐变圆底 + 主色细环 + 自绘图形
        HomeShelfGlyphChip(kind = glyph)
        Spacer(modifier = Modifier.width(AppSpacing.sp8))
        Text(
            text = title,
            color = AppColors.white,
            fontSize = AppFontSize.size17,
            fontWeight = AppFontWeight.bold,
        )
        // 唯一带权重的子项：吃满标题与「全部」之间的所有空间，把「全部」顶到最右
        Spacer(modifier = Modifier.width(AppSpacing.sp8))
        Text(
            text = subtitle,
            color = AppColors.white38,
            fontSize = AppFontSize.size13,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // 区块操作（换风格 / 换一批）：44dp 命中区，天然占满行高，不需要额外留白
        if (actionGlyph != null && onAction != null) {
            HomeShelfActionButton(glyph = actionGlyph, onClick = onAction)
        }
        if (onSeeAll != null) {
            Spacer(modifier = Modifier.width(AppSpacing.sp8))
            Text(
                text = "全部",
                color = AppColors.whiteA50,
                fontSize = AppFontSize.size13,
                modifier = Modifier
                    .clickableRipple(onSeeAll)
                    .padding(vertical = AppSpacing.sp4),
            )
        }
    }
}

// ================================================================================================
// 歌曲行（与歌手详情「最热门」同一套几何：序号列 28 + 封面 40 + 两行文字，行高 60 + 右侧 ⋮）
// `showIndex = false` 只给 `SongRowPager` 那一段用；酷狗风格浮层（长列表）仍显示序号。
// ================================================================================================

@Composable
internal fun HomeSongRow(
    song: Song,
    index: Int,
    isActive: Boolean,
    onPlay: () -> Unit,
    onMore: () -> Unit,
    /** 行首序号列是否显示（「歌曲」货架是横滑分页，序号是噪声；长列表照旧显示） */
    showIndex: Boolean = true,
    /** 行尾留白；分页容器里传 0，否则 ⋮ 会缩在页内显得不够靠右 */
    trailingSpace: Dp = AppSpacing.sp16,
    /** 行内左侧留白；分页容器 contentPadding 为 0，传 16dp 才与上方货架左边对齐 */
    leadingSpace: Dp = AppSpacing.sp12,
) {
    val playingTint = AppColors.playingGray
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = trailingSpace),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(AppSize.detailRowHeight)
                .then(
                    if (isActive) {
                        // 竖条贴行左边界（内缩 2dp），与封面之间留出 10dp 空隙
                        Modifier.playingRowBackground(tint = playingTint, startInset = 2.dp)
                    } else {
                        Modifier
                    },
                )
                .clickableRipple(onPlay)
                .padding(
                    start = leadingSpace,
                    end = AppSpacing.sp12,
                    top = AppSpacing.sp8,
                    bottom = AppSpacing.sp8,
                ),
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showIndex) {
                    Box(
                        modifier = Modifier.width(AppSpacing.sp28),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isActive) {
                            PlayingIndicator(tint = playingTint)
                        } else {
                            Text(
                                text = "${index + 1}",
                                color = AppColors.white60,
                                fontSize = AppFontSize.size15,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(AppSpacing.sp8))
                }
                Box(contentAlignment = Alignment.Center) {
                    HomeSongCover(song = song)
                    if (isActive && !showIndex) {
                        // 序号列不显示时，等化器叠在封面中心（46% 黑罩保证看得清）
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clip(RoundedCornerShape(AppRadius.r6))
                                .background(Color.Black.copy(alpha = 0.46f)),
                        )
                        PlayingIndicator(tint = playingTint)
                    }
                }
                Spacer(modifier = Modifier.width(AppSpacing.sp10))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        color = if (isActive) playingTint else AppColors.textPrimary,
                        fontSize = AppFontSize.lg,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.sp1))
                    Text(
                        text = if (song.album.isNullOrEmpty()) {
                            song.artist
                        } else {
                            "${song.artist} · ${song.album}"
                        },
                        color = AppColors.textTertiary,
                        fontSize = AppFontSize.size13,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HomeSongQualityBadges(song)
            }
        }
        SongMoreButton(onClick = onMore)
    }
}

/**
 * 音质角标（与歌手详情同一套规则）。
 *
 * 只标「特殊规格」：高解析 / Atmos / 无损 → `Hi-Res`（Apple 另外单列 `Dolby`）。
 * 普通 320K / 128K 不占位 —— 首页行宽有限，人人都有档位标签等于没有标签。
 */
private fun homeQualityBadges(song: Song): List<String> =
    if (song.platform == MusicPlatform.apple) {
        buildList {
            if (Quality.hires in song.qualities) add("Hi-Res")
            if (Quality.atmos in song.qualities) add("Dolby")
        }
    } else {
        if (song.qualities.any { it == Quality.hires || it == Quality.atmos || it == Quality.alac }) {
            listOf("Hi-Res")
        } else {
            emptyList()
        }
    }

@Composable
private fun HomeSongQualityBadges(song: Song) {
    for (badge in homeQualityBadges(song)) {
        Spacer(modifier = Modifier.width(AppSpacing.sp4))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 25))
                .background(AppColors.primaryA15)
                .padding(horizontal = AppSpacing.sp4, vertical = 1.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = badge, color = AppColors.primary, fontSize = AppFontSize.size10)
        }
    }
}

/** 40dp 圆角封面（与详情页歌曲行同尺寸）；无图 / 加载失败显示音符占位。 */
@Composable
private fun HomeSongCover(song: Song) {
    Box(
        modifier = Modifier
            .size(AppSpacing.sp40)
            .clip(RoundedCornerShape(AppRadius.r6))
            .background(AppColors.whiteA03),
        contentAlignment = Alignment.Center,
    ) {
        var failed by remember(song.id) { mutableStateOf(false) }
        if (song.cover.isEmpty() || failed) {
            Text(
                text = "♪",
                color = AppColors.whiteA15,
                fontSize = AppFontSize.size15,
            )
        } else {
            AsyncImage(
                model = song.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// ================================================================================================
// 卡片行
// ================================================================================================

@Composable
private fun HomeCardRow(
    items: List<HomeItem>,
    onOpenTarget: (HomeTarget) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = AppSpacing.sp4),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sp14),
    ) {
        // 只渲染前 HomeVisibleCards 个；其余的走「全部」页（数据已经在内存里，不额外请求）
        items(items.take(HomeVisibleCards), key = { it.key }) { item ->
            val click: () -> Unit = { item.target?.let(onOpenTarget) }
            when (item.style) {
                HomeCardStyle.Playlist -> HomePlaylistCard(item, click)
                HomeCardStyle.Album -> HomeAlbumCard(item, click)
                HomeCardStyle.Artist -> HomeArtistCard(item, click)
                HomeCardStyle.Style -> HomeStyleCard(item, click)
                // Hero 走 HomeHeroRow；万一混进普通行（服务端换位），按歌单卡兜底，不至于空着
                HomeCardStyle.Hero -> HomePlaylistCard(item, click)
            }
        }
    }
}

/** 精选大图轮播每张停留时长。 */
private const val HomeHeroHoldMs = 3500L

/** 精选大图卡占屏宽的比例（留一点右边，提示「还能往左滑」）。 */
private const val HomeHeroWidthFraction = 0.88f

/**
 * 精选大图行（目前只有酷我「精选歌单」）：**封面完整显示** + **自动无限轮播**。
 *
 * 为什么不用普通的 168dp 方图行：破坏的是「显示不全」——酷我这类运营位给的是宽图
 * （`star/upload/ecom` 目录下那批），`ContentScale.Crop` 到 1:1 会砍掉左右各一大块。
 * 这里用 16:9 容器 + `ContentScale.Fit`，宽图完整、方图靠底板色补边，两个方向都不裁。
 *
 * ## 为什么要渲染两份、而不是 `% size` 往回跳
 * 卡片占屏宽 88%，**最后几张必然被 clamp**（滚不动了）：这时
 * `firstVisibleItemIndex` 停在倒数第二三张不再增长，按索引取模算出来的"下一张"会往回退，
 * 表现就是「轮播完一遍就停在那里」。所以这里把内容按两份渲染（`visible + visible`），
 * 于是"越过末尾"就等于走到第二份的开头 —— 位置永远滚得动，视觉上一直向前；
 * 越过之后再用 `scrollToItem(i - size)` **无声归位**（两份内容一模一样，看不出跳）。
 *
 * 另外两条：
 * 1. 只有一张时不动（`size <= 1` 直接返回），否则每秒都在原地 animate；
 * 2. 用户手指还在滑（`isScrollInProgress`）时跳过这一拍，不跟手抢。
 */
@Composable
private fun HomeHeroRow(
    items: List<HomeItem>,
    onOpenTarget: (HomeTarget) -> Unit,
) {
    val visible = remember(items) { items.take(HomeVisibleCards) }
    if (visible.isEmpty()) return
    // 两份内容：让"末尾之后"永远还有下一张，滚动位置不会被 clamp 住
    val looped = remember(visible) { visible + visible }
    val listState = rememberLazyListState()
    LaunchedEffect(looped.map { it.key }) {
        if (visible.size <= 1) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(HomeHeroHoldMs)
            if (listState.isScrollInProgress) continue
            val next = listState.firstVisibleItemIndex + 1
            listState.animateScrollToItem(next)
            // 已经停在第二份里（内容＝第一份同位置）→ 无声退回第一份，用户看不出跳
            if (next >= visible.size) listState.scrollToItem(next - visible.size)
        }
    }
    LazyRow(
        state = listState,
        contentPadding = PaddingValues(horizontal = AppSpacing.sp4),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sp12),
    ) {
        // key 带下标：两份内容里有重复 key，LazyRow 不允许
        itemsIndexed(looped, key = { index, item -> "$index:${item.key}" }) { _, item ->
            HomeHeroCard(
                item = item,
                modifier = Modifier.fillParentMaxWidth(HomeHeroWidthFraction),
                onClick = { item.target?.let(onOpenTarget) },
            )
        }
    }
}

/** 精选大图卡：图在上面完整显示，标题放图下面（压在图上会挡住内容）。 */
@Composable
private fun HomeHeroCard(
    item: HomeItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    PressableCard(onClick = onClick, modifier = modifier) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(AppRadius.r20))
                    .background(AppColors.whiteA03),
            ) {
                HomeCoverImage(
                    item = item,
                    placeholderSize = AppFontSize.size26,
                    scale = ContentScale.Fit,
                )
                if (item.badge != null) {
                    HomeBadge(
                        text = item.badge,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(AppSpacing.sp8),
                    )
                }
            }
            Spacer(modifier = Modifier.height(AppSpacing.sp8))
            Text(
                text = item.title,
                color = AppColors.white,
                fontSize = AppFontSize.size15,
                fontWeight = AppFontWeight.bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.subtitle.isNotEmpty()) {
                Spacer(modifier = Modifier.height(AppSpacing.sp2))
                Text(
                    text = item.subtitle,
                    color = AppColors.whiteA50,
                    fontSize = AppFontSize.size13,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 歌单卡：**封面即海报**。168dp 方图，标题压在封面下缘的渐变遮罩上。
 *
 * 与专辑卡的区别刻意做大：歌单是「一整张图 + 图上文字」，专辑是「一条信息行」，
 * 扫过去一眼就能分清这两排不是同一种东西。
 */
@Composable
private fun HomePlaylistCard(item: HomeItem, onClick: () -> Unit) {
    PressableCard(onClick = onClick, modifier = Modifier.width(PlaylistCardSize)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(AppRadius.r20))
                .background(AppColors.whiteA03),
        ) {
            HomeCoverImage(item = item, placeholderSize = AppFontSize.size26)
            // 底部压暗，保证白字在浅色封面上也读得清（只在卡内，不影响页面背景）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.42f to Color.Transparent,
                            1f to Color(0x99000000),
                        ),
                    ),
            )
            if (item.badge != null) {
                HomeBadge(
                    text = item.badge,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(AppSpacing.sp8),
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(AppSpacing.sp12),
            ) {
                Text(
                    text = item.title,
                    color = AppColors.white,
                    fontSize = AppFontSize.size15,
                    fontWeight = AppFontWeight.bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.subtitle.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(AppSpacing.sp2))
                    Text(
                        text = item.subtitle,
                        color = AppColors.white.copy(alpha = 0.72f),
                        fontSize = AppFontSize.xs,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 专辑卡：**横向唱片条**。左侧 80dp 方图 + 右侧三行（`ALBUM` 微标签 / 专辑名 / 歌手）。
 *
 * 微标签用主色 + 字距拉开，做出「唱片公司贴纸」的观感 —— 这是与歌单卡最主要的形态差异：
 * 一个是图片主导，一个是文字主导。
 */
@Composable
private fun HomeAlbumCard(item: HomeItem, onClick: () -> Unit) {
    PressableCard(onClick = onClick, modifier = Modifier.width(AlbumCardWidth)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(AlbumCardHeight)
                .clip(RoundedCornerShape(AppRadius.r16))
                .background(Color(0x14FFFFFF))
                .padding(AppSpacing.sp8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(AlbumCoverSize)
                    .clip(RoundedCornerShape(AppRadius.card))
                    .background(AppColors.whiteA03),
            ) {
                HomeCoverImage(item = item, placeholderSize = AppFontSize.size20)
            }
            Spacer(modifier = Modifier.width(AppSpacing.sp10))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "ALBUM",
                    color = AppColors.primary,
                    fontSize = AppFontSize.size9,
                    fontWeight = AppFontWeight.semiBold,
                    // 字距拉开，像唱片贴纸
                    letterSpacing = 1.4.sp,
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp2))
                Text(
                    text = item.title,
                    color = AppColors.textPrimary,
                    fontSize = AppFontSize.size13,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp2))
                Text(
                    text = item.subtitle,
                    color = AppColors.whiteA40,
                    fontSize = AppFontSize.xs,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 曲目数 / 发行日期：字号与配色对齐歌手详情的专辑卡（10sp / white30 + white70）
                if (item.count > 0 || item.pub.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(AppSpacing.sp4))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (item.count > 0) {
                            Text(
                                text = "♪",
                                color = AppColors.whiteA20,
                                fontSize = AppFontSize.size10,
                            )
                            Spacer(modifier = Modifier.width(AppSpacing.sp4))
                            Text(
                                text = "${item.count}首",
                                color = AppColors.white30,
                                fontSize = AppFontSize.size10,
                            )
                        }
                        if (item.pub.isNotEmpty()) {
                            if (item.count > 0) Spacer(modifier = Modifier.width(AppSpacing.sp4))
                            Text(
                                text = item.pub,
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
}

/** 歌手卡：圆形头像 + 名字（留给歌手区块，样式与另外两种都不撞）。 */
@Composable
private fun HomeArtistCard(item: HomeItem, onClick: () -> Unit) {
    PressableCard(onClick = onClick, modifier = Modifier.width(ArtistCardSize)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(ArtistCardSize)
                    .clip(CircleShape)
                    .background(AppColors.whiteA03),
            ) {
                HomeCoverImage(item = item, placeholderSize = AppFontSize.size26)
            }
            Spacer(modifier = Modifier.height(AppSpacing.sp8))
            Text(
                text = item.title,
                color = AppColors.textPrimary,
                fontSize = AppFontSize.sm,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 流派卡：平台不给风格封面图，所以用**渐变色块**表达。
 *
 * 与歌单卡同尺寸、同圆角（168dp / r20），所以「按风格听」这一行和「新专辑」「推荐歌单」
 * 在版式上是齐的；只是内容是"可选流派"而不是"可播内容"。
 * 底色按 `key` 的哈希固定取一组渐变 —— 同一个流派每次进来都是同一个色，不会跳。
 */
@Composable
private fun HomeStyleCard(item: HomeItem, onClick: () -> Unit) {
    val gradient = HomeStyleGradients[Math.floorMod(item.key.hashCode(), HomeStyleGradients.size)]
    PressableCard(onClick = onClick, modifier = Modifier.width(PlaylistCardSize)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(AppRadius.r20))
                .background(Brush.linearGradient(gradient)),
        ) {
            // 右下角一个超大半透明音符，增加一点层次（不是图标，是背景纹样）
            Text(
                text = "♪",
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = AppSpacing.sp4, bottom = AppSpacing.sp2),
                color = AppColors.white.copy(alpha = 0.10f),
                fontSize = AppFontSize.size34,
            )
            Text(
                text = "STYLE",
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(AppSpacing.sp12),
                color = AppColors.white.copy(alpha = 0.55f),
                fontSize = AppFontSize.size9,
                fontWeight = AppFontWeight.semiBold,
                letterSpacing = 1.4.sp,
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(AppSpacing.sp12),
            ) {
                Text(
                    text = item.title,
                    color = AppColors.white,
                    fontSize = AppFontSize.size20,
                    fontWeight = AppFontWeight.bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp2))
                Text(
                    text = "点开听这一流派",
                    color = AppColors.white.copy(alpha = 0.62f),
                    fontSize = AppFontSize.xs,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 流派卡的六套渐变（沿用最初主页占位卡那组配色，与整体紫调不冲突）。 */
private val HomeStyleGradients: List<List<Color>> = listOf(
    listOf(Color(0xFF3B4C8A), Color(0xFF1B2340)),
    listOf(Color(0xFF6B3A55), Color(0xFF2A1622)),
    listOf(Color(0xFF2F5D50), Color(0xFF12241F)),
    listOf(Color(0xFF5A4A2E), Color(0xFF241C10)),
    listOf(Color(0xFF4A3560), Color(0xFF1E1428)),
    listOf(Color(0xFF34505E), Color(0xFF141F26)),
)

/** 卡片封面（含失败 / 空判定），铺满父容器。 */
@Composable
private fun HomeCoverImage(
    item: HomeItem,
    placeholderSize: androidx.compose.ui.unit.TextUnit,
    scale: ContentScale = ContentScale.Crop,
) {
    var failed by remember(item.key) { mutableStateOf(false) }
    if (item.cover.isEmpty() || failed) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "♪", color = AppColors.whiteA15, fontSize = placeholderSize)
        }
    } else {
        AsyncImage(
            model = item.cover,
            contentDescription = null,
            contentScale = scale,
            onError = { failed = true },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 封面右上角的小角标（播放量等）。 */
@Composable
private fun HomeBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(Color(0x66000000))
            .padding(horizontal = AppSpacing.sp6, vertical = AppSpacing.sp2),
    ) {
        Text(
            text = text,
            color = AppColors.white,
            fontSize = AppFontSize.size10,
        )
    }
}

/**
 * 按下回弹 + 无波纹点击。
 *
 * 用 `graphicsLayer` 缩放而不是改尺寸：缩放不触发重新布局，滚动时不会掉帧。
 */
@Composable
private fun PressableCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) CardPressedScale else 1f,
        animationSpec = tween(durationMillis = 140),
        label = "homeCardScale",
    )
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
    ) {
        content()
    }
}

// ================================================================================================
// 骨架屏 / 空态
// ================================================================================================

/** 首次进入 / 换源的骨架：5 行歌曲骨架（与 `SongRowPager` 同高）+ 一排卡片骨架。 */
@Composable
private fun HomeFeedSkeleton(modifier: Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        SkeletonLoader(columns = 1, largeRows = true)
        Spacer(modifier = Modifier.height(AppSpacing.sp28))
        Row(
            modifier = Modifier.padding(horizontal = AppSpacing.sp16),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sp14),
        ) {
            repeat(3) {
                HomeSkeletonCard()
            }
        }
    }
}

@Composable
private fun HomeSkeletonCard() {
    val transition = rememberInfiniteTransition(label = "homeSkeletonCard")
    // 只在绘制阶段读：组合期读会让每张卡每帧重组一次（首页一行 3 张）
    val pulse = transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = AppDuration.skeletonPulse, easing = AppEasing.easeInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "homeSkeletonCardAlpha",
    )
    Box(
        modifier = Modifier
            .size(PlaylistCardSize)
            .graphicsLayer { alpha = pulse.value }
            .clip(RoundedCornerShape(AppRadius.r20))
            .background(SkeletonColor),
    )
}

/** 该平台没有主页内容（Qobuz / Apple / 未接入的酷我、酷狗）时的空态。 */
@Composable
private fun HomeFeedEmpty(modifier: Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = AppSpacing.sp32),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "♪",
                color = AppColors.whiteA15,
                fontSize = AppFontSize.size32,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp12))
            Text(
                text = "这个源还没有主页内容",
                color = AppColors.white60,
                fontSize = AppFontSize.size15,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp4))
            Text(
                text = "切到网易 / QQ，或直接在上面搜索",
                color = AppColors.white38,
                fontSize = AppFontSize.size13,
                textAlign = TextAlign.Center,
            )
        }
    }
}
