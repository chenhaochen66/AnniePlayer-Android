package com.annieplayer.annie_music.kt.core.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.annieplayer.annie_music.kt.data.model.Album
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song

/** Apple 发行物的两段：「全部专辑」页只进对应一段，而不是两段一起看。 */
enum class AppleReleaseSection { singles, albums }

/**
 * 把 [incoming] 按 id 追加到 [existing] 尾部（去重、保序）。
 *
 * **只追加、不重排**：列表边拉边变长时滚动位置不动，overview 的「最热门」前 20 首也不变。
 * 续拉的第一批会与已加载的第 1 页重叠（页大小 20 vs 平台批大小 100/200/1000），去重就是干这个的。
 */
internal fun mergeSongs(existing: List<Song>, incoming: List<Song>): List<Song> {
    if (incoming.isEmpty()) return existing
    if (existing.isEmpty()) return incoming.distinctBy { it.id }
    val seen = existing.mapTo(HashSet(existing.size)) { it.id }
    val out = ArrayList<Song>(existing.size + incoming.size)
    out.addAll(existing)
    for (song in incoming) {
        if (seen.add(song.id)) out.add(song)
    }
    return out
}

/**
 * 一位歌手的详情数据（`SingerDetailCache` 的条目）。
 *
 * 为什么单独抽出来：overview / 全部单曲 / 全部专辑现在是**三个栈内页面**，各有自己的
 * 组合实例。状态若还和组合实例绑定，从 overview 点「全部」就会把歌手单曲再拉一遍
 * （KKBOX 那条要扫 800 条搜索索引 ≈ 7 秒）。所以**数据**按歌手共享、**页面形态**由路由决定。
 *
 * 字段分两类，混在一起要清楚：
 * - 数据类（歌曲 / 专辑 / 头像 / 简介）：三页共享，谁先拿到谁写；
 * - 交互类（多选 / 已选 / 批量下载进度）：只有「全部单曲」页会碰，退栈时那页自己清。
 *
 * ## 歌曲列表是「一直在变长的」
 * overview 只拉第 1 页（20 首）当「最热门」预览；**只有进了「全部单曲」页才会续拉**，
 * 一批一批追加到 [songs] 尾部。所以：
 * - [songs] 的顺序永远是平台原始顺序，前 20 首恒等于 overview 的「最热门」；
 * - [songLoadedThrough] 是续拉游标（[com.annieplayer.annie_music.kt.data.model.ArtistSongsBatch]
 *   的 offset）。退出页面只取消 job、不清进度，下次进来从这里接着拉；
 * - [songTotal] 是「已知总数」的下界：平台给了就是真值，没给（Apple）就跟着 [songs] 长。
 */
internal class SingerDetailCacheState {
    var songs by mutableStateOf<List<Song>>(emptyList())

    /** 已知曲目总数（0 = 未知）；只用来显示「共 N 首」，不参与终止判断 */
    var songTotal by mutableStateOf(0)

    /** 续拉游标：下一次 [com.annieplayer.annie_music.kt.data.MusicRepository.fetchArtistSongsBatch] 的 offset */
    var songLoadedThrough by mutableStateOf(0)

    /** 已经拉到最后一首（续拉循环正常退出） */
    var songComplete by mutableStateOf(false)

    /** 只有**首屏那一页**会置 true（整页转圈）；续拉不置，否则列表会消失 */
    var songLoading by mutableStateOf(false)

    var songError by mutableStateOf("")

    var albums by mutableStateOf<List<Album>>(emptyList())
    var albumLoading by mutableStateOf(false)

    /**
     * 专辑是否**已经拉过一次**（空结果也算）。
     *
     * 不能只看 `albums.isEmpty()`：没有专辑的歌手会变成「每次进页面再拉一次」，
     * 而 Apple 的发行物接口是串行会话，多拉一次就是多等一次。
     */
    var albumsLoaded by mutableStateOf(false)
    var albumSortAsc by mutableStateOf(false)

    /** Apple：专辑与 EP/单曲分开显示 */
    var appleAlbums by mutableStateOf<List<Album>>(emptyList())
    var appleSingles by mutableStateOf<List<Album>>(emptyList())

    var avatarUrl by mutableStateOf("")

    /** 歌手简介（各平台从已有的歌手接口顺带取；为空则整段不显示） */
    var bio by mutableStateOf("")

    val selectedSongs = mutableStateListOf<Song>()

    /** 多选模式显式开关（长按行或头部「多选」chip 进入；「取消」/ 批量下载完成后退出）。 */
    var selectMode by mutableStateOf(false)

    var batchPickerVisible by mutableStateOf(false)
    var batchDownloading by mutableStateOf(false)

    /** 批量下载进度文案（`MultiSelectDownloadBar` 下载中展示，形如 `3/12 歌名`）。 */
    var batchProgress by mutableStateOf("")
}

/**
 * 歌手详情的进程级数据缓存：按 `platform:id` 复用，**最多留 [MaxCached] 位歌手**。
 *
 * 只缓存列表级数据（一页歌曲 + 专辑 + 头像 + 简介），几十 KB 量级；按访问顺序 LRU 淘汰，
 * 避免用户在搜索结果里一路点过去把歌手数据攒成内存泄漏。
 *
 * 不做失效：歌手详情本来就是网络数据，下一次进来重新拉新数据反而是对的；
 * 这个缓存只为「同一次浏览里 overview 与两个子页共享同一份」。
 */
internal object SingerDetailCache {

    private const val MaxCached = 6

    private val entries = object : LinkedHashMap<String, SingerDetailCacheState>(8, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, SingerDetailCacheState>,
        ): Boolean = size > MaxCached
    }

    fun of(platform: MusicPlatform, id: String): SingerDetailCacheState {
        val key = "${platform.name}:$id"
        entries[key]?.let { return it }
        return SingerDetailCacheState().also { entries[key] = it }
    }
}
