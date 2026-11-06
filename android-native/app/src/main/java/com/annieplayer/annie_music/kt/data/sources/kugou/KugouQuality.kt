package com.annieplayer.annie_music.kt.data.sources.kugou

import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.network.httpPostJson
import com.annieplayer.annie_music.kt.data.sources.common.jsTruthy
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

// ================================================================================================
// 酷狗音质判档 + 批量补 `audio_info`
// ================================================================================================

/** 补数据并发度（一批 100 首、实测 ~100ms/批；并发 5 时 1000 首 ≈0.2s、1 万首 ≈2s） */
private const val _kugouEnrichConcurrency = 5

fun getKuGouQualities(s: Map<*, *>): List<Quality> {
    val q = ArrayList<Quality>()
    // Hi-Res 字段名**各接口不同**：签名专辑/歌手单曲与 `album_audio/audio` = `audio_info.hash_high`；
    // 搜索 = `ResFileHash`。歌单接口 `special/song_v2` 自身没有 → 由 [enrichKugouQualities] 补。
    if (jsTruthy(s["hash_high"]) || jsTruthy(s["hash_super"]) ||
        jsTruthy(s["ResFileHash"]) || jsTruthy(s["SuperFileHash"]) || jsTruthy(s["res_hash"])
    ) {
        q.add(Quality.hires)
    }
    if (jsTruthy(s["sqhash"]) || jsTruthy(s["hash_sq"]) || jsTruthy(s["hash_flac"]) ||
        jsTruthy(s["SQFileHash"])
    ) {
        q.add(Quality.flac)
    }
    if (jsTruthy(s["320hash"]) || jsTruthy(s["hash_320"]) || jsTruthy(s["HQFileHash"])) q.add(Quality.k320k)
    q.add(Quality.k128k)
    return q
}

/** 各档位字节数（酷狗专辑/歌手接口带 `sqfilesize`/`320filesize`/`filesize`） */
fun getKuGouQualitySizes(s: Map<*, *>): Map<Quality, Long> {
    val out = HashMap<Quality, Long>()

    fun num(vararg keys: String): Long? {
        for (k in keys) {
            val v = s[k] ?: continue
            val n = when (v) {
                is Number -> v.toLong()
                is String -> v.trim().toLongOrNull()
                else -> null
            }
            if (n != null && n > 0L) return n
        }
        return null
    }

    // Hi-Res 大小：搜索接口 = `ResFileSize`/`SuperFileSize`；签名专辑/歌手单曲 = `filesize_high`/`filesize_super`
    num("ResFileSize", "SuperFileSize", "filesize_high", "filesize_super")
        ?.let { out[Quality.hires] = it }
    num("sqfilesize", "flacfilesize", "filesize_sq", "filesize_flac", "SQFileSize")
        ?.let { out[Quality.flac] = it }
    num("320filesize", "filesize_320", "HQFileSize")?.let { out[Quality.k320k] = it }
    num("filesize", "filesize_128", "FileSize")?.let { out[Quality.k128k] = it }
    return out
}

/**
 * 酷狗按 hash 批量补音质档位 + 字节数：歌单接口 `special/song_v2` 只有 `hash`/`sqhash`/`320hash`，
 * **自身不含 Hi-Res 字段**；首页刷歌 / 风格推荐这类聚合接口则是另一套字段名，且不一定带 size。
 *
 * 对齐 LX 的两步法：拿 hash 批量 `POST gateway.kugou.com/v2/album_audio/audio`（一批 ≤100），
 * 返回的 `audio_info.hash_high`/`filesize_high` 就是 Hi-Res（实测《太阳之子》= 68MB / 1836kbps）。
 * 补不到就保留原样（退回 flac/320k），不影响播放。
 */
internal suspend fun enrichKugouQualities(songs: List<Song>): List<Song> {
    if (songs.isEmpty()) return songs
    val infos = fetchKugouAudioInfos(songs.map { it.id.removePrefix("kugou:") })
    if (infos.isEmpty()) return songs
    return songs.map { song ->
        val ai = infos[song.id.removePrefix("kugou:").lowercase()] ?: return@map song
        song.copy(
            qualities = getKuGouQualities(ai),
            qualitySizes = getKuGouQualitySizes(ai),
        )
    }
}

/**
 * 批量取 `audio_info`（key = 小写 hash）。**并发**跑（每批 ≤100），单批失败不影响其它批次。
 *
 * 为什么并发：歌单可以有上千首，串行 1 万首要 ~10s；实测每批仅 ~100ms，并发后 ~2s。
 */
private suspend fun fetchKugouAudioInfos(hashes: List<String>): Map<String, Map<*, *>> =
    coroutineScope {
        val batches = hashes.filter { it.isNotEmpty() }.distinct().chunked(100)
        val gate = Semaphore(_kugouEnrichConcurrency)
        val results = batches.map { batch ->
            async { gate.withPermit { fetchKugouAudioInfoBatch(batch) } }
        }.awaitAll()
        HashMap<String, Map<*, *>>().apply { results.forEach { putAll(it) } }
    }

/** 单批 `album_audio/audio`；失败返回空表（补不到就退回 flac/320k，不影响播放） */
private suspend fun fetchKugouAudioInfoBatch(batch: List<String>): Map<String, Map<*, *>> {
    val out = HashMap<String, Map<*, *>>()
    try {
        val payload: Map<String, Any?> = mapOf(
            "data" to batch.map { mapOf("hash" to it) },
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
            "fields" to "album_info,author_name,audio_info,base,songname",
        )
        val data = httpPostJson(
            "http://gateway.kugou.com/v2/album_audio/audio",
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
        val outer = data["data"] as? List<*> ?: return out
        for (entry in outer) {
            val item = (entry as? List<*>)?.firstOrNull() as? Map<*, *> ?: continue
            val ai = item["audio_info"] as? Map<*, *> ?: continue
            val h = (ai["hash"] ?: "").toString().lowercase()
            if (h.isNotEmpty()) out[h] = ai
        }
    } catch (_: Throwable) {
        // 单批失败不影响其它批次
    }
    return out
}
