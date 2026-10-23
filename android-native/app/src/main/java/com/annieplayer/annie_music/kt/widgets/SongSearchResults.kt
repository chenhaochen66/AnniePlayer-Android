package com.annieplayer.annie_music.kt.widgets

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.data.model.DownloadStatus
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
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
 * Flutter 用 `extentAfter < 400`（逻辑像素）；单栏大行约 92dp，故 400dp 约 4.5 行。
 * 列表是懒加载的，无法对未组合的行求和，因此按「最后可见下标」近似该阈值。
 */
private const val SongSearchLoadMoreRows = 4

/**
 * 单曲搜索的单列数据：平台 + 该平台首页的搜索结果。
 *
 * 对应 Flutter `song_search_results.dart` 的 `SearchColumn`，但 Kotlin 版是**单栏**：
 * 一次只展示一个平台，[isLoading] 为 true 时表示该平台首页仍在请求中。
 *
 * @param platform 该列对应的平台
 * @param songs 首页结果（[isLoading] 为 true 时不参与渲染）
 * @param isLoading 首页是否仍在加载
 */
data class SongSearchColumn(
    val platform: MusicPlatform,
    val songs: List<Song> = emptyList(),
    val isLoading: Boolean = false,
)

/**
 * 单曲搜索结果（**单栏**；对应 Flutter `lib/widgets/song_search_results.dart`，
 * 但已按需求从「双列并排两个源」改为一行一首的单列表）。
 *
 * 结构：一个 LazyColumn，滑到接近底部时自动追加下一页；一次只展示 [column] 的平台。
 *
 * 行为对齐点：
 * - **首屏骨架**：首页未回来时整屏 [SkeletonLoader]；空结果给平台名 + 「未找到结果」。
 * - **独立分页**：首页由调用方经 [column] 传入；第 2 页起由本组件通过 [onLoadMore] 拉取并累积，
 *   距底部约 400dp（约 4 行）内触发，仅在越线翻转时触发、不在内容增长时链式触发。
 * - **入场动画**：400ms + easeOutCubic，透明度 0->1 且同时上移 25dp -> 0
 *   （对应 Dart 的 `TweenAnimationBuilder` + `Transform.translate`），
 *   在搜索词或平台变化时重放。
 * - **多选**：有待下载选中（[selectedSongs] 非空）时进入选择模式，点击 = 选择而非播放；
 *   长按进入选择（由 [SongTile] 处理）。
 * - **下载环**：逐曲由 [downloadStatusOf] 提供状态，下载中显示旋转环。
 *
 * @param column 当前平台的结果列（null 表示还没有发起过搜索）
 * @param query 已提交的搜索词（为空且无结果时不显示「未找到结果」）
 * @param searchToken 搜索代次（调用方每次提交搜索自增；同词同平台重搜时靠它触发分页重置）
 * @param onPlay 点击播放（携带当前已加载的完整队列）
 * @param onDownload 下载
 * @param onToggleSelect 切换选中（选择模式 / 长按）
 * @param onLoadMore 加载该平台的下一页（[Int] 从 2 开始）；异常按空页处理
 * @param activeSongId 当前播放歌曲 id（决定该行高亮）
 * @param selectedSongs 已选中的歌曲（非空即进入选择模式）
 * @param downloadStatusOf 逐曲下载状态（决定是否显示下载环）
 */
