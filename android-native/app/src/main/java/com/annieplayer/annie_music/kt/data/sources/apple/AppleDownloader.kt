package com.annieplayer.annie_music.kt.data.sources.apple

import com.annieplayer.annie_music.kt.core.audio.apple.AppleInitInfo
import com.annieplayer.annie_music.kt.core.audio.apple.AppleMediaPlaylist
import com.annieplayer.annie_music.kt.core.audio.apple.AppleTier
import com.annieplayer.annie_music.kt.core.audio.apple.TemariDecryptor
import com.annieplayer.annie_music.kt.core.audio.apple.alacFix
import com.annieplayer.annie_music.kt.core.audio.apple.buildAppleHeader
import com.annieplayer.annie_music.kt.core.audio.apple.parseAppleFragment
import com.annieplayer.annie_music.kt.core.audio.apple.parseAppleInit
import com.annieplayer.annie_music.kt.core.audio.apple.parseAppleMaster
import com.annieplayer.annie_music.kt.core.audio.apple.parseAppleMediaPlaylist
import com.annieplayer.annie_music.kt.core.audio.apple.resolveAppleVariantWithFallback
import com.annieplayer.annie_music.kt.core.audio.apple.writeAppleMp4
import com.annieplayer.annie_music.kt.core.platform.MediaStoreScan
import com.annieplayer.annie_music.kt.data.DownloadService
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.network.httpGetText
import com.annieplayer.annie_music.kt.data.sources.apple.thirdparty.fetchAppleKeyTemplate
import com.annieplayer.annie_music.kt.data.sources.apple.thirdparty.fetchAppleMasterUrl
import com.annieplayer.annie_music.kt.data.network.globalOkHttp
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Apple Music 下载：master → variant → media playlist → init/分片 → Temari 解密 →
 * 重封装为普通 m4a。
 *
 * 下载链路不读写 Media3 `SimpleCache`，分片直接走 OkHttp；失败只留临时文件并在
 * finally 中删除。
 *
 * ## 为什么分片要并发
 * 一首歌的 media playlist 常有几十上百个几 MB 的分片，串行下载时每个分片都要付一次
 * 完整的 TCP/HTTP 往返，RTT 与握手时间会盖过分片本身的传输时间（真机实测是主要耗时）。
 * 这里按 [SEGMENT_CONCURRENCY] 的滑动窗口并发**下载**，解密与写盘仍然严格按分片顺序串行
 * ——`stts`/`stsz` 依赖 sample 顺序，乱序写会直接产出时间轴错乱的文件。
 */
object AppleDownloader {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /**
     * 分片并发下载数。
     *
     * 上限来自 OkHttp 的 `Dispatcher.maxRequestsPerHost`（默认 5）：Apple 的 init/分片都在
     * 同一个 host 上，开再多也会在 Dispatcher 里排队。留 4 个并发既吃满带宽，也给
     * 播放/歌词等其它请求留一个槽位。窗口内同时在内存里的分片数 = 这个值。
     */
    private const val SEGMENT_CONCURRENCY = 4

    /** 单个分片的最大尝试次数（= 首次 + 2 次重试）。整首重下代价太大，抖动就地补 */
    private const val SEGMENT_ATTEMPTS = 3

