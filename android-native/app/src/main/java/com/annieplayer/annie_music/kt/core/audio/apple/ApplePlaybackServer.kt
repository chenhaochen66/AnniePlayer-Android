package com.annieplayer.annie_music.kt.core.audio.apple

import com.annieplayer.annie_music.kt.data.model.AudioInfo
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.network.CacheService
import com.annieplayer.annie_music.kt.data.network.globalOkHttp
import com.annieplayer.annie_music.kt.data.sources.apple.wrapper.AppleWrapperApi
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * 一次 Apple 播放会话已知的源格式。`songId` 用来让播放引擎确认这份信息属于当前这首歌。
 */
data class ApplePlaybackInfo(
    val songId: String,
    val info: AudioInfo,
)

/**
 * 把 Apple 的加密 HLS 转成 127.0.0.1 上的“明文 HLS”。
 *
 * Media3 仍然负责 HLS 时间轴/seek/缓冲，ffmpeg native 负责解码；
 * 本机服务只做：拉 init/分片 → Temari 解密 → 把 mdat 原地替换成明文，
 * 并把 senc/saiz 等加密辅助盒改掉，避免 Media3 再走 DRM。
 *
 * **缓存**：明文 `init` / `seg/{n}` 由 Media3 的 `CacheDataSource` 落盘（本类只负责给出稳定
 * cache key，见 [cacheKeyFor]），所以二次播放同一首歌同一档位时不再打 Apple、也不再跑 Temari。
 * 播放列表本身**故意不缓存**（URL 带一次性 nonce），避免旧列表里的旧端口分片地址被复用。
 * 下载链路仍然独立、不碰播放缓存。
 */
object ApplePlaybackServer {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    @Volatile
    private var current: Session? = null

    /**
     * 当前这次本机 HLS 会话对应的**已知源格式**（编码 / 位深 / 采样率）。
     *
     * Apple 的播放链路是 `127.0.0.1` 上的明文 m3u8 代理，ffprobe 探不到真实编码；
     * 格式在 `parseAppleMaster` 时就已确定，播放页胶囊直接用这份数据。
     */
    @Volatile
    var currentPlayback: ApplePlaybackInfo? = null
        private set

    /**
     * 档位解析完成时的提前回调（早于 init / key 拉取）。
     *
     * 播放页的音质胶囊靠它提前拿到编码 / 位深 / 采样率，不必等整个 open() 走完。
     * 在 IO 线程调用，回调实现需要自行保证线程安全。
     */
    @Volatile
    var onInfoReady: ((ApplePlaybackInfo) -> Unit)? = null

    fun stop() {
        currentPlayback = null
        current?.close()
        current = null
    }

    /**
     * 本机 HLS 请求 → 稳定播放缓存 key；不是当前会话的分片/init 则返回 null（调用方回退）。
     *
     * key 形如 `apple:{adamId}:{tier}:init` / `apple:{adamId}:{tier}:seg:{n}`：
     * 带档位是因为同一首歌可能有 44.1/24 与 176.4/24 两个 ALAC 变体，
     * 不带档位会把两档的明文混进同一份缓存。
     */
    fun cacheKeyFor(uri: String): String? = current?.cacheKeyFor(uri)

    // ── 「下一首」预热 ──
    // 非缓存切歌的等待全在 start() 那四步（masterUrl → master → media → init+key）
    // 加上 Media3 首次拉 seg/0（一片 1~3MB + 解密）。这里提前替下一首做完，
    // 真的切过去时只剩「拿缓存 + 起播放器」。

    /** 正在预热的 key，避免并发重复打网络 */
    private val warming = java.util.Collections.newSetFromMap(
        ConcurrentHashMap<String, Boolean>(),
    )

