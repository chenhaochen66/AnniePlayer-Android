package com.annieplayer.annie_music.kt.features.search

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.annieplayer.annie_music.kt.core.state.DownloadViewModel
import com.annieplayer.annie_music.kt.core.state.PlayerViewModel
import com.annieplayer.annie_music.kt.core.state.SearchSelectionViewModel
import com.annieplayer.annie_music.kt.data.model.Album
import com.annieplayer.annie_music.kt.data.model.Artist
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.searchAlbumByPlatform
import com.annieplayer.annie_music.kt.data.searchArtistByPlatform
import com.annieplayer.annie_music.kt.data.sources.AppleStorefront
import com.annieplayer.annie_music.kt.data.sources.AppleMusicSettings
import com.annieplayer.annie_music.kt.data.sources.appleMusicStorefront
import com.annieplayer.annie_music.kt.data.sources.fetchAppleStorefronts
import com.annieplayer.annie_music.kt.data.sources.searchAppleMusic
import com.annieplayer.annie_music.kt.data.sources.parseKkboxUrl
import com.annieplayer.annie_music.kt.data.sources.parseQobuzUrl
import com.annieplayer.annie_music.kt.data.sources.searchKugouMusic
import com.annieplayer.annie_music.kt.data.sources.searchMusic
import com.annieplayer.annie_music.kt.data.sources.searchNeteaseMusic
import com.annieplayer.annie_music.kt.data.sources.searchQQMusic
import com.annieplayer.annie_music.kt.data.sources.searchQobuzTracks
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
) {
    // ── 搜索状态 ──
    var mode by mutableStateOf(ModeDropdownMode.song)
    var modePickerVisible by mutableStateOf(false)
    var queryText by mutableStateOf("")
    var songSubmitted by mutableStateOf("")
    var albumSubmitted by mutableStateOf("")
    var artistSubmitted by mutableStateOf("")

    /** 单曲双列数据（左、右），随源分组切换整列替换 */
    var songColumns by mutableStateOf<List<SongSearchColumn>>(emptyList())

    /**
     * 单曲搜索代次：每次 [startSongSearch]（提交搜索 / 切换源分组）单调自增。
     * 对外暴露给 UI 作为 SongSearchResults 的 searchToken —— 同词同源重搜时
     * query 与源分组均不变，靠它触发结果列的分页状态重置。
     */
    var songGeneration by mutableIntStateOf(0)
    var sourceGroupIndex by mutableIntStateOf(0)

    var albumPlatform by mutableStateOf(MusicPlatform.kuwo)
    var albums by mutableStateOf<List<Album>>(emptyList())
    var albumsLoading by mutableStateOf(false)
    var albumGeneration by mutableIntStateOf(0)

    var artistPlatform by mutableStateOf(MusicPlatform.kuwo)
    var artists by mutableStateOf<List<Artist>>(emptyList())
    var artistsLoading by mutableStateOf(false)
    var artistGeneration by mutableIntStateOf(0)

    // ── Apple Music 单曲搜索（单列 + 国家选择） ──
    var appleCountry by mutableStateOf(appleMusicStorefront)
    var appleStorefronts by mutableStateOf<List<AppleStorefront>>(emptyList())
    var appleStorefrontsLoading by mutableStateOf(false)

    // ── 浮层 / 下载 / 路由等本地状态 ──
    var sourcePickerVisible by mutableStateOf(false)
    var saveDirPath by mutableStateOf<String?>(null)

    /** Qobuz / KKBOX 详情页路由参数 `type to id`（对齐 Flutter `context.push('/xxx-detail?…')`） */
    var qobuzDetailRoute by mutableStateOf<Pair<String, String>?>(null)
    var kkboxDetailRoute by mutableStateOf<Pair<String, String>?>(null)

    // ── 搜索：单源分发 ──
    suspend fun searchBySource(source: SearchSource, query: String, page: Int): List<Song> =
        when (source) {
            SearchSource.kuwo -> searchMusic(query, page = page)
            SearchSource.netease -> searchNeteaseMusic(query, page = page, pageSize = kSearchPageSize)
            SearchSource.qq -> searchQQMusic(query, page = page, pageSize = kSearchPageSize)
            SearchSource.kugou -> searchKugouMusic(query, page = page, pageSize = kSearchPageSize)
            SearchSource.qobuz -> searchQobuzTracks(
                query,
                limit = kSearchPageSize,
                offset = (page - 1) * kSearchPageSize,
            )
            SearchSource.kkbox -> emptyList()
            SearchSource.apple -> searchAppleMusic(query, page = page, limit = kAppleSearchPageSize)
        }

    /** 按当前源分组发起双列搜索（两列各自独立返回；单列源只有一列） */
    fun startSongSearch(query: String) {
        val group = sourceGroups[sourceGroupIndex]
        val sources = listOfNotNull(group.left, group.right)
        songGeneration += 1
        val generation = songGeneration
        songColumns = sources.map {
            SongSearchColumn(source = it, songs = emptyList(), isLoading = true)
        }
        sources.forEach { source ->
            scope.launch {
                val result = try {
                    searchBySource(source, query, 1)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    emptyList()
                }
                if (generation != songGeneration) return@launch
                songColumns = songColumns.map {
                    if (it.source == source) SongSearchColumn(source, result, false) else it
                }
            }
        }
    }

    /** 加载 Apple Music 地区表（只请求一次）。 */
    fun loadAppleStorefronts() {
        if (appleStorefronts.isNotEmpty() || appleStorefrontsLoading) return
        appleStorefrontsLoading = true
        scope.launch {
            val list = try {
                fetchAppleStorefronts()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                emptyList()
            }
            appleStorefronts = list
            appleStorefrontsLoading = false
            if (list.isNotEmpty() && list.none { it.code == appleCountry }) {
                selectAppleCountry(list.first().code)
            }
        }
    }

    /** 当前搜索是否使用 Apple Music（单曲 Apple 源 / 专辑或歌手选 Apple）。 */
    val showAppleCountry: Boolean
        get() = when (mode) {
            ModeDropdownMode.song ->
                sourceGroups.getOrNull(sourceGroupIndex)?.contains(SearchSource.apple) == true
            ModeDropdownMode.album -> albumPlatform == MusicPlatform.apple
            ModeDropdownMode.singer -> artistPlatform == MusicPlatform.apple
            else -> false
        }

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

    /** 专辑搜索（单源；空关键词不发请求，视图侧等同「无结果」） */
    fun startAlbumSearch(query: String) {
        albumGeneration += 1
        val generation = albumGeneration
        val platform = albumPlatform
        if (query.isEmpty()) {
            albums = emptyList()
            albumsLoading = false
            return
        }
        albumsLoading = true
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

    /** 歌手搜索（单源） */
    fun startArtistSearch(query: String) {
        artistGeneration += 1
        val generation = artistGeneration
        val platform = artistPlatform
        if (query.isEmpty()) {
            artists = emptyList()
            artistsLoading = false
            return
        }
        artistsLoading = true
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
    }

    /** 单平台模式（专辑 / 歌手都是单源） */
    val singleSourceMode: Boolean
        get() = mode == ModeDropdownMode.album || mode == ModeDropdownMode.singer

    /** 「搜索选择」面板选项（单曲=源分组；专辑/歌手=单平台） */
    val pickerOptions: List<String>
        get() = if (singleSourceMode) {
            albumSearchPlatforms.map { it.label }
        } else {
            sourceGroups.map { it.label }
        }

    /** 当前生效的面板下标 */
    val pickerIndex: Int
        get() = when (mode) {
            ModeDropdownMode.singer -> albumSearchPlatforms.indexOf(artistPlatform)
            ModeDropdownMode.album -> albumSearchPlatforms.indexOf(albumPlatform)
            else -> sourceGroupIndex
        }

    /** 面板选中某项：单曲=切源分组，专辑/歌手=切平台；均保留关键词自动重搜 */
    fun handleSourceSelect(index: Int) {
        sourcePickerVisible = false
        if (singleSourceMode) {
            if (index < 0 || index >= albumSearchPlatforms.size) return
            if (mode == ModeDropdownMode.singer) {
                artistPlatform = albumSearchPlatforms[index]
                if (artistPlatform == MusicPlatform.apple) loadAppleStorefronts()
                startArtistSearch(artistSubmitted)
            } else {
                albumPlatform = albumSearchPlatforms[index]
                if (albumPlatform == MusicPlatform.apple) loadAppleStorefronts()
                startAlbumSearch(albumSubmitted)
            }
        } else {
            if (index < 0 || index >= sourceGroups.size) return
            sourceGroupIndex = index
            if (sourceGroups[index].contains(SearchSource.apple)) loadAppleStorefronts()
            startSongSearch(songSubmitted)
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
        // 对齐 Dart `context.push('/qobuz-detail?type=&id=')`：进入全屏详情页
        qobuzDetailRoute = parsed.type to parsed.id
    }

    fun handleKkboxSubmit(text: String) {
        val parsed = parseKkboxUrl(text)
        if (parsed == null) {
            scope.launch { snackbarHostState.showSnackbar("未识别到有效 KKBOX 链接") }
            return
        }
        // 对齐 Dart `context.push('/kkbox-detail?type=&id=')`：进入全屏详情页
        kkboxDetailRoute = parsed.type to parsed.id
    }

}
