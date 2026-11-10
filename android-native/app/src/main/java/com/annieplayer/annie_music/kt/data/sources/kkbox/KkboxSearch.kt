package com.annieplayer.annie_music.kt.data.sources.kkbox

import com.annieplayer.annie_music.kt.data.model.Album
import com.annieplayer.annie_music.kt.data.model.Artist
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.sources.common.jsTruthy
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

// ================================================================================================
// KKBOX 搜索（`search_music.php`）
// ================================================================================================
//
// 参数走 **GET query**（不是 form body）。响应**没有 `status` 包装**，结果直接在顶层：
// `song_list.song` / `album_list.album` / `artist_list.artist`，翻页用 `offset`。
// 未登录（拿不到 sid）也能发，只是不带 sid。

private const val KkboxSearchLimit = 60

/** 搜索接口原始响应。`artistId` 传数字 id 时用于「该歌手的全部歌曲」（`KkboxArtistSongs`）。 */
internal suspend fun _searchRaw(
    sf: String,
    query: String,
    limit: Int,
    offset: Int,
    artistId: String? = null,
): Map<String, Any?> {
    val params = buildMap {
        put("sid", kkboxRequireSid())
        put("sf", sf)
        put("limit", limit.toString())
        if (offset > 0) put("offset", offset.toString())
        put("query", query)
        put("search_ranking", "sc-A")
        if (!artistId.isNullOrEmpty()) put("artist_id", artistId)
    }
    return kkboxApiCall("ds", "search_music.php", params = params)
}

internal fun _searchItems(resp: Map<String, Any?>, listKey: String, itemKey: String): List<Map<String, Any?>> {
    val holder = kkboxAsMap(resp[listKey]) ?: return emptyList()
    return kkboxMapList(holder[itemKey])
}

/** 单曲搜索 */
suspend fun searchKkboxSongs(
    query: String,
    page: Int = 1,
    limit: Int = KkboxSearchLimit,
): List<Song> {
    if (query.isBlank()) return emptyList()
    val resp = _searchRaw("song", query, limit, (page.coerceAtLeast(1) - 1) * limit)
    return _searchItems(resp, "song_list", "song")
        .filter { jsTruthy(it["song_is_auth"]) || it["song_is_auth"] == null }
        .mapNotNull { item ->
            val track = mapSongToKkboxTrack(item)
            if (track.id.isEmpty()) null else kkboxTrackToSong(track)
        }
}

/** 专辑搜索 */
suspend fun searchKkboxAlbums(
    query: String,
    page: Int = 1,
    limit: Int = KkboxSearchLimit,
): List<Album> {
    if (query.isBlank()) return emptyList()
    val resp = _searchRaw("album", query, limit, (page.coerceAtLeast(1) - 1) * limit)
    val albums = _searchItems(resp, "album_list", "album").mapNotNull { item ->
        val id = kkboxStr(item["encrypted_album_id"]).ifEmpty { kkboxStr(item["album_id"]) }
        if (id.isEmpty()) return@mapNotNull null
        val cover = kkboxPhotoCover(item["album_photo_info"])
        Album(
            albumid = id,
            name = kkboxStr(item["album_name"]).ifEmpty { "未知专辑" },
            artist = kkboxStr(item["artist_name"]).ifEmpty { "未知歌手" },
            platform = MusicPlatform.kkbox,
            musiccnt = "0",
            pic = cover,
            img = cover,
        )
    }
    return enrichKkboxAlbums(albums)
}

/**
 * 给专辑列表补上**发行时间**和**曲目数**。
 *
 * `search_music.php?sf=album` 的条目里这两个字段都没有（只有 `album_audio_quality`），
 * 而 `v1/album` **支持批量**：`?ids=a,b,c…` 返回 `data=[{album, songs[]}]`，
 * `album.album_date` 是发行时间、`songs.length` 就是曲目数。
 *
 * 实测代价（一次请求）：5 张 661ms / 20 张 1319ms / 50 张 2337ms —— 边际成本很低，
 * 所以按整批补，比逐张（每张 575ms）便宜一个数量级。
 *
 * [limit] 只补前这么多张：搜索结果动辄 60 条，全补要 2.6 秒，而第一屏只显示十几张。
 */
internal suspend fun enrichKkboxAlbums(
    albums: List<Album>,
    limit: Int = 20,
    batchSize: Int = 33,
): List<Album> {
    if (albums.isEmpty()) return albums
    val head = albums.take(limit)
    val ids = head.map { it.albumid }.filter { it.isNotEmpty() }
    if (ids.isEmpty()) return albums
    // 分片**并行**：实测 50 张单批 2.3s，拆成 33+33 两批并行约 1.7s。
    val byId = coroutineScope {
        val jobs = ids.chunked(batchSize).map { chunk ->
            async { _fetchAlbumMeta(chunk) }
        }
        val merged = HashMap<String, Pair<String, String>>(ids.size)
        for (job in jobs) merged.putAll(job.await())
        merged
    }
    if (byId.isEmpty()) return albums
    return albums.map { a ->
        val hit = byId[a.albumid] ?: return@map a
        a.copy(pub = hit.first.ifEmpty { a.pub }, musiccnt = hit.second)
    }
}

/** `v1/album?ids=` 一批 → `id -> (发行时间, 曲目数)` */
private suspend fun _fetchAlbumMeta(ids: List<String>): Map<String, Pair<String, String>> {
    val sid = kkboxSidProvider() ?: return emptyMap()
    val resp = runCatching {
        kkboxApiCall("ds", "v1/album", params = mapOf("sid" to sid, "ids" to ids.joinToString(",")))
    }.getOrNull() ?: return emptyMap()
    val data = resp["data"] as? List<*> ?: return emptyMap()
    val out = HashMap<String, Pair<String, String>>(data.size)
    for (raw in data) {
        val entry = kkboxAsMap(raw) ?: continue
        val album = kkboxAsMap(entry["album"]) ?: continue
        val id = kkboxStr(album["encrypted_album_id"]).ifEmpty { kkboxStr(album["album_id"]) }
        if (id.isEmpty()) continue
        val date = kkboxStr(album["album_date"])
        val count = (entry["songs"] as? List<*>)?.size ?: 0
        out[id] = date to count.toString()
    }
    return out
}

/** 歌手搜索 */
suspend fun searchKkboxArtists(
    query: String,
    page: Int = 1,
    limit: Int = KkboxSearchLimit,
): List<Artist> {
    if (query.isBlank()) return emptyList()
    val resp = _searchRaw("artist", query, limit, (page.coerceAtLeast(1) - 1) * limit)
    return _searchItems(resp, "artist_list", "artist").mapNotNull { item ->
        val id = kkboxStr(item["encrypted_artist_id"]).ifEmpty { kkboxStr(item["artist_id"]) }
        if (id.isEmpty()) return@mapNotNull null
        Artist(
            id = id,
            name = kkboxStr(item["artist_name"]).ifEmpty { "未知歌手" },
            picture = kkboxPhotoCover(item["artist_photo_info"]),
        )
    }
}

/** `*_photo_info.url_template` → 指定尺寸的封面；没有模板时退回它自带的 `url` */
internal fun kkboxPhotoCover(photo: Any?): String {
    val map = kkboxAsMap(photo) ?: return ""
    val template = kkboxStr(map["url_template"])
    if (template.isNotEmpty()) return getCoverUrl(template)
    return kkboxStr(map["url"])
}
