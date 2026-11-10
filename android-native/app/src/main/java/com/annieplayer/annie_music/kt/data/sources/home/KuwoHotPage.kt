package com.annieplayer.annie_music.kt.data.sources.home

import com.annieplayer.annie_music.kt.data.model.HomeCardStyle
import com.annieplayer.annie_music.kt.data.model.HomeItem
import com.annieplayer.annie_music.kt.data.model.HomeShelf
import com.annieplayer.annie_music.kt.data.model.HomeTarget
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.sources.kuwo.kuwoMapSong

// ================================================================================================
// 酷我主页聚合流（`homepages.kuwo.cn/mgxh.s`）
// ================================================================================================
//
// ## 两条 feed，一份解析
// - `type=tuijian11`（**推荐流**，首页「推荐」tab）：歌曲行 ×2（各 28 首）+ 歌单 40 多个；
// - `type=hotpage`（**热门** tab）：编辑精选歌单 + 新歌 + 新碟。
// 两者都是同一套 `<section>` / `<music>` / `<album>` / `<songlist>` 结构，所以解析与映射共用。
//
// 注意 `tuijian11` **必须带 `notrace=0`**，否则服务端只回一个 `rcm_page_end` 空流
// （不是签名、不是登录态：`uid`/`user`/`q36`/`oaid` 全都可以不带）。
//
// ## 为什么单独一个文件
// 三家里只有酷我主页是 **XML**，另外两家都是 JSON。把「文本 → [HomeShelf]」整段放这里，
// [HomeFeedApi] 那边只剩取数 + 缓存，而这一段是**纯函数**（不碰 OkHttp、不碰 Android），
// 可以拿抓包存下来的真实响应直接做 JVM 单测。
//
// ## 为什么不用 XmlPullParser / DocumentBuilder
// 前者是 Android 类，单测一碰就 `not mocked`；后者要连带处理 DTD / 实体扩展，为一份机器拼出来
// 的 XML 引入一整套解析器不值。这份 feed 的属性值里只会出现 `&amp;` `&#34;` 这类实体，
// 用「严格属性正则 + 逐段切」足够，且行为完全可测。
//
// ## 为什么只挑几个 section
// feed 里还有 MV（我们没有 MV 播放）、weex 专区（指向 H5）、听书专辑（`getAlbumSongs` 打不开
// 有声书）、电台（`radio` 的 rid 打不开详情）、榜单（按需求不做）、数字专辑售卖位
// （`my_number_album` 点了是购买页）。只取能直接落进现有模型的三类：歌曲 / 歌单 / 新碟。
//
// ## 分类怎么来的
// 歌单不是一锅炖：`r10_songlist_tab` 用 `<r10_songlist_tab_sublist name="…">` 把歌单分成
// 「为你推荐 / 怀旧 / 经典 / 华语 / DJ」（实测 5 组 44 个），而 `<songlist>` 自身不带分类字段。
// 所以解析时把子分类名挂到 `KuwoHotItem.group` 上，上层据此**一个子分类一行**。

/** 一个 `<section>`（`type` 决定它是什么区块，`label` 是服务端给的标题）。 */
internal data class KuwoHotSection(
    val type: String,
    val label: String,
    val items: List<KuwoHotItem>,
)

/** section 里的一条内容；[tag] 是元素名（`music` / `album` / `songlist` / …）。 */
internal data class KuwoHotItem(
    val tag: String,
    val attrs: Map<String, String>,
    /** 所属子分类名（歌单墙的 `r10_songlist_tab_sublist name=`）；没有子分类时为空串。 */
    val group: String = "",
)

// 属性名固定是 `name="value"` 形态（服务端拼串，不会出现单引号或裸值），值里允许出现 `>`，
// 所以必须写成 `"[^"]*"` 而不能图省事用 `[^>]*`。
private const val KW_ATTR = """\s+[A-Za-z_][A-Za-z0-9_:.-]*="[^"]*""""

