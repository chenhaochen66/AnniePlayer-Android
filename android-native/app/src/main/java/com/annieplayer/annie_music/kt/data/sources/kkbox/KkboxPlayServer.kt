package com.annieplayer.annie_music.kt.data.sources.kkbox

import android.content.Context
import com.annieplayer.annie_music.kt.data.model.KkboxSession
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.network.globalOkHttp
import com.annieplayer.annie_music.kt.data.network.retryUntilSuccess
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

// ================================================================================================
// KKBOX 高音质流式播放：本机 HTTP 服务 = 回源 + 解密 + 顺带落盘
// ================================================================================================
//
// 与 Apple 的 `ApplePlaybackServer` **同一套结构**：播放器每次 Range 请求，服务端就按需
// 回源取那一段、当场解密、转发给播放器，同时把解密结果写进本地缓存文件。
//
// 之前的结构是「后台协程先把整首下完解密、代理轮询读这个还在增长的文件」——
// 名义上叫边播边下，实际上没有节流点：一首 31MB 的 FLAC 播完即整首落盘，预取两首就是
// 60MB+，而且拖进度条要等那个后台下载队列。改成按请求拉之后：
//
//   · 起播延迟 = 一次 RTT（不再等任何预下载）
//   · 播多少下多少（播放器不读，源站就不会被要数据）
//   · 拖进度条直接按区间回源，不等后台队列
//   · 听过的区间留在缓存里，下次播放整段命中磁盘
//
// RC4 是流密码，跳到第 N 个音频字节只需把 keystream 前 (512 + N) 个丢掉 —— 这也是为什么
// 「单条连续流」也能像 HLS 分片那样随机访问：分片只是给了一个天然的 N。

object KkboxPlayServer {

    /** 拿票据 + 首块：慢网/CDN 抖动时 8s 偏紧，给 12s；再超时由调用方退回 128k 明文流 */
    private const val HEADER_WAIT_MS = 12_000L

    /**
     * 缓存分块粒度。
     *
     * `serveInner` 按块判断「这段在不在本地」。**回源区间不再向块首对齐**：对齐会让
     * 「拖到 50%」多读前面那半块才发出第一个字节，直接拉低了首字节速度；
     * 现在改为从请求位置原样回源，只把「完整覆盖的块」标记为已缓存。
     */
    private const val CHUNK = 256 * 1024L

    /**
     * 顺序预读的前瞻**时长**（秒），与 ExoPlayer 的 `maxBufferMs = 30_000` 对齐。
     *
     * 按时间而不是字节，是因为 `DefaultLoadControl` 本身也按时间算：固定字节数在不同
     * 档位上完全不等价 —— Hi-Res 里 1.14 Mbps 的 4MB ≈ 29 秒，而 6.4 Mbps 的大长曲
     * （一二百 MB）4MB 只有 7 秒，播放器半秒读穿就卡。
     *
     * **不整首下完**：其他平台（网易/QQ/Apple/Qobuz）走 `CacheDataSource`，加载量由
     * ExoPlayer 的 `LoadControl` 决定 —— 播放点之后保持约 30 秒、到上限就停止请求。
     * 这里保持同一个窗口，行为和缓存形态才一致。
     */
    private const val AHEAD_SECONDS = 30L

    /** 起播前先备好的时长（秒）。1 秒是 `bufferForPlaybackMs`，8 秒留够抗抖。 */
    private const val PRIME_SECONDS = 8L

    private const val MIN_READAHEAD = 3L * 1024 * 1024
    private const val MAX_READAHEAD = 24L * 1024 * 1024
    private const val MIN_PRIME = 1L * 1024 * 1024
    private const val MAX_PRIME = 12L * 1024 * 1024

    private var _cacheDir: File? = null

    /** 对象级作用域：连接预热这类「没有会话也会跑」的活放这里 */
    private val _scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 同时存活的会话（key = 歌曲 id + 档位）。播放器会**预取下一首**，单槽位会互相踢死。 */
    private val _sessions = LinkedHashMap<String, Session>()
    private const val MAX_SESSIONS = 4

    fun init(context: Context) {
        _cacheDir = context.cacheDir
        // TCP/TLS 握手本身就占冷启动首包延迟的大头：应用起来先把 CDN 连接热好，
        // 第一首歌的 prime 请求就不用再付这笔钱。失败无所谓，正式请求会自己建连。
        _scope.launch {
            runCatching {
                val req = Request.Builder()
                    .url("https://api-kkbox-audio.qp.kkbox.com.tw/")
                    .header("User-Agent", kkboxUa)
                    .head()
                    .build()
                globalOkHttp.newCall(req).execute().close()
            }
        }
    }

