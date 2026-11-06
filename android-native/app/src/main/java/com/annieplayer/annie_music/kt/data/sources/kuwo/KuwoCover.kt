package com.annieplayer.annie_music.kt.data.sources.kuwo

import com.annieplayer.annie_music.kt.data.model.Album
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.sources.common.firstTruthy

// ================================================================================================
// 酷我封面（统一规整成 800px）
// ================================================================================================
//
// [buildAlbumCoverUrl] 虽然住在酷我包里，但它是**各平台通用**的封面选择器
// （按 `album.platform` 分支），所以不放进 common：它依赖酷我的 `/star/albumcover/` 规则。

private val _reLeadingDigits by lazy { Regex("^\\d+/") }
private val _reAlbumCover by lazy { Regex("/star/albumcover/\\d+/(.+)") }

/** 依据搜索条目构建 800px 封面地址（对齐 RN buildCoverUrl） */
fun buildCoverUrl(item: Map<String, Any?>): String {
    val raw = kuwoStrOr(
        firstTruthy(
            listOf(
                item["web_albumpic_short"],
                item["hts_img"],
                item["img"],
                item["pic"],
                item["web_artistpic_short"],
            ),
        ),
        "",
    )
    if (raw.isEmpty()) return ""
    if (_reLeadingDigits.containsMatchIn(raw)) {
        return "http://img1.kuwo.cn/star/albumcover/800/" +
            _reLeadingDigits.replaceFirst(raw, "")
    }
    if (raw.startsWith("http")) {
        val m = _reAlbumCover.find(raw)
        if (m != null) {
            return "http://img1.kuwo.cn/star/albumcover/800/${m.groupValues[1]}"
        }
    }
    return raw
}

/** 依据专辑构建大图封面地址（各平台规则不同） */
fun buildAlbumCoverUrl(album: Album): String = when (album.platform) {
    MusicPlatform.qq -> {
        val raw = kuwoStrOr(firstTruthy(listOf(album.pic, album.img)), "")
        if (raw.startsWith("http")) {
            raw
        } else if (album.albumid.isEmpty()) {
            ""
        } else {
            "https://y.gtimg.cn/music/photo_new/T002R800x800M000${album.albumid}.jpg"
        }
    }
    MusicPlatform.netease -> {
        // 搜索接口直接给 picUrl
        kuwoStrOr(firstTruthy(listOf(album.pic, album.img)), "")
    }
    MusicPlatform.qobuz -> kuwoStrOr(firstTruthy(listOf(album.pic, album.img)), "")
    MusicPlatform.kugou -> {
        // imgurl 带 {size} 占位符
        val raw = kuwoStrOr(firstTruthy(listOf(album.pic, album.img)), "")
        raw.replace("{size}", "480")
    }
    MusicPlatform.kuwo,
    MusicPlatform.kkbox,
    MusicPlatform.qobuz,
    MusicPlatform.apple,
    MusicPlatform.local,
    -> _buildKuwoAlbumCoverUrl(album)
}

/** 酷我专辑 800px 封面（对齐 RN buildAlbumCoverUrl） */
private fun _buildKuwoAlbumCoverUrl(album: Album): String {
    val raw = kuwoStrOr(firstTruthy(listOf(album.pic, album.htsImg, album.img)), "")
    if (raw.isEmpty()) return ""
    if (_reLeadingDigits.containsMatchIn(raw)) {
        return "http://img1.kuwo.cn/star/albumcover/800/" +
            _reLeadingDigits.replaceFirst(raw, "")
    }
    if (raw.startsWith("http")) {
        val m = _reAlbumCover.find(raw)
        if (m != null) {
            return "http://img1.kuwo.cn/star/albumcover/800/${m.groupValues[1]}"
        }
    }
    return raw
}
