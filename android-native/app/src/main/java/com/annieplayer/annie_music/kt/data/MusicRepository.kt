package com.annieplayer.annie_music.kt.data

import com.annieplayer.annie_music.kt.core.state.MoreSettings
import com.annieplayer.annie_music.kt.core.util.effectivePlayQuality
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
import com.annieplayer.annie_music.kt.data.sources.apple.fetchAppleArtistAvatar
import com.annieplayer.annie_music.kt.data.sources.apple.fetchAppleArtistBio
import com.annieplayer.annie_music.kt.data.sources.apple.getAppleAlbumSongs
import com.annieplayer.annie_music.kt.data.sources.apple.getAppleArtistAlbums
import com.annieplayer.annie_music.kt.data.sources.apple.getAppleArtistSongs
import com.annieplayer.annie_music.kt.data.sources.apple.searchAppleAlbums
import com.annieplayer.annie_music.kt.data.sources.apple.searchAppleArtists
import com.annieplayer.annie_music.kt.data.sources.apple.searchAppleMusic
import com.annieplayer.annie_music.kt.data.sources.kkbox.enrichKkboxSong
import com.annieplayer.annie_music.kt.data.sources.kkbox.fetchKkboxPlayUrl
import com.annieplayer.annie_music.kt.data.sources.kkbox.getSongLyrics
import com.annieplayer.annie_music.kt.data.sources.kugou.enrichKuGouSong
import com.annieplayer.annie_music.kt.data.sources.kugou.fetchKugouArtistInfo
import com.annieplayer.annie_music.kt.data.sources.kugou.getKugouAlbumSongs
import com.annieplayer.annie_music.kt.data.sources.kugou.getKugouArtistAlbums
import com.annieplayer.annie_music.kt.data.sources.kugou.getKugouArtistSongs
import com.annieplayer.annie_music.kt.data.sources.kugou.searchKugouAlbum
import com.annieplayer.annie_music.kt.data.sources.kugou.searchKugouArtist
import com.annieplayer.annie_music.kt.data.sources.kuwo.enrichKuwoSong
import com.annieplayer.annie_music.kt.data.sources.kuwo.fetchKuwoArtistAvatar
import com.annieplayer.annie_music.kt.data.sources.kuwo.fetchKuwoArtistBio
import com.annieplayer.annie_music.kt.data.sources.kuwo.getAlbumSongs
import com.annieplayer.annie_music.kt.data.sources.kuwo.getArtistAlbums
import com.annieplayer.annie_music.kt.data.sources.kuwo.getArtistSongs
import com.annieplayer.annie_music.kt.data.sources.kuwo.searchAlbum
import com.annieplayer.annie_music.kt.data.sources.kuwo.searchArtist
import com.annieplayer.annie_music.kt.data.sources.lyric.LyricRequest
import com.annieplayer.annie_music.kt.data.sources.lyric.LyricSources
import com.annieplayer.annie_music.kt.data.sources.lyric.NO_LYRIC
import com.annieplayer.annie_music.kt.data.sources.netease.enrichNeteaseSong
import com.annieplayer.annie_music.kt.data.sources.netease.fetchNeteaseArtistAvatar
import com.annieplayer.annie_music.kt.data.sources.netease.fetchNeteaseArtistBio
import com.annieplayer.annie_music.kt.data.sources.netease.getNeteaseAlbumSongs
import com.annieplayer.annie_music.kt.data.sources.netease.getNeteaseArtistAlbums
import com.annieplayer.annie_music.kt.data.sources.netease.getNeteaseArtistSongs
import com.annieplayer.annie_music.kt.data.sources.netease.searchNeteaseAlbum
import com.annieplayer.annie_music.kt.data.sources.netease.searchNeteaseArtist
import com.annieplayer.annie_music.kt.data.sources.qq.enrichQQSong
import com.annieplayer.annie_music.kt.data.sources.qq.fetchQQArtistBio
import com.annieplayer.annie_music.kt.data.sources.qq.getQQAlbumSongs
import com.annieplayer.annie_music.kt.data.sources.qq.getQQArtistAlbums
import com.annieplayer.annie_music.kt.data.sources.qq.getQQArtistSongs
import com.annieplayer.annie_music.kt.data.sources.qq.searchQQAlbum
import com.annieplayer.annie_music.kt.data.sources.qq.searchQQArtist
import com.annieplayer.annie_music.kt.data.sources.qobuz.fetchQobuzArtistPicture
import com.annieplayer.annie_music.kt.data.sources.qobuz.fetchQobuzLyrics
import com.annieplayer.annie_music.kt.data.sources.qobuz.getFileUrl
import com.annieplayer.annie_music.kt.data.sources.qobuz.getQobuzAlbum
import com.annieplayer.annie_music.kt.data.sources.qobuz.getQobuzArtistPage
import com.annieplayer.annie_music.kt.data.sources.qobuz.getTrack
import com.annieplayer.annie_music.kt.data.sources.qobuz.qobuzHiresFormatId
import com.annieplayer.annie_music.kt.data.sources.qobuz.qobuzQualityFormatId
import com.annieplayer.annie_music.kt.data.sources.qobuz.qobuzQualityLabel
import com.annieplayer.annie_music.kt.data.sources.qobuz.qobuzQualitySpec
import com.annieplayer.annie_music.kt.data.sources.qobuz.qobuzTokenProvider
import com.annieplayer.annie_music.kt.data.sources.qobuz.searchQobuzAlbums
import com.annieplayer.annie_music.kt.data.sources.qobuz.searchQobuzArtists
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

