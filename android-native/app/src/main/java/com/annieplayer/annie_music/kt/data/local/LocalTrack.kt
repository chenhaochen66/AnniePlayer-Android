package com.annieplayer.annie_music.kt.data.local

import android.net.Uri
import com.annieplayer.annie_music.kt.core.util.ArtistSplit
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import kotlinx.serialization.Serializable
import java.io.File
import java.util.Locale

/**
 * 本地曲库的一首歌（**扫描结果 + ffmpeg 元数据**的合并体，同时是磁盘缓存的序列化单元）。
 *
 * 生命周期：
 * 1. 扫描只拿到 [path] / [mtime] / [size]，先用 [placeholder] 造一条 —— 标题/艺术家从
 *    **文件名**回落（`{标题} - {艺术家}`，与下载命名规则一致），界面立刻有内容；
 * 2. 后台 `ffprobe` 逐条补齐 → [probed] 置 true，tag 覆盖文件名；
 * 3. 整个列表写进 `index.json`，下次启动直接读缓存（未变更的文件不再跑 ffmpeg）。
 *
 * 字段来源（全部来自 ffprobe 的 `format` / `audio stream`）：
 * - [title] / [artist] / [album] / [albumArtist] / [track] / [disc] / [year] / [genre] /
 *   [composer] / [isrc] / [copyright] / [version]：`format.tags.*`
 * - [codec] / [sampleRate] / [channels] / [bitDepth] / [bitrate]：`streams[codec_type=audio]`
 * - [lossless]：由 [codec] 判定，界面据此显示 `FLAC` 之类的音质角标
 *
 * **字段集合 = 下载打标写入的那一套**（见 `FfmpegTagger.tagInPlace`）：
 * `title / artist / album / album_artist / track / disc / date / composer / genre / ISRC /
 * comment / copyright` + 封面。本地扫描必须把这 12 个键全部读回来，否则「自己写的标签
 * 自己读不出」——流媒体下载的歌进了本地曲库就会缺字段。歌词只服务播放页，不进详情展示。
 */
