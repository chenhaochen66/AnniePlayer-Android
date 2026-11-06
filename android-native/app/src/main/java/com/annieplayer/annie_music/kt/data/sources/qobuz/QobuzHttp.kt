package com.annieplayer.annie_music.kt.data.sources.qobuz

import com.annieplayer.annie_music.kt.data.network.httpGetJson
import com.annieplayer.annie_music.kt.data.sources.common.md5Hex
import java.io.IOException

// ================================================================================================
// Qobuz 传输层：签名 / 请求头 / GET + 状态码还原
// ================================================================================================
//
// 只放「怎么发请求」。业务（哪条 URL、怎么映射）在 QobuzApi / QobuzSearch / QobuzLyric 里。

/**
 * 生成请求签名（对齐 RN 版 createSignature）。
 *
 * md5(methodKey + Σ(按 key 升序拼 k+v，过滤 app_id/user_auth_token) + ts + APP_SECRET)
 */
fun createSignature(method: String, params: Map<String, String>): QobuzSignature {
    val ts = (System.currentTimeMillis() / 1000).toString()
    val methodKey = method.replaceFirst("/", "")
    val keys = params.keys
        .filter { it != "app_id" && it != "user_auth_token" }
        .sorted()
    var raw = methodKey
    for (k in keys) {
        raw += k + (params[k] ?: "")
    }
    raw += ts + qobuzAppSecret
    return QobuzSignature(ts = ts, sig = md5Hex(raw))
}

/** 构造 Qobuz 请求头（对齐 RN 版 headers，固定 Android 设备指纹） */
fun qobuzHeaders(token: String): Map<String, String> {
    val base = mapOf(
        "X-Device-Platform" to "android",
        "X-Device-Model" to "Pixel 3",
        "X-Device-Os-Version" to "10",
        "X-Device-Manufacturer-Id" to "482D8CB7-015D-402F-A93B-5EEF0E0996F3",
        "X-App-Version" to "5.16.1.5",
        "User-Agent" to
            "Dalvik/2.1.0 (Linux; U; Android 10; Pixel 3 Build/QP1A.190711.020))QobuzMobileAndroid/5.16.1.5-b21041415",
    )
    // 搜索 / 专辑 / 歌手接口免 token：为空时不带该头（带空串会被服务端判未授权）
    return if (token.isEmpty()) base else base + ("X-User-Auth-Token" to token)
}

/** 从网络异常信息中解析 HTTP 状态码（网络层非 2xx 抛 `IOException("HTTP {code} for ...")`） */
internal fun qobuzHttpStatusFrom(error: Throwable): Int? {
    val msg = error.message ?: return null
    val m = Regex("HTTP (\\d+)").find(msg) ?: return null
    return m.groupValues[1].toIntOrNull()
}

/** 发送 GET 并解析 JSON（非 2xx 抛 `HTTP {status}`，与 RN 版一致） */
internal suspend fun qobuzGetJson(url: String, token: String): Map<String, Any?> {
    return try {
        httpGetJson(url, qobuzHeaders(token))
    } catch (e: IOException) {
        val status = qobuzHttpStatusFrom(e)
        if (status != null) throw IllegalStateException("HTTP $status")
        throw e
    }
}
