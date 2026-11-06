package com.annieplayer.annie_music.kt.data.sources.kkbox

// ================================================================================================
// KKBOX 返回值结构（对齐 Dart records / RN 版对象）
// ================================================================================================

/** KKBOX URL 解析结果（RN 版 `{ type, id }`） */
data class KkboxUrlInfo(
    /** 'song' | 'album' */
    val type: String,
    val id: String,
)

/** 登录结果 */
data class KkboxLoginResult(
    val sid: String,
    val licContentKey: String,
    val highQuality: Boolean,
    val kkid: String,
)

/** 续期结果 */
data class KkboxSessionInfo(
    val sid: String,
    val licContentKey: String,
    val highQuality: Boolean,
)

/** 歌词结果（对齐 Dart `({String lrc, String? tlyric})`，翻译恒为 null） */
data class KkboxLyric(
    val lrc: String,
    val tlyric: String?,
)

/** 专辑详情原始数据（album + songs 原始 json） */
data class KkboxAlbumData(
    val album: Map<String, Any?>,
    val songs: List<Map<String, Any?>>,
)

/** KKBOX 单曲补充信息（走专辑详情接口：曲目号/发行时间/专辑歌手）；失败返回全 null */
data class KkboxEnrich(
    val trackNumber: String?,
    val discNumber: String?,
    val releaseDate: String?,
    val albumArtist: String?,
)

/** 播放票据结果（uris + 可能被续期后的 sid） */
data class KkboxTicket(
    val uris: List<Map<String, Any?>>,
    val sid: String,
)

/** 播放直链结果 */
data class KkboxPlayResult(
    val url: String,
    val sid: String,
)

/** 下载直链结果 */
data class KkboxDownloadResult(
    val url: String,
    val formatName: String,
    val sid: String,
)
