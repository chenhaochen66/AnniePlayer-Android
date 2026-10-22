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
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionResult
import com.annieplayer.annie_music.kt.MainActivity
import com.annieplayer.annie_music.kt.core.audio.AudioOutputMode
import com.annieplayer.annie_music.kt.core.audio.AudioOutputStatus
import com.annieplayer.annie_music.kt.core.audio.AudioRoute
import com.annieplayer.annie_music.kt.core.audio.TuningConfig
import com.annieplayer.annie_music.kt.core.state.MoreSettings
import com.annieplayer.annie_music.kt.core.state.PlayerBackend
import com.annieplayer.annie_music.kt.core.audio.apple.ApplePlaybackServer
import com.annieplayer.annie_music.kt.data.model.LoopMode
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.fetchPlayUrlForBackend
import com.annieplayer.annie_music.kt.data.network.globalOkHttp
import com.annieplayer.annie_music.kt.data.playRequestQuality
import com.annieplayer.annie_music.kt.data.model.AudioInfo
import com.annieplayer.annie_music.kt.data.sources.getFileUrl
import com.annieplayer.annie_music.kt.data.sources.qobuzHiresFormatId
import com.annieplayer.annie_music.kt.data.sources.qobuzTokenProvider
import com.arthenica.ffmpegkit.FFprobeKit
import java.io.File
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
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
/*
     *
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
     * **不再需要*
     * `_setSourceChain`。保留的 `_playRequestId` 仅用于保护**异步 URL 解析*
     *
     * 期间的过期请求（解析是网络调用，仍可能乱序返回），这与 Dart 的令牌校验语义一致。
     *
     * ## 事件 key（与 `PlayerState.kt` 的 `PlayerViewModel._subscribe` 期望完全一致）
     * `absoluteIndex` (Int) / `isPlaying` (Boolean) / `position` (Double, 秒) / `duration` (Double, 秒)。
     * Dart 的事件 map 也只有这四个 key；「ended」不单独发事件，而是在内部触发自动跳下一首，
     * 与 Dart `_handleEnded` 相同。「buffered」在 Dart 里只进通知的 PlaybackState（`setPosition`
     * 的 bufferedPosition），不进事件流；Media3 由 MediaSession 自动同步缓冲进度。 */
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

    /** 本机 Apple HLS 失败不重试，见 [LocalFailFastPolicy] */
    private val _loadErrorPolicy: LoadErrorHandlingPolicy = LocalFailFastPolicy()
    private var _audioOutput: AudioOutputController? = null
    private var _renderersFactory: AnnieRenderersFactory? = null
    private var _progressJob: Job? = null
    private var _serviceStarted: Boolean = false
    private var _lastAudioInfo: AudioInfo? = null
    private var _lastMode: AudioOutputMode? = null
    private var _lastForceNative: Boolean? = null
    private var _lastResampleRate: Int? = null
// ── 应用层队列状态（对齐 Dart 字段） ──
    private var _queue: List<Map<String, String>> = emptyList()
// 已解析直链缓存，按「歌曲身份」（`平台:id:音质`）而非队列下标——

