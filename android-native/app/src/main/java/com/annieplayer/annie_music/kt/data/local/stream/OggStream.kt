package com.annieplayer.annie_music.kt.data.local.stream

import com.annieplayer.annie_music.kt.data.local.tag.OggCodec
import com.annieplayer.annie_music.kt.data.local.tag.TagBag
import com.annieplayer.annie_music.kt.data.local.tag.VorbisComment
import com.annieplayer.annie_music.kt.data.local.tag.ascii
import com.annieplayer.annie_music.kt.data.local.tag.u32le
import java.io.InputStream
import java.io.OutputStream

/**
 * Ogg（Vorbis / Opus / Speex）：comment 是**第 2 个 packet**，和 ident / setup 一起占着开头的几页。
 *
 * ```
 * 源：  [BOS: ident][comment + setup][音频页 seq=2][seq=3]…
 * 成品：[BOS: ident][comment' ……][setup ……][音频页 seq=N][seq=N+1]…
 * ```
 *
 * 和别的格式不一样的地方：**body 不是原样透传**。comment 变长以后头部页群的页数会变，
 * 后面每一页的 `page_sequence` 都得跟着改，CRC 也得重算。
 *
 * 但注意改的只有每页 27 字节的头 —— **页里的音频负载原样复制**，一个字节都不搬，
 * 所以这仍然是「零音频拷贝」的流式写法。见 [pumpBody]。
 *
 * 三条格式细节（每条都踩过）：
 * - Vorbis 的 comment packet 结尾有 **framing bit 0x01**，少一个字节整块标签就非法
 *   （表现是「文件能播、标签全不见」），Opus 的 `OpusTags` 没有这个要求；
 * - 写回时包头魔数必须原样补上（`0x03"vorbis"` 或 `"OpusTags"`）；
 * - CRC 覆盖**整页**（含 27 字节页头），计算时 CRC 字段本身必须是 0。
 */
internal object OggStream : StreamCodec {

    override val id: String = "ogg"

    override fun extension(head: ByteArray): String =
        if (isOpus(head)) ".opus" else ".ogg"

    override fun matches(head: ByteArray): Boolean =
        head.size >= 4 && head.ascii(0, 4) == "OggS"

    private fun isOpus(head: ByteArray): Boolean {
        // OpusHead 是第一个 packet 的前 8 字节，在首页 payload 的起始处
        val page = runCatching { OggCodec.readPage(head, 0) }.getOrNull() ?: return false
        val off = page.payloadOffset
        return off + 8 <= head.size && head.ascii(off, 8) == "OpusHead"
    }

    override fun plan(head: ByteArray, contentLength: Long, bag: TagBag): StreamPlan? {
        val parsed = OggCodec.headerPackets(head) ?: return null
        val oldComment = parsed.packets.getOrNull(1) ?: return null
        val magic = OggCodec.commentPrefix(oldComment)
        val isVorbis = magic.size == 7 && (magic[0].toInt() and 0xFF) == 3
        val framing = if (isVorbis) byteArrayOf(1) else StreamIo.EMPTY
        val newComment = magic + VorbisComment.encode(bag) + framing

        // 头部页群重排成「一个包一页」：页数可能和原来不同，但 seq 从 0 连续发号，
        // 后面的音频页由 pumpBody 接着这个号继续编。
        val out = java.io.ByteArrayOutputStream()
        var seq = 0
        out.write(OggCodec.pageForPacket(parsed.packets[0], 0x02, 0L, parsed.serial, seq++))
        out.write(OggCodec.pageForPacket(newComment, 0x00, 0L, parsed.serial, seq++))
        for (i in 2 until parsed.packets.size) {
            out.write(OggCodec.pageForPacket(parsed.packets[i], 0x00, 0L, parsed.serial, seq++))
        }
        return StreamPlan(prefix = out.toByteArray(), skip = parsed.end.toLong())
    }

    /**
     * 逐页重编号 + 重算 CRC，**负载原样**。
     *
     * 页数由 [plan] 写进 prefix 的前导页数决定，这里从 prefix 里数出来接着往下编，
     * 不需要额外状态（也就不需要在 [StreamPlan] 上开一个格式私有字段）。
     */
    override fun pumpBody(
        input: InputStream,
        out: OutputStream,
        plan: StreamPlan,
        onBytes: (Long) -> Unit,
    ): Long {
        var seq = countPages(plan.prefix)
        var written = 0L
        val hdr = ByteArray(OggCodec.HEADER_SIZE)
        while (true) {
            if (!StreamIo.readFully(input, hdr)) break
            if (hdr.ascii(0, 4) != "OggS") break
            val segCount = hdr[26].toInt() and 0xFF
            val segs = ByteArray(segCount)
            if (segCount > 0 && !StreamIo.readFully(input, segs)) break
            var payloadSize = 0
            val segments = IntArray(segCount) { i ->
                val v = segs[i].toInt() and 0xFF
                payloadSize += v
                v
            }
            val payload = ByteArray(payloadSize)
            if (payloadSize > 0 && !StreamIo.readFully(input, payload)) break
            val page = OggCodec.Page(
                headerType = hdr[5].toInt() and 0xFF,
                granule = granuleOf(hdr),
                serial = hdr.u32le(14),
                seq = hdr.u32le(18),
                segments = segments,
                payloadOffset = 0,
                totalSize = OggCodec.HEADER_SIZE + segCount + payloadSize,
            )
            out.write(OggCodec.pageBytes(page, StreamIo.EMPTY, payload, seq++))
            written += page.totalSize
            onBytes(written)
        }
        // Ogg 没有后缀（标签全在头部页群里）
        return written
    }

    private fun countPages(bytes: ByteArray): Int {
        var off = 0
        var count = 0
        while (off + OggCodec.HEADER_SIZE <= bytes.size) {
            val page = OggCodec.readPage(bytes, off) ?: break
            count++
            off += page.totalSize
        }
        return count
    }

    private fun granuleOf(hdr: ByteArray): Long {
        var g = 0L
        for (i in 7 downTo 0) g = (g shl 8) or (hdr[6 + i].toLong() and 0xFF)
        return g
    }
}
