package com.annieplayer.annie_music.kt.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 间距标尺（Flutter: `AppSpacing` + 全 App `EdgeInsets` / `SizedBox` 常量，单位 dp）。
 *
 * Flutter 无统一 spacing 刻度，这里把高频出现的数值收敛为一套刻度，并保留语义化别名。
 */
object AppSpacing {
    // ---- Flutter: AppSpacing（屏幕边距）----

    /** 屏幕左右边距（Flutter: AppSpacing.screenPaddingH） */
    val screenPaddingH = 24.dp

    /** 屏幕上下边距（Flutter: AppSpacing.screenPaddingV） */
    val screenPaddingV = 16.dp

    // ---- 通用刻度（dp）----

    val sp1 = 1.dp
    val sp2 = 2.dp
    val sp3 = 3.dp
    val sp4 = 4.dp
    val sp6 = 6.dp
    val sp8 = 8.dp
    val sp10 = 10.dp
    val sp12 = 12.dp
    val sp14 = 14.dp
    val sp16 = 16.dp
    val sp18 = 18.dp
    val sp20 = 20.dp
    val sp22 = 22.dp
    val sp24 = 24.dp
    val sp28 = 28.dp
    val sp32 = 32.dp
    val sp40 = 40.dp
    val sp44 = 44.dp
    val sp60 = 60.dp

    // ---- 语义化内边距 ----

    /** 浮层底部为迷你播放器预留（Flutter: `EdgeInsets.only(bottom: 100)`） */
    val sheetBottomInset = 100.dp

    /** 列表底部预留（Flutter: `EdgeInsets.only(bottom: 120)`） */
    val listBottomInset = 120.dp

    /**
     * 常驻底栏内容避让（tab 内容底部预留）。
     *
     * 推导：底栏总占用 = 内容区 [AppSize.bottomBarHeight] 60 + 封面凸出
     * [AppSize.bottomBarProtrusion] 0（当前封面与栏体等高）+ 系统导航 inset（内置栏体，手势条典型 24）
     * ≈ 84dp，再留足视觉呼吸 → 120dp（对三键导航的大 inset 也仍然够用）。
     */
    val bottomBarInset = 120.dp

    /**
     * 搜索结果列表底部预留。
     *
     * 旧注释只记录了 Flutter 原值，容易被误读为「为旧迷你条（约 78dp）预留」。按新几何重算：
     * - 常驻底栏（内容区 60 + 封面凸出 20 = 80dp；系统 inset 内置于栏体）→ 仅需 80 + 呼吸；
     * - 右下角 ⇄ 源切换按钮顶边 = [sheetBottomInset] 100 + [AppSize.headerActionHeight] 48 = 148dp
     *   → 需 148 + 呼吸，**这才是主导约束**。
     * 取较大者再加 22dp 呼吸，结论仍为 170dp：数值不变，仅补全推导（避免下一次改动误删）。
     */
    val searchListBottomInset = 170.dp
}

/**
 * 圆角标尺（Flutter: `AppRadius` + 全 App `BorderRadius.circular()`，单位 dp）。
 *
 * Flutter 语义别名 card=12 / panel=14 / pill=999 保持原样；
 * 内联圆角（1.5 / 2 / 2.5 / 4 / 6 / 8 / 10 / 11 / 16 / 20）一并收录。
 */
object AppRadius {
    // ---- Flutter: AppRadius ----
    val card = 12.dp
    val panel = 14.dp
    val pill = 999.dp

    // ---- 内联圆角 ----
    val r1_5 = 1.5.dp  // 进度条圆角
    val r2 = 2.dp      // 小指示条（app_sheet / queue_sheet）
    val r2_5 = 2.5.dp  // 播放进度条
    val r4 = 4.dp      // 标签 chip
    val r6 = 6.dp      // 骨架块 / 选中歌曲行
    val r8 = 8.dp      // 常用小圆角
    val r10 = 10.dp    // 搜索框 / 列表项
    val r11 = 11.dp    // 进度轨道（player_screen）
    val r16 = 16.dp    // 大卡片（player_screen）
    val r20 = 20.dp    // 弹窗容器
}

