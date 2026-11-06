package com.annieplayer.annie_music.kt.features.player

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing

/**
 * 播放页竖屏几何：把「屏宽 78% 的封面 + 48% 屏高的分页区 + 贴底的音质胶囊」换成按预算求解，
 * 从而在任意尺寸、任意字号下都不让胶囊压到封面、也不让分页区挤掉底部固定块。
 *
 * 预算顺序（每一档都只有上一档排不下时才启用）：
 * 1. [PagerShare] × 屏高（正常档，历史取值）
 * 2. 剩余高度（`屏高 − 固定块`，短屏压缩分页区）
 * 3. 紧凑档：隐藏歌手行与音量条，用省下的高度换分页区
 * 4. [PlayerPagerFloor] + 整页滚动（固定块本身就超出一屏）
 *
 * 不变量：`coverSize ≤ pagerHeight`；非滚动时 `pagerHeight + 固定块 ≤ screenH`；
 * [PlayerPageTier.full] / [PlayerPageTier.tight] 时封面居中后上下各留得出一条胶囊带。
 */

/** 音质胶囊槽高（恒定占位，避免探测结果到达前后封面跳动） */
internal val PlayerChipSlotHeight = 30.dp

/** 胶囊贴本页底边时留的底距（正常档） */
internal val PlayerChipBottomInset = 10.dp

/** 封面与胶囊之间的呼吸间距（正常档） */
internal val PlayerChipGap = 8.dp

/** 正常档要求的最小封面边长；不够就降档 */
internal val PlayerCoverMinSize = 120.dp

/** 挤压档：胶囊贴死本页底边，只留一道窄间距 */
internal val PlayerChipTightGap = 4.dp

/** 挤压档要求的最小封面边长；仍不够就收起胶囊 */
internal val PlayerCoverTightMinSize = 80.dp

/** 分页区低于这个高度时说明固定块自己就排不下，整页改为可滚动 */
internal val PlayerPagerFloor = 60.dp

/** 封面宽度占屏宽比例 */
private const val CoverShare = 0.78f

/** 分页区占屏高比例（历史取值，正常机型以它为基准） */
private const val PagerShare = 0.48f

/**
 * 分页区以下各块的高度。运行期由 `onSizeChanged` 实测，字号放大后自动跟着变；
 * 首帧用 [estimate] 的估值顶上，避免开页跳一下。
 */
internal data class PlayerFixedHeights(
    /** 拖拽条 + 顶栏 + 系统栏内边距 */
    val top: Dp,
    val title: Dp,
    val artist: Dp,
    val progress: Dp,
    val controls: Dp,
    val volume: Dp,
) {
    /** 全部元素都显示时的固定块总高 */
    val full: Dp
        get() = top + AppSpacing.sp14 + title + artist + AppSpacing.sp4 + progress +
            AppSpacing.sp12 + controls + AppSpacing.sp16 + volume + AppSpacing.sp18

    /** 紧凑档：隐藏歌手行与音量条，连同它们各自的上下间距一起省掉 */
    val compact: Dp
        get() = full - artist - AppSpacing.sp4 - AppSpacing.sp16 - volume

    companion object {
        /** fontScale = 1 时的估值（字号放大时只有文本行会变，固定块按常量算） */
        fun estimate(fontScale: Float) = PlayerFixedHeights(
            top = 56.dp,
            title = 42.dp,
            artist = 20.dp * fontScale,
            progress = 48.dp,
            controls = 48.dp,
            volume = 28.dp,
        )
    }
}

/** 胶囊与封面的排布档位：按可用高度从宽到窄 */
internal enum class PlayerPageTier {
    /** 封面居中 + 胶囊贴底 */
    full,

    /** 胶囊贴死底边 + 更小封面 */
    tight,

    /** 收起胶囊，封面独占本页 */
    noChip,
}

internal data class PlayerPageGeometry(
    val pagerHeight: Dp,
    val coverSize: Dp,
    val chipBottomInset: Dp,
    val tier: PlayerPageTier,
    /** 隐藏歌手行与音量条 */
    val compact: Boolean,
    val scroll: Boolean,
)

internal fun playerPageGeometry(
    screenW: Dp,
    screenH: Dp,
    fixed: PlayerFixedHeights,
): PlayerPageGeometry {
    val share = screenH * PagerShare
    val roomFull = (screenH - fixed.full).coerceAtLeast(0.dp)
    val roomCompact = (screenH - fixed.compact).coerceAtLeast(0.dp)

    val pager: Dp
    val compact: Boolean
    val scroll: Boolean
    when {
        roomFull >= share -> {
            pager = share
            compact = false
            scroll = false
        }

        roomFull >= PlayerPagerFloor -> {
            pager = roomFull
            compact = false
            scroll = false
        }

        roomCompact >= PlayerPagerFloor -> {
            pager = roomCompact
            compact = true
            scroll = false
        }

        else -> {
            pager = PlayerPagerFloor
            compact = true
            scroll = true
        }
    }

    val wide = screenW * CoverShare
    val half = screenH * 0.5f
    val fullReserve = (PlayerChipSlotHeight + PlayerChipBottomInset + PlayerChipGap) * 2f
    val tightReserve = (PlayerChipSlotHeight + PlayerChipTightGap) * 2f

    return when {
        pager >= fullReserve + PlayerCoverMinSize -> PlayerPageGeometry(
            pagerHeight = pager,
            coverSize = minOf(wide, half, pager - fullReserve).coerceAtLeast(0.dp),
            chipBottomInset = PlayerChipBottomInset,
            tier = PlayerPageTier.full,
            compact = compact,
            scroll = scroll,
        )

        pager >= tightReserve + PlayerCoverTightMinSize -> PlayerPageGeometry(
            pagerHeight = pager,
            coverSize = minOf(wide, half, pager - tightReserve).coerceAtLeast(0.dp),
            chipBottomInset = 0.dp,
            tier = PlayerPageTier.tight,
            compact = compact,
            scroll = scroll,
        )

        else -> PlayerPageGeometry(
            pagerHeight = pager,
            coverSize = minOf(wide, half, pager).coerceAtLeast(0.dp),
            chipBottomInset = 0.dp,
            tier = PlayerPageTier.noChip,
            compact = compact,
            scroll = scroll,
        )
    }
}
