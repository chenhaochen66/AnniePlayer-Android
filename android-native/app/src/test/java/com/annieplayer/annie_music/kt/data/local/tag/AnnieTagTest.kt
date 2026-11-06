package com.annieplayer.annie_music.kt.data.local.tag

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `AnnieTag` 的字节级单测（纯 JVM，不需要真机）。
 *
 * 夹具是**用 ffmpeg 生成的真实文件**（`app/src/test/resources/fixtures/fix.{mp3,flac,m4a}`），
 * 所以这不只是「自己写自己读」，而是拿一个独立实现（ffmpeg）的产物来验证我们的**读**；
 * 再验证我们的**写**不会搬动音频数据。
 */
class AnnieTagTest {

    private fun fixture(name: String): File {
        val stream = javaClass.getResourceAsStream("/fixtures/$name")
        assertNotNull("缺少夹具 $name", stream)
        val dir = Files.createTempDirectory("annietag").toFile()
        val out = File(dir, name)
        stream!!.use { input -> out.outputStream().use { input.copyTo(it) } }
        out.deleteOnExit()
        return out
    }

    /**
     * 把写过的文件留一份到 `build/annietag-verify/`。
     *
     * 用途：用**另一个独立实现**（PC 上的 ffprobe）反向读我们写出来的标签，
     * 避免「自己写自己读」的自证循环。跑完 `gradlew :app:testDebugUnitTest` 后可直接 ffprobe 这些文件。
     */
    private fun publish(name: String, file: File) {
        val dir = File("build/annietag-verify").apply { mkdirs() }
        file.copyTo(File(dir, name), overwrite = true)
    }

    // ── 读：验证能解析 ffmpeg 写出来的标签 ──

    @Test
    fun mp3_reads_ffmpeg_tags() {
        val bag = AnnieTag.read(fixture("fix.mp3"))
        assertNotNull("mp3 读不出标签", bag)
        assertEquals("Fixture Title", bag!![TagKey.Title])
        assertEquals("Fixture Artist", bag[TagKey.Artist])
        assertEquals("Fixture Album", bag[TagKey.Album])
        assertEquals("Fixture AlbumArtist", bag[TagKey.AlbumArtist])
        assertEquals("3/12", bag[TagKey.Track])
        assertEquals("1/2", bag[TagKey.Disc])
        assertEquals("2024-01-02", bag[TagKey.Year])
        assertEquals("Fixture Composer", bag[TagKey.Composer])
        assertEquals("Pop", bag[TagKey.Genre])
        assertTrue("歌词没读出来: ${bag[TagKey.Lyric]}", bag[TagKey.Lyric]?.contains("line one") == true)
        assertTrue("封面没读出来", (bag.cover?.bytes?.size ?: 0) > 0)
    }

    @Test
    fun flac_reads_ffmpeg_tags() {
        val bag = AnnieTag.read(fixture("fix.flac"))
        assertNotNull("flac 读不出标签", bag)
        assertEquals("Fixture Title", bag!![TagKey.Title])
        assertEquals("Fixture Artist", bag[TagKey.Artist])
        assertEquals("Fixture Album", bag[TagKey.Album])
        assertEquals("3/12", bag[TagKey.Track])
        assertEquals("Pop", bag[TagKey.Genre])
        assertTrue("歌词没读出来: ${bag[TagKey.Lyric]}", bag[TagKey.Lyric]?.contains("line one") == true)
        assertTrue("封面没读出来", (bag.cover?.bytes?.size ?: 0) > 0)
    }

