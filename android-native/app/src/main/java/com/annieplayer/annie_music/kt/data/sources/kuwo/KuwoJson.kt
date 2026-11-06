package com.annieplayer.annie_music.kt.data.sources.kuwo

import com.annieplayer.annie_music.kt.data.network.json
import com.annieplayer.annie_music.kt.data.network.jsonElementToAny
import com.annieplayer.annie_music.kt.data.sources.common.asText

// ================================================================================================
// 酷我伪 JSON 解析与取值
// ================================================================================================
//
// 酷我 r.s 系列返回的是**单引号伪 JSON**：先整体把 `'` 换成 `"` 再交给 kotlinx，
// 失败时用 `"key": [...]` 正则兜底（与 RN 版结构一致）。

/** 取字符串，空则用默认值（对齐 `v || def`） */
internal fun kuwoStrOr(v: Any?, def: String): String {
    val s = asText(v)
    return if (s.isEmpty()) def else s
}

/** JS `parseInt(x, 10) || 0` 语义 */
internal fun kuwoInt(v: Any?): Int = when (v) {
    is Int -> v
    is Long -> v.toInt()
    is Double -> v.toInt()
    is Float -> v.toInt()
    is Number -> v.toInt()
    else -> asText(v).toDoubleOrNull()?.toInt() ?: 0
}

/** 将 JSON 数组安全转换为 Map 列表 */
internal fun kuwoMapList(v: Any?): List<Map<String, Any?>> {
    if (v !is List<*>) return emptyList()
    val out = ArrayList<Map<String, Any?>>()
    for (e in v) {
        if (e is Map<*, *>) {
            out.add(e.entries.associate { (k, value) -> k.toString() to value })
        }
    }
    return out
}

// 注意：`}}` 这种未转义花括号在桌面 JVM 上合法，但 Android 用的是 ICU 正则引擎
// （com.android.icu.util.regex.PatternNative），会直接抛 PatternSyntaxException，
// 且这些是顶层 val → 触发 <clinit> 失败 → 整个包不可用（搜索一提交就闪退）。
// 因此 `}` 必须转义为 `\}`。
internal val kuwoReMusicList by lazy {
    Regex("\"musiclist\"\\s*:\\s*(\\[[\\s\\S]*?\\])\\s*(?:,\"|\\}\\})")
}
internal val kuwoRePub by lazy { Regex("\"pub\"\\s*:\\s*\"([^\"]+)\"") }

/** 解析酷我搜索系伪 JSON（单引号 -> 双引号）为顶层 Map；失败返回 null */
internal fun kuwoParseRoot(text: String): Map<String, Any?>? {
    if (text.isBlank()) return null
    return try {
        jsonElementToAny(json.parseToJsonElement(text.replace("'", "\""))) as? Map<String, Any?>
    } catch (_: Throwable) {
        null
    }
}

/**
 * 解析酷我搜索类响应：先整体替换 `'` 为 `"` 再解析，
 * 失败时用 `"key": [...]` 正则兜底（与 RN 版逻辑一致）。
 *
 * [keysPattern] 形如 `abslist|list`，用于定位数组字段。
 */
internal fun kuwoParseList(text: String, keysPattern: String): List<Map<String, Any?>> {
    val normalized = text.replace("'", "\"")
    try {
        val parsed = jsonElementToAny(json.parseToJsonElement(normalized))
        if (parsed is Map<*, *>) {
            for (key in keysPattern.split("|")) {
                val v = parsed[key]
                if (v is List<*>) return kuwoMapList(v)
            }
        }
        return emptyList()
    } catch (_: Throwable) {
        // 落入正则兜底
    }
    // `}` 必须转义：Android ICU 正则不接受未转义花括号（桌面 JVM 会放行，容易漏测）
    val re by lazy { Regex("\"(?:$keysPattern)\":\\s*(\\[[\\s\\S]*?\\])\\s*(?:,\"|\\}\\})") }
    val match = re.find(normalized)
    if (match != null) {
        try {
            return kuwoMapList(jsonElementToAny(json.parseToJsonElement(match.groupValues[1])))
        } catch (_: Throwable) {
            return emptyList()
        }
    }
    return emptyList()
}
