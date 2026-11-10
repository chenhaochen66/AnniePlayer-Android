package com.annieplayer.annie_music.kt.core.routing

/**
 * Flutter 端并非标准导航栈，而是「单屏 + 覆盖层栈」结构。
 *
 * 渲染顺序是载荷相关（load-bearing）的：同一层级的覆盖层，越靠后声明越后绘制，
 * 因此会盖在更早的覆盖层之上。Compose 层必须严格按 [Overlay.RENDER_ORDER]
 * 的顺序把每个覆盖层从底到顶叠加，才能得到与 Flutter 完全一致的观感。
 *
 * 本文件只描述「顺序」与「返回手势优先级」两份数据，不依赖任何 ViewModel /
 * Composable，方便后续 UI 层直接镜像。渲染顺序与返回优先级分别由
 * [Overlay.RENDER_ORDER]、[BACK_PRIORITY] / [handleBack] 承载，刻意表达为
 * 数据而非 `if` 链，避免顺序在改动中被悄悄破坏。
 */
enum class Overlay(
    /** 与 Flutter 语义一一对应的稳定标识，保留英文以对齐原实现。 */
    val key: String,
) {
    /** 基础内容列（Column），始终位于最底层。 */
    COLUMN("column"),

    /** 全局型覆盖层容器（GlobalOverlays）。 */
    GLOBAL_OVERLAYS("globalOverlays"),

    /** 侧边菜单抽屉（MenuDrawer）。 */
    MENU_DRAWER("menuDrawer"),

    /** 下载任务整页（DownloadQueueSheet）。 */
    DOWNLOAD_QUEUE("downloadQueue"),

    /** 迷你播放条（MiniPlayer）。 */
    MINI_PLAYER("miniPlayer"),

    /** 全屏播放器覆盖层（PlayerOverlay）。 */
    PLAYER_OVERLAY("playerOverlay"),

    /** 单曲操作菜单（SongActionsSheet）。 */
    SONG_ACTIONS_SHEET("songActionsSheet"),

    /**
     * 本地歌曲删除确认弹窗（LocalDeleteModal）。
     *
     * ⋮ 面板的「删除」会先收起面板再挂出这个弹窗，所以它紧跟在 [SONG_ACTIONS_SHEET] 之上：
     * 两者不会同时可见，顺序在这里只表达「谁盖谁」的兜底关系。
     */
    LOCAL_DELETE_MODAL("localDeleteModal"),

    /** 歌手选择弹窗（ArtistPickerSheet）。 */
    ARTIST_PICKER_SHEET("artistPickerSheet"),

    /** 下载音质选择器（QualityPicker / download）。 */
    QUALITY_PICKER_DOWNLOAD("qualityPickerDownload"),

    /** 批量音质选择器（QualityPicker / batch）。 */
    QUALITY_PICKER_BATCH("qualityPickerBatch"),

    /** 播放队列抽屉（QueueSheet）。 */
    QUEUE_SHEET("queueSheet"),

    /** 联系方式弹窗（ContactModal）。 */
    CONTACT_MODAL("contactModal"),

    /** 「下载中」轻提示（toast）。 */
    DOWNLOAD_PROGRESS_TOAST("downloadProgressToast"),
    ;

    companion object {
        /**
         * 自底向上的渲染顺序，Compose 层按此顺序绘制即可完全复刻 Flutter。
         *
         * 顺序来源（Flutter 原顺序）：
         * Column, GlobalOverlays, MenuDrawer, MiniPlayer, PlayerOverlay,
         * SongActionsSheet, ArtistPickerSheet, QualityPicker(download),
         * QualityPicker(batch), QueueSheet, ContactModal, 下载中 toast。
         *
         * **Kotlin 侧唯一调整**：下载任务页由 Flutter 的 GlobalOverlays 内部
         * 提到 [MENU_DRAWER] 之上（[DOWNLOAD_QUEUE]）。原因是抽屉入口不该关闭抽屉：
         * 提到抽屉之上后子页自然盖住抽屉，返回时先关子页再关抽屉 —— 与 [BACK_PRIORITY]
         * 中 `downloadQueue` 高于 `menuDrawer` 的既定次序一致。
         */
        val RENDER_ORDER: List<Overlay> = listOf(
            COLUMN,
            GLOBAL_OVERLAYS,
            MENU_DRAWER,
            DOWNLOAD_QUEUE,
            MINI_PLAYER,
            PLAYER_OVERLAY,
            SONG_ACTIONS_SHEET,
            LOCAL_DELETE_MODAL,
            ARTIST_PICKER_SHEET,
            QUALITY_PICKER_DOWNLOAD,
            QUALITY_PICKER_BATCH,
            QUEUE_SHEET,
            CONTACT_MODAL,
            DOWNLOAD_PROGRESS_TOAST,
        )
    }
}

