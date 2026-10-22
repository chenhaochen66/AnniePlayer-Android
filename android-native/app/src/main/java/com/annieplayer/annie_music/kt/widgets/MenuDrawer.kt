package com.annieplayer.annie_music.kt.widgets

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppLetterSpacing
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 菜单抽屉（1:1 移植 Flutter `lib/widgets/menu_drawer.dart`）。
 *
 * 布局与动效（必须保留）：
 * - 面板宽度 = 屏宽 * 0.72（[AppSize.drawerWidthFraction]），底色 [AppColors.drawerPanel]。
 * - 遮罩 `Colors.black54`（[AppColors.black54]），**仅在打开时存在**（瞬时出现，无淡入）。
 * - 面板左滑 **150ms + 线性曲线**（Flutter `AnimatedPositioned(duration: 150ms,
 *   默认 Curves.linear)` → [AppDuration.sheet] + [AppEasing.linear]）。
 * - 顶部内边距 = 状态栏高度 + 40dp；内容左右 20dp、底部 100dp（避开迷你播放条）。
 * - 返回手势打开时先关抽屉（对齐 Flutter `PopScope(canPop: !isOpen)`）。
 *
 * Compose 侧差异说明：
 * - Flutter `Icons.chevron_right` → 核心图标集里最接近的
 *   `Icons.Default.KeyboardArrowRight`（与 `AppSheetRow` 的处理一致）。
 * - `key_outlined` / `folder_open` / `folder_outlined` / `create_new_folder_outlined` /
 *   `delete_outline` / `public` 均不在 `material-icons-core` 中，这里按 Flutter 图标栅格自绘。
 * - 状态一律由调用方以「显式布尔 / 文本 / 回调」传入（不在组件内部 FutureBuilder / provider）。
 */
