package com.annieplayer.annie_music.kt.data.local

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.Locale

/**
 * 本地音频枚举。
 *
 * 两条路径，按权限自动选择：
 *
 * 1. **「所有文件访问」已给**（`MANAGE_EXTERNAL_STORAGE`，本工程下载功能本来就要求它）：
 *    直接走文件系统。先扫已知音乐目录（`Music` / `Download` / `AnnieMusic` …），命中够多就
 *    不再全盘；否则从存储根做一次限深广度遍历（跳过 `Android/`、`DCIM/`、`tencent/` 等
 *    明显不含音乐的大目录）。这条路径不需要任何运行时权限弹窗，也不会被 MediaStore 的
 *    收录状态影响（刚下完还没被扫进库的文件也能立刻看到）。
 * 2. **只有 `READ_MEDIA_AUDIO`**：走 `MediaStore.Audio` 查询（`IS_MUSIC != 0`），
 *    只看索引里的文件，读不到未收录的新文件。
 *
 * 两条路径都只负责「有哪些文件」，元数据交给 [LocalTagProbe]。
 */
internal object LocalMediaScanner {

    /** 视为音频的扩展名（不含 mp4/3gp 这类多为视频的容器） */
    private val AUDIO_EXT = setOf(
        "mp3", "mp2", "mpga", "flac", "m4a", "m4b", "aac", "alac", "wav", "wave",
        "ogg", "oga", "opus", "ape", "wv", "tta", "wma", "mka", "aif", "aiff", "aifc",
        "dsf", "dff", "caf", "amr",
        // 这两个 MediaStore 不收录（系统没有 audio/ac3、audio/eac3 这类 mime 的音轨），
        // 只有走文件系统遍历才看得到 —— 少写一个就等于「这种格式明明能播却扫不出来」。
        "ac3", "eac3",
        "dts",
    )

    /** 优先扫的目录名（相对存储根）；这些目录里的子目录也会一起进 */
    private val PRIORITY_DIRS = listOf(
        "Music", "AnnieMusic", "Download", "Downloads", "Ringtones", "Notifications",
        "Alarms", "Podcasts", "Recordings", "Documents", "录音", "音乐", "网易云音乐", "QQ音乐",
    )

    /** 遍历时直接跳过的目录名（照片 / 视频 / 应用私有数据，含音乐的概率极低但体量极大） */
    private val SKIP_DIRS = setOf(
        "Android", "DCIM", "Pictures", "Movies", "LOST.DIR", ".thumbnails", ".trash",
        "tencent", "MicroMsg", "cache", "Cache", "obb", "data", "MIUI", "backups",
        "Telegram", "WeChat", "com.tencent.mm", "node_modules",
    )

    private const val MAX_DEPTH = 7
    private const val MAX_FILES = 50_000

    /** 「所有文件访问」是否已给 */
    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true

    /**
     * 枚举设备上的音频文件。返回去重后的文件列表（按路径排序，稳定）。
     *
     * @param onProgress 已遍历文件数（用于界面上的扫描进度；轻量，直接回调在工作线程）
     */
    fun scan(
        context: Context,
        force: Boolean = false,
        onProgress: (Int) -> Unit = {},
    ): List<File> {
        val fromFs = if (hasAllFilesAccess()) _scanFileSystem(force, onProgress) else emptyList()
        if (fromFs.isNotEmpty()) return fromFs
        // 没有「所有文件访问」，或文件系统路径上一个都没找到 → 退 MediaStore
        return _scanMediaStore(context)
    }

    // ── 文件系统遍历 ──

