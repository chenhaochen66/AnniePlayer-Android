package com.annieplayer.annie_music.kt.data.sources.kuwo

import com.annieplayer.annie_music.kt.data.model.AlbumSong
import com.annieplayer.annie_music.kt.data.network.httpGetText
import com.annieplayer.annie_music.kt.data.network.json
import com.annieplayer.annie_music.kt.data.network.jsonElementToAny
import com.annieplayer.annie_music.kt.data.network.retryUntilSuccess
import com.annieplayer.annie_music.kt.data.sources.common.asText
import com.annieplayer.annie_music.kt.data.sources.common.decodeHtmlEntities
import com.annieplayer.annie_music.kt.data.sources.common.jsTruthy
import com.annieplayer.annie_music.kt.data.sources.splitArtistIds
import com.annieplayer.annie_music.kt.data.sources.splitArtistNames

// ================================================================================================
// 酷我专辑：曲目 + 单曲补充信息
// ================================================================================================

/** 获取专辑歌曲与发行日期；失败返回空 */
suspend fun getAlbumSongs(albumId: String): KuwoAlbumSongs {
    val cookie = "kw_token=${System.currentTimeMillis()}"
    val secret = getSecret(cookie)

    val url =
        "http://searchlist.kuwo.cn/r.s?stype=albuminfo&albumid=$albumId" +
            "&show_copyright_off=1&alflac=1&vipver=1&sortby=1&newver=1&mobi=1"

    val text = retryUntilSuccess(maxRetries = 20, delayMs = 500L) {
        httpGetText(
            url,
            mapOf("User-Agent" to kuwoAndroidUa, "Cookie" to cookie, "Secret" to secret),
        )
    }

    var songList = emptyList<Map<String, Any?>>()
    var pub = ""
    try {
        val parsed = jsonElementToAny(json.parseToJsonElement(text))
        if (parsed is Map<*, *>) {
            songList = kuwoMapList(parsed["musiclist"])
            pub = kuwoStrOr(parsed["pub"], "")
        }
    } catch (_: Throwable) {
        val m = kuwoReMusicList.find(text)
        if (m != null) {
            try {
                songList = kuwoMapList(jsonElementToAny(json.parseToJsonElement(m.groupValues[1])))
            } catch (_: Throwable) {
                songList = emptyList()
            }
        }
        val pm = kuwoRePub.find(text)
        if (pm != null) pub = pm.groupValues[1]
    }

    val songs = songList.map { item ->
        val artistNameRaw = decodeHtmlEntities(kuwoStrOr(item["artist"], "未知歌手"))
        // 酷我专辑歌曲自带 `artistid`（单歌手）与 `allartistid`（多歌手，逗号分隔）。
        // 必须在这里拆名并带上 id：否则 UI 层按下标配对时 id 为空，「跳歌手」会退化成
        // 按名字联网搜索（用户反馈的「点跳转歌手要等一会儿」）。
        val artistNames = splitArtistNames(artistNameRaw)
        val artistIds = splitArtistIds(
            asText(item["allartistid"]).ifEmpty { asText(item["artistid"]) },
        )
        AlbumSong(
            id = asText(item["id"]),
            name = decodeHtmlEntities(kuwoStrOr(item["name"], "未知歌曲")),
            artistName = artistNameRaw,
            duration = kuwoInt(item["duration"]),
            albumArtist = decodeHtmlEntities(asText(item["aartist"])).ifEmpty { null },
            // 带上真实音质：不带的话专辑弹窗的下载候选永远回退 flac/320/128（与本包同款判定）
            qualities = kuwoQualities(item),
            qualitySizes = kuwoQualitySizes(item),
            track = if (jsTruthy(item["track"])) {
                asText(item["track"]).toDoubleOrNull()?.toInt()
            } else {
                null
            },
            artistNames = artistNames.ifEmpty { listOf(artistNameRaw) },
            // 数量不一致时宁可不给 id（退回按名字搜索），也不要把 id 配错歌手
            artistIds = if (artistIds.size == artistNames.size) artistIds else emptyList(),
            composer = asText(item["composer"]).ifEmpty { null },
            genre = asText(item["genre"]).ifEmpty { null },
        )
    }
    return KuwoAlbumSongs(songs = songs, pub = pub)
}

private val _albumDetailCache = HashMap<String, KuwoAlbumSongs>()

/** 补充酷我单曲 metadata（曲目号/发行日期）；无专辑或无匹配返回全 null */
suspend fun enrichKuwoSong(songId: String, albumId: String): KuwoEnrich {
    if (albumId.isEmpty()) return KuwoEnrich(null, null, null)
    return try {
        val album = _albumDetailCache[albumId]
            ?: getAlbumSongs(albumId).also { _albumDetailCache[albumId] = it }
        var matched: AlbumSong? = null
        for (s in album.songs) {
            if (s.id == songId) {
                matched = s
                break
            }
        }
        KuwoEnrich(
            trackNumber = matched?.track?.toString(),
            releaseDate = if (album.pub.isNotEmpty()) album.pub else null,
            albumArtist = matched?.albumArtist,
        )
    } catch (_: Throwable) {
        KuwoEnrich(null, null, null)
    }
}
