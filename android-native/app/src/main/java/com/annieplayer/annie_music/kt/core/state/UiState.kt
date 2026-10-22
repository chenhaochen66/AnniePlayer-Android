package com.annieplayer.annie_music.kt.core.state

import androidx.lifecycle.ViewModel
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UI 状态（1:1 移植 Flutter `core/providers/` 下的 `ui_provider.dart`，对齐 RN 版
 * store/useUIStore.ts）。
 *
 * 本层只承载「浮层可见性 + 详情数据」这一组纯 UI 状态。浮层之间叠放顺序敏感，
 * 因此字段名与 Dart 完全一致：songActionsVisible / artistPickerVisible /
 * pickerVisible / drawerVisible / playerOverlayVisible / queueVisible /
 * downloadQueueVisible，UI 层据此镜像同一套层序。
 *
 * 与 Dart 的唯一结构性差异：专辑 / 歌手 / 歌单三个全屏详情不再各占一对
 * `xDetailVisible` + `xDetailData` 布尔槽，而是共用一条**真栈** [detailStack]：
 * 从任意详情跳转到另一个详情时原详情不被收起，返回逐层退回（见 [DetailEntry]）。
 */

/** 专辑详情数据（对齐 Dart `AlbumDetailData`） */
data class AlbumDetailData(
    val id: String,
    val cover: String,
    val name: String,
    val artist: String,
    /** 所属平台（详情页按它取曲目） */
    val platform: MusicPlatform = MusicPlatform.kuwo,
)

/** 歌手详情数据（对齐 Dart `SingerDetailData`） */
data class SingerDetailData(
    val id: String,
    val name: String,
    val picture: String,
    /** 所属平台（详情页按它取单曲 / 专辑） */
    val platform: MusicPlatform = MusicPlatform.kuwo,
)

/** 歌单详情数据（对齐 Dart `PlaylistDetailData`） */
data class PlaylistDetailData(
    val id: String,
    /** 平台标识（Dart 为 String，UI 层原样透传） */
    val platform: String,
    val name: String? = null,
    val cover: String? = null,
)

/** 详情类型（与三个全屏详情浮层一一对应，供 [UiViewModel.closeDetailKind] 按类型批量关闭）。 */
enum class DetailKind { Album, Singer, Playlist }

/**
 * 详情浮层栈条目：专辑 / 歌手 / 歌单三个全屏详情共用一条真栈。
 *
 * 旧实现里三者各占一对可见性布尔 + 数据槽，互跳前必须先关掉其它详情；改为真栈后，
 * 从任意详情跳到另一个详情只是把新详情**压栈**（原详情留在下层，自身的滚动位置 /
 * 已加载数据 / 多选状态全部保留），返回逐层退回。
 *
 * [key] 是栈内唯一标识，用于渲染时的 `key(entry.key)` 维持条目身份。用进程内自增
 * 序号生成（而非仅取 data.id），保证「同类型多层」（专辑A→歌手S→专辑B，甚至同一张
 * 专辑重复入栈）也不会撞键。
 */
sealed interface DetailEntry {
    /** 栈内唯一键（含自增序号，同类型多层也不重复）。 */
    val key: String

    /** 条目类型。 */
    val kind: DetailKind
}

/** 进程内自增序号：为每个入栈条目分配唯一 key。 */
private val detailEntrySeq = java.util.concurrent.atomic.AtomicLong(0L)

/** 专辑详情栈条目。 */
class AlbumDetailEntry(val data: AlbumDetailData) : DetailEntry {
    override val kind: DetailKind = DetailKind.Album
    override val key: String = "album:${detailEntrySeq.incrementAndGet()}"
}

/** 歌手详情栈条目。 */
class SingerDetailEntry(val data: SingerDetailData) : DetailEntry {
    override val kind: DetailKind = DetailKind.Singer
    override val key: String = "singer:${detailEntrySeq.incrementAndGet()}"
}

/** 歌单详情栈条目。 */
class PlaylistDetailEntry(val data: PlaylistDetailData) : DetailEntry {
    override val kind: DetailKind = DetailKind.Playlist
    override val key: String = "playlist:${detailEntrySeq.incrementAndGet()}"
}

