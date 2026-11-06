package com.annieplayer.annie_music.kt.core.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [planQueueRemoval] 的单测（纯 JVM）。
 *
 * 钉住的是「删除本地文件时队列怎么摘」这套下标运算：
 * - 先摘非当前项（降序，避免下标位移）；
 * - 当前曲最后处理（切歌 → 再摘），否则 `next()` 可能落到另一首待删的歌上；
 * - 整队都要删时走 `clearAll`（清空 + 停播），不逐项删。
 */
class QueueRemovalTest {

    private fun plan(queue: List<String>, remove: List<String>, currentIndex: Int) =
        planQueueRemoval(queue, remove.toSet(), currentIndex)

    @Test
    fun `空队列没有任何计划`() {
        val p = plan(emptyList(), listOf("a"), 0)
        assertTrue(p.none)
        assertFalse(p.clearAll)
    }

    @Test
    fun `没命中任何歌`() {
        val p = plan(listOf("a", "b"), listOf("x", "y"), 0)
        assertTrue(p.none)
    }

    @Test
    fun `只删非当前项时按降序排列`() {
        val p = plan(listOf("a", "b", "c", "d"), listOf("b", "d"), 0)
        assertEquals(listOf(3, 1), p.removeFirst)
        assertFalse(p.currentInIds)
        assertEquals(2, p.hitCount)
    }

    @Test
    fun `删当前曲时不进 removeFirst`() {
        val p = plan(listOf("a", "b", "c"), listOf("b"), 1)
        assertTrue(p.removeFirst.isEmpty())
        assertTrue(p.currentInIds)
        assertEquals(1, p.hitCount)
    }

    @Test
    fun `同时删当前曲与别的项`() {
        // 队列 [a, b, c]，当前是 b，删 a 与 b：
        // a 不是当前曲 → 进 removeFirst；b 是当前曲 → 等切歌之后再摘
        val p = plan(listOf("a", "b", "c"), listOf("a", "b"), 1)
        assertEquals(listOf(0), p.removeFirst)
        assertTrue(p.currentInIds)
        assertEquals(2, p.hitCount)
    }

    @Test
    fun `删当前曲两侧的项但不含当前曲`() {
        // 队列 [a, b, c]，当前 b，删 a 与 c —— 当前曲不该被标记
        val p = plan(listOf("a", "b", "c"), listOf("a", "c"), 1)
        assertEquals(listOf(2, 0), p.removeFirst)
        assertFalse(p.currentInIds)
        assertEquals(2, p.hitCount)
    }

    @Test
    fun `当前曲在末尾且被删`() {
        val p = plan(listOf("a", "b", "c"), listOf("c"), 2)
        assertTrue(p.removeFirst.isEmpty())
        assertTrue(p.currentInIds)
    }

    @Test
    fun `整队都要删走 clearAll`() {
        val p = plan(listOf("a", "b"), listOf("a", "b"), 0)
        assertTrue(p.clearAll)
        assertFalse(p.none)
    }

    @Test
    fun `只删一首但队列只有它`() {
        val p = plan(listOf("a"), listOf("a"), 0)
        assertTrue(p.clearAll)
    }

    @Test
    fun `当前下标越界时不算当前曲`() {
        val p = plan(listOf("a", "b"), listOf("a"), 99)
        assertEquals(listOf(0), p.removeFirst)
        assertFalse(p.currentInIds)
    }

    @Test
    fun `removeIds 里的空串被忽略`() {
        val p = plan(listOf("a", "b"), listOf(""), 0)
        assertTrue(p.none)
    }

    @Test
    fun `队列里的空 id 不会被误删`() {
        val p = plan(listOf("", "a"), listOf(""), 0)
        assertTrue(p.none)
    }
}
