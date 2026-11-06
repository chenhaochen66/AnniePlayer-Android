package com.annieplayer.annie_music.kt.data.sources.kkbox

// ================================================================================================
// KKBOX 链接解析（分享链接 / 裸 id）
// ================================================================================================

/** 解析 KKBOX 分享链接 / 裸 id */
fun parseKkboxUrl(text: String): KkboxUrlInfo? {
    val patterns = listOf(
        Regex("/song[/-]([A-Za-z0-9\\-_\\.]+)") to "song",
        Regex("/album[/-]([A-Za-z0-9\\-_\\.]+)") to "album",
        Regex("/playlist[/-]([A-Za-z0-9\\-_\\.]+)") to "song",
    )
    for ((re, type) in patterns) {
        val m = re.find(text) ?: continue
        val id = m.groupValues.getOrNull(1) ?: continue
        return KkboxUrlInfo(type = type, id = id)
    }
    val trimmed = text.trim()
    if (Regex("^[A-Za-z0-9\\-_\\.]+$").matches(trimmed)) {
        return KkboxUrlInfo(type = "song", id = trimmed)
    }
    return null
}
