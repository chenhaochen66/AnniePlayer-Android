package com.annieplayer.annie_music.kt.features.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.annieplayer.annie_music.kt.core.state.HomeShelfAllData
import com.annieplayer.annie_music.kt.data.model.HomeTarget
import com.annieplayer.annie_music.kt.features.library.LocalDetailTopBar
import com.annieplayer.annie_music.kt.features.library.LocalOverlayFrame
import com.annieplayer.annie_music.kt.features.library.LocalStaticBackdrop
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing

/**
 * 主页区块的「全部」页（歌单 / 专辑卡片的完整列表）—— **只负责页面壳**。
 *
 * 卡片在 `HomeShelfAllCards.kt`；这里只管「怎么进来、怎么出去、怎么滚」。
 *
 * ## 它是页面栈里的一页，不是新界面
 * 与本地曲库的「全部专辑 / 全部歌曲」子页**用同一套壳**：
 * - 入口：`uiViewModel.pushRoute(HomeShelfRoute(...))` → `UiState.pageStack`
 * - 容器：`LocalOverlayFrame`（遮罩 + 150ms 滑入 / 滑出）
 * - 顶栏：`LocalDetailTopBar`（同一个返回箭头、同一套内边距与字号）
 * - 背景：`LocalStaticBackdrop`（= `CoverPaletteBackdrop`，跟着正在播放封面取色，与主页连续）
 * - 返回：**本页不声明 `BackHandler`**。出栈统一由 `AppShell` 唯一的 PAGE 处理器裁决，
 *   再经 `PageCloseRelay` 调到 `LocalOverlayFrame` 注册的 `requestClose`（先播完滑出动画
 *   再真正出栈）。页面自己再挂一个 handler 就会多出第二条出栈路径 —— 那正是以前
 *   「返回键失效 / 关错页 / 下半层没动画」这一系列 bug 的来源。
 *
 * ## 标题与内容之间的间距必须是「滚动区之外」的
 * 之前把间距写在网格的 `contentPadding.top` 里，一往上滑就被卷走了，内容会贴到标题下面。
 * 现在改成顶栏与网格之间的一段**固定** `Spacer`：滚到什么位置，内容都被这段挡住，
 * 网格自己的 `top` 只负责首屏那点呼吸。
 *
 * ## 布局：首图大卡 + 双列网格
 * 纯等大方格在 30 个条目时会糊成一片马赛克。这里把第一项提到整行（16:9 图片 + 图上标题），
 * 后面才是双列方图 —— 视线有落点，往下扫也仍有节奏。条目数 ≤ 2 时不做大卡。
 *
 * 条目**全部来自主页已取到的数据**，本页不发任何请求 —— 主页那个 10 是「展示上限」，
 * 不是接口上限（网易云一次给 30 个歌单、QQ 官方歌单共 736 个）。
 */
@Composable
internal fun HomeShelfAllSheet(
    data: HomeShelfAllData,
    isTop: Boolean,
    onClose: () -> Unit,
    onOpenTarget: (HomeTarget) -> Unit,
) {
    LocalOverlayFrame(onClose = onClose) { requestClose ->
        // 出栈由 AppShell 的全局 PAGE 处理器统一做，本页不再声明 BackHandler
        val gridState = rememberLazyGridState()
        Box(modifier = Modifier.fillMaxSize()) {
            LocalStaticBackdrop(modifier = Modifier.fillMaxSize())
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                LocalDetailTopBar(
                    title = data.title,
                    count = "${data.items.size} 个",
                    onClose = requestClose,
                )
                // 固定在滚动区之外：滑动时内容不会贴到标题上
                Spacer(modifier = Modifier.height(AppSpacing.sp16))
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = AppSpacing.sp16,
                        end = AppSpacing.sp16,
                        top = AppSpacing.sp4,
                        bottom = AppSpacing.sp40,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sp12),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.sp16),
                ) {
                    if (data.items.size >= 3) {
                        // 首项提成整行大卡（只有 1~2 项时没必要，直接网格更整齐）
                        item(key = "hero", span = { GridItemSpan(maxLineSpan) }) {
                            val hero = data.items.first()
                            HomeShelfHeroCard(
                                item = hero,
                                onClick = { hero.target?.let(onOpenTarget) },
                            )
                        }
                        items(data.items.drop(1), key = { it.key }) { item ->
                            HomeShelfAllTile(
                                item = item,
                                onClick = { item.target?.let(onOpenTarget) },
                            )
                        }
                    } else {
                        items(data.items, key = { it.key }) { item ->
                            HomeShelfAllTile(
                                item = item,
                                onClick = { item.target?.let(onOpenTarget) },
                            )
                        }
                    }
                }
            }
        }
    }
}
