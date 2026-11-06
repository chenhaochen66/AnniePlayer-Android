package com.annieplayer.annie_music.kt.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * 全局静态调色板。
 *
 * 1:1 移植自 Flutter 端 `lib/core/theme/app_theme.dart`（Flutter 工程已从本分支移除）的 `AppColors`
 * （该文件本身对齐 RN 版 `constants/tokens.ts`）。
 *
 * 所有取值均为 `Color(0xAARRGGBB)`，与 Flutter 的 `Color(0x…)` 逐位一致，不得改动。
 * 迁移硬约束：UI 必须与 Flutter 版「看起来、用起来完全一致」，因此任何色值偏差都视为缺陷。
 */
object AppColors {
    // ---- 主调色板（Flutter: AppColors）----

    /** 主色（indigo-400 风格） */
    val primary = Color(0xFF818CF8)

    /** 强调主色（indigo-500） */
    val primaryStrong = Color(0xFF6366F1)

    /** 深蓝紫强调（secondary） */
    val secondary = Color(0xFF312E81)

    /** 页面背景（接近黑色深蓝） */
    val background = Color(0xFF0A0A1A)

    /** 面板 / 浮层表面色 */
    val surface = Color(0xFF1A1A2E)

    /** 次级面板色 */
    val surfaceLight = Color(0xFF1C1C3A)

    /** 玻璃拟态（glass） */
    val glass = Color(0x14FFFFFF)

    /** 玻璃拟态边框 */
    val glassBorder = Color(0x22FFFFFF)

    /**
     * 液态玻璃面板底色：**白色 @ 5%（≈95% 透明）**。
     *
     * 用户明确要求「框框要基本透明」：玻璃观感不能靠面板自己刷底色，而是靠
     * ① 下层的模糊封面 + 封面取色氛围（见 `CoverPaletteBackdrop`）、
     * ② 面板顶部的白色高光渐变、③ 1px 发丝描边、④ 悬浮投影 —— 这四样叠出来的「透 + 毛 + 边」。
     *
     * 原实现用 [surface95]（95% 不透明）当玻璃底 = 一块实心卡片，玻璃看不出来、背景色也吃不到
     * （真机反馈已确认两次）。透明度再往下已经没有意义（5% 白 ≈ 纯高光层）。
     */
    val glassPanel = Color(0x0DFFFFFF)

    // ---- 文字层级（rgba 白，Flutter: AppColors.textXxx）----

    val textPrimary = Color(0xFFFFFFFF)
    val textSecondary = Color(0xB3FFFFFF) // 0.7

    /**
     * 三级文字。**值已从 0.35 提到 0.7**（与 [textSecondary] 同档）。
     *
     * 全 App 的文字淡度下限 = 「本地曲库 → 全部歌曲」行里歌手那一档（0.7）。
     * 比它更淡的字在深色底 + 小字号下读不清，所以这一档统一拉平。
     * 需要「更弱」的表达时降字号，不要降透明度。
     */
    val textTertiary = Color(0xB3FFFFFF)  // 0.7（原 0.35）

    /** 禁用态文字。**刻意保留 0.2**：提亮后「置灰 = 不可点」就看不出来了。 */
    val textDisabled = Color(0x33FFFFFF)  // 0.2

    // ---- 状态色（Flutter: AppColors.success/warning/danger）----

    val success = Color(0xFF4ADE80)
    val warning = Color(0xFFFBBF24)
    val danger = Color(0xFFF87171)

    /** 「独家」标识色（橙红）：插件管理里的独家徽标，样式同「使用中」但用这个色 */
    val exclusive = Color(0xFFFF6B47)

    /** 「独家」徽标底色（同色 15% 透明度） */
    val exclusiveA15 = Color(0x26FF6B47)

