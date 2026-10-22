package com.annieplayer.annie_music.kt.core.platform

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * 「耗电优化」白名单（Doze / 电池优化）的检测与引导。
 *
 * 为什么音乐播放器需要它：应用被系统放进「受限制」名单后，熄屏一段时间就会被冻结，
 * 后台自动切歌、下一首直链解析、下载任务都会停在半路（现象是「歌停在上一首结束处、
 * 前台点一下又正常」）。加入白名单后 `PARTIAL_WAKE_LOCK` 才真正管用。
 *
 * 和「所有文件访问」一样，这是**特殊权限**：不能弹运行时权限框，
 * 只能拉起系统的电池优化对话框（加入）或列表页（移出）。
 * 系统没有提供「以编程方式移出白名单」的接口，所以关闭开关只能跳设置页让用户自己取消。
 */

/** 当前是否已在「电池优化」白名单里（= 不受限制，后台不会被冻结） */
fun isIgnoringBatteryOptimizations(context: Context): Boolean = runCatching {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    pm.isIgnoringBatteryOptimizations(context.packageName)
}.getOrDefault(false)

/**
 * 申请加入电池优化白名单。
 *
 * 优先拉起系统自带的「是否允许后台运行」对话框（`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，
 * 需要 Manifest 里的 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）；部分 ROM（含 Oppo/一加）
 * 没有这个对话框，退回电池优化列表页，再退回应用详情页。全程不抛异常。
 */
@SuppressLint("BatteryLife")
fun requestIgnoreBatteryOptimizations(context: Context) {
    val pkg = context.packageName
    val candidates = buildList {
        add(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.fromParts("package", pkg, null)
            },
        )
        add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        add(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", pkg, null)
            },
        )
    }
    startFirstAvailable(context, candidates)
}

/**
 * 打开电池优化列表页，让用户把本应用从白名单里移除。
 *
 * 系统没有反向接口（`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 只能加不能减），
 * 所以关闭开关时只能把用户带到列表页。
 */
fun openBatteryOptimizationSettings(context: Context) {
    val pkg = context.packageName
    val candidates = buildList {
        add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        add(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", pkg, null)
            },
        )
    }
    startFirstAvailable(context, candidates)
}

private fun startFirstAvailable(context: Context, candidates: List<Intent>) {
    for (intent in candidates) {
        val ok = runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.isSuccess
        if (ok) return
    }
}
