package com.annieplayer.annie_music.kt.data

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
import com.annieplayer.annie_music.kt.data.sources.enrichQQSong
import com.annieplayer.annie_music.kt.data.sources.fetchKugouArtistInfo
import com.annieplayer.annie_music.kt.data.sources.fetchKugouLyric
import com.annieplayer.annie_music.kt.data.sources.fetchKugouMusicUrl
import com.annieplayer.annie_music.kt.data.sources.fetchKuwoArtistAvatar
import com.annieplayer.annie_music.kt.data.sources.fetchKuwoDownloadUrl
import com.annieplayer.annie_music.kt.data.sources.fetchKuwoLyric
import com.annieplayer.annie_music.kt.data.sources.fetchKuwoPlayUrl
import com.annieplayer.annie_music.kt.data.sources.fetchNeteaseArtistAvatar
import com.annieplayer.annie_music.kt.data.sources.fetchNeteaseDownloadUrl
import com.annieplayer.annie_music.kt.data.sources.fetchNeteaseLyric
import com.annieplayer.annie_music.kt.data.sources.fetchNeteasePlayUrl
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
import com.annieplayer.annie_music.kt.data.sources.getFileUrl
import com.annieplayer.annie_music.kt.data.sources.qobuzHiresFormatId
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

/** 歌词结果（对齐 Dart `LyricResult`） */
data class LyricResult(
    val lrc: String,
    val tlyric: String?,
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

/** 播放 URL（netease 走 c.wwwweb.top，qq/kugou 走长青解析，其余走 kuwo） */
suspend fun fetchPlayUrlBySong(song: Song): String = when (song.platform) {
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

/** 下载 URL */
suspend fun fetchDownloadUrlBySong(song: Song, quality: Quality): String = when (song.platform) {
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
    MusicPlatform.netease -> RepoAlbumSongs(getNeteaseAlbumSongs(albumId), "")
    MusicPlatform.qq -> RepoAlbumSongs(getQQAlbumSongs(albumId), "")
    MusicPlatform.kugou -> RepoAlbumSongs(getKugouAlbumSongs(albumId), "")
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
suspend fun fetchLyric(song: Song, kkboxSid: String? = null): LyricResult {
    // KKBOX 歌词依赖会话，不进入通用歌词缓存（对齐 RN AudioContext 单独处理）
    if (song.platform == MusicPlatform.kkbox) {
        if (kkboxSid.isNullOrEmpty()) {
            return LyricResult(lrc = "[00:00.00]暂无歌词", tlyric = null)
        }
        val rawId = song.id.replaceFirst(Regex("^kkbox:"), "")
        val r = getSongLyrics(kkboxSid, rawId)
        return LyricResult(lrc = r.lrc, tlyric = r.tlyric)
    }

    val cacheKey = "lyric:${song.id}"
    val cached = cacheService.get<String>(cacheKey)
    if (cached != null) {
        try {
            val parsed = jsonElementToAny(json.parseToJsonElement(cached))
            if (parsed is Map<*, *> && parsed["lrc"] is String) {
                return LyricResult(
                    lrc = parsed["lrc"] as String,
                    tlyric = parsed["tlyric"] as String?,
                )
            }
        } catch (_: Throwable) {
            // 缓存内容损坏：忽略并重新拉取
        }
    }

    return try {
        val result: LyricResult = if (song.id.startsWith("netease:")) {
            val r = fetchNeteaseLyric(song.id)
            LyricResult(lrc = r.lrc, tlyric = r.tlyric)
        } else if (song.id.startsWith("qq:")) {
            val r = fetchQQLyric(song.id)
            LyricResult(lrc = r.lrc, tlyric = r.tlyric)
        } else if (song.id.startsWith("kugou:")) {
            val r = fetchKugouLyric(song.id, "${song.title} ${song.artist}")
            LyricResult(lrc = r.lrc, tlyric = r.tlyric)
        } else {
            val r = fetchKuwoLyric(song.id, song.title, song.artist)
            LyricResult(lrc = r.lrc, tlyric = r.tlyric)
        }
        cacheService.set(
            cacheKey,
            anyToJsonElement(
                mapOf<String, Any?>("lrc" to result.lrc, "tlyric" to result.tlyric),
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

/** 补充 metadata（kuwo/kugou/qq 下载前 enrich） */
suspend fun enrichSong(song: Song): Map<String, Any?> = when (song.platform) {
    MusicPlatform.kuwo -> {
        val r = enrichKuwoSong(song.id, song.albumId ?: "")
        mapOf<String, Any?>(
            "trackNumber" to r.trackNumber,
            "releaseDate" to r.releaseDate,
        )
    }
    MusicPlatform.kugou -> {
        val r = enrichKuGouSong(song.id)
        mapOf<String, Any?>(
            "trackNumber" to r.trackNumber,
            "discNumber" to r.discNumber,
            "releaseDate" to r.releaseDate,
        )
    }
    MusicPlatform.qq -> {
        val r = enrichQQSong(song.id)
        mapOf<String, Any?>(
            "trackNumber" to r.trackNumber,
            "discNumber" to r.discNumber,
            "releaseDate" to r.releaseDate,
        )
    }
    MusicPlatform.qobuz -> {
        val t = getTrack(song.id.removePrefix("qobuz:"), qobuzTokenProvider().orEmpty())
        mapOf<String, Any?>(
            "trackNumber" to t.trackNumber.takeIf { it > 0 }?.toString(),
            "discNumber" to t.discNumber?.toString(),
            "releaseDate" to (t.releaseDateOriginal ?: t.year.takeIf { it > 0 }?.toString()),
        )
    }
    MusicPlatform.netease, MusicPlatform.kkbox -> emptyMap()
}
