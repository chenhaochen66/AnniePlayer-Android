package com.annieplayer.annie_music.kt.features.search

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.annieplayer.annie_music.kt.core.state.AlbumDetailData
import com.annieplayer.annie_music.kt.core.state.DownloadViewModel
import com.annieplayer.annie_music.kt.core.state.PlayerViewModel
import com.annieplayer.annie_music.kt.core.state.SearchSelectionViewModel
import com.annieplayer.annie_music.kt.core.state.SingerDetailData
import com.annieplayer.annie_music.kt.core.state.UiViewModel
import com.annieplayer.annie_music.kt.core.util.enqueueDownload
import com.annieplayer.annie_music.kt.data.DownloadService
import com.annieplayer.annie_music.kt.data.LyricResult
import com.annieplayer.annie_music.kt.data.enrichSong
import com.annieplayer.annie_music.kt.data.fetchDownloadUrlBySong
import com.annieplayer.annie_music.kt.data.fetchLyric
import com.annieplayer.annie_music.kt.data.model.Album
import com.annieplayer.annie_music.kt.data.model.Artist
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.resolveArtist
import com.annieplayer.annie_music.kt.data.searchAlbumByPlatform
import com.annieplayer.annie_music.kt.data.searchArtistByPlatform
import com.annieplayer.annie_music.kt.data.sources.fetchKugouSongAuthors
import com.annieplayer.annie_music.kt.data.sources.parseKkboxUrl
import com.annieplayer.annie_music.kt.data.sources.parseQobuzUrl
import com.annieplayer.annie_music.kt.data.sources.searchKugouMusic
import com.annieplayer.annie_music.kt.data.sources.searchMusic
import com.annieplayer.annie_music.kt.data.sources.searchNeteaseMusic
import com.annieplayer.annie_music.kt.data.sources.searchQQMusic
import com.annieplayer.annie_music.kt.data.sources.searchQobuzTracks
import com.annieplayer.annie_music.kt.widgets.SongArtistRef
import com.annieplayer.annie_music.kt.widgets.SongSearchColumn
import com.annieplayer.annie_music.kt.widgets.closeOtherDetailOverlays
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
    private val uiViewModel: UiViewModel,
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

    // ── 浮层 / 下载 / 路由等本地状态 ──
    var sourcePickerVisible by mutableStateOf(false)
    var batchPickerVisible by mutableStateOf(false)
    var batchDownloading by mutableStateOf(false)
    var downloadingId by mutableStateOf<String?>(null)
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
        }

    /** 按当前源分组发起双列搜索（两列各自独立返回） */
    fun startSongSearch(query: String) {
        val group = sourceGroups[sourceGroupIndex]
        songGeneration += 1
        val generation = songGeneration
        songColumns = listOf(
            SongSearchColumn(source = group.left, songs = emptyList(), isLoading = true),
            SongSearchColumn(source = group.right, songs = emptyList(), isLoading = true),
        )
        scope.launch {
            val result = try {
                searchBySource(group.left, query, 1)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                emptyList()
            }
            if (generation != songGeneration) return@launch
            songColumns = songColumns.map {
                if (it.source == group.left) SongSearchColumn(group.left, result, false) else it
            }
        }
        scope.launch {
            val result = try {
                searchBySource(group.right, query, 1)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                emptyList()
            }
            if (generation != songGeneration) return@launch
            songColumns = songColumns.map {
                if (it.source == group.right) SongSearchColumn(group.right, result, false) else it
            }
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
                startArtistSearch(artistSubmitted)
            } else {
                albumPlatform = albumSearchPlatforms[index]
                startAlbumSearch(albumSubmitted)
            }
        } else {
            if (index < 0 || index >= sourceGroups.size) return
            sourceGroupIndex = index
            startSongSearch(songSubmitted)
        }
    }

    // ── 播放 / 下载 ──

    fun handlePlaySong(song: Song, songs: List<Song>) {
        val index = songs.indexOfFirst { it.id == song.id }
        playerViewModel.setQueue(songs, startIndex = if (index < 0) 0 else index)
    }

    fun handleDownload(song: Song) {
        uiViewModel.setSongActionsVisible(true, song)
    }

    fun handleToggleSelect(song: Song) {
        selectionViewModel.toggleSong(song)
    }

    fun handleDownloadWithQuality(quality: Quality) {
        val song = uiViewModel.state.value.pickerSong ?: return
        uiViewModel.setPicker(false)
        downloadViewModel.addTask(song, quality)
        val songId = song.id
        downloadingId = songId
        enqueueDownload(songId) {
            try {
                val url = fetchDownloadUrlBySong(song, quality)
                val lyricResult = if (song.lyric != null && song.tlyric != null) {
                    LyricResult(lrc = song.lyric, tlyric = song.tlyric)
                } else {
                    fetchLyric(song)
                }
                val extra = enrichSong(song)
                DownloadService.downloadSong(
                    songId = songId,
                    title = song.title,
                    artist = song.artist,
                    coverUrl = song.cover,
                    album = song.album,
                    downloadUrl = url,
                    quality = quality,
                    onProgress = { p -> downloadViewModel.updateProgress(songId, p * 100) },
                    lyric = lyricResult.lrc,
                    tlyric = lyricResult.tlyric,
                    albumArtist = song.albumArtist ?: extra["albumArtist"]?.toString(),
                    trackNumber = song.trackNumber ?: extra["trackNumber"]?.toString(),
                    discNumber = song.discNumber,
                    releaseDate = song.releaseDate ?: extra["releaseDate"]?.toString(),
                )
                downloadViewModel.completeTask(songId)
                playerViewModel.addDownloadedId(songId)
                playerViewModel.addToQueue(song)
            } catch (_: Exception) {
                downloadViewModel.failTask(songId)
            }
            downloadingId = null
        }
    }

    fun handleBatchDownload(quality: Quality) {
        batchPickerVisible = false
        val songs = selectionViewModel.state.value.selectedSongs
        if (songs.isEmpty()) return
        batchDownloading = true
        for (song in songs) {
            downloadViewModel.addTask(song, quality)
        }
        val batchId = "search_batch_" + System.currentTimeMillis()
        enqueueDownload(batchId) {
            for (song in songs) {
                downloadViewModel.updateProgress(song.id, 0.0)
                try {
                    val url = fetchDownloadUrlBySong(song, quality)
                    val lyricResult = fetchLyric(song)
                    val extra = enrichSong(song)
                    DownloadService.downloadSong(
                        songId = song.id,
                        title = song.title,
                        artist = song.artist,
                        coverUrl = song.cover,
                        album = song.album,
                        downloadUrl = url,
                        quality = quality,
                        onProgress = { p -> downloadViewModel.updateProgress(song.id, p * 100) },
                        lyric = lyricResult.lrc,
                        tlyric = lyricResult.tlyric,
                        albumArtist = song.albumArtist ?: extra["albumArtist"]?.toString(),
                        trackNumber = song.trackNumber ?: extra["trackNumber"]?.toString(),
                        discNumber = song.discNumber,
                        releaseDate = song.releaseDate ?: extra["releaseDate"]?.toString(),
                    )
                    downloadViewModel.completeTask(song.id)
                    playerViewModel.addDownloadedId(song.id)
                    playerViewModel.addToQueue(song)
                } catch (_: Exception) {
                    downloadViewModel.failTask(song.id)
                }
            }
            selectionViewModel.clearAll()
            batchDownloading = false
        }
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

    /** ⋮ → 歌手：有 id 直接开；没有则按名字搜索一次（对齐 Dart `openArtistDetail`） */
    fun openArtistDetail(artist: SongArtistRef) {
        // 必须用「被点 ⋮ 的那首歌」：平台要从它取，酷狗还要按它的 hash 补作者 id。
        // 之前用 currentSong（正在播放的歌），两者不同源时会拿错平台的 id 去查，
        // 表现为「跳过去的歌手名字对、曲目却是别人的」。
        val song = uiViewModel.state.value.songActionsSong
            ?: playerViewModel.state.value.currentSong
            ?: return
        scope.launch {
            var knownId = if (artist.id.isEmpty()) null else artist.id
            // 酷狗的专辑/歌单都不带歌手 id：这里按歌曲 hash 逐曲补一次
            // （接口单曲限，结果落盘缓存 → 二次及以后秒开）
            if (knownId == null && song.platform == MusicPlatform.kugou) {
                knownId = fetchKugouSongAuthors(song.id.removePrefix("kugou:"))
                    .firstOrNull { it.name == artist.name }
                    ?.id
            }
            val resolved = resolveArtist(
                song.platform,
                artist.name,
                knownId = knownId,
            )
            if (resolved == null || resolved.id.isEmpty()) {
                snackbarHostState.showSnackbar("未找到歌手「${artist.name}」")
                return@launch
            }
            // 详情浮层在播放页之下：先收起播放页，再关其它详情，最后开歌手详情
            uiViewModel.setPlayerOverlayVisible(false)
            closeOtherDetailOverlays(
                setAlbumDetailVisible = { uiViewModel.setAlbumDetailVisible(it) },
                setSingerDetailVisible = { uiViewModel.setSingerDetailVisible(it) },
                setPlaylistDetailVisible = { uiViewModel.setPlaylistDetailVisible(it) },
            )
            uiViewModel.setSingerDetailVisible(
                true,
                SingerDetailData(
                    id = resolved.id,
                    name = resolved.name,
                    picture = resolved.picture,
                    platform = song.platform,
                ),
            )
        }
    }

    /** ⋮ → 专辑：先收起播放页、关其它详情，再置专辑详情数据 */
    fun openAlbumDetail(song: Song) {
        uiViewModel.setPlayerOverlayVisible(false)
        closeOtherDetailOverlays(
            setAlbumDetailVisible = { uiViewModel.setAlbumDetailVisible(it) },
            setSingerDetailVisible = { uiViewModel.setSingerDetailVisible(it) },
            setPlaylistDetailVisible = { uiViewModel.setPlaylistDetailVisible(it) },
        )
        uiViewModel.setAlbumDetailVisible(
            true,
            AlbumDetailData(
                id = song.albumId ?: "",
                cover = song.cover,
                name = song.album ?: "",
                artist = song.albumArtist ?: song.artist,
                platform = song.platform,
            ),
        )
    }
}