private val KuwoSectionRe by lazy {
    Regex("<section($KW_ATTR)*\\s*/?>")
}

private val KuwoItemRe by lazy {
    Regex("<(music|album|songlist|mv|my_number_album)($KW_ATTR)*\\s*/?>")
}

/**
 * 歌单墙里的子分类头（实测 5 组：为你推荐 / 怀旧 / 经典 / 华语 / DJ）。
 *
 * 服务端把 44 个歌单**按子分类分组**放在 `r10_songlist_tab` 里，每个 `<songlist>` 自己不带
 * 分类字段 —— 想按类型分行就只能从这个头元素取 `name`。
 */
private val KuwoSublistRe by lazy {
    Regex("<r10_songlist_tab_sublist($KW_ATTR)*\\s*/?>")
}

private val KuwoAttrRe by lazy {
    Regex("([A-Za-z_][A-Za-z0-9_:.-]*)=\"([^\"]*)\"")
}

private val KuwoNumericEntityRe by lazy { Regex("&#(\\d{1,7});") }

/** 主页 feed 里能落进现有模型的几类 section。 */
private const val KW_SECTION_BANNER = "banner"
private const val KW_SECTION_TAB_SCROLL = "tab_scroll_list"

/** 推荐流的歌曲行（`label` 就是行标题：你的音乐宝库 / 超好听的单曲）。 */
private const val KW_SECTION_CAPTION_SONGLIST = "caption_songlist"

/** 推荐流里另一种歌曲行（带全量参数时出现）。 */
private const val KW_SECTION_REC_MUSIC = "rec_music"

/** 推荐流的歌单墙：子项带 `label`（为你推荐 / 怀旧 / …），下面挂 `<songlist>`。 */
private const val KW_SECTION_SONGLIST_TAB = "r10_songlist_tab"

/** 编辑精选那两行（老版本叫 `scroll_songlist`，12.0 起改叫 `scroll_songlist_ultra`）。 */
private const val KW_SECTION_SCROLL_SONGLIST = "scroll_songlist"
private const val KW_SECTION_SCROLL_SONGLIST_ULTRA = "scroll_songlist_ultra"

/** 会产生「歌曲行」的 section 类型。 */
private val KwSongSectionTypes = setOf(
    KW_SECTION_CAPTION_SONGLIST,
    KW_SECTION_REC_MUSIC,
    KW_SECTION_TAB_SCROLL,
)

/** 精选歌单（大图轮播）区块的固定 id；[mergeKuwoShelves] 靠它把这行提到最前。 */
internal const val KW_HERO_SHELF_ID = "kuwo:hero"

/** 会产生「歌单卡」的 section 类型。 */
private val KwPlaylistSectionTypes = setOf(
    KW_SECTION_BANNER,
    KW_SECTION_SONGLIST_TAB,
    KW_SECTION_SCROLL_SONGLIST,
    KW_SECTION_SCROLL_SONGLIST_ULTRA,
)

/** 进「精选歌单」轮播行的 section 类型：运营位 banner + 编辑精选那两行。 */
private val KwHeroSectionTypes = setOf(
    KW_SECTION_BANNER,
    KW_SECTION_SCROLL_SONGLIST,
    KW_SECTION_SCROLL_SONGLIST_ULTRA,
)

/**
 * 解析主页 XML 为 section 列表。
 *
 * 只做「切段 + 收属性」，不做任何业务判断 —— 哪个 section 对应哪个区块由 [kuwoHotShelves] 决定，
 * 这样服务端加减区块时只需要动那一处。
 *
 * 歌单墙的 `<r10_songlist_tab_sublist name="…">` 也会被切成段，其内的 item 带上 [KuwoHotItem.group]，
 * 这样上层才能「一个子分类一行」。
 */
