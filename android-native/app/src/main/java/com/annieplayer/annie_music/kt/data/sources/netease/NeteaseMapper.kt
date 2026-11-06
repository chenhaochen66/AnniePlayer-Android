package com.annieplayer.annie_music.kt.data.sources.netease

import com.annieplayer.annie_music.kt.data.model.Album
import com.annieplayer.annie_music.kt.data.model.Artist
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.sources.common.decodeHtmlEntities
import com.annieplayer.annie_music.kt.data.sources.common.jsTruthy

// ================================================================================================
// 网易云映射（纯函数：JSON → Song / Album / Artist）
// ================================================================================================
//
// 音质档位 / 封面尺寸 / 歌手 id 这些会直接影响播放与下载的字段**只在这里维护一份**：
// 主页（`sources.home.NeteaseHome`）复用的就是本文件的 [neteaseArtistSong]。

/** 网易云封面统一 800x800（`?param=800y800`）；空串原样返回 */
private fun _cover800(url: Any?): String {
    val s = url?.toString() ?: ""
    if (s.isEmpty()) return ""
    return if (s.contains("?")) "$s&param=800y800" else "$s?param=800y800"
}

/**
 * 从搜索原始条目中提取可用的网易云数字 id。
 *
 * 网易云歌曲 id 为 **64 位**（如 `3397732267`，已超过 `Int.MAX_VALUE`），
 * 且 cloudsearch 响应通常只给顶层 `id`、不带 `idStr`，因此按 `Long` 解析。
 * 取值顺序：顶层 `id` → `idStr`。两者都拿不到正整数时返回 null，
 * 调用方应丢弃该条目，避免生成 `netease:null` 这类不可用 id。
 */
private fun _extractNeteaseId(item: Map<String, Any?>): Long? {
    for (candidate in listOf(item["id"], item["idStr"])) {
        val parsed = when (candidate) {
            is Number -> candidate.toLong()
            is String -> candidate.trim().toLongOrNull()
            else -> null
        }
        if (parsed != null && parsed > 0L) return parsed
    }
    return null
}

// ── 歌曲 ──

/** 将网易云搜索原始条目映射为 [Song]（缓存命中与网络路径共用） */
internal fun neteaseMapSearchSongs(raw: Any?): List<Song> =
    neteaseListOfMaps(raw).mapNotNull { _mapSearchSong(it) }

private fun _mapSearchSong(item: Map<String, Any?>): Song? {
    // id 缺失或不可解析的条目直接丢弃（返回 null）：不生成 `netease:null`，
    // 否则这条记录会进列表但点播放/下载必然失败且没有任何提示。
    val numericId = _extractNeteaseId(item) ?: return null
    val arList = neteaseListOfMaps(item["ar"])
    val artistNames = arList
        .map { decodeHtmlEntities((it["name"] ?: "").toString()) }
        .filter { it.isNotEmpty() }
    val artistIds = arList
        .map { (it["id"] ?: "").toString() }
        .filter { it.isNotEmpty() }
        .takeIf { it.size == artistNames.size } ?: emptyList()
    val artist = artistNames.joinToString(" / ")

    val al = neteaseAsMap(item["al"]) ?: emptyMap()

    val dt = item["dt"]
    val no = item["no"]
    val cd = item["cd"]
    val publishTime = item["publishTime"]
    val firstArName = arList.firstOrNull()?.get("name")

    return Song(
        id = "netease:$numericId",
        title = decodeHtmlEntities((item["name"] ?: "未知歌曲").toString()),
        artist = artist,
        cover = _cover800(al["picUrl"]),
        album = decodeHtmlEntities((al["name"] ?: "").toString()),
        albumId = if (jsTruthy(al["id"])) al["id"].toString() else null,
        duration = neteaseMsToSec(dt),
        platform = MusicPlatform.netease,
        playUrl = "",
        lyric = null,
        qualities = parseNeteaseQualities(item),
        qualitySizes = parseNeteaseQualitySizes(item),
        albumArtist = if (jsTruthy(firstArName)) {
            decodeHtmlEntities(firstArName.toString())
        } else {
            null
        },
        trackNumber = if (jsTruthy(no)) no.toString() else null,
        discNumber = if (jsTruthy(cd)) cd.toString() else null,
        releaseDate = if (jsTruthy(publishTime)) neteaseIsoDate(publishTime) else null,
        artistNames = artistNames,
        artistIds = artistIds,
    )
}

