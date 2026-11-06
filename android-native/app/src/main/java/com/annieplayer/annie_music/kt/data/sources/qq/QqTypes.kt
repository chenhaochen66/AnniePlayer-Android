package com.annieplayer.annie_music.kt.data.sources.qq

import com.annieplayer.annie_music.kt.data.model.Song

// ================================================================================================
// QQ 返回值结构（对齐 Dart records / 私有类）
// ================================================================================================

/** 歌单单页结果（对齐 Dart 私有类 `_QQPage`） */
data class QqPage(
    val songs: List<Song>,
    val total: Int,
    val name: String,
    val creator: String,
    val cover: String,
)

/** 歌词结果（对齐 Dart `({String lrc, String? tlyric})`，另带逐字） */
data class QqLyric(
    val lrc: String,
    val tlyric: String?,
    val wordLyric: String? = null,
)

/** 单曲补充信息结果（对齐 Dart `({String? trackNumber, String? discNumber, String? releaseDate})`） */
data class QqEnrich(
    val trackNumber: String?,
    val discNumber: String?,
    val releaseDate: String?,
    val albumArtist: String?,
)
