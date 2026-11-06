package com.annieplayer.annie_music.kt.data.sources.kugou

import com.annieplayer.annie_music.kt.data.sources.common.jsTruthy

// ================================================================================================
// 酷狗取值工具 + 预编译正则
// ================================================================================================

/** 返回第一个非空字符串（对齐 Dart 的同名辅助） */
internal fun kugouFirstNonEmpty(values: List<Any?>): String {
    for (v in values) {
        if (v == null) continue
        if (v is String) {
            if (v.isNotEmpty()) return v
        } else if (jsTruthy(v)) {
            return v.toString()
        }
    }
    return ""
}

/** 可空字符串：空串 / 0 / null 归一为 null */
internal fun kugouOptString(v: Any?): String? {
    if (v == null) return null
    if (v is String) return if (v.isEmpty()) null else v
    if (v is Number) return if (v.toDouble() == 0.0) null else v.toString()
    return v.toString()
}

/** Dart 数字相等语义（1 == 1.0 为真；字符串不相等） */
internal fun kugouNumEq(v: Any?, n: Int): Boolean =
    v is Number && v.toDouble() == n.toDouble()

/** 将任意 JSON 数组元素安全转成字符串键 Map */
internal fun kugouAsMap(v: Any?): Map<String, Any?>? {
    val m = v as? Map<*, *> ?: return null
    val out = HashMap<String, Any?>()
    for ((k, value) in m) out[k.toString()] = value
    return out
}

internal val kugouReDigits by lazy { Regex("^\\d+$") }
internal val kugouReNonDigit by lazy { Regex("[^0-9]") }
internal val kugouReGcid by lazy { Regex("gcid_([a-z0-9]+)", RegexOption.IGNORE_CASE) }
internal val kugouReExt by lazy { Regex("\\.[^.]+${'$'}") }

/**
 * 酷狗多歌手分隔符：**不含 `&`** —— 组合名常自带 `&`（如 "Lucas & Steve"），
 * 实测酷狗真正的分隔符是「、」（`Bassjackers、Lucas & Steve、Caroline Pennell`）。
 */
internal val kugouReArtistSep by lazy { Regex("[、,/]") }
