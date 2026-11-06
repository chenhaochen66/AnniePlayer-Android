package com.annieplayer.annie_music.kt.data.sources.kkbox

import com.annieplayer.annie_music.kt.data.model.KkboxSession
import com.annieplayer.annie_music.kt.data.sources.common.jsTruthy
import kotlin.random.Random

// ================================================================================================
// KKBOX 会话：登录 / 续期 / 设备认证 / 登出
// ================================================================================================

/** 当前 KKBOX 会话 sid 提供者（由 AppShell 注入；未登录返回 null） */
var kkboxSidProvider: () -> String? = { null }

/**
 * 当前 KKBOX 完整会话（sid + 设备号 kkid + 内容密钥 licContentKey）。
 *
 * 播放 / 下载要用**官方票据接口**，而票据接口同时需要 sid 与 kkid，下载还要 licContentKey
 * 才能解密 DRM 音频 —— 所以这里给整份会话，而不是只给 sid。
 * 未登录返回 null，调用方必须直接失败（KKBOX 不借任何别家音源）。
 */
var kkboxSessionProvider: () -> KkboxSession? = { null }

/**
 * 票据接口可能**续期 sid**：拿到新 sid 后写回账号。
 *
 * 不写回的话下次请求还带旧 sid，会一路 -1 重试到失败。未注入时忽略（只影响下次请求的耗时）。
 */
var kkboxSessionUpdater: (KkboxSession) -> Unit = {}

/** 登录 KKBOX，返回会话信息（sid / licContentKey / highQuality / kkid） */
suspend fun login(email: String, password: String): KkboxLoginResult {
    val rnd = Random.Default
    val kkid = List(32) { rnd.nextInt(16).toString(16) }.joinToString("").uppercase()
    val passwd = kkboxMd5(password)
    val resp = kkboxApiCall(
        "login",
        "login.php",
        payload = mapOf(
            "uid" to email,
            "passwd" to passwd,
            "kkid" to kkid,
            "registration_id" to "",
        ),
    )
    val status = kkboxInt(resp["status"])
    if (status != 2 && status != 3) {
        val errors = mapOf(
            -1 to "邮箱不存在",
            -2 to "密码错误",
            -4 to "IP 地区不支持，请使用 VPN",
            1 to "账户已过期",
        )
        throw IllegalStateException(errors[status] ?: "登录失败: $status")
    }
    return KkboxLoginResult(
        sid = kkboxStr(resp["sid"]),
        licContentKey = kkboxStr(resp["lic_content_key"]),
        highQuality = jsTruthy(resp["high_quality"]),
        kkid = kkid,
    )
}

/** 续期会话 */
suspend fun renewSession(sid: String): KkboxSessionInfo {
    val resp = kkboxApiCall("login", "check.php", params = mapOf("sid" to sid))
    val status = kkboxInt(resp["status"])
    if (status != 2 && status != 3) throw IllegalStateException("会话续期失败")
    return KkboxSessionInfo(
        sid = kkboxStr(resp["sid"]),
        licContentKey = kkboxStr(resp["lic_content_key"]),
        highQuality = jsTruthy(resp["high_quality"]),
    )
}

/** 设备认证 */
suspend fun authDevice(sid: String, kkid: String) {
    val resp = kkboxApiCall(
        "ds",
        "active_sid.php",
        params = mapOf("sid" to sid),
        payload = mapOf(
            "ui_lang" to "en",
            "of" to "j",
            "os" to "android",
            "enc" to "u",
            "sid" to sid,
            "ver" to "06120082",
            "kkid" to kkid,
            "lang" to "en",
            "oenc" to "kc1",
            "osver" to "13",
        ),
    )
    if (kkboxInt(resp["status"]) != 1) throw IllegalStateException("设备认证失败")
}

/**
 * 登出。
 *
 * RN 版 kkboxApi.ts 无服务端登出接口，会话清理由 store 层负责；
 * 此处保留以对齐会话管理 API 面（无副作用）。
 */
suspend fun logout() {
    // 无服务端接口：与 RN 版一致，无副作用
}
