package com.annieplayer.annie_music.kt.widgets

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.annieplayer.annie_music.kt.core.state.PlaylistDetailData
import com.annieplayer.annie_music.kt.core.util.ArtistSplit
import com.annieplayer.annie_music.kt.data.local.LocalLibraryStore
import com.annieplayer.annie_music.kt.data.local.LocalTrackMeta
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 歌曲操作面板（⋮）+ 多歌手选择面板。
 *
 * **视觉已不是 Dart 的逐像素移植**：动作集的语义（每行 → 哪个回调）保持 1:1，但版式按
 * 「身份 + 分层动作」重做过 —— 顶部一张 [SheetHeroCard] 说明在操作哪首歌，下面按
 * 「这首歌 / 前往」分两组，只有跳转行带箭头，删除单独压在最后。逐条理由见 [SongActionsRow]。
 *
 * 两者动画都复用 [AppSheet]（150ms 线性滑入/滑出 + 遮罩），二级歌手选择面板在
 * 主面板之后声明 → 后注册的 `BackHandler` 优先消费返回手势，与 Flutter 的
 * `songActions` -> `artistPicker` 返回优先级一致（见 `core/routing/OverlayStack.kt`）。
 *
 * 结构（行为与 Dart 一致）：
 * - 单歌手：直接回调 [SongActionsSheet] 的 `onOpenArtist`（先关面板再跳）。
 * - 多歌手：主面板不关，弹出二级 [ArtistPickerSheet]，选中后关二级再跳。
 * - 专辑行：`albumId` 为空时置灰（`enabled = false`）。
 * - 下载行：先关面板，再回调 `onDownload`（由宿主打开音质选择）。
 */

/** 歌手引用：名字 + 平台 id（id 可能为空，需按名字搜索兜底）。 */
data class SongArtistRef(
    val name: String,
    val id: String,
)

/**
 * 从歌曲取出歌手列表（对齐 Dart `artistsOfSong`）：
 * 优先用结构化字段 `artistNames` / `artistIds`，缺失时按 [ArtistSplit] 拆 `artist` 字符串
 * （**Apple 例外**：它的合并串没法安全拆，见下）。
 *
 * 兜底拆分**不再**各写一份分隔符表：以前这里只认 `/,、`（刻意不含 `&`），
 * 而本地曲库那边认的是另一套 —— 同一个 `A feat. B` 在两边拆出来的结果不一样。
 * 误伤保护（`Simon & Garfunkel` / `AC/DC` / `and` / `·`）统一由 [ArtistSplit] 负责。
 */
fun songArtistsOf(song: Song?): List<SongArtistRef> {
    if (song == null) return emptyList()
    val names = song.artistNames
    // 仅当数量一致时按下标配对：否则整组不给 id（退回按名字搜索），
    // 也不要把 A 的名字配上 B 的 id（各源对 name/id 分别 filter 时会串位）
    val ids = song.artistIds.takeIf { it.size == names.size } ?: emptyList()
    if (names.isNotEmpty()) {
        return names.mapIndexed { i, name ->
            SongArtistRef(name = name, id = ids.getOrElse(i) { "" })
        }
    }
    // Apple 的 `artistName` 是**它自己的合并串**，而合并用的 `&` 也会出现在乐队名里
    //（「Lucas & Steve」本身就是一个歌手），按分隔符拆必然拆出假歌手。
    // 所以这里只给一整条 —— 点进去时 `openArtistDetail` 会先 `enrichAppleSong` 拿结构化列表。
    if (song.platform == MusicPlatform.apple) {
        return listOf(SongArtistRef(name = song.artist, id = ""))
    }
    return ArtistSplit.split(song.artist).map { SongArtistRef(name = it, id = "") }
}

