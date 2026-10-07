package com.annieplayer.annie_music.kt.core.util

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * QQ 云 QRC 解密（逐位照搬 LDDC / QQMusicDecoder 的自定义 DES 实现）。
 *
 * 与标准 DES 的差别：它把 64 位块按「每个 32 位字内字节反序」的方式编号
 * （见 [bitnum] 的索引），所以不能用 JCE 的 DESede 直接解，必须用这份实现。
 *
 * 数据来源：`GetPlayLyricInfo`（qrc=1）的 `lyric` 字段是**十六进制**字符串
 * （不是 base64 —— hex 字符恰好也是合法 base64 字符，用 base64 解会静默得到垃圾）。
 * 解密后是 zlib，解压得到 XML，歌词正文在 `<Lyric_1 LyricContent="…"/>` 里。
 */
object QrcDes {

    private const val ENCRYPT = 1
    private const val DECRYPT = 0

    private val QRC_KEY = "!@#)(*$%123ZXC!@!@#)(NHL".toByteArray(Charsets.UTF_8)

    private val SBOX: Array<IntArray> = arrayOf(
        intArrayOf(
            14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7,
            0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
            4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0,
            15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13,
        ),
        intArrayOf(
            15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10,
            3, 13, 4, 7, 15, 2, 8, 15, 12, 0, 1, 10, 6, 9, 11, 5,
            0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15,
            13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9,
        ),
        intArrayOf(
            10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8,
            13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
            13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7,
            1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12,
        ),
        intArrayOf(
            7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15,
            13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
            10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4,
            3, 15, 0, 6, 10, 10, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14,
        ),
        intArrayOf(
            2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9,
            14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
            4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14,
            11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3,
        ),
        intArrayOf(
            12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11,
            10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
            9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6,
            4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13,
        ),
        intArrayOf(
            4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1,
            13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
            1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2,
            6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12,
        ),
        intArrayOf(
            13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7,
            1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
            7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8,
            2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11,
        ),
    )