/**
 * 组件尺寸（Flutter 各文件 `SizedBox` / `height:` / 布局常量，单位 dp）。
 *
 * 每个值均来自 Flutter 源，仅按语义命名以便复用。
 */
object AppSize {
    /** 搜索栏 / 模式下拉按钮高度（Flutter: search_bar / mode_dropdown height: 36） */
    val searchBarHeight = 36.dp

    /** 模式下拉面板顶部偏移（Flutter: mode_dropdown `_panelTop = 40`） */
    val dropdownPanelTop = 40.dp

    /** 模式下拉面板宽度（Flutter: mode_dropdown `_panelWidth = 140`） */
    val dropdownPanelWidth = 140.dp

    /** 通用列表行缩略图 / 操作行高度（Flutter: height: 44） */
    val rowThumb = 44.dp

    /** 音源选择项高度（Flutter: source_picker_sheet height: 46） */
    val sourceItemHeight = 46.dp

    /** 头部操作区高度（Flutter: home_screen height: 48） */
    val headerActionHeight = 48.dp

    /** 圆形播放按钮（Flutter: mini_player height: 50） */
    val playCircle = 50.dp

    /** 播放页大操作按钮（Flutter: player_screen height: 52） */
    val bigActionButton = 52.dp

    /** 账号 / Token 列表项高度（Flutter: kkbox/qobuz sheet height: 54） */
    val tokenItemHeight = 54.dp

    /** 歌手头像（Flutter: singer_search_results height: 56） */
    val singerAvatar = 56.dp

    /** 详情页歌曲行高度（Flutter: album/singer/qobuz/kkbox `_rowHeight = 60`） */
    val detailRowHeight = 60.dp

    /** 迷你播放器高度（Flutter: mini_player height: 62） */
    val miniPlayerHeight = 62.dp

    /** 下载任务行高度（Flutter: download_queue_sheet `_itemHeight = 74`） */
    val downloadItemHeight = 74.dp

    /** 联系弹窗内层盒高度（Flutter: contact_modal height: 80） */
    val modalInnerBox = 80.dp

    /** 账号 / Token 项高度（Flutter: kkbox/qobuz sheet `_itemHeight = 90`） */
    val accountItemHeight = 90.dp

    /** 迷你播放器高度预留（Flutter: quality_picker `_miniPlayerReserve = 92`） */
    val miniPlayerReserve = 92.dp

    /** 封面占位方块（Flutter: 详情页 height: 100） */
    val coverPlaceholder = 100.dp

    /** 搜索选中浮层列表最大高度（Flutter: search_selection_overlay height: 120） */
    val selectionListHeight = 120.dp

    /** 抽屉宽度占比（Flutter: menu_drawer `drawerWidth = 0.72`，非 dp，为屏宽比例） */
    const val drawerWidthFraction = 0.72f

    // ---- 常驻底栏（Android-only 设计，Flutter 基线无对应实现）----

    /**
     * 底栏玻璃面板的**内容区**高度（系统导航 inset 之上的部分）。
     *
     * 面板实际高度 = bottomBarHeight + 系统导航 inset：inset 内置于栏体内部
     * （见 `AppBottomBar`），玻璃面板始终向下贴到屏幕底边，内容（tab / 中键）抬到
     * inset 之上 —— 因此不会出现「整条被抬高、又高又悬」的观感。
     * 内容区刻意低于封面，让封面「凸出」面板顶边之上。
     */
    val bottomBarHeight = 60.dp

    /** 底栏中键封面盒边长：与 [bottomBarHeight] **等高**（不凸出，平齐嵌在栏体中间） */
    val bottomBarCoverSize = bottomBarHeight

    /**
     * 封面盒高出面板顶边的距离（当前为 0：封面与栏体等高、不凸出）。
     *
     * 栏体总占用高度 = bottomBarHeight + 系统 inset + bottomBarProtrusion（约 60 + inset）。
     */
    val bottomBarProtrusion = 0.dp

    /** 底栏宽度占比（刻意非全宽：两侧留白，后续还能继续加按钮） */
    const val bottomBarWidthFraction = 0.84f

