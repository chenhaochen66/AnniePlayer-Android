package com.annieplayer.annie_music.kt.features.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.annieplayer.annie_music.kt.data.model.HomeShelf
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.sources.home.fetchHomeShelves

/** 主页区块标题右侧的操作按钮类型。 */
enum class HomeShelfAction {
    /** 酷狗「按风格推荐」：切到下一个风格 */
    CycleStyle,

    /** 酷狗「为你挑选」：换一批 */
    RefreshPicks,
}

/**
 * 主页内容状态机。
 *
 * ## 为什么不用 ViewModel
 * 它只服务于 `AppTab.Home` 这一个根页，且切换 tab 时**不应该重建**（否则每次回到首页都要重新
 * 请求一遍）。宿主 `AppShell` 里 `remember(scope) { HomeFeedState() }` 持有即可 —— 与
 * `SearchScreenState` / `PlaylistParseScreenState` 同一层，生命周期到 Activity 为止。
 *
 * ## 三种展示态
 * | 状态 | [loaded] | [loading] | [shelves] | UI |
 * |---|---|---|---|---|
 * | 首次进入 / 换源瞬间 | false | true | 空 | 骨架屏 |
 * | 成功 | true | false | 非空 | 内容 |
 * | 该平台没有主页（Qobuz / Apple / 酷我 / 酷狗 暂未接入） | true | false | 空 | 空态文案 |
 *
 * 失败**不单独做错误态**：主页是「锦上添花」的开屏内容，报错弹窗会打断用户；请求失败按空态
 * 处理，切走再切回来会重新尝试（因为 [load] 只在同平台且已加载时短路）。
 */
class HomeFeedState {

    /** 当前内容对应的平台；null 表示还没加载过 */
    var platform by mutableStateOf<MusicPlatform?>(null)
        private set

    /** 当前区块（按展示顺序） */
    var shelves by mutableStateOf<List<HomeShelf>>(emptyList())
        private set

    /** 正在请求 */
    var loading by mutableStateOf(false)
        private set

    /** 至少完成过一次请求（用于区分「还在加载」与「确实没有内容」） */
    var loaded by mutableStateOf(false)
        private set

    /**
     * 请求序号：快速连点切换源时，旧请求回来不许覆盖新平台的内容。
     * 每次 [load] 自增，回来后比对，不一致就丢弃。
     */
    private var requestSeq: Int = 0

    /**
     * 「为你挑选」的刷新代数。
     *
     * 只参与缓存 key：每点一次「换一批」就 +1，于是绕过上一次的缓存、又不会把旧结果冲掉。
     */
    private var kugouDiscoverToken: Int = 0

    /** 酷狗「为你挑选」换一批。 */
    suspend fun refreshKugouPicks() {
        kugouDiscoverToken += 1
        reload()
    }

    /** 原地重取当前平台（force = true 绕过「同平台已加载」的短路）。 */
    private suspend fun reload() {
        val target = platform ?: return
        load(target, force = true)
    }

    /**
     * 按平台加载主页。
     *
     * @param force true 时忽略「同平台已加载」的短路（给下拉刷新 / 重试留的口子）
     */
    suspend fun load(target: MusicPlatform, force: Boolean = false) {
        if (!force && platform == target && loaded) return
        val seq = ++requestSeq
        // 换源立刻把旧内容清掉：否则新平台的骨架屏会盖在旧平台的卡片上（或反过来闪一下）
        if (platform != target) shelves = emptyList()
        platform = target
        loading = true
        loaded = false

        val result = runCatching {
            fetchHomeShelves(
                platform = target,
                kugouDiscoverToken = kugouDiscoverToken,
            )
        }.getOrDefault(emptyList())

        // 期间又切了源：这份结果作废（不写状态，避免「切到 A 却显示 B」）
        if (seq != requestSeq) return
        shelves = result
        loading = false
        loaded = true
    }
}
