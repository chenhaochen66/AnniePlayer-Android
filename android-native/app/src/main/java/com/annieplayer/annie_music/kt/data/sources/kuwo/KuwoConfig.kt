package com.annieplayer.annie_music.kt.data.sources.kuwo

// ================================================================================================
// 酷我常量（与 RN 版完全一致，UA / 来源标识 / 基址都参与服务端校验）
// ================================================================================================
//
// 包内分工（一个用途一个文件）：
//   KuwoConfig   常量 / UA / 基址
//   KuwoJson     伪 JSON 解析 / 取值 / 正则
//   KuwoTypes    跨文件共用的记录
//   KuwoCover    封面地址（800px 规整）
//   KuwoQuality  MINFO 音质与字节数
//   KuwoMapper   JSON -> Song / Album / Artist（纯函数）
//   KuwoSearch   歌曲 / 专辑 / 歌手搜索 + 头像简介
//   KuwoArtist   歌手歌曲 / 歌手专辑
//   KuwoAlbum    专辑曲目 / 单曲补充
//   KuwoLyric    歌词（LRCX 逐字 + 翻译）
//   KuwoSecret   签名（v / getSecret / generateKuwoSecret）
//   KuwoPlaylist 歌单
//
// 直链取链（`mobi.s` / `convert_url_with_sign`）与音质→br 映射已删除：酷我的播放/下载
// 统一由落雪插件解析，本包只保留元数据（搜索 / 专辑 / 歌手 / 歌单 / 歌词）。

/** 安卓客户端 UA（搜索/专辑接口；**兼作 API 的 source 参数**，服务端会校验） */
internal const val kuwoAndroidUa = "kwplayerhd_ar_4.3.0.8_tianbao_T1A_qirui.apk"


/** 移动端浏览器 UA（播放链接/歌词接口） */
internal const val kuwoMobileUa =
    "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

/** 桌面浏览器 UA（web www 接口） */
internal const val kuwoDesktopUa =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"

/** 歌手/专辑 web 接口基地址 */
internal const val kuwoApiBase = "https://www.kuwo.cn/api/www"

/** 专辑接口基地址 */
internal const val kuwoApiBaseAlbum = "https://wapi.kuwo.cn/api/www"

/** 歌手/专辑列表缓存 TTL（对齐 RN 字面量 `30 * 1000` = 30 秒） */
internal const val kuwoArtistCacheTtl: Long = 30 * 1000L