@Composable
fun MenuDrawer(
    visible: Boolean,
    onClose: () -> Unit,
    onAppleService: () -> Unit,
    onQobuzToken: () -> Unit,
    onKkboxAccount: () -> Unit,
    onDownloadQueue: () -> Unit,
    cacheSize: MenuDrawerCacheSize,
    onClearCache: () -> Unit,
    onMoreSettings: () -> Unit,
    /** null = 检查中；与 Flutter `_ManageStorageRow._granted` 同义 */
    storageGranted: Boolean?,
    onRequestStorage: () -> Unit,
    /** 是否已在「电池优化」白名单里；null = 检查中 */
    batteryExempt: Boolean?,
    /** 参数 = 目标状态：true 申请加入白名单，false 跳系统设置页移除 */
    onToggleBattery: (Boolean) -> Unit,
    saveDirPath: String?,
    saveDirLoading: Boolean,
    onPickSaveDir: () -> Unit,
    onClearSaveDir: () -> Unit,
    onContact: () -> Unit,
    modifier: Modifier = Modifier,
    onHifiSite: () -> Unit = {},
    onSpectrumSite: () -> Unit = {},
) {
    // 返回手势：打开时先关抽屉
    BackHandler(enabled = visible) { onClose() }

    val slide = animateFloatAsState(
        targetValue = if (visible) 0f else 1f,
        animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.linear),
        label = "menuDrawerSlide",
    )
    val statusBarTop = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val panelWidth = maxWidth * AppSize.drawerWidthFraction

        // ── 遮罩：仅打开时存在（Flutter: if (isOpen) Positioned.fill） ──
        if (visible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppColors.black54)
                    .clickableNoRipple(onClose),
            )
        }

        // ── 面板：150ms 线性左滑（对齐 AnimatedPositioned(left: isOpen ? 0 : -width)） ──
        Column(
            modifier = Modifier
                .width(panelWidth)
                .fillMaxHeight()
                .offset { IntOffset((-slide.value * panelWidth.toPx()).roundToInt(), 0) }
                .background(AppColors.drawerPanel)
                // 抽屉铺满整个窗口（宿主不再统一内缩）：状态栏高度由自己补 → 标题与搜索页
                // 「安妮音乐盒」落在同一水平线（两边都是「状态栏 + 40dp」起始）。
                .padding(top = statusBarTop + AppSpacing.sp40)
                // 吸收列表区空白点击（等价 Flutter ListView 的 opaque 命中行为）
                .clickableNoRipple { }
                .verticalScroll(rememberScrollState())
                .padding(
                    start = AppSpacing.sp20,
                    end = AppSpacing.sp20,
                    bottom = AppSpacing.sheetBottomInset,
                ),
        ) {
            // ── 标题行（对齐 Flutter `menu_drawer.dart:89-114`）──
            // 「×」关闭图标（22dp / white70 / 内边距 4）+ 12dp 间距 + 「菜单」（20sp / w700 / white）。
            // 行高取 headerActionHeight(48dp)，与搜索页顶栏的「菜单按钮 + 大标题」同高 →
            // 抽屉标题与「安妮音乐盒」在屏幕上**高度对齐**。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(AppSize.headerActionHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = AppColors.white70,
                    modifier = Modifier
                        .clickableNoRipple(onClose)
                        .padding(AppSpacing.sp4)
                        .size(AppIconSize.icon22),
                )
                Spacer(modifier = Modifier.width(AppSpacing.sp12))
                Text(
                    text = "菜单",
                    color = AppColors.white,
                    fontSize = AppFontSize.size20,
                    fontWeight = AppFontWeight.bold,
                )
            }
            Spacer(modifier = Modifier.height(AppSpacing.sp32))

            // ── 系统（权限类，独立于账号；不再混在「账号配置」里） ──
            MenuDrawerSectionLabel(text = "系统")
            MenuDrawerRow(
                label = "所有文件访问",
                onTap = { if (storageGranted != true) onRequestStorage() },
                subtitle = when (storageGranted) {
                    null -> "检查中..."
                    true -> "已授权（可全盘读写）"
                    false -> "未授权，点击去开启"
                },
                icon = { MenuDrawerFolderOpenIcon(tint = AppColors.white54) },
            )
            MenuDrawerSwitchRow(
                label = "耗电优化",
                subtitle = when (batteryExempt) {
                    null -> "检查中..."
                    true -> "已加入白名单（熄屏后不被冻结）"
                    false -> "受系统限制，熄屏后可能被冻结"
                },
                checked = batteryExempt == true,
                onCheckedChange = onToggleBattery,
                icon = { MenuDrawerBatteryIcon(tint = AppColors.white54) },
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp8))

            // ── 账号配置 ──
            MenuDrawerSectionLabel(text = "账号配置")
            MenuDrawerRow(
                label = "Apple Music 服务",
                onTap = {
                    onAppleService()
                },
                icon = { MenuDrawerKeyIcon(tint = AppColors.white54) },
            )
            MenuDrawerRow(
                label = "Qobuz Token 管理",
                onTap = {
                    onQobuzToken()
                },
                icon = { MenuDrawerKeyIcon(tint = AppColors.white54) },
            )
            MenuDrawerRow(
                label = "KKBOX 账号管理",
                onTap = {
                    onKkboxAccount()
                },
                icon = { MenuDrawerKeyIcon(tint = AppColors.white54) },
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp8))

            // ── 下载 ──
            MenuDrawerSectionLabel(text = "下载")
            MenuDrawerRow(
                label = "下载任务",
                onTap = {
                    onDownloadQueue()
                },
                icon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.List,
                        contentDescription = null,
                        tint = AppColors.white54,
                        modifier = Modifier.fillMaxSize(),
                    )
                },
            )
            MenuDrawerSaveLocationCard(
                dirPath = saveDirPath,
                loading = saveDirLoading,
                onPick = onPickSaveDir,
                onClear = onClearSaveDir,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp8))

            // ── 缓存 ──
            MenuDrawerSectionLabel(text = "缓存")
            MenuDrawerRow(
                label = "清除缓存",
                onTap = {
                    onClearCache()
                },
                subtitle = menuDrawerCacheSubtitle(cacheSize),
                icon = { MenuDrawerDeleteOutlineIcon(tint = AppColors.white54) },
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp8))

            Spacer(modifier = Modifier.height(AppSpacing.sp8))

            // ── 设置 ──
            MenuDrawerSectionLabel(text = "设置")
            MenuDrawerRow(
                label = "更多设置",
                onTap = {
                    onMoreSettings()
                },
                subtitle = null,
                icon = {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = AppColors.white54,
                        modifier = Modifier.fillMaxSize(),
                    )
                },
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp8))

            // ── 关于 ──
            MenuDrawerSectionLabel(text = "关于")
            MenuDrawerRow(
                label = "HIFI 科普网站",
                onTap = onHifiSite,
                subtitle = "by@买葱花也要砍一刀(2801497426)",
                icon = { MenuDrawerGlobeIcon(tint = AppColors.white54) },
            )
            MenuDrawerRow(
                label = "频谱分析网站",
                onTap = onSpectrumSite,
                subtitle = "by@HIFI神医章鱼哥(430768125)",
                icon = { MenuDrawerGlobeIcon(tint = AppColors.white54) },
            )
            // 注意：Flutter 版点「联系我们」不关抽屉（联系弹窗叠在抽屉之上），此处保持一致
            MenuDrawerRow(
                label = "联系我们",
                onTap = onContact,
                icon = {
                    Icon(
                        imageVector = Icons.Default.MailOutline,
                        contentDescription = null,
                        tint = AppColors.white54,
                        modifier = Modifier.fillMaxSize(),
                    )
                },
            )
        }
    }
}

