package com.annieplayer.annie_music.kt.data.sources.kugou

import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Playlist
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.network.httpGetJson
import com.annieplayer.annie_music.kt.data.sources.common.decodeHtmlEntities
import com.annieplayer.annie_music.kt.data.sources.common.firstTruthy
import com.annieplayer.annie_music.kt.data.sources.common.signParams
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.ceil
import kotlin.math.floor

// ================================================================================================
// 酷狗歌单入口（GCID 路径 / 数字 id 路径）
// ================================================================================================

private fun parseSongDuration(durRaw: Any?): Int {
    if (durRaw is Number) {
        if (durRaw.toDouble() > 10000) {
            return floor(durRaw.toDouble() / 1000).toInt()
        }
        return durRaw.toInt()
    }
    return 0
}

suspend fun getKugouPlaylist(input: String): Playlist? {
    try {
        var songs: List<Song> = emptyList()
        var name = ""
        var cover = ""

        // GCID 路径
        if (input.contains("gcid_") ||
            (!kugouReDigits.containsMatchIn(input) && input.length > 15)
        ) {
            val gcidMatch = kugouReGcid.find(input)
            val gcid = (gcidMatch?.groupValues?.getOrNull(1) ?: input).lowercase()
            val globalId = decodeGcid(gcid)

            coroutineScope {
                val infoDeferred = async { fetchPlaylistInfo(globalId) }
                val pageDeferred = async { fetchSongPage(globalId, 1, 300) }
                val info = infoDeferred.await()
                val firstPage = pageDeferred.await()

                name = info.name
                cover = info.cover
                val rawSongs = ArrayList<Any?>()
                val infoRaw = firstPage["info"]
                if (infoRaw is List<*>) {
                    for (e in infoRaw) rawSongs.add(e)
                }
                val totalRaw = firstPage["total"]
                val total = if (totalRaw is Number) totalRaw.toInt() else 0
                val pages = ceil(total / 300.0).toInt()
                if (pages > 1) {
                    val rest = coroutineScope {
                        (2..pages).map { p ->
                            async { fetchSongPage(globalId, p, 300) }
                        }.awaitAll()
                    }
                    for (page in rest) {
                        val pageInfo = page["info"]
                        if (pageInfo is List<*>) {
                            for (e in pageInfo) rawSongs.add(e)
                        }
                    }
                }

                // CDN 补充封面与专辑名
                val enrichMap = HashMap<String, KugouCdnEnrich>()
                try {
                    val ct2 = System.currentTimeMillis().toString()
                    val params2 = linkedMapOf(
                        "appid" to "1058",
                        "clienttime" to ct2,
                        "clientver" to "20000",
                        "global_collection_id" to globalId,
                        "mid" to ct2,
                        "module" to "playlist",
                        "page" to "1",
                        "pagesize" to "300",
                        "specialid" to "0",
                        "srcappid" to "2919",
                        "type" to "0",
                    )
                    val sig2 = signParams(params2, kugouKeyB, "")
                    val qs2 = params2.entries.joinToString("&") {
                        "${it.key}=${it.value}"
                    }
                    val cdnData = httpGetJson(
                        "https://pubsongscdn.kugou.com/v2/get_other_list_file" +
                            "?$qs2&signature=$sig2",
                        headers = mapOf(
                            "User-Agent" to
                                "Mozilla/5.0 (iPhone; CPU iPhone OS 11_0)",
                            "Referer" to
                                "https://m3ws.kugou.com/share/index.php",
                        ),
                    )
                    if (kugouNumEq(cdnData["error_code"], 0)) {
                        val cdnDataField = cdnData["data"]
                        val cdnInfo = if (cdnDataField is Map<*, *>) {
                            cdnDataField["info"]
                        } else {
                            null
                        }
                        if (cdnInfo is List<*>) {
                            for (s in cdnInfo) {
                                if (s !is Map<*, *>) continue
                                val h = (s["hash"] ?: "").toString()
                                if (h.isNotEmpty()) {
                                    val albuminfo = s["albuminfo"]
                                    enrichMap[h.uppercase()] = KugouCdnEnrich(
                                        cover = (s["cover"] ?: "").toString(),
                                        albumName = (if (albuminfo is Map<*, *>) {
                                            albuminfo["name"] ?: ""
                                        } else {
                                            ""
                                        }).toString(),
                                        publishDate = (s["publish_date"] ?: "")
                                            .toString(),
                                    )
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {
                    // 与 Dart 一致：忽略
                }

                val built = ArrayList<Song>()
                for (s in rawSongs) {
                    val m = kugouAsMap(s) ?: continue
                    val hash = (m["hash"] ?: "").toString().uppercase()
                    val enriched = enrichMap[hash]
                    val filename = m["filename"] as? String
                    val artist = decodeHtmlEntities(
                        kugouFirstNonEmpty(
                            listOf(
                                m["singername"],
                                filename?.split(" - ")?.firstOrNull(),
                                "未知歌手",
                            ),
                        ),
                    )
                    val parts = filename?.split(" - ")
                    val titlePart = if (parts != null && parts.size > 1) {
                        kugouReExt.replace(parts[1], "")
                    } else {
                        null
                    }
                    val transParam = m["trans_param"]
                    built.add(
                        Song(
                            id = "kugou:${m["hash"] ?: ""}",
                            title = decodeHtmlEntities(
                                kugouFirstNonEmpty(
                                    listOf(m["songname"], titlePart, "未知歌曲"),
                                ),
                            ),
                            artist = artist,
                            cover = kugouFirstNonEmpty(
                                listOf(
                                    m["cover"],
                                    enriched?.cover,
                                    if (transParam is Map<*, *>) {
                                        transParam["union_cover"]
                                    } else {
                                        null
                                    },
                                ),
                            ).replace("{size}", "800"),
                            album = decodeHtmlEntities(
                                kugouFirstNonEmpty(
                                    listOf(m["album_name"], enriched?.albumName),
                                ),
                            ),
                            albumId = kugouOptString(m["album_id"]),
                            duration = parseSongDuration(
                                firstTruthy(
                                    listOf(m["timelen"], m["duration"]),
                                ),
                            ),
                            platform = MusicPlatform.kugou,
                            playUrl = "",
                            qualities = getKuGouQualities(m),
                            qualitySizes = getKuGouQualitySizes(m),
                            releaseDate = kugouOptString(
                                firstTruthy(
                                    listOf(
                                        enriched?.publishDate,
                                        m["_publish_date"],
                                    ),
                                ),
                            ),
                            albumArtist = artist,
                        ),
                    )
                }
                songs = built
            }
        } else {
            // 数字 ID 路径
            val pid = kugouReNonDigit.replace(input, "")
            val page = fetchSpecialPage(pid)
            name = page.name
            cover = page.cover
            val built = ArrayList<Song>()
            for (s in page.songs) {
                val m = kugouAsMap(s) ?: continue
                val filename = m["filename"] as? String
                val artist = decodeHtmlEntities(
                    kugouFirstNonEmpty(
                        listOf(
                            m["singername"],
                            filename?.split(" - ")?.firstOrNull(),
                            "未知歌手",
                        ),
                    ),
                )
                val parts = filename?.split(" - ")
                val titlePart = if (parts != null && parts.size > 1) {
                    kugouReExt.replace(parts[1], "")
                } else {
                    null
                }
                built.add(
                    Song(
                        id = "kugou:${m["hash"] ?: ""}",
                        title = decodeHtmlEntities(
                            kugouFirstNonEmpty(
                                listOf(m["songname"], titlePart, "未知歌曲"),
                            ),
                        ),
                        artist = artist,
                        cover = kugouFirstNonEmpty(listOf(m["cover"], m["_cover"]))
                            .replace("{size}", "800"),
                        album = decodeHtmlEntities(
                            kugouFirstNonEmpty(
                                listOf(m["album_name"], m["_album_name"]),
                            ),
                        ),
                        albumId = kugouOptString(m["album_id"]),
                        duration = parseSongDuration(
                            firstTruthy(listOf(m["timelen"], m["duration"])),
                        ),
                        platform = MusicPlatform.kugou,
                        playUrl = "",
                        qualities = getKuGouQualities(m),
                        qualitySizes = getKuGouQualitySizes(m),
                        releaseDate = kugouOptString(m["_publish_date"]),
                        albumArtist = artist,
                    ),
                )
            }
            songs = built
        }

        if (songs.isEmpty()) return null
        // 歌单接口不含 Hi-Res → 批量补 `audio_info`（`hash_high`/`filesize_high`）
        songs = enrichKugouQualities(songs)
        return Playlist(
            id = input,
            name = name,
            creator = "",
            cover = cover,
            platform = MusicPlatform.kugou,
            songs = songs,
            songCount = songs.size,
        )
    } catch (_: Throwable) {
        return null
    }
}
