package com.annieplayer.annie_music.kt.core.audio

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionResult
import com.annieplayer.annie_music.kt.MainActivity
import com.annieplayer.annie_music.kt.core.state.PlayerBackend
import com.annieplayer.annie_music.kt.data.model.LoopMode
import com.annieplayer.annie_music.kt.data.sources.fetchKugouMusicUrl
import com.annieplayer.annie_music.kt.data.sources.fetchKuwoPlayUrl
import com.annieplayer.annie_music.kt.data.sources.fetchNeteasePlayUrl
import com.annieplayer.annie_music.kt.data.sources.fetchQQMusicUrl
import com.annieplayer.annie_music.kt.data.sources.getFileUrl
import com.annieplayer.annie_music.kt.data.sources.qobuzHiresFormatId
import com.annieplayer.annie_music.kt.data.sources.qobuzTokenProvider
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 音频引擎（1:1 移植 Flutter 版 `lib/core/audio/audio_backend.dart`（该实现已随 flutter_app 从本分支移除），
 * 对齐其 `AudioPlayerBackend`）。
 *
 * 映射总览：
 * - just_audio `AudioPlayer`            -> Media3 `ExoPlayer`
 * - `LockCachingAudioSource`           -> Media3 `CacheDataSource` + `SimpleCache`
 * - `AudioPlayerHandler`（audio_service）-> `MusicSessionService`（MediaSessionService）
 * - `_setSourceIfCurrent`（串行 + 令牌）-> **删除**：ExoPlayer 天然串行，见下方说明
 * - `positionStream` / `durationStream` -> 固定 200ms 的进度协程（ExoPlayer 无 stream）
 *
 * ## 串行化说明（对应 Dart `_setSourceChain` / `_setSourceIfCurrent`）
 * Dart 需要显式串行链，是因为多次 `setUrl` 可能乱序完成而互相覆盖音源。Media3 的
 * `ExoPlayer.setMediaSource` / `prepare` 都在应用主线程顺序执行、天然串行，因此这里
 * **不再需要** `_setSourceChain`。保留的 `_playRequestId` 仅用于保护**异步 URL 解析**
 * 期间的过期请求（解析是网络调用，仍可能乱序返回），这与 Dart 的令牌校验语义一致。
 *
 * ## 事件 key（与 `PlayerState.kt` 的 `PlayerViewModel._subscribe` 期望完全一致）
 * `absoluteIndex` (Int) / `isPlaying` (Boolean) / `position` (Double, 秒) / `duration` (Double, 秒)。
 * Dart 的事件 map 也只有这四个 key；「ended」不单独发事件，而是在内部触发自动跳下一首，
 * 与 Dart `_handleEnded` 相同。「buffered」在 Dart 里只进通知的 PlaybackState（`setPosition`
 * 的 bufferedPosition），不进事件流；Media3 由 MediaSession 自动同步缓冲进度。
 */
