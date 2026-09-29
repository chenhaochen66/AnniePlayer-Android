package com.annieplayer.annie_music.kt.core.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize

/**
 * 「所有文件访问」权限（MANAGE_EXTERNAL_STORAGE）的检测与引导。
 *
 * 1:1 对齐 Flutter `lib/core/platform/media_store_scan.dart` 的两个函数：
 * - `isManageStorageGranted()` → [isManageStorageGranted]
 * - `requestManageStorage()`   → [requestManageStorage]
 *
 * **注意**：MANAGE_EXTERNAL_STORAGE 是**特殊权限**，不能像普通运行时权限那样弹窗申请，
 * 只能跳转到系统设置页让用户手动开启（Flutter 版注释里也写明了这一点）。
 * 因此这里额外提供了 [StoragePermissionDialog]：用一个带按钮的对话框先把用户引导过去，
 * 而不是只靠侧边抽屉里那一行文字提示。
 */

/**
 * 是否已授予「所有文件访问」权限。
 *
 * Android 10（API 29）及以下没有该权限概念 → 直接返回 true（对齐 Flutter 行为）；
 * Android 11+（API 30+）返回真实状态。
 */
fun isManageStorageGranted(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
    return runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
}

/**
 * 跳转系统设置页，引导用户手动开启「所有文件访问」。
 *
 * 优先跳本应用的授权页；部分 ROM 没有该页面时退回到「所有文件访问」列表页；
 * 两者都失败时退回到本应用详情页。全程不抛异常（对齐 Flutter 的静默忽略）。
 */
fun requestManageStorage(context: Context) {
    val pkg = context.packageName
    val candidates = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.fromParts("package", pkg, null)
                },
            )
            add(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
        add(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", pkg, null)
            },
        )
    }
    for (intent in candidates) {
        val ok = runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.isSuccess
        if (ok) return
    }
}

/**
 * 存储权限引导弹窗：未授权时弹给用户，点按钮直达系统设置。
 *
 * 视觉沿用应用主题（深色面板 + 主色按钮）。**这是 Flutter 版没有的新增交互**，
 * 因为 Flutter 只在侧边抽屉里放了一行「未授权，点击去开启」；此处按需求改为
 * 启动时主动弹窗引导，抽屉里那一行仍然保留（两者都通向同一个系统设置页）。
 */
@Composable
fun StoragePermissionDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppColors.surface,
        titleContentColor = Color.White,
        textContentColor = AppColors.textSecondary,
        title = {
            Text(text = "需要存储权限", fontSize = AppFontSize.xl)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "开启「所有文件访问」后，才能把下载的歌曲保存到你选择的位置，并扫描本地音乐库。",
                    fontSize = AppFontSize.base,
                )
                Text(
                    text = "系统会打开本应用的设置页，请找到「所有文件访问」并允许。",
                    fontSize = AppFontSize.sm,
                    color = AppColors.textTertiary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                requestManageStorage(context)
                onDismiss()
            }) {
                Text(text = "去设置", color = AppColors.primary, fontSize = AppFontSize.base)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "稍后", color = AppColors.textTertiary, fontSize = AppFontSize.base)
            }
        },
    )
}
