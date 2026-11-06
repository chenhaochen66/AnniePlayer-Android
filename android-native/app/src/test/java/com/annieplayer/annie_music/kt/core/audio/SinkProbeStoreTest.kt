package com.annieplayer.annie_music.kt.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统通路建轨探测的**纯逻辑**（不碰 `AudioTrack`，`opener` 注入假实现）。
 *
 * 这里锁的是三件容易写错的事：
 * 1. 缓存键必须含 `deviceId` / `route` / `rate` / `depth` —— 少一项就把一台设备的能力
 *    套到另一台上；
 * 2. `highestUsable` 是"从高往低第一个通过"，不是"取声明最大值"；
 * 3. `knownHighest` **只读缓存**，绝不能顺手建轨（状态上报每首歌都走一次）。
 */
class SinkProbeStoreTest {

    private fun store(allow: Set<Int>): Pair<SinkProbeStore, MutableList<Int>> {
        val calls = mutableListOf<Int>()
        val s = SinkProbeStore { rate, _, _ ->
            calls += rate
            rate in allow
        }
        return s to calls
    }

    @Test
    fun `highestUsable 从高往低取第一个建得出的档`() {
        val (s, calls) = store(setOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000))
        val got = s.highestUsable(
            candidates = listOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000, 352_800, 384_000),
            deviceId = 7,
            route = AudioRoute.usb,
            depth = 16,
        )
        assertEquals(192_000, got)
        // 从最高的两档开始试，不通过才往下 —— 顺序不能反（反了就先建便宜档再看高的，白探）
        assertEquals(listOf(384_000, 352_800, 192_000), calls)
    }

    @Test
    fun `一档都建不出来返回 0 不猜`() {
        val (s, _) = store(emptySet())
        assertEquals(
            0,
            s.highestUsable(listOf(44_100, 352_800), deviceId = 7, route = AudioRoute.usb, depth = 16),
        )
    }

    @Test
    fun `缓存按设备分开 换设备要重探`() {
        val (s, calls) = store(setOf(192_000))
        assertTrue(s.canBuild(SinkProbeStore.Key(1, AudioRoute.usb, 192_000, 16)))
        assertTrue(s.canBuild(SinkProbeStore.Key(1, AudioRoute.usb, 192_000, 16)))
        assertEquals(1, calls.size)   // 同一键只探一次

        // 换了一台 DAC：同一档必须重新问，否则会沿用上一台的结论
        assertTrue(s.canBuild(SinkProbeStore.Key(2, AudioRoute.usb, 192_000, 16)))
        assertEquals(2, calls.size)
    }

    @Test
    fun `缓存按位深与路由分开`() {
        val (s, calls) = store(setOf(384_000))
        s.canBuild(SinkProbeStore.Key(1, AudioRoute.usb, 384_000, 16))
        s.canBuild(SinkProbeStore.Key(1, AudioRoute.usb, 384_000, 24))   // 位深不同 → 另一次
        s.canBuild(SinkProbeStore.Key(1, AudioRoute.wired, 384_000, 16)) // 路由不同 → 另一次
        assertEquals(3, calls.size)
    }

    @Test
    fun `knownHighest 只读缓存 不建轨`() {
        val (s, calls) = store(setOf(352_800))
        assertEquals(0, s.knownHighest(listOf(192_000, 352_800), 1, AudioRoute.usb, 16))
        assertEquals(0, calls.size)   // 关键：一次都没建

        // 真正要决定用哪一档时才探
        assertEquals(352_800, s.highestUsable(listOf(192_000, 352_800), 1, AudioRoute.usb, 16))
        assertEquals(1, calls.size)

        // 探过之后 knownHighest 就能读到，而且仍然不建轨
        assertEquals(352_800, s.knownHighest(listOf(192_000, 352_800), 1, AudioRoute.usb, 16))
        assertEquals(1, calls.size)
    }

    @Test
    fun `invalidate 清空后重新探`() {
        val (s, calls) = store(setOf(192_000))
        s.canBuild(SinkProbeStore.Key(1, AudioRoute.usb, 192_000, 16))
        assertEquals(1, s.size())

        s.invalidate()
        assertEquals(0, s.size())
        assertFalse(s.isProbed(SinkProbeStore.Key(1, AudioRoute.usb, 192_000, 16)))

        s.canBuild(SinkProbeStore.Key(1, AudioRoute.usb, 192_000, 16))
        assertEquals(2, calls.size)
    }
}
