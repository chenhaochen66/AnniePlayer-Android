package com.annieplayer.annie_music.kt.widgets

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.data.local.LocalLibraryStore
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing

/**
 * 本地歌曲删除确认弹窗（破坏性操作）。
 *
 * ## 动效
 * 面板不是「瞬间出现」的，而是一段有始有终的转场：
 * - **呼出** 220ms：遮罩淡入（0 → 60% 黑），卡片从 0.94 倍放大到 1、同时从下方 18dp 升到位，
 *   配合 [FastOutSlowInEasing]（快进慢停），落位有一点"压一下"的重量感；
 * - **取消 / 关闭** 160ms：反向播放且**比入场快一档**（[LinearOutSlowInEasing]），
 *   退场利落、不拖泥带水；
 * - 卸载时机由 [rendered] 控制 —— 必须等退场动画演完才从组合里移除，
 *   否则 `visible` 变 false 的那一帧卡片就消失了（看起来像被硬切掉）。
 *
 * 所有 alpha / scale / translate 都在 `graphicsLayer { }` 的 **lambda 里**读动画值：
 * 那是绘制阶段，只失效图层、不触发重组，所以这段动画不会让整棵树逐帧重组。
 *
 * ## 层级
 * 遮罩（scrim60，只吞触摸不关闭）→ 卡片（`surfaceLight → modalPanel` 垂直渐变 +
 * 1dp 白 6% 描边 + 20dp 圆角）→ 图标徽标 / 标题 / 歌名卡 / 勾选卡 / 按钮。
 *
 * ## 「同时删除本地文件」的勾选是带记忆的
 * - 默认勾选（[LocalLibraryStore.loadDeleteLocalFile] 默认 `true`）；
 * - 每次切换立刻写盘，下次弹出来还是上次的选择，不会跳回默认；
 * - 文件已不存在时整行禁用，只剩「从曲库移除」这一种结果。
 */
