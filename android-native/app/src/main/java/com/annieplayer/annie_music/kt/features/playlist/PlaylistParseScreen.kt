package com.annieplayer.annie_music.kt.features.playlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import com.annieplayer.annie_music.kt.core.state.PlaylistDetailData
import com.annieplayer.annie_music.kt.core.state.PlaylistDetailEntry
import com.annieplayer.annie_music.kt.core.state.UiViewModel
import com.annieplayer.annie_music.kt.data.PlaylistService
import com.annieplayer.annie_music.kt.features.search.HomePlaylistLinkView
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.ui.theme.ColorPalette
import com.annieplayer.annie_music.kt.widgets.AppTopBar
import com.annieplayer.annie_music.kt.widgets.CoverPaletteBackdrop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 歌单解析页状态（从 `SearchScreenState` 拆出，原 `handlePlaylistSubmit`）。
 *
 * 为什么独立：歌单解析已从「搜索模式」迁出为底栏 tab，逻辑再挂在搜索 state 上语义不符；
 * 这里只持有所需的三个依赖，`handleSubmit` 与迁移前逐行等价。
 */
@Stable
class PlaylistParseScreenState(
    private val scope: CoroutineScope,
    private val uiViewModel: UiViewModel,
    private val snackbarHostState: SnackbarHostState,
) {
    /**
     * 提交粘贴的链接 / ID：识别失败弹 Snackbar，成功置位歌单详情浮层数据。
     *
     * 与迁移前的 `SearchScreenState.handlePlaylistSubmit` 行为一致（含失败文案）。
     */
    fun handleSubmit(text: String) {
        val parsed = PlaylistService.parsePlaylistUrl(text)
        if (parsed == null) {
            scope.launch { snackbarHostState.showSnackbar("未识别到有效的歌单链接或ID") }
            return
        }
        uiViewModel.pushDetail(
            PlaylistDetailEntry(
                PlaylistDetailData(id = parsed.id, platform = parsed.platform.name),
            ),
        )
    }
}

/**
 * 歌单解析页（底栏 tab 页面，从搜索模式迁出）。
 *
 * 结构对齐 [com.annieplayer.annie_music.kt.features.library.LocalLibraryScreen]：
 * 液态玻璃背景（**保留**玻璃质感，底色用固定品牌色板 `ColorPalette.fallback`，不跟随
 * 当前播放封面）→ 内容列（顶栏 + 链接输入视图）。
 *
 * 输入视图复用搜索页原组件 [HomePlaylistLinkView]（`internal`，同一份实现，避免两套输入框）；
 * 解析成功后行为不变：弹出既有的 `PlaylistDetailSheet` 全屏浮层。
 *
 * 底部预留 [AppSpacing.bottomBarInset]，避免输入区被常驻底栏玻璃压住。
 */
@Composable
fun PlaylistParseScreen(
    onOpenMenu: () -> Unit,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        CoverPaletteBackdrop(
            sheen = true,
            coverUrl = null,
            palette = ColorPalette.fallback,
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(bottom = AppSpacing.bottomBarInset),
        ) {
            // 顶栏：菜单按钮 + 标题（与搜索页 / 本地曲库页共用 AppTopBar，标题 token 同源）
            AppTopBar(
                title = "歌单解析",
                onOpenMenu = onOpenMenu,
            )
            HomePlaylistLinkView(onSubmit = onSubmit)
        }
    }
}
