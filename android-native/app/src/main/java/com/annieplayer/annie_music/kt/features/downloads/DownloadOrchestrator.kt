package com.annieplayer.annie_music.kt.features.downloads

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.annieplayer.annie_music.kt.core.state.DownloadViewModel
import com.annieplayer.annie_music.kt.core.state.MoreSettings
import com.annieplayer.annie_music.kt.core.state.PlayerViewModel
import com.annieplayer.annie_music.kt.core.state.TranslatePicker
import com.annieplayer.annie_music.kt.core.state.UiViewModel
import com.annieplayer.annie_music.kt.core.util.consumeCancelledSong
import com.annieplayer.annie_music.kt.core.util.downloadQualityFor
import com.annieplayer.annie_music.kt.core.util.enqueueDownload
import com.annieplayer.annie_music.kt.data.DownloadService
import com.annieplayer.annie_music.kt.data.enrichSong
import com.annieplayer.annie_music.kt.data.fetchDownloadUrlBySong
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.playQualityTiersOf
import com.annieplayer.annie_music.kt.data.resolveDownloadLyric
import com.annieplayer.annie_music.kt.data.sources.apple.enrichAppleSong
import com.annieplayer.annie_music.kt.data.sources.apple.AppleDownloader
import com.annieplayer.annie_music.kt.data.sources.kkbox.KkboxDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 音质从高到低（Quality 枚举声明顺序不保证是这个次序，故显式排一份）。
 *
 * 与 `QualityPicker` 的排序表、`playQualityTiersOf` 保持同一套次序：
 * Atmos → ALAC 超高解析 → ALAC 无损 → AAC 256 → FLAC → 320K → 128K。
 */
private val _qualityRank = listOf(
    Quality.atmos,
    Quality.hires,
    Quality.alac,
    Quality.aac256,
    Quality.flac,
    Quality.k320k,
    Quality.k128k,
)

/**
 * 批量下载的音质候选：取选中歌曲**真实** qualities 的并集，按高→低排序；为空回退三档。
 *
 * 为什么不能写死档位表：专辑 / 歌单接口是**按每首歌**返回音质的（网易 `hr` → hires、
 * 酷狗 `sqhash` → flac …），写死 `[flac,320,128]` 会把 hires 直接过滤掉 ——
 * 症状就是「单曲搜索里有 HiRes，转到专辑详情下载却找不到」。
 * 某首没有所选档位时下载侧会自然降级（歌单页走 `bestQuality`，其它页有字节嗅探兜底）。
 */
fun batchDownloadQualities(songs: List<Song>): List<Quality> {
    if (songs.isNotEmpty() && songs.all { it.platform == MusicPlatform.apple }) {
        return playQualityTiersOf(MusicPlatform.apple)
    }
    val set = songs.flatMap { it.qualities }.toSet()
    if (set.isEmpty()) return listOf(Quality.flac, Quality.k320k, Quality.k128k)
    return _qualityRank.filter { it in set }
}

/** 下载一首歌曲所需的元数据（各详情页来源不同，由调用方填好；为 null 时回落到 [Song] 自带字段） */
data class DownloadMeta(
    val coverUrl: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: String? = null,
    val discNumber: String? = null,
    val releaseDate: String? = null,
    val composer: String? = null,
    val genre: String? = null,
    val isrc: String? = null,
)

/**
 * 「下载一首并入库」的完整动作。
 *
 * 各详情页原本各自抄了同一段（取直链 → 解译文 → 补元数据 → 写文件 → 入库登记），
 * 差异只有封面/专辑/艺人/曲号/发行日几个字段 —— 那些由 [meta] 带入，其余共用本函数。
 * 译文按**批量规则**取接口返回的第一个可用语言（不弹选择器）；单曲下载走
 * [DownloadOrchestrator.downloadSingle]。
 *
 * @param onProgress 下载进度（0..1）；各页展示方式不同（进度条 / 文字），故由调用方决定。
 * @return 成功与否（各页据此累计「下载完成 N/M」）。
 */
