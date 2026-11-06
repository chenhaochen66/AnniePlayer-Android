package com.annieplayer.annie_music.kt.data.local.tag

import com.annieplayer.annie_music.kt.data.model.Song

/**
 * `Song` → 标签字段的**纯映射**（不碰文件、不碰网络）。
 *
 * 抽出来的理由很实在：这就是「下载下来的文件里到底有什么」的唯一真相，
 * 而它以前埋在 `AppleDownloader` 的私有方法里，只能靠真机下载 + 人工看结果验证。
 * 曾经因此漏过 `copyright`（`Song` 干脆没这个字段），文件里就一直缺版权。
 *
 * 现在它是纯函数 → JVM 单测可以直接断言「这 13 个字段一个都不能少」。
 */
internal object TagMapper {

    /** 多歌手的统一连接符。 */
    const val ARTIST_JOINER: String = " / "

    /**
     * 写标签用的「艺术家」文本：**多歌手一律 `A / B`**。
     *
     * 优先用结构化列表 [names]；没有列表才原样用平台给的合并串 [fallback]。
     * 刻意**不按分隔符去拆** [fallback]：各平台连接符会出现在歌手名里
     * （Apple 的「Lucas & Steve」、`AC/DC`、「Simon & Garfunkel」），拆了就是假歌手。
     * 要拆也只能由「拿到结构」的那一层去拆。
     */
    fun artistOf(names: List<String>, fallback: String): String {
        val clean = names.map { it.trim() }.filter { it.isNotEmpty() }
        return if (clean.isNotEmpty()) clean.joinToString(ARTIST_JOINER) else fallback
    }

    /**
     * @param song 平台给到的歌曲；`copyright` 对 Apple 需要在调用前从专辑层补好
     * @param lyric 已经合并好的歌词文本（原文 / 译文 / 逐字合成后的那一份）
     */
    fun ofSong(song: Song, lyric: String?): Map<TagKey, String> {
        val map = LinkedHashMap<TagKey, String>()
        fun put(key: TagKey, value: String?) {
            if (!value.isNullOrEmpty()) map[key] = value
        }
        put(TagKey.Title, song.title)
        put(TagKey.Artist, artistOf(song.artistNames, song.artist))
        put(TagKey.Album, song.album)
        put(TagKey.AlbumArtist, song.albumArtist)
        put(TagKey.Track, song.trackNumber)
        put(TagKey.Disc, song.discNumber)
        put(TagKey.Year, song.releaseDate)
        put(TagKey.Composer, song.composer)
        put(TagKey.Genre, song.genre)
        put(TagKey.Isrc, song.isrc)
        put(TagKey.Copyright, song.copyright)
        // 「版本说明」进 comment（Qobuz 的 "Remastered 2011" 之类）；Apple 没有这个字段
        put(TagKey.Comment, song.version)
        put(TagKey.Lyric, lyric)
        return map
    }
}
