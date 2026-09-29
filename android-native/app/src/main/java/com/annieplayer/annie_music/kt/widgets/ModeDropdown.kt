package com.annieplayer.annie_music.kt.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.annieplayer.annie_music.kt.ui.theme.AppBorder
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing

/**
 * 搜索模式（1:1 移植 Flutter `lib/widgets/mode_dropdown.dart` 的 `SearchMode`；
 * 展示顺序：单曲/专辑/歌手/歌单解析/国外解析）。
 *
 * 命名加 `ModeDropdown` 前缀以避免与后续页面级枚举静默冲突。
 */
enum class ModeDropdownMode(val value: String, val label: String) {
    song("song", "单曲搜索"),
    album("album", "专辑搜索"),
    singer("singer", "歌手搜索"),
    playlist("playlist", "歌单解析"),
    qobuz("qobuz", "国外解析"),
    ;

    companion object {
        /** 字符串 → 枚举（未知回退 [song]，对齐 Flutter `SearchMode.fromValue`）。 */
        fun fromValue(value: String): ModeDropdownMode =
            entries.firstOrNull { it.value == value } ?: song
    }
}

/**
 * 搜索模式下拉（1:1 移植 Flutter `ModeDropdown`）。
 *
 * 受控组件：`open` / `mode` 由外部持有，点击触发器 [onToggle]、选择项 [onSelect]。
 *
 * 关键视觉（必须保留）：
 * - 触发器：高 36dp（[AppSize.searchBarHeight]）、主色底、圆角 10（[AppRadius.r10]）、
 *   左右内边距 12dp、文案 13sp/w600 白、右侧箭头 13dp（[AppColors.textSecondary]）。
 * - 面板：宽 140dp（[AppSize.dropdownPanelWidth]）、相对触发器顶部偏移 40dp
 *   （[AppSize.dropdownPanelTop]，对齐 RN/Flutter `top: 40`）、底色 [AppColors.surface]、
 *   圆角 12、边框 `white @0.06`；选项激活底色 `primary @0.12`（[AppColors.primaryA12]）+
 *   右侧 primary 勾选（16dp），未激活文字 `white @0.6`（[AppColors.white60]）。
 *
 * Compose 实现说明：面板用 [Popup]（focusable，点击面板外关闭）替代 Flutter 的
 * `OverlayEntry`，既能溢出父级约束又不撑高无界 Column；定位锚点为触发器左上角，
 * 与 Flutter 用 `findRenderObject().localToGlobal` 取到的原点一致。
 * 调用方若要复刻 Flutter `Align(centerLeft)` 的“占满宽度、左对齐”行为，
 * 传入 `Modifier.fillMaxWidth()` 即可。
 */
@Composable
fun ModeDropdown(
    mode: ModeDropdownMode,
    open: Boolean,
    onToggle: () -> Unit,
    onSelect: (ModeDropdownMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.CenterStart) {
        // 锚点 Box 恰好包住触发器：Popup 相对它定位，等价 Flutter 的 trigger 全局坐标
        Box {
            ModeDropdownTrigger(label = mode.label, onToggle = onToggle)
            if (open) {
                val topOffset = with(LocalDensity.current) { AppSize.dropdownPanelTop.roundToPx() }
                Popup(
                    alignment = Alignment.TopStart,
                    offset = IntOffset(0, topOffset),
                    onDismissRequest = onToggle,
                    properties = PopupProperties(focusable = true),
                ) {
                    ModeDropdownPanel(mode = mode, onSelect = onSelect)
                }
            }
        }
    }
}

/** 触发器：主色药丸 + 当前模式文案 + 下箭头（对齐 Flutter `ModeDropdown.build`）。 */
@Composable
private fun ModeDropdownTrigger(label: String, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .height(AppSize.searchBarHeight)
            .background(AppColors.primary, RoundedCornerShape(AppRadius.r10))
            .clickableNoRipple(onToggle)
            .padding(horizontal = AppSpacing.sp12),
        verticalAlignment = Alignment.CenterVertically,
        ) {
        Text(
            text = label,
            color = AppColors.white,
            fontSize = AppFontSize.size13,
            fontWeight = AppFontWeight.semiBold,
        )
        Spacer(modifier = Modifier.width(AppSpacing.sp6))
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = AppColors.textSecondary,
            modifier = Modifier.size(AppIconSize.icon13),
        )
    }
}

/**
 * 下拉面板（对齐 Flutter `_ModePanel`）：
 * 宽 140、内边距 4、圆角 12、`surface` 底色 + `white @0.06` 边框，5 个选项单列。
 */
@Composable
private fun ModeDropdownPanel(mode: ModeDropdownMode, onSelect: (ModeDropdownMode) -> Unit) {
    Surface(
        color = AppColors.surface,
        shape = RoundedCornerShape(AppRadius.card),
        modifier = Modifier.width(AppSize.dropdownPanelWidth),
    ) {
        Column(
            modifier = Modifier
                .border(
                    width = AppBorder.thin,
                    color = AppColors.whiteA06,
                    shape = RoundedCornerShape(AppRadius.card),
                )
                .padding(vertical = AppSpacing.sp4, horizontal = AppSpacing.sp4),
            // Flutter Column(mainAxisSize: MainAxisSize.min)：面板高度随内容收紧
            ) {
            for (option in ModeDropdownMode.entries) {
                ModeDropdownPanelItem(
                    option = option,
                    active = option == mode,
                    onTap = { onSelect(option) },
                )
            }
        }
    }
}

/** 面板选项（对齐 Flutter `_ModePanelItem`）：激活态高亮底色 + 白字 + 主色勾选。 */
@Composable
private fun ModeDropdownPanelItem(
    option: ModeDropdownMode,
    active: Boolean,
    onTap: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.r8))
            .background(if (active) AppColors.primaryA12 else Color.Transparent)
            .clickableNoRipple(onTap)
            .padding(vertical = AppSpacing.sp10, horizontal = AppSpacing.sp12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = option.label,
            color = if (active) AppColors.white else AppColors.white60,
            fontSize = AppFontSize.base,
            fontWeight = AppFontWeight.medium,
            modifier = Modifier.weight(1f),
        )
        if (active) {
            Spacer(modifier = Modifier.width(AppSpacing.sp8))
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = AppColors.primary,
                modifier = Modifier.size(AppIconSize.icon16),
            )
        }
    }
}
