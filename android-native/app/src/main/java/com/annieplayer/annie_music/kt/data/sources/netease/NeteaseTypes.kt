package com.annieplayer.annie_music.kt.data.sources.netease

// ================================================================================================
// 网易云返回值结构（对齐 Dart records / 私有类）
// ================================================================================================

/** 歌词结果（对齐 Dart `({String lrc, String? tlyric})`，另带逐字） */
data class NeteaseLyric(
    val lrc: String,
    val tlyric: String?,
    val wordLyric: String? = null,
)

/** 网易云专辑内单曲（对齐 Dart `NeteaseAlbumSong`） */
data class NeteaseAlbumSong(
    val id: String,
    val name: String,
    val no: Int,
    val disc: String,
    val artist: String,
)

/** 网易云专辑详情（对齐 Dart `NeteaseAlbumDetail`） */
data class NeteaseAlbumDetail(
    val name: String,
    val artist: String,
    val publishTime: String,
    val company: String,
    val songs: List<NeteaseAlbumSong>,
)

/** 网易云单曲补充信息（走专辑详情接口：曲目号/CD号/发行时间/专辑歌手）；失败返回全 null */
data class NeteaseEnrich(
    val trackNumber: String?,
    val discNumber: String?,
    val releaseDate: String?,
    val albumArtist: String?,
)
