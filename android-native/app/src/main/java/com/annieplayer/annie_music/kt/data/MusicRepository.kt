package com.annieplayer.annie_music.kt.data

import com.annieplayer.annie_music.kt.core.state.MoreSettings
import com.annieplayer.annie_music.kt.data.model.Album
import com.annieplayer.annie_music.kt.data.model.AlbumSong
import com.annieplayer.annie_music.kt.data.model.Artist
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.network.anyToJsonElement
import com.annieplayer.annie_music.kt.data.network.cacheService
import com.annieplayer.annie_music.kt.data.network.json
import com.annieplayer.annie_music.kt.data.network.jsonElementToAny
import com.annieplayer.annie_music.kt.data.sources.enrichKuGouSong
import com.annieplayer.annie_music.kt.data.sources.enrichKuwoSong
import com.annieplayer.annie_music.kt.data.sources.enrichKkboxSong
import com.annieplayer.annie_music.kt.data.sources.enrichNeteaseSong
import com.annieplayer.annie_music.kt.data.sources.enrichQQSong
import com.annieplayer.annie_music.kt.data.sources.fetchKugouArtistInfo
import com.annieplayer.annie_music.kt.data.sources.fetchKugouLyric
import com.annieplayer.annie_music.kt.data.sources.fetchKugouMusicUrl
import com.annieplayer.annie_music.kt.data.sources.fetchKuwoArtistAvatar
import com.annieplayer.annie_music.kt.data.sources.fetchKuwoArtistBio
import com.annieplayer.annie_music.kt.data.sources.fetchKuwoDownloadUrl
import com.annieplayer.annie_music.kt.data.sources.fetchKuwoLyric
import com.annieplayer.annie_music.kt.data.sources.fetchKuwoPlayUrl
import com.annieplayer.annie_music.kt.data.sources.fetchNeteaseArtistAvatar
import com.annieplayer.annie_music.kt.data.sources.fetchNeteaseArtistBio
import com.annieplayer.annie_music.kt.data.sources.fetchNeteaseDownloadUrl
import com.annieplayer.annie_music.kt.data.sources.fetchNeteaseLyric
import com.annieplayer.annie_music.kt.data.sources.fetchNeteasePlayUrl
import com.annieplayer.annie_music.kt.data.sources.fetchQQArtistBio
import com.annieplayer.annie_music.kt.data.sources.fetchQQLyric
import com.annieplayer.annie_music.kt.data.sources.fetchQQMusicUrl
import com.annieplayer.annie_music.kt.data.sources.getAlbumSongs
import com.annieplayer.annie_music.kt.data.sources.getArtistAlbums
import com.annieplayer.annie_music.kt.data.sources.getArtistSongs
import com.annieplayer.annie_music.kt.data.sources.getKugouAlbumSongs
import com.annieplayer.annie_music.kt.data.sources.getKugouArtistAlbums
import com.annieplayer.annie_music.kt.data.sources.getKugouArtistSongs
import com.annieplayer.annie_music.kt.data.sources.getNeteaseAlbumSongs
import com.annieplayer.annie_music.kt.data.sources.getNeteaseArtistAlbums
import com.annieplayer.annie_music.kt.data.sources.getNeteaseArtistSongs
import com.annieplayer.annie_music.kt.data.sources.getQQAlbumSongs
import com.annieplayer.annie_music.kt.data.sources.getQQArtistAlbums
import com.annieplayer.annie_music.kt.data.sources.getQQArtistSongs
import com.annieplayer.annie_music.kt.data.sources.getQobuzAlbum
import com.annieplayer.annie_music.kt.data.sources.getQobuzArtistPage
import com.annieplayer.annie_music.kt.data.sources.getTrack
import com.annieplayer.annie_music.kt.data.sources.fetchQobuzArtistPicture
import com.annieplayer.annie_music.kt.data.sources.fetchQobuzLyrics
import com.annieplayer.annie_music.kt.data.sources.getFileUrl
import com.annieplayer.annie_music.kt.data.sources.qobuzHiresFormatId
import com.annieplayer.annie_music.kt.data.sources.qobuzQualityLabel
import com.annieplayer.annie_music.kt.data.sources.qobuzQualityFormatId
import com.annieplayer.annie_music.kt.data.sources.qobuzQualitySpec
import com.annieplayer.annie_music.kt.data.sources.qobuzTokenProvider
import com.annieplayer.annie_music.kt.data.sources.searchQobuzAlbums
import com.annieplayer.annie_music.kt.data.sources.searchQobuzArtists
import com.annieplayer.annie_music.kt.data.sources.getSongLyrics
import com.annieplayer.annie_music.kt.data.sources.searchAlbum
import com.annieplayer.annie_music.kt.data.sources.searchArtist
import com.annieplayer.annie_music.kt.data.sources.searchKugouAlbum
import com.annieplayer.annie_music.kt.data.sources.searchKugouArtist
import com.annieplayer.annie_music.kt.data.sources.searchNeteaseAlbum
import com.annieplayer.annie_music.kt.data.sources.searchNeteaseArtist
import com.annieplayer.annie_music.kt.data.sources.searchQQAlbum
import com.annieplayer.annie_music.kt.data.sources.searchQQArtist
import com.annieplayer.annie_music.kt.data.source.BuiltinSourceProvider
import com.annieplayer.annie_music.kt.data.source.LxPluginRegistry
import com.annieplayer.annie_music.kt.data.source.LxPluginSourceProvider
import kotlinx.coroutines.CancellationException

