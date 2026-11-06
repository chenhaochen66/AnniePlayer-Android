package com.annieplayer.annie_music.kt.data.sources.home

import com.annieplayer.annie_music.kt.data.model.HomeCardStyle
import com.annieplayer.annie_music.kt.data.model.HomeItem
import com.annieplayer.annie_music.kt.data.model.HomeShelf
import com.annieplayer.annie_music.kt.data.model.HomeTarget
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Quality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 酷我主页聚合流（`mgxh.s`）的解析与映射（纯 JVM，不联网）。
 *
 * 夹具都是**真机抓包**里切出来的：
 * - `fixtures/kuwo_tuijian.xml`：推荐流 `type=tuijian11` —— 两个 `caption_songlist`（各 2 首）、
 *   一个 `r10_songlist_tab`（2 个子项 × 2 个歌单）、一个 `radiobanner`（电台，必须被丢掉）；
 * - `fixtures/kuwo_hotpage.xml`：热门流 `type=hotpage` —— `banner`（3 个歌单）、
 *   `tab_scroll_list`（新歌 2 首 / 新碟 2 张）、`scroll_square`（听书专辑 1 张，必须丢掉）、
 *   `r10_tuijian_bang`（榜内歌曲 1 首，按需求不做）。
 *
 * 守三件事：
 * 1. 结构化解析不依赖 Android（XmlPullParser 一碰单测就红，所以是手写扫描）；
 * 2. **不接我们不要的 section** —— 听书专辑点进去 `getAlbumSongs` 打不开、电台/榜单没有落点；
 * 3. 歌曲必须走 `_mapKuwoSong`（档位 / 封面 / 多歌手全在它手里），不能另写一份。
 */
class KuwoHotPageTest {

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/fixtures/$name")!!.use { it.readBytes().toString(Charsets.UTF_8) }

    private val xml: String by lazy { fixture("kuwo_hotpage.xml") }

    private val tuijianXml: String by lazy { fixture("kuwo_tuijian.xml") }

    private fun shelves(): List<HomeShelf> = kuwoHotShelves(parseKuwoHotPage(xml))

    private fun tuijianShelves(): List<HomeShelf> = kuwoHotShelves(parseKuwoHotPage(tuijianXml))

    // ── 热门流（hotpage）──

    @Test
    fun parses_every_section_in_feed_order() {
        val sections = parseKuwoHotPage(xml)
        assertEquals(
            listOf("banner", "tab_scroll_list", "scroll_square", "r10_tuijian_bang"),
            sections.map { it.type },
        )
        // banner 是「歌单墙」，里面是 songlist 而不是 music
        assertEquals(listOf("songlist", "songlist", "songlist"), sections[0].items.map { it.tag })
        // tab_scroll_list 同时带新歌与新碟
        assertEquals(
            listOf("music", "music", "album", "album"),
            sections[1].items.map { it.tag },
        )
    }

    @Test
    fun hotpage_produces_three_shelves_in_songs_first_order() {
        val ids = shelves().map { it.id }
        // banner 位进「精选歌单」轮播行并且置顶；剩下的歌曲行 / 专辑行按 feed 顺序
        assertEquals(listOf("kuwo:hero", "kuwo:songs:1", "kuwo:newalbum:1"), ids)
        assertEquals(listOf("精选歌单", "新歌速递", "新专辑"), shelves().map { it.title })
        // 专辑行与歌曲行的副标题用服务端 section 的 label（「数字专辑」）
        assertEquals(listOf("酷我编辑精选", "数字专辑", "数字专辑"), shelves().map { it.subtitle })
    }

    @Test
    fun new_song_shelf_reuses_the_shared_kuwo_song_mapper() {
        val songs = shelves().filterIsInstance<HomeShelf.Songs>().first().songs
        assertEquals(2, songs.size)

        val first = songs[0]
        assertEquals("650138169", first.id)
        assertEquals("温柔的存在", first.title)
        assertEquals("梁咏琪", first.artist)
        assertEquals("102977952", first.albumId)
        assertEquals(185, first.duration)
        assertEquals(MusicPlatform.kuwo, first.platform)
        // 封面：feed 给的是 /300/，映射器统一提到 800（与搜索/歌单一致）
        assertEquals(
            "http://img1.kuwo.cn/star/albumcover/800/s4s34/93/3237014875.jpg",
            first.cover,
        )
        // 档位来自 minfo：ff=flac / p=320k / h=128k，一个都不能少
        assertTrue("缺 flac 档位：${first.qualities}", first.qualities.contains(Quality.flac))
        assertTrue("缺 320k 档位：${first.qualities}", first.qualities.contains(Quality.k320k))
        assertTrue("缺 128k 档位：${first.qualities}", first.qualities.contains(Quality.k128k))
        val flacSize = first.qualitySizes[Quality.flac] ?: 0L
        assertTrue("flac 体积没解析出来：$flacSize", flacSize > 30L * 1024 * 1024)
    }

