package com.annieplayer.annie_music.kt.core.util

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.Inflater

/**
 * 酷我 PC 端歌词（LRCX 逐字 / 普通 LRC）解密。
 *
 * 请求参数与 LRCX 正文都用密钥 "yeelion" 逐字节 XOR；响应是
 * 「文本头 `tp=content` + CRLFCRLF + zlib 二进制」的混合体。
 */
object LrcxDes {

    private const val XOR_KEY = "yeelion"

    /** 密钥 "yeelion" 逐字节 XOR（请求参数与 LRCX 正文共用） */
    fun xor(data: ByteArray): ByteArray {
        val key = XOR_KEY.toByteArray(Charsets.UTF_8)
        val out = ByteArray(data.size)
        for (i in data.indices) out[i] = (data[i].toInt() xor key[i % key.size].toInt()).toByte()
        return out
    }

    /** 请求参数：XOR 后 base64 */
    fun xorBase64(s: String): String =
        Base64.encodeToString(xor(s.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)

    /** 解响应：校验 `tp=content` 头 → 取 CRLFCRLF 之后的 zlib → inflate；LRCX 再 base64+XOR+GB18030 */
    fun decryptResponse(raw: ByteArray, isLrcx: Boolean): String? {
        val head = "tp=content"
        if (raw.size < head.length + 4) return null
        if (String(raw, 0, head.length, Charsets.UTF_8) != head) return null
        var sep = -1
        for (i in 0 until raw.size - 3) {
            if (raw[i] == 13.toByte() && raw[i + 1] == 10.toByte() &&
                raw[i + 2] == 13.toByte() && raw[i + 3] == 10.toByte()
            ) {
                sep = i + 4
                break
            }
        }
        if (sep < 0) return null
        val inflater = Inflater()
        inflater.setInput(raw, sep, raw.size - sep)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
        } catch (_: Throwable) {
            return null
        } finally {
            inflater.end()
        }
        val inflated = out.toByteArray()
        val bytes = if (isLrcx) {
            xor(Base64.decode(String(inflated, Charsets.US_ASCII).trim(), Base64.DEFAULT))
        } else {
            inflated
        }
        return String(bytes, Charset.forName("GB18030"))
    }
}
