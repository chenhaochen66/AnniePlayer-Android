package com.annieplayer.annie_music.kt.widgets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing

/**
 * 底部面板（sheet / 抽屉）标题行右侧的**统一关闭按钮**。
 *
 * ## 为什么需要它
 * 遮罩已经不再响应点击（见各浮层遮罩上的注释：`onClick = {}`，只吞触摸不关闭），
 * 于是「返回手势」成了唯一退出方式 —— 但返回手势在部分设备 / 场景下不直观
 * （三键导航还好，手势导航要划、且没有提示）。所以每个面板都必须在**可见处**给一个关闭入口。
 *
 * ## 样式统一到这一个（底部面板类）
 * | 项 | 值 |
 * |---|---|
 * | 图标 | `Icons.Default.Close` |
 * | 尺寸 | [AppIconSize.icon22] |
 * | 颜色 | [AppColors.textSecondary]（白 70%） |
 * | 触摸外扩 | [AppSpacing.sp4] padding（视觉 22dp，实际点击区 30dp） |
 *
 * **整页式面板不要用这个**：那一类（Apple 服务、落雪插件管理、更多设置…）的惯例是
 * 顶栏左侧的**返回箭头**（`DetailChevronLeft` / `KeyboardArrowLeft`），语义是「回上一层」
 * 而不是「关掉浮层」。两者不要混用。
 *
 * ## contentDescription
 * 用「关闭」而不是 null：这是面板唯一的可见退出入口，读屏必须念得出来。
 */
@Composable
fun SheetCloseButton(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clickableNoRipple(onClose)
            .padding(AppSpacing.sp4),
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "关闭",
            tint = AppColors.textSecondary,
            modifier = Modifier.size(AppIconSize.icon22),
        )
    }
}

/**
 * **整页式面板**顶栏左侧的统一返回按钮。
 *
 * 与 [SheetCloseButton] 的分工：
 *
 * | 类型 | 图标 | 语义 | 用在 |
 * |---|---|---|---|
 * | [SheetCloseButton] | ✕ | 「关掉这个浮层」 | 底部面板 / 抽屉（`AppSheet`、队列、音源选择、菜单） |
 * | [SheetBackButton] | ← | 「回上一层」 | 整页式面板（Qobuz / KKBOX / Apple 服务 / 插件管理 / 下载队列 / 更多设置） |
 *
 * 两者**不要混用**：底部面板从下往上滑出来，用 ✕ 表示丢弃；整页式面板是从上层压进来的，
 * 用 ← 表示回退。混用会让「这个按钮是关还是退」变得无法预期。
 *
 * 字形统一到 [DetailChevronLeft]（自绘 2dp 圆头笔画）：之前这里有三种字形混着
 * —— `Icons.Default.KeyboardArrowLeft`、`Icons.AutoMirrored.Filled.KeyboardArrowLeft`、
 * `DetailChevronLeft`，前两者的笔画粗细与全站自绘图标家族不一致，并排能看出来。
 */
@Composable
fun SheetBackButton(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clickableNoRipple(onClose)
            .padding(AppSpacing.sp4),
    ) {
        DetailChevronLeft(
            modifier = Modifier.size(AppIconSize.icon22),
            tint = AppColors.textSecondary,
        )
    }
}
