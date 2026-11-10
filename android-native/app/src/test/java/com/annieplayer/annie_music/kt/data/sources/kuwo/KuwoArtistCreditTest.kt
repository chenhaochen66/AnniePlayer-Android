package com.annieplayer.annie_music.kt.data.sources.kuwo

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 酷我「歌手名 + 歌手 id」配对（纯 JVM，不联网）。
 *
 * 夹具全部来自 2026-11 的真实响应，守的核心事实是：
 * **名字里的 `&` `,` `、` `/` 既可能是分隔符也可能是名字本身，判据只能是 `allartistid` 的个数。**
 *
 * 实测 551 条样本的分布（pl.svc 100 首 + 6 组搜索各 100 条）：
 * - `ids=1 但名字含分隔符` 186 条（34%）—— 旧实现把它们全拆成了假歌手，且丢掉唯一的 id
 * - `ids>1 且段数 == id 数` 177 条 —— 旧实现正确
 * - `ids>1 但段数 != id 数` 32 条（6%）—— 字符串层面无解，交给 `resolveKuwoArtistNames`
 */
class KuwoArtistCreditTest {

    /** 伪 JSON 里 `&` 被转义成 N 个反斜杠 + `u0026`；N 随解析层数变化（原串 4 个、json 解码后 2 个） */
    private fun esc(backslashes: Int) = "\\".repeat(backslashes) + "u0026"

    // ── 一个 id ⇒ 整串就是一个歌手名 ────────────────────────────────────────────

    @Test
    fun `single id keeps ampersand band name whole`() {
        val (names, ids) = kuwoArtistCredit("Years&nbsp;${esc(4)}&nbsp;Years", "185720", "185720")
        assertEquals(listOf("Years & Years"), names)
        assertEquals(listOf("185720"), ids)
    }

    @Test
    fun `single id keeps comma name whole`() {
        // Tyler, The Creator 是一个人，按 `,` 拆会变成「Tyler」+「The Creator」
        val (names, ids) = kuwoArtistCredit("Tyler,&nbsp;The&nbsp;Creator", "21751")
        assertEquals(listOf("Tyler, The Creator"), names)
        assertEquals(listOf("21751"), ids)
    }

    @Test
    fun `single id keeps slash name whole`() {
        val (names, ids) = kuwoArtistCredit("AC/DC", "9418")
        assertEquals(listOf("AC/DC"), names)
        assertEquals(listOf("9418"), ids)
    }

    @Test
    fun `single id keeps band with china blue whole`() {
        // pl.svc 只给一个 artistid，名字里却有一个 `&`
        val (names, ids) = kuwoArtistCredit("伍佰 & China Blue", "210")
        assertEquals(listOf("伍佰 & China Blue"), names)
        assertEquals(listOf("210"), ids)
    }

    @Test
    fun `single id keeps parenthesised former name whole`() {
        val (names, _) = kuwoArtistCredit("Olly Alexander (Years & Years)", "19920219")
        assertEquals(listOf("Olly Alexander (Years & Years)"), names)
    }

    // ── 多个 id ⇒ 按 id 个数拆 ────────────────────────────────────────────────

    @Test
    fun `two ids split escaped collaboration`() {
        val (names, ids) = kuwoArtistCredit(
            "Shawn&nbsp;Mendes${esc(2)}Camila&nbsp;Cabello",
            "194215${esc(2)}265732",
        )
        assertEquals(listOf("Shawn Mendes", "Camila Cabello"), names)
        assertEquals(listOf("194215", "265732"), ids)
    }

    @Test
    fun `two ids but three segments keeps whole name and both ids`() {
        // 专辑详情（真 JSON）里 `&` 不转义，第二段自己就带 `&` —— 字符串层面无解
        val (names, ids) = kuwoArtistCredit("Tove Lo & Years & Years", "125386&185720")
        assertEquals(listOf("Tove Lo & Years & Years"), names)
        assertEquals(listOf("125386", "185720"), ids)
    }

    @Test
    fun `two ids split clean pair`() {
        val (names, ids) = kuwoArtistCredit("汪苏泷&林希儿", "5357&3661")
        assertEquals(listOf("汪苏泷", "林希儿"), names)
        assertEquals(listOf("5357", "3661"), ids)
    }

    // ── 拆不准 ⇒ 整串当一个名字，id 一个都不丢 ─────────────────────────────────

    @Test
    fun `mismatch keeps whole name but all ids`() {
        val (names, ids) = kuwoArtistCredit("Pet Shop Boys&Years & Years", "25320&185720")
        assertEquals(listOf("Pet Shop Boys&Years & Years"), names)
        assertEquals(listOf("25320", "185720"), ids)
    }

    @Test
    fun `mismatch with five ids keeps all ids`() {
        val (names, ids) = kuwoArtistCredit(
            "Tyler, The Creator&Domo Genesis&Frank Ocean&Hodgy Beats&Mike G",
            "21751&33269&31126&33274&31473",
        )
        assertEquals(1, names.size)
        assertEquals(5, ids.size)
    }

    // ── 边界 ─────────────────────────────────────────────────────────────────

    @Test
    fun `no ids at all keeps whole name`() {
        val (names, ids) = kuwoArtistCredit("光武健太&初音ミク", "")
        assertEquals(listOf("光武健太&初音ミク"), names)
        assertEquals(emptyList<String>(), ids)
    }

    @Test
    fun `first artist id used when allartistid missing`() {
        // pl.svc 只有 artistid
        val (names, ids) = kuwoArtistCredit("草蜢", "", "678")
        assertEquals(listOf("草蜢"), names)
        assertEquals(listOf("678"), ids)
    }

    @Test
    fun `zero id is dropped`() {
        val (_, ids) = kuwoArtistCredit("未知歌手", "0")
        assertEquals(emptyList<String>(), ids)
    }

    @Test
    fun `blank name falls back to placeholder`() {
        val (names, _) = kuwoArtistCredit("", "185720")
        assertEquals(listOf("未知歌手"), names)
    }

    @Test
    fun `stray backslashes are stripped from name`() {
        val (names, _) = kuwoArtistCredit("Years ${"\\".repeat(2)}& Years", "185720")
        assertEquals(listOf("Years & Years"), names)
    }
}
