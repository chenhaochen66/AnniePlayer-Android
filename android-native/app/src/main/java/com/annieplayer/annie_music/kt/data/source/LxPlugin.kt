package com.annieplayer.annie_music.kt.data.source

import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Quality

/**
 * 落雪（LX Music）插件模型与映射规则。
 *
 * 契约依据官方宿主真实源码（`lyswhut/lx-music-mobile` 的 `user-api-preload.js`）：
 * 插件通过 `lx.send('inited', { sources })` 声明音源，宿主只接受
 * `kw/kg/tx/wy/mg/local` 这几个源代码，并把 `actions`/`qualitys` 与宿主白名单求交。
 */

/** 插件声明的单个音源（已按宿主白名单过滤） */
data class LxPluginSource(
    /** LX 源代码：`kw`(酷我) / `kg`(酷狗) / `tx`(QQ) / `wy`(网易) / `mg`(咪咕) / `local` */
    val source: String,
    /** 该源可用的动作；在线源只保留 `musicUrl` */
    val actions: List<String>,
    /** 插件声明的音质档位（原始字符串） */
    val qualities: List<String>,
) {
    val supportsMusicUrl: Boolean get() = actions.contains("musicUrl")
}

/** 已导入的落雪插件 */
data class LxPlugin(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    /** 私有目录 `filesDir/lx-plugins/` 下的脚本文件名 */
    val fileName: String,
    val enabled: Boolean,
    val sources: List<LxPluginSource>,
    /** 导入 / 加载失败原因（非空表示该插件不可用） */
    val error: String? = null,
    /** 安妮独家插件（文件头带 `#@安妮音源` 标识块）—— 列表里挂「独家」徽标 */
    val exclusive: Boolean = false,
)

/** 插件头部注释（对齐官方解析：块 `/^\/\*[\S|\s]+?\*\//`） */
data class LxPluginHeader(
    val name: String,
    val description: String,
    val version: String,
    val author: String,
    val homepage: String,
)

/**
 * 音质档位映射：**只在内置 4 档里取值，档位宇宙永不扩张**。
 *
 * 插件喜欢声明 `master`/`atmos`/`dolby` 之类，一律丢弃；`hires` 归到内置的 `hires`。
 */
private val _lxQualityMap: Map<String, Quality> = mapOf(
    "128k" to Quality.k128k,
    "320k" to Quality.k320k,
    "flac" to Quality.flac,
    "flac24bit" to Quality.hires,
    "hires" to Quality.hires,
)

fun lxPluginQualities(raw: List<String>): List<Quality> =
    raw.mapNotNull { _lxQualityMap[it.lowercase()] }.distinct()

/** 内置档位 -> 请求插件时用的音质字符串（`hires` 取插件里最高档 `flac24bit`） */
fun lxQualityString(quality: Quality): String = when (quality) {
    Quality.k128k -> "128k"
    Quality.k320k -> "320k"
    Quality.flac -> "flac"
    Quality.hires -> "flac24bit"
    else -> "flac"
}

/**
 * 宿主接受的全部源代码 —— **对齐 lxn 基准的 `allSources`**（比上游 LX 多 `git`）。
 *
 * 其中只有 `kw/kg/tx/wy` 能映射到本项目平台；`mg`/`git`/`local` 照常接受声明，但没有平台可路由。
 */
val LX_ALL_SOURCES: List<String> = listOf("kw", "kg", "tx", "wy", "mg", "git", "local")

/** 插件可声明的音质键 —— **对齐 lxn 基准的 `supportQualitys`**（`hires`/`atmos`/`atmos_plus`/`master`） */
val LX_QUALITY_KEYS: List<String> =
    listOf("128k", "320k", "flac", "flac24bit", "hires", "atmos", "atmos_plus", "master")

/** 本项目平台 <-> 落雪源代码 */
object LxSourceMapper {

    private val codeToPlatform: Map<String, MusicPlatform> = mapOf(
        "kw" to MusicPlatform.kuwo,
        "kg" to MusicPlatform.kugou,
        "tx" to MusicPlatform.qq,
        "wy" to MusicPlatform.netease,
    )

    private val platformToCode: Map<MusicPlatform, String> =
        codeToPlatform.entries.associate { (code, platform) -> platform to code }

    /** 本项目 id 前缀；酷我 id 无前缀（`MUSIC_xxx` 原样） */
    private val idPrefixes: Map<MusicPlatform, String> = mapOf(
        MusicPlatform.kuwo to "kuwo:",
        MusicPlatform.kugou to "kugou:",
        MusicPlatform.qq to "qq:",
        MusicPlatform.netease to "netease:",
    )

    /** 该平台能否走落雪插件（酷我/酷狗/QQ/网易）；Qobuz/KKBOX 无对应源 */
    fun lxCodeOf(platform: MusicPlatform): String? = platformToCode[platform]

    /** 该源代码在本项目里有没有对应平台（`mg`/`git`/`local` 没有 —— 插件列表里不显示） */
    fun isMappable(code: String): Boolean = codeToPlatform.containsKey(code)

    /**
     * 去掉本项目 id 前缀，得到插件要的原始 id。
     *
     * 落雪 `musicInfo` 里歌曲 id 字段是 **`songmid`**（不是 `id`）；酷狗还要用同名值当 `hash`。
     */
    fun rawId(songId: String, platform: MusicPlatform): String {
        val prefix = idPrefixes[platform] ?: return songId
        return if (songId.startsWith(prefix)) songId.substring(prefix.length) else songId
    }
}