/**
 * 「这次没拿到歌词」的短 TTL。
 *
 * 空结果也缓存，但只存 10 分钟：连续切歌/重播同一首不会每次都打接口，
 * 而瞬时失败（网络抖动、Apple 本机服务刚被拉起）也能在 10 分钟后自愈 ——
 * 不用 60 分钟那么久，也不像「完全不缓存」那样每播一次都发一次请求。
 */
private const val _lyricEmptyCacheTtl: Long = 10 * 60 * 1000L

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

/**
 * 落雪插件接管的国内四平台。
 *
 * 这两家的播放与下载**都只有插件这一条路**：内置直链接口（网易 `song/url`、酷我
 * `convert_url_with_sign`、QQ/酷狗的解析接口）已全部删除，也没有任何降级/回退。
 */
private val _lxPlatforms = setOf(
    MusicPlatform.netease,
    MusicPlatform.kuwo,
    MusicPlatform.kugou,
    MusicPlatform.qq,
)

/**
 * 播放 URL —— **音源路由**。
 *
 * | 平台 | 取链方 |
 * |---|---|
 * | 网易 / QQ / 酷狗 / 酷我 | 落雪插件（没插件 = 直接失败，不降级、不回退内置） |
 * | KKBOX | KKBOX 官方票据接口（会话缺失 = 失败，不借别家） |
 * | Qobuz | 官方 token 接口 |
 * | 本地 | `playUrl` 本身就是 `file://` 绝对路径 |
 * | Apple | 不走直链，由 `ApplePlaybackServer` 起本机 HLS 代理解密 |
 */
suspend fun fetchPlayUrlBySong(song: Song): String = resolvePlayUrl(song)

/**
 * 下载 URL —— **音源路由**。
 *
 * 四平台只有落雪插件；KKBOX 与 Apple 的下载带解密环节（DRM / FairPlay），
 * 由各自的专用下载器实现，不经这里取直链。
 */
suspend fun fetchDownloadUrlBySong(song: Song, quality: Quality): String =
    resolveDownloadUrl(song, quality)

