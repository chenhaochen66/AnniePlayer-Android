package com.annieplayer.annie_music.kt.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字号标尺（Flutter: `AppFontSize`，对齐 RN 版 tokens.ts fontSize）。
 *
 * 命名与 Flutter 完全一致：xs/sm/base/lg/xl/title。
 * 其余为 Flutter 各页面内联使用的字号（见下方注释），一并补全以保证 1:1。
 */
object AppFontSize {
    // ---- Flutter: AppFontSize ----
    val xs = 11.sp       // AppFontSize.xs
    val sm = 12.sp       // AppFontSize.sm
    val base = 14.sp     // AppFontSize.base
    val lg = 16.sp       // AppFontSize.lg
    val xl = 18.sp       // AppFontSize.xl
    val title = 24.sp    // AppFontSize.title

    // ---- Flutter 内联字号（fontSize: N）----
    val xxs = 8.sp       // 品质角标（album_detail_sheet / screen）：fontSize 8
    val size9 = 9.sp     // 歌词指示点：fontSize 9
    val size10 = 10.sp   // 元信息 / 状态标签：fontSize 10
    val size13 = 13.sp   // 列表副标题：fontSize 13
    val size15 = 15.sp   // 输入框 / 列表主文本：fontSize 15
    val size17 = 17.sp   // 弹窗标题：fontSize 17
    val size20 = 20.sp   // 页面标题：fontSize 20
    val size26 = 26.sp   // 首页大标题：fontSize 26
    val size32 = 32.sp   // 开屏标题：fontSize 32
    val size34 = 34.sp   // 播放页逐字歌词：fontSize 34
}

/**
 * 字重标尺（Flutter: `FontWeight.wXxx`）。
 *
 * Flutter 默认字重为 w400（normal）。仅收录 App 中实际出现过的字重。
 */
object AppFontWeight {
    val normal = FontWeight.Normal        // w400（Flutter 默认）
    val medium = FontWeight.Medium        // w500
    val semiBold = FontWeight.SemiBold    // w600
    val bold = FontWeight.Bold            // w700（等同 Flutter FontWeight.bold）
    val extraBold = FontWeight.ExtraBold  // w800
}

/**
 * 字距（Flutter: `TextStyle.letterSpacing`，单位已换算为 sp）。
 *
 * Flutter 默认字距为 0，此处只收录显式设置过的值。
 */
object AppLetterSpacing {
    val tight = (-0.5).sp  // home_screen 标题：letterSpacing -0.5
    val normal = 0.sp      // 默认
    val wide = 0.5.sp      // menu_drawer / home_screen 标签：letterSpacing 0.5
    val wider = 2.sp       // splash_screen 标题：letterSpacing 2
}

/**
 * 行高（Flutter: `TextStyle.height` 为「行高倍数」，Compose 为绝对行高）。
 *
 * 换算：Compose `lineHeight = Flutter fontSize × height`。
 * - contact_modal：fontSize 11、height 16/11 → 16sp
 * - player_screen 逐字歌词：fontSize 34、height 40/34 → 40sp
 * - player_screen 逐字翻译：fontSize 17、height 28/17 → 28sp
 */
object AppLineHeight {
    val body16 = 16.sp  // 11sp 文本（contact_modal height: 16/11）
    val lyric40 = 40.sp // 34sp 歌词（player_screen height: 40/34）
    val lyric28 = 28.sp // 17sp 翻译（player_screen height: 28/17）
}

/**
 * 命名文本样式（Flutter 端各处的 `TextStyle(...)` 归并）。
 *
 * 前 4 项对应 Flutter `main.dart` 中 `ThemeData.textTheme` 的映射，用于喂给
 * `MaterialTheme.typography`；其余为全 App 高频复现的组件级文本样式。
 */
object AnnieTypography {
    // ---- Flutter: main.dart ThemeData.textTheme ----

    /** Flutter: bodyMedium（textSecondary / base=14sp） */
    val bodyMedium = TextStyle(
        color = AppColors.textSecondary,
        fontSize = AppFontSize.base,
        fontWeight = AppFontWeight.normal,
    )

