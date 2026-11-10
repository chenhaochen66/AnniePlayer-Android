package com.annieplayer.annie_music.kt.data.favorites

import com.annieplayer.annie_music.kt.data.model.MusicPlatform

// ================================================================================================
// 本地收藏的失联自愈
// ================================================================================================
//
// 本地专辑 / 歌手以 **tag 分组键** 为键（理由见 `FavoriteKeys`），所以用户改了 tag 之后
// 旧键就指向不存在的东西。每条本地收藏额外存了一份 `paths`，打开收藏库时：
//
//   用键查当前索引
//   ├─ 还在   → 不动
//   └─ 失联   → 用 paths 反查，找到就把键改写成新的（自愈）
//               全找不到就标记失效（灰显「文件已丢失」），**记录保留** ——
//               用户可能只是拔了 SD 卡，或正在整理文件。
//
// 这里全是纯函数，`albumKeyOf` / `artistsOf` 由调用方注入（生产环境查 `LocalLibraryStore`
// 的内存索引，测试里给个 Map 就行）。

/** 自愈后的结果：`null` = 不用改；非 null = 用返回值替换原条目。 */
object FavoriteSelfHeal {

    /**
     * 本地专辑：键失联时用 `paths` 找回。
     *
     * @param albumKeyOf 路径 → 它**当前**所属的专辑键（`albumGroupKey`）；查不到给 null
     */
    fun healAlbum(fav: FavAlbum, albumKeyOf: (String) -> String?): FavAlbum? {
        if (fav.platform != MusicPlatform.local || fav.paths.isEmpty()) return null
        val current = fav.paths.firstNotNullOfOrNull(albumKeyOf) ?: return null
        if (current == fav.key) return null
        return fav.copy(key = current)
    }

    /**
     * 本地歌手：键失联时用 `paths` 找回。
     *
     * @param artistKeysOf 路径 → 它**当前**所属的歌手键列表（多歌手逐个列出）
     */
    fun healArtist(fav: FavArtist, artistKeysOf: (String) -> List<String>): FavArtist? {
        if (fav.platform != MusicPlatform.local || fav.paths.isEmpty()) return null
        val candidates = fav.paths.flatMap(artistKeysOf).distinct()
        if (candidates.isEmpty()) return null
        // 名字本身还在就用它（用户只改了别的歌的 tag）；否则取第一条能命中的
        val current = candidates.firstOrNull { it == fav.key } ?: candidates.first()
        if (current == fav.key) return null
        return fav.copy(key = current)
    }

    /** 本地专辑还剩几首文件在库里（0 = 全部丢失，UI 灰显）。 */
    fun aliveTrackCount(fav: FavAlbum, albumKeyOf: (String) -> String?): Int =
        if (fav.platform != MusicPlatform.local) -1
        else fav.paths.count { albumKeyOf(it) != null }

    /** 本地歌手还剩几首文件在库里（0 = 全部丢失，-1 = 不是本地条目）。 */
    fun aliveTrackCount(fav: FavArtist, artistKeysOf: (String) -> List<String>): Int =
        if (fav.platform != MusicPlatform.local) -1
        else fav.paths.count { artistKeysOf(it).isNotEmpty() }
}
