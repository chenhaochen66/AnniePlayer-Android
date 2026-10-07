package com.annieplayer.annie_music.kt.core.util

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.Inflater

/** KRC 解密 XOR key（16 字节，逐字节一致） */
private val KRC_XOR_KEY = intArrayOf(
    0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
    0x51, 0x36, 0x31, 0x2d, 0xce, 0xd2, 0x6e, 0x69,
)

/**
 * 酷狗 KRC 歌词解密：base64 → 去前 4 字节 → 16 字节 XOR → zlib inflate → UTF-8。
 */
object KrcDes {

    fun decrypt(contentB64: String): String {
        val cleaned = contentB64.replace("\r", "").replace("\n", "")
        val raw = Base64.decode(cleaned, Base64.DEFAULT)
        val sliced = raw.copyOfRange(4, raw.size)
        for (i in sliced.indices) {
            sliced[i] = (sliced[i].toInt() xor KRC_XOR_KEY[i % 16]).toByte()
        }
        val decompressed = inflate(sliced)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(decompressed))
                .toString()
        } catch (_: Throwable) {
            String(decompressed, Charsets.ISO_8859_1)
        }
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        inflater.end()
        return out.toByteArray()
    }
}
