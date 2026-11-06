package com.annieplayer.annie_music.kt.core.state

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 底栏 tab（Android-only 设计，Flutter 基线无对应实现）。
 *
 * ## [Home] 为什么不再叫 Search
 *
 * 它是 App 的**根页**：顶栏（菜单 + 头像）、搜索模式切换、待下载条、搜索框、结果区
 * 全在里面，共八件东西里六件是「首页」的框架，只有搜索框和结果区属于搜索功能。
 * 历史上这个 tab 叫 `Search`，于是「根页」和「搜索功能」在代码里长期混在一起
 * （连退出确认都写着「回到搜索页主界面」）。
 *
 * 现在名字对齐了根页语义。将来要把搜索功能拆成 Home 的子页面时，只需要往
 * `UiState.pageStack` 压一个搜索路由，不用再动 tab。
 *
 * 底栏排布「左上首页 + 其右侧歌单解析 / 右上本地曲库 / 中间封面」，左右数量不对称是有意为之。
 */
enum class AppTab {
    /** 首页（根页）。搜索框与结果区目前仍是它的一部分。 */
    Home,

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

    private val _selectedTab = MutableStateFlow(AppTab.Home)

    /** 当前选中的 tab（UI 层 collect 渲染） */
    val selectedTab: StateFlow<AppTab> = _selectedTab.asStateFlow()

    /** 切换 tab（重复选中同一 tab 时无副作用，避免无意义的重组） */
    fun selectTab(tab: AppTab) {
        if (_selectedTab.value != tab) _selectedTab.value = tab
    }
}