    /** 预热出来的首片段明文（LRU 2 片，一片 1~3MB）；切到这歌时 seg/0 直接命中 */
    private val warmSegments = object : LinkedHashMap<String, ByteArray>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean =
            size > 2
    }
    private val warmLock = Any()

    /** `songId:quality`：档位不同的同一首歌首片明文不一样，不能混用 */
    private fun warmKey(songId: String, quality: Quality): String = "$songId:${quality.value}"

    private fun takeWarmSegment(songId: String, quality: Quality): ByteArray? =
        synchronized(warmLock) { warmSegments.remove(warmKey(songId, quality)) }

    private fun putWarmSegment(songId: String, quality: Quality, bytes: ByteArray) {
        synchronized(warmLock) { warmSegments[warmKey(songId, quality)] = bytes }
    }

    /**
     * 预热一首歌：把 masterUrl / master / media playlist / init / key 模板灌进
     * [CacheService]，**不会**动当前播放会话。
     *
     * [warmFirstSegment] = true 时才额外把解密好的首片段留在内存里（切过去零网络、零解密）；
     * 只给「紧邻下一首」用，更远的歌只灌元数据/密钥，省流量也不占内存。
     */
    suspend fun warm(song: Song, quality: Quality, warmFirstSegment: Boolean = true) {
        if (current?.songId == song.id) return
        val key = warmKey(song.id, quality)
        if (!warming.add(key)) return
        val session = Session(song, quality)
        try {
            withContext(Dispatchers.IO) { session.open(warmOnly = true) }
            if (warmFirstSegment) {
                val first = runCatching {
                    withContext(Dispatchers.IO) { session.clearSegment(0) }
                }.getOrNull()
                if (first != null) putWarmSegment(song.id, quality, first)
            }
        } catch (_: Throwable) {
            // 预热失败无所谓，真正切歌时会重新解析
        } finally {
            warming.remove(key)
            session.close()
        }
    }

    suspend fun start(song: Song, quality: Quality): String = withContext(Dispatchers.IO) {
        require(song.platform == MusicPlatform.apple) { "not an Apple song" }
        stop()
        val session = Session(song, quality)
        try {
            session.open()
        } catch (t: Throwable) {
            session.close()
            throw t
        }
        current = session
        currentPlayback = ApplePlaybackInfo(song.id, session.info)
        // 播放列表 URL 带一次性 nonce：列表本身不缓存（否则会命中上一次会话里
        // 带旧端口的 init/seg 地址），分片走 [cacheKeyFor] 的稳定 key
        val nonce = System.nanoTime().toString(36)
        "http://127.0.0.1:${session.port}/media.m3u8?n=$nonce"
    }

    private class Session(
        private val song: Song,
        private val quality: Quality,
    ) {
        /** 预热/复用判断用：这首歌的 id（`apple:xxx`） */
        val songId: String get() = song.id
        private var socket: ServerSocket? = null
        private lateinit var media: AppleMediaPlaylist
        private lateinit var initBytes: ByteArray
        private lateinit var tracks: AppleInitInfo
        private val handles = ConcurrentHashMap<String, TemariDecryptor.Handle>()
        @Volatile
        private var running = true

        /** 正在处理（含正在拉取/解密）的请求数；关会话时等它归零再释放模板句柄 */
        private val inflight = AtomicInteger(0)

        /** 本次会话选中的 variant 的源格式（播放页胶囊展示用） */
        lateinit var info: AudioInfo
            private set

        /** init 里解析出的音频轨是不是 ALAC（决定要不要修帧尾保留位） */
        private val alacTrack: Boolean
            get() = runCatching { tracks.tracks.values.any { it.codec == "alac" } }.getOrDefault(false)

        /**
         * ALAC 尾位修正参数，**每个会话只解析一次**。
         *
         * 以前是三个独立的 `get()` 属性（maxBlock / 声道 / 位深），每个都会走一遍
         * [findAlacCookie]（遍历 stsd），而且是在**每个 sample** 上各调一次 ——
         * 一首 4 分钟的歌等于白跑七千多次 box 遍历。
         */
        private val alacParams: AlacTailParams? by lazy {
            if (alacTrack) alacTailParams(tracks.moov) else null
        }

        /**
         * 播放缓存 key 前缀 `apple:{adamId}:{tier}`；`open()` 里按**实际选中的 variant** 定档
         * （选「ALAC 无损」但该曲只有超高解析时，variant 会落在 hires 档，缓存也要跟着它）。
         */
        @Volatile
        private var cachePrefix: String? = null

        /** 本机 HLS 的地址前缀 `http://127.0.0.1:{port}`，监听建好后才有值 */
        @Volatile
        private var cacheKeyBase: String? = null

        val port: Int
            get() = socket?.localPort ?: 0

        /** 本机 HLS 的分片 / init → 稳定缓存 key；播放列表与其它路径返回 null（不缓存） */
        fun cacheKeyFor(uri: String): String? {
            val prefix = cachePrefix ?: return null
            val base = cacheKeyBase ?: return null
            if (!uri.startsWith(base)) return null
            val path = uri.removePrefix(base).substringBefore('?')
            return when {
                path == "/init" -> "$prefix:init"
                path.startsWith("/seg/") -> "$prefix:seg:" + path.removePrefix("/seg/")
                else -> null
            }
        }

        /**
         * 解析 + 灌缓存 + 建本机 HLS 会话。
         *
         * [warmOnly] = true 时只做前两件事（给「下一首」预热用）：
         * masterUrl / master / media playlist / init / key 模板全部进 [CacheService]，
         * 但不建 ServerSocket、不改 [currentPlayback]，句柄用完即弃。
         */
        suspend fun open(warmOnly: Boolean = false) {
            val tier = quality.toTier()
                ?: throw IllegalStateException("Apple 播放不支持音质: ${quality.value}")
            val masterUrl = AppleWrapperApi.fetchMasterUrl(song.id)
                ?: throw IllegalStateException("Apple master 获取失败")
            // master m3u8 与 media playlist 都是静态文本（不含一次性签名），
            // 缓存住能把「切到播放过的歌」从 3 次 RTT 降到 0~1 次。
            // 键的构造统一在 [AppleManifest]：下载侧用的是同一套，否则「播放过再下载」不命中。
            val masterText = AppleManifest.masterText(song.id, masterUrl)
            val variants = parseAppleMaster(masterUrl, masterText)
            // 目标档位缺失时按「超高解析 → 无损 → AAC」降级（与下载共用同一条链）
            val variant = resolveAppleVariantWithFallback(variants, tier)
                ?: throw IllegalStateException(
                    "Apple 没有可用档位: tier=${tier.name} variants=${variants.size} " +
                        variants.joinToString { it.groupId }
                )
            val mediaText = AppleManifest.mediaText(song.id, variant)
            media = parseAppleMediaPlaylist(variant.uri, mediaText)
            if (media.segments.isEmpty()) throw IllegalStateException("Apple media playlist 为空")
            info = variant.toAudioInfo()
            // 档位/位深在 master 解析完就确定了，不等 init/key 拉完就先通知播放页，
            // 音质胶囊的等待从「open 全流程 2~4s」压到「master RTT ~1s」。
            val early = ApplePlaybackInfo(song.id, info!!)
            // 预热模式（给「下一首」提前灌缓存）不能碰当前会话的公开状态，
            // 否则音质胶囊会被预取的歌覆盖。
            if (!warmOnly) {
                currentPlayback = early
                runCatching { onInfoReady?.invoke(early) }
            }
            // `:v2` 是本轮「流式播放也修 ALAC 帧尾保留位」的缓存版本号：
            // v1 缓存里存的是未修正的明文，ffmpeg 会拒帧，直接换 key 让旧数据失效。
            cachePrefix = "apple:" + song.id.removePrefix("apple:") + ":" +
                (variant.tier?.cacheTag ?: variant.kind.name.lowercase()) + ":v2"

            // init 分片与所有 key 模板互不依赖，并发发出。
            // 串行时这一段 = init(0.5s) + key1(2s) + key2(2s) ≈ 4.5s，是切歌卡顿的主因；
            // 并发后压到 max(0.5s, 2s, 2s) ≈ 2s。
            // init 分片（约 1KB）也缓存：它是每次 open 都要拉的固定头部，
            // 缓存后「切到播放过的歌」只剩 key 请求（key 另有 6 小时缓存）。
            // 先看下载侧有没有留下同一档位的 init（同一个键，见 [AppleManifest.initKey]）。
            val cachedInit = AppleManifest.cachedInit(song.id, variant)

            val keyUris = media.segments.indices.map { media.keyForSegment(it) }.distinct()
            coroutineScope {
                val initDeferred = async(Dispatchers.IO) {
                    cachedInit ?: fetchRange(media.initUri, media.initStart, media.initLength)
                        .also { bytes -> AppleManifest.storeInit(song.id, variant, bytes) }
                }
                val keyDeferred = keyUris.map { keyUri ->
                    keyUri to async(Dispatchers.IO) {
                        AppleWrapperApi.fetchKeyTemplate(song.id, keyUri)
                    }
                }

                initBytes = initDeferred.await()
                for ((keyUri, deferred) in keyDeferred) {
                    val template = deferred.await()
                        ?: throw IllegalStateException("Apple key 获取失败")
                    handles[keyUri] = TemariDecryptor.create(template.toByteArray())
                        ?: throw IllegalStateException("Apple 模板初始化失败")
                }
            }

            tracks = parseAppleInit(initBytes)
                ?: throw IllegalStateException("Apple init 解析失败")
            patchAppleInitClear(initBytes)

            // 预热模式到此为止：缓存（masterUrl / master / media / init / key）已经全灌好，
            // 不建 ServerSocket、不碰 currentPlayback；模板句柄留给 warm() 解首片，用完 close()。
            if (warmOnly) return

            val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
            socket = server
            cacheKeyBase = "http://127.0.0.1:" + server.localPort
            thread(name = "apple-hls-${song.id}", isDaemon = true) {
                while (running) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) { serve(client) }
                }
            }
        }

        fun close() {
            running = false
            runCatching { socket?.close() }
            socket = null
            // 还有请求在飞就等它自己收尾（见 [serve] 的 finally），避免把正在用的模板句柄释放掉
            if (inflight.get() == 0) releaseHandles()
        }

        private fun releaseHandles() {
            handles.values.forEach { runCatching { it.close() } }
            handles.clear()
        }

        /**
         * 处理一个本机 HTTP 请求。
         *
         * 这里跑在独立线程上，异常**绝不能**往上抛：未捕获异常会走默认处理器直接把进程带走
         * （线上表现就是「Apple 播放闪退」）。失败统一回 500，让 Media3 走它自己的错误/重试路径。
         */
        private fun serve(client: Socket) {
            inflight.incrementAndGet()
            try {
                client.use { c ->
                    val input = BufferedInputStream(c.getInputStream())
                    val requestLine = readLine(input) ?: return
                    while (true) {
                        val line = readLine(input) ?: break
                        if (line.isEmpty()) break
                    }
                    val isHead = requestLine.startsWith("HEAD ")
                    // 去掉播放列表 URL 上的 nonce 查询串再做路由
                    val path = requestLine.split(' ').getOrNull(1).orEmpty().substringBefore('?')
                    val (status, type, body) = try {
                        val (t, b) = route(path)
                        Triple("200 OK", t, b)
                    } catch (_: Throwable) {
                        Triple("500 Internal Server Error", "text/plain", ByteArray(0))
                    }
                    val header = buildString {
                        append("HTTP/1.1 ").append(status).append("\r\n")
                        append("Content-Type: ").append(type).append("\r\n")
                        append("Content-Length: ").append(body.size).append("\r\n")
                        append("Connection: close\r\n\r\n")
                    }.toByteArray(Charsets.US_ASCII)
                    c.getOutputStream().use { out ->
                        out.write(header)
                        if (!isHead && body.isNotEmpty()) out.write(body)
                        out.flush()
                    }
                }
            } catch (_: Throwable) {
                // 连接被对端关掉 / 超时：什么都不做，等 Media3 自己重试
            } finally {
                if (inflight.decrementAndGet() == 0 && !running) releaseHandles()
            }
        }

        private fun route(path: String): Pair<String, ByteArray> = when {
            path == "/media.m3u8" -> "application/vnd.apple.mpegurl" to playlist()
            path == "/init" -> "video/mp4" to initBytes
            path.startsWith("/seg/") -> {
                val index = path.removePrefix("/seg/").toIntOrNull() ?: -1
                if (index < 0 || index >= media.segments.size) {
                    throw IllegalStateException("Apple 分片下标越界: $index")
                }
                "video/mp4" to clearSegment(index)
            }
            else -> throw IllegalStateException("Apple 本机 HLS 未知路径: $path")
        }

        private fun playlist(): ByteArray {
            val target = maxOf(1, ceil(media.segments.maxOfOrNull { it.durationSeconds } ?: 1.0).toInt())
            val sb = StringBuilder()
            sb.append("#EXTM3U\n")
            sb.append("#EXT-X-VERSION:7\n")
            sb.append("#EXT-X-TARGETDURATION:").append(target).append('\n')
            sb.append("#EXT-X-MEDIA-SEQUENCE:0\n")
            sb.append("#EXT-X-PLAYLIST-TYPE:VOD\n")
            sb.append("#EXT-X-INDEPENDENT-SEGMENTS\n")
            sb.append("#EXT-X-MAP:URI=\"http://127.0.0.1:").append(port).append("/init\"\n")
            media.segments.forEachIndexed { index, segment ->
                val duration = segment.durationSeconds.takeIf { it > 0 } ?: 1.0
                sb.append("#EXTINF:").append(duration).append(",\n")
                sb.append("http://127.0.0.1:").append(port).append("/seg/").append(index).append('\n')
            }
            sb.append("#EXT-X-ENDLIST\n")
            return sb.toString().toByteArray(Charsets.UTF_8)
        }

        fun clearSegment(index: Int): ByteArray {
            // 预热过的首片段直接用：省掉一次 CDN 往返 + 一次整片解密
            if (index == 0) takeWarmSegment(song.id, quality)?.let { return it }
            if (!running) throw IllegalStateException("Apple 播放会话已关闭")
            val segment = media.segments[index]
            // 先取句柄再拉流：会话已经关掉时直接失败回 500，不会先花一次网络请求
            val keyUri = media.keyForSegment(index)
            val handle = handles[keyUri]
                ?: throw IllegalStateException("Apple key 未初始化")
            // 就地解密：不再整片 copy 一份（一片 1~3MB，之前是 copyOf + 每 sample copyOfRange）
            val clear = fetchRange(segment.uri, segment.rangeStart, segment.rangeLength)
            var offset = 0
            while (offset < clear.size) {
                val fragment = parseAppleFragment(clear, offset, tracks) ?: break
                for (sample in fragment.samples) {
                    val start = fragment.mdatPayloadOffset + sample.offset
                    val end = start + sample.length
                    if (sample.length <= 0 || start < 0 || end > clear.size) {
                        continue
                    }
                    val plain = handle.decrypt(clear.copyOfRange(start, end))
                        ?: throw IllegalStateException("Apple sample 解密失败")
                    // Apple 的未压缩 ALAC element 尾部 3 个保留位可能不是 111，
                    // ffmpeg 会整帧拒绝（ERROR_CODE_DECODING_FAILED）。只按帧头精确修，
                    // 压缩帧一律不动。
                    alacParams?.let { fixAlacTail(plain, it.maxBlock, it.channels, it.bits) }
                    System.arraycopy(plain, 0, clear, start, plain.size)
                }
                offset = fragment.nextOffset
            }
            patchAppleSegmentClear(clear)
            return clear
        }

        private fun fetchRange(uri: String, start: Long?, length: Long?): ByteArray {
            val builder = Request.Builder().url(uri).header("User-Agent", UA)
            if (start != null && length != null && length > 0) {
                builder.header("Range", "bytes=$start-${start + length - 1}")
            }
            globalOkHttp.newBuilder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
                .newCall(builder.build())
                .execute()
                .use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Apple 分片请求失败 ${response.code}")
                    }
                    return response.body?.bytes() ?: ByteArray(0)
                }
        }

        private fun readLine(input: BufferedInputStream): String? {
            val out = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (out.size() == 0) null else out.toString("US-ASCII")
                if (b == '\n'.code) break
                if (b != '\r'.code) out.write(b)
            }
            return out.toString("US-ASCII")
        }
    }

    private fun Quality.toTier(): AppleTier? = when (this) {
        Quality.alac, Quality.flac -> AppleTier.AlacLossless
        Quality.hires -> AppleTier.AlacHiRes
        Quality.aac256 -> AppleTier.Aac256
        Quality.atmos -> AppleTier.Atmos
        else -> null
    }
}

