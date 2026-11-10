package com.annieplayer.annie_music.kt.features.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import com.annieplayer.annie_music.kt.core.compose.activityViewModel
import com.annieplayer.annie_music.kt.core.state.LocalSectionRoute
import com.annieplayer.annie_music.kt.core.state.PlayerViewModel
import com.annieplayer.annie_music.kt.data.local.LocalSection
import com.annieplayer.annie_music.kt.data.local.LOCAL_BUCKET_COUNT
import com.annieplayer.annie_music.kt.data.local.LocalLibraryStore
import com.annieplayer.annie_music.kt.data.local.LocalSort
import com.annieplayer.annie_music.kt.data.local.LocalTrackMeta
import com.annieplayer.annie_music.kt.data.local.countGroups
import com.annieplayer.annie_music.kt.data.local.filterTracks
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.ui.theme.ColorPalette
import com.annieplayer.annie_music.kt.widgets.AppSheet
import com.annieplayer.annie_music.kt.widgets.AppTopBar
import com.annieplayer.annie_music.kt.widgets.CoverPaletteBackdrop
import com.annieplayer.annie_music.kt.widgets.SearchBarField
import com.annieplayer.annie_music.kt.widgets.VinylRecord
import com.annieplayer.annie_music.kt.widgets.clickableRipple
import com.annieplayer.annie_music.kt.widgets.shortScrollbar
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/** 空态「唱盘」底板直径（与原占位页同一视觉语言） */
private val LibraryEmptyDiscSize = 112.dp

/** 曲库主页 LazyColumn 里 header 部分的 item 数（搜索 / 宫格 / 统计 / 分区标题）—— 索引跳转要加这个偏移 */
private const val HEADER_ITEM_COUNT = 4

/** 空态黑胶本体直径 */
private val LibraryEmptyVinylSize = 76.dp

/** 宫格两列之间的水平间距 */
private val GridGap = 10.dp

/**
 * 本地曲库页。
 *
 * 结构（自顶向下）：
 * 1. 顶栏 [AppTopBar]（菜单 + 大标题 + 曲库统计角标）—— 与搜索页同一组件、同一套标题 token；
 * 2. 搜索框（常驻，输入即过滤标题 / 艺术家 / 专辑 / 文件夹 / 文件名）；
 * 3. 四宫格入口：艺术家 / 专辑 / 文件夹 / 最近添加（两列等宽，随列表一起滚）；
 * 4. 统计行（总数 · 体积 + 排序 + 重扫）+ 读取进度；
 * 5. 列表：默认全部歌曲；点宫格切到分组页；点分组进组内歌曲。
 *
 * 数据来自 [LocalLibraryViewModel]（扫描 + ffprobe 元数据 + 缓存），播放走 [PlayerViewModel]
 * 的标准队列，与云端歌曲完全同一条链路（`platform = local`）。
 */