    /** 底栏左右两个 tab 按钮的命中区边长（Material 建议 ≥ 48dp） */
    val bottomBarTabHitSize = 48.dp

    /** 底栏 tab 选中胶囊的边长（纯视觉，小于命中区） */
    val bottomBarTabPillSize = 40.dp

    // ---- 底栏「本地曲库」态中键迷你播放器（Android-only 设计，复用旧迷你条组件）----

    /** 中键迷你播放器行封面边长 */
    val bottomBarMiniCoverSize = 46.dp

    /** 中键迷你播放器行播放/暂停按钮直径 */
    val bottomBarMiniPlaySize = 40.dp

    /** 中键迷你播放器行进度条高度 */
    val bottomBarMiniProgressHeight = 3.dp
}

/**
 * 图标尺寸（Flutter: `Icon(..., size: N)`，单位 dp）。
 *
 * Flutter 的 `Icon.size` 默认 24；此处收录 App 中显式设置过的尺寸。
 */
object AppIconSize {
    val icon13 = 13.dp  // mode_dropdown 下拉箭头
    val icon15 = 15.dp  // player_screen 播放小标
    val icon16 = 16.dp  // 通用小图标
    val icon18 = 18.dp  // 状态图标 / 下载按钮
    val icon20 = 20.dp  // 操作图标
    val icon22 = 22.dp  // 返回 / 关闭
    val icon24 = 24.dp  // Flutter Icon 默认值
    val icon26 = 26.dp  // 交换按钮
    val icon28 = 28.dp  // 空态音乐图标
    val icon32 = 32.dp  // 大图标容器
    val icon36 = 36.dp  // 空态大图标
    val icon40 = 40.dp  // 空态专辑图标
    val icon48 = 48.dp  // 超大空态图标
}

/**
 * 描边宽度（Flutter: `BorderSide(width:)` / `Border.all(width:)` / `strokeWidth`，单位 dp）。
 */
object AppBorder {
    /** 分隔线（Flutter: width 1） */
    val thin = 1.dp

    /** 面板 / 圆环边框（Flutter: width 2） */
    val thick = 2.dp

    /** 进度指示器描边（Flutter: `CircularProgressIndicator(strokeWidth: 2)`） */
    val progressStroke = 2.dp
}

/**
 * 阴影参数（Flutter: `BoxShadow` / `Shadow`，单位 dp）。
 *
 * Flutter 端未使用 Material elevation，改为自定义 BoxShadow；
 * 因此 Dimens 中以「模糊半径 + 偏移」表达层级，而非 elevation。
 */
object AppShadow {
    /** 模糊半径 4（Flutter: player_screen BoxShadow blurRadius 4） */
    val blurSm = 4.dp

    /** 模糊半径 6（Flutter: player_screen BoxShadow blurRadius 6） */
    val blurMd = 6.dp

    /** 模糊半径 10（Flutter: splash_screen Shadow blurRadius 10） */
    val blurLg = 10.dp

    /** 模糊半径 12（Flutter: home_screen / player_screen blurRadius 12） */
    val blurXl = 12.dp

    /** 模糊半径 16（Flutter: mini_player BoxShadow blurRadius 16） */
    val blur2xl = 16.dp

    /** 模糊半径 20（Flutter: player_screen 歌词辉光 blurRadius 20） */
    val blur3xl = 20.dp

    /** 迷你播放器顶部阴影偏移（Flutter: mini_player `Offset(0, -4)`） */
    val miniPlayerOffsetY = (-4).dp

    /** 首页头部阴影偏移（Flutter: home_screen `Offset(0, 4)`） */
    val headerOffsetY = 4.dp

    /** 播放页小控件阴影偏移（Flutter: player_screen `Offset(0, 1)`） */
    val controlOffsetY = 1.dp

    /** 播放页大按钮阴影偏移（Flutter: player_screen `Offset(0, 2)`） */
    val buttonOffsetY = 2.dp

    /** 零偏移（用于无需位移的辉光，如歌词 Shadow） */
    val noOffset: Dp = 0.dp
}
