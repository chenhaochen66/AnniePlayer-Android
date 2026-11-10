package com.annieplayer.annie_music.kt.data

import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 歌单链接解析（纯函数）。
 *
 * 为什么值得测：解析错了**不会报错**，只会走错平台去拉一个不存在的歌单，用户看到的是
 * 「获取歌单失败，请检查链接是否正确」—— 和「链接本身贴错」完全分不出来。
 * 这里的用例都是各平台真实分享出来的形态。
 */
class PlaylistUrlParseTest {

    private fun platformOf(text: String) = PlaylistService.parsePlaylistUrl(text)

    @Test
    fun qobuz_open_playlist_url() {
        val ref = platformOf("https://open.qobuz.com/playlist/1979261")
        assertEquals(MusicPlatform.qobuz, ref!!.platform)
        assertEquals("1979261", ref.id)
    }

    @Test
    fun qobuz_web_playlist_url_with_locale_prefix() {
        val ref = platformOf("https://www.qobuz.com/us-en/playlists/1979261")
        assertEquals(MusicPlatform.qobuz, ref!!.platform)
        assertEquals("1979261", ref.id)
    }

    @Test
    fun apple_playlist_url_with_slug_and_lang_param() {
        val ref = platformOf(
            "https://music.apple.com/cn/playlist/jazz-chill/pl.63271312c084419891982eab46cc68ac?l=zh-Hans",
        )
        assertEquals(MusicPlatform.apple, ref!!.platform)
        assertEquals("pl.63271312c084419891982eab46cc68ac", ref.id)
    }

    @Test
    fun apple_playlist_url_without_slug() {
        val ref = platformOf("https://music.apple.com/us/playlist/pl.cbefd5b0db0e4d9698da967311cb811c")
        assertEquals(MusicPlatform.apple, ref!!.platform)
        assertEquals("pl.cbefd5b0db0e4d9698da967311cb811c", ref.id)
    }

    /**
     * 真实分享链接：id 是 base64url 风格的 `pl.u-…`，**含 `-`**。
     * 字符集漏掉 `-` 时会在那里截断成 `pl.u`，然后拿着一个不存在的 id 去请求。
     */
    @Test
    fun apple_playlist_id_may_contain_dash() {
        val ref = platformOf(
            "https://music.apple.com/tr/playlist/tiktok-songs/pl.u-LdbqEpgu2ZaRMqG?ls",
        )
        assertEquals(MusicPlatform.apple, ref!!.platform)
        assertEquals("pl.u-LdbqEpgu2ZaRMqG", ref.id)
    }

    @Test
    fun apple_playlist_url_without_storefront() {
        val ref = platformOf("https://music.apple.com/playlist/pl.0123456789abcdef0123456789abcdef")
        assertEquals(MusicPlatform.apple, ref!!.platform)
        assertEquals("pl.0123456789abcdef0123456789abcdef", ref.id)
    }

    @Test
    fun share_text_around_url_is_ignored() {
        val ref = platformOf("分享歌单 https://open.qobuz.com/playlist/1979261 一起听")
        assertEquals(MusicPlatform.qobuz, ref!!.platform)
        assertEquals("1979261", ref.id)
    }

    @Test
    fun domestic_platforms_still_resolve() {
        assertEquals(
            "123456",
            platformOf("https://www.kuwo.cn/playlist_detail/123456")?.id,
        )
        assertEquals(
            "654321",
            platformOf("https://music.163.com/playlist?id=654321")?.id,
        )
        assertEquals(
            "789",
            platformOf("https://y.qq.com/n/ryqq/playlist/789")?.id,
        )
    }

    @Test
    fun unknown_text_is_not_a_playlist() {
        assertNull(platformOf("https://example.com/whatever"))
        assertNull(platformOf("随便一句话"))
    }
}
