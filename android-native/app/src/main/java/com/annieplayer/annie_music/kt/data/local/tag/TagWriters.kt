package com.annieplayer.annie_music.kt.data.local.tag

/**
 * **标签编解码层**：只负责「字段 ↔ 字节」，不关心字节写在容器的哪个位置。
 *
 * 分成这一层的原因：容器接线（头部 / 尾部 / 块链 / 指针）各不相同，但标签本体只有四套：
 * ID3v2、Vorbis comment、APEv2、MP4 ilst。分开之后：
 *
 * | 容器 | 用哪套编解码 | 接线 |
 * |---|---|---|
 * | mp3 / tta | ID3v2 | 文件头 |
 * | dsf / dff | ID3v2 | 文件尾（dsf 还有 header 里的 8 字节指针） |
 * | wav / aiff | ID3v2 | 尾部 chunk |
 * | flac | Vorbis comment | 元数据块链 |
 * | ogg / opus | Vorbis comment | 首页群 |
 * | ape / wv / mpc | APEv2 | 文件尾 |
 * | m4a / mp4 | MP4 ilst | moov |
 *
 * 加一种新容器 = 一小段接线，编解码不用动。
 */

// ================================================================================================
// ID3v2（v2.4 写；v2.2/2.3/2.4 读）
// ================================================================================================

internal object Id3v2Tag {

    const val HEADER_SIZE = 10

    /** 语言码固定 `und`：ffmpeg/ffprobe 只把 `lyrics-und`、`comment-und` 归一化成 `lyrics`/`comment`。 */
    private const val LANG = "und"

    // ── 解码 ──

    /**
     * 解一段完整的 ID3v2 tag（含 10 字节头）。
     *
     * @return 解出的标签；不是 ID3v2 或长度不足返回 null
     */
    fun decode(tag: ByteArray): TagBag? {
        if (tag.size < HEADER_SIZE) return null
        if (tag[0] != 'I'.code.toByte() || tag[1] != 'D'.code.toByte() || tag[2] != '3'.code.toByte()) return null
        val version = tag[3].toInt() and 0xFF
        val flags = tag[5].toInt() and 0xFF
        val size = unsynchsafe(tag.u32be(6))
        if (size <= 0 || HEADER_SIZE + size > tag.size) return null
        val raw = tag.copyOfRange(HEADER_SIZE, HEADER_SIZE + size)
        val body = if (flags and 0x80 != 0) deUnsync(raw) else raw

        val out = LinkedHashMap<TagKey, String>()
        val extra = LinkedHashMap<String, String>()
        var cover: CoverBytes? = null

        var p = extendedHeaderEnd(body, version, flags)
        while (p + HEADER_SIZE <= body.size) {
            val id = body.ascii(p, 4)
            if (id[0] == '\u0000') break // 到 padding
            val frameSize = if (version >= 4) unsynchsafe(body.u32be(p + 4)) else body.u32be(p + 4)
            if (frameSize <= 0 || p + HEADER_SIZE + frameSize > body.size) break
            val frameFlags = body[p + 8].toInt() and 0xFF
            var payload = body.bytes(p + HEADER_SIZE, frameSize)
            if (version >= 4) {
                if (frameFlags and 0x02 != 0) payload = deUnsync(payload)
                if (frameFlags and 0x01 != 0 && payload.size >= 4) payload = payload.copyOfRange(4, payload.size)
            }
            when (id) {
                "TIT2" -> put(out, TagKey.Title, decodeText(payload))
                "TPE1" -> put(out, TagKey.Artist, decodeText(payload))
                "TALB" -> put(out, TagKey.Album, decodeText(payload))
                "TPE2" -> put(out, TagKey.AlbumArtist, decodeText(payload))
                "TRCK" -> put(out, TagKey.Track, decodeText(payload))
                "TPOS" -> put(out, TagKey.Disc, decodeText(payload))
                "TDRC", "TYER" -> put(out, TagKey.Year, decodeText(payload))
                "TCON" -> put(out, TagKey.Genre, decodeText(payload))
                "TCOM" -> put(out, TagKey.Composer, decodeText(payload))
                "TSRC" -> put(out, TagKey.Isrc, decodeText(payload))
                "TCOP" -> put(out, TagKey.Copyright, decodeText(payload))
                "COMM" -> put(out, TagKey.Comment, decodeCommentLike(payload))
                "USLT" -> put(out, TagKey.Lyric, decodeCommentLike(payload))
                "TXXX" -> decodeTxxx(payload)?.let { (desc, value) ->
                    if (desc.isNotEmpty() && value.isNotEmpty() && txxxKeyOf(desc) == null) {
                        extra.putIfAbsent(desc, value)
                    }
                }
                "APIC" -> if (cover == null) cover = decodeApic(payload)
            }
            p += HEADER_SIZE + frameSize
        }
        return TagBag(out, cover, extra)
    }