internal fun parseKuwoHotPage(xml: String): List<KuwoHotSection> {
    if (xml.isEmpty()) return emptyList()
    val heads = KuwoSectionRe.findAll(xml).toList()
    if (heads.isEmpty()) return emptyList()

    val out = ArrayList<KuwoHotSection>(heads.size)
    for ((index, head) in heads.withIndex()) {
        val bodyStart = head.range.last + 1
        val bodyEnd = if (index + 1 < heads.size) heads[index + 1].range.first else xml.length
        val sectionAttrs = _kuwoAttrPairs(head.value)
        val items = ArrayList<KuwoHotItem>()
        if (bodyStart < bodyEnd) {
            val body = xml.substring(bodyStart, bodyEnd)
            val subHeads = KuwoSublistRe.findAll(body).toList()
            if (subHeads.isEmpty()) {
                _collectHotItems(body, "", items)
            } else {
                for ((si, sub) in subHeads.withIndex()) {
                    val s = sub.range.last + 1
                    val e = if (si + 1 < subHeads.size) subHeads[si + 1].range.first else body.length
                    val group = _kuwoAttrPairs(sub.value)["name"].orEmpty()
                    if (s < e) _collectHotItems(body.substring(s, e), group, items)
                }
            }
        }
        out += KuwoHotSection(
            type = sectionAttrs["type"].orEmpty(),
            label = sectionAttrs["label"].orEmpty(),
            items = items,
        )
    }
    return out
}

/** 把一段 XML 里的内容项收进 [out]，并挂上子分类名 [group]。 */
private fun _collectHotItems(body: String, group: String, out: MutableList<KuwoHotItem>) {
    for (item in KuwoItemRe.findAll(body)) {
        out += KuwoHotItem(
            tag = item.groupValues[1],
            attrs = _kuwoAttrPairs(item.value),
            group = group,
        )
    }
}

/**
 * section 列表 → 主页区块。
 *
 * 顺序：**精选歌单（大图轮播，置顶）** → 歌曲行（一个 section 一行，标题用服务端的 `label`）
 * → 歌单子分类行 → 新碟一行。
 * 某个区块没内容就整行不出现，不返回空区块 —— 空区块在 UI 上是一条只有标题的空行。
 */