    // ============================================================================================
    // 以下为 Flutter 端「内联」使用的设计常量（不在 app_theme.dart 里，但全 App 高频复用）。
    // 来源：grep `Colors.` / `Color(0x..FFFFFF)` / `Color(0x80000000)` 等。
    // 命名按 alpha 百分比推导（如 0x33FFFFFF = 0.2 → whiteA20）。
    // ============================================================================================

    // ---- 纯白 / 纯黑 ----

    val white = Color(0xFFFFFFFF)
    val black = Color(0xFF000000)

    // ---- 白色标尺（Flutter: Colors.whiteXX）----
    //
    // 【文字淡度已统一到 0.7】下面 white60 / white54 / white38 / white30 / white24 原值分别是
    // 0.6 / 0.54 / 0.38 / 0.30 / 0.24，全部提到 0.7（与 textSecondary 同档）—— 这些 token
    // 当初是按 Flutter 的 Colors 标尺抄的，落到本 App 就变成「同一层级出现五六种淡度」。
    // 现在只保留一个文字下限：0.7。更淡的只留给 [textDisabled]（禁用态）和图形。
    //
    // 图标不吃这一档：图形借用文字色时会跟着变亮，所以新增 [glyphStrong] / [glyphMid] /
    // [glyphDim] 三个图形专用档位，tint 一律走那三个。

    /** Colors.white70（0.7）—— 文字下限，与 [textSecondary] 同值 */
    val white70 = Color(0xB3FFFFFF)

    /** Colors.white60 —— 文字：已提到 0.7（原 0.6） */
    val white60 = Color(0xB3FFFFFF)

    /** Colors.white54 —— 文字：已提到 0.7（原 0.54）；图形用 [glyphStrong] */
    val white54 = Color(0xB3FFFFFF)

    /** Colors.white38 —— 文字：已提到 0.7（原 0.38）；图形用 [glyphMid] */
    val white38 = Color(0xB3FFFFFF)

    /** Colors.white30（原 0.30，等同 hint 文本）—— 文字：已提到 0.7；图形用 [glyphDim] */
    val white30 = Color(0xB3FFFFFF)

    /** Colors.white24 —— 文字：已提到 0.7（原 0.24）；图形用 [glyphDim] */
    val white24 = Color(0xB3FFFFFF)

    /** Colors.white12（0.12）—— 背景 / 骨架，不是文字，保持原值 */
    val white12 = Color(0x1FFFFFFF)

    /** Colors.white10（0.10，等同骨架块 _blockColor）—— 背景，保持原值 */
    val white10 = Color(0x1AFFFFFF)

    // ---- 图形专用白色（与文字色解耦）----
    //
    // 图标 / 箭头 / 关闭按钮 / 装饰线不是「字体」，文字统一到 0.7 时它们不该跟着变亮。
    // 三档接住原来借用 white60/54/50/45/40/38（→ [glyphStrong] / [glyphMid]）与
    // white30/25/24/20（→ [glyphDim]）的图形。

    /** 图形：强（0.54，原 white54 / white60 的图形用法） */
    val glyphStrong = Color(0x8AFFFFFF)

    /** 图形：中（0.4，原 whiteA40 / whiteA45 / whiteA50 / white38 的图形用法） */
    val glyphMid = Color(0x66FFFFFF)

    /** 图形：弱（0.24，原 white24 / whiteA25 / whiteA20 / white30 的图形用法） */
    val glyphDim = Color(0x3DFFFFFF)

    // ---- 白色 alpha（内联 Color(0x..FFFFFF) 归并）----

    /** 卡片 / 封面 / 头像底色（0.031，Flutter: _cardBg/_coverBg/_avatarBg） */
    val whiteA03 = Color(0x08FFFFFF)

    /** 分隔线（0.039，Flutter: _dividerColor） */
    val whiteA04 = Color(0x0AFFFFFF)

    /** 输入框填充（0.051，Flutter: _fieldBg） */
    val whiteA05 = Color(0x0DFFFFFF)

