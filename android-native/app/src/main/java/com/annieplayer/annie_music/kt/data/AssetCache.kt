package com.annieplayer.annie_music.kt.data

import android.content.Context
import coil.imageLoader
import com.annieplayer.annie_music.kt.core.audio.PLAYBACK_CACHE_DIR_NAME
import com.annieplayer.annie_music.kt.data.network.cacheService
import com.annieplayer.annie_music.kt.data.network.globalOkHttp
import com.annieplayer.annie_music.kt.data.sources.kkbox.KkboxPlayServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 封面/音频文件缓存（1:1 移植 Flutter 数据层 asset_cache.dart，对齐 RN 版 services/assetProxy.ts）。
 *
 * 行为对齐点：
 * - 封面：网易云 URL 自动补 `param=800y800`；命中磁盘直接返回本地路径，否则下载，失败回退原 URL。
 * - 音频：文件名 `audio_` + 清洗后的 songId，扩展名按 URL 是否含 `.mp3` 取 `.mp3` / `.mp4`。
 * - 体积统计：[getFileCacheSize] = 缓存目录体积 + [cacheService] 的 API 缓存体积。
 * - 清理：[clearAllCache] 删除私有下载目录与临时目录全部内容后清空 API 缓存；[clearAssetCache] 只删 assets 子目录。
 *
 * 与 Dart 的差异（有意为之）：
 * - Dart 用 path_provider 的临时目录；这里通过 [init] 解析 `context.cacheDir`，与
 *   [com.annieplayer.annie_music.kt.data.network.CacheService] 的 `init(context)` 模式一致。
 * - 内存镜像 `_memory` 无论是否 init 都可用；未 init 时退化为 JVM 临时目录 + 内存镜像，
 *   保证 API 在初始化前仍可工作（语义对齐 CacheService 的内存优先策略）。
 * - 原 Dart 文件只有「命中即用 + 体积统计 + 清理」，并无 TTL/淘汰逻辑；此处逐行保持一致，不额外引入。
 */
object AssetCache {
    /** Chrome UA（与 Flutter `_chromeUa` 完全一致） */
    private const val _chromeUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** 缓存根目录；[init] 之前为 null，退化为临时目录 + 内存镜像 */
    private var _cacheDir: File? = null

    /** Application Context（清 Coil 磁盘缓存要用它的 ImageLoader）；[init] 之后有效 */
    private var _appContext: Context? = null

    /** 内存镜像：无论是否 init 都可用，保证缓存语义不依赖初始化时机 */
    private val _memory: ConcurrentHashMap<String, ByteArray> = ConcurrentHashMap()

    /** 由 Application / Activity 调用以解析应用缓存目录。 */
    fun init(context: Context) {
        _appContext = context.applicationContext
        _cacheDir = context.cacheDir
    }

    /**
     * Coil 磁盘缓存的目录名（Coil 的默认值 `cacheDir/image_cache`）。
     *
     * 只用来**统计体积**和「清全部」时跳过它 —— 清理必须走 [clearCoilDiskCache]。
     */
    private const val CoilCacheDirName = "image_cache"

