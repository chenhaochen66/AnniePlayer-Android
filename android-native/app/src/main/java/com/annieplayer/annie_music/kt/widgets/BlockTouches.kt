package com.annieplayer.annie_music.kt.widgets

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 把落在本节点上的触摸全部吃掉（down / move / up 一律 consume）。
 *
 * 全屏浮层的遮罩必须用它：`Modifier.background` **只画不参与命中测试**，光靠它是挡不住
 * 下层的 —— 浮层里凡是"这里没有可点子元素"的位置，触摸都会漏给下层的同级兄弟
 * （实测症状：歌单详情页顶栏以外的空白处能按到 L1 顶栏的菜单按钮，横向拖动还能拨动下层的 pager）。
 *
 * 本节点要声明在内容面板**之前**（更靠下的 z）。命中测试先派给面板里的真控件，它们照常响应；
 * 只有没人消费的触摸才落到这里被吃掉，所以不会误伤页面自己的按钮 / 列表。
 */
fun Modifier.blockTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent().changes.forEach { it.consume() }
        }
    }
}
