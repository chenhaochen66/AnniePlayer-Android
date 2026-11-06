package com.annieplayer.annie_music.kt.core.util

import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 批量下载的档位收敛（`downloadQualityFor`）。
 *
 * 为什么值得测：批量音质面板给的是**整批并集**，用户点了无损之后，这一批里「只有 320k」
 * 的歌必须被夹回 320k —— 否则插件会拿着一个它没有声明过的档位去查表，直接解析失败。
 * 这条链没有任何编译期保护，只能靠断言。
 */
class QualityUtilTest {

    private fun song(
        platform: MusicPlatform = MusicPlatform.kuwo,
        qualities: List<Quality> = emptyList(),
    ): Song = Song(
        id = "kuwo:1",
        title = "t",
        artist = "a",
        cover = "",
        album = null,
        albumId = null,
        duration = 0,
        platform = platform,
        qualities = qualities,
    )

    @Test
    fun batch_quality_is_clamped_to_the_song_real_tiers() {
        val onlyMp3 = song(qualities = listOf(Quality.k320k, Quality.k128k))
        // 面板上用户点的是无损，但这首歌只有 320k → 必须请求 320k
        assertEquals(Quality.k320k, downloadQualityFor(onlyMp3, Quality.flac))
        // 连 320k 都没有 → 128k
        assertEquals(
            Quality.k128k,
            downloadQualityFor(song(qualities = listOf(Quality.k128k)), Quality.flac),
        )
    }

    @Test
    fun batch_quality_keeps_the_request_when_the_song_has_it() {
        val lossless = song(qualities = listOf(Quality.flac, Quality.k320k, Quality.k128k))
        assertEquals(Quality.flac, downloadQualityFor(lossless, Quality.flac))
        // 要高有、要低也会夹下来（用户选 320k，歌有无损 → 就要 320k，不上调）
        assertEquals(Quality.k320k, downloadQualityFor(lossless, Quality.k320k))
    }

    @Test
    fun batch_quality_never_jumps_up_from_what_the_user_picked() {
        val neteaseHires = song(
            platform = MusicPlatform.netease,
            qualities = listOf(Quality.hires, Quality.flac, Quality.k320k, Quality.k128k),
        )
        // 用户选 128k 就下 128k，不会因为"这歌有 Hi-Res"而擅自升档
        assertEquals(Quality.k128k, downloadQualityFor(neteaseHires, Quality.k128k))
    }

    @Test
    fun batch_quality_leaves_unknown_qualities_alone() {
        // qualities 为空 = 来源没给档位信息。夹档会把它当"什么都没有"降到 128k（凭空退步），
        // 所以这里必须原样透传，交给取链层的响应判定去兜。
        assertEquals(Quality.flac, downloadQualityFor(song(qualities = emptyList()), Quality.flac))
    }

    @Test
    fun batch_quality_does_not_touch_apple_tiers() {
        // Apple 的档位体系（atmos / alac / aac256）不在四级台阶里，
        // 夹档会把它们判成"未知档位"再改写成别的值 —— 必须原样透传。
        val apple = song(
            platform = MusicPlatform.apple,
            qualities = listOf(Quality.atmos, Quality.hires, Quality.alac, Quality.aac256),
        )
        assertEquals(Quality.alac, downloadQualityFor(apple, Quality.alac))
        assertEquals(Quality.atmos, downloadQualityFor(apple, Quality.atmos))
        // 即使 qualities 为空也不动
        assertEquals(
            Quality.aac256,
            downloadQualityFor(song(platform = MusicPlatform.apple), Quality.aac256),
        )
    }

    // ── 播放侧（clampQualityToSong）：与下载同一套规则，但触发场景是「默认无损 + 单曲只有 320k」 ──

    @Test
    fun play_quality_is_clamped_to_the_song_real_tiers() {
        // 用户在设置里给酷我选了无损，但这首歌只有 320k：不能把 flac 发出去
        // （插件会把不存在的档位转给平台，平台不报错、只回低质/加密包）
        val onlyMp3 = song(qualities = listOf(Quality.k320k, Quality.k128k))
        assertEquals(Quality.k320k, clampQualityToSong(onlyMp3, Quality.flac))
        assertEquals(Quality.k320k, clampQualityToSong(onlyMp3, Quality.hires))
        // 只有 128k 的歌：hires / flac / 320k 全部夹到 128k
        val only128 = song(qualities = listOf(Quality.k128k))
        assertEquals(Quality.k128k, clampQualityToSong(only128, Quality.flac))
        assertEquals(Quality.k128k, clampQualityToSong(only128, Quality.k320k))
    }

