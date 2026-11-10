package com.annieplayer.annie_music.kt.data.sources.kkbox

import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Playlist

// ================================================================================================
// KKBOX 歌单（`v1/playlists?playlist_ids=`）
// ================================================================================================
//
// 返回 `data.playlists[]`：`title` / `content` / `cover_photo_info` / `user{name}` /
// `songs[]`（**直接是数组**，不是 `{song:[...]}`）。songs 的字段与搜索结果同构，
// 所以时长（`duration_ms`）与可用档位（`audio_quality`）都拿得到。

/**
 * 获取 KKBOX 歌单；失败返回 null。
 *
 * [playlistId] 就是分享链接里那一段（例 `https://www.kkbox.com/hk/tc/playlist/KmD_qgizu5MxGHHvaz`）。
 */
suspend fun getKkboxPlaylist(playlistId: String): Playlist? = runCatching {
    val params = buildMap {
        put("sid", kkboxRequireSid())
        put("playlist_ids", playlistId)
    }
    val resp = kkboxApiCall("ds", "v1/playlists", params = params)
    val data = kkboxAsMap(resp["data"]) ?: return@runCatching null
    val playlist = kkboxMapList(data["playlists"]).firstOrNull() ?: return@runCatching null

    val songs = kkboxMapList(playlist["songs"])
        .mapNotNull { item ->
            val track = mapSongToKkboxTrack(item)
            if (track.id.isEmpty()) null else kkboxTrackToSong(track)
        }
    Playlist(
        id = playlistId,
        name = kkboxStr(playlist["title"]).ifEmpty { "KKBOX 歌单" },
        creator = (kkboxAsMap(playlist["user"])?.get("name") ?: "").toString(),
        cover = kkboxPhotoCover(playlist["cover_photo_info"]),
        platform = MusicPlatform.kkbox,
        songs = songs,
        songCount = songs.size,
    )
}.getOrNull()
