package com.annieplayer.annie_music.kt.data.local.tag

import java.io.File
import java.io.RandomAccessFile

/**
 * DSD 容器接线：`.dsf`（Sony）与 `.dff`（Philips DSDIFF）。
 *
 * 两者都用 **ID3v2**（编解码在 [Id3v2Tag]），只是挂的位置不同 —— 而位置恰好都在**文件尾**，
 * 所以「改标签」永远不搬音频。这对几百 MB 的 DSD 是数量级的差别：
 * 5 分钟 DSD64 ≈ 200MB、DSD256 ≈ 800MB，整文件重封装要搬 0.4~1.6GB，这里只写几百 KB。
 *
 * ```
 * .dsf
 *   0   "DSD "                       4
 *   4   chunk size = 28              8  (小端)
 *   12  total file size              8  (小端)  ← 追加标签后必须更新
 *   20  pointer to ID3v2             8  (小端)  ← 没有则 0
 *   28  "fmt " chunk … "data" chunk … [ID3v2] 在 pointer 处
 *
 * .dff
 *   "FRM8" + size(8 大端) + "DSD " + 若干 chunk（ID + size 8 大端，奇数长度补 1 字节）
 *   [ "ID3 " chunk ]  payload 就是 ID3v2，通常在文件尾
 * ```
 *
 * **两套字节序混在一起**（dsf 小端、dff 大端）正是这类格式最容易翻车的点。
 */
internal object DsdCodec : TagCodec {

    override val format: String = "DSD"

    private const val DSF_HEADER = 28L
    private const val DSF_PTR_OFFSET = 20L
    private const val DSF_TOTAL_OFFSET = 12L

    override fun sniff(head: ByteArray): Boolean {
        if (head.size < 4) return false
        return head.ascii(0, 4) == "DSD " || head.ascii(0, 4) == "FRM8"
    }

    private fun isDsf(head: ByteArray): Boolean = head.size >= 4 && head.ascii(0, 4) == "DSD "

    // ── 读 ──

    override fun read(file: File): TagBag? {
        if (!file.exists()) return null
        val head = file.readHead(8)
        val tagBytes = when {
            isDsf(head) -> readDsfTag(file)
            head.size >= 4 && head.ascii(0, 4) == "FRM8" -> readDffTag(file)
            else -> null
        } ?: return null
        return Id3v2Tag.decode(tagBytes)?.takeIf { !it.isEmpty() }
    }

