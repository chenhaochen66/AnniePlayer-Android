package com.annieplayer.annie_music.kt.core.platform

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.core.app.NotificationManagerCompat
import com.annieplayer.annie_music.kt.core.audio.AnnieAudioHolder
import com.annieplayer.annie_music.kt.core.audio.MusicSessionService
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * 「退出应用」：停掉一切在跑的东西，然后把进程直接结束。
 *
 * 为什么不是简单 `finish()`：播放/下载都跑在**同进程**的服务与协程里（见
 * `MusicSessionService` 的类注释），光关界面会留下
 * - 媒体前台服务继续持有通知与音频焦点（通知栏那一条不消失、音乐继续响），
 * - 下载协程接着写文件，
 * - ExoPlayer / Temari / ffmpeg 的原生线程都还活着。
 *
 * 所以按固定顺序收尾：
 * 1. 暂停播放（避免杀进程瞬间还在出声）；
 * 2. `stopService` 掉媒体会话服务 → Media3 自己撤掉通知并退出前台态；
 * 3. 兜底 `cancelAll()`，清掉可能残留的通知；
 * 4. `finishAndRemoveTask()` 把任务从最近任务里摘掉；
 * 5. 从子线程等一小会儿再 `killProcess(myPid())`——SIGKILL 会带走进程内所有线程、
 *    协程与原生库，等价于「全杀」，同时留出时间让第 4 步的 binder 调用真正落地。
 */
fun exitAppCompletely(context: Context) {
    val app = context.applicationContext

    runCatching { AnnieAudioHolder.player?.pause() }
    runCatching { app.stopService(Intent(app, MusicSessionService::class.java)) }
    runCatching { NotificationManagerCompat.from(app).cancelAll() }

    val activity = context as? Activity
    runCatching { activity?.finishAndRemoveTask() }
        .onFailure { runCatching { activity?.finishAffinity() } }

    thread(name = "app-exit") {
        runCatching { Thread.sleep(220) }
        runCatching { Process.killProcess(Process.myPid()) }
        exitProcess(0)
    }
}
