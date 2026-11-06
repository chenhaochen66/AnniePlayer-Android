package com.annieplayer.annie_music.kt.core.platform

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Android 媒体库扫描（1:1 移植 Flutter `lib/core/platform/media_store_scan.dart`，
 * 对齐 RN 版 `modules/media-scanner`）。
 *
 * Flutter 版是 MethodChannel 薄封装，真正的原生实现在 `android/.../MainActivity.kt`（Flutter 工程已从本分支移除）；
 * 本工程直接内联该原生实现（不再需要方法通道），返回形状与 Dart 暴露的完全一致：
 * - [scanFileToMediaStore] 返回 `content://` URI 或 null；
 * - [isManageStorageGranted] 返回 Boolean；
 * - SAF 选目录 [createDownloadDirPicker] 回调真实路径或 null。
 *
 * 行为：
 * - Android 10+（Q+）：把文件插入 `MediaStore.Audio`（`RELATIVE_PATH=Music/AnnieMusic`，
 *   `IS_PENDING` 两段式提交），返回 `content://` URI；
 * - Android 9 及以下：复制到公共 `Music/AnnieMusic` 后执行 `MediaScannerConnection.scanFile`，
 *   返回 null（与 RN `MediaScannerModule` 行为一致）。
 *
 * Context 依赖：通过 [init] 注入 `applicationContext`（与
 * [com.annieplayer.annie_music.kt.data.network.CacheService] 的 `init(context)` 模式一致），
 * 用于 [scanFileToMediaStore] 的 ContentResolver。
 *
 * 说明：`media_store_scan.dart` **不包含**「列出/查询已下载音乐」的 API，本文件因此不新增此类方法。
 * 权限前提（MANAGE_EXTERNAL_STORAGE 等）由宿主应用的 AndroidManifest 声明，本文件只做运行时检测/引导。
 */
object MediaStoreScan {

    /**
     * 写入 MediaStore / 公共 Music 目录时的单次缓冲。
     *
     * 这两条路都是**整首全量复制**（私有临时文件 → 系统音乐库），默认 8KB 缓冲下一首
     * 100MB 的无损要一万两千多次 read/write；256KB 降到四百次以内，复制耗时能压掉一截。
     */
    private const val COPY_BUFFER = 256 * 1024

    private var _appContext: Context? = null

    /** 由 Application / Activity 调用以绑定 ContentResolver 所需的上下文。 */
    fun init(context: Context) {
        _appContext = context.applicationContext
    }