@Composable
fun SongSearchResults(
    column: SongSearchColumn?,
    query: String,
    searchToken: Int = 0,
    onPlay: (Song, List<Song>) -> Unit,
    onDownload: (Song) -> Unit,
    onToggleSelect: (Song) -> Unit,
    onLoadMore: suspend (Int) -> List<Song>,
    modifier: Modifier = Modifier,
    activeSongId: String? = null,
    selectedSongs: List<Song> = emptyList(),
    downloadStatusOf: (Song) -> DownloadStatus? = { null },
) {
    // 第 2 页起的累积结果（首页由调用方传入）
    var extras by remember { mutableStateOf(emptyList<Song>()) }

    // 已加载到第几页（首页 = 1）
    var page by remember { mutableIntStateOf(1) }

    // 是否还有下一页；null = 尚未翻过页，按首页条数推导
    var hasMoreOverride by remember { mutableStateOf<Boolean?>(null) }

    // 是否正在加载下一页
    var loadingMore by remember { mutableStateOf(false) }

    // 搜索代次：搜索词 / 平台变化时 +1，用于丢弃过期的分页结果
    var generation by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    val platform = column?.platform

    // 关键词、平台或搜索代次变化 -> 整列重置（对齐 Dart didUpdateWidget + _loadFirstPages）；
    // 同词同平台重搜时前两者不变，靠调用方自增的 searchToken 兜底触发，避免旧分页数据串入新结果
    LaunchedEffect(searchToken, query, platform) {
        generation++
        extras = emptyList()
        page = 1
        hasMoreOverride = null
        loadingMore = false
    }

    // 首页 + 累积页按歌曲 id 去重（保留首次出现）：源站翻页常返回与前面页重复的歌曲，
    // 重复 id 传给 items(key=...) 会抛「Key was already used」导致整屏崩溃
    val songs = remember(column, extras) {
        (column?.songs.orEmpty() + extras).distinctBy { it.id }
    }

    val hasMore = hasMoreOverride ?: (column?.songs?.isNotEmpty() == true)

    // 滑到底部时追加下一页（对齐 Dart _loadMore：分页从 2 开始、空页终止）
    fun loadMore() {
        if (loadingMore || !hasMore) return
        loadingMore = true
        val nextPage = page + 1
        val requestGeneration = generation
        scope.launch {
            val list = try {
                onLoadMore(nextPage)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            // 搜索词 / 源已切换：旧请求完成时已无人监听（对齐 Dart），丢弃
            if (requestGeneration != generation) return@launch
            loadingMore = false
            if (list.isEmpty()) {
                hasMoreOverride = false
                return@launch
            }
            page = nextPage
            val before = extras.size
            val merged = (extras + list).distinctBy { it.id }
            extras = merged
            // 本页非空且带来新条目才算还有下一页；不能用 size >= pageSize ——
            // 映射会丢弃部分条目（如酷狗无版权），条数天然少于页大小会误判成到底。
            hasMoreOverride = merged.size > before
        }
    }

    // 首页还没回来 -> 整屏骨架（单栏大行尺寸，与结果行几何一致）
    if (column == null || column.isLoading) {
        SkeletonLoader(modifier = modifier, columns = 1, largeRows = true)
        return
    }

    if (songs.isEmpty()) {
        if (query.isEmpty()) return
        SongSearchEmptyHint(platform = column.platform, modifier = modifier)
        return
    }

    // 入场动画：400ms + easeOutCubic（key = query + 平台，变化时重放）
    val progress = remember { Animatable(0f) }
    LaunchedEffect(query, platform) {
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = AppDuration.listFade, easing = AppEasing.easeOutCubic),
        )
    }
    val liftPx = with(LocalDensity.current) { SongSearchLiftDistance.toPx() }

    SongSearchList(
        platform = column.platform,
        songs = songs,
        activeSongId = activeSongId,
        selectedSongs = selectedSongs,
        // 有待下载选中 -> 进入多选模式（点击 = 选择，不播放）
        selectionMode = selectedSongs.isNotEmpty(),
        onPlay = onPlay,
        onDownload = onDownload,
        onToggleSelect = onToggleSelect,
        downloadStatusOf = downloadStatusOf,
        hasMore = hasMore,
        loadingMore = loadingMore,
        onLoadMore = { loadMore() },
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = progress.value
                translationY = liftPx * (1f - progress.value)
            }
            .padding(horizontal = AppSpacing.sp8),
    )
}

/**
 * 单曲结果列表（一个 LazyColumn）。
 *
 * Flutter 用 `NotificationListener` 监听 `extentAfter < 400` 触发下一页；
 * Compose 侧改用 `derivedStateOf` 观察最后可见下标是否进入距底部 [SongSearchLoadMoreRows] 行内，
 * 触发时机等价（仅在越线翻转时触发，不在内容增长时链式触发）。
 */
@Composable
private fun SongSearchList(
    platform: MusicPlatform,
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
    modifier: Modifier = Modifier,
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
        modifier = modifier.shortScrollbar(listState),
        // 底部留白：避开常驻底栏与右下角 ⇄ 悬浮按钮
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
                sourceLabel = platform.label,
                selectionMode = selectionMode,
                downloadStatus = downloadStatusOf(song),
            )
        }
        item(key = "songSearchFooter") {
            SongSearchColumnFooter(hasMore = hasMore, loading = loadingMore)
        }
    }
}

/** 列尾：加载下一页中 / 没有更多了 */
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

/** 空结果提示（带平台名） */
@Composable
private fun SongSearchEmptyHint(platform: MusicPlatform, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = platform.label, color = AppColors.white30, fontSize = AppFontSize.sm)
        Spacer(modifier = Modifier.height(AppSpacing.sp4))
        Text(text = "未找到结果", color = AppColors.white30, fontSize = AppFontSize.base)
    }
}
