package com.annieplayer.annie_music.kt.core.state

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.annieplayer.annie_music.kt.core.audio.AudioOutputStatus
import com.annieplayer.annie_music.kt.data.fetchLyric
import com.annieplayer.annie_music.kt.data.model.AudioInfo
import com.annieplayer.annie_music.kt.data.model.LoopMode
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.network.anyToJsonElement
import com.annieplayer.annie_music.kt.data.network.json
import com.annieplayer.annie_music.kt.data.network.jsonElementToAny
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 播放状态（1:1 移植 Flutter `core/providers/` 下的 `player_provider.dart`，对齐 RN 版
 * store/usePlayerStore.ts）。
 *
 * 职责边界：本文件只移植**状态与状态迁移**以及歌词 / 持久化副作用；
 * 真正的音频引擎由后续任务实现，并遵循下方 [PlayerBackend] 契约注入。
 */

/**
 * 播放器控制接口（对齐 Dart `PlayerBackend`，即 RN `services/playerService.native.ts`
 * + AudioContext 的契约）。
 *
 * 由后续「音频引擎」任务实现并通过 [PlayerViewModel.setBackend] 注入；本层只依赖该接口。
 * Dart 的 `Future<T>` -> Kotlin `suspend fun`，Dart `Stream<Map>` -> Kotlin `Flow<Map>`。
 * 事件 key 与 Dart 完全一致：`absoluteIndex` / `isPlaying` / `position` / `duration`。
 */
interface PlayerBackend {
    /** 初始化引擎（对齐 Dart `setup()`） */
    suspend fun setup()

    /** 设置队列并跳转到 startIndex；loopMode 取 Dart 枚举名（sequential/single/shuffle） */
    suspend fun setQueue(items: List<Map<String, String>>, startIndex: Int, loopMode: String)

    suspend fun skipNext()

    suspend fun skipPrevious()

    suspend fun skipTo(index: Int)

    /**
     * 调整队列内部顺序（拖拽排序）：把 [fromIndex] 的歌挪到 [toIndex]。
     *
     * **不要用 `setQueue(同一队列, 同一 index)` 实现这件事** —— 那条路会
     * `_generation++`、清 URL 缓存、重新 prepare 当前 item：进度归零、白等一次加载，
     * Apple 还要重开一遍一次性代理端口。这里走 ExoPlayer 的 `moveMediaItem`，
     * 只重排 timeline，正在播的那一首不中断。
     */
    suspend fun moveQueueItem(fromIndex: Int, toIndex: Int)

    /**
     * 把队列第 [index] 项摘掉（队列抽屉手动删除 / 删除本地文件）。
     *
     * **只动引擎自己的队列模型，不碰 player 的 timeline** —— 本后端的 timeline 始终只有
     * 一条 MediaItem（见 `_setSource` 用 `setMediaSource`），整条队列是 `_queue` 自己维护的。
     * 正在播的那一项若被摘掉，调用方必须**先切歌**（[skipNext]），否则 `_currentAbsIndex`
     * 会指到一个已经不存在的位置。
     */
    suspend fun removeQueueItem(index: Int)

    /** 设置 App 层循环模式（取 Dart 枚举名） */
    suspend fun setAppLoopMode(loopMode: String)

    /** 读取引擎当前队列下标 */
    suspend fun getQueueIndex(): Int

    suspend fun play()

    suspend fun pause()

    suspend fun seekTo(seconds: Double)

    suspend fun setVolume(volume: Double)

    /** 清理播放缓存（Media3 流式缓存）；返回释放的字节数（未接线时 0） */
    suspend fun clearPlaybackCache(): Long

    /** 引擎事件流（keyed map，见接口 KDoc） */
    val events: Flow<Map<String, Any?>>
}

/**
 * 播放进度（**独立于 [PlayerState] 的高频流**）。
 *
 * ## 为什么要拆出来
 *
 * 进度是全场唯一按 **5 Hz** 变化的字段（引擎固定 200ms 推一次，见
 * `MediaPlayerBackend._progressIntervalMs`）。它原先和「队列 / 当前曲 / 音质状态」放在
 * 同一个 `PlayerState` 里，于是每 200ms 就 `copyWith` 出一个**新的 PlayerState 实例**，
 * 所有读 `PlayerState` 的组合全部失效 —— `AppShell` 在函数体顶层 `collectAsState`，
 * 等于 1598 行的函数体每秒重跑 5 次（lambda 重建、测量重算、整棵树从头走一遍）。
 *
 * 拆成独立流后，**组合依赖只建立在真正读进度的那几个叶子上**（进度条、歌词页、迷你条）。
 * 主语 `AppShell` 只把这条流当对象往下传，从不读 `.value`，因此完全不参与重组。
 *
 * ## 传播纪律（改这个文件之前先读）
 *
 * - **不要**把这几个字段搬回 [PlayerState]。
 * - 上层**不许**在组合期 `collectAsState()` 之后再往下传值：`val p by progress.collectAsState()`
 *   会让读它的那个函数重组。正确做法是把 `StateFlow` 本身传下去，在叶子内部 collect。
 * - 需要「在回调里读一次」的场合（例：`seekToSeconds(ratio * duration)`）直接用
 *   `progress.value.duration` —— 那是事件回调，不建立组合依赖。
 */
data class PlaybackProgress(
    val currentTime: Double = 0.0,
    val duration: Double = 0.0,
    val bufferedTime: Double = 0.0,
)

