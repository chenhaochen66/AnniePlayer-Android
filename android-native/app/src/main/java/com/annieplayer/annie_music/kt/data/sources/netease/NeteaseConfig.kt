package com.annieplayer.annie_music.kt.data.sources.netease

// ================================================================================================
// 网易云常量与请求头
// ================================================================================================
//
// 请求头逐字节对齐 RN 版：`Cookie: os=pc; deviceId=pyncm!` 是「免登录 web 端」的关键，
// 去掉会被服务端按未登录处理（部分接口直接返回 code:-462「请登录手机号」）。
//
// 包内分工（一个用途一个文件）：
//   NeteaseConfig   常量 / 请求头 / 缓存 TTL
//   NeteaseJson     JSON 取值 / 状态码 / 时间 / query 编码
//   NeteaseTypes    跨文件共用的记录
//   NeteaseQuality  音质与字节数判定
//   NeteaseMapper   JSON -> Song / Album / Artist（纯函数）
//   NeteaseSearch   歌曲 / 专辑 / 歌手搜索
//   NeteaseAlbum    专辑曲目 / 专辑详情 / 单曲补充信息
//   NeteaseArtist   头像 / 简介 / 单曲分页 / 专辑
//   NeteaseLyric    原文 / 翻译 / 逐字 yrc
//   NeteasePlaylist 歌单（200 条一档批量补全）
//
// 直链取链（第三方 `music/url`，原 `NeteasePlay.kt`）已删除：网易的播放/下载统一由落雪插件解析。

/** 网易云网页 API 通用请求头 */
val neteaseApiHeaders: Map<String, String> = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    "Referer" to "https://music.163.com/",
    "Content-Type" to "application/x-www-form-urlencoded",
    "Cookie" to "os=pc; deviceId=pyncm!",
)
