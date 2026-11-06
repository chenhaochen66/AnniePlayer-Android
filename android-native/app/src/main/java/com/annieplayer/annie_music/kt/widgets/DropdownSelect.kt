package com.annieplayer.annie_music.kt.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing

/**
 * 「值 + 浮层选择器」的通用实现 —— 设置页的每个下拉、DSD 页的每个档位都用它。
 *
 * ## 为什么抽出来
 *
 * 这套东西踩过的坑不少，而且坑都在细节里：
 *
 * 1. 值区宽度必须**固定**，否则「PEQ」和「系统 · AudioTrack」差出一大截，
 *    整页箭头落成锯齿；
 * 2. 浮层必须**贴着值掉出来**（锚在值的下沿、宽度 = 值宽 + 箭头），
 *    从整行底部弹会隔出一大段空白；
 * 3. 浮层必须 `focusable = false`，否则整个页面的手势都被它吃掉（滚不动）；
 * 4. 展开状态必须在**页面级**，否则返回键/手势不知道有下拉开着；
 * 5. 同一次触摸会被投递**两次**（实测间隔 ~20ms），不加防抖就是"怎么点都收不回去"；
 * 6. 返回手势的边缘触摸会**先**被行当成一次点击、把下拉关掉，等 `onBackPressed`
 *    到达时状态已经是 null（实测间隔 ~50ms），于是"下拉和页面一起没了"。
 *
 * 这些都收在 [DropdownController] + [DropdownSelectRow] 里，调用方只需要：
 *
 * ```kotlin
 * val dropdown = rememberDropdownController()
 * BackHandler { if (!dropdown.handleBack()) onClose() }
 * DropdownSelectRow(key = "xxx", label = "...", value = "...", options = ..., controller = dropdown)
 * ```
 */

/** 值那一块的固定宽度。取最长值「系统 · AudioTrack」（12sp × 11 字符 ≈ 115dp）+ 内边距 20dp。 */
val DropdownValueWidth: Dp = 124.dp

/**
 * 值/选项文字的字号：**按长度分三级**，长了就缩，不换行也不截断。
 *
 * 为什么不用「固定 13sp + Ellipsis」：这些值本身是用户要看的信息，
 * 截成「系统 · AudioT…」等于没给。缩一级字号能多塞两个字，比截断有用。
 */
fun dropdownFontSizeFor(text: String): TextUnit = when {
    text.length <= 6 -> AppFontSize.size13
    text.length <= 10 -> AppFontSize.sm
    else -> AppFontSize.xs
}

/** 浮层里的一个候选。 */
data class DropdownOption(
    val label: String,
    val selected: Boolean,
    val enabled: Boolean = true,
    val onSelect: () -> Unit,
)

/**
 * 页面级的下拉状态：**打开的是谁** + 防抖 + 返回处理。
 *
 * 必须由页面持有（`rememberDropdownController()`），不能放在每一行自己身上 ——
 * 否则页面级的返回处理器不知道有下拉开着，返回会把整页关掉。
 */
class DropdownController internal constructor(
    val openKey: MutableState<String?>,
    private val lastToggleAtMs: MutableLongState,
) {

    /** 点一下：开 → 关、关 → 开。250ms 内的重复投递只认第一次。 */
    fun toggle(key: String) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastToggleAtMs.longValue < 250) return
        lastToggleAtMs.longValue = now
        openKey.value = if (openKey.value == key) null else key
    }

    fun dismiss() {
        openKey.value = null
    }

    /**
     * 返回时的处理。返回 `true` = 这次返回已被消费，调用方**不要**再关页面。
     *
     * 三种情况：
     * - 下拉开着 → 只收下拉；
     * - 下拉刚被"手势的边缘触摸"关掉（<400ms）→ **吃掉这次返回**，页面留着；
     * - 其余 → `false`，调用方照常关页。
     */
    fun handleBack(): Boolean {
        if (openKey.value != null) {
            openKey.value = null
            return true
        }
        val since = android.os.SystemClock.uptimeMillis() - lastToggleAtMs.longValue
        return since < 400
    }

    /** 页面关闭时清空（下拉开着的时候关页，下次进来不该还开着） */
    fun reset() {
        openKey.value = null
    }
}

@Composable
fun rememberDropdownController(): DropdownController {
    val open = remember { mutableStateOf<String?>(null) }
    val lastToggleAt = remember { mutableLongStateOf(0L) }
    return remember(open, lastToggleAt) { DropdownController(open, lastToggleAt) }
}

/**
 * 一行「标签 + 值（固定宽，居中）+ 箭头」，点开浮层选一项。
 *
 * @param subtitle 副标题，**独占第二行全宽** —— 值区固定后左侧那一列只剩 ~150dp，
 *   副标题挤在里面会被折成四五行的窄条
 * @param info 标签右侧的 ⓘ（自己的可点区域，不会连带展开）
 */
