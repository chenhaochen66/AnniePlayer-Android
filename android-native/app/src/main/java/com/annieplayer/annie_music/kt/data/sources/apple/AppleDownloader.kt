package com.annieplayer.annie_music.kt.data.sources.apple

import com.annieplayer.annie_music.kt.core.audio.apple.APPLE_MDAT_HEADER
import com.annieplayer.annie_music.kt.core.audio.apple.AppleInitInfo
import com.annieplayer.annie_music.kt.core.audio.apple.AppleManifest
import com.annieplayer.annie_music.kt.core.audio.apple.AppleMediaPlaylist
import com.annieplayer.annie_music.kt.core.audio.apple.TemariDecryptor
import com.annieplayer.annie_music.kt.core.audio.apple.alacTailParams
import com.annieplayer.annie_music.kt.core.audio.apple.appleMdatSizeBytes
import com.annieplayer.annie_music.kt.core.audio.apple.appleMdatSizeOffset
import com.annieplayer.annie_music.kt.core.audio.apple.buildApplePrologue
import com.annieplayer.annie_music.kt.core.audio.apple.buildAppleTrailingMoov
import com.annieplayer.annie_music.kt.core.audio.apple.fixAlacTail
import com.annieplayer.annie_music.kt.core.audio.apple.parseAppleFragment
import com.annieplayer.annie_music.kt.core.audio.apple.parseAppleInit
import com.annieplayer.annie_music.kt.core.audio.apple.parseAppleMaster
import com.annieplayer.annie_music.kt.core.audio.apple.parseAppleMediaPlaylist
import com.annieplayer.annie_music.kt.core.audio.apple.resolveAppleVariantWithFallback
import com.annieplayer.annie_music.kt.core.audio.apple.toAppleTier
import com.annieplayer.annie_music.kt.data.DownloadService
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.local.tag.AnnieTag
import com.annieplayer.annie_music.kt.data.local.tag.CoverBytes
import com.annieplayer.annie_music.kt.data.local.tag.TagBag
import com.annieplayer.annie_music.kt.data.local.tag.TagMapper
// enrichAppleSong / fetchAppleAlbumCopyright 与本文件同包（data.sources.apple），无需 import
import com.annieplayer.annie_music.kt.data.sources.apple.wrapper.AppleWrapperApi
import com.annieplayer.annie_music.kt.data.network.globalOkHttp
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
     * 同一个 host 上，开再多也会在 Dispatcher 里排队。取 5 正好把 per-host 槽位吃满；
     * 播放走的是本机 HLS 代理（127.0.0.1，另一个 host），不会跟它抢。
     * 窗口内同时在内存里的分片数 = 这个值。
     */
    private const val SEGMENT_CONCURRENCY = 5

    /** 单个分片的最大尝试次数（= 首次 + 2 次重试）。整首重下代价太大，抖动就地补 */
    private const val SEGMENT_ATTEMPTS = 3

    /**
     * 分片重试的基础等待（第 n 次重试等 `n * 这个值`）。
     *
     * 以前是**零间隔**连打三次：移动网络瞬断（连接重置 / DNS 抖动）时三次尝试全在几十
     * 毫秒内失败，等于没有重试。给一点退避，抖动才有机会恢复。
     */
    private const val SEGMENT_RETRY_DELAY_MS = 400L

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
        // 歌手页/搜索给的 `artistName` 是 Apple 的**合并串**（`Lucas & Steve & Blackstreet`），
        // 只有 `/songs/{id}` 的 relationships 才拆得对。写标签前补一次，拿不到就退回原串。
        val tagged = enrichAppleSong(song)
        val tier = quality.toAppleTier()
            ?: throw IllegalStateException("Apple 不支持音质: ${quality.value}")

        val finalName = "${safeName(song.title)} - ${safeName(song.artist)}.m4a"
        val handles = mutableMapOf<String, TemariDecryptor.Handle>()

        try {
            onProgress(0.01)
            val masterUrl = AppleWrapperApi.fetchMasterUrl(song.id)
                ?: throw IllegalStateException("Apple master 获取失败")
            // master m3u8 / media playlist / init 都走 AppleManifest：与播放侧共用同一份缓存，
            // 「播放过再下载」时这三步全是 0 往返。
            val masterText = AppleManifest.masterText(song.id, masterUrl)
            // 目标档位缺失时按「超高解析 → 无损 → AAC」降级，与播放共用同一条链；
            // 以前这里直接抛"没有可用音质档位"，选 Atmos 批量下到非 Atmos 曲目会整首失败
            val variant = resolveAppleVariantWithFallback(
                parseAppleMaster(masterUrl, masterText),
                tier,
            ) ?: throw IllegalStateException("Apple 没有可用音质档位: ${tier.label}")

            onProgress(0.04)
            val mediaText = AppleManifest.mediaText(song.id, variant)
            val media = parseAppleMediaPlaylist(variant.uri, mediaText)
            if (media.segments.isEmpty()) throw IllegalStateException("Apple media playlist 没有分片")

            val initBytes = AppleManifest.cachedInit(song.id, variant) ?: fetchBytes(
                media.initUri,
                media.initStart.takeIf { media.initLength != null },
                media.initLength,
            ).also { AppleManifest.storeInit(song.id, variant, it) }
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
                        val template = AppleWrapperApi.fetchKeyTemplate(song.id, keyUri)
                            ?: throw IllegalStateException("Apple key 获取失败: $keyUri")
                        keyUri to template
                    }
                }.awaitAll()
            }
            for ((keyUri, template) in templates) {
                handles[keyUri] = TemariDecryptor.create(template.toByteArray())
                    ?: throw IllegalStateException("Apple 模板初始化失败")
            }

            // 落点在下载**开始前**就打开：音频字节从解密那一刻起就直接写进最终位置，
            // 不再经过 raw 临时文件，也不再有「再复制进媒体库」那一趟。
            val sink = DownloadService.openDownloadSink(finalName)
                ?: throw IllegalStateException("无法创建下载目标")

            val durations = ArrayList<Int>()
            val sizes = ArrayList<Int>()
            val chunkOffset = init.ftyp.size + APPLE_MDAT_HEADER
            val path = try {
                val out = BufferedOutputStream(sink.output, 256 * 1024)
                // ftyp + 64 位 mdat 头：chunk 起点就此确定，和后面 moov 的大小无关
                out.write(buildApplePrologue(init.ftyp))

                // 封面与分片并行取：封面是 600×600（~100KB），本该在音频全部下完
                // 之后才串行付一次 RTT。挪到这里，这点等待被并发的分片流完全遮掉。
                val cover = coroutineScope {
                    val coverDeferred = async(Dispatchers.IO) {
                        DownloadService.fetchCoverBytes(song.cover, song.id)
                    }
                    downloadSegments(
                        media = media,
                        init = init,
                        handles = handles,
                        rawOut = out,
                        durations = durations,
                        sizes = sizes,
                        onProgress = onProgress,
                    )
                    coverDeferred.await()
                }
                onProgress(0.85)

                // 标签直接编进 moov（零趟打标），moov 追在音频后面：
                // stsz/stts 要等全部 sample 到手才知道，正好这时候写。
                out.write(
                    buildAppleTrailingMoov(
                        moov = init.moov,
                        codec = track.codec,
                        durations = durations,
                        sizes = sizes,
                        chunkOffset = chunkOffset,
                        udta = AnnieTag.mp4UdtaBox(buildTagBag(tagged, lyric, cover)),
                    ),
                )
                out.flush()
                // mdat 长度只有写完才知道：64 位 largesize 回填（8 字节）
                sink.patch(
                    appleMdatSizeOffset(init.ftyp.size),
                    appleMdatSizeBytes(sizes.sumOf { it.toLong() }),
                )
                sink.commit()
            } catch (t: Throwable) {
                runCatching { sink.abort() }
                throw t
            }
            onProgress(1.0)
            return path
        } finally {
            handles.values.forEach { runCatching { it.close() } }
        }
    }

    /**
     * 滑动窗口并发下载 + 严格顺序解密写盘。
     *
     * `pending[i]` 只在前 [SEGMENT_CONCURRENCY] 个还没被消费的索引上存在，消费完就置空并
     * 补下一个 → 内存里同时最多 [SEGMENT_CONCURRENCY] 个分片。
     *
     * @param rawOut 解密后的样本直接写这里 —— 就是最终文件的 mdat，**没有中间文件**。
     */
    private suspend fun downloadSegments(
        media: AppleMediaPlaylist,
        init: AppleInitInfo,
        handles: Map<String, TemariDecryptor.Handle>,
        rawOut: OutputStream,
        durations: MutableList<Int>,
        sizes: MutableList<Int>,
        onProgress: (Double) -> Unit,
    ) = coroutineScope {
        val segments = media.segments
        val count = segments.size
        val permits = Semaphore(SEGMENT_CONCURRENCY)
        val pending = arrayOfNulls<Deferred<ByteArray>>(count)
        // ALAC 尾位修正参数：从 init 的 moov 解析**一次**（非 ALAC 为 null，整条路径零开销）
        val alac = alacTailParams(init.moov)

        fun prefetch(index: Int) {
            if (index >= count) return
            val segment = segments[index]
            pending[index] = async(Dispatchers.IO) {
                permits.withPermit {
                    fetchBytes(segment.uri, segment.rangeStart, segment.rangeLength, init)
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
            var parsedFragments = 0
            while (offset < bytes.size) {
                // 包里解析不出东西 = 这个分片是坏的（断流截断 / 运营商注入页 / 200 + HTML）。
                // 坏响应在 fetchBytes 的校验里已经拦过一次，这里是兜底：绝不能像以前那样
                // `?: break` 安静地少写几十个 sample —— 那会产出「能播但变短」的 m4a。
                val fragment = parseAppleFragment(bytes, offset, init)
                    ?: if (parsedFragments == 0) {
                        throw IOException("Apple 分片解析失败: ${segments[index].uri}")
                    } else {
                        break
                    }
                parsedFragments++
                for (sample in fragment.samples) {
                    if (sample.length <= 0) continue
                    val start = fragment.mdatPayloadOffset + sample.offset
                    val end = start + sample.length
                    if (start < 0 || end > bytes.size) {
                        throw IllegalStateException("Apple sample 越界")
                    }
                    val plain = handle.decrypt(bytes.copyOfRange(start, end))
                        ?: throw IllegalStateException("Apple sample 解密失败")
                    // 未压缩 ALAC element 的尾 3 个保留位要修成 111，否则别的播放器（含 ffmpeg）
                    // 会整帧拒绝。以前是全部下完再开文件随机 seek 修一遍，现在解密完就地改内存。
                    alac?.let { fixAlacTail(plain, it.maxBlock, it.channels, it.bits) }
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
     * 取一个分片（可按 Range），并做一次**结构校验**，失败原地重试到 [SEGMENT_ATTEMPTS] 次。
     *
     * 为什么校验放在这里：解密与写盘必须严格按分片顺序串行（`stts`/`stsz` 依赖 sample
     * 顺序），在写盘循环里抛异常就只能整首重下。这里只校验「第一层 box 解析得出来」，
     * 足够识别 200 + HTML 注入页、半截分片、返回空体这类坏响应，让重试有机会接住。
     *
     * 重试策略：4xx（除 408/429）是确定性失败（分片签名过期 / 已下线），**不重试**，
     * 免得白等三个 RTT；可重试的错误之间退避 [SEGMENT_RETRY_DELAY_MS] × 次数。
     */
    private suspend fun fetchBytes(
        url: String,
        rangeStart: Long?,
        rangeLength: Long?,
        init: AppleInitInfo? = null,
    ): ByteArray = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url).header("User-Agent", UA)
        if (rangeStart != null && rangeLength != null && rangeLength > 0) {
            builder.header("Range", "bytes=$rangeStart-${rangeStart + rangeLength - 1}")
        }
        val request = builder.build()
        var lastError: Throwable? = null
        for (attempt in 0 until SEGMENT_ATTEMPTS) {
            var retryable = true
            try {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        retryable = response.code !in 400..499 ||
                            response.code == 408 || response.code == 429
                        throw IOException("Apple 请求失败 ${response.code}: $url")
                    }
                    val body = response.body?.bytes()
                    if (body == null || body.isEmpty()) throw IOException("Apple 空响应: $url")
                    // init 分片还没解析出 [AppleInitInfo]（它就是解析的来源），只能校验非空
                    if (init != null && parseAppleFragment(body, 0, init) == null) {
                        throw IOException("Apple 分片解析失败: $url")
                    }
                    return@withContext body
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                lastError = t
                if (!retryable) break
                if (attempt < SEGMENT_ATTEMPTS - 1) {
                    delay(SEGMENT_RETRY_DELAY_MS * (attempt + 1))
                }
            }
        }
        throw lastError ?: IOException("Apple 请求失败: $url")
    }

    /** 把 Song 的元数据转成一份标签载荷（字段映射在 [TagMapper]，这里只补 Apple 特有的取数）。 */
    private suspend fun buildTagBag(song: Song, lyric: String, cover: CoverBytes?): TagBag {
        // Apple 的版权在**专辑**上（歌曲对象没有），按专辑缓存取一次
        val copyright = song.copyright ?: fetchAppleAlbumCopyright(song.albumId)
        val text = TagMapper.ofSong(song.copy(copyright = copyright), lyric)
        return TagBag(text, cover)
    }

    private fun safeName(value: String): String =
        value.replace(Regex("[/\\\\?%*:|\"<>]"), "_").ifEmpty { "Unknown" }
}
