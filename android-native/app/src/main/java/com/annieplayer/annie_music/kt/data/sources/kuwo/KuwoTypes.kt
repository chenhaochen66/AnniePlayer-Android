package com.annieplayer.annie_music.kt.data.sources.kuwo

import com.annieplayer.annie_music.kt.data.model.AlbumSong
import com.annieplayer.annie_music.kt.data.model.Song

// ================================================================================================
// 酷我返回值结构（对齐 Dart records）
// ================================================================================================

/** 歌手歌曲分页结果（对齐 Dart `({List<Song> songs, int total})`） */
data class KuwoArtistSongs(
    val songs: List<Song>,
    val total: Int,
)

/** 专辑歌曲结果（对齐 Dart `({List<AlbumSong> songs, String pub})`） */
data class KuwoAlbumSongs(
    val songs: List<AlbumSong>,
    val pub: String,
)

/** 歌词结果（对齐 Dart `({String lrc, String? tlyric})`，另带逐字） */
data class KuwoLyric(
    val lrc: String,
    val tlyric: String?,
    val wordLyric: String? = null,
)

/** 单曲补充信息结果（对齐 Dart `({String? trackNumber, String? releaseDate})`） */
data class KuwoEnrich(
    val trackNumber: String?,
    val releaseDate: String?,
    val albumArtist: String?,
)

/** 歌单单页结果（对齐 Dart 私有类 `_KuwoPage`） */
data class KuwoPage(
    val songs: List<Song>,
    val total: Int,
    val name: String,
    val creator: String,
    val cover: String,
)
