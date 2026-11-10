package com.annieplayer.annie_music.kt.data.sources.kugou

import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.sources.common.signParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 酷狗歌单详情（`pubsongs/v2/get_other_list_file_nofilt`）的签名与映射。
 *
 * 全部是纯函数，不联网。夹具里的值是**真机抓包 / 实测响应**里抄下来的，
 * 所以断言的是「服务端真实形状」而不是我们自己编的结构。
 *
 * 最要紧的一条是 [detail_signature_matches_captured_request]：签名算法一旦被服务端换掉，
 * 这里会立刻红，不用等到真机上「歌单打不开」才发现。
 */
class KugouPlaylistTest {

    // ── 签名 ──

    @Test
    fun detail_signature_matches_captured_request() {
        // 抓包里那条请求（begin_idx=0 / pagesize=100）的 signature 原值
        val params = kugouPlaylistDetailParams(
            globalCollectionId = "collection_3_663783710_39_0",
            specialId = "6748583",
            beginIdx = 0,
            pageSize = 100,
        )
        assertEquals("8e43d5df54d27001238bc172ce632b0b", signParams(params, kugouPlaylistSecret))
    }

    @Test
    fun signature_changes_with_begin_index() {
        // 翻页必须重算签名：同一套参数换个 begin_idx，签名必然不同（服务端会判 20006）
        fun signAt(begin: Int) = signParams(
            kugouPlaylistDetailParams("collection_3_663783710_39_0", "6748583", begin, 100),
            kugouPlaylistSecret,
        )
        assertTrue(signAt(0) != signAt(100))
    }

    @Test
    fun params_carry_only_the_id_that_exists() {
        // 两个 id 传一个就够；空串参数会改变签名，所以不能塞空值占位
        val gcidOnly = kugouPlaylistDetailParams("collection_3_663783710_39_0", "", 0, 300)
        assertTrue(gcidOnly.containsKey("global_collection_id"))
        assertTrue(!gcidOnly.containsKey("specialid"))

        val specialOnly = kugouPlaylistDetailParams("", "6748583", 0, 300)
        assertTrue(!specialOnly.containsKey("global_collection_id"))
        assertTrue(specialOnly.containsKey("specialid"))
        assertEquals("3116", specialOnly["appid"])
    }

    // ── 歌手 ──

    @Test
    fun singers_keep_id_and_name_paired() {
        val (names, ids) = kugouPlaylistSingers(
            listOf(
                mapOf("id" to 170921, "name" to "Troye Sivan"),
                mapOf("id" to 217160, "name" to "Allday"),
            ),
        )
        assertEquals(listOf("Troye Sivan", "Allday"), names)
        assertEquals(listOf("170921", "217160"), ids)
    }

    @Test
    fun singers_drop_entries_missing_either_side() {
        // 缺 name 或 id=0 的都丢掉，绝不产出半截映射（下游按「数量一致」判定整组）
        val (names, ids) = kugouPlaylistSingers(
            listOf(
                mapOf("id" to 1, "name" to ""),
                mapOf("id" to 0, "name" to "无效"),
                mapOf("id" to 2, "name" to "有效"),
            ),
        )
        assertEquals(listOf("有效"), names)
        assertEquals(listOf("2"), ids)
    }

    @Test
    fun singers_empty_when_field_absent() {
        val (names, ids) = kugouPlaylistSingers(null)
        assertTrue(names.isEmpty())
        assertTrue(ids.isEmpty())
    }

    // ── 曲目映射 ──

    private fun rawSong(
        hash: String = "1F245541E5B65CE1DD1BCA94D780C745",
        name: String = "Just the Way You Are",
        singers: List<Pair<String, String>> = listOf("22043" to "Bruno Mars"),
        albumId: String = "969063",
        albumName: String = "Doo-Wops &amp; Hooligans(Deluxe Version)",
        cover: String = "http://imge.kugou.com/stdmusic/{size}/20200620/20200620081833643412.jpg",
        timelen: Int = 220708,
    ): Map<String, Any?> = mapOf(
        "hash" to hash,
        "name" to name,
        "singerinfo" to singers.map { (id, n) -> mapOf("id" to id.toInt(), "name" to n) },
        "album_id" to albumId,
        "albuminfo" to mapOf("name" to albumName),
        "cover" to cover,
        "timelen" to timelen,
        "publish_date" to "2010-07-20",
    )

