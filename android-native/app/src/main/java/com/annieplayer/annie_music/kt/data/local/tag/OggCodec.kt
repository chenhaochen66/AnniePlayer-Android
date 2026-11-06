package com.annieplayer.annie_music.kt.data.local.tag

import java.io.File
import java.io.RandomAccessFile

/**
 * Ogg 容器接线（Vorbis / Opus / Speex 的 comment header）。
 *
 * ## 为什么它比其他格式麻烦
 * Ogg 是**页（page）**流：每个页有 27 字节头和一张 lacing 表，页内按 lacing 切出 packet。
 * comment 是第 2 个 packet，和 ident / setup 一起占着开头的几页。想改它：
 *
 * - 新 comment **长度一样** → 直接覆盖这几页（CRC 重算），后面的页一个字节都不用动；
 * - 长度不同 → 开头这几页的**页数**可能变化 → 后面所有页的 `sequence` 要重编号、CRC 要重算。
 *   但注意：**音频数据本身不搬**（页内容原样复制，只改 27 字节头里的 seq + crc）。
 *
 * 所以这里的实现是：重建「头部页群」，把后续页逐页复制并修正 seq / crc。
 * 只有当文件结构不符合预期（找不到 comment packet 等）时才明确失败，绝不硬写出坏文件。
 */
internal object OggCodec : TagCodec {

    override val format: String = "Ogg"

    /** 页头固定 27 字节（不含 lacing 表）。 */
    internal const val HEADER_SIZE = 27

    override fun sniff(head: ByteArray): Boolean =
        head.size >= 4 && head.ascii(0, 4) == "OggS"

    // ── 页解析（`StreamCodec` 里的 Ogg 流式注入复用同一套） ──

    internal class Page(
        val headerType: Int,
        val granule: Long,
        val serial: Int,
        val seq: Int,
        val segments: IntArray,
        val payloadOffset: Int,
        val totalSize: Int,
    )

    internal fun readPage(buf: ByteArray, off: Int): Page? {
        if (off + HEADER_SIZE > buf.size) return null
        if (buf.ascii(off, 4) != "OggS") return null
        val headerType = buf[off + 5].toInt() and 0xFF
        var granule = 0L
        for (i in 7 downTo 0) granule = (granule shl 8) or (buf[off + 6 + i].toLong() and 0xFF)
        val serial = buf.u32le(off + 14)
        val seq = buf.u32le(off + 18)
        val segCount = buf[off + 26].toInt() and 0xFF
        if (off + HEADER_SIZE + segCount > buf.size) return null
        val segments = IntArray(segCount) { buf[off + HEADER_SIZE + it].toInt() and 0xFF }
        val payloadSize = segments.sum()
        val payloadOffset = off + HEADER_SIZE + segCount
        if (payloadOffset + payloadSize > buf.size) return null
        return Page(headerType, granule, serial, seq, segments, payloadOffset, HEADER_SIZE + segCount + payloadSize)
    }

    internal fun pageBytes(page: Page, buf: ByteArray, payload: ByteArray, seq: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(page.totalSize)
        out.write("OggS".toByteArray(Charsets.ISO_8859_1))
        out.write(0) // version
        out.write(page.headerType)
        out.write(u64leBytes(page.granule))
        out.write(page.serial.toU32le())
        out.write(seq.toU32le())
        out.write(ByteArray(4)) // CRC 占位，最后回填
        out.write(page.segments.size)
        for (s in page.segments) out.write(s)
        out.write(payload)
        val bytes = out.toByteArray()
        // CRC 覆盖**整个页**（含 27 字节页头），且计算时 CRC 字段本身必须是 0
        val crc = oggCrc(bytes, 0, bytes.size)
        bytes[22] = (crc and 0xFF).toByte()
        bytes[23] = ((crc ushr 8) and 0xFF).toByte()
        bytes[24] = ((crc ushr 16) and 0xFF).toByte()
        bytes[25] = ((crc ushr 24) and 0xFF).toByte()
        return bytes
    }