@Serializable
data class LocalTrackMeta(
    /** 绝对路径（唯一键，转 [Song] 时 id = `local:<path>`） */
    val path: String,
    val mtime: Long = 0L,
    val size: Long = 0L,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String? = null,
    val duration: Int = 0,
    val track: Int? = null,
    val disc: Int? = null,
    val year: String? = null,
    val genre: String? = null,
    /** 作曲 / 词曲作者（打标写 `composer`） */
    val composer: String? = null,
    /** ISRC（打标写 `ISRC`） */
    val isrc: String? = null,
    /**
     * 版本 / 备注（打标把 Qobuz 的 `version` 写进 `comment`，这里从 `COMMENT`（退 `VERSION`）读回）。
     * 第三方文件里这个位置可能只是编码器签名（`ExactAudioCopy` 之类），原样显示，不改写。
     */
    val version: String? = null,
    /** 版权声明（打标写 `copyright`） */
    val copyright: String? = null,
    val codec: String = "",
    val sampleRate: Int = 0,
    val bitDepth: Int = 0,
    /** kbps */
    val bitrate: Int = 0,
    val channels: Int = 0,
    val lossless: Boolean = false,
    /** 抽取出来的封面文件路径（`filesDir/local_library/covers/` 下的 jpg）；无封面为 null */
    val coverPath: String? = null,
    /** 是否已跑过 ffprobe；false = tag 字段还是文件名回落值 */
    val probed: Boolean = false,
    /**
     * 内嵌歌词（`LYRICS` / `USLT` / `©lyr`）。
     *
     * 扫描时和上面那些字段同一次 TagLib `getMetadata` 一起取出来，**不额外碰磁盘**；
     * 用户手动编辑歌词时由 `AnnieTag` 写回文件（原地写，不搬音频）。
     */
    val lyric: String? = null,
) {
    /** 播放队列 / 列表复用键 */
    val id: String get() = "local:$path"

    val file: File get() = File(path)

    val folderName: String get() = file.parentFile?.name.orEmpty()

    val ext: String get() = path.substringAfterLast('.', "").lowercase(Locale.ROOT)

    /**
     * 艺术家多值拆分，供按艺术家分组 / ⋮「跳转到歌手」使用。
     *
     * 规则只有一份（[ArtistSplit]）：`; ； | , 、 + & ×`、`feat./ft./featuring/vs./with/w/`、
     * 以及能判成「分隔」的 `/`（`AC/DC` 那种名字内部的斜杠不拆）。
     * 以前这里手写 `split(';', '；', '/', '|')` —— `A feat. B`、`A & B`、`周杰伦、费玉清`
     * 全被当成一整个艺术家。
     */
    val artistNames: List<String>
        get() = ArtistSplit.split(artist)

    /**
     * **展示用的歌手串**：多歌手统一成 `A / B`。
     *
     * 标签里的原始写法保留在 [artist]（那是文件里的真实内容），界面上一律用这个：
     * 列表行 / 详情面板 / 播放页 / 队列 / 下载文件名 / 打标都取它，
     * 于是 `周杰伦、费玉清`、`A feat. B`、`A & B` 在界面上都是 `A / B`。
     * 单歌手与 [artist] 完全相同（拆不开就原样）。
     */
    val artistDisplay: String
        get() = ArtistSplit.normalize(artist)

    /** 音质角标：无损给 `FLAC` / `ALAC` / `DSD64`，有损给 `320K` */
    val qualityChip: String
        get() = when {
            lossless -> when (codec.lowercase(Locale.ROOT)) {
                "alac" -> if (bitDepth > 16) "ALAC ${bitDepth}bit" else "ALAC"
                "flac" -> if (bitDepth > 16) "FLAC ${bitDepth}bit" else "FLAC"
                "ape" -> "APE"
                "wav" -> "WAV"
                "aiff", "aif" -> "AIFF"
                "dsd", "dsf", "dff" -> "DSD"
                // dsd64 / dsd128 … 直接当档位名显示
                else -> codec.uppercase(Locale.ROOT)
            }
            // [bitrate] 存的是 kbps（ffprobe 的 bps 已除 1000）
            bitrate > 0 -> "${bitrate}K"
            else -> codec.uppercase(Locale.ROOT)
        }

    /** `27.6 MB` */
    val sizeText: String
        get() {
            if (size <= 0) return ""
            val mb = size.toDouble() / (1024.0 * 1024.0)
            return if (mb >= 100) String.format(Locale.US, "%.0f MB", mb)
            else String.format(Locale.US, "%.1f MB", mb)
        }

    /** `3:34` */
    val durationText: String
        get() {
            if (duration <= 0) return ""
            val m = duration / 60
            val s = duration % 60
            return "%d:%02d".format(m, s)
        }

    /** 采样率 / 位深，如 `44.1kHz / 16bit`、`192kHz / 24bit` */
    val formatText: String
        get() {
            val parts = ArrayList<String>(2)
            if (sampleRate > 0) {
                val khz = sampleRate / 1000.0
                // 整数 kHz 不带小数（44kHz / 192kHz），带小数的保留一位（44.1kHz / 2822.4kHz）
                parts += if (khz == khz.toInt().toDouble()) {
                    "${khz.toInt()}kHz"
                } else {
                    String.format(Locale.US, "%.1fkHz", khz)
                }
            }
            if (bitDepth > 0) parts += "${bitDepth}bit"
            return parts.joinToString(" / ")
        }

    /** 列表第三行：`FLAC 24bit · 27.6 MB · 3:34`（空字段自动省略） */
    val metaLine: String
        get() = listOf(qualityChip, sizeText, durationText)
            .filter { it.isNotEmpty() }
            .joinToString(" · ")

    /** 曲目 / 碟号：`第 3 首 · 第 1 碟`（缺哪个省哪个，都没有是空串） */
    val trackDiscText: String
        get() = listOfNotNull(
            track?.takeIf { it > 0 }?.let { "第 $it 首" },
            disc?.takeIf { it > 0 }?.let { "第 $it 碟" },
        ).joinToString(" · ")

    /**
     * 转成播放链路的 [Song]。
     *
     * - `playUrl` 用 `Uri.fromFile` 拼（中文/空格会被正确编码），引擎侧见到 `file://` 直接播；
     * - `platform = local`：`fetchLyric` 走本地文件，`fetchPlayUrlBySong` 直接用 `playUrl`；
     * - [lyric] 由 [LocalLibraryStore.readLyric] 在播放时单独读取（这里不碰磁盘）。
     */
    fun toSong(lyric: String? = null): Song = Song(
        id = id,
        title = title.ifBlank { file.nameWithoutExtension },
        // 多歌手统一成 `A / B`：播放页 / 队列 / 下载文件名 / 打标都吃这一份
        artist = artistDisplay.ifBlank { "未知艺术家" },
        cover = coverPath?.let { "file://$it" }.orEmpty(),
        album = album.ifBlank { null },
        albumId = null,
        duration = duration,
        platform = MusicPlatform.local,
        playUrl = Uri.fromFile(file).toString(),
        lyric = lyric,
        qualities = if (lossless) listOf(Quality.flac) else listOf(Quality.k320k),
        albumArtist = albumArtist,
        trackNumber = track?.toString(),
        discNumber = disc?.toString(),
        releaseDate = year,
        genre = genre,
        composer = composer,
        isrc = isrc,
        artistNames = artistNames,
    )

    companion object {
        /**
         * 扫描阶段的占位记录：只有路径 / 时间戳 / 大小，tag 字段从文件名回落。
         *
         * [mtime] / [size] 允许调用方传进来：缓存命中判定那一轮已经各自 stat 过一次，
         * 再让这里 stat 两遍就是 3000 首白跑 6000 次 `stat`（FUSE 上约 0.1~0.2ms/次）。
         */
        fun placeholder(
            file: File,
            mtime: Long = file.lastModified(),
            size: Long = file.length(),
        ): LocalTrackMeta {
            val (t, a) = parseFileName(file.nameWithoutExtension)
            return LocalTrackMeta(
                path = file.absolutePath,
                mtime = mtime,
                size = size,
                title = t,
                artist = a,
                album = file.parentFile?.name.orEmpty(),
                probed = false,
            )
        }

        /**
         * 文件名回落：优先按 `标题 - 艺术家`（与 `DownloadService` 的命名规则一致）拆，
         * 拆不出就整段当标题、艺术家留空。
         */
        fun parseFileName(base: String): Pair<String, String> {
            val trimmed = base.trim()
            if (trimmed.isEmpty()) return "" to ""
            for (sep in listOf(" - ", " − ", " – ", "-", "－")) {
                val i = trimmed.indexOf(sep)
                if (i > 0 && i < trimmed.length - sep.length) {
                    val left = trimmed.substring(0, i).trim()
                    val right = trimmed.substring(i + sep.length).trim()
                    if (left.isNotEmpty() && right.isNotEmpty()) return left to right
                }
            }
            return trimmed to ""
        }
    }
}

