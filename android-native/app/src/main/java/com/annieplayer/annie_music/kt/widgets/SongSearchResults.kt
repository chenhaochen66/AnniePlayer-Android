package com.annieplayer.annie_music.kt.widgets

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.data.model.DownloadStatus
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.features.search.SearchSource
import com.annieplayer.annie_music.kt.features.search.kSearchPageSize
import com.annieplayer.annie_music.kt.ui.theme.AppBorder
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 入场动画上移距离（对齐 Flutter `Transform.translate(offset: Offset(0, 25 * (1 - t)))`） */
private val SongSearchLiftDistance = 25.dp

/**
 * 距底部多少行内触发下一页。
 *
 * Flutter 用 `extentAfter < 400`（逻辑像素）；一行 SongTile 约 60dp，故 400dp 约 6 行。
 * 列表是懒加载的，无法对未组合的行求和，因此按「最后可见下标」近似该阈值。
 */
private const val SongSearchLoadMoreRows = 6

/**
 * 单列数据：源 + 该源首页的搜索结果。
 *
 * 对应 Flutter `song_search_results.dart` 的 `SearchColumn`（源 + 可空 Future）；
 * Kotlin 侧按 [SingerSearchResults] 的约定改为「显式 isLoading + 非空列表」，
 * 分支顺序与 Dart 保持一致：loading -> empty-hint -> content。
 *
 * @param source 该列对应的源
 * @param songs 首页结果（[isLoading] 为 true 时不参与渲染）
 * @param isLoading 首页是否仍在加载（对齐 Dart `_isColumnPending`）
 */
data class SongSearchColumn(
    val source: SearchSource,
    val songs: List<Song> = emptyList(),
    val isLoading: Boolean = false,
)

/**
 * 单曲双列结果（1:1 移植 Flutter `lib/widgets/song_search_results.dart`）。
 *
 * 结构：左右两列各自独立分页（左列 = columns[0]、右列 = columns[1]），
 * 中间一条 1dp 分隔线；每列滑到底部附近自动追加该源下一页。
 *
 * 行为对齐点：
 * - **每列独立加载**：先返回的源立刻显示，慢的源单独转圈（不被最慢的源拖住）；
 *   只有两列都还在加载时才整屏显示 [SkeletonLoader]。
 * - **每列独立分页**：首页由调用方经 [columns] 传入；第 2 页起由本组件通过
 *   [onLoadMore] 拉取并累积，距底部约 400dp（约 6 行）内触发。
 * - **入场动画**：400ms + easeOutCubic，透明度 0->1 且同时上移 25dp -> 0
 *   （对应 Dart 的 `TweenAnimationBuilder` + `Transform.translate`），
 *   在搜索词或源分组变化时重放。
 * - **多选**：有待下载选中（[selectedSongs] 非空）时进入选择模式，点击 = 选择而非播放；
 *   长按进入选择（由 [SongTile] 处理）。
 * - **下载环**：逐曲由 [downloadStatusOf] 提供状态，下载中显示旋转环。
 * - 尺寸与视觉全部取自主题 token；文本只设 fontSize，不设 lineHeight（主题已中和 Material3 默认行高）。
 *
 * 调用方需提供有界高度（对齐 Flutter 中 Row 依赖 `Expanded` 的约束）。
 *
 * @param columns 两列数据（最多取前两个；更少 / 为 null 时该半区保持空白，与 Dart 一致）
 * @param query 已提交的搜索词（为空且无结果时不显示「未找到结果」）
 * @param searchToken 搜索代次（调用方每次提交搜索自增；同词同源重搜时靠它触发分页重置）
 * @param onPlay 点击播放（携带所在列当前已加载的完整队列）
 * @param onDownload 下载
 * @param onToggleSelect 切换选中（选择模式 / 长按）
 * @param onLoadMore 加载某个源的下一页（[Int] 从 2 开始）；异常按空页处理
 * @param pageSize 每页条数（默认 [kSearchPageSize] = 60）
 * @param activeSongId 当前播放歌曲 id（决定该行高亮）
 * @param selectedSongs 已选中的歌曲（非空即进入选择模式）
 * @param downloadStatusOf 逐曲下载状态（决定是否显示下载环）
 */
