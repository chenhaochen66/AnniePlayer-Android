package com.annieplayer.annie_music.kt.data

import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「全部单曲」续拉的终止判断与分批粒度（纯 JVM）。
 *
 * 这两处错了的表现都不明显：判早了列表停在半路（用户以为歌手就这么多歌），
 * 判晚了就是一个无限请求循环。所以宁可多测几条边界。
 */
class ArtistSongsBatchTest {

    @Test
    fun `分批粒度是各平台实测的单请求上限`() {
        assertEquals(1000, artistSongsBatchSize(MusicPlatform.kuwo))
        assertEquals(200, artistSongsBatchSize(MusicPlatform.netease))
        assertEquals(100, artistSongsBatchSize(MusicPlatform.qq))
        assertEquals(100, artistSongsBatchSize(MusicPlatform.apple))
        assertEquals(200, artistSongsBatchSize(MusicPlatform.kkbox))
        assertEquals(30, artistSongsBatchSize(MusicPlatform.kugou))
    }

    @Test
    fun `一次给全的平台不需要续拉`() {
        assertEquals(0, artistSongsBatchSize(MusicPlatform.qobuz))
        assertEquals(0, artistSongsBatchSize(MusicPlatform.local))
    }

    @Test
    fun `有 total 时按 total 判`() {
        // 周杰伦 1708 首：第一批 1000 条 → 还有
        assertEquals(true, artistSongsHasMore(offset = 0, got = 1000, total = 1708, size = 1000))
        // 第二批 708 条，到 1708 → 到底
        assertEquals(false, artistSongsHasMore(offset = 1000, got = 708, total = 1708, size = 1000))
        // 恰好整除：589 首 / 200 → 第三批 189 条到 589
        assertEquals(false, artistSongsHasMore(offset = 400, got = 189, total = 589, size = 200))
        assertEquals(true, artistSongsHasMore(offset = 200, got = 200, total = 589, size = 200))
    }

    @Test
    fun `没有 total 时退化成满批就还有`() {
        assertEquals(true, artistSongsHasMore(offset = 0, got = 100, total = 0, size = 100))
        assertEquals(false, artistSongsHasMore(offset = 100, got = 78, total = 0, size = 100))
    }

    @Test
    fun `空批一律算到底 否则会无限请求`() {
        // total 虚高时（网易实测同一个歌手有时报 589 有时报 280），
        // 只看 total 就会一直请求下去
        assertEquals(false, artistSongsHasMore(offset = 0, got = 0, total = 589, size = 200))
        assertEquals(false, artistSongsHasMore(offset = 400, got = 0, total = 0, size = 200))
    }
}
