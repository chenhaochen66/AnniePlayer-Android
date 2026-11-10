package com.annieplayer.annie_music.kt.data.sources.kugou

// ================================================================================================
// 酷狗返回值结构（对齐 Dart records / 私有类）
// ================================================================================================

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

/** 专辑信息：发行日期 / 专辑歌手（`album/song` 都不带，需另取 `album/info`） */
internal data class KugouAlbumInfo(
    val pub: String,
    val albumArtist: String,
)