/**
 * 返回手势可能被消费的目标。枚举声明顺序即「从高到低」的优先级，
 * 与 [BACK_PRIORITY] 保持一致。
 */
enum class BackTarget(
    /** 稳定标识，保留英文以对齐 Flutter 语义。 */
    val key: String,
) {
    /** 单曲操作菜单。 */
    SONG_ACTIONS("songActions"),

    /** 本地歌曲删除确认弹窗（模态，优先级高于 ⋮ 面板：它弹出时 ⋮ 已经关掉了）。 */
    LOCAL_DELETE("localDelete"),

    /** 歌手选择弹窗。 */
    ARTIST_PICKER("artistPicker"),

    /** 音质选择器（下载 / 批量共用）。 */
    QUALITY_PICKER("qualityPicker"),

    /** 播放队列抽屉。 */
    QUEUE("queue"),

    /** 全屏播放器覆盖层。 */
    PLAYER_OVERLAY("playerOverlay"),

    // ── 页面级 / 全局面板 ─────────────────────────────────────────────────────────────
    //
    // 这一组全部排在 [PAGE] **之上**：它们出现时，详情页不许再吃返回（否则页面会在面板
    // 背后把自己的子标签 / 多选状态悄悄改掉，表现为「返回键没反应」）。
    //
    // 它们在 `AppShell` 里的声明位置都在 `pageStack` 之后，所以实际互斥顺序本来就由
    // **注册顺序**决定（见 7.5）；这里登记只是为了 `handleBack` 能裁决 PAGE / SEARCH
    // 两个处理器该不该启用。组内顺序按声明顺序的逆序排，仅作阅读用。
    TRANSLATE_PICKER("translatePicker"),
    CACHE_CLEAR("cacheClear"),
    CONTACT("contact"),
    SOURCE_PICKER("sourcePicker"),
    LX_PLUGIN_MANAGER("lxPluginManager"),

    /**
     * 「DSD 输出」页。
     *
     * 它是**更多设置的子页**，所以排在 [MORE_SETTINGS] 之上：返回时先关 DSD 页，
     * 再关更多设置 —— 顺序反了会表现为「返回键把两层一起关掉」。
     */
    DSD_OUTPUT("dsdOutput"),

    MORE_SETTINGS("moreSettings"),
    KKBOX_ACCOUNT("kkboxAccount"),
    QOBUZ_TOKEN("qobuzToken"),
    APPLE_SERVICE("appleService"),

    /** 页面栈（`UiState.pageStack`）非空：至少有一个子页面压在根页之上。 */
    PAGE("page"),

    /**
     * 搜索页的「待下载」面板（`SearchSelectionOverlay`）。
     *
     * 它画在搜索结果之上、页面栈之下，所以返回优先级也排在 [PAGE] 之后：
     * 详情页开着时先关详情，面板才轮到。排在 [SEARCH_SELECTION] **之前** ——
     * 面板是「已选列表」的展开形态，返回先收面板，再退多选。
     */
    SEARCH_SELECTION_OVERLAY("searchSelectionOverlay"),

    /**
     * 搜索页的多选态（`selectedSongs` 非空）。
     *
     * 搜索结果的一种**形态**，不是浮层；但返回必须能退它 —— 否则勾了几首歌再按返回，
     * 会直接把搜索态收掉（甚至弹退出确认），选中列表纹丝不动。
     * 排在 [SEARCH] 之上：先退多选，再收搜索态。
     */
    SEARCH_SELECTION("searchSelection"),

    /**
     * 主页的搜索态展开（`UiState.homeSearchExpanded`）。
     *
     * 搜索不是独立页面，但它**压在主页之上**，所以顺序排在 [PAGE] 之后：
     * 详情页开着时返回先关详情，详情关了才收搜索态。
     */
    SEARCH("search"),

    /** 下载队列页 / 面板。 */
    DOWNLOAD_QUEUE("downloadQueue"),

    /** 侧边菜单抽屉。 */
    MENU_DRAWER("menuDrawer"),

    /** 没有任何覆盖层消费该次返回，交还系统或上层处理。 */
    NONE("none"),
}

/**
 * 各覆盖层当前是否可见的简单快照。UI 层可从任意来源（ViewModel、Composable
 * 状态或直接传字面量）填充，本文件不依赖它们是否存在。
 */
