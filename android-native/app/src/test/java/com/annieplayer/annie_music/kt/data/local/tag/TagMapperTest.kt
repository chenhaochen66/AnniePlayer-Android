package com.annieplayer.annie_music.kt.data.local.tag

import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 写标签时的「多歌手」规则（纯 JVM）。
 *
 * 这组用例守的是两个**踩过**的东西：
 * 1. 多歌手统一写成 `A / B`；
 * 2. **绝不按分隔符拆合并串** —— Apple 的 `Lucas & Steve & Blackstreet` 里，`&`
 *    既是连接符又是乐队名（`Lucas & Steve`）的一部分，拆出来是三个假歌手。
 *    真实接口验证过：只有 `relationships.artists` 才给出正确的两个名字。
 */
class TagMapperTest {

    private fun song(artist: String, names: List<String> = emptyList()) = Song(
        id = "apple:1",
        title = "T",
        artist = artist,
        cover = "",
        duration = 0,
        platform = MusicPlatform.apple,
        artistNames = names,
    )

    @Test
    fun single_artist_is_untouched() {
        assertEquals("Lucas & Steve", TagMapper.artistOf(listOf("Lucas & Steve"), "Lucas & Steve"))
        assertEquals("周杰伦", TagMapper.artistOf(listOf("周杰伦"), "周杰伦"))
    }

    @Test
    fun multi_artist_uses_slash_joiner() {
        assertEquals(
            "Lucas & Steve / Blackstreet",
            TagMapper.artistOf(listOf("Lucas & Steve", "Blackstreet"), "Lucas & Steve & Blackstreet"),
        )
        assertEquals("A / B / C", TagMapper.artistOf(listOf("A", "B", "C"), ""))
    }

    @Test
    fun blank_names_are_dropped_and_fallback_wins_when_list_is_empty() {
        assertEquals("A / B", TagMapper.artistOf(listOf("A", " ", "", "B"), "x"))
        assertEquals("平台合并串", TagMapper.artistOf(emptyList(), "平台合并串"))
    }

    @Test
    fun joined_string_is_never_split_on_separators() {
        // 没有结构化列表时，原串整段照写（不拆 `&` / `,` / `/`）
        val raw = "Lucas & Steve & Blackstreet"
        assertEquals(raw, TagMapper.artistOf(emptyList(), raw))
        assertEquals(raw, TagMapper.artistOf(listOf(), raw))
    }

    @Test
    fun tag_map_uses_structured_names_for_artist_field() {
        val withNames = TagMapper.ofSong(
            song("Lucas & Steve & Blackstreet", listOf("Lucas & Steve", "Blackstreet")),
            lyric = null,
        )
        assertEquals("Lucas & Steve / Blackstreet", withNames[TagKey.Artist])

        val withoutNames = TagMapper.ofSong(song("Lucas & Steve & Blackstreet"), lyric = null)
        assertEquals("Lucas & Steve & Blackstreet", withoutNames[TagKey.Artist])
    }

    @Test
    fun artist_field_is_not_empty_for_normal_song() {
        val map = TagMapper.ofSong(song("Someone", listOf("Someone")), "lrc")
        assertTrue("艺术家不该为空", (map[TagKey.Artist] ?: "").isNotEmpty())
        assertEquals("lrc", map[TagKey.Lyric])
    }
}
