package com.annieplayer.annie_music.kt.data

import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.sources.getKugouPlaylist
import com.annieplayer.annie_music.kt.data.sources.getKuwoPlaylist
import com.annieplayer.annie_music.kt.data.sources.getNeteasePlaylist
import com.annieplayer.annie_music.kt.data.sources.getQQPlaylist

/**
 * 歌单链接解析与分发（1:1 移植 Flutter 数据层 playlist_service.dart，对齐 RN 版 services/playlistService.ts）。
 *
 * [parsePlaylistUrl] 从混合文本中识别酷我 / 网易云 / QQ / 酷狗的分享链接或纯 ID；
 * [fetchPlaylist] 按平台分发到各平台已有的歌单接口（本文件不重复实现平台函数）。
 */

/** 歌单获取结果（对齐 Dart `PlaylistResult`） */
data class PlaylistResult(
    val name: String,
    val creator: String,
    val cover: String,
    val platform: MusicPlatform,
    val songs: List<Song>,
)

/** 歌单链接解析结果（对齐 Dart 返回记录 `({MusicPlatform platform, String id})`） */
data class PlaylistRef(
    val platform: MusicPlatform,
    val id: String,
)

object PlaylistService {
    private val _reUrl by lazy { Regex("https?://\\S+") }
    private val _reKuwo by lazy { Regex("kuwo\\.cn/.*?playlist_detail/(\\d+)") }
    private val _reNetease by lazy { Regex("music\\.163\\.com/.*[?&]id=(\\d+)") }
    private val _reQqId by lazy { Regex("[^.]*\\.?y\\.qq\\.com/.*[?&]id=(\\d+)") }
    private val _reQqPath by lazy { Regex("[^.]*\\.?y\\.qq\\.com/.*/playlist/(\\d+)") }
    private val _reKugouGcid by lazy { Regex("kugou\\.com/.*gcid_([a-z0-9]+)", RegexOption.IGNORE_CASE) }
    private val _reKugouSpecial by lazy { Regex("kugou\\.com/(yy/)?special/single/(\\d+)") }
    private val _reDigits by lazy { Regex("^\\d+$") }
    private val _reGcid by lazy { Regex("gcid_([a-z0-9]+)", RegexOption.IGNORE_CASE) }
    /**
     * 从文本中解析歌单链接，返回 [PlaylistRef]（平台 + id）；无法识别返回 null。
     *
     * 支持从「分享歌单: xxx https://...」这类混合文本中提取首个 URL。
     */
    fun parsePlaylistUrl(text: String): PlaylistRef? {
        // 从混合文本中提取 URL
        val urlMatch = _reUrl.find(text.trim())
        val raw = (urlMatch?.value ?: text.trim()).split("?")[0]
        val full = urlMatch?.value ?: text.trim()

        // 酷我：kuwo.cn/.../playlist_detail/数字
        val kuwo = _reKuwo.find(raw)
        if (kuwo != null) return PlaylistRef(MusicPlatform.kuwo, kuwo.groupValues[1])

        // 网易云：music.163.com/.../playlist?id=数字
        val netease = _reNetease.find(full)
        if (netease != null) return PlaylistRef(MusicPlatform.netease, netease.groupValues[1])

        // QQ：y.qq.com 域名下 id=数字
        val qqId = _reQqId.find(full)
        if (qqId != null) return PlaylistRef(MusicPlatform.qq, qqId.groupValues[1])
        val qqPath = _reQqPath.find(raw)
        if (qqPath != null) return PlaylistRef(MusicPlatform.qq, qqPath.groupValues[1])

        // 酷狗：kugou.com 含 gcid_
        val kugouGcid = _reKugouGcid.find(full)
        if (kugouGcid != null) {
            return PlaylistRef(MusicPlatform.kugou, "gcid_${kugouGcid.groupValues[1]}")
        }
        val kugouSpecial = _reKugouSpecial.find(raw)
        if (kugouSpecial != null) {
            return PlaylistRef(MusicPlatform.kugou, kugouSpecial.groupValues[2])
        }

        // 纯 ID 识别
        val clean = text.trim()
        if (_reDigits.matches(clean)) return PlaylistRef(MusicPlatform.netease, clean)
        if (clean.lowercase().contains("gcid_")) {
            val g = _reGcid.find(clean)?.groupValues?.get(1) ?: clean
            return PlaylistRef(MusicPlatform.kugou, "gcid_$g")
        }

        return null
    }

    /**
     * 按平台拉取歌单；平台不支持或拉取失败返回 null。
     *
     * 复用各平台既有实现：酷我 [getKuwoPlaylist]、网易云 [getNeteasePlaylist]、
     * QQ [getQQPlaylist]、酷狗 [getKugouPlaylist]。
     */
    suspend fun fetchPlaylist(platform: MusicPlatform, id: String): PlaylistResult? = when (platform) {
        MusicPlatform.kuwo -> getKuwoPlaylist(id)?.let { result ->
            PlaylistResult(
                name = result.name,
                creator = result.creator ?: "",
                cover = result.cover,
                platform = MusicPlatform.kuwo,
                songs = result.songs,
            )
        }
        MusicPlatform.netease -> getNeteasePlaylist(id)?.let { result ->
            PlaylistResult(
                name = result.name,
                creator = result.creator ?: "",
                cover = result.cover,
                platform = MusicPlatform.netease,
                songs = result.songs,
            )
        }
        MusicPlatform.qq -> getQQPlaylist(id)?.let { result ->
            PlaylistResult(
                name = result.name,
                creator = result.creator ?: "",
                cover = result.cover,
                platform = MusicPlatform.qq,
                songs = result.songs,
            )
        }
        MusicPlatform.kugou -> getKugouPlaylist(id)?.let { result ->
            PlaylistResult(
                name = result.name,
                creator = result.creator ?: "",
                cover = result.cover,
                platform = MusicPlatform.kugou,
                songs = result.songs,
            )
        }
        // 对齐 Dart `default: return null`（kkbox / qobuz 不支持歌单链接）
        MusicPlatform.kkbox,
        MusicPlatform.qobuz,
        MusicPlatform.apple,
        -> null
    }
}