private suspend fun resolveDownloadUrl(song: Song, quality: Quality): String = when (song.platform) {
    MusicPlatform.netease, MusicPlatform.qq, MusicPlatform.kugou, MusicPlatform.kuwo -> {
        requireLxPlugin(song)
        _lxProvider.resolveDownloadUrl(song, quality)
    }
    MusicPlatform.kkbox -> error("KKBOX 下载走 KkboxDownloader（官方票据 + DRM 解密）")
    MusicPlatform.apple -> error("Apple Music 下载走 AppleDownloader")
    MusicPlatform.local -> error("本地文件无需下载")
    MusicPlatform.qobuz -> _builtinProvider.resolveDownloadUrl(song, quality)
}

private suspend fun resolvePlayUrl(song: Song): String = when (song.platform) {
    MusicPlatform.netease, MusicPlatform.qq, MusicPlatform.kugou, MusicPlatform.kuwo -> {
        requireLxPlugin(song)
        _lxProvider.resolvePlayUrl(song, MoreSettings.qualityFor(song.platform))
    }
    MusicPlatform.kkbox -> fetchKkboxPlayUrl(song.id)
    MusicPlatform.qobuz -> _builtinProvider.resolvePlayUrl(song, MoreSettings.qualityFor(song.platform))
    MusicPlatform.local -> song.playUrl
    MusicPlatform.apple -> error("Apple Music 播放走 ApplePlaybackServer，不经直链解析")
}

/**
 * 「该平台没有可用插件」——**直接失败**。
 *
 * 内置直链已删除，没有可回退的东西：文案必须指向「去插件管理导入」这个唯一解法。
 */
private fun requireLxPlugin(song: Song) {
    if (LxPluginRegistry.pluginFor(song.platform) != null) return
    val active = LxPluginRegistry.activePlugin()
    val message = if (active != null) {
        "「${active.name}」不支持${song.platform.label}：请在「插件管理」里换一个支持它的插件"
    } else {
        "${song.platform.label}的音源由落雪插件提供：请先在「插件管理」里导入并启用一个插件"
    }
    LxPluginRegistry.postNotice(message)
    throw IllegalStateException(message)
}

/**
 * 引擎侧预取入口：把 Media3 队列项还原成 [Song] 后走同一条音源路由。
 *
 * 队列项带 `qualities`（该歌真实可用档位），`LxPluginSourceProvider.pickQuality` 据此把
 * 「选了无损但这首歌只有 320k」的请求夹到它真实有的档位。
 *
 * **档位缺失时不要伪造 `[320k]`**：伪造会让夹档把「未知」当成「只有 320k」，
 * 于是无损歌被平白降到 320k。空表交给 `pickQuality` 判「未知」（自动档仍按 320k 请求）。
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
            qualities = qualities,
            mediaMid = item["mediaMid"]?.takeIf { it.isNotEmpty() },
        ),
    )
}

/**
 * 队列项 → **本次实际会请求的档位**（播放缓存分区 + 「自动」文案都用它）。
 *
 * 规则与 `LxPluginSourceProvider.pickQuality` 逐条相同（同一个 `effectivePlayQuality`）：
 * 配了档位就按歌夹档，选「自动」就取这首歌的最高档，未知才退 320k。
 *
 * 只有国内四平台走这套；Apple / KKBOX / Qobuz 的档位另有来源（Apple 是 `alac/hires/...`），
 * 保持改造前的 `配置档 ?: 320k`，避免无谓地把它们的缓存 key 改掉。
 */
internal fun effectivePlayQualityOf(item: Map<String, String>): Quality {
    val platform = MusicPlatform.entries.firstOrNull { it.name == item["platform"] } ?: MusicPlatform.kuwo
    val configured = MoreSettings.qualityFor(platform)
    return when (platform) {
        MusicPlatform.netease, MusicPlatform.qq, MusicPlatform.kugou, MusicPlatform.kuwo -> {
            val qualities = item["qualities"].orEmpty()
                .split(',')
                .mapNotNull { Quality.fromValue(it.trim().ifEmpty { null }) }
                .distinct()
            effectivePlayQuality(platform, qualities, configured)
        }
        else -> configured ?: Quality.k320k
    }
}

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
    MusicPlatform.apple -> listOf(Quality.atmos, Quality.hires, Quality.alac, Quality.aac256)
    // 本地文件没有「音质档位」可选：文件本身就是最终档，选档界面不出现
    MusicPlatform.kkbox, MusicPlatform.local -> emptyList()
}