    @Test
    fun m4a_reads_ffmpeg_tags() {
        val bag = AnnieTag.read(fixture("fix.m4a"))
        assertNotNull("m4a 读不出标签", bag)
        assertEquals("测试标题", bag!![TagKey.Title])
        assertEquals("测试歌手", bag[TagKey.Artist])
        assertEquals("测试专辑", bag[TagKey.Album])
        assertEquals("专辑歌手", bag[TagKey.AlbumArtist])
        assertEquals("3/12", bag[TagKey.Track])
        assertEquals("1/2", bag[TagKey.Disc])
        assertEquals("2024-01-02", bag[TagKey.Year])
        assertEquals("作曲人", bag[TagKey.Composer])
        assertEquals("Pop", bag[TagKey.Genre])
        assertTrue("歌词没读出来: ${bag[TagKey.Lyric]}", bag[TagKey.Lyric]?.contains("第一行") == true)
        assertTrue("封面没读出来", (bag.cover?.bytes?.size ?: 0) > 0)
    }

    // ── 写：原地改，且音频一个字节都不许动 ──

    @Test
    fun mp3_write_in_place_keeps_audio() {
        val file = fixture("fix.mp3")
        val beforeLen = file.length()
        val beforeAudio = audioOf(file, "mp3")

        val result = AnnieTag.write(file, TagBag.of(TagKey.Lyric to "new lyric line"))
        assertTrue("写失败: ${result.message}", result.ok)
        assertTrue("应当原地写而不是重写", result.inPlace)
        assertEquals("文件长度不该变", beforeLen, file.length())
        assertArrayEquals("音频数据被搬动了", beforeAudio, audioOf(file, "mp3"))

        val bag = AnnieTag.read(file)
        assertEquals("new lyric line", bag!![TagKey.Lyric])
        // 没给的字段必须保留原值
        assertEquals("Fixture Title", bag[TagKey.Title])
        assertEquals("Fixture Artist", bag[TagKey.Artist])
        publish("out.mp3", file)
    }

    @Test
    fun flac_write_in_place_keeps_audio() {
        val file = fixture("fix.flac")
        val beforeLen = file.length()
        val beforeAudio = audioOf(file, "flac")

        // 加一段歌词（变大），只要还塞得进原块链就能原地写
        val longLyric = "扩写歌词".repeat(40)
        val result = AnnieTag.write(file, TagBag.of(TagKey.Lyric to longLyric))
        assertTrue("写失败: ${result.message}", result.ok)

        val bag = AnnieTag.read(file)
        assertEquals(longLyric, bag!![TagKey.Lyric])
        assertEquals("Fixture Title", bag[TagKey.Title])
        assertEquals("封面应保留", true, (bag.cover?.bytes?.size ?: 0) > 0)

        if (result.inPlace) {
            assertEquals("原地写不该改变文件长度", beforeLen, file.length())
            assertArrayEquals("音频数据被搬动了", beforeAudio, audioOf(file, "flac"))
        }
        publish("out.flac", file)
    }

    @Test
    fun m4a_write_in_place_keeps_mdat() {
        val file = fixture("fix.m4a")
        val beforeMdat = audioOf(file, "m4a")

        val result = AnnieTag.write(
            file,
            TagBag.of(TagKey.Lyric to "新的歌词第一行\n第二行", TagKey.Comment to "AnnieTag"),
        )
        assertTrue("写失败: ${result.message}", result.ok)
        assertTrue("MP4 应当原地写（旧 moov 改 free + 尾部追加）", result.inPlace)
        assertArrayEquals("mdat 被搬动了", beforeMdat, audioOf(file, "m4a"))

        val bag = AnnieTag.read(file)
        assertEquals("新的歌词第一行\n第二行", bag!![TagKey.Lyric])
        assertEquals("AnnieTag", bag[TagKey.Comment])
        // 原字段保留
        assertEquals("测试标题", bag[TagKey.Title])
        assertEquals("3/12", bag[TagKey.Track])
        assertEquals(true, (bag.cover?.bytes?.size ?: 0) > 0)
        publish("out.m4a", file)
    }

    @Test
    fun unsupported_format_is_reported() {
        val dir = Files.createTempDirectory("annietag-txt").toFile()
        val txt = File(dir, "a.txt")
        txt.writeText("not audio at all, just text padding here")
        assertEquals(null, AnnieTag.read(txt))
        val result = AnnieTag.write(txt, TagBag.of(TagKey.Title to "x"))
        assertTrue("不支持的格式应当明确失败", !result.ok)
        assertEquals("不支持的格式", result.message)
    }

