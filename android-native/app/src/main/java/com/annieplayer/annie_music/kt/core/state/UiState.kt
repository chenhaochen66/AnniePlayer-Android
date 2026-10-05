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
 * albumDetailVisible / singerDetailVisible / playlistDetailVisible /
 * downloadQueueVisible，UI 层据此镜像同一套层序。
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
    val albumDetailVisible: Boolean = false,
    val albumDetailData: AlbumDetailData? = null,
    val singerDetailVisible: Boolean = false,
    val singerDetailData: SingerDetailData? = null,
    val downloadQueueVisible: Boolean = false,
    val qobuzTokenVisible: Boolean = false,
    val kkboxAccountVisible: Boolean = false,
    val playlistDetailVisible: Boolean = false,
    val playlistDetailData: PlaylistDetailData? = null,
    val showLyricsTranslation: Boolean = true,
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
        albumDetailVisible: Boolean? = null,
        albumDetailData: AlbumDetailData? = null,
        singerDetailVisible: Boolean? = null,
        singerDetailData: SingerDetailData? = null,
        downloadQueueVisible: Boolean? = null,
        qobuzTokenVisible: Boolean? = null,
        kkboxAccountVisible: Boolean? = null,
        playlistDetailVisible: Boolean? = null,
        playlistDetailData: PlaylistDetailData? = null,
        showLyricsTranslation: Boolean? = null,
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
        albumDetailVisible = albumDetailVisible ?: this.albumDetailVisible,
        albumDetailData = albumDetailData ?: this.albumDetailData,
        singerDetailVisible = singerDetailVisible ?: this.singerDetailVisible,
        singerDetailData = singerDetailData ?: this.singerDetailData,
        downloadQueueVisible = downloadQueueVisible ?: this.downloadQueueVisible,
        qobuzTokenVisible = qobuzTokenVisible ?: this.qobuzTokenVisible,
        kkboxAccountVisible = kkboxAccountVisible ?: this.kkboxAccountVisible,
        playlistDetailVisible = playlistDetailVisible ?: this.playlistDetailVisible,
        playlistDetailData = playlistDetailData ?: this.playlistDetailData,
        showLyricsTranslation = showLyricsTranslation ?: this.showLyricsTranslation,
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

    fun setAlbumDetailVisible(visible: Boolean, data: AlbumDetailData? = null) {
        _state.value = _state.value.copyWith(albumDetailVisible = visible, albumDetailData = data)
    }

    fun setSingerDetailVisible(visible: Boolean, data: SingerDetailData? = null) {
        _state.value = _state.value.copyWith(singerDetailVisible = visible, singerDetailData = data)
    }

    fun setDownloadQueueVisible(visible: Boolean) {
        _state.value = _state.value.copyWith(downloadQueueVisible = visible)
    }

    fun setQobuzTokenVisible(visible: Boolean) {
        _state.value = _state.value.copyWith(qobuzTokenVisible = visible)
    }

    fun setKkboxAccountVisible(visible: Boolean) {
        _state.value = _state.value.copyWith(kkboxAccountVisible = visible)
    }

    fun setPlaylistDetailVisible(visible: Boolean, data: PlaylistDetailData? = null) {
        _state.value = _state.value.copyWith(
            playlistDetailVisible = visible,
            playlistDetailData = data,
        )
    }

    fun setShowLyricsTranslation(visible: Boolean) {
        _state.value = _state.value.copyWith(showLyricsTranslation = visible)
    }

    fun setCurrentLineIndex(index: Int) {
        _state.value = _state.value.copyWith(currentLineIndex = index)
    }
}
