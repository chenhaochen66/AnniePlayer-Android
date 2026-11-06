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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.data.model.AppleWrapper
import com.annieplayer.annie_music.kt.data.sources.apple.wrapper.APPLE_LOCAL_WRAPPER_BASE
import com.annieplayer.annie_music.kt.data.sources.apple.wrapper.AppleServiceSettings
import com.annieplayer.annie_music.kt.data.sources.apple.wrapper.AppleWrapperApi
import com.annieplayer.annie_music.kt.data.sources.apple.wrapper.AppleWrapperRegistry
import com.annieplayer.annie_music.kt.data.sources.apple.wrapper.parseWrapperUrl
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
import kotlinx.coroutines.launch

/**
 * 「Apple Music 服务」浮层：**本机切换 + 远程 Wrapper 地址管理**。
 *
 * ## 两个源，二选一
 *
 * - 开关「使用本机服务」打开 → 一切请求打本机 WrapperLite（[APPLE_LOCAL_WRAPPER_BASE]），
 *   下面的远程地址列表**不生效**（保留着，方便随时切回去）。
 * - 开关关闭 → 用列表里选中（「使用中」）的那条远程地址，地址里的 userinfo 按 HTTP Basic 发。
 * - 关掉开关又没有远程地址 / 打开开关但本机服务没起来 → 播放与下载**直接失败并提示**，
 *   不会自动换源（静默回落会让人分不清在用哪台机、烧的是谁家账号）。
 *
 * 第三方站点（ak1ra）、Cloudflare 挑战、隐藏 WebView 兜底、原地重试那一套已全部删除。
 *
 * 交互与视觉对齐 [QobuzTokenSheet]（增删改 / 单选使用中 / 每条可检测）。
 */
