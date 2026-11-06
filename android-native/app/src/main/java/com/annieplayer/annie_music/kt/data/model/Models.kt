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
    apple("Apple Music"),

    /**
     * **本地文件**（本地曲库）。
     *
     * 与云端平台的区别：没有 id/直链解析，`Song.playUrl` 直接是 `file://` 绝对路径，
     * 歌词来自同名 `.lrc` / 内嵌 tag，音质档位不由接口决定（由文件本身决定，不参与请求）。
     * 所有按平台分发的 when 分支都必须显式处理它：播放/歌词走本地路径，搜索/下载/歌手
     * 等云端能力一律返回空 —— 不能让本地的歌泄漏进云端请求。
     */
    local("本地"),
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

    @SerialName("aac256")
    aac256("aac256", "AAC 256kbps", "aac256"),

    @SerialName("alac")
    alac("alac", "ALAC 无损", "alac"),

    @SerialName("atmos")
    atmos("atmos", "Dolby Atmos", "atmos"),
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
    /** 逐字歌词（统一格式：`[mm:ss.mmm]<绝对起始ms,时长ms>词…`；无逐字数据时为 null） */
    val wordLyric: String? = null,
    /** 可用的歌词译文语言（Qobuz 官方接口的 translation_langs；其它平台为空） */
    val lyricTranslateLangs: List<String> = emptyList(),
    /** 当前选中的译文语言（null = 未选；仅 Qobuz 官方歌词用） */
    val lyricTranslateLang: String? = null,
    val qualities: List<Quality> = emptyList(),
    /** 各档位对应的文件大小（字节）；平台没给就不含该键。仅用于下载音质面板展示 */
    val qualitySizes: Map<Quality, Long> = emptyMap(),
    val albumArtist: String? = null,
    val trackNumber: String? = null,
    val discNumber: String? = null,
    val releaseDate: String? = null,
    /** 歌手名列表（多歌手逐个列出；单歌手为 1 个元素） */
    val artistNames: List<String> = emptyList(),
    /** 歌手 id 列表（与 [artistNames] 按下标对应；平台拿不到时为空列表） */
    val artistIds: List<String> = emptyList(),
    /** 作曲 / 词曲作者（有则写 tag） */
    val composer: String? = null,
    /** 流派（有则写 tag） */
    val genre: String? = null,
    /** ISRC（有则写 tag） */
    val isrc: String? = null,
    /**
     * 版权声明（写标签时进 `cprt` / `TCOP` / `COPYRIGHT`）。
     *
     * 只有部分平台在**歌曲级**接口给这个字段；Apple 的是**专辑级**的
     * （`albums/{id}.attributes.copyright`），下载时按专辑缓存取一次
     * （见 `fetchAppleAlbumCopyright`）。
     */
    val copyright: String? = null,
    /**
     * 版本说明（Qobuz 的 `version`，如 "Remastered 2011"；其它平台为 null）。
     * 写标签时进 comment —— 本地库扫描把 `COMMENT` 读回 `LocalTrackMeta.version`。
     */
    val version: String? = null,
    /**
     * QQ 的 `file.media_mid`（**媒体 mid，与 songmid 不是一回事**，例如《晴天》是
     * `songmid=0039MnYb0qxYhV` / `media_mid=003Qui1q2u1Zho`）。
     *
     * 只有落雪插件直接读它（`info.musicInfo.strMediaMid`），内置直链走 songmid；其它平台为 null。
     */
    val mediaMid: String? = null,
) {
    /**
     * 局部更新（对齐 Dart `Song.copyWith`：仅这几个字段可改，其余原样拷贝）。
     *
     * 注意：与 Dart 语义一致，传 `null` **不会**清空 `lyric` / `tlyric` / `wordLyric`，
     * 因为原实现使用 `?? this.xxx` 回退（非幂等 setter）。
     */
    fun copyWith(
        playUrl: String? = null,
        lyric: String? = null,
        tlyric: String? = null,
        wordLyric: String? = null,
        qualities: List<Quality>? = null,
        qualitySizes: Map<Quality, Long>? = null,
        lyricTranslateLangs: List<String>? = null,
        lyricTranslateLang: String? = null,
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
        wordLyric = wordLyric ?: this.wordLyric,
        qualities = qualities ?: this.qualities,
        qualitySizes = qualitySizes ?: this.qualitySizes,
        lyricTranslateLangs = lyricTranslateLangs ?: this.lyricTranslateLangs,
        lyricTranslateLang = lyricTranslateLang ?: this.lyricTranslateLang,
        albumArtist = albumArtist,
        trackNumber = trackNumber,
        discNumber = discNumber,
        releaseDate = releaseDate,
        artistNames = artistNames,
        artistIds = artistIds,
        composer = composer,
        genre = genre,
        isrc = isrc,
        mediaMid = mediaMid,
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

/** 专辑曲目 + 发行时间（[pub] 取接口给的发行日期，可能为空串） */
data class AlbumSongsPage(
    val songs: List<AlbumSong>,
    val pub: String = "",
)

/** 歌手单曲每页条数：各源按它请求、详情页按它算页码，两侧必须一致 */
const val ArtistSongsPageSize = 20

/** 歌手单曲的一页（[total] 是接口返回的真实曲目总数，非本页条数） */
data class ArtistSongsPage(
    val songs: List<Song>,
    val total: Int,
)

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
    val discNumber: Int? = null,
    val albumArtist: String? = null,
    val qualities: List<Quality> = emptyList(),
    /** 各档位对应的文件大小（字节）；平台没给就不含该键 */
    val qualitySizes: Map<Quality, Long> = emptyMap(),
    /** 歌手名列表（多歌手逐个列出） */
    val artistNames: List<String> = emptyList(),
    /** 歌手 id 列表（与 [artistNames] 按下标对应；平台拿不到时为空） */
    val artistIds: List<String> = emptyList(),
    /** 作曲 / 词曲作者 */
    val composer: String? = null,
    /** 流派 */
    val genre: String? = null,
    /** ISRC */
    val isrc: String? = null,
    /** QQ 的 `file.media_mid`（见 [Song.mediaMid]）；其它平台为 null */
    val mediaMid: String? = null,
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
    val releaseDateOriginal: String? = null,
    val isrc: String? = null,
    val version: String? = null,
    val copyright: String? = null,
    /** 作曲 */
    val composer: String? = null,
    /** 流派（专辑 genre 回退） */
    val genre: String? = null,
    /** 多歌手名（performers 逗号串拆分；单歌手时取 performer.name） */
    val artistNames: List<String> = emptyList(),
    /** 与 [artistNames] 下标对应；仅单歌手时拿得到 performer.id */
    val artistIds: List<String> = emptyList(),
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
    val maxBitDepth: Int? = null,
    val maxSamplingRateKhz: Double? = null,
    val hires: Boolean = false,
    val trackCount: Int = 0,
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

/**
 * Apple Music Wrapper 地址（用户自填）。
 *
 * 服务是 WorldObservationLog/wrapper 那一族（本机 wrapper-lite 同构），接口形状固定：
 *
 * ```
 * GET /status                                → data.ready / data.regions
 * GET /m3u8?adamId=                          → data.m3u8
 * GET /key?adamId=&uri=                      → data.{contentKey,ctx,state,rcx,…}
 * GET /lyrics?adamId=&language=&syllable=1   → data.lyrics（TTML）
 * ```
 *
 * [url] 原样保存用户输入（可带 userinfo，如 `https://<token>@wm.wol.moe`）——
 * 认证按 HTTP Basic 发，解析与请求头构造见 `AppleWrapperUrl` / `AppleWrapperApi`。
 */
@Serializable
data class AppleWrapper(
    val id: String,
    val url: String,
    /** 唯一「使用中」；同一时刻只有一条 active */
    val isActive: Boolean = false,
)

/**
 * 正在播放音频的格式信息（播放页胶囊展示，由引擎 ffprobe 探测后经事件流下发）。
 *
 * 无损才有 [bitDepth]（mp3/aac 等有损格式取不到，保持 0），此时改用 [bitrate] 展示。
 */
data class AudioInfo(
    /** 编码（`FLAC` / `MP3` / `AAC` / `OPUS` …；未探测到为空串） */
    val codec: String = "",
    /** 位深（bit，如 16 / 24；未知为 0） */
    val bitDepth: Int = 0,
    /** 采样率（Hz，如 44100 / 96000；未知为 0） */
    val sampleRate: Int = 0,
    /** 比特率（bit/s，如 320000；未知为 0）。无位深的有损格式用它顶替展示 */
    val bitrate: Int = 0,
    /** ReplayGain 曲目增益（dB，来自标签；无标签为 0）。调音模式的响度归一化据此工作 */
    val replayGainDb: Float = 0f,
) {
    /** 是否无有效信息（UI 据此隐藏胶囊） */
    val isEmpty: Boolean get() = codec.isEmpty() && sampleRate == 0
}
