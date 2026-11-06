package com.annieplayer.annie_music.kt.data

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import com.annieplayer.annie_music.kt.core.platform.DownloadSink
import com.annieplayer.annie_music.kt.core.platform.FileDownloadSink
import com.annieplayer.annie_music.kt.core.platform.MediaStoreScan
import com.annieplayer.annie_music.kt.core.state.MoreSettings
import com.annieplayer.annie_music.kt.core.util.LyricLine
import com.annieplayer.annie_music.kt.core.util.lrcTimeOfMs
import com.annieplayer.annie_music.kt.core.util.mmssOfMs
import com.annieplayer.annie_music.kt.core.util.parseLrcWithTranslation
import com.annieplayer.annie_music.kt.data.local.stream.StreamTag
import com.annieplayer.annie_music.kt.data.local.tag.AnnieTag
import com.annieplayer.annie_music.kt.data.local.tag.CoverBytes
import com.annieplayer.annie_music.kt.data.local.tag.TagBag
import com.annieplayer.annie_music.kt.data.local.tag.TagKey
import com.annieplayer.annie_music.kt.data.local.tag.TagMapper
import com.annieplayer.annie_music.kt.data.local.tag.WritePolicy
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.network.globalOkHttp
import com.annieplayer.annie_music.kt.data.sources.common.decodeHtmlEntities
import com.annieplayer.annie_music.kt.data.sources.qobuz.getTrack
import com.annieplayer.annie_music.kt.data.sources.qobuz.qobuzTokenProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
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
 * - 文件名规则一致：`{title} - {artist}{.mp3|.flac}`，非法字符替换为 `_`。
 * - 进度：下载中用 `received / total`，打标与入库各占一小段收尾。
 *
 * Context 依赖：通过 [init] 解析 `context.cacheDir` 作为临时目录（与
 * [com.annieplayer.annie_music.kt.data.network.CacheService] 的 `init(context)` 模式一致）。
 * 未调用 [init] 时退化为 JVM 临时目录，保证 API 在初始化前仍可工作。
 *
 * ## 两条落盘路径
 *
 * 1. **快路径**（[_tryStreaming]）：字节从网络来的同时就把容器头和标签写出去，直接落进
 *    目标（自定义目录的真实文件 / `MediaStore` 的 pending 条目）。全程只有下载这一趟写盘，
 *    没有临时文件、没有入库复制。覆盖 mp3 / flac / ogg / opus / aac / dsd / dff / wav / aiff
 *    —— 具体由 `StreamTag` 的格式表决定。
 * 2. **落盘路径**（[_downloadLegacy]）：快路径放弃的格式（m4a 的 `moov` 在文件尾、ape 系
 *    的旧尾标签没法在流里裁掉）先写临时文件，再用 `AnnieTag` 打标。`AnnieTag` 对这些格式
 *    也尽量「只碰几 KB」，但至少多一趟落盘。
 *
 * 打标内容与「事后补标」（[tagAudioFile]）共用同一个 [buildTagBag]，两处字段不会走偏。
 */
object DownloadService {

    private const val TAG = "DownloadService"

    /**
     * 落盘/搬移用的单次缓冲。
     *
     * 下载走的是单条 HTTP 流（不像 Apple 分片那样能并发），缓冲大小直接决定 syscall 次数：
     * 8KB（Kotlin `copyTo` 默认）下一首 100MB 的无损要一万两千多次 read/write，
     * 256KB 时降到四百次以内，在 UFS 上就能吃满顺序写带宽。
     */
    private const val COPY_BUFFER = 256 * 1024

