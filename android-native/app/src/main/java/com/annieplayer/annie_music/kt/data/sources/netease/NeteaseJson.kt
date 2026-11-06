package com.annieplayer.annie_music.kt.data.sources.netease

// ================================================================================================
// 网易云响应取值工具（JSON 形状 / 状态码 / 时间 / query 编码）
// ================================================================================================
//
// 这些是「按网易的字段口径取值」，不是通用 JSON 工具 —— 所以留在平台包里，不上收 common。
// 真值判定与 HTML 实体解码是六家逐字节相同的，已上收到 `sources.common`。

/** 状态码数值比较（对齐 Dart `code != 200`，int/double 数值相等均可） */
internal fun neteaseIsOk(value: Any?, expected: Int): Boolean =
    value is Number && value.toDouble() == expected.toDouble()

/** 毫秒 -> 秒（对齐 Dart `dt ~/ 1000`）；非 num 返回 0 */
internal fun neteaseMsToSec(value: Any?): Int =
    if (value is Number) (value.toLong() / 1000L).toInt() else 0

/**
 * 毫秒时间戳 -> UTC ISO 日期（yyyy-MM-dd）。
 *
 * 对齐 `new Date(ms).toISOString().slice(0,10)`：非数值按 Dart
 * `int.tryParse(...) ?? 0` 处理。
 */
internal fun neteaseIsoDate(timestamp: Any?): String {
    val ms = when (timestamp) {
        is Number -> timestamp.toLong()
        else -> timestamp?.toString()?.toLongOrNull() ?: 0L
    }
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
    fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
    return fmt.format(java.util.Date(ms))
}

/** 从 `netease:123` 中剥离前缀得到纯 id */
internal fun neteaseRawId(songId: String): String =
    songId.replaceFirst(Regex("^netease:"), "")

/** 将任意值安全转换为 `Map<String, Any?>`；非 Map 返回 null */
internal fun neteaseAsMap(value: Any?): Map<String, Any?>? {
    if (value !is Map<*, *>) return null
    val out = LinkedHashMap<String, Any?>(value.size)
    for ((k, v) in value) out[k.toString()] = v
    return out
}

/** 过滤出列表中的 Map 元素（对齐 Dart `whereType<Map>()` + `cast`） */
internal fun neteaseListOfMaps(raw: Any?): List<Map<String, Any?>> {
    if (raw !is List<*>) return emptyList()
    val out = ArrayList<Map<String, Any?>>(raw.size)
    for (e in raw) {
        val m = neteaseAsMap(e)
        if (m != null) out.add(m)
    }
    return out
}

/** 组装 query 串（对齐 Dart `Uri(queryParameters: ...).query` 的编码） */
internal fun neteaseQuery(params: List<Pair<String, String>>): String =
    params.joinToString("&") { (k, v) ->
        "${_encodeQueryComponent(k)}=${_encodeQueryComponent(v)}"
    }

/**
 * query 参数编码（对齐 Dart `Uri.encodeQueryComponent`）。
 *
 * 与 `common.encodeComponent`（RFC 2396，保留 `-_.!~*'()`）**不同**：
 * 官方文档明确本函数仅保留 `A-Za-z0-9-._~`，并把空格编码为 `+`，其余字节按 UTF-8
 * 百分号编码（大写十六进制）。网易/QQ 等使用 `Uri(queryParameters:)` 的接口必须用它，
 * 否则空格 `%20`≠`+`、`!*'()` 不编码，原始报文与 Flutter 不一致。
 */
private fun _encodeQueryComponent(s: String): String {
    val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    val hexDigit = "0123456789ABCDEF"
    val sb = StringBuilder()
    for (b in s.toByteArray(Charsets.UTF_8)) {
        val code = b.toInt() and 0xFF
        val c = code.toChar()
        when {
            unreserved.indexOf(c) >= 0 -> sb.append(c)
            code == 0x20 -> sb.append('+')
            else -> {
                sb.append('%')
                sb.append(hexDigit[code ushr 4])
                sb.append(hexDigit[code and 0x0F])
            }
        }
    }
    return sb.toString()
}
