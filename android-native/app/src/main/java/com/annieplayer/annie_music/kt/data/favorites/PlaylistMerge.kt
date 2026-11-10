package com.annieplayer.annie_music.kt.data.favorites

import com.annieplayer.annie_music.kt.data.model.Song

// ================================================================================================
// 歌单合并（纯函数，可单测）
// ================================================================================================
//
// 从链接 / 收藏歌单往自建歌单里导入时用。三条语义：
//   1. **追加**，不覆盖 —— 用户可能先手动加了几首，导入不该把它们冲掉；
//   2. **按 `songKey` 去重** —— 同一个歌单导入两次不该翻倍；
//   3. **保持顺序** —— 先已有的（用户手动排过的），再新导入的（歌单原顺序）。
//
// 为什么不用 `addSongToUserPlaylist` 逐首加：那是「单行 INSERT + 每次 reload()」，
// 导一个 800 首的歌单会变成 800 次写盘 + 800 次全量重读。这里算好整份，
// 一次 `saveUserPlaylist`（一个事务）写回。

/**
 * 把 [incoming] 追加到 [existing] 后面，按 [FavoriteKeys.song] 去重。
 *
 * `incoming` 内部重复的也去掉（同一个歌单里偶尔会有同 id 的两条）。
 */
fun mergePlaylistSongs(existing: List<Song>, incoming: List<Song>): List<Song> {
    if (incoming.isEmpty()) return existing
    if (existing.isEmpty()) return incoming.distinctBy { FavoriteKeys.song(it) }
    val seen = existing.mapTo(HashSet(), FavoriteKeys::song)
    return existing + incoming.filter { seen.add(FavoriteKeys.song(it)) }
}
