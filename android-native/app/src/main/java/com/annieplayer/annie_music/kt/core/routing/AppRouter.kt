package com.annieplayer.annie_music.kt.core.routing

/**
 * 路由路径常量（对齐 Flutter `lib/core/routing/app_router.dart` 中的 `AppRoutes`）。
 *
 * 路径字符串必须与 Flutter 完全一致，供下面的 [AppRoute] 与后续导航层复用。
 */
object AppRoutes {
    /** 首页。 */
    const val home = "/"

    /** 播放页。 */
    const val player = "/player"

    /** 下载页。 */
    const val downloads = "/downloads"

    /** 专辑详情页。 */
    const val albumDetail = "/album-detail"

    /** KKBOX 详情页。 */
    const val kkboxDetail = "/kkbox-detail"

    /** Qobuz 详情页。 */
    const val qobuzDetail = "/qobuz-detail"
}

/**
 * 路由表（对齐 Flutter `createRouter()` 中的 6 个 `GoRoute`）。
 *
 * Compose UI 尚未编写，故这里不引用任何 Screen 类，只把「路线 == 路径 + 名称 +
 * 屏幕标识」表达为数据；后续 UI 层按 [screen] 映射到真正的 Composable 即可。
 * 6 个路由均无路径参数（Flutter 侧 builder 不读取 state），因此不携带额外参数。
 */
sealed interface AppRoute {
    /** 对应 Flutter `GoRoute.path`。 */
    val path: String

    /** 对应 Flutter `GoRoute.name`。 */
    val name: String

    /** 对应的 Flutter Screen 类名，供 UI 层建立映射时对号入座。 */
    val screen: String

    /** 首页 `/`（Flutter `HomeScreen`）。 */
    data object Home : AppRoute {
        override val path: String = AppRoutes.home
        override val name: String = "home"
        override val screen: String = "HomeScreen"
    }

    /** 播放页 `/player`（Flutter `PlayerScreen`）。 */
    data object Player : AppRoute {
        override val path: String = AppRoutes.player
        override val name: String = "player"
        override val screen: String = "PlayerScreen"
    }

    /** 下载页 `/downloads`（Flutter `DownloadsScreen`）。 */
    data object Downloads : AppRoute {
        override val path: String = AppRoutes.downloads
        override val name: String = "downloads"
        override val screen: String = "DownloadsScreen"
    }

    /** 专辑详情页 `/album-detail`（Flutter `AlbumDetailScreen`）。 */
    data object AlbumDetail : AppRoute {
        override val path: String = AppRoutes.albumDetail
        override val name: String = "album-detail"
        override val screen: String = "AlbumDetailScreen"
    }

    /** KKBOX 详情页 `/kkbox-detail`（Flutter `KkboxDetailScreen`）。 */
    data object KkboxDetail : AppRoute {
        override val path: String = AppRoutes.kkboxDetail
        override val name: String = "kkbox-detail"
        override val screen: String = "KkboxDetailScreen"
    }

    /** Qobuz 详情页 `/qobuz-detail`（Flutter `QobuzDetailScreen`）。 */
    data object QobuzDetail : AppRoute {
        override val path: String = AppRoutes.qobuzDetail
        override val name: String = "qobuz-detail"
        override val screen: String = "QobuzDetailScreen"
    }

    companion object {
        /** 全部路由，顺序与 Flutter `createRouter()` 中的声明顺序一致。 */
        val all: List<AppRoute> = listOf(
            Home,
            Player,
            Downloads,
            AlbumDetail,
            KkboxDetail,
            QobuzDetail,
        )

        /** 初始路由（对齐 Flutter `initialLocation: AppRoutes.home`）。 */
        val initial: AppRoute = Home

        /** 按路径查路由；未知返回 null。 */
        fun fromPath(path: String): AppRoute? = all.firstOrNull { it.path == path }

        /** 按名称查路由；未知返回 null。 */
        fun fromName(name: String): AppRoute? = all.firstOrNull { it.name == name }
    }
}
