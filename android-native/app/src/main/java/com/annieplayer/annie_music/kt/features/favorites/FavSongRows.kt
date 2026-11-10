package com.annieplayer.annie_music.kt.features.favorites

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.core.compose.activityViewModel
import com.annieplayer.annie_music.kt.data.local.LocalTrackMeta
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.features.library.LocalLibraryViewModel
import com.annieplayer.annie_music.kt.features.library.LocalTrackRow
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.PlayIcon
import com.annieplayer.annie_music.kt.widgets.SongTile
import com.annieplayer.annie_music.kt.widgets.clickableRipple
import com.annieplayer.annie_music.kt.widgets.shortScrollbar

/**
 * 收藏库所有「单曲列表」共用的部分（我喜欢 / 自建播放列表）。
 *
 * 抽出来的原因只有一个：这两个页面的行必须**一模一样**（流媒体走 [SongTile]、
 * 本地走 [LocalTrackRow]），复制一份就会在下次调行样式时只改一处。
 */

/** 「播放 / 随机播放」胶囊的高度比例无关，只看圆角与外边距，见 [FavPlayPills] */
private val PillsVerticalPadding = 12.dp

/**
 * 本地曲库的曲目表（触发一次扫描，与本地曲库页同一个 `LocalLibraryViewModel`）。
 *
 * 收藏里只存了 `Song`，本地歌的专辑 / 编码 / 大小这些只有 [LocalTrackMeta] 有；
 * 而「本地艺人 / 本地专辑」两个子页的计数与封面也只能从这里算。
 */
@Composable
internal fun rememberLocalTracks(): List<LocalTrackMeta> {
    val context = LocalContext.current
    val libraryViewModel: LocalLibraryViewModel = activityViewModel()
    // start() 自身幂等（已扫描过就直接读缓存），这里只是保证「直接进收藏库」时也有数据
    LaunchedEffect(Unit) { libraryViewModel.start(context) }
    val state by libraryViewModel.state.collectAsState()
    return state.tracks
}

/** 曲目路径 → 元数据（本地行要用它拿编码/大小/时长，收藏里没存这些） */
@Composable
internal fun rememberLocalMetaByPath(): Map<String, LocalTrackMeta> {
    val tracks = rememberLocalTracks()
    return remember(tracks) { tracks.associateBy { it.path } }
}

/**
 * 把收藏里的 `Song` 快照换成曲库的**实时**对象（只对本地歌有效）。
 *
 * 收藏存的是收藏那一刻的整首 `Song`（标题 / 艺术家 / 专辑 / 封面都在里面），而本地曲库的 tag
 * 随时可能被改动。列表行本来就是从曲库现取的，但**队列**此前用的是快照 ——
 * 于是同一个文件在列表里显示新标题、点开播放页又变回旧标题。
 *
 * 曲库里找不到（文件被移走 / 改名）就保留快照：那种情况下收藏确实失联了，
 * 与其显示空白不如继续显示「上次已知」。
 */
@Composable
internal fun rememberLiveSongs(songs: List<Song>): List<Song> {
    val metaByPath = rememberLocalMetaByPath()
    return remember(songs, metaByPath) {
        songs.map { s ->
            if (s.platform != MusicPlatform.local) {
                s
            } else {
                metaByPath[s.id.removePrefix("local:")]?.toSong() ?: s
            }
        }
    }
}

/**
 * 收藏里的单曲列表。
 *
 * [songs] 同时是**播放队列**：点第 index 首就从那一首开始播，所以调用方传进来的
 * 必须是「屏幕上看到的这份」（搜索过滤后 / 歌单内），不能传全量。
 */
@Composable
internal fun FavSongList(
    songs: List<Song>,
    currentSong: Song?,
    onPlayAt: (Int) -> Unit,
    onMore: (Song) -> Unit,
    modifier: Modifier = Modifier,
) {
    val metaByPath = rememberLocalMetaByPath()
    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().shortScrollbar(listState),
        contentPadding = PaddingValues(
            start = AppSpacing.sp8,
            end = AppSpacing.sp8,
            top = AppSpacing.sp4,
            // 底部给常驻底栏（迷你播放器态）让位
            bottom = AppSpacing.listBottomInset,
        ),
    ) {
        itemsIndexed(songs, key = { _, s -> "${s.platform}:${s.id}" }) { index, song ->
            // 两种来源用两种行：流媒体照搜索结果（`SongTile`，三行含音质/来源），
            // 本地照「本地曲库 · 全部歌曲」（`LocalTrackRow`，带编码/大小/时长）。
            val localMeta = if (song.platform == MusicPlatform.local) {
                metaByPath[song.id.removePrefix("local:")]
            } else {
                null
            }
            if (localMeta != null) {
                LocalTrackRow(
                    meta = localMeta,
                    isActive = currentSong?.id == localMeta.id,
                    playingTint = AppColors.playingGray,
                    onClick = { onPlayAt(index) },
                    onMore = { onMore(localMeta.toSong()) },
                )
            } else {
                SongTile(
                    song = song,
                    // 第三行的平台名：收藏里混着各家来源，必须按每首歌自己的 `platform`，
                    // 不能像搜索结果那样用「当前选的搜索源」
                    sourceLabel = song.platform.label,
                    onPlay = { onPlayAt(index) },
                    // ⋮ → 单曲操作面板；本地歌的面板里主操作是「歌曲详情」，没有下载项
                    onDownload = { onMore(it) },
                    isActive = currentSong != null &&
                        currentSong.id == song.id &&
                        currentSong.platform == song.platform,
                )
            }
        }
    }
}

/**
 * 「播放 / 随机播放」两个并排胶囊（照 Apple Music，底色用玻璃灰）。
 *
 * [onPlay] 收到的是**要播的那一份队列**（随机播放时已经是乱序后的），
 * 调用方只要 `setQueue(list, 0)` 即可 —— 两个页面的播放语义因此只有一处。
 */
@Composable
internal fun FavPlayPills(
    songs: List<Song>,
    onPlay: (List<Song>) -> Unit,
) {
    if (songs.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.sp16, vertical = AppSpacing.sp8),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sp10),
    ) {
        FavActionPill(
            label = "播放",
            leading = { tint -> PlayIcon(modifier = Modifier.size(AppIconSize.icon16), tint = tint) },
            modifier = Modifier.weight(1f),
        ) { onPlay(songs) }
        FavActionPill(
            label = "随机播放",
            leading = null,
            modifier = Modifier.weight(1f),
        ) { onPlay(songs.shuffled()) }
    }
}

@Composable
private fun FavActionPill(
    label: String,
    leading: (@Composable (androidx.compose.ui.graphics.Color) -> Unit)?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(AppColors.whiteA06)
            .clickableRipple(onClick)
            .padding(vertical = PillsVerticalPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading(AppColors.white)
            Spacer(modifier = Modifier.size(AppSpacing.sp6))
        }
        Text(
            text = label,
            color = AppColors.white,
            fontSize = AppFontSize.size15,
            fontWeight = AppFontWeight.medium,
        )
    }
}
