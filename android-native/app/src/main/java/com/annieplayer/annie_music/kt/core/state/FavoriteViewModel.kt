package com.annieplayer.annie_music.kt.core.state

import androidx.lifecycle.ViewModel
import com.annieplayer.annie_music.kt.data.favorites.FavAlbum
import com.annieplayer.annie_music.kt.data.favorites.FavArtist
import com.annieplayer.annie_music.kt.data.favorites.FavPlaylist
import com.annieplayer.annie_music.kt.data.favorites.FavoriteKeys
import com.annieplayer.annie_music.kt.data.favorites.FavoriteMode
import com.annieplayer.annie_music.kt.data.favorites.FavoriteSelfHeal
import com.annieplayer.annie_music.kt.data.favorites.FavoriteSnapshot
import com.annieplayer.annie_music.kt.data.favorites.FavoriteStore
import com.annieplayer.annie_music.kt.data.favorites.UserPlaylist
import com.annieplayer.annie_music.kt.data.local.LocalTrackMeta
import com.annieplayer.annie_music.kt.data.local.albumGroupKey
import com.annieplayer.annie_music.kt.data.local.artistGroupKeys
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 收藏媒体库的唯一状态源（Activity 作用域，与 [LocalLibraryViewModel] 同级）。
 *
 * ## 为什么必须单点
 * 每个页面自己去读 `FavoriteStore` 会导致「A 页收藏了，B 页爱心还是空心」——
 * 心形按钮到处都是（四个详情页 + 播放页），各读一份必然走散。
 *
 * ## 两种读法
 * - **要跟着变**（爱心、计数、列表）：`collectAsState()` 订阅 [snapshot]；
 * - **只要判一下**（`isLiked` 这种 O(1) 查询）：调 `isXxxFavorite()`，它走 store 的 key 缓存，
 *   不订阅、不触发重组。列表行里逐行订阅 flow 是滚动掉帧的典型来源。
 */
class FavoriteViewModel : ViewModel() {

    private val _snapshot = MutableStateFlow(FavoriteStore.snapshot())
    val snapshot: StateFlow<FavoriteSnapshot> = _snapshot.asStateFlow()

    /** 重新读盘（收藏变更后必须调，否则 flow 还是旧的） */
    fun reload() {
        _snapshot.value = FavoriteStore.snapshot()
    }

    fun setMode(mode: FavoriteMode) {
        if (FavoriteStore.mode() == mode) return
        FavoriteStore.setMode(mode)
        reload()
    }

    // ── 判定（不订阅） ──

    fun isSongLiked(song: Song): Boolean = FavoriteStore.isSongLiked(song)

    fun isAlbumFavorite(key: String): Boolean = FavoriteStore.isAlbumFavorite(key)

    fun isArtistFavorite(key: String): Boolean = FavoriteStore.isArtistFavorite(key)

    fun isPlaylistFavorite(key: String): Boolean = FavoriteStore.isPlaylistFavorite(key)

    // ── 切换 ──

    fun toggleSong(song: Song): Boolean = FavoriteStore.toggleSong(song).also { reload() }

    /**
     * 收藏 / 取消收藏一张专辑。
     *
     * [paths] 只有本地专辑才需要（键失联时靠它找回，见 `FavoriteSelfHeal`）。
     * [key] 留空时按 [FavoriteKeys.album] 现算；本地曲库的专辑键是**曲库自己算的**
     * （`albumGroupKey`，用 `albumArtist`），与 `FavoriteKeys.album` 拼出来的
     * （用 `artistDisplay`）在「专辑艺术家 ≠ 曲目歌手」时并不相同 —— 那种情况必须显式传 key，
     * 否则收藏后判定用的键和存进去的键对不上，爱心点了不变。
     * [trackCount] / [releaseDate] 来自详情页当时已加载的数据，只用于列表展示，
     * 之后由 [syncAlbumMeta] 在再次打开详情页时校准。
     */
    fun toggleAlbum(
        platform: MusicPlatform,
        albumId: String?,
        name: String,
        artist: String,
        cover: String,
        paths: List<String> = emptyList(),
        key: String? = null,
        trackCount: Int = 0,
        releaseDate: String = "",
        artistId: String = "",
    ): Boolean {
        val resolved = key ?: FavoriteKeys.album(platform, albumId, name, artist)
        return FavoriteStore.toggleAlbum(
            FavAlbum(
                key = resolved,
                id = if (platform == MusicPlatform.local) "" else albumId.orEmpty(),
                name = name,
                artist = artist,
                artistId = if (platform == MusicPlatform.local) "" else artistId,
                cover = cover,
                platform = platform,
                trackCount = trackCount,
                releaseDate = releaseDate,
                paths = paths,
            ),
        ).also { reload() }
    }

    /** 收藏 / 取消收藏一位歌手；本地的 [paths] 同样用于自愈，[key] 同 [toggleAlbum]。 */
    fun toggleArtist(
        platform: MusicPlatform,
        artistId: String?,
        name: String,
        picture: String,
        paths: List<String> = emptyList(),
        key: String? = null,
    ): Boolean {
        val resolved = key ?: FavoriteKeys.artist(platform, artistId, name)
        return FavoriteStore.toggleArtist(
            FavArtist(
                key = resolved,
                id = if (platform == MusicPlatform.local) "" else artistId.orEmpty(),
                name = name,
                picture = picture,
                platform = platform,
                paths = paths,
            ),
        ).also { reload() }
    }