@Composable
fun SongSearchResults(
    columns: List<SongSearchColumn>,
    query: String,
    searchToken: Int = 0,
    onPlay: (Song, List<Song>) -> Unit,
    onDownload: (Song) -> Unit,
    onToggleSelect: (Song) -> Unit,
    onLoadMore: suspend (SearchSource, Int) -> List<Song>,
    modifier: Modifier = Modifier,
    pageSize: Int = kSearchPageSize,
    activeSongId: String? = null,
    selectedSongs: List<Song> = emptyList(),
    downloadStatusOf: (Song) -> DownloadStatus? = { null },
) {
    // 每列第 2 页起的累积结果（首页由调用方传入）
    val extrasBySource = remember { mutableStateMapOf<SearchSource, List<Song>>() }

    // 每列已加载到第几页（首页 = 1）
    val pageBySource = remember { mutableStateMapOf<SearchSource, Int>() }

    // 每列是否还有下一页（无记录时由首页条数推导：songs.size >= pageSize）
    val hasMoreBySource = remember { mutableStateMapOf<SearchSource, Boolean>() }

    // 每列是否正在加载下一页
    val loadingMoreBySource = remember { mutableStateMapOf<SearchSource, Boolean>() }

    // 查询代次：搜索词 / 源分组变化时 +1，用于丢弃过期的分页结果
    var generation by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    val sourceKey = columns.joinToString(separator = "-") { it.source.name }

    // 关键词、源分组或搜索代次变化 -> 整列重置（对齐 Dart didUpdateWidget + _loadFirstPages）；
    // 同词同源重搜时前两者不变，靠调用方自增的 searchToken 兜底触发，避免旧分页数据串入新结果
    LaunchedEffect(searchToken, query, sourceKey) {
        generation++
        extrasBySource.clear()
        pageBySource.clear()
        hasMoreBySource.clear()
        loadingMoreBySource.clear()
    }

    // 滑到底部时追加该源下一页（对齐 Dart _loadMore：分页从 2 开始、空页终止）
    fun loadMore(source: SearchSource) {
        if (loadingMoreBySource[source] == true) return
        val column = columns.firstOrNull { it.source == source } ?: return
        val hasMore = hasMoreBySource[source] ?: column.songs.isNotEmpty()
        if (!hasMore) return

        loadingMoreBySource[source] = true
        val nextPage = (pageBySource[source] ?: 1) + 1
        val requestGeneration = generation
        scope.launch {
            val list = try {
                onLoadMore(source, nextPage)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            // 搜索词 / 源分组已切换：旧请求完成时已无人监听（对齐 Dart），丢弃
            if (requestGeneration != generation) return@launch
            loadingMoreBySource[source] = false
            if (list.isEmpty()) {
                hasMoreBySource[source] = false
                return@launch
            }
            pageBySource[source] = nextPage
            // 按歌曲 id 去重（保留首次出现）：源站翻页常返回与前面页重复的歌曲，
            // 重复 id 传给 items(key=...) 会抛「Key was already used」导致整屏崩溃
            val before = extrasBySource[source].orEmpty().size
            val merged = (extrasBySource[source].orEmpty() + list).distinctBy { it.id }
            extrasBySource[source] = merged
            // 本页非空且带来新条目才算还有下一页；不能用 size >= pageSize ——
            // 映射会丢弃部分条目（如酷狗无版权），条数天然少于页大小会误判成到底。
            hasMoreBySource[source] = list.isNotEmpty() && merged.size > before
        }
    }

    val left = columns.getOrNull(0)
    val right = columns.getOrNull(1)

    val leftPending = left?.isLoading == true
    val rightPending = right?.isLoading == true

    // 两列都还没回来 -> 整屏骨架（首屏观感与 RN 一致）
    if (leftPending && rightPending) {
        SkeletonLoader(modifier = modifier)
        return
    }

    val leftSongs = songSearchColumnContent(left, extrasBySource)
    val rightSongs = songSearchColumnContent(right, extrasBySource)

    // 两列都回来且都为空才算「未找到结果」
    if (!leftPending && !rightPending && leftSongs.isEmpty() && rightSongs.isEmpty()) {
        if (query.isEmpty()) return
        SongSearchGlobalEmptyHint(modifier = modifier)
        return
    }

    // 入场动画：400ms + easeOutCubic（key = query + 源分组，变化时重放）
    val progress = remember { Animatable(0f) }
    LaunchedEffect(query, sourceKey) {
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = AppDuration.listFade, easing = AppEasing.easeOutCubic),
        )
    }
    val liftPx = with(LocalDensity.current) { SongSearchLiftDistance.toPx() }

    Row(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = progress.value
                translationY = liftPx * (1f - progress.value)
            }
            .padding(horizontal = AppSpacing.sp8),
    ) {
        // ── 左列 ──
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            SongSearchColumnBody(
                column = left,
                pending = leftPending,
                songs = leftSongs,
                pageSize = pageSize,
                hasMoreBySource = hasMoreBySource,
                loadingMoreBySource = loadingMoreBySource,
                onLoadMore = { src -> loadMore(src) },
                activeSongId = activeSongId,
                selectedSongs = selectedSongs,
                query = query,
                onPlay = onPlay,
                onDownload = onDownload,
                onToggleSelect = onToggleSelect,
                downloadStatusOf = downloadStatusOf,
            )
        }

        // ── 中间列分隔线（上下各留 8dp，撑满行高；单列时不画） ──
        if (right != null) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(vertical = AppSpacing.sp8)
                    .width(AppBorder.thin)
                    .background(AppColors.whiteA04),
            )

            // ── 右列 ──
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                SongSearchColumnBody(
                    column = right,
                    pending = rightPending,
                    songs = rightSongs,
                    pageSize = pageSize,
                    hasMoreBySource = hasMoreBySource,
                    loadingMoreBySource = loadingMoreBySource,
                    onLoadMore = { src -> loadMore(src) },
                    activeSongId = activeSongId,
                    selectedSongs = selectedSongs,
                    query = query,
                    onPlay = onPlay,
                    onDownload = onDownload,
                    onToggleSelect = onToggleSelect,
                    downloadStatusOf = downloadStatusOf,
                )
            }
        }
    }
}

