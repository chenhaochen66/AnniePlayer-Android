package com.annieplayer.annie_music.kt.data.sources.common

// ================================================================================================
// 签名原语
// ================================================================================================
//
// 酷狗 android 接口的签名口径：`md5(key + sorted(k+'='+v).join('') + body + key)`。
// 这里只放**纯函数**（无网络、无状态），所以能单测；拼 header / 发请求留在各平台文件里。

/** MD5 十六进制**小写**（对齐 Dart `md5.convert(utf8.encode(x)).toString()`）。 */
internal fun md5Hex(input: String): String {
    val md = java.security.MessageDigest.getInstance("MD5")
    val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
    val hex = "0123456789abcdef"
    val sb = StringBuilder()
    for (b in bytes) {
        val v = b.toInt() and 0xFF
        sb.append(hex[v ushr 4])
        sb.append(hex[v and 0x0F])
    }
    return sb.toString()
}

/** `md5(key + sorted('k=v').join('') + body + key)` —— 参数按 key 字典序拼，无分隔符。 */
internal fun signParams(params: Map<String, String>, key: String, body: String = ""): String {
    val sorted = params.keys.sorted()
    val joined = sorted.joinToString("") { "$it=${params[it]}" }
    return md5Hex("$key$joined$body$key")
}
