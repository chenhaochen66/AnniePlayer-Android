package com.annieplayer.annie_music.kt.widgets

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import com.annieplayer.annie_music.kt.core.state.QobuzViewModel
import com.annieplayer.annie_music.kt.data.model.QobuzToken
import com.annieplayer.annie_music.kt.data.sources.qobuz.checkToken
import com.annieplayer.annie_music.kt.ui.theme.AppBorder
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.ui.theme.AnnieTypography
import kotlin.random.Random
import kotlinx.coroutines.launch

/**
 * Qobuz Token 管理浮层（1:1 移植 Flutter `lib/widgets/qobuz_token_sheet.dart`，
 * 视觉对齐 RN 版 `components/QobuzTokenOverlay.tsx`）。
 *
 * 由宿主传入 [visible]（对应 Dart `uiProvider.qobuzTokenVisible`）控制显隐；
 * 常驻组件树：隐藏时整体位移出屏、遮罩停用点击（等价 Flutter 的
 * `IgnorePointer(ignoring: !visible)`）；打开时底部滑入全屏面板，
 * 动画 150ms + 线性（AppDuration.sheet / AppEasing.linear）。
 *
 * 功能：Token 列表（增删改 / 切换使用中）、添加表单、有效性校验（[checkToken]）。
 */
@Composable
fun QobuzTokenSheet(
    visible: Boolean,
    qobuzViewModel: QobuzViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val tokens by qobuzViewModel.state.collectAsState()

    var checkingId by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<QobuzTokenSheetDialog?>(null) }
    var pendingRemove by remember { mutableStateOf<QobuzToken?>(null) }

    // 新增 / 编辑走弹窗（与 KKBOX 账号、Apple Wrapper 地址共用 `MultiFieldDialog`）
    var editorVisible by remember { mutableStateOf(false) }
    var editorTarget by remember { mutableStateOf<QobuzToken?>(null) }
    var editorError by remember { mutableStateOf<String?>(null) }

    fun openEditor(target: QobuzToken?) {
        editorTarget = target
        editorError = null
        editorVisible = true
    }

    /** @return true = 已保存；false = 校验不过（弹窗保持打开） */
    fun handleSave(values: List<String>): Boolean {
        val name = values.getOrElse(0) { "" }.trim()
        val token = values.getOrElse(1) { "" }.trim()
        if (name.isEmpty() || token.isEmpty()) {
            editorError = "请填写名称和 Token"
            return false
        }
        val editing = editorTarget
        if (editing != null) {
            qobuzViewModel.updateToken(
                QobuzToken(id = editing.id, name = name, token = token, isActive = editing.isActive),
            )
        } else {
            qobuzViewModel.addToken(
                QobuzToken(
                    id = qobuzTokenSheetGenerateId(),
                    name = name,
                    token = token,
                    isActive = tokens.isEmpty(),
                ),
            )
        }
        editorVisible = false
        editorTarget = null
        return true
    }

    fun handleCheck(item: QobuzToken) {
        checkingId = item.id
        scope.launch {
            try {
                val result = checkToken(item.token)
                dialog = QobuzTokenSheetDialog(
                    title = if (result.valid) "有效" else "无效",
                    message = if (result.valid) "地区: ${result.region}" else "Token 无效或已过期",
                )
            } catch (e: Exception) {
                dialog = QobuzTokenSheetDialog("检测失败", qobuzTokenSheetErrorText(e))
            } finally {
                checkingId = null
            }
        }
    }

    BackHandler(enabled = visible) { onClose() }

    val scrimAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.linear),
        label = "qobuzTokenScrim",
    )
    val slide by animateFloatAsState(
        targetValue = if (visible) 0f else 1f,
        animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.linear),
        label = "qobuzTokenSlide",
    )

    // 【关键 · 全屏浮层不得吃掉下层点击】
    // Compose 里 `clickable(enabled = false)` **依然占据命中测试**，会阻止事件传给下层兄弟节点。
    // 这些浮层的遮罩是 fillMaxSize，一旦常驻就会把顶栏抽屉按钮 / 搜索框 / 模式下拉 / ⇄ 按钮
    // 的点击全部挡掉（现象：界面能显示，但所有按钮点不动）。
    // 因此完全隐藏时**整个浮层不参与组合**，等价 Flutter 的 IgnorePointer(ignoring: !visible)。
    if (!visible && scrimAlpha == 0f) return
    Box(modifier = modifier.fillMaxSize()) {
        // 遮罩：只吞点击，**不关闭**（退出只走返回手势 / 标题栏「关闭」）
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

        // 全屏面板：自下而上滑入 / 滑出
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = slide * size.height }
                .background(AppColors.background)
                // 面板必须自己**吃掉点击**：本面板是全屏的，而 Modifier.background 不参与命中测试，
                // 一旦不吃事件，点面板空白处就会穿透到后面整屏遮罩的 onClose → 表现为「一碰就返回」。
                // 关页只走系统返回手势（BackHandler）或标题栏关闭按钮。
                .clickableNoRipple { }
                .statusBarsPadding()
                .navigationBarsPadding()
                // MainActivity 用 adjustNothing（窗口不随键盘缩放，迷你条才不会被顶起来），
                // 所以这里面板需自己按 IME 高度上移，保证输入框不被键盘遮住
                .imePadding(),
        ) {
            QobuzTokenSheetHeader(
                onClose = onClose,
                onAdd = { openEditor(null) },
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (tokens.isEmpty()) {
                    QobuzTokenSheetEmpty()
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = AppSpacing.sheetBottomInset),
                    ) {
                        items(items = tokens, key = { it.id }) { item ->
                            QobuzTokenSheetItem(
                                token = item,
                                checking = checkingId == item.id,
                                onCheck = { handleCheck(item) },
                                onSelect = { qobuzViewModel.setActive(item.id) },
                                onEdit = { openEditor(item) },
                                onRemove = { pendingRemove = item },
                            )
                        }
                    }
                }
            }
        }

        // 新增 / 编辑弹窗（与 KKBOX 账号、Apple Wrapper 地址共用同一个组件）
        MultiFieldDialog(
            visible = editorVisible,
            title = if (editorTarget != null) "编辑 Token" else "添加 Token",
            fields = listOf(
                MultiFieldDialogField(
                    hint = "名称（如：主账号）",
                    initial = editorTarget?.name.orEmpty(),
                ),
                MultiFieldDialogField(
                    hint = "Token",
                    initial = editorTarget?.token.orEmpty(),
                    imeAction = ImeAction.Done,
                ),
            ),
            error = editorError,
            confirmLabel = if (editorTarget != null) "更新" else "保存",
            onDismiss = {
                editorVisible = false
                editorTarget = null
                editorError = null
            },
            onConfirm = { values -> handleSave(values) },
        )

        // 提示弹窗（对齐 Dart `_alert`）
        dialog?.let { d ->
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(text = d.title, style = AnnieTypography.dialogTitle) },
                text = { Text(text = d.message, style = AnnieTypography.caption) },
                confirmButton = {
                    TextButton(onClick = { dialog = null }) {
                        Text(text = "好", color = AppColors.primary, fontWeight = AppFontWeight.semiBold)
                    }
                },
                containerColor = AppColors.surface,
            )
        }

        // 删除确认（对齐 Dart `_handleRemove` 里的 showDialog<bool>）
        pendingRemove?.let { item ->
            AlertDialog(
                onDismissRequest = { pendingRemove = null },
                title = { Text(text = "删除 Token", style = AnnieTypography.dialogTitle) },
                text = { Text(text = "确定删除 \"${item.name}\" 吗？", style = AnnieTypography.caption) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            qobuzViewModel.removeToken(item.id)
                            pendingRemove = null
                        },
                    ) {
                        Text(text = "删除", color = AppColors.danger, fontWeight = AppFontWeight.semiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingRemove = null }) {
                        Text(text = "取消", color = AppColors.textTertiary)
                    }
                },
                containerColor = AppColors.surface,
            )
        }
    }
}

