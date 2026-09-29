package com.annieplayer.annie_music.kt.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing

/**
 * 动画时长（Flutter: `Duration(milliseconds: N)`，单位毫秒）。
 *
 * 全 App 浮层 / 弹层统一使用 **150ms + Curves.linear**（见 [AppDuration.sheet] 与
 * [AppEasing.linear]）；此为迁移中最关键的动效规格，务必 1:1 保留。
 */
object AppDuration {
    /** 浮层 / 底部弹层进出场（Flutter: app_sheet / *_detail_sheet / queue_sheet 等 `_animDuration = 150`） */
    const val sheet = 150

    /** 播放器覆层 / 迷你播放器显隐（Flutter: player_overlay 200） */
    const val overlay = 200

    /** 歌词翻译行淡入（Flutter: player_screen `TweenAnimationBuilder` 200) */
    const val lyricTranslationFade = 200

    /** 歌词页切换（Flutter: player_screen `animateToPage` 250） */
    const val lyricPage = 250

    /** 搜索结果列表淡入（Flutter: song/album/singer_search_results 400） */
    const val listFade = 400

    /** 歌词行切换淡入（Flutter: player_screen 400） */
    const val lyricLineFade = 400

    /** 开屏淡入 / 淡出（Flutter: splash_screen `AnimatedOpacity` 500） */
    const val fade = 500

    /** 歌词自动滚动（Flutter: player_screen `_controller.animateTo` 550） */
    const val lyricScroll = 550

    /** 跑马灯单程最小时长（Flutter: moving_text `math.max(600, moveMs)`） */
    const val marqueeMoveMin = 600

    /** 歌曲下载进度环旋转（Flutter: song_tile `_spin` 900） */
    const val downloadRingSpin = 900

    /** 开屏准备计时 / 骨架脉冲（Flutter: splash_screen 1000；skeleton_loader 脉冲 1000） */
    const val skeletonPulse = 1000

    /** 跑马灯停留（Flutter: moving_text hold 1200） */
    const val marqueeHold = 1200

    /** 开屏整体时长（Flutter: splash_screen 1500 后移除覆层） */
    const val splashTotal = 1500

    // ---- 非 UI / 行为型时长（移植时仅作参考，不属于视觉设计 token）----

    /** 网络请求重试间隔（Flutter: kuwo_api / kkbox_api / http_client `Duration(milliseconds: 500)`） */
    const val networkRetry = 500
}

/**
 * 缓动曲线（Flutter: `Curves.xxx`）。
 *
 * Flutter 的 `Curves.easeOut` 等均为三次贝塞尔 `Cubic(a, b, c, d)`；
 * Compose 侧用 [CubicBezierEasing] 以完全相同的控制点复刻，保证缓动观感一致。
 */
object AppEasing {
    /**
     * 线性（Flutter: `Curves.linear`）。
     *
     * 浮层 / 弹层的 150ms 动画即用此曲线。
     */
    val linear: Easing = LinearEasing

    /** Flutter: `Curves.easeOut` = Cubic(0.0, 0.0, 0.58, 1.0)（player_screen 250ms） */
    val easeOut = CubicBezierEasing(0.0f, 0.0f, 0.58f, 1.0f)

    /** Flutter: `Curves.easeInOut` = Cubic(0.42, 0.0, 0.58, 1.0)（skeleton_loader 脉冲；player_screen 1017） */
    val easeInOut = CubicBezierEasing(0.42f, 0.0f, 0.58f, 1.0f)

    /** Flutter: `Curves.easeOutCubic` = Cubic(0.215, 0.61, 0.355, 1.0)（搜索结果淡入、queue_sheet 等） */
    val easeOutCubic = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1.0f)

    /** Flutter: `Curves.easeInOutQuad` = Cubic(0.455, 0.03, 0.515, 0.955)（多数组件弹层的 150ms 曲线） */
    val easeInOutQuad = CubicBezierEasing(0.455f, 0.03f, 0.515f, 0.955f)
}
