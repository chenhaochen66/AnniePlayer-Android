package com.annieplayer.annie_music.kt.data.sources.kugou

import com.annieplayer.annie_music.kt.data.network.httpGetJson
import com.annieplayer.annie_music.kt.data.network.httpPostJson
import com.annieplayer.annie_music.kt.data.sources.common.jsTruthy
import com.annieplayer.annie_music.kt.data.sources.common.signParams
import java.util.concurrent.ConcurrentHashMap

// ================================================================================================
// 酷狗单曲补元信息（专辑 id / 曲目号 / 碟号 / 发行日期 / 专辑歌手）
// ================================================================================================

suspend fun getKugouSongAlbumId(songHash: String): KugouSongAlbumId? {
    try {
        val payload: Map<String, Any?> = mapOf(
            "data" to listOf(mapOf("hash" to songHash)),
            "area_code" to "1",
            "show_privilege" to 1,
            "show_album_info" to "1",
            "is_publish" to "",
            "appid" to 1005,
            "clientver" to 11451,
            "mid" to "1",
            "dfid" to "-",
            "clienttime" to System.currentTimeMillis(),
            "key" to kugouKeyA,
            "fields" to
                "album_info,author_name,audio_info,base,songname,classification",
        )
        val data = httpPostJson(
            "http://gateway.kugou.com/v3/album_audio/audio",
            body = payload,
            headers = mapOf(
                "KG-THash" to "13a3164",
                "KG-RC" to "1",
                "KG-Fake" to "0",
                "KG-RF" to "00869891",
                "User-Agent" to
                    "Android712-AndroidPhone-11451-376-0-FeeCacheUpdate-wifi",
                "x-router" to "kmr.service.kugou.com",
                "Content-Type" to "application/json",
            ),
        )
        val outer = data["data"]
        if (outer !is List<*> || outer.isEmpty()) return null
        val inner = outer[0]
        if (inner !is List<*> || inner.isEmpty()) return null
        val item = inner[0]
        if (item !is Map<*, *>) return null
        val albumInfo = item["album_info"]
        if (albumInfo !is Map<*, *> || !jsTruthy(albumInfo["album_id"])) {
            return null
        }
        return KugouSongAlbumId(
            albumId = albumInfo["album_id"].toString(),
            publishDate = (albumInfo["publish_date"] ?: "").toString(),
        )
    } catch (_: Throwable) {
        return null
    }
}

private val _kugouAlbumTracksCache =
    ConcurrentHashMap<String, Map<String, KugouAlbumTrack>>()

suspend fun fetchKugouAlbumTracks(
    albumId: String,
): Map<String, KugouAlbumTrack> {
    val cached = _kugouAlbumTracksCache[albumId]
    if (cached != null) return cached
    try {
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
            "page" to "1",
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
            "https://gateway.kugou.com/openapi/v1/union/album/audios" +
                "?$qs&signature=$sig",
            headers = mapOf(
                "User-Agent" to
                    "Android16-AndroidPhone-20729-46-0-AlbumDetailFragment",
                "kg-thash" to "228424f",
                "kg-rc" to "1",
                "kg-fake" to "0",
                "kg-rf" to "DC6373DBF6F823AF925CC56D1A81DD72",
            ),
        )
        val data = raw["data"]
        val songData = if (data is Map<*, *>) data["song_data"] else null
        val list = if (songData is Map<*, *>) songData["song_list"] else null
        val lookup = HashMap<String, KugouAlbumTrack>()
        if (list is List<*>) {
            for (s in list) {
                if (s !is Map<*, *>) continue
                val audioInfo = s["audio_info"]
                val h = (
                    if (audioInfo is Map<*, *>) {
                        audioInfo["hash"] ?: ""
                    } else {
                        ""
                    }
                    ).toString()
                if (h.isNotEmpty()) {
                    val extend = s["extend"]
                    lookup[h.uppercase()] = KugouAlbumTrack(
                        sort = kugouFirstNonEmpty(
                            listOf(
                                if (extend is Map<*, *>) extend["sort"] else null,
                            ),
                        ),
                        disc = kugouFirstNonEmpty(
                            listOf(
                                if (extend is Map<*, *>) extend["disc"] else null,
                            ),
                        ),
                    )
                }
            }
        }
        _kugouAlbumTracksCache[albumId] = lookup
        return lookup
    } catch (_: Throwable) {
        return emptyMap()
    }
}

suspend fun enrichKuGouSong(songId: String): KugouSongEnrich {
    val hash = songId.replaceFirst(Regex("^kugou:"), "").uppercase()
    try {
        val info = getKugouSongAlbumId(hash)
            ?: return KugouSongEnrich(null, null, null, null)
        val lookup = fetchKugouAlbumTracks(info.albumId)
        val matched = lookup[hash]
        return KugouSongEnrich(
            trackNumber = matched?.sort,
            discNumber = matched?.disc,
            releaseDate = if (info.publishDate.isNotEmpty()) {
                info.publishDate
            } else {
                null
            },
            albumArtist = fetchKugouAlbumInfo(info.albumId).albumArtist.ifEmpty { null },
        )
    } catch (_: Throwable) {
        return KugouSongEnrich(null, null, null, null)
    }
}