    /**
     * 长尾字段（`extra`）在三格式上都要能写能读 —— 这条是「以后加新标签不用改打标库」的保证。
     */
    @Test
    fun extra_fields_round_trip_on_all_formats() {
        for (name in listOf("fix.mp3", "fix.flac", "fix.m4a")) {
            val file = fixture(name)
            val result = AnnieTag.write(
                file,
                TagBag.extraOf("BPM" to "128", "MOOD" to "Chill"),
            )
            assertTrue("$name 写 extra 失败: ${result.message}", result.ok)

            val bag = AnnieTag.read(file)
            assertNotNull("$name 读不出标签", bag)
            assertEquals("$name 的 BPM", "128", bag!!.extra["BPM"])
            assertEquals("$name 的 MOOD", "Chill", bag.extra["MOOD"])
            // 标准字段不能被 extra 顶掉
            assertNotNull("$name 标题丢了", bag[TagKey.Title])
            publish("extra-$name", file)
        }
    }

    // ── 其余容器（ogg / opus / wav / aiff / wv / tta） ──

    @Test
    fun other_formats_read_ffmpeg_tags() {
        for (name in listOf("fix.ogg", "fix.opus", "fix.wav", "fix.aiff", "fix.wv", "fix.tta")) {
            val file = fixture(name)
            val bag = AnnieTag.read(file)
            assertNotNull("$name 读不出标签", bag)
            assertEquals("$name 标题", "Fixture Title", bag!![TagKey.Title])
            assertEquals("$name 艺术家", "Fixture Artist", bag[TagKey.Artist])
            assertEquals("$name 专辑", "Fixture Album", bag[TagKey.Album])
            assertEquals("$name 音轨", "3/12", bag[TagKey.Track])
        }
    }

    @Test
    fun other_formats_write_round_trip_and_keep_audio() {
        for (name in listOf("fix.ogg", "fix.opus", "fix.wav", "fix.aiff", "fix.wv", "fix.tta")) {
            val file = fixture(name)
            val before = file.readBytes()

            val result = AnnieTag.write(
                file,
                TagBag.of(
                    TagKey.Lyric to "lyric for $name",
                    TagKey.Title to "New Title",
                ),
            )
            assertTrue("$name 写失败: ${result.message}", result.ok)

            val bag = AnnieTag.read(file)
            assertNotNull("$name 写后读不出", bag)
            assertEquals("$name 新标题", "New Title", bag!![TagKey.Title])
            assertEquals("$name 歌词", "lyric for $name", bag[TagKey.Lyric])
            assertEquals("$name 艺术家应保留", "Fixture Artist", bag[TagKey.Artist])

            val after = file.readBytes()
            when (name.substringAfterLast('.')) {
                // RIFF / AIFF：标签是尾部 chunk，音频在 data / SSND 里 —— 直接比那段载荷
                "wav" -> assertBytesEqual(
                    "$name 的 data 载荷被改动了",
                    chunkPayload(before, "data", littleEndian = true),
                    chunkPayload(after, "data", littleEndian = true),
                )
                "aiff" -> assertBytesEqual(
                    "$name 的 SSND 载荷被改动了",
                    chunkPayload(before, "SSND", littleEndian = false),
                    chunkPayload(after, "SSND", littleEndian = false),
                )
                // 尾部标签型：音频 = 旧标签起点之前的全部字节
                "wv", "tta" -> {
                    val audioEnd = apeTagStart(before)
                    assertBytesEqual(
                        "$name 的音频区被改动了",
                        before.copyOfRange(0, audioEnd),
                        after.copyOfRange(0, audioEnd),
                    )
                }
                else -> Unit
            }
            publish("w-$name", file)
        }
    }