    /** 封面字节上限。超过就当作「没有封面」—— 30MB 的 BMP 塞进标签只会拖垮播放器。 */
    private const val COVER_LIMIT = 8 * 1024 * 1024L

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
    private class ActiveDownload(val call: Call) {
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
     * 合并歌词（普通分支）：翻译行紧跟在原行之后，两行用**同一个** `[mm:ss.xx]` 标签。
     *
     * 对齐必须走 [parseLrcWithTranslation]（内部有容差），**不能**自己按毫秒精确查表：
     * QQ 的 lrc 行是 `[00:10.00]` 而 trans 是别的精度，精确匹配会整句丢翻译（真机复现：
     * 《Sorry》从第 4 行起翻译全没写进 flac 标签）。
     *
     * [enhancedLyric] 非空且设置里开了「下载歌词写入逐字」时改走**增强 LRC**：
     * 对齐交给 [parseLrcWithTranslation] —— 各平台逐字时间轴与 lrc **不是同一条**
     * （网易 yrc 与 lrc 实测差 ~8.3s），必须由它统一平移到音频时间轴，
     * 再序列化成 `[行时间]<00:00.000><行时间>词…`，翻译行紧随其后（复用同一行标签）。
     */
    fun mergeLyrics(lrc: String, tlyric: String?, enhancedLyric: String? = null): String {
        val enhanced = enhancedLyric?.takeIf {
            it.isNotEmpty() && MoreSettings.writeEnhancedLyrics.value
        }
        if (enhanced != null) {
            return _toEnhancedLrc(parseLrcWithTranslation(lrc, tlyric, enhanced))
        }
        if (tlyric.isNullOrEmpty()) return lrc
        val result = ArrayList<String>()
        for (line in parseLrcWithTranslation(lrc, tlyric)) {
            val ts = _centisTag((line.time * 1000).roundToInt())
            result.add("$ts${line.text}")
            line.translation?.let { result.add("$ts$it") }
        }
        return result.joinToString("\n")
    }

    /** `[mm:ss.xx]`（厘秒）。**整数运算** —— 浮点 `0.999` 四舍五入会得到非法的 `.100` */
    private fun _centisTag(msTotal: Int): String {
        val safe = if (msTotal < 0) 0 else msTotal
        return "[" + (safe / 60000).toString().padStart(2, '0') + ":" +
            ((safe % 60000) / 1000).toString().padStart(2, '0') + "." +
            ((safe % 1000) / 10).toString().padStart(2, '0') + "]"
    }

