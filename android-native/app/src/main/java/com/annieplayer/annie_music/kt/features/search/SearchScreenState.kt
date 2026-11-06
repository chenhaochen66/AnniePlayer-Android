package com.annieplayer.annie_music.kt.features.search

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.annieplayer.annie_music.kt.core.state.AppRoute
import com.annieplayer.annie_music.kt.core.state.DownloadViewModel
import com.annieplayer.annie_music.kt.core.state.KkboxRoute
import com.annieplayer.annie_music.kt.core.state.PlayerViewModel
import com.annieplayer.annie_music.kt.core.state.QobuzRoute
import com.annieplayer.annie_music.kt.core.state.SearchSelectionViewModel
import com.annieplayer.annie_music.kt.data.model.Album
import com.annieplayer.annie_music.kt.data.model.Artist
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.searchAlbumByPlatform
import com.annieplayer.annie_music.kt.data.searchArtistByPlatform
import com.annieplayer.annie_music.kt.data.sources.apple.AppleStorefront
import com.annieplayer.annie_music.kt.data.sources.apple.AppleMusicSettings
import com.annieplayer.annie_music.kt.data.sources.apple.appleMusicStorefront
import com.annieplayer.annie_music.kt.data.sources.apple.fetchAppleStorefronts
import com.annieplayer.annie_music.kt.data.sources.apple.searchAppleMusic
import com.annieplayer.annie_music.kt.data.sources.kkbox.parseKkboxUrl
import com.annieplayer.annie_music.kt.data.sources.qobuz.parseQobuzUrl
import com.annieplayer.annie_music.kt.data.sources.kugou.searchKugouMusic
import com.annieplayer.annie_music.kt.data.sources.kuwo.searchMusic
import com.annieplayer.annie_music.kt.data.sources.netease.searchNeteaseMusic
import com.annieplayer.annie_music.kt.data.sources.qq.searchQQMusic
import com.annieplayer.annie_music.kt.data.sources.qobuz.searchQobuzTracks
import com.annieplayer.annie_music.kt.widgets.SongSearchColumn
import com.annieplayer.annie_music.kt.widgets.ModeDropdownMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 首页状态与业务逻辑（从 `HomeScreen.kt` 抽出，缓解单文件体量）。
 *
 * 对齐 Dart `_HomeScreenState` 字段 + 各 handler；所有字段为 Compose 状态，供 `HomeScreen`
 * 以 `with(state) { ... }` 直接读写（等价原先的 `by remember { mutableStateOf(...) }`）。
 */
