package com.annieplayer.annie_music.kt

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.annieplayer.annie_music.kt.core.audio.AnnieAudioHolder
import com.annieplayer.annie_music.kt.core.audio.BluetoothCodec
import com.annieplayer.annie_music.kt.core.audio.ExclusiveVolume
import com.annieplayer.annie_music.kt.core.platform.MediaStoreScan
import com.annieplayer.annie_music.kt.core.platform.StoragePermissionDialog
import com.annieplayer.annie_music.kt.core.platform.isManageStorageGranted
import com.annieplayer.annie_music.kt.core.state.KkboxViewModel
import com.annieplayer.annie_music.kt.core.state.MoreSettings
import com.annieplayer.annie_music.kt.core.state.PlayerViewModel
import com.annieplayer.annie_music.kt.core.state.QobuzViewModel
import com.annieplayer.annie_music.kt.data.AssetCache
import com.annieplayer.annie_music.kt.data.DownloadDirService
import com.annieplayer.annie_music.kt.data.DownloadService
import com.annieplayer.annie_music.kt.data.network.CacheService
import com.annieplayer.annie_music.kt.data.sources.apple.AppleMusicSettings
import com.annieplayer.annie_music.kt.data.sources.apple.wrapper.AppleFixedKey
import com.annieplayer.annie_music.kt.data.sources.apple.wrapper.AppleServiceSettings
import com.annieplayer.annie_music.kt.data.sources.apple.wrapper.AppleWrapperRegistry
import com.annieplayer.annie_music.kt.data.sources.kkbox.KkboxDownloader
import com.annieplayer.annie_music.kt.data.sources.kkbox.KkboxPlayCache
import com.annieplayer.annie_music.kt.data.sources.kkbox.KkboxPlayServer
import com.annieplayer.annie_music.kt.data.source.LxPluginRegistry
import com.annieplayer.annie_music.kt.features.search.SearchSettings
import com.annieplayer.annie_music.kt.features.shell.AppShell
import com.annieplayer.annie_music.kt.ui.theme.AnnieTheme
import com.annieplayer.annie_music.kt.ui.widgets.ExclusiveVolumeBar
import com.annieplayer.annie_music.kt.widgets.SplashScreen

/**
 * 唯一 Activity（单 Activity + 状态驱动浮层栈，对齐 Flutter 版的单页架构）。
 *
 * 结构完全对齐 Flutter `main.dart`：
 * ```
 * AnnieTheme {
 *     Stack { HomeScreen; if (!splashDone) SplashScreen(onFinish) }
 * }
 * ```
 *
 * 说明：**不需要额外的 RootScreen** —— `HomeScreen` 内部已按 Flutter 的
 * `Stack` 顺序渲染了全部浮层（GlobalOverlays / MenuDrawer / MiniPlayer /
 * PlayerOverlay / SongActionsSheet / 音质选择 / QueueSheet / 详情浮层 …）。
 */
class MainActivity : ComponentActivity() {