    /** 面板边框（0.059，Flutter: _panelBorder） */
    val whiteA06 = Color(0x0FFFFFFF)

    /** 0.149（Flutter: _clearIconColor）—— 占位符号专用，**保持原值**：空封面上的音符等 */
    val whiteA15 = Color(0x26FFFFFF)

    /** 0.2（原 Flutter: _metaWeakColor）—— 文字：已提到 0.7；图形用 [glyphDim] */
    val whiteA20 = Color(0xB3FFFFFF)

    /** 0.251（原分隔边框等）—— 文字：已提到 0.7；图形用 [glyphDim] */
    val whiteA25 = Color(0xB3FFFFFF)

    /** 0.4（原 Flutter: _artistColor / 弱化文本）—— 文字：已提到 0.7；图形用 [glyphMid] */
    val whiteA40 = Color(0xB3FFFFFF)

    /** 0.451（原次级弱化文本）—— 文字：已提到 0.7；图形用 [glyphMid] */
    val whiteA45 = Color(0xB3FFFFFF)

    /** 0.5（原 Flutter: _downloadIconColor / searchIconColor）—— 文字：已提到 0.7；图形用 [glyphMid] */
    val whiteA50 = Color(0xB3FFFFFF)

    // ---- 黑色遮罩层（barrier）----

    /** 浮层遮罩 50%（Flutter: Color(0x80000000)） */
    val scrim50 = Color(0x80000000)

    /** 浮层遮罩 60%（Flutter: Color(0x99000000)） */
    val scrim60 = Color(0x99000000)

    /** Colors.black54（0.54） */
    val black54 = Color(0x8A000000)

    /** 图片查看器遮罩（0.92，Flutter: contact_modal Color(0xEB000000)） */
    val scrim92 = Color(0xEB000000)

    // ---- 主色 alpha（primary = 0xFF818CF8 的透明变体）----

    /** 进度环轨道（0.2，Flutter: _ringTrackColor = 0x336366F1 的 primaryStrong 变体） */
    val primaryStrongA20 = Color(0x336366F1)

    /** 选中行底色（0.078，Flutter: _selectedBg / 列表选中项） */
    val primaryA08 = Color(0x14818CF8)

    /** 高亮行底色（0.059，Flutter: _activeBg） */
    val primaryA06 = Color(0x0F818CF8)

    /** 下拉菜单选中项（0.122，Flutter: mode_dropdown _itemActiveBg） */
    val primaryA12 = Color(0x1F818CF8)

    /** 底部 Tab / 分段选中底色（0.149，Flutter: download_queue_sheet 等） */
    val primaryA15 = Color(0x26818CF8)

    /** 歌曲元信息弱化色（0.6，Flutter: song_tile _metaDim） */
    val primaryA60 = Color(0x99818CF8)

    // ---- 其他品牌 / 特殊色 ----

    /** Hi-Res 高解析品质标识（pink-400，Flutter: 0xFFF472B6） */
    val hires = Color(0xFFF472B6)

    /** 抽屉面板底色（Flutter: menu_drawer 0xFF12122A） */
    val drawerPanel = Color(0xFF12122A)

    /** 联系弹窗底色（Flutter: contact_modal 0xFF1E1E3A） */
    val modalPanel = Color(0xFF1E1E3A)

    /** surface @95%（Flutter: mini_player / 详情底栏 0xF21A1A2E） */
    val surface95 = Color(0xF21A1A2E)

    /** 歌曲元信息高亮（indigo-300 @0.949，Flutter: song_tile _metaQuality 0xF2A5B4FC） */
    val indigo300A95 = Color(0xF2A5B4FC)

    /**
     * 单曲搜索结果「正在播放」行的中性灰（`playingRowBackground` + `PlayingIndicator` 的 tint）。
     *
     * 详情页用封面取色主色给正在播放的行着色；搜索页是**单栏长列表**，整屏跟着封面换色会很花，
     * 故统一用这档中性灰（≈ white 70%）：8% 圆角底仍能看出选中，竖条 / 等化器 / 标题也够亮。
     */
    val playingGray = Color(0xB3FFFFFF)

