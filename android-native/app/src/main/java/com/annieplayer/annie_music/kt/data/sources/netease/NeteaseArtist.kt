package com.annieplayer.annie_music.kt.data.sources.netease

import com.annieplayer.annie_music.kt.data.model.Album
import com.annieplayer.annie_music.kt.data.model.ArtistSongsPage
import com.annieplayer.annie_music.kt.data.model.ArtistSongsPageSize
import com.annieplayer.annie_music.kt.data.network.httpGetJson

// ================================================================================================
// 网易云歌手（头像 / 简介 / 单曲分页 / 专辑）
// ================================================================================================

/** 网易云歌手头像（`/api/v1/artist/{id}` 的 `artist.picUrl`） */
suspend fun fetchNeteaseArtistAvatar(artistId: String): String {
    return try {
        val data = httpGetJson(
            "https://music.163.com/api/v1/artist/$artistId",
            neteaseApiHeaders,
        )
        val a = data["artist"]
        if (a is Map<*, *>) (a["picUrl"] ?: "").toString() else ""
    } catch (_: Throwable) {
        ""
    }
}

/** 网易云歌手简介：同一接口的 `artist.briefDesc`（免登录） */
suspend fun fetchNeteaseArtistBio(artistId: String): String {
    return try {
        val data = httpGetJson(
            "https://music.163.com/api/v1/artist/$artistId",
            neteaseApiHeaders,
        )
        val a = data["artist"]
        if (a is Map<*, *>) (a["briefDesc"] ?: "").toString().trim() else ""
    } catch (_: Throwable) {
        ""
    }
}

/**
 * 获取网易云歌手单曲（`/api/v1/artist/songs`，按 `limit`/`offset` 真分页）。
 *
 * 旧实现走 `/api/v1/artist/{id}` 的 `hotSongs`：只有热门约 50 首、且不带总数，
 * 导致详情页「歌曲 N」和翻页全错。该接口同时返回真实总数 `total`。
 */
suspend fun getNeteaseArtistSongs(artistId: String, page: Int = 1): ArtistSongsPage =
    getNeteaseArtistSongsRange(
        artistId = artistId,
        limit = ArtistSongsPageSize,
        offset = (page - 1) * ArtistSongsPageSize,
    )

/**
 * 按 `limit` / `offset` 直接取一段（「全部单曲」页续拉用）。
 *
 * **`limit` 硬上限 200**：`limit=500` / `1000` 实测都只回 200 条，所以续拉粒度就是 200。
 */
suspend fun getNeteaseArtistSongsRange(
    artistId: String,
    limit: Int,
    offset: Int,
): ArtistSongsPage {
    return try {
        val data = httpGetJson(
            "https://music.163.com/api/v1/artist/songs?id=$artistId" +
                "&limit=${limit.coerceIn(1, 200)}&offset=$offset",
            neteaseApiHeaders,
        )
        val songsRaw = data["songs"]
        val list = if (songsRaw is List<*>) songsRaw else emptyList<Any?>()
        val total = (data["total"] as? Number)?.toInt() ?: 0
        ArtistSongsPage(neteaseListOfMaps(list).map { neteaseArtistSong(it) }, total)
    } catch (_: Throwable) {
        ArtistSongsPage(emptyList(), 0)
    }
}

/** 网易云歌手专辑（`/api/artist/albums/{id}`，一页 100 张，最多拉到 3000 张） */
suspend fun getNeteaseArtistAlbums(artistId: String): List<Album> {
    val out = mutableListOf<Album>()
    return try {
        var offset = 0
        while (offset < 3000) {
            val data = httpGetJson(
                "https://music.163.com/api/artist/albums/$artistId?limit=100&offset=$offset",
                neteaseApiHeaders,
            )
            val raw = data["hotAlbums"]
            val list = if (raw is List<*>) raw else emptyList<Any?>()
            if (list.isEmpty()) break
            out += neteaseMapAlbums(list)
            if (list.size < 100) break
            offset += 100
        }
        out
    } catch (_: Throwable) {
        out
    }
}