/**
 * ⋮ 歌曲操作面板 + 二级歌手选择。
 *
 * 隐藏时保持挂载（对齐 Flutter 的常驻浮层约定），仅由 [AppSheet] 内部把面板移出屏外、
 * 遮罩停止吃触摸；`BackHandler` 也仅在本面板可见时启用。
 *
 * 回调语义（对齐 Dart 行内先关面板再动作的顺序）：
 * - [onClose]：宿主置 `songActionsVisible = false`。
 * - [onDownload]：宿主置 `pickerVisible = true, pickerSong = song`。
 * - [onOpenArtist]：宿主先收起播放页、再把歌手详情**压入真栈**
 *   （对齐 Dart `openArtistDetail` 的异步 `resolveArtist` + SnackBar 兜底）。
 * - [onOpenAlbum]：宿主先收起播放页、再把专辑详情压入真栈。
 */
@Composable
fun SongActionsSheet(
    visible: Boolean,
    song: Song?,
    onClose: () -> Unit,
    onDownload: (Song) -> Unit,
    onOpenArtist: (SongArtistRef) -> Unit,
    onOpenAlbum: (Song) -> Unit,
    /**
     * 本地歌曲的「删除」：本面板先自己收起，再把目标交给宿主弹确认框。
     *
     * 为什么不在面板内部弹：确认框必须活到「面板已关」之后，而 `AppSheet` 里所有二级浮层
     * 都挂在 `visible && ...` 上 —— 面板一关它们就跟着消失了。
     */
    onDelete: (Song) -> Unit = {},
    /**
     * 「跳转到歌单」的目标：当前播放队列来自某个歌单时才有（`PlayerState.queuePlaylist`）。
     *
     * 挂在全局 ⋮ 面板上而不是某个页面里 —— 从歌单开播之后，无论在播放页、搜索页、
     * 歌手页还是主页打开 ⋮，都能一键回到那个歌单。null 时整行不渲染。
     */
    playlistTarget: PlaylistDetailData? = null,
    onOpenPlaylist: (PlaylistDetailData) -> Unit = {},
    /**
     * 「加入到歌单」的候选（自建、与这首歌同一边、已带「是否已在里面」）。
     *
     * 空列表仍然会显示那一行 —— 点开是「还没有自建播放列表」的空态，
     * 比「功能看不见」诚实。本地歌只列本地歌单（混边在数据层就不允许）。
     */
    playlistOptions: List<SongPlaylistOption> = emptyList(),
    /** 加入某个自建歌单（**面板不关**，那一行随即变成「已加入」） */
    onAddToPlaylist: (playlistId: String) -> Unit = {},
    /**
     * 「我喜欢」这一行（与「加入到歌单」同一个二级面板的第一行）：
     * 这首歌是否已收藏、那一边一共收藏了多少首。见 [onAddToFavorites]。
     */
    favoriteSelected: Boolean = false,
    favoriteCount: Int = 0,
    /**
     * 收藏到「我喜欢」/ 取消收藏（开关）。null = 整行不渲染。
     *
     * 和「加入到歌单」放进同一个面板：用户心里这两件事是同一个动作（「把这首收起来」），
     * 分成两个入口（一个在 ⋮、一个在心形按钮）只会让人多点一次。
     * 与自建歌单的「已加入」不同，这一行点第二次是**取消收藏**（面板不关，行变回「+」）。
     */
    onAddToFavorites: (() -> Unit)? = null,
    /**
     * 「从本歌单移除」：只有从自建歌单详情页打开面板时才有（宿主传 null 即整行不渲染）。
     *
     * 不移除确认：这是可撤销动作（再加回来就行），加确认只会多一次点击。
     */
    onRemoveFromPlaylist: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // 二级歌手选择面板可见性（面板收起时同步复位，避免下次打开直接闪出二级面板）
    var artistPickerVisible by remember { mutableStateOf(false) }
    var playlistPickerVisible by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (!visible) {
            artistPickerVisible = false
            playlistPickerVisible = false
        }
    }

    val artists = remember(song) { songArtistsOf(song) }
    val albumId = song?.albumId ?: ""
    val albumName = song?.album ?: ""
    // 本地文件（本地曲库 / 已下载）：没有「下载」语义，换成「歌曲详情」
    val isLocalSong = song?.platform == MusicPlatform.local
    var localDetailsVisible by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (!visible) localDetailsVisible = false
    }

    Box(modifier = modifier.fillMaxSize()) {
        AppSheet(
            visible = visible && song != null,
            title = "歌曲操作",
            onClose = onClose,
            // 高度上限 = 面板可用高度全额（扣抓手 / 标题 / 底部 inset 之后）。
            // 也就是「能长多高长多高，顶到屏幕才开始滚」—— 内容刚好多到 1~2 行时
            // 面板会就地变高，不会立刻出现滚动条。原行为（null）在小屏上会把抓手和标题顶出屏幕。
            contentMaxHeightFraction = 1f,
        ) {
            // ── 身份 ──
            // 面板标题只说「歌曲操作」，不说操作的是哪首；从播放页 / 搜索页 / 歌手页
            // 打开都一样。先把对象摆出来，下面两组的动作才有主语。
            val current = song
            if (current != null) {
                SheetHeroCard(
                    title = current.title.ifBlank { "未知标题" },
                    subtitle = listOfNotNull(current.artist, current.album)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                        .ifBlank { "未知艺术家" },
                    cover = current.cover.takeIf { it.isNotBlank() },
                )
            }

            // 动作：一条连续列表，不分组（参照 Apple Music 的动作菜单）。
            // 加入到歌单：**下载之上、第一个动作行**。两种情况都有这一项，只是候选不同：
            // 在线歌列 `scope = online` 的自建歌单，本地歌列 `scope = local` 的
            // （分边由宿主算好传进来，这里不判断平台）。
            SongActionsRow(
                icon = { tint ->
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(AppIconSize.icon22),
                    )
                },
                label = "加入到歌单",
                subtitle = if (playlistOptions.isEmpty()) {
                    "我喜欢"
                } else {
                    "我喜欢 · ${playlistOptions.size} 个歌单"
                },
                showsChevron = false,
                onTap = { playlistPickerVisible = true },
            )

            if (isLocalSong) {
                // 本地文件：第一行是「歌曲详情」（替代流媒体的「下载」）。
                // 图标用信息圆点 —— 之前复用了「跳转到专辑」的唱片图标，两行长得一模一样。
                SongActionsRow(
                    icon = { tint ->
                        SongActionsInfoGlyph(
                            modifier = Modifier.size(AppIconSize.icon22),
                            tint = tint,
                        )
                    },
                    label = "歌曲详情",
                    subtitle = "文件路径 / 编码 / 位深 / 采样率",
                    showsChevron = false,
                    onTap = { localDetailsVisible = true },
                )
            } else {
                // 下载 → 音质选择（先关面板，由宿主打开 picker）。全面板唯一的主操作。
                SongActionsRow(
                    icon = { tint ->
                        DownloadIconPlaceholder(
                            modifier = Modifier.size(AppIconSize.icon22),
                            tint = tint,
                        )
                    },
                    label = "下载",
                    subtitle = "选择音质",
                    tone = SongActionTone.Accent,
                    showsChevron = false,
                    onTap = {
                        if (current != null) {
                            onClose()
                            onDownload(current)
                        }
                    },
                )
            }

            // 歌手（单个直接跳；多个进二级选择，主面板保持可见）。
            // 文案自带「跳转到」：没有小节标题了，动词必须由行本身给出，
            // 否则「歌手」既可读成「看歌手」也可读成「按歌手筛选」。
            SongActionsRow(
                icon = { tint ->
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(AppIconSize.icon22),
                    )
                },
                label = "跳转到歌手",
                subtitle = if (artists.isEmpty()) "未知歌手" else artists.joinToString(" / ") { it.name },
                subtitleIsValue = true,
                enabled = artists.isNotEmpty(),
                onTap = {
                    if (artists.size == 1) {
                        onClose()
                        onOpenArtist(artists[0])
                    } else {
                        artistPickerVisible = true
                    }
                },
            )
            // 专辑（云端平台要求 albumId；本地歌没有 albumId，靠 album 名 + 专辑艺术家定位）
            SongActionsRow(
                icon = { tint ->
                    SongActionsAlbumGlyph(
                        modifier = Modifier.size(AppIconSize.icon22),
                        tint = tint,
                    )
                },
                label = "跳转到专辑",
                subtitle = if (albumName.isEmpty()) "未知专辑" else albumName,
                subtitleIsValue = true,
                enabled = albumId.isNotEmpty() || isLocalSong,
                onTap = {
                    if (current != null) {
                        onClose()
                        onOpenAlbum(current)
                    }
                },
            )
            // 歌单：只有「这份队列从某个歌单开始播」时才有目标（见 playlistTarget 注释）
            val playlist = playlistTarget
            if (playlist != null) {
                SongActionsRow(
                    icon = { tint ->
                        PlaylistParseGlyph(
                            modifier = Modifier.size(AppIconSize.icon22),
                            tint = tint,
                        )
                    },
                    label = "跳转到歌单",
                    subtitle = playlist.name.orEmpty(),
                    subtitleIsValue = true,
                    onTap = {
                        onClose()
                        onOpenPlaylist(playlist)
                    },
                )
            }

            // 从本歌单移除：只有「从自建歌单详情页」打开面板时才有。
            // 与「删除」同在最底部，但它排在删除**之上** —— 删文件不可逆，
            // 破坏性更强的那个永远压最底。
            if (onRemoveFromPlaylist != null) {
                Spacer(modifier = Modifier.height(AppSpacing.sp8))
                SongActionsRow(
                    icon = { tint ->
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(AppIconSize.icon22),
                        )
                    },
                    label = "从本歌单移除",
                    subtitle = "不会删除下载的文件",
                    showsChevron = false,
                    onTap = {
                        onClose()
                        onRemoveFromPlaylist()
                    },
                )
            }

            // 删除：破坏性操作不跟其它行连排，用一段留白隔开压在最后，
            // 手指从上往下滑时不会顺势点到它。
            // 只有本地文件才有这个语义（云端歌曲的「删除」应该是取消下载，不属于本面板）。
            // 点它先收起面板，由宿主弹确认框（见 [onDelete] 注释）。
            if (isLocalSong) {
                Spacer(modifier = Modifier.height(AppSpacing.sp8))
                SongActionsRow(
                    icon = { tint ->
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(AppIconSize.icon22),
                        )
                    },
                    label = "删除",
                    subtitle = "从曲库移除，可同时删除本地文件",
                    tone = SongActionTone.Danger,
                    showsChevron = false,
                    onTap = {
                        onClose()
                        onDelete(current)
                    },
                )
            }
            // 面板底部留白：末行不要贴着手势条
            Spacer(modifier = Modifier.height(AppSpacing.sp6))
        }

        // 二级：多歌手选择（在主面板之后声明 → 后注册的 BackHandler 优先）
        ArtistPickerSheet(
            visible = visible && artistPickerVisible,
            artists = artists,
            onClose = { artistPickerVisible = false },
            onSelect = { artist ->
                artistPickerVisible = false
                // 与单歌手分支保持一致：跳转前先关掉「歌曲操作」主面板，
                // 否则多歌手选择后跳到歌手详情，主面板仍留在屏幕上不消失
                onClose()
                onOpenArtist(artist)
            },
        )

        // 二级：本地文件详情（仅本地歌曲；替代流媒体的「下载」入口）
        LocalFileDetailsSheet(
            visible = visible && localDetailsVisible,
            song = song,
            onClose = { localDetailsVisible = false },
        )

        // 二级：选择播放列表（点「加入到歌单」弹出）。
        // 加入后**面板不关**：宿主 reload 之后 `playlistOptions` 会把这个歌单变成
        // 「已加入」，用户可以接着加进别的歌单（也接着收进「我喜欢」）。
        PlaylistPickerSheet(
            visible = visible && playlistPickerVisible,
            options = playlistOptions,
            favoriteSelected = favoriteSelected,
            favoriteCount = favoriteCount,
            onClose = { playlistPickerVisible = false },
            onSelect = { onAddToPlaylist(it) },
            onAddToFavorites = onAddToFavorites,
        )
    }
}

