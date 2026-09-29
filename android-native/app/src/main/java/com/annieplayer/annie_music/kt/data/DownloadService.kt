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
    ) {
        val input = File(inputPath)
        val cover = if (coverPath != null && File(coverPath).exists()) File(coverPath) else null
        val dot = inputPath.lastIndexOf('.')
        val outExt = if (dot >= 0) inputPath.substring(dot) else ".mp3"
        val tmpOut = File(input.parentFile, "tagged_${System.currentTimeMillis()}$outExt")
        val lower = inputPath.lowercase()

        val comments = ArrayList<Pair<String, String>>()
        comments.add("TITLE" to title)
        comments.add("ARTIST" to artist)
        if (album.isNotEmpty()) comments.add("ALBUM" to album)
        if (!albumArtist.isNullOrEmpty()) comments.add("ALBUMARTIST" to albumArtist)
        if (!trackNumber.isNullOrEmpty()) comments.add("TRACKNUMBER" to trackNumber)
        if (!discNumber.isNullOrEmpty()) comments.add("DISCNUMBER" to discNumber)
        if (!releaseDate.isNullOrEmpty()) comments.add("DATE" to releaseDate)
        if (lyric.isNotEmpty()) comments.add("LYRICS" to lyric)

        val ok: Boolean = when {
            lower.endsWith(".mp3") ->
                _writeMp3(input, tmpOut, title, artist, album, lyric, albumArtist, trackNumber, discNumber, releaseDate, cover)

            lower.endsWith(".flac") -> _writeFlac(input, tmpOut, comments, cover)

            else -> {
                Log.w(TAG, "[tag] 不支持的封装格式，跳过打标并保留原始文件: $outExt")
                false
            }
        }

        if (!ok) {
            if (tmpOut.exists()) tmpOut.delete()
            return
        }

        // 用打标后的文件替换原文件（对齐 Dart：同目录 rename，失败则复制覆盖）
        try {
            if (!tmpOut.renameTo(input)) {
                tmpOut.copyTo(input, overwrite = true)
                tmpOut.delete()
            }
        } catch (_: Throwable) {
            try {
                tmpOut.copyTo(input, overwrite = true)
                tmpOut.delete()
            } catch (_: Throwable) {
                // 打标文件无法替换时保留原文件
            }
        }

        val verified = readTags(inputPath)
        Log.i(TAG, "[tag] ok -> $inputPath, verified=${verified.keys}")
    }

    // ---- MP3：ID3v2.4 ----

    /** 生成 ID3v2.4 标签（UTF-8）并写入 [output]：先写新标签，再拼接去掉旧 ID3v2 后的音频数据。 */
    private fun _writeMp3(
        input: File,
        output: File,
        title: String,
        artist: String,
        album: String,
        lyric: String,
        albumArtist: String?,
        trackNumber: String?,
        discNumber: String?,
        releaseDate: String?,
        cover: File?,
    ): Boolean {
        return try {
            val frames = ByteArrayOutputStream()
            frames.write(_id3TextFrame("TIT2", title))
            frames.write(_id3TextFrame("TPE1", artist))
            if (album.isNotEmpty()) frames.write(_id3TextFrame("TALB", album))
            if (!albumArtist.isNullOrEmpty()) frames.write(_id3TextFrame("TPE2", albumArtist))
            if (!trackNumber.isNullOrEmpty()) frames.write(_id3TextFrame("TRCK", trackNumber))
            if (!discNumber.isNullOrEmpty()) frames.write(_id3TextFrame("TPOS", discNumber))
            if (!releaseDate.isNullOrEmpty()) frames.write(_id3TextFrame("TDRC", releaseDate))
            if (lyric.isNotEmpty()) frames.write(_id3UsltFrame(lyric))
            if (cover != null) {
                val pic = _id3ApicFrame(cover)
                if (pic != null && pic.isNotEmpty()) frames.write(pic)
            }
            val body = frames.toByteArray()

            val header = ByteArray(10)
            header[0] = 'I'.code.toByte()
            header[1] = 'D'.code.toByte()
            header[2] = '3'.code.toByte()
            header[3] = 0x04 // 版本 2.4
            header[4] = 0x00
            header[5] = 0x00 // 无扩展头 / 无同步位
            val size = _synchsafe(body.size)
            header[6] = size[0]
            header[7] = size[1]
            header[8] = size[2]
            header[9] = size[3]

            BufferedInputStream(FileInputStream(input)).use { bis ->
                bis.mark(16)
                val h = ByteArray(10)
                val read = bis.read(h)
                var skip = 0L
                if (read == 10 &&
                    h[0] == 'I'.code.toByte() &&
                    h[1] == 'D'.code.toByte() &&
                    h[2] == '3'.code.toByte()
                ) {
                    val id3Size = ((h[6].toInt() and 0x7F) shl 21) or
                        ((h[7].toInt() and 0x7F) shl 14) or
                        ((h[8].toInt() and 0x7F) shl 7) or
                        (h[9].toInt() and 0x7F)
                    skip = (10 + id3Size).toLong()
                }
                bis.reset()
                var remaining = skip
                while (remaining > 0) {
                    val s = bis.skip(remaining)
                    if (s <= 0) break
                    remaining -= s
                }
                FileOutputStream(output).use { fos ->
                    fos.write(header)
                    fos.write(body)
                    bis.copyTo(fos)
                }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** 文本帧（编码 0x03 = UTF-8） */
    private fun _id3TextFrame(id: String, value: String): ByteArray {
        val textBytes = value.toByteArray(Charsets.UTF_8)
        val data = ByteArray(1 + textBytes.size)
        data[0] = 0x03
        System.arraycopy(textBytes, 0, data, 1, textBytes.size)
        return _id3Frame(id, data)
    }

    /** 非同步歌词帧（encoding + 语言 eng + 空描述符 + 歌词 UTF-8） */
    private fun _id3UsltFrame(lyric: String): ByteArray {
        val text = lyric.toByteArray(Charsets.UTF_8)
        val data = ByteArray(1 + 3 + 1 + text.size)
        data[0] = 0x03
        data[1] = 'e'.code.toByte()
        data[2] = 'n'.code.toByte()
        data[3] = 'g'.code.toByte()
        data[4] = 0x00
        System.arraycopy(text, 0, data, 5, text.size)
        return _id3Frame("USLT", data)
    }

    /** 封面帧（encoding + mime + 0x00 + picture type=3 + 空描述符 + 图片数据） */
    private fun _id3ApicFrame(cover: File): ByteArray? {
        return try {
            val img = cover.readBytes()
            val mime = if (cover.name.lowercase().endsWith(".png")) "image/png" else "image/jpeg"
            val mimeBytes = mime.toByteArray(Charsets.US_ASCII)
            val data = ByteArray(1 + mimeBytes.size + 1 + 1 + 1 + img.size)
            var i = 0
            data[i] = 0x03
            i += 1
            System.arraycopy(mimeBytes, 0, data, i, mimeBytes.size)
            i += mimeBytes.size
            data[i] = 0x00
            i += 1
            data[i] = 0x03
            i += 1
            data[i] = 0x00
            i += 1
            System.arraycopy(img, 0, data, i, img.size)
            _id3Frame("APIC", data)
        } catch (_: Throwable) {
            null
        }
    }

    /** ID3v2.4 帧：4 字节 id + 4 字节 synchsafe 长度 + 2 字节 flags + 数据 */
    private fun _id3Frame(id: String, data: ByteArray): ByteArray {
        val out = ByteArray(10 + data.size)
        val idBytes = id.toByteArray(Charsets.US_ASCII)
        var i = 0
        while (i < 4 && i < idBytes.size) {
            out[i] = idBytes[i]
            i += 1
        }
        val size = _synchsafe(data.size)
        out[4] = size[0]
        out[5] = size[1]
        out[6] = size[2]
        out[7] = size[3]
        out[8] = 0x00
        out[9] = 0x00
        System.arraycopy(data, 0, out, 10, data.size)
        return out
    }

    /** 28 位 synchsafe 整数（每字节 7 位有效），ID3v2.4 的长度编码 */
    private fun _synchsafe(size: Int): ByteArray = byteArrayOf(
        ((size shr 21) and 0x7F).toByte(),
        ((size shr 14) and 0x7F).toByte(),
        ((size shr 7) and 0x7F).toByte(),
        (size and 0x7F).toByte(),
    )

    // ---- FLAC：Vorbis comment + PICTURE ----

    /**
     * 改写 FLAC 元数据块：丢弃旧的 VORBIS_COMMENT(4) / PICTURE(6) / PADDING(1)，
     * 写入新的注释与封面，其余块（含首块 STREAMINFO）原样保留，随后拼接音频帧数据。
     */
    private fun _writeFlac(
        input: File,
        output: File,
        comments: List<Pair<String, String>>,
        cover: File?,
    ): Boolean {
        return try {
            BufferedInputStream(FileInputStream(input)).use { bis ->
                val magic = readExactly(bis, 4) ?: return false
                if (magic[0] != 'f'.code.toByte() ||
                    magic[1] != 'L'.code.toByte() ||
                    magic[2] != 'a'.code.toByte() ||
                    magic[3] != 'C'.code.toByte()
                ) {
                    return false
                }

                val keep = ArrayList<Pair<Int, ByteArray>>()
                var last = false
                while (!last) {
                    val h = readExactly(bis, 4) ?: return false
                    val b0 = h[0].toInt() and 0xFF
                    last = (b0 and 0x80) != 0
                    val type = b0 and 0x7F
                    val len = ((h[1].toInt() and 0xFF) shl 16) or
                        ((h[2].toInt() and 0xFF) shl 8) or
                        (h[3].toInt() and 0xFF)
                    val payload = readExactly(bis, len) ?: return false
                    if (type != 4 && type != 6 && type != 1) keep.add(type to payload)
                }

                val vorbis = _flacVorbisComment(comments)
                val picture = if (cover != null) _flacPicture(cover) else null
                val blocks = ArrayList<Pair<Int, ByteArray>>()
                blocks.addAll(keep)
                blocks.add(4 to vorbis)
                if (picture != null) blocks.add(6 to picture)

                FileOutputStream(output).use { fos ->
                    fos.write("fLaC".toByteArray(Charsets.US_ASCII))
                    for (idx in blocks.indices) {
                        val blk = blocks[idx]
                        val isLast = idx == blocks.size - 1
                        fos.write(_flacBlockHeader(blk.first, blk.second.size, isLast))
                        fos.write(blk.second)
                    }
                    val buf = ByteArray(64 * 1024)
                    var n = bis.read(buf)
                    while (n >= 0) {
                        fos.write(buf, 0, n)
                        n = bis.read(buf)
                    }
                }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Vorbis comment 负载（小端长度 + UTF-8 键值对） */
    private fun _flacVorbisComment(comments: List<Pair<String, String>>): ByteArray {
        val out = ByteArrayOutputStream()
        val vendor = "AnnieMusic".toByteArray(Charsets.UTF_8)
        out.write(_le32(vendor.size))
        out.write(vendor)
        out.write(_le32(comments.size))
        for (c in comments) {
            val entry = "${c.first}=${c.second}".toByteArray(Charsets.UTF_8)
            out.write(_le32(entry.size))
            out.write(entry)
        }
        return out.toByteArray()
    }

    /** FLAC PICTURE 块负载（大端字段；picture type=3 表示 front cover） */
    private fun _flacPicture(cover: File): ByteArray? {
        return try {
            val data = cover.readBytes()
            val mime = if (cover.name.lowercase().endsWith(".png")) "image/png" else "image/jpeg"
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(cover.path, opts)
            val w = if (opts.outWidth > 0) opts.outWidth else 0
            val h = if (opts.outHeight > 0) opts.outHeight else 0
            val depth = if (mime == "image/png") 32 else 24

            val out = ByteArrayOutputStream()
            out.write(_be32(3))
            val mimeBytes = mime.toByteArray(Charsets.US_ASCII)
            out.write(_be32(mimeBytes.size))
            out.write(mimeBytes)
            out.write(_be32(0)) // 描述长度 0
            out.write(_be32(w))
            out.write(_be32(h))
            out.write(_be32(depth))
            out.write(_be32(0)) // 颜色数
            out.write(_be32(data.size))
            out.write(data)
            out.toByteArray()
        } catch (_: Throwable) {
            null
        }
    }

    /** FLAC 元数据块头：1 字节（last 标志 + 7 位类型）+ 3 字节大端长度 */
    private fun _flacBlockHeader(type: Int, length: Int, last: Boolean): ByteArray {
        val b0 = (type and 0x7F) or (if (last) 0x80 else 0)
        return byteArrayOf(
            b0.toByte(),
            ((length shr 16) and 0xFF).toByte(),
            ((length shr 8) and 0xFF).toByte(),
            (length and 0xFF).toByte(),
        )
    }

    private fun _le32(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    private fun _be32(value: Int): ByteArray = byteArrayOf(
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    /** 精确读取 [length] 字节；流提前结束返回 null */
    private fun readExactly(input: InputStream, length: Int): ByteArray? {
        if (length < 0) return null
        val buf = ByteArray(length)
        var off = 0
        while (off < length) {
            val r = input.read(buf, off, length - off)
            if (r < 0) return null
            off += r
        }
        return buf
    }
}
