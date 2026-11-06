package com.annieplayer.annie_music.kt.widgets

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing

/** 每页行数：与歌手详情「最热门」一致（5 行/页，最多 4 页 = 20 首）。 */
const val SongRowPagerRows: Int = 5

/**
 * 每页右侧给下一张留出的宽度（**固定值，不是比例**）。
 *
 * ## 为什么改成固定值
 * 这个数同时决定两件事，而它们争的是同一块右侧像素：
 * 1. **露出多少下一页** = 本值 − 行内左留白；
 * 2. **⋮ 离屏幕右边多远** = 本值 + 按钮半宽 14dp。
 *
 * 用「页宽百分比」那次行不通：百分比是相对容器宽的，容器一换（不同页面 padding）露出量就漂；
 * 而且没法同时表达「左边对齐 16dp」和「右边露出 20dp」。现在左边由
 * [contentPadding] 的 start 负责、右边由本值负责，两边互不干扰。
 *
 * 20dp 的由来：行内左留白 4dp + 可见封面 16dp，同时 ⋮ 距屏幕右边 34dp
 * （普通满宽列表是 30dp，差 4dp，不会被读成「缩在中间」）。
 */
private val SongRowPeekWidth = 20.dp

/** 分页容器左侧留白：默认 0 —— 行本身已有列表 padding，再叠一层会把竖条与封面挤到一起 */
private val SongRowStartInset = 0.dp

/**
 * 歌曲翻页容器：**几何只此一份**。
 *
 * 主页歌曲区块、流媒体歌手详情的「热门单曲」、本地歌手详情的「歌曲」段，三处观感必须一致，
 * 所以页宽 / 页间距 / 左右留白 / 高度 / 页码指示全部收在这里 —— 以前本地那份是内联复制，
 * 改宽度要改两处，一漏就漂移。
 *
 * 泛型是为了让本地曲库（`LocalSongMeta`）和流媒体（`Song`）共用同一份几何：
 * 行长什么样由 [row] 决定，这里只管排版。
 *
 * @param items 已经是「要展示的全部条目」（调用方自己 `take(n)`，一般不超过每页行数 × 4）
 * @param rowsPerPage 每页行数
 * @param minHeightRows 高度按这么多行算（少于 [rowsPerPage] 时用，比如本地歌手总曲目不足一页 → 收高不留白）
 * @param startInset 左侧留白（分页容器自己那份，不含行内留白）。
 *   默认 12dp（主页货架与上下区块同为 16dp 视觉左边距）；两个歌手详情页传 20dp ——
 *   那两页的 hero 用的是 24dp，列表若停在 16dp 会比上面的头像/标题突出 8dp，读起来像没对齐。
 *   **加这个值只会让页整体右移并变窄，右边界不动**（页宽公式里同步减掉了它），
 *   所以右侧露出和 ⋮ 位置完全不受影响。
 * @param row 单行渲染；`index` 是**整段列表里的绝对下标**（用于显示序号 1..n）
 */
@Composable
fun <T> SongRowPager(
    items: List<T>,
    modifier: Modifier = Modifier,
    rowsPerPage: Int = SongRowPagerRows,
    minHeightRows: Int = rowsPerPage,
    startInset: Dp = SongRowStartInset,
    row: @Composable (item: T, index: Int) -> Unit,
) {
    if (items.isEmpty() || rowsPerPage <= 0) return
    val pageCount = (items.size + rowsPerPage - 1) / rowsPerPage
    val pagerState = rememberPagerState(pageCount = { pageCount })
    // 固定高度：Pager 在无界高度里测量会抛异常；
    // 只有一页时按实际条目数收高，避免底部留一块空白
    val visibleRows = minOf(minHeightRows.coerceAtLeast(1), rowsPerPage, items.size).coerceAtLeast(1)

    Column(modifier = modifier.fillMaxWidth()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(AppSize.detailRowHeight * visibleRows),
        ) {
            // 页宽 = 可用宽度 − 左侧留白 − 右侧露出；两边各自独立，互不侵占。
            // 调 startInset 时页宽同步变窄，右边界（= 可用宽度 − peek）保持不变。
            val pageWidth = (maxWidth - startInset - SongRowPeekWidth).coerceAtLeast(0.dp)
            HorizontalPager(
                state = pagerState,
                pageSize = PageSize.Fixed(pageWidth),
                pageSpacing = AppSpacing.sp4,
                // 只给 start：end 必须留 0，否则右侧露出会被 contentPadding 再吃掉一层
                contentPadding = PaddingValues(start = startInset),
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                Column(modifier = Modifier.fillMaxSize()) {
                    for (rowInPage in 0 until rowsPerPage) {
                        val index = page * rowsPerPage + rowInPage
                        if (index >= items.size) break
                        row(items[index], index)
                    }
                }
            }
        }
        if (pageCount > 1) {
            SongRowPagerIndicator(page = pagerState.currentPage, pageCount = pageCount)
        }
    }
}

/**
 * 页码指示：一个半透明小胶囊里放「页点 + `2/4`」。
 *
 * 只在多于一页时出现 —— 单页还挂个「1/1」纯属噪音。当前页那个点会拉长成短条、
 * 颜色从半透明白渐到主色，时长取 [AppDuration.overlay]（200ms）：再长会显得拖沓，
 * 跟手滑的手感不一致。
 *
 * 为什么做成胶囊而不是"裸点"：歌曲行本身没有底板，裸点浮在页面上会显得零碎；
 * 加一层 `whiteA04` 的胶囊把「第几页 / 共几页」收成一个控件，视觉上更像设计过的组件。
 */
@Composable
private fun SongRowPagerIndicator(page: Int, pageCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = AppSpacing.sp8, end = AppSpacing.sp16),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(AppColors.whiteA04)
                .padding(horizontal = AppSpacing.sp10, vertical = AppSpacing.sp4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (index in 0 until pageCount) {
                val active = index == page
                val dotWidth by animateDpAsState(
                    targetValue = if (active) AppSpacing.sp16 else AppSpacing.sp4,
                    animationSpec = tween(durationMillis = AppDuration.overlay),
                    label = "songRowDotWidth",
                )
                val dotColor by animateColorAsState(
                    targetValue = if (active) AppColors.primary else AppColors.whiteA20,
                    animationSpec = tween(durationMillis = AppDuration.overlay),
                    label = "songRowDotColor",
                )
                Box(
                    modifier = Modifier
                        .padding(horizontal = AppSpacing.sp2)
                        .width(dotWidth)
                        .height(AppSpacing.sp4)
                        .clip(CircleShape)
                        .background(dotColor),
                )
            }
            Spacer(modifier = Modifier.width(AppSpacing.sp8))
            Text(
                text = "${page + 1}/$pageCount",
                color = AppColors.whiteA40,
                fontSize = AppFontSize.size10,
                fontWeight = AppFontWeight.medium,
            )
        }
    }
}
