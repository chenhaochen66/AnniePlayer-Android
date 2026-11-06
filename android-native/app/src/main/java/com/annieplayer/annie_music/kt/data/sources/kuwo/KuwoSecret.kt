package com.annieplayer.annie_music.kt.data.sources.kuwo

import kotlin.random.Random

// ================================================================================================
// 酷我签名（由 Python 脚本移植，逐字节对齐；服务端会校验）
// ================================================================================================

/** 自定义签名使用的 cookie 名 */
private const val _kwCookieName = "Hm_Iuvt_cdb524f42f23cer9b268564v7y735ewrq2324"

/** 硬编码 cookie 值（供 [generateKuwoSecret] 调用方使用，与 RN 版保持一致） */
const val kuwoHardcodedCookie = "RiGQWD34iKkZrnSYQycpmdGKAkaawJEA"

private fun _charAt(s: String, i: Int): String =
    if (i >= 0 && i < s.length) s.substring(i, i + 1) else ""

/** 哈希函数（对齐 RN v(data)，31 进制滚动哈希，掩码 0x7FFFFFFF） */
fun v(data: String): String {
    // 注意：必须用 Long —— Dart 的 int 是 64 位，Kotlin Int 会在掩码前溢出（多字节/中文输入尤甚）
    var value = 0L
    for (i in data.indices) {
        val c = data[i].code
        value = (value * 31 + c) and 0x7FFFFFFF
    }
    return value.toString()
}

/** 依据 cookie 推导 Secret 头（对齐 RN getSecret） */
fun getSecret(cookie: String): String {
    val s = v(cookie)
    val keys = listOf("3d6b", "b4a2", "9f7e", "2c81", "e5f0", "1a8b", "7c3d", "6e9f")
    val last2 = if (s.length >= 2) s.substring(s.length - 2) else s
    val idx = (last2.toIntOrNull() ?: 0) % keys.size
    return keys[idx]
}

/**
 * 生成酷我签名 Secret（对齐 RN generateKuwoSecret，逐字节一致）。
 *
 * 自定义 PRNG：以 cookieName 的数字拼接串为种子，对 cookieVal 逐字符异或后
 * 生成十六进制串，末尾附 8 位随机十六进制。
 */
fun generateKuwoSecret(cookieVal: String, cookieName: String = _kwCookieName): String {
    val nBuf = StringBuilder()
    for (i in cookieName.indices) {
        nBuf.append(cookieName[i].code.toString())
    }
    val n = nBuf.toString()
    val o = n.length / 5
    val rStr =
        _charAt(n, o) +
            _charAt(n, 2 * o) +
            _charAt(n, 3 * o) +
            _charAt(n, 4 * o) +
            _charAt(n, 5 * o)
    val r = rStr.toLongOrNull() ?: 0L
    val c = (cookieName.length + 1) / 2 // ceil(len/2)
    val l = 2147483647L // 2^31 - 1
    if (r < 2L) return ""
    val d = (Random.nextDouble() * 1e9).toLong() % 100000000L
    var nStr = n + d.toString()

    // 前 10 位 + 后 15 位相加，避免 JS 大数精度问题（逐字节对齐 RN 循环）
    while (nStr.length > 10) {
        val first = nStr.substring(0, 10).toLongOrNull() ?: 0L
        val end = if (nStr.length < 25) nStr.length else 25
        val rest = nStr.substring(10, end).toLongOrNull() ?: 0L
        nStr = (first + rest).toString()
    }

    var nVal = (r * (nStr.toLongOrNull() ?: 0L) + c) % l
    val hBuf = StringBuilder()
    for (i in cookieVal.indices) {
        // 注意：Dart 的 `/` 是浮点除法，这里必须用 Double 才能逐字节对齐
        val xorKey = ((nVal.toDouble() / l.toDouble()) * 255.0).toInt()
        val fVal = cookieVal[i].code xor xorKey
        hBuf.append(if (fVal < 16) "0" else "")
        hBuf.append(fVal.toString(16))
        nVal = (r * nVal + c) % l
    }
    return hBuf.toString() + d.toString(16).padStart(8, '0')
}
