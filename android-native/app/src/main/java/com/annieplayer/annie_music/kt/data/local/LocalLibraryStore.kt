package com.annieplayer.annie_music.kt.data.local

import android.content.Context
import android.util.Log
import com.annieplayer.annie_music.kt.data.local.tag.AnnieTag
import com.annieplayer.annie_music.kt.data.local.tag.TagBag
import com.annieplayer.annie_music.kt.data.local.tag.WritePolicy
import com.annieplayer.annie_music.kt.data.local.tag.WriteResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 本地曲库的磁盘缓存（**唯一**碰文件系统的地方，除扫描与 ffmpeg 之外）。
 *
 * 三样东西，全部在 `filesDir/local_library/` 下：
 * - `index.json`：整份 [LocalTrackMeta] 列表（含 `mtime` / `size`）。下次启动先读它 →
 *   界面秒开；再扫目录比对 `mtime + size`，只有变过/新增的文件才重跑 ffprobe；
 * - `covers/<hash>.jpg`：ffmpeg 抽出来的内嵌封面（512px），一首一份；
 * - 歌词不落缓存：直接读同名 `.lrc` 或文件内嵌 `lyrics` tag（播放时才读，且量小）。
 *
 * 缓存损坏（写到一半被杀 / 手改坏了）一律当空处理，不抛异常 —— 曲库重新扫一遍就好，
 * 不该因为一个 json 打不开就让整页报错。
 */
internal object LocalLibraryStore {

    private const val TAG = "LocalLibraryStore"

    private const val DIR_NAME = "local_library"
    private const val INDEX_NAME = "index.json"
    private const val COVERS_NAME = "covers"

    /**
     * 缓存结构版本。
     *
     * 换读取引擎（ffprobe → MediaMetadataRetriever → TagLib）或字段语义变化时**必须 +1**：
     * 老缓存会整体作废，下次进入曲库自动全量重扫，避免拿旧引擎的残缺结果当有效缓存。
     *
     * v2：改用 TagLib 主路径（位深多数为 0，靠 enrich 补）
     * v3：加 `LocalHeaderProbe` 头解析，位深/采样率/声道在扫描阶段就齐了（v2 缓存里位深是 0，必须作废）
     * v4：修正 TagLib 时长单位（ms → 秒）；v3 缓存里的 duration 大了 1000 倍，必须重扫
     * v5：`.m4a` 改用头解析的 sample entry 判定真实编码（ALAC / AAC），v4 里全是 `m4a`
     * v6：DSD 改由头解析强制接管（真实 1bit 率 + `dsd64/128…` 档位名）；v5 里存的是 ffprobe 的
     *     解码器中间格式（`dsd_lsbf_planar` / 8bit / 源率÷8），必须重扫
     * v7：修 DSF 的 `fmt ` 内容起点（少算 4 字节 → 采样率读成了声道数，v6 里是 1Hz）
     * v8：`.m4a` 的 AAC 采样率改从 `esds → AudioSpecificConfig` 的 samplingFrequencyIndex 读。
     *     `mp4a` sample entry 的 samplerate 是 16.16 定点，整数部分只有 16 位（≤65535），
     *     96k/192k 根本装不下 —— 容器只能写 core rate（48k），所以 v7 里 96k AAC 全被记成 48000。
     * v9：`LocalHeaderProbe.fill` 的位深/采样率/声道改成**头解析优先**（原来只补空，
     *     TagLib 读 sample entry 得到的 48000/16 一直压着真值）。v8 里 96k AAC 仍是 48000、
     *     24bit ALAC 仍是 16bit，所以 v9 必须再重扫一次。
     * v10：ALAC magic cookie 跳过 full-box 的 4 字节 version/flags。
     *     v9 里 ALAC 一律读不到 cookie（bitDepth 读成 0 被判非法），退回 sample entry ——
     *     sample entry 写 24 的碰巧对，写 16 的 24bit ALAC 全被记成 16bit。
     * v11：字段不再兜底。头解析读不出就存 0（未知），不再回退 TagLib 的值；
     *     另外有损编码（AAC/MP3/AC3…）的位深强制 0 —— 它们没有位深概念，
     *     TagLib 给的 16/32 是容器默认值，v10 里会出现"AC3 32 Bit"这种假信息。
     * v12：头解析补齐 mp3 / ogg / wv / tta / ac3 / wma 六种容器，覆盖率达到 54/54。
     *     ffprobe 从此只在头解析不认识的罕见格式上触发，扫描不再被 100ms/首 拖住。
     * v13：字段集与下载打标（`FfmpegTagger`）对齐 —— 新增 `composer` / `isrc` / `version` /
     *     `copyright`。v12 缓存里这四项全空（老引擎没读），不 +1 会让「自己打标写进去的
     *     作曲 / ISRC / 版权」在详情页永远显示不出来。
     * v14：码率改用**文件实际平均码率**（`字节数×8÷时长`）修正「未压缩速率冒充码率」。
     *     v13 里 ALAC 24/48 全被记成 2304 kbps（= 48000×24×2），实际只有约 1937 kbps。
     *     详见 `LocalBitrate.kt`。
     */
    private const val INDEX_VERSION = 14