    /**
     * 把 [path] 指向的音频文件插入系统媒体库。
     *
     * Android 10+ 成功时返回插入后的 `content://` URI；文件不存在、未初始化、插入失败或
     * Android 9 及以下均返回 null（保持 Dart 返回形状）。
     */
    fun scanFileToMediaStore(path: String): String? {
        val ctx = _appContext ?: return null
        val file = File(path)
        if (!file.exists()) return null
        val displayName = file.name
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            _insertIntoMediaStore(ctx, file, displayName)
        } else {
            _copyToPublicMusicAndScan(ctx, file, displayName)
            null
        }
    }

    /**
     * 直接开一个「写进去就是成品」的落点（没有临时文件、没有二次复制）。
     *
     * - Android 10+：插一条 `IS_PENDING=1` 的 `MediaStore.Audio` 记录，返回它的输出流；
     *   [DownloadSink.commit] 把 `IS_PENDING` 置 0，[DownloadSink.abort] 删掉这条记录。
     * - Android 9 及以下：没有可写的 MediaStore，直接写公共 `Music/AnnieMusic`，
     *   提交时触发一次媒体扫描。
     *
     * 返回 null 表示未初始化或目录创建失败 —— 调用方按失败处理，不要静默继续。
     */
    fun openAudioSink(displayName: String): DownloadSink? {
        val ctx = _appContext ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return _pendingSink(ctx, displayName)
        return runCatching {
            @Suppress("DEPRECATION")
            val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val targetDir = File(musicDir, "AnnieMusic")
            if (!targetDir.exists()) targetDir.mkdirs()
            val target = File(targetDir, displayName)
            if (target.exists()) target.delete()
            LegacyMusicSink(ctx, target)
        }.getOrNull()
    }

    /**
     * Android 10+ 的 pending 条目落点。
     *
     * 回填走 `openFileDescriptor(uri, "rw")` 直接拿 fd 定位写，**不重开输出流**（重开会把
     * 已有内容截断）。
     */
    private fun _pendingSink(context: Context, displayName: String): DownloadSink? {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, _mimeTypeFor(displayName))
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/AnnieMusic")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        val stream = resolver.openOutputStream(uri) ?: run {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        return MediaStoreSink(context, uri, stream)
    }

    /** Android 9 及以下：写公共目录，提交后扫描入库。 */
    private class LegacyMusicSink(
        private val context: Context,
        file: File,
    ) : DownloadSink {
        private val inner = FileDownloadSink(file)

        override val output
            get() = inner.output

        override fun commit(): String {
            val path = inner.commit()
            MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
            return path
        }

        override fun abort() = inner.abort()

        override fun patch(offset: Long, bytes: ByteArray) = inner.patch(offset, bytes)
    }

    private class MediaStoreSink(
        private val context: Context,
        private val uri: Uri,
        private val stream: java.io.OutputStream,
    ) : DownloadSink {

        private var committed = false

        override val output
            get() = stream

        override fun commit(): String {
            runCatching { stream.flush() }
            runCatching { stream.close() }
            val done = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
            context.contentResolver.update(uri, done, null, null)
            committed = true
            return uri.toString()
        }

        override fun abort() {
            runCatching { stream.close() }
            if (committed) return
            runCatching { context.contentResolver.delete(uri, null, null) }
        }

        override fun patch(offset: Long, bytes: ByteArray) {
            runCatching { stream.flush() }
            val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
                ?: throw IllegalStateException("openFileDescriptor returned null")
            try {
                // 直接拿 fd 的 channel 定位写：另开一个 FileOutputStream 会截断已有内容
                val channel = FileOutputStream(pfd.fileDescriptor).channel
                channel.position(offset)
                channel.write(java.nio.ByteBuffer.wrap(bytes))
            } finally {
                runCatching { pfd.close() }
            }
        }
    }

    /** 是否已授予「所有文件访问」权限（MANAGE_EXTERNAL_STORAGE）。 */    fun isManageStorageGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            // Android 10 及以下无此权限概念：视为已授予（对齐 Dart/原生实现）
            true
        }

    /** 跳转系统设置页引导用户开启「所有文件访问」；已授权或低版本不做任何事。 */
    fun requestManageStorage(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                .setData(Uri.parse("package:" + activity.packageName))
            try {
                activity.startActivity(intent)
            } catch (_: Exception) {
                // 部分设备不支持直接跳转到本应用条目，退回通用页面
                activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    /**
     * 创建 SAF 选目录启动器：用户在系统界面选择目录后，回调其**真实文件系统路径**
     * （如 `/storage/emulated/0/Download`），取消或解析失败回调 null。
     *
     * 必须在宿主 Activity 进入 STARTED 之前（通常在 `onCreate`）调用并持有返回值，
     * 之后调用 `launcher.launch(null)` 触发选择（对齐 androidx ActivityResult 契约）。
     */
    fun createDownloadDirPicker(
        activity: ComponentActivity,
        onPicked: (String?) -> Unit,
    ): ActivityResultLauncher<Uri?> {
        return activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) {
                onPicked(null)
                return@registerForActivityResult
            }
            // 持久化授权（兜底；有 MANAGE_EXTERNAL_STORAGE 时路径可直接访问）
            try {
                activity.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (_: Exception) {
                // ignore
            }
            onPicked(resolveTreeToPath(uri))
        }
    }

    /** 把 SAF tree URI 解析为真实文件系统路径；解析失败返回 null。 */
    fun resolveTreeToPath(treeUri: Uri): String? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            // docId 形如 "primary:Download/Music" 或 "XXXX-XXXX:SomeDir"
            val sep = docId.indexOf(':')
            if (sep <= 0) return null
            val storageId = docId.substring(0, sep)
            val relPath = docId.substring(sep + 1)
            val rootDir: File = if (storageId == "primary") {
                Environment.getExternalStorageDirectory()
            } else {
                File("/storage/$storageId")
            }
            if (!rootDir.exists()) return null
            File(rootDir, relPath).absolutePath
        } catch (_: Exception) {
            null
        }
    }

    // ---------------- 原生实现细节 ----------------

    /**
     * 删掉 MediaStore 里指向 [path] 的音频记录（删文件时配套调用）。
     *
     * 只删索引记录、不碰文件：文件由调用方自己 `delete()`。顺序必须是**先删记录再删文件**
     * （记录里存着 `_data` 路径，文件先没了就查不到这一行了）。
     * 宿主已声明 `MANAGE_EXTERNAL_STORAGE`，直接按 `_data` 条件删除不会被 Recoverable 拦截；
     * 拿不到权限时静默失败 —— 系统媒体库里留一条打不开的条目，不影响本 App 的曲库。
     *
     * @return 删除的行数（失败返回 0）
     */
    fun deleteFromMediaStore(path: String): Int {
        val ctx = _appContext ?: return 0
        if (path.isBlank()) return 0
        return try {
            ctx.contentResolver.delete(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.Audio.Media.DATA} = ?",
                arrayOf(path),
            )
        } catch (_: Throwable) {
            0
        }
    }

    private fun _mimeTypeFor(displayName: String): String = when {
        displayName.endsWith(".flac", ignoreCase = true) -> "audio/flac"
        displayName.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
        displayName.endsWith(".m4a", ignoreCase = true) -> "audio/mp4"
        displayName.endsWith(".aac", ignoreCase = true) -> "audio/aac"
        displayName.endsWith(".wav", ignoreCase = true) -> "audio/wav"
        displayName.endsWith(".ogg", ignoreCase = true) -> "audio/ogg"
        else -> "audio/*"
    }

    /** Android 10+：两段式写入 MediaStore.Audio，成功返回 content URI。 */
    private fun _insertIntoMediaStore(context: Context, file: File, displayName: String): String? {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, _mimeTypeFor(displayName))
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/AnnieMusic")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null

        try {
            FileInputStream(file).use { input ->
                val output = resolver.openOutputStream(uri)
                if (output == null) throw IllegalStateException("openOutputStream returned null")
                output.use { out -> input.copyTo(out, COPY_BUFFER) }
            }
            val done = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (_: Exception) {
            // 回滚占位条目，避免残留 pending 记录
            try {
                resolver.delete(uri, null, null)
            } catch (_: Exception) {
                // ignore
            }
            return null
        }
        return uri.toString()
    }

    /** Android 9 及以下：复制到公共 Music/AnnieMusic 后触发媒体扫描（失败静默）。 */
    private fun _copyToPublicMusicAndScan(context: Context, file: File, displayName: String) {
        try {
            @Suppress("DEPRECATION")
            val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val targetDir = File(musicDir, "AnnieMusic")
            if (!targetDir.exists()) targetDir.mkdirs()
            val target = File(targetDir, displayName)
            if (target.exists()) target.delete()
            file.copyTo(target, overwrite = true, bufferSize = COPY_BUFFER)
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
        } catch (_: Exception) {
            // 与 RN 版一致：失败静默
        }
    }
}
