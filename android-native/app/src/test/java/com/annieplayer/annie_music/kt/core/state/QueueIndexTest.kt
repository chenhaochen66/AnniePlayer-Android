package com.annieplayer.annie_music.kt.core.state

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [indexAfterMove] / [indexAfterRemoval] 的单测（纯 JVM）。
 *
 * 这两个公式**App 层与引擎层共用**：只要有一处算错，`PlayerViewModel.currentIndex`
 * 与 `MediaPlayerBackend._currentAbsIndex` 就会错位 —— 现象是「当前曲高亮错位」或
 * 「下一首跳到奇怪的地方」，而且极难从现象倒推。所以把四种跨越关系全部写成断言。
 */
class QueueIndexTest {

    // ── 拖拽：indexAfterMove(current, from, to) ──

    @Test
    fun `移动在当前曲同一侧不动指针`() {
        // 队列 5 首，当前 2；把 0 挪到 1（都在当前曲上方）
        assertEquals(2, indexAfterMove(current = 2, from = 0, to = 1))
        // 把 4 挪到 3（都在当前曲下方）
        assertEquals(2, indexAfterMove(current = 2, from = 4, to = 3))
    }

    @Test
    fun `从上方跨过当前曲时指针前移一格`() {
        // 0 挪到 3：1、2、3 整体前移，当前曲 2 变成 1
        assertEquals(1, indexAfterMove(current = 2, from = 0, to = 3))
    }

    @Test
    fun `从下方跨过当前曲时指针后移一格`() {
        // 4 挪到 0：0、1 后移，当前曲 2 变成 3
        assertEquals(3, indexAfterMove(current = 2, from = 4, to = 0))
    }

    @Test
    fun `移动当前曲自己就跟着走`() {
        assertEquals(0, indexAfterMove(current = 2, from = 2, to = 0))
        assertEquals(4, indexAfterMove(current = 2, from = 2, to = 4))
    }

    @Test
    fun `挪到紧邻当前曲前面不算跨过`() {
        // 0 挪到 1：当前曲 2 前面仍是两项，位置不变
        assertEquals(2, indexAfterMove(current = 2, from = 0, to = 1))
        // 4 挪到 3：当前曲下方仍是同样的项，位置不变
        assertEquals(2, indexAfterMove(current = 2, from = 4, to = 3))
    }

    @Test
    fun `挪到当前曲所在位置则指针前移`() {
        // 0 挪到 2（正好是当前曲的位置）：0 变 1、1 变 2，当前曲被挤到 1
        assertEquals(1, indexAfterMove(current = 2, from = 0, to = 2))
    }

    // ── 摘项：indexAfterRemoval(current, removed, remainingSize) ──

    @Test
    fun `摘掉当前曲前面的项指针前移`() {
        assertEquals(1, indexAfterRemoval(current = 2, removed = 0, remainingSize = 4))
    }

    @Test
    fun `摘掉当前曲后面的项指针不动`() {
        assertEquals(2, indexAfterRemoval(current = 2, removed = 4, remainingSize = 4))
    }

    @Test
    fun `摘掉当前曲自身时保持原值`() {
        // 调用方必须先切歌；这里只保证不越界
        assertEquals(2, indexAfterRemoval(current = 2, removed = 2, remainingSize = 4))
    }

    @Test
    fun `摘到只剩一项时夹到 0`() {
        // 队列 3 首，当前在末位 2，摘掉 0 与 1 之后只剩 1 项
        assertEquals(0, indexAfterRemoval(current = 2, removed = 0, remainingSize = 1))
        assertEquals(0, indexAfterRemoval(current = 2, removed = 2, remainingSize = 1))
    }

    @Test
    fun `摘空后下标为 0 不越界`() {
        assertEquals(0, indexAfterRemoval(current = 0, removed = 0, remainingSize = 0))
    }
}