/** 该列当前应展示的完整列表 = 首页（来自调用方）+ 本地累积的后续页（按 id 去重，保留首次出现） */
private fun songSearchColumnContent(
    column: SongSearchColumn?,
    extras: Map<SearchSource, List<Song>>,
): List<Song> {
    if (column == null) return emptyList()
    // 双保险：追加处已去重，这里对「首页 + 累积页」整体再按 id 去重，
    // 确保任何合并路径都不会把重复 id 交给 LazyColumn 的 key
    return (column.songs + extras[column.source].orEmpty()).distinctBy { it.id }
}

/**
 * 单列内容（对齐 Dart `_buildColumn`）：null 列静默、加载中转圈、空列提示、否则歌曲列表。
 * 分支顺序与 Dart 完全一致：loading -> empty-hint -> content。
 */
@Composable
private fun SongSearchColumnBody(
    column: SongSearchColumn?,
    pending: Boolean,
    songs: List<Song>,
    pageSize: Int,
    hasMoreBySource: Map<SearchSource, Boolean>,
    loadingMoreBySource: Map<SearchSource, Boolean>,
    onLoadMore: (SearchSource) -> Unit,
    activeSongId: String?,
    selectedSongs: List<Song>,
    query: String,
    onPlay: (Song, List<Song>) -> Unit,
    onDownload: (Song) -> Unit,
    onToggleSelect: (Song) -> Unit,
    downloadStatusOf: (Song) -> DownloadStatus?,
) {
    if (column == null) return
    // 该列单独转圈：先回来的那一列立刻显示，不被慢的源拖住
    if (pending) {
        SongSearchColumnLoading()
        return
    }
    if (songs.isEmpty()) {
        // 空列保持静默（对齐 RN），有关键词时显示带源名的提示
        if (query.isEmpty()) return
        SongSearchEmptyHint(source = column.source)
        return
    }

    SongSearchColumnList(
        source = column.source,
        songs = songs,
        activeSongId = activeSongId,
        selectedSongs = selectedSongs,
        // 有待下载选中 -> 进入多选模式（点击 = 选择，不播放）
        selectionMode = selectedSongs.isNotEmpty(),
        onPlay = onPlay,
        onDownload = onDownload,
        onToggleSelect = onToggleSelect,
        downloadStatusOf = downloadStatusOf,
        hasMore = hasMoreBySource[column.source] ?: column.songs.isNotEmpty(),
        loadingMore = loadingMoreBySource[column.source] == true,
        onLoadMore = { onLoadMore(column.source) },
    )
}