/** 纯 UI 状态（对齐 Dart `UIState`） */
data class UiState(
    val playerOverlayVisible: Boolean = false,
    val queueVisible: Boolean = false,
    val drawerVisible: Boolean = false,
    val pickerVisible: Boolean = false,
    /** ⋮ 歌曲操作面板是否可见 */
    val songActionsVisible: Boolean = false,
    val songActionsSong: Song? = null,
    /** 多歌手选择面板是否可见 */
    val artistPickerVisible: Boolean = false,
    val pickerSong: Song? = null,
    val contactVisible: Boolean = false,

    /** 「清除缓存」弹窗（列出各类缓存体积，勾选后清理） */
    val cacheClearVisible: Boolean = false,
    val isDragging: Boolean = false,
    val seekRatio: Double = 0.0,
    /** 全屏详情真栈（栈底在前、栈顶在后；栈顶渲染在最上层、返回先处理）。 */
    val detailStack: List<DetailEntry> = emptyList(),
    val downloadQueueVisible: Boolean = false,
    /** 「更多设置」整页是否可见 */
    val moreSettingsVisible: Boolean = false,
    /** 「落雪音源」插件管理页是否可见 */
    val lxPluginManagerVisible: Boolean = false,
    val qobuzTokenVisible: Boolean = false,
    /** 「Apple Music 服务」（本机 wrapper-lite）浮层是否可见 */
    val appleServiceVisible: Boolean = false,
    val kkboxAccountVisible: Boolean = false,
    val showLyricsTranslation: Boolean = true,
    /** 逐字歌词展示开关（有逐字数据时生效） */
    val showWordByWord: Boolean = true,
    val currentLineIndex: Int = -1,
) {
    /**
     * 局部更新（对齐 Dart `UIState.copyWith`）。
     *
     * 注意：与 Dart 语义一致，可空字段传 `null` **不会**清空原值（`?: this.xxx` 回退），
     * 因为 Dart 原实现即如此（非幂等 setter）。
     */
    fun copyWith(
        playerOverlayVisible: Boolean? = null,
        queueVisible: Boolean? = null,
        drawerVisible: Boolean? = null,
        pickerVisible: Boolean? = null,
        songActionsVisible: Boolean? = null,
    songActionsSong: Song? = null,
        artistPickerVisible: Boolean? = null,
        pickerSong: Song? = null,
        contactVisible: Boolean? = null,
    cacheClearVisible: Boolean? = null,
        isDragging: Boolean? = null,
        seekRatio: Double? = null,
        detailStack: List<DetailEntry>? = null,
        downloadQueueVisible: Boolean? = null,
        moreSettingsVisible: Boolean? = null,
        lxPluginManagerVisible: Boolean? = null,
        qobuzTokenVisible: Boolean? = null,
        appleServiceVisible: Boolean? = null,
        kkboxAccountVisible: Boolean? = null,
        showLyricsTranslation: Boolean? = null,
        showWordByWord: Boolean? = null,
        currentLineIndex: Int? = null,
    ): UiState = UiState(
        playerOverlayVisible = playerOverlayVisible ?: this.playerOverlayVisible,
        queueVisible = queueVisible ?: this.queueVisible,
        drawerVisible = drawerVisible ?: this.drawerVisible,
        pickerVisible = pickerVisible ?: this.pickerVisible,
        songActionsVisible = songActionsVisible ?: this.songActionsVisible,
        songActionsSong = songActionsSong ?: this.songActionsSong,
        artistPickerVisible = artistPickerVisible ?: this.artistPickerVisible,
        pickerSong = pickerSong ?: this.pickerSong,
        contactVisible = contactVisible ?: this.contactVisible,
        cacheClearVisible = cacheClearVisible ?: this.cacheClearVisible,
        isDragging = isDragging ?: this.isDragging,
        seekRatio = seekRatio ?: this.seekRatio,
        detailStack = detailStack ?: this.detailStack,
        downloadQueueVisible = downloadQueueVisible ?: this.downloadQueueVisible,
        moreSettingsVisible = moreSettingsVisible ?: this.moreSettingsVisible,
        lxPluginManagerVisible = lxPluginManagerVisible ?: this.lxPluginManagerVisible,
        qobuzTokenVisible = qobuzTokenVisible ?: this.qobuzTokenVisible,
        appleServiceVisible = appleServiceVisible ?: this.appleServiceVisible,
        kkboxAccountVisible = kkboxAccountVisible ?: this.kkboxAccountVisible,
        showLyricsTranslation = showLyricsTranslation ?: this.showLyricsTranslation,
        showWordByWord = showWordByWord ?: this.showWordByWord,
        currentLineIndex = currentLineIndex ?: this.currentLineIndex,
    )
}

