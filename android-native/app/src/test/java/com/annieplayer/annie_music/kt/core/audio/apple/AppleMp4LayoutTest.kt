package com.annieplayer.annie_music.kt.core.audio.apple

import com.annieplayer.annie_music.kt.data.local.tag.AnnieTag
import com.annieplayer.annie_music.kt.data.local.tag.TagBag
import com.annieplayer.annie_music.kt.data.local.tag.TagKey
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Apple m4a「**moov 在文件尾**」布局的单测（纯 JVM）。
 *
 * 这套布局是「音频只搬一趟」的关键：`stco` 只依赖 `ftyp + mdat 头` 的长度，与 moov 自身大小
 * 无关，所以可以在**写第一个 sample 之前**就定下来，moov（含标签）留到最后写。
 *
 * 测的东西：
 * - `stco[0]` == `ftyp.size + 16`（`mdat` 载荷起点）；
 * - `mdat` 是 64 位长度头，`largesize` == 音频字节数 + 16；
 * - moov 落在文件末尾，且 `AnnieTag` 能从里面读出标签；
 * - duration 被回填到 mvhd/tkhd/mdhd（ffprobe 会拿它算时长）。
 *
 * mdat 里的"音频"是填充字节（不是真的 AAC 帧），所以这份产出只验证**结构**：
 * 用 `ffprobe -show_entries format=duration:format_tags` 反查即可，别去解码。
 */
class AppleMp4LayoutTest {

    private fun fixture(name: String): ByteArray {
        val stream = javaClass.getResourceAsStream("/fixtures/$name")
        assertNotNull("缺少夹具 $name", stream)
        return stream!!.use { it.readBytes() }
    }

    private fun publish(name: String, bytes: ByteArray): File {
        val dir = File("build/annietag-verify").apply { mkdirs() }
        return File(dir, name).apply { writeBytes(bytes) }
    }

    @Test
    fun trailing_moov_layout_points_stco_at_mdat_payload() {
        val src = fixture("fix-fast.m4a")
        val ftyp = topBox(src, "ftyp")
        val moovSrc = topBox(src, "moov")
        assertNotNull("夹具缺 ftyp", ftyp)
        assertNotNull("夹具缺 moov", moovSrc)
        val ftypBytes = src.copyOfRange(ftyp!!.first, ftyp.first + ftyp.second)
        val moovBytes = src.copyOfRange(moovSrc!!.first, moovSrc.first + moovSrc.second)

        val samples = 50
        val sampleSize = 100
        val sizes = List(samples) { sampleSize }
        val durations = List(samples) { 1024 }
        val chunkOffset = ftypBytes.size + APPLE_MDAT_HEADER

        val moov = buildAppleTrailingMoov(
            moov = moovBytes,
            codec = "mp4a",
            durations = durations,
            sizes = sizes,
            chunkOffset = chunkOffset,
            udta = AnnieTag.mp4UdtaBox(
                TagBag.of(
                    TagKey.Title to "Trailing Title",
                    TagKey.Artist to "Trailing Artist",
                    TagKey.Copyright to "(C) 2026 Trailing",
                ),
            ),
        )

        val out = ByteArrayOutputStream()
        out.write(buildApplePrologue(ftypBytes))
        repeat(samples) { out.write(ByteArray(sampleSize)) }
        out.write(moov)
        val bytes = out.toByteArray()
        val sampleBytes = (samples * sampleSize).toLong()
        appleMdatSizeBytes(sampleBytes).copyInto(bytes, appleMdatSizeOffset(ftypBytes.size).toInt())

        // 时长必须回填到 mvhd / mdhd（ffprobe 用它算 duration）
        val totalDuration = durations.sum().toLong()
        val mvhd = fourccIndex(moov, "mvhd")
        val mdhd = fourccIndex(moov, "mdhd")
        assertTrue("moov 里找不到 mvhd", mvhd > 0)
        assertTrue("moov 里找不到 mdhd", mdhd > 0)
        // 找到的是 "mvhd" 首字节，payload 从它 +4 开始
        val mvhdVersion = moov[mvhd + 4].toInt() and 0xFF
        assertEquals(
            "mvhd 时长没回填",
            totalDuration,
            if (mvhdVersion == 1) u64be(moov, mvhd + 4 + 20) else u32be(moov, mvhd + 4 + 16).toLong(),
        )
        val mdhdVersion = moov[mdhd + 4].toInt() and 0xFF
        assertEquals(
            "mdhd 时长没回填",
            totalDuration,
            if (mdhdVersion == 1) u64be(moov, mdhd + 4 + 28) else u32be(moov, mdhd + 4 + 16).toLong(),
        )

        // stco 的 chunk 偏移必须正好是 mdat 载荷起点
        assertEquals("stco 没指向 mdat 载荷", chunkOffset, stcoFirstOffset(moov))

        // mdat：size=1 + "mdat" + 64 位 largesize
        assertEquals("mdat 应当用 64 位长度头", 1, u32be(bytes, ftypBytes.size))
        assertEquals("mdat", String(bytes, ftypBytes.size + 4, 4, Charsets.ISO_8859_1))
        assertEquals(
            "mdat largesize 不对",
            sampleBytes + APPLE_MDAT_HEADER,
            u64be(bytes, ftypBytes.size + 8),
        )

        // moov 必须在文件末尾
        val lastMoov = topBox(bytes, "moov")
        assertNotNull("产出里找不到 moov", lastMoov)
        assertEquals("moov 不在文件末尾", bytes.size - moov.size, lastMoov!!.first)

        // 标签能被自己的读取器读出来（udta 在 moov 里，和 mdat 前后无关）
        val file = publish("apple-trailing.m4a", bytes)
        val read = AnnieTag.read(file)
        assertNotNull("moov 在尾部的 m4a 读不出标签", read)
        assertEquals("Trailing Title", read!![TagKey.Title])
        assertEquals("Trailing Artist", read[TagKey.Artist])
        assertEquals("(C) 2026 Trailing", read[TagKey.Copyright])
    }

