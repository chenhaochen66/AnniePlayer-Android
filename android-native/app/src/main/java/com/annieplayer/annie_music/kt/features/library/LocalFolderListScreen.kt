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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.data.local.LocalLibraryStore
import com.annieplayer.annie_music.kt.data.local.LocalSection
import com.annieplayer.annie_music.kt.data.local.LocalTrackMeta
import com.annieplayer.annie_music.kt.data.local.folderGroupKey
import com.annieplayer.annie_music.kt.data.local.groupTracks
import com.annieplayer.annie_music.kt.data.local.localAlphabetComparator
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.ui.theme.ColorPalette
import com.annieplayer.annie_music.kt.widgets.CoverPaletteBackdrop
import com.annieplayer.annie_music.kt.widgets.DetailPlayAllPill
import com.annieplayer.annie_music.kt.widgets.clickableRipple
import com.annieplayer.annie_music.kt.widgets.shortScrollbar

/**
 * **文件夹列表页**（点宫格「文件夹」弹出）。
 *
 * 与歌手列表同一套行布局，只是左侧换成文件夹 glyph、右侧直接显示首数：
 * 文件夹本身没有"详情页"这一层，点进去看到的就是该目录下的曲目列表
 * （由 [LocalFolderDetailScreen] 承接）。
 */
@Composable
fun LocalFolderListScreen(
    tracks: List<LocalTrackMeta>,
    isTop: Boolean,
    onClose: () -> Unit,
    onOpenFolder: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups = remember(tracks) { groupTracks(tracks, LocalSection.folders) }
    // 排序：目录名（默认，字母序）/ 歌曲数；模式与升降序都持久化（退出再回来不重置）
    val pref = rememberSortPreference(
        page = LocalLibraryStore.PAGE_FOLDERS,
        defaultMode = SORT_MODE_NAME,
        defaultAscending = true,
    )
    val sortByName = pref.mode == SORT_MODE_NAME
    val ordered = remember(groups, pref.mode, pref.ascending) {
        val text = localAlphabetComparator()
        if (sortByName) {
            groups.sortedWith { a, b ->
                val c = text.compare(a.title, b.title)
                if (pref.ascending) c else -c
            }
        } else {
            groups.sortedWith { a, b ->
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
                title = "文件夹",
                count = "${groups.size} 个",
                onClose = onClose,
            )
            LocalSortRow(
                hint = "共 ${groups.size} 个目录",
                sortLabel = if (sortByName) "目录名" else "歌曲数",
                onCycleSort = {
                    // 换模式时方向落到该模式的默认值：目录名→升序、歌曲数→降序
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
                        bottom = AppSpacing.listBottomInset,
                    ),
                ) {
                    items(ordered, key = { "f:${it.key}" }) { group ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(AppRadius.r8))
                                .clickableRipple { onOpenFolder(group.key) }
                                .padding(horizontal = AppSpacing.sp12, vertical = AppSpacing.sp8),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(AppRadius.r8))
                                    .background(AppColors.whiteA05),
                                contentAlignment = Alignment.Center,
                            ) {
                                LocalCategoryGlyph(
                                    kind = LocalCategory.folder,
                                    modifier = Modifier.size(AppIconSize.icon20),
                                    tint = AppColors.glyphMid,
                                )
                            }
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
                                    text = "${group.count} 首",
                                    color = AppColors.textTertiary,
                                    fontSize = AppFontSize.size13,
                                    maxLines = 1,
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

/**
 * **文件夹内容页**：该目录下的曲目，点一首就从这里开播。
 *
 * 没有 Hero（目录不是作品），只保留返回条 + 「播放全部」+ 曲目行 —— 与专辑详情共用 [LocalSongRow]。
 * 播放方案与专辑详情一致：**整个目录就是队列**，「播放全部」从第一首开始、点单曲从那一首开始。
 */
@Composable
fun LocalFolderDetailScreen(
    folderName: String,
    tracks: List<LocalTrackMeta>,
    currentSongId: String?,
    isTop: Boolean,
    onClose: () -> Unit,
    onPlayAt: (List<LocalTrackMeta>, Int) -> Unit,
    onMore: (LocalTrackMeta) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 分组键统一走 folderGroupKey（空目录名归到 `/`），与「文件夹」列表的分组规则同源
    val members = remember(tracks, folderName) {
        tracks.filter { folderGroupKey(it) == folderName }
    }
    val ordered = remember(members) { sortAlbumTracks(members) }

    // 出栈由 AppShell 的全局 PAGE 处理器统一做

    Box(modifier = modifier.fillMaxSize()) {
        LocalStaticBackdrop(modifier = Modifier.fillMaxSize())
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            LocalDetailTopBar(title = folderName, count = "${ordered.size} 首", onClose = onClose)
            // 播放全部：整目录进队列（与专辑 / 歌手详情的播放胶囊同一个组件）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.sp16, vertical = AppSpacing.sp8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DetailPlayAllPill(
                    onClick = { if (ordered.isNotEmpty()) onPlayAt(ordered, 0) },
                    enabled = ordered.isNotEmpty(),
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = AppSpacing.sp12, vertical = AppSpacing.sp8),
            ) {
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().shortScrollbar(listState),
                    contentPadding = PaddingValues(
                        top = AppSpacing.sp8,
                        bottom = AppSpacing.listBottomInset,
                    ),
                ) {
                    items(ordered.size, key = { i -> "t:${ordered[i].path}" }) { index ->
                        val t = ordered[index]
                        LocalSongRow(
                            meta = t,
                            index = index,
                            isActive = currentSongId == t.id,
                            // 单栏长列表：正在播放用中性灰（与流媒体搜索列表一致）
                            playingTint = AppColors.playingGray,
                            onPlay = { onPlayAt(ordered, index) },
                            onMore = { onMore(t) },
                            trackLabel = t.track?.toString().orEmpty(),
                        )
                    }
                }
            }
        }
    }
}
