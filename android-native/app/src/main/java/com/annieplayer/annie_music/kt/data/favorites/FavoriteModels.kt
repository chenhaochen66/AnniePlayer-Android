package com.annieplayer.annie_music.kt.data.favorites

import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import kotlinx.serialization.Serializable

// ================================================================================================
// 收藏媒体库的数据模型
// ================================================================================================
//
// ## 收藏只是一份索引
// 这里的 `name` / `cover` / `picture` 是「上次已知」，只负责第一帧立刻画出列表；
// 打开页面时由 `FavoriteRefresher` 并行刷新覆盖（详情页则每次都现拉全量）。
//
// ## 为什么两类收藏结构不同
// 在线条目有稳定的平台 id，键直接用 `platform:id`。本地条目（专辑 / 歌手）**没有 id**：
// 本地曲库是按 tag 分组的，所以键必须与 `LocalLibraryStore` 的分组规则一致
// （`albumGroupKey` / `artistDisplay`），否则「收藏之后再打开」会对不上同一组。
// 代价是改 tag 会让键失联 —— 每条本地收藏额外存 `paths` 做自愈（见 `FavoriteSelfHeal`）。

/** 收藏库的两种模式。切换只影响**显示哪一半**，不影响另一边的数据。 */
@Serializable
enum class FavoriteMode {
    /** 流媒体（在线歌曲 / 专辑 / 歌手 / 歌单） */
    online,

    /** 本地曲库里的文件 */
    local,
}

/**
 * 自建播放列表归属于哪一边。
 *
 * **建的时候就定死，创建后不可改** —— 这样「一个歌单里混着本地歌和在线歌」在数据层不可能存在，
 * 也就没有「这个歌单算哪边」的裁决问题。
 */
@Serializable
enum class PlaylistScope {
    online,
    local,
}

/** 收藏库首页四行入口对应的子页。 */
enum class FavSection {
    /** 我喜欢（收藏的单曲） */
    liked,
    artists,
    albums,
    playlists,
}

/** 收藏的单曲（整首 [Song] 快照，列表直接用它渲染）。 */
@Serializable
data class FavSong(
    val key: String,
    val song: Song,
    val addedAt: Long = 0L,
)

/** 收藏的专辑。本地专辑 [id] 为空串、[paths] 非空（自愈用）。 */
@Serializable
data class FavAlbum(
    val key: String,
    val id: String = "",
    val name: String = "",
    val artist: String = "",
    /**
     * 专辑歌手的平台 id（收藏那一刻详情页拿到的）。
     *
     * 只用于「从收藏库进专辑详情 → 点歌手」按 id 秒开；空串就退回按名字搜索。
     * 酷我的名字里常带 `&` / `,`（`Years & Years`、`Tyler, The Creator`），按名字搜会搜错人。
     */
    val artistId: String = "",
    val cover: String = "",
    val platform: MusicPlatform = MusicPlatform.kuwo,
    /**
     * 收藏那一刻详情页给的曲目数（0 = 不知道）。
     *
     * 列表**只显示这个值**，不发任何请求；打开这张专辑的详情页时用真实值校准
     * （见 `FavoriteStore.syncAlbumMeta`）。本地专辑恒为 0 —— 它的曲目数由曲库现算。
     */
    val trackCount: Int = 0,
    /**
     * 收藏那一刻详情页给的发行日期（`yyyy-MM-dd` / `yyyy`，可能为空）。
     *
     * 本地专辑留空：本地日期来自 tag（`localAlbumDateOf`），不在收藏记录里存一份。
     */
    val releaseDate: String = "",
    /** 仅本地：收藏那一刻属于这张专辑的文件路径，键失联时用来找回 */
    val paths: List<String> = emptyList(),
    val addedAt: Long = 0L,
)

/** 收藏的歌手。本地歌手同样靠 [paths] 自愈。 */
@Serializable
data class FavArtist(
    val key: String,
    val id: String = "",
    val name: String = "",
    val picture: String = "",
    val platform: MusicPlatform = MusicPlatform.kuwo,
    val paths: List<String> = emptyList(),
    val addedAt: Long = 0L,
)

/** 收藏的在线歌单（别人的歌单，不是自建的）。 */
@Serializable
data class FavPlaylist(
    val key: String,
    val id: String = "",
    val name: String = "",
    val cover: String = "",
    val platform: MusicPlatform = MusicPlatform.kuwo,
    /** 收藏那一刻详情页给的创建者；打开歌单详情时用真实值校准 */
    val creator: String = "",
    /** 收藏那一刻详情页给的曲目数（0 = 不知道）；校准规则同 [FavAlbum.trackCount] */
    val trackCount: Int = 0,
    val addedAt: Long = 0L,
)

/**
 * 用户自建的播放列表。
 *
 * 不叫 `Playlist`：那个名字已被平台的**只读**歌单结构占用（`data/model/Models.kt`），
 * 两者一个有 `songCount`、一个带 `scope`，混用会让人以为能互相替换。
 */
@Serializable
data class UserPlaylist(
    val id: String,
    val name: String,
    val scope: PlaylistScope = PlaylistScope.online,
    val cover: String = "",
    val createdAt: Long = 0L,
    val songs: List<Song> = emptyList(),
)

/** 收藏库的一次性快照（UI 层订阅它，避免每处各读一次磁盘）。 */
data class FavoriteSnapshot(
    val mode: FavoriteMode = FavoriteMode.online,
    val songs: List<FavSong> = emptyList(),
    val albums: List<FavAlbum> = emptyList(),
    val artists: List<FavArtist> = emptyList(),
    val playlists: List<FavPlaylist> = emptyList(),
    val userPlaylists: List<UserPlaylist> = emptyList(),
) {
    /** 当前模式下收藏的单曲（本地/在线按 `Song.platform` 分）。 */
    fun songsIn(mode: FavoriteMode): List<FavSong> =
        songs.filter { (it.song.platform == MusicPlatform.local) == (mode == FavoriteMode.local) }

    fun albumsIn(mode: FavoriteMode): List<FavAlbum> =
        albums.filter { (it.platform == MusicPlatform.local) == (mode == FavoriteMode.local) }

    fun artistsIn(mode: FavoriteMode): List<FavArtist> =
        artists.filter { (it.platform == MusicPlatform.local) == (mode == FavoriteMode.local) }

    /** 收藏的在线歌单只属于流媒体模式。 */
    fun playlistsIn(mode: FavoriteMode): List<FavPlaylist> =
        if (mode == FavoriteMode.local) emptyList() else playlists

    /** 自建歌单按 `scope` 归边（混合不存在，所以不需要"两边都显示"）。 */
    fun userPlaylistsIn(mode: FavoriteMode): List<UserPlaylist> =
        userPlaylists.filter {
            (it.scope == PlaylistScope.local) == (mode == FavoriteMode.local)
        }
}
