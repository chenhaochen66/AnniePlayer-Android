package com.annieplayer.annie_music.kt.data.local.tag

import java.io.File
import java.io.RandomAccessFile

/**
 * MP3 容器接线：ID3v2 在**文件头**（编解码在 [Id3v2Tag]）。
 *
 * ## 原地写
 * ID3 的 tag 尺寸写在 10 字节头里，帧区后面允许填充（padding）。只要「新标签 ≤ 旧标签」，
 * 就能把新帧写进旧区域、剩余补 0，**头尺寸字段保持不变** → 音频偏移一个字节都不动。
 * 只有装不下才退化成「新 ID3 + 复制音频」。
 *
 * TTA 不在这里：TTA 用的是尾巴上的 APEv2（见 [ApeCodec]），实测 ffmpeg 也是这么写的。
 */
internal object Id3v2Codec : TagCodec {

    override val format: String = "ID3v2"

    private const val ID3V1_SIZE = 128

    override fun sniff(head: ByteArray): Boolean {
        if (head.size >= 3 &&
            head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()
        ) {
            return true
        }
        // 裸 MP3：帧同步字
        return head.size >= 2 && (head[0].toInt() and 0xFF) == 0xFF && (head[1].toInt() and 0xE0) == 0xE0
    }

    // ── 读 ──

    override fun read(file: File): TagBag? {
        if (!file.exists() || file.length() < Id3v2Tag.HEADER_SIZE) return null
        var bag: TagBag? = null
        val total = Id3v2Tag.totalAtFileStart(file)
        if (total > 0) {
            val tag = ByteArray(total.toInt())
            RandomAccessFile(file, "r").use { raf ->
                if (raf.read(tag) != tag.size) return null
            }
            bag = Id3v2Tag.decode(tag)
        }
        // ID3v1 兜底：v2 里没有的字段才用
        val v1 = readId3v1(file) ?: return bag
        val base = bag ?: TagBag()
        return TagBag(base.text + v1.text, base.cover, base.extra)
    }

    private fun readId3v1(file: File): TagBag? {
        if (file.length() <= ID3V1_SIZE) return null
        val v1 = ByteArray(ID3V1_SIZE)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(file.length() - ID3V1_SIZE)
            if (raf.read(v1) != ID3V1_SIZE) return null
        }
        if (v1.ascii(0, 3) != "TAG") return null
        val out = LinkedHashMap<TagKey, String>()
        fun put(key: TagKey, value: String) {
            val t = value.trimEnd('\u0000', ' ').trim()
            if (t.isNotEmpty()) out[key] = t
        }
        put(TagKey.Title, v1.ascii(3, 30))
        put(TagKey.Artist, v1.ascii(33, 30))
        put(TagKey.Album, v1.ascii(63, 30))
        put(TagKey.Year, v1.ascii(93, 4))
        return if (out.isEmpty()) null else TagBag(out)
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
        val oldTotal = Id3v2Tag.totalAtFileStart(file)

        // ① 装得下 → 原地：头尺寸保持旧值，帧区后面补 0
        if (policy == WritePolicy.PreferInPlace && oldTotal > 0 && tag.size <= oldTotal) {
            return runCatching {
                val bodyLen = (oldTotal - Id3v2Tag.HEADER_SIZE).toInt()
                val patched = ByteArray(oldTotal.toInt())
                headerOf(bodyLen).copyInto(patched, 0)
                tag.copyOfRange(Id3v2Tag.HEADER_SIZE, tag.size).copyInto(patched, Id3v2Tag.HEADER_SIZE)
                patchInPlace(file, 0, patched)
                WriteResult.ok(format, inPlace = true)
            }.getOrElse { WriteResult.fail(format, "原地写失败: ${it.message}") }
        }

        // ② 全量重写：新 tag + 音频（跳过旧 tag 与尾部 ID3v1）
        val ok = writeAtomically(file) { out ->
            out.write(tag)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(oldTotal)
                val audioEnd = raf.length() - if (hasTrailingId3v1(raf)) ID3V1_SIZE.toLong() else 0L
                val buf = ByteArray(256 * 1024)
                while (raf.filePointer < audioEnd) {
                    val want = minOf(buf.size.toLong(), audioEnd - raf.filePointer).toInt()
                    val n = raf.read(buf, 0, want)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
            }
        }
        return if (ok) WriteResult.ok(format, inPlace = false) else WriteResult.fail(format, "重写失败")
    }

    private fun headerOf(bodyLen: Int): ByteArray {
        val h = ByteArray(Id3v2Tag.HEADER_SIZE)
        h[0] = 'I'.code.toByte(); h[1] = 'D'.code.toByte(); h[2] = '3'.code.toByte()
        h[3] = 4
        bodyLen.synchsafeBytes().copyInto(h, 6)
        return h
    }

    private fun hasTrailingId3v1(raf: RandomAccessFile): Boolean {
        if (raf.length() <= ID3V1_SIZE) return false
        raf.seek(raf.length() - ID3V1_SIZE)
        val tag = ByteArray(3)
        raf.readFully(tag)
        return tag.ascii(0, 3) == "TAG"
    }
}
