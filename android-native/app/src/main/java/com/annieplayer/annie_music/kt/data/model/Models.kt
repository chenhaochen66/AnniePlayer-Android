package com.annieplayer.annie_music.kt.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 数据模型（Flutter `lib/core/models/models.dart` 的 Kotlin 一一对应移植）。
 *
 * 迁移约定：
 * - Dart 的 `const` 构造函数在 Kotlin 中由 `data class` + 默认参数承担；Dart 的 `required`
 *   参数 → 无默认值参数，可选参数 → 带默认值参数，nullability 语义完全保留。
 * - Flutter 原文件 **没有** `fromJson` / `toJson`：真实的 JSON 解析分散在各
 *   `data/sources/` 下的 `*_api.dart`（kuwo/qq/netease/kugou/kkbox）与 provider 的持久化代码里。
 *   因此 `@SerialName` 只标注「真实 JSON key 与 Kotlin 属性名不一致」的字段（见下方
 *   [Album.htsImg] 与 [Album.playcnt]），其余字段的 JSON key 与属性名一致，无需标注。
 * - 全程不使用 `!!` 与 `lateinit`，用可空类型 + 默认值表达所有可选语义。
 * - 平台专属怪癖（酷我 `&`/`\u0026` 转义、各平台封面规则、音质档位）在对应成员上以中文注释保留。
 */
@Serializable
enum class MusicPlatform(val label: String) {
    netease("网易"),
    kuwo("酷我"),
    kkbox("KKBOX"),
    qq("QQ"),
    kugou("酷狗"),
    qobuz("Qobuz"),
    ;

    companion object {
        /**
         * 前缀字符串 -> 枚举；未知返回 null（对齐 Dart `MusicPlatform.fromPrefix`）。
         * 判定依据是 Dart 的 `name`，故常量名即前缀（如 `netease:` / `qq:`）。
         */
        fun fromPrefix(prefix: String): MusicPlatform? =
            entries.firstOrNull { prefix == "${it.name}:" }
    }
}

/**
 * 音质档位（对齐 Dart `Quality`，比任务描述多一档 `hires`）。
 *
 * 常量名保留 Dart 原样（`k128k` / `k320k` / `flac` / `hires`）。
 * `@SerialName` 取 Dart 的 `value`（'128k' / '320k' / 'flac' / 'hires'），
 * 即请求平台接口时真正使用的字符串值。
 */
@Serializable
enum class Quality(
    /** 字符串值（'128k' / '320k' / 'flac' / 'hires'） */
    val value: String,
    /** 中文标签 */
    val label: String,
    /** 平台格式名（kuwo 风格） */
    val format: String,
) {
    @SerialName("128k")
    k128k("128k", "标准音质", "128kmp3"),

    @SerialName("320k")
    k320k("320k", "高品音质", "320kmp3"),

    @SerialName("flac")
    flac("flac", "无损音质", "2000kflac"),

    @SerialName("hires")
    hires("hires", "HiRes", "4000kflac"),
    ;

    companion object {
        /** 字符串值 -> 枚举；未知返回 null（对齐 Dart `Quality.fromValue`）。 */
        fun fromValue(v: String?): Quality? = entries.firstOrNull { it.value == v }
    }
}

/** 歌曲 */
@Serializable
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val cover: String,
    val album: String? = null,
    val albumId: String? = null,
    /** 时长（秒） */
    val duration: Int,
    val platform: MusicPlatform,
    val playUrl: String = "",
    val lyric: String? = null,
    val tlyric: String? = null,
    val qualities: List<Quality> = emptyList(),
    val albumArtist: String? = null,
    val trackNumber: String? = null,
    val discNumber: String? = null,
    val releaseDate: String? = null,
    /** 歌手名列表（多歌手逐个列出；单歌手为 1 个元素） */
    val artistNames: List<String> = emptyList(),
    /** 歌手 id 列表（与 [artistNames] 按下标对应；平台拿不到时为空列表） */
    val artistIds: List<String> = emptyList(),
) {
    /**
     * 局部更新（对齐 Dart `Song.copyWith`：仅这 4 个字段可改，其余原样拷贝）。
     *
     * 注意：与 Dart 语义一致，传 `null` **不会**清空 `lyric` / `tlyric`，
     * 因为原实现使用 `?? this.xxx` 回退（非幂等 setter）。
     */
    fun copyWith(
        playUrl: String? = null,
        lyric: String? = null,
        tlyric: String? = null,
        qualities: List<Quality>? = null,
    ): Song = Song(
        id = id,
        title = title,
        artist = artist,
        cover = cover,
        album = album,
        albumId = albumId,
        duration = duration,
        platform = platform,
        playUrl = playUrl ?: this.playUrl,
        lyric = lyric ?: this.lyric,
        tlyric = tlyric ?: this.tlyric,
        qualities = qualities ?: this.qualities,
        albumArtist = albumArtist,
        trackNumber = trackNumber,
        discNumber = discNumber,
        releaseDate = releaseDate,
        artistNames = artistNames,
        artistIds = artistIds,
    )
}

/** 歌单 */
@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val creator: String? = null,
    val cover: String,
    val platform: MusicPlatform,
    val songs: List<Song>,
    val songCount: Int,
)

/** 循环模式 */
@Serializable
enum class LoopMode {
    sequential,
    single,
    shuffle,
    ;

    companion object {
        /** 名称 -> 枚举；未知回退为 [sequential]（对齐 Dart `LoopMode.fromName`）。 */
        fun fromName(name: String?): LoopMode =
            entries.firstOrNull { it.name == name } ?: sequential
    }
}

/** 下载任务状态 */
@Serializable
enum class DownloadStatus {
    pending,
    downloading,
    completed,
    error,
    paused,
    cancelled,
}

