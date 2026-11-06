package com.annieplayer.annie_music.kt.widgets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 全局浮层挂载器（1:1 移植 Flutter `lib/widgets/global_overlays.dart`）。
 *
 * 所有浮层都是自驱动的（内部由宿主传入 `visible` / 数据与回调），统一放在根 Stack 中，
 * 与 RN / Flutter 的挂载方式一致。**渲染顺序即叠放顺序**：靠后者后绘制 → 在上层。
 *
 * 顺序（数组靠后者在上层）：
 * QobuzTokenSheet → KkboxAccountSheet → SearchSelectionOverlay → 页面栈（pageStack）。
 *
 * 关键约束（载荷相关，不得重排）：
 * - 页面栈（[pageStack]）整体位于 `searchSelectionOverlay` 之后，即 `Overlay.RENDER_ORDER`
 *   的 `GLOBAL_OVERLAYS` 层内、`MENU_DRAWER` 之下。子页面的相对顺序不再是固定声明，
 *   而是由宿主的 `pageStack` 按「栈底→栈顶」动态渲染（栈顶盖住下层并先消费返回）。
 * - **下载任务页（DownloadQueueSheet）不在本挂载器内**：它需要盖住侧边抽屉且抽屉不关闭，
 *   由宿主声明在 `MenuDrawer` 之后（见 `Overlay.RENDER_ORDER` 的 [Overlay.DOWNLOAD_QUEUE]）。
 * - **音质选择（[QualityPickerSheet]）不在这里**：它需要在全屏播放页之上才能点选，
 *   由宿主在更上层单独挂载（对齐 Dart `global_overlays.dart` 的注释约定）。
 * - 对应 `core/routing/OverlayStack.kt` 的 `Overlay.RENDER_ORDER`：本挂载器整体即
 *   `GLOBAL_OVERLAYS` 这一层，位于 `COLUMN`（基础内容）之上、`MENU_DRAWER` 之下。
 *
 * 迁移完成前保留插槽，是为了不引用尚未存在的 Composable 而破坏构建。
 */
@Composable
fun GlobalOverlays(
    modifier: Modifier = Modifier,
    qobuzTokenSheet: @Composable () -> Unit = {},
    kkboxAccountSheet: @Composable () -> Unit = {},
    searchSelectionOverlay: @Composable () -> Unit = {},
    /** 页面栈：由宿主按栈底→栈顶顺序渲染；后声明者绘制在上层。 */
    pageStack: @Composable () -> Unit = {},
) {
    Box(modifier = modifier.fillMaxSize()) {
        qobuzTokenSheet()
        kkboxAccountSheet()
        searchSelectionOverlay()
        // 页面栈：整体位于其余全局浮层之后（见上方「关键约束」）
        pageStack()
    }
}