@Composable
fun LocalDeleteModal(
    visible: Boolean,
    songTitle: String,
    songSubtitle: String,
    filePath: String,
    fileSizeText: String,
    fileExists: Boolean,
    onConfirm: (deleteFile: Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 是否还挂在组合里：入场立刻 true，退场等动画结束才 false
    var rendered by remember { mutableStateOf(false) }
    val appear = remember { Animatable(0f) }

    LaunchedEffect(visible) {
        if (visible) {
            rendered = true
            appear.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
            )
        } else if (rendered) {
            appear.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 160, easing = LinearOutSlowInEasing),
            )
            rendered = false
        }
    }

    if (!rendered) return

    // 退场动画期间不再吃返回键（已经关过一次了）
    BackHandler(enabled = visible) { onClose() }

    // 每次打开都从偏好里读：用户上次的选择会被记住，不会恢复默认
    var deleteFile by remember(visible) { mutableStateOf(LocalLibraryStore.loadDeleteLocalFile()) }
    val toggle: (Boolean) -> Unit = { value ->
        deleteFile = value
        LocalLibraryStore.saveDeleteLocalFile(value)
    }

    Box(modifier = modifier.fillMaxSize()) {
        // ── 遮罩：淡入淡出，只吞触摸不关闭 ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = appear.value }
                .background(AppColors.scrim60)
                .clickableNoRipple { },
        )

        // ── 卡片：放大 + 上浮 ──
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.88f)
                .graphicsLayer {
                    alpha = appear.value
                    val s = 0.94f + 0.06f * appear.value
                    scaleX = s
                    scaleY = s
                    // 从下方 18dp 升到位；退场时反向沉下去
                    translationY = (1f - appear.value) * 18.dp.toPx()
                }
                .background(
                    brush = Brush.verticalGradient(
                        listOf(AppColors.surfaceLight, AppColors.modalPanel),
                    ),
                    shape = RoundedCornerShape(AppRadius.r20),
                )
                .border(
                    width = 1.dp,
                    color = AppColors.whiteA06,
                    shape = RoundedCornerShape(AppRadius.r20),
                )
                .clickableNoRipple { }
                .padding(horizontal = AppSpacing.sp20, vertical = AppSpacing.sp24),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // ── 图标徽标 ──
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(52.dp)
                        .background(DeleteRedSoft, CircleShape)
                        .border(1.dp, DeleteRedBorder, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = DeleteRed,
                        modifier = Modifier.size(AppIconSize.icon24),
                    )
                }
                Spacer(modifier = Modifier.height(AppSpacing.sp14))
                Text(
                    text = "删除歌曲",
                    modifier = Modifier.fillMaxWidth(),
                    color = AppColors.white,
                    fontSize = AppFontSize.xl,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp6))
                Text(
                    text = "将从本地曲库移除这首歌",
                    modifier = Modifier.fillMaxWidth(),
                    color = AppColors.white38,
                    fontSize = AppFontSize.xs,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(AppSpacing.sp16))

                // ── 歌名卡 ──
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppColors.whiteA03, RoundedCornerShape(AppRadius.card))
                        .padding(horizontal = AppSpacing.sp12, vertical = AppSpacing.sp10),
                ) {
                    Text(
                        text = songTitle.ifBlank { "未知标题" },
                        color = AppColors.white,
                        fontSize = AppFontSize.size15,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (songSubtitle.isNotBlank()) {
                        Spacer(modifier = Modifier.height(AppSpacing.sp2))
                        Text(
                            text = songSubtitle,
                            color = AppColors.white30,
                            fontSize = AppFontSize.xs,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(AppSpacing.sp12))

                // ── 左下角：是否连文件一起删（选中态换成红色玻璃） ──
                DeleteFileToggleRow(
                    checked = deleteFile,
                    enabled = fileExists,
                    sizeText = fileSizeText,
                    path = filePath,
                    onToggle = { toggle(!deleteFile) },
                )

                if (fileExists && deleteFile) {
                    Spacer(modifier = Modifier.height(AppSpacing.sp8))
                    Text(
                        text = "删除后文件不进回收站，不可撤销。",
                        color = DeleteWarn,
                        fontSize = AppFontSize.xs,
                    )
                }

                Spacer(modifier = Modifier.height(AppSpacing.sp20))

                // ── 按钮：等宽，取消在左 ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sp12),
                ) {
                    ModalActionButton(
                        label = "取消",
                        container = AppColors.glass,
                        contentColor = AppColors.white,
                        onClick = onClose,
                        modifier = Modifier.weight(1f),
                    )
                    ModalActionButton(
                        label = "删除",
                        container = DeleteRed,
                        contentColor = AppColors.white,
                        onClick = { onConfirm(deleteFile && fileExists) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * 「同时删除本地文件」勾选行。
 *
 * 选中时整行换成红色玻璃（10% 红底 + 26% 红边），未选中是中性白 3% 底 ——
 * 让「这次会不会真的删文件」一眼可见，而不是只靠一个 18dp 的小方框。
 */
@Composable
private fun DeleteFileToggleRow(
    checked: Boolean,
    enabled: Boolean,
    sizeText: String,
    path: String,
    onToggle: () -> Unit,
) {
    val active = checked && enabled
    val shape = RoundedCornerShape(AppRadius.card)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (active) DeleteRedSoft else AppColors.whiteA03, shape)
            .border(1.dp, if (active) DeleteRedBorder else AppColors.whiteA06, shape)
            .clickableNoRipple { if (enabled) onToggle() }
            .padding(horizontal = AppSpacing.sp12, vertical = AppSpacing.sp10),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (active) {
            CheckSquareIcon(modifier = Modifier.size(AppIconSize.icon18), color = DeleteRed)
        } else {
            Box(
                modifier = Modifier
                    .size(AppIconSize.icon18)
                    .border(1.dp, AppColors.white24, RoundedCornerShape(AppRadius.r4)),
            )
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp10))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "同时删除本地文件",
                color = when {
                    !enabled -> AppColors.white24
                    active -> AppColors.white
                    else -> AppColors.white54
                },
                fontSize = AppFontSize.base,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp2))
            Text(
                text = when {
                    !enabled -> "文件已不存在，只移除曲库记录"
                    sizeText.isBlank() -> path
                    else -> "$sizeText · $path"
                },
                color = AppColors.white24,
                fontSize = AppFontSize.xs,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 弹窗按钮：按下时缩到 0.95 再弹回（90ms），比静态方块更"实"。
 *
 * 用 `indication = null`（本工程统一无涟漪），缩放就是这里唯一的触感反馈。
 */
@Composable
private fun ModalActionButton(
    label: String,
    container: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = tween(durationMillis = 90),
        label = "modalActionPress",
    )
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .background(container, RoundedCornerShape(AppRadius.card))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = AppSpacing.sp12),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = contentColor,
            fontSize = AppFontSize.size15,
            fontWeight = AppFontWeight.semiBold,
        )
    }
}

/** 破坏性操作的强调色（主题里没有 danger token，只在本弹窗内使用） */
private val DeleteRed = Color(0xFFEF5350)

/** 红色玻璃底（10% 不透明度） */
private val DeleteRedSoft = Color(0x1AEF5350)

/** 红色描边（26% 不透明度） */
private val DeleteRedBorder = Color(0x42EF5350)

/** 「不可撤销」提示用更浅一档的红，避免和按钮抢 */
private val DeleteWarn = Color(0xB3EF5350)