    /**
     * 下载专用 client：复用 [globalOkHttp] 的连接池/Dispatcher（`newBuilder()` 是复制配置，
     * 连接池仍共享），只把读超时放宽到分片级别。
     */
    private val httpClient: OkHttpClient by lazy {
        globalOkHttp.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    suspend fun download(
        song: Song,
        quality: Quality,
        lyric: String,
        onProgress: (Double) -> Unit,
    ): String {
        require(song.platform == MusicPlatform.apple) { "not an Apple song" }
        val tier = quality.toAppleTier()
            ?: throw IllegalStateException("Apple 不支持音质: ${quality.value}")

        val downloadDir = DownloadService.downloadDirectory()
        val tempDir = DownloadService.tempDirectory()
        tempDir.mkdirs()
        val safeId = song.id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val rawFile = File(tempDir, "apple_${safeId}_${System.nanoTime()}.raw")
        val partFile = File(tempDir, "apple_${safeId}_${System.nanoTime()}.part.m4a")
        val finalName = "${safeName(song.title)} - ${safeName(song.artist)}.m4a"
        val finalFile = File(downloadDir, finalName)
        val handles = mutableMapOf<String, TemariDecryptor.Handle>()

        try {
            onProgress(0.01)
            val masterUrl = fetchAppleMasterUrl(song.id)
                ?: throw IllegalStateException("Apple master 获取失败")
            val masterText = httpGetText(masterUrl)
            // 目标档位缺失时按「超高解析 → 无损 → AAC」降级，与播放共用同一条链；
            // 以前这里直接抛"没有可用音质档位"，选 Atmos 批量下到非 Atmos 曲目会整首失败
            val variant = resolveAppleVariantWithFallback(
                parseAppleMaster(masterUrl, masterText),
                tier,
            ) ?: throw IllegalStateException("Apple 没有可用音质档位: ${tier.label}")

            onProgress(0.04)
            val mediaText = httpGetText(variant.uri)
            val media = parseAppleMediaPlaylist(variant.uri, mediaText)
            if (media.segments.isEmpty()) throw IllegalStateException("Apple media playlist 没有分片")

            val initBytes = fetchBytes(
                media.initUri,
                media.initStart.takeIf { media.initLength != null },
                media.initLength,
            )
            val init = parseAppleInit(initBytes)
                ?: throw IllegalStateException("Apple init segment 解析失败")
            val track = init.tracks.values.firstOrNull()
                ?: throw IllegalStateException("Apple init 没有音频轨")

            // 先把用到的 key 模板一次性并发取好（最多两个：segment 0 的固定 key + 单曲 key）。
            // 放在这里是为了不让它们落在「边下边解」的关键路径上串行等 RTT。
            val keyUris = media.segments.indices.map { media.keyForSegment(it) }.distinct()
            val templates = coroutineScope {
                keyUris.map { keyUri ->
                    async(Dispatchers.IO) {
                        val template = fetchAppleKeyTemplate(song.id, keyUri)
                            ?: throw IllegalStateException("Apple key 获取失败: $keyUri")
                        keyUri to template
                    }
                }.awaitAll()
            }
            for ((keyUri, template) in templates) {
                handles[keyUri] = TemariDecryptor.create(template.toByteArray())
                    ?: throw IllegalStateException("Apple 模板初始化失败")
            }

            val rawOut = BufferedOutputStream(FileOutputStream(rawFile), 256 * 1024)
            val durations = ArrayList<Int>()
            val sizes = ArrayList<Int>()
            try {
                downloadSegments(
                    media = media,
                    init = init,
                    handles = handles,
                    rawOut = rawOut,
                    durations = durations,
                    sizes = sizes,
                    onProgress = onProgress,
                )
            } finally {
                rawOut.close()
            }

            val header = buildAppleHeader(
                ftyp = init.ftyp,
                moov = init.moov,
                codec = track.codec,
                durations = durations,
                sizes = sizes,
            )
            writeAppleMp4(header, rawFile, partFile)
            if (track.codec == "alac") alacFix(partFile)
            onProgress(0.78)

            withContext(Dispatchers.IO) {
                DownloadService.tagAudioFile(
                    inputPath = partFile.absolutePath,
                    title = song.title,
                    artist = song.artist,
                    album = song.album.orEmpty(),
                    coverUrl = song.cover,
                    lyric = lyric,
                    albumArtist = song.albumArtist,
                    trackNumber = song.trackNumber,
                    discNumber = song.discNumber,
                    releaseDate = song.releaseDate,
                    composer = song.composer,
                    genre = song.genre,
                    coverCacheKey = song.id,
                )
            }
            onProgress(0.9)

            downloadDirFile(downloadDir).mkdirs()
            if (finalFile.exists()) finalFile.delete()
            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
            }
            onProgress(1.0)

            val custom = DownloadService.hasCustomDownloadDirectory()
            if (custom) return finalFile.absolutePath

            val uri = withContext(Dispatchers.IO) {
                runCatching { MediaStoreScan.scanFileToMediaStore(finalFile.absolutePath) }
                    .getOrNull()
            }.orEmpty()
            if (uri.isNotEmpty()) {
                runCatching { finalFile.delete() }
                return uri
            }
            return finalFile.absolutePath
        } finally {
            handles.values.forEach { runCatching { it.close() } }
            runCatching { if (rawFile.exists()) rawFile.delete() }
            runCatching { if (partFile.exists()) partFile.delete() }
        }
    }