/**
 * 汉堡按钮（对齐 Flutter `DrawerController`）：右侧 8dp 外边距，`Icons.menu` 24dp white70。
 */
@Composable
fun MenuDrawerButton(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clickableNoRipple(onOpen)
            .padding(end = AppSpacing.sp8),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Menu,
            contentDescription = "菜单",
            tint = AppColors.white70,
            modifier = Modifier.size(AppIconSize.icon24),
        )
    }
}

/** 缓存大小三态（对齐 Flutter `cacheSizeProvider` 的 loading / data / error） */
sealed interface MenuDrawerCacheSize {
    /** 正在计算（Flutter: loading → `正在计算…`） */
    data object Loading : MenuDrawerCacheSize

    /** 计算失败（Flutter: error → `大小计算失败`） */
    data object Error : MenuDrawerCacheSize

    /** 计算完成；`bytes <= 0` 时显示 `暂无可清理内容` */
    data class Ready(val bytes: Long) : MenuDrawerCacheSize
}

/**
 * 缓存大小格式化（1:1 移植 Flutter `formatSize`）：
 * `< 1024` 显示 `B`，`< 1MB` 显示 `KB`，否则 `MB`，均保留 1 位小数（小数点固定为 `.`）。
 */
fun menuDrawerFormatSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

/** 「清除缓存」副标题（对齐 Flutter `cacheSizeProvider.when(...)` 文案） */
private fun menuDrawerCacheSubtitle(cacheSize: MenuDrawerCacheSize): String = when (cacheSize) {
    MenuDrawerCacheSize.Loading -> "正在计算…"
    MenuDrawerCacheSize.Error -> "大小计算失败"
    is MenuDrawerCacheSize.Ready -> if (cacheSize.bytes <= 0L) {
        "暂无可清理内容"
    } else {
        "当前 ${menuDrawerFormatSize(cacheSize.bytes)}｜播放缓存 + 封面/图片 + 下载文件 + 接口缓存"
    }
}

/** 区块标题：white30 / 12sp / w600 / letterSpacing 0.5，底部 12dp */
@Composable
private fun MenuDrawerSectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(bottom = AppSpacing.sp12),
        color = AppColors.white30,
        fontSize = AppFontSize.sm,
        fontWeight = AppFontWeight.semiBold,
        letterSpacing = AppLetterSpacing.wide,
    )
}

/**
 * 抽屉行（对齐 Flutter `_MenuRow`）：图标 18dp + 文案 15sp/w600 + 可选副标题 + 右箭头 16dp；
 * 卡片底 `white @0.03`、圆角 12、内边距 16、下外边距 12。
 */
