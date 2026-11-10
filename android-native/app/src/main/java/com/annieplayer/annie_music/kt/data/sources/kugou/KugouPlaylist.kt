package com.annieplayer.annie_music.kt.data.sources.kugou

import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Playlist
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.sources.common.decodeHtmlEntities

// ================================================================================================
// 酷狗歌单入口（gcid 短码 / 数字 specialid / 纯数字 id）
// ================================================================================================
//
// 取数只有一条路，见 [fetchKugouPlaylistDetail]：`get_other_list_file_nofilt` 的响应里
// - `list_info` 给歌单名 / 封面 / 创建者 / 曲目数，
// - `songs[]` 给曲目，且每首都带 **`singerinfo[]`（歌手 id / 名 / 头像）**、纯歌名、
//   `album_id` + `albuminfo.name` + `cover`。
//
// 所以这里只做「data → Playlist」的映射，不碰网络；映射全是纯函数，可离线单测。
//
// ## 音质
// 这条接口**不提供分档**（没有 `320hash` / `sqhash` / `*filesize`，`bitrate` 恒 128），
// 所以 [getKuGouQualities] 只会给出 128k，必须靠 [enrichKugouQualities] 按 hash 批量补
// `audio_info`。补不到就退回 128k，不影响播放 —— 与老接口的处境一样（Hi-Res 一直靠补）。

/** `list_info` 里能落进现有模型的字段。 */
internal data class KugouPlaylistMeta(
    val name: String,
    val cover: String,
    val creator: String,
)

/**
 * 获取酷狗歌单；失败返回 null。
 *
 * [input] 可以是四种形态，都归一成 `(global_collection_id, specialid)` 里的一个：
 * - `collection_…`（已经是 global_collection_id，直接用）
 * - `gcid_xxx` / 长串 → [decodeGcid] 换 `collection_…`
 * - 纯数字 → 直接当 `specialid`
 */
suspend fun getKugouPlaylist(input: String): Playlist? {
    val raw = input.trim()
    if (raw.isEmpty()) return null

    val isCollectionId = raw.startsWith("collection_")
    val looksLikeGcid = !isCollectionId &&
        (raw.contains("gcid_") || (!kugouReDigits.containsMatchIn(raw) && raw.length > 15))
    val globalCollectionId = when {
        isCollectionId -> raw
        looksLikeGcid -> runCatching {
            decodeGcid(kugouReGcid.find(raw)?.groupValues?.getOrNull(1) ?: raw)
        }.getOrNull().orEmpty()
        else -> ""
    }
    val specialId = if (isCollectionId || looksLikeGcid) "" else raw.replace(kugouReNonDigit, "")
    if (globalCollectionId.isEmpty() && specialId.isEmpty()) return null

    val data = fetchKugouPlaylistDetail(globalCollectionId, specialId) ?: return null
    val songs = enrichKugouQualities(kugouPlaylistSongsOf(data))
    if (songs.isEmpty()) return null
    val meta = kugouPlaylistMetaOf(data)
    return Playlist(
        id = input,
        name = meta.name,
        creator = meta.creator,
        cover = meta.cover,
        platform = MusicPlatform.kugou,
        songs = songs,
        songCount = songs.size,
    )
}

/** `data.list_info` → 歌单名 / 封面 / 创建者；缺什么给什么默认值。 */
internal fun kugouPlaylistMetaOf(data: Map<String, Any?>): KugouPlaylistMeta {
    val info = kugouAsMap(data["list_info"]) ?: emptyMap()
    return KugouPlaylistMeta(
        name = decodeHtmlEntities(kugouFirstNonEmpty(listOf(info["name"], "酷狗歌单"))),
        // `pic` 是 `{size}` 模板；没有就退回空串（UI 侧有唱片占位）
        cover = kugouFirstNonEmpty(listOf(info["pic"], info["cover"])).replace("{size}", "800"),
        creator = decodeHtmlEntities((info["list_create_username"] ?: "").toString()),
    )
}

/** `data.songs[]` → [Song]；单条缺 hash / 歌名的直接丢。 */
internal fun kugouPlaylistSongsOf(data: Map<String, Any?>): List<Song> {
    val list = data["songs"] as? List<*> ?: return emptyList()
    return list.mapNotNull { it as? Map<*, *> }.mapNotNull(::kugouPlaylistSongOf)
}

/**
 * 单条曲目 → [Song]。
 *
 * `singerinfo[]` 是这条接口相对老接口的核心差异：**多歌手的 id 一并给到**，
 * 所以 `artistIds` 从此不再是空的（老接口连歌手字段都没有）。
 * id 与 name 成对过滤 —— 只有一个时不完整，宁可整组不要，也不留半截映射。
 */
internal fun kugouPlaylistSongOf(item: Map<*, *>): Song? {
    val hash = (item["hash"] ?: "").toString().uppercase()
    if (hash.isEmpty()) return null
    val title = decodeHtmlEntities((item["name"] ?: "").toString())
    if (title.isEmpty()) return null

    val (names, ids) = kugouPlaylistSingers(item["singerinfo"])
    val album = item["albuminfo"] as? Map<*, *>
    val albumName = decodeHtmlEntities(
        kugouFirstNonEmpty(listOf(album?.get("name"), item["album_name"])),
    )
    val albumId = (item["album_id"] ?: "").toString().takeIf { it.isNotEmpty() && it != "0" }
    val cover = kugouFirstNonEmpty(listOf(item["cover"], album?.get("cover")))
        .replace("{size}", "800")
    val millis = (item["timelen"] as? Number)?.toLong() ?: 0L

    return Song(
        id = "kugou:$hash",
        title = title,
        artist = names.joinToString(" / ").ifEmpty { "未知歌手" },
        cover = cover,
        album = albumName.ifEmpty { null },
        albumId = albumId,
        duration = (millis / 1000L).toInt(),
        platform = MusicPlatform.kugou,
        playUrl = "",
        qualities = getKuGouQualities(item),
        qualitySizes = getKuGouQualitySizes(item),
        releaseDate = (item["publish_date"] ?: "").toString().ifEmpty { null },
        albumArtist = names.firstOrNull(),
        artistNames = names,
        artistIds = ids,
    )
}

/** `singerinfo[]` → (`names`, `ids`)，两个列表等长且下标对应。 */
internal fun kugouPlaylistSingers(raw: Any?): Pair<List<String>, List<String>> {
    val list = raw as? List<*> ?: return emptyList<String>() to emptyList()
    val pairs = list.mapNotNull { it as? Map<*, *> }
        .map {
            decodeHtmlEntities((it["name"] ?: "").toString()) to (it["id"] ?: "").toString()
        }
        .filter { (name, id) -> name.isNotEmpty() && id.isNotEmpty() && id != "0" }
    return pairs.map { it.first } to pairs.map { it.second }
}
