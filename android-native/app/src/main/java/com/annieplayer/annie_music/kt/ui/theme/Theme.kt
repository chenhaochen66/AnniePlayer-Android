package com.annieplayer.annie_music.kt.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 安妮音乐盒设计 token 集合（供 [AnnieTheme] 通过 CompositionLocal 下发）。
 *
 * 其中 [palette] 是**唯一可运行时变化**的 token（由封面动态取色生成）；
 * 其余都是静态标尺（与 Flutter 端 `AppColors` / `AppFontSize` / … 一一对应）。
 *
 * 为便于调用方统一读取，这里把各 `object` 以字段形式聚合；字段默认为对应单例，
 * 因此 `Annie.palette` 与直接访问 `AppColors.primary` 都能拿到正确的值。
 */
@Immutable
data class AnnieTokens(
    /** 当前动态色板（primary / secondary / background / surface） */
    val palette: ColorPalette,
    val colors: AppColors = AppColors,
    val typography: AnnieTypography = AnnieTypography,
    val fontSize: AppFontSize = AppFontSize,
    val fontWeight: AppFontWeight = AppFontWeight,
    val letterSpacing: AppLetterSpacing = AppLetterSpacing,
    val lineHeight: AppLineHeight = AppLineHeight,
    val spacing: AppSpacing = AppSpacing,
    val radius: AppRadius = AppRadius,
    val size: AppSize = AppSize,
    val iconSize: AppIconSize = AppIconSize,
    val border: AppBorder = AppBorder,
    val shadow: AppShadow = AppShadow,
    val duration: AppDuration = AppDuration,
    val easing: AppEasing = AppEasing,
)

/**
 * 当前 [AnnieTokens]；必须在 [AnnieTheme] 作用域内读取。
 *
 * 直接读取会抛异常，避免误用未初始化 token（对齐 MaterialTheme 的 `LocalXxx` 约定）。
 */
val LocalAnnieTokens = staticCompositionLocalOf<AnnieTokens> {
    error("AnnieTokens 未提供：请在 AnnieTheme { ... } 作用域内使用 LocalAnnieTokens.current")
}

/**
 * 「变色页色板」（Aurora 背景的三个锚点，`core/util/PlayerCoverColorExtractor.kt`）。
 *
 * ## 和 [LocalAnnieTokens] 里的 palette 什么关系
 *
 * | | `AnnieTokens.palette` | 本 Local |
 * |---|---|---|
 * | 数据 | 同一份取色结果 | 同一份取色结果（经过切歌补间） |
 * | 消费者 | 全屏播放页（显式传参，另含 4×4 网格） | 主页 / 本地曲库子页 / 各详情页背景 |
 *
 * 取色全站只有一套：[com.annieplayer.annie_music.kt.core.util.extractPlayerCoverPalette]。
 * 历史上这里跑的是另一套（LunaBeat 中心采样 + 36 色量化），已合并。
 *
 * 需要留意：这套取色产出的 `primary` / `secondary` / `accent` 是「全图平均 / 最鲜艳 /
 * 最大占比」，三者常常同色相、且 `primary` 偏灰。**色相分离不在这里做**，
 * 由 Aurora 背景层处理（见 `widgets/AuroraBackdrop.kt` 的 `_ensureAnchorSpread`）。
 *
 * ## 谁提供、谁消费
 *
 * 由 `AppShell` 按「当前播放歌曲封面」用 `extractPlayerCoverPalette` 算出，
 * 切歌时先做颜色补间再下发；没有可取色的封面（首次安装 / 清过数据 / 取色失败）时
 * 保持 [ColorPalette.fallback]，页面自然退回原来的深色底。
 *
 * 用 `compositionLocalOf`（动态）而不是 `staticCompositionLocalOf`：切歌时只有真正读它的
 * 背景层重组，不会把整棵 UI 树一起打穿。
 */
val LocalCoverThemePalette = compositionLocalOf { ColorPalette.fallback }

/**
 * 「变色页背景用的封面地址」——当前播放歌曲封面。
 *
 * 用途：
 * 1. **运动相位种子**：Aurora 背景用它的 hash 派生色斑的起始位置，
 *    保证同一封面永远同一形态（见 `widgets/AuroraBackdrop.kt`）；
 * 2. 需要显示「当前歌封面」的地方直接取用。
 *
 * 空串表示没有正在播放的歌（首次安装 / 清过数据）。
 */