/** 播放状态（对齐 Dart `PlayerState`） */
data class PlayerState(
    val queue: List<Song> = emptyList(),
    val currentIndex: Int = 0,
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val loopMode: LoopMode = LoopMode.sequential,
    val volume: Double = 0.8,
    val downloadedIds: List<String> = emptyList(),
    val isLyricsVisible: Boolean = false,
    /** 正在播放音频的格式信息（引擎探测后下发；空 = 隐藏胶囊） */
    val audioInfo: AudioInfo = AudioInfo(),
    /** 当前输出链路状态（模式 / 设备 / 是否重采样 / 是否 bit-perfect） */
    val audioStatus: AudioOutputStatus? = null,
    /**
     * 这份队列是「从哪个歌单开始播」的。
     *
     * 只用于全局 ⋮ 面板的「跳转到歌单」：从歌单详情开播时写进来，之后无论切到播放页、
     * 搜索页还是任何别的地方，都能一键回到那个歌单。**换队列才重置**（其它状态迁移一律
     * 走 [copyWith]，会自动沿用这份来源）。
     */
    val queuePlaylist: PlaylistDetailData? = null,
) {
    /**
     * 局部更新（对齐 Dart `PlayerState.copyWith`）。
     *
     * 注意：与 Dart 语义一致，可空字段传 `null` **不会**清空原值（`?: this.xxx` 回退）。
     * 例如 `currentSong = null` 保留旧曲目 —— 清空只能通过构造新的 [PlayerState] 实现。
     *
     * **进度不在这里**：`currentTime` / `duration` / `bufferedTime` 走 [PlaybackProgress]，
     * 见那个类的 KDoc（5 Hz 的字段混进来会让整棵树的组合每秒失效 5 次）。
     */
    fun copyWith(
        queue: List<Song>? = null,
        currentIndex: Int? = null,
        currentSong: Song? = null,
        isPlaying: Boolean? = null,
        loopMode: LoopMode? = null,
        volume: Double? = null,
        downloadedIds: List<String>? = null,
        isLyricsVisible: Boolean? = null,
        audioInfo: AudioInfo? = null,
        audioStatus: AudioOutputStatus? = null,
        queuePlaylist: PlaylistDetailData? = null,
    ): PlayerState = PlayerState(
        queue = queue ?: this.queue,
        currentIndex = currentIndex ?: this.currentIndex,
        currentSong = currentSong ?: this.currentSong,
        isPlaying = isPlaying ?: this.isPlaying,
        loopMode = loopMode ?: this.loopMode,
        volume = volume ?: this.volume,
        downloadedIds = downloadedIds ?: this.downloadedIds,
        isLyricsVisible = isLyricsVisible ?: this.isLyricsVisible,
        audioInfo = audioInfo ?: this.audioInfo,
        audioStatus = audioStatus ?: this.audioStatus,
        queuePlaylist = queuePlaylist ?: this.queuePlaylist,
    )
}

/**
 * 歌曲 -> 引擎队列项（1:1 保留 Dart `setQueue` 内的 Map 构造，含 qobuz 分支）。
 *
 * Dart 原实现用 map 字面量的两个 `if` 依次写入 `url`；同键两次写入后者覆盖前者，
 * 等价于「playUrl 非空即带 url」。此处用可变 Map 的两次 put **逐字保留**该语义。
 */
private fun _toBackendItem(song: Song): Map<String, String> {
    val any = song
    val m = LinkedHashMap<String, String>()
    m["id"] = song.id
    m["platform"] = song.platform.name
    m["title"] = song.title
    m["artist"] = song.artist
    m["cover"] = song.cover
    m["album"] = song.album ?: ""
    // 带上该歌**真实可用档位**：引擎据此把「选了无损但歌只有 320k」直接选成 320k，
    // 不再去发注定失败的请求（否则会拿到坏链 → 播放器报错 → 自动跳下一首）
    m["qualities"] = song.qualities.joinToString(",") { it.value }
    // QQ 的媒体 mid：引擎侧解析（尤其落雪插件）要用它，songmid 顶替不了
    song.mediaMid?.takeIf { it.isNotEmpty() }?.let { m["mediaMid"] = it }
    // 只有 qobuz 需要把直链带进队列（详情页/搜索拿到的直链是唯一来源）。
    // 其余平台一律留空 → 交给引擎按平台解析，否则 Song 上任何残留的旧直链都会
    // 让引擎「直接用、不请求播放接口」（真机抓包：网易/QQ 完全没发请求）。
    // 本地文件没有「解析」这一步：playUrl 就是 file:// 绝对路径，必须带进队列。
    if (song.platform == MusicPlatform.qobuz && any.playUrl.isNotEmpty()) m["url"] = any.playUrl
    if (song.platform == MusicPlatform.local && any.playUrl.isNotEmpty()) m["url"] = any.playUrl
    return m
}

/**
 * 播放状态 ViewModel（对齐 Dart `PlayerNotifier`）。
 *
 * 持久化 key 与 Dart 完全一致：
 * - `annie_player_volume`（Double，Android 侧以 Float 存）
 * - `annie_player_loopMode`（String，Dart 枚举名）
 * - `annie_player_downloadedIds`（有序 id 列表）
 */
class PlayerViewModel : ViewModel() {

    private val _state = MutableStateFlow(PlayerState())

    /** 只读状态流 */
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(PlaybackProgress())

    /**
     * 只读进度流（引擎固定 200ms 推一次，见 `MediaPlayerBackend._progressIntervalMs`）。
     *
     * **和 [state] 分开的理由见 [PlaybackProgress]**：这条流每 200ms 变一次，谁读它谁重组，
     * 所以只能让进度条 / 歌词页这类叶子读。宿主读它 = 宿主每秒重组 5 次。
     *
     * 语义与旧 `PlayerState.currentTime/duration/bufferedTime` 逐字一致（含「后端时长非 0 才覆盖」
     * 与「切歌把位置归零」两条），只是搬了存放位置。
     */
    val progress: StateFlow<PlaybackProgress> = _progress.asStateFlow()

