package com.annieplayer.annie_music.kt.widgets

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppLetterSpacing
import com.annieplayer.annie_music.kt.ui.theme.AppSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing

/**
 * 主页面共享顶栏（Android-only 抽取）：汉堡菜单按钮 + 大标题（+ 可选后缀 / 右侧插槽）。
 *
 * 抽出的动机：搜索页（「安妮音乐盒」）与本地曲库页（「本地曲库」）需要**完全一致**的标题
 * 排版与顶栏间距 —— 由本组件单点维护标题 token（[AppFontSize.size26] /
 * [AppFontWeight.bold] / [AppLetterSpacing.tight]）与内边距（16 / 40 / 16 / 20，
 * 对齐 Flutter `home_screen` 顶栏），避免两页各写一份造成漂移。
 *
 * @param title 标题文案。
 * @param onOpenMenu 点击左侧菜单按钮（打开菜单抽屉）。
 * @param titleAdornment 紧跟标题之后的可选装饰（如本地曲库页的「建设中」角标）；默认无。
 * @param trailing 顶栏最右侧的可选插槽（如搜索页头像）；默认无。
 */
@Composable
fun AppTopBar(
    title: String,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier,
    titleAdornment: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.padding(
            start = AppSpacing.sp16,
            top = AppSpacing.sp40,
            end = AppSpacing.sp16,
            bottom = AppSpacing.sp20,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MenuDrawerButton(
            onOpen = onOpenMenu,
            modifier = Modifier.size(AppSize.headerActionHeight),
        )
        Text(
            text = title,
            color = AppColors.white,
            fontSize = AppFontSize.size26,
            fontWeight = AppFontWeight.bold,
            letterSpacing = AppLetterSpacing.tight,
        )
        if (titleAdornment != null) {
            Spacer(modifier = Modifier.width(AppSpacing.sp8))
            titleAdornment()
        }
        // 弹性空隙：把 trailing 推到最右（与旧搜索页 `Text(weight(1f))` 的观感一致）
        Spacer(modifier = Modifier.weight(1f))
        if (trailing != null) {
            trailing()
        }
    }
}
