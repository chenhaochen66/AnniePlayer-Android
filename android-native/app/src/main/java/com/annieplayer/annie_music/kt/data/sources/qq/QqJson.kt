package com.annieplayer.annie_music.kt.data.sources.qq

// ================================================================================================
// QQ 响应取值工具（JS 语义 / 嵌套取值 / BOM / 前缀）
// ================================================================================================

/** 对齐 Dart `v ?? def`（null 用默认值，空串保持空串） */
internal fun qqOr(v: Any?, def: String): String = v?.toString() ?: def

/** 对齐 Dart `x == 0`（仅数字 0 为真） */
internal fun qqIsZero(v: Any?): Boolean = when (v) {
    is Number -> v.toDouble() == 0.0
    else -> false
}

/** 对齐 Dart `x != 0`（null / 非数字 / 非零均为真） */
internal fun qqNeqZero(v: Any?): Boolean = !qqIsZero(v)

/** 将 JSON 对象安全转换为 `Map<String, Any?>` */
internal fun qqNormMap(m: Map<*, *>): Map<String, Any?> =
    m.entries.associate { (k, v) -> k.toString() to v }

/** 取嵌套字段：支持 `album.name` 这类路径（新接口把字段收进了子对象） */
internal fun qqDeep(s: Map<String, Any?>, vararg path: String): Any? {
    var cur: Any? = s
    for (key in path) {
        cur = (cur as? Map<*, *>)?.get(key) ?: return null
    }
    return cur
}

/** 将任意值安全转换为 `Map<String, Any?>?` */
internal fun qqToMap(v: Any?): Map<String, Any?>? =
    if (v is Map<*, *>) qqNormMap(v) else null

/** 从 `qq:songmid` 中剥离前缀得到纯 mid */
internal fun qqRawId(songId: String): String = songId.removePrefix("qq:")

/**
 * 剥离响应开头的 UTF-8 BOM。
 *
 * RN 版用正则剥离。Dart 侧视解码路径不同，既可能是标准 U+FEFF（utf8 解码），
 * 也可能是 latin1 误解码出的三个字符，两者都处理以保证结果一致。
 */
internal fun qqStripBom(text: String): String = when {
    text.startsWith("\uFEFF") -> text.substring(1)
    text.startsWith("\u00EF\u00BB\u00BF") -> text.substring(3)
    else -> text
}