/**
 * 音乐仓库层（1:1 移植 Flutter `lib/data/music_repository.dart`，对齐 RN 版
 * `services/musicApi.ts` 的平台分发逻辑）。
 *
 * 本文件只负责「按平台分发」与「歌词缓存」，具体平台请求一律委托给同工程
 * `data/sources/` 下已移植的六个平台文件（kuwo / netease / qq / kugou / kkbox / qobuz），
 * 不重复定义任何平台实现。
 */

/** 歌词缓存 TTL（对齐 RN `LYRIC_CACHE_TTL` = 60 分钟） */
private const val _lyricCacheTtl: Long = 60 * 60 * 1000L

/** 歌词结果（对齐 Dart `LyricResult`，另带逐字与可用译文语言） */
data class LyricResult(
    val lrc: String,
    val tlyric: String?,
    val wordLyric: String? = null,
    /** 可用的译文语言（当前只有 Qobuz 官方歌词提供；其它平台为空） */
    val translateLangs: List<String> = emptyList(),
    /** 本次实际取到的译文语言（null = 未取译文） */
    val translateLang: String? = null,
)

/** 专辑曲目结果（对齐 Dart `({List<AlbumSong> songs, String pub})`） */
data class RepoAlbumSongs(
    val songs: List<AlbumSong>,
    val pub: String,
    val qualitySpec: String = "",
)

/** 歌手单曲结果（对齐 Dart `({List<Song> songs, int total})`） */
data class RepoArtistSongs(
    val songs: List<Song>,
    val total: Int,
    /** 该平台能否翻页；为 false 时详情页只显示已取回的一页，不渲染页码控件 */
    val pageable: Boolean = true,
)

private val _builtinProvider by lazy { BuiltinSourceProvider() }
private val _lxProvider by lazy { LxPluginSourceProvider() }

/** 落雪插件能接管的国内四平台；开关打开后这些平台**不再使用内置音源** */
private val _lxPlatforms = setOf(
    MusicPlatform.netease,
    MusicPlatform.kuwo,
    MusicPlatform.kugou,
    MusicPlatform.qq,
)

/**
 * 播放 URL —— **音源路由**。
 *
 * 开关开且是落雪可接管的四平台：**只用插件**（无插件/插件失败 → 直接失败并提示，不回退内置）。
 * 其余情况（开关关、或 Qobuz/KKBOX 这类插件覆盖不到的平台）走内置。
 */
suspend fun fetchPlayUrlBySong(song: Song): String = resolveUrlByProvider(song, null)

/** 下载 URL —— 路由同上 */
suspend fun fetchDownloadUrlBySong(song: Song, quality: Quality): String =
    resolveUrlByProvider(song, quality)

