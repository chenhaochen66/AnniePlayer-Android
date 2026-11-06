package com.annieplayer.annie_music.kt.features.details

import androidx.compose.material3.SnackbarHostState
import com.annieplayer.annie_music.kt.core.state.AlbumDetailData
import com.annieplayer.annie_music.kt.core.state.AlbumRoute
import com.annieplayer.annie_music.kt.core.state.PlayerViewModel
import com.annieplayer.annie_music.kt.core.state.SingerDetailData
import com.annieplayer.annie_music.kt.core.state.SingerRoute
import com.annieplayer.annie_music.kt.core.state.UiViewModel
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.resolveArtist
import com.annieplayer.annie_music.kt.data.sources.apple.enrichAppleSong
import com.annieplayer.annie_music.kt.data.sources.kugou.fetchKugouSongAuthors
import com.annieplayer.annie_music.kt.widgets.SongArtistRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 歌曲 → 歌手 / 专辑详情的统一入口。
 *
 * 从 `SearchScreenState` 拆出：搜索页状态不应该承担全局 ⋮ 面板的详情跳转。
 * `SongActionsSheet` 只发回调，真正的解析/Apple enrich/详情栈 push 都在这里。
 */
class DetailNavigator(
    private val scope: CoroutineScope,
    private val uiViewModel: UiViewModel,
    private val playerViewModel: PlayerViewModel,
    private val snackbarHostState: SnackbarHostState,
) {

    /**
     * ⋮ → 歌手：有 id 直接开；没有则解析一次。
     *
     * 必须用「被点 ⋮ 的那首歌」：平台要从它取，酷狗还要按它的 hash 补作者 id。
     */
    fun openArtistDetail(artist: SongArtistRef) {
        val song = uiViewModel.state.value.songActionsSong
            ?: playerViewModel.state.value.currentSong
            ?: return
        // **先收起播放页**：补 id 可能要等网络（Apple enrich / 酷狗 hash），
        // 不能让用户一直停在播放页上等。失败时弹 snackbar，人就停在下面那一页。
        uiViewModel.setPlayerOverlayVisible(false)
        scope.launch {
            var knownId = if (artist.id.isEmpty()) null else artist.id
            // Apple 搜索结果只有 artistName，没有 id；先补单曲详情拿 relationships.artists
            if (knownId == null && song.platform == MusicPlatform.apple) {
                val enriched = runCatching { enrichAppleSong(song) }.getOrDefault(song)
                fun norm(value: String): String =
                    value.lowercase().replace(Regex("""[\s\p{Punct}]"""), "")
                val target = norm(artist.name)
                var idx = enriched.artistNames.indexOfFirst { norm(it) == target }
                if (idx < 0) {
                    idx = enriched.artistNames.indexOfFirst {
                        it.isNotEmpty() && (
                            norm(artist.name).contains(norm(it)) ||
                                norm(it).contains(target)
                            )
                    }
                }
                if (idx < 0 && enriched.artistIds.size == 1) idx = 0
                if (idx >= 0) knownId = enriched.artistIds.getOrNull(idx)
            }
            // 酷狗的专辑/歌单都不带歌手 id：这里按歌曲 hash 逐曲补一次
            // （接口单曲限，结果落盘缓存 → 二次及以后秒开）
            if (knownId == null && song.platform == MusicPlatform.kugou) {
                knownId = fetchKugouSongAuthors(song.id.removePrefix("kugou:"))
                    .firstOrNull { it.name == artist.name }
                    ?.id
            }
            val resolved = resolveArtist(
                song.platform,
                artist.name,
                knownId = knownId,
            )
            if (resolved == null || resolved.id.isEmpty()) {
                snackbarHostState.showSnackbar("未找到歌手「${artist.name}」")
                return@launch
            }
            uiViewModel.pushRoute(
                SingerRoute(
                    SingerDetailData(
                        id = resolved.id,
                        name = resolved.name,
                        picture = resolved.picture,
                        platform = song.platform,
                    ),
                ),
            )
        }
    }

    /** ⋮ → 专辑：先收起播放页，再把专辑详情压入真栈。 */
    fun openAlbumDetail(song: Song) {
        // 与歌手分支一致：点了就收播放页，不等网络
        uiViewModel.setPlayerOverlayVisible(false)
        if (song.platform == MusicPlatform.apple && song.albumId.isNullOrEmpty()) {
            scope.launch {
                val enriched = runCatching { enrichAppleSong(song) }.getOrDefault(song)
                if (enriched.albumId.isNullOrEmpty()) {
                    snackbarHostState.showSnackbar("未找到《${song.album ?: song.title}》的专辑信息")
                    return@launch
                }
                pushAlbumDetail(enriched)
            }
            return
        }
        pushAlbumDetail(song)
    }

    private fun pushAlbumDetail(song: Song) {
        uiViewModel.pushRoute(
            AlbumRoute(
                AlbumDetailData(
                    id = song.albumId ?: "",
                    cover = song.cover,
                    name = song.album ?: "",
                    artist = song.albumArtist ?: song.artist,
                    platform = song.platform,
                ),
            ),
        )
    }
}
