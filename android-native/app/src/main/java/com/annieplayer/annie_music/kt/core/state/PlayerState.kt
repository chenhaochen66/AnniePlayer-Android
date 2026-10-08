package com.annieplayer.annie_music.kt.core.state

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.annieplayer.annie_music.kt.data.fetchLyric
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

/** 播放状态（对齐 Dart `PlayerState`） */
data class PlayerState(
    val queue: List<Song> = emptyList(),
    val currentIndex: Int = 0,
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val loopMode: LoopMode = LoopMode.sequential,
    val currentTime: Double = 0.0,
    val duration: Double = 0.0,
    val bufferedTime: Double = 0.0,
    val volume: Double = 0.8,
    val downloadedIds: List<String> = emptyList(),
    val isLyricsVisible: Boolean = false,
) {
    /**
     * 局部更新（对齐 Dart `PlayerState.copyWith`）。
     *
     * 注意：与 Dart 语义一致，可空字段传 `null` **不会**清空原值（`?: this.xxx` 回退）。
     * 例如 `currentSong = null` 保留旧曲目 —— 清空只能通过构造新的 [PlayerState] 实现。
     */
    fun copyWith(
        queue: List<Song>? = null,
        currentIndex: Int? = null,
        currentSong: Song? = null,
        isPlaying: Boolean? = null,
        loopMode: LoopMode? = null,
        currentTime: Double? = null,
        duration: Double? = null,
        bufferedTime: Double? = null,
        volume: Double? = null,
        downloadedIds: List<String>? = null,
        isLyricsVisible: Boolean? = null,
    ): PlayerState = PlayerState(
        queue = queue ?: this.queue,
        currentIndex = currentIndex ?: this.currentIndex,
        currentSong = currentSong ?: this.currentSong,
        isPlaying = isPlaying ?: this.isPlaying,
        loopMode = loopMode ?: this.loopMode,
        currentTime = currentTime ?: this.currentTime,
        duration = duration ?: this.duration,
        bufferedTime = bufferedTime ?: this.bufferedTime,
        volume = volume ?: this.volume,
        downloadedIds = downloadedIds ?: this.downloadedIds,
        isLyricsVisible = isLyricsVisible ?: this.isLyricsVisible,
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
    // 只有 qobuz 需要把直链带进队列（详情页/搜索拿到的直链是唯一来源）。
    // 其余平台一律留空 → 交给引擎按平台解析，否则 Song 上任何残留的旧直链都会
    // 让引擎「直接用、不请求播放接口」（真机抓包：网易/QQ 完全没发请求）。
    if (song.platform == MusicPlatform.qobuz && any.playUrl.isNotEmpty()) m["url"] = any.playUrl
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

    init {
        _loadPersisted()
    }

    /**
     * 注入音频引擎（对齐 Dart `playerBackendProvider` 被 main 异步赋值的行为）。
     * 传 null 表示卸载；切换时会取消旧事件订阅。
     */
    fun setBackend(backend: PlayerBackend?) {
        _backend = backend
        _subscribe(backend)
    }

    override fun onCleared() {
        super.onCleared()
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
                    _state.value = _state.value.copyWith(currentIndex = idx, currentSong = song)
                    if (songChanged) _onSongChanged(song)
                }

                val isPlaying = event["isPlaying"]
                if (isPlaying is Boolean) _state.value = _state.value.copyWith(isPlaying = isPlaying)

                // 进度 / 时长（后端 positionStream / durationStream 推送）
                val pos = event["position"]
                val dur = event["duration"]
                if (pos is Number || dur is Number) {
                    _state.value = _state.value.copyWith(
                        currentTime = if (pos is Number) pos.toDouble() else null,
                        // 后端真实时长优先；为 0 / 未就绪时保留元数据时长
                        duration = if (dur is Number && dur.toDouble() > 0) dur.toDouble() else null,
                    )
                }
            }
        }
    }

    /** 引擎调用（fire-and-forget，异常静默，对齐 Dart 未 await 的调用点） */
    /** 清理播放缓存（转发给引擎；未接线时返回 0） */
    suspend fun clearPlaybackCache(): Long = _backend?.clearPlaybackCache() ?: 0L

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
        _state.value = _state.value.copyWith(
            currentTime = 0.0,
            duration = if (song.duration > 0) song.duration.toDouble() else null,
        )
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

    /** 设置队列并从 startIndex 开播；整体重建 state，但**沿用当前循环模式**（切歌不该把模式打回顺序） */
    fun setQueue(songs: List<Song>, startIndex: Int = 0) {
        val mode = _state.value.loopMode
        _state.value = PlayerState(
            queue = songs,
            currentIndex = startIndex,
            currentSong = if (songs.isEmpty()) null else songs[startIndex],
            isPlaying = true,
            volume = _state.value.volume,
            downloadedIds = _state.value.downloadedIds,
            isLyricsVisible = _state.value.isLyricsVisible,
            duration = if (songs.isEmpty()) 0.0 else songs[startIndex].duration.toDouble(),
            loopMode = mode,
        )
        val items = songs.map { _toBackendItem(it) }
        val modeName = mode.name
        _backendCall { it.setQueue(items, startIndex, modeName) }
        _onSongChanged(_state.value.currentSong)
    }

    fun addToQueue(song: Song) {
        if (_state.value.queue.any { it.id == song.id }) return
        _state.value = _state.value.copyWith(queue = _state.value.queue + song)
    }

    fun removeFromQueue(index: Int) {
        val st = _state.value
        val newQueue = st.queue.toMutableList().apply { removeAt(index) }
        val newIndex = if (index < st.currentIndex) st.currentIndex - 1 else st.currentIndex
        val clamped = if (newIndex < 0) 0 else newIndex
        _state.value = st.copyWith(
            queue = newQueue,
            currentIndex = clamped,
            // 注意：copyWith 无法清空 currentSong（Dart 同此语义），空队列时保留旧值
            currentSong = if (newQueue.isEmpty()) null else newQueue[if (clamped < newQueue.size) clamped else newQueue.size - 1],
        )
    }

    fun next() {
        val st = _state.value
        if (st.queue.isEmpty()) return
        val nextIndex: Int = when (st.loopMode) {
            LoopMode.shuffle -> (System.currentTimeMillis() % st.queue.size).toInt()
            LoopMode.single -> st.currentIndex
            LoopMode.sequential -> (st.currentIndex + 1) % st.queue.size
        }
        val nextSong = st.queue[nextIndex]
        _state.value = st.copyWith(currentIndex = nextIndex, currentSong = nextSong, isPlaying = true)
        _onSongChanged(nextSong)
        _backendCall { it.skipNext() }
    }

    fun previous() {
        val st = _state.value
        if (st.queue.isEmpty()) return
        val prevIndex: Int = if (st.loopMode == LoopMode.shuffle) {
            (System.currentTimeMillis() % st.queue.size).toInt()
        } else {
            (st.currentIndex - 1 + st.queue.size) % st.queue.size
        }
        val prevSong = st.queue[prevIndex]
        _state.value = st.copyWith(currentIndex = prevIndex, currentSong = prevSong, isPlaying = true)
        _onSongChanged(prevSong)
        _backendCall { it.skipPrevious() }
    }

    fun seekTo(index: Int) {
        val st = _state.value
        if (index < 0 || index >= st.queue.size) return
        val song = st.queue[index]
        _state.value = st.copyWith(currentIndex = index, currentSong = song)
        _onSongChanged(song)
    }

    fun playIndex(index: Int) {
        val st = _state.value
        if (index < 0 || index >= st.queue.size) return
        val song = st.queue[index]
        _state.value = st.copyWith(currentIndex = index, currentSong = song, isPlaying = true)
        _onSongChanged(song)
        _backendCall { it.skipTo(index) }
    }

    fun togglePlay() {
        _state.value = _state.value.copyWith(isPlaying = !_state.value.isPlaying)
        if (_state.value.isPlaying) {
            _backendCall { it.play() }
        } else {
            _backendCall { it.pause() }
        }
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
        _state.value = _state.value.copyWith(currentTime = t)
    }

    fun setDuration(d: Double) {
        _state.value = _state.value.copyWith(duration = d)
    }

    fun syncProgress(currentTime: Double, duration: Double, bufferedTime: Double) {
        _state.value = _state.value.copyWith(
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
        _state.value = _state.value.copyWith(currentTime = seconds)
        _backendCall { it.seekTo(seconds) }
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