    /** 把若干 packet 打包成页（简化版：一个包一页，够用于头部页群）。 */
    internal fun pageForPacket(
        packet: ByteArray,
        headerType: Int,
        granule: Long,
        serial: Int,
        seq: Int,
    ): ByteArray {
        val lacing = ArrayList<Int>()
        var remaining = packet.size
        while (remaining >= 255) {
            lacing.add(255)
            remaining -= 255
        }
        lacing.add(remaining)
        val page = Page(
            headerType = headerType,
            granule = granule,
            serial = serial,
            seq = seq,
            segments = lacing.toIntArray(),
            payloadOffset = HEADER_SIZE + lacing.size,
            totalSize = HEADER_SIZE + lacing.size + packet.size,
        )
        return pageBytes(page, ByteArray(0), packet, seq)
    }

    // ── 读 ──

    override fun read(file: File): TagBag? {
        val located = readCommentPacket(file) ?: return null
        val comment = VorbisComment.decode(located)
        return comment.takeIf { !it.isEmpty() }
    }

    /** 取 comment packet 的**原始字节**（去掉包头的魔数，交给 [VorbisComment] 解）。 */
    private fun readCommentPacket(file: File): ByteArray? {
        val (packets, _) = readHeaderPackets(file) ?: return null
        val comment = packets.getOrNull(1) ?: return null
        val prefix = commentPrefix(comment)
        return comment.copyOfRange(prefix.size, comment.size)
    }

    /**
     * comment packet 的包头魔数（写回时必须原样补上）：
     * - Vorbis：`0x03` + `vorbis`（7 字节）
     * - Opus：`OpusTags`（8 字节）
     */
    internal fun commentPrefix(comment: ByteArray): ByteArray = when {        comment.size > 8 && comment.ascii(0, 8) == "OpusTags" -> comment.copyOfRange(0, 8)
        comment.size > 7 && (comment[0].toInt() and 0xFF) == 3 && comment.ascii(1, 6) == "vorbis" ->
            comment.copyOfRange(0, 7)
        else -> ByteArray(0)
    }

    /** 头部页群解析结果。 */
    internal class OggHead(val packets: List<ByteArray>, val end: Int, val serial: Int)

    /**
     * 从文件头缓冲里解析头部页群（ident / comment / setup）。
     *
     * 文件路径和「边下边写」（`StreamCodec`）共用这一份：两边要是各写一遍，
     * 早晚有一边把 lacing 或「Opus 只有 2 个包头」的判定写错。
     */
    internal fun headerPackets(buf: ByteArray): OggHead? {
        var off = 0
        var serial = -1
        val packets = ArrayList<ByteArray>()
        while (off + HEADER_SIZE <= buf.size) {
            val page = readPage(buf, off) ?: break
            if (serial < 0) serial = page.serial
            if (page.serial != serial) break
            // 页内按 lacing 切 packet
            var p = page.payloadOffset
            var current = java.io.ByteArrayOutputStream()
            for (seg in page.segments) {
                current.write(buf, p, seg)
                p += seg
                if (seg < 255) { // 一个 packet 结束
                    packets.add(current.toByteArray())
                    current = java.io.ByteArrayOutputStream()
                }
            }
            off += page.totalSize
            // Vorbis: 3 个包；Opus: 2 个包。到齐就停。
            if (packets.size >= 3) break
            if (packets.size >= 2 && packets[0].ascii(0, 8) == "OpusHead") break
        }
        if (packets.size < 2) return null
        return OggHead(packets, off, serial)
    }

    /**
     * 读出头部页群里的前几个 packet（ident / comment / setup）。
     *
     * @return (packets, 头部页群结束偏移)
     */
    private fun readHeaderPackets(file: File): Pair<List<ByteArray>, Int>? {
        val head = headerPackets(file.readHead(512 * 1024)) ?: return null
        return head.packets to head.end
    }

    // ── 写 ──

