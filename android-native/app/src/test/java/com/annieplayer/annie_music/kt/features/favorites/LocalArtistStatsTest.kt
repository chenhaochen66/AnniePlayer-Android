package com.annieplayer.annie_music.kt.features.favorites

import com.annieplayer.annie_music.kt.data.local.LocalTrackMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 本地艺人统计（收藏库「艺人」子页的副标题与封面）。
 *
 * 这里的每一个断言都对应一处曾经会算错的地方：多歌手拆不拆、专辑怎么去重、
 * 封面取哪一张 —— 算错不会崩，只会让列表显示「1 首」或干脆没有封面。
 */
class LocalArtistStatsTest {

    private fun track(
        path: String,
        artist: String,
        album: String = "专辑",
        albumArtist: String? = null,
        coverPath: String? = null,
    ) = LocalTrackMeta(
        path = path,
        artist = artist,
        album = album,
        albumArtist = albumArtist,
        coverPath = coverPath,
    )

    @Test
    fun `多歌手拆开各自计入`() {
        val stats = localArtistStats(
            listOf(track("/a.flac", artist = "周杰伦、费玉清", album = "依然范特西")),
        )
        // `、` 是分隔符：两位艺人各 1 首，别把整串当成一个艺人名
        assertEquals(setOf("周杰伦", "费玉清"), stats.keys)
        assertEquals(1, stats.getValue("周杰伦").songs)
        assertEquals(1, stats.getValue("费玉清").songs)
    }

    @Test
    fun `同一专辑的多首只算一张`() {
        val stats = localArtistStats(
            listOf(
                track("/a1.flac", artist = "A", album = "专辑一"),
                track("/a2.flac", artist = "A", album = "专辑一"),
                track("/a3.flac", artist = "A", album = "专辑二"),
            ),
        )
        assertEquals(3, stats.getValue("A").songs)
        assertEquals(2, stats.getValue("A").albums)
    }

    @Test
    fun `专辑艺术家不同就是两张专辑`() {
        // 键是 `专辑 · 专辑艺术家`（albumGroupKey），与曲库分组逐字一致
        val stats = localArtistStats(
            listOf(
                track("/a1.flac", artist = "A", album = "同名专辑", albumArtist = "A"),
                track("/a2.flac", artist = "A", album = "同名专辑", albumArtist = "Various Artists"),
            ),
        )
        assertEquals(2, stats.getValue("A").albums)
    }

    @Test
    fun `封面取第一张非空的`() {
        val stats = localArtistStats(
            listOf(
                track("/a1.flac", artist = "A", coverPath = null),
                track("/a2.flac", artist = "A", coverPath = "/covers/a2.jpg"),
                track("/a3.flac", artist = "A", coverPath = "/covers/a3.jpg"),
            ),
        )
        assertEquals("/covers/a2.jpg", stats.getValue("A").coverPath)
    }

    @Test
    fun `没有封面时是 null`() {
        val stats = localArtistStats(listOf(track("/a1.flac", artist = "A")))
        assertNull(stats.getValue("A").coverPath)
    }

    @Test
    fun `空歌手字段归到未知艺术家`() {
        // 与 `artistGroupKeys` 的回落一致；否则这类文件在收藏库里永远对不上
        val stats = localArtistStats(listOf(track("/a1.flac", artist = "   ")))
        assertEquals(setOf("未知艺术家"), stats.keys)
    }

    @Test
    fun `空曲库返回空表`() {
        assertEquals(emptyMap<String, ArtistStat>(), localArtistStats(emptyList()))
    }
}
