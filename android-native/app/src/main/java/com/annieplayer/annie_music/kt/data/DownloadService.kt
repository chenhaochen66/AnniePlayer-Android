package com.annieplayer.annie_music.kt.data

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import com.annieplayer.annie_music.kt.core.platform.MediaStoreScan
import com.annieplayer.annie_music.kt.core.util.parseLrc
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.network.globalOkHttp
import com.annieplayer.annie_music.kt.data.sources.decodeHtmlEntities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 下载服务（1:1 移植 Flutter `lib/data/download_service.dart`，对齐 RN 版 `services/downloadService.ts`）。
 *
 * 与 Dart 的对应关系：
 * - 网络下载：Dart 用 dio.download（CancelToken）；这里用 OkHttp 流式写入，暂停/取消通过
 *   [Call.cancel] 实现（语义对齐：暂停=停止当前下载，保留已写入的临时文件；取消=删除临时文件）。
 * - 目录解析：Dart `downloadDirService.getDownloadDirPath()`；这里同包 [DownloadDirService]。
 * - 媒体库入库：Dart 走 MethodChannel；这里直接调用同工程原生实现
 *   [com.annieplayer.annie_music.kt.core.platform.MediaStoreScan]。
 * - 进度语义完全一致：下载中用 `received / total`，随后依次回调 0.5 / 0.65 / 0.8 / 1.0。
 * - 文件名规则一致：`{title} - {artist}{.mp3|.flac}`，非法字符替换为 `_`；临时文件 `tmp_{safeSongId}{ext}`。
 *
 * Context 依赖：通过 [init] 解析 `context.cacheDir` 作为临时目录（与
 * [com.annieplayer.annie_music.kt.data.network.CacheService] 的 `init(context)` 模式一致）。
 * 未调用 [init] 时退化为 JVM 临时目录，保证 API 在初始化前仍可工作。
 *
 * ## 元数据写入（ffmpeg-kit 已被上游停止维护，改用平台 API）
 *
 * 读取统一使用 [MediaMetadataRetriever]（见 [readTags]）。写入按封装格式区分，**不静默跳过**：
 *
 * - **MP3**：可写。纯 Kotlin 生成 ID3v2.4 标签块（UTF-8），帧包括 TIT2/TPE1/TALB/TPE2/
 *   TRCK/TPOS/TDRC/USLT（歌词），封面写为 APIC（picture type=3，front cover）。
 * - **FLAC**：可写。改写 FLAC 元数据块（Vorbis comment，UTF-8；封面写为 PICTURE 块），
 *   STREAMINFO 仍保持在首块，SEEKTABLE/APPLICATION/CUESHEET 原样保留。
 * - **M4A / MP4 / AAC**：**不可写**。`android.media.MediaMuxer` 没有通用元数据标签 API，
 *   平台侧无法在不引入 Media3 Transformer 依赖的前提下写入标题/封面；[MediaMetadataRetriever]
 *   只能读取。此类文件仅完成下载，保持原始文件并在日志中明确记录（不静默跳过打标）。
 * - 其它格式：同样明确记录后保持原文件。
 *
 * 封面下载与打标的失败回退与 Dart 完全对齐：先带封面打标，失败则去掉封面重试一次，
 * 再失败则使用原始文件（至少保证文件可用）。
 */
object DownloadService {

    private const val TAG = "DownloadService"

    /** Chrome UA（与 Flutter `download_service.dart` 的 `_chromeUa` 完全一致） */
    private const val _chromeUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36"

    /** 应用缓存目录；[init] 之前为 null，退化为 JVM 临时目录 */
    private var _cacheDir: File? = null

    /** 由 Application / Activity 调用以解析应用缓存目录（对齐 CacheService 的 init 模式）。 */
    fun init(context: Context) {
        _cacheDir = context.cacheDir
    }