    private fun _dir(): File {
        val dir = File(_cacheDir ?: File(System.getProperty("java.io.tmpdir").orEmpty()), "kkbox_play")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 全部关掉（退出播放 / 清缓存时调用；**不要**在每次取链时调用） */
    fun stop() {
        synchronized(_sessions) {
            _sessions.values.forEach { it.close() }
            _sessions.clear()
        }
    }

    /**
     * 让所有代理会话退居后台（切到别的平台时调用）。
     *
     * 后台会话只保证「够起播」，不再全速拉整首，把带宽让给当前这首。
     */
    fun markAllBackground() {
        synchronized(_sessions) { _sessions.values.forEach { it.markBackground() } }
    }

    /**
     * 播放器**真的开始播**这个 url 了：对应会话提为前台，其余降级。
     *
     * 这是唯一该改前台状态的地方 —— 取链（`playUrl`）既可能是「用户点了这首」，
     * 也可能是「预取下一首」，在那里改状态会把正在播的那首降级、把预取的那首提上来，
     * 结果正好反过来（当前歌停预读，下一首开始全速拉）。
     *
     * 传入的不是本机代理地址（例如 128k 明文直链）时，等于把所有会话都降级。
     */
    fun markForegroundByUrl(url: String) {
        synchronized(_sessions) {
            _sessions.values.forEach { s ->
                if (s.url == url) s.markForeground() else s.markBackground()
            }
        }
    }

    /**
     * 起一个本机代理播这首歌。
     *
     * 同一首歌同一档位**复用已有会话**：播放器 seek / 重试会重复取链，若每次都新建，
     * 旧连接被 server 关掉 —— 表现就是「点下去播两秒就停」。
     *
     * @return 交给 Media3 的 `http://127.0.0.1:PORT/play` 地址
     * @throws IllegalStateException 拿不到票据 / 拿不到总长度（无法给 Content-Length，seek 会坏）
     */
    suspend fun start(song: Song, quality: Quality, session: KkboxSession): String =
        withContext(Dispatchers.IO) {
            val key = "${song.id}|${quality.value}"
            synchronized(_sessions) { _sessions[key] }?.let { alive ->
                if (alive.isOpen()) {
                    // 命中就是「最近用过」：挪到末尾，别在下次新建时被当成最旧的淘汰掉
                    synchronized(_sessions) {
                        _sessions.remove(key)
                        _sessions[key] = alive
                    }
                    return@withContext alive.url
                }
                synchronized(_sessions) { _sessions.remove(key) }
                alive.close()
            }
            val s = Session(song, quality, session, _dir())
            s.open()
            synchronized(_sessions) {
                _sessions[key] = s
                while (_sessions.size > MAX_SESSIONS) {
                    val victim = _sessions.keys.firstOrNull { it != key } ?: break
                    _sessions.remove(victim)?.close()
                }
            }
            // 前台/后台由 `markForegroundByUrl` 在播放器真正 setSource 时决定 —— 取链阶段
            // 分不清「用户点了这首」和「预取下一首」，这里不能擅自提升自己
            s.url
        }

    private class Session(
        private val song: Song,
        private val quality: Quality,
        private val session: KkboxSession,
        dir: File,
    ) {
        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val port: Int = server.localPort
        val url: String = "http://127.0.0.1:$port/play"

        private val safeId = song.id.replace(Regex("[^A-Za-z0-9_-]"), "_")
        private val part = File(dir, "play_${safeId}_${quality.value}.part")

        /**
         * 分块位图（`[块数 int][位图]`）。
         *
         * 「听了一半就切歌」是常态：没有这份索引，下次回来只能从头重下；
         * 有了它，已经落盘的那几分几秒照样秒开。
         */
        private val idxFile = File(dir, "play_${safeId}_${quality.value}.idx")

        private val raf = RandomAccessFile(part, "rw")
        private val channel = raf.channel

        @Volatile private var totalBytes: Long = 0L
        @Volatile private var kkdrmUrl: String = ""
        @Volatile private var failure: String? = null
        @Volatile private var downloadDone = false
        /** 源站是否认 Range。不认就退回「一次拉完」的老路，功能不受影响。 */
        @Volatile private var rangeSupported = true
        /** 播放器读到哪了：顺序预读以它为基准往前推 */
        @Volatile private var servedPos = 0L
        /**
         * 这首是不是「当前正在播的那首」。
         *
         * 只有前台会话可以全速把整首下完；切走之后立刻降级成后台，
         * 只填够起播的量 —— 否则「100MB 的歌听到一半切走，旧会话还在全速拉剩下的 60MB」，
         * 新歌的 prime 请求抢不到带宽，15s 超时后界面就报「加载失败」。
         */
        @Volatile private var foreground = false

        /** 按 [CHUNK] 粒度的「这一块已解密落盘」标记 */
        @Volatile private var chunks: BooleanArray? = null
        private val chunkLock = Any()
        private var cachedChunks = 0
        private var lastIndexSaveAt = 0L

        private val headerReady = CountDownLatch(1)
        private var acceptJob: Job? = null
        private var prefetchJob: Job? = null

        /** 会话还能用吗：server 已关或准备阶段已失败都不算 */
        fun isOpen(): Boolean = !server.isClosed && failure == null

        /** 这首开始播了：允许全速下完 */
        fun markForeground() {
            foreground = true
        }

        /** 切走了：预读降级为「只保证起播」，把带宽让给新歌 */
        fun markBackground() {
            foreground = false
        }

        fun open() {
            File(part.path + ".done").delete()
            scope.launch {
                try {
                    val ticket = fetchKkboxDownloadTicket(song.id, kkboxQualityKeyOf(quality))
                    if (!isDrmFormat(ticket.formatName)) {
                        // 票里只剩明文 128k：这里没法给内容（调用方按 128k 直接播流处理）
                        failure = "该档位不可用"
                        return@launch
                    }
                    kkdrmUrl = ticket.url
                    val prime = primeFirstChunk(ticket.url)
                    totalBytes = prime.first
                    rangeSupported = prime.second
                    chunks = BooleanArray(((totalBytes + CHUNK - 1) / CHUNK).toInt())
                    if (!restoreIndex()) {
                        // 没有可复用的部分缓存：从头写
                        runCatching { channel.truncate(0) }
                        idxFile.delete()
                        synchronized(chunkLock) { cachedChunks = 0 }
                    }
                    val head = prime.third
                    if (head.isNotEmpty()) {
                        // 块 0 的内容随探测一起拿到了，直接落盘（重复写同位置无害）
                        channel.write(ByteBuffer.wrap(head), 0L)
                        markChunks(0, head.size - 1L)
                    }
                } catch (e: Throwable) {
                    failure = e.message ?: "准备失败"
                } finally {
                    headerReady.countDown()
                }
            }

            acceptJob = scope.launch {
                while (true) {
                    val client = try {
                        server.accept()
                    } catch (_: Throwable) {
                        break
                    }
                    launch { serve(client) }
                }
            }

            prefetchJob = scope.launch { prefetchLoop() }

            // 等总长度：没有它就没法给 Content-Length，Media3 的 seek 会坏
            val ok = headerReady.await(HEADER_WAIT_MS, TimeUnit.MILLISECONDS)
            if (!ok || totalBytes <= 0) {
                val reason = failure ?: "拿不到音频长度"
                close()
                throw IllegalStateException("KKBOX 高音质准备失败：$reason")
            }
        }

        /** 平均码率（字节/秒）。`duration` 未知时按 1 秒算，会被 clamp 到上限。 */
        private fun bytesPerSecond(): Long {
            val dur = song.duration.coerceAtLeast(1)
            return (totalBytes / dur).coerceAtLeast(1L)
        }

        /** 起播前要备好的字节数（约 [PRIME_SECONDS] 秒） */
        private fun primeTarget(): Long =
            (bytesPerSecond() * PRIME_SECONDS)
                .coerceIn(MIN_PRIME, MAX_PRIME)
                .coerceAtMost(totalBytes)

        /** 播放点之后要保持的字节数（约 [AHEAD_SECONDS] 秒） */
        private fun readaheadBytes(): Long =
            (bytesPerSecond() * AHEAD_SECONDS)
                .coerceIn(MIN_READAHEAD, MAX_READAHEAD)
                .coerceAtMost(totalBytes)

        /**
         * 第一次回源：一次请求同时拿到「总长度」和「起播要用的那段」。
         *
         * Range 上界按 [MAX_PRIME] 申请，但**读到算出来的起播量就断开** —— 一个 TTFB 拿到
         * 总长 + 缓冲，又不会为大长曲白读几十 MB。
         *
         * @return (解密后总长, 源站是否认 Range, 起播段的解密数据)
         */
        private fun primeFirstChunk(url: String): Triple<Long, Boolean, ByteArray> {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", kkboxUa)
                .header("Range", "bytes=1024-${1024 + MAX_PRIME - 1}")
                .build()
            globalOkHttp.newCall(req).execute().use { resp ->
                val contentRange = resp.header("Content-Range")
                val full = contentRange?.substringAfterLast('/')?.toLongOrNull() ?: 0L
                if (resp.code == 206 && full > 1024) {
                    val total = full - 1024
                    val bps = (total / song.duration.coerceAtLeast(1)).coerceAtLeast(1L)
                    val limit = (bps * PRIME_SECONDS)
                        .coerceIn(MIN_PRIME, MAX_PRIME)
                        .coerceAtMost(total)
                    val rc4 = KkboxRc4(session.licContentKey, 512)
                    val buf = ByteArray(64 * 1024)
                    val dec = java.io.ByteArrayOutputStream(limit.toInt())
                    var p = 0L
                    resp.body?.byteStream()?.use { ins ->
                        while (p < limit) {
                            val n = ins.read(buf)
                            if (n <= 0) break
                            val take = minOf(n.toLong(), limit - p).toInt()
                            rc4.processInPlace(buf, 0, take)
                            dec.write(buf, 0, take)
                            p += take
                        }
                    }
                    return Triple(total, true, dec.toByteArray())
                }
                // 源站不认 Range：响应就是整个加密文件，长度即总长（只读了开头就关掉）
                val length = resp.header("Content-Length")?.toLongOrNull() ?: 0L
                if (length > 1024) return Triple(length - 1024, false, ByteArray(0))
            }
            throw IllegalStateException("拿不到音频长度")
        }

        private fun serve(client: Socket) {
            // 播放器 seek / 切歌会直接断开旧连接，写已关闭的 socket 会抛 SocketException。
            // 协程里逃出的异常会交给默认 handler → **整个进程崩**（歌手页闪退就是这么来的），
            // 所以这里必须整体吞掉：一条连接失败只代表这条连接结束。
            runCatching { serveInner(client) }
        }

        private fun serveInner(client: Socket) {
            client.use { socket ->
                val input = BufferedInputStream(socket.getInputStream())
                val requestLine = readLine(input) ?: return
                if (!requestLine.startsWith("GET")) return
                foreground = true
                var rangeHeader: String? = null
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", ignoreCase = true)) rangeHeader = line.substringAfter(':').trim()
                }

                val total = totalBytes
                val spec = parseKkboxRangeHeader(rangeHeader, total)
                val head = kkboxPlayHead(spec, total)
                if (head.code == 416) {
                    writeHeaders(socket, 416, head.contentRange + "\r\n", 0L)
                    return
                }
                writeHeaders(
                    socket,
                    head.code,
                    if (head.contentRange.isEmpty()) "" else head.contentRange + "\r\n",
                    head.length,
                )

                val out = socket.getOutputStream()
                val end = spec.end
                var pos = spec.start
                if (!rangeSupported) {
                    // 源站不认 Range：只能从头拉一遍（少见，功能仍正确）
                    streamSource(0L, total - 1, if (pos == 0L) out else null)
                    if (pos > 0L) {
                        copyCached(pos, end, out)
                    }
                    return
                }
                while (pos <= end) {
                    if (isChunkCached(pos)) {
                        val next = copyCached(pos, end, out)
                        if (next <= pos) break
                        pos = next
                    } else {
                        // 单次回源最多 READAHEAD_BYTES：否则「拖到 50%」会一口气把剩下半首全拉下来，
                        // 既占带宽又让预读插不进来。播放器读到这里会再发请求（那时预读已备好）。
                        val runEnd = minOf(
                            kkboxUncachedSpanEnd(pos, end, CHUNK, chunks),
                            pos + readaheadBytes() - 1,
                        )
                        if (runEnd < pos) break
                        streamSource(pos, runEnd, out)
                        pos = runEnd + 1
                    }
                    servedPos = pos
                }
            }
        }

        private fun chunkIndexOf(pos: Long): Int = kkboxChunkIndexOf(pos, CHUNK)

        private fun isChunkCached(pos: Long): Boolean {
            val c = chunks ?: return false
            val i = chunkIndexOf(pos)
            return i in c.indices && c[i]
        }

        /** 把已缓存的连续区间写给播放器，返回新的读取位置 */
        private fun copyCached(pos: Long, end: Long, out: OutputStream): Long {
            var p = pos
            val buf = ByteBuffer.allocate(64 * 1024)
            while (p <= end) {
                val i = chunkIndexOf(p)
                val c = chunks ?: return p
                if (i !in c.indices || !c[i]) return p
                val blockEnd = minOf(end, i.toLong() * CHUNK + CHUNK - 1)
                var cursor = p
                while (cursor <= blockEnd) {
                    buf.clear()
                    val want = minOf(buf.capacity().toLong(), blockEnd - cursor + 1).toInt()
                    buf.limit(want)
                    val n = channel.read(buf, cursor)
                    if (n <= 0) return cursor
                    out.write(buf.array(), 0, n)
                    out.flush()
                    cursor += n
                }
                p = cursor
            }
            return p
        }

        /**
         * 回源取一段、当场解密、同时写给播放器和本地缓存。
         *
         * **不再向块首对齐**：从请求位置原样回源，第一个字节立刻就能发出去
         * （对齐会让「拖到 50%」先多读半块再吐数据）。代价是起点所在的不完整块不会被标记，
         * 下次从该块起点读时会再回源一次 —— 顺序播放时起点本来就落在块首，不会触发。
         *
         * [out] 为 null 时只落盘不转发（预读、源站不支持 Range 的兜底路径会用到）。
         */
        private fun streamSource(pos: Long, to: Long, out: OutputStream?, shouldStop: (() -> Boolean)? = null) {
            val rangeFrom = 1024 + pos
            val rangeTo = 1024 + to
            val request = Request.Builder()
                .url(kkdrmUrl)
                .header("User-Agent", kkboxUa)
                .header("Range", "bytes=$rangeFrom-$rangeTo")
                .build()
            globalOkHttp.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return
                val body = resp.body ?: return
                val input: InputStream = body.byteStream()
                val rc4 = KkboxRc4(session.licContentKey, 512 + pos.toInt())
                val buf = ByteArray(64 * 1024)
                var p = pos
                input.use { ins ->
                    while (p <= to) {
                        if (shouldStop?.invoke() == true) break
                        val n = ins.read(buf)
                        if (n <= 0) break
                        val take = minOf(n.toLong(), to - p + 1).toInt()
                        // 原地异或：流式路径每块都走这里，零分配才不会在拖动时抖 GC
                        rc4.processInPlace(buf, 0, take)
                        // 落盘：按解密后的位置写（FileChannel 的带位置写是原子的）
                        channel.write(ByteBuffer.wrap(buf, 0, take), p)
                        if (out != null) {
                            out.write(buf, 0, take)
                            out.flush()
                        }
                        p += take
                    }
                }
                markChunks(pos, minOf(p - 1, to))
            }
        }

