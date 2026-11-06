package com.annieplayer.annie_music.kt.features.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.sources.home.fetchKugouStyleSongs
import com.annieplayer.annie_music.kt.features.library.LocalDetailTopBar
import com.annieplayer.annie_music.kt.features.library.LocalOverlayFrame
import com.annieplayer.annie_music.kt.features.library.LocalStaticBackdrop
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.SkeletonLoader
import com.annieplayer.annie_music.kt.widgets.shortScrollbar

/**
 * 酷狗「按风格听」的流派二级页。
 *
 * ## 为什么是二级页而不是主页的一行歌
 * 主页列 18 个流派，若每个都预先取 30 首就是 18 次请求（每份约 114KB）。这里改成
 * **点谁拉谁**：主页只发一张卡（零请求），进来才按 `tagIds` 拉那一个流派。
 *
 * ## 壳与其它栈页面完全一致
 * `LocalOverlayFrame`（遮罩 + 滑入滑出，并负责把自己的 `requestClose` 注册进
 * `PageCloseRelay`）+ `LocalDetailTopBar`（同一个返回箭头与字号）+
 * `LocalStaticBackdrop`（跟正在播放封面取色）。
 *
 * **本页不声明 `BackHandler`**：出栈由 `AppShell` 唯一的 PAGE 处理器裁决，
 * 经 relay 调到 `requestClose`，保证退出有滑出动画。自己再挂一个 handler 就是第二条
 * 出栈路径，会导致「返回键失效 / 关错页 / 下层没动画」。
 *
 * ## 列表为什么是纵向整页
 * 主页那套「5 行 × 左右翻页」是**横向卡片流**的替代品；这一页本身就是纵向可滚的整页列表，
 * 再套一层横向 pager 反而别扭。所以这里直接 `LazyColumn`，行样式复用主页的 [HomeSongRow]。
 */
@Composable
internal fun KugouStyleSheet(
    tagIds: String,
    title: String,
    isTop: Boolean,
    currentSongId: String?,
    onClose: () -> Unit,
    onPlay: (Song, List<Song>) -> Unit,
    onMore: (Song) -> Unit,
) {
    var songs by remember(tagIds) { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember(tagIds) { mutableStateOf(true) }

    LaunchedEffect(tagIds) {
        loading = true
        songs = runCatching { fetchKugouStyleSongs(tagIds) }.getOrDefault(emptyList())
        loading = false
    }

    LocalOverlayFrame(onClose = onClose) { requestClose ->
        // 出栈由 AppShell 的全局 PAGE 处理器统一做，本页不再声明 BackHandler
        Box(modifier = Modifier.fillMaxSize()) {
            LocalStaticBackdrop(modifier = Modifier.fillMaxSize())
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                LocalDetailTopBar(
                    title = title,
                    count = if (songs.isEmpty()) null else "${songs.size} 首",
                    onClose = requestClose,
                )
                // 固定在滚动区之外的间距：滑动时内容不会贴到标题上
                Spacer(modifier = Modifier.height(AppSpacing.sp16))
                when {
                    loading -> Box(modifier = Modifier.fillMaxSize()) {
                        SkeletonLoader(columns = 1, largeRows = true)
                    }

                    songs.isEmpty() -> StyleEmptyState()

                    else -> {
                        val listState = rememberLazyListState()
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().shortScrollbar(listState),
                            contentPadding = PaddingValues(bottom = AppSpacing.sp40),
                        ) {
                            itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                                HomeSongRow(
                                    song = song,
                                    index = index,
                                    isActive = song.id == currentSongId,
                                    onPlay = { onPlay(song, songs) },
                                    onMore = { onMore(song) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 该流派没拿到曲目（网络失败 / 平台返回空）时的空态。 */
@Composable
private fun StyleEmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "♪",
                color = AppColors.whiteA15,
                fontSize = AppFontSize.size32,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp12))
            Text(
                text = "这个流派暂时没有推荐",
                color = AppColors.white60,
                fontSize = AppFontSize.size15,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp4))
            Text(
                text = "网络波动或平台没给数据，稍后再试",
                color = AppColors.white38,
                fontSize = AppFontSize.size13,
                textAlign = TextAlign.Center,
            )
        }
    }
}