    /** 是否已销毁（对齐 Dart `_disposed`；异步回调据此丢弃越界写入） */
    @Volatile
    private var _disposed: Boolean = false

    /** 已注入的音频引擎（对齐 Dart `playerBackendProvider`） */
    private var _backend: PlayerBackend? = null

    /** 引擎事件订阅（后端切换时取消旧订阅） */
    private var _eventJob: Job? = null

    /** 正在拉取歌词的歌曲 id，避免重复请求（对齐 Dart `_lyricFetching`） */
    private val _lyricFetching: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * KKBOX 活跃账号 sid 提供者（对齐 Dart `ref.read(kkboxStoreProvider.notifier)
     * .getActiveAccount()?.session?.sid`）。
     *
     * 由 UI 层把 [KkboxViewModel] 的取值函数注入进来，避免 ViewModel 之间直接耦合。
     */
    var kkboxSidProvider: (() -> String?)? = null

    // ── 播放现场恢复（重启后回到退出时的歌 + 进度） ──

    /** 待续播位置（毫秒）；首次 play 时 seek 过去并清零 */
    private var _pendingResumeMs: Long = 0L

    /** 引擎队列是否已装载。恢复态下为 false —— 首次 play 才真正 setQueue（不然一开 App 就出声） */
    private var _backendQueueReady: Boolean = true

    /**
     * 「引擎还没注入时用户就点了播放」的待播位置（毫秒）；-1 = 没有待播。
     *
     * 有了它，`setBackend` 注入完成后能自动把这次播放补上 —— 否则用户那次点击会被静默吞掉，
     * 而 `isPlaying` 已经是 true，状态机随后会卡在"点了没声音、再点变暂停"的死循环里。
     */
    private var _pendingRestorePlayMs: Long = -1L

    /**
     * 恢复播放时「等播放器 ready 再 seek 到哪」（毫秒）；0 = 没有待办。
     *
     * 不能在 `setQueue` 之后立刻 seek：那时 ExoPlayer 常还在 BUFFERING，seek 会打断加载，
     * 结果是停在正确位置却不出声。等第一个 `position` 事件到了再 seek 才稳。
     */
    private var _pendingSeekAfterStart: Long = 0L

    /** 最近一次已知播放位置（毫秒），用于节流写盘 */
    private var _lastPositionMs: Long = 0L

    private var _lastPositionWriteAt: Long = 0L

    /**
     * 初始化**必须放在所有字段声明之后**。
     *
     * Kotlin 的属性初始化器和 `init` 块按源码顺序执行 —— 如果把 `init` 写在
     * `_pendingResumeMs` / `_backendQueueReady` 前面，`_restoreResume()` 里设的值会被
     * 后面的字段初始化器覆盖回默认值（`false` 变回 `true`），表现就是「恢复出了歌和进度，
     * 但点播放不走恢复分支 → 引擎里没有队列 → 怎么点都没声音」。
     */
    init {
        _loadPersisted()
        _restoreResume()
    }

    /**
     * 从磁盘恢复上次的队列 / 当前曲 / 进度。
     *
     * 只写 state，**不碰引擎**：恢复出来是"暂停在退出时的位置"，用户点播放才续上，
     * 避免一启动就外放。流媒体的直链一律清空（一次性，跨进程必失效），
     * 本地文件的 `file://` 保留 —— 详见 [com.annieplayer.annie_music.kt.data.PlayerResumeStore]。
     */
    private fun _restoreResume() {
        val snap = com.annieplayer.annie_music.kt.data.PlayerResumeStore.load() ?: return
        val songs = snap.songs.map { s ->
            if (s.platform == MusicPlatform.local) s else s.copyWith(playUrl = "")
        }
        if (songs.isEmpty()) return
        val idx = snap.index.coerceIn(0, songs.lastIndex)
        val cur = songs[idx]
        _pendingResumeMs = snap.positionMs
        _lastPositionMs = snap.positionMs
        _backendQueueReady = false
        _state.value = _state.value.copy(
            queue = songs,
            currentIndex = idx,
            currentSong = cur,
            // 恢复态一律是「暂停」：不自动续播
            isPlaying = false,
            // 队列来源歌单一并恢复，否则重启后 ⋮ 里的「跳转到歌单」会消失
            queuePlaylist = snap.playlist?.let {
                PlaylistDetailData(
                    id = it.id,
                    platform = it.platform,
                    name = it.name,
                    cover = it.cover,
                )
            },
        )
        _progress.value = PlaybackProgress(
            currentTime = snap.positionMs / 1000.0,
            duration = if (cur.duration > 0) cur.duration.toDouble() else 0.0,
        )
        // 恢复的这首歌也要补歌词：重启后接着放，进度和队列都回来了，但歌词不能是空的
        _ensureLyric(cur)
    }

    /** 把当前 index + 进度落盘（高频调用，文件很小） */
    private fun _persistResumeState() {
        val st = _state.value
        if (st.queue.isEmpty()) return
        com.annieplayer.annie_music.kt.data.PlayerResumeStore.saveState(st.currentIndex, _lastPositionMs)
    }

    /** 供宿主（Activity onStop）在退到后台时立刻落盘一次 */
    fun persistResumeNow() {
        _persistResumeState()
    }

