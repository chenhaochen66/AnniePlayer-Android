package com.annieplayer.annie_music.kt.data.local.tag

import java.io.File
import java.io.RandomAccessFile

/**
 * FLAC 容器接线：Vorbis comment（编解码在 [VorbisComment]）+ PICTURE 块。
 *
 * ## 原地写
 * FLAC 的元数据块链在文件头，`PADDING`（type 1）天生就是给「以后改注释」用的：
 * 新块链 ≤ 旧块链时用一个 PADDING 补足差额 → 块链总长不变 → **音频帧偏移不变**，一个字节都不搬。
 *
 * ## 不碰的东西
 * `STREAMINFO` 必须留在首块；`SEEKTABLE` / `APPLICATION` / `CUESHEET` 原样保留。
 */
internal object FlacCodec : TagCodec {

    override val format: String = "FLAC"

    private const val TYPE_STREAMINFO = 0
    private const val TYPE_PADDING = 1
    private const val TYPE_VORBIS_COMMENT = 4
    private const val TYPE_PICTURE = 6

    private val MAGIC = byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte())

    override fun sniff(head: ByteArray): Boolean =
        head.size >= 4 && head[0] == MAGIC[0] && head[1] == MAGIC[1] && head[2] == MAGIC[2] && head[3] == MAGIC[3]

    private class Block(val type: Int, val payload: ByteArray)

    private class Parsed(val blocks: List<Block>, val audioStart: Long) {
        val metaTotal: Int = blocks.sumOf { 4 + it.payload.size }
    }

    override fun read(file: File): TagBag? {
        val parsed = readBlocks(file) ?: return null
        var bag = TagBag()
        var cover: CoverBytes? = null
        for (block in parsed.blocks) {
            when (block.type) {
                TYPE_VORBIS_COMMENT -> {
                    val c = VorbisComment.decode(block.payload)
                    bag = TagBag(bag.text + c.text, null, bag.extra + c.extra)
                }
                TYPE_PICTURE -> if (cover == null) cover = parsePicture(block.payload)
            }
        }
        val result = TagBag(bag.text, cover ?: bag.cover, bag.extra)
        return if (result.isEmpty()) null else result
    }

    private fun readBlocks(file: File): Parsed? {
        if (!file.exists()) return null
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(4)
            if (raf.read(head) != 4 || !sniff(head)) return null
            val blocks = ArrayList<Block>()
            while (true) {
                val bh = ByteArray(4)
                if (raf.read(bh) != 4) return null
                val flagType = bh[0].toInt() and 0xFF
                val len = ((bh[1].toInt() and 0xFF) shl 16) or
                    ((bh[2].toInt() and 0xFF) shl 8) or (bh[3].toInt() and 0xFF)
                val payload = ByteArray(len)
                if (len > 0 && raf.read(payload) != len) return null
                blocks.add(Block(flagType and 0x7F, payload))
                if (flagType and 0x80 != 0) break
            }
            return Parsed(blocks, raf.filePointer)
        }
    }

    override fun write(file: File, bag: TagBag, policy: WritePolicy): WriteResult {
        val parsed = readBlocks(file) ?: return WriteResult.fail(format, "不是 FLAC 文件")
        val existing = runCatching { read(file) }.getOrNull()
        val merged = TagBag(
            text = (existing?.text.orEmpty() + bag.text).filterValues { it.isNotEmpty() },
            cover = bag.cover ?: existing?.cover,
            extra = (existing?.extra.orEmpty() + bag.extra).filterValues { it.isNotEmpty() },
        )

        val kept = ArrayList<Block>()
        for (block in parsed.blocks) {
            when (block.type) {
                TYPE_VORBIS_COMMENT, TYPE_PADDING -> Unit
                TYPE_PICTURE -> if (merged.cover == null) kept.add(block)
                else -> kept.add(block)
            }
        }
        val streamInfo = kept.firstOrNull { it.type == TYPE_STREAMINFO }
            ?: return WriteResult.fail(format, "缺少 STREAMINFO")
        kept.remove(streamInfo)

        val ordered = ArrayList<Block>()
        ordered.add(streamInfo)
        ordered.add(Block(TYPE_VORBIS_COMMENT, VorbisComment.encode(merged)))
        merged.cover?.let { ordered.add(Block(TYPE_PICTURE, encodePicture(it))) }
        ordered.addAll(kept)

        val baseTotal = ordered.sumOf { 4 + it.payload.size }

        if (policy == WritePolicy.PreferInPlace) {
            val diff = parsed.metaTotal - baseTotal
            if (diff >= 4) {
                val padded = ArrayList(ordered)
                padded.add(Block(TYPE_PADDING, ByteArray(diff - 4)))
                val bytes = encodeChain(padded)
                if (bytes.size == parsed.metaTotal) {
                    return runCatching {
                        patchInPlace(file, 4, bytes)
                        WriteResult.ok(format, inPlace = true)
                    }.getOrElse { WriteResult.fail(format, "原地写失败: ${it.message}") }
                }
            }
        }

        val ok = writeAtomically(file) { out ->
            out.write(MAGIC)
            out.write(encodeChain(ordered))
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(parsed.audioStart)
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = raf.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
            }
        }
        return if (ok) WriteResult.ok(format, inPlace = false) else WriteResult.fail(format, "重写失败")
    }

    private fun encodeChain(blocks: List<Block>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for ((index, block) in blocks.withIndex()) {
            val last = index == blocks.size - 1
            out.write((if (last) 0x80 else 0) or (block.type and 0x7F))
            val len = block.payload.size
            out.write((len ushr 16) and 0xFF)
            out.write((len ushr 8) and 0xFF)
            out.write(len and 0xFF)
            out.write(block.payload)
        }
        return out.toByteArray()
    }

    // ── PICTURE 块：**全文大端**（Vorbis comment 是小端，别混） ──

    private fun parsePicture(payload: ByteArray): CoverBytes? {
        if (payload.size < 8) return null
        var p = 4
        val mimeLen = payload.u32be(p); p += 4
        if (mimeLen < 0 || p + mimeLen > payload.size) return null
        val mime = String(payload, p, mimeLen, Charsets.ISO_8859_1); p += mimeLen
        if (p + 4 > payload.size) return null
        val descLen = payload.u32be(p); p += 4
        if (descLen < 0 || p + descLen > payload.size) return null
        p += descLen
        if (p + 16 > payload.size) return null
        p += 16
        if (p + 4 > payload.size) return null
        val dataLen = payload.u32be(p); p += 4
        if (dataLen <= 0 || p + dataLen > payload.size) return null
        return CoverBytes(payload.copyOfRange(p, p + dataLen), if (mime.isEmpty()) "image/jpeg" else mime)
    }

    /** 供流式注入复用：FLAC `PICTURE` 块的 payload（**大端**，和 Vorbis comment 相反）。 */
    internal fun picturePayload(cover: CoverBytes): ByteArray = encodePicture(cover)

    private fun encodePicture(cover: CoverBytes): ByteArray {
        val mime = cover.mime.toByteArray(Charsets.ISO_8859_1)
        val out = java.io.ByteArrayOutputStream()
        out.write(3.toU32be()) // front cover
        out.write(mime.size.toU32be())
        out.write(mime)
        out.write(0.toU32be())
        out.write(0.toU32be())
        out.write(0.toU32be())
        out.write(0.toU32be())
        out.write(0.toU32be())
        out.write(cover.bytes.size.toU32be())
        out.write(cover.bytes)
        return out.toByteArray()
    }
}
