package com.annieplayer.annie_music.kt.data.local

import android.os.ParcelFileDescriptor
import android.util.Log
import com.lonx.audiotag.TagLib
import com.lonx.audiotag.model.Metadata
import java.io.File
import java.util.Locale

/**
 * 用 **TagLib（C++，via `libtaglib.so`）** 读本地音频标签。
 *
 * 这是扫描链路的**第一层**：TagLib 只解析容器头与 tag 区，单首耗时通常在微秒~毫秒级，
 * 比 `MediaMetadataRetriever`（约 22ms/首）再快一个量级，曲库上千首时差距非常明显。
 *
 * 能力与边界：
 * - 有：标题 / 艺术家 / 专辑 / 专辑艺术家 / 音轨号 / 碟号 / 年份 / 流派 / 作曲 / ISRC /
 *   版权 / 版本（comment）/ 时长 / 采样率 / 声道 / 码率 / 内嵌图片字节；
 *   **一次 `getMetadata` 的 PropertyMap 里全都在**，多读几个键不产生任何额外 I/O ——
 *   别再为某个字段单独开一个 fd（一次 open + 一次完整标签解析，3000 首就是 3000 次白跑）；
 * - **没有位深**（TagLib 的 `AudioProperties` 不暴露 `bitsPerSample`，个别 FLAC 会在
 *   `BITS_PER_SAMPLE` 键里带），拿不到时由 [LocalTagProbe]（ffprobe）按需补；
 * - 拿不到编码名，用扩展名映射（只用于界面角标，足够）。
 *
 * JNI 载体见 `com.lonx.audiotag.TagLib`（包名/方法名与 so 导出符号一一对应，不可改名）。
 * 若 so 缺失或 ABI 不匹配（例如 32 位设备），[isAvailable] 返回 false，调用方自动降级到
 * `MediaMetadataRetriever` / ffprobe。
 */
internal object LocalTagLibProbe {

    private const val TAG = "LocalTagLibProbe"

    /** JNI 门面的全限定名（触发类初始化才会 loadLibrary） */
    private const val TAGLIB_CLASS = "com.lonx.audiotag.TagLib"

    /** TagLib 的 ReadStyle.Average（0=Fast 1=Average 2=Accurate）；曲库扫描用 1 足够 */
    private const val READ_STYLE_AVERAGE = 1

    private val LOSSLESS = setOf(
        "flac", "alac", "ape", "wav", "aiff", "aif", "wv", "tta", "dsf", "dff", "dsd", "mka",
    )

    @Volatile
    private var available: Boolean? = null

