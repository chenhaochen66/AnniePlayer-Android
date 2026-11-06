package com.annieplayer.annie_music.kt.data.sources.kugou

import com.annieplayer.annie_music.kt.data.model.AlbumSong
import com.annieplayer.annie_music.kt.data.model.AlbumSongsPage
import com.annieplayer.annie_music.kt.data.network.httpGetJson
import com.annieplayer.annie_music.kt.data.sources.common.decodeHtmlEntities
import com.annieplayer.annie_music.kt.data.sources.common.signParams

// ================================================================================================
// 酷狗专辑：曲目（签名接口）+ 专辑信息
// ================================================================================================

/**
 * 签名专辑接口（gateway `union/album/audios`）：曲目号/碟号/歌手 id/音质大小齐全；失败返回 null。
 *
 * `pagesize` 上限 50：传 1000 服务端直接返回 20010 invalid param（实测），故按 50 分页。
 */
private suspend fun fetchKugouAlbumSongsSigned(albumId: String): List<AlbumSong>? {
    val out = ArrayList<AlbumSong>()
    try {
        var page = 1
        while (page <= 40) {
            val ct = System.currentTimeMillis().toString()
            val params = linkedMapOf(
                "album_id" to albumId,
                "appid" to "1005",
                "area_code" to "1",
                "clienttime" to ct,
                "clientver" to "20729",
                "dfid" to "-",
                "is_buy" to "0",
                "mid" to ct,
                "page" to page.toString(),
                "pagesize" to "50",
                "show_album_audios_timelength" to "1",
                "show_album_info" to "1",
                "show_album_tags" to "1",
                "show_awards" to "1",
                "show_classical_author" to "1",
                "show_dycover" to "1",
                "show_short_intro_v2" to "1",
                "userid" to "0",
            )
            val sig = signParams(params, kugouKeyA)
            val qs = params.entries.joinToString("&") { "${it.key}=${it.value}" }
            val raw = httpGetJson(
                "https://gateway.kugou.com/openapi/v1/union/album/audios?$qs&signature=$sig",
                headers = mapOf(
                    "User-Agent" to "Android16-AndroidPhone-20729-46-0-AlbumDetailFragment",
                    "kg-thash" to "228424f",
                    "kg-rc" to "1",
                    "kg-fake" to "0",
                    "kg-rf" to "DC6373DBF6F823AF925CC56D1A81DD72",
                ),
            )
            val songData = (raw["data"] as? Map<*, *>)?.get("song_data") as? Map<*, *> ?: break
            val list = songData["song_list"] as? List<*> ?: break
            if (list.isEmpty()) break
            out += list.filterIsInstance<Map<*, *>>().map { s ->
                val ai = s["audio_info"] as? Map<*, *> ?: emptyMap<String, Any?>()
                val base = s["base"] as? Map<*, *> ?: emptyMap<String, Any?>()
                val ext = s["extend"] as? Map<*, *> ?: emptyMap<String, Any?>()
                val authors = (s["authors"] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()
                val hash = (ai["hash"] ?: "").toString()
                val artistNames = authors
                    .map { decodeHtmlEntities((it["author_name"] ?: "").toString()) }
                    .filter { it.isNotEmpty() }
                val artistIds = authors.map { (it["author_id"] ?: "").toString() }.filter { it.isNotEmpty() }
                val dur = ai["duration"]
                AlbumSong(
                    id = "kugou:$hash",
                    name = decodeHtmlEntities((base["audio_name"] ?: "").toString()),
                    artistName = artistNames.joinToString(" / "),
                    duration = (when (dur) {
                        is Number -> dur.toLong()
                        else -> dur?.toString()?.toLongOrNull() ?: 0L
                    } / 1000).toInt(),
                    track = ext["sort"]?.toString()?.toIntOrNull(),
                    discNumber = ext["disc"]?.toString()?.toIntOrNull(),
                    qualities = getKuGouQualities(ai),
                    qualitySizes = getKuGouQualitySizes(ai),
                    artistNames = artistNames,
                    artistIds = if (artistIds.size == artistNames.size) artistIds else emptyList(),
                )
            }
            if (list.size < 50) break
            page++
        }
    } catch (_: Throwable) {
        return null
    }
    return out.ifEmpty { null }
}

suspend fun getKugouAlbumSongs(albumId: String): AlbumSongsPage {
    val songs = fetchKugouAlbumSongsSigned(albumId) ?: return AlbumSongsPage(emptyList())
    return AlbumSongsPage(songs, fetchKugouAlbumInfo(albumId).pub)
}

/** 酷狗专辑信息：发行日期 / 专辑歌手（`album/song` 都不带，需另取 `album/info`） */
internal suspend fun fetchKugouAlbumInfo(albumId: String): KugouAlbumInfo {
    return try {
        val info = httpGetJson(
            "http://mobilecdn.kugou.com/api/v3/album/info?albumid=$albumId&version=9108&plat=0",
            headers = mapOf("User-Agent" to kugouWebUa),
        )
        val d = info["data"]
        if (d is Map<*, *>) {
            KugouAlbumInfo(
                pub = (d["publishtime"] ?: "").toString().trim().take(10),
                albumArtist = decodeHtmlEntities((d["singername"] ?: "").toString()),
            )
        } else {
            KugouAlbumInfo("", "")
        }
    } catch (_: Throwable) {
        KugouAlbumInfo("", "")
    }
}
