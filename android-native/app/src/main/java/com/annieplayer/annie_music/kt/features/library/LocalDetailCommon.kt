package com.annieplayer.annie_music.kt.features.library

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.annieplayer.annie_music.kt.data.local.LocalLibraryStore
import com.annieplayer.annie_music.kt.data.local.LocalTrackMeta
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.ui.theme.ColorPalette
import com.annieplayer.annie_music.kt.ui.theme.LocalAmbientCoverUrl
import com.annieplayer.annie_music.kt.ui.theme.LocalCoverThemePalette
import com.annieplayer.annie_music.kt.widgets.CoverPaletteBackdrop
import com.annieplayer.annie_music.kt.widgets.MusicNoteGlyph
import com.annieplayer.annie_music.kt.widgets.SongMoreButton
import com.annieplayer.annie_music.kt.widgets.clickableRipple
import com.annieplayer.annie_music.kt.widgets.playingRowBackground
import kotlinx.coroutines.launch

/**
 * 本地曲库各页共用的零件。
 *
 * 尺寸/层级全部**照流媒体详情页**取值，保证两边看起来是同一套东西：
 * - [LocalDetailTopBar] ← `SingerDetailHeader` / `AlbumDetailHeader`
 *   （`start/end 12`、**`top 40`**、`bottom 4`；chevron 22dp + 标题 17sp SemiBold + 数量 13sp）
 * - [LocalSongRow] ← `AlbumDetailSongRow` / `SingerDetailSongRow`
 *   （行高 [AppSize.detailRowHeight]、序号列 28dp、封面 40dp 圆角 r6、标题 16sp Medium、副标题 13sp）
 * - [LocalAlbumGridCard] ← `SingerDetailAlbumCard`
 *   （白 3% 底 + `AppRadius.card`，封面边长 = 卡宽 − 12dp，文字区 padding 8）
 * - [LocalSectionHeader] ← `SingerDetailSectionHeader`（20sp Bold + 右侧「全部 ›」玻璃 pill）
 */
/**
 * 本地曲库各页的**进出场外壳**（照流媒体详情浮层）：
 * 半透明遮罩 `scrim50` + 面板从底部滑入（[AppDuration.sheet] + [AppEasing.linear]），
 * 关闭时**先反向滑出、动画结束才真正出栈** —— 与 `AlbumDetailSheet` / `SingerDetailSheet`
 * 的行为一模一样（那两个也是 `Animatable(1f)` → `animateTo(0f)` + `translationY * height`）。
 *
 * [content] 拿到的是 `requestClose`：页面里的返回键 / 顶栏 chevron 都用它，
 * 这样每条关闭路径都会播完滑出动画（直接调 `onClose` 会硬切）。
 */
@Composable
internal fun LocalOverlayFrame(
    onClose: () -> Unit,
    content: @Composable (requestClose: () -> Unit) -> Unit,
) {
    val slide = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        slide.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.linear),
        )
    }
    var closing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val requestClose: () -> Unit = {
        if (!closing) {
            closing = true
            scope.launch {
                slide.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.linear),
                )
                onClose()
            }
        }
    }
    // 把自己的「优雅关闭」交给返回裁决：AppShell 收到返回时优先让这一页播滑出再出栈，
    // 而不是直接 popRoute（那样页面会瞬间消失，没有滑出动画）
    com.annieplayer.annie_music.kt.core.routing.RememberPageClose(requestClose)

    Box(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().background(AppColors.scrim50))
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = slide.value * size.height },
        ) {
            content(requestClose)
        }
    }
}

internal val LocalIconStroke = 2.dp

/**
 * 本地曲库各页（**除专辑详情、歌手详情**）的背景：LunaBeat 风格，跟随「正在播放歌曲封面」。
 *
 * 图源与底色都取自全局：
 * - 模糊封面图 = [LocalAmbientCoverUrl]（当前播放歌曲封面）
 * - 兜底底色 = [LocalCoverThemePalette]（LunaBeat 规则算出）
 *
 * 与 [CoverPaletteBackdrop] 是同一个渲染链路，所以曲库页、主页、各详情页现在**长得一样**：
 * 都是「模糊封面为主体 + 底色兜底 + 30% 暗纱」，切歌时封面 560ms 交叉淡化、底色 360ms 补间。
 *
 * 没有正在播放的歌（首次安装 / 清过数据）时两者都是空/fallback，只剩兜底底色渐变。
 */
