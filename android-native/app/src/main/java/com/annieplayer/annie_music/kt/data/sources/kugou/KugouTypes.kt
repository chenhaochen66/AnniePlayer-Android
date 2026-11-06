package com.annieplayer.annie_music.kt.data.sources.kugou

// ================================================================================================
// 酷狗返回值结构（对齐 Dart records / 私有类）
// ================================================================================================

/** fetchPlaylistInfo 返回：name / cover */
data class KugouPlaylistInfo(
    val name: String,
    val cover: String,
)

/** fetchSpecialPage 返回：name / cover / songs */
data class KugouSpecialPage(
    val name: String,
    val cover: String,
    val songs: List<Any?>,
)

/** fetchKugouLyric 返回：lrc / tlyric / 逐字 */
data class KugouLyricResult(
    val lrc: String,
    val tlyric: String?,
    val wordLyric: String? = null,
)

/** enrichKuGouSong 返回：trackNumber / discNumber / releaseDate */
data class KugouSongEnrich(
    val trackNumber: String?,
    val discNumber: String?,
    val releaseDate: String?,
    val albumArtist: String?,
)

/** KRC 歌词行：time / text */
data class KugouKrcLine(
    val time: Int,
    val text: String,
)

/** 专辑曲目信息：sort / disc */
data class KugouAlbumTrack(
    val sort: String,
    val disc: String,
)

/** getKugouSongAlbumId 返回：albumId / publishDate */
data class KugouSongAlbumId(
    val albumId: String,
    val publishDate: String,
)

/** fetchKugouArtistInfo 返回：avatar / songCount / intro（歌手简介，可能为空） */
data class KugouArtistInfo(
    val avatar: String,
    val songCount: Int,
    val intro: String = "",
)

/** 逐曲歌手（[fetchKugouSongAuthors] 的返回值） */
data class KugouAuthor(val id: String, val name: String)

/** CDN 补充信息（封面 / 专辑名 / 发行日期） */
internal data class KugouCdnEnrich(
    val cover: String,
    val albumName: String,
    val publishDate: String,
)

/** 专辑信息：发行日期 / 专辑歌手（`album/song` 都不带，需另取 `album/info`） */
internal data class KugouAlbumInfo(
    val pub: String,
    val albumArtist: String,
)