    override fun write(file: File, bag: TagBag, policy: WritePolicy): WriteResult {
        val (packets, headerEnd) = readHeaderPackets(file) ?: return WriteResult.fail(format, "读不到头部页群")
        val oldComment = packets[1]
        val existing = runCatching { read(file) }.getOrNull()
        val merged = TagBag(
            text = (existing?.text.orEmpty() + bag.text).filterValues { it.isNotEmpty() },
            cover = bag.cover ?: existing?.cover,
            extra = (existing?.extra.orEmpty() + bag.extra).filterValues { it.isNotEmpty() },
        )
        val prefix = commentPrefix(oldComment)
        val isVorbis = prefix.size == 7 && (prefix[0].toInt() and 0xFF) == 3
        // Vorbis 的 comment 包结尾有一个 **framing bit**（1 字节 0x01），少了它 libvorbis
        // 会认为整块标签非法 —— 表现是「文件能播，但所有标签都不见了」。
        // Opus 的 OpusTags 没有这个要求。
        val tail = if (isVorbis) byteArrayOf(1) else ByteArray(0)
        val newCommentPacket = prefix + VorbisComment.encode(merged) + tail

        // 头部页群：ident 原样、comment 换新、其余（setup）原样
        val rebuilt = java.io.ByteArrayOutputStream()
        val head = file.readHead(HEADER_SIZE + 255 + 4)
        val firstPage = readPage(head, 0) ?: return WriteResult.fail(format, "首页解析失败")
        val serial = firstPage.serial
        var seq = 0
        rebuilt.write(pageForPacket(packets[0], 0x02, 0L, serial, seq++)) // BOS
        rebuilt.write(pageForPacket(newCommentPacket, 0x00, 0L, serial, seq++))
        for (i in 2 until packets.size) {
            rebuilt.write(pageForPacket(packets[i], 0x00, 0L, serial, seq++))
        }
        val headBytes = rebuilt.toByteArray()

        // 后续页原样复制，只改 seq + crc。逐页精确读（不能按固定块读，页会跨块被切断）
        val parent = file.parentFile ?: return WriteResult.fail(format, "路径异常")
        val tmp = File(parent, "${file.name}.annietag.tmp")
        val ok = runCatching {
            RandomAccessFile(file, "r").use { src ->
                java.io.FileOutputStream(tmp).use { fos ->
                    val out = java.io.BufferedOutputStream(fos, 256 * 1024)
                    out.write(headBytes)
                    src.seek(headerEnd.toLong())
                    val hdr = ByteArray(HEADER_SIZE)
                    while (readFully(src, hdr)) {
                        if (hdr.ascii(0, 4) != "OggS") break
                        val segCount = hdr[26].toInt() and 0xFF
                        val segs = ByteArray(segCount)
                        if (!readFully(src, segs)) break
                        var payloadSize = 0
                        val segments = IntArray(segCount) { i ->
                            val v = segs[i].toInt() and 0xFF
                            payloadSize += v
                            v
                        }
                        val payload = ByteArray(payloadSize)
                        if (!readFully(src, payload)) break
                        val page = Page(
                            headerType = hdr[5].toInt() and 0xFF,
                            granule = granuleOf(hdr),
                            serial = hdr.u32le(14),
                            seq = hdr.u32le(18),
                            segments = segments,
                            payloadOffset = 0,
                            totalSize = HEADER_SIZE + segCount + payloadSize,
                        )
                        out.write(pageBytes(page, ByteArray(0), payload, seq++))
                    }
                    out.flush()
                }
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            true
        }.getOrDefault(false)
        runCatching { if (tmp.exists() && tmp != file) tmp.delete() }
        return if (ok) WriteResult.ok(format, inPlace = false) else WriteResult.fail(format, "Ogg 重写失败")
    }

    private fun granuleOf(hdr: ByteArray): Long {
        var g = 0L
        for (i in 7 downTo 0) g = (g shl 8) or (hdr[6 + i].toLong() and 0xFF)
        return g
    }

    private fun readFully(raf: RandomAccessFile, buf: ByteArray): Boolean {
        var read = 0
        while (read < buf.size) {
            val n = raf.read(buf, read, buf.size - read)
            if (n <= 0) return false
            read += n
        }
        return true
    }

    // ── Ogg CRC32（多项式 0x04C11DB7，非反射，初值 0） ──

    private val CRC_TABLE: IntArray = IntArray(256) { n ->
        var c = n shl 24
        repeat(8) {
            c = if (c and 0x80000000.toInt() != 0) (c shl 1) xor 0x04C11DB7 else c shl 1
        }
        c
    }

    private fun oggCrc(buf: ByteArray, from: Int, to: Int): Int {
        var crc = 0
        for (i in from until to) {
            crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) xor (buf[i].toInt() and 0xFF)) and 0xFF]
        }
        return crc
    }

    private fun u64leBytes(v: Long): ByteArray = ByteArray(8) { i -> ((v ushr (8 * i)) and 0xFF).toByte() }
}
