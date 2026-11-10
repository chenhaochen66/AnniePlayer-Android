package com.annieplayer.annie_music.kt.data.sources.kkbox

import com.annieplayer.annie_music.kt.data.network.globalOkHttp
import com.annieplayer.annie_music.kt.data.network.json
import com.annieplayer.annie_music.kt.data.network.jsonElementToAny
import com.annieplayer.annie_music.kt.data.network.retryUntilSuccess
import com.annieplayer.annie_music.kt.data.sources.common.encodeComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// ================================================================================================
// KKBOX 传输层：签名 / 基础参数 / 表单编码 / 调用 + RC4(KC1) 解密
// ================================================================================================

/** KKBOX 客户端 UA */
internal const val kkboxUa = "okhttp/3.14.9"

/** 生成签名（timestamp 秒 + secret）。返回 (timestamp, secret) */
private fun _createSignature(): Pair<Long, String> {
    val timestamp = System.currentTimeMillis() / 1000
    val secret = kkboxMd5("06120082$timestamp$secretKey")
    return timestamp to secret
}

/** 基础请求参数（含签名；sid 非空时附带） */
private fun _getBaseParams(sid: String? = null): Map<String, String> {
    val (timestamp, secret) = _createSignature()
    val params = LinkedHashMap<String, String>()
    params["enc"] = "u"
    params["ver"] = "06120082"
    params["os"] = "android"
    params["osver"] = "13"
    params["lang"] = "en"
    params["ui_lang"] = "en"
    params["dist"] = "0021"
    params["dist2"] = "0021"
    params["resolution"] = "411x841"
    params["of"] = "j"
    params["oenc"] = "kc1"
    params["secret"] = secret
    params["timestamp"] = timestamp.toString()
    if (sid != null) params["sid"] = sid
    return params
}

/**
 * `application/x-www-form-urlencoded` 单值编码（等价 URLSearchParams）。
 *
 * 保留集 `A-Za-z0-9*-._`，空格转 `+`，其余大写十六进制。
 */
private fun _formComponent(input: String): String {
    val sb = StringBuilder()
    for (b in input.toByteArray(Charsets.UTF_8)) {
        val v = b.toInt() and 0xFF
        val isDigit = v in 0x30..0x39
        val isUpper = v in 0x41..0x5A
        val isLower = v in 0x61..0x7A
        if (isDigit || isUpper || isLower || v == 0x2A || v == 0x2D || v == 0x2E || v == 0x5F) {
            sb.append(v.toChar())
        } else if (v == 0x20) {
            sb.append('+')
        } else {
            sb.append('%')
            sb.append(v.toString(16).padStart(2, '0').uppercase())
        }
    }
    return sb.toString()
}

/** 表单编码（对齐 Dart `_formEncode`） */
private fun _formEncode(payload: Map<*, *>): String {
    val sb = StringBuilder()
    var first = true
    for ((k, v) in payload) {
        if (!first) sb.append('&')
        first = false
        sb.append(_formComponent(k.toString()))
        sb.append('=')
        sb.append(_formComponent(v.toString()))
    }
    return sb.toString()
}

/**
 * 调用 KKBOX API：拼接签名参数 → 请求 → RC4(KC1) 解密 → UTF-8 → JSON。
 *
 * 对齐 Dart 的 `validateStatus: (_) => true`：HTTP 非 2xx 也解密响应体，由业务状态码判断，
 * 故这里不校验 OkHttp 状态码。
 */
internal suspend fun kkboxApiCall(
    host: String,
    path: String,
    params: Map<String, String> = emptyMap(),
    payload: Any? = null,
): Map<String, Any?> {
    val allParams = LinkedHashMap<String, String>()
    allParams.putAll(_getBaseParams(params["sid"]))
    allParams.putAll(params)
    val qs = allParams.entries.joinToString("&") { (k, v) ->
        "${encodeComponent(k)}=${encodeComponent(v)}"
    }
    val url = "https://api-$host.kkbox.com.tw/$path?$qs"

    var body: String? = null
    val headers = mutableMapOf("User-Agent" to kkboxUa)
    if (payload != null) {
        if (payload is String) {
            body = payload
        } else if (payload is Map<*, *>) {
            body = _formEncode(payload)
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        }
    }

    // `execute()` 是阻塞调用：login 由 UI 的 Main scope 触发，不切 IO 会直接抛
    // NetworkOnMainThreadException（登录按钮必崩，而播放/下载在后台线程触发所以看不出来）。
    val bytes = withContext(Dispatchers.IO) {
        retryUntilSuccess {
            val builder = Request.Builder().url(url)
            headers.forEach { (k, v) -> builder.header(k, v) }
            // 对齐 Dart：payload != null 即 POST（即使 body 为 null）
            if (payload != null) {
                builder.method("POST", body?.toRequestBody(null))
            } else {
                builder.method("GET", null)
            }
            val resp = globalOkHttp.newCall(builder.build()).execute()
            resp.use { r -> r.body?.bytes() ?: ByteArray(0) }
        }
    }

    val decrypted = kkboxRc4(kc1Key, bytes)
    val decoded = jsonElementToAny(json.parseToJsonElement(String(decrypted, Charsets.UTF_8)))
    if (decoded is Map<*, *>) {
        return decoded.entries.associate { (k, v) -> k.toString() to v }
    }
    throw IllegalStateException("响应格式错误")
}