    // ---- 详情页共享组件追加（Android-only 设计，Flutter 基线无对应实现）----

    /**
     * 玻璃面板投影（black @0.40）。
     *
     * 与 [whiteA40] 互为黑白镜像；用于 [com.annieplayer.annie_music.kt.widgets.glassPanel]
     * 的 `dropShadow`，给液态玻璃一个可感知的悬浮阴影（AppBottomBar 的黑色投影为 0.55，
     * 详情页容器更轻，取 0.40，避免大面板投影过重）。
     */
    val blackA40 = Color(0x66000000)

    // ---- 开屏装饰气泡（Flutter: splash_screen.dart 6 个圆点色）----

    val splashBubbleRed = Color(0xFFFF6B6B)
    val splashBubbleYellow = Color(0xFFFFE66D)
    val splashBubbleCyan = Color(0xFF48DBFB)
    val splashBubblePink = Color(0xFFFF9FF3)
    val splashBubbleBlue = Color(0xFF54A0FF)
    val splashBubblePurple = Color(0xFF5F27CD)
}

/**
 * 动态色板（Flutter: `ColorPalette`，对齐 RN 版 useColorStore 的 ColorPalette）。
 *
 * 由「封面动态取色」功能在运行时生成，会替换主色与次级色；调用方通过
 * [AnnieTokens.palette]（CompositionLocal）读取，从而让整屏 UI 随封面变化。
 */
@Immutable
data class ColorPalette(
    val primary: Color,
    val secondary: Color,
    val background: Color,
    val surface: Color,
    val accent: Color = secondary,
    /** 封面 N×N 网格取色（行优先，长度 = [coverGridDim]²）；空表示不可用 */
    val coverGrid: List<Color> = emptyList(),
    val coverGridDim: Int = 0,
) {
    /** 对齐 Flutter `ColorPalette.copyWith`。 */
    fun copyWith(
        primary: Color = this.primary,
        secondary: Color = this.secondary,
        background: Color = this.background,
        surface: Color = this.surface,
        accent: Color = this.accent,
        coverGrid: List<Color> = this.coverGrid,
        coverGridDim: Int = this.coverGridDim,
    ): ColorPalette =
        ColorPalette(primary, secondary, background, surface, accent, coverGrid, coverGridDim)

    // ---- 暗底前景可读性 ----------------------------------------------------------------------

    /**
     * **前景版**强调色：保证它压在 [surface] 上的 WCAG 对比度 ≥ [minContrast]。
     *
     * 取色结果是任意颜色（深红封面 → 深红、近黑封面 → 近黑），直接拿它当歌曲名 / 图标 /
     * 「正在播放」动效的颜色用，在暗底上会不可读（真机已复现）。实现：按 WCAG 2.x 相对亮度
     * 公式计算对比度，已达标则原样返回；否则每次往白色混 8%、最多 12 步直到达标；仍不达标
     * 则回退 [AppColors.textPrimary]。
     *
     * **背景 / 边条 / 渐变**类用途不要用它，直接用 [primary] 配 `.copy(alpha = …)`。
     */
    fun readablePrimary(surface: Color = AppColors.surface, minContrast: Float = 4.5f): Color =
        _readableOnDark(primary, surface, minContrast)

    companion object {

        /** sRGB 分量 → 线性值（WCAG 2.x 定义）。 */
        private fun _linear(v: Float): Float =
            if (v <= 0.03928f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)

        /** WCAG 2.x 相对亮度：线性分量按 0.2126 / 0.7152 / 0.0722 加权。 */
        private fun _luminance(c: Color): Float =
            0.2126f * _linear(c.red) + 0.7152f * _linear(c.green) + 0.0722f * _linear(c.blue)

        /** WCAG 对比度 (L_亮 + 0.05) / (L_暗 + 0.05)。 */
        private fun _contrast(a: Color, b: Color): Float {
            val la = _luminance(a)
            val lb = _luminance(b)
            return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
        }

        /** 见 [ColorPalette.readablePrimary]。返回可安全用作暗底前景的颜色。 */
        private fun _readableOnDark(color: Color, surface: Color, minContrast: Float): Color {
            if (_contrast(color, surface) >= minContrast) return color
            var c = color
            repeat(12) {
                c = _mixWithWhite(c, 0.08f)
                if (_contrast(c, surface) >= minContrast) return c
            }
            return if (_contrast(AppColors.textPrimary, surface) >= minContrast) {
                AppColors.textPrimary
            } else {
                c
            }
        }

        /** 把 [color] 往白色混 [t]（0..1），保持 alpha 不变。 */
        private fun _mixWithWhite(color: Color, t: Float): Color = Color(
            red = color.red + (1f - color.red) * t,
            green = color.green + (1f - color.green) * t,
            blue = color.blue + (1f - color.blue) * t,
            alpha = color.alpha,
        )

        /**
         * 取色失败回退色板（Flutter: `ColorPalette.fallback` / RN useColorExtractor 默认值）。
         *
         * primary = #6366F1，secondary = #8B5CF6，background = #0A0A1A，surface = #1E1B4B。
         */
        val fallback = ColorPalette(
            primary = Color(0xFF6366F1),
            secondary = Color(0xFF8B5CF6),
            background = Color(0xFF0A0A1A),
            surface = Color(0xFF1E1B4B),
        )
    }
}

