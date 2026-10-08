package com.annieplayer.annie_music.kt.core.util

/**
 * ISO 639-1 语言码 -> 中文名。
 *
 * 各平台返回的语言码都是两位小写（Qobuz 的 `translation_langs` 用 `de`/`es`/`fr`…）；
 * 未知码原样大写回退，避免显示成空。
 */
fun lyricLangLabel(code: String): String = when (code.lowercase()) {
    "zh", "zh-cn", "zh-hans" -> "中文（简体）"
    "zh-tw", "zh-hant" -> "中文（繁体）"
    "en" -> "英语"
    "ja" -> "日语"
    "ko" -> "韩语"
    "fr" -> "法语"
    "de" -> "德语"
    "es" -> "西班牙语"
    "it" -> "意大利语"
    "pt" -> "葡萄牙语"
    "ru" -> "俄语"
    "nl" -> "荷兰语"
    "pl" -> "波兰语"
    "sv" -> "瑞典语"
    "da" -> "丹麦语"
    "fi" -> "芬兰语"
    "no", "nb" -> "挪威语"
    "cs" -> "捷克语"
    "sk" -> "斯洛伐克语"
    "hu" -> "匈牙利语"
    "ro" -> "罗马尼亚语"
    "el" -> "希腊语"
    "tr" -> "土耳其语"
    "uk" -> "乌克兰语"
    "ar" -> "阿拉伯语"
    "he" -> "希伯来语"
    "hi" -> "印地语"
    "th" -> "泰语"
    "vi" -> "越南语"
    "id" -> "印尼语"
    "ms" -> "马来语"
    else -> code.uppercase()
}
