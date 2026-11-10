package com.annieplayer.annie_music.kt.data.favorites

// ================================================================================================
// 收藏记录的「校准」判断（纯函数，可单测）
// ================================================================================================
//
// ## 什么时候校准
// 收藏的专辑 / 歌手 / 歌单只存了**收藏那一刻**的事实（名字、封面、曲目数、发行日期、创建者）。
// 列表页永远显示这份存储值、不发任何请求；只有用户真的打开了那张专辑 / 那位歌手 / 那个歌单的
// 详情页，才拿当时拿到的事实来对齐。
//
// ## 真值从哪来（三处都不一样，别混）
// | 对象 | 字段 | 来源 |
// |---|---|---|
// | 专辑 | 曲目数 / 发行日期 | 接口（`fetchAlbumSongsByPlatform` 的 songs.size / pub） |
// | 专辑 | 名字 / 歌手 / 封面 | **入参** `AlbumDetailData` —— 从主页 / 搜索进来时是平台当前值；
// |      |                    | 从收藏库进来时它就是收藏记录本身（于是等于没变、不写库） |
// | 专辑 | 歌手 id | **入参** `AlbumDetailData.artistId`（酷我的专辑接口本来就给 `artistid`，以前没读） |
// | 歌手 | 头像 | 接口（`fetchArtistAvatarByPlatform`） |
// | 歌手 | 名字 / 头像 | 同上，入参 `SingerDetailData` |
// | 歌单 | 名字 / 封面 / 创建者 / 曲目数 | 接口（`PlaylistService.fetchPlaylist`） |
//
// 专辑的「接口级」名字 / 封面拿不到：专辑详情接口只返回曲目与发行日期，
// 那要等 §3.4 的 `FavoriteRefresher` + 轻接口。
//
// ## 为什么判断要独立成纯函数
// `FavoriteStore` 里那一步依赖 SQLite（JVM 单测跑不了），但「该不该写」的语义是纯的：
// 未收藏不写、值没变不写、变了才写。三条规则写错任何一条的表现都是
// 「每次打开详情页都产生一次写」或「涨了也不更新」，而这两种都不会崩、只会悄悄错。

/** 空串 = 「这次没拿到」，保留原值；不要用空串去覆盖已有数据。 */
private fun String.orKeep(current: String): String = ifBlank { current }

/** 0 / 负数 = 「这次没拿到」，保留原值。 */
private fun Int.orKeep(current: Int): Int = if (this > 0) this else current

/** 专辑元信息校准：返回需要写回的新记录；`null` = 不用动（未收藏 / 值没变）。 */
internal fun albumMetaChange(
    existing: FavAlbum?,
    name: String,
    artist: String,
    cover: String,
    trackCount: Int,
    releaseDate: String,
    artistId: String = "",
): FavAlbum? {
    if (existing == null) return null
    val updated = existing.copy(
        name = name.orKeep(existing.name),
        artist = artist.orKeep(existing.artist),
        cover = cover.orKeep(existing.cover),
        trackCount = trackCount.orKeep(existing.trackCount),
        releaseDate = releaseDate.orKeep(existing.releaseDate),
        // 歌手 id 一旦拿到就不再变，空串表示这次没拿到 → 保留原值
        artistId = artistId.orKeep(existing.artistId),
    )
    return if (updated == existing) null else updated
}

/** 歌手元信息校准：理由同 [albumMetaChange]。 */
internal fun artistMetaChange(existing: FavArtist?, name: String, picture: String): FavArtist? {
    if (existing == null) return null
    val updated = existing.copy(
        name = name.orKeep(existing.name),
        picture = picture.orKeep(existing.picture),
    )
    return if (updated == existing) null else updated
}

/** 歌单元信息校准：理由同 [albumMetaChange]。 */
internal fun playlistMetaChange(
    existing: FavPlaylist?,
    name: String,
    cover: String,
    creator: String,
    trackCount: Int,
): FavPlaylist? {
    if (existing == null) return null
    val updated = existing.copy(
        name = name.orKeep(existing.name),
        cover = cover.orKeep(existing.cover),
        creator = creator.orKeep(existing.creator),
        trackCount = trackCount.orKeep(existing.trackCount),
    )
    return if (updated == existing) null else updated
}