/**
 * 本地文件详情面板（本地歌曲的「歌曲详情」）。
 *
 * 数据来自 `LocalLibraryStore` 的曲库索引（`Song.id` = `local:<绝对路径>`）。
 *
 * **字段集 = 下载打标写入的字段集**（`FfmpegTagger.tagInPlace`）：
 * 标题 / 艺术家 / 专辑 / 专辑艺术家 / 年份（`date`）/ 曲目 / 碟号 / 流派 / 作曲 / ISRC /
 * 版本（`comment`）/ 版权 —— 少一个就会出现「我们自己写进去的标签，自己的详情页看不到」。
 * 之下才是文件侧的技术参数（编码 / 位深 / 采样率 / 码率 / 声道 / 大小 / 时长）与
 * 文件信息（添加时间 / 播放次数 / 目录 / 路径）。
 *
 * 歌词**不进这个面板**：它属于播放页的展示物，且写入的是整段 LRC 文本，
 * 列在这里既读不下也没意义。
 *
 * 索引里没有记录（例如刚下载、还没扫进曲库）时回落到 [Song] 自带的字段。
 */
@Composable
private fun LocalFileDetailsSheet(
    visible: Boolean,
    song: Song?,
    onClose: () -> Unit,
) {
    val path = song?.id?.removePrefix("local:").orEmpty()
    // 用 [LocalLibraryStore.trackOf]（内存副本）而不是 `loadIndex()`：
    // 后者每次打开面板都要在主线程读盘 + 解析整份 `index.json`（几千首时是几百 KB 的
    // JSON，几十毫秒），而曲库扫描结束时早就把同一份数据放进了内存。字段多了以后这个
    // 面板会被点得更频繁，这里必须是 O(1) 查表。
    val meta = remember(path, visible) {
        if (path.isEmpty()) null else LocalLibraryStore.trackOf(path)
    }
    // 播放次数：与「最近播放」同一份记录（一次读盘，面板打开时算一次）
    val playCount = remember(path, visible) {
        if (path.isEmpty()) {
            0
        } else {
            LocalLibraryStore.loadRecentPlays().firstOrNull { it.path == path }?.count ?: 0
        }
    }
    // 添加时间 = 索引里的 mtime（与「添加时间」排序用的同一个字段）
    val addedText = remember(meta?.mtime) {
        val mtime = meta?.mtime ?: 0L
        if (mtime <= 0L) "—"
        else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(mtime))
    }

    AppSheet(
        visible = visible && song != null,
        title = "歌曲详情",
        onClose = onClose,
        // 固定「一多半屏 + 内部滚动」：字段集与打标一一对应后一共 19 行，
        // 按内容撑高会在小屏上把抓手和标题顶出屏幕外。
        contentHeightFraction = 0.55f,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                DetailHeroCard(song = song, meta = meta)

                // ── 标签（打标端 FfmpegTagger 写了哪些，这里就显示哪些）──
                DetailSection("标签")
                DetailInfoRow(
                    "艺术家",
                    meta?.artistDisplay?.ifBlank { null } ?: song?.artist.orEmpty(),
                )
                DetailInfoRow(
                    "专辑",
                    meta?.album?.ifBlank { null } ?: song?.album.orEmpty(),
                )
                DetailInfoRow(
                    "专辑艺术家",
                    meta?.albumArtist?.ifBlank { null } ?: song?.albumArtist.orEmpty(),
                )
                DetailInfoRow("年份", meta?.year?.ifBlank { null } ?: song?.releaseDate.orEmpty())
                // 曲目 / 碟号：写入端是 MP3 的 TRCK/TPOS、FLAC 的 TRACKNUMBER/DISCNUMBER
                DetailInfoRow(
                    "曲目",
                    meta?.trackDiscText?.ifBlank { null }
                        ?: listOfNotNull(song?.trackNumber, song?.discNumber)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                )
                DetailInfoRow("流派", meta?.genre?.ifBlank { null } ?: song?.genre.orEmpty())
                DetailInfoRow("作曲", meta?.composer?.ifBlank { null } ?: song?.composer.orEmpty())
                DetailInfoRow("ISRC", meta?.isrc?.ifBlank { null } ?: song?.isrc.orEmpty())
                // 版本说明：打标写进 `comment`（Qobuz 的 version / Apple 的 Remastered）
                DetailInfoRow("版本", meta?.version.orEmpty())
                DetailInfoRow("版权", meta?.copyright.orEmpty())

                // ── 技术参数 ──
                DetailSection("音质")
                DetailInfoRow(
                    "编码",
                    listOfNotNull(
                        meta?.codec?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() },
                        meta?.formatText?.takeIf { it.isNotEmpty() },
                        meta?.bitrate?.takeIf { it > 0 }?.let { "${it}kbps" },
                        meta?.channels?.takeIf { it > 0 }?.let { "${it}ch" },
                    ).joinToString(" · ").ifEmpty { "—" },
                )
                DetailInfoRow(
                    "音质",
                    listOfNotNull(
                        meta?.qualityChip?.takeIf { it.isNotEmpty() },
                        if ((meta?.lossless == true)) "无损" else null,
                    ).joinToString(" · ").ifEmpty { "—" },
                )
                DetailInfoRow("时长", meta?.durationText?.ifEmpty { null } ?: "—")
                DetailInfoRow("大小", meta?.sizeText?.ifEmpty { null } ?: "—")

                // ── 文件 ──
                DetailSection("文件")
                DetailInfoRow("添加时间", addedText)
                DetailInfoRow("播放次数", if (playCount > 0) "$playCount 次" else "未播放")
                DetailInfoRow("文件夹", meta?.folderName?.ifBlank { null } ?: "—")
                DetailInfoRow("路径", path.ifEmpty { "—" })
                Spacer(modifier = Modifier.height(AppSpacing.sp32))
            }
            // 底部渐隐：滚动区把最后一行的底边遮掉一点，给出「下面还有」的暗示，
            // 同时避免内容半截被硬切在圆角上。
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(AppSpacing.sp20)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, AppColors.surface),
                        ),
                    ),
            )
        }
    }
}

