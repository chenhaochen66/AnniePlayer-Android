package com.annieplayer.annie_music.kt.core.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 返回裁决的优先级表（`core/routing/OverlayStack.kt`）。
 *
 * 这张表是纯数据 + 纯函数，改一行就改变全局返回行为，而 Compose 那边没有任何编译期保护
 * （`handleBack` 的调用方各自传 `enabled = handleBack(...) == 目标`），所以顺序必须钉在测试里。
 *
 * 这里只钉**语义契约**，不钉具体枚举全集 —— 新增浮层不该让这份测试变红，
 * 只有「谁比谁先吃返回」被改错时才红。
 */
class BackPriorityTest {

    /** 搜索页的两个状态：待下载面板 > 多选态 > 搜索态（一次返回只退一层）。 */
    @Test
    fun `search selection layers sit between page and search`() {
        val page = BACK_PRIORITY.indexOf(BackTarget.PAGE)
        val overlay = BACK_PRIORITY.indexOf(BackTarget.SEARCH_SELECTION_OVERLAY)
        val selection = BACK_PRIORITY.indexOf(BackTarget.SEARCH_SELECTION)
        val search = BACK_PRIORITY.indexOf(BackTarget.SEARCH)

        assertTrue("三个目标都必须登记在表里", listOf(page, overlay, selection, search).all { it >= 0 })
        assertTrue("详情页必须先于搜索页的任何状态", page < overlay)
        assertTrue("待下载面板要先于多选态", overlay < selection)
        assertTrue("多选态要先于搜索态本身", selection < search)
    }

    @Test
    fun `selection mode consumes back before collapsing search`() {
        val flags = OverlayVisibilityFlags(searchExpanded = true, searchSelection = true)
        assertEquals(BackTarget.SEARCH_SELECTION, handleBack(flags))
    }

    @Test
    fun `pending download overlay consumes back before selection mode`() {
        val flags = OverlayVisibilityFlags(
            searchSelectionOverlay = true,
            searchSelection = true,
        )
        assertEquals(BackTarget.SEARCH_SELECTION_OVERLAY, handleBack(flags))
    }

    @Test
    fun `open detail page still wins over search selection`() {
        val flags = OverlayVisibilityFlags(
            page = true,
            searchSelectionOverlay = true,
            searchSelection = true,
            searchExpanded = true,
        )
        assertEquals(BackTarget.PAGE, handleBack(flags))
    }

    @Test
    fun `nothing visible hands back to system`() {
        assertEquals(BackTarget.NONE, handleBack(OverlayVisibilityFlags()))
    }

    /** 同一目标只能在表里出现一次，否则 `handleBack` 的语义取决于重复项的位置。 */
    @Test
    fun `priority table has no duplicate target`() {
        assertEquals(BACK_PRIORITY.size, BACK_PRIORITY.distinct().size)
    }
}
