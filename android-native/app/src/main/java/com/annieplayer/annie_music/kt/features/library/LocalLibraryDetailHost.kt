package com.annieplayer.annie_music.kt.features.library

import androidx.compose.runtime.Composable

/**
 * 本地曲库页面条目的外壳（由 `AppShell` 的 `pageStack` 槽位调用）。
 *
 * 存在的意义只有一件：把「遮罩 + 150ms 滑入/滑出 + 关闭先播动画」这套外壳
 * （[LocalOverlayFrame]，规格与流媒体 `AlbumDetailSheet` / `SingerDetailSheet` 相同）
 * 套在本地页面上，同时**不改动页面自身的签名** —— 页面照旧收 `isTop` / `onClose`。
 *
 * 注意：本地页面已经并入流媒体那条 `pageStack`，所以这里**不再自己管栈**；
 * [onClose] 由宿主传 `uiViewModel.popRoute()`，返回裁决/层级顺序全部走 AppShell 那一套。
 */
@Composable
fun LocalLibraryDetailHost(
    isTop: Boolean,
    onClose: () -> Unit,
    content: @Composable (requestClose: () -> Unit) -> Unit,
) {
    LocalOverlayFrame(onClose = onClose) { requestClose ->
        content(requestClose)
    }
}
