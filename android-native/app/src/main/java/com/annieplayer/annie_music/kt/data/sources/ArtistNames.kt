package com.annieplayer.annie_music.kt.data.sources

/**
 * 多歌手名字符串的拆分工具。
 *
 * 各平台专辑/搜索接口常把多歌手塞在一个字符串里（如 `A & B`、`A/B`、`A、B`），
 * 而 UI 层 `songArtistsOf` 是按 `artistNames` / `artistIds` **下标配对**取歌手的。
 * 因此各源在构造 `AlbumSong` / `Song` 时必须把名字拆成列表、并与 id 列表对齐，
 * 否则「跳歌手」会因为缺 id 退化成按名字联网搜索（表现为点一下要等一会儿）。
 *
 * 分隔符与 UI 层保持一致（`/`、`、`、`,`、`&`）。
 */
internal val artistNameSeparators: Regex by lazy { Regex("\\s*[/、,&]\\s*") }

/** 把「A & B / C」这类歌手串拆成名字列表（去空白、去空项、保序）。 */
internal fun splitArtistNames(raw: String): List<String> =
    raw.split(artistNameSeparators).map { it.trim() }.filter { it.isNotEmpty() }

/**
 * 把歌手 id 串拆成列表。
 *
 * 酷我 `allartistid` 可能用 `&` 或 `,` 分隔，且伪 JSON 会把 `&` 转义成 `\u0026` / `\\u0026`
 * （须与 `KuwoApi` 搜索路径的处理保持一致）。这里统一还原后拆分：去空白、去空项、去 `"0"`、保序。
 */
internal fun splitArtistIds(raw: String): List<String> =
    raw
        .replace(Regex("\\\\+u0026"), "&")
        .replace("\\", "")
        .split(Regex("[&,]"))
        .map { it.trim() }
        .filter { it.isNotEmpty() && it != "0" }