@Composable
fun AppleServiceSheet(
    visible: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val wrappers by AppleWrapperRegistry.wrappers.collectAsState()

    var useLocal by remember { mutableStateOf(AppleServiceSettings.useLocal) }
    var checkingId by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<AppleServiceSheetDialog?>(null) }
    var pendingRemove by remember { mutableStateOf<AppleWrapper?>(null) }

    // 添加 / 编辑地址走**弹窗**（与「在线导入插件」同款）：
    //   editorVisible = 弹窗是否打开；editorTarget = null 表示新增，非 null 表示改这一条
    var editorVisible by remember { mutableStateOf(false) }
    var editorTarget by remember { mutableStateOf<AppleWrapper?>(null) }
    var editorError by remember { mutableStateOf<String?>(null) }

    // 本机检测用原来那套「按钮 + 结果一行」的样式（与第三方旧版一致）
    var localChecking by remember { mutableStateOf(false) }
    var localResult by remember { mutableStateOf<String?>(null) }
    var localOk by remember { mutableStateOf(false) }

    fun openEditor(target: AppleWrapper?) {
        editorTarget = target
        editorError = null
        editorVisible = true
    }

    /** 保存地址；返回 false 时弹窗不关，把原因写在弹窗里 */
    fun save(raw: String): Boolean {
        val editing = editorTarget
        val ok = if (editing != null) {
            AppleWrapperRegistry.update(editing.id, raw)
        } else {
            AppleWrapperRegistry.add(raw) != null
        }
        if (!ok) {
            editorError = "地址解析不出来，或与已有条目重复"
            return false
        }
        editorVisible = false
        editorTarget = null
        return true
    }

    fun check(id: String?, rawUrl: String) {
        checkingId = id
        scope.launch {
            val r = AppleWrapperApi.checkStatus(rawUrl)
            checkingId = null
            dialog = AppleServiceSheetDialog(
                title = if (r.isSuccess) "连接正常" else "连接失败",
                message = r.fold(
                    onSuccess = { it },
                    onFailure = { e -> e.message ?: e::class.simpleName ?: "未知错误" },
                ),
            )
        }
    }

    BackHandler(enabled = visible) { onClose() }

    val scrimAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.linear),
        label = "appleServiceScrim",
    )
    val slide by animateFloatAsState(
        targetValue = if (visible) 0f else 1f,
        animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.linear),
        label = "appleServiceSlide",
    )

    // 【关键 · 全屏浮层不得吃掉下层点击】完全隐藏时不参与组合（`clickable(enabled=false)`
    // 依然占据命中测试，遮罩常驻会把顶栏 / 搜索框 / 迷你条的点击全挡掉）。
    if (!visible && scrimAlpha == 0f) return

    Box(modifier = modifier.fillMaxSize()) {
        // 遮罩：只吞点击，**不关闭**（退出只走返回手势 / 标题栏返回）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = scrimAlpha }
                .clickable(
                    enabled = visible,
                    interactionSource = remember { MutableInteractionSource() },
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
                .statusBarsPadding()
                .navigationBarsPadding()
                // MainActivity 用 adjustNothing，输入框要自己跟着键盘上移
                .imePadding(),
        ) {
            // ── 标题栏：返回 + 标题 + 「+」 ──
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
                SheetBackButton(onClose = onClose)
                Spacer(modifier = Modifier.size(AppSpacing.sp8))
                Text(
                    text = "Apple Music 服务",
                    modifier = Modifier.weight(1f),
                    style = AnnieTypography.screenTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    modifier = Modifier
                        .clickableNoRipple { openEditor(null) }
                        .padding(AppSpacing.sp4),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "添加 Wrapper 地址",
                        tint = AppColors.primary,
                        modifier = Modifier.size(AppIconSize.icon24),
                    )
                }
            }

            // ── 本机开关 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.screenPaddingH, vertical = AppSpacing.sp8),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "使用本机服务",
                        color = AppColors.white,
                        fontSize = AppFontSize.base,
                    )
                    Spacer(Modifier.height(AppSpacing.sp4))
                    Text(
                        text = if (useLocal) {
                            "查询与密钥都走本机 WrapperLite；下面的远程地址暂不生效"
                        } else {
                            "改用下面「使用中」的远程 Wrapper 地址"
                        },
                        color = AppColors.white54,
                        fontSize = AppFontSize.sm,
                    )
                }
                Switch(
                    checked = useLocal,
                    onCheckedChange = { value ->
                        useLocal = value
                        AppleServiceSettings.setUseLocal(value)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = AppColors.background,
                        checkedTrackColor = AppColors.white,
                        uncheckedThumbColor = AppColors.white54,
                        uncheckedTrackColor = AppColors.whiteA06,
                    ),
                )
            }

            // ── 本机地址 + 检测（原样式：地址一行、全宽「检测服务」按钮、结果一行） ──
            if (useLocal) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.screenPaddingH, vertical = AppSpacing.sp12),
                ) {
                    Text(
                        text = APPLE_LOCAL_WRAPPER_BASE,
                        color = AppColors.white54,
                        fontSize = AppFontSize.sm,
                    )
                    Spacer(Modifier.height(AppSpacing.sp12))
                    Button(
                        onClick = {
                            if (localChecking) return@Button
                            localChecking = true
                            localResult = null
                            scope.launch {
                                val r = AppleWrapperApi.checkLocalStatus()
                                localChecking = false
                                localOk = r.isSuccess
                                localResult = r.fold(
                                    onSuccess = { it },
                                    onFailure = { e ->
                                        "连接失败：" + (e.message ?: e::class.simpleName ?: "未知错误")
                                    },
                                )
                            }
                        },
                        enabled = !localChecking,
                        shape = RoundedCornerShape(AppRadius.card),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppColors.whiteA06,
                            contentColor = AppColors.white,
                            disabledContainerColor = AppColors.whiteA06,
                            disabledContentColor = AppColors.white54,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                    ) {
                        if (localChecking) {
                            CircularProgressIndicator(
                                color = AppColors.white54,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.height(AppSpacing.sp8))
                            Text(
                                text = "检测中…",
                                fontSize = AppFontSize.base,
                                color = AppColors.white54,
                            )
                        } else {
                            Text(
                                text = "检测服务",
                                fontSize = AppFontSize.base,
                                color = AppColors.white,
                            )
                        }
                    }
                    localResult?.let { text ->
                        Spacer(Modifier.height(AppSpacing.sp12))
                        Text(
                            text = text,
                            color = if (localOk) AppColors.white else AppColors.danger,
                            fontSize = AppFontSize.sm,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(AppSpacing.sp8))
                    Text(
                        text = "由 WrapperLite 应用托管：先在那边登录 Apple ID 并启动服务，" +
                            "这里检测通过后即可使用。App 直连端口是 12341，12340 仅给网页工具用。",
                        color = AppColors.white54,
                        fontSize = AppFontSize.sm,
                    )
                }
            }

            // ── 远程地址列表 ──
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (wrappers.isEmpty()) {
                    AppleServiceSheetEmpty()
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = AppSpacing.sheetBottomInset),
                    ) {
                        items(items = wrappers, key = { it.id }) { item ->
                            AppleServiceSheetItem(
                                wrapper = item,
                                // 开关打开时远程地址不生效：整列压暗并禁用「使用中」的强调
                                dimmed = useLocal,
                                checking = checkingId == item.id,
                                onCheck = { check(item.id, item.url) },
                                onSelect = { AppleWrapperRegistry.setActive(item.id) },
                                onEdit = { openEditor(item) },
                                onRemove = { pendingRemove = item },
                            )
                        }
                    }
                }
            }
        }

        // 添加 / 编辑地址弹窗（点「+」或条目上的铅笔）—— 与 Qobuz / KKBOX 共用同一个组件
        MultiFieldDialog(
            visible = editorVisible,
            title = if (editorTarget != null) "编辑 Wrapper 地址" else "添加 Wrapper 地址",
            fields = listOf(
                MultiFieldDialogField(
                    hint = "https://<token>@host",
                    initial = editorTarget?.url.orEmpty(),
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
            ),
            error = editorError,
            tip = "地址可带 token：https://<token>@host；本机/局域网可只写 host:port",
            confirmLabel = if (editorTarget != null) "更新" else "保存",
            onDismiss = {
                editorVisible = false
                editorTarget = null
                editorError = null
            },
            onConfirm = { values -> save(values.firstOrNull().orEmpty()) },
        )

        // 提示弹窗
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

        // 删除确认
        pendingRemove?.let { item ->
            AlertDialog(
                onDismissRequest = { pendingRemove = null },
                title = { Text(text = "删除地址", style = AnnieTypography.dialogTitle) },
                text = {
                    Text(
                        text = "确定删除 ${parseWrapperUrl(item.url)?.display ?: item.url} 吗？",
                        style = AnnieTypography.caption,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            AppleWrapperRegistry.remove(item.id)
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

/** 提示弹窗的文案载体 */
private class AppleServiceSheetDialog(val title: String, val message: String)

/** 空态 */
@Composable
private fun AppleServiceSheetEmpty() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "暂无远程地址，点击右上角 + 添加",
            color = AppColors.whiteA20,
            fontSize = AppFontSize.lg,
        )
        Spacer(modifier = Modifier.height(AppSpacing.sp8))
        Text(
            text = "地址形如 https://<token>@your-wrapper-host",
            color = AppColors.white30,
            fontSize = AppFontSize.sm,
        )
    }
}

/** 远程地址列表项：检测 / 地址（原样显示，不打码）/ 使用中 / 改 / 删 */
@Composable
private fun AppleServiceSheetItem(
    wrapper: AppleWrapper,
    dimmed: Boolean,
    checking: Boolean,
    onCheck: () -> Unit,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    val active = wrapper.isActive && !dimmed
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(AppSize.accountItemHeight)
            .background(if (active) AppColors.success.copy(alpha = 0.04f) else Color.Transparent)
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
        if (active) {
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
            // 检测
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
                    Text(text = "检测", color = AppColors.white30, fontSize = AppFontSize.size10)
                }
            }
            // 地址（不打码：token 就是这条地址的一部分，用户要能一眼核对）
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickableNoRipple(onSelect),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.Start,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = parseWrapperUrl(wrapper.url)?.display ?: wrapper.url,
                        modifier = Modifier.weight(1f, fill = false),
                        style = AnnieTypography.dialogTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (dimmed) AppColors.white54 else AppColors.white,
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
                Spacer(modifier = Modifier.height(AppSpacing.sp3))
                Text(
                    text = wrapper.url,
                    color = AppColors.white30,
                    fontSize = AppFontSize.sm,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
