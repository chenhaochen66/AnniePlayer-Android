package com.annieplayer.annie_music.kt.features.favorites

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.annieplayer.annie_music.kt.core.compose.activityViewModel
import com.annieplayer.annie_music.kt.core.state.FavoriteSectionRoute
import com.annieplayer.annie_music.kt.core.state.FavoriteViewModel
import com.annieplayer.annie_music.kt.core.state.UiViewModel
import com.annieplayer.annie_music.kt.data.favorites.FavAlbum
import com.annieplayer.annie_music.kt.data.favorites.FavSection
import com.annieplayer.annie_music.kt.data.favorites.FavoriteMode
import com.annieplayer.annie_music.kt.features.library.LocalCategory
import com.annieplayer.annie_music.kt.features.library.LocalCategoryGlyph
import com.annieplayer.annie_music.kt.features.library.LocalStaticBackdrop
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.AppTopBar
import com.annieplayer.annie_music.kt.widgets.HeartGlyph
import com.annieplayer.annie_music.kt.widgets.SegmentedPill
import com.annieplayer.annie_music.kt.widgets.clickableRipple

/** 最近添加最多显示几张（一屏横滑就能看完，不用「全部」） */
private const val RecentLimit = 8

/** 四行入口的行高（64dp：48 命中区之上留出放大的呼吸，图标 24dp 才不顶到上下边） */
private val EntryRowHeight = 64.dp

/**
 * 收藏媒体库首页。
 *
 * 结构照 Apple Music 的「资料库」，但骨架用的是本地曲库页那一套（同一个 `AppTopBar`、
 * 同一块 `CoverPaletteBackdrop`），所以两个 tab 切来切去不会觉得换了 App。
 *
 * 顶部那枚两段切换是**整页唯一**的模式开关：切了之后四行入口的计数、最近添加、
 * 以及进入的子页内容全部跟着换一边（本地 / 在线两边数据本来就分开存）。
 */
