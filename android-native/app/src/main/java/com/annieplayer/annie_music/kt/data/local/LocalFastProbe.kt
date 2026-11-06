package com.annieplayer.annie_music.kt.data.local

import android.media.MediaMetadataRetriever
import android.os.Build
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 快速元数据读取（`MediaMetadataRetriever`）。
 *
 * 为什么需要它：`ffprobe` 每首都要走一遍 ffmpeg-kit 的命令/会话封装（解析参数、建 session、
 * 拿 JSON、再解析），单首 30~120ms；曲库上千首时这一段能占掉整个扫描时间的 90%。而
 * `MediaMetadataRetriever` 是系统自带的 native 解析器，直接读容器头，单首通常 3~15ms，
 * 常规 tag（标题/艺术家/专辑/时长/音轨号/采样率/码率/封面字节）全都有。
 *
 * 它的短板也很明确，因此只当**第一层**用：
 * - 拿不到位深（FLAC 的 `bits_per_raw_sample`）→ 需要时再让 [LocalTagProbe] 补；
 * - APE / DSD / WMA / 特殊容器支持差，可能返回空 → 落 [LocalTagProbe] 兜底；
 * - 内嵌歌词读不到（`lyrics` tag 不在 `METADATA_KEY_*` 里）→ 同样由 [LocalTagProbe] 补。
 *
 * 所以扫描流程是：**全部先走本类**（快），字段缺失 / 文件读不了时才升级到 ffprobe。
 */
internal object LocalFastProbe {

    /** 位深拿不到时的占位值（`0` = 未知；详情页看到 0 才去补 ffprobe） */
    const val UNKNOWN_BIT_DEPTH = 0

