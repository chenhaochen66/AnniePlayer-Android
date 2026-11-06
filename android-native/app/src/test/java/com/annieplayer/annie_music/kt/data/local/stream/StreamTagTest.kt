package com.annieplayer.annie_music.kt.data.local.stream

import com.annieplayer.annie_music.kt.data.local.tag.AnnieTag
import com.annieplayer.annie_music.kt.data.local.tag.CoverBytes
import com.annieplayer.annie_music.kt.data.local.tag.OggCodec
import com.annieplayer.annie_music.kt.data.local.tag.TagBag
import com.annieplayer.annie_music.kt.data.local.tag.TagKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `StreamTag` 的字节级单测（纯 JVM）。
 *
 * 测的是**下载链路真正会发生的事**：给一段头部 + 一条剩余的字节流，看它写出来的文件
 * 能不能被独立实现（ffmpeg 产出的夹具 → 我们读回来）认出标签，同时**音频字节有没有被搬动**。
 *
 * 每个用例都把结果留在 `build/annietag-verify/stream-*`，可以再用 PC 上的 ffprobe 反查：
 * ```
 * ffprobe -v error -show_entries format_tags:stream_tags -of default=noprint_wrappers=1 build\annietag-verify\stream-fix.mp3
 * ```
 */
class StreamTagTest {

    private val bag = TagBag(
        text = mapOf(
            TagKey.Title to "Stream Title",
            TagKey.Artist to "Stream Artist",
            TagKey.Album to "Stream Album",
            TagKey.Track to "3/12",
            TagKey.Copyright to "(C) 2026 Stream",
            TagKey.Lyric to "[00:01.00]stream lyric line",
        ),
    )

    private class Streamed(val bytes: ByteArray, val formatId: String)

    /**
     * 模拟「边收边写」：只把前 [split] 字节当头部，其余走流。
     *
     * 默认切在离文件尾 1000 字节处 —— 这样头部解析拿到的是**完整结构**（真实下载给的是
     * 256KB），同时音频尾巴确实经过 `SequenceInputStream` 拼回来，把重组逻辑也一起测了。
     *
     * @return null 表示这个格式主动放弃（调用方该走落盘路径）
     */
    private fun stream(src: ByteArray, split: Int = src.size - 1000, tags: TagBag = bag): Streamed? {
        val headSize = minOf(maxOf(split, 0), src.size)
        val head = src.copyOf(headSize)
        val rest = ByteArrayInputStream(src, headSize, src.size - headSize)
        val out = ByteArrayOutputStream()
        val outcome = StreamTag.write(head, rest, src.size.toLong(), tags, out) ?: return null
        val bytes = out.toByteArray()
        // 真实落点（文件 / MediaStore fd）可以随机写，这里在内存里模拟回填
        for ((offset, patch) in outcome.patches) patch.copyInto(bytes, offset.toInt())
        return Streamed(bytes, outcome.formatId)
    }

    private fun streamedOrFail(label: String, src: ByteArray, split: Int = src.size - 1000, tags: TagBag = bag): Streamed {
        val value = stream(src, split, tags)
        assertNotNull("$label 应当支持流式注入", value)
        return value!!
    }

    private fun fixtureBytes(name: String): ByteArray {
        val stream = javaClass.getResourceAsStream("/fixtures/$name")
        assertNotNull("缺少夹具 $name", stream)
        return stream!!.use { it.readBytes() }
    }

    /** 写进 `build/annietag-verify/` 后交给 `AnnieTag` 读 —— 「另一个实现认不认」的第一步。 */
    private fun publish(name: String, bytes: ByteArray): File {
        val dir = File("build/annietag-verify").apply { mkdirs() }
        val file = File(dir, name)
        file.writeBytes(bytes)
        return file
    }

