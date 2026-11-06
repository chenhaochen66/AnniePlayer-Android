package com.annieplayer.annie_music.kt.data.sources.qq

import com.annieplayer.annie_music.kt.data.model.AlbumSong
import com.annieplayer.annie_music.kt.data.model.AlbumSongsPage
import com.annieplayer.annie_music.kt.data.network.httpGetText
import com.annieplayer.annie_music.kt.data.network.json
import com.annieplayer.annie_music.kt.data.network.jsonElementToAny
import com.annieplayer.annie_music.kt.data.sources.common.asText
import com.annieplayer.annie_music.kt.data.sources.common.decodeHtmlEntities

// ================================================================================================
// QQ 专辑（曲目 / 单曲补充信息）
// ================================================================================================

/** 获取 QQ 音乐专辑曲目（`fcg_v8_album_info_cp`） */
suspend fun getQQAlbumSongs(albumMid: String): AlbumSongsPage {
    try {
        val url =
            "https://c.y.qq.com/v8/fcg-bin/fcg_v8_album_info_cp.fcg" +
                "?albummid=$albumMid&format=json&inCharset=utf8&outCharset=utf-8" +
                "&notice=0&platform=yqq.json&needNewCode=0"
        val text = httpGetText(url, qqHeaders)
        val decoded = jsonElementToAny(json.parseToJsonElement(qqStripBom(text)))
        if (decoded !is Map<*, *>) return AlbumSongsPage(emptyList())
        val data = decoded["data"]
        val listRaw = if (data is Map<*, *>) data["list"] else null
        val list = if (listRaw is List<*>) listRaw else emptyList<Any?>()
        // 发行时间在 `data.aDate`（形如 2013-06-03；同一响应内，无需额外请求）
        val pub = if (data is Map<*, *>) asText(data["aDate"]) else ""
        // 专辑流派（如 "Pop 流行"）
        val genre = if (data is Map<*, *>) asText(data["genre"]) else ""
        // 专辑歌手在 `data.singer[]`（与 `list[].singer` 是「歌曲歌手」不同）
        val albumSingerRaw = if (data is Map<*, *>) data["singer"] else null
        val albumArtist = if (albumSingerRaw is List<*>) {
            albumSingerRaw.filterIsInstance<Map<*, *>>()
                .map { decodeHtmlEntities(asText(it["name"])) }
                .filter { it.isNotEmpty() }
                .joinToString(" / ")
        } else {
            ""
        }
        val out = mutableListOf<AlbumSong>()
        for (item in list) {
            val m = qqToMap(item) ?: continue
            val mid = asText(m["songmid"])
            if (mid.isEmpty()) continue
            // QQ 专辑歌曲自带 `singer:[{id, mid, name}]` —— 直接取**全部**歌手的名字与 mid。
            // 之前只取第一位歌手的名字、且不带 id，导致「跳歌手」缺 id 退化成联网搜索（有延迟）。
            val singer = m["singer"]
            val singerMaps = if (singer is List<*>) {
                singer.filterIsInstance<Map<*, *>>()
            } else {
                emptyList()
            }
            val artistNames = singerMaps
                .map { decodeHtmlEntities(asText(it["name"])) }
                .filter { it.isNotEmpty() }
            val artistIds = singerMaps
                .map { asText(it["mid"]) }
                .filter { it.isNotEmpty() }
            val artistName = artistNames.firstOrNull() ?: ""
            val interval = m["interval"]
            val belongCd = m["belongCD"]
            val cdIdx = m["cdIdx"]
            out.add(
                AlbumSong(
                    id = "qq:$mid",
                    name = decodeHtmlEntities(asText(m["songname"])),
                    artistName = decodeHtmlEntities(artistName),
                    duration = if (interval is Number) interval.toInt() else 0,
                    track = if (belongCd is Number) belongCd.toInt() else null,
                    discNumber = if (cdIdx is Number) cdIdx.toInt() + 1 else null,
                    albumArtist = albumArtist.ifEmpty { null },
                    // 带上真实音质（与搜索/歌手单曲同一套判定，见 [qqQualities]）
                    qualities = qqQualities(m),
                    qualitySizes = qqQualitySizes(m),
                    mediaMid = qqMediaMid(m),
                    artistNames = artistNames,
                    // 数量不一致时宁可不给 id（退回按名字搜索），也不要把 id 配错歌手
                    artistIds = if (artistIds.size == artistNames.size) artistIds else emptyList(),
                    genre = genre.ifEmpty { null },
                ),
            )
        }
        return AlbumSongsPage(out, pub)
    } catch (_: Throwable) {
        return AlbumSongsPage(emptyList())
    }
}

/** 补充 QQ 单曲 metadata（走专辑详情接口）；失败返回全 null */
suspend fun enrichQQSong(songId: String, albumId: String): QqEnrich {
    val empty = QqEnrich(null, null, null, null)
    if (albumId.isEmpty()) return empty
    return try {
        val page = getQQAlbumSongs(albumId)
        val matched = page.songs.firstOrNull { it.id == songId }
        QqEnrich(
            trackNumber = matched?.track?.toString(),
            discNumber = matched?.discNumber?.toString(),
            releaseDate = page.pub.ifEmpty { null },
            albumArtist = matched?.albumArtist,
        )
    } catch (_: Throwable) {
        empty
    }
}
