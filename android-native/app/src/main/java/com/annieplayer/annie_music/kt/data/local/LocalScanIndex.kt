package com.annieplayer.annie_music.kt.data.local

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 目录级扫描索引（**枚举加速**）。
 *
 * 背景：全量扫 2000 首实测「枚举 0.85s + probe 1.3s」。probe 已经是 TagLib + 头解析
 * （每首 0.5ms 级），再压只能动枚举 —— 而枚举的耗时全在 2000 次 `listFiles()`。
 *
 * 做法：按**目录**缓存「mtime + 子目录列表 + 该目录下的音频文件列表」。
 * 下次扫描时先比目录的 `lastModified()`：
 * - 没变 → 直接复用缓存的子目录与文件列表，**跳过 listFiles**（只递归已知子目录）；
 * - 变了 → 只重扫这一个目录（改动的目录才会 listFiles）。
 *
 * 稳定曲库里 2000 首分布在一二十个目录，于是枚举退化成「几十次 stat + 一次遍历」，
 * 实测从 ~850ms 掉到 ~100ms 量级。
 *
 * 正确性靠两点兜底：
 * 1. 缓存里的每个文件路径在返回前都做一次 `isFile` 校验（stat，比 listFiles 便宜得多），
 *    文件被删/改名不会残留；
 * 2. 目录 `mtime` 变化（新增/删除/重命名文件）必然触发该目录重扫。
 *
 * 已知边界：某些 FUSE / 云盘挂载对 `lastModified()` 的更新不严格（可能不随内容变化刷新）。
 * 这类存储上缓存会退化成"每次都认为没变"，最坏情况是漏掉新增文件 —— 所以用户点「重扫」
 * （`force = true`）时**完全绕过本索引**，走真实 listFiles。
 */
internal object LocalScanIndex {

    private const val TAG = "LocalScanIndex"
    private const val FILE_NAME = "scan_dirs.json"
    private const val VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 一个目录的缓存条目 */
    @Serializable
    data class DirEntry(
        val path: String,
        /** 目录自身的 `lastModified()`；变了就重扫这个目录 */
        val mtime: Long,
        /** 直接子目录的绝对路径（跳过黑名单后剩下的） */
        val subdirs: List<String> = emptyList(),
        /** 该目录下（不含子目录）的音频文件绝对路径 */
        val files: List<String> = emptyList(),
    )

    @Serializable
    private data class IndexFile(
        val version: Int = VERSION,
        val dirs: Map<String, DirEntry> = emptyMap(),
    )

    private var appContext: android.content.Context? = null

    @Volatile
    private var cache: Map<String, DirEntry>? = null

    fun init(context: android.content.Context) {
        appContext = context.applicationContext
    }

    private fun file(): File? = appContext?.filesDir?.let { File(it, "local_library/$FILE_NAME") }

    /** 读索引（内存副本；文件损坏/缺失返回空表，退化为全量遍历） */
    fun load(): Map<String, DirEntry> {
        cache?.let { return it }
        val f = file()
        if (f == null || !f.isFile) {
            cache = emptyMap()
            return emptyMap()
        }
        val parsed = try {
            val idx = json.decodeFromString(IndexFile.serializer(), f.readText())
            if (idx.version > VERSION) emptyMap() else idx.dirs
        } catch (t: Throwable) {
            Log.w(TAG, "[scan] read failed: ${t.message}")
            emptyMap()
        }
        cache = parsed
        return parsed
    }

    /** 整份覆盖写（先写临时文件再 rename） */
    fun save(dirs: Map<String, DirEntry>) {
        cache = dirs
        val f = file() ?: return
        try {
            f.parentFile?.mkdirs()
            val payload = json.encodeToString(IndexFile.serializer(), IndexFile(dirs = dirs))
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(payload)
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "[scan] write failed: ${t.message}")
        }
    }

    /** 清空索引（强制重扫时用） */
    fun clear() {
        cache = null
        runCatching { file()?.delete() }
    }
}