/** 曲库分区（宫格四个入口） */
enum class LocalSection(val title: String) {
    songs("全部歌曲"),
    artists("艺术家"),
    albums("专辑"),
    folders("文件夹"),

    /** 最近**播放**（不是最近添加）：按播放时间倒序，最多 [LocalLibraryStore.RECENT_LIMIT] 条 */
    recent("最近播放"),
    ;

    val isGrouped: Boolean get() = this == artists || this == albums || this == folders
}

/** 歌曲列表排序（枚举项名不能叫 `title`：会和构造参数同名冲突） */
enum class LocalSort(val label: String, val defaultAscending: Boolean) {
    byTitle("标题", true),
    byArtist("歌手", true),
    byAlbum("专辑", true),

    /** 时长默认长→短（升序 = 短→长） */
    byDuration("时长", false),

    /** 添加时间默认新→旧（升序 = 旧→新） */
    byAdded("添加时间", false),
    ;

    fun next(): LocalSort = entries[(ordinal + 1) % entries.size]
}

/** 分组条目（艺术家 / 专辑 / 文件夹） */
data class LocalGroup(
    val key: String,
    val title: String,
    val subtitle: String,
    val count: Int,
    val coverPath: String? = null,
    val tracks: List<LocalTrackMeta> = emptyList(),
)

/** 专辑分组的键（`专辑 · 专辑艺术家`）—— 分组与计数必须用同一套规则，所以抽出来 */
fun albumGroupKey(t: LocalTrackMeta): String =
    listOfNotNull(t.album.ifBlank { "未知专辑" }, t.albumArtist?.takeIf { it.isNotBlank() })
        .joinToString(" · ")