    private fun extendedHeaderEnd(body: ByteArray, version: Int, tagFlags: Int): Int {
        if (tagFlags and 0x40 == 0 || body.size < 4) return 0
        return when {
            version >= 4 -> unsynchsafe(body.u32be(0)).coerceIn(0, body.size)
            else -> (body.u32be(0) + 4).coerceIn(0, body.size) // v2.3 扩展头尺寸不含那 4 字节
        }
    }

    private fun decodeCommentLike(payload: ByteArray): String {
        if (payload.size < 5) return ""
        val enc = payload[0].toInt() and 0xFF
        val start = terminatorEnd(payload, 4, enc).second
        return decodeText(payload.copyOfRange(start, payload.size), enc)
    }

    private fun decodeTxxx(payload: ByteArray): Pair<String, String>? {
        if (payload.size < 2) return null
        val enc = payload[0].toInt() and 0xFF
        val (descEnd, valueStart) = terminatorEnd(payload, 1, enc)
        return decodeText(payload.copyOfRange(1, descEnd), enc) to
            decodeText(payload.copyOfRange(valueStart, payload.size), enc)
    }

    private fun txxxKeyOf(desc: String): TagKey? =
        if (desc.equals("ISRC", ignoreCase = true)) TagKey.Isrc else null

    private fun terminatorEnd(buf: ByteArray, from: Int, enc: Int): Pair<Int, Int> {
        if (enc == 1 || enc == 2) {
            var p = from
            while (p + 1 < buf.size) {
                if (buf[p].toInt() == 0 && buf[p + 1].toInt() == 0) return p to (p + 2)
                p += 2
            }
            return buf.size to buf.size
        }
        var p = from
        while (p < buf.size) {
            if (buf[p].toInt() == 0) return p to (p + 1)
            p++
        }
        return buf.size to buf.size
    }

    private fun decodeApic(payload: ByteArray): CoverBytes? {
        if (payload.size < 4) return null
        val enc = payload[0].toInt() and 0xFF
        val mimeEnd = indexOfNul(payload, 1)
        if (mimeEnd < 0) return null
        val mime = payload.ascii(1, mimeEnd - 1)
        var p = mimeEnd + 1
        if (p >= payload.size) return null
        p++ // picture type
        p = terminatorEnd(payload, p, enc).second
        if (p >= payload.size) return null
        return CoverBytes(payload.copyOfRange(p, payload.size), if (mime.isEmpty()) "image/jpeg" else mime)
    }

    private fun indexOfNul(buf: ByteArray, from: Int): Int {
        for (i in from until buf.size) if (buf[i].toInt() == 0) return i
        return -1
    }

    private fun decodeText(payload: ByteArray, forcedEnc: Int = -1): String {
        if (payload.isEmpty()) return ""
        val enc = if (forcedEnc >= 0) forcedEnc else payload[0].toInt() and 0xFF
        val start = if (forcedEnc >= 0) 0 else 1
        if (start >= payload.size) return ""
        val raw = payload.copyOfRange(start, payload.size)
        val charset = when (enc) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return String(raw, charset).trimEnd('\u0000').trim()
    }

    private fun put(out: MutableMap<TagKey, String>, key: TagKey, value: String?) {
        if (!value.isNullOrEmpty() && out[key].isNullOrEmpty()) out[key] = value
    }

    private fun deUnsync(buf: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(buf.size)
        var i = 0
        while (i < buf.size) {
            out.write(buf[i].toInt())
            if (buf[i].toInt() and 0xFF == 0xFF && i + 1 < buf.size && buf[i + 1].toInt() == 0) i += 2 else i++
        }
        return out.toByteArray()
    }

    // ── 编码 ──

    /** 编成完整的 ID3v2.4 tag（含 10 字节头）。 */
    fun encode(bag: TagBag): ByteArray {
        val body = encodeFrames(bag)
        val out = java.io.ByteArrayOutputStream(HEADER_SIZE + body.size)
        out.write("ID3".toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf(4, 0, 0)) // v2.4.0，无 unsync / 无扩展头
        out.write(body.size.synchsafeBytes())
        out.write(body)
        return out.toByteArray()
    }