val LocalAmbientCoverUrl = compositionLocalOf { "" }

/**
 * 应用级主题（对应 Flutter `main.dart` 中的 `ThemeData(...)`）。
 *
 * 行为：
 * 1. 用 [palette] + [AppColors] 构造深色 `ColorScheme`（对齐 Flutter `ColorScheme.dark`：
 *    primary=#818CF8、secondary=#312E81、surface=#1A1A2E、onSurface=#FFFFFF，
 *    scaffoldBackgroundColor=#0A0A1A → background）。
 * 2. 将 Flutter `textTheme`（bodyMedium / bodySmall / titleLarge / titleMedium）映射进
 *    [MaterialTheme] 的 typography。
 * 3. 通过 [CompositionLocalProvider] 下发 [AnnieTokens]，让自定义 token（尤其是动态
 *    [ColorPalette]）在整棵子树可用。
 *
 * @param palette 动态色板，默认 [ColorPalette.fallback]；封面取色成功后在顶层传入新值即可
 *                让整屏 UI 随之换色（对齐 Flutter `ColorNotifier.setColors`）。
 */
@Composable
fun AnnieTheme(
    palette: ColorPalette = ColorPalette.fallback,
    content: @Composable () -> Unit,
) {
    // ColorScheme 使用与 Flutter `main.dart` 完全一致的**静态** AppColors：
    // Flutter 的 ThemeData.colorScheme 只写死 AppColors.*，动态取色仅下发给业务组件的
    // 自定义 token（Annie.palette），从不驱动 Material 组件；若这里用 palette.*，
    // 默认态就会变成 fallback 的 #6366F1/#8B5CF6/#1E1B4B，与 Flutter 视觉系统性偏色。
    val colorScheme = darkColorScheme(
        primary = AppColors.primary,
        onPrimary = AppColors.textPrimary,
        primaryContainer = AppColors.primaryStrong,
        onPrimaryContainer = AppColors.textPrimary,
        secondary = AppColors.secondary,
        onSecondary = AppColors.textPrimary,
        background = AppColors.background,
        onBackground = AppColors.textPrimary,
        surface = AppColors.surface,
        onSurface = AppColors.textPrimary,
        surfaceVariant = AppColors.surfaceLight,
        onSurfaceVariant = AppColors.textSecondary,
        error = AppColors.danger,
        onError = AppColors.white,
        outline = AppColors.glassBorder,
    )

    val tokens = AnnieTokens(palette = palette)

    val typography = Typography(
        bodyMedium = AnnieTypography.bodyMedium,
        bodySmall = AnnieTypography.bodySmall,
        titleLarge = AnnieTypography.titleLarge,
        titleMedium = AnnieTypography.titleMedium,
    )

    // ── 关键：必须在 MaterialTheme 内部抹掉默认 lineHeight ──
    // MaterialTheme 自身会把 LocalTextStyle 设为 typography.bodyLarge（16sp/24sp），
    // 因此在它「外面」覆盖无效——必须嵌在它内部再覆盖一层。
    // 影响：调用方只写 `Text(fontSize = 14.sp)` 时，lineHeight 仍会继承 24sp，
    // 使每行文字都比 Flutter 高（Flutter 的 TextStyle 默认 height 为 null = 自然行高）。
    // 这里置为 Unspecified 让所有文本回到自然行高；颜色等其余属性保持不变。
    CompositionLocalProvider(LocalAnnieTokens provides tokens) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
        ) {
            val naturalTextStyle = LocalTextStyle.current.copy(lineHeight = TextUnit.Unspecified)
            CompositionLocalProvider(LocalTextStyle provides naturalTextStyle) {
                content()
            }
        }
    }
}

/**
 * 便捷访问器（用法对齐 `MaterialTheme`）。
 *
 * ```kotlin
 * AnnieTheme {
 *     val primary = Annie.palette.primary
 *     val sheetMs = Annie.tokens.duration.sheet
 * }
 * ```
 */
object Annie {
    /** 当前全部 token（须在 [AnnieTheme] 内读取）。 */
    val tokens: AnnieTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalAnnieTokens.current

    /** 当前动态色板（须在 [AnnieTheme] 内读取）。 */
    val palette: ColorPalette
        @Composable
        @ReadOnlyComposable
        get() = LocalAnnieTokens.current.palette
}