/** 艺术家分组的键列表（多值歌手逐个列出；空则归到「未知艺术家」） */
fun artistGroupKeys(t: LocalTrackMeta): List<String> =
    t.artistNames.ifEmpty { listOf("未知艺术家") }

/**
 * 本地没有「专辑类型」字段：按曲目数推断 —— 1 首 = 单曲，2~6 首 = EP，其余 = 专辑。
 *
 * 只用于专辑卡第三行和流媒体对齐（那边显示的是平台给的 `专辑 / 单曲 / EP`），
 * **不参与任何分组或排序逻辑**。
 */
fun localAlbumType(count: Int): String = when {
    count <= 1 -> "单曲"
    count <= 6 -> "EP"
    else -> "专辑"
}

/**
 * 发行日期的形态：`2023` / `2023-05` / `2023-05-12`，后面跟什么都不管。
 *
 * 只认「开头是四位年」，因为 tag 里的日期还会带时间（`2023-05-12T00:00:00`）、
 * 带时区、或者被第三方工具写成 `2023-5-12`（月日不补零）。
 */
private val ReleaseDateRegex = Regex("""^(\d{4})(?:[-/.](\d{1,2})(?:[-/.](\d{1,2}))?)?""")

/**
 * 发行日期规范化 → `yyyy` / `yyyy-MM` / `yyyy-MM-dd`；认不出返回 null。
 *
 * 曲库里的日期 tag 取决于**谁写的**：本项目的打标写 `releaseDate`（`yyyy-MM-dd`），
 * 下载站给的常常只有年，第三方工具还有 `2023/05/12`、`2023-05-12T00:00:00` 这些。
 * 显示要统一（本地曲库现在一律显示到年月日）、排序又要能直接按字符串比，
 * 所以在这里一次性收口 —— **只有年就只给年**，月日无法凭空补出来，宁短不错。
 */
fun normalizeReleaseDate(raw: String?): String? {
    val s = raw?.trim().orEmpty()
    if (s.isEmpty()) return null
    val m = ReleaseDateRegex.find(s) ?: return null
    val year = m.groupValues[1].toIntOrNull() ?: return null
    val month = m.groupValues[2].toIntOrNull()
    if (month == null || month !in 1..12) return "%04d".format(year)
    val day = m.groupValues[3].toIntOrNull()
    if (day == null || day !in 1..31) return "%04d-%02d".format(year, month)
    return "%04d-%02d-%02d".format(year, month, day)
}

/**
 * 专辑发行日期：组里第一条能规范化的 [LocalTrackMeta.year]。
 *
 * 取「第一条非空」而不是「最多的那个」：同一张专辑的曲目日期本来就该一致，
 * 不一致时（合集 / 再版）用第一首的，行为可预测。
 */
fun localAlbumDateOf(tracks: List<LocalTrackMeta>): String? =
    tracks.firstNotNullOfOrNull { normalizeReleaseDate(it.year) }

/** 文件夹分组的键（空路径归到根 `/`） */
fun folderGroupKey(t: LocalTrackMeta): String = t.folderName.ifBlank { "/" }

/**
 * 一次遍历算出三个宫格入口的计数。
 *
 * 为什么不直接 `groupTracks(...).size` 三次：那会在**每次重组**里做三遍全表分组
 * （建 key、拼字符串、排序），3000 首的曲库里滚动时会稳定掉帧。这里只做三组哈希去重，
 * 单遍 O(n)，也不构造任何分组对象。
 */
fun countGroups(tracks: List<LocalTrackMeta>): Triple<Int, Int, Int> {
    if (tracks.isEmpty()) return Triple(0, 0, 0)
    val artists = HashSet<String>(tracks.size)
    val albums = HashSet<String>(tracks.size)
    val folders = HashSet<String>(64)
    for (t in tracks) {
        for (k in artistGroupKeys(t)) artists.add(k)
        albums.add(albumGroupKey(t))
        folders.add(folderGroupKey(t))
    }
    return Triple(artists.size, albums.size, folders.size)
}

