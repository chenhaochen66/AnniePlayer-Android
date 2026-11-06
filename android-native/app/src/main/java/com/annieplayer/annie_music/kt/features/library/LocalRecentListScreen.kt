package com.annieplayer.annie_music.kt.features.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.annieplayer.annie_music.kt.data.local.LocalLibraryStore
import com.annieplayer.annie_music.kt.data.local.LocalTrackMeta
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.shortScrollbar

/**
 * **最近播放**（点宫格「最近播放」弹出）。
 *
 * 与「最近添加」的区别：这里按**播放次数从高到低**排（次数相同看最近一次播放时间），
 * 行首的数字就是**播放次数**。记录由播放器在 `setSource` 时写入
 * （`LocalLibraryStore.recordPlay`），最多保留 [LocalLibraryStore.RECENT_LIMIT] 条并持久化到
 * `filesDir/local_library/recent.json`，重启后仍然在。
 *
 * 列表用曲库里的最新元数据渲染（不存快照）—— 文件被重命名/改过 tag 后这里也跟着更新；
 * 已被删除的文件会被跳过（记录本身等下次上限清理时自然淘汰）。
 */
@Composable
fun LocalRecentListScreen(
    tracks: List<LocalTrackMeta>,
    currentSongId: String?,
    isTop: Boolean,
    onClose: () -> Unit,
    onPlayAt: (List<LocalTrackMeta>, Int) -> Unit,
    onMore: (LocalTrackMeta) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 固定排序：**播放次数从多到少**（次数相同看最近一次播放时间）—— 这一页不提供排序切换
    val ordered: List<Pair<LocalTrackMeta, LocalLibraryStore.RecentPlay>> =
        remember(tracks) {
            val byPath = tracks.associateBy { it.path }
            LocalLibraryStore.loadRecentPlays()
                .mapNotNull { r -> byPath[r.path]?.let { meta -> meta to r } }
                .sortedWith(
                    compareByDescending<Pair<LocalTrackMeta, LocalLibraryStore.RecentPlay>> {
                        it.second.count
                    }.thenByDescending { it.second.at },
                )
        }
    val queue = remember(ordered) { ordered.map { it.first } }

    // 出栈由 AppShell 的全局 PAGE 处理器统一做

    Box(modifier = modifier.fillMaxSize()) {
        LocalStaticBackdrop(modifier = Modifier.fillMaxSize())
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            LocalDetailTopBar(
                title = "最近播放",
                count = "${ordered.size} 首",
                onClose = onClose,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = AppSpacing.sp12, vertical = AppSpacing.sp8),
            ) {
                if (ordered.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) {
                        Text(
                            text = "还没有播放记录",
                            color = AppColors.textTertiary,
                            fontSize = AppFontSize.base,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    val listState = rememberLazyListState()
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().shortScrollbar(listState),
                        contentPadding = PaddingValues(
                            top = AppSpacing.sp8,
                            bottom = AppSpacing.listBottomInset,
                        ),
                    ) {
                        items(ordered, key = { "r:${it.first.path}" }) { (meta, record) ->
                            val index = queue.indexOfFirst { it.path == meta.path }.coerceAtLeast(0)
                            LocalSongRow(
                                meta = meta,
                                index = index,
                                isActive = currentSongId == meta.id,
                                // 单栏长列表：正在播放用中性灰（与流媒体搜索列表一致）
                                playingTint = AppColors.playingGray,
                                onPlay = { onPlayAt(queue, index) },
                                onMore = { onMore(meta) },
                                // 行首数字 = 播放次数（不是序号）
                                trackLabel = "${record.count}",
                            )
                        }
                    }
                }
            }
        }
    }
}