@Composable
fun LocalLibraryScreen(
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier,
    playerViewModel: PlayerViewModel = activityViewModel(),
    libraryViewModel: LocalLibraryViewModel = activityViewModel(),
    // 宫格 / 行尾 ⋮ 都走流媒体那套 UiViewModel（pageStack / songActions），不再自带栈
    uiViewModel: com.annieplayer.annie_music.kt.core.state.UiViewModel = activityViewModel(),
) {
    val st by libraryViewModel.state.collectAsState()
    val playback by playerViewModel.state.collectAsState()
    val context = LocalContext.current

    // 首次进入启动扫描（VM 是 Activity 作用域，重复进入不会重复扫）
    LaunchedEffect(Unit) { libraryViewModel.start(context) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var sheetTrack by remember { mutableStateOf<LocalTrackMeta?>(null) }

    // ── 派生数据 ──
    // 注意：这里每个 remember 都挂在不常变的值上。曲库页一旦有几千首，
    // 「每次重组都重算一遍分组/求和」就是滚动掉帧的直接原因（单次 O(n log n) × N 次）。
    val groupCounts = remember(st.tracks) { countGroups(st.tracks) }
    // 「最近播放」的条数：只算曲库里还存在的（与列表页 `ordered.size` 同一口径），
    // 读的是 `LocalLibraryStore` 的内存副本（`recentCache`），不会每次都读盘。
    // 挂 `playback.currentSong?.id`：切歌后 `recordPlay` 已更新缓存，这里跟着刷新数字。
    val recentCount = remember(st.tracks, playback.currentSong?.id) {
        val paths = st.tracks.mapTo(HashSet(st.tracks.size)) { it.path }
        LocalLibraryStore.loadRecentPlays().count { it.path in paths }
    }
    val totalBytes = remember(st.tracks) { st.tracks.sumOf { it.size } }
    // 已读条数同样只算一次：`tracks.count { }` 是 O(n)，放进重组里就是几千次比较/帧
    val probedCount = remember(st.tracks) { st.tracks.count { it.probed } }
    val visible = remember(st.tracks, st.query) { filterTracks(st.tracks, st.query) }

    // 字母索引条：只有「按标题 / 艺术家 / 专辑」这类字母排序才有意义
    val alphaKeyOf: ((LocalTrackMeta) -> String)? = when (st.sort) {
        LocalSort.byTitle -> { t -> t.title }
        LocalSort.byArtist -> { t -> t.artistDisplay }
        LocalSort.byAlbum -> { t -> t.album }
        else -> null
    }
    val alphabetBucketsOfVisible = remember(visible, st.sort) {
        alphaKeyOf?.let { key -> alphabetBuckets(visible, key) } ?: emptyMap()
    }

    /** 当前列表作为播放队列（点哪首从哪首开始，顺序与屏幕上一致） */
    val playFrom: (LocalTrackMeta) -> Unit = { meta ->
        val queue = visible
        if (queue.isEmpty()) {
            playerViewModel.setQueue(listOf(meta.toSong()), 0)
        } else {
            val songs = queue.map { it.toSong() }
            val index = queue.indexOfFirst { it.path == meta.path }.coerceAtLeast(0)
            playerViewModel.setQueue(songs, index)
        }
    }

    // 封面按需：只对「滚动到可见」的行抽（3000 首也不会在扫描阶段解 3000 张图）
    LaunchedEffect(listState, visible) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.key } }
            .collect { keys ->
                for (key in keys) {
                    val s = key as? String ?: continue
                    if (!s.startsWith("t:")) continue
                    libraryViewModel.requestCover(s.removePrefix("t:"))
                }
            }
    }

    // 详情 sheet 打开时补位深/声道这类「快读拿不到」的字段（ffprobe，一首只跑一次）
    LaunchedEffect(sheetTrack) {
        sheetTrack?.let { libraryViewModel.enrich(it.path) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LocalStaticBackdrop(modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding(),
        ) {
            // ── 顶栏（菜单 + 大标题；标题旁不再挂角标，数量在下面的统计行里）──
            AppTopBar(
                title = "本地曲库",
                onOpenMenu = onOpenMenu,
            )

            // 列表 + 右侧字母索引条（索引条只在按字母排序时出现）
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // 字母条要能"往下超出"（未上滑时显示不全），溢出部分在这里裁掉
                    .clipToBounds(),
            ) {
                // 槽高 = 从列表区顶部到底部导航栏上方，27 个字母平分：
                // 第一首顶住搜索栏时整条正好铺到导航栏上方，字母也就能放大到 13sp 左右
                val slotHeight = ((maxHeight - AppSpacing.bottomBarInset - AppSpacing.sp8) /
                    LOCAL_BUCKET_COUNT.toFloat()).coerceIn(16.dp, 30.dp)
                // 白色小圆点：位置按「顶部那首歌落在哪个字母桶」换算（[alphabetDotSlot]），
                // 所以圆点滑到哪个字母，那个字母区间的第一首就正好顶在搜索条位置。
                // header（搜索框/宫格/统计/分区标题）还没滚完时 songTop < 0，圆点钉在最上面不动。
                val slotStarts = remember(alphabetBucketsOfVisible, visible.size, st.sortAscending) {
                    alphabetSlotStarts(
                        alphabetBucketsOfVisible,
                        visible.size,
                        reversed = !st.sortAscending,
                    )
                }
                val lastSlot = remember(slotStarts, st.sortAscending) {
                    alphabetLastBucketSlot(slotStarts, reversed = !st.sortAscending)
                }
                var dotSlot by remember { mutableStateOf<Float?>(null) }
                LaunchedEffect(listState, slotStarts, visible.size) {
                    if (slotStarts.isEmpty()) {
                        dotSlot = null
                    } else {
                        snapshotFlow {
                            val first = listState.layoutInfo.visibleItemsInfo.firstOrNull()
                                ?: return@snapshotFlow 0f
                            // 到底了：圆点钉在最后一个有内容的字母上（最后一首永远顶不到搜索条）
                            if (!listState.canScrollForward && lastSlot >= 0) {
                                return@snapshotFlow lastSlot.toFloat()
                            }
                            val scrolled = (-first.offset).toFloat() / first.size.coerceAtLeast(1)
                            val songTop = (first.index - HEADER_ITEM_COUNT) + scrolled
                            alphabetDotSlotFor(
                                slotStarts,
                                songTop,
                                visible.size,
                                reversed = !st.sortAscending,
                            )
                        }.collect { dotSlot = it }
                    }
                }
                // 字母条的顶部 = 第一首歌当前的 y：没上滑时第一首在页面中部，条子跟着往下排；
                // 上滑后第一首贴住搜索栏，y 收敛到 0，条子就顶到列表顶部
                var barTopPx by remember { mutableStateOf(0f) }
                LaunchedEffect(listState) {
                    snapshotFlow {
                        listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.index == HEADER_ITEM_COUNT }
                            ?.offset?.coerceAtLeast(0) ?: 0
                    }.collect { barTopPx = it.toFloat() }
                }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    // 字母条只在按字母排序时出现；它不出现时（时长 / 添加时间）右侧细滑条要回来
                    .then(
                        if (alphabetBucketsOfVisible.isEmpty()) {
                            Modifier.shortScrollbar(listState)
                        } else {
                            Modifier
                        },
                    ),
                contentPadding = PaddingValues(
                    // 列表一直铺到屏幕最底（底栏浮在上面），只在**内容末尾**给底栏让位，
                    // 这样最后一项仍能滚到胶囊上方，而背景不会在胶囊前就断掉。
                    bottom = AppSpacing.bottomBarInset + AppSpacing.sp24,
                ),
            ) {
                // ── 搜索框 ──
                item(key = "search") {
                    SearchBarField(
                        value = st.query,
                        onValueChange = { libraryViewModel.setQuery(it) },
                        onSubmit = {},
                        hint = "搜索歌曲、歌手、专辑、文件夹",
                        horizontalPadding = AppSpacing.sp16,
                        verticalPadding = AppSpacing.sp4,
                        height = 44.dp,
                    )
                }

                // ── 四宫格：点开就是压入 pageStack（与流媒体详情同一套栈 / 返回裁决）──
                item(key = "grid") {
                    Column(modifier = Modifier.padding(horizontal = AppSpacing.sp16)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(GridGap)) {
                            LocalEntryCard(
                                kind = LocalCategory.artist,
                                label = "歌手",
                                trailing = "${groupCounts.first}",
                                selected = false,
                                onClick = { uiViewModel.pushRoute(LocalSectionRoute(LocalSection.artists)) },
                                modifier = Modifier.weight(1f),
                            )
                            LocalEntryCard(
                                kind = LocalCategory.album,
                                label = "专辑",
                                trailing = "${groupCounts.second}",
                                selected = false,
                                onClick = { uiViewModel.pushRoute(LocalSectionRoute(LocalSection.albums)) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(modifier = Modifier.height(GridGap))
                        Row(horizontalArrangement = Arrangement.spacedBy(GridGap)) {
                            LocalEntryCard(
                                kind = LocalCategory.folder,
                                label = "文件夹",
                                trailing = "${groupCounts.third}",
                                selected = false,
                                onClick = { uiViewModel.pushRoute(LocalSectionRoute(LocalSection.folders)) },
                                modifier = Modifier.weight(1f),
                            )
                            LocalEntryCard(
                                kind = LocalCategory.recent,
                                label = "最近播放",
                                trailing = "$recentCount",
                                selected = false,
                                onClick = { uiViewModel.pushRoute(LocalSectionRoute(LocalSection.recent)) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                // ── 统计行 ──
                item(key = "stats") {
                    LocalStatsRow(
                        countText = "${visible.size} 首",
                        sizeText = formatTotalSize(totalBytes),
                        progressText = when {
                            st.scanning && st.tracks.isEmpty() -> "正在扫描文件…"
                            st.pending > 0 -> "读取元数据 $probedCount/${st.tracks.size}"
                            else -> null
                        },
                        progress = when {
                            st.scanning && st.tracks.isEmpty() -> null
                            st.pending > 0 && st.tracks.isNotEmpty() ->
                                probedCount.toFloat() / st.tracks.size
                            else -> null
                        },
                        sortLabel = st.sort.label,
                        onSort = { libraryViewModel.cycleSort() },
                        ascending = st.sortAscending,
                        onToggleOrder = { libraryViewModel.toggleSortOrder() },
                        onRescan = { libraryViewModel.refresh(context, force = true) },
                        modifier = Modifier.padding(
                            horizontal = AppSpacing.sp16,
                            vertical = AppSpacing.sp8,
                        ),
                    )
                }

                // ── 分区标题（主页固定「全部歌曲」；分组内容在各自弹出的列表页里）──
                item(key = "header") {
                    Box(modifier = Modifier.padding(horizontal = AppSpacing.sp16)) {
                        LocalSectionHeader(
                            title = "全部歌曲",
                            subtitle = "${visible.size} 首",
                            onBack = null,
                        )
                    }
                }

                // ── 列表主体：全部歌曲 ──
                if (st.tracks.isEmpty()) {
                    item(key = "empty") {
                        LibraryEmptyState(
                            permissionMissing = st.permissionMissing,
                            scanning = st.busy,
                            onRetry = { libraryViewModel.refresh(context, force = true) },
                        )
                    }
                } else if (visible.isEmpty()) {
                    item(key = "noresult") {
                        Text(
                            text = "没有匹配的歌曲",
                            color = AppColors.textTertiary,
                            fontSize = AppFontSize.base,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = AppSpacing.sp24),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                } else {
                    items(visible, key = { "t:${it.path}" }) { meta ->
                        Box(
                            modifier = Modifier.padding(
                                start = AppSpacing.sp12,
                                // 字母条压在右侧：行尾让出它的宽度，卡片边缘不会被条子切到
                                end = AppSpacing.sp12 + if (alphabetBucketsOfVisible.isEmpty()) {
                                    0.dp
                                } else {
                                    LocalAlphabetBarWidth + AppSpacing.sp8
                                },
                            ),
                        ) {
                            LocalTrackRow(
                                meta = meta,
                                isActive = playback.currentSong?.id == meta.id,
                                // 正在播放用**中性灰**（与流媒体单栏搜索列表 `SongTile` 完全一致）：
                                // 单栏长列表整屏被主色染会很吵，只有跟封面取色的页面才用彩色。
                                playingTint = AppColors.playingGray,
                                onClick = { playFrom(meta) },
                                // ⋮ 复用流媒体的歌曲操作面板（下载 → 歌曲详情，在面板内按平台区分）
                                onMore = { uiViewModel.setSongActionsVisible(true, meta.toSong()) },
                            )
                        }
                    }
                }
            }
                // 字母索引条：顶部对齐**第一首歌**，跟着它上下移动（未上滑时允许显示不全）
                LocalAlphabetIndex(
                    buckets = alphabetBucketsOfVisible,
                    onJump = { index ->
                        scope.launch { listState.scrollToItem(HEADER_ITEM_COUNT + index) }
                    },
                    slotHeight = slotHeight,
                    dotSlot = dotSlot,
                    reversed = !st.sortAscending,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset(0, barTopPx.roundToInt()) }
                        .padding(end = 2.dp),
                )
            }
        }

    }

    // ── 文件详情 / 队列 sheet ──
    // 注意：sheet 里要显示「实时」的 meta（打开后 enrich 会补上位深/声道），
    // 所以每次都从最新 state 里按 path 取，而不是用点击时抓的快照。
    val target = sheetTrack?.let { s -> st.tracks.firstOrNull { it.path == s.path } ?: s }
    if (target != null) {
        AppSheet(
            visible = true,
            title = target.title.ifBlank { target.file.name },
            onClose = { sheetTrack = null },
        ) {
            Column(modifier = Modifier.padding(horizontal = AppSpacing.sp16)) {
                LocalInfoLine("艺术家", target.artist.ifBlank { "—" })
                LocalInfoLine("专辑", target.album.ifBlank { "—" })
                LocalInfoLine(
                    "编码",
                    listOfNotNull(
                        target.codec.uppercase(Locale.ROOT).takeIf { it.isNotEmpty() },
                        target.formatText.takeIf { it.isNotEmpty() },
                        target.bitrate.takeIf { it > 0 }?.let { "${it}kbps" },
                    ).joinToString(" · ").ifEmpty { "—" },
                )
                LocalInfoLine("时长", target.durationText.ifBlank { "—" })
                LocalInfoLine("大小", target.sizeText.ifBlank { "—" })
                LocalInfoLine("文件夹", target.folderName.ifBlank { "—" })
                LocalInfoLine("路径", target.path)

                Spacer(modifier = Modifier.height(AppSpacing.sp12))
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sp10)) {
                    SheetAction("加入下一首") {
                        playerViewModel.addToQueue(target.toSong())
                        sheetTrack = null
                    }
                    SheetAction("播放") {
                        playFrom(target)
                        sheetTrack = null
                    }
                }
                Spacer(modifier = Modifier.height(AppSpacing.sp16))
            }
        }
    }
}

/** 总字节 → `4.8 GB` / `812 MB` */
private fun formatTotalSize(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    if (gb >= 1) return String.format(Locale.US, "%.1f GB", gb)
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return String.format(Locale.US, "%.0f MB", mb)
}

/** sheet 里的一行「标签 —— 值」 */
@Composable
private fun LocalInfoLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppSpacing.sp6),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            color = AppColors.textTertiary,
            fontSize = AppFontSize.size13,
            modifier = Modifier.padding(end = AppSpacing.sp12),
        )
        Text(
            text = value,
            color = AppColors.textSecondary,
            fontSize = AppFontSize.size13,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** sheet 里的胶囊按钮 */
@Composable
private fun SheetAction(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(AppRadius.r10))
            .background(AppColors.primaryA12)
            .clickableRipple { onClick() }
            .padding(horizontal = AppSpacing.sp16, vertical = AppSpacing.sp10),
    ) {
        Text(
            text = label,
            color = AppColors.primary,
            fontSize = AppFontSize.size13,
            fontWeight = AppFontWeight.medium,
        )
    }
}