    /**
     * 版权（`Copyright`）在**所有能表达的格式**上都要能写能读。
     *
     * 加这条是因为踩过一次：Apple 下载下来的歌在 ffprobe / TagLib 里都没有版权，
     * 一开始怀疑写入器，实际是**数据没进来**（`Song` 没这个字段、Apple 的版权在专辑层）。
     * 有了这条测试，至少「写入器这一端」不会再有疑问。
     */
    @Test
    fun copyright_round_trip_on_all_formats() {
        val copyright = "℗ 2024 Annie Test Records"
        for (name in listOf("fix.mp3", "fix.flac", "fix.m4a", "fix.ogg", "fix.opus", "fix.wav", "fix.aiff", "fix.wv", "fix.tta")) {
            val file = fixture(name)
            val result = AnnieTag.write(file, TagBag.of(TagKey.Copyright to copyright))
            assertTrue("$name 写版权失败: ${result.message}", result.ok)

            val bag = AnnieTag.read(file)
            assertNotNull("$name 写后读不出", bag)
            assertEquals("$name 版权", copyright, bag!![TagKey.Copyright])
            publish("c-$name", file)
        }
    }

    // ── 下载链路里能纯 JVM 测的那一段：Song → 标签字段 ──

    /**
     * 「下载下来会写哪些字段」的守门测试。
     *
     * 之前 Apple 下载的文件缺版权，根因就是这张表漏了 `Copyright` —— 而这段代码当时
     * 埋在 `AppleDownloader` 的私有方法里，只能靠真机下载再看结果。抽成纯函数之后，
     * 少一个字段这里就红。
     */
    @Test
    fun download_tag_field_set_is_complete() {
        val song = com.annieplayer.annie_music.kt.data.model.Song(
            id = "apple:1440857781",
            title = "Title",
            artist = "Artist",
            cover = "https://example.com/cover.jpg",
            album = "Album",
            albumArtist = "AlbumArtist",
            duration = 200,
            platform = com.annieplayer.annie_music.kt.data.model.MusicPlatform.apple,
            trackNumber = "3",
            discNumber = "1",
            releaseDate = "2024-01-02",
            composer = "Composer",
            genre = "Pop",
            isrc = "USXXX0000001",
            copyright = "℗ 2024 Test Records",
            version = "Remastered 2011",
        )
        val text = TagMapper.ofSong(song, "lyric line")

        val expected = listOf(
            TagKey.Title, TagKey.Artist, TagKey.Album, TagKey.AlbumArtist,
            TagKey.Track, TagKey.Disc, TagKey.Year, TagKey.Composer,
            TagKey.Genre, TagKey.Isrc, TagKey.Copyright, TagKey.Comment, TagKey.Lyric,
        )
        for (key in expected) {
            assertTrue("下载打标漏了字段: ${key.label}（$key）", text[key]?.isNotEmpty() == true)
        }
        assertEquals("℗ 2024 Test Records", text[TagKey.Copyright])
        assertEquals("Remastered 2011", text[TagKey.Comment])
        // 空值不该占位（否则会写出空标签）
        val sparse = TagMapper.ofSong(song.copy(composer = null, genre = ""), null)
        assertTrue("空字段不该写", sparse[TagKey.Composer] == null && sparse[TagKey.Genre] == null)
        assertTrue("空歌词不该写", sparse[TagKey.Lyric] == null)
    }