@Composable
internal fun LocalStaticBackdrop(modifier: Modifier = Modifier) {
    CoverPaletteBackdrop(
        sheen = false,
        coverUrl = LocalAmbientCoverUrl.current,
        palette = LocalCoverThemePalette.current,
        modifier = modifier,
    )
}

/** 顶部返回条：chevron + 标题 + 数量 + 右侧纯文字动作（照 `SingerDetailHeader`） */
@Composable
internal fun LocalDetailTopBar(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    count: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = AppSpacing.sp12,
                end = AppSpacing.sp12,
                top = AppSpacing.sp40,
                bottom = AppSpacing.sp4,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clickableRipple(onClose)
                .padding(AppSpacing.sp4),
            contentAlignment = Alignment.Center,
        ) {
            LocalChevronLeft(modifier = Modifier.size(AppIconSize.icon22), tint = AppColors.textSecondary)
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp8))
        Text(
            text = title,
            modifier = Modifier.weight(1f, fill = false),
            color = AppColors.textPrimary,
            fontSize = AppFontSize.size17,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!count.isNullOrEmpty()) {
            Spacer(modifier = Modifier.width(AppSpacing.sp6))
            Text(
                text = count,
                color = AppColors.whiteA40,
                fontSize = AppFontSize.size13,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Box(
                modifier = Modifier
                    .clickableRipple(onAction)
                    .padding(AppSpacing.sp4),
            ) {
                Text(
                    text = action,
                    color = AppColors.primary,
                    fontSize = AppFontSize.size13,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/** 排序模式：按名字 / 按目录名（字母序） */
internal const val SORT_MODE_NAME = "name"

/** 排序模式：按歌曲数 */
internal const val SORT_MODE_COUNT = "count"

/**
 * 子页面排序状态（模式 + 升降序），**持久化到 SharedPreferences**：
 * 退出页面、切走再回来、杀进程重进，都还是上次选的那套排序。
 *
 * @param page 分页键（[LocalLibraryStore.PAGE_ARTISTS] 之类）
 * @param defaultMode 没存过时用的模式
 * @param defaultAscending 没存过时的方向
 */
internal class SortPreference(
    val mode: String,
    val ascending: Boolean,
    /** 换排序模式：方向同时落到该模式的默认值，并写盘 */
    val setMode: (String, Boolean) -> Unit,
    val setAscending: (Boolean) -> Unit,
)

@Composable
internal fun rememberSortPreference(
    page: String,
    defaultMode: String,
    defaultAscending: Boolean,
): SortPreference {
    var mode by remember(page) {
        mutableStateOf(LocalLibraryStore.loadSortMode(page) ?: defaultMode)
    }
    var ascending by remember(page) {
        mutableStateOf(LocalLibraryStore.loadSortAscending(page, defaultAscending))
    }
    return SortPreference(
        mode = mode,
        ascending = ascending,
        setMode = { newMode, newAscending ->
            mode = newMode
            ascending = newAscending
            LocalLibraryStore.saveSortMode(page, newMode)
            LocalLibraryStore.saveSortAscending(page, newAscending)
        },
        setAscending = { value ->
            ascending = value
            LocalLibraryStore.saveSortAscending(page, value)
        },
    )
}

/**
 * 子页面的「排序」行：左侧说明文字，右侧排序按钮（点一下换下一种）。
 *
 * 与曲库主页统计行的排序按钮同一套观感（13sp / textSecondary / 8dp 内边距），
 * 这样主页与四个子页面在"排序"这件事上长得一样。
 *
 * @param trailing 右侧额外的操作（现在固定是「↑ 升序 / ↓ 降序」切换）
 */
@Composable
internal fun LocalSortRow(
    hint: String,
    sortLabel: String,
    onCycleSort: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.sp16, vertical = AppSpacing.sp4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = hint,
            color = AppColors.textTertiary,
            fontSize = AppFontSize.xs,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(AppRadius.r6))
                .clickableRipple { onCycleSort() }
                .padding(horizontal = AppSpacing.sp8, vertical = AppSpacing.sp4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "排序 · $sortLabel",
                color = AppColors.textSecondary,
                fontSize = AppFontSize.xs,
            )
        }
    }
}

/** 升序 / 降序切换（每个列表的每种排序方式都可用） */
@Composable
internal fun LocalOrderToggle(ascending: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(AppRadius.r6))
            .clickableRipple { onToggle() }
            .padding(horizontal = AppSpacing.sp8, vertical = AppSpacing.sp4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (ascending) "↑" else "↓",
            color = AppColors.textSecondary,
            fontSize = AppFontSize.xs,
        )
        Spacer(modifier = Modifier.width(AppSpacing.sp2))
        Text(
            text = if (ascending) "升序" else "降序",
            color = AppColors.textSecondary,
            fontSize = AppFontSize.xs,
        )
    }
}

