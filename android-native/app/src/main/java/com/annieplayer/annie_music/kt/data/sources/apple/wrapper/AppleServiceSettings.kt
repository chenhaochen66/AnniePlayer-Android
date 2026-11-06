package com.annieplayer.annie_music.kt.data.sources.apple.wrapper

import android.content.Context
import android.content.SharedPreferences

/**
 * 本机 wrapper-lite 的固定端口（由 WrapperLite 应用托管：它跑在 QEMU guest 里，
 * 把 12341 转发出来）。**不走**它自带的 CORS 代理 —— 原生请求没有同源限制，
 * 而那层代理长时间运行后会卡住（端口在听但无响应）。
 */
const val APPLE_LOCAL_WRAPPER_BASE = "http://127.0.0.1:12341"

/**
 * Apple 非官方接口的**源选择**：本机 wrapper-lite ↔ 用户自填的远程 wrapper 地址。
 *
 * 开 = 本机（[APPLE_LOCAL_WRAPPER_BASE]，无认证）；关 = 远程（[AppleWrapperRegistry] 里选中的那条，带 Basic）。
 * 两条路**互斥、没有自动回落**：本机挂了就报错，不会偷偷去远程（反之亦然）——
 * 静默回落会让人分不清「到底在用哪台机、烧的是谁的账号额度」。
 *
 * 以前的第三个选项（第三方站点 ak1ra，带 Cloudflare 挑战 / WebView 兜底 / 原地重试）
 * 连同它的重试与回落逻辑一起删掉了。
 */
object AppleServiceSettings {

    private const val PREFS = "apple_service"
    private const val KEY_USE_LOCAL = "use_local"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** 默认开启：本机服务给出的 key 模板才是可用的 */
    val useLocal: Boolean
        get() = prefs?.getBoolean(KEY_USE_LOCAL, true) ?: true

    fun setUseLocal(value: Boolean) {
        prefs?.edit()?.putBoolean(KEY_USE_LOCAL, value)?.apply()
    }
}
