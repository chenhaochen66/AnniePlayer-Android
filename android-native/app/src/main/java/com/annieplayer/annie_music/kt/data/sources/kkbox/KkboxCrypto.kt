package com.annieplayer.annie_music.kt.data.sources.kkbox

import java.security.MessageDigest

// ================================================================================================
// KKBOX 加解密原语（纯 Kotlin，无原生依赖）
// ================================================================================================
//
// 原 RN 版依赖原生模块 `KkboxDecoder`；这里改为逐字节等价的纯 Kotlin RC4，
// KSA + PRGA 手写，含 drop。MD5 用 `MessageDigest`（对 ASCII 输入与 RN 手写实现一致）。

/** 响应体 RC4 密钥（无 drop） */
const val kc1Key: String = "7f1a68f00b747f4ac1469c72e7ef492c"

/** 签名密钥 */
const val secretKey: String = "0ff29b7c9bd8fb60a3abd6b3d402b02c"

/**
 * RC4 流密码（KSA + PRGA 逐字节），支持 drop 参数。
 *
 * 实例在多次 [process] 之间保持 S 盒与 i/j 状态，因此可分块调用以流式解密（与 RN 版行为一致）。
 *
 * 逐字节对齐说明：Dart 的 `key.codeUnits` 为 UTF-16 码元（0..65535），故这里用
 * `key[i].code` 取码元而非 UTF-8 字节；`& 0xFF` 显式掩码复刻 Dart 的 8 位回绕。
 */
internal class KkboxRc4(key: String, drop: Int = 0) {
    private val _s = IntArray(256)
    private var _i = 0
    private var _j = 0

    init {
        val keyCodes = IntArray(key.length) { key[it].code }
        for (i in 0 until 256) {
            _s[i] = i
        }

        // KSA
        var j = 0
        for (i in 0 until 256) {
            j = (j + _s[i] + keyCodes[i % keyCodes.size]) and 0xFF
            val t = _s[i]
            _s[i] = _s[j]
            _s[j] = t
        }

        // drop
        for (n in 0 until drop) {
            _i = (_i + 1) and 0xFF
            _j = (_j + _s[_i]) and 0xFF
            val t = _s[_i]
            _s[_i] = _s[_j]
            _s[_j] = t
        }
    }

    /** PRGA：加密/解密输入（异或流密钥） */
    fun process(input: ByteArray): ByteArray {
        val output = ByteArray(input.size)
        for (n in input.indices) {
            _i = (_i + 1) and 0xFF
            _j = (_j + _s[_i]) and 0xFF
            val t = _s[_i]
            _s[_i] = _s[_j]
            _s[_j] = t
            val k = _s[(_s[_i] + _s[_j]) and 0xFF]
            output[n] = ((input[n].toInt() and 0xFF) xor k).toByte()
        }
        return output
    }
}

/** 一次性 RC4（key + data + drop） */
internal fun kkboxRc4(key: String, data: ByteArray, drop: Int = 0): ByteArray =
    KkboxRc4(key, drop).process(data)

/** MD5 十六进制小写（对齐 Dart `package:crypto` 的 `md5.convert(...).toString()`） */
internal fun kkboxMd5(s: String): String {
    val digest = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
    val sb = StringBuilder()
    for (b in digest) {
        val v = b.toInt() and 0xFF
        if (v < 16) sb.append('0')
        sb.append(v.toString(16))
    }
    return sb.toString()
}