    private fun encodeFrames(bag: TagBag): ByteArray {
        val out = java.io.ByteArrayOutputStream()

        fun text(id: String, value: String?) {
            if (value.isNullOrEmpty()) return
            val bytes = value.toByteArray(Charsets.UTF_8)
            val payload = ByteArray(1 + bytes.size)
            payload[0] = 3 // UTF-8
            bytes.copyInto(payload, 1)
            out.write(frame(id, payload))
        }

        fun commentLike(id: String, value: String?) {
            if (value.isNullOrEmpty()) return
            val bytes = value.toByteArray(Charsets.UTF_8)
            val payload = ByteArray(1 + 3 + 1 + bytes.size)
            payload[0] = 3
            LANG.toByteArray(Charsets.ISO_8859_1).copyInto(payload, 1)
            payload[4] = 0 // 空描述
            bytes.copyInto(payload, 5)
            out.write(frame(id, payload))
        }

        text("TIT2", bag[TagKey.Title])
        text("TPE1", bag[TagKey.Artist])
        text("TALB", bag[TagKey.Album])
        text("TPE2", bag[TagKey.AlbumArtist])
        text("TRCK", bag[TagKey.Track])
        text("TPOS", bag[TagKey.Disc])
        text("TDRC", bag[TagKey.Year])
        text("TCON", bag[TagKey.Genre])
        text("TCOM", bag[TagKey.Composer])
        text("TSRC", bag[TagKey.Isrc])
        text("TCOP", bag[TagKey.Copyright])
        commentLike("COMM", bag[TagKey.Comment])
        commentLike("USLT", bag[TagKey.Lyric])

        for ((desc, value) in bag.extra) {
            if (desc.isEmpty() || value.isEmpty()) continue
            if (txxxKeyOf(desc) != null) continue
            val descBytes = desc.toByteArray(Charsets.UTF_8)
            val valueBytes = value.toByteArray(Charsets.UTF_8)
            val payload = ByteArray(1 + descBytes.size + 1 + valueBytes.size)
            payload[0] = 3
            descBytes.copyInto(payload, 1)
            payload[1 + descBytes.size] = 0
            valueBytes.copyInto(payload, 1 + descBytes.size + 1)
            out.write(frame("TXXX", payload))
        }

        bag.cover?.let { out.write(apicFrame(it)) }
        return out.toByteArray()
    }

    private fun frame(id: String, payload: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(HEADER_SIZE + payload.size)
        out.write(id.toByteArray(Charsets.ISO_8859_1))
        out.write(payload.size.synchsafeBytes())
        out.write(byteArrayOf(0, 0))
        out.write(payload)
        return out.toByteArray()
    }

    private fun apicFrame(cover: CoverBytes): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(3)
        out.write(cover.mime.toByteArray(Charsets.ISO_8859_1))
        out.write(0)
        out.write(3) // front cover
        out.write(0)
        out.write(cover.bytes)
        return frame("APIC", out.toByteArray())
    }

    /** 文件开头那个 ID3v2 tag 的总长（含头）；没有返回 0。 */
    fun totalAtFileStart(file: java.io.File): Long {
        val head = file.readHead(HEADER_SIZE)
        return tagTotalOf(head)
    }

    /** 从任意一段「以 ID3 开头」的字节里算 tag 总长；不是 ID3v2 返回 0。 */
    fun tagTotalOf(head: ByteArray): Long {
        if (head.size < HEADER_SIZE) return 0
        if (head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() || head[2] != '3'.code.toByte()) return 0
        return (HEADER_SIZE + unsynchsafe(head.u32be(6))).toLong()
    }
}

// ================================================================================================
// Vorbis comment（FLAC 块 / Ogg 的 comment packet 共用）
// ================================================================================================

internal object VorbisComment {

    private const val VENDOR = "AnnieTag"

    fun decode(payload: ByteArray): TagBag {
        val out = LinkedHashMap<TagKey, String>()
        val extra = LinkedHashMap<String, String>()
        if (payload.size < 8) return TagBag()
        var p = 0
        val vendorLen = payload.u32le(p).coerceAtLeast(0)
        p += 4 + vendorLen
        if (p + 4 > payload.size) return TagBag()
        val count = payload.u32le(p).coerceAtLeast(0)
        p += 4
        repeat(count) {
            if (p + 4 > payload.size) return TagBag(out, null, extra)
            val len = payload.u32le(p)
            p += 4
            if (len < 0 || p + len > payload.size) return TagBag(out, null, extra)
            val entry = String(payload, p, len, Charsets.UTF_8)
            p += len
            val eq = entry.indexOf('=')
            if (eq > 0) {
                val key = entry.substring(0, eq).uppercase()
                val value = entry.substring(eq + 1)
                val mapped = keyOf(key)
                if (mapped != null) {
                    if (out[mapped].isNullOrEmpty() && value.isNotEmpty()) out[mapped] = value
                } else if (value.isNotEmpty()) {
                    extra.putIfAbsent(key, value)
                }
            }
        }
        return TagBag(out, null, extra)
    }