    @Serializable
    private data class IndexFile(
        val version: Int = INDEX_VERSION,
        val tracks: List<LocalTrackMeta> = emptyList(),
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 同目录外挂封面的常见文件名（freedb / MusicBrainz / 各类抓轨工具的共识命名） */
    private val SIDECAR_COVER_NAMES = listOf(
        "cover.jpg", "cover.jpeg", "cover.png",
        "folder.jpg", "folder.jpeg", "folder.png",
        "front.jpg", "front.jpeg", "front.png",
        "album.jpg", "album.png",
        "AlbumArt.jpg", "AlbumArtSmall.jpg",
    )

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        // 目录级扫描索引（枚举加速）
        LocalScanIndex.init(context)
    }

    fun rootDir(): File = File(appContext?.filesDir ?: File("/data/local/tmp"), DIR_NAME)

    val coversDir: File get() = File(rootDir(), COVERS_NAME)

    private val indexFile: File get() = File(rootDir(), INDEX_NAME)

    /** 某首歌的封面缓存文件（不管存不存在） */
    fun coverFileFor(path: String): File {
        val h = Integer.toHexString(path.hashCode())
        val tail = Integer.toHexString(path.takeLast(24).hashCode())
        return File(coversDir, "${h}_${tail}.jpg")
    }

    // ── index.json 读写 ──

    fun loadIndex(): Map<String, LocalTrackMeta> {
        val f = indexFile
        if (!f.isFile) return emptyMap()
        return try {
            val parsed = json.decodeFromString(IndexFile.serializer(), f.readText())
            // 版本不符 = 换过读取引擎 / 字段语义变了 → 当没有缓存，触发全量重扫
            if (parsed.version != INDEX_VERSION) return emptyMap()
            // 自愈：`covers/` 里明明有这张图，但记录里 `coverPath` 是 null
            // （历史版本的「按需抽封面」只更新了内存、没写回索引）→ 直接把路径补回。
            parsed.tracks.associateBy { it.path }.mapValues { (path, t) ->
                if (t.coverPath == null) {
                    val cover = coverFileFor(path)
                    if (cover.isFile && cover.length() > 0) t.copy(coverPath = cover.absolutePath) else t
                } else {
                    t
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "[cache] read failed: ${t.message}")
            emptyMap()
        }
    }

    /**
     * 索引的**唯一读取入口**：有内存副本就直接给，没有才读盘解析一次。
     *
     * 为什么不能各处直接 `loadIndex()`：进入曲库时「先亮缓存」和「扫盘判定 mtime 命中」
     * 会各读一次，同一份几千首的 JSON（几百 KB~1MB）就被解析了两遍；曲库每次重进都白烧
     * 几十毫秒 CPU。内存副本由 [saveIndex] / [updateMemoryIndex] 维护，与磁盘一致。
     */
    fun indexOrLoad(): Map<String, LocalTrackMeta> {
        inMemoryIndex?.let { return it }
        // 加锁是因为进曲库时「先亮缓存」与「扫盘判定命中」是两个并发协程：
        // 不加锁它们会同时看到 null，然后把同一份 JSON 各解析一遍。
        synchronized(indexLock) {
            inMemoryIndex?.let { return it }
            val loaded = loadIndex()
            inMemoryIndex = loaded
            return loaded
        }
    }

    /** 整份覆盖写（先写临时文件再 rename，避免半份 json 留在盘上） */
    fun saveIndex(tracks: Collection<LocalTrackMeta>): Boolean {
        if (tracks.isEmpty()) return false
        return try {
            rootDir().mkdirs()
            val tmp = File(rootDir(), "$INDEX_NAME.tmp")
            val list = tracks.toList()
            tmp.writeText(json.encodeToString(IndexFile.serializer(), IndexFile(tracks = list)))
            if (indexFile.exists()) indexFile.delete()
            if (!tmp.renameTo(indexFile)) return false
            // 落盘成功就顺手刷新内存副本：[trackOf] 是详情面板 / 播放页的唯一数据源，
            // 不同步的话「enrich 补的位深、按需抽到的封面」要等下次读盘才可见，
            // 表现就是「刚点开是空的，退出去再进来才有」。
            inMemoryIndex = list.associateBy { it.path }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "[cache] write failed: ${t.message}")
            false
        }
    }

