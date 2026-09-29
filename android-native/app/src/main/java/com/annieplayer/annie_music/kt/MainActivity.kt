package com.annieplayer.annie_music.kt

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.annieplayer.annie_music.kt.core.platform.MediaStoreScan
import com.annieplayer.annie_music.kt.core.platform.StoragePermissionDialog
import com.annieplayer.annie_music.kt.core.platform.isManageStorageGranted
import com.annieplayer.annie_music.kt.core.state.KkboxViewModel
import com.annieplayer.annie_music.kt.core.state.PlayerViewModel
import com.annieplayer.annie_music.kt.core.state.QobuzViewModel
import com.annieplayer.annie_music.kt.data.AssetCache
import com.annieplayer.annie_music.kt.data.DownloadDirService
import com.annieplayer.annie_music.kt.data.DownloadService
import com.annieplayer.annie_music.kt.data.network.CacheService
import com.annieplayer.annie_music.kt.features.search.HomeScreen
import com.annieplayer.annie_music.kt.ui.theme.AnnieTheme
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

    override fun onCreate(savedInstanceState: Bundle?) {
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
        PlayerViewModel.init(applicationContext)
        KkboxViewModel.init(applicationContext)
        QobuzViewModel.init(applicationContext)

        // Android 13+ 媒体通知需要 POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            AnnieTheme {
                // 开屏覆层：首屏之上叠 SplashScreen，结束后移除（对齐 Flutter `builder` 里的 Stack）
                var splashDone by remember { mutableStateOf(false) }
                // 存储权限引导弹窗：未授予「所有文件访问」时主动弹一次（Flutter 版只在抽屉里提示）
                var showStorageDialog by remember { mutableStateOf(false) }
                val storageGranted = remember { isManageStorageGranted(this@MainActivity) }
                LaunchedEffect(Unit) {
                    if (!storageGranted) showStorageDialog = true
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    HomeScreen()
                    if (!splashDone) {
                        SplashScreen(onFinish = { splashDone = true })
                    }
                    StoragePermissionDialog(
                        visible = showStorageDialog,
                        onDismiss = { showStorageDialog = false },
                    )
                }
            }
        }
    }
}