internal fun kuwoHotShelves(sections: List<KuwoHotSection>): List<HomeShelf> {
    if (sections.isEmpty()) return emptyList()
    val out = mutableListOf<HomeShelf>()

    // ── 精选歌单（置顶 + 大图轮播）──
    // 来源是热门流的运营位：`banner`（实测 10 张）+ 编辑精选那两行
    // （老版本 `scroll_songlist`、12.0 起 `scroll_songlist_ultra`；这两行服务端经常给空）。
    // 这些图是**运营宽图**（`star/upload/ecom/*.jpg`），塞进 168dp 方图会被裁掉大半 ——
    // 所以给它们 `HomeCardStyle.Hero`，UI 端用接近满宽的卡片 + 完整显示 + 自动轮播。
    val heroCards = LinkedHashMap<String, HomeItem>()
    for (section in sections) {
        if (section.type !in KwHeroSectionTypes) continue
        for (item in section.items) {
            if (item.tag != "songlist") continue
            val card = _kuwoHotPlaylist(item.attrs, HomeCardStyle.Hero) ?: continue
            heroCards[card.key] = card
        }
    }
    if (heroCards.isNotEmpty()) {
        out += HomeShelf.Cards(
            id = KW_HERO_SHELF_ID,
            title = "精选歌单",
            subtitle = "酷我编辑精选",
            items = heroCards.values.toList().take(KuwoPlaylistsMax),
        )
    }

    // ── 歌曲行 ──
    // 推荐流是两个 `caption_songlist`（你的音乐宝库 / 超好听的单曲），热门流是 `tab_scroll_list`
    // 里的新歌（它的 label 是「数字专辑」，不能当行标题，所以单独给标题）。
    var songShelfIndex = 0
    for (section in sections) {
        if (section.type !in KwSongSectionTypes) continue
        val songs = section.items.asSequence()
            .filter { it.tag == "music" }
            .mapNotNull { _kuwoHotSong(it.attrs) }
            .distinctBy { it.id }
            .take(HomeSongsMax)
            .toList()
        if (songs.isEmpty()) continue
        songShelfIndex += 1
        val isNewSongRow = section.type == KW_SECTION_TAB_SCROLL
        out += HomeShelf.Songs(
            id = "kuwo:songs:$songShelfIndex",
            title = if (isNewSongRow) "新歌速递" else section.label.ifEmpty { "为你推荐" },
            // 混合型 section（`tab_scroll_list`，实测 label=「数字专辑」）把服务端的分类名放副标题
            subtitle = if (isNewSongRow) section.label.ifEmpty { "刚刚上架" } else "",
            songs = songs,
        )
    }

    // ── 歌单：**一个子分类一行**（服务端把 44 个歌单分在 5 个 sublist 下）──
    // 以前全部塞进一行「推荐歌单」，44 个不同口味挤在一起；现在按 sublist 的 name 分行。
    // banner / 编辑精选那几行已经进了置顶的轮播行，这里只处理子分类歌单墙。
    val seenPlaylist = HashSet<String>()
    var playlistRowIndex = 0
    for (section in sections) {
        if (section.type != KW_SECTION_SONGLIST_TAB) continue
        val grouped = LinkedHashMap<String, MutableList<HomeItem>>()
        for (item in section.items) {
            if (item.tag != "songlist") continue
            val card = _kuwoHotPlaylist(item.attrs) ?: continue
            val id = (card.target as? HomeTarget.Playlist)?.id ?: continue
            if (!seenPlaylist.add(id)) continue
            grouped.getOrPut(item.group) { mutableListOf() }.add(card)
        }
        for ((group, cards) in grouped) {
            if (cards.isEmpty()) continue
            playlistRowIndex += 1
            out += HomeShelf.Cards(
                id = "kuwo:playlist:$playlistRowIndex",
                title = group.ifEmpty {
                    section.label.ifEmpty {
                        if (section.type == KW_SECTION_BANNER) "精选歌单" else "推荐歌单"
                    }
                },
                // 子分类行把大类（section 的 label）放副标题，两行同名时不重复
                subtitle = if (group.isNotEmpty() && section.label.isNotEmpty() && section.label != group) {
                    section.label
                } else {
                    ""
                },
                // 用酷我自己的上限：一页给 44 个，套 Netease 那套 30 会白丢 14 个
                items = cards.take(KuwoPlaylistsMax),
            )
        }
    }

    // ── 新碟（`tab_scroll_list` 里的 `<album>`；听书专辑在 `scroll_square`，进不来）──
    var albumRowIndex = 0
    for (section in sections) {
        if (section.type != KW_SECTION_TAB_SCROLL) continue
        val items = section.items.asSequence()
            .filter { it.tag == "album" }
            .mapNotNull { _kuwoHotAlbum(it.attrs) }
            .distinctBy { it.key }
            .take(HomeCardsMax)
            .toList()
        if (items.isEmpty()) continue
        albumRowIndex += 1
        out += HomeShelf.Cards(
            id = "kuwo:newalbum:$albumRowIndex",
            title = "新专辑",
            // 服务端目前只给这一种专辑位（label=「数字专辑」），如实放副标题
            subtitle = section.label.ifEmpty { "刚刚发行" },
            items = items,
        )
    }

    return out
}

/**
 * 把两条 feed 的区块合起来：推荐流在前（歌曲行 + 44 张歌单墙），热门流补后面几行
 * （编辑精选 / 新歌 / 新碟）。**过去两条 feed 是互斥的备胎**，所以只要推荐流能取到，
 * 编辑精选和新碟那两行就永远不出现 —— 首页看着就比服务端实际给的内容少。
 *
 * 合并规则（都是纯函数，可单测）：
 * - 两条 feed 的卡片行 id 会撞（都叫 `kuwo:playlist`），撞了才给 [extra] 那行加 `:2` 后缀，
 *   让它和主 feed 的同一行**并排**出现而不是被去重掉；不撞就保留原 id；
 * - [extra] 的卡片行若与主 feed 同 id 且标题也一样，标题换成 [extraCardTitle]（否则两行同名）；
 * - 同一张卡（`HomeItem.key` 相同）不跨行重复出现；
 * - 歌曲行按标题去重：标题一样说明是同一个区块。
 */