    /**
     * 清掉**已不在曲库里**的歌的封面缓存。
     *
     * 关键：keep 集合按「路径 → 封面文件名」反查（`coverFileFor`），**不能**依赖
     * [LocalTrackMeta.coverPath] —— 封面是按需抽的，扫描/重扫刚结束时大多数记录的
     * `coverPath` 还是 null，拿它当 keep 会把好好的封面全删掉（这正是「重扫后封面丢失」的原因）。
     */
    fun pruneCachedCovers(tracks: Collection<LocalTrackMeta>) {
        val dir = coversDir
        if (!dir.isDirectory) return
        val keepNames = tracks.map { coverFileFor(it.path).name }.toHashSet()
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name !in keepNames) runCatching { f.delete() }
        }
    }

    /** 某个音频文件**已经抽好的**封面（没有则 null）；重扫时用它把封面路径捡回来 */
    fun existingCover(audioPath: String): String? {
        val f = coverFileFor(audioPath)
        return if (f.isFile && f.length() > 0) f.absolutePath else null
    }

    // ── 用户偏好（各列表的排序方式 + 升降序），与 index.json 分开存，删缓存不会丢偏好 ──

    private const val PREFS_NAME = "local_library_prefs"

    /** 排序偏好的分页键：主页歌曲 + 三个分组列表 */
    const val PAGE_SONGS = "songs"
    const val PAGE_ARTISTS = "artists"
    const val PAGE_ALBUMS = "albums"
    const val PAGE_FOLDERS = "folders"

    private fun prefs(): android.content.SharedPreferences? =
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun sortModeKey(page: String) = "sort_$page"

    private fun sortAscKey(page: String) = "sort_${page}_asc"

    /** 读某页的排序模式名（枚举项名或短标签），没有返回 null（调用方用自己的默认值） */
    fun loadSortMode(page: String): String? = prefs()?.getString(sortModeKey(page), null)

    fun saveSortMode(page: String, mode: String) {
        runCatching { prefs()?.edit()?.putString(sortModeKey(page), mode)?.apply() }
    }

    /** 读某页的升降序；没存过返回 [default] */
    fun loadSortAscending(page: String, default: Boolean): Boolean =
        prefs()?.getBoolean(sortAscKey(page), default) ?: default

    fun saveSortAscending(page: String, ascending: Boolean) {
        runCatching { prefs()?.edit()?.putBoolean(sortAscKey(page), ascending)?.apply() }
    }

    // ── 删除歌曲的选项（记住上次选择；存在偏好文件里，清曲库缓存不会重置） ──

    private const val KEY_DELETE_LOCAL_FILE = "delete_local_file"

    /**
     * 删除本地歌曲时是否**连音频文件一起删**。
     *
     * 默认 `true`（用户点了删除就是不想再看到这首歌），并记住上一次的选择 ——
     * 取消勾选后再删一首，下次弹出来仍是没勾的状态，不会跳回默认。
     */
    fun loadDeleteLocalFile(): Boolean = prefs()?.getBoolean(KEY_DELETE_LOCAL_FILE, true) ?: true

    fun saveDeleteLocalFile(value: Boolean) {
        runCatching { prefs()?.edit()?.putBoolean(KEY_DELETE_LOCAL_FILE, value)?.apply() }
    }

    /** 曲库主页的排序（枚举形态） */
    fun loadSortPreference(): LocalSort? = loadSortMode(PAGE_SONGS)?.let { name ->
        LocalSort.entries.firstOrNull { it.name == name }
    }

    fun saveSortPreference(sort: LocalSort) = saveSortMode(PAGE_SONGS, sort.name)

    // ── 最近播放（最多 100 条，持久化） ──

    /**
     * 一条播放记录。
     *
     * @param path 文件绝对路径
     * @param at 最后一次播放时间（毫秒；次数相同时用它排前后）
     * @param count 累计播放次数（列表行首显示的就是它）
     */
    @Serializable
    data class RecentPlay(val path: String, val at: Long, val count: Int = 1)

    @Serializable
    private data class RecentFile(
        val version: Int = RECENT_VERSION,
        val items: List<RecentPlay> = emptyList(),
    )

    private const val RECENT_NAME = "recent.json"
    private const val RECENT_VERSION = 2

    /** 最近播放上限：超过就丢最旧的 */
    const val RECENT_LIMIT = 100

    private val recentFile: File get() = File(rootDir(), RECENT_NAME)

    private val recentLock = Any()

    /** 内存副本（列表页每帧都要按顺序映射，不能反复读盘） */
    @Volatile
    private var recentCache: List<RecentPlay>? = null

    /** 读最近播放：**按播放次数从高到低**（次数相同按最近播放时间倒序），最多 [RECENT_LIMIT] 条 */
    fun loadRecentPlays(): List<RecentPlay> {
        recentCache?.let { return it }
        val f = recentFile
        if (!f.isFile) {
            recentCache = emptyList()
            return emptyList()
        }
        val parsed = try {
            val file = json.decodeFromString(RecentFile.serializer(), f.readText())
            // 只拒绝「比当前更新」的格式；旧格式照读（count 有默认值）
            if (file.version > RECENT_VERSION) emptyList() else file.items
        } catch (_: Throwable) {
            emptyList()
        }
        val sorted = parsed
            .sortedWith(compareByDescending<RecentPlay> { it.count }.thenByDescending { it.at })
            .take(RECENT_LIMIT)
        recentCache = sorted
        return sorted
    }

    /**
     * 记录一次播放：已有记录 **次数 +1** 并刷新时间；新记录 count = 1。
     * 超过 [RECENT_LIMIT] 条时淘汰**次数最少、最久没播**的那条。
     *
     * 写入很小（≤100 条 ≈ 10KB），直接同步落盘 —— 调用点在播放器的 setSource 之后，
     * 异步反而会出现「刚播完就杀进程 → 记录丢失」。
     */
    fun recordPlay(path: String) {
        if (path.isBlank()) return
        synchronized(recentLock) {
            val now = System.currentTimeMillis()
            val current = loadRecentPlays()
            val existing = current.firstOrNull { it.path == path }
            val updated = if (existing != null) {
                current.map {
                    if (it.path == path) it.copy(at = now, count = it.count + 1) else it
                }
            } else {
                current + RecentPlay(path, now, 1)
            }.sortedWith(compareByDescending<RecentPlay> { it.count }.thenByDescending { it.at })
                .take(RECENT_LIMIT)
            recentCache = updated
            _writeRecent(updated)
        }
    }

    /**
     * 删掉一条最近播放记录（删歌时同步调用）。
     *
     * 不清的话，「最近播放」与详情面板的播放次数里会留着一条指向已删文件的死记录 ——
     * 点进去就是一首放不出来的歌。
     */
    fun removeRecentPlay(path: String) {
        if (path.isBlank()) return
        synchronized(recentLock) {
            val current = loadRecentPlays()
            val updated = current.filterNot { it.path == path }
            if (updated.size == current.size) return
            recentCache = updated
            _writeRecent(updated)
        }
    }

    /** 最近播放写盘（先写临时文件再 rename，避免半份 json） */
    private fun _writeRecent(items: List<RecentPlay>) {
        try {
            rootDir().mkdirs()
            val tmp = File(rootDir(), "$RECENT_NAME.tmp")
            tmp.writeText(
                json.encodeToString(RecentFile.serializer(), RecentFile(items = items)),
            )
            if (recentFile.exists()) recentFile.delete()
            tmp.renameTo(recentFile)
        } catch (t: Throwable) {
            Log.w(TAG, "[recent] write failed: ${t.message}")
        }
    }

    /** 清空最近播放（目前没有 UI 入口，留给以后） */
    fun clearRecentPlays() {
        synchronized(recentLock) {
            recentCache = emptyList()
            runCatching { recentFile.delete() }
        }
    }

    // ── 歌词 ──

    /**
     * 读本地歌词：**同名 `.lrc` 优先**（用户自己放翻译/逐字文件时以它为准），
     * 之后依次是 `AnnieTag`（纯 Kotlin）→ TagLib（native）→ ffprobe。
     *
     * ## 为什么内嵌歌词不再只依赖 ffprobe
     *
     * 原先这里直接 `return LocalTagProbe.embeddedLyric(path)` —— **内嵌歌词只有 ffprobe 一条路**。
     * 那条路要过 JNI 和整套 libavformat，只要 ffmpeg 侧有任何变动（本项目刚把 libav\*.so
     * 换成了自编裁剪版），歌词就会静默消失，而且失败是被 catch 吞掉的，连日志都没有。
     *
     * 可本地标签解析根本不需要 ffmpeg：`AnnieTag` 本来就覆盖
     * flac / mp3 / m4a / ogg / opus / ape / wv / dsf / dff / wav / aiff 的注释块，
     * 扫描阶段 `LocalTagLibProbe` 也早已把 `LYRICS` 读进 `LocalTrackMeta.lyric`。
     *
     * 现在的顺序按「依赖多少」排：纯 Kotlin → native TagLib → JNI ffprobe 兜底。
     * 都拿不到返回 null（播放页显示「暂无歌词」）。
     */
    fun readLyric(path: String): String? {
        val audio = File(path)
        val base = audio.absolutePath.substringBeforeLast('.')
        for (ext in listOf("lrc", "LRC", "Lrc", "txt")) {
            val f = File("$base.$ext")
            if (!f.isFile) continue
            val text = runCatching { f.readText() }.getOrNull()
            if (!text.isNullOrBlank()) return text.trim()
        }
        // 1) AnnieTag：纯 Kotlin，无 JNI，格式覆盖最全
        runCatching { AnnieTag.readLyric(audio) }
            .getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        // 2) TagLib（native）：AnnieTag 认不出容器时兜底
        runCatching { LocalTagLibProbe.probe(path)?.lyric }
            .getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        // 3) ffprobe：最后一条路（要 libavformat 的 JNI 链路可用）
        return LocalTagProbe.embeddedLyric(path)
    }

    /**
     * 按「标题 + 艺术家」在已建索引里反查本地文件的歌词。
     *
     * 用途：**已下载的歌**（platform 仍是云端平台、播放走本地文件）的歌词本地优先 ——
     * 播放方案的阶段 2 第 3 项要求「本地优先、API 兜底」，下载文件名规则是固定的
     * `{标题} - {艺术家}.ext`，扫进曲库后就是普通本地文件，这里按名字反查即可，
     * 不需要额外维护一份 `songId → path` 的持久化映射。
     *
     * 只在索引已经建过（用户进过一次本地曲库 / 读过缓存）时才有结果，
     * 没索引就返回 null，调用方照常走 API。
     */
    fun lyricForDownloadedSong(title: String, artist: String): String? {
        if (title.isBlank()) return null
        val index = indexOrLoad()
        if (index.isEmpty()) return null
        val hit = index.values.firstOrNull { t ->
            t.title.equals(title, ignoreCase = true) &&
                (artist.isBlank() || t.artist.contains(artist, ignoreCase = true))
        } ?: return null
        return readLyric(hit.path)?.takeIf { it.isNotBlank() }
    }

    /** 内存里的索引副本（[indexOrLoad] 的缓存，`lyricForDownloadedSong` / `trackOf` 每次都要查） */
    @Volatile
    private var inMemoryIndex: Map<String, LocalTrackMeta>? = null

    private val indexLock = Any()

    /**
     * 按绝对路径取曲库里的记录（本地文件播放时用它给播放页提供编码/位深/采样率）。
     *
     * 比现场跑 ffprobe 更准也更快：扫描阶段已经把 TagLib 的 tag 和头解析的
     * `codec`（`flac` / `alac` / `aac`…）+ 位深 + 采样率算好并落盘了。
     */
    fun trackOf(path: String): LocalTrackMeta? {
        val index = indexOrLoad()
        return index[path]
    }

    /** 曲库索引更新后同步内存副本（VM 扫完会调） */
    fun updateMemoryIndex(tracks: Collection<LocalTrackMeta>) {
        inMemoryIndex = tracks.associateBy { it.path }
    }

    /**
     * 把标签写进文件（本地曲库「编辑标签 / 写歌词」的唯一入口）。
     *
     * 写用 [AnnieTag]：能原地就原地（FLAC 复用 PADDING、MP3 复用旧 ID3 区、MP4 只碰 moov），
     * **音频数据不搬**。写完立刻用 TagLib 重读一遍落回索引 —— 文件里的值才是唯一事实来源，
     * 不能拿「我写进去的」当真相（第三方播放器、其他工具都可能改过）。
     *
     * @return 写入结果；成功时索引已同步。
     */
    fun writeTags(path: String, bag: TagBag): WriteResult {
        val file = File(path)
        if (!file.isFile) return WriteResult.fail("", "文件不存在")
        val result = AnnieTag.write(file, bag, WritePolicy.PreferInPlace)
        if (!result.ok) return result

        val fresh = runCatching { LocalTagLibProbe.probe(path) }.getOrNull()
        if (fresh != null) {
            val current = indexOrLoad().toMutableMap()
            // coverPath 是「已抽出来的封面缓存」，重读 tag 不会带上它，要手动保留
            // withTrueBitrate：打标后重读也不能把 ALAC 的假码率（未压缩速率）写回去
            current[path] = fresh.copy(coverPath = current[path]?.coverPath ?: fresh.coverPath)
                .withTrueBitrate()
            updateMemoryIndex(current.values)
            saveIndex(current.values)
        }
        return result
    }

    /** 该文件是否有可用歌词（列表上打「有歌词」标记用；只查 .lrc，不跑 ffprobe） */
    fun hasSidecarLyric(path: String): Boolean {
        val base = path.substringBeforeLast('.')
        return listOf("lrc", "LRC", "Lrc").any { File("$base.$it").isFile }
    }

    /**
     * 同目录的**外挂封面**（播放方案阶段 2 第 3 项：内嵌优先 → 否则同目录 `cover.jpg`）。
     *
     * 返回原图路径**不复制**：同一张专辑的十几首歌共用一张 `cover.jpg`，
     * 每首复制一份到 `covers/` 只会白占几百 KB × N。UI 侧统一用 `file://` 加载。
     */
    fun sidecarCover(audioPath: String): File? {
        val dir = File(audioPath).parentFile ?: return null
        // 先按常见名精确找（大小写敏感的设备上最快）
        for (name in SIDECAR_COVER_NAMES) {
            val f = File(dir, name)
            if (f.isFile && f.length() > 0) return f
        }
        // 再按前缀不区分大小写兜一遍（Cover.JPG / FOLDER.PNG…）
        val files = dir.listFiles() ?: return null
        for (f in files) {
            if (!f.isFile || f.length() <= 0) continue
            val n = f.name.lowercase()
            val hit = n.startsWith("cover.") || n.startsWith("folder.") ||
                n.startsWith("front.") || n.startsWith("albumart.")
            if (hit && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp"))) {
                return f
            }
        }
        return null
    }

    /** 调试用：缓存目录体积 */
    fun cacheSizeBytes(): Long {
        var total = 0L
        val dir = rootDir()
        if (!dir.isDirectory) return 0L
        dir.walkTopDown().forEach { f -> if (f.isFile) total += f.length() }
        return total
    }

    /** 清空曲库缓存（index + covers） */
    fun clearAll() {
        runCatching { indexFile.delete() }
        runCatching { coversDir.deleteRecursively() }
        // 内存副本必须一起清：清完缓存后 [indexOrLoad] 若还拿着旧副本，
        // 曲库会显示一批「磁盘上已经不存在」的记录。
        inMemoryIndex = null
    }
}
