package com.annieplayer.annie_music.kt.data.sources.kkbox

import com.annieplayer.annie_music.kt.data.model.KkboxAlbum
import com.annieplayer.annie_music.kt.data.model.KkboxTrack
import com.annieplayer.annie_music.kt.data.sources.common.jsTruthy

// ================================================================================================
// KKBOX 映射（纯函数：原始 JSON -> KkboxTrack / KkboxAlbum）
// ================================================================================================

/** 拼接封面 URL（替换模板占位符、去除逗号多规格、扩展名转小写） */
fun getCoverUrl(urlTemplate: String, size: Int = 800): String {
    var url = urlTemplate
        .replace("{width}", size.toString())
        .replace("{height}", size.toString())
        .replace("{format}", "jpg")
    if (url.contains(",")) {
        val parts = url.split("/").toMutableList()
        val imgRe by lazy { Regex("\\.(jpg|jpeg|png)", RegexOption.IGNORE_CASE) }
        for (i in parts.indices) {
            if (parts[i].contains(",") && imgRe.containsMatchIn(parts[i])) {
                parts[i] = parts[i].split(",").first()
            }
        }
        url = parts.joinToString("/")
    }
    url = Regex("\\.(JPG|JPEG|PNG)").replace(url) { it.value.lowercase() }
    return url
}

/** 原始歌曲 json → [KkboxTrack] */
fun mapSongToKkboxTrack(raw: Map<String, Any?>): KkboxTrack {
    val photo = raw["album_photo_info"]
    val coverTemplate = (if (photo is Map<*, *>) photo["url_template"] else null)?.toString() ?: ""
    val songMoreUrl = (raw["song_more_url"] ?: "").toString()
    var songId = ""
    if (songMoreUrl.isNotEmpty()) {
        val parts = songMoreUrl.split("/")
        songId = if (parts.isNotEmpty()) parts.last() else ""
    }
    if (songId.isEmpty()) {
        songId = if (raw["song_id"] != null) raw["song_id"].toString() else ""
    }

    val artistRole = raw["artist_role"]
    val mainArtists = if (artistRole is Map<*, *>) artistRole["mainartists"] else null
    // `mainartists` 是**结构化列表**（KKBOX 自己就拆好了）；`artist_name` 是它合并后的展示串。
    // 多歌手统一写成 `A / B`，所以优先用列表拼；拿不到列表才退回原串。
    val artist: String = if (mainArtists is List<*> && mainArtists.isNotEmpty()) {
        mainArtists.mapNotNull { it?.toString()?.trim()?.takeIf { name -> name.isNotEmpty() } }
            .joinToString(" / ")
    } else if (jsTruthy(raw["artist_name"])) {
        raw["artist_name"].toString()
    } else {
        ""
    }

    val albumId = if (raw["album_id"] != null) raw["album_id"].toString() else ""
    val releaseDate = if (jsTruthy(raw["album_date"])) raw["album_date"].toString() else null

    val qualities = ArrayList<String>()
    val aq = raw["audio_quality"]
    if (aq is List<*>) {
        for (q in aq) {
            qualities.add(q.toString())
        }
    }

    return KkboxTrack(
        id = songId,
        encryptedId = kkboxStr(raw["encrypted_song_id"]),
        title = (raw["song_name"] ?: "").toString(),
        artist = artist,
        cover = if (coverTemplate.isNotEmpty()) getCoverUrl(coverTemplate) else "",
        album = (raw["album_name"] ?: "").toString(),
        albumId = albumId,
        albumArtist = artist,
        trackNumber = kkboxInt(raw["song_idx"]),
        releaseDate = releaseDate,
        duration = kkboxInt(raw["duration_ms"]) / 1000,
        qualities = qualities,
    )
}

/** 原始专辑 json + 歌曲 json 列表 → [KkboxAlbum] */
fun mapAlbumToKkboxAlbum(
    raw: Map<String, Any?>,
    rawSongs: List<Map<String, Any?>>,
): KkboxAlbum {
    val album: Map<String, Any?> = kkboxAsMap(raw["album"]) ?: raw

    val photo = album["album_photo_info"]
    val coverTemplate = (if (photo is Map<*, *>) photo["url_template"] else null)?.toString() ?: ""
    val albumCover = if (coverTemplate.isNotEmpty()) getCoverUrl(coverTemplate) else ""
    val albumArtist = (album["artist_name"] ?: "").toString()
    val albumName = (album["album_name"] ?: "").toString()

    val tracks = rawSongs.map { s ->
        val t = mapSongToKkboxTrack(s)
        KkboxTrack(
            id = t.id,
            encryptedId = t.encryptedId,
            title = t.title,
            artist = if (t.artist.isNotEmpty()) t.artist else albumArtist,
            cover = if (t.cover.isNotEmpty()) t.cover else albumCover,
            album = if (t.album.isNotEmpty()) t.album else albumName,
            albumId = t.albumId,
            albumArtist = t.albumArtist,
            trackNumber = t.trackNumber,
            duration = t.duration,
            qualities = t.qualities,
            releaseDate = t.releaseDate,
        )
    }

    return KkboxAlbum(
        id = (album["album_id"] ?: "").toString(),
        encryptedId = kkboxStr(album["encrypted_album_id"]),
        title = albumName,
        artist = albumArtist,
        cover = albumCover,
        year = (album["album_date"] ?: "").toString(),
        tracks = tracks,
    )
}