    /**
     * 遍历存储。**按目录 mtime 走增量**（见 [LocalScanIndex]）：
     * 目录没变就直接复用上次的子目录 + 音频列表，不 `listFiles`；
     * 只有内容变过的目录才会真正读目录。
     *
     * [force] = true（用户点「重扫」）时**完全绕过索引**，走真实遍历。
     */
    private fun _scanFileSystem(force: Boolean, onProgress: (Int) -> Unit): List<File> {
        val roots = _storageRoots()
        if (roots.isEmpty()) return emptyList()

        val index = if (force) {
            LocalScanIndex.clear()
            emptyMap()
        } else {
            LocalScanIndex.load()
        }
        val found = LinkedHashMap<String, File>()
        val newIndex = HashMap<String, LocalScanIndex.DirEntry>()
        var visited = 0

        fun walk(dir: File, depth: Int) {
            if (depth > MAX_DEPTH) return
            val path = dir.absolutePath
            val mtime = dir.lastModified()
            val cached = index[path]
            // 目录 mtime 没变 → 复用缓存，跳过 listFiles（这是整个加速的关键）
            if (cached != null && mtime != 0L && cached.mtime == mtime) {
                for (f in cached.files) {
                    if (found.size >= MAX_FILES) break
                    // 这里**故意不做 isFile 校验**：2000 次 stat 在 FUSE 上要 ~300ms，
                    // 而"文件是否还在"由后续 probe 兜底 —— 三层解析全失败时那条记录会被丢掉
                    // （见 LocalLibraryViewModel._probeAll）。
                    found[f] = File(f)
                }
                newIndex[path] = cached
                for (sub in cached.subdirs) walk(File(sub), depth + 1)
                return
            }
            val children = try {
                dir.listFiles()
            } catch (_: Throwable) {
                null
            } ?: return
            visited++
            onProgress(visited)
            val subdirs = ArrayList<String>()
            val files = ArrayList<String>()
            for (child in children) {
                val name = child.name
                if (name.startsWith(".")) continue
                if (child.isDirectory) {
                    if (name in SKIP_DIRS) continue
                    subdirs += child.absolutePath
                    walk(child, depth + 1)
                } else if (found.size < MAX_FILES && _isAudio(name)) {
                    files += child.absolutePath
                    found[child.absolutePath] = child
                }
            }
            newIndex[path] = LocalScanIndex.DirEntry(
                path = path,
                mtime = mtime,
                subdirs = subdirs,
                files = files,
            )
        }

        // 第一轮：已知音乐目录（存在几个扫几个）
        for (root in roots) {
            for (name in PRIORITY_DIRS) {
                val dir = File(root, name)
                if (dir.isDirectory) walk(dir, 0)
            }
            if (found.size >= 20) break
        }
        // 第二轮：命中太少 → 从存储根限深遍历
        if (found.size < 20) {
            for (root in roots) {
                walk(root, 0)
                if (found.size >= MAX_FILES) break
            }
        }
        LocalScanIndex.save(newIndex)
        return _sorted(found.values)
    }

    /** 可读的存储根（内部存储 + 已挂载的 SD / U 盘） */
    private fun _storageRoots(): List<File> {
        val list = ArrayList<File>(2)
        @Suppress("DEPRECATION")
        val primary = Environment.getExternalStorageDirectory()
        if (primary != null && primary.canRead()) list.add(primary)
        // 物理外置卡：/storage/XXXX-XXXX（跳过 emulated / self，它们在 primary 里已覆盖）
        val storage = File("/storage")
        storage.listFiles()?.forEach { dir ->
            val name = dir.name
            if (name == "emulated" || name == "self" || name.startsWith(".")) return@forEach
            if (!dir.isDirectory) return@forEach
            // 只要能列目录就是挂了卡
            if (runCatching { dir.list()?.isNotEmpty() == true }.getOrDefault(false)) list.add(dir)
        }
        return list.distinctBy { it.absolutePath }
    }

    // ── MediaStore 查询 ──

    private fun _scanMediaStore(context: Context): List<File> {
        val out = LinkedHashMap<String, File>()
        val projection = arrayOf(MediaStore.Audio.Media.DATA)
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                "${MediaStore.Audio.Media.TITLE} ASC",
            )?.use { c ->
                val dataIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                while (c.moveToNext() && out.size < MAX_FILES) {
                    // 只认有真实路径的条目：ffmpeg 直接吃绝对路径，省掉 content:// 的额外解析
                    val path = c.getString(dataIdx)
                    if (path.isNullOrBlank() || !_isAudio(path)) continue
                    val f = File(path)
                    if (f.isFile) out[f.absolutePath] = f
                }
            }
        } catch (_: Throwable) {
            // 无权限 / 查询失败：返回空列表，界面据此显示「未授权」态
        }
        return _sorted(out.values)
    }

    // ── 工具 ──

    private fun _isAudio(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return false
        return name.substring(dot + 1).lowercase(Locale.ROOT) in AUDIO_EXT
    }

    private fun _sorted(files: Collection<File>): List<File> =
        files.sortedBy { it.absolutePath.lowercase() }
}