private suspend fun resolveUrlByProvider(song: Song, quality: Quality?): String {
    val pluginMode = LxPluginRegistry.enabled.value && song.platform in _lxPlatforms
    if (pluginMode) requireLxPlugin(song)

    // 下载：显式档位，只请求一次（下载侧有字节嗅探等自身兜底）
    if (quality != null) {
        return if (pluginMode) {
            _lxProvider.resolveDownloadUrl(song, quality)
        } else {
            _builtinProvider.resolveDownloadUrl(song, quality)
        }
    }

    // 播放：先按「歌实际有的最高档」起跳 —— 选了无损但歌只有 320k 时**直接要 320k**，
    // 不发注定失败的请求（否则拿到坏链 → 播放器报错 → 自动跳下一首）；再逐级往下兜底。
    val configured = MoreSettings.qualityFor(song.platform)
    val tiers: List<Quality?> = if (configured == null) {
        listOf(null)
    } else {
        val start = if (degradesGracefully(song.platform)) {
            configured
        } else {
            bestAvailableAtOrBelow(song, configured) ?: configured
        }
        val from = _playQualityLadder.indexOf(start).takeIf { it >= 0 }
            ?: _playQualityLadder.lastIndex
        _playQualityLadder.subList(from, _playQualityLadder.size)
    }
    var lastError: Throwable? = null
    for (tier in tiers) {
        try {
            return playOnce(song, pluginMode, tier)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = e
        }
    }
    // 配置档位全失败 → 退回自动（自动几乎不会失败）
    if (configured != null) {
        try {
            return playOnce(song, pluginMode, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = e
        }
    }
    val err = lastError ?: IllegalStateException("解析失败")
    if (pluginMode) LxPluginRegistry.postNotice("落雪插件解析失败：${err.message ?: "未知错误"}")
    throw err
}

/** 插件模式但该平台没有可用插件：直接失败并提示（配置问题，不参与降级重试） */
private fun requireLxPlugin(song: Song) {
    if (LxPluginRegistry.pluginFor(song.platform) != null) return
    val active = LxPluginRegistry.activePlugin()
    val message = if (active != null) {
        "「${active.name}」不支持${song.platform.label}，内置音源已停用"
    } else {
        "未添加可用的落雪插件，内置音源已停用"
    }
    LxPluginRegistry.postNotice(message)
    throw IllegalStateException(message)
}

/** 单次播放取链（插件或内置；[quality] 为 null = 自动） */
private suspend fun playOnce(song: Song, pluginMode: Boolean, quality: Quality?): String =
    if (pluginMode) {
        _lxProvider.resolvePlayUrl(song, quality)
    } else {
        _builtinProvider.resolvePlayUrl(song, quality)
    }

/**
 * 引擎侧预取入口：把 Media3 队列项还原成 [Song] 后走同一条音源路由（插件/内置）。
 *
 * 队列项现在带 `qualities`（该歌真实可用档位），用于把「选了无损但歌只有 320k」直接选成 320k；
 * 老队列项没有该字段时退回「假装有 320k」的旧行为。
 */
suspend fun fetchPlayUrlForBackend(item: Map<String, String>): String {
    val platform = MusicPlatform.entries.firstOrNull { it.name == item["platform"] } ?: MusicPlatform.kuwo
    val qualities = item["qualities"].orEmpty()
        .split(',')
        .mapNotNull { Quality.fromValue(it.trim().ifEmpty { null }) }
        .distinct()
    return fetchPlayUrlBySong(
        Song(
            id = item["id"].orEmpty(),
            title = item["title"].orEmpty(),
            artist = item["artist"].orEmpty(),
            cover = item["cover"].orEmpty(),
            album = item["album"],
            duration = 0,
            platform = platform,
            qualities = qualities.ifEmpty { listOf(Quality.k320k) },
        ),
    )
}

/** 该平台播放请求档位 = 设置里该平台的「默认播放音质」；未配置（自动）当 320k。缓存 key 随之一致变化 */
internal fun playRequestQuality(platformName: String): Quality =
    MusicPlatform.entries.firstOrNull { it.name == platformName }
        ?.let { MoreSettings.qualityFor(it) }
        ?: Quality.k320k

/**
 * 该平台「默认播放音质」可选档位（高→低，不含「自动」；**KKBOX 暂不提供**）。
 *
 * 网易 `hr`、酷狗 `hash_high`（签名接口）都有真 Hi-Res；QQ（`hires` 被接口映射成 `lossless`）
 * 与酷我实测**无损封顶**；Qobuz 走自己的三档（无 128k）。
 */
fun playQualityTiersOf(platform: MusicPlatform): List<Quality> = when (platform) {
    MusicPlatform.netease, MusicPlatform.kugou ->
        listOf(Quality.hires, Quality.flac, Quality.k320k, Quality.k128k)
    MusicPlatform.qobuz -> listOf(Quality.hires, Quality.flac, Quality.k320k)
    MusicPlatform.qq, MusicPlatform.kuwo ->
        listOf(Quality.flac, Quality.k320k, Quality.k128k)
    MusicPlatform.kkbox -> emptyList()
}

/** 档位显示名：Qobuz 用它自己的（`MP3 320` / `FLAC 16bit…`），其余用通用 [Quality.label] */
fun playQualityLabelOf(platform: MusicPlatform, quality: Quality): String =
    if (platform == MusicPlatform.qobuz) qobuzQualityLabel(quality) else quality.label

/**
 * 「自动」档显示名 —— 顺带写出它实际取到的档，避免看着像"没档位"。
 *
 * 国内四平台自动 = 320k（`builtinPlayUrl` 既有规则）；**Qobuz 自动 = FLAC Hires Max**（`qobuzHiresFormatId`）。
 */
fun playQualityAutoLabelOf(platform: MusicPlatform): String =
    if (platform == MusicPlatform.qobuz) "自动（FLAC Hires Max）" else "自动（320k）"

/**
 * 内置播放直链（netease 走 c.wwwweb.top，qq/kugou 走长青解析，其余走 kuwo）。
 *
 * [quality] 为 null = 自动（各平台既有取档规则，旧行为）；指定档位只请求该档一次 ——
 * **降级统一由仓库层做**（见 [resolveUrlByProvider]），插件/内置同一套，避免双重降级。
 */
internal suspend fun builtinPlayUrl(song: Song, quality: Quality? = null): String {
    if (quality != null) return _builtinPlayUrlAt(song, quality)
    return when (song.platform) {
        MusicPlatform.netease -> fetchNeteasePlayUrl(song.id)
        MusicPlatform.qq -> fetchQQMusicUrl(
            song.id.replaceFirst(Regex("^qq:"), ""),
            if (song.qualities.contains(Quality.k320k)) "320k" else "128k",
        )
        MusicPlatform.kugou -> fetchKugouMusicUrl(
            song.id.replaceFirst(Regex("^kugou:"), ""),
            if (song.qualities.contains(Quality.k320k)) "320k" else "128k",
        )
        MusicPlatform.qobuz -> fetchQobuzUrl(song)
        MusicPlatform.kuwo, MusicPlatform.kkbox -> fetchKuwoPlayUrl(song.id)
    }
}

/** 播放档位由高到低（降级梯子；仓库层插件/内置共用） */
private val _playQualityLadder =
    listOf(Quality.hires, Quality.flac, Quality.k320k, Quality.k128k)

/**
 * 该平台内置取链在「要的档位不可用」时是否**优雅降级**（返回它能给的最高档、不报错）。
 *
 * 酷狗实测如此：`level=hires` 遇到无 Hi-Res 的歌给 `quflac`。这类平台**不该用列表元数据预先夹档** ——
 * 否则歌单链路拿不到 `hash_high` 的酷狗歌永远用不上 Hi-Res。其余平台会报错/给坏链，必须靠夹档 + 降级梯子。
 */
private fun degradesGracefully(platform: MusicPlatform): Boolean =
    platform == MusicPlatform.kugou

/** 歌曲已知档位里「不超过 [want] 的最高档」；qualities 为空（未知）返回 null */
private fun bestAvailableAtOrBelow(song: Song, want: Quality): Quality? {    val wantRank = _playQualityLadder.indexOf(want)
    if (wantRank < 0) return null
    return song.qualities
        .mapNotNull { q -> _playQualityLadder.indexOf(q).takeIf { it >= 0 }?.let { it to q } }
        .filter { it.first >= wantRank }
        .minByOrNull { it.first }
        ?.second
}

/** 按指定档位请求（各平台各自的接口） */
private suspend fun _builtinPlayUrlAt(song: Song, quality: Quality): String = when (song.platform) {
    MusicPlatform.netease -> fetchNeteasePlayUrl(song.id, quality)
    MusicPlatform.qq -> fetchQQMusicUrl(song.id.replaceFirst(Regex("^qq:"), ""), quality.value)
    MusicPlatform.kugou -> fetchKugouMusicUrl(song.id.replaceFirst(Regex("^kugou:"), ""), quality.value)
    MusicPlatform.qobuz -> fetchQobuzUrl(song, quality)
    MusicPlatform.kuwo, MusicPlatform.kkbox -> fetchKuwoPlayUrl(song.id, quality)
}

/** 内置下载直链 */
internal suspend fun builtinDownloadUrl(song: Song, quality: Quality): String = when (song.platform) {
    MusicPlatform.netease -> fetchNeteaseDownloadUrl(song.id, quality)
    MusicPlatform.qq -> fetchQQMusicUrl(
        song.id.replaceFirst(Regex("^qq:"), ""),
        quality.value,
    )
    MusicPlatform.kugou -> fetchKugouMusicUrl(
        song.id.replaceFirst(Regex("^kugou:"), ""),
        quality.value,
    )
    MusicPlatform.qobuz -> fetchQobuzUrl(song, quality)
    MusicPlatform.kuwo, MusicPlatform.kkbox -> fetchKuwoDownloadUrl(song.id, quality)
}

/** Qobuz 直链（需 token；[quality] 为空表示播放，取最高档） */
private suspend fun fetchQobuzUrl(song: Song, quality: Quality? = null): String {
    val token = qobuzTokenProvider().orEmpty()
    if (token.isEmpty()) throw IllegalStateException("请先在菜单里配置 Qobuz Token")
    val formatId = if (quality == null) qobuzHiresFormatId else qobuzQualityFormatId(quality)
    return getFileUrl(song.id.removePrefix("qobuz:"), token, qualityId = formatId).url
}

/** 专辑搜索（按平台分发） */
suspend fun searchAlbumByPlatform(
    platform: MusicPlatform,
    query: String,
    page: Int = 1,
    pageSize: Int = 30,
): List<Album> = when (platform) {
    MusicPlatform.netease -> searchNeteaseAlbum(query, page = page, pageSize = pageSize)
    MusicPlatform.qq -> searchQQAlbum(query, page = page, pageSize = pageSize)
    MusicPlatform.kugou -> searchKugouAlbum(query, page = page, pageSize = pageSize)
    MusicPlatform.qobuz -> searchQobuzAlbums(
        query,
        limit = pageSize,
        offset = (page - 1) * pageSize,
    )
    MusicPlatform.kuwo, MusicPlatform.kkbox ->
        searchAlbum(query, page = page, pageSize = pageSize)
}

/** 专辑曲目（按平台分发） */
suspend fun fetchAlbumSongsByPlatform(
    platform: MusicPlatform,
    albumId: String,
): RepoAlbumSongs = when (platform) {
    MusicPlatform.netease -> getNeteaseAlbumSongs(albumId).let { RepoAlbumSongs(it.songs, it.pub) }
    MusicPlatform.qq -> getQQAlbumSongs(albumId).let { RepoAlbumSongs(it.songs, it.pub) }
    MusicPlatform.kugou -> getKugouAlbumSongs(albumId).let { RepoAlbumSongs(it.songs, it.pub) }
    MusicPlatform.qobuz -> {
        val a = getQobuzAlbum(albumId, "")
        RepoAlbumSongs(
            a.tracks.map { t ->
                AlbumSong(
                    id = "qobuz:${t.id}",
                    name = t.title,
                    artistName = t.artist,
                    duration = t.duration,
                    track = if (t.trackNumber > 0) t.trackNumber else null,
                    discNumber = t.discNumber,
                    qualities = if (a.hires) {
                        listOf(Quality.hires, Quality.flac, Quality.k320k)
                    } else {
                        listOf(Quality.flac, Quality.k320k)
                    },
                    artistNames = t.artistNames,
                    artistIds = t.artistIds,
                )
            },
            if (a.year > 0) a.year.toString() else "",
            qobuzQualitySpec(a),
        )
    }
    MusicPlatform.kuwo, MusicPlatform.kkbox -> {
        val r = getAlbumSongs(albumId)
        RepoAlbumSongs(r.songs, r.pub)
    }
}

/**
 * 歌手头像（按平台取；用于「从播放页跳歌手详情」时补头像）。
 *
 * - qq：直接用 singerMID 拼头像地址，无需请求
 * - kugou：`singer/info`（缓存在 `v2` key，失败不缓存）
 * - netease：`/api/v1/artist/{id}`
 * - kuwo：兜底走歌手搜索
 */
suspend fun fetchArtistAvatarByPlatform(
    platform: MusicPlatform,
    artistId: String,
    name: String = "",
): String = when (platform) {
    MusicPlatform.qq ->
        if (artistId.isEmpty()) {
            ""
        } else {
            "https://y.gtimg.cn/music/photo_new/T001R300x300M000$artistId.jpg"
        }
    MusicPlatform.kugou -> fetchKugouArtistInfo(artistId).avatar
    MusicPlatform.netease -> fetchNeteaseArtistAvatar(artistId)
    MusicPlatform.kuwo -> fetchKuwoArtistAvatar(name)
    MusicPlatform.qobuz -> fetchQobuzArtistPicture(artistId)
    MusicPlatform.kkbox -> ""
}

/**
 * 歌手简介（按平台分发）。
 *
 * 各平台都能从「歌手详情 / 歌手单曲 / 歌手搜索」这类已有接口里拿到；[name] 仅酷我需要
 * （它的简介只在经典歌手搜索的 `desc` 里）。
 */
suspend fun fetchArtistBioByPlatform(
    platform: MusicPlatform,
    artistId: String,
    name: String = "",
): String = when (platform) {
    MusicPlatform.netease -> fetchNeteaseArtistBio(artistId)
    MusicPlatform.qq -> fetchQQArtistBio(artistId)
    MusicPlatform.kugou -> fetchKugouArtistInfo(artistId).intro
    MusicPlatform.kuwo -> fetchKuwoArtistBio(name)
    MusicPlatform.qobuz, MusicPlatform.kkbox -> ""
}

/**
 * 解析歌手（用于「跳到歌手详情」）：
 *
 * - 歌曲自带歌手 id 时直接返回（无网络、秒开）
 * - 否则按名字搜索一次，精确同名优先
 */
suspend fun resolveArtist(
    platform: MusicPlatform,
    name: String,
    knownId: String? = null,
): Artist? {
    val q = name.trim()
    if (q.isEmpty()) return null
    if (!knownId.isNullOrEmpty()) {
        return Artist(id = knownId, name = q, picture = "")
    }
    return try {
        val list = searchArtistByPlatform(platform, q)
        if (list.isEmpty()) return null
        for (a in list) {
            if (a.name == q) return a
        }
        list.first()
    } catch (_: Throwable) {
        null
    }
}

/** 歌手搜索（按平台分发） */
suspend fun searchArtistByPlatform(
    platform: MusicPlatform,
    query: String,
): List<Artist> = when (platform) {
    MusicPlatform.netease -> searchNeteaseArtist(query)
    MusicPlatform.qq -> searchQQArtist(query)
    MusicPlatform.kugou -> searchKugouArtist(query)
    MusicPlatform.qobuz -> searchQobuzArtists(query)
    MusicPlatform.kuwo, MusicPlatform.kkbox -> searchArtist(query)
}

/** 歌手单曲（按平台分发） */
suspend fun fetchArtistSongsByPlatform(
    platform: MusicPlatform,
    artistId: String,
    page: Int,
): RepoArtistSongs = when (platform) {
    MusicPlatform.netease -> {
        val p = getNeteaseArtistSongs(artistId, page)
        RepoArtistSongs(p.songs, p.total)
    }
    MusicPlatform.qq -> {
        val p = getQQArtistSongs(artistId, page)
        RepoArtistSongs(p.songs, p.total)
    }
    MusicPlatform.kugou -> {
        val p = getKugouArtistSongs(artistId, page)
        RepoArtistSongs(p.songs, p.total)
    }
    MusicPlatform.qobuz -> {
        val p = getQobuzArtistPage(artistId)
        RepoArtistSongs(p.songs, p.songs.size, pageable = false)
    }
    MusicPlatform.kuwo, MusicPlatform.kkbox -> {
        val r = getArtistSongs(artistId, page)
        RepoArtistSongs(r.songs, r.total)
    }
}

/**
 * 歌手专辑（按平台分发）。
 *
 * [artistName] 目前仅 QQ 需要（官方歌手专辑接口已失效，暂用专辑搜索兜底）。
 */
suspend fun fetchArtistAlbumsByPlatform(
    platform: MusicPlatform,
    artistId: String,
    artistName: String = "",
): List<Album> = when (platform) {
    MusicPlatform.netease -> getNeteaseArtistAlbums(artistId)
    MusicPlatform.qq -> getQQArtistAlbums(artistId, artistName)
    MusicPlatform.kugou -> getKugouArtistAlbums(artistId)
    MusicPlatform.qobuz -> getQobuzArtistPage(artistId).albums
    MusicPlatform.kuwo, MusicPlatform.kkbox -> getArtistAlbums(artistId)
}

/**
 * 歌词（按 id 前缀分发）。
 *
 * [kkboxSid] 为 KKBOX 活跃账号会话 sid：仅当 platform 为 kkbox 时使用，
 * 有 sid 调 `getSongLyrics` 拉真实歌词，无则返回占位。
 */
suspend fun fetchLyric(
    song: Song,
    kkboxSid: String? = null,
    autoPickTranslate: Boolean = false,
): LyricResult {
    // KKBOX 歌词依赖会话，不进入通用歌词缓存（对齐 RN AudioContext 单独处理）
    if (song.platform == MusicPlatform.kkbox) {
        if (kkboxSid.isNullOrEmpty()) {
            return LyricResult(lrc = "[00:00.00]暂无歌词", tlyric = null)
        }
        val rawId = song.id.replaceFirst(Regex("^kkbox:"), "")
        val r = getSongLyrics(kkboxSid, rawId)
        return LyricResult(lrc = r.lrc, tlyric = r.tlyric)
    }

    // 译文语言要进 key：同一首歌换语言是不同结果，共用 key 会一直命中旧译文；
    // autoPickTranslate 也进 key，否则「自动取第一个译文」的缓存会被「只要原文」的下载分支命中。
    val cacheKey = "lyric:v5:${song.id}:${song.lyricTranslateLang.orEmpty()}:" +
        (if (autoPickTranslate) "auto" else "raw")
    val cached = cacheService.get<String>(cacheKey)
    if (cached != null) {
        try {
            val parsed = jsonElementToAny(json.parseToJsonElement(cached))
            if (parsed is Map<*, *> && parsed["lrc"] is String) {
                return LyricResult(
                    lrc = parsed["lrc"] as String,
                    tlyric = parsed["tlyric"] as String?,
                    wordLyric = parsed["wordLyric"] as String?,
                    translateLangs = (parsed["translateLangs"] as? List<*>)
                        ?.mapNotNull { it as? String }
                        .orEmpty(),
                    translateLang = parsed["translateLang"] as String?,
                )
            }
        } catch (_: Throwable) {
            // 缓存内容损坏：忽略并重新拉取
        }
    }

    return try {
        val result: LyricResult = if (song.id.startsWith("netease:")) {
            val r = fetchNeteaseLyric(song.id)
            LyricResult(lrc = r.lrc, tlyric = r.tlyric, wordLyric = r.wordLyric)
        } else if (song.id.startsWith("qq:")) {
            val r = fetchQQLyric(song.id)
            LyricResult(lrc = r.lrc, tlyric = r.tlyric, wordLyric = r.wordLyric)
        } else if (song.id.startsWith("kugou:")) {
            val r = fetchKugouLyric(song.id, "${song.title} ${song.artist}")
            LyricResult(lrc = r.lrc, tlyric = r.tlyric, wordLyric = r.wordLyric)
        } else if (song.id.startsWith("qobuz:")) {
            val token = qobuzTokenProvider().orEmpty()
            if (token.isEmpty()) {
                val r = fetchKuwoLyric(song.id, song.title, song.artist)
                LyricResult(lrc = r.lrc, tlyric = r.tlyric, wordLyric = r.wordLyric)
            } else {
                val r = fetchQobuzLyrics(
                    song.id.removePrefix("qobuz:"),
                    token,
                    song.lyricTranslateLang,
                    autoPickTranslate,
                )
                LyricResult(
                    lrc = r.lrc,
                    tlyric = r.tlyric,
                    translateLangs = r.translateLangs,
                    translateLang = r.translateLang,
                )
            }
        } else {
            val r = fetchKuwoLyric(song.id, song.title, song.artist)
            LyricResult(lrc = r.lrc, tlyric = r.tlyric, wordLyric = r.wordLyric)
        }
        cacheService.set(
            cacheKey,
            anyToJsonElement(
                mapOf<String, Any?>(
                    "lrc" to result.lrc,
                    "tlyric" to result.tlyric,
                    "wordLyric" to result.wordLyric,
                    "translateLangs" to result.translateLangs,
                    "translateLang" to result.translateLang,
                ),
            ).toString(),
            _lyricCacheTtl,
        )
        result
    } catch (_: Throwable) {
        // 拉取失败：缓存并返回占位，避免 60 分钟内反复请求同一首（对齐 RN 行为）
        val fallback = LyricResult(lrc = "[00:00.00]暂无歌词", tlyric = null)
        cacheService.set(
            cacheKey,
            anyToJsonElement(
                mapOf<String, Any?>("lrc" to fallback.lrc, "tlyric" to fallback.tlyric),
            ).toString(),
            _lyricCacheTtl,
        )
        fallback
    }
}

/**
 * 解析「下载要写进文件」的歌词。
 *
 * 非 Qobuz 歌直接返回常规歌词；Qobuz 歌在 [writeTranslation] 打开时用 [pickTranslate]
 * 让用户选译文语言（批量下载可传 `{ it.firstOrNull() }` 直接取接口返回的第一个可用语言），
 * 再按该语言取译文；用户取消则只写原文。
 */
suspend fun resolveDownloadLyric(
    song: Song,
    writeTranslation: Boolean,
    pickTranslate: suspend (List<String>) -> String?,
): LyricResult {
    if (song.platform != MusicPlatform.qobuz) return fetchLyric(song)
    // 下载分支只要原文：清掉播放页选中的语言，避免把「当前显示的语言」也写进文件
    val base = fetchLyric(song.copy(lyricTranslateLang = null))
    if (!writeTranslation || base.translateLangs.isEmpty()) return base
    val chosen = pickTranslate(base.translateLangs) ?: return base
    return base.copy(tlyric = fetchLyric(song.copy(lyricTranslateLang = chosen)).tlyric)
}

/** 补充 metadata（kuwo/kugou/qq 下载前 enrich） */
suspend fun enrichSong(song: Song): Map<String, Any?> = when (song.platform) {
    MusicPlatform.kuwo -> {
        val r = enrichKuwoSong(song.id, song.albumId ?: "")
        mapOf<String, Any?>(
            "trackNumber" to r.trackNumber,
            "releaseDate" to r.releaseDate,
            "albumArtist" to r.albumArtist,
        )
    }
    MusicPlatform.kugou -> {
        val r = enrichKuGouSong(song.id)
        mapOf<String, Any?>(
            "trackNumber" to r.trackNumber,
            "discNumber" to r.discNumber,
            "releaseDate" to r.releaseDate,
            "albumArtist" to r.albumArtist,
        )
    }
    MusicPlatform.qq -> {
        val r = enrichQQSong(song.id, song.albumId ?: "")
        mapOf<String, Any?>(
            "trackNumber" to r.trackNumber,
            "discNumber" to r.discNumber,
            "releaseDate" to r.releaseDate,
            "albumArtist" to r.albumArtist,
        )
    }
    MusicPlatform.qobuz -> {
        val t = getTrack(song.id.removePrefix("qobuz:"), qobuzTokenProvider().orEmpty())
        mapOf<String, Any?>(
            "trackNumber" to t.trackNumber.takeIf { it > 0 }?.toString(),
            "discNumber" to t.discNumber?.toString(),
            "releaseDate" to (t.releaseDateOriginal ?: t.year.takeIf { it > 0 }?.toString()),
            "albumArtist" to t.albumArtist,
        )
    }
    MusicPlatform.netease -> {
        val r = enrichNeteaseSong(song.id, song.albumId ?: "")
        mapOf<String, Any?>(
            "trackNumber" to r.trackNumber,
            "discNumber" to r.discNumber,
            "releaseDate" to r.releaseDate,
            "albumArtist" to r.albumArtist,
        )
    }
    MusicPlatform.kkbox -> {
        val r = enrichKkboxSong(song.id, song.albumId ?: "")
        mapOf<String, Any?>(
            "trackNumber" to r.trackNumber,
            "discNumber" to r.discNumber,
            "releaseDate" to r.releaseDate,
            "albumArtist" to r.albumArtist,
        )
    }
}