internal fun mergeKuwoShelves(
    primary: List<HomeShelf>,
    extra: List<HomeShelf>,
    extraCardTitle: String = "编辑精选",
): List<HomeShelf> {
    if (extra.isEmpty()) return primary
    if (primary.isEmpty()) return extra

    val out = ArrayList<HomeShelf>(primary.size + extra.size)
    out += primary
    val satTitles = primary.filterIsInstance<HomeShelf.Songs>().map { it.title }.toHashSet()
    // 主 feed 已经用掉的卡片 key：跨行去重，避免同一张歌单出现两次
    val usedCardKeys = primary.filterIsInstance<HomeShelf.Cards>()
        .flatMap { shelf -> shelf.items.map { it.key } }
        .toHashSet()
    val primaryCardTitles = primary.filterIsInstance<HomeShelf.Cards>().associate { it.id to it.title }

    val usedIds = primary.mapTo(HashSet()) { it.id }

    /** id 撞了才加 `:2`、`:3`…（不撞就原样，UI 端按 id 做的记忆不受影响）。 */
    fun claimId(base: String): String {
        if (usedIds.add(base)) return base
        var n = 2
        while (true) {
            val candidate = "$base:$n"
            if (usedIds.add(candidate)) return candidate
            n += 1
        }
    }

    for (shelf in extra) {
        when (shelf) {
            is HomeShelf.Songs -> {
                if (!satTitles.add(shelf.title)) continue
                out += shelf.copy(id = claimId(shelf.id))
            }
            is HomeShelf.Cards -> {
                val items = shelf.items.filter { usedCardKeys.add(it.key) }
                if (items.isEmpty()) continue
                val title = if (primaryCardTitles[shelf.id] == shelf.title) {
                    extraCardTitle
                } else {
                    shelf.title
                }
                out += shelf.copy(id = claimId(shelf.id), title = title, items = items)
            }
        }
    }

    // 精选歌单（大图轮播）必须在最上面：它在热门流里，合并后本来会被推荐流的行挤到中间
    val hero = out.filter { it.id == KW_HERO_SHELF_ID }
    if (hero.isNotEmpty()) {
        out.removeAll(hero)
        out.addAll(0, hero)
    }
    return out
}

// ── 单条映射 ──
/**
 * `<music>` → [Song]。
 *
 * **必须复用 [_mapKuwoSong]**：它才知道 `MINFO` 怎么解析档位、封面怎么按 albumcover 规则提到
 * 800、多歌手怎么与 artistid 配对 —— 另写一份必然漏字段（历史上漏了 `qualities`，
 * 表现为「主页里的歌只有 128k」）。这里只做「XML 属性名 → 接口字段名」的转写：
 * `minfo → MINFO`、`n_minfo → N_MINFO`，其余同名。
 */
private fun _kuwoHotSong(attrs: Map<String, String>): Song? {
    val rid = attrs["rid"].orEmpty()
    val name = attrs["name"].orEmpty()
    if (rid.isEmpty() || name.isEmpty()) return null
    return kuwoMapSong(
        mapOf(
            "rid" to rid,
            "name" to name,
            "artist" to attrs["artist"].orEmpty(),
            "artistid" to attrs["artistid"].orEmpty(),
            "album" to attrs["album"].orEmpty(),
            "albumid" to attrs["albumid"].orEmpty(),
            "duration" to attrs["duration"].orEmpty(),
            "img" to attrs["img"].orEmpty(),
            "MINFO" to attrs["minfo"].orEmpty(),
            "N_MINFO" to attrs["n_minfo"].orEmpty(),
        ),
    )
}

