package com.annieplayer.annie_music.kt.data.sources.qq

// ================================================================================================
// QQ 音乐常量与请求头
// ================================================================================================
//
// 包内分工（一个用途一个文件）：
//   QqConfig   请求头 / musicu 端点与 guid
//   QqJson     JSON 取值 / BOM / 前缀剥离
//   QqTypes    跨文件共用的记录
//   QqQuality  音质与字节数判定（下游唯一依据）
//   QqMapper   JSON -> Song / Album / Artist（纯函数）
//   QqSearch   歌曲 / 专辑 / 歌手搜索
//   QqAlbum    专辑曲目 / 单曲补充
//   QqArtist   简介 / 单曲分页 / 专辑
//   QqPlaylist 歌单分页
//   QqLyric    歌词（QRC 逐字 + 翻译）
//
// 直链取链（长青解析，原 `QqPlay.kt`）已删除：QQ 的播放/下载统一由落雪插件解析。

/** QQ 音乐网页 API 通用请求头 */
val qqHeaders: Map<String, String> = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    "Referer" to "https://y.qq.com/",
)

/** 移动端搜索接口（单次 60 条，比旧网页接口快约 3 倍）。 */
internal const val qqMusicuUrl = "https://u.y.qq.com/cgi-bin/musicu.fcg"

/** musicu `comm.guid`：32 位 hex，进程内随机一次。 */
internal val qqMusicuGuid: String by lazy {
    val hex = "0123456789abcdef"
    (1..32).joinToString("") { hex[(Math.random() * 16).toInt()].toString() }
}
