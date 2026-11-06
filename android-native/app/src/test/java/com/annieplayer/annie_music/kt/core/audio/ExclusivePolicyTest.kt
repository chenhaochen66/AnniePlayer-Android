package com.annieplayer.annie_music.kt.core.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `decideExclusive` 的判定表。
 *
 * 这几条断言的价值不在于"跑通了"，而在于**把优先级钉住**：通路许否 > 运行时黑名单 > 探测结论。
 * 这个顺序弄反就会出两种真实事故：
 * - 探测结论压过"通路没选独占" → 用户明明选了系统通路，引擎还去试独占；
 * - 黑名单压过通路判断 → 无所谓，但反过来"通路判断压过黑名单"会让播过一首的率
 *   把黑名单刷一遍（所以 `AaudioSink` 里事前拒绝时**刻意不写黑名单**）。
 */
class ExclusivePolicyTest {

    @Test
    fun `通路不走独占时一律不试`() {
        val v = decideExclusive(wanted = false, runtimeFailed = false, probedExclusive = true)
        assertEquals(ExclusiveVerdict.Denied(ExclusiveDenial.PathNotExclusive), v)
    }

    @Test
    fun `运行时失败过就不再试`() {
        val v = decideExclusive(wanted = true, runtimeFailed = true, probedExclusive = true)
        assertEquals(ExclusiveVerdict.Denied(ExclusiveDenial.Blacklisted), v)
    }

    @Test
    fun `探测判定拿不到就不必试`() {
        val v = decideExclusive(wanted = true, runtimeFailed = false, probedExclusive = false)
        assertEquals(ExclusiveVerdict.Denied(ExclusiveDenial.ProbedShared), v)
    }

    @Test
    fun `探测说能独占就试`() {
        val v = decideExclusive(wanted = true, runtimeFailed = false, probedExclusive = true)
        assertEquals(ExclusiveVerdict.Possible, v)
    }

    /**
     * **未探到 ≠ 不可能**。
     *
     * 探测是异步的、且绑定当前路由：启动早期、刚插拔设备、刚 `invalidate()` 之后矩阵都是空的。
     * 把 `null` 当 `false` 会让"插上耳机的第一首永远赶不上独占" —— 这正是当年踩过的坑。
     */
    @Test
    fun `还没探到就允许试`() {
        val v = decideExclusive(wanted = true, runtimeFailed = false, probedExclusive = null)
        assertEquals(ExclusiveVerdict.Possible, v)
    }

    @Test
    fun `优先级 通路判断 高于 黑名单 高于 探测`() {
        assertEquals(
            ExclusiveVerdict.Denied(ExclusiveDenial.PathNotExclusive),
            decideExclusive(wanted = false, runtimeFailed = true, probedExclusive = false),
        )
        assertEquals(
            ExclusiveVerdict.Denied(ExclusiveDenial.Blacklisted),
            decideExclusive(wanted = true, runtimeFailed = true, probedExclusive = false),
        )
    }

    /** 每个原因都要有可读文案（面板/日志直接用它，空字符串会让日志出现 `AAudio 独占未成立： · 48 kHz`） */
    @Test
    fun `每个拒绝原因都有文案`() {
        for (denial in ExclusiveDenial.entries) {
            assertEquals(
                "原因 ${denial.name} 缺文案",
                true,
                denial.label.isNotBlank(),
            )
        }
    }
}