    /**
     * 恢复态下的首次播放：装载队列 → 播放；**续播位置的 seek 交给第一个进度事件**。
     *
     * 为什么不在 `setQueue` 之后立刻 seek：`setQueue` 只负责 `setMediaSource + prepare`，
     * 返回时 ExoPlayer 往往还在 BUFFERING。此时 `seekTo` 会取消当前加载、重新定位，
     * 真机表现就是「seek 到了正确位置，但一直不出声、进度也不动」（`isPlaying` 始终 false）。
     * 改成等后端推来第一个 `position` 事件（= 播放器已 ready 且在走）再 seek，
     * 位置准确且不会打断加载。
     *
     * 失败（引擎未注入 / setQueue 抛异常）时**必须把 isPlaying 复位**，
     * 否则状态机会卡在"显示正在播放但引擎里没有队列"。
     */
    private fun _startRestoredPlayback(seekMs: Long) {
        val st = _state.value
        if (st.queue.isEmpty()) return
        viewModelScope.launch {
            val b = _backend
            if (b == null) {
                // 引擎还没注入：记下待播位置，setBackend 里会自动补
                _pendingRestorePlayMs = seekMs
                return@launch
            }
            val queued = try {
                b.setQueue(st.queue.map { _toBackendItem(it) }, st.currentIndex, st.loopMode.name)
                _backendQueueReady = true
                true
            } catch (_: Throwable) {
                false
            }
            if (!queued) {
                _state.value = _state.value.copyWith(isPlaying = false)
                return@launch
            }
            // setQueue 内部已经 play()；这里只登记"等 ready 后要 seek 到哪"
            if (seekMs > 0) _pendingSeekAfterStart = seekMs
            runCatching { b.play() }
        }
    }

