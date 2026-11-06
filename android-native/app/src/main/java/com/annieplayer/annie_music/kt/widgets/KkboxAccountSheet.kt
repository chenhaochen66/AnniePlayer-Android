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
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import com.annieplayer.annie_music.kt.core.state.KkboxViewModel
import com.annieplayer.annie_music.kt.data.model.KkboxAccount
import com.annieplayer.annie_music.kt.data.model.KkboxSession
import com.annieplayer.annie_music.kt.data.sources.kkbox.login
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
 * KKBOX 账号管理浮层（1:1 移植 Flutter `lib/widgets/kkbox_account_sheet.dart`，
 * 视觉对齐 RN 版 `components/KkboxAccountOverlay.tsx`）。
 *
 * 由宿主传入 [visible]（对应 Dart `uiProvider.kkboxAccountVisible`）控制显隐；
 * 常驻组件树：隐藏时整体位移出屏、遮罩停用点击（等价 Flutter 的
 * `IgnorePointer(ignoring: !visible)`，沿用 DownloadQueueSheet 已验证的写法）；
 * 打开时底部滑入全屏面板，动画 150ms + 线性（AppDuration.sheet / AppEasing.linear）。
 *
 * 功能：账号列表（增删改 / 切换使用中）、登录表单（email + password -> [login]，
 * 并把 session 写回 [KkboxViewModel]）。
 */
@Composable
fun KkboxAccountSheet(
    visible: Boolean,
    kkboxViewModel: KkboxViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val accounts by kkboxViewModel.state.collectAsState()

    var loginLoadingId by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<KkboxAccountSheetDialog?>(null) }
    var pendingRemove by remember { mutableStateOf<KkboxAccount?>(null) }

    // 新增 / 编辑走弹窗（与 Qobuz Token、Apple Wrapper 地址共用 `MultiFieldDialog`）
    var editorVisible by remember { mutableStateOf(false) }
    var editorTarget by remember { mutableStateOf<KkboxAccount?>(null) }
    var editorError by remember { mutableStateOf<String?>(null) }

    fun openEditor(target: KkboxAccount?) {
        editorTarget = target
        editorError = null
        editorVisible = true
    }

    /** @return true = 已保存；false = 校验不过（弹窗保持打开） */
    fun handleSave(values: List<String>): Boolean {
        val name = values.getOrElse(0) { "" }.trim()
        val email = values.getOrElse(1) { "" }.trim()
        val password = values.getOrElse(2) { "" }
        if (name.isEmpty() || email.isEmpty() || password.isEmpty()) {
            editorError = "请填写名称、邮箱和密码"
            return false
        }
        val editing = editorTarget
        if (editing != null) {
            kkboxViewModel.updateAccount(
                KkboxAccount(
                    id = editing.id,
                    name = name,
                    email = email,
                    password = password,
                    session = editing.session,
                    isActive = editing.isActive,
                ),
            )
        } else {
            kkboxViewModel.addAccount(
                KkboxAccount(
                    id = kkboxAccountSheetGenerateId(),
                    name = name,
                    email = email,
                    password = password,
                    isActive = accounts.isEmpty(),
                ),
            )
        }
        editorVisible = false
        editorTarget = null
        return true
    }

    fun handleLogin(item: KkboxAccount) {
        loginLoadingId = item.id
        scope.launch {
            try {
                val result = login(item.email, item.password)
                kkboxViewModel.updateAccountSession(
                    item.id,
                    KkboxSession(
                        sid = result.sid,
                        licContentKey = result.licContentKey,
                        highQuality = result.highQuality,
                        kkid = result.kkid,
                        email = item.email,
                        loggedIn = true,
                    ),
                )
                if (!item.isActive) kkboxViewModel.setActive(item.id)
                dialog = KkboxAccountSheetDialog("成功", "KKBOX 登录成功")
            } catch (e: Exception) {
                dialog = KkboxAccountSheetDialog("登录失败", kkboxAccountSheetErrorText(e))
            } finally {
                loginLoadingId = null
            }
        }
    }

    BackHandler(enabled = visible) { onClose() }

    val scrimAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.linear),
        label = "kkboxAccountScrim",
    )
    val slide by animateFloatAsState(
        targetValue = if (visible) 0f else 1f,
        animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.linear),
        label = "kkboxAccountSlide",
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
            KkboxAccountSheetHeader(
                onClose = onClose,
                onAdd = { openEditor(null) },
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (accounts.isEmpty()) {
                    KkboxAccountSheetEmpty()
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = AppSpacing.sheetBottomInset),
                    ) {
                        items(items = accounts, key = { it.id }) { item ->
                            KkboxAccountSheetItem(
                                account = item,
                                loginLoading = loginLoadingId == item.id,
                                onLogin = { handleLogin(item) },
                                onSelect = { kkboxViewModel.setActive(item.id) },
                                onEdit = { openEditor(item) },
                                onRemove = { pendingRemove = item },
                            )
                        }
                    }
                }
            }
        }

        // 新增 / 编辑弹窗（与 Qobuz Token、Apple Wrapper 地址共用同一个组件）
        MultiFieldDialog(
            visible = editorVisible,
            title = if (editorTarget != null) "编辑 KKBOX 账号" else "添加 KKBOX 账号",
            fields = listOf(
                MultiFieldDialogField(
                    hint = "名称（如：主账号）",
                    initial = editorTarget?.name.orEmpty(),
                ),
                MultiFieldDialogField(
                    hint = "邮箱",
                    initial = editorTarget?.email.orEmpty(),
                    keyboardType = KeyboardType.Email,
                ),
                MultiFieldDialogField(
                    hint = "密码",
                    initial = editorTarget?.password.orEmpty(),
                    password = true,
                    imeAction = ImeAction.Done,
                ),
            ),
            error = editorError,
            tip = "保存后需要点条目上的登录按钮完成 KKBOX 登录",
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
                title = { Text(text = "删除账号", style = AnnieTypography.dialogTitle) },
                text = { Text(text = "确定删除 \"${item.name}\" 吗？", style = AnnieTypography.caption) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            kkboxViewModel.removeAccount(item.id)
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
private class KkboxAccountSheetDialog(val title: String, val message: String)

/** 账号 id 生成（对齐 Dart `_generateId`：毫秒时间戳 36 进制 + 随机 36 进制） */
private fun kkboxAccountSheetGenerateId(): String =
    System.currentTimeMillis().toString(36) + Random.nextInt(1 shl 20).toString(36)

/** 邮箱打码（对齐 Dart `_maskEmail`） */
private fun kkboxAccountSheetMaskEmail(email: String): String {
    if (email.length <= 6) return "****"
    val at = email.indexOf('@')
    if (at <= 1) {
        return if (at < 0) "****" else "${email.substring(0, 1)}****${email.substring(at)}"
    }
    return "${email.substring(0, 2)}****${email.substring(at)}"
}

/** 错误文案（对齐 Dart `_errText`：只取 message，Kotlin 异常前缀为类名故直接读 message） */
private fun kkboxAccountSheetErrorText(e: Throwable): String {
    val message = e.message
    return if (!message.isNullOrEmpty()) message else e.toString()
}

/** 头部：返回 + 标题 + 新增 / 关闭切换（对齐 Dart `_buildHeader`） */
@Composable
private fun KkboxAccountSheetHeader(
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
            text = "KKBOX 账号管理",
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
                contentDescription = "添加 KKBOX 账号",
                tint = AppColors.primary,
                modifier = Modifier.size(AppIconSize.icon24),
            )
        }
    }
}