    private val KEY_RND_SHIFT = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)
    private val KEY_PERM_C = intArrayOf(
        56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17, 9, 1,
        58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35,
    )
    private val KEY_PERM_D = intArrayOf(
        62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21, 13, 5,
        60, 52, 44, 36, 28, 20, 12, 4, 27, 19, 11, 3,
    )
    private val KEY_COMPRESSION = intArrayOf(
        13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3,
        25, 7, 15, 6, 26, 19, 12, 1, 40, 51, 30, 36,
        46, 54, 29, 39, 50, 44, 32, 47, 43, 48, 38, 55,
        33, 52, 45, 41, 49, 35, 28, 31,
    )

    private fun bitnum(a: ByteArray, b: Int, c: Int): Int {
        val idx = (b / 32) * 4 + 3 - (b % 32) / 8
        val byteVal = a[idx].toInt() and 0xFF
        return ((byteVal shr (7 - b % 8)) and 1) shl c
    }

    private fun bitnumIntr(a: Int, b: Int, c: Int): Int = ((a ushr (31 - b)) and 1) shl c

    private fun bitnumIntl(a: Int, b: Int, c: Int): Int =
        (((a shl b) and 0x80000000.toInt()) ushr c)

    private fun sboxBit(a: Int): Int = (a and 32) or ((a and 31) shr 1) or ((a and 1) shl 4)

    private fun initialPermutation(input: ByteArray): IntArray = intArrayOf(
        bitnum(input, 57, 31) or bitnum(input, 49, 30) or bitnum(input, 41, 29) or bitnum(input, 33, 28) or
            bitnum(input, 25, 27) or bitnum(input, 17, 26) or bitnum(input, 9, 25) or bitnum(input, 1, 24) or
            bitnum(input, 59, 23) or bitnum(input, 51, 22) or bitnum(input, 43, 21) or bitnum(input, 35, 20) or
            bitnum(input, 27, 19) or bitnum(input, 19, 18) or bitnum(input, 11, 17) or bitnum(input, 3, 16) or
            bitnum(input, 61, 15) or bitnum(input, 53, 14) or bitnum(input, 45, 13) or bitnum(input, 37, 12) or
            bitnum(input, 29, 11) or bitnum(input, 21, 10) or bitnum(input, 13, 9) or bitnum(input, 5, 8) or
            bitnum(input, 63, 7) or bitnum(input, 55, 6) or bitnum(input, 47, 5) or bitnum(input, 39, 4) or
            bitnum(input, 31, 3) or bitnum(input, 23, 2) or bitnum(input, 15, 1) or bitnum(input, 7, 0),
        bitnum(input, 56, 31) or bitnum(input, 48, 30) or bitnum(input, 40, 29) or bitnum(input, 32, 28) or
            bitnum(input, 24, 27) or bitnum(input, 16, 26) or bitnum(input, 8, 25) or bitnum(input, 0, 24) or
            bitnum(input, 58, 23) or bitnum(input, 50, 22) or bitnum(input, 42, 21) or bitnum(input, 34, 20) or
            bitnum(input, 26, 19) or bitnum(input, 18, 18) or bitnum(input, 10, 17) or bitnum(input, 2, 16) or
            bitnum(input, 60, 15) or bitnum(input, 52, 14) or bitnum(input, 44, 13) or bitnum(input, 36, 12) or
            bitnum(input, 28, 11) or bitnum(input, 20, 10) or bitnum(input, 12, 9) or bitnum(input, 4, 8) or
            bitnum(input, 62, 7) or bitnum(input, 54, 6) or bitnum(input, 46, 5) or bitnum(input, 38, 4) or
            bitnum(input, 30, 3) or bitnum(input, 22, 2) or bitnum(input, 14, 1) or bitnum(input, 6, 0),
    )

    private fun inversePermutation(s0: Int, s1: Int): ByteArray {
        val data = ByteArray(8)
        data[3] = (bitnumIntr(s1, 7, 7) or bitnumIntr(s0, 7, 6) or bitnumIntr(s1, 15, 5) or bitnumIntr(s0, 15, 4) or
            bitnumIntr(s1, 23, 3) or bitnumIntr(s0, 23, 2) or bitnumIntr(s1, 31, 1) or bitnumIntr(s0, 31, 0)).toByte()
        data[2] = (bitnumIntr(s1, 6, 7) or bitnumIntr(s0, 6, 6) or bitnumIntr(s1, 14, 5) or bitnumIntr(s0, 14, 4) or
            bitnumIntr(s1, 22, 3) or bitnumIntr(s0, 22, 2) or bitnumIntr(s1, 30, 1) or bitnumIntr(s0, 30, 0)).toByte()
        data[1] = (bitnumIntr(s1, 5, 7) or bitnumIntr(s0, 5, 6) or bitnumIntr(s1, 13, 5) or bitnumIntr(s0, 13, 4) or
            bitnumIntr(s1, 21, 3) or bitnumIntr(s0, 21, 2) or bitnumIntr(s1, 29, 1) or bitnumIntr(s0, 29, 0)).toByte()
        data[0] = (bitnumIntr(s1, 4, 7) or bitnumIntr(s0, 4, 6) or bitnumIntr(s1, 12, 5) or bitnumIntr(s0, 12, 4) or
            bitnumIntr(s1, 20, 3) or bitnumIntr(s0, 20, 2) or bitnumIntr(s1, 28, 1) or bitnumIntr(s0, 28, 0)).toByte()
        data[7] = (bitnumIntr(s1, 3, 7) or bitnumIntr(s0, 3, 6) or bitnumIntr(s1, 11, 5) or bitnumIntr(s0, 11, 4) or
            bitnumIntr(s1, 19, 3) or bitnumIntr(s0, 19, 2) or bitnumIntr(s1, 27, 1) or bitnumIntr(s0, 27, 0)).toByte()
        data[6] = (bitnumIntr(s1, 2, 7) or bitnumIntr(s0, 2, 6) or bitnumIntr(s1, 10, 5) or bitnumIntr(s0, 10, 4) or
            bitnumIntr(s1, 18, 3) or bitnumIntr(s0, 18, 2) or bitnumIntr(s1, 26, 1) or bitnumIntr(s0, 26, 0)).toByte()
        data[5] = (bitnumIntr(s1, 1, 7) or bitnumIntr(s0, 1, 6) or bitnumIntr(s1, 9, 5) or bitnumIntr(s0, 9, 4) or
            bitnumIntr(s1, 17, 3) or bitnumIntr(s0, 17, 2) or bitnumIntr(s1, 25, 1) or bitnumIntr(s0, 25, 0)).toByte()
        data[4] = (bitnumIntr(s1, 0, 7) or bitnumIntr(s0, 0, 6) or bitnumIntr(s1, 8, 5) or bitnumIntr(s0, 8, 4) or
            bitnumIntr(s1, 16, 3) or bitnumIntr(s0, 16, 2) or bitnumIntr(s1, 24, 1) or bitnumIntr(s0, 24, 0)).toByte()
        return data
    }

    private fun f(state: Int, key: IntArray): Int {
        val t1 = bitnumIntl(state, 31, 0) or ((state and 0xf0000000.toInt()) ushr 1) or bitnumIntl(state, 4, 5) or
            bitnumIntl(state, 3, 6) or ((state and 0x0f000000) ushr 3) or bitnumIntl(state, 8, 11) or
            bitnumIntl(state, 7, 12) or ((state and 0x00f00000) ushr 5) or bitnumIntl(state, 12, 17) or
            bitnumIntl(state, 11, 18) or ((state and 0x000f0000) ushr 7) or bitnumIntl(state, 16, 23)
        val t2 = bitnumIntl(state, 15, 0) or ((state and 0x0000f000) shl 15) or bitnumIntl(state, 20, 5) or
            bitnumIntl(state, 19, 6) or ((state and 0x00000f00) shl 13) or bitnumIntl(state, 24, 11) or
            bitnumIntl(state, 23, 12) or ((state and 0x000000f0) shl 11) or bitnumIntl(state, 28, 17) or
            bitnumIntl(state, 27, 18) or ((state and 0x0000000f) shl 9) or bitnumIntl(state, 0, 23)
        val lrg = intArrayOf(
            (t1 ushr 24) and 0xff, (t1 ushr 16) and 0xff, (t1 ushr 8) and 0xff,
            (t2 ushr 24) and 0xff, (t2 ushr 16) and 0xff, (t2 ushr 8) and 0xff,
        )
        for (i in 0..5) lrg[i] = lrg[i] xor key[i]
        val s = (SBOX[0][sboxBit(lrg[0] shr 2)] shl 28) or
            (SBOX[1][sboxBit(((lrg[0] and 0x03) shl 4) or (lrg[1] shr 4))] shl 24) or
            (SBOX[2][sboxBit(((lrg[1] and 0x0f) shl 2) or (lrg[2] shr 6))] shl 20) or
            (SBOX[3][sboxBit(lrg[2] and 0x3f)] shl 16) or
            (SBOX[4][sboxBit(lrg[3] shr 2)] shl 12) or
            (SBOX[5][sboxBit(((lrg[3] and 0x03) shl 4) or (lrg[4] shr 4))] shl 8) or
            (SBOX[6][sboxBit(((lrg[4] and 0x0f) shl 2) or (lrg[5] shr 6))] shl 4) or
            SBOX[7][sboxBit(lrg[5] and 0x3f)]
        return bitnumIntl(s, 15, 0) or bitnumIntl(s, 6, 1) or bitnumIntl(s, 19, 2) or
            bitnumIntl(s, 20, 3) or bitnumIntl(s, 28, 4) or bitnumIntl(s, 11, 5) or
            bitnumIntl(s, 27, 6) or bitnumIntl(s, 16, 7) or bitnumIntl(s, 0, 8) or
            bitnumIntl(s, 14, 9) or bitnumIntl(s, 22, 10) or bitnumIntl(s, 25, 11) or
            bitnumIntl(s, 4, 12) or bitnumIntl(s, 17, 13) or bitnumIntl(s, 30, 14) or
            bitnumIntl(s, 9, 15) or bitnumIntl(s, 1, 16) or bitnumIntl(s, 7, 17) or
            bitnumIntl(s, 23, 18) or bitnumIntl(s, 13, 19) or bitnumIntl(s, 31, 20) or
            bitnumIntl(s, 26, 21) or bitnumIntl(s, 2, 22) or bitnumIntl(s, 8, 23) or
            bitnumIntl(s, 18, 24) or bitnumIntl(s, 12, 25) or bitnumIntl(s, 29, 26) or
            bitnumIntl(s, 5, 27) or bitnumIntl(s, 21, 28) or bitnumIntl(s, 10, 29) or
            bitnumIntl(s, 3, 30) or bitnumIntl(s, 24, 31)
    }

    private fun crypt(input: ByteArray, key: Array<IntArray>): ByteArray {
        val perm = initialPermutation(input)
        var s0 = perm[0]
        var s1 = perm[1]
        for (idx in 0 until 15) {
            val prevS1 = s1
            s1 = f(s1, key[idx]) xor s0
            s0 = prevS1
        }
        s0 = f(s1, key[15]) xor s0
        return inversePermutation(s0, s1)
    }

    private fun keySchedule(key: ByteArray, mode: Int): Array<IntArray> {
        val schedule = Array(16) { IntArray(6) }
        var c = 0
        var d = 0
        for (i in 0..27) {
            c = c or bitnum(key, KEY_PERM_C[i], 31 - i)
            d = d or bitnum(key, KEY_PERM_D[i], 31 - i)
        }
        for (i in 0..15) {
            val shift = KEY_RND_SHIFT[i]
            c = ((c shl shift) or (c ushr (28 - shift))) and 0xfffffff0.toInt()
            d = ((d shl shift) or (d ushr (28 - shift))) and 0xfffffff0.toInt()
            val togen = if (mode == DECRYPT) 15 - i else i
            for (j in 0..23) schedule[togen][j / 8] = schedule[togen][j / 8] or bitnumIntr(c, KEY_COMPRESSION[j], 7 - (j % 8))
            for (j in 24..47) schedule[togen][j / 8] = schedule[togen][j / 8] or bitnumIntr(d, KEY_COMPRESSION[j] - 27, 7 - (j % 8))
        }
        return schedule
    }

    private fun tripleDesKeySetup(key: ByteArray, mode: Int): Array<Array<IntArray>> = if (mode == ENCRYPT) {
        arrayOf(keySchedule(key.copyOfRange(0, 8), ENCRYPT), keySchedule(key.copyOfRange(8, 16), DECRYPT), keySchedule(key.copyOfRange(16, 24), ENCRYPT))
    } else {
        arrayOf(keySchedule(key.copyOfRange(16, 24), DECRYPT), keySchedule(key.copyOfRange(8, 16), ENCRYPT), keySchedule(key.copyOfRange(0, 8), DECRYPT))
    }

    private fun tripleDesCrypt(data: ByteArray, key: Array<Array<IntArray>>): ByteArray {
        var out = data
        for (i in 0..2) out = crypt(out, key[i])
        return out
    }

    /** 十六进制串 -> 字节；非法返回空数组 */
    fun hexToBytes(s: String): ByteArray {
        if (s.isEmpty() || s.length % 2 != 0) return ByteArray(0)
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(s[i * 2], 16)
            val lo = Character.digit(s[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return ByteArray(0)
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /**
     * 解密 QQ 云 QRC 的 `lyric`（十六进制）为 QRC 正文（XML 里的 `LyricContent`）。
     * 失败返回 null。
     */
    fun decryptToQrc(encryptedHex: String): String? = try {
        val encrypted = hexToBytes(encryptedHex.trim())
        if (encrypted.isEmpty()) {
            null
        } else {
            val schedule = tripleDesKeySetup(QRC_KEY, DECRYPT)
            val out = ByteArrayOutputStream()
            var i = 0
            while (i < encrypted.size) {
                // 逐 8 字节（末块可能不足 8，照参考实现直接交给 crypt）
                val end = if (i + 8 <= encrypted.size) i + 8 else encrypted.size
                val block = encrypted.copyOfRange(i, end)
                out.write(tripleDesCrypt(block, schedule))
                i += 8
            }
            val inflated = inflate(out.toByteArray()) ?: return null
            val xml = String(inflated, Charsets.UTF_8)
            _extractLyricContent(xml)
        }
    } catch (_: Throwable) {
        null
    }

    private fun inflate(data: ByteArray): ByteArray? = try {
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
        out.toByteArray()
    } catch (_: Throwable) {
        null
    }

    /** 从 QRC 的 XML 里取 `<Lyric_1 LyricContent="…"/>` 的正文（无 XML 时按原样返回） */
    private fun _extractLyricContent(text: String): String? {
        val idx = text.indexOf("LyricContent=\"")
        if (idx < 0) return text.ifEmpty { null }
        val start = idx + "LyricContent=\"".length
        val end = text.indexOf('"', start)
        if (end <= start) return text.ifEmpty { null }
        return text.substring(start, end)
    }
}