    @Test
    fun play_quality_keeps_the_request_when_the_song_has_it() {
        val lossless = song(qualities = listOf(Quality.hires, Quality.flac, Quality.k320k))
        assertEquals(Quality.flac, clampQualityToSong(lossless, Quality.flac))
        assertEquals(Quality.hires, clampQualityToSong(lossless, Quality.hires))
        // 不上调：用户选 320k 就要 320k
        assertEquals(Quality.k320k, clampQualityToSong(lossless, Quality.k320k))
    }

    @Test
    fun play_quality_leaves_unknown_tiers_alone() {
        // 老队列项 / 来源没给档位 → 不能当成"只有 128k"去夹，否则无损歌被平白降档
        assertEquals(Quality.flac, clampQualityToSong(song(qualities = emptyList()), Quality.flac))
        assertEquals(Quality.hires, clampQualityToSong(song(qualities = emptyList()), Quality.hires))
    }

    @Test
    fun play_and_download_share_one_rule() {
        // 两条路必须同一套规则：谁改了一边而没改另一边，就会出现「播放 320k、下载 flac」这种分裂
        val mixed = song(qualities = listOf(Quality.flac, Quality.k320k))
        for (want in Quality.entries) {
            assertEquals(
                "want=$want",
                clampQualityToSong(mixed, want),
                downloadQualityFor(mixed, want),
            )
        }
    }

    // ── 「自动」档 = 这首歌自己的最高档（不再是固定 320k） ──

    @Test
    fun auto_quality_picks_the_songs_own_highest_tier() {
        val kuwo = MusicPlatform.kuwo
        // 有无损就拿无损
        assertEquals(
            Quality.flac,
            effectivePlayQuality(kuwo, listOf(Quality.flac, Quality.k320k, Quality.k128k), null),
        )
        // 有 Hi-Res 就拿 Hi-Res
        assertEquals(
            Quality.hires,
            effectivePlayQuality(
                MusicPlatform.netease,
                listOf(Quality.hires, Quality.flac, Quality.k320k),
                null,
            ),
        )
        // 只有 320k 的歌仍然 320k
        assertEquals(
            Quality.k320k,
            effectivePlayQuality(kuwo, listOf(Quality.k320k, Quality.k128k), null),
        )
    }

    @Test
    fun auto_quality_falls_back_to_320k_only_when_the_tiers_are_unknown() {
        assertEquals(Quality.k320k, effectivePlayQuality(MusicPlatform.qq, emptyList(), null))
        // 只认得 Apple 档位（不在四级台阶里）→ 等于未知 → 320k，不会瞎猜
        assertEquals(
            Quality.k320k,
            effectivePlayQuality(
                MusicPlatform.qq,
                listOf(Quality.atmos, Quality.alac),
                null,
            ),
        )
    }

    @Test
    fun configured_quality_still_wins_over_the_songs_own_tier() {
        val kuwo = MusicPlatform.kuwo
        // 用户明确选了 320k → 就算这歌有无损也只请求 320k（不擅自升档）
        assertEquals(
            Quality.k320k,
            effectivePlayQuality(kuwo, listOf(Quality.flac, Quality.k320k), Quality.k320k),
        )
        // 用户选了无损但这歌只有 320k → 夹到 320k
        assertEquals(
            Quality.k320k,
            effectivePlayQuality(kuwo, listOf(Quality.k320k, Quality.k128k), Quality.flac),
        )
    }

    @Test
    fun highest_quality_ignores_apple_only_tiers() {
        assertEquals(
            Quality.hires,
            highestQualityOf(listOf(Quality.atmos, Quality.alac, Quality.hires, Quality.aac256)),
        )
        assertNull(highestQualityOf(emptyList()))
        assertNull(highestQualityOf(listOf(Quality.atmos, Quality.alac)))
    }
}
