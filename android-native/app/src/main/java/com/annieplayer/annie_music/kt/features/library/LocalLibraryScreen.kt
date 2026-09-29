package com.annieplayer.annie_music.kt.features.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.ui.theme.AnnieTypography
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.AppTopBar
import com.annieplayer.annie_music.kt.widgets.VinylRecord

/** 空态「唱盘」底板直径（圆形白 3.1% 底，托住黑胶） */
private val LibraryEmptyDiscSize = 112.dp

/** 空态黑胶本体直径（复用底栏黑胶占位图形，保持同一视觉语言） */
private val LibraryEmptyVinylSize = 76.dp

/**
 * 本地曲库页（**占位页**）。
 *
 * 本轮只交付页面骨架：标题 + 「建设中」角标 + 一个居中空态。**没有** MediaStore 扫描、
 * 没有列表、没有播放接线——数据层与播放接线是后续任务。
 *
 * 顶栏与搜索页共用 [AppTopBar]（菜单按钮 + 大标题）：标题排版（[AppFontSize.size26] /
 * [AppFontWeight.bold] / `AppLetterSpacing.tight`）与搜索页「安妮音乐盒」逐 token 一致，
 * 左侧汉堡按钮点击回调由宿主注入（打开菜单抽屉）。本页右侧**不需要**头像：页面没有
 * 账号 / 品牌头像语义，顶栏保持「菜单 + 标题」两项即可。
 * 顶栏自带 16 / 40 / 16 / 20 内边距，因此外层只留底部——按常驻底栏预留
 * [AppSpacing.bottomBarInset]，保证空态在「栏体之上」的可视区域里居中，不被玻璃栏体压住。
 * 所有颜色 / 间距 / 圆角 / 字号一律取 `ui/theme` 设计 token。
 */
@Composable
fun LocalLibraryScreen(
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            // inset 由本页自己补（宿主已不再统一内缩）；随后再留底栏高度，空态位置与改动前一致
            .systemBarsPadding()
            .padding(bottom = AppSpacing.bottomBarInset),
    ) {
        // 顶栏：菜单按钮 + 「本地曲库」标题（+「建设中」角标），与搜索页同一组件、同一套标题 token
        AppTopBar(
            title = "本地曲库",
            onOpenMenu = onOpenMenu,
            titleAdornment = {
                Box(
                    modifier = Modifier
                        .background(AppColors.primaryA15, RoundedCornerShape(AppRadius.r4))
                        .padding(horizontal = AppSpacing.sp8, vertical = AppSpacing.sp3),
                ) {
                    Text(
                        text = "建设中",
                        color = AppColors.primary,
                        fontSize = AppFontSize.size10,
                        fontWeight = AppFontWeight.semiBold,
                    )
                }
            },
        )

        // 空态：占满余下高度 → 相对「可视区域」居中
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = AppSpacing.sp16),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // 唱盘底板 + 黑胶（复用底栏占位图形，无需新增资源）
                Box(
                    modifier = Modifier
                        .size(LibraryEmptyDiscSize)
                        .background(AppColors.whiteA03, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    VinylRecord(modifier = Modifier.size(LibraryEmptyVinylSize))
                }
                Spacer(modifier = Modifier.height(AppSpacing.sp24))
                Text(
                    text = "本地曲库建设中",
                    style = AnnieTypography.sectionTitle,
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp8))
                Text(
                    text = "后续将在此扫描并管理设备上的音乐文件",
                    color = AppColors.white38,
                    fontSize = AppFontSize.base,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