    /** 临时目录；未 [init] 时回退 JVM 临时目录，保证 API 在 init 前可工作。 */
    private fun _tempDir(): File =
        _cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: ".")

    /** 活跃下载（对齐 Dart `ActiveDownload`），用于暂停/取消 */
    private class ActiveDownload(val call: Call, val tempPath: String) {
        @Volatile
        var cancelReason: String? = null
    }

    private val _activeDownloads = ConcurrentHashMap<String, ActiveDownload>()

    /**
     * 暂停下载：取消底层请求并保留临时文件以便后续续传（对齐 Dart `pauseDownload`）。
     * 返回是否确实暂停了一个进行中的下载。
     */
    fun pauseDownload(songId: String): Boolean {
        val ad = _activeDownloads[songId] ?: return false
        // 不在此 remove：要留给 downloadSong 的 catch 读取 cancelReason，进而区分「暂停」与真失败。
        ad.cancelReason = "paused"
        ad.call.cancel()
        return true
    }

    /** 取消下载并清理临时文件（对齐 Dart `cancelDownload`）。 */
    fun cancelDownload(songId: String) {
        val ad = _activeDownloads[songId] ?: return
        ad.cancelReason = "cancelled"
        ad.call.cancel()
        // 临时文件清理交给 downloadSong 的 catch（避免与正在写入的输出流竞争）。
    }

    /**
     * 合并翻译歌词（对齐 Dart `mergeLyrics`）：以毫秒时间戳对齐，翻译行紧跟在原行之后。
     * 未匹配到翻译的行保持原样。
     */
    fun mergeLyrics(lrc: String, tlyric: String?): String {
        if (tlyric.isNullOrEmpty()) return lrc
        val lrcLines = parseLrc(lrc)
        val tlyricLines = parseLrc(tlyric)
        val transMap = HashMap<Int, String>()
        for (tl in tlyricLines) {
            transMap[(tl.time * 1000).roundToInt()] = tl.text
        }
        val result = ArrayList<String>()
        for (line in lrcLines) {
            val timeKey = (line.time * 1000).roundToInt()
            val totalSec = line.time.toInt()
            val min = totalSec / 60
            val sec = totalSec % 60
            val frac = ((line.time - floor(line.time)) * 100).roundToInt()
            val ts = "[" + min.toString().padStart(2, '0') + ":" +
                sec.toString().padStart(2, '0') + "." +
                frac.toString().padStart(2, '0') + "]"
            result.add("$ts${line.text}")
            val trans = transMap[timeKey]
            if (trans != null) result.add("$ts$trans")
        }
        return result.joinToString("\n")
    }

    /** 读取文件中可被 [MediaMetadataRetriever] 识别的标签（用于打标后校验，键为规范名）。 */
    fun readTags(path: String): Map<String, String> {
        val retriever = MediaMetadataRetriever()
        val out = HashMap<String, String>()
        try {
            retriever.setDataSource(path)
            _putIfNotBlank(out, "title", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))
            _putIfNotBlank(out, "artist", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST))
            _putIfNotBlank(out, "album", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM))
            _putIfNotBlank(
                out,
                "albumArtist",
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
            )
            _putIfNotBlank(
                out,
                "trackNumber",
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER),
            )
            _putIfNotBlank(out, "discNumber", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER))
            _putIfNotBlank(out, "date", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE))
            val pic = retriever.embeddedPicture
            if (pic != null && pic.size > 0) out["cover"] = pic.size.toString()
        } catch (_: Throwable) {
            // 读取失败：返回已收集内容
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
                // ignore
            }
        }
        return out
    }

    private fun _putIfNotBlank(map: MutableMap<String, String>, key: String, value: String?) {
        if (!value.isNullOrEmpty()) map[key] = value
    }

    /**
     * 下载歌曲（1:1 对齐 Dart `downloadSong` 语义）。
     *
     * 直链由调用方通过仓库层 `fetchDownloadUrlBySong(song, quality)` 取得后传入，
     * 与 Flutter 调用点（`fetchDownloadUrlBySong` -> `downloadSong`）一致。
     *
     * @return 自定义目录时直接返回最终文件路径；默认目录入库成功时返回 `content://` URI，
     *   入库失败时返回私有目录下的原路径。
     */
    suspend fun downloadSong(
        songId: String,
        title: String,
        artist: String,
        coverUrl: String,
        downloadUrl: String,
        quality: Quality,
        onProgress: (Double) -> Unit,
        lyric: String,
        album: String? = null,
        tlyric: String? = null,
        albumArtist: String? = null,
        trackNumber: String? = null,
        discNumber: String? = null,
        releaseDate: String? = null,
        isrc: String? = null,
        version: String? = null,
        copyright: String? = null,
    ): String {
        val safeTitle = decodeHtmlEntities(title)
        val safeArtist = decodeHtmlEntities(artist)
        val e = _ext(quality)
        val safeName = "$safeTitle - $safeArtist$e".replace(Regex("[/\\\\?%*:|\"<>]"), "_")

        // 目标目录：优先用户自定义下载目录，否则私有 downloads/（对齐 Dart）
        val customDir = DownloadDirService.getDownloadDirPath()
        val downloadDir: String = if (!customDir.isNullOrEmpty()) {
            if (customDir.endsWith("/") || customDir.endsWith("\\")) {
                customDir
            } else {
                customDir + File.separator
            }
        } else {
            _tempDir().absolutePath + File.separator + "downloads" + File.separator
        }
        val dir = File(downloadDir)
        if (!dir.exists()) dir.mkdirs()

        val safeSongId = songId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val tempPath = downloadDir + "tmp_$safeSongId" + e
        val tmpFile = File(tempPath)
        if (tmpFile.exists()) tmpFile.delete()

        val request = Request.Builder().url(downloadUrl).header("User-Agent", _chromeUa).build()
        val call = globalOkHttp.newCall(request)
        _activeDownloads[songId] = ActiveDownload(call, tempPath)

        try {
            withContext(Dispatchers.IO) { _streamToFile(call, tmpFile, onProgress) }
        } catch (ex: Exception) {
            when (_activeDownloads[songId]?.cancelReason) {
                "paused" -> {
                    _activeDownloads.remove(songId)
                    throw IllegalStateException("下载已暂停")
                }
                "cancelled" -> {
                    _activeDownloads.remove(songId)
                    try {
                        if (tmpFile.exists()) tmpFile.delete()
                    } catch (_: Throwable) {
                        // 忽略
                    }
                    throw IllegalStateException("下载已取消")
                }
                else -> throw ex
            }
        }
        if (!tmpFile.exists()) throw IllegalStateException("Download failed")
        onProgress(0.5)

        val coverPath = _downloadCover(coverUrl, songId)
        onProgress(0.65)

        withContext(Dispatchers.IO) {
            val mergedLyric = mergeLyrics(lyric, tlyric)
            try {
                _tagFile(
                    inputPath = tempPath,
                    coverPath = coverPath,
                    title = safeTitle,
                    artist = safeArtist,
                    album = album ?: "",
                    lyric = mergedLyric,
                    albumArtist = albumArtist,
                    trackNumber = trackNumber,
                    discNumber = discNumber,
                    releaseDate = releaseDate,
                    isrc = isrc,
                    version = version,
                    copyright = copyright,
                )
            } catch (ex: Throwable) {
                Log.w(TAG, "[download] tag failed (with cover): $ex")
                // 封面往往是失败主因；去掉封面重试一次，至少保证文本标签写入成功。
                try {
                    _tagFile(
                        inputPath = tempPath,
                        coverPath = null,
                        title = safeTitle,
                        artist = safeArtist,
                        album = album ?: "",
                        lyric = mergedLyric,
                        albumArtist = albumArtist,
                        trackNumber = trackNumber,
                        discNumber = discNumber,
                        releaseDate = releaseDate,
                        isrc = isrc,
                        version = version,
                        copyright = copyright,
                    )
                    Log.i(TAG, "[download] tag retry WITHOUT cover succeeded")
                } catch (ex2: Throwable) {
                    Log.w(TAG, "[download] tag failed (no cover) too, using raw: $ex2")
                }
            }
        }
        onProgress(0.8)

        if (coverPath != null) {
            val cf = File(coverPath)
            try {
                if (cf.exists()) cf.delete()
            } catch (_: Throwable) {
                // 与 Dart 一致：忽略
            }
        }

        val finalPath = downloadDir + safeName
        if (tempPath != finalPath) {
            withContext(Dispatchers.IO) {
                val src = File(tempPath)
                val finalFile = File(finalPath)
                if (finalFile.exists()) finalFile.delete()
                try {
                    if (!src.renameTo(finalFile)) {
                        src.copyTo(finalFile, overwrite = true)
                        src.delete()
                    }
                } catch (_: Throwable) {
                    try {
                        src.copyTo(finalFile, overwrite = true)
                    } catch (_: Throwable) {
                        // 与 Dart `catchError((_) => File(...))` 一致：保留原文件
                    }
                }
            }
        }

        val isCustomDir = !customDir.isNullOrEmpty()
        if (isCustomDir) {
            onProgress(1.0)
            _activeDownloads.remove(songId)
            return finalPath
        }

        // 默认目录：媒体库扫描（Android 10+ 插入 MediaStore.Audio，文件进入 Music/AnnieMusic）
        var outUri = ""
        try {
            outUri = withContext(Dispatchers.IO) { MediaStoreScan.scanFileToMediaStore(finalPath) } ?: ""
        } catch (_: Throwable) {
            // 与 Dart 一致：扫描失败静默，保留私有副本
        }

        if (outUri.isNotEmpty()) {
            val privateFile = File(finalPath)
            try {
                if (privateFile.exists()) privateFile.delete()
            } catch (_: Throwable) {
                // ignore
            }
        }

        onProgress(1.0)
        _activeDownloads.remove(songId)
        return if (outUri.isNotEmpty()) outUri else finalPath
    }

    /** 扩展名：flac/hires 用 `.flac`，其余 `.mp3`（对齐 Dart `_ext`）。 */
    private fun _ext(q: Quality): String =
        if (q == Quality.flac || q == Quality.hires) ".flac" else ".mp3"

    /** 流式下载到 [tmpFile]，按 `received / total` 回调进度（对齐 dio onReceiveProgress）。 */
    private fun _streamToFile(call: Call, tmpFile: File, onProgress: (Double) -> Unit) {
        call.execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Download failed: ${resp.code}")
            val body = resp.body ?: throw IOException("Download failed")
            val total = body.contentLength()
            body.byteStream().use { input ->
                tmpFile.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var received = 0L
                    var n = input.read(buf)
                    while (n >= 0) {
                        output.write(buf, 0, n)
                        received += n
                        if (total > 0) onProgress(received.toDouble() / total)
                        n = input.read(buf)
                    }
                }
            }
        }
    }

    /** 下载封面为临时文件，返回路径（失败返回 null，对齐 Dart `_downloadCover`）。 */
    private suspend fun _downloadCover(coverUrl: String, songId: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val extName = if (coverUrl.contains(".")) {
                    coverUrl.split(".").last().split("?").first()
                } else {
                    "jpg"
                }
                val safeExt = if (extName.contains("/")) "jpg" else extName
                val coverTmp = File(_tempDir(), "cover_$songId.$safeExt")
                coverTmp.parentFile?.mkdirs()
                if (coverTmp.exists()) coverTmp.delete()

                val request = Request.Builder().url(coverUrl).header("User-Agent", _chromeUa).build()
                val ok = globalOkHttp.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        false
                    } else {
                        val body = resp.body
                        if (body == null) {
                            false
                        } else {
                            body.byteStream().use { input ->
                                coverTmp.outputStream().use { output -> input.copyTo(output) }
                            }
                            true
                        }
                    }
                }
                if (ok && coverTmp.exists()) coverTmp.path else null
            } catch (_: Throwable) {
                null
            }
        }

    // ---------------- 元数据打标 ----------------

    /**
     * 为「已落盘的文件」写元数据（供 KKBOX DRM 解密路径等复用，对齐 Flutter `_tagDrmAudio`）。
     *
     * 与 [downloadSong] 内联打标同源：先尝试带封面打标，失败则去掉封面重试一次；
     * 仍失败则保留原文件（不抛异常）。MP3/FLAC 可写，其余封装格式按 [_tagFile] 的既有约定
     * 保留原始文件。
     */
    suspend fun tagAudioFile(
        inputPath: String,
        title: String,
        artist: String,
        album: String,
        coverUrl: String,
        lyric: String,
        albumArtist: String? = null,
        trackNumber: String? = null,
        discNumber: String? = null,
        releaseDate: String? = null,
        coverCacheKey: String = inputPath,
    ) {
        val safeTitle = decodeHtmlEntities(title)
        val safeArtist = decodeHtmlEntities(artist)
        val coverPath = if (coverUrl.isNotEmpty()) _downloadCover(coverUrl, coverCacheKey) else null
        withContext(Dispatchers.IO) {
            try {
                _tagFile(
                    inputPath = inputPath,
                    coverPath = coverPath,
                    title = safeTitle,
                    artist = safeArtist,
                    album = album,
                    lyric = lyric,
                    albumArtist = albumArtist,
                    trackNumber = trackNumber,
                    discNumber = discNumber,
                    releaseDate = releaseDate,
                )
            } catch (ex: Throwable) {
                Log.w(TAG, "[tag] tag failed (with cover): $ex")
                try {
                    _tagFile(
                        inputPath = inputPath,
                        coverPath = null,
                        title = safeTitle,
                        artist = safeArtist,
                        album = album,
                        lyric = lyric,
                        albumArtist = albumArtist,
                        trackNumber = trackNumber,
                        discNumber = discNumber,
                        releaseDate = releaseDate,
                    )
                } catch (ex2: Throwable) {
                    Log.w(TAG, "[tag] tag failed (no cover) too, using raw: $ex2")
                }
            }
        }
        if (coverPath != null) {
            try {
                File(coverPath).takeIf { it.exists() }?.delete()
            } catch (_: Throwable) {
                // 忽略清理失败
            }
        }
    }

    /**
     * 打标调度：按扩展名选择写入器。MP3/FLAC 可写，其余格式明确记录后使用原始文件。
     * 打标成功后用 [readTags] 读取校验并写日志。
     */
    private fun _tagFile(
        inputPath: String,
        coverPath: String?,
        title: String,
        artist: String,
        album: String,
        lyric: String,
        albumArtist: String?,
        trackNumber: String?,
        discNumber: String?,
        releaseDate: String?,
        isrc: String? = null,
        version: String? = null,
        copyright: String? = null,
    ) {
        // 交给 ffmpeg 写入（覆盖 mp3/flac/m4a 等全格式，对齐 Flutter 的 ffmpeg 打标）
        fun runTag(cover: String?): Boolean = FfmpegTagger.tagInPlace(
            inputPath = inputPath,
            coverPath = cover,
            title = title,
            artist = artist,
            album = album,
            lyric = lyric,
            albumArtist = albumArtist,
            trackNumber = trackNumber,
            discNumber = discNumber,
            releaseDate = releaseDate,
            isrc = isrc,
            version = version,
            copyright = copyright,
        )

        var ok = runTag(coverPath)
        if (!ok && coverPath != null) {
            // 封面常是打标失败主因：去掉封面重试一次，至少保证文本标签写入（对齐 Flutter）
            Log.w(TAG, "[tag] ffmpeg 带封面打标失败，去封面重试: $inputPath")
            ok = runTag(null)
        }
        if (!ok) {
            Log.w(TAG, "[tag] ffmpeg 打标失败，保留原始文件: $inputPath")
            return
        }
        val verified = readTags(inputPath)
        Log.i(TAG, "[tag] ok -> $inputPath, verified=${verified.keys}")
    }
}