@Composable
private fun MenuDrawerRow(
    label: String,
    onTap: () -> Unit,
    icon: @Composable () -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = AppSpacing.sp12)
            .clip(RoundedCornerShape(AppRadius.card))
            .background(AppColors.whiteA03)
            .clickableNoRipple(onTap)
            .padding(AppSpacing.sp16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(AppIconSize.icon18), contentAlignment = Alignment.Center) {
            icon()
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp10))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = AppColors.white,
                fontSize = AppFontSize.size15,
                fontWeight = AppFontWeight.semiBold,
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(AppSpacing.sp2))
                Text(
                    text = subtitle,
                    color = AppColors.white30,
                    fontSize = AppFontSize.xs,
                )
            }
        }
        Icon(
            imageVector = Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            tint = AppColors.white24,
            modifier = Modifier.size(AppIconSize.icon16),
        )
    }
}

/**
 * 抽屉开关行：与 [MenuDrawerRow] 同一张卡片，右侧换成 [Switch]。
 *
 * 整行可点（等价于点开关本身），开关配色沿用 Apple Music 服务页那套
 * 「白色轨道 + 底色滑块」，与「更多设置」里的其它开关保持一致。
 */
@Composable
private fun MenuDrawerSwitchRow(
    label: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = AppSpacing.sp12)
            .clip(RoundedCornerShape(AppRadius.card))
            .background(AppColors.whiteA03)
            .clickableNoRipple { onCheckedChange(!checked) }
            .padding(start = AppSpacing.sp16, top = AppSpacing.sp10, end = AppSpacing.sp12, bottom = AppSpacing.sp10),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(AppIconSize.icon18), contentAlignment = Alignment.Center) {
            icon()
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp10))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = AppColors.white,
                fontSize = AppFontSize.size15,
                fontWeight = AppFontWeight.semiBold,
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(AppSpacing.sp2))
                Text(
                    text = subtitle,
                    color = AppColors.white30,
                    fontSize = AppFontSize.xs,
                )
            }
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp8))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AppColors.background,
                checkedTrackColor = AppColors.white,
                uncheckedThumbColor = AppColors.white54,
                uncheckedTrackColor = AppColors.whiteA06,
                uncheckedBorderColor = AppColors.white24,
            ),
        )
    }
}

/**
 * 保存位置卡片（对齐 Flutter `_SaveLocationCard`）：
 * 标题行（folder 图标 + `保存位置` + 有自定义目录时的清除 ×）、路径文案、`选择下载目录` 按钮。
 */
