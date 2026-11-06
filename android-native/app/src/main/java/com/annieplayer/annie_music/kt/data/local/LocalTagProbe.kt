package com.annieplayer.annie_music.kt.data.local

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.MediaInformation
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.StreamInformation
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 用 **ffmpeg / ffprobe** 读本地音频的元数据与内嵌封面。
 *
 * 为什么用 ffprobe 而不是 `MediaMetadataRetriever`：
 * - `ffprobe` 一次给出 `format.tags` + 全部 stream 参数（编码 / 采样率 / 位深 / 声道 / 码率），
 *   互相一致，不依赖系统媒体库扫过没有（新下的文件、改过 tag 的文件都能立刻读对）；
 * - 无损文件（FLAC / ALAC / APE / DSD）的 `bits_per_raw_sample` 只有 ffprobe 给得准；
 * - 工程里已经带了 `ffmpeg-kit-audio`，`FFprobeKit` 与 `FFmpegKit` 同包，没有额外依赖。
 *
 * 两件事分开做：
 * - [probe] 读元数据（**不写文件**，失败返回 null 由调用方保留文件名回落值）；
 * - [extractCover] 把 `attached_pic` 抽成 512px JPEG 落盘（一次即可，之后走缓存）。
 *
 * 都是**阻塞**调用（ffmpeg-kit 的同步 API），调用方必须在 IO 线程且限制并发。
 */
internal object LocalTagProbe {

    private const val TAG = "LocalTagProbe"

    /** 无损编码白名单（DSD 也算无损） */
    private val LOSSLESS = setOf(
        "flac", "alac", "ape", "wav", "aiff", "aif", "dsd_lsbf", "dsd_msbf", "dsd_lsbf_planar",
        "dsd_msbf_planar", "wavpack", "tta", "truehd", "mlp",
    )

    /** 封面抽出的最长边（再大对列表缩略图毫无意义，还会把缓存目录撑爆） */
    private const val COVER_MAX_PX = 512

    /**
     * 读元数据；文件读不了或 ffprobe 失败返回 null。
     *
     * [coverOut] 非空且文件里确实有内嵌封面（存在 video stream）时，顺手把封面抽到该路径
     * 并写进 `coverPath` —— 一次 ffprobe 就够，不必为了判断「有没有封面」再探一遍。
     */
    fun probe(path: String, coverOut: File? = null): LocalTrackMeta? {
        val file = File(path)
        if (!file.isFile) return null
        val info: MediaInformation = try {
            FFprobeKit.getMediaInformation(path).mediaInformation
        } catch (t: Throwable) {
            Log.w(TAG, "[ffprobe] failed: $path (${t.message})")
            null
        } ?: return null

        val tags = info.tags
        val streams: List<StreamInformation> = info.streams.orEmpty()
        val audio = streams.firstOrNull { it.type.equals("audio", ignoreCase = true) }
        val hasVideo = streams.any { it.type.equals("video", ignoreCase = true) }

        val codec = audio?.codec?.lowercase(Locale.ROOT).orEmpty()
        val sampleRate = audio?.sampleRate?.toIntOrNull() ?: 0
        val channels = audio?.getNumberProperty("channels")?.toInt()
            ?: _channelsOfLayout(audio?.channelLayout)

        // 位深：整型 PCM/FLAC 看 bits_per_raw_sample（个别容器只给 bits_per_sample），
        // 浮点编码退 sample_fmt（s16/s32/fltp…）
        val bitDepth = listOf("bits_per_raw_sample", "bits_per_sample")
            .firstNotNullOfOrNull { key ->
                audio?.getStringProperty(key)?.toIntOrNull()?.takeIf { it > 0 }
                    ?: audio?.getNumberProperty(key)?.toInt()?.takeIf { it > 0 }
            }
            ?: audio?.sampleFormat?.let { _bitDepthOfSampleFmt(it) }
            ?: 0

        // 码率：优先音频流，退 format 级；ffprobe 给 bps，统一折成 kbps
        val bitrateBps = audio?.bitrate?.toLongOrNull()
            ?: info.bitrate?.toLongOrNull()
            ?: 0L
        val bitrateKbps = if (bitrateBps > 0) (bitrateBps / 1000.0).roundToInt() else 0

        val duration = info.duration?.toDoubleOrNull()?.roundToInt() ?: 0
        val (fileNameTitle, fileNameArtist) = LocalTrackMeta.parseFileName(file.nameWithoutExtension)

        return LocalTrackMeta(
            path = file.absolutePath,
            mtime = file.lastModified(),
            size = file.length(),
            title = _tag(tags, "title") ?: fileNameTitle,
            artist = _tag(tags, "artist", "album_artist", "albumartist") ?: fileNameArtist,
            album = _tag(tags, "album") ?: file.parentFile?.name.orEmpty(),
            albumArtist = _tag(tags, "album_artist", "albumartist"),
            duration = duration,
            track = _tag(tags, "track")?.let { _leadingInt(it) },
            disc = _tag(tags, "disc")?.let { _leadingInt(it) },
            year = _tag(tags, "date", "year"),
            genre = _tag(tags, "genre"),
            // 与打标端（FfmpegTagger）写入的键一一对应；同一个 tags 对象取，无额外 I/O
            composer = _tag(tags, "composer", "writer"),
            isrc = _tag(tags, "isrc"),
            version = _tag(tags, "comment", "version"),
            copyright = _tag(tags, "copyright"),
            codec = codec,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            bitrate = bitrateKbps,
            channels = channels,
            lossless = codec in LOSSLESS ||
                (codec.isEmpty() && file.extension.lowercase(Locale.ROOT) in LOSSLESS),
            // 有 video stream（attached_pic）才值得跑一次 ffmpeg 抽图；没有就直接留 null
            coverPath = if (hasVideo && coverOut != null && extractCover(path, coverOut)) {
                coverOut.absolutePath
            } else {
                null
            },
            probed = true,
        )
    }