/**
 * 把 ALAC element 末尾的 3 个保留位修成 `111`（就地改）。
 *
 * 只有**未压缩（is_not_compressed=1, uncompressed_bytes=0）**的 element 才需要修，
 * 且结束位置必须按帧结构精确算：`23 + has_size?32 + samples*channels*bits`。
 * 压缩帧的尾部是压缩数据本身，动它等于破坏音频 —— 所以这里严格按帧头判定，
 * 判不出来就原样返回（对照 am-hook 的 `am_alac::Config::repair_in_place`）。
 */
internal fun fixAlacTail(sample: ByteArray, maxBlock: Int, channels: Int, bits: Int) {
    if (sample.size < 7 || channels !in 1..2 || bits !in listOf(16, 20, 24, 32)) return
    val header = ((sample[0].toInt() and 0xFF) shl 16) or
        ((sample[1].toInt() and 0xFF) shl 8) or
        (sample[2].toInt() and 0xFF)
    val tag = (header shr 21) and 0x7
    val mono = channels == 1
    if (!((mono && (tag == 0 || tag == 3)) || (!mono && tag == 1))) return
    if ((header shr 5) and 0xFFF != 0) return          // unused 必须为 0
    if ((header shr 2) and 0x3 != 0) return            // uncompressed_bytes 必须为 0
    if ((header shr 1) and 0x1 != 1) return            // 必须是未压缩 element
    val partial = (header shr 4) and 0x1 != 0
    val samples = if (partial) {
        if (sample.size < 7) return
        ((sample[2].toInt() and 0xFF) shl 27) or
            ((sample[3].toInt() and 0xFF) shl 19) or
            ((sample[4].toInt() and 0xFF) shl 11) or
            ((sample[5].toInt() and 0xFF) shl 3) or
            ((sample[6].toInt() and 0xFF) shr 5)
    } else {
        maxBlock
    }
    if (samples <= 0 || samples > maxBlock) return
    val end = 23 + (if (partial) 32 else 0) + samples * channels * bits
    val totalBits = sample.size * 8
    if (end + 3 > totalBits) return
    if (totalBits - end > 10) return                    // 只有贴着尾部才算"完整 element"
    var changed = false
    for (i in end until end + 3) {
        val idx = i / 8
        val mask = 0x80 shr (i % 8)
        if (sample[idx].toInt() and mask == 0) {
            sample[idx] = (sample[idx].toInt() or mask).toByte()
            changed = true
        }
    }
    if (!changed) return
    for (i in end + 3 until totalBits) {
        val idx = i / 8
        val mask = 0x80 shr (i % 8)
        sample[idx] = (sample[idx].toInt() and mask.inv()).toByte()
    }
}
