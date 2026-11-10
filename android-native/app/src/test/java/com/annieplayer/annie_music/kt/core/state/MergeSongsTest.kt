package com.annieplayer.annie_music.kt.core.state

import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「全部单曲」页续拉的追加合并（纯 JVM）。
 *
 * 守三件事，任何一条错了都不会崩、只会悄悄错：
 * 1. **只追加**：已有顺序一个都不许动 —— 动了滚动位置会跳、overview 的「最热门」前 20 首也会变；
 * 2. **去重**：续拉第一批必然与已加载的第 1 页重叠（页大小 20 vs 平台批大小 100/200/1000）；
 * 3. 空列表两个方向都不能炸（首次进入 / 平台返回空批）。
 */
class MergeSongsTest {

    private fun song(id: String) = Song(
        id = id,
        title = "t$id",
        artist = "a",
        cover = "",
        duration = 1,
        platform = MusicPlatform.kuwo,
    )

    private fun ids(list: List<Song>) = list.map { it.id }

    @Test
    fun `首批直接给去重后的入参`() {
        val merged = mergeSongs(emptyList(), listOf(song("1"), song("2"), song("2")))
        assertEquals(listOf("1", "2"), ids(merged))
    }

    @Test
    fun `空批不动已有列表`() {
        val existing = listOf(song("1"), song("2"))
        assertEquals(existing, mergeSongs(existing, emptyList()))
    }

    @Test
    fun `重叠部分不重复追加 且已有顺序不变`() {
        // 第 1 页 20 首（1..20），续拉第一批 100 首（1..100）
        val page = (1..20).map { song("$it") }
        val batch = (1..100).map { song("$it") }
        val merged = mergeSongs(page, batch)
        assertEquals((1..100).map { "$it" }, ids(merged))
    }

    @Test
    fun `第二批接着往尾部追加`() {
        val first = (1..100).map { song("$it") }
        val second = (101..200).map { song("$it") }
        assertEquals((1..200).map { "$it" }, ids(mergeSongs(first, second)))
    }

    @Test
    fun `平台重排了也要保已有顺序`() {
        // 万一平台把热门顺序换了，也不能让已经在屏上的行跳位置
        val existing = listOf(song("3"), song("1"), song("2"))
        val incoming = listOf(song("2"), song("4"), song("1"))
        assertEquals(listOf("3", "1", "2", "4"), ids(mergeSongs(existing, incoming)))
    }
}