/** 档位显示名：Qobuz / Apple 用自己的档位命名，其余用通用 [Quality.label] */
fun playQualityLabelOf(platform: MusicPlatform, quality: Quality): String = when {
    platform == MusicPlatform.qobuz -> qobuzQualityLabel(quality)
    platform == MusicPlatform.apple && quality == Quality.alac -> "ALAC 无损"
    platform == MusicPlatform.apple && quality == Quality.hires -> "ALAC 超高解析"
    else -> quality.label
}

/**
 * 「自动」档显示名 —— 括号里写**该平台可选的最高档位名**，避免看着像"没档位"。
 *
 * 国内四平台的「自动」= 这首歌自己的最高档（逐曲不同，所以这里只能写平台天花板，
 * 例如网易/酷狗 `自动（HiRes）`、QQ/酷我 `自动（无损音质）`）；实际逐曲夹档见
 * `effectivePlayQuality`。**Qobuz 自动 = FLAC Hires Max**（`qobuzHiresFormatId`）。
 */
fun playQualityAutoLabelOf(platform: MusicPlatform): String = when (platform) {
    MusicPlatform.qobuz -> "自动（FLAC Hires Max）"
    // Apple 的「自动」= ALAC 超高解析，曲目没有该档时按 ALAC 无损 → AAC 256 降级
    MusicPlatform.apple -> "自动（ALAC 超高解析）"
    MusicPlatform.netease, MusicPlatform.qq, MusicPlatform.kugou, MusicPlatform.kuwo ->
        playQualityTiersOf(platform).firstOrNull()
            ?.let { "自动（${playQualityLabelOf(platform, it)}）" }
            ?: "自动（320k）"
    else -> "自动（320k）"
}

/**
 * 内置音源取链 —— **只剩 Qobuz**。
 *
 * 国内四平台（网易 / QQ / 酷狗 / 酷我）的内置直链、KKBOX 的酷我兜底、以及它们各自
 * 的档位降级梯子已全部删除：四平台走落雪插件、KKBOX 走自己的官方票据接口。
 * 这里对别的平台一律抛错，避免"漏走路由还能碰巧出链"。
 */
internal suspend fun builtinPlayUrl(song: Song, quality: Quality? = null): String = when (song.platform) {
    MusicPlatform.qobuz -> fetchQobuzUrl(song, quality)
    MusicPlatform.netease, MusicPlatform.qq, MusicPlatform.kugou, MusicPlatform.kuwo ->
        error("${song.platform.label}的音源由落雪插件提供，内置直链已移除")
    MusicPlatform.kkbox -> error("KKBOX 播放走官方票据接口（fetchKkboxPlayUrl）")
    MusicPlatform.apple -> error("Apple Music 播放走 ApplePlaybackServer，不经直链解析")
    // 本地文件：playUrl 已经是 file:// 绝对路径，没有任何「解析」步骤
    MusicPlatform.local -> if (quality == null) song.playUrl else error("本地文件不支持按档位取链")
}

/**
 * 内置下载直链 —— **只剩 Qobuz**。
 *
 * 带解密环节的两家（KKBOX 的 DRM、Apple 的 FairPlay）各有专用下载器，不经这里取直链。
 */