        /** 标记「整块已落盘」；全部到齐就写 `.done`（下次播放直接走 `file://`） */
        private fun markChunks(from: Long, to: Long) {
            val c = chunks ?: return
            if (to < from) return
            val first = kkboxChunkFirstFull(from, CHUNK)
            // 文件末尾那一块可能不满 CHUNK：它到 EOF 就是完整内容，允许直接标记，
            // 否则每次播放都要为最后几百字节回源一次。
            val last = if (to >= totalBytes - 1) c.size - 1 else kkboxChunkLastFull(to, CHUNK)
            if (last < first) return
            synchronized(chunkLock) {
                for (i in first..last) {
                    if (i in c.indices && !c[i]) {
                        c[i] = true
                        cachedChunks++
                    }
                }
                if (!downloadDone && cachedChunks >= c.size) {
                    downloadDone = true
                    channel.force(false)
                    runCatching { File(part.path + ".done").createNewFile() }
                }
            }
            saveIndexThrottled()
        }

        /**
         * 顺序预读：只把播放位置之后 [AHEAD_SECONDS] 秒填满，不做整首下载。
         *
         * 这个窗口和其他平台的 `CacheDataSource` + `LoadControl` 完全等价：
         * 播放推进 → 窗口跟着滑动 → 听完一首自然整首都在磁盘（`.done`），
         * 暂停/切歌后就不再往前拉。
         *
         * 一次请求拉满整个窗口而不是逐块：单块 256KB 实测要 1.4 秒，其中绝大部分是首包
         * 延迟，分十几次请求就是二十秒；一次拉完只付一个 TTFB。
         */
        private suspend fun prefetchLoop() {
            while (true) {
                if (!isOpen() || totalBytes <= 0 || chunks == null) {
                    delay(80)
                    continue
                }
                // 先补播放点之后，再回头补前面的空洞（seek 过的歌前面会留洞）
                var from = nextUncachedChunkStart(servedPos)
                if (from < 0) from = nextUncachedChunkStart(0)
                if (from < 0) {
                    // 整首都在了
                    delay(300)
                    continue
                }
                // 窗口：只填 [servedPos, servedPos + readahead]；切走（后台）时窗口冻结在这一刻
                if (from > servedPos + readaheadBytes()) {
                    delay(60)
                    continue
                }
                runCatching {
                    val span = readaheadBytes()
                    val to = minOf(totalBytes - 1, from + span - 1)
                    val anchor = from
                    streamSource(from, to, null) {
                        // 已切走（降级为后台）：立刻撒手，把带宽让给新歌。
                        // 新会话此时 servedPos 还是 0，不会误伤自己。
                        (!foreground && servedPos > 0) ||
                            // 用户拖了进度条：这一段已经没意义
                            (foreground && Math.abs(servedPos - anchor) > span * 2)
                    }
                }
                delay(5)
            }
        }