    /** so 是否可用（首次调用会触发 `System.loadLibrary`，失败一次后永久 false） */
    fun isAvailable(): Boolean {
        available?.let { return it }
        val ok = try {
            // 只触发类初始化（静态块里 loadLibrary）——绝不拿假 fd 去调 native 方法，
            // 那会直接 SIGSEGV（不是异常，捕不到）
            Class.forName(TAGLIB_CLASS, true, LocalTagLibProbe::class.java.classLoader)
            true
        } catch (t: Throwable) {
            // UnsatisfiedLinkError / ExceptionInInitializerError（无 so 或 ABI 不匹配）
            Log.w(TAG, "[taglib] unavailable: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
        available = ok
        return ok
    }

    /**
     * 读一首的标签 + 音频参数；文件读不了 / TagLib 解析失败返回 null（调用方继续降级）。
     *
     * 不读封面（`readPictures = false`）：封面按需另调 [cover]。
     */
    fun probe(path: String): LocalTrackMeta? {
        val file = File(path)
        if (!file.isFile) return null
        if (!isAvailable()) return null

        val metadata: Metadata = try {
            openFd(file) { fd -> TagLib.getMetadata(fd, false) }
        } catch (t: Throwable) {
            Log.w(TAG, "[taglib] getMetadata failed: $path (${t.message})")
            null
        } ?: return null

        val props = try {
            openFd(file) { fd -> TagLib.getAudioProperties(fd, READ_STYLE_AVERAGE) }
        } catch (_: Throwable) {
            null
        }

        val map = metadata.propertyMap.orEmpty()
        val ext = file.extension.lowercase(Locale.ROOT)
        val (fileNameTitle, fileNameArtist) = LocalTrackMeta.parseFileName(file.nameWithoutExtension)

        return LocalTrackMeta(
            path = file.absolutePath,
            mtime = file.lastModified(),
            size = file.length(),
            title = tag(map, "TITLE") ?: fileNameTitle,
            artist = tag(map, "ARTIST", "ALBUMARTIST") ?: fileNameArtist,
            album = tag(map, "ALBUM") ?: file.parentFile?.name.orEmpty(),
            albumArtist = tag(map, "ALBUMARTIST"),
            // TagLib 的 `AudioProperties::length` 给的是**毫秒**（真机实测：2:56 的歌拿到 175777），
            // 之前按秒用，列表全显示成 "2929:37" 这种。这里折成秒。
            duration = ((props?.length ?: 0) / 1000),
            track = _leadingInt(tag(map, "TRACKNUMBER")),
            disc = _leadingInt(tag(map, "DISCNUMBER")),
            year = tag(map, "DATE", "YEAR"),
            genre = tag(map, "GENRE"),
            // 下面四个键都在同一个 PropertyMap 里，取值不额外碰磁盘
            composer = tag(map, "COMPOSER"),
            isrc = tag(map, "ISRC"),
            // 打标端把「版本说明」写进 comment（Qobuz 的 version / Apple 的 Remastered），
            // 少数工具另用 VERSION 键
            version = tag(map, "COMMENT", "VERSION"),
            copyright = tag(map, "COPYRIGHT"),
            // 歌词：FLAC/OGG 是 LYRICS，MP3 的 USLT 与 MP4 的 ©lyr 在 TagLib 里也归一到 LYRICS
            lyric = tag(map, "LYRICS", "UNSYNCEDLYRICS", "LYRIC"),
            codec = ext,
            sampleRate = props?.sampleRate ?: 0,
            // 少数 FLAC 会把位深写进 vorbis comment；没有就留 0，由 ffprobe 按需补
            bitDepth = tag(map, "BITS_PER_SAMPLE")?.toIntOrNull() ?: 0,
            bitrate = props?.bitrate ?: 0,
            channels = props?.channels ?: 0,
            lossless = ext in LOSSLESS,
            coverPath = null,
            probed = true,
        )
    }

    /**
     * 抽内嵌封面到 [out]（**按需**调用：列表行滚到可见时才抽）。
     *
     * @return 写成功返回封面文件路径；没有内嵌图片返回 null
     */
    fun cover(path: String, out: File): String? {
        if (out.exists() && out.length() > 0) return out.absolutePath
        if (!isAvailable()) return null
        val file = File(path)
        if (!file.isFile) return null
        val pictures = try {
            openFd(file) { fd -> TagLib.getPictures(fd) }
        } catch (_: Throwable) {
            null
        } ?: return null
        if (pictures.isEmpty()) return null
        // 优先正面封面，没有就取第一张
        val pic = pictures.firstOrNull { it.pictureType?.equals("Front Cover", true) == true }
            ?: pictures.firstOrNull()
            ?: return null
        val bytes = pic.data ?: return null
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

    // ── 内部 ──

    /**
     * 打开一个**交给 native 的** fd 并执行 [block]。
     *
     * 必须 `dup().detachFd()`，不能直接用 `ParcelFileDescriptor.fd`：
     * Android 10+ 的 fdsan 会校验 fd 的 owner tag，而 native 侧（TagLib）用 `fdopen()`
     * 接管该 fd 时要求它「无主」，否则直接 `SIGABRT`（真机日志：
     * `fdsan: failed to exchange ownership of file descriptor: fd 164 is owned by
     * ParcelFileDescriptor`）。
     *
     * `dup()` 出来的新 fd 再 `detachFd()` 后 owner tag 为 0，所有权交给 native；
     * **由 native 侧 `fclose` 关闭**，这里不能再关（双重 close 会关掉别人的 fd）。
     * 每次取不同信息（标签 / 属性 / 图片）都单独开一个 fd，避免文件偏移状态互相影响。
     */
    private inline fun <T> openFd(file: File, block: (Int) -> T): T {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { base ->
            val fd = base.dup().detachFd()
            return block(fd)
        }
    }

    /** TagLib PropertyMap 取值：键全大写，值是数组（多值）；空串视为没有 */
    private fun tag(map: Map<String, Array<String>>, vararg keys: String): String? {
        for (key in keys) {
            val values = map[key] ?: continue
            val v = values.firstOrNull { it.isNotBlank() } ?: continue
            return v.trim()
        }
        return null
    }

    /** `3/12` → 3，`03` → 3 */
    private fun _leadingInt(raw: String?): Int? =
        raw?.trim()?.substringBefore('/')?.trim()?.toIntOrNull()
}
