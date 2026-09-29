package com.annieplayer.annie_music.kt.core.state

import androidx.lifecycle.ViewModel
import com.annieplayer.annie_music.kt.ui.theme.ColorPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 颜色 / 封面状态（1:1 移植 Flutter `core/providers/` 下的 `color_provider.dart`，
 * 对齐 RN 版 store/useColorStore.ts）。
 *
 * 初始色板为 [ColorPalette.fallback]；封面动态取色结果通过 [ColorViewModel.setColors]
 * 写入（取色算法位于 core/util 的 ColorExtractor，本层不负责计算）。
 */

/** 颜色状态（对齐 Dart `ColorState`） */
data class ColorState(
    val colors: ColorPalette,
    val currentCoverUrl: String = "",
) {
    /** 局部更新（对齐 Dart `ColorState.copyWith`；`null` 不覆盖旧值） */
    fun copyWith(
        colors: ColorPalette? = null,
        currentCoverUrl: String? = null,
    ): ColorState = ColorState(
        colors = colors ?: this.colors,
        currentCoverUrl = currentCoverUrl ?: this.currentCoverUrl,
    )
}

/** 颜色状态 ViewModel（对齐 Dart `ColorNotifier`） */
class ColorViewModel : ViewModel() {

    private val _state = MutableStateFlow(ColorState(colors = ColorPalette.fallback))

    /** 只读状态流 */
    val state: StateFlow<ColorState> = _state.asStateFlow()

    fun setColors(colors: ColorPalette) {
        _state.value = _state.value.copyWith(colors = colors)
    }

    fun setCurrentCoverUrl(url: String) {
        _state.value = _state.value.copyWith(currentCoverUrl = url)
    }
}
