package com.annieplayer.annie_music.kt.data.sources.common

// ================================================================================================
// 文本 / JSON 标量的公共原语
// ================================================================================================
//
// 这几个函数此前在 6 个平台文件里各有一份**逐字节相同**的实现（`_truthy` ×5、`_encodeComponent`
// ×6、`decodeHtmlEntities`），改口径要改六处，漏一处就是静默行为差异。现在只保留这一份。
//
// 语义口径是 **JS / Dart 移植**，不是 Kotlin 直觉（例如 `0` 与 `"0"` 的真值判定、空格的编码）；
// 改这里的任何一行，都会同时影响 6 个平台的签名与参数拼装 —— 先对照各平台原有的实现再动。
//
// 平台专属的（各平台不一样的）不要放这里：`_strOr` / `_toInt` / `_asMap` / `_listOfMaps` 这类
// 「同名不同义」的辅助留在各自平台文件里。

/** JS 真值语义：null / false / 0 / '' / NaN 为假，其余为真。 */
internal fun jsTruthy(v: Any?): Boolean = when (v) {
    null -> false
    is Boolean -> v
    is Number -> v.toDouble().let { it != 0.0 && !it.isNaN() }
    is String -> v.isNotEmpty()
    else -> true
}

/** `a || b || c` 语义：返回第一个真值；全为假返回 null。 */
internal fun firstTruthy(values: List<Any?>): Any? {
    for (v in values) {
        if (jsTruthy(v)) return v
    }
    return null
}

/** 转字符串（null -> ''）。对齐 Dart `v?.toString() ?? ''`，**不 trim**。 */
internal fun asText(v: Any?): String = v?.toString() ?: ""

/**
 * 百分号编码（对齐 Dart `Uri.encodeComponent`，保留集 `A-Za-z0-9-_.!~*'()`，大写十六进制）。
 *
 * `Uri.encode` / `URLEncoder` 与 Dart 的保留集不一致（空格、`!~*'()`），故此实现逐字节对齐。
 */
internal fun encodeComponent(s: String): String {
    val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"
    val hexDigit = "0123456789ABCDEF"
    val sb = StringBuilder()
    for (b in s.toByteArray(Charsets.UTF_8)) {
        val code = b.toInt() and 0xFF
        val c = code.toChar()
        if (unreserved.indexOf(c) >= 0) {
            sb.append(c)
        } else {
            sb.append('%')
            sb.append(hexDigit[code ushr 4])
            sb.append(hexDigit[code and 0x0F])
        }
    }
    return sb.toString()
}

/**
 * 解码 HTML 实体（1:1 移植 Flutter `lib/core/utils/string_utils.dart`）。
 *
 * 网易云 / QQ / 酷狗的标题与歌手名里都夹着实体（`&quot;`、`&#39;`），也夹着**反斜杠形式**
 * 的 Unicode 转义（`\u0026`，某些接口把它当字面量返回），两种都要处理。
 */
internal fun decodeHtmlEntities(text: String): String = text
    .replace("&nbsp;", " ")
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&apos;", "'")
    .replace("\\u0026", "&")
    .replace("\\u0020", " ")
    .replace("\\&", "&")
    .replace(Regex("_+u0026"), "&")
    .replace(Regex("_+u0020"), " ")
