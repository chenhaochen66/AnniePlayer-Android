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

    /** 歌手选择弹窗。 */
    ARTIST_PICKER("artistPicker"),

    /** 音质选择器（下载 / 批量共用）。 */
    QUALITY_PICKER("qualityPicker"),

    /** 播放队列抽屉。 */
    QUEUE("queue"),

    /** 全屏播放器覆盖层。 */
    PLAYER_OVERLAY("playerOverlay"),

    /** 专辑 / 歌手 / 歌单详情页。 */
    DETAIL("detail"),

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
    /** 专辑 / 歌手 / 歌单详情页是否可见。 */
    val detail: Boolean = false,
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
    BackRule(BackTarget.SONG_ACTIONS) { it.songActions },
    BackRule(BackTarget.ARTIST_PICKER) { it.artistPicker },
    BackRule(BackTarget.QUALITY_PICKER) { it.qualityPicker },
    BackRule(BackTarget.QUEUE) { it.queue },
    BackRule(BackTarget.PLAYER_OVERLAY) { it.playerOverlay },
    BackRule(BackTarget.DETAIL) { it.detail },
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