/**
 * 单曲条目 -> [Song]（旧字段名：`artists` / `duration` / `album`）。
 *
 * `internal` 是为了让主页（`sources.home.NeteaseHome`）复用同一份映射 ——
 * 音质档位 / 封面尺寸这些会直接影响播放与下载的字段只在这里维护一份。
 */
internal fun neteaseArtistSong(m: Map<String, Any?>): Song {
    val arRaw = m["ar"] ?: m["artists"]
    val arList = neteaseListOfMaps(arRaw)
    val artistNames = arList
        .map { decodeHtmlEntities((it["name"] ?: "").toString()) }
        .filter { it.isNotEmpty() }
    val artistIds = arList
        .map { (it["id"] ?: "").toString() }
        .filter { it.isNotEmpty() }
        .takeIf { it.size == artistNames.size } ?: emptyList()
    val artistName = if (artistNames.isEmpty()) "" else artistNames.joinToString(" / ")
    val alMap = neteaseAsMap(m["al"] ?: m["album"]) ?: emptyMap()
    val dt = m["dt"] ?: m["duration"]
    val qualities = parseNeteaseQualities(m).toMutableList()
    if (qualities.isEmpty()) qualities.add(Quality.k128k)
    return Song(
        id = "netease:${m["id"]}",
        title = decodeHtmlEntities((m["name"] ?: "").toString()),
        artist = decodeHtmlEntities(artistName),
        cover = _cover800(alMap["picUrl"]),
        album = decodeHtmlEntities((alMap["name"] ?: "").toString()),
        albumId = (alMap["id"] ?: "").toString(),
        duration = neteaseMsToSec(dt),
        platform = MusicPlatform.netease,
        qualities = qualities,
        qualitySizes = parseNeteaseQualitySizes(m),
        albumArtist = decodeHtmlEntities(artistName),
        artistNames = artistNames,
        artistIds = artistIds,
    )
}

// ── 专辑 / 歌手 ──

/** 网易云专辑原始条目 -> [Album] */
internal fun neteaseMapAlbums(raw: Any?): List<Album> {
    val out = ArrayList<Album>()
    for (m in neteaseListOfMaps(raw)) {
        var artistName = ""
        val artist = m["artist"]
        if (artist is Map<*, *>) artistName = (artist["name"] ?: "").toString()
        if (artistName.isEmpty()) {
            val artists = m["artists"]
            if (artists is List<*> && artists.isNotEmpty()) {
                val first = artists.first()
                if (first is Map<*, *>) artistName = (first["name"] ?: "").toString()
            }
        }

        val publishTime = m["publishTime"]
        out.add(
            Album(
                albumid = (m["id"] ?: m["idStr"] ?: "").toString(),
                name = decodeHtmlEntities((m["name"] ?: "").toString()),
                artist = decodeHtmlEntities(artistName),
                platform = MusicPlatform.netease,
                musiccnt = (m["size"] ?: "0").toString(),
                pub = if (publishTime is Number && publishTime.toDouble() > 0) {
                    neteaseIsoDate(publishTime)
                } else {
                    ""
                },
                pic = _cover800(m["picUrl"] ?: m["blurPicUrl"]),
                company = (m["company"] ?: "").toString(),
            ),
        )
    }
    return out
}

/** 网易云歌手原始条目 -> [Artist] */
internal fun neteaseMapArtists(raw: Any?): List<Artist> =
    neteaseListOfMaps(raw).map { m ->
        val size = m["musicSize"]
        Artist(
            id = (m["id"] ?: "").toString(),
            name = decodeHtmlEntities((m["name"] ?: "").toString()),
            picture = (m["picUrl"] ?: "").toString(),
            musicNum = if (size is Number) size.toInt() else 0,
        )
    }
