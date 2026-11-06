package com.annieplayer.annie_music.kt.features.player

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放页竖屏几何的两条硬约束：
 * 1. 正常机型（363×800dp 这一档）分页区与封面与旧公式逐位相同 —— 「位置不变」的钉子。
 * 2. 任意尺寸、任意字号下：封面不越出本页、胶囊带不与封面重叠、分页区 + 固定块不超出屏高
 *    （超出时必须走整页滚动）。
 */
class PlayerPageGeometryTest {

    private val eps = 0.05f

    /** fontScale = 1 的估值；[bars] 模拟系统栏内边距 */
    private fun fixedDefault(bars: Dp = 18.dp) = PlayerFixedHeights.estimate(1f).let {
        it.copy(top = it.top + bars)
    }

    /** 字号放大后的实测值：文本行按 1.6 倍，图标行不变 */
    private fun fixedLargeText(bars: Dp = 18.dp) = PlayerFixedHeights(
        top = 56.dp + bars,
        title = 68.dp,
        artist = 32.dp,
        progress = 60.dp,
        controls = 48.dp,
        volume = 28.dp,
    )

    private fun assertNear(msg: String, expected: Dp, actual: Dp) =
        assertEquals(msg, expected.value, actual.value, eps)

    private fun assertInvariants(w: Dp, h: Dp, fixed: PlayerFixedHeights) {
        val g = playerPageGeometry(w, h, fixed)
        val tag = "${w.value}x${h.value} tier=${g.tier} compact=${g.compact}"
        assertTrue("$tag cover ≥ 0", g.coverSize.value >= 0f)
        assertTrue("$tag pager ≥ 0", g.pagerHeight.value >= 0f)
        assertTrue("$tag cover ≤ pager", g.coverSize.value <= g.pagerHeight.value + eps)
        val stack = if (g.compact) fixed.compact else fixed.full
        assertTrue(
            "$tag 分页区 + 固定块不超出屏高",
            g.scroll || g.pagerHeight.value + stack.value <= h.value + eps,
        )
        val band = when (g.tier) {
            PlayerPageTier.full -> PlayerChipSlotHeight + g.chipBottomInset + PlayerChipGap
            PlayerPageTier.tight -> PlayerChipSlotHeight + PlayerChipTightGap
            PlayerPageTier.noChip -> 0.dp
        }
        if (band.value > 0f) {
            assertTrue(
                "$tag 胶囊带不与封面重叠",
                g.coverSize.value + 2 * band.value <= g.pagerHeight.value + eps,
            )
        }
    }

    @Test
    fun `正常机型与旧公式逐位相同`() {
        val w = 363.43f.dp
        val h = 800.dp
        val g = playerPageGeometry(w, h, fixedDefault())
        assertEquals(PlayerPageTier.full, g.tier)
        assertFalse(g.compact)
        assertFalse(g.scroll)
        // 同一个表达式、同一批 Float 运算，所以按位相等
        assertEquals(w * 0.78f, g.coverSize)
        assertEquals(h * 0.48f, g.pagerHeight)
        assertEquals(PlayerChipBottomInset, g.chipBottomInset)
    }

    @Test
    fun `小屏收小封面但不重叠`() {
        val g = playerPageGeometry(375.dp, 667.dp, fixedDefault())
        assertEquals(PlayerPageTier.full, g.tier)
        assertFalse(g.compact)
        assertNear("pager", 667.dp * 0.48f, g.pagerHeight)
        assertNear("cover", 320.16f.dp - 96.dp, g.coverSize)
        assertTrue(g.coverSize.value < 375f * 0.78f)
    }

    @Test
    fun `十六比九手机收小封面`() {
        val g = playerPageGeometry(411.dp, 731.dp, fixedDefault())
        assertEquals(PlayerPageTier.full, g.tier)
        assertNear("cover", 350.88f.dp - 96.dp, g.coverSize)
    }

    @Test
    fun `折叠屏与平板不被封面越界`() {
        val fold = playerPageGeometry(673.dp, 897.dp, fixedDefault())
        assertEquals(PlayerPageTier.full, fold.tier)
        assertNear("fold cover", 430.56f.dp - 96.dp, fold.coverSize)

        val pad = playerPageGeometry(800.dp, 1280.dp, fixedDefault())
        assertEquals(PlayerPageTier.full, pad.tier)
        assertNear("pad cover", 614.4f.dp - 96.dp, pad.coverSize)
    }

    @Test
    fun `分屏走紧凑档且不需要滚动`() {
        val fixed = fixedDefault()
        val g = playerPageGeometry(363.dp, 343.dp, fixed)
        assertTrue("紧凑档", g.compact)
        assertFalse("紧凑档刚好排下", g.scroll)
        assertEquals(PlayerPageTier.noChip, g.tier)
        // 分页区正好吃掉紧凑后剩下的高度
        assertNear("pager", 343.dp - fixed.compact, g.pagerHeight)
        assertNear("cover", 343.dp - fixed.compact, g.coverSize)
    }

    @Test
    fun `极小窗才需要滚动`() {
        val g = playerPageGeometry(363.dp, 257.dp, fixedDefault())
        assertTrue(g.compact)
        assertTrue("连紧凑档都排不下", g.scroll)
        assertNear("pager", PlayerPagerFloor, g.pagerHeight)
    }

    @Test
    fun `字号放大后按实测固定块让出高度`() {
        // 同一台 363×700dp 的机器，字号放大后固定块从 324 涨到 374
        val h = 700.dp
        val small = fixedDefault()
        val normal = playerPageGeometry(363.dp, h, small)
        assertNear("字号正常时 48% 屏高仍装得下", h * 0.48f, normal.pagerHeight)

        val large = fixedLargeText()
        val big = playerPageGeometry(363.dp, h, large)
        assertFalse(big.scroll)
        // 旧常量(324) 会给出 48% 屏高，实体溢出 10dp；实测后必须压到剩余高度
        assertNear("字号放大后压到剩余高度", h - large.full, big.pagerHeight)
        assertTrue(big.pagerHeight.value < h.value * 0.48f)
        assertTrue(big.pagerHeight.value + large.full.value <= h.value + eps)
    }

    @Test
    fun `尺寸网格全都不重叠`() {
        val widths = listOf(320f, 360f, 363.43f, 375f, 393f, 411f, 480f, 600f, 673f, 800f).map { it.dp }
        val heights = listOf(
            257f, 300f, 343f, 400f, 480f, 540f, 600f, 640f, 667f, 700f, 731f, 800f, 841f, 900f, 1280f,
        ).map { it.dp }
        val fixeds = listOf(fixedDefault(), fixedDefault(bars = 40.dp), fixedLargeText())
        widths.forEach { w ->
            heights.forEach { h ->
                fixeds.forEach { fixed -> assertInvariants(w, h, fixed) }
            }
        }
    }
}