    /** Flutter: bodySmall（textTertiary / sm=12sp） */
    val bodySmall = TextStyle(
        color = AppColors.textTertiary,
        fontSize = AppFontSize.sm,
        fontWeight = AppFontWeight.normal,
    )

    /** Flutter: titleLarge（textPrimary / title=24sp / w700） */
    val titleLarge = TextStyle(
        color = AppColors.textPrimary,
        fontSize = AppFontSize.title,
        fontWeight = AppFontWeight.bold,
    )

    /** Flutter: titleMedium（textPrimary / xl=18sp / w600） */
    val titleMedium = TextStyle(
        color = AppColors.textPrimary,
        fontSize = AppFontSize.xl,
        fontWeight = AppFontWeight.semiBold,
    )

    // ---- 组件级命名样式（Flutter 内联 TextStyle 归并）----

    /** 弹窗 / 浮层主标题：white 17sp w600（kkbox_account_sheet / qobuz_token_sheet / mini_player 等） */
    val dialogTitle = TextStyle(
        color = AppColors.white,
        fontSize = AppFontSize.size17,
        fontWeight = AppFontWeight.semiBold,
    )

    /** 页面 / 头部标题：white 20sp w700（downloads_screen / token sheet 头部） */
    val screenTitle = TextStyle(
        color = AppColors.white,
        fontSize = AppFontSize.size20,
        fontWeight = AppFontWeight.bold,
    )

    /** 浮层标题：white 18sp w700（app_sheet / contact_modal / quality_picker） */
    val sheetTitle = TextStyle(
        color = AppColors.white,
        fontSize = AppFontSize.xl,
        fontWeight = AppFontWeight.bold,
    )

    /** 区块标题：white 16sp w600（详情页头部、搜索选中浮层） */
    val sectionTitle = TextStyle(
        color = AppColors.white,
        fontSize = AppFontSize.lg,
        fontWeight = AppFontWeight.semiBold,
    )

    /** 列表主文本：white 14sp（search_selection_overlay / downloads_screen） */
    val bodyStrong = TextStyle(
        color = AppColors.white,
        fontSize = AppFontSize.base,
        fontWeight = AppFontWeight.medium,
    )

    /** 列表次文本：white70 14sp（搜索栏输入） */
    val body = TextStyle(
        color = AppColors.white70,
        fontSize = AppFontSize.base,
        fontWeight = AppFontWeight.normal,
    )

    /** 说明 / 弱化文本：textSecondary 14sp */
    val caption = TextStyle(
        color = AppColors.textSecondary,
        fontSize = AppFontSize.base,
        fontWeight = AppFontWeight.normal,
    )

    /** 小号元信息：textTertiary 11sp（xs） */
    val meta = TextStyle(
        color = AppColors.textTertiary,
        fontSize = AppFontSize.xs,
        fontWeight = AppFontWeight.normal,
    )

    /** 极小号角标：10sp w600（品质 / 状态标签） */
    val badge = TextStyle(
        color = AppColors.primary,
        fontSize = AppFontSize.size10,
        fontWeight = AppFontWeight.semiBold,
    )

    /** 开屏标题：white 32sp bold / letterSpacing 2 */
    val splashTitle = TextStyle(
        color = AppColors.white,
        fontSize = AppFontSize.size32,
        fontWeight = AppFontWeight.bold,
        letterSpacing = AppLetterSpacing.wider,
    )

    /** 逐字歌词：white 34sp w800（player_screen） */
    val lyricLine = TextStyle(
        color = AppColors.white,
        fontSize = AppFontSize.size34,
        fontWeight = AppFontWeight.extraBold,
        lineHeight = AppLineHeight.lyric40,
    )

    /** 逐字歌词翻译：17sp w700（player_screen） */
    val lyricTranslation = TextStyle(
        color = AppColors.white70,
        fontSize = AppFontSize.size17,
        fontWeight = AppFontWeight.bold,
        lineHeight = AppLineHeight.lyric28,
    )
}