/** section 标题 + 右侧「全部 ›」（照 `SingerDetailSectionHeader`） */@Composable
internal fun LocalSectionHeader(title: String, onSeeAll: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = AppSpacing.sp16,
                end = AppSpacing.sp16,
                top = AppSpacing.sp16,
                bottom = AppSpacing.sp8,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            color = AppColors.textPrimary,
            fontSize = AppFontSize.size20,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onSeeAll != null) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(AppColors.whiteA06)
                    .clickableRipple(onSeeAll)
                    .padding(horizontal = AppSpacing.sp12, vertical = AppSpacing.sp6),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "全部", color = AppColors.white70, fontSize = AppFontSize.xs)
                Spacer(modifier = Modifier.width(AppSpacing.sp2))
                LocalChevronRight(modifier = Modifier.size(AppIconSize.icon13), tint = AppColors.glyphStrong)
            }
        }
    }
}

/**
 * 曲目行（专辑/歌手详情统一用它）。
 *
 * 与 `AlbumDetailSongRow` 逐项对齐：行高 [AppSize.detailRowHeight]、序号列 28dp、
 * 播放中换成等化器并给整行主色氛围底。差别只有右侧——本地没有下载，统一是 ⋮。
 */
@Composable
internal fun LocalSongRow(
    meta: LocalTrackMeta,
    index: Int,
    isActive: Boolean,
    playingTint: Color,
    onPlay: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    /** 序号列显示的内容，默认是曲目号（专辑页）或行号 */
    trackLabel: String = "",
    /**
     * 是否显示行内封面。
     *
     * 专辑详情**不显示**（与流媒体 `AlbumDetailSongRow` 一致：那一页整张专辑同一张封面，
     * 每行再放一遍只是噪声，序号 + 标题 + 歌手就够了）；歌手详情显示
     * （对齐 `SingerDetailSongRow`，那里每行可能来自不同专辑）。
     */
    showCover: Boolean = true,
    /**
     * 副标题是否带上专辑名（`歌手 · 专辑`）。
     *
     * 流媒体 `SingerDetailSongRow` 的副标题就是 `artist · album`：歌手页里每行的专辑
     * 并不相同，带上专辑才看得懂。专辑详情页整页同一张专辑，带上反而是噪声。
     */
    showAlbum: Boolean = false,
    /**
     * 行首序号列是否显示。
     *
     * **只有 `SongRowPager` 里「最热门」那一段传 false** —— 那一页是横向分页、
     * 每页只有 5 行，序号在那里只是噪声。其余页面（专辑详情曲目号 / 文件夹曲目号 /
     * 最近播放的播放次数）照旧显示，那些数字承载的是真信息，不是序号。
     *
     * 传 false 时，正在播放的等化器叠到封面中心（46% 黑罩）；否则仍在序号列原位。
     */
    showIndex: Boolean = true,
    /**
     * 行尾留白（⋮ 右侧到行右边界）。
     *
     * 常列表页保持 16dp，让 ⋮ 不贴屏幕边；`SongRowPager` 里必须传 0 ——
     * 分页容器的页宽本身就比屏幕窄一截（露出下一页的边），再叠 16dp
     * 会让 ⋮ 看起来缩在中间（实测距屏幕右边 41dp，普通列表只有 30dp）。
     */
    trailingSpace: Dp = AppSpacing.sp16,
    /**
     * 行内左侧留白（封面到行左边界）。
     *
     * 常列表页保持 12dp；`SongRowPager` 里传 16dp —— 分页容器的 contentPadding 是 0
     * （为了不让右侧露出被吃掉），若不补这 4dp，封面会比上下相邻的货架更靠左。
     */
    leadingSpace: Dp = AppSpacing.sp12,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(end = trailingSpace),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
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
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showIndex) {
                Box(
                    modifier = Modifier.width(AppSpacing.sp28),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isActive) {
                        com.annieplayer.annie_music.kt.widgets.PlayingIndicator(tint = playingTint)
                    } else {
                        Text(
                            text = trackLabel.ifEmpty { "${index + 1}" },
                            color = AppColors.white60,
                            fontSize = AppFontSize.size15,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(if (showCover) AppSpacing.sp8 else AppSpacing.sp10))
            }
            if (showCover) {
                // 封面 40dp / 圆角 6（与歌手详情行一致）
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(AppSpacing.sp40)
                            .clip(RoundedCornerShape(AppRadius.r6))
                            .background(AppColors.whiteA03),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (meta.coverPath != null) {
                            AsyncImage(
                                model = "file://${meta.coverPath}",
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            MusicNoteGlyph(
                                modifier = Modifier.size(AppIconSize.icon16),
                                tint = AppColors.whiteA15,
                            )
                        }
                    }
                    if (isActive && !showIndex) {
                        // 序号列不显示时，等化器叠在封面中心（46% 黑罩保证看得清）
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clip(RoundedCornerShape(AppRadius.r6))
                                .background(Color.Black.copy(alpha = 0.46f)),
                        )
                        com.annieplayer.annie_music.kt.widgets.PlayingIndicator(tint = playingTint)
                    }
                }
                Spacer(modifier = Modifier.width(AppSpacing.sp10))
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = meta.title.ifBlank { meta.file.nameWithoutExtension },
                    color = if (isActive) playingTint else AppColors.textPrimary,
                    fontSize = AppFontSize.lg,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp1))
                Text(
                    text = buildString {
                        // 多歌手统一成 `A / B`（与列表行 / 播放页同一份格式化）
                        append(meta.artistDisplay.ifBlank { meta.folderName })
                        if (showAlbum && meta.album.isNotBlank()) {
                            append(" · ")
                            append(meta.album)
                        }
                    },
                    color = AppColors.textTertiary,
                    fontSize = AppFontSize.size13,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 音质角标：FLAC 24bit / 320K（本地文件的"规格"，流媒体那边显示的是 Hi-Res/Dolby）
            if (meta.qualityChip.isNotEmpty()) {
                Spacer(modifier = Modifier.width(AppSpacing.sp4))
                LocalQualityBadge(label = meta.qualityChip)
            }
        }
        SongMoreButton(onClick = onMore)
    }
}