    @Test
    fun hero_row_carries_the_banner_playlists_with_the_hero_style() {
        val hero = shelves().first { it.id == "kuwo:hero" } as HomeShelf.Cards
        assertEquals("精选歌单", hero.title)
        assertEquals(
            listOf("1082685104", "735209889", "1082656711"),
            hero.items.map { it.key.substringAfterLast(':') },
        )
        val first = hero.items[0]
        assertEquals("每日最新单曲推荐", first.title)
        // 大图轮播行：卡片必须是 Hero（UI 端据此走「完整显示 + 自动翻动」那条渲染路径）
        assertEquals(HomeCardStyle.Hero, first.style)
        assertEquals("2亿", first.badge)
        assertEquals("2亿播放", first.subtitle)
        val target = first.target
        assertNotNull(target)
        assertTrue(target is HomeTarget.Playlist)
        target as HomeTarget.Playlist
        assertEquals(MusicPlatform.kuwo, target.platform)
        assertEquals("1082685104", target.id)
        assertEquals("https://kwimg3.kuwo.cn/star/upload/ecom/1678851094908.jpg", first.cover)
        // banner 的卡不能再出现在普通歌单行里（否则同一张卡出现两次）
        assertTrue(
            shelves().filterIsInstance<HomeShelf.Cards>()
                .filter { it.id != "kuwo:hero" }
                .flatMap { it.items }
                .none { it.style == HomeCardStyle.Hero },
        )
    }

    @Test
    fun album_cards_carry_release_date_and_track_count() {
        val cards = shelves().first { it.id.startsWith("kuwo:newalbum") }.let { (it as HomeShelf.Cards).items }
        // 听书专辑（scroll_square）不进这一行：那一行的 album id 是「听书专辑」，getAlbumSongs 打不开
        assertEquals(2, cards.size)
        val first = cards[0]
        assertEquals("105748203", (first.target as HomeTarget.Album).id)
        assertEquals("Когда душе твоей больно (When Your Soul Hurts)", first.title)
        assertEquals("Ploho", first.subtitle)
        assertEquals("2026-10-24", first.pub)
        assertEquals(1, first.count)
        assertEquals(HomeCardStyle.Album, first.style)
        // `&quot;` 要解码成引号，否则专辑名带着实体串进 UI
        assertEquals("By My Side (From \"XO, Kitty\") (Stripped Version)", cards[1].title)
    }

    @Test
    fun hotpage_drops_the_sections_we_cannot_use() {
        // 榜内那首（NEON）与听书专辑都不能漏进来：一共 3 首 music / 3 张 album，各丢掉一个
        val out = shelves()
        val songs = out.filterIsInstance<HomeShelf.Songs>().flatMap { it.songs }
        val albumCards = out.filterIsInstance<HomeShelf.Cards>()
            .first { it.id.startsWith("kuwo:newalbum") }
            .items
        assertEquals(2, songs.size)
        assertEquals(2, albumCards.size)
        assertTrue(songs.none { it.title == "NEON" })
        assertTrue(albumCards.none { (it.target as HomeTarget.Album).id == "104792767" })
    }

    // ── 推荐流（tuijian11）──

    @Test
    fun tuijian_yields_one_song_row_per_section_with_server_label_as_title() {
        val out = tuijianShelves()
        // 2 个歌曲行 + 2 个歌单子分类行（夹具里是「为你推荐」和「怀旧」）
        assertEquals(
            listOf("kuwo:songs:1", "kuwo:songs:2", "kuwo:playlist:1", "kuwo:playlist:2"),
            out.map { it.id },
        )
        assertEquals(
            listOf("你的音乐宝库", "超好听的单曲", "为你推荐", "怀旧"),
            out.map { it.title },
        )
        // 子分类行把大类（section label=「为你推荐」）放副标题；与子分类同名时不重复
        assertEquals(listOf("", "", "", "为你推荐"), out.map { it.subtitle })

        val first = (out[0] as HomeShelf.Songs).songs
        assertEquals(2, first.size)
        assertEquals("59436283", first[0].id)
        assertEquals("Love Story", first[0].title)
        assertEquals("Taylor Swift", first[0].artist)
        assertEquals(236, first[0].duration)
        // 推荐流给的是 160 宽封面，映射器照样提到 800，并把 host 统一成 img1（buildCoverUrl 的既有规则）
        assertEquals("http://img1.kuwo.cn/star/albumcover/800/s4s58/24/682659257.jpg", first[0].cover)

        val second = (out[1] as HomeShelf.Songs).songs
        assertEquals("38522965", second[0].id)
        assertEquals("再次重逢的世界", second[0].title)
    }

