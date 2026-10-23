package com.annieplayer.annie_music.kt.data.source

import com.annieplayer.annie_music.kt.data.builtinDownloadUrl
import com.annieplayer.annie_music.kt.data.builtinPlayUrl
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 音源提供方：内置四平台与落雪插件是**平级实现**，由 `MusicRepository` 的路由链串联
 * （插件优先、内置兜底）。**不合并四平台文件**。
 */
interface MusicSourceProvider {
    /** [playQuality] 为 null = 自动：沿用各平台既有取档规则（旧行为） */
    suspend fun resolvePlayUrl(song: Song, playQuality: Quality?): String
    suspend fun resolveDownloadUrl(song: Song, quality: Quality): String
}

/** 内置四平台：仅转发到 `MusicRepository` 里既有的按平台分发实现 */
class BuiltinSourceProvider : MusicSourceProvider {
    override suspend fun resolvePlayUrl(song: Song, playQuality: Quality?): String =
        builtinPlayUrl(song, playQuality)

    override suspend fun resolveDownloadUrl(song: Song, quality: Quality): String =
        builtinDownloadUrl(song, quality)
}

/** 落雪插件：按选定档位（缺省取插件声明的最高档）请求 `musicUrl` */
class LxPluginSourceProvider : MusicSourceProvider {

    override suspend fun resolvePlayUrl(song: Song, playQuality: Quality?): String = resolve(song, playQuality)

    override suspend fun resolveDownloadUrl(song: Song, quality: Quality): String = resolve(song, quality)

    private suspend fun resolve(song: Song, quality: Quality?): String {
        val code = LxSourceMapper.lxCodeOf(song.platform) ?: error("该平台不支持落雪插件")
        val plugin = LxPluginRegistry.pluginFor(song.platform) ?: error("没有支持该平台的落雪插件")
        val declared = plugin.sources.firstOrNull { it.source == code }?.qualities.orEmpty()
        return LxPluginHost.resolveMusicUrl(
            cacheKey = plugin.id,
            script = LxPluginRegistry.readScript(plugin),
            fileName = plugin.fileName,
            source = code,
            quality = pickQuality(song, quality, declared),
            musicInfoJson = LxMusicInfo.build(song, code),
        )
    }

    /**
     * 选请求给插件的音质档位。
     *
     * **播放**：与内置播放一致（有 320k 就 `320k`，否则 `128k`）。曾经取"插件声明的最高档"，
     * 会对没有 HiRes 的歌去要 `hires`/`4000kflac`，插件查表失败或接口直接拒绝 ——
     * 表现就是「酷我/网易经常失败」「酷我跳歌」。
     * **下载**：用用户选的档位；插件没声明该档位就退到它声明的最高档，避免"不支持的音质"。
     */
    private fun pickQuality(song: Song, quality: Quality?, declared: List<String>): String {
        val want = if (quality != null) {
            lxQualityString(quality)
        } else {
            if (song.qualities.contains(Quality.k320k)) "320k" else "128k"
        }
        if (want in declared) return want
        // 退到「声明里不超过所选档位」的最高档（hires 不可用 → 退 flac，而不是 320k）
        val wantRank = _LxQualityOrder.indexOf(want)
        val ranked = declared.mapNotNull { q ->
            val r = _LxQualityOrder.indexOf(q)
            if (r >= 0) r to q else null
        }
        return ranked.filter { it.first <= wantRank }.maxByOrNull { it.first }?.second
            ?: ranked.maxByOrNull { it.first }?.second
            ?: want
    }
}

/** 插件音质档位从低到高（用于降级时取「不超过所选档位」的最高档） */
private val _LxQualityOrder = listOf(
    "128k", "320k", "flac", "flac24bit", "hires", "atmos", "atmos_plus", "master",
)

/**
 * 构造插件要的 `info.musicInfo`。
 *
 * 字段对齐官方 `toOldMusicInfo()` 的**旧格式**：歌曲 id 字段是 **`songmid`**（不是 `id`），
 * 且带平台附加字段 —— 酷狗要 `hash`；**QQ 要 `strMediaMid`/`songId`/`albumMid`**（插件普遍直接读）。
 */
object LxMusicInfo {

    fun build(song: Song, lxCode: String): String {
        val rawId = LxSourceMapper.rawId(song.id, song.platform)
        val albumId = song.albumId?.let { LxSourceMapper.rawId(it, song.platform) }.orEmpty()
        val types = song.qualities.map { lxQualityString(it) }.distinct()
        return buildJsonObject {
            put("name", song.title)
            put("singer", song.artist)
            put("source", lxCode)
            put("songmid", rawId)
            put("interval", song.duration)
            put("albumName", song.album.orEmpty())
            put("albumId", albumId)
            put("img", song.cover)
            put("typeUrl", buildJsonObject { })
            put("types", buildJsonArray { types.forEach { add(buildJsonObject { put("type", it) }) } })
            put("_types", buildJsonObject { types.forEach { put(it, buildJsonObject { put("size", estimateSize(it, song.duration)) }) } })
            when (lxCode) {
                "kg" -> put("hash", rawId)
                "tx" -> {
                    // strMediaMid 必须是**真的 media_mid**（`file.media_mid`）：它与 songmid 不是一回事，
                    // 拿 songmid 顶替会让插件去取一个不存在的文件（表现为解析失败 / 放不出声）。
                    // 老队列项 / 老缓存没有这个字段时才退回 songmid。
                    put("strMediaMid", song.mediaMid?.takeIf { it.isNotBlank() } ?: rawId)
                    put("songId", rawId)
                    put("albumMid", albumId)
                }
            }
        }.toString()
    }

    /** `_types[quality].size` 的粗略估算（官方给真实字节数，本项目拿不到，按码率×时长估） */
    private fun estimateSize(type: String, durationSec: Int): Int {
        val bytesPerSec = when (type) {
            "128k" -> 16_000L
            "320k" -> 40_000L
            "flac" -> 100_000L
            else -> 200_000L
        }
        return (bytesPerSec * durationSec.coerceAtLeast(0)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