/** 下载任务 */
@Serializable
data class DownloadTask(
    val songId: String,
    val title: String,
    val artist: String,
    val cover: String,
    val quality: Quality,
    val progress: Double = 0.0,
    val status: DownloadStatus = DownloadStatus.pending,
    val filePath: String? = null,
) {
    /**
     * 局部更新（对齐 Dart `DownloadTask.copyWith`）。
     * 同样地，传 `null` 不会清空 [filePath]（`?? this.filePath` 回退）。
     */
    fun copyWith(
        progress: Double? = null,
        status: DownloadStatus? = null,
        filePath: String? = null,
    ): DownloadTask = DownloadTask(
        songId = songId,
        title = title,
        artist = artist,
        cover = cover,
        quality = quality,
        progress = progress ?: this.progress,
        status = status ?: this.status,
        filePath = filePath ?: this.filePath,
    )
}

/**
 * 专辑（kuwo 数据源；其他平台复用同一结构，由 [platform] 分发）。
 *
 * 字段名沿用酷我接口的小写 key。`htsImg` 与 `playcnt` 的真实 JSON key 与属性名不一致，
 * 故显式标注 `@SerialName`（酷我 `_firstTruthy` 读取顺序：小写优先，`PLAYCNT` 为大写）。
 */
@Serializable
data class Album(
    val albumid: String,
    val name: String,
    val artist: String,
    /** 所属平台（专辑搜索 / 专辑详情按它分发） */
    val platform: MusicPlatform = MusicPlatform.kuwo,
    val musiccnt: String = "0",
    val pub: String = "",
    val pic: String = "",
    val img: String = "",
    @SerialName("hts_img")
    val htsImg: String = "",
    val company: String = "",
    val lang: String = "",
    val vip: String = "",
    @SerialName("PLAYCNT")
    val playcnt: String = "",
)

/** 专辑内歌曲（kuwo） */
@Serializable
data class AlbumSong(
    val id: String,
    val name: String,
    val artistName: String,
    val duration: Int,
    val track: Int? = null,
    /** 歌手名列表（多歌手逐个列出） */
    val artistNames: List<String> = emptyList(),
    /** 歌手 id 列表（与 [artistNames] 按下标对应；平台拿不到时为空） */
    val artistIds: List<String> = emptyList(),
)

/** 歌手 */
@Serializable
data class Artist(
    val id: String,
    val name: String,
    val picture: String,
    val musicNum: Int = 0,
)

/** Qobuz 单曲 */
@Serializable
data class QobuzTrack(
    val id: String,
    val title: String,
    val artist: String,
    val cover: String,
    val album: String,
    val albumId: String,
    val albumArtist: String? = null,
    val trackNumber: Int,
    val discNumber: Int? = null,
    val duration: Int,
    val streamable: Boolean,
    val label: String = "",
    val year: Int = 0,
)

/** Qobuz 专辑 */
@Serializable
data class QobuzAlbum(
    val id: String,
    val title: String,
    val artist: String,
    val cover: String,
    val year: Int = 0,
    val label: String = "",
    val tracks: List<QobuzTrack>,
)

/** Qobuz Token */
@Serializable
data class QobuzToken(
    val id: String,
    val name: String,
    val token: String,
    val isActive: Boolean = false,
) {
    /** 局部更新（对齐 Dart `QobuzToken.copyWith`）。持久化 key 与属性名一致（id/name/token/isActive）。 */
    fun copyWith(isActive: Boolean? = null): QobuzToken = QobuzToken(
        id = id,
        name = name,
        token = token,
        isActive = isActive ?: this.isActive,
    )
}

/**
 * KKBOX 单曲。
 * 注意 FFI/接口字段名：`encryptedId` 为 KKBOX 加密 id，与明文 id 并存。
 */
@Serializable
data class KkboxTrack(
    val id: String,
    val encryptedId: String,
    val title: String,
    val artist: String,
    val cover: String,
    val album: String,
    val albumId: String,
    val albumArtist: String? = null,
    val trackNumber: Int,
    val duration: Int,
    val qualities: List<String> = emptyList(),
    val releaseDate: String? = null,
)

/** KKBOX 专辑 */
@Serializable
data class KkboxAlbum(
    val id: String,
    val encryptedId: String,
    val title: String,
    val artist: String,
    val cover: String,
    val year: String = "",
    val tracks: List<KkboxTrack>,
)

/** KKBOX 会话 */
@Serializable
data class KkboxSession(
    val sid: String,
    val licContentKey: String,
    val highQuality: Boolean,
    val kkid: String,
    val email: String,
    val loggedIn: Boolean,
)

/**
 * KKBOX 账号。
 *
 * 安全说明：RN 版明文存密码；Flutter 版改用安全存储（密码单独存于
 * `kkbox_pw_{id}`，不进入 JSON）。持久化 JSON key 与属性名一致：
 * id / name / email / isActive / session，session 内为 sid / licContentKey /
 * highQuality / kkid / email / loggedIn —— 均与 Kotlin 属性名一一对应。
 */
@Serializable
data class KkboxAccount(
    val id: String,
    val name: String,
    val email: String,
    val password: String,
    val session: KkboxSession? = null,
    val isActive: Boolean = false,
) {
    /**
     * 局部更新（对齐 Dart `KkboxAccount.copyWith`）。
     * 传 `null` 不会清空 [session]（`?? this.session` 回退）。
     */
    fun copyWith(
        session: KkboxSession? = null,
        isActive: Boolean? = null,
    ): KkboxAccount = KkboxAccount(
        id = id,
        name = name,
        email = email,
        password = password,
        session = session ?: this.session,
        isActive = isActive ?: this.isActive,
    )
}