suspend fun downloadOneAndRegister(
    downloadViewModel: DownloadViewModel,
    playerViewModel: PlayerViewModel,
    song: Song,
    quality: Quality,
    meta: DownloadMeta = DownloadMeta(),
    onProgress: (Double) -> Unit,
): Boolean {
    // 用户已在下载列表里取消这一首（批量是一个批次一个任务，只能靠标记跳过）
    if (consumeCancelledSong(song.id)) return false
    return try {
        val lyric = resolveDownloadLyric(
            song = song,
            writeTranslation = MoreSettings.writeQobuzDownloadTranslation.value,
            pickTranslate = { it.firstOrNull() },
        )
        val filePath = when (song.platform) {
            MusicPlatform.apple -> AppleDownloader.download(
                song = song,
                quality = quality,
                lyric = DownloadService.mergeLyrics(lyric.lrc, lyric.tlyric, lyric.wordLyric),
                onProgress = onProgress,
            )
            // KKBOX 的高音质全是 DRM，必须走官方票据 + 解密，不能把直链丢给通用下载器
            MusicPlatform.kkbox -> KkboxDownloader.download(song = song, onProgress = onProgress)
            else -> {
                val url = fetchDownloadUrlBySong(song, quality)
                val extra = enrichSong(song)
                DownloadService.downloadSong(
                    songId = song.id,
                    title = song.title,
                    artist = song.artist,
                    coverUrl = meta.coverUrl ?: song.cover,
                    album = meta.album ?: song.album,
                    downloadUrl = url,
                    quality = quality,
                    onProgress = onProgress,
                    lyric = lyric.lrc,
                    tlyric = lyric.tlyric,
                    wordLyric = lyric.wordLyric,
                    albumArtist = extra["albumArtist"]?.toString() ?: meta.albumArtist ?: song.albumArtist,
                    trackNumber = meta.trackNumber ?: song.trackNumber ?: extra["trackNumber"]?.toString(),
                    discNumber = meta.discNumber ?: song.discNumber ?: extra["discNumber"]?.toString(),
                    releaseDate = meta.releaseDate ?: song.releaseDate ?: extra["releaseDate"]?.toString(),
                    composer = meta.composer ?: song.composer ?: extra["composer"]?.toString(),
                    genre = meta.genre ?: song.genre ?: extra["genre"]?.toString(),
                    isrc = meta.isrc ?: song.isrc ?: extra["isrc"]?.toString(),
                    copyright = song.copyright,
                    version = song.version,
                    // 多歌手统一写成 `A / B`（结构化列表优先，见 TagMapper.artistOf）
                    artistNames = song.artistNames,
                )
            }
        }
        downloadViewModel.completeTask(song.id, filePath)
        playerViewModel.addDownloadedId(song.id)
        playerViewModel.addToQueue(song)
        true
    } catch (e: Exception) {
        // 批量下载"成功率不高"必须能定位到具体环节：这里把歌曲 + 异常打出来，
        // 便于用 `adb logcat | grep AnnieDownload` 一眼看出是取直链 / 解译文 / 写入 / 打标哪一步炸的。
        Log.w("AnnieDownload", "[download] failed song=${song.id} quality=$quality: $e", e)
        downloadViewModel.failTask(song.id)
        false
    }
}

/**
 * 下载编排（从 `features/search/SearchScreenState` 搬出）。
 *
 * 为什么单开：⋮ 的「下载」是**全局唯一**的单曲入口（任意页面的行尾 ⋮ 都走它），
 * 但编排逻辑原先寄生在搜索页的 State 里 —— 换页面、改下载流程都要动搜索页，语义不清。
 * 搬到这里后只依赖三个 ViewModel + 一个 scope，与搜索页彻底解耦。
 *
 * 三类入口：
 * - [openActions]：打开 ⋮ 歌曲操作面板（真正的下载入口在这个面板里）。
 * - [downloadSingle]：单曲下载。**先问译文语言、再建任务入队**（顺序载荷相关，见下）。
 * - [downloadBatch]：批量下载，收 `songs` 参数（不依赖搜索页的多选状态）。
 */