    /** 通知权限请求（对齐 Flutter `_requestNotificationPermission`，Android 13+） */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝也不阻塞播放 */ }

    /** 蓝牙权限（Android 12+）：用于读取当前 codec / 采样率 / 码率 */
    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) BluetoothCodec.onPermissionGranted()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 【必须在 super.onCreate 之前】targetSdk 35+ 只让 **Android 15 及以上**的系统强制
        // edge-to-edge；Android 14 及以下仍然按 `setDecorFitsSystemWindows(true)` 处理，
        // 内容会被状态栏 / 导航栏挤开，主题里那两条实心色块就成了上下一圈「边框」。
        //
        // 而本项目所有页面根部本来就写着 `systemBarsPadding()` / `statusBarsPadding()`
        // （按 edge-to-edge 设计的），decorFits=true 时这些 padding 恒为 0 —— 布局按全屏算、
        // 系统栏却不透，观感正是「没做全屏」。这里显式开启后，Android 14 与 15+ 行为一致。
        //
        // `SystemBarStyle.dark(透明)`：本 App 恒为深色底 → 系统栏图标用浅色（白），
        // 且系统栏本身透明，内容（封面 / 氛围背景）可以透上去。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        // 缓存服务需要 Context 才能持久化（未 init 时退化为纯内存，语义不变）
        CacheService.init(applicationContext)

        // 初始化所有需要 Context 的服务与状态镜像（对齐 Flutter 侧各 provider/service
        // 直接持有全局单例 ApplicationContext 的行为）。漏调这些 init 会导致：
        // 媒体库入库、封面/音频缓存、自定义下载目录、音量/循环/已下载 id 与
        // KKBOX/Qobuz 账号持久化全部失效。
        MediaStoreScan.init(applicationContext)
        AssetCache.init(applicationContext)
        DownloadDirService.init(applicationContext)
        DownloadService.init(applicationContext)
        // KKBOX 下载要 app cacheDir 放 DRM 解密中间文件
        KkboxDownloader.init(applicationContext)
        KkboxPlayCache.init(applicationContext)
        KkboxPlayServer.init(applicationContext)
        AppleMusicSettings.init(applicationContext)
        AppleFixedKey.init(applicationContext)
        // Apple 的非官方接口：本机 WrapperLite（开关）↔ 用户自填的远程 wrapper 地址，二选一
        AppleServiceSettings.init(applicationContext)
        AppleWrapperRegistry.init(applicationContext)
        SearchSettings.init(applicationContext)
        PlayerViewModel.init(applicationContext)
        // 播放现场持久化（重启恢复队列 + 进度）
        com.annieplayer.annie_music.kt.data.PlayerResumeStore.init(applicationContext)
        KkboxViewModel.init(applicationContext)
        QobuzViewModel.init(applicationContext)
        MoreSettings.init(applicationContext)
        // 独占通路的音量：绕开 mixer 后系统音量键调的是一个和声音无关的旋钮，
        // 所以这条链路的音量只能我方记、我方施加（见 `ExclusiveVolume`）。
        ExclusiveVolume.init(SharedPrefsVolumeStore(applicationContext))
        LxPluginRegistry.init(applicationContext)
        BluetoothCodec.init(applicationContext)
        // 本地曲库缓存（index.json + 抽出的封面）
        com.annieplayer.annie_music.kt.data.local.LocalLibraryStore.init(applicationContext)
        // 收藏媒体库（filesDir/favorites/）
        com.annieplayer.annie_music.kt.data.favorites.FavoriteStore.init(applicationContext)

        // Android 13+ 媒体通知需要 POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Android 12+ 读取蓝牙 codec 需要 BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }

        setContent {
            AnnieTheme {
                // 开屏覆层：首屏之上叠 SplashScreen，结束后移除（对齐 Flutter `builder` 里的 Stack）。
                // 例外：**已有歌曲装载**（典型场景：从媒体通知 / 控制中心切回前台，进程与播放都没断）
                // → 不再走开屏，直接进主界面，避免「又重开了一遍软件」的观感。
                val alreadyHasSong = (AnnieAudioHolder.player?.mediaItemCount ?: 0) > 0
                var splashDone by remember { mutableStateOf(alreadyHasSong) }
                // 存储权限引导弹窗：未授予「所有文件访问」时主动弹一次（Flutter 版只在抽屉里提示）
                var showStorageDialog by remember { mutableStateOf(false) }
                val storageGranted = remember { isManageStorageGranted(this@MainActivity) }
                LaunchedEffect(Unit) {
                    if (!storageGranted) showStorageDialog = true
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    AppShell()
                    if (!splashDone) {
                        SplashScreen(onFinish = { splashDone = true })
                    }
                    StoragePermissionDialog(
                        visible = showStorageDialog,
                        onDismiss = { showStorageDialog = false },
                    )
                    // 独占通路的音量条：只在按音量键时出现，4 秒后自己淡出
                    ExclusiveVolumeBar()
                }
            }
        }
    }

    /**
     * 退到后台时立刻把播放现场（队列下标 + 进度）落盘一次。
     *
     * ViewModel 里有 5 秒节流的进度写盘，但"从最近任务划掉 / 系统回收"可能刚好落在
     * 两次写盘之间 —— 这里补一刀，保证下次启动的续播位置尽量接近退出时。
     */
    override fun onStop() {
        super.onStop()
        runCatching {
            androidx.lifecycle.ViewModelProvider(this)[PlayerViewModel::class.java].persistResumeNow()
        }
    }

    /**
     * 音量键改由独占通路自己处理。
     *
     * 返回 true 就**吞掉事件**：系统不会弹音量条，也不会去调 `STREAM_MUSIC`
     * —— 那个旋钮在独占流上没有任何接收方，让它响只会调了个寂寞。
     *
     * 只在「有可调的独占流」时接管（`ExclusiveVolume.step` 返回 false 就交回系统），
     * 所以系统通路 / 蓝牙 / 没有独占流时行为完全不变。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.onKeyDown(keyCode, event)
        }
        if (!ExclusiveVolume.handles) return super.onKeyDown(keyCode, event)
        // 长按：系统的重复率约 20 次/秒，每来一次就 1 dB 等于 20 dB/s，完全不可控。
        // 每三次取一次（≈7 dB/s）；不取的那些**同样要吞掉**，否则长按中途系统音量条会冒出来。
        val repeat = event?.repeatCount ?: 0
        if (repeat % 3 == 0) {
            ExclusiveVolume.step(up = keyCode == KeyEvent.KEYCODE_VOLUME_UP)
        }
        return true
    }
}

/** `ExclusiveVolume` 的持久化后端：与其余设置同一个 prefs，按通路分键。 */
private class SharedPrefsVolumeStore(context: Context) : ExclusiveVolume.Store {
    private val prefs = context.getSharedPreferences("annie_shared_prefs", Context.MODE_PRIVATE)

    override fun get(key: String): Float? =
        if (prefs.contains(key)) prefs.getFloat(key, 0f) else null

    override fun put(key: String, db: Float) {
        prefs.edit().putFloat(key, db).apply()
    }
}