/** 按当前分区与排序生成分组 */
fun groupTracks(tracks: List<LocalTrackMeta>, section: LocalSection): List<LocalGroup> {
    if (!section.isGrouped) return emptyList()
    val map = LinkedHashMap<String, MutableList<LocalTrackMeta>>()
    for (t in tracks) {
        val keys = when (section) {
            LocalSection.artists -> artistGroupKeys(t)
            LocalSection.albums -> listOf(albumGroupKey(t))
            LocalSection.folders -> listOf(folderGroupKey(t))
            else -> listOf("")
        }
        for (k in keys) map.getOrPut(k) { ArrayList() }.add(t)
    }
    // 组名排序：与歌曲列表同一套（先字母桶位、再 ICU 中文排序），比较器只建一次
    val text = localAlphabetComparator()
    return map.entries.map { (key, list) ->
        val first = list.first()
        LocalGroup(
            key = key,
            title = key,
            subtitle = when (section) {
                LocalSection.artists -> "${list.size} 首"
                LocalSection.albums -> list.map { it.artistDisplay }
                    .distinct().take(2).joinToString(ArtistSplit.SEPARATOR)
                LocalSection.folders -> "${list.size} 首"
                else -> ""
            },
            count = list.size,
            // 封面是「按需抽」的：主页只对滚到可见的行抽过封面，所以组里第一首常常
            // `coverPath == null` —— 但磁盘上 `covers/` 里可能早就有图了。这里同步兜一层
            // `existingCover`（一次 stat，短路即返回），否则会出现「主页有封面、子页面没有」。
            coverPath = list.firstNotNullOfOrNull { t ->
                t.coverPath ?: LocalLibraryStore.existingCover(t.path)
            },
            tracks = list.sortedBy { it.track ?: Int.MAX_VALUE },
        )
    }.sortedWith { a, b -> text.compare(a.title, b.title) }
}

/**
 * 按 [sort] + 升降序排序。
 *
 * 文本字段（标题 / 艺术家 / 专辑）走 [localAlphabetComparator]：**字母桶位 → 拉丁优先 → ICU 中文排序**，
 * 所以中文按拼音、日文按罗马字，同桶里英文排在汉字前面（字母索引条能连续跳）。
 * 升降序只作用于**主键**：专辑倒序时，同一张专辑里的曲目仍然是 1、2、3 的顺序。
 */
fun sortTracks(
    tracks: List<LocalTrackMeta>,
    sort: LocalSort,
    ascending: Boolean = sort.defaultAscending,
): List<LocalTrackMeta> {
    if (tracks.size < 2) return tracks
    val text = localAlphabetComparator()
    /** 主键方向：降序就把比较结果取反 */
    fun dir(c: Int): Int = if (ascending) c else -c
    return when (sort) {
        LocalSort.byTitle -> tracks.sortedWith { a, b -> dir(text.compare(a.title, b.title)) }
        // 按歌手排序用展示串（`A / B`）：和屏幕上看到的一致，否则 `周杰伦、费玉清`
        // 会按「、」的位置排到别处去
        LocalSort.byArtist -> tracks.sortedWith(
            Comparator<LocalTrackMeta> { a, b -> dir(text.compare(a.artistDisplay, b.artistDisplay)) }
                .thenComparator { a, b -> text.compare(a.album, b.album) }
                .thenBy { it.track ?: Int.MAX_VALUE },
        )
        LocalSort.byAlbum -> tracks.sortedWith(
            Comparator<LocalTrackMeta> { a, b -> dir(text.compare(a.album, b.album)) }
                .thenComparator { a, b -> text.compare(a.artistDisplay, b.artistDisplay) }
                .thenBy { it.disc ?: 0 }
                .thenBy { it.track ?: Int.MAX_VALUE },
        )
        LocalSort.byDuration -> tracks.sortedWith { a, b -> dir(a.duration.compareTo(b.duration)) }
        LocalSort.byAdded -> tracks.sortedWith { a, b -> dir(a.mtime.compareTo(b.mtime)) }
    }
}

/** 搜索过滤：标题 / 艺术家 / 专辑 / 文件夹 / 文件名全覆盖 */
fun filterTracks(tracks: List<LocalTrackMeta>, query: String): List<LocalTrackMeta> {
    val q = query.trim()
    if (q.isEmpty()) return tracks
    return tracks.filter { t ->
        t.title.contains(q, ignoreCase = true) ||
            t.artist.contains(q, ignoreCase = true) ||
            t.album.contains(q, ignoreCase = true) ||
            t.folderName.contains(q, ignoreCase = true) ||
            t.path.substringAfterLast('/').contains(q, ignoreCase = true)
    }
}