@Composable
fun FavoritesScreen(
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier,
    favoriteViewModel: FavoriteViewModel = activityViewModel(),
    uiViewModel: UiViewModel = activityViewModel(),
) {
    val snapshot by favoriteViewModel.snapshot.collectAsState()
    val mode = snapshot.mode

    val likedSongs = remember(snapshot, mode) { snapshot.songsIn(mode) }
    // 本地收藏的键来自 tag 分组，改过 tag 就会失联（收藏还在、爱心变空心、再点一次多一条）。
    // 进收藏库时用曲库现索引修正一次；只在本地方向做 —— 在线条目没有这个问题。
    val localTracks = rememberLocalTracks()
    LaunchedEffect(localTracks, mode) {
        if (mode == FavoriteMode.local) favoriteViewModel.healLocalEntries(localTracks)
    }
    val albums = remember(snapshot, mode) { snapshot.albumsIn(mode) }
    val artists = remember(snapshot, mode) { snapshot.artistsIn(mode) }
    val playlistCount = remember(snapshot, mode) {
        snapshot.playlistsIn(mode).size + snapshot.userPlaylistsIn(mode).size
    }
    // 最近添加只看专辑：单曲没有独立封面，塞进网格就是一排重复的专辑图
    val recent = remember(albums) { albums.sortedByDescending { it.addedAt }.take(RecentLimit) }

    fun open(section: FavSection) {
        uiViewModel.pushRoute(FavoriteSectionRoute(section))
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 与主页 / 本地曲库**同一条链路**（`LocalAmbientCoverUrl` + `LocalCoverThemePalette`）：
        // 全局色板由 AppShell 算一次、切歌时补间，页面之间共享。
        // 自己 `rememberCoverPalette(封面)` 是另一条链路 —— 每次进页面重新提取，
        // 第一帧还是 fallback、提取完突变，看起来就是「这个页面要现变色」。
        LocalStaticBackdrop(modifier = Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding(),
        ) {
            AppTopBar(
                title = "收藏库",
                onOpenMenu = onOpenMenu,
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    bottom = AppSpacing.bottomBarInset + AppSpacing.sp24,
                ),
            ) {
                item(key = "mode") {
                    SegmentedPill(
                        options = listOf("流媒体", "本地"),
                        selectedIndex = if (mode == FavoriteMode.online) 0 else 1,
                        onSelect = { index ->
                            favoriteViewModel.setMode(
                                if (index == 0) FavoriteMode.online else FavoriteMode.local,
                            )
                        },
                        modifier = Modifier.padding(
                            start = AppSpacing.sp16,
                            end = AppSpacing.sp16,
                            top = AppSpacing.sp4,
                            bottom = AppSpacing.sp12,
                        ),
                    )
                }

                item(key = "entries") {
                    Column(modifier = Modifier.padding(horizontal = AppSpacing.sp16)) {
                        FavEntryRow(
                            title = "我喜欢",
                            count = "${likedSongs.size} 首",
                            onClick = { open(FavSection.liked) },
                        ) {
                            // 描边红心（不填充）：四个入口并排时，一块纯色实心比另外三个线稿重太多
                            HeartGlyph(
                                modifier = Modifier.size(AppIconSize.icon24),
                                tint = AppColors.heart,
                                filled = false,
                            )
                        }
                        FavEntryRow(
                            title = "艺人",
                            count = "${artists.size} 位",
                            onClick = { open(FavSection.artists) },
                        ) {
                            LocalCategoryGlyph(
                                kind = LocalCategory.artist,
                                modifier = Modifier.size(AppIconSize.icon24),
                                tint = AppColors.white70,
                            )
                        }
                        FavEntryRow(
                            title = "专辑",
                            count = "${albums.size} 张",
                            onClick = { open(FavSection.albums) },
                        ) {
                            LocalCategoryGlyph(
                                kind = LocalCategory.album,
                                modifier = Modifier.size(AppIconSize.icon24),
                                tint = AppColors.white70,
                            )
                        }
                        FavEntryRow(
                            title = "播放列表",
                            count = "$playlistCount 个",
                            onClick = { open(FavSection.playlists) },
                        ) {
                            LocalCategoryGlyph(
                                kind = LocalCategory.folder,
                                modifier = Modifier.size(AppIconSize.icon24),
                                tint = AppColors.white70,
                            )
                        }
                    }
                }

                if (recent.isNotEmpty()) {
                    item(key = "recent-title") {
                        Text(
                            text = "最近添加",
                            color = AppColors.white,
                            fontSize = AppFontSize.size17,
                            fontWeight = AppFontWeight.bold,
                            modifier = Modifier.padding(
                                start = AppSpacing.sp16,
                                end = AppSpacing.sp16,
                                top = AppSpacing.sp20,
                                bottom = AppSpacing.sp10,
                            ),
                        )
                    }
                    item(key = "recent") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = AppSpacing.sp16),
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sp12),
                        ) {
                            items(recent, key = { it.key }) { album ->
                                RecentAlbumCard(album) { open(FavSection.albums) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 四行入口的一行：图标 + 标题 + 计数 + `›` */
@Composable
private fun FavEntryRow(
    title: String,
    count: String,
    onClick: () -> Unit,
    glyph: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(EntryRowHeight)
            .clickableRipple(onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(AppSpacing.sp32),
            contentAlignment = Alignment.Center,
        ) {
            glyph()
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp12))
        Text(
            text = title,
            color = AppColors.white,
            fontSize = AppFontSize.size20,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = count,
            color = AppColors.white38,
            fontSize = AppFontSize.size15,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.width(AppSpacing.sp6))
        Text(
            text = "›",
            color = AppColors.white38,
            fontSize = AppFontSize.size20,
        )
    }
}

/** 「最近添加」里的方图卡 */
@Composable
private fun RecentAlbumCard(album: FavAlbum, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(132.dp)
            .clickableRipple(onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(AppRadius.r10))
                .background(AppColors.whiteA05),
        ) {
            if (album.cover.isNotEmpty()) {
                AsyncImage(
                    model = album.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(modifier = Modifier.height(AppSpacing.sp6))
        Text(
            text = album.name.ifEmpty { "未知专辑" },
            color = AppColors.white,
            fontSize = AppFontSize.size13,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = album.artist,
            color = AppColors.white38,
            fontSize = AppFontSize.size10,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