/** 音质角标（照 `SingerDetailQualityBadge`：primary 15% 底 + 圆角胶囊 + 10sp 主色） */
@Composable
internal fun LocalQualityBadge(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 25))
            .background(AppColors.primaryA15)
            .padding(horizontal = AppSpacing.sp4, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = AppColors.primary, fontSize = AppFontSize.size10)
    }
}

/**
 * 专辑卡（照 `SingerDetailAlbumCard`）：封面边长 = 卡宽 − 12dp，文字区两行 + 底部一行
 * 「音符 · N 首 · 类型 · 发行年」（末项与流媒体一样右对齐加粗）。
 *
 * 用在「专辑列表页」的 2 列网格、「歌手详情」的横滑架、以及歌手详情的「全部专辑」页，
 * 四处同一尺寸语言。
 */
@Composable
internal fun LocalAlbumGridCard(
    name: String,
    artist: String,
    coverPath: String?,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 发行年（没有就不显示） */
    year: Int? = null,
    /** 专辑类型：单曲 / EP / 专辑（见 [localAlbumType]） */
    type: String? = null,
) {
    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.card))
            .background(AppColors.whiteA03)
            .clickableRipple(onClick),
    ) {
        val coverSize = maxWidth - AppSpacing.sp12
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.padding(
                    start = AppSpacing.sp6,
                    top = AppSpacing.sp6,
                    end = AppSpacing.sp6,
                ),
            ) {
                Box(
                    modifier = Modifier
                        .size(coverSize)
                        .clip(RoundedCornerShape(AppRadius.r8))
                        .background(AppColors.whiteA03),
                    contentAlignment = Alignment.Center,
                ) {
                    var coverFailed by remember(coverPath) { mutableStateOf(false) }
                    if (coverPath == null || coverFailed) {
                        MusicNoteGlyph(
                            modifier = Modifier.size(AppIconSize.icon40),
                            tint = AppColors.white10,
                        )
                    } else {
                        AsyncImage(
                            model = "file://$coverPath",
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            onError = { coverFailed = true },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            Column(modifier = Modifier.padding(AppSpacing.sp8)) {
                Text(
                    text = name,
                    color = AppColors.textPrimary,
                    fontSize = AppFontSize.size13,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp2))
                Text(
                    text = artist,
                    color = AppColors.whiteA40,
                    fontSize = AppFontSize.xs,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp4))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MusicNoteGlyph(modifier = Modifier.size(10.dp), tint = AppColors.glyphDim)
                    Spacer(modifier = Modifier.width(AppSpacing.sp4))
                    Text(
                        text = "${count}首",
                        color = AppColors.white30,
                        fontSize = AppFontSize.size10,
                    )
                    if (!type.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.width(AppSpacing.sp4))
                        Text(
                            text = type,
                            color = AppColors.white30,
                            fontSize = AppFontSize.size10,
                        )
                    }
                    if (year != null && year > 0) {
                        Spacer(modifier = Modifier.width(AppSpacing.sp4))
                        Text(
                            text = "$year",
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

/** 圆形头像位（歌手列表行 / 歌手详情 Hero 共用）：有封面放封面，否则放首字 */
@Composable
internal fun LocalArtistAvatar(
    name: String,
    coverPath: String?,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    letterSize: androidx.compose.ui.unit.TextUnit = AppFontSize.size20,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(AppColors.whiteA03),
        contentAlignment = Alignment.Center,
    ) {
        var failed by remember(coverPath) { mutableStateOf(false) }
        if (coverPath != null && !failed) {
            AsyncImage(
                model = "file://$coverPath",
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
        } else if (name.isNotBlank()) {
            Text(
                text = name.take(1).uppercase(),
                color = AppColors.primary,
                fontSize = letterSize,
                fontWeight = FontWeight.Bold,
            )
        } else {
            LocalPersonOutline(
                modifier = Modifier.size(AppIconSize.icon24),
                tint = AppColors.textDisabled,
            )
        }
    }
}

// ── 本组页面共用的自绘图标（照 SingerDetailSheet 末尾那套：2dp 圆头笔画） ──

/** chevron_left：返回 / 上一页 */
@Composable
internal fun LocalChevronLeft(modifier: Modifier = Modifier, tint: Color = AppColors.textSecondary) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = LocalIconStroke.toPx()
        drawLine(tint, Offset(w * 0.62f, h * 0.16f), Offset(w * 0.30f, h * 0.5f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.30f, h * 0.5f), Offset(w * 0.62f, h * 0.84f), stroke, StrokeCap.Round)
    }
}

/** chevron_right：列表行尾 / 下一页 */
@Composable
internal fun LocalChevronRight(modifier: Modifier = Modifier, tint: Color = AppColors.textSecondary) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = LocalIconStroke.toPx()
        drawLine(tint, Offset(w * 0.38f, h * 0.16f), Offset(w * 0.70f, h * 0.5f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.70f, h * 0.5f), Offset(w * 0.38f, h * 0.84f), stroke, StrokeCap.Round)
    }
}

/** person_outline：头圆 + 肩弧（头像兜底） */
@Composable
internal fun LocalPersonOutline(modifier: Modifier = Modifier, tint: Color = AppColors.textDisabled) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = LocalIconStroke.toPx()
        drawCircle(
            color = tint,
            radius = w * 0.17f,
            center = Offset(w * 0.5f, h * 0.31f),
            style = Stroke(width = stroke),
        )
        drawArc(
            color = tint,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.16f, h * 0.50f),
            size = androidx.compose.ui.geometry.Size(w * 0.68f, h * 0.62f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}