@Composable
private fun MenuDrawerSaveLocationCard(
    dirPath: String?,
    loading: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = AppSpacing.sp12)
            .clip(RoundedCornerShape(AppRadius.card))
            .background(AppColors.whiteA03)
            .padding(AppSpacing.sp16),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(AppIconSize.icon18), contentAlignment = Alignment.Center) {
                MenuDrawerFolderIcon(tint = AppColors.white54)
            }
            Spacer(modifier = Modifier.width(AppSpacing.sp10))
            Text(
                text = "保存位置",
                color = AppColors.white,
                fontSize = AppFontSize.size15,
                fontWeight = AppFontWeight.semiBold,
                modifier = Modifier.weight(1f),
            )
            if (dirPath != null) {
                Box(
                    modifier = Modifier
                        .clickableNoRipple(onClear)
                        .padding(AppSpacing.sp4),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        tint = AppColors.white38,
                        modifier = Modifier.size(AppIconSize.icon16),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(AppSpacing.sp8))
        Text(
            text = if (loading) "加载中..." else "保存到：${dirPath ?: "Music/AnnieMusic"}",
            color = AppColors.white38,
            fontSize = AppFontSize.size13,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(AppSpacing.sp10))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(AppRadius.r8))
                .background(AppColors.primary.copy(alpha = 0.15f))
                .clickableNoRipple(onPick)
                .padding(vertical = AppSpacing.sp8, horizontal = AppSpacing.sp12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Flutter: Icon(Icons.create_new_folder_outlined, size: 14)
            Box(modifier = Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                MenuDrawerCreateFolderIcon(tint = AppColors.white70)
            }
            Spacer(modifier = Modifier.width(AppSpacing.sp6))
            Text(
                text = "选择下载目录",
                color = AppColors.white,
                fontSize = AppFontSize.sm,
                fontWeight = AppFontWeight.semiBold,
            )
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════════
// 自绘图标（material-icons-core 不含：key_outlined / folder_open / folder_outlined /
// create_new_folder_outlined / delete_outline / public；按 Flutter 图标栅格近似绘制）
// ═════════════════════════════════════════════════════════════════════════════════

/** 钥匙（对齐 `Icons.key_outlined`）：左侧圆环 + 横向匙杆 + 右侧匙齿。 */
@Composable
private fun MenuDrawerKeyIcon(modifier: Modifier = Modifier.fillMaxSize(), tint: Color = AppColors.white54) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.10f
        drawCircle(
            color = tint,
            radius = w * 0.21f,
            center = Offset(w * 0.28f, h * 0.50f),
            style = Stroke(width = stroke),
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.49f, h * 0.50f),
            end = Offset(w * 0.90f, h * 0.50f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.72f, h * 0.50f),
            end = Offset(w * 0.72f, h * 0.70f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** 文件夹（对齐 `Icons.folder_outlined`）：闭合文件夹轮廓 + 折角。 */
@Composable
private fun MenuDrawerFolderIcon(modifier: Modifier = Modifier.fillMaxSize(), tint: Color = AppColors.white54) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        val path = Path().apply {
            moveTo(w * 0.06f, h * 0.82f)
            lineTo(w * 0.06f, h * 0.20f)
            lineTo(w * 0.40f, h * 0.20f)
            lineTo(w * 0.48f, h * 0.33f)
            lineTo(w * 0.94f, h * 0.33f)
            lineTo(w * 0.94f, h * 0.82f)
            close()
        }
        drawPath(path = path, color = tint, style = Stroke(width = stroke, join = StrokeJoin.Round))
    }
}

/** 打开的文件夹（对齐 `Icons.folder_open`）：背板 + 前翻盖。 */
@Composable
private fun MenuDrawerFolderOpenIcon(modifier: Modifier = Modifier.fillMaxSize(), tint: Color = AppColors.white54) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        val back = Path().apply {
            moveTo(w * 0.06f, h * 0.78f)
            lineTo(w * 0.06f, h * 0.20f)
            lineTo(w * 0.36f, h * 0.20f)
            lineTo(w * 0.45f, h * 0.33f)
            lineTo(w * 0.78f, h * 0.33f)
            lineTo(w * 0.78f, h * 0.47f)
        }
        drawPath(path = back, color = tint, style = Stroke(width = stroke, join = StrokeJoin.Round))
        val front = Path().apply {
            moveTo(w * 0.17f, h * 0.46f)
            lineTo(w * 0.97f, h * 0.46f)
            lineTo(w * 0.85f, h * 0.80f)
            lineTo(w * 0.05f, h * 0.80f)
            close()
        }
        drawPath(path = front, color = tint, style = Stroke(width = stroke, join = StrokeJoin.Round))
    }
}