    /** 缓存根目录；未 [init] 时回退 JVM 临时目录，保证 API 在 init 前可工作。 */
    private fun _baseDir(): File =
        _cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: ".")

    /** 资源子目录（assets），不存在则创建（对齐 Dart `_assetDir`）。 */
    private fun _assetDir(): File {
        val dir = File(_baseDir(), "assets")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 网易云封面补 800y800 参数（对齐 Dart `_normalizeCoverUrl`）。 */
    private fun _normalizeCoverUrl(url: String): String {
        if (url.contains("music.126.net") && !url.contains("param=")) {
            return url + (if (url.contains("?")) "&" else "?") + "param=800y800"
        }
        return url
    }

    /** 下载并写入磁盘（UA 覆盖；失败返回 false）。 */
    private suspend fun _downloadToFile(url: String, target: File): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", _chromeUa)
                    .build()
                globalOkHttp.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext false
                    val body = resp.body ?: return@withContext false
                    target.parentFile?.mkdirs()
                    body.byteStream().use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    true
                }
            } catch (_: Throwable) {
                false
            }
        }

    /** 命中磁盘/内存返回 true；否则下载并写入内存镜像后返回结果。 */
    private suspend fun _ensureCached(url: String, file: File): Boolean {
        if (file.exists()) return true
        val mirrored = _memory[file.name]
        if (mirrored != null) {
            file.parentFile?.mkdirs()
            file.writeBytes(mirrored)
            return file.exists()
        }
        if (!_downloadToFile(url, file)) return false
        try {
            _memory[file.name] = file.readBytes()
        } catch (_: Throwable) {
            // 镜像写入失败不影响磁盘缓存命中
        }
        return true
    }

    /** 缓存封面，返回本地路径；失败返回原 URL（对齐 Dart `fetchAndCacheCover`）。 */
    suspend fun fetchAndCacheCover(url: String): String {
        if (url.isEmpty()) return ""
        val dlUrl = _normalizeCoverUrl(url)
        val lastSegment = dlUrl.split("/").lastOrNull() ?: ""
        val base = lastSegment.split("?")[0]
        val filename = if (base.isEmpty()) "${System.currentTimeMillis()}.jpg" else base
        val file = File(_assetDir(), filename)
        return try {
            if (_ensureCached(dlUrl, file) && file.exists()) file.path else url
        } catch (_: Throwable) {
            url
        }
    }

    /** 缓存音频文件，返回本地路径；失败抛异常（对齐 Dart `fetchAndCacheAudio`）。 */
    suspend fun fetchAndCacheAudio(url: String, songId: String): String {
        val filename = "audio_" + songId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val ext = if (url.contains(".mp3")) ".mp3" else ".mp4"
        val file = File(_assetDir(), "$filename$ext")
        if (!_ensureCached(url, file) || !file.exists()) {
            throw IllegalStateException("Download failed")
        }
        return file.path
    }

    /** 递归统计目录体积（对齐 Dart `_totalDirSize`）。 */
    private fun _totalDirSize(dir: File): Long {
        return try {
            if (!dir.exists()) return 0L
            var total = 0L
            val stack = ArrayDeque<File>()
            stack.addLast(dir)
            while (stack.isNotEmpty()) {
                val current = stack.removeLast()
                val children = current.listFiles() ?: continue
                for (child in children) {
                    if (child.isDirectory) {
                        stack.addLast(child)
                    } else if (child.isFile) {
                        total += child.length()
                    }
                }
            }
            total
        } catch (_: Throwable) {
            0L
        }
    }

    /** 文件缓存 + API 缓存总大小（对齐 Dart `getFileCacheSize`）。 */
    suspend fun getFileCacheSize(): Long =
        _totalDirSize(_baseDir()) + cacheService.getSize().toLong()

    // ── 「清除缓存」弹窗：分类体积 + 选择性清理 ──

    /** 播放缓存目录（Media3 流式缓存 `SimpleCache`） */
    private fun _playbackDir(): File = File(_baseDir(), PLAYBACK_CACHE_DIR_NAME)

    /** 封面/图片缓存目录：本层 `assets`（封面/试听）+ Coil 的 `image_cache` */
    private fun _coverDirs(): List<File> =
        listOf(File(_baseDir(), "assets"), File(_baseDir(), CoilCacheDirName))

    /**
     * 清 Coil 的磁盘缓存 —— **必须走它的 API，不能删目录**。
     *
     * `ImageLoader` 单例在内存里握着 `DiskLruCache` 和它的 journal 文件。从外部
     * `deleteRecursively()` 掉 `image_cache` 之后 journal 也没了，后续写入会持续失败、
     * 读取一直 miss，而界面看不出任何错误（只是封面一直空白/转圈），
     * 只有杀掉进程重建 ImageLoader 才恢复。
     *
     * `DiskCache.clear()` 会清空条目并重置 journal，目录与后续写入都由 Coil 自己维护。
     *
     * @return 是否真的清了（未 init / Coil 不可用时为 false，此时按目录体积统计释放量）
     */
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    private fun clearCoilDiskCache(): Boolean {
        val ctx = _appContext ?: return false
        return runCatching {
            ctx.imageLoader.diskCache?.clear()
            true
        }.getOrDefault(false)
    }

    /** 私有下载目录 */
    private fun _downloadsDir(): File = File(_baseDir(), "downloads")

    /**
     * KKBOX 高音质解密缓存目录（`cacheDir/kkbox_play`）。
     *
     * 这一份**不是** Media3 的流式缓存：它是把 kkdrm 整首解出来落的 FLAC/M4A，
     * 一首 20~40MB，实测能堆到 700MB+。它既不在 [CacheCategory.PLAYBACK] 的目录里，
     * 也不能走 Media3 的 `Cache` API 删 —— 必须单列一类，否则「清除缓存」永远删不掉它。
     */
    private fun _kkboxDir(): File = File(_baseDir(), "kkbox_play")

    /**
     * 逐项统计可清理的缓存体积（供弹窗展示）。
     *
     * 注意「播放缓存」占的是 Media3 流式缓存，**无上限（不淘汰）**，所以边听边涨很凶，
     * 是「缓存涨得快」的主要原因；封面还会被 Coil 再缓存一份到 `image_cache`。
     */
    suspend fun cacheItems(): List<CacheItem> = withContext(Dispatchers.IO) {
        listOf(
            // 「播放缓存」= Media3 流式缓存 + KKBOX 解密缓存：对用户是同一件事（都是听歌产生的），
            // 分开列只会让人以为多了一项花钱的东西。
            CacheItem(CacheCategory.PLAYBACK, _totalDirSize(_playbackDir()) + _totalDirSize(_kkboxDir())),
            CacheItem(CacheCategory.COVER, _coverDirs().sumOf { _totalDirSize(it) }),
            CacheItem(CacheCategory.DOWNLOADS, _totalDirSize(_downloadsDir())),
            CacheItem(CacheCategory.API, cacheService.getSize().toLong()),
        )
    }

    /**
     * 清理指定分类。返回本次释放的字节数。
     *
     * [CacheCategory.PLAYBACK] 在这里**只清 KKBOX 解密目录**：Media3 那份目录正被 `SimpleCache`
     * 连索引一起占着，必须由 `PlayerBackend.clearPlaybackCache()` 走 Cache API 清。
     * 两者语义对用户是同一项，所以入口在调用方合成（见 `AppShell` 的清缓存回调）。
     */
    suspend fun clearCacheCategories(categories: Set<CacheCategory>): Long =
        withContext(Dispatchers.IO) {
            var freed = 0L
            if (CacheCategory.PLAYBACK in categories) {
                // 先把本地播放代理的会话关掉：它们正 RandomAccessFile 读着这些文件
                runCatching { KkboxPlayServer.stop() }
                val dir = _kkboxDir()
                freed += _totalDirSize(dir)
                try {
                    if (dir.exists()) dir.deleteRecursively()
                } catch (_: Throwable) {
                }
            }
            if (CacheCategory.COVER in categories) {
                // 本层自己的封面/试听目录：没有别人握着，直接删
                val assets = File(_baseDir(), "assets")
                freed += _totalDirSize(assets)
                try {
                    if (assets.exists()) assets.deleteRecursively()
                } catch (_: Throwable) {
                }
                // Coil 那份走 API（见 clearCoilDiskCache 的注释：删目录会让 journal 一起消失，
                // 之后封面一直加载不出来，只有重启进程才恢复）
                val coilBytes = _totalDirSize(File(_baseDir(), CoilCacheDirName))
                if (clearCoilDiskCache()) freed += coilBytes
                _memory.clear()
            }
            if (CacheCategory.DOWNLOADS in categories) {
                val dir = _downloadsDir()
                freed += _totalDirSize(dir)
                try {
                    if (dir.exists()) dir.deleteRecursively()
                } catch (_: Throwable) {
                }
            }
            if (CacheCategory.API in categories) {
                freed += cacheService.getSize().toLong()
                cacheService.clear()
            }
            freed
        }

    /** 清除全部缓存（对齐 Dart `clearAllCache`）。 */
    suspend fun clearAllCache() {
        // Coil 那份先走 API 清；下面整目录删除时跳过它（理由同 clearCoilDiskCache）
        clearCoilDiskCache()
        val base = _baseDir()
        // 私有下载目录显式先删（语义明确；即使不在 temp 根目录下也能覆盖）
        val downloads = File(base, "downloads")
        if (downloads.exists()) {
            try {
                downloads.deleteRecursively()
            } catch (_: Throwable) {
                // 与 Dart `catchError((_) => downloads)` 一致：忽略
            }
        }
        try {
            if (base.exists()) {
                val children = base.listFiles()
                if (children != null) {
                    for (entity in children) {
                        if (entity.name == CoilCacheDirName) continue
                        entity.deleteRecursively()
                    }
                }
            }
        } catch (_: Throwable) {
            // 与 Dart 一致：静默
        }
        cacheService.clear()
        _memory.clear()
    }

    /** 清除封面/资源缓存（对齐 Dart `clearAssetCache`）。 */
    fun clearAssetCache() {
        val dir = _assetDir()
        if (dir.exists()) dir.deleteRecursively()
        _memory.clear()
    }
}

/** 「清除缓存」弹窗里的缓存分类。 */
enum class CacheCategory {
    /** 播放缓存：Media3 流式缓存 + KKBOX 高音质解密缓存 */
    PLAYBACK,

    /** 封面/图片缓存（本层 assets + Coil image_cache） */
    COVER,

    /** 私有下载目录 */
    DOWNLOADS,

    /** 接口/歌词等 API 缓存 */
    API,
}

/** 分类 + 体积。 */
data class CacheItem(val category: CacheCategory, val bytes: Long)
