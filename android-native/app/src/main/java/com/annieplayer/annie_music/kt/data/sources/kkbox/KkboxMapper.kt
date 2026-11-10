package com.annieplayer.annie_music.kt.data.sources.kkbox

import com.annieplayer.annie_music.kt.data.model.KkboxAlbum
import com.annieplayer.annie_music.kt.data.model.KkboxTrack
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
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

/**
 * `artist_role` 的三种形态（KKBOX 各接口不一致）：
 * - `{"mainartists":["A","B"]}`             ← `v2/song`
 * - `{"mainartist":["A"]}`                  ← `v3/artist` 的 hot_song / album、`v1/album`
 * - `{"mainartist_list":{"mainartist":[…]}}`← `search_music.php`
 */
private fun _mainArtistNames(role: Any?): List<String> {
    val map = kkboxAsMap(role) ?: return emptyList()
    fun names(raw: Any?): List<String> =
        (raw as? List<*>)?.mapNotNull { it?.toString()?.trim()?.takeIf { n -> n.isNotEmpty() } }
            ?: emptyList()
    names(map["mainartists"]).takeIf { it.isNotEmpty() }?.let { return it }
    names(map["mainartist"]).takeIf { it.isNotEmpty() }?.let { return it }
    kkboxAsMap(map["mainartist_list"])?.let { nested ->
        names(nested["mainartist"]).takeIf { it.isNotEmpty() }?.let { return it }
        names(nested["mainartists"]).takeIf { it.isNotEmpty() }?.let { return it }
    }
    return emptyList()
}

/** 原始歌曲 json → [KkboxTrack] */
fun mapSongToKkboxTrack(raw: Map<String, Any?>): KkboxTrack {
    val photo = raw["album_photo_info"]
    val coverTemplate = (if (photo is Map<*, *>) photo["url_template"] else null)?.toString() ?: ""
    // 票据只认**加密 id**：`v1/ticket` 收到数字 `song_id` 会返回
    // `status=-2, errmsg="invalid song_id"`。`v3/artist` 的 `hot_song` 恰好没有
    // `song_more_url`，早先在这里退回数字 id，结果就是「歌手页点哪首都播不了」。
    val songMoreUrl = (raw["song_more_url"] ?: "").toString()
    var songId = kkboxStr(raw["encrypted_song_id"])
    if (songId.isEmpty() && songMoreUrl.isNotEmpty()) {
        songId = songMoreUrl.split("/").lastOrNull().orEmpty()
    }
    if (songId.isEmpty()) {
        songId = if (raw["song_id"] != null) raw["song_id"].toString() else ""
    }

    val mainArtists = _mainArtistNames(raw["artist_role"])
    // `mainartists` 是**结构化列表**（KKBOX 自己就拆好了）；`artist_name` 是它合并后的展示串。
    // 多歌手统一写成 `A / B`，所以优先用列表拼；拿不到列表才退回原串。
    val artist: String = if (mainArtists.isNotEmpty()) {
        mainArtists.joinToString(" / ")
    } else if (jsTruthy(raw["artist_name"])) {
        raw["artist_name"].toString()
    } else {
        ""
    }

    // 专辑 id 同理只认**加密形态**：`v1/album/{数字}` 直接回
    // `403 {"subtype":"Forbidden","message":"Unauthorized album"}`，
    // 所以任何「从歌曲跳专辑」的入口（播放页、迷你条、搜索行尾）都必须拿 encrypted_album_id。
    val albumId = kkboxStr(raw["encrypted_album_id"]).ifEmpty {
        if (raw["album_id"] != null) raw["album_id"].toString() else ""
    }
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

/**
 * `audio_quality`（例 `["128k","192k","320k","hifi"]`）→ 可用档位，由高到低。
 *
 * 192k 没有独立档位，归到 320k（两者都只是有损，面板上再分一档没有意义）；
 * 列表为空时至少给 128k —— KKBOX 任何一首可播的歌都有这一档。
 */
fun kkboxQualitiesOf(raw: List<String>): List<Quality> {
    val out = mutableListOf<Quality>()
    if (raw.contains("hires")) out += Quality.hires
    if (raw.contains("hifi")) out += Quality.flac
    if (raw.contains("320k") || raw.contains("192k")) out += Quality.k320k
    out += Quality.k128k
    return out.distinct()
}

/** [KkboxTrack] → [Song]（搜索结果与详情页共用同一份构造，别再各写一遍） */
fun kkboxTrackToSong(track: KkboxTrack): Song = Song(
    id = "kkbox:${track.id}",
    title = track.title,
    artist = track.artist,
    cover = track.cover,
    album = track.album.ifEmpty { null },
    albumId = track.albumId.ifEmpty { null },
    duration = track.duration,
    platform = MusicPlatform.kkbox,
    qualities = kkboxQualitiesOf(track.qualities),
    albumArtist = track.albumArtist,
    trackNumber = if (track.trackNumber > 0) track.trackNumber.toString() else null,
    releaseDate = track.releaseDate,
)

/** 原始专辑 json + 歌曲 json 列表 → [KkboxAlbum] */fun mapAlbumToKkboxAlbum(
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
