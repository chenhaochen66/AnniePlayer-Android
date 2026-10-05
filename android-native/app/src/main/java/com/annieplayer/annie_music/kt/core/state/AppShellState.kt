package com.annieplayer.annie_music.kt.core.state

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 宿主屏 tab（Android-only 设计，Flutter 基线无对应实现）。
 *
 * 三个 tab：搜索（原首页内容）、歌单解析（从搜索模式迁出的独立页面）、本地曲库（占位页）。
 * 底栏排布「左上搜索 + 其右侧歌单解析 / 右上本地曲库 / 中间封面」，左右数量不对称是有意为之。
 */
enum class AppTab {
    /** 搜索（首页） */
    Search,

    /** 歌单解析（粘贴歌单链接 / ID → 打开歌单详情浮层） */
    PlaylistParse,

    /** 本地曲库 */
    Library,
}

/**
 * 应用外壳状态（当前 tab）。
 *
 * 为什么不塞进 [UiState]：`UiState` 承载的是「浮层可见性 + 详情数据」，字段与 Dart
 * `ui_provider` 一一对应，`copyWith` 已经很宽；tab 属于 Android 端新增的**导航**状态，
 * 语义不同，独立成一个 VM 更清晰。
 *
 * 用宿主屏现有的 `activityViewModel()` 构造（Activity 作用域），因此旋转 / Activity
 * 重建后当前 tab 保持；切换 tab 不会重建任何 ViewModel（尤其是播放引擎所在的
 * `PlayerViewModel`，音频接线只在 `AppShell` 里跑一次）。
 */
class AppShellViewModel : ViewModel() {

    private val _selectedTab = MutableStateFlow(AppTab.Search)

    /** 当前选中的 tab（UI 层 collect 渲染） */
    val selectedTab: StateFlow<AppTab> = _selectedTab.asStateFlow()

    /** 切换 tab（重复选中同一 tab 时无副作用，避免无意义的重组） */
    fun selectTab(tab: AppTab) {
        if (_selectedTab.value != tab) _selectedTab.value = tab
    }
}
