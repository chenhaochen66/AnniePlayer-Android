package com.annieplayer.annie_music.kt.data.model

/**
 * 流媒体主页（`HomeFeed`）的数据模型。
 *
 * ## 为什么要独立一套，而不复用 [Album] / [Playlist]
 * 主页拿到的是「展示用的最小集」：标题、副标题、封面，外加一个「点进去是谁」的目标。
 * 如果为了主页先去请求专辑详情 / 歌单详情，一次首屏就要发十几条请求；而各平台的详情接口
 * 都需要**另一种 id**（QQ 专辑只认 `albummid`、歌单只认数字 `disstid`），从展示字段反推
 * 反而更容易出错。因此这里把「能点」表达成 [HomeTarget]，由 UI 层直接翻译成
 * `AlbumRoute` / `SingerRoute` / `PlaylistRoute` —— 详情页仍然走既有实现。
 *
 * ## 为什么 [HomeShelf] 是 sealed
 * 主页有且只有两种区块形态：
 * - [HomeShelf.Songs]：歌曲整行翻页（与歌手详情「最热门」同一套 5 行 × 最多 4 页）；
 * - [HomeShelf.Cards]：横向封面大卡。
 *
 * 两种形态的 `items` 类型完全不同（`List<Song>` vs `List<HomeItem>`），用 sealed 表达比
 * 「一个类里放两个可为空的列表 + 一个 kind 枚举」更难写错。
 */

/** 卡片点进去的目标；UI 层据此构造详情栈路由。 */
sealed interface HomeTarget {

    /** 所属平台（详情页按它分发接口） */
    val platform: MusicPlatform

    /**
     * 专辑。
     *
     * [id] 必须已经是**该平台详情接口认的那个 id**：QQ 是 `albummid`（不是数字 album id），
     * 网易云是数字 album id。
     */
    data class Album(
        override val platform: MusicPlatform,
        val id: String,
        val name: String,
        val artist: String,
        val cover: String,
    ) : HomeTarget

    /**
     * 歌手。
     *
     * [id] 同样是详情接口认的 id：QQ 是 `singerMid`，网易云是数字 artist id。
     */
    data class Artist(
        override val platform: MusicPlatform,
        val id: String,
        val name: String,
        val picture: String,
    ) : HomeTarget

    /** 歌单（QQ 是数字 `disstid`，网易云是数字 playlist id）。 */
    data class Playlist(
        override val platform: MusicPlatform,
        val id: String,
        val name: String,
        val cover: String,
    ) : HomeTarget

    /**
     * 流派（酷狗的「按风格听」）。
     *
     * 不带曲目：风格卡点进去是**二级页面**，由那一页按 [tagIds] 现拉 30 首。
     * 主页一次列 18 个流派，若预先取曲目就是 18 次请求 —— 这正是不预取的理由。
     */
    data class Style(
        override val platform: MusicPlatform,
        val tagIds: String,
        val title: String,
    ) : HomeTarget
}

/**
 * 卡片形态。
 *
 * 主页的卡片**不是一种**：歌单是「封面即海报」的大卡，专辑是「唱片条」，歌手是圆形头像。
 * 用独立枚举而不是从 [HomeTarget] 的类型反推，是因为两者的生命周期不同 —— 以后可能出现
 * 「同样点进歌单、但想用小卡」的情形（比如某平台的榜单），样式不该被目标类型锁死。
 */
enum class HomeCardStyle {
    /** 封面即海报的大卡：标题压在封面下缘。 */
    Playlist,

    /** 横向「唱片条」：左侧方形封面 + 右侧 ALBUM 微标签 / 专辑名 / 歌手。 */
    Album,

    /** 圆形头像卡：适合歌手。 */
    Artist,

    /**
     * 流派卡：没有封面图（平台不给风格图），用**渐变色块 + 风格名**表达。
     *
     * 与歌单卡同样大小、同样圆角，所以这一行和「新专辑」「推荐歌单」在版式上是齐的，
     * 只是内容是"可选的流派"而不是"可播的内容"。
     */
    Style,

    /**
     * 精选大图轮播卡（目前只有酷我「精选歌单」用）：
     * 卡片接近满宽、封面**完整显示**（不裁剪）、整行自动循环翻动。
     *
     * 为什么单独一种 style 而不是给歌单卡加参数：酷我 banner 位给的是运营宽图
     * （`star/upload/ecom` 目录下那批），塞进 168dp 方图会被裁掉大半；而其它平台的歌单封面
     * 是方图，用方图铺满才对。两种口径必须分开。
     */
    Hero,
}

/**
 * 一个横向卡片条目。
 *
 * @param key 区块内唯一（`LazyRow` 的 key）；同一条目出现在两个区块里也必须不同，
 *            所以约定为「{平台}:{类型}:{id}」
 * @param subtitle 第二行文案（歌手名 / 播放量之类，可能为空串）
 * @param badge 封面右上角的小角标（播放量 / 发行日期），null 表示不画
 * @param style 渲染形态（见 [HomeCardStyle]）
 * @param pub 发行日期（专辑卡右下角那行小字，`yyyy-MM-dd`；拿不到就是空串）
 * @param count 曲目数（专辑卡的 `N首`；0 表示拿不到，UI 据此不画那一段）
 */
data class HomeItem(
    val key: String,
    val title: String,
    val subtitle: String = "",
    val cover: String = "",
    val badge: String? = null,
    val style: HomeCardStyle = HomeCardStyle.Playlist,
    val pub: String = "",
    val count: Int = 0,
    val target: HomeTarget? = null,
)

/** 主页的一个区块。 */
sealed interface HomeShelf {

    /** 区块唯一 id（`LazyColumn` 的 key / 进入动画的 remember 锚点） */
    val id: String

    /** 主标题（如「新歌速递」） */
    val title: String

    /** 副标题（灰色小字，可为空串） */
    val subtitle: String

    /** 歌曲区块：横向翻页的整行列表。 */
    data class Songs(
        override val id: String,
        override val title: String,
        override val subtitle: String = "",
        val songs: List<Song>,
    ) : HomeShelf

    /** 卡片区块：横向封面大卡。 */
    data class Cards(
        override val id: String,
        override val title: String,
        override val subtitle: String = "",
        val items: List<HomeItem>,
    ) : HomeShelf
}