// 重进列表/来回切歌时不必重复解析（原按下标实现会被 setQueue 清空）
    private val _urlCache: MutableMap<String, String> = mutableMapOf()
    // 解析出的直链 -> 稳定缓存 key（`平台:id:音质`）。Media3 默认拿 URL 当 key，
    // 插件直链一变就 miss；按歌曲身份映射后，同一首歌无论直链怎么变都命中同一份缓存
    private val _cacheKeyByUrl: MutableMap<String, String> = ConcurrentHashMap()
    private val _pendingPrefetch: MutableSet<Int> = mutableSetOf()
    private var _appLoopMode: LoopMode = LoopMode.sequential

    /**
     * 随机模式下**提前定好的下一首**（-1 = 还没定）。
     *
     * 之前 `skipNext()` 和 `_handleEnded()` 各自现掷一次随机，于是：① 预取按顺序盲猜、
     * 命中率 1/(n-1)；② 界面点「下一首」时 `PlayerState` 又掷一次，可能和实际播的不是同一首。
     * 现在每次起播成功就定好下一首并存下来，预取 / 自动切 / 下一首按钮统一认它。
     */
    private var _shuffleNextIndex: Int = -1

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

            // 共享 OkHttp（连接池 + HTTP/2）：Apple 每首歌的 CDN 主机都可能不同，
            // 用 DefaultHttpDataSource 每次都要重开 TLS；走 globalOkHttp 直接复用连接。
            val httpFactory = OkHttpDataSource.Factory(globalOkHttp)
                .setUserAgent(_userAgent)
            val upstream: DataSource.Factory = DefaultDataSource.Factory(_context, httpFactory)
            val cacheFactory = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstream)
                .setCacheKeyFactory(::_cacheKeyOfSpec)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            _cachedSourceFactory = DefaultMediaSourceFactory(cacheFactory)
                .setLoadErrorHandlingPolicy(_loadErrorPolicy)
            _plainSourceFactory = DefaultMediaSourceFactory(upstream)
                .setLoadErrorHandlingPolicy(_loadErrorPolicy)
            // 2) ExoPlayer（音频焦点 + 拔耳机暂停，等价 just_audio + audio_session 行为）
            //    RenderersFactory 负责 float 输出 + 调音/重采样处理器链（方案阶段 1）
            val renderersFactory = AnnieRenderersFactory(_context)
            _renderersFactory = renderersFactory
            val player = ExoPlayer.Builder(_context)
                .setRenderersFactory(renderersFactory)
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
            AnnieAudioHolder.renderersFactory = renderersFactory
            // 服务实例可能被系统回收（例如划掉任务且未在播放时 stopSelf）；一旦销毁需复位
            // _serviceStarted，否则后续播放不会再拉起前台服务/媒体通知。
            AnnieAudioHolder.onServiceStopped = { _serviceStarted = false }
            // 供后续接管的新实例把本实例「下线」，避免两个 backend 争抢同一个 player
            AnnieAudioHolder.onDeactivate = { deactivate() }

            // 4) bit-perfect groundwork
            _audioOutput = AudioOutputController(_context)
            _audioOutput?.onDevicesChanged = {
                BluetoothCodec.refresh()
                _applyOutputPolicy(_lastAudioInfo)
                // 设备/采样率能力变了，输出格式需要重新 configure 才生效
                _reloadCurrentSource()
            }
            BluetoothCodec.onInfoChanged = { _applyOutputPolicy(_lastAudioInfo) }
            AnnieAudioHolder.audioOutput = _audioOutput
            _observeAudioSettings()
            _applyOutputPolicy(null)

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
        _renderersFactory = AnnieAudioHolder.renderersFactory
        _audioOutput = AnnieAudioHolder.audioOutput ?: AudioOutputController(_context)
        _audioOutput?.onDevicesChanged = {
            BluetoothCodec.refresh()
            _applyOutputPolicy(_lastAudioInfo)
            _reloadCurrentSource()
        }
        AnnieAudioHolder.audioOutput = _audioOutput

        // 复用旧实例的数据源工厂；若缺失（理论上不会）则以共享缓存重建
        if (AnnieAudioHolder.cachedSourceFactory == null) {
            val httpFactory = OkHttpDataSource.Factory(globalOkHttp)
                .setUserAgent(_userAgent)
            val upstream: DataSource.Factory = DefaultDataSource.Factory(_context, httpFactory)
            AnnieAudioHolder.cachedSourceFactory = DefaultMediaSourceFactory(
                CacheDataSource.Factory()
                    .setCache(_cache ?: sharedCache(_context))
                    .setUpstreamDataSourceFactory(upstream)
                    .setCacheKeyFactory(::_cacheKeyOfSpec)
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
        // 【不要 stop ApplePlaybackServer】新实例接管的是**同一个** ExoPlayer，
        // Apple 那首歌还在从这个本机 HLS 端口拉分片；在这里关掉会话会直接把正在播的流掐断。
        // Apple 会话只由「换歌/换源」时的 start()/非 Apple 的 _setSource 负责关闭。
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
            _cacheKeyByUrl.clear()
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
            val next = if (_appLoopMode == LoopMode.shuffle) {
                _consumeShuffleNext()
            } else {
                (_currentAbsIndex + 1) % _queue.size
            }
            _playAbsIndex(next)
        }
    }

    override suspend fun skipPrevious() {
        withContext(Dispatchers.Main.immediate) {
            if (_queue.isEmpty()) return@withContext
            val prev = if (_appLoopMode == LoopMode.shuffle) {
                _consumeShuffleNext()
            } else {
                if (_currentAbsIndex <= 0) _queue.size - 1 else _currentAbsIndex - 1
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
            // 切模式不换歌，但要立刻让「预取目标」跟上新模式：
            // - 切到随机：现在就把「下一首」掷出来并告知 UI（否则下一首按钮没有提示，会闪一下）
            // - 切回顺序/单曲：清掉随机提示
            if (_appLoopMode == LoopMode.shuffle) {
                _shuffleNextIndex = _pickShuffleNext()
                _emit(mapOf("nextIndex" to _shuffleNextIndex))
            } else {
                _shuffleNextIndex = -1
            }
            // 按新模式的窗口预热。命中的都是 CacheService / 内存里的旧数据，不会重复打网络
            _startPrefetchUpcoming()
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
     * 用 Media3 的 [Cache] API 逐项 `removeResource`，**不直接删目录**
     * —— 缓存目录正被 `SimpleCache`（含其索引库）占用，直接 `deleteRecursively` 会让索引与文件不一致。
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
        // 【故意不清空输出策略】之前这里先 `_applyOutputPolicy(null)` 回落一次、等 audioInfo
        // 到了再 apply 一次，同一采样率连续切歌时会多一次 AudioSink 重配（几十到上百 ms）。
        // 策略跟着上一首继续跑，新信息到达后按需再调整，重配次数减半。
        val url = _resolveUrl(index, item, false)
        if (requestId != _playRequestId) return
        if (url == null) {
            _autoAdvanceOnFailure(index)
            return
        }
        // 解析期间用户可能已经切歌 -> 放弃本次设置，避免旧请求覆盖新音源
        if (requestId != _playRequestId) return

        _urlCache[_cacheKeyOf(item)] = url
        if (!_setSource(url, item)) {
            _handlePlaybackFailure(index)
            return
        }
        if (requestId != _playRequestId) return

        _consecutiveFailures = 0
        _emit(mapOf("absoluteIndex" to index))
        _player?.play()
        _ensureServiceStarted()
        // 随机模式：起播成功就立刻定下「下一首」，预取 / 自动切 / 下一首按钮都认这一个
        if (_appLoopMode == LoopMode.shuffle) {
            _shuffleNextIndex = _pickShuffleNext()
            _emit(mapOf("nextIndex" to _shuffleNextIndex))
        } else {
            _shuffleNextIndex = -1
        }
        _startPrefetchUpcoming()
        _startAudioInfoProbe(index, url)
    }

    /** 随机模式下掷一个「下一首」（排掉当前曲，避免原地重复） */
    private fun _pickShuffleNext(): Int {
        val candidates = _queue.indices.filter { it != _currentAbsIndex }
        if (candidates.isEmpty()) return _currentAbsIndex
        return candidates[(System.currentTimeMillis() % candidates.size).toInt()]
    }

    /** 取已定好的随机下一首；用掉后置空，等下一次起播再掷 */
    private fun _consumeShuffleNext(): Int {
        val picked = _shuffleNextIndex
            .takeIf { it in _queue.indices && it != _currentAbsIndex }
            ?: _pickShuffleNext()
        _shuffleNextIndex = -1
        return picked
    }

    /**
     * 命中缓存或解析 [index] 的播放直链；失败返回 null（不抛异常，对齐 Dart `_resolveUrl`）。
     * 预取入口 [fetchPreloadUrl] 的 Kotlin 移植见 [_fetchPreloadUrl]。
     */
    private suspend fun _resolveUrl(index: Int, item: Map<String, String>, forceRefresh: Boolean): String? {
        val songKey = _cacheKeyOf(item)
        if (!forceRefresh) {
            val cached = _urlCache[songKey]
            // Apple 的本机 HLS 地址是**一次性**的：每次 ApplePlaybackServer.start() 都会先
            // stop() 掉旧会话，再 listen 一个新的临时端口（ServerSocket(0)）。复用上一次的地址

            // 必然是死链——Media3 要先跑完 HLS 清单重试（约 3 秒）才报错，报错后
            // _handlePlaybackFailure 才会重新解析，表现就是「点切歌后 3 秒才出声」。
            // 所以 Apple 每次都真实走一遍 start()（master/media/init 都有缓存，20~60ms）。
            if (!cached.isNullOrEmpty() && item["platform"] != "apple") return cached
            if (item["platform"] != "apple") {
                _cachedOnlyUrl(songKey)?.let { return it }
            }
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
            Log.w(_tag, "[audio] resolve failed (${item["platform"]} ${item["id"]}): ${e.message}", e)
            null
        }
    }

    /**
     * 预取 URL。
     *
     * - 队列项自带 url（qobuz 详情页 / 已解析）优先
     * - kkbox：无 url 时返回 null
     * - qobuz：无 url 时用活跃 token 取直链（搜索 / 跳转进来的歌靠这条）
     * - **其余平台（国内四平台）：统一走仓库层音源路由**（`fetchPlayUrlForBackend`），
     *   这样落雪插件才能接管播放；此前这里直连 `data/sources/` 会绕过插件。
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
            "apple" -> {
                val song = _appleSongOf(item)
                // 未手动选档（「自动」）时走高解析档：没有超高解析的曲目由
                // `resolveAppleVariant(AlacHiRes)` 自己降级到最高 ALAC / AAC，不会失败
                val quality = MoreSettings.qualityFor(MusicPlatform.apple) ?: Quality.hires
                ApplePlaybackServer.start(song, quality)
            }
            else -> {
                fetchPlayUrlForBackend(item)
            }
        }
    }

    /**
     * 队列项 → Apple [Song]（`/parse/song`、`/key`、预热都只要 id，其它字段是元数据占位）。
     */
    private fun _appleSongOf(item: Map<String, String>): Song = Song(
        id = item["id"].orEmpty(),
        title = item["title"].orEmpty(),
        artist = item["artist"].orEmpty(),
        cover = item["cover"].orEmpty(),
        album = item["album"],
        duration = 0,
        platform = MusicPlatform.apple,
    )

    /**
     * 设置播放源（对齐 Dart `_setSource`）。返回是否成功（进程内同步错误如非法 URI 返回 false；
     * 网络/解码错误异步到达 [AnniePlayerListener.onPlayerError]）。
     *
     * 磁盘缓存规则：**所有平台**统一走 CacheDataSource + SimpleCache，缓存 key 用歌曲身份
     * `平台:id:音质`（见 [_cacheKeyOf]）而非直链 URL——这样插件换直链（带时间戳/签名）也命中同一份缓存。
     */
    private fun _setSource(url: String, item: Map<String, String>): Boolean {
        val p = _player ?: return false
        return try {
            val apple = item["platform"] == "apple"
            // Apple 的本机分片自带稳定 key（`apple:{id}:{tier}:seg:{n}`，见
            // ApplePlaybackServer.cacheKeyFor）；其余平台按「歌曲身份」登记，
            // 这样插件换直链（带时间戳/签名）也命中同一份缓存。
            if (!apple) _cacheKeyByUrl[url] = _cacheKeyOf(item)
            if (!apple) ApplePlaybackServer.stop()
            val factory = _cachedSourceFactory ?: _plainSourceFactory
            val source: MediaSource = (factory ?: return false).createMediaSource(_buildMediaItem(url, item))
            p.setMediaSource(source)
            p.prepare()
            true
        } catch (t: Throwable) {
            Log.w(_tag, "[audio] setSource failed url=$url", t)
            false
        }
    }

    /**
     * 缓存 key 解析：Apple 本机 HLS 的分片/init 由 [ApplePlaybackServer.cacheKeyFor] 给稳定 key
     * （播放列表不缓存，所以那边返回 null 时直接用带 nonce 的完整 URL），其余平台走登记表。
     */
    private fun _cacheKeyOfSpec(spec: DataSpec): String {
        val uri = spec.uri.toString()
        return ApplePlaybackServer.cacheKeyFor(uri) ?: _cacheKeyByUrl[uri] ?: uri
    }

    /** 稳定缓存 key：`平台:id:音质`（与直链无关，插件/内置、换链重解析都命中同一份） */
    private fun _cacheKeyOf(item: Map<String, String>): String =
        "${item["platform"].orEmpty()}:${item["id"].orEmpty()}:" +
            playRequestQuality(item["platform"].orEmpty()).value
/*
     *
     * 整首已在本地时，返回一个**不联网**的占位 URI（并登记到该缓存 key）。
     *
     * 判定极严：必须已记录内容总长、且「已缓存字节 == 总长」才成立 —— 只要差一字节就返回 null，
     * 退回正常解析拿真直链（缓存前缀照读、缺的部分照下）。占位 URI 因请求区间全命中，不会打开 upstream。     */
    private fun _cachedOnlyUrl(songKey: String): String? {
        val cache = _cache ?: return null
        val length = runCatching {
            ContentMetadata.getContentLength(cache.getContentMetadata(songKey))
        }.getOrNull() ?: return null
        if (length <= 0) return null
        val cachedBytes = runCatching {
            cache.getCachedBytes(songKey, 0L, length)
        }.getOrNull() ?: return null
        if (cachedBytes != length) return null
        val url = Uri.parse("https://annie.local/cache/" + Uri.encode(songKey)).toString()
        _cacheKeyByUrl[url] = songKey
        return url
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
        _urlCache.remove(_cacheKeyOf(item))
        val url = _resolveUrl(index, item, true)
        if (requestId != _playRequestId) return
        if (url == null) {
            _autoAdvanceOnFailure(index)
            return
        }
        _urlCache[_cacheKeyOf(item)] = url
        if (!_setSource(url, item)) {
            _urlCache.remove(_cacheKeyOf(item))
            _autoAdvanceOnFailure(index)
            return
        }
        if (requestId != _playRequestId) return

        _consecutiveFailures = 0
        _emit(mapOf("absoluteIndex" to index))
        _player?.play()
        _ensureServiceStarted()
        _startPrefetchUpcoming()
        _startAudioInfoProbe(index, url)
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
        val next: Int = if (_appLoopMode == LoopMode.shuffle) {
            _consumeShuffleNext()
        } else {
            (_currentAbsIndex + 1) % _queue.size
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
        // 单曲循环：下一首永远是它自己，没有可预热的对象
        if (_appLoopMode == LoopMode.single) return

        val targets = mutableListOf<Int>()
        if (_appLoopMode == LoopMode.shuffle) {
            // 随机模式：只预热已经定好的那一首（命中率 100%），不按顺序盲猜
            _shuffleNextIndex.takeIf { it in _queue.indices }?.let { targets.add(it) }
        } else {
            var step = 1
            while (step <= _prefetchAhead && step < count) {
                targets.add((_currentAbsIndex + step) % count)
                step++
            }
        }
        if (targets.isEmpty()) return

        // 只有「紧邻下一首」才预下首片；更远的只灌元数据/密钥（省流量，也不占内存）
        val segmentTarget = targets.first()

        val cursor = AtomicInteger(0)
        repeat(_prefetchConcurrency) {
            _scope.launch {
                while (true) {
                    val i = cursor.getAndIncrement()
                    if (i >= targets.size) return@launch
                    val idx = targets[i]
                    if (gen != _generation) return@launch
                    val item = _queue.getOrNull(idx) ?: continue
                    if (item["platform"] == "apple") {
                        // Apple 的「预取」不是解析直链（本机地址跟会话绑定、不能存），
                        // 而是把 masterUrl / master / media / init / key 灌进缓存，
                        // 顺手把首片段解密好留在内存里，切换时只剩起播放器。
                        if (_pendingPrefetch.contains(idx)) continue
                        _pendingPrefetch.add(idx)
                        try {
                            val quality = MoreSettings.qualityFor(MusicPlatform.apple) ?: Quality.hires
                            ApplePlaybackServer.warm(
                                song = _appleSongOf(item),
                                quality = quality,
                                warmFirstSegment = idx == segmentTarget,
                            )
                        } catch (_: Throwable) {
                            // 预热失败静默：切歌时会重新解析
                        } finally {
                            _pendingPrefetch.remove(idx)
                        }
                        continue
                    }
                    val songKey = _cacheKeyOf(item)
                    if (_urlCache.containsKey(songKey) || _pendingPrefetch.contains(idx)) continue
                    _pendingPrefetch.add(idx)
                    try {
                        val url = _fetchPreloadUrl(item)
                        if (gen == _generation && !url.isNullOrEmpty()) {
                            _urlCache[songKey] = url
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
     * 探测当前曲目的音频格式（编码 / 位深 / 采样率）并下发 `audioInfo` 事件。
     *
     * 优先读 Media3 磁盘缓存里**已落盘的本地文件**（零网络）；刚起播缓存还没写入、
     * 或该平台不走缓存（酷狗直链走 [_plainSourceFactory]）时退回直接探测 URL。
     *
     * Apple 例外：播放用的是本机 `127.0.0.1` 的明文 HLS 代理，ffprobe 探不到真实编码
     * （拿到的只有 m3u8 文本）。它的编码/位深/采样率在解析 master 时就已经确定，
     * 直接用 [ApplePlaybackServer.currentPlayback]，省一次 256KB 拉取也不会空胶囊。
     */
    private fun _startAudioInfoProbe(index: Int, url: String) {
        if (!_active || url.isEmpty()) return
        // Apple 专用提前通道：档位解析完（~1s）就推音质胶囊，不必等整个 open()（2~4s）。
        // 这里每首歌重设一次，lambda 无状态，重复赋值无害。
        ApplePlaybackServer.onInfoReady = { early ->
            if (early.songId == _queue.getOrNull(_currentAbsIndex)?.get("id")) {
                _lastAudioInfo = early.info
                _applyOutputPolicy(early.info)
                _emit(mapOf("audioInfo" to early.info))
            }
        }
        val gen = _generation
        val item = _queue.getOrNull(index)
        if (item?.get("platform") == "apple") {
            val known = ApplePlaybackServer.currentPlayback
                ?.takeIf { it.songId == item["id"] }?.info
            if (known != null) {
                _lastAudioInfo = known
                _applyOutputPolicy(known)
                _emit(mapOf("audioInfo" to known))
                return
            }
        }
        _scope.launch {
            val info = _probeAudioInfo(url)
            if (gen != _generation || index != _currentAbsIndex) return@launch
            _lastAudioInfo = info
            _applyOutputPolicy(info)
            _emit(mapOf("audioInfo" to (info ?: AudioInfo())))
        }
    }

    /**
     * 把「模式 / EQ 预设 / 响度 / 强制原生率」实时下发到处理器链。
     *
     * EQ 系数是整体替换的，播放中改预设立即生效；重采样开关会改变输出格式，
     * 必须重新 `prepare` 才能重配 AudioSink —— 下一次切歌生效（副标题已注明）。
     */
    private fun _observeAudioSettings() {
        _scope.launch {
            kotlinx.coroutines.flow.combine(
                MoreSettings.audioMode,
                MoreSettings.tuningEngine,
                MoreSettings.loudnessNormalization,
                MoreSettings.forceNativeRate,
                MoreSettings.resampleRate,
            ) { mode, _, _, force, rate -> Triple(mode, force, rate) }
                .collect { (mode, force, rate) ->
                    // 这几个开关会改变 AudioSink 的 configure 结果（processor 是否入链 / 输出采样率），
                    // 光改参数不会重新评估 isActive，必须重建一次音源才会生效。
                    val needReload = (_lastMode != null && _lastMode != mode) ||
                        (_lastForceNative != null &&
                            (_lastForceNative != force || _lastResampleRate != rate))
                    _lastMode = mode
                    _lastForceNative = force
                    _lastResampleRate = rate
                    _applyOutputPolicy(_lastAudioInfo)
                    if (needReload) _reloadCurrentSource()
                }
        }
        _scope.launch {
            MoreSettings.resampleDepth.collect {
                _applyOutputPolicy(_lastAudioInfo)
                _reloadCurrentSource()
            }
        }
        _scope.launch {
            kotlinx.coroutines.flow.combine(
                MoreSettings.graphicGains,
                MoreSettings.graphicAmp,
                MoreSettings.peqBands,
                MoreSettings.peqAmp,
                MoreSettings.mseb,
            ) { _, _, _, _, _ -> Unit }
                .collect { _applyTuningParams() }
        }
    }

    /** 用当前直链重建音源（保留播放位置），让处理器链按新设置重新 configure */
    private fun _reloadCurrentSource() {
        val p = _player ?: return
        if (p.mediaItemCount == 0) return
        val item = _queue.getOrNull(_currentAbsIndex) ?: return
        val url = _urlCache[_cacheKeyOf(item)] ?: return
        val position = p.currentPosition
        val wasPlaying = p.isPlaying
        if (_setSource(url, item)) {
            if (position > 0) p.seekTo(position)
            if (wasPlaying) p.play()
        }
    }

    /** 只重建调音参数，不查设备/路由（拖动滑块时高频调用） */
    private fun _applyTuningParams(): Boolean {
        val factory = _renderersFactory ?: return false
        val tuned = MoreSettings.audioMode.value == AudioOutputMode.tuned
        val loudnessOn = tuned && MoreSettings.loudnessNormalization.value
        val loudnessDb = if (loudnessOn) _lastAudioInfo?.replayGainDb ?: 0f else 0f
        val base = TuningConfig(
            engine = MoreSettings.tuningEngine.value,
            graphicGains = MoreSettings.graphicGains.value,
            graphicAmpDb = MoreSettings.graphicAmp.value,
            peqBands = MoreSettings.peqBands.value,
            peqAmpDb = MoreSettings.peqAmp.value,
            mseb = MoreSettings.mseb.value,
            loudnessDb = loudnessDb,
        )
        val eqOn = tuned && base.hasToneChange()
        // 限幅只在真的有正增益时才有意义：调音模式 + 引擎抬升或响度为正
        val limiterOn = tuned && (loudnessDb > 0f || base.hasBoost())
        factory.tuning.setConfig(if (tuned) base.copy(limiter = limiterOn) else TuningConfig(), tuned)
        return eqOn
    }
    private fun _applyOutputPolicy(info: AudioInfo?) {
        val out = _audioOutput ?: return
        val factory = _renderersFactory
        val mode = MoreSettings.audioMode.value
        val tuned = mode == AudioOutputMode.tuned
        val loudnessOn = tuned && MoreSettings.loudnessNormalization.value
        val loudnessDb = if (loudnessOn) info?.replayGainDb ?: 0f else 0f
        val loudnessApplied = loudnessOn && loudnessDb != 0f
        val eqOn = _applyTuningParams()
        val nativeRate = out.nativeSampleRate()
        val srcRate = info?.sampleRate ?: 0
        val route = out.currentRoute()
        val deviceRates = out.currentOutputDevice()?.sampleRates.orEmpty()
        // 设备声明的最高率（USB DAC 才有意义）
        val maxDeviceRate = deviceRates
            .filter { it in 8_000..768_000 }
            .maxOrNull()
            ?: nativeRate
        // 自动策略：
        // - 蓝牙：旁路（codec 决定实际率，再重采样只会双重重采样）
        // - USB DAC：设备最高率（HAL 可能 rate-match）
        // - 3.5mm / 内置：系统 primary output 率（通常 48k），避免 mixer 再 SRC
        val autoTargetRate = when (route) {
            AudioRoute.bluetooth -> 0
            AudioRoute.usb -> maxDeviceRate
            AudioRoute.wired, AudioRoute.speaker -> nativeRate
            else -> nativeRate
        }
        val configuredRate = MoreSettings.resampleRate.value
        val requestedRate = if (MoreSettings.forceNativeRate.value) {
            if (configuredRate > 0) configuredRate else autoTargetRate
        } else {
            0
        }
        // 设备没声明该档位时回退自动值，避免 AudioTrack 创建失败
        val targetRate = when {
            requestedRate <= 0 -> 0
            deviceRates.isEmpty() || requestedRate in deviceRates || requestedRate == nativeRate ->
                requestedRate
            else -> autoTargetRate
        }
        val willResample = targetRate > 0 && srcRate > 0 && srcRate != targetRate
        val needDepthConvert = MoreSettings.resampleDepth.value != 0
        factory?.resampler?.setEnabled(willResample || needDepthConvert, targetRate)
        factory?.resampler?.setOutputDepth(MoreSettings.resampleDepth.value)
        // MP3 默认加 TPDF dither（对齐海贝 mpg123 的默认行为）；其它格式不加
        factory?.resampler?.setDither(info?.codec.equals("MP3", ignoreCase = true))
        // 输出位深：显式选了才非 0（跟随源 = 0，显示时回退源/蓝牙位深）
        val outputDepth = when (MoreSettings.resampleDepth.value) {
            16 -> 16
            24 -> 24
            32 -> 32
            else -> 0
        }
        // 蓝牙链路：只认真实 codec 配置；读不到就退回系统输出线程率，不用设备最高率猜
        val btCodec = if (route == AudioRoute.bluetooth) BluetoothCodec.info.value else null
        val btRate = when {
            route != AudioRoute.bluetooth -> 0
            btCodec != null && btCodec.sampleRate > 0 -> btCodec.sampleRate
            else -> nativeRate
        }
        val btDepth = when {
            route != AudioRoute.bluetooth -> 0
            btCodec != null && btCodec.bitsPerSample > 0 -> btCodec.bitsPerSample
            else -> info?.bitDepth ?: 0
        }
        // 只有「透明 + 有源格式 + 外接 USB」才值得向系统申请 bit-perfect；
        // 其余情况如实显示做不到（方案 1.2.3 的第三种状态）。
        val bitPerfect = if (!tuned && srcRate > 0) {
            out.applyBitPerfectPassthrough(srcRate, info?.bitDepth?.takeIf { it > 0 } ?: 16)
        } else {
            out.clearBitPerfectPassthrough()
            false
        }
        _emit(
            mapOf(
                "audioStatus" to AudioOutputStatus(
                    mode = mode,
                    route = out.currentRoute(),
                    deviceName = out.currentDeviceName(),
                    nativeRate = nativeRate,
                    deviceMaxRate = maxDeviceRate,
                    deviceSampleRates = deviceRates,
                    autoTargetRate = autoTargetRate,
                    bluetoothRate = btRate,
                    bluetoothDepth = btDepth,
                    outputDepth = outputDepth,
                    sourceRate = srcRate,
                    sourceDepth = info?.bitDepth ?: 0,
                    resampledTo = if (willResample) targetRate else 0,
                    bitPerfect = bitPerfect,
                    eqOn = eqOn,
                    loudnessOn = loudnessApplied,
                ),
            ),
        )
    }

    /**
     * 用 ffprobe 读取 [url] 的格式信息；失败 / 超时返回 null。
     *
     * 缓存分片可能还没落盘完整：此时 ffprobe 拿得到采样率/比特率却**拿不到 `codec_name`**，
     * 于是结果里缺编码。所以探到「无编码」时改用完整 URL 重探一次，保证编码一定拿得到。
     */
    private suspend fun _probeAudioInfo(url: String): AudioInfo? = withContext(Dispatchers.IO) {
        val cached = _cachedFileFor(url)?.absolutePath
        if (cached != null) {
            val info = _probeTarget(cached)
            if (info != null && info.codec.isNotEmpty()) return@withContext info
        }
        // 首次播放时 Media3 还没 commit 缓存分片（只在数据源关闭时才写索引），本地无文件可探；
        // 而 ffprobe 直接拉 URL 常拿不到编码 → 自己抓一段头部字节再 probe
        val prefix = _probeUrlPrefix(url)
        if (prefix != null && prefix.codec.isNotEmpty()) return@withContext prefix
        _probeTarget(url)
    }

    /**
     * 抓 [url] 开头 [Companion._probePrefixBytes] 字节到临时文件后 probe。
     *
     * 首次播放只能这样拿编码：mp3 的 ID3 标签 + 首帧、flac 的 STREAMINFO 都在文件头部，
     * 拿到头部 ffprobe 就能给出编码 / 位深 / 采样率。用完即删。
     */
    private suspend fun _probeUrlPrefix(url: String): AudioInfo? = withContext(Dispatchers.IO) {
        var file: File? = null
        try {
            val req = Request.Builder().url(url).header("User-Agent", _userAgent).build()
            val client = globalOkHttp.newBuilder()
                .callTimeout(_probeTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .build()
            file = File.createTempFile("probe_", ".bin", _context.cacheDir)
            client.newCall(req).execute().use { resp ->
                val body = resp.body ?: return@withContext null
                body.byteStream().use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0
                        while (total < _probePrefixBytes) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            total += n
                        }
                    }
                }
            }
            _probeTarget(file.absolutePath)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { file?.delete() }
        }
    }

    /** 对单个目标（本地文件或 URL）跑一次 ffprobe。 */
    private suspend fun _probeTarget(target: String): AudioInfo? = withContext(Dispatchers.IO) {
        try {
            val media = FFprobeKit.getMediaInformation(target, _probeTimeoutMs).mediaInformation
                ?: return@withContext null
            val audio = media.streams.firstOrNull { it.type == "audio" } ?: return@withContext null
            Log.i(
                _tag,
                "[audio] probe target=$target streams=${media.streams.size} " +
                    "codec=${audio.codec} depth=${audio.getStringProperty("bits_per_raw_sample")} " +
                    "rate=${audio.sampleRate} bps=${audio.bitrate}",
            )
            val codec = _formatCodec(audio.codec)
            val rate = audio.sampleRate?.toIntOrNull() ?: 0
            if (codec.isEmpty() && rate == 0) return@withContext null
            Log.i(
                _tag,
                "[audio] route=${AudioFormatRouter.route(media.format.orEmpty(), audio.codec.orEmpty())} " +
                    "container=${media.format}",
            )
            AudioInfo(
                codec = codec,
                bitDepth = audio.getStringProperty("bits_per_raw_sample")?.toIntOrNull()
                    ?: audio.getStringProperty("bits_per_sample")?.toIntOrNull()
                    ?: 0,
                sampleRate = rate,
                bitrate = audio.bitrate?.toIntOrNull()?.takeIf { it > 0 }
                    ?: media.bitrate?.toIntOrNull()?.takeIf { it > 0 }
                    ?: 0,
                replayGainDb = _replayGainOf(media),
            )
        } catch (_: Throwable) {
            null
        }
    }

    /** 取 [url] 在 Media3 缓存里从 0 开始的 span 文件（存在即已落盘、可本地 probe）。 */
    private fun _cachedFileFor(url: String): File? {
        val cache = _cache ?: return null
        val key = _cacheKeyByUrl[url] ?: url
        return try {
            cache.getCachedSpans(key).firstOrNull { it.position == 0L && it.length > 0 }?.file
        } catch (_: Throwable) {
            null
        }
    }

    /** ffprobe 编码名 -> 展示名（`mp3` -> `MP3`；`pcm_*` 归并为 `PCM`）。 */
    private fun _formatCodec(codec: String?): String {
        val c = codec?.trim().orEmpty()
        if (c.isEmpty()) return ""
        if (c.startsWith("pcm", ignoreCase = true)) return "PCM"
        return c.uppercase()
    }

    /** 读 ReplayGain 曲目增益（dB）；没有标签或解析失败返回 0（= 不做响度处理）。 */
    private fun _replayGainOf(media: com.arthenica.ffmpegkit.MediaInformation): Float {
        val tags = media.tags ?: return 0f
        val raw = tags.optString("REPLAYGAIN_TRACK_GAIN", "")
            .ifEmpty { tags.optString("replaygain_track_gain", "") }
        if (raw.isEmpty()) return 0f
        return raw.trim().substringBefore(' ').toFloatOrNull()?.coerceIn(-24f, 24f) ?: 0f
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

/*
     *
     * MediaSession 回调：把通知栏/锁屏/线控的「上一首/下一首」转发到应用级切歌。
     *
     * 引擎队列每次只有一个 MediaItem，Media3 默认的 `seekToNext/PreviousMediaItem` 会因没有
     * 下一/上一项而 no-op，导致通知栏按钮失效。这里拦截对应命令调用 [skipNext]/[skipPrevious]
     * （对齐 Flutter `AudioPlayerHandler.skipToNext/skipToPrevious → onNext/onPrevious`）。
     * 由于队列仅单个 MediaItem，返回 RESULT_SUCCESS 后即便命令再转发给 player 也无副作用。     */
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

/*
     *
     * 让「上一首 / 下一首」在 Media3 眼里**可用**，否则通知栏 / 锁屏 / 蓝牙不会画出切歌键。
     *
     * 本引擎每次只装 1 个 MediaItem（每首歌的直链都要按需解析），ExoPlayer 因此把
     * `COMMAND_SEEK_TO_NEXT(_MEDIA_ITEM)` / `..PREVIOUS..` 从可用命令里剔掉 →
     * `DefaultMediaNotificationProvider` 判定「没有上一首/下一首」→ 媒体通知里没有切歌按钮。
     *
     * 这里只覆盖 [getAvailableCommands]（其余全部转发给真实 player，应用侧逻辑用的仍是原
     * ExoPlayer 实例），把这两条命令补回可用集合；真正的切歌仍由
     * [AnnieSessionCallback.onPlayerCommandRequest] 转发到 skipNext / skipPrevious。     */

/*
     *
     * 本机 `127.0.0.1` 上的 Apple HLS 代理**不做清单重试**：它要么立刻成功，要么就是死链
     * （会话已切走 / 端口已关）。Media3 默认的 HLS 清单重试会把这个失败拖成 3 秒静音，
     * 直接返回 [C.TIME_UNSET] 让它立刻走 `onPlayerError`，由
     * [_handlePlaybackFailure] 重新解析，用户几乎察觉不到。远端平台沿用默认策略。     */
    private class LocalFailFastPolicy(
        private val delegate: LoadErrorHandlingPolicy = DefaultLoadErrorHandlingPolicy(),
    ) : LoadErrorHandlingPolicy by delegate {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val host = loadErrorInfo.loadEventInfo.dataSpec.uri.host
            if (host == "127.0.0.1" || host == "localhost" || host == "::1") return C.TIME_UNSET
            return delegate.getRetryDelayMsFor(loadErrorInfo)
        }
    }

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

        /** 首次播放探测音频信息时抓的头部字节数（够覆盖 ID3 标签 + 首帧 / FLAC STREAMINFO） */
        const val _probePrefixBytes: Int = 256 * 1024

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
                val created = SimpleCache(dir, NoOpCacheEvictor())
                _sharedCache = created
                return created
            }
        }
        const val _userAgent: String = "AnnieMusic/1.0 (Android)"

        /**
         * 顺序模式往后预热的曲目数。
         *
         * 只留 2：Apple 每首都要打 `/parse/song`（Cloudflare 后面）+ 2 次 CDN + init + key，
         * 5 首在弱网下纯浪费流量；实际使用里连跳两首以上极少见。
         */
        const val _prefetchAhead: Int = 2
        const val _probeTimeoutMs: Int = 10_000
        const val _prefetchConcurrency: Int = 3
        const val _maxConsecutiveFailures: Int = 3
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

    /** 自建 RenderersFactory（处理器链持有 EQ / 重采样参数，Activity 重建时需复用） */
    @Volatile
    var renderersFactory: AnnieRenderersFactory? = null

    /** 输出设备控制器（播放页的设备切换面板直接读取/操作） */
    @Volatile
    var audioOutput: AudioOutputController? = null

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