package com.annieplayer.annie_music.kt.data.sources.kkbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地播放代理的 Range 语义。
 *
 * 这些断言对的是**播放器**而不是我们自己：只要请求里带 Range 就必须 206 +
 * Content-Range，否则 Media3 认为源不支持 seek（真机表现 = 一拖进度条就不播）。
 */
class KkboxRangeTest {

    private val total = 1_000_000L

    @Test
    fun `无 Range 头给 200 与全长`() {
        val spec = parseKkboxRangeHeader(null, total)
        assertFalse(spec.hasRange)
        assertTrue(spec.satisfiable)
        val head = kkboxPlayHead(spec, total)
        assertEquals(200, head.code)
        assertEquals("", head.contentRange)
        assertEquals(total, head.length)
    }

    @Test
    fun `bytes=0- 也给 206`() {
        val head = kkboxPlayHead(parseKkboxRangeHeader("bytes=0-", total), total)
        assertEquals(206, head.code)
        assertEquals("bytes 0-999999/1000000", head.contentRange)
        assertEquals(total, head.length)
    }

    @Test
    fun `开区间从现在到结尾`() {
        val head = kkboxPlayHead(parseKkboxRangeHeader("bytes=400000-", total), total)
        assertEquals(206, head.code)
        assertEquals("bytes 400000-999999/1000000", head.contentRange)
        assertEquals(600_000L, head.length)
    }

    @Test
    fun `闭区间按请求截断`() {
        val head = kkboxPlayHead(parseKkboxRangeHeader("bytes=0-65535", total), total)
        assertEquals(206, head.code)
        assertEquals("bytes 0-65535/1000000", head.contentRange)
        assertEquals(65_536L, head.length)
    }

    @Test
    fun `闭区间超出文件尾时夹到末字节`() {
        val head = kkboxPlayHead(parseKkboxRangeHeader("bytes=999000-5000000", total), total)
        assertEquals(206, head.code)
        assertEquals("bytes 999000-999999/1000000", head.contentRange)
        assertEquals(1_000L, head.length)
    }

    @Test
    fun `后缀式取最后 N 字节`() {
        val spec = parseKkboxRangeHeader("bytes=-1024", total)
        assertTrue(spec.hasRange)
        assertEquals(998_976L, spec.start)
        assertEquals(999_999L, spec.end)
        assertEquals(1_024L, kkboxPlayHead(spec, total).length)
    }

    @Test
    fun `起点越界给 416`() {
        val spec = parseKkboxRangeHeader("bytes=1000000-", total)
        assertFalse(spec.satisfiable)
        val head = kkboxPlayHead(spec, total)
        assertEquals(416, head.code)
        assertEquals("bytes */1000000", head.contentRange)
        assertEquals(0L, head.length)
    }

    @Test
    fun `总长未知时任何 Range 都给 416`() {
        val head = kkboxPlayHead(parseKkboxRangeHeader("bytes=0-", 0L), 0L)
        assertEquals(416, head.code)
    }

    @Test
    fun `无法解析的 Range 退回整段`() {
        val spec = parseKkboxRangeHeader("bytes=abc", total)
        assertFalse(spec.hasRange)
        assertEquals(200, kkboxPlayHead(spec, total).code)
    }

    @Test
    fun `末端区间长度恒等于 end-start+1`() {
        for (h in listOf("bytes=0-", "bytes=1-2", "bytes=500000-", "bytes=-1", "bytes=999999-999999")) {
            val spec = parseKkboxRangeHeader(h, total)
            val head = kkboxPlayHead(spec, total)
            assertEquals("头 $h", spec.end - spec.start + 1, head.length)
        }
    }
}
