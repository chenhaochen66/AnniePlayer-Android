package com.annieplayer.annie_music.kt.data.sources.kkbox

// ================================================================================================
// KKBOX 响应取值工具（状态 / 整数 / 字符串 / 裸 Map）
// ================================================================================================

/** 响应状态：`status.type`（成功为 `"OK"`） */
internal fun kkboxStatusType(resp: Map<String, Any?>): String? {
    val s = resp["status"]
    return if (s is Map<*, *>) s["type"]?.toString() else null
}

/** 取整（对齐 Dart `_asInt`：int / num 直取，字符串 tryParse，其余 0） */
internal fun kkboxInt(value: Any?): Int = when (value) {
    is Int -> value
    is Long -> value.toInt()
    is Double -> value.toInt()
    is Float -> value.toInt()
    is Number -> value.toInt()
    is String -> value.toIntOrNull() ?: 0
    else -> 0
}

/** 转字符串（null -> ''） */
internal fun kkboxStr(value: Any?): String = value?.toString() ?: ""

/** 任意 JSON 对象 -> 字符串键 Map；非对象返回 null */
internal fun kkboxAsMap(value: Any?): Map<String, Any?>? {
    val m = value as? Map<*, *> ?: return null
    return m.entries.associate { (k, v) -> k.toString() to v }
}

/** 列表里的对象元素 -> Map 列表（丢掉非对象元素） */
internal fun kkboxMapList(raw: Any?): List<Map<String, Any?>> {
    val list = raw as? List<*> ?: return emptyList()
    return list.filterIsInstance<Map<*, *>>()
        .map { e -> e.entries.associate { (k, v) -> k.toString() to v } }
}