    /**
     * 注入音频引擎（对齐 Dart `playerBackendProvider` 被 main 异步赋值的行为）。
     * 传 null 表示卸载；切换时会取消旧事件订阅。
     */
    fun setBackend(backend: PlayerBackend?) {
        _backend = backend
        _subscribe(backend)
        // 引擎注入后再补一次歌词：恢复态在 init 里就拉了，但 KKBOX 这类要账号会话的源
        // 那时 sid provider 还没接上，只能等宿主把 backend 注入完再来一次。
        _state.value.currentSong?.let { _ensureLyric(it) }
        // 恢复态下用户可能先点了「播放」（那时引擎还没注入）→ 现在补上
        val pending = _pendingRestorePlayMs
        if (backend != null && pending >= 0) {
            _pendingRestorePlayMs = -1L
            _startRestoredPlayback(pending)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // 退场前把播放现场落一次盘（节流写盘可能还差几秒）
        _persistResumeState()
        _disposed = true
        _eventJob?.cancel()
        _eventJob = null
    }

    // ── 持久化（对齐 Dart `_loadPersisted` / `_persist*`） ──

    /** 从 SharedPreferences 读取 volume/loopMode/downloadedIds 并合并；失败静默降级为默认值 */
    private fun _loadPersisted() {
        val p = _prefs ?: return
        try {
            val volume: Double? =
                if (p.contains(VOLUME_KEY)) p.getFloat(VOLUME_KEY, 0.8f).toDouble() else null
            val loopName: String? = p.getString(LOOP_MODE_KEY, null)
            val downloaded: List<String>? = _readDownloadedIds(p)
            _state.value = _state.value.copyWith(
                volume = volume ?: _state.value.volume,
                loopMode = if (loopName != null) LoopMode.fromName(loopName) else _state.value.loopMode,
                downloadedIds = downloaded ?: _state.value.downloadedIds,
            )
        } catch (_: Throwable) {
            // 持久化读取失败：保留默认值（不阻塞播放）
        }
    }

    private fun _readDownloadedIds(p: SharedPreferences): List<String>? {
        val raw = p.getString(DOWNLOADED_IDS_KEY, null) ?: return null
        val parsed = jsonElementToAny(json.parseToJsonElement(raw))
        val list = parsed as? List<*> ?: return null
        return list.filterIsInstance<String>()
    }

    private fun _persistVolume(v: Double) {
        try {
            _prefs?.edit()?.putFloat(VOLUME_KEY, v.toFloat())?.apply()
        } catch (_: Throwable) {
            // 持久化失败静默忽略
        }
    }

    private fun _persistLoopMode(name: String) {
        try {
            _prefs?.edit()?.putString(LOOP_MODE_KEY, name)?.apply()
        } catch (_: Throwable) {
            // 持久化失败静默忽略
        }
    }

    private fun _persistDownloadedIds(ids: List<String>) {
        try {
            _prefs?.edit()?.putString(DOWNLOADED_IDS_KEY, anyToJsonElement(ids).toString())?.apply()
        } catch (_: Throwable) {
            // 持久化失败静默忽略
        }
    }

    // ── 引擎事件订阅（对齐 Dart `_subscribe`） ──

    private fun _subscribe(backend: PlayerBackend?) {
        // 取消旧订阅，避免后端切换后多个监听导致事件被重复处理
        _eventJob?.cancel()
        _eventJob = null
        if (backend == null) return
        _eventJob = viewModelScope.launch {
            backend.events.collect { event ->
                // 绝对索引变化 -> 切歌（更新当前曲目并触发时长/歌词加载）
                val idx = event["absoluteIndex"]
                if (idx is Int && _state.value.queue.isNotEmpty()) {
                    val q = _state.value.queue
                    val song = if (idx in q.indices) q[idx] else _state.value.currentSong
                    val songChanged = song?.id != _state.value.currentSong?.id
                    _state.value = _state.value.copyWith(
                        currentIndex = idx,
                        currentSong = song,
                        // 切歌先清空音质胶囊，等引擎探测/解析结果下发后再填充
                        audioInfo = if (songChanged) AudioInfo() else null,
                    )
                    if (songChanged) _onSongChanged(song)
                }

                // 随机模式下引擎提前定好的「下一首」：点「下一首」时用它做乐观 UI，
                // 保证界面切的歌和实际播的是同一首（此前两边各掷一次随机会不一致）
                val nextIdx = event["nextIndex"]
                if (nextIdx is Int) _shuffleNextHint = nextIdx

                val audioInfo = event["audioInfo"]
                if (audioInfo is AudioInfo && !audioInfo.isEmpty) {
                    _state.value = _state.value.copyWith(audioInfo = audioInfo)
                }

                val audioStatus = event["audioStatus"]
                if (audioStatus is AudioOutputStatus) {
                    _state.value = _state.value.copyWith(audioStatus = audioStatus)
                }

                val isPlaying = event["isPlaying"]
                if (isPlaying is Boolean) _state.value = _state.value.copyWith(isPlaying = isPlaying)

                // 进度 / 时长（后端 positionStream / durationStream 推送）—— 只动进度流
                val pos = event["position"]
                val dur = event["duration"]
                if (pos is Number || dur is Number) {
                    val prev = _progress.value
                    _progress.value = PlaybackProgress(
                        currentTime = if (pos is Number) pos.toDouble() else prev.currentTime,
                        // 后端真实时长优先；为 0 / 未就绪时保留元数据时长
                        duration = if (dur is Number && dur.toDouble() > 0) {
                            dur.toDouble()
                        } else {
                            prev.duration
                        },
                        bufferedTime = prev.bufferedTime,
                    )
                    // 播放现场落盘：位置每 5 秒记一次（进程被杀也不至于丢太多），
                    // 暂停/切歌/seek 另有即时写入。
                    if (pos is Number) {
                        _lastPositionMs = (pos.toDouble() * 1000).toLong().coerceAtLeast(0L)
                        // 恢复播放的续播位置：等播放器真的开始走（第一个进度事件）再 seek，
                        // 避免在 BUFFERING 里 seek 打断加载 → 停在正确位置却不出声。
                        val pendingSeek = _pendingSeekAfterStart
                        if (pendingSeek > 0) {
                            _pendingSeekAfterStart = 0
                            _backendCall { it.seekTo(pendingSeek / 1000.0) }
                        }
                        val now = System.currentTimeMillis()
                        if (now - _lastPositionWriteAt >= 5_000) {
                            _lastPositionWriteAt = now
                            _persistResumeState()
                        }
                    }
                }
            }
        }
    }

    /** 清理播放缓存（转发给引擎；未接线时返回 0） */
    suspend fun clearPlaybackCache(): Long = _backend?.clearPlaybackCache() ?: 0L

    /** 随机模式下引擎提前定好的下一首下标（`nextIndex` 事件）；null = 还没有 */
    private var _shuffleNextHint: Int? = null

    /** 引擎调用（fire-and-forget，异常静默，对齐 Dart 未 await 的调用点） */
    private fun _backendCall(block: suspend (PlayerBackend) -> Unit) {
        val b = _backend ?: return
        viewModelScope.launch {
            try {
                block(b)
            } catch (_: Throwable) {
                // 对齐 Dart：忽略引擎异步错误
            }
        }
    }

    /**
     * 当前曲目变化：设置时长（元数据兜底）+ 异步拉取歌词。
     *
     * 对齐 RN 版 AudioContext 中监听 `currentSong.id` 拉歌词的 effect，
     * 使任意切歌路径（点击 / 自动下一首 / 锁屏）都能拿到歌词。
     */
    private fun _onSongChanged(song: Song?) {
        if (song == null) return
        // 切歌把位置归零；时长先用元数据兜底（引擎 200ms 内会用真实值覆盖，
        // 所以这里拿不到元数据时长时保留上一个值，与原 `copyWith(duration = null)` 同义）
        val prev = _progress.value
        _progress.value = PlaybackProgress(
            currentTime = 0.0,
            duration = if (song.duration > 0) song.duration.toDouble() else prev.duration,
            bufferedTime = prev.bufferedTime,
        )
        _ensureLyric(song)
    }

    /**
     * 「这首歌还没有歌词就去拉一次」。
     *
     * 与 [_onSongChanged] 拆开是因为**续播恢复**也要用：那条路径不能把进度归零，
     * 但同样需要把歌词补回来 —— 否则重启后接着放，进度对、歌词区却是空的
     * （恢复态只重建了队列与位置，从来没走过切歌那条 effect）。
     */
    private fun _ensureLyric(song: Song) {
        // 空串或平台占位歌词（如 `[00:00.00]暂无歌词`）视为未获取，需要重新拉取
        val lyric = song.lyric
        val hasLyric = lyric != null && lyric.isNotEmpty() && !lyric.contains("暂无歌词")
        if (hasLyric || _lyricFetching.contains(song.id)) return
        _lyricFetching.add(song.id)
        // KKBOX 需活跃账号会话 sid 才能拉真实歌词（对齐 RN AudioContext）
        val kkboxSid = if (song.platform == MusicPlatform.kkbox) kkboxSidProvider?.invoke() else null
        viewModelScope.launch {
            try {
                val r = fetchLyric(song, kkboxSid, autoPickTranslate = true)
                if (!_disposed) {
                    updateSongLyric(song.id, r.lrc, r.tlyric, r.wordLyric, r.translateLangs, r.translateLang)
                }
            } catch (_: Throwable) {
                // 歌词拉取失败静默忽略（保留「暂无歌词」）
            } finally {
                _lyricFetching.remove(song.id)
            }
        }
    }

    // ── 状态迁移（逐条对齐 Dart） ──

    /**
     * 设置队列并从 startIndex 开播；整体重建 state，但**沿用当前循环模式**（切歌不该把模式打回顺序）。
     *
     * @param fromPlaylist 从歌单详情开播时传它，供全局 ⋮ 面板「跳转到歌单」用；其它入口留空。
     */
    fun setQueue(songs: List<Song>, startIndex: Int = 0, fromPlaylist: PlaylistDetailData? = null) {
        val mode = _state.value.loopMode
        // 换队列：随机下一首的提示作废（下标已经对不上了）
        _shuffleNextHint = null
        _state.value = PlayerState(
            queue = songs,
            currentIndex = startIndex,
            currentSong = if (songs.isEmpty()) null else songs[startIndex],
            isPlaying = true,
            volume = _state.value.volume,
            downloadedIds = _state.value.downloadedIds,
            isLyricsVisible = _state.value.isLyricsVisible,
            loopMode = mode,
            // 换队列才重置来源：非歌单入口传 null，旧的「跳转到歌单」自然消失
            queuePlaylist = fromPlaylist,
        )
        // 换队列 = 新曲目：进度归零（队列为空时也要清，否则界面还挂着上一首的时长）
        _progress.value = PlaybackProgress(
            currentTime = 0.0,
            duration = if (songs.isEmpty()) 0.0 else songs[startIndex].duration.toDouble(),
        )
        val items = songs.map { _toBackendItem(it) }
        val modeName = mode.name
        _backendCall { it.setQueue(items, startIndex, modeName) }
        _onSongChanged(_state.value.currentSong)
        // 换队列 = 播放现场变了：队列整份落盘 + 位置归零
        _backendQueueReady = true
        _pendingResumeMs = 0
        _lastPositionMs = 0
        // 队列整份落盘时把「来自哪个歌单」一起存 —— 否则重启后播放现场回来了，
        // ⋮ 面板里的「跳转到歌单」却没了（用户实测反馈）。
        com.annieplayer.annie_music.kt.data.PlayerResumeStore.saveQueue(
            songs,
            fromPlaylist?.let {
                com.annieplayer.annie_music.kt.data.PlayerResumeStore.ResumePlaylist(
                    id = it.id,
                    platform = it.platform,
                    name = it.name,
                    cover = it.cover,
                )
            },
        )
        _persistResumeState()
    }

    fun addToQueue(song: Song) {
        if (_state.value.queue.any { it.id == song.id }) return
        _state.value = _state.value.copyWith(queue = _state.value.queue + song)
    }

    /**
     * 从队列里摘掉第 [index] 项（队列抽屉的手动删除）。
     *
     * 只是 [removeSongsFromQueue] 的按位入口 —— 两处（手动删 / 删本地文件）**必须**走同一套
     * 逻辑，否则「队列抽屉删得掉、引擎不认」这类不同步会再出现一次。
     */
    fun removeFromQueue(index: Int) {
        val st = _state.value
        val song = st.queue.getOrNull(index) ?: return
        removeSongsFromQueue(listOf(song.id))
    }

    /**
     * 把若干首歌从播放队列里摘掉（删除本地文件时用）。
     *
     * 为什么要单独一个方法、而不复用 [removeFromQueue] 的单条实现：
     * 一次删一首时也要处理「删的正是当前播放」「队列里全是待删的」这些组合，
     * 而且三处状态必须一起改 ——
     * 1. App 层 `PlayerState.queue`（队列抽屉读它）；
     * 2. **引擎的自管队列**（`MediaPlayerBackend._queue`，走 [PlayerBackend.removeQueueItem]）——
     *    不同步的话「删完文件，下一首又转回那一首」；
     * 3. 落盘的播放现场（`PlayerResumeStore`）—— 不同步的话重启后队列里又长出这首歌。
     *
     * 语义（顺序本身就是正确性的一部分）：
     * - 待删的歌不在队列里 → 什么都不做，返回 0；
     * - 队列**全是**待删的 → 整队清空并停播；
     * - 否则**先摘掉「不是当前播放」的那些**，最后才处理当前曲：切走 → 再摘。
     *   反过来做会踩两个坑：`next()` 可能正好落到另一首待删的歌上；摘掉当前项后
     *   `_currentAbsIndex` 已经指到别处，再用旧下标删就会删错。
     *
     * @return 实际摘掉的条数
     */
    fun removeSongsFromQueue(songIds: Collection<String>): Int {
        val ids = songIds.filter { it.isNotBlank() }.toSet()
        if (ids.isEmpty()) return 0
        val st = _state.value
        val plan = planQueueRemoval(st.queue.map { it.id }, ids, st.currentIndex)
        if (plan.none) return 0

        // 整个队列都要摘：清空 + 停播（引擎 setQueue 对空队列直接 return，不会 prepare，
        // 所以这里显式 pause，别让正在响的那首继续放）
        if (plan.clearAll) {
            _backendCall { it.pause() }
            _state.value = st.copy(
                queue = emptyList(),
                currentIndex = 0,
                currentSong = null,
                isPlaying = false,
            )
            _persistQueueSnapshot()
            return st.queue.size
        }

        // 1) 先摘「不是当前播放」的（plan 已按降序给好，前面的下标不受影响）
        for (index in plan.removeFirst) _removeQueueEntry(index)

        // 2) 当前曲也在名单里 → 此时队列里已无别的待删项，可以安全地切走再摘
        if (plan.currentInIds) {
            val before = _state.value
            val wasPlaying = before.isPlaying
            val currentHit = before.queue.indexOfFirst { it.id in ids }
            if (currentHit >= 0) {
                next()
                // 原本暂停着：切歌只是把指针移开，不该突然出声
                if (!wasPlaying) {
                    _state.value = _state.value.copyWith(isPlaying = false)
                    _backendCall { it.pause() }
                }
                val remain = _state.value.queue.indexOfFirst { it.id in ids }
                if (remain >= 0) _removeQueueEntry(remain)
            }
        }

        _persistQueueSnapshot()
        return plan.hitCount
    }

    /** 摘掉队列第 [index] 项：App 状态 + 引擎自管队列，两处一起改 */
    private fun _removeQueueEntry(index: Int) {
        val st = _state.value
        if (index !in st.queue.indices) return
        val newQueue = st.queue.toMutableList().apply { removeAt(index) }
        val newIndex = indexAfterRemoval(st.currentIndex, index, newQueue.size)
        _state.value = st.copyWith(
            queue = newQueue,
            currentIndex = newIndex,
            currentSong = newQueue.getOrNull(newIndex) ?: st.currentSong,
        )
        _backendCall { it.removeQueueItem(index) }
    }

    /** 把当前队列整份写进「播放现场」（含来源歌单），重启后不会把已删的歌恢复出来 */
    private fun _persistQueueSnapshot() {
        val st = _state.value
        if (st.queue.isEmpty()) {
            // saveQueue 对空列表早退，删光最后一首时必须显式清文件
            com.annieplayer.annie_music.kt.data.PlayerResumeStore.clearQueue()
            _persistResumeState()
            return
        }
        com.annieplayer.annie_music.kt.data.PlayerResumeStore.saveQueue(
            st.queue,
            st.queuePlaylist?.let {
                com.annieplayer.annie_music.kt.data.PlayerResumeStore.ResumePlaylist(
                    id = it.id,
                    platform = it.platform,
                    name = it.name,
                    cover = it.cover,
                )
            },
        )
        _persistResumeState()
    }

    /**
     * 拖拽排序：把队列里 [from] 的歌挪到 [to]。
     *
     * 只重排队列 + 修正当前下标，后端走 [PlayerBackend.moveQueueItem]
     * （ExoPlayer 的 `moveMediaItem`）—— 当前这首不会被掐断、进度不丢。
     *
     * 与播放模式的关系：
     * - 顺序 / 单曲：下一首按**画面上的顺序**走，拖完立即生效。
     * - 随机：「下一首」是引擎随机掷的，拖拽不改变掷骰结果，只改列表顺序；
     *   这里把 `_shuffleNextHint` 作废，让引擎重掷（否则它会指向拖拽后错位的那首歌）。
     */
    fun moveInQueue(from: Int, to: Int) {
        val st = _state.value
        if (from == to) return
        if (from !in st.queue.indices || to !in st.queue.indices) return
        val newQueue = st.queue.toMutableList().apply { add(to, removeAt(from)) }
        val newIndex = indexAfterMove(st.currentIndex, from, to)
        _shuffleNextHint = null
        _state.value = st.copyWith(queue = newQueue, currentIndex = newIndex)
        _backendCall { it.moveQueueItem(from, to) }
        // 顺序是「播放现场」的一部分：不落盘的话，重启恢复的是旧顺序，
        // 当前歌还在但下标已经对不上。
        _persistQueueSnapshot()
    }

    /**
     * 下一首：**先把 UI 切过去**（封面/标题/歌手/进度条/歌词立刻跟着点的那一下走），
     * 音频在后台解析 + 加载，出声比信息慢是正常的，信息不需要等出声。
     */
    fun next() {
        val st = _state.value
        if (st.queue.isEmpty()) return
        val nextIndex: Int = when (st.loopMode) {
            // 随机：认引擎提前定好的那首（没有提示就原地不动，等引擎的 absoluteIndex 事件纠正）
            LoopMode.shuffle -> _shuffleNextHint ?: st.currentIndex
            LoopMode.single -> st.currentIndex
            LoopMode.sequential -> (st.currentIndex + 1) % st.queue.size
        }
        val nextSong = st.queue[nextIndex]
        _state.value = st.copyWith(currentIndex = nextIndex, currentSong = nextSong, isPlaying = true)
        _onSongChanged(nextSong)
        _backendCall { it.skipNext() }
        _lastPositionMs = 0
        _persistResumeState()
    }

    fun previous() {
        val st = _state.value
        if (st.queue.isEmpty()) return
        val prevIndex: Int = when (st.loopMode) {
            LoopMode.shuffle -> _shuffleNextHint ?: st.currentIndex
            else -> (st.currentIndex - 1 + st.queue.size) % st.queue.size
        }
        val prevSong = st.queue[prevIndex]
        _state.value = st.copyWith(currentIndex = prevIndex, currentSong = prevSong, isPlaying = true)
        _onSongChanged(prevSong)
        _backendCall { it.skipPrevious() }
        _lastPositionMs = 0
        _persistResumeState()
    }

    fun seekTo(index: Int) {
        val st = _state.value
        if (index < 0 || index >= st.queue.size) return
        val song = st.queue[index]
        _state.value = st.copyWith(currentIndex = index, currentSong = song)
        _onSongChanged(song)
        _lastPositionMs = 0
        _persistResumeState()
    }

    fun playIndex(index: Int) {
        val st = _state.value
        if (index < 0 || index >= st.queue.size) return
        val song = st.queue[index]
        // 同 [next]：UI 立刻切，音频随后加载
        _state.value = st.copyWith(currentIndex = index, currentSong = song, isPlaying = true)
        _onSongChanged(song)
        _backendCall { it.skipTo(index) }
        _lastPositionMs = 0
        _persistResumeState()
    }

    /**
     * 播放 / 暂停。
     *
     * 恢复态（上次退出留下的队列、引擎还没装载）下的第一次「播放」走 [_startRestoredPlayback]：
     * 必须 setQueue → seekTo(退出位置) → play()，否则会从头播。
     * 引擎还没注入时**不吞掉这次点击**：记下待播位置，`setBackend` 注入后自动播。
     */
    fun togglePlay() {
        val st = _state.value
        if (st.isPlaying) {
            _state.value = st.copyWith(isPlaying = false)
            _backendCall { it.pause() }
            _persistResumeState()
            return
        }
        val resumeMs = _pendingResumeMs
        _pendingResumeMs = 0
        _state.value = st.copyWith(isPlaying = true)
        // 恢复态判定双保险：标志位 + 「有待续播位置」。只要有一项成立就走恢复分支，
        // 免得标志位被意外复位时引擎里没队列却去调 play()（点了没声音）。
        if (!_backendQueueReady || resumeMs > 0) {
            if (_backend == null) {
                _pendingRestorePlayMs = resumeMs
            } else {
                _startRestoredPlayback(resumeMs)
            }
            return
        }
        _backendCall { it.play() }
    }

    fun setLoopMode(mode: LoopMode) {
        _state.value = _state.value.copyWith(loopMode = mode)
        _backendCall { it.setAppLoopMode(mode.name) }
        _persistLoopMode(mode.name)
    }

    fun updateSongPlayUrl(id: String, playUrl: String) {
        val cs = _state.value.currentSong
        _state.value = _state.value.copyWith(
            queue = _state.value.queue.map { s -> if (s.id == id) s.copyWith(playUrl = playUrl) else s },
            currentSong = if (cs != null && cs.id == id) cs.copyWith(playUrl = playUrl) else cs,
        )
    }

    /**
     * 写入歌词。[tlyric] / [wordLyric] **覆盖式**写入（可清空）；[translateLangs] 为 null 时保留原值。
     *
     * 不动 [Song.lyricTranslateLang]（切换译文语言走 [setSongTranslateLang]）。
     */
    fun updateSongLyric(
        id: String,
        lrc: String,
        tlyric: String? = null,
        wordLyric: String? = null,
        translateLangs: List<String>? = null,
        translateLang: String? = null,
    ) {
        fun apply(s: Song): Song = s.copy(
            lyric = lrc,
            tlyric = tlyric,
            wordLyric = wordLyric,
            lyricTranslateLangs = translateLangs ?: s.lyricTranslateLangs,
            lyricTranslateLang = translateLang ?: s.lyricTranslateLang,
        )
        val cs = _state.value.currentSong
        _state.value = _state.value.copyWith(
            queue = _state.value.queue.map { if (it.id == id) apply(it) else it },
            currentSong = if (cs != null && cs.id == id) apply(cs) else cs,
        )
    }

    /** 切换当前曲目的译文语言并按新语言重取歌词（译文随语言变化，必须重取） */
    fun setSongTranslateLang(lang: String) {
        val song = _state.value.currentSong ?: return
        val tagged = song.copy(lyricTranslateLang = lang, tlyric = null)
        val cs = _state.value.currentSong
        _state.value = _state.value.copyWith(
            queue = _state.value.queue.map { if (it.id == song.id) tagged else it },
            currentSong = if (cs != null && cs.id == song.id) tagged else cs,
        )
        viewModelScope.launch {
            try {
                val sid = if (tagged.platform == MusicPlatform.kkbox) kkboxSidProvider?.invoke() else null
                val r = fetchLyric(tagged, sid, autoPickTranslate = true)
                if (!_disposed) {
                    updateSongLyric(tagged.id, r.lrc, r.tlyric, r.wordLyric, r.translateLangs, r.translateLang)
                }
            } catch (_: Throwable) {
                // 重取失败：保留已有原文歌词
            }
        }
    }

    fun setCurrentTime(t: Double) {
        _progress.value = _progress.value.copy(currentTime = t)
    }

    fun setDuration(d: Double) {
        _progress.value = _progress.value.copy(duration = d)
    }

    fun syncProgress(currentTime: Double, duration: Double, bufferedTime: Double) {
        _progress.value = PlaybackProgress(
            currentTime = currentTime,
            duration = duration,
            bufferedTime = bufferedTime,
        )
    }

    fun setVolume(v: Double) {
        _state.value = _state.value.copyWith(volume = v)
        _persistVolume(v)
        // 对齐 Flutter `player_screen.dart`（同时调用后端 setVolume）；仅改 state 只是动 UI，
        // 不会改变实际发声音量。
        _backendCall { it.setVolume(v) }
    }

    /** 按秒 seek（对齐 Flutter `playerBackendProvider.state?.seekTo(t)`） */
    fun seekToSeconds(seconds: Double) {
        if (seconds.isNaN() || seconds.isInfinite() || seconds < 0) return
        _progress.value = _progress.value.copy(currentTime = seconds)
        _backendCall { it.seekTo(seconds) }
        // 拖动进度条后立刻记下位置：这时候退出/被杀，下次应该从这里续
        _lastPositionMs = (seconds * 1000).toLong().coerceAtLeast(0L)
        _persistResumeState()
    }

    fun setLyricsVisible(visible: Boolean) {
        _state.value = _state.value.copyWith(isLyricsVisible = visible)
    }

    fun addDownloadedId(id: String) {
        if (_state.value.downloadedIds.contains(id)) return
        val next = _state.value.downloadedIds + id
        _state.value = _state.value.copyWith(downloadedIds = next)
        _persistDownloadedIds(next)
    }

    companion object {
        /** 持久化 key（与 Dart 完全一致） */
        private const val VOLUME_KEY = "annie_player_volume"
        private const val LOOP_MODE_KEY = "annie_player_loopMode"
        private const val DOWNLOADED_IDS_KEY = "annie_player_downloadedIds"

        /** 与 Kkbox/Qobuz 状态共用一个 SharedPreferences 文件（对应 Dart 单一 prefs store） */
        private const val PREFS_NAME = "annie_shared_prefs"

        private var _prefs: SharedPreferences? = null

        /** 由 Application / Activity 调用以开启持久化（对齐 CacheService.init 模式） */
        fun init(context: Context) {
            _prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }
}
