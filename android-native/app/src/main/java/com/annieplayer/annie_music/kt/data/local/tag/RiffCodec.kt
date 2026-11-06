package com.annieplayer.annie_music.kt.data.local.tag

import java.io.File
import java.io.RandomAccessFile

/**
 * WAV（RIFF）与 AIFF（FORM）的容器接线。
 *
 * 两者都能用 **ID3v2 的 `id3 ` / `ID3 ` chunk** 存元数据（ffmpeg、foobar、mp3tag 都这么写）。
 * 结构上只有两处差别：**RIFF 小端、AIFF 大端**，以及 `LIST/INFO` / `NAME·AUTH·ANNO` 这套老式纯文本块。
 *
 * ## 原地写
 * 标签 chunk 通常在文件尾 → 截掉旧 chunk + 追加新 chunk，只改文件头 4 字节的总长度，
 * **音频样本一个字节都不搬**。若旧 chunk 不在尾部（少见），退回全量重写。
 */
internal object RiffCodec : TagCodec {

    override val format: String = "RIFF"

    private const val TYPE_RIFF = "RIFF"
    private const val TYPE_FORM = "FORM"

    override fun sniff(head: ByteArray): Boolean {
        if (head.size < 8) return false
        val id = head.ascii(0, 4)
        return id == TYPE_RIFF || id == TYPE_FORM
    }

    private fun isRiff(head: ByteArray): Boolean = head.size >= 4 && head.ascii(0, 4) == TYPE_RIFF

    // ── 读 ──