    /**
     * 滑动窗口并发下载 + 严格顺序解密写盘。
     *
     * `pending[i]` 只在前 [SEGMENT_CONCURRENCY] 个还没被消费的索引上存在，消费完就置空并
     * 补下一个 → 内存里同时最多 [SEGMENT_CONCURRENCY] 个分片。
     */
    private suspend fun downloadSegments(
        media: AppleMediaPlaylist,
        init: AppleInitInfo,
        handles: Map<String, TemariDecryptor.Handle>,
        rawOut: BufferedOutputStream,
        durations: MutableList<Int>,
        sizes: MutableList<Int>,
        onProgress: (Double) -> Unit,
    ) = coroutineScope {
        val segments = media.segments
        val count = segments.size
        val permits = Semaphore(SEGMENT_CONCURRENCY)
        val pending = arrayOfNulls<Deferred<ByteArray>>(count)

        fun prefetch(index: Int) {
            if (index >= count) return
            val segment = segments[index]
            pending[index] = async(Dispatchers.IO) {
                permits.withPermit {
                    fetchBytes(segment.uri, segment.rangeStart, segment.rangeLength)
                }
            }
        }

        for (i in 0 until minOf(SEGMENT_CONCURRENCY, count)) prefetch(i)

        for (index in 0 until count) {
            val bytes = pending[index]!!.await()
            pending[index] = null

            val handle = handles[media.keyForSegment(index)]
                ?: throw IllegalStateException("Apple key 未初始化")
            var offset = 0
            while (offset < bytes.size) {
                val fragment = parseAppleFragment(bytes, offset, init) ?: break
                for (sample in fragment.samples) {
                    if (sample.length <= 0) continue
                    val start = fragment.mdatPayloadOffset + sample.offset
                    val end = start + sample.length
                    if (start < 0 || end > bytes.size) {
                        throw IllegalStateException("Apple sample 越界")
                    }
                    val plain = handle.decrypt(bytes.copyOfRange(start, end))
                        ?: throw IllegalStateException("Apple sample 解密失败")
                    rawOut.write(plain)
                    durations += sample.duration.takeIf { it > 0 } ?: 1024
                    sizes += plain.size
                }
                offset = fragment.nextOffset
            }

            prefetch(index + SEGMENT_CONCURRENCY)
            onProgress(0.06 + 0.62 * (index + 1) / count)
        }
    }

    /**
     * 取一个分片（可按 Range）。
     *
     * 失败会原地重试到 [SEGMENT_ATTEMPTS] 次：并发下载时任一失败都会连带取消整首歌，
     * 而移动网络下半路断流很常见，重下一次比整首重下便宜得多。
     */
    private suspend fun fetchBytes(
        url: String,
        rangeStart: Long?,
        rangeLength: Long?,
    ): ByteArray = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url).header("User-Agent", UA)
        if (rangeStart != null && rangeLength != null && rangeLength > 0) {
            builder.header("Range", "bytes=$rangeStart-${rangeStart + rangeLength - 1}")
        }
        val request = builder.build()
        var lastError: Throwable? = null
        for (attempt in 0 until SEGMENT_ATTEMPTS) {
            try {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Apple 请求失败 ${response.code}: $url")
                    }
                    return@withContext response.body?.bytes() ?: ByteArray(0)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw lastError ?: IOException("Apple 请求失败: $url")
    }

    private fun Quality.toAppleTier(): AppleTier? = when (this) {
        Quality.alac, Quality.flac -> AppleTier.AlacLossless
        Quality.hires -> AppleTier.AlacHiRes
        Quality.aac256 -> AppleTier.Aac256
        Quality.atmos -> AppleTier.Atmos
        else -> null
    }

    private fun safeName(value: String): String =
        value.replace(Regex("[/\\\\?%*:|\"<>]"), "_").ifEmpty { "Unknown" }

    private fun downloadDirFile(path: String): File = File(path)
}