internal suspend fun builtinDownloadUrl(song: Song, quality: Quality): String = when (song.platform) {
    MusicPlatform.qobuz -> fetchQobuzUrl(song, quality)
    MusicPlatform.netease, MusicPlatform.qq, MusicPlatform.kugou, MusicPlatform.kuwo ->
        error("${song.platform.label}的内置下载接口已移除，下载统一走落雪插件")
    MusicPlatform.kkbox -> error("KKBOX 下载走 KkboxDownloader（官方票据 + DRM 解密）")
    MusicPlatform.apple -> error("Apple Music 下载走 AppleDownloader")
    MusicPlatform.local -> error("本地文件无需下载")
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
    MusicPlatform.apple -> searchAppleAlbums(query, page = page, limit = pageSize)
    MusicPlatform.kuwo, MusicPlatform.kkbox ->
        searchAlbum(query, page = page, pageSize = pageSize)
    // 本地曲库不做云端专辑搜索
    MusicPlatform.local -> emptyList()
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
    MusicPlatform.apple -> getAppleAlbumSongs(albumId).let {
        RepoAlbumSongs(it.songs, it.pub)
    }
    MusicPlatform.local -> RepoAlbumSongs(emptyList(), "")
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
    MusicPlatform.apple -> fetchAppleArtistAvatar(artistId, name)
    MusicPlatform.kkbox -> ""
    MusicPlatform.local -> ""
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
    MusicPlatform.apple -> fetchAppleArtistBio(artistId, name)
    MusicPlatform.qobuz, MusicPlatform.kkbox -> ""
    MusicPlatform.local -> ""
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
    MusicPlatform.apple -> searchAppleArtists(query)
    MusicPlatform.kuwo, MusicPlatform.kkbox -> searchArtist(query)
    MusicPlatform.local -> emptyList()
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
    MusicPlatform.apple -> {
        val r = getAppleArtistSongs(artistId)
        RepoArtistSongs(r.songs, r.total, pageable = false)
    }
    MusicPlatform.local -> RepoArtistSongs(emptyList(), 0, pageable = false)
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
    MusicPlatform.apple -> getAppleArtistAlbums(artistId)
    MusicPlatform.kuwo, MusicPlatform.kkbox -> getArtistAlbums(artistId)
    MusicPlatform.local -> emptyList()
}

/**
 * 歌词（按 `platform` 分发，**没有兜底平台**）。
 *
 * [kkboxSid] 为 KKBOX 活跃账号会话 sid：仅当 platform 为 kkbox 时使用，
 * 有 sid 调 `getSongLyrics` 拉真实歌词，无则返回占位。
 */