@androidx.annotation.OptIn(UnstableApi::class)
class MediaPlayerBackend(
    private val appContext: Context,
) : PlayerBackend {

    private val _context: Context = appContext.applicationContext

    /**
     * 引擎协程域。
     *
     * 必须带 [CoroutineExceptionHandler]：否则 `launch` 里漏网的异常会走默认未捕获处理器
     * **直接崩掉进程**（`SupervisorJob` 只隔离兄弟任务，并不吞异常）。
     */
    private val _scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> Log.e(_tag, "[audio] uncaught in engine scope", e) },
    )

    private val _events = MutableSharedFlow<Map<String, Any?>>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 引擎事件流（keyed map，key 见类 KDoc） */
    override val events: Flow<Map<String, Any?>> = _events.asSharedFlow()

    // ── 引擎对象（全部可空，不用 lateinit / 不用强制解包） ──

    private var _player: ExoPlayer? = null
    private var _session: MediaSession? = null
    private var _cache: Cache? = null
    private var _cachedSourceFactory: DefaultMediaSourceFactory? = null
    private var _plainSourceFactory: DefaultMediaSourceFactory? = null
    private var _audioOutput: AudioOutputController? = null
    private var _progressJob: Job? = null
    private var _serviceStarted: Boolean = false

    // ── 应用层队列状态（对齐 Dart 字段） ──

    private var _queue: List<Map<String, String>> = emptyList()
    private val _urlCache: MutableMap<Int, String> = mutableMapOf()
    private val _pendingPrefetch: MutableSet<Int> = mutableSetOf()
    private var _appLoopMode: LoopMode = LoopMode.sequential
    private var _preloadBaseIndex: Int = 0
    private var _currentAbsIndex: Int = 0
    private var _generation: Int = 0
    private var _playRequestId: Int = 0
    private var _consecutiveFailures: Int = 0

    /** 当前这次 `_playAbsIndex` 是否已做过一次「清缓存 / 强刷解析」重试（对齐 Dart catch 分支） */
    private var _retriedCurrent: Boolean = false

    /**
     * 本实例是否仍是「当前活跃引擎」。Activity 重建时，新实例会接管进程内既有 ExoPlayer；
     * 此时旧实例被置为 false，其监听/协程全部短路，避免两个 backend 争抢同一个 player。
     */
    @Volatile
    private var _active: Boolean = true

    // ── 生命周期 ──

    /**
     * 初始化引擎（对齐 Dart `setup()`）：
     * 1. 建缓存 + 建 ExoPlayer + 监听
     * 2. 建 MediaSession（通知 / 锁屏 / 线控）并注册进进程级 holder 供服务使用
     * 3. 建采样率/位深直通控制器（bit-perfect groundwork）
     * 4. 启动进度事件循环、推送初始 isPlaying=false
     * 5. Android 13+ 通知权限检查（运行时申请归 Activity，见 [_requestNotificationPermission]）
     */
    override suspend fun setup() {
        withContext(Dispatchers.Main.immediate) {
            if (_player != null) return@withContext

            // ⓪ 进程级复用：Activity 重建（旋转 / 深色模式 / 分屏 / 从通知栏返回被回收的界面）
            //    会让本方法在**同一进程**里被再次调用。`SimpleCache` 对同一目录在进程内只允许一个
            //    实例（第二个实例直接抛 IllegalStateException 崩溃），且旧 ExoPlayer 可能仍在播放。
            //    因此这里直接接管既有引擎，避免「二次 SimpleCache 崩溃」与「双引擎 / 控制失效」。
            val existingPlayer = AnnieAudioHolder.player
            val existingSession = AnnieAudioHolder.session
            if (existingPlayer != null && existingSession != null) {
                adoptEngine(existingPlayer, existingSession)
                return@withContext
            }

            try {

            // 1) 磁盘缓存：进程级单例（等价 Dart `getTemporaryDirectory`/audio_cache，交给 Media3 自管）
            val cache = sharedCache(_context)
            _cache = cache

            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(_userAgent)
                // 酷狗直链是 http 且可能 302：允许跨协议重定向（CacheDataSource 也原生处理 302）
                .setAllowCrossProtocolRedirects(true)
            val upstream: DataSource.Factory = DefaultDataSource.Factory(_context, httpFactory)
            val cacheFactory = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            _cachedSourceFactory = DefaultMediaSourceFactory(cacheFactory)
            _plainSourceFactory = DefaultMediaSourceFactory(upstream)

            // 2) ExoPlayer（音频焦点 + 拔耳机暂停，等价 just_audio + audio_session 行为）
            val player = ExoPlayer.Builder(_context)
                .setMediaSourceFactory(_cachedSourceFactory ?: DefaultMediaSourceFactory(cacheFactory))
                .setAudioAttributes(_mediaAudioAttributes, true)
                .setHandleAudioBecomingNoisy(true)
                // 熄屏后继续串流：必须显式拿 wake lock（WAKE_LOCK 权限已在 Manifest 声明）。
                // 不设置时屏幕熄灭后 CPU 可休眠 → 后台自动切歌要做的「下一首解析 URL + 拉流」
                // 会偶发失败（现象：歌停在上一首结束处不切下一首），前台手动点一下又正常。
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build()
            player.addListener(AnniePlayerListener())
            _player = player

            // 3) MediaSession：服务 / 通知 / 锁屏 / 耳机按钮的宿主
            val sessionActivity = PendingIntent.getActivity(
                _context,
                0,
                Intent(_context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            // 会话拿到的是「补声明了上一首/下一首」的包装播放器：通知栏 / 锁屏 / 蓝牙
            // 才会显示切歌键；应用内其余逻辑仍使用真实 player（应用侧只用 _player）。
            val session = MediaSession.Builder(_context, CommandAdvertisedPlayer(player))
                .setSessionActivity(sessionActivity)
                .setCallback(AnnieSessionCallback())
                .build()
            _session = session
            AnnieAudioHolder.player = player
            AnnieAudioHolder.session = session
            AnnieAudioHolder.cache = cache
            AnnieAudioHolder.cachedSourceFactory = _cachedSourceFactory
            AnnieAudioHolder.plainSourceFactory = _plainSourceFactory
            // 服务实例可能被系统回收（例如划掉任务且未在播放时 stopSelf）；一旦销毁需复位
            // _serviceStarted，否则后续播放不会再拉起前台服务/媒体通知。
            AnnieAudioHolder.onServiceStopped = { _serviceStarted = false }
            // 供后续接管的新实例把本实例「下线」，避免两个 backend 争抢同一个 player
            AnnieAudioHolder.onDeactivate = { deactivate() }

            // 4) bit-perfect groundwork
            _audioOutput = AudioOutputController(_context)

            // 5) 进度事件循环 + 初始状态
            _startProgressLoop()
            _emit(mapOf("isPlaying" to false))

            // 6) Android 13+ POST_NOTIFICATIONS
            _requestNotificationPermission()

            } catch (t: Throwable) {
                // 兜底：setup 阶段任何异常都不应让 App 闪退；记日志后由后续操作重试
                Log.e(_tag, "[audio] setup failed", t)
            }
        }
    }

    /**
     * 接管进程内既有引擎（Activity 重建场景）：复用同一个 ExoPlayer / MediaSession / 磁盘缓存，
     * 并把上一实例「下线」。这样既不二次创建 `SimpleCache`（会抛异常崩溃），也不产生双引擎。
     */
    private fun adoptEngine(player: ExoPlayer, session: MediaSession) {
        Log.w(_tag, "[audio] adopt existing engine (activity recreated)")
        // 先让旧实例停止其回调与进度协程
        AnnieAudioHolder.onDeactivate?.invoke()

        _player = player
        _session = session
        _cache = AnnieAudioHolder.cache ?: sharedCache(_context)

        // 复用旧实例的数据源工厂；若缺失（理论上不会）则以共享缓存重建
        if (AnnieAudioHolder.cachedSourceFactory == null) {
            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(_userAgent)
                .setAllowCrossProtocolRedirects(true)
            val upstream: DataSource.Factory = DefaultDataSource.Factory(_context, httpFactory)
            AnnieAudioHolder.cachedSourceFactory = DefaultMediaSourceFactory(
                CacheDataSource.Factory()
                    .setCache(_cache ?: sharedCache(_context))
                    .setUpstreamDataSourceFactory(upstream)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR),
            )
            AnnieAudioHolder.plainSourceFactory = DefaultMediaSourceFactory(upstream)
        }
        _cachedSourceFactory = AnnieAudioHolder.cachedSourceFactory
        _plainSourceFactory = AnnieAudioHolder.plainSourceFactory

        player.addListener(AnniePlayerListener())
        AnnieAudioHolder.onServiceStopped = { _serviceStarted = false }
        AnnieAudioHolder.onDeactivate = { deactivate() }
        _startProgressLoop()
        _emit(mapOf("isPlaying" to player.isPlaying))
    }

    /** 把本实例下线：停掉进度协程并短路所有回调（player/session 归新实例所有，不在此释放）。 */
    private fun deactivate() {
        _active = false
        _progressJob?.cancel()
        _player = null
        _session = null
    }

    /**
     * 对齐 Dart `_requestNotificationPermission`：Dart 通过 `annie_music/media_store` 通道
     * 让原生 Activity 弹权限框。纯 Kotlin 引擎层没有 Activity，运行时申请由 MainActivity 完成；
     * 这里只做状态检查并记录（不阻塞前台播放）。
     */
    private fun _requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            _context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.i(_tag, "[audio] POST_NOTIFICATIONS 未授予：应由 MainActivity 发起运行时申请")
        }
    }

    /** 固定节拍的进度/时长推送（ExoPlayer 没有 positionStream / durationStream） */
    private fun _startProgressLoop() {
        _progressJob?.cancel()
        _progressJob = _scope.launch {
            var lastPositionMs = -1L
            var lastDurationMs = -1L
            while (isActive) {
                val p = _player
                if (p != null) {
                    val pos = p.currentPosition
                    if (p.isPlaying || pos != lastPositionMs) {
                        lastPositionMs = pos
                        _emit(mapOf("position" to pos / 1000.0))
                    }
                    val dur = p.duration
                    if (dur != C.TIME_UNSET && dur > 0 && dur != lastDurationMs) {
                        lastDurationMs = dur
                        _emit(mapOf("duration" to dur / 1000.0))
                    }
                }
                delay(_progressIntervalMs)
            }
        }
    }

    private fun _emit(event: Map<String, Any?>) {
        _events.tryEmit(event)
    }

    // ── PlayerBackend 实现 ──

    /**
     * 设置队列并从 [startIndex] 开播（对齐 Dart `setQueue`）。
     *
     * 注意：这里必须把 `loopMode` 同步落到播放器（见 [_applyRepeatMode]）——否则冷启动后
     * `_appLoopMode` 已是 single 而 `player.repeatMode` 仍是 OFF，单曲循环会「播完即停」。
     */
    override suspend fun setQueue(items: List<Map<String, String>>, startIndex: Int, loopMode: String) {
        withContext(Dispatchers.Main.immediate) {
            _generation++
            _playRequestId++
            _queue = items.toList()
            _urlCache.clear()
            _pendingPrefetch.clear()
            _consecutiveFailures = 0
            _appLoopMode = LoopMode.fromName(loopMode)
            _applyRepeatMode()
            if (_queue.isEmpty()) return@withContext
            val safeIndex = if (startIndex in _queue.indices) startIndex else 0
            _playAbsIndex(safeIndex)
        }
    }

    override suspend fun skipNext() {
        withContext(Dispatchers.Main.immediate) {
            if (_queue.isEmpty()) return@withContext
            val cur = _currentAbsIndex
            val next = if (_appLoopMode == LoopMode.shuffle) {
                (System.currentTimeMillis() % _queue.size).toInt()
            } else {
                (cur + 1) % _queue.size
            }
            _playAbsIndex(next)
        }
    }

    override suspend fun skipPrevious() {
        withContext(Dispatchers.Main.immediate) {
            if (_queue.isEmpty()) return@withContext
            val cur = _currentAbsIndex
            val prev = if (_appLoopMode == LoopMode.shuffle) {
                (System.currentTimeMillis() % _queue.size).toInt()
            } else {
                if (cur <= 0) _queue.size - 1 else cur - 1
            }
            _playAbsIndex(prev)
        }
    }

    override suspend fun skipTo(index: Int) {
        withContext(Dispatchers.Main.immediate) {
            _playAbsIndex(index)
        }
    }

    /** 设置 App 层循环模式；单曲 -> `REPEAT_MODE_ONE`，其余 -> `REPEAT_MODE_OFF`（对齐 Dart） */
    override suspend fun setAppLoopMode(loopMode: String) {
        withContext(Dispatchers.Main.immediate) {
            _appLoopMode = LoopMode.fromName(loopMode)
            _applyRepeatMode()
        }
    }

    /** 把 `_appLoopMode` 落到播放器（单曲 -> ONE，其余 -> OFF）；[setQueue] 与 [setAppLoopMode] 共用 */
    private fun _applyRepeatMode() {
        _player?.repeatMode = if (_appLoopMode == LoopMode.single) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_OFF
        }
    }

    override suspend fun getQueueIndex(): Int = _currentAbsIndex

    override suspend fun play() {
        withContext(Dispatchers.Main.immediate) {
            val p = _player ?: return@withContext
            if (p.mediaItemCount == 0) return@withContext
            // 播放器可能已退到 IDLE（服务被系统回收时会话被释放 → 底层 stop）：此时 play() 是
            // 空操作，表现为「暂停后切后台、回来点播放不播」，要点第二次或切歌（setQueue 里有
            // prepare）才恢复。这里兜底重新 prepare，保证一次点击就能续播。
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.play()
            _ensureServiceStarted()
        }
    }

    override suspend fun pause() {
        withContext(Dispatchers.Main.immediate) {
            _player?.pause()
        }
    }

    override suspend fun seekTo(seconds: Double) {
        withContext(Dispatchers.Main.immediate) {
            _player?.seekTo((seconds * 1000.0).roundToLong())
        }
    }

    /**
     * 音量（对齐 Dart `setVolume`）。
     *
     * 注意（见 `KOTLIN_PLAYBACK_PLAN.md` §5.3）：ExoPlayer 的 `player.volume` 是**软件音量**，
     * 调它就不是 bit-perfect。当前按 Dart 1:1 保留；bit-perfect 模式下应改走系统/DAC 硬件音量。
     */
    override suspend fun setVolume(volume: Double) {
        withContext(Dispatchers.Main.immediate) {
            _player?.volume = volume.toFloat()
        }
    }

    /**
     * 清理播放缓存（「清除缓存」弹窗里的「播放缓存」项）。
     *
     * 用 Media3 的 [Cache] API 逐项 `removeResource`，**不直接删目录** —— 缓存目录正被
     * `SimpleCache`（含其索引库）占用，直接 `deleteRecursively` 会让索引与文件不一致。
     */
    override suspend fun clearPlaybackCache(): Long = withContext(Dispatchers.IO) {
        val cache = _cache ?: return@withContext 0L
        var freed = 0L
        try {
            for (key in cache.keys.toList()) {
                freed += cache.getCachedBytes(key, 0L, Long.MAX_VALUE)
                cache.removeResource(key)
            }
        } catch (_: Throwable) {
            // 单个 key 失败不影响整体清理
        }
        freed
    }

    // ── 核心：解析 + 设置音源 + 自动前进 ──

    /**
     * 播放绝对下标 [index]（对齐 Dart `_playAbsIndex`）。
     *
     * 步骤与 Dart 一一对应：立即暂停旧曲 -> 解析 URL（先缓存后网络）-> 设置音源（失败清缓存重试一次）
     * -> 清零失败计数、发 absoluteIndex、自动 play、预取后续。
     */
    private suspend fun _playAbsIndex(index: Int) {
        val queue = _queue
        if (queue.isEmpty()) return
        if (index < 0 || index >= queue.size) return

        val requestId = ++_playRequestId
        _preloadBaseIndex = index
        _currentAbsIndex = index
        _retriedCurrent = false
        val item = queue[index]

        // 立即停掉上一首：解析直链可能耗时，否则旧歌会继续响（对齐 Dart unawaited(_player.pause())）
        _player?.pause()

        val url = _resolveUrl(index, item, false)
        if (requestId != _playRequestId) return
        if (url == null) {
            _autoAdvanceOnFailure(index)
            return
        }
        // 解析期间用户可能已经切歌 -> 放弃本次设置，避免旧请求覆盖新音源
        if (requestId != _playRequestId) return

        _urlCache[index] = url
        if (!_setSource(url, item)) {
            _handlePlaybackFailure(index)
            return
        }
        if (requestId != _playRequestId) return

        _consecutiveFailures = 0
        _emit(mapOf("absoluteIndex" to index))
        _player?.play()
        _ensureServiceStarted()
        _startPrefetchUpcoming()
    }

    /**
     * 命中缓存或解析 [index] 的播放直链；失败返回 null（不抛异常，对齐 Dart `_resolveUrl`）。
     * 预取入口 [fetchPreloadUrl] 的 Kotlin 移植见 [_fetchPreloadUrl]。
     */
    private suspend fun _resolveUrl(index: Int, item: Map<String, String>, forceRefresh: Boolean): String? {
        if (!forceRefresh) {
            val cached = _urlCache[index]
            if (!cached.isNullOrEmpty()) return cached
        }
        return try {
            val url = _fetchPreloadUrl(item)
            if (url.isNullOrEmpty()) {
                Log.w(_tag, "[audio] resolve empty (${item["platform"]} ${item["id"]})")
                null
            } else {
                url
            }
        } catch (e: Throwable) {
            Log.w(_tag, "[audio] resolve failed (${item["platform"]} ${item["id"]}): ${e.message}")
            null
        }
    }

    /**
     * 预取 URL（1:1 移植 Dart `fetchPreloadUrl`）。
     *
     * - 队列项自带 url（qobuz 详情页 / 已解析）优先
     * - kkbox：无 url 时返回 null（与 Dart 相同）
     * - qobuz：无 url 时用活跃 token 取直链（搜索 / 跳转进来的歌靠这条）
     * - netease / qq / kugou / kuwo：委托既有 `data/sources/` 的实现，qq/kugou 固定 320k（同 Dart）
     */
    private suspend fun _fetchPreloadUrl(item: Map<String, String>): String? {
        val provided = item["url"]
        if (!provided.isNullOrEmpty()) return provided
        val id = item["id"].orEmpty()
        return when (item["platform"]) {
            "qobuz" -> {
                val token = qobuzTokenProvider().orEmpty()
                if (token.isEmpty()) null else {
                    getFileUrl(
                        id.removePrefix("qobuz:"),
                        token,
                        qualityId = qobuzHiresFormatId,
                    ).url
                }
            }
            "kkbox" -> null
            "netease" -> fetchNeteasePlayUrl(id)
            "qq" -> fetchQQMusicUrl(id.replaceFirst(_reQqPrefix, ""), _prefetchQuality)
            "kugou" -> fetchKugouMusicUrl(id.replaceFirst(_reKugouPrefix, ""), _prefetchQuality)
            // 默认（kuwo / 未知）
            else -> fetchKuwoPlayUrl(id)
        }
    }

    /**
     * 设置播放源（对齐 Dart `_setSource`）。返回是否成功（进程内同步错误如非法 URI 返回 false；
     * 网络/解码错误异步到达 [AnniePlayerListener.onPlayerError]）。
     *
     * 磁盘缓存规则（同 Dart `_cacheFileFor`）：酷狗直链**不走**边播边缓存，
     * 因此用不带缓存的 `_plainSourceFactory`；其余平台走 CacheDataSource + SimpleCache。
     */
    private fun _setSource(url: String, item: Map<String, String>): Boolean {
        val p = _player ?: return false
        return try {
            val isKugou = item["platform"] == "kugou"
            val factory = if (isKugou) _plainSourceFactory else _cachedSourceFactory
            val source: MediaSource = (factory ?: return false).createMediaSource(_buildMediaItem(url, item))
            p.setMediaSource(source)
            p.prepare()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** 构造 MediaItem：把队列项 metadata 带入，供通知/锁屏展示（对齐 Dart handler.setMediaItem 的兜底文案） */
    private fun _buildMediaItem(url: String, item: Map<String, String>): MediaItem {
        val title = item["title"].orEmpty().ifEmpty { "未知歌曲" }
        val artist = item["artist"].orEmpty().ifEmpty { "未知歌手" }
        val album = item["album"].orEmpty().ifEmpty { null }
        val cover = item["cover"].orEmpty().ifEmpty { null }
        val builder = MediaMetadata.Builder().setTitle(title).setArtist(artist)
        if (album != null) builder.setAlbumTitle(album)
        if (cover != null) {
            val artUri = Uri.parse(cover)
            builder.setArtworkUri(artUri)
        }
        return MediaItem.Builder()
            .setUri(url)
            .setMediaId(item["id"].orEmpty())
            .setMediaMetadata(builder.build())
            .build()
    }

    /**
     * 播放失败处理（对齐 Dart `_playAbsIndex` 的 catch + 重试分支）：
     * 首次失败清缓存并强制重解析一次；仍失败（或已重试过）则自动跳下一首。
     *
     * Media3 的网络/解码失败是异步 `onPlayerError`，Dart 的 `setSource` 同步抛异常；
     * 两条路径都汇入这里，语义与 Dart「失败重试一次」一致。
     */
    private suspend fun _handlePlaybackFailure(index: Int) {
        if (!_active) return
        if (index != _currentAbsIndex) return
        val item = _queue.getOrNull(index)
        if (item == null) {
            _autoAdvanceOnFailure(index)
            return
        }
        if (_retriedCurrent) {
            _autoAdvanceOnFailure(index)
            return
        }
        _retriedCurrent = true

        val requestId = _playRequestId
        _urlCache.remove(index)
        val url = _resolveUrl(index, item, true)
        if (requestId != _playRequestId) return
        if (url == null) {
            _autoAdvanceOnFailure(index)
            return
        }
        _urlCache[index] = url
        if (!_setSource(url, item)) {
            _urlCache.remove(index)
            _autoAdvanceOnFailure(index)
            return
        }
        if (requestId != _playRequestId) return

        _consecutiveFailures = 0
        _emit(mapOf("absoluteIndex" to index))
        _player?.play()
        _ensureServiceStarted()
        _startPrefetchUpcoming()
    }

    /** 当前曲目播放失败 -> 自动切下一首；连续失败达上限则暂停（对齐 Dart `_autoAdvanceOnFailure`） */
    private suspend fun _autoAdvanceOnFailure(failedIndex: Int) {
        if (!_active) return
        _consecutiveFailures++
        if (_consecutiveFailures >= _maxConsecutiveFailures || _queue.size <= 1) {
            _player?.pause()
            _emit(mapOf("isPlaying" to false))
            return
        }
        val next = (failedIndex + 1) % _queue.size
        _playAbsIndex(next)
    }

    /** 播放自然结束（对齐 Dart `_handleEnded`）；单曲循环由 `REPEAT_MODE_ONE` 处理不会走到这里 */
    private suspend fun _handleEnded() {
        if (!_active) return
        if (_queue.size <= 1) return
        if (_appLoopMode == LoopMode.single) return
        val cur = _currentAbsIndex
        val next: Int = if (_appLoopMode == LoopMode.shuffle) {
            val available = _queue.indices.filter { it != cur }
            if (available.isEmpty()) return
            available[(System.currentTimeMillis() % available.size).toInt()]
        } else {
            (cur + 1) % _queue.size
        }
        _playAbsIndex(next)
    }

    /**
     * 后台预取当前曲目之后 N 首（对齐 Dart `_prefetchUpcoming`：代际校验 + 并发上限 + 去重）。
     *
     * 与 Dart 的 `unawaited(_prefetchUpcoming())` 一致：targets 与代际在调用点**同步**快照，
     * 真正抓取在 [_scope] 上并发进行（网络调用内部挂起，不阻塞主线程）。
     */
    private fun _startPrefetchUpcoming() {
        if (!_active) return
        val gen = _generation
        val count = _queue.size
        if (count <= 1) return

        val targets = mutableListOf<Int>()
        var step = 1
        while (step <= _prefetchAhead && step < count) {
            targets.add((_currentAbsIndex + step) % count)
            step++
        }

        val cursor = AtomicInteger(0)
        repeat(_prefetchConcurrency) {
            _scope.launch {
                while (true) {
                    val i = cursor.getAndIncrement()
                    if (i >= targets.size) return@launch
                    val idx = targets[i]
                    if (gen != _generation) return@launch
                    if (_urlCache.containsKey(idx) || _pendingPrefetch.contains(idx)) continue
                    _pendingPrefetch.add(idx)
                    try {
                        val item = _queue.getOrNull(idx) ?: continue
                        val url = _fetchPreloadUrl(item)
                        if (gen == _generation && !url.isNullOrEmpty()) {
                            _urlCache[idx] = url
                        }
                    } catch (_: Throwable) {
                        // 预取失败静默忽略，切歌时会再次尝试解析
                    } finally {
                        _pendingPrefetch.remove(idx)
                    }
                }
            }
        }
    }

    /**
     * 启动前台服务（`MusicSessionService`）：Media3 在服务内看到 player 正在播放时会自动
     * 进入前台并展示媒体通知。仅在已有媒体项时调用，避免 startForegroundService 的 5s 超时。
     */
    private fun _ensureServiceStarted() {
        val p = _player
        if (p == null || p.mediaItemCount == 0) return
        if (_serviceStarted) return
        _serviceStarted = true
        try {
            ContextCompat.startForegroundService(
                _context,
                Intent(_context, MusicSessionService::class.java),
            )
        } catch (_: Throwable) {
            _serviceStarted = false
        }
    }

    /**
     * MediaSession 回调：把通知栏/锁屏/线控的「上一首/下一首」转发到应用级切歌。
     *
     * 引擎队列每次只有一个 MediaItem，Media3 默认的 `seekToNext/PreviousMediaItem` 会因没有
     * 下一/上一项而 no-op，导致通知栏按钮失效。这里拦截对应命令调用 [skipNext]/[skipPrevious]
     * （对齐 Flutter `AudioPlayerHandler.skipToNext/skipToPrevious → onNext/onPrevious`）。
     * 由于队列仅单个 MediaItem，返回 RESULT_SUCCESS 后即便命令再转发给 player 也无副作用。
     */
    private inner class AnnieSessionCallback : MediaSession.Callback {
        @androidx.annotation.OptIn(UnstableApi::class)
        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            @Player.Command playerCommand: Int,
        ): Int = when (playerCommand) {
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                _scope.launch { skipNext() }
                SessionResult.RESULT_SUCCESS
            }
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                _scope.launch { skipPrevious() }
                SessionResult.RESULT_SUCCESS
            }
            else -> super.onPlayerCommandRequest(session, controller, playerCommand)
        }
    }

    /**
     * 让「上一首 / 下一首」在 Media3 眼里**可用**，否则通知栏 / 锁屏 / 蓝牙不会画出切歌键。
     *
     * 本引擎每次只装 1 个 MediaItem（每首歌的直链都要按需解析），ExoPlayer 因此把
     * `COMMAND_SEEK_TO_NEXT(_MEDIA_ITEM)` / `..PREVIOUS..` 从可用命令里剔掉 →
     * `DefaultMediaNotificationProvider` 判定「没有上一首/下一首」→ 媒体通知里没有切歌按钮。
     *
     * 这里只覆盖 [getAvailableCommands]（其余全部转发给真实 player，应用侧逻辑用的仍是原
     * ExoPlayer 实例），把这两条命令补回可用集合；真正的切歌仍由
     * [AnnieSessionCallback.onPlayerCommandRequest] 转发到 skipNext / skipPrevious。
     */
    private inner class CommandAdvertisedPlayer(player: ExoPlayer) : ForwardingPlayer(player) {
        private val seekCommands: Player.Commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            )
            .build()

        override fun getAvailableCommands(): Player.Commands {
            val base = super.getAvailableCommands()
            // 队列只有一首时不要凭空声明切歌能力，避免通知栏出现「点了没反应」的按钮
            if (_queue.size <= 1) return base
            return base.buildUpon().addAll(seekCommands).build()
        }
    }

    /** ExoPlayer 监听：isPlaying / ended / error（对齐 Dart 的 playerStateStream 与 processingStateStream） */
    private inner class AnniePlayerListener : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _emit(mapOf("isPlaying" to isPlaying))
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (!_active) return
            if (playbackState == Player.STATE_ENDED) {
                _scope.launch { _handleEnded() }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!_active) return
            val idx = _currentAbsIndex
            Log.w(_tag, "[audio] onPlayerError: ${error.errorCodeName} ${error.message}")
            _scope.launch { _handlePlaybackFailure(idx) }
        }
    }

    private companion object {
        const val _tag: String = "AnnieAudio"
        const val _progressIntervalMs: Long = 200L
        const val _cacheDirName: String = PLAYBACK_CACHE_DIR_NAME
        const val _cacheMaxBytes: Long = 256L * 1024L * 1024L

        /** 进程级唯一的磁盘缓存：`SimpleCache` 对同一目录只允许一个实例，重复创建会抛异常崩溃 */
        @Volatile
        private var _sharedCache: Cache? = null
        private val _sharedCacheLock = Any()

        fun sharedCache(context: Context): Cache {
            _sharedCache?.let { return it }
            synchronized(_sharedCacheLock) {
                _sharedCache?.let { return it }
                val dir = File(context.cacheDir, _cacheDirName)
                if (!dir.exists()) dir.mkdirs()
                val created = SimpleCache(dir, LeastRecentlyUsedCacheEvictor(_cacheMaxBytes))
                _sharedCache = created
                return created
            }
        }
        const val _userAgent: String = "AnnieMusic/1.0 (Android)"
        const val _prefetchQuality: String = "320k"
        const val _prefetchAhead: Int = 5
        const val _prefetchConcurrency: Int = 3
        const val _maxConsecutiveFailures: Int = 3
        val _reQqPrefix: Regex by lazy { Regex("^qq:") }
        val _reKugouPrefix: Regex by lazy { Regex("^kugou:") }
    }
}

