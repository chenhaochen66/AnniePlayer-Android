package com.annieplayer.annie_music.kt.data.sources.apple.thirdparty

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** WebView 里 fetch 用的同源页面（只为拿 cookie / 通过挑战，不解析内容） */
private const val ORIGIN = "https://music.ak1ra.de5.net/"

/**
 * 第三方 Apple 接口的第二条通道：**在 WebView 里用 `fetch` 发请求**（兜底）。
 *
 * ## 为什么要它
 * [AppleChallenge] 是「OkHttp + 借来的 `cf_clearance`」，快，但只在 Cloudflare 按 IP + UA
 * 判定时成立；一旦站点改绑 TLS/JA4 指纹，OkHttp 会重新 403。这里的请求从真正的 Chromium
 * 发出，TLS / HTTP2 / JS / cookie 全部原生，指纹层面无法区分——所以只放在最后兜底，
 * **平时一次都不创建 WebView**。
 *
 * ## 体积 / 内存
 * `android.webkit.WebView` 是系统组件，不增加 APK 体积（引擎由系统 WebView 提供）。
 * 代价只有内存（约 30~60MB），因此空闲 60 秒就 `destroy()`，需要时再建。
 *
 * ## 串行
 * 同一时刻只服务一个请求；拿不到锁直接返回 null，绝不阻塞播放线程。
 */
internal object AppleWebViewFetch {

    private const val PAGE_TIMEOUT_MS = 12_000L
    private const val FETCH_TIMEOUT_MS = 15_000L
    private const val IDLE_DESTROY_MS = 60_000L
    private const val BRIDGE_NAME = "AnnieFetch"

    @Volatile
    private var appContext: Context? = null

    private val gate = Mutex()

    // 以下状态只在主线程读写
    private var webView: WebView? = null
    private var pageReady = false
    private var lastUsedAt = 0L
    private var pendingId = 0
    private var pending: ((Int, String?) -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())
    private val idSeq = AtomicInteger(0)

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun isAvailable(): Boolean = appContext != null

    /** 在 WebView 里 GET [url]；任何失败返回 null（调用方保留原异常语义） */
    suspend fun getText(url: String): String? {
        val context = appContext ?: return null
        // 占着锁的可能是播放/下载/歌词任意一方；排队等，但整体加超时，绝不无限阻塞
        return withTimeoutOrNull(PAGE_TIMEOUT_MS + FETCH_TIMEOUT_MS) {
            gate.withLock { runFetch(context, url) }
        }
    }

    private suspend fun runFetch(context: Context, url: String): String? {
        return try {
            withContext(Dispatchers.Main) {
                if (!ensurePage(context)) return@withContext null
                val result = executeFetch(url) ?: return@withContext null
                val (status, body) = result
                if (status !in 200..299 || body.isNullOrEmpty()) return@withContext null
                adoptCookies(url)
                body
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** 这次兜底成功后，把 cookie / UA 交给快路径，后续请求回到 OkHttp */
    private fun adoptCookies(url: String) {
        runCatching {
            val cookie = CookieManager.getInstance().getCookie(url).orEmpty()
            val ua = webView?.settings?.userAgentString.orEmpty()
            AppleChallenge.adoptFromWebView(cookie, ua)
        }
    }

    /**
     * 保证 WebView 加载过同源页面（挑战脚本会在这期间跑完）。
     *
     * 完成条件：出现 `cf_clearance`，或等满 [PAGE_TIMEOUT_MS]。超时也放行——有些网络下
     * 站点对浏览器直接放行、不发挑战，此时本来就没有 cookie，让 fetch 自己给出状态码即可。
     */
    private suspend fun ensurePage(context: Context): Boolean {
        if (pageReady && webView != null) return true
        val web = webView ?: runCatching { createWebView(context) }.getOrNull() ?: return false
        webView = web
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                // 挑战脚本在 JS 里继续跑，这里不结算，交给下面的轮询
            }
        }
        runCatching { web.loadUrl(ORIGIN) }

        withTimeoutOrNull(PAGE_TIMEOUT_MS) {
            while (!hasClearance()) delay(300L)
        }
        pageReady = true
        lastUsedAt = SystemClock.uptimeMillis()
        return true
    }

    private fun hasClearance(): Boolean = runCatching {
        CookieManager.getInstance().getCookie(ORIGIN).orEmpty().contains("cf_clearance")
    }.getOrDefault(false)

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context): WebView {
        val web = WebView(context)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.loadsImagesAutomatically = false
        runCatching { CookieManager.getInstance().setAcceptCookie(true) }
        web.addJavascriptInterface(Bridge(), BRIDGE_NAME)
        return web
    }

    private suspend fun executeFetch(url: String): Pair<Int, String?>? =
        suspendCancellableCoroutine { continuation ->
            val web = webView ?: run {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            val id = idSeq.incrementAndGet()
            val finished = AtomicBoolean(false)

            fun settle(status: Int, body: String?) {
                if (!finished.compareAndSet(false, true)) return
                pending = null
                lastUsedAt = SystemClock.uptimeMillis()
                scheduleIdleDestroy()
                if (continuation.isActive) continuation.resume(status to body)
            }

            pendingId = id
            pending = { status, body -> settle(status, body) }

            handler.postDelayed({ if (!finished.get()) settle(0, null) }, FETCH_TIMEOUT_MS)

            runCatching { web.evaluateJavascript(buildFetchScript(id, url), null) }
                .onFailure { settle(0, null) }

            continuation.invokeOnCancellation { settle(0, null) }
        }

    /**
     * `credentials:'include'` 让 `cf_clearance` 自动带上；url 用 [JSONObject.quote] 转义，
     * 避免查询串里的引号把脚本截断。
     */
    private fun buildFetchScript(id: Int, url: String): String {
        val quotedUrl = JSONObject.quote(url)
        return "(function(){" +
            "var id=$id;" +
            "fetch($quotedUrl,{credentials:'include',headers:{'Accept':'application/json'}})" +
            ".then(function(r){return r.text().then(function(t){$BRIDGE_NAME.onResult(id,r.status,t);});})" +
            ".catch(function(e){$BRIDGE_NAME.onError(id,String(e));});" +
            "})();"
    }

    private fun scheduleIdleDestroy() {
        handler.postDelayed({
            if (pending != null) return@postDelayed
            if (SystemClock.uptimeMillis() - lastUsedAt < IDLE_DESTROY_MS) return@postDelayed
            destroyWebView()
        }, IDLE_DESTROY_MS)
    }

    private fun destroyWebView() {
        val web = webView ?: return
        webView = null
        pageReady = false
        runCatching {
            web.removeJavascriptInterface(BRIDGE_NAME)
            web.webViewClient = WebViewClient()
            web.stopLoading()
            web.destroy()
        }
    }

    /** JS 桥：回调来自 WebView 的 JS 线程，切回主线程 */
    private class Bridge {
        @JavascriptInterface
        fun onResult(id: Int, status: Int, body: String?) {
            handler.post {
                if (id != pendingId) return@post
                pending?.invoke(status, body)
            }
        }

        @JavascriptInterface
        fun onError(id: Int, message: String?) {
            handler.post {
                if (id != pendingId) return@post
                pending?.invoke(0, null)
            }
        }
    }
}
