package com.annieplayer.annie_music.kt.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppSize

/** rgba(255,255,255,0.05)（输入框底色） —— 对齐 Flutter `_fieldBg` */
private val FieldBg = Color(0x0DFFFFFF)

/** rgba(255,255,255,0.15)（清除按钮） —— 对齐 Flutter `_clearIconColor` */
private val ClearIconColor = Color(0x26FFFFFF)

/** rgba(255,255,255,0.5)（搜索图标） —— 对齐 Flutter `_searchIconColor` */
internal val SearchIconColor = Color(0x80FFFFFF)

/**
 * 搜索栏（1:1 移植 Flutter `lib/widgets/search_bar.dart`）。
 *
 * 受控输入：文本状态由外部持有（对齐 Flutter 的 `TextEditingController` 受控模式）。
 * 回车或点击搜索图标触发 [onSubmit]；有内容时显示清除按钮。
 *
 * 使用 `BasicTextField` 而非 Material `TextField`，是为了完全去掉 Material 的下划线、
 * 容器色与内边距——Flutter 版用的是 `border: InputBorder.none`，视觉上是一条干净的
 * 半透明圆角条。
 *
 * 尺寸可配：[horizontalPadding] / [verticalPadding] 由调用方决定外边距，[height] 默认
 * [AppSize.searchBarHeight]（36dp）；搜索页把搜索框和模式按钮并排时传 0 边距 +
 * 44dp（= 模式按钮高度），两件东西同一行、同一高度。
 *
 * @param horizontalPadding 左右外边距（并排时传 0）
 * @param verticalPadding 上下外边距（并排时传 0）
 * @param height 输入条高度
 */
@Composable
fun SearchBarField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "搜索歌曲、歌手...",
    horizontalPadding: Dp = 16.dp,
    verticalPadding: Dp = 8.dp,
    height: Dp = AppSize.searchBarHeight,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = horizontalPadding,
                end = horizontalPadding,
                top = verticalPadding,
                bottom = verticalPadding,
            )
            .height(height)
            .background(FieldBg, RoundedCornerShape(10.dp))
            .padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ── 输入区 ──
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = AppFontSize.base),
                cursorBrush = SolidColor(AppColors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.fillMaxWidth(),
            )
            // 占位提示（输入为空时）
            if (value.isEmpty()) {
                Text(text = hint, color = AppColors.textDisabled, fontSize = AppFontSize.base)
            }
        }

        // ── 清除按钮（仅有内容时） ──
        if (value.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .clickableRipple { onValueChange("") }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                ClearCircleIcon(modifier = Modifier.size(15.dp))
            }
        }

        // ── 搜索按钮（命中区随条高，44dp 时正好填满右端） ──
        Box(
            modifier = Modifier
                .size(height)
                .clickableRipple(onSubmit),
            contentAlignment = Alignment.Center,
        ) {
            // 放大镜改为自绘线条家族（与主页搜索入口同形；笔重 2dp 等效、圆头端点），
            // 替换原 Material 实心 Search（粗实心、平头，与同屏自绘图标笔重不一致）
            SearchBarMagnifierGlyph(
                modifier = Modifier.size(17.dp),
                color = SearchIconColor,
            )
        }
    }
}

/**
 * 清除按钮图标：实心圆 + 挖空叉。
 *
 * 自绘而非用 `Icons.Default.Cancel` —— 后者属于 `material-icons-extended`，
 * 为单个图标引入整包不划算；自绘也更贴近 Flutter `Icons.cancel` 的实心圆造型。
 *
 * 尺寸对齐 Flutter：外层 15dp；圆占比与叉的粗细按 Flutter 图标栅格比例换算。
 */
@Composable
private fun ClearCircleIcon(modifier: Modifier = Modifier, color: Color = ClearIconColor) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val r = size.minDimension / 2f
        drawCircle(color = color, radius = r)
        val inset = r * 0.40f
        val strokeW = r * 0.18f
        // 用 Clear 混合模式把叉「挖」成透明（等价于 Flutter 图标字形的镂空）
        drawLine(
            color = Color.Black,
            start = androidx.compose.ui.geometry.Offset(center.x - r + inset, center.y - r + inset),
            end = androidx.compose.ui.geometry.Offset(center.x + r - inset, center.y + r - inset),
            strokeWidth = strokeW,
            blendMode = androidx.compose.ui.graphics.BlendMode.Clear,
        )
        drawLine(
            color = Color.Black,
            start = androidx.compose.ui.geometry.Offset(center.x + r - inset, center.y - r + inset),
            end = androidx.compose.ui.geometry.Offset(center.x - r + inset, center.y + r - inset),
            strokeWidth = strokeW,
            blendMode = androidx.compose.ui.graphics.BlendMode.Clear,
        )
    }
}

/**
 * 放大镜（搜索图标，自绘线条家族）。
 *
 * 与主页的搜索入口同形（镜圈 + 45° 手柄，比例坐标），笔重 `w * 0.118f`
 * 在 17dp 栅格上 ≈ 2dp（2dp 等效），圆头端点；替换原 `Icons.Default.Search`
 * （Material 实心、平头，与同屏自绘图标笔重不一致）。
 *
 * **两个使用点、同一个图形**：
 * 1. `SearchBarField` 内部右端的提交按钮；
 * 2. 主页态的独立搜索入口（位置与 1 完全重合，见 `HomeScreen` 的 `HomeSearchEntryRow`）。
 * 所以这里是 `internal` 而不是 `private`。
 */
@Composable
internal fun SearchBarMagnifierGlyph(modifier: Modifier = Modifier, color: Color = SearchIconColor) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.118f
        val lensRadius = w * 0.30f
        val lensCenter = Offset(w * 0.44f, h * 0.44f)

        drawCircle(
            color = color,
            radius = lensRadius,
            center = lensCenter,
            style = Stroke(width = stroke),
        )
        // 手柄：自镜头 45° 处起，指向右下角（圆头端点，与其它 glyph 一致）
        drawLine(
            color = color,
            start = Offset(
                lensCenter.x + lensRadius * 0.707f,
                lensCenter.y + lensRadius * 0.707f,
            ),
            end = Offset(w * 0.86f, h * 0.86f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}