/** 进程级共享持有者：`MediaPlayerBackend` 创建，`MusicSessionService` 读取以对外暴露 MediaSession */
@androidx.annotation.OptIn(UnstableApi::class)
internal object AnnieAudioHolder {
    @Volatile
    var player: ExoPlayer? = null

    @Volatile
    var session: MediaSession? = null

    /** 进程级共享磁盘缓存与数据源工厂（供 Activity 重建时新实例接管复用） */
    @Volatile
    var cache: Cache? = null

    @Volatile
    var cachedSourceFactory: DefaultMediaSourceFactory? = null

    @Volatile
    var plainSourceFactory: DefaultMediaSourceFactory? = null

    /** 服务 `onDestroy` 时的回调，用于让 backend 复位 `_serviceStarted`（否则后续播放不再起前台服务） */
    @Volatile
    var onServiceStopped: (() -> Unit)? = null

    /** 新实例接管引擎时调用，把上一实例「下线」（短路其回调/进度协程，避免争抢 player） */
    @Volatile
    var onDeactivate: (() -> Unit)? = null
}

/** 播放缓存目录名（Media3 `SimpleCache`）；`AssetCache` 统计/清理时复用同一常量，避免写错。 */
const val PLAYBACK_CACHE_DIR_NAME: String = "annie_audio_cache"

/** 媒体音频用途（ExoPlayer 音频焦点 / 直通属性；与 AudioOutputController 的 android.media 属性一致） */
private val _mediaAudioAttributes: AudioAttributes = AudioAttributes.Builder()
    .setUsage(C.USAGE_MEDIA)
    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
    .build()
