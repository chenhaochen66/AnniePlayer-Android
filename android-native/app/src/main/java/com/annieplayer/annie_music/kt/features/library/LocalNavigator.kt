package com.annieplayer.annie_music.kt.features.library

import com.annieplayer.annie_music.kt.data.local.LocalSection
import com.annieplayer.annie_music.kt.data.local.LocalTrackMeta
import com.annieplayer.annie_music.kt.data.local.localAlphabetComparator

/**
 * 本地曲库专用的排序工具。
 *
 * > 这里原本还有一套 `LocalRoute` / `LocalNavEntry`（本地自己的页面栈），在本地页面
 * > 并入全局页面栈之后就没人用了，已删除。文件里只留下两个排序函数。
 */

// ── 各页共用的排序（用户要求：专辑按 CD 曲目号，没有号就按字母） ──

/** 专辑内排序：`CD 号 → 曲目号 → 标题`；没有曲目号的排在最后、按标题字母序（中文按拼音） */
internal fun sortAlbumTracks(tracks: List<LocalTrackMeta>): List<LocalTrackMeta> {
    val text = localAlphabetComparator()
    return tracks.sortedWith(
        compareBy<LocalTrackMeta> { it.disc ?: 1 }
            .thenBy { it.track ?: Int.MAX_VALUE }
            .thenComparator { a, b -> text.compare(a.title, b.title) },
    )
}

/** 歌手内排序：`专辑名 → CD 号 → 曲目号 → 标题`（按专辑分段展示） */
internal fun sortArtistTracks(tracks: List<LocalTrackMeta>): List<LocalTrackMeta> {
    val text = localAlphabetComparator()
    return tracks.sortedWith(
        Comparator<LocalTrackMeta> { a, b -> text.compare(a.album, b.album) }
            .thenBy { it.disc ?: 1 }
            .thenBy { it.track ?: Int.MAX_VALUE }
            .thenComparator { a, b -> text.compare(a.title, b.title) },
    )
}
