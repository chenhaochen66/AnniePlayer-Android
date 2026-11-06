package com.annieplayer.annie_music.kt.data.sources.qobuz

import com.annieplayer.annie_music.kt.data.model.Album
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.QobuzAlbum
import com.annieplayer.annie_music.kt.data.model.QobuzTrack
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.sources.common.firstTruthy
import com.annieplayer.annie_music.kt.data.sources.common.jsTruthy

// ================================================================================================
// Qobuz 映射（纯函数：JSON → QobuzTrack / QobuzAlbum → Song / Album）
// ================================================================================================
//
// 这个文件不碰网络、不碰 Android，是可以单测的那一层。

/** 转 Int，失败时回退 fallback（等价 `x || 0` 的数字语义） */
internal fun qobuzIntOr(value: Any?, fallback: Int): Int {
    if (value is Int) return value
    if (value is Long) return value.toInt()
    if (value is Double) return value.toInt()
    if (value is Float) return value.toInt()
    if (value is Number) return value.toInt()
    if (value == null) return fallback
    val s = value.toString()
    if (s.isEmpty()) return fallback
    return s.toIntOrNull() ?: fallback
}

/** 把 Qobuz 封面 `_<size>.jpg` 提升到 `_max.jpg`（Qobuz 无 800 档，实测 `_600`=600、`_max`=1400、`_800`=404） */
internal fun qobuzMaxCover(url: Any?): Any? {
    val s = url?.toString() ?: return url
    return s.replace(Regex("_\\d+\\.jpg$"), "_max.jpg")
}

/** 取容器 `container.image.large`（用于 `album?.image?.large`） */
internal fun qobuzNestedLarge(container: Any?): Any? {
    if (container is Map<*, *>) {
        val image = container["image"]
        if (image is Map<*, *>) return qobuzMaxCover(image["large"])
    }
    return null
}

/** 取图像对象 `image.large`（用于 `image?.large`） */
internal fun qobuzDirectLarge(image: Any?): Any? {
    if (image is Map<*, *>) return qobuzMaxCover(image["large"])
    return null
}

internal fun qobuzAsMap(v: Any?): Map<String, Any?>? =
    if (v is Map<*, *>) v.entries.associate { (k, item) -> k.toString() to item } else null

internal fun qobuzContainerItems(container: Any?): List<Any?> {
    val m = qobuzAsMap(container) ?: return emptyList()
    val items = m["items"]
    return if (items is List<*>) items else emptyList()
}

/** 映射 Qobuz 单曲 JSON -> QobuzTrack（对齐 RN 版 mapTrackToQobuzTrack） */
fun mapTrackToQobuzTrack(raw: Map<String, Any?>): QobuzTrack {
    val performer = raw["performer"]
    val performerName = if (performer is Map<*, *>) performer["name"] else performer
    val artistRaw = raw["artist"]
    val artistName = if (artistRaw is Map<*, *>) artistRaw["name"] else artistRaw

    val album = raw["album"]
    var albumArtistName: Any? = null
    if (album is Map<*, *>) {
        val aa = album["artist"]
        albumArtistName = if (aa is Map<*, *>) aa["name"] else aa
    }

    val discRaw = firstTruthy(listOf(raw["media_number"], raw["disc_number"]))
    val composerRaw = raw["composer"]
    val composerName = when {
        composerRaw is Map<*, *> -> composerRaw["name"]?.toString()
        composerRaw != null -> composerRaw.toString()
        else -> null
    }?.takeIf { it.isNotBlank() }
    val albumGenreRaw = if (album is Map<*, *>) album["genre"] else null
    val genreName = when {
        albumGenreRaw is Map<*, *> -> albumGenreRaw["name"]?.toString()
        albumGenreRaw != null -> albumGenreRaw.toString()
        else -> null
    }?.takeIf { it.isNotBlank() }

    // 只用 performer（该曲演唱者）：performers 逗号串会把制作人/工程师也算进来
    val performerId = if (performer is Map<*, *>) (performer["id"] ?: "").toString() else ""
    val artistNames = if (jsTruthy(performerName)) listOf(performerName.toString()) else emptyList()
    val artistIds = if (performerId.isNotEmpty()) listOf(performerId) else emptyList()

    return QobuzTrack(
        id = raw["id"]?.toString() ?: "",
        title = (firstTruthy(listOf(raw["title"])) ?: "").toString(),
        artist = (firstTruthy(listOf(performerName, artistName)) ?: "").toString(),
        cover = (firstTruthy(listOf(qobuzNestedLarge(album), qobuzDirectLarge(raw["image"]))) ?: "")
            .toString(),
        album = (firstTruthy(listOf(if (album is Map<*, *>) album["title"] else null)) ?: "")
            .toString(),
        albumId = (firstTruthy(listOf(if (album is Map<*, *>) album["id"] else null)) ?: "")
            .toString(),
        albumArtist = if (jsTruthy(albumArtistName)) albumArtistName.toString() else null,
        trackNumber = qobuzIntOr(raw["track_number"], 0),
        discNumber = if (discRaw == null) null else qobuzIntOr(discRaw, 0),
        duration = qobuzIntOr(raw["duration"], 0),
        streamable = jsTruthy(raw["streamable"]),
        label = (firstTruthy(listOf(raw["label"])) ?: "").toString(),
        year = qobuzIntOr(raw["year"], 0),
        releaseDateOriginal = raw["release_date_original"]?.toString()?.takeIf { it.isNotEmpty() },
        isrc = raw["isrc"]?.toString()?.takeIf { it.isNotEmpty() },
        version = raw["version"]?.toString()?.takeIf { it.isNotEmpty() },
        copyright = raw["copyright"]?.toString()?.takeIf { it.isNotEmpty() },
        composer = composerName,
        genre = genreName,
        artistNames = artistNames,
        artistIds = artistIds,
    )
}