/** 新建文件夹（对齐 `Icons.create_new_folder_outlined`）：文件夹轮廓 + 加号。 */
@Composable
private fun MenuDrawerCreateFolderIcon(modifier: Modifier = Modifier.fillMaxSize(), tint: Color = AppColors.white54) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.10f
        val folder = Path().apply {
            moveTo(w * 0.07f, h * 0.84f)
            lineTo(w * 0.07f, h * 0.18f)
            lineTo(w * 0.38f, h * 0.18f)
            lineTo(w * 0.47f, h * 0.32f)
            lineTo(w * 0.93f, h * 0.32f)
            lineTo(w * 0.93f, h * 0.84f)
            close()
        }
        drawPath(path = folder, color = tint, style = Stroke(width = stroke, join = StrokeJoin.Round))
        drawLine(
            color = tint,
            start = Offset(w * 0.56f, h * 0.62f),
            end = Offset(w * 0.82f, h * 0.62f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.69f, h * 0.49f),
            end = Offset(w * 0.69f, h * 0.75f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** 垃圾桶（对齐 `Icons.delete_outline`）：盖子 + 提手 + 桶身 + 两条竖线。 */
@Composable
private fun MenuDrawerDeleteOutlineIcon(modifier: Modifier = Modifier.fillMaxSize(), tint: Color = AppColors.white54) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        // 盖子
        drawLine(
            color = tint,
            start = Offset(w * 0.10f, h * 0.26f),
            end = Offset(w * 0.90f, h * 0.26f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        // 提手
        val handle = Path().apply {
            moveTo(w * 0.36f, h * 0.26f)
            lineTo(w * 0.36f, h * 0.14f)
            lineTo(w * 0.64f, h * 0.14f)
            lineTo(w * 0.64f, h * 0.26f)
        }
        drawPath(path = handle, color = tint, style = Stroke(width = stroke, join = StrokeJoin.Round))
        // 桶身
        val body = Path().apply {
            moveTo(w * 0.18f, h * 0.26f)
            lineTo(w * 0.24f, h * 0.90f)
            lineTo(w * 0.76f, h * 0.90f)
            lineTo(w * 0.82f, h * 0.26f)
        }
        drawPath(path = body, color = tint, style = Stroke(width = stroke, join = StrokeJoin.Round))
        // 内竖线
        drawLine(
            color = tint,
            start = Offset(w * 0.42f, h * 0.42f),
            end = Offset(w * 0.44f, h * 0.74f),
            strokeWidth = stroke * 0.8f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.58f, h * 0.42f),
            end = Offset(w * 0.56f, h * 0.74f),
            strokeWidth = stroke * 0.8f,
            cap = StrokeCap.Round,
        )
    }
}

/** 地球（对齐 `Icons.public`）：圆 + 经线椭圆 + 赤道。 */
@Composable
private fun MenuDrawerGlobeIcon(modifier: Modifier = Modifier.fillMaxSize(), tint: Color = AppColors.white54) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        drawCircle(
            color = tint,
            radius = size.minDimension * 0.42f,
            center = center,
            style = Stroke(width = stroke),
        )
        drawOval(
            color = tint,
            topLeft = Offset(w * 0.31f, h * 0.08f),
            size = androidx.compose.ui.geometry.Size(w * 0.38f, h * 0.84f),
            style = Stroke(width = stroke),
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.08f, h * 0.50f),
            end = Offset(w * 0.92f, h * 0.50f),
            strokeWidth = stroke,
        )
    }
}

/** 电池（`material-icons-core` 里没有 `battery_*`）：机身轮廓 + 正极小凸起 + 内部电量条。 */
@Composable
private fun MenuDrawerBatteryIcon(modifier: Modifier = Modifier.fillMaxSize(), tint: Color = AppColors.white54) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        // 机身：横置电池，左侧留出正极凸起
        val body = Path().apply {
            moveTo(w * 0.07f, h * 0.28f)
            lineTo(w * 0.80f, h * 0.28f)
            lineTo(w * 0.80f, h * 0.72f)
            lineTo(w * 0.07f, h * 0.72f)
            close()
        }
        drawPath(path = body, color = tint, style = Stroke(width = stroke, join = StrokeJoin.Round))
        // 正极凸起
        drawLine(
            color = tint,
            start = Offset(w * 0.80f, h * 0.42f),
            end = Offset(w * 0.93f, h * 0.42f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.93f, h * 0.42f),
            end = Offset(w * 0.93f, h * 0.58f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.93f, h * 0.58f),
            end = Offset(w * 0.80f, h * 0.58f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        // 电量条（两格，示意「有电」而不是满格）
        drawLine(
            color = tint,
            start = Offset(w * 0.20f, h * 0.50f),
            end = Offset(w * 0.38f, h * 0.50f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.48f, h * 0.50f),
            end = Offset(w * 0.66f, h * 0.50f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}