suspend fun fetchLyric(
    song: Song,
    kkboxSid: String? = null,
    autoPickTranslate: Boolean = false,
): LyricResult {
    // 本地文件：歌词就在文件旁边（同名 .lrc）或文件内嵌（`lyrics` tag），
    // 不联网、不进歌词缓存 —— 用户改了 .lrc 应该立刻生效。
    if (song.platform == MusicPlatform.local) {
        return runCatching {
            val path = song.id.removePrefix("local:")
            val raw = com.annieplayer.annie_music.kt.data.local.LocalLibraryStore.readLyric(path)
            if (raw.isNullOrBlank()) {
                LyricResult(lrc = "[00:00.00]暂无歌词", tlyric = null)
            } else {
                val (lrc, tlyric, word) = com.annieplayer.annie_music.kt.core.util.splitEmbeddedLyric(raw)
                LyricResult(lrc = lrc.ifEmpty { raw }, tlyric = tlyric, wordLyric = word)
            }
        }.getOrElse { LyricResult(lrc = "[00:00.00]暂无歌词", tlyric = null) }
    }

    // 已下载的歌（platform 还是云端平台，但本地有文件）：**歌词本地优先** ——
    // 下载时把歌词写进了文件 tag，同目录也可能有 .lrc。命中就直接用，不联网；
    // 没命中再照常走各平台接口（右边这一整段就是 API 兜底）。
    if (!song.lyric.isNullOrBlank() && !song.lyric.contains("暂无歌词")) {
        // 队列里已经带了歌词（本地曲库播放时填过），不必再查
        return LyricResult(lrc = song.lyric, tlyric = song.tlyric, wordLyric = song.wordLyric)
    }
    runCatching {
        com.annieplayer.annie_music.kt.data.local.LocalLibraryStore
            .lyricForDownloadedSong(song.title, song.artist)
    }.getOrNull()?.let { local ->
        val (lrc, tlyric, word) = com.annieplayer.annie_music.kt.core.util.splitEmbeddedLyric(local)
        return LyricResult(lrc = lrc.ifEmpty { local }, tlyric = tlyric, wordLyric = word)
    }

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
    // （以前 Apple 还要多带一个 `:local`/`:remote` 源标记 —— 非官方源已收敛成用户配的
    //  wrapper 地址这一条，源分叉不存在了，标记随之删除。）
    // v7：v6 及以前会把「拉取失败」当成占位文本缓存满 TTL（分不清失败与真空），
    // 那些坏条目要一次性作废，否则修好了行为也会被旧缓存挡着。
    val cacheKey = "lyric:v7:${song.id}:${song.lyricTranslateLang.orEmpty()}:" +
        (if (autoPickTranslate) "auto" else "raw")
    val cached = cacheService.get<String>(cacheKey)
    if (cached != null) {
        try {
            val parsed = jsonElementToAny(json.parseToJsonElement(cached))
            if (parsed is Map<*, *> && parsed["lrc"] is String) {
                val cachedLrc = parsed["lrc"] as String
                return LyricResult(
                    // 缓存里存的是「空」表示没取到，对外统一成占位文本
                    lrc = cachedLrc.ifEmpty { NO_LYRIC },
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
        // 平台实现全部在 `data/sources/lyric/`：按 platform 一对一匹配，**没有兜底平台**。
        // 认不出（含以后新增但忘了注册）就给占位 —— 一眼能发现，不会静默去请求某一家。
        val source = LyricSources.of(song)
            ?: return LyricResult(lrc = NO_LYRIC, tlyric = null)
        val fetched = source.fetch(
            song,
            LyricRequest(
                translateLang = song.lyricTranslateLang,
                autoPickTranslate = autoPickTranslate,
            ),
        )
        // 各平台实现内部仍会吞异常并返回那个占位串，这里统一归一成「空」——
        // 门面只认「有词 / 没词」两种，才好决定缓存多久。
        val result = if (fetched.lrc == NO_LYRIC) fetched.copy(lrc = "") else fetched
        val ttl = when {
            result.lrc.isNotEmpty() -> _lyricCacheTtl
            // Apple 走的是用户配的 wrapper 地址：空结果多半是「服务刚起来 / 那台实例没有这首歌」，
            // 把空结果缓存住会让歌词一直不出来 —— 不缓存，下次进页面再问一次。
            song.platform == MusicPlatform.apple -> null
            else -> _lyricEmptyCacheTtl
        }
        if (ttl != null) {
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
                ttl,
            )
        }
        if (result.lrc.isEmpty()) result.copy(lrc = NO_LYRIC) else result
    } catch (_: Throwable) {
        // 抛异常 = 明确的失败。存 10 分钟的空结果：下次播放/切歌能自愈，
        // 又不像以前那样把失败当结果锁满 60 分钟。Apple 不存（本机服务随时可能就绪）。
        if (song.platform != MusicPlatform.apple) {
            cacheService.set(
                cacheKey,
                anyToJsonElement(mapOf<String, Any?>("lrc" to "", "tlyric" to null)).toString(),
                _lyricEmptyCacheTtl,
            )
        }
        LyricResult(lrc = NO_LYRIC, tlyric = null)
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
            "composer" to t.composer,
            "genre" to t.genre,
            "isrc" to t.isrc,
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
    MusicPlatform.apple -> emptyMap()
    // 本地文件的元信息已经由 ffprobe 读全（tag 就在文件里），不需要再联网补
    MusicPlatform.local -> emptyMap()
}