    /**
     * Qobuz 的元数据映射必须把 `QobuzTrack` 上**已有**的字段全带进 `Song`。
     *
     * 之前漏了 `copyright` / `version` —— 于是下载时又发一次 `getTrack` 去补，
     * 补不到就把字段丢了，白搭一次网络请求。
     */
    @Test
    fun qobuz_track_maps_all_tag_fields() {
        val track = com.annieplayer.annie_music.kt.data.model.QobuzTrack(
            id = "123456",
            title = "Track Title",
            artist = "Performer",
            cover = "https://example.com/c.jpg",
            album = "Album Name",
            albumId = "abc",
            albumArtist = "Album Artist",
            trackNumber = 4,
            discNumber = 2,
            duration = 200,
            streamable = true,
            year = 2011,
            releaseDateOriginal = "2011-05-06",
            isrc = "FRXXX1100001",
            version = "Remastered 2011",
            copyright = "℗ 2011 Test Label",
            composer = "Composer Name",
            genre = "Rock",
        )
        val song = com.annieplayer.annie_music.kt.data.sources.qobuz.qobuzTrackToSong(track)

        val text = TagMapper.ofSong(song, "lyric")
        for (key in listOf(
            TagKey.Title, TagKey.Artist, TagKey.Album, TagKey.AlbumArtist,
            TagKey.Track, TagKey.Disc, TagKey.Year, TagKey.Composer,
            TagKey.Genre, TagKey.Isrc, TagKey.Copyright, TagKey.Comment, TagKey.Lyric,
        )) {
            assertTrue("Qobuz 映射漏了 ${key.label}（$key）", text[key]?.isNotEmpty() == true)
        }
        assertEquals("4", song.trackNumber)
        assertEquals("2", song.discNumber)
        assertEquals("2011-05-06", song.releaseDate)
        assertEquals("℗ 2011 Test Label", song.copyright)
        assertEquals("Remastered 2011", song.version)
    }

    // ── DSD：ffmpeg 没有 muxer，夹具在测试里现场造 ──
    @Test
    fun dsf_round_trip_updates_pointer_and_total_size() {
        val file = synthDsf(audioBytes = 4096)
        val before = file.readBytes()

        assertNull("空 DSF 不该读出标签", AnnieTag.read(file))
        val result = AnnieTag.write(file, TagBag.of(TagKey.Title to "DSD Title", TagKey.Lyric to "dsd lyric"))
        assertTrue("DSF 写失败: ${result.message}", result.ok)
        assertTrue("DSF 应当是原地写", result.inPlace)

        val bag = AnnieTag.read(file)
        assertNotNull("DSF 写后读不出", bag)
        assertEquals("DSD Title", bag!![TagKey.Title])
        assertEquals("dsd lyric", bag[TagKey.Lyric])

        val after = file.readBytes()
        // 8 字节小端：offset 12 = total file size，offset 20 = ID3v2 指针
        val totalSize = u64le(after, 12)
        val pointer = u64le(after, 20)
        assertEquals("total file size 必须等于实际长度", after.size.toLong(), totalSize)
        assertTrue("指针必须指向音频之后", pointer > 0 && pointer < after.size)
        // 音频区（92 字节头之后的全部原始字节）必须原样保留；
        // 头部那 8+8 字节（total size / pointer）本来就是我们要改的字段。
        assertPrefix("DSF 音频被搬动了", before.copyOfRange(92, before.size), after.copyOfRange(92, after.size))
        publish("w-dsf.dsf", file)
    }

    @Test
    fun dff_round_trip_appends_id3_chunk() {
        val file = synthDff(audioBytes = 4096)
        val before = file.readBytes()

        assertNull("空 DFF 不该读出标签", AnnieTag.read(file))
        val result = AnnieTag.write(file, TagBag.of(TagKey.Title to "DFF Title"))
        assertTrue("DFF 写失败: ${result.message}", result.ok)

        val bag = AnnieTag.read(file)
        assertNotNull("DFF 写后读不出", bag)
        assertEquals("DFF Title", bag!![TagKey.Title])

        val after = file.readBytes()
        // FRM8 的 size 字段（offset 4..11）本来就是我们要改的，从 16 字节后比音频
        assertPrefix("DFF 音频被搬动了", before.copyOfRange(16, before.size), after.copyOfRange(16, after.size))
        assertTrue("必须出现 ID3  chunk", String(after, Charsets.ISO_8859_1).contains("ID3 "))
        publish("w-dff.dff", file)
    }

