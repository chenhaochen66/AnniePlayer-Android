package com.annieplayer.annie_music.kt.data.sources.apple.thirdparty

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * 第三方 Apple 解析站的 Cloudflare 人机验证（managed challenge）过检器。
 *
 * ## 为什么需要它
 * 站点在部分网络下会用 CF 挑战拦掉**非浏览器**请求：同一条 IP 上 Chrome 直接 200，
 * 而 OkHttp/curl 拿到 `403 + Just a moment...`。App 的 HTTP 客户端没有 JS 能力，永远过不了，
 * 表现就是「Apple master 获取失败」→ 播放/下载/歌词全废。
 *
 * ## 做法
 * 用一个隐藏 WebView 加载站点首页，等它自己跑完挑战脚本，从 `CookieManager` 取出
 * `cf_clearance` 以及那次加载用的 UA；之后把这两样塞进 OkHttp 请求头重试一次。
 *
 * 注意：`cf_clearance` 与 **UA、IP** 绑定，所以 UA 必须原样复用 WebView 的（不能用假的 Chrome UA），
 * 也不能在换网络后继续用旧 cookie（[invalidate] 由调用方在仍失败时触发）。
 * cookie 不落盘：只在进程内复用，重启后重新过一次挑战即可（挑战通常 1~3 秒）。
 */
internal object AppleChallenge {

    private const val MAX_WAIT_MS = 20_000L
    private const val POLL_INTERVAL_MS = 400L

    /** 页面加载完后给挑战脚本的宽限；超时还没有 cookie 就判失败，交给浏览器通道兜底 */
    private const val CHALLENGE_GRACE_MS = 3_000L

    /**
     * cookie 复用时长。
     *
     * Cloudflare 的 `cf_clearance` 默认半小时左右，这里取 30 分钟并**落盘**：冷启动后
     * 第一首 Apple 歌就不用重新过挑战（也就是省掉那 1~3 秒）。换网络导致失效时，
     * 请求会拿到 403 → 自动作废并重过一次，所以拿旧值试没有副作用。
     */
    private const val COOKIE_TTL_MS = 30 * 60 * 1000L

    private const val COOKIE_KEY = "cf_clearance"

    private const val PREFS = "annie_apple_cf"
    private const val KEY_COOKIE = "cookie"
    private const val KEY_UA = "ua"
    private const val KEY_AT = "fetched_at"

    @Volatile
    private var appContext: Context? = null

    private var prefs: SharedPreferences? = null

    @Volatile
    private var cookie: String? = null

    @Volatile
    private var userAgent: String? = null