    /** 收藏 / 取消收藏一张在线歌单。[creator] / [trackCount] 同 [toggleAlbum] 的曲目数：只供列表展示，之后校准。 */
    fun togglePlaylist(
        platform: MusicPlatform,
        id: String,
        name: String,
        cover: String,
        creator: String = "",
        trackCount: Int = 0,
    ): Boolean {
        return FavoriteStore.togglePlaylist(
            FavPlaylist(
                key = FavoriteKeys.playlist(platform, id),
                id = id,
                name = name,
                cover = cover,
                platform = platform,
                creator = creator,
                trackCount = trackCount,
            ),
        ).also { reload() }
    }

    /**
     * 幂等加入收藏（链接导入用）。返回 false = 本来就在收藏里。
     *
     * 不用 [togglePlaylist]：导入的语义是「确保它在收藏里」，toggle 第二次会把它删掉。
     */
    fun addPlaylist(
        platform: MusicPlatform,
        id: String,
        name: String,
        cover: String,
        creator: String = "",
        trackCount: Int = 0,
    ): Boolean {
        val added = FavoriteStore.addPlaylist(
            FavPlaylist(
                key = FavoriteKeys.playlist(platform, id),
                id = id,
                name = name,
                cover = cover,
                platform = platform,
                creator = creator,
                trackCount = trackCount,
            ),
        )
        if (added) reload()
        return added
    }

    // ── 打开详情页时的校准 ──
    //
    // 列表页永远只显示收藏那一刻存下来的数据（名字 / 封面 / 曲目数 / 发行日期 / 创建者），
    // **不发请求**；只有用户真的打开了那张专辑 / 那位歌手 / 那个歌单，才拿当时的真值对齐一次。
    // 未收藏或值没变都不写库（见 `FavoriteStore.syncAlbumMeta` 与 `FavoriteSync`）。
    //
    // 传进来的空串 / 0 一律当作「这次没拿到」，`FavoriteSync` 会保留原值 ——
    // 所以调用方不需要自己判断哪些字段有值。

    fun syncAlbumMeta(
        platform: MusicPlatform,
        albumId: String,
        name: String,
        artist: String,
        cover: String,
        trackCount: Int,
        releaseDate: String,
        artistId: String = "",
    ) {
        val key = FavoriteKeys.album(platform, albumId, name, artist)
        if (
            FavoriteStore.syncAlbumMeta(
                key, name, artist, cover, trackCount, releaseDate, artistId,
            )
        ) {
            reload()
        }
    }

    fun syncArtistMeta(
        platform: MusicPlatform,
        artistId: String?,
        name: String,
        picture: String,
    ) {
        val key = FavoriteKeys.artist(platform, artistId, name)
        if (FavoriteStore.syncArtistMeta(key, name, picture)) reload()
    }

    fun syncPlaylistMeta(
        platform: MusicPlatform,
        id: String,
        name: String,
        cover: String,
        creator: String,
        trackCount: Int,
    ) {
        val key = FavoriteKeys.playlist(platform, id)
        if (FavoriteStore.syncPlaylistMeta(key, name, cover, creator, trackCount)) reload()
    }

    // ── 本地收藏的失联自愈 ──

    /**
     * 用当前曲库修正本地专辑 / 歌手的键（用户改过 tag 之后）。
     *
     * 本地条目的键来自 tag 分组，改 tag 就失联：收藏还在库里，但爱心变空心、
     * 再点一次会**多出一条**收藏。每条本地收藏都存了 `paths`，这里用它反查。
     *
     * 触发时机：打开收藏库且当前是本地模式（调用方给 `tracks`）。
     * 找不到新键时**不动记录** —— 文件可能只是暂时不在（拔卡 / 正在整理）。
     */
    fun healLocalEntries(tracks: List<LocalTrackMeta>) {
        if (tracks.isEmpty()) return
        var changed = false
        val albumKeyByPath = tracks.associate { it.path to albumGroupKey(it) }
        val artistKeysByPath = tracks.associate { it.path to artistGroupKeys(it) }

        // 先快照再遍历：rekey 会改 store 的内存副本，边遍历边改会漏条目
        FavoriteStore.albums().toList().forEach { fav ->
            FavoriteSelfHeal.healAlbum(fav) { albumKeyByPath[it] }?.let { healed ->
                FavoriteStore.rekeyAlbum(fav.key, healed)
                changed = true
            }
        }
        FavoriteStore.artists().toList().forEach { fav ->
            FavoriteSelfHeal.healArtist(fav) { artistKeysByPath[it].orEmpty() }?.let { healed ->
                FavoriteStore.rekeyArtist(fav.key, healed)
                changed = true
            }
        }
        if (changed) reload()
    }

    // ── 自建播放列表 ──

    fun saveUserPlaylist(playlist: UserPlaylist, coverBlob: ByteArray? = null) {
        FavoriteStore.saveUserPlaylist(playlist, coverBlob)
        reload()
    }

    /** 自建歌单的自选封面字节（没有返回 null）；带内存缓存，列表滚动时不会反复查库 */
    fun playlistCover(id: String): ByteArray? = FavoriteStore.playlistCover(id)

    fun deleteUserPlaylist(id: String) {
        FavoriteStore.deleteUserPlaylist(id)
        reload()
    }

    fun addSongToUserPlaylist(playlistId: String, song: Song): Boolean =
        FavoriteStore.addSongToUserPlaylist(playlistId, song).also { if (it) reload() }

    fun removeSongFromUserPlaylist(playlistId: String, song: Song) {
        FavoriteStore.removeSongFromUserPlaylist(playlistId, song.id, song.platform)
        reload()
    }

    /** 批量移除（自建歌单的多选）：一次写库 + 一次 reload */
    fun removeSongsFromUserPlaylist(playlistId: String, songs: List<Song>) {
        if (songs.isEmpty()) return
        FavoriteStore.removeSongsFromUserPlaylist(playlistId, songs)
        reload()
    }
}