    private fun assertTags(file: File, label: String) {
        val read = AnnieTag.read(file)
        assertNotNull("$label: 读不出标签", read)
        assertEquals("$label: 标题", "Stream Title", read!![TagKey.Title])
        assertEquals("$label: 艺术家", "Stream Artist", read[TagKey.Artist])
        assertEquals("$label: 专辑", "Stream Album", read[TagKey.Album])
        assertEquals("$label: 版权", "(C) 2026 Stream", read[TagKey.Copyright])
        assertEquals("$label: 音轨号", "3/12", read[TagKey.Track])
        assertTrue("$label: 歌词: ${read[TagKey.Lyric]}", read[TagKey.Lyric]?.contains("stream lyric") == true)
    }

    /** 源文件末尾 [n] 字节必须原封不动出现在产出末尾（音频尾巴被搬动/截断会立刻红）。 */
    private fun assertTailKept(src: ByteArray, out: ByteArray, n: Int, label: String) {
        val size = minOf(n, src.size, out.size)
        assertArrayEquals(
            "$label: 音频尾部被改了",
            src.copyOfRange(src.size - size, src.size),
            out.copyOfRange(out.size - size, out.size),
        )
    }

    // ── 头部注入：mp3 / flac ──

    @Test
    fun mp3_injects_id3_and_keeps_frames() {
        val src = fixtureBytes("fix.mp3")
        val streamed = streamedOrFail("mp3", src)
        assertEquals("mp3", streamed.formatId)
        assertEquals("产出应当以我们的 ID3 开头", "ID3", String(streamed.bytes, 0, 3, Charsets.ISO_8859_1))
        assertTailKept(src, streamed.bytes, 4096, "mp3")
        assertTags(publish("stream-fix.mp3", streamed.bytes), "mp3")
    }

    @Test
    fun flac_rebuilds_chain_and_keeps_frames() {
        val src = fixtureBytes("fix.flac")
        val streamed = streamedOrFail("flac", src)
        assertEquals("flac", streamed.formatId)
        // STREAMINFO 必须原样搬过来（里面有整段音频的 MD5，重建就错）
        assertArrayEquals(
            "STREAMINFO 被改了",
            src.copyOfRange(4, 4 + 4 + 34),
            streamed.bytes.copyOfRange(4, 4 + 4 + 34),
        )
        assertTailKept(src, streamed.bytes, 4096, "flac")
        assertTags(publish("stream-fix.flac", streamed.bytes), "flac")
    }

    // ── 头部页群重建：ogg / opus（页头重编号，但负载必须原样） ──

    @Test
    fun ogg_rebuilds_header_pages() {
        val src = fixtureBytes("fix.ogg")
        val streamed = streamedOrFail("ogg", src)
        assertEquals("ogg", streamed.formatId)
        assertSameAudioPayloads(src, streamed.bytes, "ogg")
        assertEquals(".ogg", StreamTag.extensionOf(src))
        assertTags(publish("stream-fix.ogg", streamed.bytes), "ogg")
    }

    @Test
    fun opus_replaces_tags_packet() {
        val src = fixtureBytes("fix.opus")
        val streamed = streamedOrFail("opus", src)
        assertEquals("ogg", streamed.formatId)
        assertSameAudioPayloads(src, streamed.bytes, "opus")
        assertEquals(".opus", StreamTag.extensionOf(src))
        val read = AnnieTag.read(publish("stream-fix.opus", streamed.bytes))
        assertNotNull("opus 读不出标签", read)
        assertEquals("Stream Title", read!![TagKey.Title])
    }

    // ── 尾部追加 + 回填：wav / aiff ──

    @Test
    fun wav_drops_legacy_info_and_appends_id3() {
        val src = fixtureBytes("fix.wav")
        val streamed = streamedOrFail("wav", src)
        assertEquals("riff", streamed.formatId)

        // ffmpeg 把 LIST/INFO 写在 data 前面：不筛掉就会出现两个 title
        assertNull("旧 LIST/INFO 块没被丢掉", chunkPayload(streamed.bytes, "LIST"))
        // 音频块逐字节原样
        val srcData = chunkPayload(src, "data")
        val outData = chunkPayload(streamed.bytes, "data")
        assertNotNull("夹具应当有 data 块", srcData)
        assertNotNull("产出丢了 data 块", outData)
        assertArrayEquals("wav 音频被搬动了", srcData!!, outData!!)
        assertEquals("RIFF 长度字段没回填", streamed.bytes.size - 8, u32le(streamed.bytes, 4))
        assertTags(publish("stream-fix.wav", streamed.bytes), "wav")
    }