    /** 造一个最小合法 DSF：DSD(28) + fmt(52) + data(12+N)。 */
    private fun synthDsf(audioBytes: Int): File {
        val audio = ByteArray(audioBytes) { (it % 251).toByte() }
        val total = 28 + 52 + 12 + audioBytes
        val out = java.io.ByteArrayOutputStream()
        out.write("DSD ".toByteArray(Charsets.ISO_8859_1))
        out.write(u64leBytes(28))
        out.write(u64leBytes(total.toLong()))
        out.write(u64leBytes(0)) // 无 ID3v2
        out.write("fmt ".toByteArray(Charsets.ISO_8859_1))
        out.write(u64leBytes(52))
        // fmt 载荷按 DSF 规格填真值（version/formatId/channelType/ch/采样率/位深/样本数/块大小/reserved），
        // 这样 ffprobe 也能独立读这个夹具，验证我们的结构改动没破坏文件。
        out.write(u32leBytes(1)) // format version
        out.write(u32leBytes(0)) // format id = DSD raw
        out.write(u32leBytes(2)) // channel type = stereo
        out.write(u32leBytes(2)) // channel num
        out.write(u32leBytes(2_822_400)) // sampling frequency（DSD64）
        out.write(u32leBytes(1)) // bits per sample
        out.write(u64leBytes((audioBytes * 8).toLong())) // sample count
        out.write(u32leBytes(4096)) // block size per channel
        out.write(u32leBytes(0)) // reserved
        out.write("data".toByteArray(Charsets.ISO_8859_1))
        out.write(u64leBytes(12L + audioBytes))
        out.write(audio)
        val dir = Files.createTempDirectory("annietag-dsf").toFile()
        return File(dir, "test.dsf").apply { writeBytes(out.toByteArray()) }
    }

    /** 造一个合法 DFF（DSDIFF）：FRM8 + FVER + PROP(SND:FS/CHNL/CMPR) + DSD 音频。 */
    private fun synthDff(audioBytes: Int): File {
        val audio = ByteArray(audioBytes) { (it % 241).toByte() }

        fun chunk(id: String, payload: ByteArray): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            out.write(id.toByteArray(Charsets.ISO_8859_1))
            out.write(u64beBytes(payload.size.toLong()))
            out.write(payload)
            if (payload.size % 2 != 0) out.write(0) // DSDIFF 奇数长度补 1 字节
            return out.toByteArray()
        }

        val fver = chunk("FVER", byteArrayOf(0x01, 0x05, 0x00, 0x00))
        val fs = chunk("FS  ", byteArrayOf(0x00, 0x2B, 0x11, 0x00)) // 2822400 Hz
        val chnl = chunk(
            "CHNL",
            byteArrayOf(0x00, 0x02) + "SLFT".toByteArray(Charsets.ISO_8859_1) + "SRGT".toByteArray(Charsets.ISO_8859_1),
        )
        val cmpr = chunk("CMPR", "DSD not compressed".toByteArray(Charsets.ISO_8859_1))
        val prop = chunk("PROP", "SND ".toByteArray(Charsets.ISO_8859_1) + fs + chnl + cmpr)
        val dsd = chunk("DSD ", audio)

        val body = fver + prop + dsd
        val out = java.io.ByteArrayOutputStream()
        out.write("FRM8".toByteArray(Charsets.ISO_8859_1))
        out.write(u64beBytes((4 + body.size).toLong()))
        out.write("DSD ".toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        val dir = Files.createTempDirectory("annietag-dff").toFile()
        return File(dir, "test.dff").apply { writeBytes(out.toByteArray()) }
    }

    private fun u64le(buf: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (buf[off + i].toLong() and 0xFF)
        return v
    }

    private fun u64leBytes(v: Long): ByteArray = ByteArray(8) { i -> ((v ushr (8 * i)) and 0xFF).toByte() }

    private fun u32leBytes(v: Int): ByteArray = ByteArray(4) { i -> ((v ushr (8 * i)) and 0xFF).toByte() }

    private fun u64beBytes(v: Long): ByteArray = ByteArray(8) { i -> ((v ushr (8 * (7 - i))) and 0xFF).toByte() }

