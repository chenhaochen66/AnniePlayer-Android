package com.annieplayer.annie_music.kt.data.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 网络层封装（1:1 移植 Flutter `lib/core/network/http_client.dart`）。
 *
 * Flutter 端用 dio；Kotlin 端用 OkHttp。行为对齐点：
 * - 超时：连接 15s / 读取 30s / 写入 15s
 * - 默认 User-Agent，可被调用方 headers 覆盖（对齐 dio BaseOptions + per-request 覆盖）
 * - 非 2xx 抛异常（对齐 dio 默认 validateStatus 只接受 200-299）
 * - 统一以「原始文本」取响应再手动 JSON 解码（Flutter 端注释里说明过原因：
 *   部分平台响应 Content-Type 是 text/plain，自动解码会失败）
 */

/** 全局 JSON 实例（宽松解析，对齐 Dart `jsonDecode` 的容忍度） */
val json: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    allowSpecialFloatingPointValues = true
}

private const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"

/** 全局共享 OkHttp 实例（对齐 Flutter 的 `globalDio`） */
val globalOkHttp: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(15, TimeUnit.SECONDS)
    .build()

/**
 * 带重试的请求执行（对齐 Flutter `retryUntilSuccess`）。
 *
 * 与 Dart 的差异：这里会**透传协程取消**（`CancellationException` 不参与重试），
 * 否则页面销毁后重试仍会继续跑。
 */
suspend fun <T> retryUntilSuccess(
    maxRetries: Int = 3,
    delayMs: Long = 500L,
    action: suspend () -> T,
): T {
    var attempt = 0
    var lastError: Throwable? = null
    while (attempt < maxRetries) {
        try {
            return action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = e
            attempt++
            if (attempt < maxRetries) delay(delayMs)
        }
    }
    // Dart 是 `throw lastError!`；这里避免 !!，用兜底异常
    throw lastError ?: IllegalStateException("retryUntilSuccess: 未执行任何尝试")
}

/** 组装请求：默认 UA 先放，调用方 headers 覆盖之 */
private fun buildRequest(url: String, headers: Map<String, String>?): Request {
    val builder = Request.Builder().url(url).header("User-Agent", DEFAULT_UA)
    headers?.forEach { (k, v) -> builder.header(k, v) }
    return builder.build()
}

/** 简单 GET 返回字符串（处理非 JSON 文本响应，如 kugou/kuwo 伪 JSON） */
suspend fun httpGetText(url: String, headers: Map<String, String>? = null): String =
    withContext(Dispatchers.IO) {
        val resp = globalOkHttp.newCall(buildRequest(url, headers)).execute()
        resp.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} for $url")
            it.body?.string() ?: ""
        }
    }

/** 简单 GET 返回原始字节（酷我 PC 歌词是「文本头 + zlib 二进制体」的混合响应） */
suspend fun httpGetBytes(url: String, headers: Map<String, String>? = null): ByteArray =
    withContext(Dispatchers.IO) {
        val resp = globalOkHttp.newCall(buildRequest(url, headers)).execute()
        resp.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} for $url")
            it.body?.bytes() ?: ByteArray(0)
        }
    }

/** 简单 POST 返回 Map（JSON） */
suspend fun httpPostJson(
    url: String,
    headers: Map<String, String>? = null,
    body: Any? = null,
): Map<String, Any?> {
    val payload: String = when (body) {
        null -> ""
        is String -> body
        else -> anyToJsonElement(body).toString()
    }
    // 对齐 Flutter dio：媒体类型尊重调用方显式声明的 Content-Type，未声明才默认 JSON。
    // OkHttp 的 BridgeInterceptor 会用 RequestBody 的类型覆盖同名请求头，若这里不看调用方头，
    // 会把网易的 application/x-www-form-urlencoded 表单体当 JSON 发出（服务器返回 400 参数错误）。
    val declaredType = headers?.entries
        ?.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
        ?.value
    val mediaType = declaredType?.toMediaTypeOrNull()
        ?: "application/json; charset=utf-8".toMediaType()
    val text = withContext(Dispatchers.IO) {
        val req = buildRequest(url, headers).newBuilder()
            .post(payload.toRequestBody(mediaType))
            .build()
        val resp = globalOkHttp.newCall(req).execute()
        resp.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} for $url")
            it.body?.string() ?: ""
        }
    }
    return decodeJsonMap(text)
}

/** 简单 GET 返回 Map（JSON），编码处理同 [httpPostJson] */
suspend fun httpGetJson(url: String, headers: Map<String, String>? = null): Map<String, Any?> =
    decodeJsonMap(httpGetText(url, headers))

/**
 * 文本 -> Map。空文本返回空 Map（对齐 Dart `if (raw.trim().isEmpty) return {}`）。
 *
 * 与 Dart 的差异：Dart 用 `as Map<String, dynamic>`，顶层不是对象时会抛异常；
 * 这里退化为空 Map 更稳（调用方本来都包在 try/catch 里）。
 */
private fun decodeJsonMap(raw: String): Map<String, Any?> {
    if (raw.isBlank()) return emptyMap()
    val element = json.parseToJsonElement(raw)
    @Suppress("UNCHECKED_CAST")
    return jsonElementToAny(element) as? Map<String, Any?> ?: emptyMap()
}

/**
 * JsonElement -> 原生容器，对齐 Dart `jsonDecode` 的语义：
 * - 对象 -> `Map<String, Any?>`
 * - 数组 -> `List<Any?>`
 * - 字符串 -> String；true/false -> Boolean
 * - 整数 -> Int（超出 Int 用 Long）；小数 -> Double
 * - null -> null
 */
fun jsonElementToAny(element: JsonElement?): Any? = when (element) {
    null -> null
    is JsonNull -> null
    is JsonObject -> element.mapValues { (_, v) -> jsonElementToAny(v) }
    is JsonArray -> element.map { jsonElementToAny(it) }
    is JsonPrimitive -> when {
        element.isString -> element.content
        element.content == "true" -> true
        element.content == "false" -> false
        else -> {
            val content = element.content
            content.toIntOrNull() ?: content.toLongOrNull() ?: content.toDoubleOrNull() ?: content
        }
    }
    else -> null
}

/** 原生容器 -> JsonElement（[CacheService] 写入缓存时使用，与 [jsonElementToAny] 互逆） */
fun anyToJsonElement(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is Boolean -> JsonPrimitive(value)
    is Int -> JsonPrimitive(value)
    is Long -> JsonPrimitive(value)
    is Double -> JsonPrimitive(value)
    is Float -> JsonPrimitive(value.toDouble())
    is Number -> JsonPrimitive(value.toDouble())
    is String -> JsonPrimitive(value)
    is Map<*, *> -> buildJsonObject {
        value.forEach { (k, v) -> put(k?.toString() ?: "", anyToJsonElement(v)) }
    }
    is Iterable<*> -> buildJsonArray { value.forEach { add(anyToJsonElement(it)) } }
    is Array<*> -> buildJsonArray { value.forEach { add(anyToJsonElement(it)) } }
    else -> JsonPrimitive(value.toString())
}
