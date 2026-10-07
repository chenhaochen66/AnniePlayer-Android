package com.annieplayer.annie_music.kt.features.search

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppLetterSpacing
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppShadow
import com.annieplayer.annie_music.kt.ui.theme.AppSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.clickableRipple
import com.annieplayer.annie_music.kt.widgets.glassPanel

/**
 * 首页的零散构件（1:1 移植 Flutter `lib/features/search/home_screen.dart` 内的私有 Widget）：
 * 待下载条 / ⇄ 搜索源按钮 / KKBOX·QOBUZ 链接解析视图 / 歌单解析视图 / 「下载中」轻提示。
 *
 * 拆分说明：Dart 侧这些都是 `home_screen.dart` 文件内的私有组件；Kotlin 侧 `HomeScreen.kt`
 * 若全部收纳会超过单文件体量上限，因此拆到本文件。新增顶层名字统一加 `Home` 前缀，避免
 * 与其它文件的同级符号静默冲突。
 *
 * 设计约束：所有颜色 / 间距 / 圆角 / 时长一律取 `ui/theme` 设计 token；Dart 内联且未进入
 * 标尺的数值（如 toast 底部 160）收敛为本文件的私有常量，不写魔法数字。
 */

/** 「下载中」轻提示的底部偏移（对齐 Dart `Positioned(bottom: 160)`） */
private val HomeDownloadToastBottom = 160.dp

/** 待下载条计数圆点直径（对齐 Dart `Container(width: 22, height: 22)`） */
private val HomePendingBadgeSize = 22.dp

/** KKBOX / QOBUZ 模式按钮的上下内边距（对齐 Dart `EdgeInsets.symmetric(vertical: 9)`） */
private val HomeForeignTabPadV = 9.dp

/**
 * 待下载条（对齐 Dart `_PendingDownloadBar`）：
 * 计数圆点 + 「待下载歌曲」→ 查看浮层；「清空」；「下载全部」（下载中置灰）。
 */
@Composable
internal fun HomePendingDownloadBar(
    count: Int,
    batchDownloading: Boolean,
    onView: () -> Unit,
    onClear: () -> Unit,
    onBatch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = AppSpacing.sp16, end = AppSpacing.sp16, bottom = AppSpacing.sp8)
            .background(AppColors.primaryA08, RoundedCornerShape(AppRadius.panel))
            .padding(horizontal = AppSpacing.sp14, vertical = AppSpacing.sp10),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧：计数 + 文案（点整块查看多选浮层）
        Row(
            modifier = Modifier.weight(1f).clickableRipple(onView),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(HomePendingBadgeSize)
                    .background(AppColors.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$count",
                    color = AppColors.white,
                    fontSize = AppFontSize.xs,
                    fontWeight = AppFontWeight.bold,
                )
            }
            Spacer(modifier = Modifier.width(AppSpacing.sp6))
            Text(
                text = "待下载歌曲",
                color = AppColors.white70,
                fontSize = AppFontSize.size13,
                fontWeight = AppFontWeight.medium,
            )
        }
        // 清空
        Box(modifier = Modifier.clickableRipple(onClear).padding(AppSpacing.sp4)) {
            Text(text = "清空", color = AppColors.white38, fontSize = AppFontSize.sm)
        }
        // 下载全部（Dart: onTap 在下载中为 null → 这里在 lambda 内门控）
        Box(
            modifier = Modifier
                .clickableRipple { if (!batchDownloading) onBatch() }
                .background(AppColors.primary, RoundedCornerShape(AppRadius.r10))
                .padding(horizontal = AppSpacing.sp16, vertical = AppSpacing.sp8),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (batchDownloading) "下载中..." else "下载全部",
                color = AppColors.white,
                fontSize = AppFontSize.sm,
                fontWeight = AppFontWeight.semiBold,
            )
        }
    }
}

/**
 * ⇄ 切换搜索源按钮（原「圆形主色 FAB」，现对齐详情页「下载 N 首」的玻璃 pill）。
 *
 * 样式：`glassPanel` + [AppRadius.pill]（白 5% 底、无描边无投影），高 44dp
 * （[AppSize.detailDownloadBarHeight]）、左右内边距 16dp、⇄ 图标 18dp + 当前源文案。
 * 位置仍在右下角：右 16 / 底 [AppSpacing.sheetBottomInset]（避开迷你播放条）；
 * 原圆形按钮需让出半个身位（end = 40）才与列表右缘对齐，改成 pill 后改回 16。
 *
 * @param label 当前生效的搜索源 / 平台文案（宿主按 `pickerOptions[pickerIndex]` 注入）。
 */