/**
 * 封面动态取色契约（**仅移植契约，不含算法实现**）。
 *
 * 对应 Flutter `lib/core/utils/color_extractor.dart`（Flutter 工程已从本分支移除）的
 * `extractPaletteFromCover`，也对应 RN 版 `useColorExtractor.ts`。
 *
 * > 注意：实现见 `core/util/PlayerCoverColorExtractor.kt` 的顶层函数
 * > `extractPlayerCoverPalette` —— **全站共用**（播放页取 4×4 网格，
 * > 其它页面取 primary / secondary / accent 三个锚点）。
 *
 * ## 输入
 * - [coverSource]：封面地址，可以是
 *   - 本地绝对路径，
 *   - `file://` URI，
 *   - 或网络 URL（下载时需带浏览器 UA 绕过防盗链）。
 *
 * ## 输出
 * - 成功：返回 [ColorPalette]，其中仅 `primary` / `secondary` 为**提取结果**，
 *   `background` / `surface` 沿用 [ColorPalette.fallback] 默认值；
 *   调用方应使用 [ColorPalette.copyWith] 合并，避免覆盖当前背景色。
 * - 失败（下载 / 解码失败，或 [coverSource] 为空）：返回 `null`。
 *
 * ## 行为约定
 * 1. [coverSource] 为空 → 立即返回 `null`。
 * 2. 相同 [coverSource] 的结果必须缓存（含 `null`），避免重复下载与解码。
 * 3. 解码与量化应在后台线程执行，不得阻塞主线程（Flutter 用 `compute` isolate）。
 * 4. 任何异常必须被静默吞掉并返回 `null`，由调用方保留当前默认色板。
 *
 * ## 取色语义（供实现参考，本接口不强制）
 * - `primary`：全图像素算术平均 RGB（对齐 RN Android `average`）。
 * - `secondary`：按 AndroidX Palette 目标依次退化为
 *   Vibrant → Muted → DarkVibrant → DarkMuted，仍无则取 `#8B5CF6`。
 *
 * > 说明：本文件只保留上述契约文档；**取色算法的实际实现已迁移至
 * > `core/util/PlayerCoverColorExtractor.kt` 的顶层函数 `extractPlayerCoverPalette`**
 * > （播放页专用），因此这里不再保留空接口，避免与真实实现重复。
 */
