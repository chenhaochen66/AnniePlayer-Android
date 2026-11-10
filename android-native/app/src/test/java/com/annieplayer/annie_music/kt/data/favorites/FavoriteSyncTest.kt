package com.annieplayer.annie_music.kt.data.favorites

import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 收藏元信息的「校准」判断（打开详情页时才会走到）。
 *
 * 三条规则各自都对应一个真实故障：
 * - 未收藏也写 → 浏览别人的专辑会把它加进收藏库；
 * - 值没变也写 → 每次打开详情页一次写盘（列表滚动时更明显）；
 * - 变了不写 → 9 首涨到 11 首，收藏库永远显示 9 首。
 */
class FavoriteSyncTest {

    private fun album(
        trackCount: Int = 9,
        releaseDate: String = "2011-11-11",
        artistId: String = "",
    ) = FavAlbum(
        key = "netease:123",
        id = "123",
        name = "依然范特西",
        artist = "周杰伦",
        artistId = artistId,
        cover = "https://c/1.jpg",
        platform = MusicPlatform.netease,
        trackCount = trackCount,
        releaseDate = releaseDate,
        paths = listOf("/a.flac"),
        addedAt = 1000L,
    )

    private fun playlist(creator: String = "小明", trackCount: Int = 12) = FavPlaylist(
        key = "netease:456",
        id = "456",
        name = "跑步",
        cover = "https://c/2.jpg",
        platform = MusicPlatform.netease,
        creator = creator,
        trackCount = trackCount,
        addedAt = 2000L,
    )

    private fun artist(picture: String = "https://a/1.jpg") = FavArtist(
        key = "netease:789",
        id = "789",
        name = "周杰伦",
        picture = picture,
        platform = MusicPlatform.netease,
        addedAt = 3000L,
    )

    // ── 专辑 ──

    @Test
    fun `未收藏不写`() = assertNull(
        albumMetaChange(
            existing = null,
            name = "新名",
            artist = "新歌手",
            cover = "https://c/9.jpg",
            trackCount = 11,
            releaseDate = "2023-01-01",
        ),
    )

    @Test
    fun `值没变不写`() = assertNull(
        albumMetaChange(
            existing = album(),
            name = "依然范特西",
            artist = "周杰伦",
            cover = "https://c/1.jpg",
            trackCount = 9,
            releaseDate = "2011-11-11",
        ),
    )

    @Test
    fun `曲目数变了才写`() {
        val updated = albumMetaChange(
            existing = album(),
            name = "依然范特西",
            artist = "周杰伦",
            cover = "https://c/1.jpg",
            trackCount = 11,
            releaseDate = "2011-11-11",
        )
        assertEquals(11, updated?.trackCount)
        assertEquals("2011-11-11", updated?.releaseDate)
    }

    @Test
    fun `发行日期变了才写`() {
        val updated = albumMetaChange(
            existing = album(),
            name = "依然范特西",
            artist = "周杰伦",
            cover = "https://c/1.jpg",
            trackCount = 9,
            releaseDate = "2011-11-12",
        )
        assertEquals("2011-11-12", updated?.releaseDate)
        assertEquals(9, updated?.trackCount)
    }

    @Test
    fun `专辑名字封面歌手变了也要跟上`() {
        // 从主页 / 搜索进详情时，`data` 是平台当前值 —— 收藏记录应该被它对齐
        val updated = albumMetaChange(
            existing = album(),
            name = "依然范特西（再版）",
            artist = "周杰伦 / 费玉清",
            cover = "https://c/new.jpg",
            trackCount = 9,
            releaseDate = "2011-11-11",
        )!!
        assertEquals("依然范特西（再版）", updated.name)
        assertEquals("周杰伦 / 费玉清", updated.artist)
        assertEquals("https://c/new.jpg", updated.cover)
    }

    @Test
    fun `空串与 0 是没拿到 不能覆盖已有值`() {
        // 详情页在某些情况下拿不到名字 / 封面（接口没给），传空串进来必须当作「不动」
        val updated = albumMetaChange(
            existing = album(),
            name = "",
            artist = "",
            cover = "",
            trackCount = 0,
            releaseDate = "",
        )
        assertNull(updated)
    }

    @Test
    fun `歌手 id 拿到了才写 空串不覆盖`() {
        // 老记录没有歌手 id（v4 之前没存），打开详情页拿到后就补上
        val updated = albumMetaChange(
            existing = album(),
            name = "依然范特西",
            artist = "周杰伦",
            cover = "https://c/1.jpg",
            trackCount = 9,
            releaseDate = "2011-11-11",
            artistId = "6452",
        )
        assertEquals("6452", updated?.artistId)
        // 已有 id 时传空串 = 这次没拿到，保留原值
        assertNull(
            albumMetaChange(
                existing = album(artistId = "6452"),
                name = "依然范特西",
                artist = "周杰伦",
                cover = "https://c/1.jpg",
                trackCount = 9,
                releaseDate = "2011-11-11",
                artistId = "",
            ),
        )
    }

    @Test
    fun `校准不动本地路径与收藏时间`() {
        val updated = albumMetaChange(
            existing = album(),
            name = "新名",
            artist = "新歌手",
            cover = "https://c/9.jpg",
            trackCount = 11,
            releaseDate = "2023-01-01",
        )!!
        assertEquals(listOf("/a.flac"), updated.paths)
        assertEquals(1000L, updated.addedAt)
        assertEquals("netease:123", updated.key)
    }

    // ── 歌手 ──

    @Test
    fun `歌手未收藏不写`() = assertNull(
        artistMetaChange(existing = null, name = "周杰伦", picture = "https://a/1.jpg"),
    )

    @Test
    fun `歌手头像变了才写`() {
        val updated = artistMetaChange(existing = artist(), name = "周杰伦", picture = "https://a/2.jpg")
        assertEquals("https://a/2.jpg", updated?.picture)
        assertEquals("周杰伦", updated?.name)
    }

    @Test
    fun `歌手头像没变不写`() = assertNull(
        artistMetaChange(existing = artist(), name = "周杰伦", picture = "https://a/1.jpg"),
    )

    @Test
    fun `歌手空头像不覆盖`() = assertNull(
        artistMetaChange(existing = artist(), name = "", picture = ""),
    )

    // ── 歌单 ──

    @Test
    fun `歌单未收藏不写`() = assertNull(
        playlistMetaChange(
            existing = null,
            name = "跑步",
            cover = "https://c/2.jpg",
            creator = "小明",
            trackCount = 12,
        ),
    )

    @Test
    fun `歌单值没变不写`() = assertNull(
        playlistMetaChange(
            existing = playlist(),
            name = "跑步",
            cover = "https://c/2.jpg",
            creator = "小明",
            trackCount = 12,
        ),
    )

    @Test
    fun `歌单曲目数变了才写`() {
        val updated = playlistMetaChange(
            existing = playlist(),
            name = "跑步",
            cover = "https://c/2.jpg",
            creator = "小明",
            trackCount = 30,
        )
        assertEquals(30, updated?.trackCount)
        assertEquals("小明", updated?.creator)
    }

    @Test
    fun `歌单名字封面创建者变了都要跟上`() {
        val updated = playlistMetaChange(
            existing = playlist(),
            name = "跑步 2024",
            cover = "https://c/new.jpg",
            creator = "歌手本人",
            trackCount = 12,
        )!!
        assertEquals("跑步 2024", updated.name)
        assertEquals("https://c/new.jpg", updated.cover)
        assertEquals("歌手本人", updated.creator)
        assertEquals(2000L, updated.addedAt)
    }
}
