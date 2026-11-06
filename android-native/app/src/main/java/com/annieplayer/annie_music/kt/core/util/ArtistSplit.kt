package com.annieplayer.annie_music.kt.core.util

import java.util.Locale

/**
 * 「多歌手串」拆分 + 展示规范化。
 *
 * ## 只认两种分隔符
 * | 分隔符 | 例子 | 说明 |
 * |---|---|---|
 * | `/`（**至少一侧有空格**） | `A / B`、`A /B`、`A/ B` | 无空格的 `AC/DC`、`A/B` 不算 |
 * | `、`（顿号，可带空格） | `周杰伦、费玉清` | 中文并列的唯一写法 |
 *
 * ## 为什么不再认别的
 * 之前收过 `;` `|` `,` `+` `&` `×` `feat.` `ft.` `featuring` `vs.` `with`，结果是**误拆**：
 * 歌手名本身就带这些符号（`Simon & Garfunkel`、`AC/DC`、`Earth, Wind & Fire`、
 * `Tyler, The Creator`），一拆就把一个歌手劈成两个，艺术家列表里凭空多出几条。
 *
 * 现在只有「空格 + 斜杠」和「顿号」被视为分隔 —— 这两种在真实标签里几乎不会出现在名字内部。
 * 代价是 `A & B`、`A feat. B` 这种写法拆不开，会被当成一个歌手；这是**有意的取舍**：
 * 宁可少拆，也不要把真组合名拆坏。
 *
 * ## 展示统一成 `A / B`
 * 拆分只影响「按艺术家分组」与「⋮ 跳转到歌手」；界面上的歌手串由 [normalize] 统一成 `A / B`
 * （见 `LocalTrackMeta.artistDisplay`）。拆不开的串原样返回，所以对单歌手是无损的。
 *
 * ## 与流媒体平台字段的分工
 * `data/sources/ArtistNames.kt` 的 `splitArtistNames` 是另一回事：它拆的是**平台协议字段**
 * （酷我 `allartistname` 用 `&` 连接多个歌手），那里 `&` 是确定的分隔符，不适用本规则。
 */
object ArtistSplit {

    /** 展示用的统一分隔符：多歌手一律写成 `A / B`。要改格式只改这里。 */
    const val SEPARATOR = " / "

    /** 顿号（中文并列） */
    private val IDEOGRAPHIC_COMMA = Regex("\\s*、\\s*")

    /**
     * 斜杠：**至少一侧有空白**才算分隔。
     *
     * - `A / B` / `A /B` / `A/ B` → 拆；
     * - `AC/DC` / `A/B`（两侧都紧贴字母）→ 不拆，那是名字本身。
     */
    private val SPACE_SLASH = Regex("""\s+[/／]\s*|\s*[/／]\s+""")

    /**
     * 拆成歌手名列表。**保序、去空白、去空项、忽略大小写去重**。
     *
     * 空串返回空列表；拆不出第二个名字时返回单元素列表（原样 trim）。
     */
    fun split(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        // LinkedHashMap：保序 + 忽略大小写去重（`A; a` 只留第一次出现的写法）
        val seen = LinkedHashMap<String, String>()
        for (group in trimmed.split(IDEOGRAPHIC_COMMA)) {
            for (part in group.split(SPACE_SLASH)) {
                val name = part.trim()
                if (name.isEmpty()) continue
                seen.getOrPut(name.lowercase(Locale.ROOT)) { name }
            }
        }
        return if (seen.isEmpty()) listOf(trimmed) else seen.values.toList()
    }

    /** 把歌手名列表拼成**展示串**：`A / B`。空列表返回空串（回落由调用方决定）。 */
    fun join(names: List<String>): String = names.joinToString(SEPARATOR)

    /**
     * 一步到位：把多歌手串统一成 `A / B`。
     *
     * 只有 `A、B` 会被改写成 `A / B`；`A & B`、`A feat. B` 之类原样保留（拆不开就没得统一）。
     */
    fun normalize(raw: String): String {
        val names = split(raw)
        return if (names.size <= 1) raw.trim() else join(names)
    }
}