@Composable
fun DropdownSelectRow(
    /** 在 [DropdownController.openKey] 里的标识；同一页面内唯一 */
    selectKey: String,
    label: String,
    value: String,
    options: List<DropdownOption>,
    controller: DropdownController,
    subtitle: String? = null,
    info: (() -> Unit)? = null,
    locked: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val isOpen = controller.openKey.value == selectKey && !locked
    var valueSize by remember { mutableStateOf(IntSize.Zero) }
    val chevronRotation = if (isOpen) 180f else 0f

    Box(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .background(AppColors.whiteA05, RoundedCornerShape(AppRadius.card))
                .then(
                    if (locked) Modifier
                    else Modifier.clickableNoRipple { controller.toggle(selectKey) },
                )
                .padding(horizontal = AppSpacing.sp16, vertical = AppSpacing.sp14),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label,
                        color = if (locked) AppColors.textDisabled else AppColors.textPrimary,
                        fontSize = AppFontSize.size15,
                        fontWeight = FontWeight.Medium,
                    )
                    info?.let { onInfo ->
                        Spacer(modifier = Modifier.width(AppSpacing.sp6))
                        Box(
                            modifier = Modifier
                                .clickableNoRipple(onInfo)
                                .padding(AppSpacing.sp2),
                        ) {
                            InfoGlyph(
                                tint = AppColors.glyphMid,
                                modifier = Modifier.size(AppIconSize.icon13),
                            )
                        }
                    }
                }
                // 固定宽 + 居中：宽度不随内容变，整页箭头才会落在同一条竖线上
                Box(modifier = Modifier.onGloballyPositioned { valueSize = it.size }) {
                    Text(
                        text = value,
                        color = if (locked) AppColors.textDisabled else AppColors.textPrimary,
                        fontSize = dropdownFontSizeFor(value),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .width(DropdownValueWidth)
                            .background(
                                if (locked) AppColors.whiteA05 else AppColors.whiteA15,
                                RoundedCornerShape(AppRadius.r8),
                            )
                            .padding(horizontal = AppSpacing.sp10, vertical = AppSpacing.sp6),
                    )
                    // 浮层锚在**这个值**的下沿（不是整行下沿）
                    if (valueSize.width > 0) {
                        DropdownPopup(
                            expanded = isOpen,
                            onDismiss = { controller.dismiss() },
                            anchorHeightPx = valueSize.height,
                            panelWidth = DropdownValueWidth + AppSpacing.sp8 + AppIconSize.icon16,
                            anchorStartPx = 0,
                        ) {
                            for (option in options) {
                                DropdownOptionRow(option) { controller.dismiss() }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.width(AppSpacing.sp8))
                Canvas(
                    modifier = Modifier
                        .size(AppIconSize.icon16)
                        .graphicsLayer { rotationZ = chevronRotation },
                ) {
                    val w = size.width
                    val h = size.height
                    val stroke = w * 0.15f
                    val tint = if (locked) AppColors.textDisabled else AppColors.textSecondary
                    drawLine(
                        tint,
                        Offset(w * 0.25f, h * 0.36f),
                        Offset(w * 0.50f, h * 0.62f),
                        stroke,
                        StrokeCap.Round,
                    )
                    drawLine(
                        tint,
                        Offset(w * 0.75f, h * 0.36f),
                        Offset(w * 0.50f, h * 0.62f),
                        stroke,
                        StrokeCap.Round,
                    )
                }
            }
            if (!subtitle.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(AppSpacing.sp4))
                Text(
                    text = subtitle,
                    color = AppColors.textTertiary,
                    fontSize = AppFontSize.sm,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}

/**
 * 「i」说明图标（自绘，与全站图标同一套笔画）。
 *
 * 从 `MoreSettingsScreen` 搬到这里：下拉行和设置页的开关/信息行都要用它，
 * 留两份必然慢慢长不一样。
 */
@Composable
fun InfoGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val r = size.minDimension * 0.44f
        val stroke = size.minDimension * 0.11f
        drawCircle(color = tint, radius = r, center = center, style = Stroke(width = stroke))
        // 「i」：一个点 + 一竖
        drawCircle(color = tint, radius = stroke * 0.62f, center = Offset(center.x, center.y - r * 0.42f))
        drawLine(
            color = tint,
            start = Offset(center.x, center.y - r * 0.05f),
            end = Offset(center.x, center.y + r * 0.50f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** 浮层里的一个候选行；选中项用主色高亮 + 左侧竖条。 */@Composable
fun DropdownOptionRow(option: DropdownOption, onPicked: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.card))
            .background(if (option.selected) AppColors.primaryA12 else Color.Transparent)
            .drawBehind {
                if (!option.selected) return@drawBehind
                val barWidth = 3.dp.toPx()
                drawRoundRect(
                    color = AppColors.primary,
                    size = Size(barWidth, size.height * 0.56f),
                    topLeft = Offset(0f, size.height * 0.22f),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                )
            }
            .then(
                if (option.enabled) {
                    Modifier.clickableNoRipple {
                        option.onSelect()
                        onPicked()
                    }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = AppSpacing.sp14, vertical = AppSpacing.sp12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = option.label,
            color = when {
                !option.enabled -> AppColors.textTertiary.copy(alpha = 0.45f)
                option.selected -> AppColors.primary
                else -> AppColors.textSecondary
            },
            fontSize = dropdownFontSizeFor(option.label),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (option.selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