    override fun read(file: File): TagBag? {
        if (!file.exists()) return null
        val info = walk(file) ?: return null
        val id3Offset = info.id3Offset
        if (id3Offset != null) {
            val payload = ByteArray(info.id3Size)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(id3Offset)
                if (raf.read(payload) != payload.size) return null
            }
            Id3v2Tag.decode(payload)?.takeIf { !it.isEmpty() }?.let { return it }
        }
        return info.legacy.takeIf { !it.isEmpty() }
    }

    private class Layout(
        val id3Offset: Long?,
        val id3Size: Int,
        val legacy: TagBag,
        val headerSize: Int,
        /** 存在老式文本块（`LIST/INFO` 或 `NAME/AUTH/ANNO`）—— 它们会和我们的 ID3 打架。 */
        val hasLegacyChunks: Boolean = false,
    )

    /** 走一遍 chunk 链，找 `id3 ` / `ID3 ` 与老式文本块。 */
    private fun walk(file: File): Layout? {
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(12)
            raf.seek(0)
            if (raf.read(head) != 12) return null
            val magic = head.ascii(0, 4)
            val riff = magic == TYPE_RIFF
            if (!riff && magic != TYPE_FORM) return null
            val text = LinkedHashMap<TagKey, String>()
            var id3Offset: Long? = null
            var id3Size = 0
            var hasLegacy = false
            var pos = 12L
            val end = raf.length()
            val ch = ByteArray(8)
            while (pos + 8 <= end) {
                raf.seek(pos)
                if (raf.read(ch) != 8) break
                val id = ch.ascii(0, 4)
                val size = if (riff) ch.u32le(4) else ch.u32be(4)
                if (size < 0) break
                when (id) {
                    "id3 ", "ID3 " -> {
                        id3Offset = pos + 8
                        id3Size = size
                    }
                    // WAV 老式：LIST(INFO) 里的小块；AIFF 老式：三个纯文本块
                    "LIST" -> if (riff && size >= 4) {
                        hasLegacy = true
                        readInfoChunks(raf, pos + 8, size, text)
                    }
                    "NAME" -> {
                        hasLegacy = true
                        readTextChunk(raf, pos + 8, size, TagKey.Title, text)
                    }
                    "AUTH" -> {
                        hasLegacy = true
                        readTextChunk(raf, pos + 8, size, TagKey.Artist, text)
                    }
                    "ANNO" -> {
                        hasLegacy = true
                        readTextChunk(raf, pos + 8, size, TagKey.Comment, text)
                    }
                }
                pos += 8 + size + (size % 2)
            }
            return Layout(id3Offset, id3Size, TagBag(text), 12, hasLegacy)
        }
    }

    private fun readInfoChunks(raf: RandomAccessFile, start: Long, size: Int, out: MutableMap<TagKey, String>) {
        val type = ByteArray(4)
        raf.seek(start)
        if (raf.read(type) != 4) return
        if (type.ascii(0, 4) != "INFO") return
        var p = start + 4
        val end = start + size
        val ch = ByteArray(8)
        while (p + 8 <= end) {
            raf.seek(p)
            if (raf.read(ch) != 8) return
            val id = ch.ascii(0, 4)
            val len = ch.u32le(4)
            if (len < 0 || p + 8 + len > end) return
            val key = when (id) {
                "INAM" -> TagKey.Title
                "IART" -> TagKey.Artist
                "IPRD" -> TagKey.Album
                "ICMT" -> TagKey.Comment
                "ICRD" -> TagKey.Year
                "IGNR" -> TagKey.Genre
                "ICOP" -> TagKey.Copyright
                "ISRC" -> TagKey.Isrc
                // ffmpeg 把音轨号写成 ITRK（RIFF INFO 没有官方的 track 键，各家各写一个）
                "ITRK", "IPRT", "TRCK" -> TagKey.Track
                else -> null
            }
            if (key != null) {
                val text = ByteArray(len)
                raf.seek(p + 8)
                if (raf.read(text) == len) {
                    val value = String(text, Charsets.ISO_8859_1).trimEnd('\u0000').trim()
                    if (value.isNotEmpty()) out.putIfAbsent(key, value)
                }
            }
            p += 8 + len + (len % 2)
        }
    }

    private fun readTextChunk(raf: RandomAccessFile, start: Long, size: Int, key: TagKey, out: MutableMap<TagKey, String>) {
        if (size <= 0 || size > 64 * 1024) return
        val text = ByteArray(size)
        raf.seek(start)
        if (raf.read(text) != size) return
        val value = String(text, Charsets.ISO_8859_1).trimEnd('\u0000').trim()
        if (value.isNotEmpty()) out.putIfAbsent(key, value)
    }

    // ── 写 ──

    override fun write(file: File, bag: TagBag, policy: WritePolicy): WriteResult {
        val layout = walk(file) ?: return WriteResult.fail(format, "不是 RIFF/AIFF 文件")
        val existing = runCatching { read(file) }.getOrNull()
        val merged = TagBag(
            text = (existing?.text.orEmpty() + bag.text).filterValues { it.isNotEmpty() },
            cover = bag.cover ?: existing?.cover,
            extra = (existing?.extra.orEmpty() + bag.extra).filterValues { it.isNotEmpty() },
        )
        val tag = Id3v2Tag.encode(merged)

        val head = file.readHead(4)
        val riff = head.size >= 4 && head.ascii(0, 4) == TYPE_RIFF

        // 旧 id3 chunk 在尾部 → 截掉再追加，音频不搬；否则全量重写。
        // 注意 id3Offset 是 **payload** 偏移，chunk 起点还得往前 8 字节。
        //
        // 有老式文本块（`LIST/INFO` / `NAME/AUTH/ANNO`）时必须走重写：留着它们会出现
        // **两个 title**（旧 chunk 一个、我们的 ID3 一个），不同播放器各取一个，用户看到的是旧的。
        val oldOffset = layout.id3Offset
        val oldChunkStart = oldOffset?.minus(8)
        val oldChunkEnd = oldOffset?.let { it + layout.id3Size + (layout.id3Size % 2) }
        val canTail = !layout.hasLegacyChunks &&
            (oldChunkStart == null || (oldChunkEnd ?: 0L) >= file.length() - 1)
        if (!canTail) return rewrite(file, tag, riff, layout)

        val appendAt = oldChunkStart ?: file.length()
        val chunkLen = 8L + tag.size + (tag.size % 2)
        val ok = runCatching {
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(appendAt)
                raf.seek(appendAt)
                raf.write((if (riff) "id3 " else "ID3 ").toByteArray(Charsets.ISO_8859_1))
                raf.write(if (riff) tag.size.toU32le() else tag.size.toU32be())
                raf.write(tag)
                if (tag.size % 2 != 0) raf.write(0)
                // 文件总长字段（RIFF 小端 / FORM 大端）
                raf.seek(4)
                val total = appendAt + chunkLen - 8
                raf.write(if (riff) total.toInt().toU32le() else total.toInt().toU32be())
                raf.channel.force(true)
            }
            true
        }.getOrDefault(false)
        return if (ok) WriteResult.ok(format, inPlace = true) else WriteResult.fail(format, "写 id3 chunk 失败")
    }

    /**
     * 重写：丢掉旧 id3 chunk 与老式文本块（ID3 是唯一事实来源），新 chunk 追加到尾部。
     */
    private fun rewrite(file: File, tag: ByteArray, riff: Boolean, layout: Layout): WriteResult {
        val parent = file.parentFile ?: return WriteResult.fail(format, "路径异常")
        val oldChunkStart = layout.id3Offset?.minus(8)
        val tmp = File(parent, "${file.name}.annietag.tmp")
        val ok = runCatching {
            RandomAccessFile(file, "r").use { src ->
                java.io.FileOutputStream(tmp).use { fos ->
                    val out = java.io.BufferedOutputStream(fos, 256 * 1024)
                    val head = ByteArray(12)
                    src.seek(0)
                    src.readFully(head)
                    out.write(head)
                    // 复制除旧 id3 / 老式文本块之外的全部 chunk
                    var pos = 12L
                    val end = src.length()
                    val ch = ByteArray(8)
                    while (pos + 8 <= end) {
                        src.seek(pos)
                        if (src.read(ch) != 8) break
                        val id = ch.ascii(0, 4)
                        val size = if (riff) ch.u32le(4) else ch.u32be(4)
                        if (size < 0) break
                        val total = 8L + size + (size % 2)
                        if (total > Int.MAX_VALUE) break
                        val skip = (oldChunkStart != null && pos == oldChunkStart) ||
                            id == "LIST" || id == "NAME" || id == "AUTH" || id == "ANNO"
                        if (!skip) {
                            val buf = ByteArray(total.toInt())
                            src.seek(pos)
                            if (src.read(buf) != buf.size) break
                            out.write(buf)
                        }
                        pos += total
                    }
                    out.write((if (riff) "id3 " else "ID3 ").toByteArray(Charsets.ISO_8859_1))
                    out.write(if (riff) tag.size.toU32le() else tag.size.toU32be())
                    out.write(tag)
                    if (tag.size % 2 != 0) out.write(0)
                    out.flush()
                    val total = fos.channel.size() - 8
                    fos.channel.position(4)
                    fos.channel.write(
                        java.nio.ByteBuffer.wrap(if (riff) total.toInt().toU32le() else total.toInt().toU32be()),
                    )
                }
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            true
        }.getOrDefault(false)
        runCatching { if (tmp.exists()) tmp.delete() }
        return if (ok) WriteResult.ok(format, inPlace = false) else WriteResult.fail(format, "重写失败")
    }
}