    @Test
    fun aiff_drops_legacy_name_chunk_and_appends_id3() {
        val src = fixtureBytes("fix.aiff")
        val streamed = streamedOrFail("aiff", src)
        assertEquals("riff", streamed.formatId)

        // 老式 NAME/AUTH/ANNO 和我们的 ID3 抢同一个字段，必须丢掉
        assertNull("旧 NAME 块没被丢掉", chunkPayload(streamed.bytes, "NAME"))
        val srcSound = chunkPayload(src, "SSND")
        val outSound = chunkPayload(streamed.bytes, "SSND")
        assertNotNull("夹具应当有 SSND 块", srcSound)
        assertNotNull("产出丢了 SSND 块", outSound)
        assertArrayEquals("aiff 音频被搬动了", srcSound!!, outSound!!)
        assertEquals("FORM 长度字段没回填", streamed.bytes.size - 8, u32be(streamed.bytes, 4))
        assertTags(publish("stream-fix.aiff", streamed.bytes), "aiff")
    }

    // ── 尾部追加 + 指针回填：dsf / dff（合成，无需真夹具） ──

    @Test
    fun dsf_appends_id3_and_patches_pointer() {
        val audio = ByteArray(4096) { (it * 7).toByte() }
        val src = synthDsf(audio)
        val streamed = streamedOrFail("dsf", src, split = 512)
        assertEquals("dsd", streamed.formatId)

        // 头 28 字节里只有 total(12) / ptr(20) 两个字段被回填，其余不变
        assertArrayEquals("DSF 头部被改坏", src.copyOfRange(0, 12), streamed.bytes.copyOfRange(0, 12))
        assertEquals("DSF 头 chunk size 被改", 28L, u64le(streamed.bytes, 4))
        assertEquals("DSF 总长度没回填", streamed.bytes.size.toLong(), u64le(streamed.bytes, 12))
        val audioEnd = 28L + 12 + 52 + 12 + audio.size
        val ptr = u64le(streamed.bytes, 20)
        assertEquals("DSF ID3 指针没回填", audioEnd, ptr)
        assertEquals("ID3", String(streamed.bytes, ptr.toInt(), 3, Charsets.ISO_8859_1))
        // 音频区（data 载荷）原样
        assertArrayEquals("DSF 音频被搬动了", audio, streamed.bytes.copyOfRange(ptr.toInt() - audio.size, ptr.toInt()))
        assertTags(publish("stream-fix.dsf", streamed.bytes), "dsf")
    }

    @Test
    fun dff_appends_id3_chunk_and_patches_frm8_size() {
        val audio = ByteArray(4096) { (it * 3).toByte() }
        val src = synthDff(audio)
        val streamed = streamedOrFail("dff", src, split = 512)
        assertEquals("dsd", streamed.formatId)

        assertArrayEquals("DFF formType 被改", src.copyOfRange(12, 16), streamed.bytes.copyOfRange(12, 16))
        assertEquals("FRM8 长度没回填", streamed.bytes.size - 12L, u64be(streamed.bytes, 4))
        val text = String(streamed.bytes, Charsets.ISO_8859_1)
        assertTrue("追加的 ID3 chunk 缺失", text.contains("ID3 "))
        assertTags(publish("stream-fix.dff", streamed.bytes), "dff")
    }

    // ── m4a：faststart 能流式，moov 在尾部则明确放弃 ──