class DownloadOrchestrator(
    private val scope: CoroutineScope,
    private val uiViewModel: UiViewModel,
    private val playerViewModel: PlayerViewModel,
    private val downloadViewModel: DownloadViewModel,
) {

    /** 正在下载的歌曲 id（宿主据此弹「正在下载…」Toast） */
    var downloadingId by mutableStateOf<String?>(null)
        private set

    /** 批量音质面板是否可见（由宿主页面控制开关） */
    var batchPickerVisible by mutableStateOf(false)
        private set

    /** 批量下载进行中（宿主据此把批量按钮置灰） */
    var batchDownloading by mutableStateOf(false)
        private set

    fun openBatchPicker() {
        batchPickerVisible = true
    }

    fun closeBatchPicker() {
        batchPickerVisible = false
    }

    /**
     * 打开 ⋮ 歌曲操作面板。
     *
     * **先弹，再补**：Apple 的多歌手信息（`relationships.artists`）要额外一次 `/songs/{id}`，
     * 以前是等它回来才弹面板 —— 首页/搜索第一次点 ⋮ 就卡一个 RTT。现在立刻弹，
     * 歌手的细分列表随后填进去（面板里 `remember(song)` 会自动重算）。
     */
    fun openActions(song: Song) {
        uiViewModel.setSongActionsVisible(true, song)
        if (song.platform != MusicPlatform.apple) return
        // 已经带结构化歌手了（搜索/专辑接口现在都会带），就一次网络都不用发
        if (song.artistNames.isNotEmpty() || song.artistIds.isNotEmpty()) return
        scope.launch {
            val enriched = runCatching { enrichAppleSong(song) }.getOrDefault(song)
            val changed = enriched.artistNames != song.artistNames ||
                enriched.artistIds != song.artistIds ||
                enriched.albumId != song.albumId
            if (changed) uiViewModel.updateSongActionsSong(song.id, enriched)
        }
    }

    /**
     * 单曲下载。
     *
     * 【顺序要紧】先把译文语言问清楚，再建任务 / 入队。否则语言面板还没选，
     * 「下载任务」列表里就已经先冒出一条在转圈 —— 用户会认为「没选就开始下载了」。
     */
    fun downloadSingle(song: Song, quality: Quality) {
        val songId = song.id
        scope.launch {
            val lyricResult = resolveDownloadLyric(
                song = song,
                writeTranslation = MoreSettings.writeQobuzDownloadTranslation.value,
                pickTranslate = { langs -> TranslatePicker.request(langs, song.title).await() },
            )
            downloadViewModel.addTask(song, quality)
            downloadingId = songId
            enqueueDownload(songId) {
                try {
                    if (song.platform == MusicPlatform.apple) {
                        AppleDownloader.download(
                            song = song,
                            quality = quality,
                            lyric = DownloadService.mergeLyrics(
                                lyricResult.lrc,
                                lyricResult.tlyric,
                                lyricResult.wordLyric,
                            ),
                            onProgress = { p -> downloadViewModel.updateProgress(songId, p * 100) },
                        )
                    } else if (song.platform == MusicPlatform.kkbox) {
                        // KKBOX 高音质全是 DRM：官方票据 + 解密，不经通用直链下载
                        KkboxDownloader.download(
                            song = song,
                            onProgress = { p -> downloadViewModel.updateProgress(songId, p * 100) },
                        )
                    } else {
                        val url = fetchDownloadUrlBySong(song, quality)
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
                            wordLyric = lyricResult.wordLyric,
                            albumArtist = extra["albumArtist"]?.toString() ?: song.albumArtist,
                            trackNumber = song.trackNumber ?: extra["trackNumber"]?.toString(),
                            discNumber = song.discNumber ?: extra["discNumber"]?.toString(),
                            releaseDate = song.releaseDate ?: extra["releaseDate"]?.toString(),
                            composer = song.composer ?: extra["composer"]?.toString(),
                            genre = song.genre ?: extra["genre"]?.toString(),
                            isrc = song.isrc ?: extra["isrc"]?.toString(),
                            copyright = song.copyright,
                            version = song.version,
                            // 多歌手统一写成 `A / B`（结构化列表优先，见 TagMapper.artistOf）
                            artistNames = song.artistNames,
                        )
                    }
                    downloadViewModel.completeTask(songId)
                    playerViewModel.addDownloadedId(songId)
                    playerViewModel.addToQueue(song)
                } catch (_: Exception) {
                    downloadViewModel.failTask(songId)
                }
                downloadingId = null
            }
        }
    }

    /**
     * 批量下载。
     *
     * [songs] 由宿主传入（搜索页传当前多选），整个批次只跑一次翻译决策：
     * 按既定规则写入**接口返回的第一个可用译文语言**（不弹选择器）。
     * [onFinished] 在整批跑完后回调（宿主用来清空多选）。
     *
     * **每首歌的档位在这里夹到它真实可用的最高档**（[downloadQualityFor]）：面板给的是整批
     * 并集，用户点了无损之后，这批里「只有 320k」的歌必须降到 320k —— 否则会把 `flac`
     * 原样发出去换回加密包（酷我 `mgg`）或跨源直链（QQ 回到酷我 `.mflac`）。
     * `addTask` 与真正的下载用同一个值，下载列表显示的就是实际请求的档位。
     */
    fun downloadBatch(songs: List<Song>, quality: Quality, onFinished: () -> Unit = {}) {
        batchPickerVisible = false
        if (songs.isEmpty()) return
        batchDownloading = true
        for (song in songs) {
            downloadViewModel.addTask(song, downloadQualityFor(song, quality))
        }
        val batchId = "search_batch_" + System.currentTimeMillis()
        enqueueDownload(batchId) {
            for (song in songs) {
                downloadViewModel.updateProgress(song.id, 0.0)
                downloadOneAndRegister(
                    downloadViewModel = downloadViewModel,
                    playerViewModel = playerViewModel,
                    song = song,
                    quality = downloadQualityFor(song, quality),
                    onProgress = { p -> downloadViewModel.updateProgress(song.id, p * 100) },
                )
            }
            onFinished()
            batchDownloading = false
        }
    }
}