/** 提示 / 确认弹窗的文案载体 */
private class QobuzTokenSheetDialog(val title: String, val message: String)

/** Token id 生成（对齐 Dart `_generateId`：毫秒时间戳 36 进制 + 随机 36 进制） */
private fun qobuzTokenSheetGenerateId(): String =
    System.currentTimeMillis().toString(36) + Random.nextInt(1 shl 20).toString(36)

/** Token 打码（对齐 Dart `_maskToken`） */
private fun qobuzTokenSheetMaskToken(token: String): String {
    if (token.length <= 8) return "****"
    return "${token.substring(0, 4)}****${token.substring(token.length - 4)}"
}

/** 错误文案（对齐 Dart `_errText`：只取 message，Kotlin 异常前缀为类名故直接读 message） */
private fun qobuzTokenSheetErrorText(e: Throwable): String {
    val message = e.message
    return if (!message.isNullOrEmpty()) message else e.toString()
}

/** 头部：返回 + 标题 + 「+」（点开新增弹窗） */
@Composable
private fun QobuzTokenSheetHeader(
    onClose: () -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = AppSpacing.sp24,
                top = AppSpacing.sp40,
                end = AppSpacing.sp24,
                bottom = AppSpacing.sp8,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 统一的返回按钮（见 `SheetBackButton`）
        SheetBackButton(onClose = onClose)
        Spacer(modifier = Modifier.size(AppSpacing.sp8))
        Text(
            text = "Qobuz Token 管理",
            modifier = Modifier.weight(1f),
            style = AnnieTypography.screenTitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            modifier = Modifier
                .clickableNoRipple(onAdd)
                .padding(AppSpacing.sp4),
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "添加 Token",
                tint = AppColors.primary,
                modifier = Modifier.size(AppIconSize.icon24),
            )
        }
    }
}