/**
 * 面板顶部的主信息卡：封面 + 标题 + 歌手 + 规格角标。
 *
 * 字段怎么取：本地索引（[meta]）优先，回落 [Song] 自带字段；视觉本身交给
 * [SheetHeroCard]（歌曲操作面板用的是同一张卡，改样式只改那一处）。
 */
@Composable
private fun DetailHeroCard(song: Song?, meta: LocalTrackMeta?) {
    val title = meta?.title?.ifBlank { null } ?: song?.title.orEmpty()
    // 多歌手统一成 `A / B`：与列表行 / 播放页同一份格式化（单歌手与原始标签完全相同）
    val artist = meta?.artistDisplay?.ifBlank { null } ?: song?.artist.orEmpty()
    // 本地曲库的封面是抽到 `covers/` 的文件（file://）；已下载但未扫进曲库的走 Song 的远端地址
    val cover = meta?.coverPath?.let { "file://$it" }?.takeIf { it.isNotEmpty() }
        ?: song?.cover?.takeIf { it.isNotEmpty() }
    SheetHeroCard(
        title = title.ifBlank { "未知标题" },
        subtitle = artist.ifBlank { "未知艺术家" },
        cover = cover,
        // 顺序即重要度：音质档位（主色）→ 时长 → 体积
        chips = listOfNotNull(
            meta?.qualityChip?.takeIf { it.isNotBlank() }?.let { SheetHeroChip(it, accent = true) },
            meta?.durationText?.takeIf { it.isNotBlank() }?.let { SheetHeroChip(it) },
            meta?.sizeText?.takeIf { it.isNotBlank() }?.let { SheetHeroChip(it) },
        ),
    )
}