    @Volatile
    private var solvedAt: Long = 0L

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        val store = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = store
        // 冷启动先尝试恢复上次的 cookie（多数情况下还没过期，省掉一次挑战）
        val savedAt = store.getLong(KEY_AT, 0L)
        val savedCookie = store.getString(KEY_COOKIE, null)
        if (savedAt > 0L &&
            System.currentTimeMillis() - savedAt < COOKIE_TTL_MS &&
            !savedCookie.isNullOrBlank() &&
            savedCookie.contains(COOKIE_KEY)
        ) {
            cookie = savedCookie
            userAgent = store.getString(KEY_UA, null)
            solvedAt = savedAt
        }
    }

    /** 是否有一份还能用的 `cf_clearance` */
    fun isFresh(): Boolean =
        cookie != null && System.currentTimeMillis() - solvedAt < COOKIE_TTL_MS

    /** 带上这份 Cookie 头；没有可用 cookie 时返回 null */
    fun cookieHeader(): String? = cookie?.takeIf { isFresh() }

    /** 过挑战时用的 UA；没有可用的 cookie 时返回 null */
    fun userAgent(): String? = userAgent?.takeIf { isFresh() }

    /** 作废当前 cookie（带了它也还是 403，说明绑定关系已变） */
    fun invalidate() {
        cookie = null
        userAgent = null
        solvedAt = 0L
        runCatching {
            prefs?.edit()?.remove(KEY_COOKIE)?.remove(KEY_UA)?.remove(KEY_AT)?.apply()
        }
    }

    /**
     * 由 [AppleWebViewFetch] 兜底成功时回填：WebView 里已经继承了通过挑战的 cookie，
     * 借给 OkHttp 用，后续请求就能回到快路径。
     */
    fun adoptFromWebView(rawCookie: String, rawUserAgent: String?) {
        if (!rawCookie.contains(COOKIE_KEY)) return
        cookie = rawCookie
        userAgent = rawUserAgent?.takeIf { it.isNotBlank() }
        solvedAt = System.currentTimeMillis()
        persist()
    }

    /** 写入磁盘，供下次冷启动直接复用 */
    private fun persist() {
        val value = cookie ?: return
        runCatching {
            prefs?.edit()
                ?.putString(KEY_COOKIE, value)
                ?.putString(KEY_UA, userAgent)
                ?.putLong(KEY_AT, solvedAt)
                ?.apply()
        }
    }

    /**
     * 加载 [url]，等 `cf_clearance` 出现在 cookie 里。
     *
     * @return 是否拿到了 cookie（拿到即已缓存，可直接给 HTTP 客户端用）
     */
    suspend fun solve(url: String): Boolean {
        if (isFresh()) return true
        val context = appContext ?: return false

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val web = runCatching { WebView(context) }.getOrNull()
                if (web == null) {
                    continuation.resume(false)
                    return@suspendCancellableCoroutine
                }

                val finished = AtomicBoolean(false)
                val handler = Handler(Looper.getMainLooper())
                val startedAt = SystemClock.uptimeMillis()

                fun currentCookie(): String =
                    runCatching { CookieManager.getInstance().getCookie(url) }
                        .getOrNull()
                        .orEmpty()

                fun finish(success: Boolean) {
                    if (!finished.compareAndSet(false, true)) return
                    val ua = runCatching { web.settings.userAgentString }.getOrNull().orEmpty()
                    val acquired = currentCookie()
                    runCatching {
                        web.stopLoading()
                        web.webViewClient = WebViewClient()
                        web.destroy()
                    }
                    if (success && acquired.contains(COOKIE_KEY)) {
                        cookie = acquired
                        userAgent = ua.ifEmpty { null }
                        solvedAt = System.currentTimeMillis()
                        persist()
                        continuation.resume(true)
                    } else {
                        continuation.resume(false)
                    }
                }

                fun poll() {
                    if (finished.get()) return
                    if (currentCookie().contains(COOKIE_KEY)) {
                        finish(true)
                        return
                    }
                    if (SystemClock.uptimeMillis() - startedAt > MAX_WAIT_MS) {
                        finish(false)
                        return
                    }
                    handler.postDelayed({ poll() }, POLL_INTERVAL_MS)
                }

                runCatching {
                    CookieManager.getInstance().setAcceptCookie(true)
                    web.settings.javaScriptEnabled = true
                    web.settings.domStorageEnabled = true
                    web.settings.userAgentString?.let { /* 保留 WebView 原生 UA：cf_clearance 与它绑定 */ }
                    web.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, pageUrl: String?) {
                            // 页面已加载完：给挑战脚本 3 秒宽限。若还是没有 cf_clearance，
                            // 说明本站当前要么不需要挑战、要么挑战没跑完 —— 立刻判失败，
                            // 让调用方走 [AppleWebViewFetch] 的浏览器通道，比空等 20 秒划算。
                            handler.postDelayed({
                                if (!finished.get() && !currentCookie().contains(COOKIE_KEY)) {
                                    finish(false)
                                }
                            }, CHALLENGE_GRACE_MS)
                            poll()
                        }
                    }
                    web.loadUrl(url)
                }.onFailure { finish(false) }

                handler.postDelayed({ poll() }, POLL_INTERVAL_MS)
                continuation.invokeOnCancellation {
                    runCatching { web.stopLoading() }
                    runCatching { web.destroy() }
                }
            }
        }
    }
}
