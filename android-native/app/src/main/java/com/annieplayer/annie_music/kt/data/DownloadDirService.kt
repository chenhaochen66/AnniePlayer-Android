package com.annieplayer.annie_music.kt.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 下载目录设置（1:1 移植 Flutter 数据层 download_dir_service.dart，自用版：支持自定义保存位置）。
 *
 * 保存路径优先级：
 * 1. 用户通过 SAF 选择的目录（解析为真实文件路径并持久化）
 * 2. 默认：应用私有目录 downloads/（无额外权限要求）
 *
 * 与 Dart 的差异（有意为之）：Dart 通过 SharedPreferences 异步读写；这里内存镜像
 * 始终可用，另在 [init] 之后额外持久化，模式与网络层 CacheService 的 `init(context)`
 * 保持一致。未调用 [init] 时退化为纯内存，读写/清除语义不变。
 */
object DownloadDirService {
    /** SharedPreferences 键名（对齐 Dart `_kDownloadDirKey`） */
    private const val _kDownloadDirKey = "download_dir_path"

    /** 本工程独立的 preferences 文件名（对齐 CacheService 的专用文件名策略） */
    private const val PREFS_NAME = "annie_download_dir"

    /** 内存镜像：无论是否 init 都可用，保证读写语义不依赖初始化时机 */
    private var _memory: String? = null

    private var prefs: SharedPreferences? = null

    /** 由 Application / Activity 调用以开启持久化。 */
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 用户选择的下载目录真实路径（如 /storage/emulated/0/Download）；null 表示未设置。 */
    fun getDownloadDirPath(): String? =
        prefs?.getString(_kDownloadDirKey, null) ?: _memory

    /** 保存用户选择的下载目录路径。 */
    fun setDownloadDirPath(path: String) {
        _memory = path
        prefs?.edit()?.putString(_kDownloadDirKey, path)?.apply()
    }

    /** 清除自定义下载目录，恢复默认。 */
    fun clearDownloadDir() {
        _memory = null
        prefs?.edit()?.remove(_kDownloadDirKey)?.apply()
    }
}