    private fun assertNull(message: String, value: Any?) {
        if (value != null) throw AssertionError(message)
    }

    /** [original] 必须是 [after] 的前缀（尾部追加型格式的强不变量）。 */
    private fun assertPrefix(message: String, original: ByteArray, after: ByteArray) {
        assertTrue("$message（长度 ${after.size} < ${original.size}）", after.size >= original.size)
        for (i in original.indices) {
            if (original[i] != after[i]) throw AssertionError("$message（首个差异在 $i 字节）")
        }
    }

    private fun assertBytesEqual(message: String, expected: ByteArray, actual: ByteArray) {
        assertEquals("$message（长度 ${expected.size} vs ${actual.size}）", expected.size, actual.size)
        for (i in expected.indices) {
            if (expected[i] != actual[i]) throw AssertionError("$message（首个差异在 $i 字节）")
        }
    }

    /** 取 RIFF/AIFF 里某个 chunk 的载荷（跳过 `RIFF`/`FORM` + size + formType 共 12 字节）。 */
    private fun chunkPayload(bytes: ByteArray, id: String, littleEndian: Boolean): ByteArray {
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val cid = bytes.ascii(pos, 4)
            val size = if (littleEndian) bytes.u32le(pos + 4) else bytes.u32be(pos + 4)
            if (size < 0 || pos + 8 + size > bytes.size) break
            if (cid == id) return bytes.copyOfRange(pos + 8, pos + 8 + size)
            pos += 8 + size + (size % 2)
        }
        return ByteArray(0)
    }

    /** APEv2 标签（含可选前置 header）的起点；用于断言「音频区没被动」。 */
    private fun apeTagStart(bytes: ByteArray): Int {
        val text = String(bytes, Charsets.ISO_8859_1)
        val footer = text.lastIndexOf("APETAGEX")
        if (footer < 0) return bytes.size
        val size = bytes.u32le(footer + 12)
        val flags = bytes.u32le(footer + 20)
        var start = footer + 32 - size
        if (flags and 0x80000000.toInt() != 0) start -= 32
        return start.coerceIn(0, bytes.size)
    }


    // ── 夹具辅助：取「音频区」字节，用来断言写标签没搬动音频 ──

    private fun audioOf(file: File, kind: String): ByteArray {
        val bytes = file.readBytes()
        val start = when (kind) {
            "mp3" -> {
                if (bytes.size > 10 && bytes[0].toInt() == 0x49 && bytes[1].toInt() == 0x44 && bytes[2].toInt() == 0x33) {
                    10 + unsynchsafe(bytes.u32be(6))
                } else {
                    0
                }
            }
            "flac" -> {
                var p = 4
                while (true) {
                    val flagType = bytes[p].toInt() and 0xFF
                    val len = ((bytes[p + 1].toInt() and 0xFF) shl 16) or
                        ((bytes[p + 2].toInt() and 0xFF) shl 8) or (bytes[p + 3].toInt() and 0xFF)
                    p += 4 + len
                    if (flagType and 0x80 != 0) break
                }
                p
            }
            else -> {
                // mdat 的载荷起点（box 头 8 字节之后）
                var p = 0
                while (p + 8 <= bytes.size) {
                    val size = bytes.u32be(p)
                    val type = bytes.ascii(p + 4, 4)
                    if (size < 8) break
                    if (type == "mdat") return bytes.copyOfRange(p + 8, p + size)
                    p += size
                }
                return ByteArray(0)
            }
        }
        return bytes.copyOfRange(start, bytes.size)
    }

    private fun assertArrayEquals(message: String, expected: ByteArray, actual: ByteArray) {
        assertEquals(message + "（长度 ${expected.size} vs ${actual.size}）", expected.size, actual.size)
        for (i in expected.indices) {
            if (expected[i] != actual[i]) {
                throw AssertionError("$message（首个差异在 $i 字节）")
            }
        }
    }
}
