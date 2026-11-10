package com.annieplayer.annie_music.kt.data.sources.kkbox

import com.annieplayer.annie_music.kt.data.sources.common.jsTruthy

// ================================================================================================
// KKBOX 曲库：批量歌曲 / 歌词 / 专辑 + 单曲补充信息
// ================================================================================================

/** 按 id 批量获取歌曲原始 json（映射请用 [mapSongToKkboxTrack]） */
suspend fun getSongs(sid: String, ids: List<String>): List<Map<String, Any?>> {
    val resp = kkboxApiCall(
        "ds",
        "v2/song",
        params = mapOf("sid" to sid),
        payload = mapOf(
            "ids" to ids.joinToString(","),
            "fields" to "song_id,encrypted_song_id,song_name,artist_name,album_name,artist_role,song_idx," +
                "album_photo_info,song_is_explicit,song_more_url,album_more_url,artist_more_url," +
                "genre_name,is_lyrics,audio_quality,album_date",
        ),
    )
    if (kkboxStatusType(resp) != "OK") throw IllegalStateException("获取歌曲信息失败")
    val data = resp["data"]
    val songs = if (data is Map<*, *>) data["songs"] else null
    return kkboxMapList(songs)
}

/** 获取歌词（原始 lrc + 翻译歌词，翻译恒为 null，对齐 RN 版） */
suspend fun getSongLyrics(sid: String, songId: String): KkboxLyric {
    val resp = kkboxApiCall("ds", "v1/song/$songId/lyrics", params = mapOf("sid" to sid))
    if (kkboxStatusType(resp) != "OK") {
        return KkboxLyric(lrc = "[00:00.00]暂无歌词", tlyric = null)
    }
    val data = resp["data"]
    val items = if (data is Map<*, *>) data["lyrics"] else null
    val list: List<Any?> = if (items is List<*>) items else emptyList()

    val sb = StringBuilder()
    for (item in list) {
        if (item !is Map<*, *>) continue
        val content = item["content"]
        if (!jsTruthy(content)) {
            sb.append('\n')
            continue
        }
        val timeMs = kkboxInt(item["start_time"])
        val min = timeMs / 60000
        val sec = (timeMs / 1000) % 60
        val ms = timeMs % 1000
        sb.append('[')
            .append(min.toString().padStart(2, '0'))
            .append(':')
            .append(sec.toString().padStart(2, '0'))
            .append('.')
            .append(ms.toString().padStart(3, '0'))
            .append(']')
            .append(content)
            .append('\n')
    }
    val lrc = sb.toString()
    return KkboxLyric(
        lrc = if (lrc.isNotEmpty()) lrc else "[00:00.00]暂无歌词",
        tlyric = null,
    )
}

/** 获取专辑信息与歌曲列表 */
suspend fun getKkboxAlbum(sid: String, albumId: String): KkboxAlbumData {
    val resp = kkboxApiCall("ds", "v1/album/$albumId", params = mapOf("sid" to sid))
    if (kkboxStatusType(resp) != "OK") throw IllegalStateException("获取专辑信息失败")
    val data = resp["data"]
    val albumRaw = if (data is Map<*, *>) data["album"] else null
    val album = kkboxAsMap(albumRaw) ?: emptyMap()
    val songsRaw = if (data is Map<*, *>) data["songs"] else null
    return KkboxAlbumData(album = album, songs = kkboxMapList(songsRaw))
}

/** 单曲补充信息（曲目号 / 发行时间 / 专辑歌手）；拿不到 sid 或失败时返回全 null */
suspend fun enrichKkboxSong(songId: String, albumId: String): KkboxEnrich {
    val empty = KkboxEnrich(null, null, null, null)
    val sid = kkboxSidProvider() ?: return empty
    if (albumId.isEmpty()) return empty
    return try {
        val data = getKkboxAlbum(sid, albumId)
        val rawId = songId.removePrefix("kkbox:")
        // 传进来的可能是加密 id（`song_more_url` 末段），而 `v1/album` 的曲目三种 id 都有：
        // 只比 `song_id`（数字）永远匹配不上，曲目号 / 发行时间会整批丢掉。
        val matched = data.songs.firstOrNull {
            kkboxStr(it["encrypted_song_id"]) == rawId ||
                (it["song_id"] ?: "").toString() == rawId ||
                kkboxStr(it["song_more_url"]).substringAfterLast('/') == rawId
        }
        KkboxEnrich(
            trackNumber = matched?.let { kkboxInt(it["song_idx"]) }?.takeIf { it > 0 }?.toString(),
            discNumber = null,
            releaseDate = matched?.let { (it["album_date"] ?: "").toString().ifEmpty { null } },
            albumArtist = (data.album["artist_name"] ?: "").toString().ifEmpty { null },
        )
    } catch (_: Throwable) {
        empty
    }
}
