package com.annieplayer.annie_music.kt.features.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.data.local.LocalLibraryStore
import com.annieplayer.annie_music.kt.data.local.LocalSection
import com.annieplayer.annie_music.kt.data.local.LocalTrackMeta
import com.annieplayer.annie_music.kt.data.local.albumGroupKey
import com.annieplayer.annie_music.kt.data.local.groupTracks
import com.annieplayer.annie_music.kt.data.local.localAlphabetComparator
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.ui.theme.ColorPalette
import com.annieplayer.annie_music.kt.widgets.CoverPaletteBackdrop
import com.annieplayer.annie_music.kt.widgets.SearchBarField
import com.annieplayer.annie_music.kt.widgets.clickableRipple
import com.annieplayer.annie_music.kt.widgets.shortScrollbar

/**
 * **歌手列表页**（点宫格「艺术家」弹出）。
 *
 * 样式照流媒体的歌手列表：一行一个歌手，左侧圆形头像位，右侧箭头。
 * 视觉上刻意**不跟封面变色**（`coverUrl = null` + 固定品牌色板）：这一页是"目录"，
 * 不是"作品"，跟着封面染色反而会把列表本身冲淡。
 *
 * 副标题 `N 首 · M 张专辑` 里的专辑数是**预计算**的（一次遍历），不在每行里 `distinct()`，
 * 否则几千首的曲库里滚动会掉帧。
 */
@Composable
fun LocalArtistListScreen(
    tracks: List<LocalTrackMeta>,
    isTop: Boolean,
    onClose: () -> Unit,
    onOpenArtist: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups = remember(tracks) { groupTracks(tracks, LocalSection.artists) }
    // 每位歌手的专辑数：一次遍历算完，避免每行重复 distinct()
    val albumCounts = remember(groups) {
        groups.associate { g -> g.key to g.tracks.map { albumGroupKey(it) }.distinct().size }
    }
    // 搜索框：与曲库主页同一个组件、同一套尺寸（sp16 左右 / sp4 上下 / 44dp 高）
    var query by remember { mutableStateOf("") }
    val visibleGroups = remember(groups, query) {
        val q = query.trim()
        if (q.isEmpty()) groups else groups.filter { it.title.contains(q, ignoreCase = true) }
    }
    // 排序：名字（默认，字母序）/ 歌曲数；模式与升降序都持久化（退出再回来不重置）
    val pref = rememberSortPreference(
        page = LocalLibraryStore.PAGE_ARTISTS,
        defaultMode = SORT_MODE_NAME,
        defaultAscending = true,
    )
    val sortByName = pref.mode == SORT_MODE_NAME
    val ordered = remember(visibleGroups, pref.mode, pref.ascending) {
        val text = localAlphabetComparator()
        if (sortByName) {
            visibleGroups.sortedWith { a, b ->
                val c = text.compare(a.title, b.title)
                if (pref.ascending) c else -c
            }
        } else {
            visibleGroups.sortedWith { a, b ->
                val c = a.count.compareTo(b.count)
                if (pref.ascending) c else -c
            }
        }
    }
    val listState = rememberLazyListState()

    // 出栈由 AppShell 的全局 PAGE 处理器统一做

    Box(modifier = modifier.fillMaxSize()) {
        LocalStaticBackdrop(modifier = Modifier.fillMaxSize())
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            LocalDetailTopBar(
                title = "歌手",
                count = "${ordered.size} 位",
                onClose = onClose,
            )
            SearchBarField(
                value = query,
                onValueChange = { query = it },
                onSubmit = {},
                hint = "搜索歌手",
                horizontalPadding = AppSpacing.sp16,
                verticalPadding = AppSpacing.sp4,
                height = 44.dp,
            )
            LocalSortRow(
                hint = "共 ${ordered.size} 位",
                sortLabel = if (sortByName) "名字" else "歌曲数",
                onCycleSort = {
                    // 换模式时方向落到该模式的默认值：名字→升序、歌曲数→降序
                    if (sortByName) {
                        pref.setMode(SORT_MODE_COUNT, false)
                    } else {
                        pref.setMode(SORT_MODE_NAME, true)
                    }
                },
                trailing = {
                    LocalOrderToggle(pref.ascending) { pref.setAscending(!pref.ascending) }
                },
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = AppSpacing.sp12, vertical = AppSpacing.sp8),
            ) {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().shortScrollbar(listState),
                    contentPadding = PaddingValues(
                        top = AppSpacing.sp8,
                        // 底部给常驻底栏（迷你播放器态）让位，否则最后几行被胶囊压住
                        bottom = AppSpacing.listBottomInset,
                    ),
                ) {
                    if (ordered.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                text = "没有匹配的歌手",
                                color = AppColors.textTertiary,
                                fontSize = AppFontSize.base,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = AppSpacing.sp24),
                            )
                        }
                    }
                    items(ordered, key = { "a:${it.key}" }) { group ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(AppRadius.r8))
                                .clickableRipple { onOpenArtist(group.key) }
                                .padding(horizontal = AppSpacing.sp12, vertical = AppSpacing.sp8),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LocalArtistAvatar(
                                name = group.title,
                                coverPath = group.coverPath,
                                size = 48.dp,
                            )
                            Spacer(modifier = Modifier.width(AppSpacing.sp12))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = group.title,
                                    color = AppColors.textPrimary,
                                    fontSize = AppFontSize.size15,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(modifier = Modifier.height(AppSpacing.sp2))
                                Text(
                                    text = "${group.count} 首 · ${albumCounts[group.key] ?: 0} 张专辑",
                                    color = AppColors.textTertiary,
                                    fontSize = AppFontSize.size13,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            LocalChevronRight(
                                modifier = Modifier.width(AppSpacing.sp18).height(AppSpacing.sp18),
                                tint = AppColors.glyphDim,
                            )
                            Spacer(modifier = Modifier.width(AppSpacing.sp8))
                        }
                    }
                }
            }
            }
        }
    }
}
