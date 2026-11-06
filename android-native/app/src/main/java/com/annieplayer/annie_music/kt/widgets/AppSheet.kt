package com.annieplayer.annie_music.kt.widgets

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing

/**
 * 通用底部面板（1:1 移植 Flutter `lib/widgets/app_sheet.dart`）。
 *
 * 视觉与动画参数与 MenuDrawer / 搜索源选择面板保持一致：**150ms + 线性曲线**。
 *
 * 关键行为（必须保留）：
 * - **常驻组件树**：隐藏时不移除，否则滑出动画无法播放。
 * - **隐藏时不吃触摸**：对齐 Flutter `IgnorePointer(ignoring: !visible)`。
 *   Compose 侧的做法是：遮罩的 `clickable` 只在 visible 时启用；面板本身不挂任何
 *   指针处理器，因此隐藏时事件会继续往下传。
 * - **返回手势**：面板打开时先关面板（对齐 Flutter `PopScope(canPop: !visible)`）。
 */
@Composable
fun AppSheet(
    visible: Boolean,
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 是否在底部为常驻底栏预留 [AppSpacing.sheetBottomInset]（68dp = 栏体 60 + 8 呼吸）。
     *
     * 默认 true（底栏常驻，浮层永远压在它上面）；**从设置页打开的纯信息面板传 false**
     * （那些面板是从设置页内部弹出的，底下没有底栏，留了就是一块死空白）。
     */
    reserveBottomInset: Boolean = true,
    /**
     * 内容区固定高度 = 「本面板可用高度」× 该比例；null = 原行为（内容多高，面板就多高）。
     *
     * 字段多的信息面板（本地歌曲详情这类）需要「固定高度 + 内部滚动」的形态：
     * 按内容撑高会在小屏上把抓手和标题顶出屏幕，只加 `heightIn(max)` 又会一路顶到整屏。
     * 这里不写死 dp —— 可用高度由面板自己算（已扣抓手 / 标题 / 底部 inset），
     * 传 0.6 左右就是「占屏一多半、内容在内部滑」。
     */
    contentHeightFraction: Float? = null,
    /**
     * 内容区**最大**高度 = 「本面板可用高度」× 该比例；null = 不设上限（内容多高就多高）。
     *
     * 与 [contentHeightFraction] 的分工，别混用：
     * - [contentHeightFraction] 是**固定**高度 —— 内容少也撑满（字段固定的信息面板）；
     * - 本参数是**上限** —— 内容少就按内容，只有超出才内部滚动（条目数不定的列表）。
     *
     * 典型场景：多歌手选择。一首合唱曲挂 20+ 个歌手时，按内容撑高会把面板一路顶到
     * 屏幕最上面、抓手和标题贴到状态栏，观感像整页而不是面板。传 0.5 就是「最多半屏、多了滑」。
     */
    contentMaxHeightFraction: Float? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 返回手势：打开时先关面板
    BackHandler(enabled = visible) { onClose() }

    val scrimAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.linear),
        label = "appSheetScrim",
    )
    val slide by animateFloatAsState(
        targetValue = if (visible) 0f else 1f,
        animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.linear),
        label = "appSheetSlide",
    )

    // 【关键 · 全屏浮层不得吃掉下层点击】
    // Compose 里 `clickable(enabled = false)` **依然占据命中测试**，会阻止事件传给下层兄弟节点。
    // 这些浮层的遮罩是 fillMaxSize，一旦常驻就会把顶栏抽屉按钮 / 搜索框 / 模式下拉 / ⇄ 按钮
    // 的点击全部挡掉（现象：界面能显示，但所有按钮点不动）。
    // 因此完全隐藏时**整个浮层不参与组合**，等价 Flutter 的 IgnorePointer(ignoring: !visible)。
    if (!visible && scrimAlpha == 0f) return
    Box(modifier = modifier.fillMaxSize()) {
        // ── 遮罩（Flutter: Colors.black54）：只吞点击，**不关闭** ──
        // 退出只走返回手势（本组件没有标题栏关闭按钮）。
        // 【关键】隐藏时**整条 clickable 都不挂**，而不是 clickable(enabled = false)。
        // Compose 的 clickable 即使 disabled 仍会占据命中测试，阻止事件传给下层兄弟节点
        // （表现为：面板隐藏着，顶栏抽屉按钮 / 搜索框 / 下拉全都点不动）。
        // 对齐 Flutter 的 IgnorePointer(ignoring: !visible)。
        // 注意 `onClick = {}` 不能删：它是吃掉触摸、阻止点击穿透到下层页面的唯一手段。
        val scrimInteraction = remember { MutableInteractionSource() }
        val scrimClickModifier = if (visible) {
            Modifier.clickable(
                interactionSource = scrimInteraction,
                indication = null,
                onClick = {},
            )
        } else {
            Modifier
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = scrimAlpha }
                .then(scrimClickModifier)
                .background(ScrimColor),
        )

        // ── 底部面板 ──
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // 宿主已不再统一内缩 → 面板自己补导航栏 inset，内容位置与改动前完全一致
                .navigationBarsPadding()
                .fillMaxWidth()
                .graphicsLayer { translationY = slide * size.height }
                .background(
                    color = AppColors.surface,
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                )
                // 底部留白避开常驻底栏（栏体 60 + 8 呼吸，推导见 sheetBottomInset 注释）
                .padding(bottom = if (reserveBottomInset) AppSpacing.sheetBottomInset else 0.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            // 抓手
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 12.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(2.dp)),
            )
            // 标题行：右侧统一关闭按钮。
            // 本面板没有任何别的关闭入口（遮罩只吞点击、不关闭），必须在这里给一个可见出口。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    fontSize = AppFontSize.xl,
                    fontWeight = FontWeight.Bold,
                )
                SheetCloseButton(onClose = onClose)
            }
            when {
                contentHeightFraction != null -> {
                    // 固定高度 + 由调用方在自己的内容里加 verticalScroll（面板不管滚动语义：
                    // 有的面板是列表、有的是分组的表单）
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(maxHeight * contentHeightFraction),
                        ) {
                            content()
                        }
                    }
                }
                contentMaxHeightFraction != null -> {
                    // 上限高度 + 面板自己提供滚动。
                    // 注意 `heightIn` 必须在外、`verticalScroll` 在内：外层先把约束限到上限，
                    // 滚动容器再用无限高度测量内容，自身取 min(内容高, 上限) ——
                    // 于是「内容少 → 面板矮」「内容多 → 到上限就内部滑」。
                    // 反过来写（先 scroll 再 heightIn）会让滚动容器把无限高度传给 heightIn，
                    // 结果无论如何都撑到上限，内容只有两行也占半屏。
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = maxHeight * contentMaxHeightFraction)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            content()
                        }
                    }
                }
                else -> content()
            }
        }
    }
}
/** 遮罩色：Flutter `Colors.black54` */
private val ScrimColor = Color(0x8A000000)

/**
 * 面板里的一行（图标 + 文案 + 可选副标题 + 右箭头）。
 *
 * 对齐 Flutter `AppSheetRow`：禁用态整体变暗、无点击、箭头更暗。
 * 图标差异说明：Flutter 用 `Icons.chevron_right`，Compose 核心图标集里最接近的是
 * `Icons.Default.KeyboardArrowRight`（形状略有差异）。若并排截图发现可见差异，
 * 再引入 material-icons-extended 精确对齐。
 */
@Composable
fun AppSheetRow(
    icon: ImageVector,
    label: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: Boolean = true,
    enabled: Boolean = true,
) {
    val contentColor = if (enabled) Color.White else Color.White.copy(alpha = 0.15f)
    val rowInteraction = remember { MutableInteractionSource() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                enabled = enabled,
                interactionSource = rowInteraction,
                indication = null,
                onClick = onTap,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(text = label, color = contentColor, fontSize = AppFontSize.base)
            if (subtitle != null && subtitle.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.24f),
                    fontSize = AppFontSize.sm,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = if (enabled) Color.White.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.08f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