    @Test
    fun m4a_faststart_streams_by_freeing_old_moov() {
        val src = fixtureBytes("fix-fast.m4a")
        val moovAt = asciiIndex(src, "moov") - 4
        val mdatAt = asciiIndex(src, "mdat") - 4
        assertTrue("夹具应当是 faststart（moov 在 mdat 前）", moovAt in 0 until mdatAt)

        val streamed = streamedOrFail("faststart m4a", src)
        assertEquals("mp4", streamed.formatId)

        // 旧 moov 的类型被改写成 free，mdat 位置不动 → stco 全部依然有效
        assertEquals("旧 moov 没被作废", "free", String(streamed.bytes, moovAt + 4, 4, Charsets.ISO_8859_1))
        // 音频区（mdat 起点到源末尾）逐字节不变
        assertArrayEquals(
            "m4a 音频区被搬动了",
            src.copyOfRange(mdatAt, src.size),
            streamed.bytes.copyOfRange(mdatAt, mdatAt + (src.size - mdatAt)),
        )
        // 新 moov 追加在源末尾之后
        assertTrue("新 moov 没追加到尾部", streamed.bytes.size > src.size)
        assertEquals("新 moov 没追加到尾部", "moov", String(streamed.bytes, src.size + 4, 4, Charsets.ISO_8859_1))
        assertTags(publish("stream-fix-m4a.m4a", streamed.bytes), "m4a")
    }

    @Test
    fun m4a_with_moov_at_tail_falls_back() {
        // ffmpeg 默认把 moov 放在 mdat 之后：拿不到 moov 字节，只能回退到落盘打标
        assertNull("moov 在尾部的 m4a 应当放弃流式", stream(fixtureBytes("fix.m4a")))
    }

    // ── ape 系：登记扩展名，但明确不流式 ──

    @Test
    fun ape_family_reports_extension_but_falls_back() {
        val cases = mapOf("fix.wv" to ".wv", "fix.tta" to ".tta")
        for ((name, ext) in cases) {
            val src = fixtureBytes(name)
            assertEquals("$name 扩展名", ext, StreamTag.extensionOf(src))
            assertNull("$name 不该流式注入", stream(src))
        }
    }

    @Test
    fun extension_detection_covers_every_fixture() {
        val cases = mapOf(
            "fix.mp3" to ".mp3",
            "fix.flac" to ".flac",
            "fix.m4a" to ".m4a",
            "fix-fast.m4a" to ".m4a",
            "fix.ogg" to ".ogg",
            "fix.opus" to ".opus",
            "fix.wav" to ".wav",
            "fix.aiff" to ".aiff",
        )
        for ((name, ext) in cases) {
            assertEquals("$name 扩展名", ext, StreamTag.extensionOf(fixtureBytes(name)))
        }
    }

    @Test
    fun unknown_bytes_have_no_extension() {
        assertNull(StreamTag.extensionOf(ByteArray(64) { 0x41 }))
    }

    @Test
    fun canstream_matches_write_outcome() {
        // 分流判断必须和真正写的时候一致，否则会先建出一个 MediaStore 条目再回滚
        for (name in listOf("fix.mp3", "fix.flac", "fix.ogg", "fix.m4a", "fix.wv")) {
            val src = fixtureBytes(name)
            val head = src.copyOf(maxOf(src.size - 1000, 0))
            assertEquals(
                "$name: canStream 与 write 结论不一致",
                StreamTag.canStream(head, src.size.toLong(), bag),
                stream(src) != null,
            )
        }
    }

    // ── 封面：flac 的 PICTURE 是**大端**，这里顺带守住 ──

    @Test
    fun flac_stream_writes_cover_picture() {
        val cover = CoverBytes(ByteArray(64) { (it + 1).toByte() }, "image/jpeg")
        val withCover = TagBag(bag.text, cover)
        val src = fixtureBytes("fix.flac")
        val streamed = streamedOrFail("flac+cover", src, tags = withCover)
        val read = AnnieTag.read(publish("stream-fix-cover.flac", streamed.bytes))
        assertNotNull(read)
        assertNotNull("封面没写进去", read!!.cover)
        assertArrayEquals("封面字节被改了", cover.bytes, read.cover!!.bytes)
    }

    // ── 工具 ──

