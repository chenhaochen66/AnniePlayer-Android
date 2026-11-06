package com.annieplayer.annie_music.kt.core.audio.apple

import com.annieplayer.annie_music.kt.data.fetchLyric
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.sources.apple.wrapper.AppleWrapperApi
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 批量下载时的**前置预备队列**：抢在下载前面把清单类请求打掉，让它们藏在上一首的下载窗口里。
 *
 * ## 为什么是串行、而且是单条协程
 * 实测（本机 wrapper-lite）：
 * - `/m3u8` 2.0s、`/key` 4.5s，**同一时刻只能有一个**：两路并发会排队（`/m3u8`∥`/key`
 *   互相阻塞，`/key`∥`/key` 也是 4.5→8.4→12.5s 递增）
 * - 只有 `/lyrics`（不走 Apple session）能和它们并行
 *
 * 所以这里**不并发**：并发只会把请求排成一队，既不会更快，还会让「谁先完成」变得不可预测。
 * 一条协程按顺序跑，正好卡在 session 的串行节奏上。
 *
 * ## 为什么只领先 [LOOKAHEAD] 首
 * 领太多没有收益（session 串行，跑不完就是跑不完），反而会在用户取消批量后继续烧 8 秒/首。
 *
 * ## 和下载侧的关系
 * 两边共用 [AppleManifest] 的缓存键，所以预热过的歌轮到下载时，`masterText` / `mediaText`
 * 直接命中，只剩一次 `/m3u8`（masterUrl，由 `AppleWrapperApi` 自己缓存 30min）和
 * 一次 `/key` 需要串行等待 —— 也就是说**每首歌省掉的是「重复解析 + CDN 往返 + 排队」**，
 * session 那两次请求本身省不掉。
 */
internal object ApplePrefetch {

    /** 领先当前下载的歌曲数。 */
    private const val LOOKAHEAD = 2

    /**
     * 每条预热的起跑让位。
     *
     * apple session 是串行的，而 `ahead()` 是在**当前这首歌开始下载之前**调用的 ——
     * 如果预热立刻发请求，当前这首歌自己的 `/m3u8` 会排在预热后面，白白晚 2 秒。
     * 让 1.5s 出去，当前这首歌的 `/m3u8` 先拿到 session；等它进到分片下载（20~40s）时，
     * 预热再占用 session，正好完全重叠。
     */
    private const val START_GRACE_MS = 1500L

    private data class Request(val key: String, val song: Song, val tier: AppleTier)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 还没被消费的请求（同时也是去重表）。 */
    private val queued = ConcurrentHashMap.newKeySet<String>()

    /**
     * `Channel.UNLIMITED` + 单消费者：投递永远不阻塞 UI 协程。
     *
     * 用 Channel 而不是序列化一个 List，是因为批量可以被取消：取消时只要 [drain] 把没跑的
     * 请求抽干，正在跑的那一首随它跑完（Apple session 中途不能打断，硬停只会留下半截状态）。
     */
    private val queue = Channel<Request>(Channel.UNLIMITED)

    private var pump: Job? = null

    /**
     * 批量下载循环每首歌调一次：把 [upcoming]（后面还没下到的歌）里的前 [LOOKAHEAD] 首
     * 苹果歌排进预热队列。
     *
     * @param quality 用户在批量里选的档位。档位缺失时预热与下载会走同一条降级链
     *   （[resolveAppleVariantWithFallback]），落到同一个 variant，所以仍然命中。
     */
    fun ahead(upcoming: List<Song>, quality: Quality) {
        val tier = quality.toAppleTier() ?: return
        var taken = 0
        for (song in upcoming) {
            if (taken >= LOOKAHEAD) break
            if (song.platform != MusicPlatform.apple) continue
            val key = song.id + "|" + tier.name
            if (!queued.add(key)) continue
            if (queue.trySend(Request(key, song, tier)).isSuccess) taken++
        }
        ensurePump()
    }

    /** 批量结束 / 取消：丢掉所有还没开始的预热。 */
    fun drain() {
        while (true) {
            val req = queue.tryReceive().getOrNull() ?: break
            queued.remove(req.key)
        }
    }

    private fun ensurePump() {
        if (pump?.isActive == true) return
        synchronized(this) {
            if (pump?.isActive == true) return
            pump = scope.launch {
                for (req in queue) {
                    try {
                        delay(START_GRACE_MS)
                        warm(req)
                    } catch (_: Throwable) {
                        // 预热失败不影响下载：真正下载时还会按同一条链重试
                    } finally {
                        queued.remove(req.key)
                    }
                }
            }
        }
    }

    /** 把一首歌的清单类请求全部打掉（全部落在 [AppleManifest] / CacheService 里）。 */
    private suspend fun warm(req: Request) {
        val song = req.song
        val masterUrl = AppleWrapperApi.fetchMasterUrl(song.id) ?: return
        val masterText = AppleManifest.masterText(song.id, masterUrl)
        val variant = resolveAppleVariantWithFallback(
            parseAppleMaster(masterUrl, masterText),
            req.tier,
        ) ?: return
        val mediaText = AppleManifest.mediaText(song.id, variant)
        val media = parseAppleMediaPlaylist(variant.uri, mediaText)
        if (media.segments.isEmpty()) return

        // key 模板：固定 key（P000000000）走包内 asset 不联网，真 key 一次约 4.5s —— 这正是要提前打的
        for (keyUri in media.segments.indices.map { media.keyForSegment(it) }.distinct()) {
            AppleWrapperApi.fetchKeyTemplate(song.id, keyUri)
        }

        // 歌词也顺手预热：`/lyrics` 实测 4.0s，但它**不占用 Apple session**（能跟 /key 并行），
        // 所以放在最后跑、失败无所谓（下载时还会再取一次，且有 60 分钟缓存）。
        runCatching { fetchLyric(song) }
    }
}