/** 信息分组的小标题 + 一条向右延伸的细线（不用整块背景，保持轻盈） */
@Composable
private fun DetailSection(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = AppSpacing.sp16,
                end = AppSpacing.sp16,
                top = AppSpacing.sp16,
                bottom = AppSpacing.sp6,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = AppColors.textTertiary,
            fontSize = AppFontSize.size10,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
        )
        Spacer(modifier = Modifier.width(AppSpacing.sp8))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(AppColors.whiteA04),
        )
    }
}

/** 「标签 —— 值」两列信息行（标签定宽、值可换行） */
@Composable
private fun DetailInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.sp16, vertical = AppSpacing.sp6),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            color = AppColors.textTertiary,
            // 标签比正文小一号：19 行里标签只是"字段名"，不该和值抢注意力。
            // 72dp 是因为最长的标签是「专辑艺术家」（5 字 ≈ 60dp @12sp），56dp 会折行。
            fontSize = AppFontSize.sm,
            modifier = Modifier
                .width(72.dp)
                .padding(top = 1.dp),
        )
        Text(
            text = value.ifBlank { "—" },
            color = AppColors.textSecondary,
            fontSize = AppFontSize.size13,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 多歌手选择面板（对齐 Dart `ArtistPickerSheet`，复用 [AppSheet] + [AppSheetRow]）。 */
@Composable
fun ArtistPickerSheet(
    visible: Boolean,
    artists: List<SongArtistRef>,
    onClose: () -> Unit,
    onSelect: (SongArtistRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    AppSheet(
        visible = visible,
        title = "选择歌手",
        onClose = onClose,
        // 歌手数量不定：独唱 2 个、合唱/群星 20+ 个都常见。
        // 按内容撑高时，20 个歌手会把面板一路顶到屏幕最上面，抓手和标题贴住状态栏，
        // 看着像「整页」而不是「面板」。这里限制最多半屏，超出的在内容区里滑。
        contentMaxHeightFraction = 0.5f,
        modifier = modifier,
    ) {
        for (artist in artists) {
            AppSheetRow(
                icon = Icons.Default.Person,
                label = artist.name,
                onTap = { onSelect(artist) },
            )
        }
    }
}

/**
 * 行基调：决定图标底片 / 图标 / 文字的颜色。
 *
 * - [Neutral] 普通动作（跳转）；
 * - [Accent] 唯一的主操作（下载）：主色底片 + 主色图标；
 * - [Danger] 破坏性操作（删除）：红系底片 + 红字。
 *
 * 用枚举而不是三个 `Color?` 参数：颜色的**组合**（底片 / 图标 / 文字）必须同步变化，
 * 分开传就会出现「红字配主色底片」这种没人想过的中间态。
 */
private enum class SongActionTone { Neutral, Accent, Danger }

/**
 * 面板行：图标 + 文案 + 可选副标题 + 可选右箭头，行高 ≈66dp。
 *
 * 为什么不用 [AppSheetRow]：`AppSheetRow` 只接受 `ImageVector`，而下载 / 专辑图标
 * 在 `material-icons-core` 中不存在，必须复用 [DownloadIconPlaceholder] 等 Canvas
 * 自绘组件。也因此本行自己维护视觉，不再与 `AppSheetRow` 逐项对齐。
 *
 * 版式参照 Apple Music 的动作菜单（用户给的参考）：
 * 1. **图标不着底片**：22dp 图形直接坐在行首，靠尺寸与 16dp 间距和文字拉开。
 *    之前试过 36dp 圆角底片，六个色块并排会把面板切碎 —— 大面积留白比色块更安静。
 * 2. **行高 66dp、标签 16sp**：动作菜单是「伸手指」的界面，行距和字号都要大于列表行。
 * 3. **箭头是语义**：[showsChevron] 只给「点下去会离开这个面板」的行（歌手 / 专辑 / 歌单）。
 *    下载、删除就地生效，不给箭头 —— 有没有箭头，一眼看出点下去是换页还是干活。
 * 4. **按压反馈**：整行铺 5% 白底、90ms 过渡（旧版 `indication = null` 完全无反馈）。
 *    走 `background`，纯 draw 阶段，不触发布局。
 *
 * 副标题分两种对比度（[subtitleIsValue]）：目标名（歌手名 / 专辑名）是信息，用 60% 白
 * （surface 上 6.84:1）；功能说明（「选择音质」）是注解，压到 38%（3.54:1）。
 */
@Composable
private fun SongActionsRow(
    icon: @Composable (tint: Color) -> Unit,
    label: String,
    onTap: () -> Unit,
    subtitle: String? = null,
    subtitleIsValue: Boolean = false,
    tone: SongActionTone = SongActionTone.Neutral,
    showsChevron: Boolean = true,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressedBg by animateColorAsState(
        targetValue = if (pressed && enabled) AppColors.whiteA05 else Color.Transparent,
        animationSpec = tween(durationMillis = 90),
        label = "songActionRowPress",
    )

    // 禁用态图标与文字同步压暗：只把文字变灰、图标仍亮着，
    // 会读成「这一行还能点，只是名字暗了点」。
    val iconTint = when {
        !enabled -> AppColors.textDisabled
        tone == SongActionTone.Accent -> AppColors.primary
        tone == SongActionTone.Danger -> AppColors.danger
        else -> AppColors.white70
    }
    val labelColor = when {
        !enabled -> AppColors.textDisabled
        tone == SongActionTone.Danger -> AppColors.danger
        else -> AppColors.textPrimary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 左右各留 8dp：按压底色内缩成一条圆角带，不贴面板边缘形成「通栏色块」
            .padding(horizontal = AppSpacing.sp8)
            .clip(RoundedCornerShape(AppRadius.r10))
            .background(pressedBg)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onTap,
            )
            // 内缩 8 后与 16 的面板边距对齐；上下 14 → 两行文字 38 + 28 = 66dp 行高
            .padding(horizontal = AppSpacing.sp8, vertical = AppSpacing.sp14),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon(iconTint)
        Spacer(modifier = Modifier.width(AppSpacing.sp16))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(text = label, color = labelColor, fontSize = AppFontSize.lg)
            if (!subtitle.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(AppSpacing.sp2))
                Text(
                    text = subtitle,
                    color = when {
                        !enabled -> AppColors.textDisabled
                        // 目标名是信息，要读得清：60% 白 = 6.84:1（surface #1A1A2E）
                        subtitleIsValue -> AppColors.white60
                        // 功能说明是注解层：38% 白 = 3.54:1，与全局 textTertiary 同级
                        else -> AppColors.white38
                    },
                    fontSize = AppFontSize.sm,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (showsChevron) {
            Spacer(modifier = Modifier.width(AppSpacing.sp8))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                // 38% 白 = 3.54:1，过「非文本 UI 元素 3:1」；24%（旧值）只有 2.19:1
                tint = if (enabled) AppColors.glyphMid else AppColors.white10,
                modifier = Modifier.size(AppIconSize.icon18),
            )
        }
    }
}

/**
 * 信息图标（自绘：描边圆 + 中间的 i）。
 *
 * `material-icons-core` 不含 `Info`，所以用 Canvas 画一个；笔重与 [SongActionsAlbumGlyph]
 * 统一（0.09f 圆环 + 实心笔画），和「跳转到专辑」的唱片图标区分开。
 */
@Composable
private fun SongActionsInfoGlyph(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier = modifier) {
        val side = size.minDimension
        val stroke = side * 0.09f
        drawCircle(color = tint, radius = (side - stroke) / 2f, style = Stroke(width = stroke))
        // i 的点
        drawCircle(color = tint, radius = side * 0.055f, center = Offset(side / 2f, side * 0.31f))
        // i 的竖
        drawRoundRect(
            color = tint,
            topLeft = Offset(side * 0.44f, side * 0.43f),
            size = Size(side * 0.12f, side * 0.30f),
            cornerRadius = CornerRadius(side * 0.06f, side * 0.06f),
        )
    }
}

/**
 * 专辑图标（自绘：描边圆盘 + 中心圆点，对齐 Flutter `Icons.album_outlined`）。
 * `material-icons-core` 不含 Album 图标，故用 Canvas 等价绘制。
 */
@Composable
private fun SongActionsAlbumGlyph(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier = modifier) {
        val side = size.minDimension
        val stroke = side * 0.09f
        drawCircle(color = tint, radius = (side - stroke) / 2f, style = Stroke(width = stroke))
        drawCircle(color = tint, radius = side * 0.15f)
    }
}
