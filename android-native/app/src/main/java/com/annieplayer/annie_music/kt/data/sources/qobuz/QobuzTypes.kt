package com.annieplayer.annie_music.kt.data.sources.qobuz

import com.annieplayer.annie_music.kt.data.model.Album
import com.annieplayer.annie_music.kt.data.model.Song

// ================================================================================================
// Qobuz 返回值结构（对齐 Dart records）
// ================================================================================================
//
// 只放**跨文件共用**的记录类型。各文件自己的中间结构（如歌词两步法的 dto）留在使用处。

/** 请求签名结果（对齐 Dart `({String ts, String sig})`） */
data class QobuzSignature(
    val ts: String,
    val sig: String,
)

/** checkToken 返回（对齐 Dart `({String region, bool valid})`） */
data class QobuzTokenStatus(
    val region: String,
    val valid: Boolean,
)

/** getFileUrl 返回（对齐 Dart `({String url, String mimeType})`） */
data class QobuzFileUrl(
    val url: String,
    val mimeType: String,
)

/** parseQobuzUrl 返回（对齐 Dart `({String type, String id})`） */
data class QobuzUrlRef(
    val type: String,
    val id: String,
)

/** 官方歌词：原文 + 可选译文 + 可用译文语言清单 + 本次实际使用的译文语言 */
data class QobuzLyricResult(
    val lrc: String,
    val tlyric: String?,
    val translateLangs: List<String>,
    val translateLang: String? = null,
)

/** 歌手详情页数据（Qobuz 没有「歌手全部单曲」接口，故歌曲由两路合并而来） */
data class QobuzArtistPage(
    val name: String,
    val picture: String,
    val albums: List<Album>,
    val songs: List<Song>,
)