    /**
     * 序列化为**增强 LRC**（严格对齐项目根目录 `增强lrc.txt` 的形状）：
     * `[行时间]<00:00.000><行时间>词<词时间>词…<收尾时间>` —— 行首固定零标记；
     * 收尾标记（无文本）是最后一个词的结束时间，**未知就不写**（样例里也只有部分行有）。
     *
     * 翻译行**必须复用原行那一模一样的标签**（[lrcTimeOfMs]，3 位毫秒）：显示端是按同一时间戳
     * 把翻译合并进原行的，写成厘秒（[ _centisTag ]）或另取 `line.time` 就会整句错位。
     */
    private fun _toEnhancedLrc(lines: List<LyricLine>): String {
        val out = ArrayList<String>()
        out.add("[offset:0]")
        lines.forEachIndexed { i, line ->
            val lineMs = if (line.words.isNotEmpty()) {
                line.words.first().startMs
            } else {
                (line.time * 1000).roundToInt()
            }
            if (line.words.isNotEmpty()) {
                val sb = StringBuilder(lrcTimeOfMs(lineMs))
                sb.append("<00:00.000>")
                for (w in line.words) {
                    sb.append('<').append(mmssOfMs(w.startMs)).append('>').append(w.text)
                }
                // 收尾标记只在「最后一个词的结束**早于**下一行起点」时写 —— 说明源数据给了明确时长；
                // 若结束正好等于下一行起点，那只是补出来的时长，样例文件里这种行也没有收尾标记。
                val last = line.words.last()
                val endMs = last.startMs + last.durMs
                val nextLineMs = lines.getOrNull(i + 1)?.let { (it.time * 1000).roundToInt() }
                if (last.durMs > 0 && (nextLineMs == null || endMs < nextLineMs)) {
                    sb.append('<').append(mmssOfMs(endMs)).append('>')
                }
                out.add(sb.toString())
            } else {
                out.add(lrcTimeOfMs(lineMs) + line.text)
            }
            line.translation?.let { out.add(lrcTimeOfMs(lineMs) + it) }
        }
        return out.joinToString("\n")
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
        wordLyric: String? = null,
        albumArtist: String? = null,
        trackNumber: String? = null,
        discNumber: String? = null,
        releaseDate: String? = null,
        composer: String? = null,
        genre: String? = null,
        isrc: String? = null,
        copyright: String? = null,
        version: String? = null,
        /** 结构化歌手名列表；给了就按 `A / B` 拼（多歌手统一写法），没给才用 [artist] 原串 */
        artistNames: List<String> = emptyList(),
    ): String {
        var isrcValue: String? = isrc
        var versionValue: String? = version
        var copyrightValue: String? = copyright
        // Qobuz 的 `Song` 现在自己就带全了（isrc / version / copyright），
        // 只有真缺字段时才去补一次 track 详情 —— 省掉每首一次网络请求。
        if (songId.startsWith("qobuz:") &&
            (isrcValue == null || versionValue == null || copyrightValue == null)
        ) {
            runCatching { getTrack(songId.removePrefix("qobuz:"), qobuzTokenProvider().orEmpty()) }
                .getOrNull()
                ?.let {
                    isrcValue = isrcValue ?: it.isrc
                    versionValue = versionValue ?: it.version
                    copyrightValue = copyrightValue ?: it.copyright
                }
        }
        val safeTitle = decodeHtmlEntities(title)
        val safeArtist = decodeHtmlEntities(artist)
        // 先不算最终文件名：真实格式要等拿到字节才能定（见 StreamTag.extensionOf）。
        // 否则「请求 flac 但平台只给 mp3」会把 mp3 内容写成 .flac 扩展名 + FLAC 标签。
        val safeBaseName = "$safeTitle - $safeArtist".replace(Regex("[/\\\\?%*:|\"<>]"), "_")
        val mergedLyric = mergeLyrics(lyric, tlyric, wordLyric)
        val bagOf: (CoverBytes?) -> TagBag = { cover ->
            buildTagBag(
                cover = cover,
                title = safeTitle,
                artist = safeArtist,
                artistNames = artistNames,
                album = album ?: "",
                lyric = mergedLyric,
                albumArtist = albumArtist,
                trackNumber = trackNumber,
                discNumber = discNumber,
                releaseDate = releaseDate,
                composer = composer,
                genre = genre,
                isrc = isrcValue,
                version = versionValue,
                copyright = copyrightValue,
            )
        }

        val result = coroutineScope {
            // 封面与音频流并发：封面必须赶在「写出头部」之前到（flac/mp3 的标签在文件最前），
            // 但整首要下好几秒，这点 RTT 完全被遮掉。
            val coverDeferred = async(Dispatchers.IO) {
                if (coverUrl.isEmpty()) null else fetchCoverBytes(coverUrl, songId)
            }
            try {
                // 快路径：边收边封装 + 直写（一趟，零音频拷贝、零临时文件）
                _tryStreaming(
                    songId = songId,
                    downloadUrl = downloadUrl,
                    baseName = safeBaseName,
                    requestedExt = _ext(quality),
                    bagOf = bagOf,
                    cover = { coverDeferred.await() },
                    onProgress = onProgress,
                ) ?: _downloadLegacy(
                    songId = songId,
                    downloadUrl = downloadUrl,
                    baseName = safeBaseName,
                    requestedExt = _ext(quality),
                    bagOf = bagOf,
                    cover = { coverDeferred.await() },
                    onProgress = onProgress,
                )
            } catch (ex: Exception) {
                when (_activeDownloads[songId]?.cancelReason) {
                    "paused" -> {
                        _activeDownloads.remove(songId)
                        throw IllegalStateException("下载已暂停")
                    }
                    "cancelled" -> {
                        _activeDownloads.remove(songId)
                        throw IllegalStateException("下载已取消")
                    }
                    else -> {
                        // 普通失败（含 L3 内容校验不过）也要摘掉登记：
                        // 留着会让之后的「暂停 / 取消」拿到一个已经结束的 call。
                        _activeDownloads.remove(songId)
                        throw ex
                    }
                }
            }
        }
        onProgress(1.0)
        _activeDownloads.remove(songId)
        if (MoreSettings.saveLrcFile.value) _writeLrc(result, mergedLyric)
        return result
    }