/** UI 状态 ViewModel（对齐 Dart `UINotifier`，Riverpod Notifier -> Kotlin ViewModel + StateFlow） */
class UiViewModel : ViewModel() {

    private val _state = MutableStateFlow(UiState())

    /** 只读状态流（UI 层 collect 渲染） */
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun setPlayerOverlayVisible(visible: Boolean) {
        _state.value = _state.value.copyWith(playerOverlayVisible = visible)
    }

    fun setQueueVisible(visible: Boolean) {
        _state.value = _state.value.copyWith(queueVisible = visible)
    }

    fun setDrawerVisible(visible: Boolean) {
        _state.value = _state.value.copyWith(drawerVisible = visible)
    }

    /** 播放选择器（pickerVisible + pickerSong 一并设置；song 为 null 时不覆盖旧值，对齐 Dart） */
    fun setPicker(visible: Boolean, song: Song? = null) {
        _state.value = _state.value.copyWith(pickerVisible = visible, pickerSong = song)
    }

    /** ⋮ 歌曲操作面板（[song] 为本次要操作的歌；任意页面行尾都能呼出） */
    fun setSongActionsVisible(visible: Boolean, song: Song? = null) {
        _state.value = _state.value.copyWith(songActionsVisible = visible, songActionsSong = song)
    }

    /** 多歌手选择面板 */
    fun setArtistPickerVisible(visible: Boolean) {
        _state.value = _state.value.copyWith(artistPickerVisible = visible)
    }

    fun setContactVisible(visible: Boolean) {
        _state.value = _state.value.copyWith(contactVisible = visible)
    }

    fun setCacheClearVisible(visible: Boolean) {
        _state.value = _state.value.copyWith(cacheClearVisible = visible)
    }

    /** 拖动进度条（seekRatio 为 null 时归 0，对齐 Dart） */
    fun setDragging(dragging: Boolean, seekRatio: Double? = null) {
        _state.value = _state.value.copyWith(isDragging = dragging, seekRatio = seekRatio ?: 0.0)
    }

    fun setDownloadQueueVisible(visible: Boolean) {
        _state.value = _state.value.copyWith(downloadQueueVisible = visible)
    }

    fun setMoreSettingsVisible(visible: Boolean) {
        _state.value = _state.value.copyWith(moreSettingsVisible = visible)
    }

    fun setLxPluginManagerVisible(visible: Boolean) {
        _state.value = _state.value.copyWith(lxPluginManagerVisible = visible)
    }

    fun setQobuzTokenVisible(visible: Boolean) {
        _state.value = _state.value.copyWith(qobuzTokenVisible = visible)
    }

    fun setAppleServiceVisible(visible: Boolean) {
        _state.value = _state.value.copyWith(appleServiceVisible = visible)
    }

    fun setKkboxAccountVisible(visible: Boolean) {
        _state.value = _state.value.copyWith(kkboxAccountVisible = visible)
    }

    /** 详情压栈：栈顶渲染在最上层，返回先处理。 */
    fun pushDetail(entry: DetailEntry) {
        _state.value = _state.value.copyWith(detailStack = _state.value.detailStack + entry)
    }

    /** 弹出栈顶详情（返回逐层退回；空栈时为 no-op）。 */
    fun popDetail() {
        val stack = _state.value.detailStack
        if (stack.isEmpty()) return
        _state.value = _state.value.copyWith(detailStack = stack.dropLast(1))
    }

    /** 关闭栈内指定类型的全部详情（保留其它类型条目）。 */
    fun closeDetailKind(kind: DetailKind) {
        _state.value = _state.value.copyWith(
            detailStack = _state.value.detailStack.filterNot { it.kind == kind },
        )
    }

    /** 关闭全部详情。 */
    fun closeAllDetails() {
        _state.value = _state.value.copyWith(detailStack = emptyList())
    }

    fun setShowLyricsTranslation(visible: Boolean) {
        _state.value = _state.value.copyWith(showLyricsTranslation = visible)
    }

    fun setShowWordByWord(visible: Boolean) {
        _state.value = _state.value.copyWith(showWordByWord = visible)
    }

    fun setCurrentLineIndex(index: Int) {
        _state.value = _state.value.copyWith(currentLineIndex = index)
    }
}