    /** 逐页比页内负载：页头（序号/CRC）会变，负载一个字节都不许变。 */
    private fun assertSameAudioPayloads(src: ByteArray, out: ByteArray, label: String) {
        val srcHead = OggCodec.headerPackets(src)
        val outHead = OggCodec.headerPackets(out)
        assertNotNull("$label: 源头部页群解析失败", srcHead)
        assertNotNull("$label: 产出头部页群解析失败", outHead)
        val srcPages = pagePayloads(src, srcHead!!.end)
        val outPages = pagePayloads(out, outHead!!.end)
        assertEquals("$label: 音频页数量变了", srcPages.size, outPages.size)
        for (i in srcPages.indices) {
            assertArrayEquals("$label: 第 $i 页负载被改了", srcPages[i], outPages[i])
        }
    }

    private fun pagePayloads(buf: ByteArray, from: Int): List<ByteArray> {
        val pages = ArrayList<ByteArray>()
        var off = from
        while (off + OggCodec.HEADER_SIZE <= buf.size) {
            val page = OggCodec.readPage(buf, off) ?: break
            val size = page.segments.sum()
            pages.add(buf.copyOfRange(page.payloadOffset, page.payloadOffset + size))
            off += page.totalSize
        }
        return pages
    }

    private fun asciiIndex(buf: ByteArray, needle: String): Int {
        val target = needle.toByteArray(Charsets.ISO_8859_1)
        outer@ for (i in 0..buf.size - target.size) {
            for (j in target.indices) if (buf[i + j] != target[j]) continue@outer
            return i
        }
        return -1
    }

