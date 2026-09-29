package com.annieplayer.annie_music.kt.core.state

import androidx.lifecycle.ViewModel
import com.annieplayer.annie_music.kt.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 搜索 / 歌手页多选状态（1:1 移植 Flutter `core/providers/` 下的
 * `selection_provider.dart`，对齐 RN 版 useSearchSelectionStore + useSingerSelectionStore）。
 *
 * RN 版有两个几乎相同的 store，Flutter 版合并为一个通用 selection 状态，Kotlin 沿用该合并设计。
 */

/** 多选状态（对齐 Dart `SelectionState`） */
data class SelectionState(
    val selectedSongs: List<Song> = emptyList(),
    val overlayVisible: Boolean = false,
) {
    /** 局部更新（对齐 Dart `SelectionState.copyWith`；`null` 不覆盖旧值） */
    fun copyWith(
        selectedSongs: List<Song>? = null,
        overlayVisible: Boolean? = null,
    ): SelectionState = SelectionState(
        selectedSongs = selectedSongs ?: this.selectedSongs,
        overlayVisible = overlayVisible ?: this.overlayVisible,
    )
}

/** 多选状态 ViewModel（对齐 Dart `SearchSelectionNotifier`） */
class SearchSelectionViewModel : ViewModel() {

    private val _state = MutableStateFlow(SelectionState())

    /** 只读状态流 */
    val state: StateFlow<SelectionState> = _state.asStateFlow()

    fun addSong(song: Song) {
        if (_state.value.selectedSongs.any { it.id == song.id }) return
        _state.value = _state.value.copyWith(selectedSongs = _state.value.selectedSongs + song)
    }

    fun removeSong(id: String) {
        _state.value = _state.value.copyWith(
            selectedSongs = _state.value.selectedSongs.filter { it.id != id },
        )
    }

    fun toggleSong(song: Song) {
        if (_state.value.selectedSongs.any { it.id == song.id }) {
            removeSong(song.id)
        } else {
            addSong(song)
        }
    }

    fun clearAll() {
        _state.value = _state.value.copyWith(selectedSongs = emptyList())
    }

    fun setOverlayVisible(visible: Boolean) {
        _state.value = _state.value.copyWith(overlayVisible = visible)
    }
}