    /**
     * 快路径：**边收边封装 + 直写**。
     *
     * mp3 / flac / ogg / dsd / wav / aiff 这些「标签在头部或尾部」的格式，可以在字节流经过的
     * 同时把容器头和标签写出去 —— 落地的就是成品，全程只有下载那一趟写盘。
     *
     * @return 落定地址（真实路径或 `content://`）；**null** = 这个格式不能流式注入，
     *   调用方接着走 [downloadLegacy]。
     *
     * 返回 null 时代价只有一个头部预读（≤ [StreamTag.PEEK]，通常 256KB）：
     * `StreamTag.write` 在判定放弃之前既不写目标、也不读到 head 之后。
     */
    private suspend fun _tryStreaming(
        songId: String,
        downloadUrl: String,
        baseName: String,
        requestedExt: String,
        bagOf: (CoverBytes?) -> TagBag,
        cover: suspend () -> CoverBytes?,
        onProgress: (Double) -> Unit,
    ): String? = coroutineScope {
        val request = Request.Builder().url(downloadUrl).header("User-Agent", _chromeUa).build()
        val call = globalOkHttp.newCall(request)
        _activeDownloads[songId] = ActiveDownload(call)

        withContext(Dispatchers.IO) {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Download failed: ${resp.code}")
                val body = resp.body ?: throw IOException("Download failed")
                val total = body.contentLength()
                val input = BufferedInputStream(body.byteStream(), COPY_BUFFER)
                val head = _readUpTo(input, StreamTag.PEEK)
                if (head.isEmpty()) throw IOException("Download failed: 内容为空")

                val ext = StreamTag.extensionOf(head) ?: requestedExt
                val bag = bagOf(cover())
                // 先问一句「能不能流式」。不能就在**还没写任何东西**的时候收手，
                // 交给落盘路径 —— 这样不会留下一个写了一半的 MediaStore 条目。
                if (!StreamTag.canStream(head, total, bag)) return@use null

                val sink = openDownloadSink(baseName + ext)
                    ?: throw IOException("无法创建下载目标")
                try {
                    val out = BufferedOutputStream(sink.output, COPY_BUFFER)
                    var lastPct = -1
                    val outcome = StreamTag.write(
                        head = head,
                        rest = input,
                        contentLength = total,
                        bag = bag,
                        out = out,
                        onBytes = { consumed ->
                            if (total > 0) {
                                val pct = ((consumed * 100) / total).toInt()
                                if (pct != lastPct && pct < 100) {
                                    lastPct = pct
                                    onProgress(0.02 + 0.93 * consumed.toDouble() / total)
                                }
                            }
                        },
                    )
                    if (outcome == null) {
                        // canStream 已经拦过一遍，正常到不了这里；真到了就干净回滚再去落盘
                        runCatching { sink.abort() }
                        return@use null
                    }
                    out.flush()
                    // 长度字段（RIFF/FORM/DSF/DSDIFF）只有最后一字节落地才知道，这里回填
                    for ((offset, bytes) in outcome.patches) sink.patch(offset, bytes)
                    sink.commit()
                } catch (t: Throwable) {
                    runCatching { sink.abort() }
                    throw t
                }
            }
        }
    }