/** 映射 Qobuz 专辑 JSON -> QobuzAlbum（对齐 RN 版 mapAlbumToQobuzAlbum） */
fun mapAlbumToQobuzAlbum(raw: Map<String, Any?>): QobuzAlbum {
    val cover =
        (firstTruthy(listOf(qobuzDirectLarge(raw["image"]), qobuzNestedLarge(raw["album"]))) ?: "")
            .toString()
    val artistRaw = raw["artist"]
    val artistName = if (artistRaw is Map<*, *>) artistRaw["name"] else artistRaw
    val performerRaw = raw["performer"]
    val performerName = if (performerRaw is Map<*, *>) performerRaw["name"] else performerRaw
    val artistStr = (firstTruthy(listOf(artistName, performerName)) ?: "").toString()

    val tracksContainer = raw["tracks"]
    val items: List<Any?> = if (tracksContainer is Map<*, *> && tracksContainer["items"] is List<*>) {
        tracksContainer["items"] as List<Any?>
    } else {
        emptyList()
    }

    val tracks = items.map { t ->
        val merged = if (t is Map<*, *>) {
            LinkedHashMap<String, Any?>(t.entries.associate { (k, v) -> k.toString() to v })
        } else {
            LinkedHashMap<String, Any?>()
        }
        // 只在缺失时补：专辑接口的曲目相对专辑是「绝对」信息，曲目自带的 performer 才是该曲真实表演者
        //（单歌手专辑里也会有 feat. 曲目，覆盖会把多歌手信息压成专辑艺术家）
        if (merged["album"] == null) {
            merged["album"] = mapOf(
                "id" to raw["id"],
                "title" to raw["title"],
                "image" to mapOf("large" to cover),
                "artist" to mapOf("name" to artistStr),
            )
        }
        if (merged["performer"] == null) {
            merged["performer"] = mapOf("name" to artistStr)
        }
        mapTrackToQobuzTrack(merged)
    }

    return QobuzAlbum(
        id = raw["id"]?.toString() ?: "",
        title = (firstTruthy(listOf(raw["title"])) ?: "").toString(),
        artist = artistStr,
        cover = cover,
        year = qobuzIntOr(raw["year"], 0),
        label = (firstTruthy(listOf(raw["label"])) ?: "").toString(),
        tracks = tracks,
        maxBitDepth = (raw["maximum_bit_depth"] as? Number)?.toInt(),
        maxSamplingRateKhz = (raw["maximum_sampling_rate"] as? Number)?.toDouble(),
        hires = jsTruthy(raw["hires"]),
        trackCount = qobuzIntOr(
            firstTruthy(listOf(raw["tracks_count"], raw["media_count"])),
            tracks.size,
        ),
    )
}

// ── 统一模型映射（搜索 / 详情共用；UI 不再各自实现一份） ──

/** Qobuz 单曲 -> [Song]。[hires] 用于推断可用音质档位。 */
fun qobuzTrackToSong(track: QobuzTrack, hires: Boolean = false): Song = Song(
    id = "qobuz:${track.id}",
    title = track.title,
    artist = track.artist,
    cover = track.cover,
    album = track.album,
    albumId = track.albumId.ifEmpty { null },
    duration = track.duration,
    platform = MusicPlatform.qobuz,
    albumArtist = track.albumArtist,
    trackNumber = if (track.trackNumber > 0) track.trackNumber.toString() else null,
    discNumber = track.discNumber?.toString(),
    releaseDate = track.releaseDateOriginal
        ?: if (track.year > 0) track.year.toString() else null,
    qualities = if (hires) {
        listOf(Quality.hires, Quality.flac, Quality.k320k)
    } else {
        listOf(Quality.flac, Quality.k320k)
    },
    artistNames = track.artistNames,
    artistIds = track.artistIds,
    composer = track.composer,
    genre = track.genre,
    isrc = track.isrc,
    // Qobuz 的 track 详情本来就带这两个。以前没往 Song 里放，结果下载时又发一次
    // `getTrack` 去补 —— 白多一次请求，补不到就整条字段丢掉。
    copyright = track.copyright,
    version = track.version,
)

/** Qobuz 专辑 -> [Album]（封面放 `pic`，`buildAlbumCoverUrl` 对 qobuz 直接取它）。 */
fun qobuzAlbumToAlbum(a: QobuzAlbum): Album = Album(
    albumid = a.id,
    name = a.title,
    artist = a.artist,
    platform = MusicPlatform.qobuz,
    musiccnt = a.trackCount.toString(),
    pic = a.cover,
    img = a.cover,
)