    @Test
    fun song_maps_every_field_we_need_after_collect() {
        val song = kugouPlaylistSongOf(rawSong())!!
        assertEquals("kugou:1F245541E5B65CE1DD1BCA94D780C745", song.id)
        assertEquals("Just the Way You Are", song.title)
        assertEquals("Bruno Mars", song.artist)
        assertEquals("Bruno Mars", song.albumArtist)
        assertEquals(listOf("Bruno Mars"), song.artistNames)
        assertEquals(listOf("22043"), song.artistIds)
        // `&amp;` 必须解码，否则专辑名带着实体串进 UI 与标签
        assertEquals("Doo-Wops & Hooligans(Deluxe Version)", song.album)
        assertEquals("969063", song.albumId)
        // `{size}` 模板替换成 800（与其它平台一个档）
        assertEquals(
            "http://imge.kugou.com/stdmusic/800/20200620/20200620081833643412.jpg",
            song.cover,
        )
        // 接口给的是毫秒
        assertEquals(220, song.duration)
        assertEquals("2010-07-20", song.releaseDate)
        assertEquals(MusicPlatform.kugou, song.platform)
    }

    @Test
    fun multi_singer_song_carries_all_ids() {
        val song = kugouPlaylistSongOf(
            rawSong(
                hash = "ABC123",
                name = "for him.",
                singers = listOf("170921" to "Troye Sivan", "217160" to "Allday"),
            ),
        )!!
        assertEquals("Troye Sivan / Allday", song.artist)
        assertEquals(listOf("170921", "217160"), song.artistIds)
    }

    @Test
    fun song_without_hash_or_title_is_dropped() {
        assertNull(kugouPlaylistSongOf(rawSong(hash = "")))
        assertNull(kugouPlaylistSongOf(rawSong(name = "")))
    }

    @Test
    fun songs_list_drops_incomplete_entries() {
        val data = mapOf<String, Any?>(
            "songs" to listOf(
                mapOf("name" to "没有 hash"),
                rawSong(),
            ),
        )
        assertEquals(1, kugouPlaylistSongsOf(data).size)
        assertTrue(kugouPlaylistSongsOf(emptyMap()).isEmpty())
    }

    @Test
    fun album_id_zero_is_normalized_to_null() {
        // 酷狗用 0 表示「无专辑」，直接塞进 Song 会让跳专辑拿到 id=0
        assertNull(kugouPlaylistSongOf(rawSong(albumId = "0"))!!.albumId)
    }

    // ── 歌单元信息 ──

    @Test
    fun meta_reads_list_info() {
        val meta = kugouPlaylistMetaOf(
            mapOf(
                "list_info" to mapOf(
                    "name" to "私藏欧美流行歌曲，享受听觉盛宴",
                    "pic" to "http://c1.kgimg.com/v2/custom/a6d288b629352e12e4a0cc47375050fc.png",
                    "list_create_username" to "ZHANG",
                ),
            ),
        )
        assertEquals("私藏欧美流行歌曲，享受听觉盛宴", meta.name)
        assertEquals(
            "http://c1.kgimg.com/v2/custom/a6d288b629352e12e4a0cc47375050fc.png",
            meta.cover,
        )
        assertEquals("ZHANG", meta.creator)
    }

    @Test
    fun meta_falls_back_when_list_info_missing() {
        val meta = kugouPlaylistMetaOf(emptyMap())
        assertEquals("酷狗歌单", meta.name)
        assertEquals("", meta.cover)
        assertEquals("", meta.creator)
    }
}
