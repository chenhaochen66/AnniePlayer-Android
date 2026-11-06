package com.annieplayer.annie_music.kt.widgets

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import com.annieplayer.annie_music.kt.data.source.LxPlugin
import com.annieplayer.annie_music.kt.data.source.LxSourceMapper
import com.annieplayer.annie_music.kt.ui.theme.AnnieTypography
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing

/**
 * 「插件管理」页（全屏面板，交互与「更多设置」一致）。
 *
 * 做「已导入插件的查看 / 启停 / 删除」；导入入口是标题右侧的「+」（[onImportClick]），
 * 点它由宿主弹出「在线导入 / 本地导入」选择。
 */
@Composable
fun LxPluginManagerSheet(
    visible: Boolean,
    plugins: List<LxPlugin>,
    emptyWarning: Boolean,
    onClose: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onImportClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<LxPlugin?>(null) }

    BackHandler(enabled = visible) { onClose() }

    val scrimAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.easeInOutQuad),
        label = "lxPluginScrim",
    )
    val slide by animateFloatAsState(
        targetValue = if (visible) 0f else 1f,
        animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.easeInOutQuad),
        label = "lxPluginSlide",
    )

    if (!visible && scrimAlpha == 0f) return

    Box(modifier = modifier.fillMaxSize()) {
        // 遮罩：只吞点击，**不关闭**（退出只走返回手势 / 顶栏返回）
        // `clickable` 必须保留：吃掉触摸、阻止点击穿透到下层页面。
        val scrimInteraction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = scrimAlpha }
                .clickable(
                    enabled = visible,
                    interactionSource = scrimInteraction,
                    indication = null,
                    onClick = {},
                )
                .background(AppColors.scrim50),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = slide * size.height }
                .background(AppColors.background)
                .clickableNoRipple { }
                .systemBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = AppSpacing.sp24, top = AppSpacing.sp40, end = AppSpacing.sp24, bottom = AppSpacing.sp8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 统一的返回按钮（见 `SheetBackButton`）
                SheetBackButton(onClose = onClose)
                Spacer(modifier = Modifier.width(AppSpacing.sp8))
                Text(
                    text = "插件管理",
                    modifier = Modifier.weight(1f),
                    color = AppColors.textPrimary,
                    fontSize = AppFontSize.size20,
                    fontWeight = FontWeight.Bold,
                )
                // 「+」：导入插件（在线 / 本地二选一，弹框由宿主出）——样式对齐 Qobuz Token 管理页
                Box(
                    modifier = Modifier
                        .clickableNoRipple(onImportClick)
                        .padding(AppSpacing.sp4),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = AppColors.primary,
                        modifier = Modifier.size(AppIconSize.icon24),
                    )
                }
            }

            if (emptyWarning) {
                Text(
                    text = "没有可用的插件：国内四平台无法播放和下载，请先导入一个插件",
                    color = AppColors.danger,
                    fontSize = AppFontSize.sm,
                    modifier = Modifier.padding(
                        start = AppSpacing.sp24,
                        end = AppSpacing.sp24,
                        bottom = AppSpacing.sp8,
                    ),
                )
            } else {
                Text(
                    text = "四平台的下载由插件提供、播放优先走插件；一次只能启用一个（点条目选中）",
                    color = AppColors.textTertiary,
                    fontSize = AppFontSize.sm,
                    modifier = Modifier.padding(
                        start = AppSpacing.sp24,
                        end = AppSpacing.sp24,
                        bottom = AppSpacing.sp8,
                    ),
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (plugins.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(text = "还没有导入插件", color = AppColors.whiteA20, fontSize = AppFontSize.lg)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = AppSpacing.sp24,
                            end = AppSpacing.sp24,
                            bottom = AppSpacing.sheetBottomInset,
                        ),
                    ) {
                        items(items = plugins, key = { it.id }) { plugin ->
                            LxPluginRow(
                                plugin = plugin,
                                onSelect = { onSelect(plugin.id) },
                                onDelete = { pendingDelete = plugin },
                            )
                            Spacer(modifier = Modifier.height(AppSpacing.sp8))
                        }
                    }
                }
            }
        }

        pendingDelete?.let { plugin ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text(text = "删除插件", style = AnnieTypography.dialogTitle) },
                text = { Text(text = "确定删除 \"${plugin.name}\" 吗？", style = AnnieTypography.caption) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onDelete(plugin.id)
                            pendingDelete = null
                        },
                    ) {
                        Text(text = "删除", color = AppColors.danger, fontWeight = AppFontWeight.semiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) {
                        Text(text = "取消", color = AppColors.textTertiary)
                    }
                },
                containerColor = AppColors.surface,
            )
        }
    }
}