    /**
     * 读元数据；文件读不了或系统解析器也拿不到任何字段时返回 null（由调用方落 ffprobe）。
     *
     * @param coverOut 非空且有内嵌封面时，把封面字节直接写成该文件（省掉一次 ffmpeg 抽帧）
     */
    fun probe(path: String, coverOut: File? = null): LocalTrackMeta? {
        val file = File(path)
        if (!file.isFile) return null
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            val mime = r.extract(METADATA_KEY_MIMETYPE)
            val hasAudio = r.extract(METADATA_KEY_HAS_AUDIO)
            // 不是音频（封面党 / 视频）或系统解析不了：交给 ffprobe
            if (hasAudio == "no" || (mime == null && r.extract(METADATA_KEY_DURATION) == null)) {
                return null
            }

            val ext = file.extension.lowercase(Locale.ROOT)
            val codec = _codecOf(mime, ext)
            val (fileNameTitle, fileNameArtist) = LocalTrackMeta.parseFileName(file.nameWithoutExtension)

            val durationMs = r.extract(METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val bitrate = r.extract(METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
            val sampleRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                r.extract(METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 0
            } else {
                0
            }

            LocalTrackMeta(
                path = file.absolutePath,
                mtime = file.lastModified(),
                size = file.length(),
                // tag 为空 → 文件名回落；文件名也拆不出就留空，由 UI 用 nameWithoutExtension 兜
                title = r.extract(METADATA_KEY_TITLE)?.trim()?.takeIf { it.isNotEmpty() } ?: fileNameTitle,
                artist = r.extract(METADATA_KEY_ARTIST)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: r.extract(METADATA_KEY_ALBUMARTIST)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: fileNameArtist,
                album = r.extract(METADATA_KEY_ALBUM)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: file.parentFile?.name.orEmpty(),
                albumArtist = r.extract(METADATA_KEY_ALBUMARTIST)?.trim()?.takeIf { it.isNotEmpty() },
                duration = (durationMs / 1000.0).roundToInt(),
                track = _leadingInt(r.extract(METADATA_KEY_CD_TRACK_NUMBER)),
                disc = _leadingInt(r.extract(METADATA_KEY_DISC_NUMBER)),
                year = r.extract(METADATA_KEY_YEAR)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: r.extract(METADATA_KEY_DATE)?.trim()?.takeIf { it.isNotEmpty() },
                genre = r.extract(METADATA_KEY_GENRE)?.trim()?.takeIf { it.isNotEmpty() },
                // 系统解析器只认得出「作曲」（COMPOSER / WRITER）；ISRC / 版权 / 版本说明
                // 没有对应的 METADATA_KEY_*，这里如实留空 —— 它们由 TagLib / ffprobe 补齐，
                // 绝不为了这三个字段在本层再开一次文件。
                composer = (r.extract(METADATA_KEY_COMPOSER) ?: r.extract(METADATA_KEY_WRITER))
                    ?.trim()?.takeIf { it.isNotEmpty() },
                codec = codec,
                sampleRate = sampleRate,
                bitDepth = UNKNOWN_BIT_DEPTH,
                bitrate = if (bitrate > 0) (bitrate / 1000.0).roundToInt() else 0,
                channels = 0,
                lossless = codec in LOSSLESS,
                coverPath = coverOut?.let { out -> _writeCover(r, out) },
                probed = true,
            )
        } catch (_: Throwable) {
            // APE / DSD / 损坏文件等：这套系统解析器支持不好，交给 ffprobe
            null
        } finally {
            runCatching { r.release() }
        }
    }

    /** 只读时长（播放页兜底用；比整套 probe 便宜） */
    fun durationSeconds(path: String): Int {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            ((r.extract(METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0).roundToInt()
        } catch (_: Throwable) {
            0
        } finally {
            runCatching { r.release() }
        }
    }

    /**
     * 只抽封面（不读任何 tag）。
     *
     * 列表是**滚动到可见才抽**的，所以这个方法会被频繁调用：单首只 `setDataSource` +
     * `embeddedPicture`，比整套 [probe] 便宜一半以上。
     *
     * @return 写出成功返回封面路径；无内嵌封面返回 null
     */
    fun extractCover(path: String, out: File): String? {
        if (out.exists() && out.length() > 0) return out.absolutePath
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            _writeCover(r, out)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    /** 内嵌封面字节 → JPEG/PNG 原样落盘（`MediaMetadataRetriever` 给的就是原始图片字节） */
    private fun _writeCover(r: MediaMetadataRetriever, out: File): String? {
        if (out.exists() && out.length() > 0) return out.absolutePath
        val bytes = try {
            r.embeddedPicture
        } catch (_: Throwable) {
            null
        } ?: return null
        if (bytes.isEmpty()) return null
        return try {
            out.parentFile?.mkdirs()
            out.writeBytes(bytes)
            out.absolutePath
        } catch (_: Throwable) {
            runCatching { out.delete() }
            null
        }
    }

    /** MIME / 扩展名 → 编码名（与 ffprobe 的 `codec_name` 尽量对齐，UI 只用它显示角标） */
    private fun _codecOf(mime: String?, ext: String): String = when {
        mime != null -> when {
            mime.contains("flac", true) -> "flac"
            mime.contains("alac", true) -> "alac"
            mime.contains("mp4", true) || mime.contains("aac", true) -> "aac"
            mime.contains("mpeg", true) -> "mp3"
            mime.contains("opus", true) -> "opus"
            mime.contains("vorbis", true) || mime.contains("ogg", true) -> "vorbis"
            mime.contains("wav", true) || mime.contains("raw", true) -> "wav"
            mime.contains("amr", true) -> "amr"
            else -> ext
        }
        else -> ext
    }

    private fun _leadingInt(raw: String?): Int? =
        raw?.trim()?.substringBefore('/')?.trim()?.toIntOrNull()

    /** 无损编码白名单（与 [LocalTagProbe] 保持同一份语义） */
    private val LOSSLESS = setOf(
        "flac", "alac", "ape", "wav", "aiff", "aif", "wv", "tta", "dsf", "dff", "dsd",
    )

    // ── 常量（避免 lint 提示"API 级别"的直接引用）──
    private const val METADATA_KEY_MIMETYPE = MediaMetadataRetriever.METADATA_KEY_MIMETYPE
    private const val METADATA_KEY_HAS_AUDIO = MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO
    private const val METADATA_KEY_TITLE = MediaMetadataRetriever.METADATA_KEY_TITLE
    private const val METADATA_KEY_ARTIST = MediaMetadataRetriever.METADATA_KEY_ARTIST
    private const val METADATA_KEY_ALBUM = MediaMetadataRetriever.METADATA_KEY_ALBUM
    private const val METADATA_KEY_ALBUMARTIST = MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST
    private const val METADATA_KEY_DURATION = MediaMetadataRetriever.METADATA_KEY_DURATION
    private const val METADATA_KEY_BITRATE = MediaMetadataRetriever.METADATA_KEY_BITRATE
    private const val METADATA_KEY_CD_TRACK_NUMBER = MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER
    private const val METADATA_KEY_DISC_NUMBER = MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER
    private const val METADATA_KEY_YEAR = MediaMetadataRetriever.METADATA_KEY_YEAR
    private const val METADATA_KEY_DATE = MediaMetadataRetriever.METADATA_KEY_DATE
    private const val METADATA_KEY_GENRE = MediaMetadataRetriever.METADATA_KEY_GENRE
    private const val METADATA_KEY_COMPOSER = MediaMetadataRetriever.METADATA_KEY_COMPOSER
    private const val METADATA_KEY_WRITER = MediaMetadataRetriever.METADATA_KEY_WRITER
    private const val METADATA_KEY_SAMPLERATE = 38 // MediaMetadataRetriever.METADATA_KEY_SAMPLERATE（API 31）
}

/** `r.extract(key)` 的空安全包装（`extractMetadata` 返回 `String?`） */
private fun MediaMetadataRetriever.extract(key: Int): String? = try {
    extractMetadata(key)
} catch (_: Throwable) {
    null
}