/** 空态 / 未授权态 */
@Composable
private fun LibraryEmptyState(
    permissionMissing: Boolean,
    scanning: Boolean,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.sp16, vertical = AppSpacing.sp40),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(LibraryEmptyDiscSize)
                .background(AppColors.whiteA03, androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            VinylRecord(modifier = Modifier.size(LibraryEmptyVinylSize))
        }
        Spacer(modifier = Modifier.height(AppSpacing.sp24))
        Text(
            text = when {
                permissionMissing -> "没有读取文件的权限"
                scanning -> "正在扫描设备上的音乐…"
                else -> "没有找到音频文件"
            },
            color = AppColors.textPrimary,
            fontSize = AppFontSize.size17,
            fontWeight = AppFontWeight.semiBold,
        )
        Spacer(modifier = Modifier.height(AppSpacing.sp8))
        Text(
            text = when {
                permissionMissing -> "在系统设置里开启「所有文件访问」后即可扫描本地音乐"
                scanning -> "首次扫描会读取文件元数据，稍等片刻"
                else -> "把音频放进 Music 或 Download 目录后再试"
            },
            color = AppColors.textTertiary,
            fontSize = AppFontSize.base,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(AppSpacing.sp18))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(AppRadius.r10))
                .background(AppColors.whiteA06)
                .clickableRipple { onRetry() }
                .padding(horizontal = AppSpacing.sp20, vertical = AppSpacing.sp10),
        ) {
            Text(text = "重新扫描", color = AppColors.textPrimary, fontSize = AppFontSize.size13)
        }
    }
}