@Composable
private fun LxPluginRow(
    plugin: LxPlugin,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    val active = plugin.enabled
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (active) AppColors.success.copy(alpha = 0.04f) else AppColors.whiteA05,
                shape = RoundedCornerShape(AppRadius.card),
            ),
    ) {
        if (active) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(width = AppSpacing.sp3, height = AppSize.tokenItemHeight)
                    .background(
                        color = AppColors.success,
                        shape = RoundedCornerShape(topEnd = AppRadius.r2, bottomEnd = AppRadius.r2),
                    ),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = plugin.error == null, onClick = onSelect)
                .padding(AppSpacing.sp16),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    // 安妮独家插件：名字前挂「独家」徽标（样式同「使用中」，配色橙红）
                    if (plugin.exclusive) {
                        Text(
                            text = "独家",
                            modifier = Modifier
                                .padding(end = AppSpacing.sp8)
                                .background(AppColors.exclusiveA15, RoundedCornerShape(AppRadius.r4))
                                .padding(horizontal = AppSpacing.sp8, vertical = AppSpacing.sp3),
                            color = AppColors.exclusive,
                            fontSize = AppFontSize.sm,
                            fontWeight = AppFontWeight.semiBold,
                        )
                    }
                    Text(
                        text = plugin.name,
                        modifier = Modifier.weight(1f, fill = false),
                        color = AppColors.textPrimary,
                        fontSize = AppFontSize.size15,
                        fontWeight = AppFontWeight.semiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (active) {
                        Text(
                            text = "使用中",
                            modifier = Modifier
                                .padding(start = AppSpacing.sp8)
                                .background(AppColors.primaryA15, RoundedCornerShape(AppRadius.r4))
                                .padding(horizontal = AppSpacing.sp8, vertical = AppSpacing.sp3),
                            color = AppColors.primary,
                            fontSize = AppFontSize.sm,
                            fontWeight = AppFontWeight.semiBold,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .clickableNoRipple(onDelete)
                        .padding(AppSpacing.sp6),
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = AppColors.glyphDim,
                        modifier = Modifier.size(AppIconSize.icon20),
                    )
                }
            }

            val meta = buildString {
                if (plugin.version.isNotEmpty()) append("v${plugin.version}")
                if (plugin.author.isNotEmpty()) {
                    if (isNotEmpty()) append(" · ")
                    append(plugin.author)
                }
            }
            if (meta.isNotEmpty()) {
                Spacer(modifier = Modifier.height(AppSpacing.sp1))
                Text(text = meta, color = AppColors.textTertiary, fontSize = AppFontSize.sm, maxLines = 1)
            }

            // 插件描述（排在「版本 · 作者」之后，字体与元信息行统一）：
            // 洛雪源取 `@description`，独家源取标识块 `#@description`，导入时已并入 header
            if (plugin.description.isNotEmpty()) {
                Spacer(modifier = Modifier.height(AppSpacing.sp1))
                Text(
                    text = plugin.description,
                    color = AppColors.textTertiary,
                    fontSize = AppFontSize.sm,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            val usable = plugin.sources.filter { LxSourceMapper.isMappable(it.source) }
            if (usable.isNotEmpty()) {
                Spacer(modifier = Modifier.height(AppSpacing.sp6))
                Text(
                    text = usable.joinToString(" / ") { lxSourceLabel(it.source) },
                    color = AppColors.primary,
                    fontSize = AppFontSize.sm,
                    maxLines = 1,
                )
            } else if (plugin.error == null) {
                Spacer(modifier = Modifier.height(AppSpacing.sp6))
                Text(
                    text = "没有可用的国内平台",
                    color = AppColors.danger,
                    fontSize = AppFontSize.sm,
                    maxLines = 1,
                )
            }

            plugin.error?.let { err ->
                Spacer(modifier = Modifier.height(AppSpacing.sp6))
                Text(
                    text = "加载失败：$err",
                    color = AppColors.danger,
                    fontSize = AppFontSize.sm,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 落雪源代码 -> 平台中文名 */
private fun lxSourceLabel(code: String): String = when (code) {
    "kw" -> "酷我"
    "kg" -> "酷狗"
    "tx" -> "QQ"
    "wy" -> "网易"
    "mg" -> "咪咕"
    else -> code
}

/** 在线导入弹窗：粘贴插件直链 */
@Composable
fun LxImportUrlDialog(
    visible: Boolean,
    importing: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    if (!visible) return
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!importing) onDismiss() },
        title = { Text(text = "在线导入插件", style = AnnieTypography.dialogTitle) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.whiteA05, RoundedCornerShape(AppRadius.r8))
                    .padding(AppSpacing.sp12)
                    .imePadding(),
            ) {
                BasicTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    textStyle = TextStyle(color = AppColors.white, fontSize = AppFontSize.size15),
                    cursorBrush = SolidColor(AppColors.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (url.isEmpty()) {
                    Text(text = "https://…/plugin.js", color = AppColors.white30, fontSize = AppFontSize.size15)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url.trim()) },
                enabled = !importing && url.isNotBlank(),
            ) {
                Text(
                    text = if (importing) "导入中…" else "导入",
                    color = AppColors.primary,
                    fontWeight = AppFontWeight.semiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !importing) {
                Text(text = "取消", color = AppColors.textTertiary)
            }
        },
        containerColor = AppColors.surface,
    )
}
