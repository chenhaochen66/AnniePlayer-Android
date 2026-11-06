package com.annieplayer.annie_music.kt.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ArtistSplit] 的单测（纯 JVM）。
 *
 * 规则刻意收得很窄：**只有「空格 + 斜杠」和「顿号」算分隔**。
 * 因为歌手名本身就带 `&` / `,` / `+` / `feat.` 这些符号
 * （`Simon & Garfunkel`、`Earth, Wind & Fire`、`Florence + the Machine`），
 * 一拆就把一个歌手劈成两个 —— 误拆比不拆更难发现，所以「不拆」这一侧也全部写成断言。
 */
class ArtistSplitTest {

    private fun assertSplit(raw: String, expected: List<String>) {
        assertEquals("split(\"$raw\")", expected, ArtistSplit.split(raw))
    }

    private fun assertNormalized(raw: String, expected: String) {
        assertEquals("normalize(\"$raw\")", expected, ArtistSplit.normalize(raw))
    }

    // ── 认的分隔符：` / ` 与 `、` ──

    @Test
    fun `两侧带空格的斜杠`() = assertSplit("A / B", listOf("A", "B"))

    @Test
    fun `左侧带空格的斜杠`() = assertSplit("A /B", listOf("A", "B"))

    @Test
    fun `右侧带空格的斜杠`() = assertSplit("A/ B", listOf("A", "B"))

    @Test
    fun `全角斜杠带空格`() = assertSplit("A ／ B", listOf("A", "B"))

    @Test
    fun `顿号`() = assertSplit("周杰伦、费玉清", listOf("周杰伦", "费玉清"))

    @Test
    fun `顿号带空格`() = assertSplit("周杰伦 、 费玉清", listOf("周杰伦", "费玉清"))

    @Test
    fun `三个歌手`() = assertSplit("A、B、C", listOf("A", "B", "C"))

    @Test
    fun `顿号与斜杠混用`() =
        assertSplit("周杰伦、费玉清 / 五月天", listOf("周杰伦", "费玉清", "五月天"))

    // ── 不认的分隔符（都会误拆真实歌手名） ──

    @Test
    fun `和号不拆`() = assertSplit("A & B", listOf("A & B"))

    @Test
    fun `Simon and Garfunkel`() = assertSplit("Simon & Garfunkel", listOf("Simon & Garfunkel"))

    @Test
    fun `Earth Wind and Fire 的逗号与和号都不拆`() =
        assertSplit("Earth, Wind & Fire", listOf("Earth, Wind & Fire"))

    @Test
    fun `Tyler The Creator 的逗号不拆`() =
        assertSplit("Tyler, The Creator", listOf("Tyler, The Creator"))

    @Test
    fun `加号不拆`() = assertSplit("Florence + the Machine", listOf("Florence + the Machine"))

    @Test
    fun `分号不拆`() = assertSplit("A; B", listOf("A; B"))

    @Test
    fun `竖线不拆`() = assertSplit("A | B", listOf("A | B"))

    @Test
    fun `逗号不拆`() = assertSplit("A, B", listOf("A, B"))

    @Test
    fun `feat 不拆`() = assertSplit("A feat. B", listOf("A feat. B"))

    @Test
    fun `ft 不拆`() = assertSplit("A ft. B", listOf("A ft. B"))

    @Test
    fun `with 不拆`() = assertSplit("A with B", listOf("A with B"))

    @Test
    fun `vs 不拆`() = assertSplit("A vs. B", listOf("A vs. B"))

    @Test
    fun `乘号不拆`() = assertSplit("周杰伦 × 五月天", listOf("周杰伦 × 五月天"))

    @Test
    fun `字母 x 不拆`() = assertSplit("Malcolm X", listOf("Malcolm X"))

    @Test
    fun `间隔号是译名`() = assertSplit("迈克尔·杰克逊", listOf("迈克尔·杰克逊"))

    // ── 斜杠：无空格时属于名字本身 ──

    @Test
    fun `AC DC 不拆`() = assertSplit("AC/DC", listOf("AC/DC"))

    @Test
    fun `无空格斜杠不拆`() = assertSplit("A/B", listOf("A/B"))

    @Test
    fun `全角无空格斜杠不拆`() = assertSplit("他／她", listOf("他／她"))

    // ── 清洗 ──

    @Test
    fun `多余分隔符不产出空项`() = assertSplit("A 、 、 B", listOf("A", "B"))

    @Test
    fun `斜杠结尾不产出空项`() = assertSplit("A / ", listOf("A"))

    @Test
    fun `首尾空白`() = assertSplit("  A /  B  ", listOf("A", "B"))

    @Test
    fun `忽略大小写去重`() = assertSplit("A / a", listOf("A"))

    @Test
    fun `空串`() = assertSplit("", emptyList())

    @Test
    fun `全空白`() = assertSplit("   ", emptyList())

    @Test
    fun `单歌手原样返回`() = assertSplit("周杰伦", listOf("周杰伦"))

    // ── 展示规范化：统一成 `A / B` ──

    @Test
    fun `顿号规范化成斜杠`() = assertNormalized("周杰伦、费玉清", "周杰伦 / 费玉清")

    @Test
    fun `已经是斜杠格式就原样`() = assertNormalized("A / B", "A / B")

    @Test
    fun `和号原样保留`() = assertNormalized("Simon & Garfunkel", "Simon & Garfunkel")

    @Test
    fun `feat 原样保留`() = assertNormalized("A feat. B", "A feat. B")

    @Test
    fun `单歌手原样`() = assertNormalized("周杰伦", "周杰伦")

    @Test
    fun `拼接用统一分隔符`() = assertEquals("A / B / C", ArtistSplit.join(listOf("A", "B", "C")))
}
