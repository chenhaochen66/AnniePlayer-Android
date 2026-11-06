package com.annieplayer.annie_music.kt.data

import android.content.Context
import android.util.Log
import com.annieplayer.annie_music.kt.data.model.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 播放现场的持久化（**重启后恢复上次播放**：队列 + 当前曲 + 进度）。
 *
 * 拆成两个文件，是为了让「切歌 / 进度」这类高频写入足够便宜：
 *
 * | 文件 | 内容 | 写入时机 |
 * |---|---|---|
 * | `player_queue.json` | 整个队列（[Song] 列表） | 只在 `setQueue` 时（换队列） |
 * | `player_state.json` | `index + positionMs` | 切歌、暂停、seek、每 5 秒进度节流、`onStop` |
 *
 * 队列可能有几千首（≈ 1MB），所以**绝不能**每次进度更新都重写它；而只写 index+position
 * 的那个文件只有几十字节，5 秒一次完全不心疼。
 *
 * 恢复时的两条硬约束：
 * 1. **不信任流媒体直链**：QQ/网易/酷狗/酷我/Qobuz/Apple 的 url 都是一次性的（带签名或
 *    一次性端口），跨进程复用必然失败 → 恢复时清空 `playUrl`，让引擎重新解析
 *    （缓存被清掉的情况也一并覆盖：反正都会重新请求）。
 * 2. **本地文件保留 `file://`**：本地没有"解析"这一步，路径就是地址。
 */
internal object PlayerResumeStore {

    private const val TAG = "PlayerResumeStore"
    private const val QUEUE_FILE = "player_queue.json"
    private const val STATE_FILE = "player_state.json"
    private const val VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * 队列来源歌单的轻量存档。
     *
     * 只存「重启后还能跳回那个歌单」所必需的四个字段 —— 不存整个 `PlaylistDetailData`
     * （那个在 `core.state`，且曲目可能上万首，落盘没意义）。
     */
    @Serializable
    data class ResumePlaylist(
        val id: String,
        /** 枚举名（`qq` / `netease` / `kuwo`…），恢复时按名字回查 */
        val platform: String,
        val name: String? = null,
        val cover: String? = null,
    )

    @Serializable
    private data class QueueFile(
        val version: Int = VERSION,
        val songs: List<Song> = emptyList(),
        /** 这份队列来自哪个歌单（非歌单入口为 null，旧存档没有这个键也按 null 处理） */
        val playlist: ResumePlaylist? = null,
    )

    @Serializable
    private data class StateFile(
        val version: Int = VERSION,
        val index: Int = 0,
        val positionMs: Long = 0L,
        val savedAt: Long = 0L,
    )

    /** 恢复出来的播放现场 */
    data class Snapshot(
        val songs: List<Song>,
        val index: Int,
        val positionMs: Long,
        /** 队列来源歌单；null = 上次是从搜索 / 专辑 / 歌手等入口开播的 */
        val playlist: ResumePlaylist? = null,
    )

    private var appContext: Context? = null

    private val lock = Any()

    /**
     * 队列写盘走单线程：队列可能有几千首（≈1MB JSON 序列化 + 写盘），
     * 而 `setQueue` 是在 UI 线程调用的 —— 同步写会明显卡一下。
     * `saveState`（几十字节）保持同步，保证"退到后台/被杀"那一刀来得及。
     */
    private val queueWriter = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "player-resume-writer").apply { isDaemon = true }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun dir(): File? = appContext?.filesDir

    private fun queueFile(): File? = dir()?.let { File(it, QUEUE_FILE) }

    private fun stateFile(): File? = dir()?.let { File(it, STATE_FILE) }

    /**
     * 队列变化时调用（`setQueue`）—— 异步写，不阻塞 UI。
     *
     * [playlist] 一并落盘：重启后要能恢复「跳转到歌单」，否则播放现场回来了、
     * 但那行入口消失（用户实测反馈过）。
     */
    fun saveQueue(songs: List<Song>, playlist: ResumePlaylist? = null) {
        if (songs.isEmpty()) return
        val f = queueFile() ?: return
        val payload = try {
            json.encodeToString(
                QueueFile.serializer(),
                QueueFile(songs = songs, playlist = playlist),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "[resume] queue encode failed: ${t.message}")
            return
        }
        queueWriter.execute {
            synchronized(lock) {
                try {
                    writeAtomically(f, payload)
                } catch (t: Throwable) {
                    Log.w(TAG, "[resume] queue write failed: ${t.message}")
                }
            }
        }
    }

    /**
     * 清空播放现场里的队列（队列被整队删空时用）。
     *
     * **必须单独一个方法**：[saveQueue] 对空列表是早退的（它服务于「有队列才写」的常规路径），
     * 所以删光最后一首时不能靠 `saveQueue(emptyList())` —— 那样旧文件还在，
     * 重启后已删的歌会整条队列冒回来。
     */
    fun clearQueue() {
        val f = queueFile() ?: return
        queueWriter.execute {
            synchronized(lock) {
                runCatching { f.delete() }
            }
        }
    }

    /** 进度 / 当前下标变化时调用（高频，文件很小） */
    fun saveState(index: Int, positionMs: Long) {
        val f = stateFile() ?: return
        synchronized(lock) {
            try {
                writeAtomically(
                    f,
                    json.encodeToString(
                        StateFile.serializer(),
                        StateFile(
                            index = index,
                            positionMs = positionMs.coerceAtLeast(0L),
                            savedAt = System.currentTimeMillis(),
                        ),
                    ),
                )
            } catch (t: Throwable) {
                Log.w(TAG, "[resume] state write failed: ${t.message}")
            }
        }
    }

    /** 读回现场；队列为空 / 文件缺失 / 损坏都返回 null（当作没有上次播放） */
    fun load(): Snapshot? {
        val qf = queueFile() ?: return null
        if (!qf.isFile) return null
        var savedPlaylist: ResumePlaylist? = null
        val queue = try {
            val parsed = json.decodeFromString(QueueFile.serializer(), qf.readText())
            if (parsed.version > VERSION) return null
            savedPlaylist = parsed.playlist
            parsed.songs
        } catch (_: Throwable) {
            null
        } ?: return null
        if (queue.isEmpty()) return null

        val state = try {
            stateFile()?.takeIf { it.isFile }?.let {
                json.decodeFromString(StateFile.serializer(), it.readText())
            }
        } catch (_: Throwable) {
            null
        }
        val index = (state?.index ?: 0).coerceIn(0, queue.lastIndex)
        return Snapshot(queue, index, state?.positionMs ?: 0L, savedPlaylist)
    }

    fun clear() {
        synchronized(lock) {
            runCatching { queueFile()?.delete() }
            runCatching { stateFile()?.delete() }
        }
    }

    /** 先写临时文件再 rename，避免进程被杀时留下半份 json */
    private fun writeAtomically(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }
}