/** 空态（对齐 Dart `_buildList` 的空列表分支） */
@Composable
private fun QobuzTokenSheetEmpty() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        QobuzTokenSheetKeyGlyph(modifier = Modifier.size(AppIconSize.icon48), tint = AppColors.whiteA06)
        Spacer(modifier = Modifier.height(AppSpacing.sp12))
        Text(text = "暂无 Token，点击右上角添加", color = AppColors.whiteA20, fontSize = AppFontSize.lg)
    }
}

/** Token 列表项（对齐 Dart `_TokenItem`） */
@Composable
private fun QobuzTokenSheetItem(
    token: QobuzToken,
    checking: Boolean,
    onCheck: () -> Unit,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(AppSize.accountItemHeight)
            .background(if (token.isActive) AppColors.success.copy(alpha = 0.04f) else Color.Transparent)
            .drawBehind {
                val stroke = AppBorder.thin.toPx()
                drawLine(
                    color = AppColors.whiteA04,
                    start = Offset(0f, size.height - stroke / 2f),
                    end = Offset(size.width, size.height - stroke / 2f),
                    strokeWidth = stroke,
                )
            },
    ) {
        // 使用中：左侧竖条（3 x 54，右圆角）
        if (token.isActive) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = AppSpacing.sp18)
                    .size(width = AppSpacing.sp3, height = AppSize.tokenItemHeight)
                    .background(
                        color = AppColors.success,
                        shape = RoundedCornerShape(topEnd = AppRadius.r2, bottomEnd = AppRadius.r2),
                    ),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = AppSpacing.sp24, vertical = AppSpacing.sp14),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 验证按钮
            Column(
                modifier = Modifier
                    .clickableNoRipple(onCheck)
                    .padding(
                        start = AppSpacing.sp8,
                        top = AppSpacing.sp8,
                        end = AppSpacing.sp18,
                        bottom = AppSpacing.sp8,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (checking) {
                    Box(modifier = Modifier.size(AppIconSize.icon22), contentAlignment = Alignment.Center) {
                        Text(text = "...", color = AppColors.primary, fontSize = AppFontSize.size10)
                    }
                } else {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = AppColors.glyphMid,
                        modifier = Modifier.size(AppIconSize.icon22),
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.sp2))
                    Text(text = "验证", color = AppColors.white30, fontSize = AppFontSize.size10)
                }
            }
            // 主体：名称 + 使用中标签 + 打码 Token
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickableNoRipple(onSelect),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.Start,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = token.name,
                        modifier = Modifier.weight(1f, fill = false),
                        style = AnnieTypography.dialogTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (token.isActive) {
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
                Spacer(modifier = Modifier.height(AppSpacing.sp3))
                Text(
                    text = qobuzTokenSheetMaskToken(token.token),
                    color = AppColors.white30,
                    fontSize = AppFontSize.base,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 编辑 / 删除
            Box(
                modifier = Modifier
                    .clickableNoRipple(onEdit)
                    .padding(AppSpacing.sp6),
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null,
                    tint = AppColors.glyphMid,
                    modifier = Modifier.size(AppIconSize.icon20),
                )
            }
            Box(
                modifier = Modifier
                    .clickableNoRipple(onRemove)
                    .padding(AppSpacing.sp6),
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = AppColors.glyphDim,
                    modifier = Modifier.size(AppIconSize.icon20),
                )
            }
        }
    }
}

/**
 * 「密钥」图标：圆环 + 齿杆。
 * 形状对齐 Flutter `Icons.vpn_key_outlined`（核心图标集无此图标，自绘等价剪影）。
 */
@Composable
private fun QobuzTokenSheetKeyGlyph(
    modifier: Modifier = Modifier,
    tint: Color = AppColors.whiteA06,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.1f
        drawCircle(
            color = tint,
            radius = w * 0.18f,
            center = Offset(w * 0.24f, h * 0.5f),
            style = Stroke(width = stroke),
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.42f, h * 0.5f),
            end = Offset(w * 0.90f, h * 0.5f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.72f, h * 0.5f),
            end = Offset(w * 0.72f, h * 0.74f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.88f, h * 0.5f),
            end = Offset(w * 0.88f, h * 0.74f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}
