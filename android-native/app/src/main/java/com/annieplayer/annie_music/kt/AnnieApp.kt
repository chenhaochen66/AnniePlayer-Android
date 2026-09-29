package com.annieplayer.annie_music.kt

import android.app.Application
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用入口：安装全局未捕获异常处理器，把崩溃堆栈落盘，便于在没有 adb / 没有 logcat 的情况下定位闪退。
 *
 * 落盘位置（两份，任取其一）：
 * - 内部：`/data/data/com.annieplayer.annie_music.kt/files/crash/crash-<时间>.log`（root 可读）
 * - 外部：`Android/data/com.annieplayer.annie_music.kt/files/crash/crash-<时间>.log`（免 root 可读）
 *
 * 同时最多保留最近 [MAX_LOGS] 份，避免无限堆积。
 */
class AnnieApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashLog(thread, throwable)
            } catch (t: Throwable) {
                Log.e(TAG, "写崩溃日志失败", t)
            }
            // 交回默认处理器，保持系统行为（进程仍会退出）
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashLog(thread: Thread, throwable: Throwable) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())

        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))

        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            "?"
        }

        val header = buildString {
            appendLine("time=$stamp")
            appendLine("thread=${thread.name} (id=${thread.id})")
            appendLine("process=$processName")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android=${Build.VERSION.RELEASE} (sdk=${Build.VERSION.SDK_INT})")
            appendLine("appVersion=${appVersionName()}")
            appendLine("--- stacktrace ---")
        }

        // 内部目录（root 可读）
        writeTo(File(filesDir, LOG_DIR), "crash-$stamp.log", header + sw.toString())
        // 外部目录（免 root 可读，便于远程导出）
        externalCacheDir?.let { /* 触发外部目录可用性判断 */ }
        getExternalFilesDir(null)?.let {
            writeTo(File(it, LOG_DIR), "crash-$stamp.log", header + sw.toString())
        }
    }

    private fun writeTo(dir: File, name: String, content: String) {
        if (!dir.exists()) dir.mkdirs()
        File(dir, name).writeText(content)
        // 只保留最近 MAX_LOGS 份
        dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") }
            ?.sortedByDescending { it.name }
            ?.drop(MAX_LOGS)
            ?.forEach { it.delete() }
    }

    @Suppress("DEPRECATION")
    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Throwable) {
        "?"
    }

    private companion object {
        const val TAG = "AnnieCrash"
        const val LOG_DIR = "crash"
        const val MAX_LOGS = 10
    }
}