    @Test
    fun tuijian_playlist_wall_is_read_from_nested_sublists() {
        // 夹具里歌单墙有两个子分类：为你推荐（2 个）/ 怀旧（2 个）→ 两行，不能挤成一行
        val rows = tuijianShelves().filterIsInstance<HomeShelf.Cards>()
        assertEquals(listOf("为你推荐", "怀旧"), rows.map { it.title })
        assertEquals(
            listOf("3391190743", "2592084714"),
            rows[0].items.map { (it.target as HomeTarget.Playlist).id },
        )
        assertEquals(
            listOf("3373318845", "3071654059"),
            rows[1].items.map { (it.target as HomeTarget.Playlist).id },
        )
        assertEquals("【一囗甜】耳机分你一只，开启心动模式", rows[0].items[0].title)
        assertEquals("1.8万", rows[0].items[0].badge)
        // radiobanner 的电台没有落点（rid 打不开歌单/专辑详情），不许变成卡片
        assertTrue(rows.flatMap { it.items }.none { it.title == "猜你喜欢" })
        assertEquals(4, tuijianShelves().size)
    }

    // ── 两条 feed 合并（推荐流 + 热门流一起展示）──

    @Test
    fun merge_keeps_hotpage_rows_after_the_tuijian_rows() {
        // 过去热门流只是「推荐流拿不到时的备胎」→ 编辑精选 / 新歌 / 新碟 平时永远不出现
        val merged = mergeKuwoShelves(tuijianShelves(), shelves())
        assertEquals(
            listOf(
                // 精选歌单（大图轮播）置顶：它在热门流里，合并后不能被推荐流的行挤到中间
                "kuwo:hero",
                // 推荐流：2 个歌曲行 + 2 个歌单子分类行
                "kuwo:songs:1",
                "kuwo:songs:2",
                "kuwo:playlist:1",
                "kuwo:playlist:2",
                // 热门流其余：与上面撞 id 的加 `:2`，`newalbum:1` 不撞、保留原 id
                "kuwo:songs:1:2",
                "kuwo:newalbum:1",
            ),
            merged.map { it.id },
        )
        assertEquals(
            listOf("精选歌单", "你的音乐宝库", "超好听的单曲", "为你推荐", "怀旧", "新歌速递", "新专辑"),
            merged.map { it.title },
        )
        assertEquals(7, merged.size)
    }

    @Test
    fun merge_dedupes_a_card_that_both_feeds_carry() {
        fun card(id: String) = HomeItem(
            key = "kuwo:playlist:$id",
            title = "歌单$id",
            style = HomeCardStyle.Playlist,
            target = HomeTarget.Playlist(MusicPlatform.kuwo, id, "歌单$id", ""),
        )
        val primary = listOf(
            HomeShelf.Cards(id = "kuwo:playlist", title = "推荐歌单", items = listOf(card("1"), card("2"))),
        )
        val extra = listOf(
            HomeShelf.Cards(id = "kuwo:playlist", title = "推荐歌单", items = listOf(card("2"), card("3"))),
        )
        val merged = mergeKuwoShelves(primary, extra)
        assertEquals(2, merged.size)
        // 第二行只留主 feed 没有的那张，且标题改成「编辑精选」
        assertEquals("编辑精选", merged[1].title)
        assertEquals(listOf("kuwo:playlist:3"), (merged[1] as HomeShelf.Cards).items.map { it.key })
    }

    @Test
    fun playlist_wall_is_not_truncated_to_the_netease_cap() {
        // 服务端一页给 44 个；套用 HomePlaylistsMax=30 会白丢 14 个
        val many = buildString {
            append("""<root><section type="r10_songlist_tab" label="为你推荐">""")
            repeat(44) { i ->
                append(
                    """<songlist id="${1000 + i}" name="歌单$i" """ +
                        """img="http://img1.kuwo.cn/x/300/1.jpg" listencnt="100"/>""",
                )
            }
            append("</section></root>")
        }
        val shelves = kuwoHotShelves(parseKuwoHotPage(many))
        assertEquals(1, shelves.size)
        assertEquals(44, (shelves[0] as HomeShelf.Cards).items.size)
    }

    // ── 边界 ──

    @Test
    fun empty_feed_yields_no_shelves() {
        // 推荐流缺 `notrace=0` 时服务端就是回这个：只有结束标记
        val sections = parseKuwoHotPage(
            """<?xml version="1.0" encoding="UTF-8"?><root><section weight="" type="rcm_page_end" label="推荐页结束标记"></section></root>""",
        )
        assertEquals(1, sections.size)
        assertTrue(kuwoHotShelves(sections).isEmpty())
        assertTrue(kuwoHotShelves(parseKuwoHotPage("")).isEmpty())
    }

    @Test
    fun attribute_entities_are_decoded_in_the_right_order() {
        assertEquals("A2O LITE&A2O", _kuwoDecodeAttr("A2O LITE&amp;A2O"))
        assertEquals("By My Side (From \"XO, Kitty\")", _kuwoDecodeAttr("By My Side (From &#34;XO, Kitty&#34;)"))
        // `&amp;` 必须最后解，否则 `&amp;lt;` 会被当成 `&lt;` 再解成 `<`
        assertEquals("a&lt;b", _kuwoDecodeAttr("a&amp;lt;b"))
    }
}
