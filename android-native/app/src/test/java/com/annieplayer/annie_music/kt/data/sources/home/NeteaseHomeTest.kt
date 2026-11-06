package com.annieplayer.annie_music.kt.data.sources.home

import com.annieplayer.annie_music.kt.data.model.HomeTarget
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网易云主页里**纯映射**那一段（`neteasePlaylistCards`）。
 *
 * 为什么单独测：推荐行（`personalized/playlist`）和分类行（`playlist/list?cat=`）用的是
 * **两套字段名**——前者 `picUrl`、后者 `coverImgUrl`。这俩只要一个没认到，分类行的封面
 * 就会整片空着，而它不会报错、只是"看起来没图"，只有断言能挡住。
 */
class NeteaseHomeTest {

    private fun item(
        id: Any? = 1L,
        name: Any? = "歌单",
        picUrl: String? = null,
        coverImgUrl: String? = null,
        playCount: Any? = 0L,
        trackCount: Any? = 0L,
    ): Map<String, Any?> = buildMap {
        if (id != null) put("id", id)
        if (name != null) put("name", name)
        if (picUrl != null) put("picUrl", picUrl)
        if (coverImgUrl != null) put("coverImgUrl", coverImgUrl)
        put("playCount", playCount)
        put("trackCount", trackCount)
    }

    @Test
    fun category_playlist_reads_cover_img_url_and_upsizes_it() {
        // 分类接口给的字段是 coverImgUrl（个性推荐那边是 picUrl）
        val cards = neteasePlaylistCards(
            listOf(item(id = 13007986643L, name = "重返2016", coverImgUrl = "http://p6.music.126.net/a.png")),
        )
        assertEquals(1, cards.size)
        assertEquals("http://p6.music.126.net/a.png?param=500y500", cards[0].cover)
    }

    @Test
    fun personalized_playlist_still_reads_pic_url() {
        val cards = neteasePlaylistCards(
            listOf(item(id = 2L, name = "推荐", picUrl = "http://p1.music.126.net/b.jpg")),
        )
        assertEquals("http://p1.music.126.net/b.jpg?param=500y500", cards[0].cover)
    }

    @Test
    fun card_carries_play_count_badge_and_track_count_subtitle() {
        val cards = neteasePlaylistCards(
            listOf(item(id = 3L, name = "重返2016", coverImgUrl = "http://x/c.png", playCount = 406079L, trackCount = 98L)),
        )
        assertEquals("40.6万播放 · 98 首", cards[0].subtitle)
        assertEquals("40.6万", cards[0].badge)
        val target = cards[0].target as HomeTarget.Playlist
        assertEquals(MusicPlatform.netease, target.platform)
        assertEquals("3", target.id)
        assertEquals("netease:playlist:3", cards[0].key)
    }

    @Test
    fun entries_without_id_or_name_are_dropped() {
        val cards = neteasePlaylistCards(
            listOf(
                item(id = null, name = "没 id"),
                item(id = 4L, name = null),
                item(id = 5L, name = "正常"),
                "不是对象",
            ),
        )
        assertEquals(listOf("5"), cards.map { (it.target as HomeTarget.Playlist).id })
        assertTrue(neteasePlaylistCards(null).isEmpty())
        assertTrue(neteasePlaylistCards(emptyList<Any?>()).isEmpty())
    }

    @Test
    fun category_tag_list_is_not_empty() {
        // 空列表会让分类行整片消失（上层按 tag 逐行取数）
        assertTrue(neteasePlaylistCats.isNotEmpty())
        assertEquals(neteasePlaylistCats.distinct(), neteasePlaylistCats)
    }
}