    private fun u32le(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or
            ((buf[off + 1].toInt() and 0xFF) shl 8) or
            ((buf[off + 2].toInt() and 0xFF) shl 16) or
            ((buf[off + 3].toInt() and 0xFF) shl 24)

    private fun u32be(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 24) or
            ((buf[off + 1].toInt() and 0xFF) shl 16) or
            ((buf[off + 2].toInt() and 0xFF) shl 8) or
            (buf[off + 3].toInt() and 0xFF)

    private fun u64le(buf: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (buf[off + i].toLong() and 0xFF)
        return v
    }

    private fun u64be(buf: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (buf[off + i].toLong() and 0xFF)
        return v
    }

    /** 走一遍 RIFF/FORM chunk 链，取某个块（不含头和补齐字节）的载荷。 */
    private fun chunkPayload(buf: ByteArray, wanted: String): ByteArray? {
        val riff = String(buf, 0, 4, Charsets.ISO_8859_1) == "RIFF"
        var pos = 12
        while (pos + 8 <= buf.size) {
            val id = String(buf, pos, 4, Charsets.ISO_8859_1)
            val size = if (riff) u32le(buf, pos + 4) else u32be(buf, pos + 4)
            if (size < 0 || pos + 8 + size > buf.size) return null
            if (id == wanted) return buf.copyOfRange(pos + 8, pos + 8 + size)
            pos += 8 + size + (size % 2)
        }
        return null
    }

    /** 最小合法 DSF：DSD(28) + fmt(12+52) + data(12+N)。 */
    private fun synthDsf(audio: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("DSD ".toByteArray(Charsets.ISO_8859_1))
        out.write(u64leBytes(28))
        out.write(u64leBytes(28L + 12 + 52 + 12 + audio.size)) // total（暂无标签）
        out.write(u64leBytes(0)) // ID3 指针：没有
        out.write("fmt ".toByteArray(Charsets.ISO_8859_1))
        out.write(u64leBytes(52))
        out.write(ByteArray(52) { 1 }) // 载荷内容不参与解析
        out.write("data".toByteArray(Charsets.ISO_8859_1))
        out.write(u64leBytes(audio.size.toLong()))
        out.write(audio)
        return out.toByteArray()
    }

    /** 最小合法 DSDIFF：FRM8(16) + FVER + PROP + DSD。 */
    private fun synthDff(audio: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val fver = "FVER".toByteArray(Charsets.ISO_8859_1) + u64beBytes(4) + ByteArray(4)
        val propPayload = ByteArray(30) { 2 }
        val prop = "PROP".toByteArray(Charsets.ISO_8859_1) + u64beBytes(propPayload.size.toLong()) + propPayload
        val dsd = "DSD ".toByteArray(Charsets.ISO_8859_1) + u64beBytes(audio.size.toLong()) + audio
        val body = fver + prop + dsd
        out.write("FRM8".toByteArray(Charsets.ISO_8859_1))
        out.write(u64beBytes((body.size + 4).toLong())) // FRM8 长度 = 全文件 - 12
        out.write("DSD ".toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        return out.toByteArray()
    }

    private fun u64leBytes(v: Long): ByteArray = ByteArray(8) { i -> ((v ushr (8 * i)) and 0xFF).toByte() }

    private fun u64beBytes(v: Long): ByteArray = ByteArray(8) { i -> ((v ushr (8 * (7 - i))) and 0xFF).toByte() }

    // ============================================================================================
    // 落盘校验（L3）：`extensionOf` = null 表示「这不是音频，必须判失败」
    // ============================================================================================
    //
    // 下载器拿到的内容**不一定**是音频：酷我对不存在的档位回 `format:"mgg"` 的加密包，
    // QQ 的 `lossless` 对没有无损的歌跨源回酷我 `.mflac`。这两类都会走到
    // `_sniffFileExt`，而它的返回值就是"能不能落盘"的判据 —— 所以这里既要有
    // 「正常容器认得出」的正例，也要有「加密包 / 错误页认不出」的反例。

    @Test
    fun extension_of_recognizes_every_container_our_platforms_return() {
        assertEquals(".mp3", StreamTag.extensionOf("ID3\u0004\u0000\u0000".toByteArray(Charsets.ISO_8859_1)))
        assertEquals(".flac", StreamTag.extensionOf("fLaC".toByteArray(Charsets.ISO_8859_1)))
        assertEquals(".ogg", StreamTag.extensionOf("OggS".toByteArray(Charsets.ISO_8859_1)))
        // MP4/M4A：ftyp 在 offset 4
        val m4a = byteArrayOf(0, 0, 0, 0x20) + "ftypM4A ".toByteArray(Charsets.ISO_8859_1)
        assertEquals(".m4a", StreamTag.extensionOf(m4a))
    }

    @Test
    fun extension_of_recognizes_bare_mpeg_frames_without_id3() {
        // 很多 CDN 的 mp3 直接是 MPEG 帧（0xFF 0xFB = MPEG1 Layer3 128kbps），没有 ID3v2。
        // Mp3Stream 的帧同步判定就是为这一类准备的 —— 判不出来会把正常 mp3 全判死。
        assertEquals(".mp3", StreamTag.extensionOf(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)))
    }

    @Test
    fun extension_of_rejects_kuwo_encrypted_package() {
        // 实测酷我 `.mflac` 头 64 字节：ASCII "Q-V-R45iixBY4R" 后面开始乱码。
        // 这不是任何容器的 magic —— 必须返回 null，让下载器判失败而不是命名成 .flac。
        val mflac = (
            "Q-V-R45iixBY4R" +
                "\u009eM\u00e732L\u00ab\u0007\u00a28\u00b7v\u0016GT\u00f6\u0092\u00b2+" +
                "`\u00fc\u00cc\u00c3\u00c2L\u0089\u0003J\u00d9o25=mVf4pB6qrxA8W3sl 5"
            ).toByteArray(Charsets.ISO_8859_1)
        assertNull(StreamTag.extensionOf(mflac))
    }

    @Test
    fun extension_of_rejects_non_audio_payloads() {
        // HTML 错误页 / 空响应 / 纯文本：都必须是 null（判失败），不能套扩展名
        assertNull(StreamTag.extensionOf("<html><body>403</body></html>".toByteArray()))
        assertNull(StreamTag.extensionOf(ByteArray(0)))
        assertNull(StreamTag.extensionOf("{\"code\":400}".toByteArray()))
    }
}
