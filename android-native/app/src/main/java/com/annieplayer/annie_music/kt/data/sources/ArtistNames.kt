package com.annieplayer.annie_music.kt.data.sources

/**
 * 多歌手名字符串的拆分工具（**平台字段专用**）。
 *
 * 各平台专辑/搜索接口常把多歌手塞在一个字符串里（如 `A & B`、`A/B`、`A、B`），
 * 而 UI 层 `songArtistsOf` 是按 `artistNames` / `artistIds` **下标配对**取歌手的。
 * 因此各源在构造 `AlbumSong` / `Song` 时必须把名字拆成列表、并与 id 列表对齐，
 * 否则「跳歌手」会因为缺 id 退化成按名字联网搜索（表现为点一下要等一会儿）。
 *
 * **为什么这里比标签解析多认 `&`**：酷我/酷狗的 `allartistname` 是用 `&` 把多个歌手
 * 连接起来的协议字段，`&` 在那里是确定的分隔；而本地文件标签里的 `A & B` 常常就是
 * 一个组合名（`Simon & Garfunkel`）。两处语义不同，所以规则也不同：
 * 本地标签走 [com.annieplayer.annie_music.kt.core.util.ArtistSplit]（只认 ` / ` 与 `、`）。
 */
internal val artistNameSeparators: Regex by lazy { Regex("\\s*[/、,&]\\s*") }

/** 把平台字段里的「A & B / C」拆成名字列表（去空白、去空项、保序）。 */
internal fun splitArtistNames(raw: String): List<String> =
    raw.split(artistNameSeparators).map { it.trim() }.filter { it.isNotEmpty() }

/**
 * 把歌手 id 串拆成列表。
 *
 * 酷我 `allartistid` 可能用 `&` 或 `,` 分隔，且伪 JSON 会把 `&` 转义成 `\u0026` / `\\u0026`
 * （须与 `KuwoApi` 搜索路径的处理保持一致）。这里统一还原后拆分：去空白、去空项、去 `"0"`、保序。
 *
 * 注意：id 串**不跟随**名字那边的扩展分隔符 —— 它只来自平台字段，实际只有 `&` / `,`
 * 两种。名字拆得比 id 多时，调用方的 `takeIf { it.size == artistNames.size }` 会自动丢弃 id
 * （退化成按名字搜索），不会把 A 的 id 配到 B 的名字上。
 */
internal fun splitArtistIds(raw: String): List<String> =
    raw
        .replace(Regex("\\\\+u0026"), "&")
        .replace("\\", "")
        .split(Regex("[&,]"))
        .map { it.trim() }
        .filter { it.isNotEmpty() && it != "0" }
