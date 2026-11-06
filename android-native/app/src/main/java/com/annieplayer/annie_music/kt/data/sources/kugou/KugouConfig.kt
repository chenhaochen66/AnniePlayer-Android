package com.annieplayer.annie_music.kt.data.sources.kugou

// ================================================================================================
// 酷狗常量（签名密钥 / UA）
// ================================================================================================
//
// 包内分工（一个用途一个文件）：
//   KugouConfig    密钥 / UA
//   KugouJson      取值工具 + 预编译正则
//   KugouTypes     跨文件共用的记录
//   KugouHttp      设备 MID / 公开 JSON UA / 签名 POST / web 签名 GET（传输层唯一一份）
//   KugouQuality   音质与字节数（含批量补 audio_info）
//   KugouPlaylistFetch 歌单底层取数（GCID / 分页 / HTML 兜底）
//   KugouPlaylist  歌单入口
//   KugouLyric     歌词（KRC 逐字 + 翻译）
//   KugouAlbum     专辑曲目与专辑信息
//   KugouSongMeta  单曲补元信息（专辑 id / 曲目号 / 歌手）
//   KugouAuthors   逐曲歌手（跳歌手用）
//   KugouSearch    单曲 / 专辑 / 歌手搜索
//   KugouArtist    歌手信息 / 单曲 / 专辑
//
// 直链取链（长青解析 + 跟随 302，原 `KugouPlay.kt`）已删除：酷狗的播放/下载统一由落雪插件解析。

/** 酷狗签名密钥 A（decodeGcid / 专辑接口） */
const val kugouKeyA: String = "OIlwieks28dk2k092lksi2UIkp"

/** 酷狗签名密钥 B（歌单 / web 搜索接口） */
internal const val kugouKeyB: String = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt"

/** 酷狗 web 接口通用 UA */
internal const val kugouWebUa: String = "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36"

/** 歌手补充信息缓存 TTL（头像/歌曲数变化慢，30 分钟） */
internal const val kugouArtistInfoTtl: Long = 30 * 60 * 1000L

/** 逐曲歌手缓存 TTL：30 天（歌手 id 基本不变） */
internal const val kugouAuthorsCacheTtl: Long = 30L * 24 * 60 * 60 * 1000L