    /**
     * 落盘路径：先写临时文件，再用 `AnnieTag` 打标，最后搬进目标。
     *
     * 只有 [StreamTag] 覆盖不到的格式才会走到这里（m4a 的 `moov` 在文件尾、ape 系的旧尾标签
     * 无法在流里裁掉）。`AnnieTag` 对这些格式多半也是「截尾追加」或「旧 moov 改 free」，
     * 所以这里的音频搬运通常只有一次。
     */
    private suspend fun _downloadLegacy(
        songId: String,
        downloadUrl: String,
        baseName: String,
        requestedExt: String,
        bagOf: (CoverBytes?) -> TagBag,
        cover: suspend () -> CoverBytes?,
        onProgress: (Double) -> Unit,
    ): String = coroutineScope {
        val customDir = DownloadDirService.getDownloadDirPath()
        val isCustom = !customDir.isNullOrEmpty()
        // 默认目录要入库：`scanFileToMediaStore` 用文件名当 DISPLAY_NAME，
        // 所以临时文件也必须叫最终名字，不能叫 tmp_xxx。
        val dir = if (isCustom) File(customDir) else File(_tempDir(), "pending")
        if (!dir.exists()) dir.mkdirs()

        val safeSongId = songId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val request = Request.Builder().url(downloadUrl).header("User-Agent", _chromeUa).build()
        val call = globalOkHttp.newCall(request)
        _activeDownloads[songId] = ActiveDownload(call)

        val finalFile = withContext(Dispatchers.IO) {
            var file = File(dir, "tmp_$safeSongId$requestedExt")
            if (file.exists()) file.delete()
            _streamToFile(call, file) { p -> onProgress(p * 0.9) }
            if (!file.exists()) throw IllegalStateException("Download failed")

            // 以实际字节为准确定扩展名。**认不出来就判失败**（L3 落盘校验）：
            // 走到这里说明 StreamTag 的各类 codec 全都不认识这段字节 —— 那不是音频，
            // 是「该档位不存在」的回执或错误页（加密包 `mgg`/`mflac`、跨源地址、
            // 会员/版权提示页）。以前会套用请求档位的扩展名，产出
            // 「文件名 .flac、内容是密文」的坏文件并且任务照样标成功。
            val ext = _sniffFileExt(file)
            if (ext == null) {
                val bytes = file.length()
                file.delete()
                throw IllegalStateException(
                    "下载内容不是可识别音频（$bytes 字节），已丢弃 —— " +
                        "直链返回的不是音频文件（加密包或错误页），换个音质或换个插件再试",
                )
            }
            if (ext != requestedExt) {
                val renamed = File(dir, "tmp_$safeSongId$ext")
                if (file.renameTo(renamed)) file = renamed
            }
            _tagFile(file.absolutePath, bagOf(cover()))
            onProgress(0.97)

            val target = File(dir, baseName + ext)
            if (target.exists()) target.delete()
            if (file.absolutePath != target.absolutePath) {
                if (!file.renameTo(target)) {
                    file.copyTo(target, overwrite = true)
                    file.delete()
                }
            }
            target
        }

        if (isCustom) return@coroutineScope finalFile.absolutePath

        // 默认目录：入库（Android 10+ 插入 MediaStore.Audio，文件进 Music/AnnieMusic）
        val uri = withContext(Dispatchers.IO) {
            runCatching { MediaStoreScan.scanFileToMediaStore(finalFile.absolutePath) }.getOrNull()
        }.orEmpty()
        if (uri.isNotEmpty()) {
            runCatching { finalFile.delete() }
            return@coroutineScope uri
        }
        finalFile.absolutePath
    }

    /** 扩展名：flac/hires 用 `.flac`，其余 `.mp3`（对齐 Dart `_ext`）。 */
    private fun _ext(q: Quality): String =
        if (q == Quality.flac || q == Quality.hires) ".flac"
        else if (q == Quality.atmos || q == Quality.alac || q == Quality.aac256) ".m4a"
        else ".mp3"

    /**
     * 嗅探已落盘文件的真实格式，返回带点的扩展名；不认识返回 null。
     *
     * **返回值是"能不能落盘"的判据，不是"扩展名猜一个"**：调用方拿到 null 必须判失败
     * （见 `_downloadLegacy`）。原因有两条：
     *
     * 1. 请求档位 ≠ 实际拿到的格式 —— 该曲没有无损时平台可能回 mp3，
     *    沿用请求档位定扩展名就会产出「扩展名 flac、内容 mp3」的假无损；
     * 2. 更糟的是**内容根本不是音频**：拿到的可能是加密包（`mgg` / `mflac`，
     *    酷我对不存在的档位就是这么回的、QQ 跨源也回这种）或一张会员/版权提示页。
     *    这两类送到这里都是「所有 codec 全不认识」，套扩展名落盘就得到一个打不开的
     *    文件 + 一个打勾的任务。四平台的下载走落雪插件后，这层校验更要留着 ——
     *    插件把某档位解析成加密地址时，只有这里能挡住。
     *
     * 覆盖范围由 `StreamTag.codecs` 决定（mp4 / flac / ogg / dsd / riff / ape 系 / aac / mp3，
     * 其中 mp3 认 ID3 与裸帧同步），四家平台实际会返回的容器都在里面。
     */
    private fun _sniffFileExt(file: File): String? = try {
        val head = ByteArray(64)
        val read = FileInputStream(file).use { it.read(head) }
        if (read <= 0) null else StreamTag.extensionOf(head.copyOf(read))
    } catch (_: Throwable) {
        null
    }