    /**
     * 把内嵌封面抽成 JPEG 写到 [out]。无封面 / 抽取失败返回 false（并清掉半成品）。
     *
     * `-map 0:v:0` 只取第一条视频流（attached_pic 就是它），`-frames:v 1` 只要第一帧；
     * `scale` 等比缩到 [COVER_MAX_PX] 以内，避免 3000×3000 的封面把缓存目录撑爆。
     */
    fun extractCover(path: String, out: File): Boolean {
        if (out.exists() && out.length() > 0) return true
        out.parentFile?.mkdirs()
        val args = arrayOf(
            "-hide_banner", "-y",
            "-i", path,
            "-map", "0:v:0",
            "-frames:v", "1",
            "-vf", "scale=$COVER_MAX_PX:$COVER_MAX_PX:force_original_aspect_ratio=decrease",
            "-c:v", "mjpeg",
            "-q:v", "4",
            "-f", "image2",
            out.absolutePath,
        )
        val ok = try {
            val session = FFmpegKit.executeWithArguments(args)
            ReturnCode.isSuccess(session.returnCode)
        } catch (t: Throwable) {
            Log.w(TAG, "[ffmpeg] cover failed: $path (${t.message})")
            false
        }
        if (!ok || !out.exists() || out.length() == 0L) {
            runCatching { out.delete() }
            return false
        }
        return true
    }

    /** 读内嵌歌词（`lyrics` / `LYRICS` / `unsyncedlyrics` 等 tag） */
    fun embeddedLyric(path: String): String? = try {
        val tags = FFprobeKit.getMediaInformation(path).mediaInformation?.tags
        _tag(tags, "lyrics", "unsyncedlyrics", "syncedlyrics")?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }

    // ── 内部工具 ──

    /** tag 大小写不敏感查找（不同封装写的键名大小写不一致：MP3 常全大写，FLAC 小写） */
    private fun _tag(tags: JSONObject?, vararg keys: String): String? {
        if (tags == null) return null
        val names = tags.keys().asSequence().toList()
        for (key in keys) {
            val real = names.firstOrNull { it.equals(key, ignoreCase = true) } ?: continue
            val v = tags.optString(real)
            if (v.isNotBlank()) return v.trim()
        }
        return null
    }

    /** `3/12` → 3；`03` → 3；`1/2` → 1 */
    private fun _leadingInt(raw: String): Int? =
        raw.trim().substringBefore('/').trim().toIntOrNull()

    /** `stereo` → 2，`5.1` → 6，`mono` → 1（拿不到 `channels` 字段时的回落） */
    private fun _channelsOfLayout(layout: String?): Int = when (layout?.lowercase(Locale.ROOT)?.trim()) {
        null, "" -> 0
        "mono" -> 1
        "stereo", "downmix" -> 2
        "2.1" -> 3
        "3.0" -> 3
        "4.0" -> 4
        "quad" -> 4
        "5.0" -> 5
        "5.1" -> 6
        "6.1" -> 7
        "7.1" -> 8
        else -> layout!!.count { it == ' ' } + 1
    }

    /** `s16`/`s16p` → 16，`s32p` → 32，`flt`/`fltp` → 32，`dbl` → 64 */
    private fun _bitDepthOfSampleFmt(fmt: String): Int? {
        val f = fmt.lowercase(Locale.ROOT)
        return when {
            f.startsWith("u8") || f.startsWith("s8") -> 8
            f.startsWith("s16") -> 16
            f.startsWith("s32") -> 32
            f.startsWith("s64") -> 64
            f.startsWith("flt") -> 32
            f.startsWith("dbl") -> 64
            else -> null
        }
    }
}