    fun encode(bag: TagBag): ByteArray {
        val vendor = VENDOR.toByteArray(Charsets.UTF_8)
        val entries = ArrayList<ByteArray>()
        for ((key, value) in bag.text) {
            if (value.isEmpty()) continue
            val name = nameOf(key) ?: continue
            entries.add("$name=$value".toByteArray(Charsets.UTF_8))
        }
        for ((rawKey, value) in bag.extra) {
            if (value.isEmpty()) continue
            val name = rawKey.uppercase()
            if (keyOf(name) != null) continue
            entries.add("$name=$value".toByteArray(Charsets.UTF_8))
        }
        val out = java.io.ByteArrayOutputStream()
        out.write(vendor.size.toU32le())
        out.write(vendor)
        out.write(entries.size.toU32le())
        for (e in entries) {
            out.write(e.size.toU32le())
            out.write(e)
        }
        return out.toByteArray()
    }

    private fun keyOf(k: String): TagKey? = when (k) {
        "TITLE" -> TagKey.Title
        "ARTIST" -> TagKey.Artist
        "ALBUM" -> TagKey.Album
        "ALBUMARTIST", "ALBUM ARTIST" -> TagKey.AlbumArtist
        "TRACKNUMBER" -> TagKey.Track
        "DISCNUMBER" -> TagKey.Disc
        "DATE", "YEAR" -> TagKey.Year
        "GENRE" -> TagKey.Genre
        "COMPOSER" -> TagKey.Composer
        "ISRC" -> TagKey.Isrc
        "VERSION", "COMMENT" -> TagKey.Comment
        "COPYRIGHT" -> TagKey.Copyright
        "LYRICS", "UNSYNCEDLYRICS" -> TagKey.Lyric
        else -> null
    }

    private fun nameOf(key: TagKey): String? = when (key) {
        TagKey.Title -> "TITLE"
        TagKey.Artist -> "ARTIST"
        TagKey.Album -> "ALBUM"
        TagKey.AlbumArtist -> "ALBUMARTIST"
        TagKey.Track -> "TRACKNUMBER"
        TagKey.Disc -> "DISCNUMBER"
        TagKey.Year -> "DATE"
        TagKey.Genre -> "GENRE"
        TagKey.Composer -> "COMPOSER"
        TagKey.Isrc -> "ISRC"
        TagKey.Comment -> "COMMENT"
        TagKey.Copyright -> "COPYRIGHT"
        TagKey.Lyric -> "LYRICS"
    }
}

// ================================================================================================
// APEv2（ape / wavpack / musepack 的尾部标签）
// ================================================================================================

internal object ApeV2Tag {

    /** footer（也是最小写法里的唯一结构）：32 字节，末尾 8 字节是 "APETAGEX"。 */
    const val FOOTER_SIZE = 32
    private const val PREAMBLE = "APETAGEX"

