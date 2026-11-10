package com.annieplayer.annie_music.kt.data.favorites

import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song

// ================================================================================================
// 收藏对象的唯一键
// ================================================================================================
//
// 一套规则，四处复用（toggle、去重、失联自愈、UI 判断爱心亮不亮）。写散一次就会
// 「同一个专辑能收藏两次」。
//
// ## 在线 / 本地为什么不是同一套
// 在线条目有平台给的稳定 id；本地条目**没有 id** —— 本地曲库是按 tag 分组的，
// 所以键必须与分组规则逐字一致，否则「收藏之后再打开」对不上同一组。
// 具体：
//   本地专辑 → `LocalTrack.albumGroupKey()` 的结果（`"专辑名 · 专辑艺术家"`）
//   本地歌手 → `LocalTrackMeta.artistDisplay`（多歌手拆开后逐个算一条）
// 改了 `albumGroupKey` 的拼接方式就必须同步改这里，否则老收藏集体失联。

object FavoriteKeys {

    /**
     * 在线条目键：`platform:id`。
     *
     * 本地歌的 `Song.id` 本身已经是 `local:<绝对路径>`，再拼前缀就变成
     * `local:local:/…`，改一次 id 规则就全丢 —— 所以本地直接原样返回。
     */
    fun online(platform: MusicPlatform, id: String): String =
        if (platform == MusicPlatform.local || id.startsWith("local:")) id
        else "${platform.name}:$id"

    /** 单曲键：在线走 `platform:id`，本地就是 `Song.id`。 */
    fun song(song: Song): String = online(song.platform, song.id)

    /**
     * 本地文件的收藏键：`local:<绝对路径>`。
     *
     * **必须与 `LocalTrackMeta.id` 逐字一致** —— 那就是收藏时 `Song.id` 的值。
     * 单独抽出来是因为「删掉本地文件」那条路只有路径、没有 `Song`：
     * 这里拼错一个字符，收藏就永远删不掉，而且静默。
     */
    fun localPath(path: String): String = "local:$path"

    /**
     * 本地专辑键。**必须与 `albumGroupKey(LocalTrackMeta)` 逐字一致**：
     * 那边是 `listOfNotNull(album.ifBlank { "未知专辑" }, albumArtist?.takeIf { isNotBlank() })`。
     */
    fun localAlbum(album: String?, albumArtist: String?): String =
        listOfNotNull(
            album?.takeIf { it.isNotBlank() } ?: "未知专辑",
            albumArtist?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")

    /** 本地歌手键：展示用的歌手串（多歌手已拆成 `A / B`，这里只放单个名字）。 */
    fun localArtist(artist: String): String = artist.trim()

    /** 专辑键：本地走 tag 分组键，在线走 `platform:id`。 */
    fun album(platform: MusicPlatform, albumId: String?, name: String, artist: String): String =
        if (platform == MusicPlatform.local) localAlbum(name, artist)
        else online(platform, albumId.orEmpty())

    /** 歌手键：本地走名字，在线走 `platform:id`。 */
    fun artist(platform: MusicPlatform, artistId: String?, name: String): String =
        if (platform == MusicPlatform.local) localArtist(name)
        else online(platform, artistId.orEmpty())

    /** 歌单键：歌单只存在于在线平台。 */
    fun playlist(platform: MusicPlatform, id: String): String = online(platform, id)
}
