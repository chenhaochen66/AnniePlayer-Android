package com.annieplayer.annie_music.kt.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing

/**
 * 歌曲操作面板（⋮）+ 多歌手选择面板（1:1 移植 Flutter `lib/widgets/song_actions_sheet.dart`）。
 *
 * 两者动画都复用 [AppSheet]（150ms 线性滑入/滑出 + 遮罩），二级歌手选择面板在
 * 主面板之后声明 → 后注册的 `BackHandler` 优先消费返回手势，与 Flutter 的
 * `songActions` -> `artistPicker` 返回优先级一致（见 `core/routing/OverlayStack.kt`）。
 *
 * 结构（对齐 Dart 行为）：
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
 * 多歌手名分隔符：**不含 `&`**。结构化字段缺失时才会走这个兜底拆名，而 `&` 更常见的身份是
 * 「组合名的一部分」（Lucas & Steve / Simon & Garfunkel…），当分隔符用会把一个歌手劈成两个。
 */
private val SongArtistSeparators by lazy { Regex("\\s*[/、,]\\s*") }
/**
 * 从歌曲取出歌手列表（对齐 Dart `artistsOfSong`）：
 * 优先用结构化字段 `artistNames` / `artistIds`，缺失时按分隔符拆 `artist` 字符串。
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
    return song.artist
        .split(SongArtistSeparators)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { SongArtistRef(name = it, id = "") }
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
    modifier: Modifier = Modifier,
) {
    // 二级歌手选择面板可见性（面板收起时同步复位，避免下次打开直接闪出二级面板）
    var artistPickerVisible by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (!visible) artistPickerVisible = false
    }

    val artists = remember(song) { songArtistsOf(song) }
    val albumId = song?.albumId ?: ""
    val albumName = song?.album ?: ""

    Box(modifier = modifier.fillMaxSize()) {
        AppSheet(
            visible = visible && song != null,
            title = "歌曲操作",
            onClose = onClose,
        ) {
            // 下载 → 音质选择（先关面板，由宿主打开 picker）
            SongActionsRow(
                icon = { tint ->
                    DownloadIconPlaceholder(
                        modifier = Modifier.size(AppIconSize.icon20),
                        tint = tint,
                    )
                },
                label = "下载",
                subtitle = "选择音质后下载到本地",
                enabled = song != null,
                onTap = {
                    val current = song
                    if (current != null) {
                        onClose()
                        onDownload(current)
                    }
                },
            )
            // 歌手（单个直接跳；多个进二级选择，主面板保持可见）
            SongActionsRow(
                icon = { tint ->
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(AppIconSize.icon20),
                    )
                },
                label = "跳转到歌手",
                subtitle = if (artists.isEmpty()) "未知歌手" else artists.joinToString(" / ") { it.name },
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
            // 专辑（albumId 为空时置灰）
            SongActionsRow(
                icon = { tint ->
                    SongActionsAlbumGlyph(
                        modifier = Modifier.size(AppIconSize.icon20),
                        tint = tint,
                    )
                },
                label = "跳转到专辑",
                subtitle = if (albumName.isEmpty()) "未知专辑" else albumName,
                enabled = albumId.isNotEmpty(),
                onTap = {
                    val current = song
                    if (current != null) {
                        onClose()
                        onOpenAlbum(current)
                    }
                },
            )
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
 * 面板行（图标槽 + 文案 + 可选副标题 + 右箭头）。
 *
 * 为什么不用 [AppSheetRow]：`AppSheetRow` 只接受 `ImageVector`，而下载 / 专辑图标
 * 在 `material-icons-core` 中不存在，必须复用 [DownloadIconPlaceholder] 等 Canvas
 * 自绘组件。本行的内边距 / 字号 / 颜色与 `AppSheetRow` 逐项一致（16/14 内边距、
 * 图标 20、间距 12、标签 14sp、副标题 12sp @0.24、箭头 18 @0.24 / 禁用 @0.08）。
 */
@Composable
private fun SongActionsRow(
    icon: @Composable (tint: Color) -> Unit,
    label: String,
    onTap: () -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    val contentColor = if (enabled) Color.White else Color.White.copy(alpha = 0.15f)
    val interaction = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onTap,
            )
            .padding(horizontal = AppSpacing.sp16, vertical = AppSpacing.sp14),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon(contentColor)
        Spacer(modifier = Modifier.width(AppSpacing.sp12))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(text = label, color = contentColor, fontSize = AppFontSize.base)
            if (!subtitle.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(AppSpacing.sp2))
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.24f),
                    fontSize = AppFontSize.sm,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            imageVector = Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            tint = if (enabled) Color.White.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.08f),
            modifier = Modifier.size(AppIconSize.icon18),
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