    /**
     * 整个 APE 标签（可选前置 header + items + footer）在文件里的起点；没有返回 -1。
     *
     * APEv2 的 footer 里 `size` 覆盖「items + footer」，若 flags 的 bit31 置位则前面还有一个
     * 32 字节 header —— 两者都要算进「要删掉的旧标签」。
     */
    fun findTagStart(file: java.io.File): Long {
        if (file.length() < FOOTER_SIZE) return -1
        val tail = java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(file.length() - FOOTER_SIZE)
            val b = ByteArray(FOOTER_SIZE)
            raf.readFully(b)
            b
        }
        if (tail.ascii(0, 8) != PREAMBLE) return -1
        val size = tail.u32le(12)
        if (size <= 0) return -1
        val flags = tail.u32le(20)
        val hasHeader = (flags and 0x80000000.toInt()) != 0
        val start = file.length() - size - if (hasHeader) FOOTER_SIZE.toLong() else 0L
        return if (start < 0) -1 else start
    }

    /** 解 `items + footer` 这段字节（前置 header 要先由调用方剥掉）。 */
    fun decode(tagBytes: ByteArray): TagBag {
        val out = LinkedHashMap<TagKey, String>()
        val extra = LinkedHashMap<String, String>()
        val footerStart = tagBytes.size - FOOTER_SIZE
        if (footerStart < 0 || tagBytes.ascii(footerStart, 8) != PREAMBLE) return TagBag()
        val count = tagBytes.u32le(footerStart + 16)
        var p = 0
        repeat(count) {
            if (p + 8 > footerStart) return@repeat
            val valueLen = tagBytes.u32le(p)
            val flags = tagBytes.u32le(p + 4)
            val keyEnd = indexOfNul(tagBytes, p + 8, footerStart)
            if (keyEnd < 0 || valueLen < 0) return@repeat
            val key = tagBytes.ascii(p + 8, keyEnd - (p + 8))
            val valueStart = keyEnd + 1
            if (valueStart + valueLen > footerStart) return@repeat
            val value = String(tagBytes, valueStart, valueLen, Charsets.UTF_8)
            p += 8 + (keyEnd - (p + 8)) + 1 + valueLen
            if (key.isEmpty() || value.isEmpty()) return@repeat
            val mapped = keyOf(key)
            if (mapped != null) {
                if (out[mapped].isNullOrEmpty()) out[mapped] = value
            } else if (flags and 0x2 == 0) {
                // 跳过二进制项（封面等），文本项放 extra
                extra.putIfAbsent(key.uppercase(), value)
            }
        }
        return TagBag(out, null, extra)
    }

    /** 编成「items + footer」这段字节（最小写法：不写前置 header）。 */
    fun encode(bag: TagBag): ByteArray {
        val items = java.io.ByteArrayOutputStream()
        var count = 0
        fun item(key: String, value: String) {
            val k = key.toByteArray(Charsets.ISO_8859_1)
            val v = value.toByteArray(Charsets.UTF_8)
            items.write(v.size.toU32le())
            items.write(0.toU32le()) // flags：0 = 文本
            items.write(k)
            items.write(0)
            items.write(v)
            count++
        }
        for ((key, value) in bag.text) {
            if (value.isEmpty()) continue
            val name = nameOf(key) ?: continue
            item(name, value)
        }
        for ((rawKey, value) in bag.extra) {
            if (value.isEmpty()) continue
            val name = rawKey.uppercase()
            if (keyOf(name) != null) continue
            item(name, value)
        }
        val itemsBytes = items.toByteArray()
        val size = itemsBytes.size + FOOTER_SIZE
        val footer = java.io.ByteArrayOutputStream()
        footer.write(PREAMBLE.toByteArray(Charsets.ISO_8859_1))
        footer.write(2000.toU32le()) // 版本
        footer.write(size.toU32le())
        footer.write(count.toU32le())
        footer.write(0.toU32le()) // flags：0 = 没有前置 header
        footer.write(ByteArray(8)) // reserved
        return itemsBytes + footer.toByteArray()
    }

    private fun indexOfNul(buf: ByteArray, from: Int, end: Int): Int {
        for (i in from until end) if (buf[i].toInt() == 0) return i
        return -1
    }

    private fun keyOf(k: String): TagKey? = when (k.uppercase()) {
        "TITLE" -> TagKey.Title
        "ARTIST" -> TagKey.Artist
        "ALBUM" -> TagKey.Album
        "ALBUM ARTIST", "ALBUMARTIST" -> TagKey.AlbumArtist
        "TRACK" -> TagKey.Track
        "DISC" -> TagKey.Disc
        "YEAR" -> TagKey.Year
        "GENRE" -> TagKey.Genre
        "COMPOSER" -> TagKey.Composer
        "ISRC" -> TagKey.Isrc
        "COMMENT" -> TagKey.Comment
        "COPYRIGHT" -> TagKey.Copyright
        "LYRICS" -> TagKey.Lyric
        else -> null
    }

    private fun nameOf(key: TagKey): String? = when (key) {
        TagKey.Title -> "Title"
        TagKey.Artist -> "Artist"
        TagKey.Album -> "Album"
        TagKey.AlbumArtist -> "Album Artist"
        TagKey.Track -> "Track"
        TagKey.Disc -> "Disc"
        TagKey.Year -> "Year"
        TagKey.Genre -> "Genre"
        TagKey.Composer -> "Composer"
        TagKey.Isrc -> "ISRC"
        TagKey.Comment -> "Comment"
        TagKey.Copyright -> "Copyright"
        TagKey.Lyric -> "Lyrics"
    }
}