    @Test
    fun prologue_is_ftyp_plus_64bit_mdat_header() {
        val ftyp = fixture("fix-fast.m4a")
        val box = topBox(ftyp, "ftyp")!!
        val ftypBytes = ftyp.copyOfRange(box.first, box.first + box.second)

        val prologue = buildApplePrologue(ftypBytes)
        assertEquals("前置头长度应当是 ftyp + 16", ftypBytes.size + APPLE_MDAT_HEADER, prologue.size)
        assertEquals("mdat", String(prologue, ftypBytes.size + 4, 4, Charsets.ISO_8859_1))
        // largesize 占位必须全 0，等写完再回填
        assertTrue(
            "mdat 长度占位不是 0",
            prologue.copyOfRange(ftypBytes.size + 8, prologue.size).all { it.toInt() == 0 },
        )
    }

    // ── 工具 ──

    /** 顶层 box 链里找某个类型，返回 (起点, 长度)。 */
    private fun topBox(buf: ByteArray, type: String): Pair<Int, Int>? {
        var pos = 0
        while (pos + 8 <= buf.size) {
            var size = u32be(buf, pos)
            var header = 8
            val id = String(buf, pos + 4, 4, Charsets.ISO_8859_1)
            if (size == 1) {
                if (pos + 16 > buf.size) return null
                size = u64be(buf, pos + 8).toInt()
                header = 16
            }
            if (size < header || pos + size > buf.size) return null
            if (id == type) return pos to size
            pos += size
        }
        return null
    }

    /** stco 第一条目的值：`size(4) type(4) flags(4) count(4) offset(4)`。 */
    private fun stcoFirstOffset(moov: ByteArray): Int {
        val i = fourccIndex(moov, "stco")
        if (i <= 0) throw AssertionError("moov 里找不到 stco")
        // i 指向 "stco" 的首字节 → 条目值在 i + 4(类型之后) + 4(flags) + 4(count)
        return u32be(moov, i + 12)
    }

    /** box 类型在缓冲区里的位置（返回 "xxxx" 首字节的下标）；找不到返回 -1。 */
    private fun fourccIndex(buf: ByteArray, type: String): Int {
        val needle = type.toByteArray(Charsets.ISO_8859_1)
        outer@ for (i in 4..buf.size - needle.size) {
            for (j in needle.indices) if (buf[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun u32be(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 24) or
            ((buf[off + 1].toInt() and 0xFF) shl 16) or
            ((buf[off + 2].toInt() and 0xFF) shl 8) or
            (buf[off + 3].toInt() and 0xFF)

    private fun u64be(buf: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (buf[off + i].toLong() and 0xFF)
        return v
    }
}