/** `<songlist>` → 歌单卡（点进去仍是 `getKuwoPlaylist`，与其它入口同一条链路）。 */
private fun _kuwoHotPlaylist(
    attrs: Map<String, String>,
    style: HomeCardStyle = HomeCardStyle.Playlist,
): HomeItem? {
    val id = attrs["id"].orEmpty()
    val name = attrs["name"].orEmpty()
    if (id.isEmpty() || name.isEmpty()) return null
    val cover = _kuwoHomeCover(_kuwoFeedImage(attrs))
    val listen = attrs["listencnt"]?.toLongOrNull() ?: 0L
    return HomeItem(
        key = "kuwo:playlist:$id",
        title = name,
        subtitle = playlistSubtitle(listen, 0),
        cover = cover,
        badge = formatCount(listen).takeIf { it.isNotEmpty() },
        style = style,
        target = HomeTarget.Playlist(MusicPlatform.kuwo, id, name, cover),
    )
}

/** `<album>` → 专辑卡（点进去仍是 `getAlbumSongs`）。`releasedate` 已经是 `yyyy-MM-dd`。 */
private fun _kuwoHotAlbum(attrs: Map<String, String>): HomeItem? {
    val id = attrs["id"].orEmpty()
    val name = attrs["name"].orEmpty()
    if (id.isEmpty() || name.isEmpty()) return null
    val artist = attrs["artist"].orEmpty()
    val cover = _kuwoHomeCover(_kuwoFeedImage(attrs))
    return HomeItem(
        key = "kuwo:album:$id",
        title = name,
        subtitle = artist,
        cover = cover,
        style = HomeCardStyle.Album,
        pub = attrs["releasedate"].orEmpty().take(10),
        count = attrs["musiccnt"]?.toIntOrNull() ?: 0,
        target = HomeTarget.Album(MusicPlatform.kuwo, id, name, artist, cover),
    )
}

/**
 * 卡面封面统一 500：feed 给的是 300（168dp 卡在 3x 屏要 504px，300 会糊）。
 * 酷我 `albumcover` 目录只认 120/160/300/500/700，没有 800 之外的档位，故 500 是最优。
 */
private fun _kuwoHomeCover(url: String): String =
    if (url.isEmpty()) "" else url.replace("/300/", "/500/")

/**
 * feed 条目的图：`img` 是常规位，**运营位（`banner` 里 `statistics_id=text_ad_*` 那些）只有
 * `pic_full_size`**，没有 `img`。只读 `img` 会让整行运营卡变成无图占位。
 */
private fun _kuwoFeedImage(attrs: Map<String, String>): String =
    attrs["img"].orEmpty()
        .ifEmpty { attrs["pic_full_size"].orEmpty() }
        .ifEmpty { attrs["small_img"].orEmpty() }

// ── 属性解析 ──

/** 把一段 `<tag attr="v" …>` 里的属性收成 Map，值在此处解码实体。 */
private fun _kuwoAttrPairs(tagText: String): Map<String, String> {
    val out = HashMap<String, String>(8)
    for (m in KuwoAttrRe.findAll(tagText)) {
        out[m.groupValues[1]] = _kuwoDecodeAttr(m.groupValues[2])
    }
    return out
}

/**
 * XML 属性值里的实体解码。
 *
 * 数字实体必须处理：专辑名里真的会出现 `&#34;`（`By My Side (From &#34;XO, Kitty&#34;)`），
 * 不解就是一个引号乱码。`&amp;` 放最后 —— 否则 `&amp;lt;` 会被解成 `<`。
 */
internal fun _kuwoDecodeAttr(raw: String): String {
    if (raw.indexOf('&') < 0) return raw
    var s = raw
    if (s.contains("&#")) {
        s = KuwoNumericEntityRe.replace(s) { m ->
            val code = m.groupValues[1].toIntOrNull()
            if (code == null || code !in 0..0x10FFFF) m.value else String(Character.toChars(code))
        }
    }
    return s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
}