@Composable
internal fun BoxScope.HomeSourceSwitchButton(label: String, onTap: () -> Unit) {
    Row(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = AppSpacing.sp16, bottom = AppSpacing.sheetBottomInset)
            .height(AppSize.detailDownloadBarHeight)
            // 玻璃底加深到 surfaceLight @50%（默认白 5% 浮在列表上太透），与底栏同一口径
            .glassPanel(
                shape = RoundedCornerShape(AppRadius.pill),
                tint = AppColors.surfaceLight.copy(alpha = 0.50f),
            )
            .clickableRipple(onTap)
            .padding(horizontal = AppSpacing.sp16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeSourceSwitchGlyph(modifier = Modifier.size(AppIconSize.icon18), tint = AppColors.white)
        Spacer(modifier = Modifier.width(AppSpacing.sp8))
        Text(
            text = label,
            color = AppColors.white,
            fontSize = AppFontSize.base,
            fontWeight = AppFontWeight.medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 自绘 ⇄（`material-icons-core` 无 `swap_horiz`）：上箭头向右、下箭头向左。
 * 栅格比例对齐 Material 图标（圆头笔画）。
 */
@Composable
private fun HomeSourceSwitchGlyph(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        val left = w * 0.10f
        val right = w * 0.90f
        val topY = h * 0.40f
        val bottomY = h * 0.60f
        val head = w * 0.20f
        val headDrop = h * 0.13f

        // 上：向右
        drawLine(tint, Offset(left, topY), Offset(right, topY), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(right - head, topY - headDrop), Offset(right, topY), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(right - head, topY + headDrop), Offset(right, topY), stroke, cap = StrokeCap.Round)

        // 下：向左
        drawLine(tint, Offset(right, bottomY), Offset(left, bottomY), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(left + head, bottomY - headDrop), Offset(left, bottomY), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(left + head, bottomY + headDrop), Offset(left, bottomY), stroke, cap = StrokeCap.Round)
    }
}

/**
 * 国外解析视图（对齐 Dart `_ForeignLinkView`）：
 * KKBOX / QOBUZ 两个模式按钮（分开、居中）→ 「{SERVICE}解析」大标题 → 链接输入框。
 *
 * 提交后由宿主解析（失败弹 SnackBar；成功进入对应详情页）。
 */
@Composable
internal fun HomeForeignLinkView(
    onQobuzSubmit: (String) -> Unit,
    onKkboxSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 0 = KKBOX，1 = Qobuz
    var tab by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf("") }
    val label = if (tab == 0) "KKBOX" else "QOBUZ"

    fun submit() {
        val value = text.trim()
        if (value.isEmpty()) return
        // 按当前模式提交；链接不合法时由宿主弹提示
        if (tab == 0) onKkboxSubmit(value) else onQobuzSubmit(value)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = AppSpacing.sp32, top = AppSpacing.sp44, end = AppSpacing.sp32),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 模式按钮（KKBOX / QOBUZ 分开，居中）
        Row {
            HomeForeignModeButton(label = "KKBOX", active = tab == 0, onTap = { tab = 0 })
            Spacer(modifier = Modifier.width(AppSpacing.sp12))
            HomeForeignModeButton(label = "QOBUZ", active = tab == 1, onTap = { tab = 1 })
        }
        Spacer(modifier = Modifier.height(AppSpacing.sp22))
        // 标题（服务名大写）
        Text(
            text = "${label}解析",
            modifier = Modifier.fillMaxWidth(),
            color = AppColors.white60,
            fontSize = AppFontSize.title,
            fontWeight = AppFontWeight.bold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(AppSpacing.sp24))
        HomeLinkTextField(
            value = text,
            onValueChange = { text = it },
            hint = if (tab == 0) "请粘贴 KKBOX 链接" else "请粘贴 Qobuz 链接",
            onSubmit = { submit() },
        )
    }
}

/**
 * KKBOX / QOBUZ 模式按钮（对齐 Dart `_ModeBtn`）：
 * 选中主色 @18% 底、白字；未选中白 5% 底、white54；150ms 线性过渡，圆角 10。
 */
@Composable
private fun HomeForeignModeButton(label: String, active: Boolean, onTap: () -> Unit) {
    val background by animateColorAsState(
        targetValue = if (active) AppColors.primary.copy(alpha = 0.18f) else AppColors.whiteA05,
        animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.linear),
        label = "homeForeignModeBg",
    )
    Box(
        modifier = Modifier
            .background(background, RoundedCornerShape(AppRadius.r10))
            .clickableRipple(onTap)
            .padding(horizontal = AppSpacing.sp22, vertical = HomeForeignTabPadV),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (active) AppColors.white else AppColors.white54,
            fontSize = AppFontSize.base,
            fontWeight = AppFontWeight.bold,
            letterSpacing = AppLetterSpacing.wide,
        )
    }
}

/**
 * 歌单解析链接输入视图（对齐 Dart `_PlaylistLinkView`）：
 * 居中大标题「歌单解析」+ 单个输入框；提交后由宿主解析。
 */
@Composable
internal fun HomePlaylistLinkView(onSubmit: (String) -> Unit, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf("") }

    fun submit() {
        val value = text.trim()
        if (value.isEmpty()) return
        onSubmit(value)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = AppSpacing.sp32, top = AppSpacing.sp60, end = AppSpacing.sp32),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "歌单解析",
            modifier = Modifier.fillMaxWidth(),
            color = AppColors.white60,
            fontSize = AppFontSize.title,
            fontWeight = AppFontWeight.bold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(AppSpacing.sp24))
        HomeLinkTextField(
            value = text,
            onValueChange = { text = it },
            hint = "请粘贴歌单链接",
            onSubmit = { submit() },
        )
    }
}

/**
 * 链接输入框（对齐 Dart `TextField`：`filled` + `white @0.06` 底、圆角 14、无边框，
 * 正文白字 15sp，提示 white30；回车提交）。
 */
@Composable
private fun HomeLinkTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(AppColors.whiteA06, RoundedCornerShape(AppRadius.panel))
            .padding(horizontal = AppSpacing.sp16, vertical = AppSpacing.sp14),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = AppColors.white, fontSize = AppFontSize.size15),
            cursorBrush = SolidColor(AppColors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        // 占位提示（输入为空时）
        if (value.isEmpty()) {
            Text(text = hint, color = AppColors.white30, fontSize = AppFontSize.size15)
        }
    }
}
