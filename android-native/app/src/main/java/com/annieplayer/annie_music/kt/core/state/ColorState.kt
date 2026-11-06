package com.annieplayer.annie_music.kt.core.state

import androidx.lifecycle.ViewModel
import com.annieplayer.annie_music.kt.core.util.extractPlayerCoverPalette
import com.annieplayer.annie_music.kt.ui.theme.ColorPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 颜色 / 封面状态（1:1 移植 Flutter `core/providers/` 下的 `color_provider.dart`，
 * 对齐 RN 版 store/useColorStore.ts）。
 *
 * ## 全站只有一套取色
 *
 * 播放页、主页、本地曲库、各详情页、底栏，全部用
 * [extractPlayerCoverPalette]：整图缩到 64×64，`primary` = 全图算术平均、
 * `secondary` = 最鲜艳、`accent` = 最大占比，外加 4×4 的 `coverGrid`。
 *
 * 历史上这里有两个字段：播放页一套（本函数），其它页面一套（LunaBeat 的
 * 中心采样 + 36 色量化）。同一个封面、同一个触发点，算两遍没有意义，已合并为 [colors]。
 *
 * 变色页需要「三个不同色相」这件事**不在这里处理** —— 取色算法统一之后，
 * 三色常常同色相、且 `primary` 偏灰，色相分离放在渲染层做，
 * 见 `widgets/AuroraBackdrop.kt` 的 `_ensureAnchorSpread`。
 */

/** 颜色状态（对齐 Dart `ColorState`）。 */
data class ColorState(
    /** 全局色板（[extractPlayerCoverPalette] 的产出，含 4×4 网格）。 */
    val colors: ColorPalette,
    val currentCoverUrl: String = "",
) {
    /** 局部更新（对齐 Dart `ColorState.copyWith`；`null` 不覆盖旧值）。 */
    fun copyWith(
        colors: ColorPalette? = null,
        currentCoverUrl: String? = null,
    ): ColorState = ColorState(
        colors = colors ?: this.colors,
        currentCoverUrl = currentCoverUrl ?: this.currentCoverUrl,
    )
}

/** 颜色状态 ViewModel（对齐 Dart `ColorNotifier`）。 */
class ColorViewModel : ViewModel() {

    private val _state = MutableStateFlow(ColorState(colors = ColorPalette.fallback))

    /** 只读状态流。 */
    val state: StateFlow<ColorState> = _state.asStateFlow()

    fun setColors(colors: ColorPalette) {
        _state.value = _state.value.copyWith(colors = colors)
    }

    fun setCurrentCoverUrl(url: String) {
        _state.value = _state.value.copyWith(currentCoverUrl = url)
    }
}
