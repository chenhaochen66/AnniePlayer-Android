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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.data.local.LocalGroup
import com.annieplayer.annie_music.kt.data.local.LocalLibraryStore
import com.annieplayer.annie_music.kt.data.local.LocalSection
import com.annieplayer.annie_music.kt.data.local.LocalTrackMeta
import com.annieplayer.annie_music.kt.data.local.albumYearOf
import com.annieplayer.annie_music.kt.data.local.groupTracks
import com.annieplayer.annie_music.kt.data.local.localAlbumType
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
 * **专辑列表页**（点宫格「专辑」弹出）。
 *
 * 照 `SingerDetailAlbums` 的布局：右上角「时间正序 / 时间倒序」切换 + 2 列网格，
 * 卡片尺寸 `cardWidth = (屏宽 − 16 − 10) / 2`、`cardHeight = cardWidth + 68dp`
 * （那 68dp 是文字区，含专辑名 / 艺术家 / 首数三行）。
 *
 * 与流媒体一致：**背景固定色板、不跟封面变色**；排序用本地 tag 里的发行年（`year`），
 * 没有年份的排最后 —— 这一页没有云端 `pub` 字段可用。
 */
@Composable
fun LocalAlbumListScreen(
    tracks: List<LocalTrackMeta>,
    isTop: Boolean,
    onClose: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups = remember(tracks) { groupTracks(tracks, LocalSection.albums) }
    // 搜索框：与曲库主页同一个组件、同一套尺寸（sp16 左右 / sp4 上下 / 44dp 高）；
    // 匹配专辑名或专辑艺术家
    var query by remember { mutableStateOf("") }
    val visibleGroups = remember(groups, query) {
        val q = query.trim()
        if (q.isEmpty()) {
            groups
        } else {
            groups.filter {
                it.title.contains(q, ignoreCase = true) ||
                    it.tracks.first().artistDisplay.contains(q, ignoreCase = true)
            }
        }
    }
    // 排序：发行时间（默认）/ 专辑名 / 艺术家 / 歌曲数；**每种都能切升降序**，且持久化
    val pref = rememberSortPreference(
        page = LocalLibraryStore.PAGE_ALBUMS,
        defaultMode = AlbumSort.released.name,
        defaultAscending = AlbumSort.released.defaultAscending,
    )
    val sort = AlbumSort.entries.firstOrNull { it.name == pref.mode } ?: AlbumSort.released
    val ascending = pref.ascending
    val ordered = remember(visibleGroups, pref.mode, pref.ascending) {
        // 文本比较统一走 [localAlphabetComparator]：中文按拼音、日文按罗马字、同桶英文在前
        val text = localAlphabetComparator()
        /** 主键方向：降序取反 */
        fun dir(c: Int): Int = if (ascending) c else -c
        fun albumName(g: LocalGroup) = g.title.substringBefore(" · ")
        when (sort) {
            AlbumSort.byName -> visibleGroups.sortedWith { a, b ->
                dir(text.compare(albumName(a), albumName(b)))
            }
            AlbumSort.artist -> visibleGroups.sortedWith { a, b ->
                dir(text.compare(a.tracks.first().artistDisplay, b.tracks.first().artistDisplay))
            }
            AlbumSort.songCount -> visibleGroups.sortedWith { a, b -> dir(a.count.compareTo(b.count)) }
            AlbumSort.released -> {
                val withYear = visibleGroups.map { g -> g to yearOf(g.tracks) }
                withYear.sortedWith { a, b ->
                    val (ga, ya) = a
                    val (gb, yb) = b
                    when {
                        // 没年份的永远排在最后（不随升降序翻面）
                        ya == null && yb == null -> dir(text.compare(ga.title, gb.title))
                        ya == null -> 1
                        yb == null -> -1
                        ya == yb -> dir(text.compare(ga.title, gb.title))
                        else -> dir(ya.compareTo(yb))
                    }
                }.map { it.first }
            }
        }
    }
    val gridState = rememberLazyGridState()

    // 出栈由 AppShell 的全局 PAGE 处理器统一做（见 AppShell「页面栈的返回」）

    Box(modifier = modifier.fillMaxSize()) {
        LocalStaticBackdrop(modifier = Modifier.fillMaxSize())
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            LocalDetailTopBar(
                title = "专辑",
                count = "${ordered.size} 张",
                onClose = onClose,
            )

            SearchBarField(
                value = query,
                onValueChange = { query = it },
                onSubmit = {},
                hint = "搜索专辑、歌手",
                horizontalPadding = AppSpacing.sp16,
                verticalPadding = AppSpacing.sp4,
                height = 44.dp,
            )

            LocalSortRow(
                hint = "共 ${ordered.size} 张",
                sortLabel = sort.label,
                onCycleSort = {
                    // 换模式时方向落到该模式的默认值（发行时间/歌曲数→降序，名字/艺术家→升序）
                    val next = sort.next()
                    pref.setMode(next.name, next.defaultAscending)
                },
                trailing = {
                    LocalOrderToggle(ascending = ascending, onToggle = { pref.setAscending(!ascending) })
                },
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = AppSpacing.sp12, vertical = AppSpacing.sp8),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        val cardWidth = (maxWidth - AppSpacing.sp16 - AppSpacing.sp10) / 2
                        val cardHeight = cardWidth + 68.dp
                        Box(modifier = Modifier.fillMaxSize()) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            state = gridState,
                            modifier = Modifier.fillMaxSize().shortScrollbar(gridState),
                            contentPadding = PaddingValues(
                                start = AppSpacing.sp8,
                                top = AppSpacing.sp4,
                                end = AppSpacing.sp8,
                                bottom = AppSpacing.listBottomInset,
                            ),
                            verticalArrangement = Arrangement.spacedBy(AppSpacing.sp10),
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sp10),
                        ) {
                            if (ordered.isEmpty()) {
                                item(key = "empty", span = { GridItemSpan(2) }) {
                                    Text(
                                        text = "没有匹配的专辑",
                                        color = AppColors.textTertiary,
                                        fontSize = AppFontSize.base,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = AppSpacing.sp24),
                                    )
                                }
                            }
                            items(ordered, key = { it.key }) { group ->
                                LocalAlbumGridCard(
                                    name = group.title.substringBefore(" · "),
                                    artist = group.tracks.first().artistDisplay,
                                    coverPath = group.coverPath,
                                    count = group.count,
                                    onClick = { onOpenAlbum(group.key) },
                                    modifier = Modifier.height(cardHeight),
                                    // 与流媒体专辑卡同一行信息：N 首 · 类型 · 发行年
                                    year = albumYearOf(group.tracks),
                                    type = localAlbumType(group.count),
                                )
                            }
                        }
                        }
                    }
                }
            }
        }
    }
}

/** 专辑默认排序用的发行年（与 `albumYearOf` 同一套规则，只是空值在这里参与比较） */
private fun yearOf(tracks: List<LocalTrackMeta>): Int? = albumYearOf(tracks)

/**
 * 专辑列表的排序模式；每种都能切升降序（`defaultAscending` 是切换模式时的初始方向）。
 * （`name` 是 Enum 自带属性，不能用作枚举项名，所以叫 `byName`）
 */
private enum class AlbumSort(val label: String, val defaultAscending: Boolean) {
    released("发行时间", false),
    byName("专辑名", true),
    artist("艺术家", true),
    songCount("歌曲数", false),
    ;

    fun next(): AlbumSort = entries[(ordinal + 1) % entries.size]
}