    /**
     * 从源流预读最多 [max] 字节（头部解析用）。
     *
     * 返回的字节会作为「已经消费过的源前缀」交给 [StreamTag.write]，它内部用
     * `SequenceInputStream` 拼回去，所以调用方**不用**把流往回退。
     */
    private fun _readUpTo(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(max, 64 * 1024))
        val chunk = ByteArray(64 * 1024)
        var left = max
        while (left > 0) {
            val n = input.read(chunk, 0, minOf(chunk.size, left))
            if (n <= 0) break
            out.write(chunk, 0, n)
            left -= n
        }
        return out.toByteArray()
    }

    /**
     * 打开下载落点：自定义目录 → 真实文件；默认目录 → MediaStore pending 条目。
     *
     * 两边的共同点是「写进去的就是最终字节」，所以下载与打标能在同一个流里完成。
     */
    internal fun openDownloadSink(displayName: String): DownloadSink? {
        val customDir = DownloadDirService.getDownloadDirPath()
        if (!customDir.isNullOrEmpty()) {
            val dir = File(customDir)
            if (!dir.exists()) dir.mkdirs()
            return FileDownloadSink(File(dir, displayName))
        }
        return MediaStoreScan.openAudioSink(displayName)
    }

    /** 写 `.lrc` 旁挂文件。目标是 `content://` 时没有「旁边」，直接跳过。 */
    private suspend fun _writeLrc(path: String, lyric: String) {
        if (path.startsWith("content://")) return
        withContext(Dispatchers.IO) {
            try {
                val audio = File(path)
                File(audio.parentFile, audio.nameWithoutExtension + ".lrc").writeText(lyric)
            } catch (ex: Throwable) {
                Log.w(TAG, "[download] write lrc failed: $ex")
            }
        }
    }

    /** 流式下载到 [tmpFile]，按 `received / total` 回调进度（对齐 dio onReceiveProgress）。 */
    private fun _streamToFile(call: Call, tmpFile: File, onProgress: (Double) -> Unit) {
        call.execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Download failed: ${resp.code}")
            val body = resp.body ?: throw IOException("Download failed")
            val total = body.contentLength()
            body.byteStream().use { input ->
                tmpFile.outputStream().use { output ->
                    val buf = ByteArray(COPY_BUFFER)
                    var received = 0L
                    var lastPct = -1
                    var n = input.read(buf)
                    while (n >= 0) {
                        output.write(buf, 0, n)
                        received += n
                        // 节流：只在整数百分比变化时上报。原先每 64KB 都回调，一首歌几百次，
                        // 批量下载上千首时会把状态流刷爆（每次 emit 都让订阅者重组成新 map）。
                        if (total > 0) {
                            val pct = ((received * 100) / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(received.toDouble() / total)
                            }
                        }
                        n = input.read(buf)
                    }
                    // 补一次收尾，避免最后不足 1% 的尾巴没上报
                    if (total > 0 && lastPct != 100) onProgress(1.0)
                }
            }
        }
    }

    /**
     * 取封面**字节**（不再落临时文件）。
     *
     * 以前封面要下载到 `cacheDir/cover_xxx.jpg`，用完再删 —— 多两次磁盘操作、还要处理
     * 「异常路径没删干净」。标签库本来就要的是字节（`AnnieTag.coverOf`），直接读进内存
     * 一步到位；封面是 600×600 的 JPEG/PNG，几十~一百多 KB。
     *
     * 失败一律返回 null：封面缺失不该让整首下载失败。
     */
    internal suspend fun fetchCoverBytes(coverUrl: String, coverCacheKey: String): CoverBytes? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(coverUrl).header("User-Agent", _chromeUa).build()
                globalOkHttp.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body ?: return@use null
                    // 有的 CDN 不报长度，所以两条路都卡上限
                    if (body.contentLength() > COVER_LIMIT) return@use null
                    val bytes = body.bytes()
                    if (bytes.size > COVER_LIMIT) null else AnnieTag.coverOf(bytes)
                }
            }.getOrNull()
        }

    // ---------------- 元数据打标 ----------------

    /**
     * 为「已落盘的文件」写元数据（供 KKBOX DRM 解密路径等复用）。
     *
     * 与 [downloadSong] 共用同一套 [buildTagBag] + [AnnieTag]；先带封面写一次，
     * 失败就去掉封面重试一次，仍失败保留原文件（不抛异常）。
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
        composer: String? = null,
        genre: String? = null,
        /** ISRC：与 [downloadSong] 的写入集对齐 */
        isrc: String? = null,
        coverCacheKey: String = inputPath,
        /** 调用方已经取好的封面；给了就不再下载 */
        prefetchedCover: CoverBytes? = null,
    ) {
        val cover = prefetchedCover
            ?: if (coverUrl.isNotEmpty()) fetchCoverBytes(coverUrl, coverCacheKey) else null
        val bag = buildTagBag(
            cover = cover,
            title = decodeHtmlEntities(title),
            artist = decodeHtmlEntities(artist),
            album = album,
            lyric = lyric,
            albumArtist = albumArtist,
            trackNumber = trackNumber,
            discNumber = discNumber,
            releaseDate = releaseDate,
            composer = composer,
            genre = genre,
            isrc = isrc,
        )
        withContext(Dispatchers.IO) { _tagFile(inputPath, bag) }
    }

    /**
     * 组装一份标签载荷。
     *
     * 抽成独立函数是为了「下载」与「事后补标」这两条路写下去的字段**必然一致** ——
     * 以前两处各抄一份 `put(...)` 列表，加字段时漏一边就会出现「下载写了、编辑界面看不到」。
     */
    private fun buildTagBag(
        cover: CoverBytes?,
        title: String,
        artist: String,
        album: String,
        lyric: String,
        albumArtist: String?,
        trackNumber: String?,
        discNumber: String?,
        releaseDate: String?,
        composer: String? = null,
        genre: String? = null,
        isrc: String? = null,
        version: String? = null,
        copyright: String? = null,
        artistNames: List<String> = emptyList(),
    ): TagBag {
        val text = LinkedHashMap<TagKey, String>()
        fun put(key: TagKey, value: String?) {
            if (!value.isNullOrEmpty()) text[key] = value
        }
        put(TagKey.Title, title)
        // 多歌手统一写成 `A / B`：有结构化列表就用它，没有才用平台原串（不硬拆）
        put(TagKey.Artist, TagMapper.artistOf(artistNames, artist))
        put(TagKey.Album, album)
        put(TagKey.AlbumArtist, albumArtist)
        put(TagKey.Track, trackNumber)
        put(TagKey.Disc, discNumber)
        put(TagKey.Year, releaseDate)
        put(TagKey.Composer, composer)
        put(TagKey.Genre, genre)
        put(TagKey.Isrc, isrc)
        put(TagKey.Comment, version)
        put(TagKey.Copyright, copyright)
        put(TagKey.Lyric, lyric)
        return TagBag(text, cover)
    }

    /**
     * 给「已落盘的文件」打标。
     *
     * 走 [AnnieTag]：能原地就原地（FLAC 复用 PADDING、MP3 复用旧 ID3 区、MP4 旧 moov 改 free），
     * 装不下才退化成「新标签 + 复制音频」。封面常是失败主因（个别容器对 PICTURE 尺寸敏感），
     * 所以带封面失败时去掉封面再试一次。
     */
    private fun _tagFile(inputPath: String, bag: TagBag) {
        val file = File(inputPath)
        var ok = AnnieTag.write(file, bag, WritePolicy.PreferInPlace).ok
        if (!ok && bag.cover != null) {
            Log.w(TAG, "[tag] 带封面打标失败，去封面重试: $inputPath")
            ok = AnnieTag.write(file, bag.copy(cover = null), WritePolicy.PreferInPlace).ok
        }
        if (!ok) {
            Log.w(TAG, "[tag] 打标失败，保留原始文件: $inputPath")
            return
        }
        val verified = readTags(inputPath)
        Log.i(TAG, "[tag] ok -> $inputPath, verified=${verified.keys}")
    }
}

