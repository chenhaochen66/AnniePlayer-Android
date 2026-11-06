package com.annieplayer.annie_music.kt.data.source

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `decodeUriComponent` —— 落雪插件桥里 `md5` 的前置解码。
 *
 * 这个函数存在的唯一理由是**修 bug**：JS shim 里 `nativeCall('md5', encodeURIComponent(str))`
 * 把串编码后过桥，原生侧原先直接拿编码后的串求 MD5。结果任何含 `|` `@` `#` `/` `&` `=`
 * 或中文的输入都会被按 `%7C` / `%40` / `%E4%B8%AD` 求摘要，插件签名全错 ——
 * 表现是「插件加载正常、一取直链就报获取失败」，而错误信息里完全看不到 md5。
 *
 * 所以这里锁两件事：
 * 1. 解码必须与 JS `encodeURIComponent` **严格互逆**（否则 md5 还是错的）；
 * 2. 不能有 `URLDecoder` 那个 `+` → 空格的语义（那会在换 `encodeURI` 时静默出错）。
 */
class LxBridgeDecodeTest {

    /** 与 `md5Hex` 完全一致的实现，用来验证「解码后再摘要」等于「对原文摘要」。 */
    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** JS `encodeURIComponent` 的等价实现（保留 `A-Za-z0-9-_.!~*'()`，其余按 UTF-8 转 %XX）。 */
    private fun encodeUriComponent(text: String): String {
        val keep = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"
        val out = StringBuilder()
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val c = (byte.toInt() and 0xFF).toChar()
            if (c in keep) out.append(c) else out.append('%').append("%02X".format(byte.toInt() and 0xFF))
        }
        return out.toString()
    }

    @Test
    fun plain_ascii_is_untouched() {
        assertEquals("1761666000000", decodeUriComponent("1761666000000"))
        assertEquals("abcXYZ_-.!~*'()", decodeUriComponent("abcXYZ_-.!~*'()"))
        assertEquals("", decodeUriComponent(""))
    }

    @Test
    fun the_real_signature_base_round_trips() {
        // 实测样本：独家音源 v7 的 s2 基串（`|` × 3、`@`、`#`）
        val base1 = "/v4s/url/kw/356308108/flac|1761666000000" +
            "|42bc92aaf1a30247e40eab9523ca0713|LxSrv@2026#Sig"
        val encoded = encodeUriComponent(base1)
        assertEquals(base1, decodeUriComponent(encoded))
        // 修复前后的分水岭：对原文摘要 ≠ 对编码串摘要
        assertEquals("46b0bfc4791da36ac48fb05338be57c3", md5(decodeUriComponent(encoded)))
        assertEquals("99bb7d50d2251964e72c0f1e2ccfdd7b", md5(encoded))
    }

    @Test
    fun percent_2b_decodes_to_plus_not_space() {
        // 这一条是与 `java.net.URLDecoder` 的分歧点：URLDecoder 会把 `%2B` 解成 `+`（碰巧对），
        // 但把裸 `+` 解成空格（错）。我们的实现只认 %XX，裸 `+` 原样保留。
        assertEquals("a+b", decodeUriComponent("a%2Bb"))
        assertEquals("a+b", decodeUriComponent("a+b"))
        assertEquals("a b", decodeUriComponent("a%20b"))
    }

    @Test
    fun multi_byte_utf8_sequences_are_joined() {
        // 中文在 encodeURIComponent 里是 3 个连续 %XX，必须攒成字节再按 UTF-8 还原
        assertEquals("中", decodeUriComponent("%E4%B8%AD"))
        assertEquals("晴天 - 周杰伦", decodeUriComponent(encodeUriComponent("晴天 - 周杰伦")))
        // 混合：中文 + 特殊字符 + ASCII
        val mixed = "kw|中|flac&v=7"
        assertEquals(mixed, decodeUriComponent(encodeUriComponent(mixed)))
    }

    @Test
    fun malformed_escapes_are_kept_verbatim() {
        // 残缺的 % 不该让整个请求失败（插件不该因为一个坏字符全挂）
        assertEquals("%ZZ", decodeUriComponent("%ZZ"))
        assertEquals("100%", decodeUriComponent("100%"))
        assertEquals("%4", decodeUriComponent("%4"))
        assertEquals("50%的折扣", decodeUriComponent("50%的折扣"))
    }
}
