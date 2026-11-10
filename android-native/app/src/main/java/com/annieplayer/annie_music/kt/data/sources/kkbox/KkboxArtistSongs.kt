package com.annieplayer.annie_music.kt.data.sources.kkbox

import com.annieplayer.annie_music.kt.data.model.ArtistSongsPageSize
import com.annieplayer.annie_music.kt.data.model.Song
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

// ================================================================================================
// KKBOX 歌手的**全部**歌曲（`v3/artist` 的 hot_song 只有 20 首，写不了「共 N 首」）
// ================================================================================================
//
// KKBOX 没有 `v1/artist/{id}/song` 这类接口（试过 v1/v2 的 song / songs / track，全 404）。
// 但它自己的搜索索引带 `artist_id` 过滤，且每页能取 200 条：
//
//   search_music.php?sf=song&query=<歌手名>&artist_id=<数字id>&limit=200&offset=N
//
// `artist_id` **不是硬过滤**（2026-11 实测：搜 G.E.M. 前 200 条里 28 条是别人的歌），
// 所以按条目的 `artist_ids` 再精确筛一遍。命中归零的页说明相关性已经掉出去了，
// 连着两页零命中就停 —— G.E.M.（255 首）4 页收敛，别家通常 1~2 页。
//
// 结果整份缓存：歌手页一次要「总数 + 分页」，重复扫 4 页纯属浪费。

data class KkboxArtistSongsPage(
    val songs: List<Song>,
    val total: Int,
)

private val _allSongsCache = HashMap<String, List<Song>>()

/** 缓存驻留的歌手数上限（一个歌手最多几百首，占用很小；留几个来回切） */
private const val MAX_CACHED_ARTISTS = 12

/** 每页最多 200 条（传 500 会被截到 200） */
private const val KkboxArtistSearchPage = 200

/** 首屏之后最多再并发几页（`song_total` 是模糊数，可能报到 1200+） */
private const val MAX_EXTRA_PAGES = 7

/** 一次取完整份（本地分页）；[page] 从 1 开始 */
suspend fun getKkboxArtistSongs(
    artistId: String,
    page: Int,
    pageSize: Int = ArtistSongsPageSize,
): KkboxArtistSongsPage {
    val all = _allSongsCache[artistId] ?: _loadArtistSongs(artistId).also {
        if (it.isNotEmpty()) {
            _allSongsCache[artistId] = it
            while (_allSongsCache.size > MAX_CACHED_ARTISTS) {
                val eldest = _allSongsCache.keys.firstOrNull { k -> k != artistId } ?: break
                _allSongsCache.remove(eldest)
            }
        }
    }
    if (all.isEmpty()) return KkboxArtistSongsPage(emptyList(), 0)
    val from = (page.coerceAtLeast(1) - 1) * pageSize
    if (from >= all.size) return KkboxArtistSongsPage(emptyList(), all.size)
    return KkboxArtistSongsPage(
        songs = all.subList(from, minOf(from + pageSize, all.size)).toList(),
        total = all.size,
    )
}

/** 清掉缓存（换账号 / 重新登录时用） */
fun clearKkboxArtistSongsCache() {
    _allSongsCache.clear()
    clearKkboxArtistCache()
}

private suspend fun _loadArtistSongs(artistId: String): List<Song> = coroutineScope {
    // 只用 `v3/artist`（约 1s），不碰专辑分页：专辑那 2s 由 `loadAlbums` 自己并行去等
    val profile = runCatching { getKkboxArtistProfile(artistId) }.getOrNull()
        ?: return@coroutineScope emptyList()
    val numeric = profile.numericId
    val name = profile.name
    // 拿不到数字 id / 名字就没法用搜索过滤：退回 hot_song（20 首），至少能播
    if (numeric.isEmpty() || name.isEmpty()) return@coroutineScope profile.hotSongs

    val out = LinkedHashMap<String, Song>()
    fun absorb(items: List<Map<String, Any?>>) {
        for (item in items) {
            val ids = kkboxStr(item["artist_ids"]).split(",").map { it.trim() }
            if (ids.none { it == numeric }) continue
            val track = mapSongToKkboxTrack(item)
            if (track.id.isEmpty()) continue
            out.putIfAbsent("kkbox:${track.id}", kkboxTrackToSong(track))
        }
    }

    // **一次并发打完前 4 页（800 条）**，不等第一页回来再决定页数：
    // 原来「先第 1 页拿 song_total（3.8s）→ 再并发其余（7.1s）」串成 11 秒，
    // 现在只等最慢那一页。800 条覆盖绝大多数歌手，万一 `song_total` 更大再补。
    val firstBatch = (0 until 4).map { i ->
        val offset = i * KkboxArtistSearchPage
        async {
            runCatching {
                _searchRaw("song", name, limit = KkboxArtistSearchPage, offset = offset, artistId = numeric)
            }.getOrNull()
        }
    }
    val pages = firstBatch.mapNotNull { it.await() }
    if (pages.isEmpty()) return@coroutineScope profile.hotSongs
    val firstItems = _searchItems(pages[0], "song_list", "song")
    if (firstItems.isEmpty()) return@coroutineScope profile.hotSongs
    for (p in pages) absorb(_searchItems(p, "song_list", "song"))

    // `song_total` 是**模糊**匹配总数（G.E.M. 报 721，按 artist_ids 筛完只有 255），
    // 只有当它明显超过已取范围时才继续补，避免白扫。
    val songTotal = kkboxInt(kkboxAsMap(pages[0]["search_info"])?.get("song_total"))
    val fetched = KkboxArtistSearchPage * 4
    if (songTotal > fetched) {
        val extraOffsets = (fetched until songTotal step KkboxArtistSearchPage)
            .take(MAX_EXTRA_PAGES)
            .toList()
        val jobs = extraOffsets.map { off ->
            async {
                runCatching {
                    _searchRaw("song", name, limit = KkboxArtistSearchPage, offset = off, artistId = numeric)
                }.getOrNull()
            }
        }
        for (job in jobs) {
            val resp = job.await() ?: continue
            absorb(_searchItems(resp, "song_list", "song"))
        }
    }

    if (out.isEmpty()) return@coroutineScope profile.hotSongs
    // 搜索索引未必收录 hot_song 里那些（新歌/独家），补进去但排在后面
    for (s in profile.hotSongs) out.putIfAbsent(s.id, s)
    out.values.toList()
}