/**
 * 单列歌曲列表（RN: 每列独立 FlatList；此处每列独立 LazyColumn + 独立分页）。
 *
 * Flutter 用 `NotificationListener` 监听 `extentAfter < 400` 触发下一页；
 * Compose 侧改用 `derivedStateOf` 观察最后可见下标是否进入距底部 [SongSearchLoadMoreRows] 行内，
 * 触发时机等价（仅在越线翻转时触发，不在内容增长时链式触发）。
 */
@Composable
private fun SongSearchColumnList(
    source: SearchSource,
    songs: List<Song>,
    activeSongId: String?,
    selectedSongs: List<Song>,
    selectionMode: Boolean,
    onPlay: (Song, List<Song>) -> Unit,
    onDownload: (Song) -> Unit,
    onToggleSelect: (Song) -> Unit,
    downloadStatusOf: (Song) -> DownloadStatus?,
    hasMore: Boolean,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
) {
    val listState = rememberLazyListState()
    val shouldLoadMore by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()
            lastVisible != null &&
                info.totalItemsCount > 0 &&
                lastVisible.index >= info.totalItemsCount - 1 - SongSearchLoadMoreRows
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && hasMore && !loadingMore) onLoadMore()
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        // 底部留白：避开迷你播放条（约 92dp）与右下角悬浮按钮
        contentPadding = PaddingValues(bottom = AppSpacing.searchListBottomInset),
    ) {
        items(items = songs, key = { it.id }) { song ->
            SongTile(
                song = song,
                onPlay = { played -> onPlay(played, songs) },
                onDownload = onDownload,
                onToggleSelect = onToggleSelect,
                selected = selectedSongs.any { s -> s.id == song.id },
                isActive = song.id == activeSongId,
                sourceLabel = source.label,
                selectionMode = selectionMode,
                downloadStatus = downloadStatusOf(song),
            )
        }
        item(key = "songSearchFooter-${source.name}") {
            SongSearchColumnFooter(hasMore = hasMore, loading = loadingMore)
        }
    }
}

/** 列尾：加载下一页中 / 没有更多了（对齐 Dart `_ColumnFooter`） */
@Composable
private fun SongSearchColumnFooter(hasMore: Boolean, loading: Boolean) {
    if (loading) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = AppSpacing.sp16),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = AppColors.white30,
                strokeWidth = AppBorder.progressStroke,
            )
        }
        return
    }
    if (!hasMore) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = AppSpacing.sp16),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "没有更多了", color = AppColors.white30, fontSize = AppFontSize.xs)
        }
        return
    }
    Spacer(modifier = Modifier.height(AppSpacing.sp24))
}

/** 单列加载中（另一列已出结果时，该列单独显示） */
@Composable
private fun SongSearchColumnLoading() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            color = AppColors.white30,
            strokeWidth = AppBorder.progressStroke,
        )
        Spacer(modifier = Modifier.height(AppSpacing.sp8))
        Text(text = "加载中", color = AppColors.white30, fontSize = AppFontSize.xs)
    }
}

/** 单列空结果提示（带源名；对齐 Dart `_EmptyHint(source: ...)`） */
@Composable
private fun SongSearchEmptyHint(source: SearchSource) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = source.label, color = AppColors.white30, fontSize = AppFontSize.sm)
        Spacer(modifier = Modifier.height(AppSpacing.sp4))
        Text(text = "未找到结果", color = AppColors.white30, fontSize = AppFontSize.base)
    }
}

/** 整屏空结果提示（两列都为空；对齐 Dart `_EmptyHint()`） */
@Composable
private fun SongSearchGlobalEmptyHint(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "未找到结果", color = AppColors.white30, fontSize = AppFontSize.base)
    }
}
