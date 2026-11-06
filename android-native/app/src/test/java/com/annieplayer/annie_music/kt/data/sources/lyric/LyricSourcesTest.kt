package com.annieplayer.annie_music.kt.data.sources.lyric

import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 歌词来源注册表（纯 JVM，不联网）。
 *
 * 守的是重构前最容易出事的那条：**不许有兜底平台**。
 * 以前按 `id` 前缀分派、最后 `else` 落到酷我 —— 加平台忘了写分支也会「碰巧」出歌词，
 * 这种 bug 只能靠肉眼比对歌词内容才能发现。现在漏注册就是 null → 占位，一眼可见。
 */
class LyricSourcesTest {

    private fun song(platform: MusicPlatform) = Song(
        id = "x:1",
        title = "T",
        artist = "A",
        cover = "",
        duration = 0,
        platform = platform,
    )

    @Test
    fun every_platform_with_a_lyric_source_is_registered() {
        val expected = listOf(
            MusicPlatform.netease,
            MusicPlatform.qq,
            MusicPlatform.kugou,
            MusicPlatform.kuwo,
            MusicPlatform.qobuz,
            MusicPlatform.apple,
        )
        for (platform in expected) {
            val source = LyricSources.of(song(platform))
            assertNotNull("$platform 没注册歌词来源", source)
            assertEquals("$platform 注册到了别的来源", platform, source!!.platform)
        }
    }

    @Test
    fun kkbox_and_local_have_no_source_on_purpose() {
        // KKBOX 要会话 sid 且不进通用缓存、local 直接读文件 —— 两者都不属于「拉取」，
        // 由门面单独处理。注册进来反而会破坏这两条语义。
        assertNull(LyricSources.of(song(MusicPlatform.kkbox)))
        assertNull(LyricSources.of(song(MusicPlatform.local)))
    }

    @Test
    fun no_source_claims_more_than_one_platform() {
        // 一对一匹配：同一个 Song 只会命中一个来源，所以注册顺序不影响结果
        val claimed = MusicPlatform.values().count { LyricSources.of(song(it)) != null }
        assertEquals("注册来源数与预期不符（多了 = 有人加兜底，少了 = 漏注册）", 6, claimed)
    }
}
