package com.annieplayer.annie_music.kt.data.sources.kkbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本机代理的缓存分块边界。
 *
 * 这几个数错一位，播放器就会读到没写过的区域（静音 / 解码失败），而它不会报错，
 * 只表现为「放到某处停住」—— 所以边界单独测。
 */
class KkboxChunkTest {

    private val chunk = 256L * 1024

    @Test
    fun `块下标按整块对齐`() {
        assertEquals(0, kkboxChunkIndexOf(0, chunk))
        assertEquals(0, kkboxChunkIndexOf(chunk - 1, chunk))
        assertEquals(1, kkboxChunkIndexOf(chunk, chunk))
        assertEquals(1, kkboxChunkIndexOf(chunk + 1, chunk))
    }

    @Test
    fun `整块区间的首尾`() {
        // 恰好一块
        assertEquals(0, kkboxChunkFirstFull(0, chunk))
        assertEquals(0, kkboxChunkLastFull(chunk - 1, chunk))
        // 跨两块（到第二块末尾）
        assertEquals(0, kkboxChunkFirstFull(0, chunk))
        assertEquals(1, kkboxChunkLastFull(chunk * 2 - 1, chunk))
        // 只碰到第二块的第一个字节：第二块不算完整
        assertEquals(0, kkboxChunkLastFull(chunk, chunk))
        // 起点不在边界：自己那块不完整，不算
        assertEquals(1, kkboxChunkFirstFull(1, chunk))
        assertEquals(2, kkboxChunkFirstFull(chunk + 1, chunk))
        // 正好落在块首就是完整的
        assertEquals(1, kkboxChunkFirstFull(chunk, chunk))
    }

    @Test
    fun `不满一块的尾巴不算完整块`() {
        // 末尾那 100 字节不足以构成整块；调用方（markChunks）会按「到 EOF 即完整」另行处理
        val total = chunk * 2 + 100
        assertEquals(1, kkboxChunkLastFull(total - 1, chunk))
        assertEquals(2, kkboxChunkFirstFull(chunk * 2, chunk))
    }

    @Test
    fun `还没准备好的时候整段都算未缓存`() {
        assertEquals(999L, kkboxUncachedSpanEnd(0, 999, chunk, null))
    }

    @Test
    fun `全未缓存时一直延伸到请求末尾`() {
        val chunks = BooleanArray(4)
        assertEquals(chunk * 4 - 1, kkboxUncachedSpanEnd(0, chunk * 4 - 1, chunk, chunks))
    }

    @Test
    fun `遇到已缓存块就停在它的前一个字节`() {
        val chunks = BooleanArray(4)
        chunks[2] = true
        assertEquals(chunk * 2 - 1, kkboxUncachedSpanEnd(0, chunk * 4 - 1, chunk, chunks))
    }

    @Test
    fun `请求末尾早于已缓存块时取请求末尾`() {
        val chunks = BooleanArray(4)
        chunks[2] = true
        assertEquals(chunk - 1, kkboxUncachedSpanEnd(0, chunk - 1, chunk, chunks))
    }

    @Test
    fun `从已缓存块的中间起算不会回退到负数`() {
        val chunks = BooleanArray(4)
        chunks[1] = true
        // 防御：理论上调用方会先判 isChunkCached，这里只要求不返回 < pos
        assertTrue(kkboxUncachedSpanEnd(chunk + 10, chunk * 3, chunk, chunks) >= chunk + 9)
    }

    @Test
    fun `起点落在已缓存块内时没有可回源区间`() {
        val chunks = BooleanArray(6)
        chunks[0] = true
        chunks[1] = true
        // 调用方会先判 isChunkCached，这里只要求不返回越界的正值
        assertTrue(kkboxUncachedSpanEnd(0, chunk * 6 - 1, chunk, chunks) < 0)
        // 从块 2 起就一路到末尾
        assertEquals(chunk * 6 - 1, kkboxUncachedSpanEnd(chunk * 2, chunk * 6 - 1, chunk, chunks))
    }
}
