package com.annieplayer.annie_music.kt.data.sources.qobuz

import com.annieplayer.annie_music.kt.data.model.Album
import com.annieplayer.annie_music.kt.data.model.Artist
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.network.retryUntilSuccess
import com.annieplayer.annie_music.kt.data.sources.common.encodeComponent
import com.annieplayer.annie_music.kt.data.sources.common.firstTruthy
import com.annieplayer.annie_music.kt.data.sources.common.jsTruthy

// ================================================================================================
// Qobuz 搜索 + 歌手页
// ================================================================================================
//
// `catalog/search` 与 `artist/get` 都**免 token、免签名**，只要 `app_id`。

enum class QobuzSearchType(val value: String) {
    tracks("tracks"),
    albums("albums"),
    artists("artists"),
}

private suspend fun _searchQobuzRaw(
    type: QobuzSearchType,
    query: String,
    limit: Int,
    offset: Int,
): Map<String, Any?> {
    val url = "${qobuzBaseUrl}catalog/search?query=${encodeComponent(query)}" +
        "&type=${type.value}&limit=$limit&offset=$offset&app_id=$qobuzAppId"
    return retryUntilSuccess { qobuzGetJson(url, "") }
}

private suspend fun _searchQobuzItems(
    type: QobuzSearchType,
    query: String,
    limit: Int,
    offset: Int = 0,
): List<Map<String, Any?>> =
    qobuzContainerItems(_searchQobuzRaw(type, query, limit, offset)[type.value])
        .mapNotNull { qobuzAsMap(it) }

suspend fun searchQobuzTracks(query: String, limit: Int = 60, offset: Int = 0): List<Song> =
    _searchQobuzItems(QobuzSearchType.tracks, query, limit, offset)
        .map { qobuzTrackToSong(mapTrackToQobuzTrack(it), hires = jsTruthy(it["hires"])) }

suspend fun searchQobuzAlbums(query: String, limit: Int = 30, offset: Int = 0): List<Album> =
    _searchQobuzItems(QobuzSearchType.albums, query, limit, offset)
        .map { qobuzAlbumToAlbum(mapAlbumToQobuzAlbum(it)) }

suspend fun searchQobuzArtists(query: String, limit: Int = 60, offset: Int = 0): List<Artist> =
    _searchQobuzItems(QobuzSearchType.artists, query, limit, offset).map { r ->
        Artist(
            id = r["id"]?.toString() ?: "",
            name = (firstTruthy(listOf(r["name"])) ?: "").toString(),
            picture = (qobuzDirectLarge(r["image"]) ?: "").toString(),
            musicNum = qobuzIntOr(r["albums_count"], 0),
        )
    }

// ── 歌手详情（artist/get：免 token） ──

private suspend fun fetchArtistJson(
    artistId: String,
    limit: Int = 100,
    offset: Int = 0,
): Map<String, Any?> {
    val url = "${qobuzBaseUrl}artist/get?artist_id=$artistId&app_id=$qobuzAppId" +
        "&extra=albums,playlists,tracks_appears_on,albums_with_last_release,focusAll" +
        "&limit=$limit&offset=$offset"
    return retryUntilSuccess { qobuzGetJson(url, "") }
}

/** 歌手头像（`artist/get` 的 `image.large`）；失败或缺失返回空串 */
suspend fun fetchQobuzArtistPicture(artistId: String): String = try {
    (qobuzDirectLarge(fetchArtistJson(artistId)["image"]) ?: "").toString()
} catch (_: Throwable) {
    ""
}

/**
 * 歌手详情数据。Qobuz 没有"歌手全部单曲"接口，故歌曲 = `tracks_appears_on`
 * ∪ 按歌手名搜索后按 performer.id 过滤（前者量少、后者量足），专辑取其 albums 全量。
 */
suspend fun getQobuzArtistPage(artistId: String, artistName: String = ""): QobuzArtistPage {
    val raw = fetchArtistJson(artistId)
    val name = (firstTruthy(listOf(raw["name"])) ?: artistName).toString()
    val picture = (qobuzDirectLarge(raw["image"]) ?: "").toString()
    val albums = qobuzContainerItems(raw["albums"])
        .mapNotNull { qobuzAsMap(it) }
        .map { qobuzAlbumToAlbum(mapAlbumToQobuzAlbum(it)) }

    val appearsOn = qobuzContainerItems(raw["tracks_appears_on"])
        .mapNotNull { qobuzAsMap(it) }
        .map { qobuzTrackToSong(mapTrackToQobuzTrack(it), hires = jsTruthy(it["hires"])) }

    val byNameSearch = if (name.isEmpty()) {
        emptyList()
    } else {
        runCatching {
            _searchQobuzItems(QobuzSearchType.tracks, name, 60)
                .filter { r -> (qobuzAsMap(r["performer"])?.get("id")?.toString() ?: "") == artistId }
                .map { qobuzTrackToSong(mapTrackToQobuzTrack(it), hires = jsTruthy(it["hires"])) }
        }.getOrDefault(emptyList())
    }

    return QobuzArtistPage(
        name = name,
        picture = picture,
        albums = albums,
        songs = (appearsOn + byNameSearch).distinctBy { it.id },
    )
}
