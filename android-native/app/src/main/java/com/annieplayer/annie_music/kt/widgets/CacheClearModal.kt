package com.annieplayer.annie_music.kt.widgets

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.data.CacheCategory
import com.annieplayer.annie_music.kt.data.CacheItem
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.ui.theme.AnnieTypography
import java.util.Locale

/**
 * 「清除缓存」弹窗（样式对齐 [ContactModal]）：
 * 居中卡片 + 60% 遮罩；逐项列出各类缓存的体积，勾选后只清理所选。
 *
 * 分类（见 [CacheCategory]）：
 * - 播放缓存：Media3 流式缓存（无上限不淘汰）——边听边涨，通常最大
 * - 封面/图片：本层 assets + Coil image_cache
 * - 下载文件：私有下载目录
 * - 接口缓存：搜索/歌词等 API 缓存
 *
 * 状态由宿主持有（[items] 异步加载、[selected] 由宿主维护），本组件只做展示与回调。
 */
@Composable
fun CacheClearModal(
    visible: Boolean,
    loading: Boolean,
    clearing: Boolean,
    items: List<CacheItem>,
    selected: Set<CacheCategory>,
    onToggle: (CacheCategory) -> Unit,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    // 返回键关闭（与 ContactModal 一致；弹窗自身处理，无需进 OverlayStack）
    BackHandler { if (!clearing) onClose() }

    val totalBytes = items.sumOf { it.bytes }
    val selectedBytes = items.filter { it.category in selected }.sumOf { it.bytes }

    Box(modifier = modifier.fillMaxSize()) {
        // 遮罩
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AppColors.scrim60)
                .clickableNoRipple { if (!clearing) onClose() },
        )

        // 居中卡片（吞掉卡片内空白点击）
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.88f)
                .background(AppColors.modalPanel, RoundedCornerShape(AppRadius.r20))
                .clickableNoRipple { }
                .padding(horizontal = AppSpacing.sp20, vertical = AppSpacing.sp24),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "清除缓存",
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    style = AnnieTypography.sheetTitle,
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp6))
                Text(
                    text = if (loading) {
                        "正在计算…"
                    } else {
                        "共 " + menuFormatBytes(totalBytes) + "，可逐项选择清理"
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    color = AppColors.white30,
                    fontSize = AppFontSize.xs,
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp16))

                if (loading) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(AppSpacing.sp44),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "正在读取缓存目录…",
                            color = AppColors.white38,
                            fontSize = AppFontSize.sm,
                        )
                    }
                } else {
                    for (item in items) {
                        CacheClearRow(
                            label = cacheCategoryLabel(item.category),
                            bytes = item.bytes,
                            checked = item.category in selected,
                            enabled = !clearing && item.bytes > 0L,
                            onToggle = { onToggle(item.category) },
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.sp10))
                    }
                }

                Spacer(modifier = Modifier.height(AppSpacing.sp6))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    // 取消
                    Box(
                        modifier = Modifier
                            .clickableNoRipple { if (!clearing) onClose() }
                            .background(AppColors.glass, RoundedCornerShape(AppRadius.card))
                            .padding(horizontal = AppSpacing.sp24, vertical = AppSpacing.sp10),
                    ) {
                        Text(
                            text = "取消",
                            color = AppColors.white,
                            fontSize = AppFontSize.size15,
                            fontWeight = AppFontWeight.semiBold,
                        )
                    }
                    Spacer(modifier = Modifier.width(AppSpacing.sp12))
                    // 清理所选
                    val canClear = !clearing && !loading && selected.isNotEmpty() && selectedBytes > 0L
                    Box(
                        modifier = Modifier
                            .clickableNoRipple { if (canClear) onConfirm() }
                            .background(
                                if (canClear) AppColors.primary else AppColors.white12,
                                RoundedCornerShape(AppRadius.card),
                            )
                            .padding(horizontal = AppSpacing.sp24, vertical = AppSpacing.sp10),
                    ) {
                        Text(
                            text = if (clearing) {
                                "清理中…"
                            } else {
                                "清理所选 (" + menuFormatBytes(selectedBytes) + ")"
                            },
                            color = if (canClear || clearing) AppColors.white else AppColors.white54,
                            fontSize = AppFontSize.size15,
                            fontWeight = AppFontWeight.semiBold,
                        )
                    }
                }
            }
        }
    }
}

/** 单行：勾选框 + 名称 + 体积 */
@Composable
private fun CacheClearRow(
    label: String,
    bytes: Long,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickableNoRipple { if (enabled) onToggle() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (checked) {
            CheckSquareIcon(modifier = Modifier.size(AppIconSize.icon18))
        } else {
            Box(
                modifier = Modifier
                    .size(AppIconSize.icon18)
                    .border(1.dp, AppColors.white24, RoundedCornerShape(AppRadius.r4)),
            )
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp10))
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = if (enabled) AppColors.white else AppColors.white38,
            fontSize = AppFontSize.base,
        )
        Text(
            text = menuFormatBytes(bytes),
            color = if (enabled) AppColors.white54 else AppColors.white30,
            fontSize = AppFontSize.sm,
        )
    }
}

/** 分类显示名 */
private fun cacheCategoryLabel(category: CacheCategory): String = when (category) {
    CacheCategory.PLAYBACK -> "播放缓存"
    CacheCategory.COVER -> "封面 / 图片"
    CacheCategory.DOWNLOADS -> "下载文件"
    CacheCategory.API -> "接口 / 歌词"
}

/** 体积格式化（B / KB / MB，保留 1 位） */
internal fun menuFormatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
