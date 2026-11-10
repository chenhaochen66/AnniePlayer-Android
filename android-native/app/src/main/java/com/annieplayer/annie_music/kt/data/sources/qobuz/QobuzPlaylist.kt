package com.annieplayer.annie_music.kt.data.sources.qobuz

import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Playlist
import com.annieplayer.annie_music.kt.data.network.retryUntilSuccess
import com.annieplayer.annie_music.kt.data.sources.common.encodeComponent
import com.annieplayer.annie_music.kt.data.sources.common.jsTruthy

// ================================================================================================
// Qobuz 歌单（`playlist/get`，按 limit/offset 翻页）
// ================================================================================================
//
// 与 `album/get` 一样**不需要 token**（token 只用于 getFileUrl 与 user/get）。

private const val QobuzPlaylistPageSize = 500

private suspend fun _playlistPage(playlistId: String, offset: Int): Map<String, Any?> {
    val params = mapOf(
        "app_id" to qobuzAppId,
        "playlist_id" to playlistId,
        "extra" to "tracks",
        "limit" to QobuzPlaylistPageSize.toString(),
        "offset" to offset.toString(),
    )
    val s = createSignature("playlist/get", params)
    val qs = params.entries.joinToString("&") { "${it.key}=${encodeComponent(it.value)}" } +
        "&request_ts=${s.ts}&request_sig=${s.sig}"
    return retryUntilSuccess(maxRetries = 2, delayMs = 500L) {
        qobuzGetJson("${qobuzBaseUrl}playlist/get?$qs", "")
    }
}

/**
 * 获取 Qobuz 歌单；失败返回 null。
 *
 * 封面取 `images` 数组的第一项：歌单**没有**专辑那种 `image.large`，那个字段只在单曲上。
 * 歌单里的每条 track 自带 `album`（含自己的 `image.large`），所以逐曲封面用得上，
 * 只有缺 `album` 时才回落到歌单封面。
 */
suspend fun getQobuzPlaylist(playlistId: String): Playlist? = runCatching {
    val first = _playlistPage(playlistId, 0)
    val container = qobuzAsMap(first["tracks"])
    val firstItems = qobuzContainerItems(container)
    val total = qobuzIntOr(container?.get("total"), firstItems.size)

    val rawItems = firstItems.toMutableList()
    while (rawItems.size < total) {
        val items = qobuzContainerItems(qobuzAsMap(_playlistPage(playlistId, rawItems.size)["tracks"]))
        if (items.isEmpty()) break
        rawItems.addAll(items)
    }

    val cover = qobuzMaxCover((first["images"] as? List<*>)?.firstOrNull()) as? String ?: ""
    val songs = rawItems.mapNotNull { raw ->
        val item = qobuzAsMap(raw) ?: return@mapNotNull null
        val merged = LinkedHashMap<String, Any?>(item)
        if (merged["album"] == null) merged["album"] = mapOf("image" to mapOf("large" to cover))
        val track = mapTrackToQobuzTrack(merged)
        if (track.id.isEmpty()) null else qobuzTrackToSong(track, jsTruthy(item["hires"]))
    }

    Playlist(
        id = playlistId,
        name = (first["name"] as? String).orEmpty(),
        creator = qobuzAsMap(first["owner"])?.get("name")?.toString(),
        cover = cover,
        platform = MusicPlatform.qobuz,
        songs = songs,
        songCount = songs.size,
    )
}.getOrNull()
