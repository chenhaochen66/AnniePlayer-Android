package com.annieplayer.annie_music.kt.data.favorites

import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 歌单导入的合并规则（从链接 / 收藏歌单往自建歌单里灌）。
 *
 * 三条语义各自对应一个会真的发生的问题：
 * - 覆盖而不是追加 → 用户手动加过的歌被冲掉；
 * - 不去重 → 同一个歌单导入两次，列表翻倍；
 * - 顺序乱 → 新导入的歌插在手动排好的前面。
 */
class PlaylistMergeTest {

    private fun song(id: String, platform: MusicPlatform = MusicPlatform.netease) =
        Song(
            id = id,
            title = id,
            artist = "",
            cover = "",
            duration = 0,
            platform = platform,
        )

    @Test
    fun `空歌单导入就是原样`() {
        val incoming = listOf(song("1"), song("2"))
        assertEquals(incoming, mergePlaylistSongs(emptyList(), incoming))
    }

    @Test
    fun `导入空列表不动已有内容`() {
        val existing = listOf(song("1"))
        assertEquals(existing, mergePlaylistSongs(existing, emptyList()))
    }

    @Test
    fun `已有的在前 新的追加在后`() {
        val merged = mergePlaylistSongs(
            existing = listOf(song("a"), song("b")),
            incoming = listOf(song("c"), song("d")),
        )
        assertEquals(listOf("a", "b", "c", "d"), merged.map { it.id })
    }

    @Test
    fun `重复导入不会翻倍`() {
        val existing = listOf(song("a"), song("b"))
        val merged = mergePlaylistSongs(existing, listOf(song("b"), song("c")))
        assertEquals(listOf("a", "b", "c"), merged.map { it.id })
    }

    @Test
    fun `导入列表内部的重复也去掉`() {
        val merged = mergePlaylistSongs(emptyList(), listOf(song("a"), song("a"), song("b")))
        assertEquals(listOf("a", "b"), merged.map { it.id })
    }

    @Test
    fun `同 id 不同平台算两首`() {
        // key 是 platform:id，网易的 123 和 QQ 的 123 是两首歌
        val existing = listOf(song("123", MusicPlatform.netease))
        val merged = mergePlaylistSongs(existing, listOf(song("123", MusicPlatform.qq)))
        assertEquals(2, merged.size)
    }

    @Test
    fun `本地歌按 local 路径去重`() {
        val path = "local:/storage/emulated/0/Music/a.flac"
        val existing = listOf(song(path, MusicPlatform.local))
        val merged = mergePlaylistSongs(existing, listOf(song(path, MusicPlatform.local)))
        assertEquals(1, merged.size)
        assertEquals(path, merged.first().id)
    }
}