        /** 从 [from] 起第一个未缓存块的起点；全都有了返回 -1 */
        private fun nextUncachedChunkStart(from: Long): Long {
            val c = chunks ?: return -1
            var i = kkboxChunkIndexOf(from, CHUNK).coerceAtLeast(0)
            while (i < c.size) {
                if (!c[i]) return i.toLong() * CHUNK
                i++
            }
            return -1
        }

        /** 位图落盘（回源结束后调一次；预读频繁时按 500ms 节流，别把 IO 打满） */
        private fun saveIndexThrottled() {
            val now = System.currentTimeMillis()
            if (now - lastIndexSaveAt < 500L) return
            lastIndexSaveAt = now
            saveIndex()
        }
        private fun saveIndex() {
            val c = chunks ?: return
            runCatching {
                val bits = ByteArray((c.size + 7) / 8)
                for (i in c.indices) {
                    if (c[i]) bits[i / 8] = (bits[i / 8].toInt() or (1 shl (i % 8))).toByte()
                }
                val head = ByteBuffer.allocate(4).putInt(c.size).array()
                idxFile.writeBytes(head + bits)
            }
        }

        /** 恢复上一次留下的部分缓存；没有可复用的返回 false */
        private fun restoreIndex(): Boolean {
            val c = chunks ?: return false
            if (!part.exists() || part.length() <= 0 || !idxFile.exists()) return false
            return runCatching {
                val bytes = idxFile.readBytes()
                if (bytes.size < 4) return@runCatching false
                val count = ByteBuffer.wrap(bytes, 0, 4).int
                if (count != c.size) return@runCatching false
                val bits = bytes.copyOfRange(4, bytes.size)
                val partLen = part.length()
                var restored = 0
                for (i in c.indices) {
                    if ((i / 8) >= bits.size) break
                    if (((bits[i / 8].toInt() shr (i % 8)) and 1) != 1) continue
                    // 块必须整块落在已写文件里才算有效（末尾块按总长算）
                    val blockEnd = minOf((i + 1).toLong() * CHUNK - 1, totalBytes - 1)
                    if (blockEnd < partLen) {
                        c[i] = true
                        restored++
                    }
                }
                synchronized(chunkLock) { cachedChunks = restored }
                restored > 0
            }.getOrDefault(false)
        }

        private fun writeHeaders(socket: Socket, code: Int, extra: String, length: Long) {
            val head = buildString {
                append("HTTP/1.1 ").append(code).append(' ')
                append(
                    when (code) {
                        206 -> "Partial Content"
                        416 -> "Range Not Satisfiable"
                        else -> "OK"
                    },
                ).append("\r\n")
                append("Content-Type: ").append(contentTypeFor()).append("\r\n")
                append("Accept-Ranges: bytes\r\n")
                append(extra)
                append("Content-Length: ").append(length).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().write(head.toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()
        }

        private fun contentTypeFor(): String = when (qualityExtension(quality.value)) {
            ".flac" -> "audio/flac"
            ".m4a" -> "audio/mp4"
            else -> "audio/mpeg"
        }

        private fun readLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
            }
        }

        fun close() {
            runCatching { saveIndex() }
            prefetchJob?.cancel()
            acceptJob?.cancel()
            runCatching { server.close() }
            runCatching { scope.cancel() }
            runCatching { channel.close() }
            runCatching { raf.close() }
        }
    }
}
