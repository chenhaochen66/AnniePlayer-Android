package com.annieplayer.annie_music.kt.data.sources.kkbox

import com.annieplayer.annie_music.kt.data.model.Album
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// ================================================================================================
// KKBOX 歌手页（`v3/artist/{encrypted_id}`）
// ================================================================================================
//
// `data` = `{profile, album[], hot_song[], related_artist[]}`：
//   profile.artist_name / artist_id(数字) / artist_bio / artist_photo_info
//   hot_song[]  与搜索结果同构（含 duration_ms / audio_quality / album_photo_info）
//   album[]     只有十几张（热门）
//
// **必须拆成两段**：`v3/artist` 约 1s（37KB），而全集专辑要靠数字 id 走
// `v2/artist/{数字}/album` 分页（每张 50，G.E.M. 两页 ≈ 2s）。原来把两者串在一起，
// 于是「拿歌手名去搜全部歌曲」这条根本不需要专辑的路径也要白等 2s —— 一进歌手页就是 3s 起步。
//
// 现在 [getKkboxArtistProfile] 只打 v3 并缓存，[getKkboxArtistPage] 在它之上补专辑，
// 两个都能各自被别人复用（搜索要 profile，专辑卡片要 page）。

data class KkboxArtistPage(
    val name: String,
    val bio: String,
    val picture: String,
    val songs: List<Song>,
    val albums: List<Album>,
    /** 数字 artist id：`v2/artist/{数字}/album` 与 `search_music.php?artist_id=` 都要它 */
    val artistId: String = "",
)

/** `v3/artist` 能直接给出的部分（不含全集专辑） */
data class KkboxArtistProfile(
    val name: String,
    val numericId: String,
    val bio: String,
    val picture: String,
    val hotSongs: List<Song>,
    /** v3 自带的热门专辑原始项，分页接口失败时兜底用 */
    val topAlbumItems: List<Map<String, Any?>>,
)

private data class _ProfileEntry(val at: Long, val profile: KkboxArtistProfile)

private data class _PageEntry(val at: Long, val page: KkboxArtistPage)

private var _profileCache: Pair<String, _ProfileEntry>? = null
private var _pageCache: Pair<String, _PageEntry>? = null

/** 同一份数据会被并发要多次（头像/简介/单曲/专辑各一次），让并发者等**一次**请求 */
private val _profileMutex = Mutex()
private val _pageMutex = Mutex()

private const val CACHE_TTL_MS = 120_000L

private fun _freshProfile(artistId: String): KkboxArtistProfile? = _profileCache?.let { (id, entry) ->
    if (id == artistId && System.currentTimeMillis() - entry.at < CACHE_TTL_MS) entry.profile else null
}

/** 歌手基本信息 + 热门歌曲（`v3/artist`，约 1s） */
suspend fun getKkboxArtistProfile(artistId: String): KkboxArtistProfile? {
    _freshProfile(artistId)?.let { return it }
    return _profileMutex.withLock {
        _freshProfile(artistId)?.let { return@withLock it }
        val profile = _fetchKkboxArtistProfile(artistId) ?: return@withLock null
        _profileCache = artistId to _ProfileEntry(System.currentTimeMillis(), profile)
        profile
    }
}

/** 完整歌手页（含全部专辑）。专辑分页在 [getKkboxArtistProfile] 之上叠加。 */
suspend fun getKkboxArtistPage(artistId: String): KkboxArtistPage? {
    fun fresh(): KkboxArtistPage? = _pageCache?.let { (id, entry) ->
        if (id == artistId && System.currentTimeMillis() - entry.at < CACHE_TTL_MS) entry.page else null
    }
    fresh()?.let { return it }
    return _pageMutex.withLock {
        fresh()?.let { return@withLock it }
        val profile = getKkboxArtistProfile(artistId) ?: return@withLock null
        val numeric = profile.numericId.toIntOrNull() ?: 0
        val albumItems = if (numeric > 0) {
            _artistAlbumsPaged(numeric).ifEmpty { profile.topAlbumItems }
        } else {
            profile.topAlbumItems
        }
        val page = KkboxArtistPage(
            name = profile.name,
            bio = profile.bio,
            picture = profile.picture,
            songs = profile.hotSongs,
            // 发行时间与曲目数只能靠 `v1/album?ids=` 批量补（`v2/artist/{id}/album` 没有这两个字段）。
            // 全部补上：66 张拆成两批并行约 1.7 秒，专辑 tab 有 loading 态，等得起。
            albums = enrichKkboxAlbums(
                _albumItemsToAlbums(albumItems, profile.name),
                limit = Int.MAX_VALUE,
            ),
            artistId = profile.numericId,
        )
        _pageCache = artistId to _PageEntry(System.currentTimeMillis(), page)
        page
    }
}