/** 空态（对齐 Dart `_buildList` 的空列表分支） */
@Composable
private fun KkboxAccountSheetEmpty() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DetailPersonOutline(modifier = Modifier.size(AppIconSize.icon48), tint = AppColors.whiteA06)
        Spacer(modifier = Modifier.height(AppSpacing.sp12))
        Text(text = "暂无账号，点击右上角添加", color = AppColors.whiteA20, fontSize = AppFontSize.lg)
    }
}

/** 账号列表项（对齐 Dart `_AccountItem`） */
@Composable
private fun KkboxAccountSheetItem(
    account: KkboxAccount,
    loginLoading: Boolean,
    onLogin: () -> Unit,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    val loggedIn = account.session?.loggedIn == true

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(AppSize.accountItemHeight)
            .background(if (account.isActive) AppColors.success.copy(alpha = 0.04f) else Color.Transparent)
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
        if (account.isActive) {
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
            // 登录 / 续期按钮
            Column(
                modifier = Modifier
                    .clickableNoRipple(onLogin)
                    .padding(
                        start = AppSpacing.sp8,
                        top = AppSpacing.sp8,
                        end = AppSpacing.sp18,
                        bottom = AppSpacing.sp8,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (loginLoading) {
                    Box(modifier = Modifier.size(AppIconSize.icon22), contentAlignment = Alignment.Center) {
                        Text(text = "...", color = AppColors.primary, fontSize = AppFontSize.size10)
                    }
                } else {
                    if (loggedIn) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = AppColors.glyphMid,
                            modifier = Modifier.size(AppIconSize.icon22),
                        )
                    } else {
                        KkboxAccountSheetLoginGlyph(
                            modifier = Modifier.size(AppIconSize.icon22),
                            tint = AppColors.glyphMid,
                        )
                    }
                    Spacer(modifier = Modifier.height(AppSpacing.sp2))
                    Text(
                        text = if (loggedIn) "续期" else "登录",
                        color = AppColors.white30,
                        fontSize = AppFontSize.size10,
                    )
                }
            }
            // 主体：名称 + 使用中标签 + 邮箱 / 未登录
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickableNoRipple(onSelect),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.Start,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = account.name,
                        modifier = Modifier.weight(1f, fill = false),
                        style = AnnieTypography.dialogTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (account.isActive) {
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
                    text = if (loggedIn) kkboxAccountSheetMaskEmail(account.email) else "未登录",
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
 * 登录图标：横杆 + 右向箭头 + 右侧门框。
 * 形状对齐 Flutter `Icons.login`（核心图标集无此图标，自绘等价剪影）。
 */
@Composable
private fun KkboxAccountSheetLoginGlyph(
    modifier: Modifier = Modifier,
    tint: Color = AppColors.whiteA40,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.1f
        drawLine(
            color = tint,
            start = Offset(w * 0.10f, h * 0.5f),
            end = Offset(w * 0.58f, h * 0.5f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.40f, h * 0.28f),
            end = Offset(w * 0.62f, h * 0.5f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.40f, h * 0.72f),
            end = Offset(w * 0.62f, h * 0.5f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        val door = Path().apply {
            moveTo(w * 0.72f, h * 0.14f)
            lineTo(w * 0.92f, h * 0.14f)
            lineTo(w * 0.92f, h * 0.86f)
            lineTo(w * 0.72f, h * 0.86f)
        }
        drawPath(
            path = door,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/**
 * 登录图标：横杆 + 右向箭头 + 右侧门框。
 * 形状对齐 Flutter `Icons.login`（核心图标集无此图标，自绘等价剪影）。
 */