data class OverlayVisibilityFlags(
    /** 单曲操作菜单是否可见。 */
    val songActions: Boolean = false,
    /** 歌手选择弹窗是否可见。 */
    val artistPicker: Boolean = false,
    /** 音质选择器是否可见（下载与批量任一即可）。 */
    val qualityPicker: Boolean = false,
    /** 播放队列抽屉是否可见。 */
    val queue: Boolean = false,
    /** 全屏播放器覆盖层是否可见。 */
    val playerOverlay: Boolean = false,
    /** 翻译语言选择器是否可见（`TranslatePicker.visible`，不在 UiState 里）。 */
    val translatePicker: Boolean = false,
    /** 清缓存确认弹窗是否可见。 */
    val cacheClear: Boolean = false,
    /** 本地歌曲删除确认弹窗是否可见。 */
    val localDelete: Boolean = false,
    /** 联系方式弹窗是否可见。 */
    val contact: Boolean = false,
    /** 音源选择器是否可见（`SearchScreenState.sourcePickerVisible`，不在 UiState 里）。 */
    val sourcePicker: Boolean = false,
    /** 落雪插件管理页是否可见。 */
    val lxPluginManager: Boolean = false,
    /** 「DSD 输出」页是否可见。 */
    val dsdOutput: Boolean = false,
    /** 更多设置页是否可见。 */
    val moreSettings: Boolean = false,
    /** KKBOX 账号面板是否可见。 */
    val kkboxAccount: Boolean = false,
    /** Qobuz Token 面板是否可见。 */
    val qobuzToken: Boolean = false,
    /** Apple 服务面板是否可见。 */
    val appleService: Boolean = false,
    /** 页面栈是否非空（有子页面压在根页之上）。 */
    val page: Boolean = false,
    /** 搜索页的「待下载」面板是否打开。 */
    val searchSelectionOverlay: Boolean = false,
    /** 搜索页是否处于多选态（有勾选的歌）。 */
    val searchSelection: Boolean = false,
    /** 主页的搜索态是否展开。 */
    val searchExpanded: Boolean = false,
    /** 下载队列是否可见。 */
    val downloadQueue: Boolean = false,
    /** 侧边菜单抽屉是否可见。 */
    val menuDrawer: Boolean = false,
)

/** 单条返回规则：目标 + 判定其是否处于激活状态的谓词。 */
private class BackRule(
    /** 命中后应被消费的目标。 */
    val target: BackTarget,
    /** 该目标是否可见。 */
    val isActive: (OverlayVisibilityFlags) -> Boolean,
)

/**
 * 返回手势优先级（从高到低）的纯数据表。
 *
 * 顺序来源（Flutter 原顺序）：
 * songActions / artistPicker -> qualityPicker -> queue -> playerOverlay ->
 * album / singer / playlist detail -> downloadQueue -> menuDrawer。
 */
private val BACK_RULES: List<BackRule> = listOf(
    BackRule(BackTarget.LOCAL_DELETE) { it.localDelete },
    BackRule(BackTarget.SONG_ACTIONS) { it.songActions },
    BackRule(BackTarget.ARTIST_PICKER) { it.artistPicker },
    BackRule(BackTarget.QUALITY_PICKER) { it.qualityPicker },
    BackRule(BackTarget.QUEUE) { it.queue },
    BackRule(BackTarget.PLAYER_OVERLAY) { it.playerOverlay },
    BackRule(BackTarget.TRANSLATE_PICKER) { it.translatePicker },
    BackRule(BackTarget.CACHE_CLEAR) { it.cacheClear },
    BackRule(BackTarget.CONTACT) { it.contact },
    BackRule(BackTarget.SOURCE_PICKER) { it.sourcePicker },
    BackRule(BackTarget.LX_PLUGIN_MANAGER) { it.lxPluginManager },
    BackRule(BackTarget.DSD_OUTPUT) { it.dsdOutput },
    BackRule(BackTarget.MORE_SETTINGS) { it.moreSettings },
    BackRule(BackTarget.KKBOX_ACCOUNT) { it.kkboxAccount },
    BackRule(BackTarget.QOBUZ_TOKEN) { it.qobuzToken },
    BackRule(BackTarget.APPLE_SERVICE) { it.appleService },
    BackRule(BackTarget.PAGE) { it.page },
    BackRule(BackTarget.SEARCH_SELECTION_OVERLAY) { it.searchSelectionOverlay },
    BackRule(BackTarget.SEARCH_SELECTION) { it.searchSelection },
    BackRule(BackTarget.SEARCH) { it.searchExpanded },
    BackRule(BackTarget.DOWNLOAD_QUEUE) { it.downloadQueue },
    BackRule(BackTarget.MENU_DRAWER) { it.menuDrawer },
)

/** 仅暴露目标次序的只读列表，供 UI 层展示或断言。 */
val BACK_PRIORITY: List<BackTarget> = BACK_RULES.map { it.target }

/**
 * 解析「本次返回手势应由哪个覆盖层先处理」。
 *
 * 返回 [BACK_RULES] 中第一个处于可见状态的规则目标；若全部不可见，
 * 返回 [BackTarget.NONE]，表示交还系统 / 上层处理。查找按优先级进行，
 * 是纯函数，无副作用。
 */
fun handleBack(flags: OverlayVisibilityFlags): BackTarget =
    BACK_RULES.firstOrNull { it.isActive(flags) }?.target ?: BackTarget.NONE