fun clearKkboxArtistCache() {
    _profileCache = null
    _pageCache = null
}

private suspend fun _fetchKkboxArtistProfile(artistId: String): KkboxArtistProfile? = runCatching {
    val raw = artistId.removePrefix("kkbox:")
    val resp = kkboxApiCall("ds", "v3/artist/$raw", params = mapOf("sid" to kkboxRequireSid()))
    val data = kkboxAsMap(resp["data"]) ?: return@runCatching null
    val profile = kkboxAsMap(data["profile"]) ?: emptyMap()
    val numericId = kkboxInt(profile["artist_id"])
    KkboxArtistProfile(
        name = kkboxStr(profile["artist_name"]),
        numericId = if (numericId > 0) numericId.toString() else "",
        bio = kkboxStr(profile["artist_bio"]),
        picture = kkboxPhotoCover(profile["artist_photo_info"]),
        hotSongs = kkboxMapList(data["hot_song"]).mapNotNull { item ->
            val track = mapSongToKkboxTrack(item)
            if (track.id.isEmpty()) null else kkboxTrackToSong(track)
        },
        topAlbumItems = kkboxMapList(data["album"]),
    )
}.getOrNull()

private fun _albumItemsToAlbums(
    items: List<Map<String, Any?>>,
    artistName: String,
): List<Album> = items.mapNotNull { item ->
    val id = kkboxStr(item["encrypted_album_id"]).ifEmpty { kkboxStr(item["album_id"]) }
    if (id.isEmpty()) return@mapNotNull null
    val cover = kkboxPhotoCover(item["album_photo_info"])
    Album(
        albumid = id,
        name = kkboxStr(item["album_name"]).ifEmpty { "未知专辑" },
        artist = artistName.ifEmpty { kkboxStr(item["artist_name"]) },
        platform = MusicPlatform.kkbox,
        musiccnt = "0",
        pub = kkboxStr(item["album_date"]),
        pic = cover,
        img = cover,
    )
}

/**
 * 歌手全部专辑（`v2/artist/{数字id}/album`，每页 50）。
 *
 * 前两页**并行**（大多数歌手 50~100 张，两页就完）；每页约 1s，串行会白等一倍。
 * 再往后（>100 张的极少数）继续串行，直到拿到不满 50 的一页。
 */
private suspend fun _artistAlbumsPaged(numericArtistId: Int): List<Map<String, Any?>> = coroutineScope {
    val sid = kkboxSidProvider() ?: return@coroutineScope emptyList()

    suspend fun page(offset: Int): List<Map<String, Any?>> {
        val resp = runCatching {
            kkboxApiCall(
                "ds",
                "v2/artist/$numericArtistId/album",
                params = mapOf("sid" to sid, "limit" to "50", "offset" to offset.toString()),
            )
        }.getOrNull() ?: return emptyList()
        val data = kkboxAsMap(resp["data"]) ?: return emptyList()
        return kkboxMapList(data["album"]).ifEmpty { kkboxMapList(data["albums"]) }
    }

    val firstDeferred = async { page(0) }
    val secondDeferred = async { page(50) }
    val first = firstDeferred.await()
    val second = secondDeferred.await()

    val out = ArrayList<Map<String, Any?>>(first.size + second.size)
    out += first
    out += second

    var offset = out.size
    var guard = 0
    // 两页都满 50 才可能有第三页（>100 张的极少数歌手）；后面每页不满 50 即结束
    if (first.size >= 50 && second.size >= 50) {
        while (guard < 8) {
            val next = page(offset)
            if (next.isEmpty()) break
            out += next
            offset += next.size
            guard++
            if (next.size < 50) break
        }
    }
    out.distinctBy { kkboxStr(it["encrypted_album_id"]).ifEmpty { kkboxStr(it["album_id"]) } }
}