    private fun readDsfTag(file: File): ByteArray? {
        if (file.length() <= DSF_HEADER) return null
        val h = ByteArray(DSF_HEADER.toInt())
        RandomAccessFile(file, "r").use { raf ->
            if (raf.read(h) != h.size) return null
        }
        val ptr = u64le(h, DSF_PTR_OFFSET.toInt())
        if (ptr <= 0 || ptr >= file.length()) return null
        val len = (file.length() - ptr).toInt()
        if (len <= Id3v2Tag.HEADER_SIZE) return null
        return ByteArray(len).also { buf ->
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(ptr)
                if (raf.read(buf) != len) return null
            }
        }
    }

    private fun readDffTag(file: File): ByteArray? {
        val located = locateDffId3(file) ?: return null
        val (offset, size) = located
        if (size <= Id3v2Tag.HEADER_SIZE) return null
        return ByteArray(size).also { buf ->
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                if (raf.read(buf) != size) return null
            }
        }
    }

    // ── 写 ──

    override fun write(file: File, bag: TagBag, policy: WritePolicy): WriteResult {
        if (!file.exists()) return WriteResult.fail(format, "文件不存在")
        val existing = runCatching { read(file) }.getOrNull()
        val merged = TagBag(
            text = (existing?.text.orEmpty() + bag.text).filterValues { it.isNotEmpty() },
            cover = bag.cover ?: existing?.cover,
            extra = (existing?.extra.orEmpty() + bag.extra).filterValues { it.isNotEmpty() },
        )
        val tag = Id3v2Tag.encode(merged)
        val head = file.readHead(8)
        return when {
            isDsf(head) -> writeDsf(file, tag)
            head.size >= 4 && head.ascii(0, 4) == "FRM8" -> writeDff(file, tag)
            else -> WriteResult.fail(format, "不是 DSD 文件")
        }
    }

    private fun writeDsf(file: File, tag: ByteArray): WriteResult {
        val h = ByteArray(DSF_HEADER.toInt())
        RandomAccessFile(file, "r").use { raf ->
            if (raf.read(h) != h.size) return WriteResult.fail(format, "DSF 头太短")
        }
        val ptr = u64le(h, DSF_PTR_OFFSET.toInt())
        val audioEnd = if (ptr in 1 until file.length()) ptr else file.length()
        val newTotal = audioEnd + tag.size
        val ok = runCatching {
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(audioEnd)
                raf.seek(audioEnd)
                raf.write(tag)
                // 指针 + 总长度都得跟着改，否则播放器找不到标签 / 认为文件被截断
                raf.seek(DSF_PTR_OFFSET)
                raf.write(u64leBytes(audioEnd))
                raf.seek(DSF_TOTAL_OFFSET)
                raf.write(u64leBytes(newTotal))
                raf.channel.force(true)
            }
            true
        }.getOrDefault(false)
        return if (ok) WriteResult.ok(format, inPlace = true) else WriteResult.fail(format, "DSF 写失败")
    }

    private fun writeDff(file: File, tag: ByteArray): WriteResult {
        val located = locateDffId3(file)
        val audioEnd = located?.first ?: file.length()
        val chunkLen = 12L + tag.size + (tag.size % 2)
        val newTotal = audioEnd + chunkLen
        val ok = runCatching {
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(audioEnd)
                raf.seek(audioEnd)
                raf.write("ID3 ".toByteArray(Charsets.ISO_8859_1))
                raf.write(u64beBytes(tag.size.toLong()))
                raf.write(tag)
                if (tag.size % 2 != 0) raf.write(0) // DSDIFF 奇数长度补 1 字节（不计入 size）
                // FRM8 的总长度（8 字节大端，位于 offset 4）
                raf.seek(4)
                raf.write(u64beBytes(newTotal - 12))
                raf.channel.force(true)
            }
            true
        }.getOrDefault(false)
        return if (ok) WriteResult.ok(format, inPlace = true) else WriteResult.fail(format, "DFF 写失败")
    }

    /** 找 DFF 里的 `ID3 ` chunk：返回 (payload 偏移, payload 长度)。 */
    private fun locateDffId3(file: File): Pair<Long, Int>? {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 16) return null
            val head = ByteArray(16)
            raf.seek(0)
            if (raf.read(head) != 16) return null
            if (head.ascii(0, 4) != "FRM8") return null
            // FRM8(4) + size(8) + formType "DSD "(4) = 16 字节，第一个 chunk 从这里开始
            var pos = 16L
            val end = raf.length()
            val ch = ByteArray(12)
            while (pos + 12 <= end) {
                raf.seek(pos)
                if (raf.read(ch) != 12) return null
                val id = ch.ascii(0, 4)
                val size = u64be(ch, 4)
                if (size < 0) return null
                if (id == "ID3 ") {
                    if (size > Int.MAX_VALUE) return null
                    return (pos + 12) to size.toInt()
                }
                pos += 12 + size + (size % 2)
            }
        }
        return null
    }

    // ── 字节序工具（dsf 小端 / dff 大端，故意分开写，防止混用） ──

    private fun u64le(buf: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (buf[off + i].toLong() and 0xFF)
        return v
    }

    private fun u64leBytes(v: Long): ByteArray =
        ByteArray(8) { i -> ((v ushr (8 * i)) and 0xFF).toByte() }

    private fun u64be(buf: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (buf[off + i].toLong() and 0xFF)
        return v
    }

    private fun u64beBytes(v: Long): ByteArray =
        ByteArray(8) { i -> ((v ushr (8 * (7 - i))) and 0xFF).toByte() }
}
