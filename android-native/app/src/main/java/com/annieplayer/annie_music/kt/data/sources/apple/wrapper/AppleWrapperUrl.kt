package com.annieplayer.annie_music.kt.data.sources.apple.wrapper

/**
 * Wrapper 地址解析 —— **纯函数**，不碰网络也不碰 Android，可单测。
 *
 * 用户填的可能是这几种形态（都见过）：
 *
 * ```
 * https://qXut5osAOzeJN5dG-YXxq8-5@wm.wol.moe      ← 服务方给的那种（userinfo 里是 token）
 * http://192.168.1.7:12341                          ← 局域网里的自建实例
 * 127.0.0.1:12341                                   ← 只有 host:port（本机 wrapper-lite）
 * https://host/prefix                               ← 挂在反代子路径下
 * ```
 *
 * 输出 [WrapperEndpoint]：
 * - [base] 去掉 userinfo 与尾部 `/` 的根地址（拼路径时直接用 `"$base/m3u8?…"`）；
 * - [userInfo] 原样的 `user` 或 `user:pass`（HTTP Basic 的载荷，**不含** `Basic ` 前缀）。
 *
 * 解析失败返回 null（调用方按「地址无效」处理，不要瞎猜）。
 */
internal data class WrapperEndpoint(
    val base: String,
    val userInfo: String?,
) {
    /** 显示用：`scheme://host[:port][/path]`（userinfo 已在 base 里去掉） */
    val display: String get() = base

    /** HTTP Basic 的明文载荷（`user` 或 `user:pass`；密码空时也要带冒号） */
    val basicPayload: String?
        get() = userInfo?.let { if (it.contains(':')) it else "$it:" }
}

private val _schemeRe = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://")
private val _hostOkRe = Regex("^[A-Za-z0-9._\\-\\[\\]:]+$")

/**
 * 解析一条 wrapper 地址。
 *
 * [raw] 允许两侧空白；没有 scheme 时补 `http://`（本机 / 局域网实例通常是明文 HTTP）。
 */
internal fun parseWrapperUrl(raw: String): WrapperEndpoint? {
    var text = raw.trim()
    if (text.isEmpty()) return null
    // 用户可能只贴了 host:port（本机 wrapper-lite 的写法）
    if (!_schemeRe.containsMatchIn(text)) text = "http://$text"

    val schemeEnd = text.indexOf("://")
    if (schemeEnd <= 0) return null
    val scheme = text.substring(0, schemeEnd).lowercase()
    if (scheme != "http" && scheme != "https") return null
    var rest = text.substring(schemeEnd + 3)

    // 去掉 fragment / query（地址栏里常带着复制来的尾巴）
    rest = rest.substringBefore('#').substringBefore('?')
    if (rest.isEmpty()) return null

    // 拆 userinfo（最后一个 '@' 之前都是 userinfo：密码里可能含 '@'）
    var userInfo: String? = null
    val at = rest.lastIndexOf('@')
    if (at > 0) {
        val rawInfo = rest.substring(0, at)
        rest = rest.substring(at + 1)
        // 逐段解码：token 可能有 %40 这类转义，也可能需要原样保留
        val decoded = rawInfo.split(':').joinToString(":") { _percentDecode(it) }
        if (decoded.isNotEmpty()) userInfo = decoded
    }

    val hostEnd = rest.indexOfFirst { it == '/' }
    val authority = if (hostEnd < 0) rest else rest.substring(0, hostEnd)
    val path = if (hostEnd < 0) "" else rest.substring(hostEnd)
    if (authority.isEmpty() || !_hostOkRe.matches(authority)) return null

    val base = (scheme + "://" + authority + path).trimEnd('/')
    return WrapperEndpoint(base = base, userInfo = userInfo)
}

/** `%XX` 解码（非法转义原样保留，与浏览器行为一致） */
private fun _percentDecode(text: String): String {
    if (!text.contains('%')) return text
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '%' && i + 2 < text.length) {
            val hex = text.substring(i + 1, i + 3)
            val code = hex.toIntOrNull(16)
            if (code != null) {
                out.append(code.toChar())
                i += 3
                continue
            }
        }
        out.append(c)
        i += 1
    }
    return out.toString()
}