@Stable
class SearchScreenState(
    private val scope: CoroutineScope,
    private val playerViewModel: PlayerViewModel,
    private val downloadViewModel: DownloadViewModel,
    private val selectionViewModel: SearchSelectionViewModel,
    private val snackbarHostState: SnackbarHostState,
    /**
     * 打开一个子页面：把路由压到全局 `pageStack`。
     *
     * 之所以用回调而不是直接持有 `UiViewModel`：这个类只负责「解析出要去哪」，
     * 往哪压栈是宿主的决定（和 `SongActionsSheet` 只发回调、由 `DetailNavigator` 压栈同一思路）。
     */
    private val onOpenRoute: (AppRoute) -> Unit,
) {
    // ── 搜索状态 ──
    var mode by mutableStateOf(ModeDropdownMode.song)
    var modePickerVisible by mutableStateOf(false)
    var queryText by mutableStateOf("")
    var songSubmitted by mutableStateOf("")
    var albumSubmitted by mutableStateOf("")
    var artistSubmitted by mutableStateOf("")

    /** 单曲搜索结果（**单栏**：一次只展示一个平台），随平台切换整列替换 */
    var songColumn by mutableStateOf<SongSearchColumn?>(null)

    /**
     * 单曲搜索代次：每次 [startSongSearch]（提交搜索 / 切换平台）单调自增。
     * 对外暴露给 UI 作为 SongSearchResults 的 searchToken —— 同词同平台重搜时
     * query 与平台均不变，靠它触发结果列的分页状态重置。
     */
    var songGeneration by mutableIntStateOf(0)

    /**
     * 搜索平台：**单曲 / 专辑 / 歌手三种模式共用这一个**。
     *
     * - 切换模式不动它；在「搜索选择」面板换平台，三种模式一起换；
     * - **落盘**（[SearchSettings]）：退出再打开还是上次选的平台，不再回到默认酷我。
     */
    var searchPlatform by mutableStateOf(SearchSettings.platform)
        private set

    /**
     * 专辑 / 歌手结果是用哪个平台拉的。
     *
     * 平台共用后，切模式时可能出现「平台换了、这个模式的结果还是旧平台拉的」，
     * 靠它判断要不要重拉（null = 这个模式还没有结果）。单曲侧看 `songColumn.platform` 即可。
     */
    private var albumQueryPlatform by mutableStateOf<MusicPlatform?>(null)
    private var artistQueryPlatform by mutableStateOf<MusicPlatform?>(null)

    var albums by mutableStateOf<List<Album>>(emptyList())
    var albumsLoading by mutableStateOf(false)
    var albumGeneration by mutableIntStateOf(0)

    var artists by mutableStateOf<List<Artist>>(emptyList())
    var artistsLoading by mutableStateOf(false)
    var artistGeneration by mutableIntStateOf(0)

    // ── Apple Music 单曲搜索（单栏 + 国家选择） ──
    var appleCountry by mutableStateOf(appleMusicStorefront)
    var appleStorefronts by mutableStateOf<List<AppleStorefront>>(emptyList())
    var appleStorefrontsLoading by mutableStateOf(false)
    /** 地区表加载失败的原因（非空时面板显示失败 + 重试，而不是「没有匹配的地区」） */
    var appleStorefrontsError by mutableStateOf<String?>(null)

    // ── 浮层 / 下载等本地状态 ──
    var sourcePickerVisible by mutableStateOf(false)
    var saveDirPath by mutableStateOf<String?>(null)

    // ── 搜索：单平台分发 ──
    suspend fun searchSongs(platform: MusicPlatform, query: String, page: Int): List<Song> =
        when (platform) {
            MusicPlatform.kuwo -> searchMusic(query, page = page)
            MusicPlatform.netease -> searchNeteaseMusic(query, page = page, pageSize = kSearchPageSize)
            MusicPlatform.qq -> searchQQMusic(query, page = page, pageSize = kSearchPageSize)
            MusicPlatform.kugou -> searchKugouMusic(query, page = page, pageSize = kSearchPageSize)
            MusicPlatform.qobuz -> searchQobuzTracks(
                query,
                limit = kSearchPageSize,
                offset = (page - 1) * kSearchPageSize,
            )
            MusicPlatform.apple -> searchAppleMusic(query, page = page, limit = kAppleSearchPageSize)
            // 本地曲库不参与云端搜索（自己那一页自带筛选框）
            MusicPlatform.kkbox, MusicPlatform.local -> emptyList()
        }

    /** 按当前平台发起单曲搜索（单栏：只有一列） */
    fun startSongSearch(query: String) {
        val platform = searchPlatform
        songGeneration += 1
        val generation = songGeneration
        songColumn = SongSearchColumn(platform = platform, songs = emptyList(), isLoading = true)
        scope.launch {
            val result = try {
                searchSongs(platform, query, 1)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                emptyList()
            }
            if (generation != songGeneration) return@launch
            songColumn = SongSearchColumn(platform, result, false)
        }
    }

    /** 加载 Apple Music 地区表。
     *
     * **触发点有两个**：切到 Apple 平台时（[handleSourceSelect]）、以及地区面板被打开时
     * （`HomeSearchPanel` 的 `LaunchedEffect` + 胶囊点击）。以前只有前者，于是
     * 「上次退出时平台就是 Apple」→ 冷启动后从来没人拉这张表 → 国家面板空着；
     * 而一次网络抖动的失败也没有第二次机会，症状就是「切换平台再切回来就好了」。
     *
     * @param force 列表已加载过也重新拉一次（用户点「重试」时）
     */
    fun loadAppleStorefronts(force: Boolean = false) {
        if (appleStorefrontsLoading) return
        if (!force && appleStorefronts.isNotEmpty()) return
        appleStorefrontsLoading = true
        appleStorefrontsError = null
        scope.launch {
            val list = try {
                fetchAppleStorefronts()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 失败要**说出来**：以前这里吞成空列表，用户只看到「没有匹配的地区」，
                // 分不清是自己搜错了还是根本没加载到。
                appleStorefrontsError = e.message?.takeIf { it.isNotBlank() } ?: "地区表加载失败"
                appleStorefrontsLoading = false
                return@launch
            }
            appleStorefronts = list
            appleStorefrontsLoading = false
            if (list.isEmpty()) appleStorefrontsError = "地区表为空"
            if (list.isNotEmpty() && list.none { it.code == appleCountry }) {
                selectAppleCountry(list.first().code)
            }
        }
    }

    /** 当前搜索是否使用 Apple Music（三种模式同一个平台，直接看平台即可）。 */
    val showAppleCountry: Boolean
        get() = mode != ModeDropdownMode.qobuz && searchPlatform == MusicPlatform.apple

    /** 切换 Apple Music 国家/地区；当前搜索重新按新地区请求。 */
    fun selectAppleCountry(code: String) {
        if (code.isEmpty()) return
        appleCountry = code
        AppleMusicSettings.setStorefront(code)
        when (mode) {
            ModeDropdownMode.song -> if (songSubmitted.isNotEmpty()) startSongSearch(songSubmitted)
            ModeDropdownMode.album -> if (albumSubmitted.isNotEmpty()) startAlbumSearch(albumSubmitted)
            ModeDropdownMode.singer -> if (artistSubmitted.isNotEmpty()) startArtistSearch(artistSubmitted)
            else -> Unit
        }
    }

    /** 专辑搜索（单平台；空关键词不发请求，视图侧等同「无结果」） */
    fun startAlbumSearch(query: String) {
        albumGeneration += 1
        val generation = albumGeneration
        val platform = searchPlatform
        if (query.isEmpty()) {
            albums = emptyList()
            albumsLoading = false
            albumQueryPlatform = null
            return
        }
        albumsLoading = true
        albumQueryPlatform = platform
        scope.launch {
            val result = try {
                searchAlbumByPlatform(platform, query, page = 1, pageSize = 30)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                emptyList()
            }
            if (generation != albumGeneration) return@launch
            albums = result
            albumsLoading = false
        }
    }

    /** 歌手搜索（单平台） */
    fun startArtistSearch(query: String) {
        artistGeneration += 1
        val generation = artistGeneration
        val platform = searchPlatform
        if (query.isEmpty()) {
            artists = emptyList()
            artistsLoading = false
            artistQueryPlatform = null
            return
        }
        artistsLoading = true
        artistQueryPlatform = platform
        scope.launch {
            val result = try {
                searchArtistByPlatform(platform, query)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                emptyList()
            }
            if (generation != artistGeneration) return@launch
            artists = result
            artistsLoading = false
        }
    }

    fun handleSearch() {
        val query = queryText.trim()
        when (mode) {
            ModeDropdownMode.song -> {
                songSubmitted = query
                startSongSearch(query)
            }
            ModeDropdownMode.album -> {
                albumSubmitted = query
                startAlbumSearch(query)
            }
            ModeDropdownMode.singer -> {
                artistSubmitted = query
                startArtistSearch(query)
            }
            ModeDropdownMode.qobuz -> Unit
        }
    }

    fun handleModeSelect(target: ModeDropdownMode) {
        mode = target
        modePickerVisible = false
        // 平台是三种模式共用的：切到某个模式时，若它手里的结果还是别的平台拉的，按当前平台重拉
        refreshIfPlatformChanged(target)
    }

    /** 结果所属平台与当前平台不一致时重搜（各模式只重拉自己那一份） */
    private fun refreshIfPlatformChanged(target: ModeDropdownMode) {
        when (target) {
            ModeDropdownMode.song ->
                if (songColumn != null && songColumn?.platform != searchPlatform) {
                    startSongSearch(songSubmitted)
                }

            ModeDropdownMode.album ->
                if (albumQueryPlatform != null && albumQueryPlatform != searchPlatform) {
                    startAlbumSearch(albumSubmitted)
                }

            ModeDropdownMode.singer ->
                if (artistQueryPlatform != null && artistQueryPlatform != searchPlatform) {
                    startArtistSearch(artistSubmitted)
                }

            ModeDropdownMode.qobuz -> Unit
        }
    }

    /**
     * 「搜索选择」面板选项：三种模式**同一份平台列表**（平台是共用的，换一次三种模式都换）。
     */
    val pickerOptions: List<String>
        get() = searchPlatforms.map { it.label }

    /** 当前平台在面板里的下标 */
    val pickerIndex: Int
        get() = searchPlatforms.indexOf(searchPlatform)

    /** 面板选中某项：换共用的平台（并落盘），按当前模式保留关键词自动重搜 */
    fun handleSourceSelect(index: Int) {
        sourcePickerVisible = false
        if (index < 0 || index >= searchPlatforms.size) return
        searchPlatform = searchPlatforms[index]
        SearchSettings.setPlatform(searchPlatform)
        if (searchPlatform == MusicPlatform.apple) loadAppleStorefronts()
        when (mode) {
            ModeDropdownMode.song -> startSongSearch(songSubmitted)
            ModeDropdownMode.album -> startAlbumSearch(albumSubmitted)
            ModeDropdownMode.singer -> startArtistSearch(artistSubmitted)
            ModeDropdownMode.qobuz -> Unit
        }
    }

    // ── 播放 / 下载 ──

    fun handlePlaySong(song: Song, songs: List<Song>) {
        val index = songs.indexOfFirst { it.id == song.id }
        playerViewModel.setQueue(songs, startIndex = if (index < 0) 0 else index)
    }

    fun handleToggleSelect(song: Song) {
        selectionViewModel.toggleSong(song)
    }

    // ── 链接解析 ──

    fun handleQobuzSubmit(text: String) {
        val parsed = parseQobuzUrl(text)
        if (parsed == null) {
            scope.launch { snackbarHostState.showSnackbar("未识别到有效 Qobuz 链接") }
            return
        }
        // 对齐 Dart `context.push('/qobuz-detail?type=&id=')`：压入全局页面栈
        onOpenRoute(QobuzRoute(parsed.type, parsed.id))
    }

    fun handleKkboxSubmit(text: String) {
        val parsed = parseKkboxUrl(text)
        if (parsed == null) {
            scope.launch { snackbarHostState.showSnackbar("未识别到有效 KKBOX 链接") }
            return
        }
        // 对齐 Dart `context.push('/kkbox-detail?type=&id=')`：压入全局页面栈
        onOpenRoute(KkboxRoute(parsed.type, parsed.id))
    }

}
