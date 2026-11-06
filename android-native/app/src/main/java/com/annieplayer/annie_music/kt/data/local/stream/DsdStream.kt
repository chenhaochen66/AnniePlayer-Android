package com.annieplayer.annie_music.kt.data.local.stream

import com.annieplayer.annie_music.kt.data.local.tag.Id3v2Tag
import com.annieplayer.annie_music.kt.data.local.tag.TagBag
import com.annieplayer.annie_music.kt.data.local.tag.ascii

/**
 * DSD 两个容器：`.dsf`（Sony）与 `.dff`（Philips DSDIFF）。
 *
 * 两者都用 **ID3v2**，而且都挂在**文件尾**，所以「加标签」天然不用搬音频 ——
 * 一首 5 分钟的 DSD256 有 800MB，整份重写是不可接受的。
 *
 * ```
 * .dsf   DSD <28>  totalSize  ptr=audioEnd   fmt … data … [ID3]   ← 指针和总长都要回填
 * .dff   FRM8 <size> DSD  FVER PROP DSD …  [ID3 ]                 ← FRM8 长度要回填
 * ```
 *
 * **两套字节序混在一起**（dsf 小端、dff 大端），所以这里的读长度函数故意分成两个，
 * 谁都不许通用 —— 混用一次就写坏文件。
 */
internal object DsdStream : StreamCodec {

    override val id: String = "dsd"

    /** DSF 固定头 28 字节。 */
    private const val DSF_HEADER = 28

    private const val DSF_TOTAL_OFFSET = 12L
    private const val DSF_PTR_OFFSET = 20L

    /** DSDIFF：`FRM8`(4) + size(8) + formType `DSD `(4) = 16，第一个 chunk 从这里开始。 */
    private const val DFF_HEADER = 16

    /** DSDIFF chunk 头：id(4) + size(8 大端)。 */
    private const val DFF_CHUNK_HEADER = 12

    override fun extension(head: ByteArray): String =
        if (head.ascii(0, 4) == "DSD ") ".dsf" else ".dff"

    override fun matches(head: ByteArray): Boolean {
        if (head.size < 4) return false
        val magic = head.ascii(0, 4)
        return magic == "DSD " || magic == "FRM8"
    }

    override fun plan(head: ByteArray, contentLength: Long, bag: TagBag): StreamPlan? {
        val tag = Id3v2Tag.encode(bag)
        return if (head.ascii(0, 4) == "DSD ") dsfPlan(head, tag) else dffPlan(head, contentLength, tag)
    }

    /**
     * DSF：`data` chunk 结束的地方就是音频结束的地方。
     *
     * 头里的 `ptr` 字段本来就指向 ID3 起点，所以「源里有没有旧标签」看一眼就知道 ——
     * 有的话直接用 `ptr` 当截断点，旧的连读都不用读。
     */
    private fun dsfPlan(head: ByteArray, tag: ByteArray): StreamPlan? {
        if (head.size < DSF_HEADER) return null
        val ptr = u64le(head, DSF_PTR_OFFSET.toInt())
        val audioEnd: Long = if (ptr > 0 && ptr < Long.MAX_VALUE) {
            ptr
        } else {
            // 没有旧标签：走一遍 chunk 链找 data 的结束位置
            var pos = DSF_HEADER
            var end = -1L
            while (pos + 12 <= head.size) {
                val id = head.ascii(pos, 4)
                val size = u64le(head, pos + 4)
                if (size < 0) return null
                if (id == "data") {
                    end = pos + 12L + size
                    break
                }
                if (size > Int.MAX_VALUE) return null
                pos += 12 + size.toInt()
            }
            end
        }
        if (audioEnd < DSF_HEADER) return null

        return StreamPlan(
            bodyLimit = audioEnd,
            suffix = tag,
            patches = listOf(
                // ID3 起点：音频结束位置，plan 阶段就已经确定（body 是逐字节透传的）
                StreamPatch(DSF_PTR_OFFSET) { StreamIo.u64le(audioEnd) },
                // 全文件长度（少了它播放器会认为文件被截断）
                StreamPatch(DSF_TOTAL_OFFSET) { total -> StreamIo.u64le(total) },
            ),
        )
    }

    /**
     * DSDIFF：`DSD ` chunk 很大（几百 MB），头部窗口里只能读到它的 chunk 头，
     * 所以靠 [contentLength] 判断「它是不是最后一块」—— 是才敢把 `ID3 ` 追在后面。
     */
    private fun dffPlan(head: ByteArray, contentLength: Long, tag: ByteArray): StreamPlan? {
        var pos = DFF_HEADER
        while (pos + DFF_CHUNK_HEADER <= head.size) {
            val id = head.ascii(pos, 4)
            val size = u64be(head, pos + 4)
            if (size < 0) return null
            val end = pos + DFF_CHUNK_HEADER + size + (size % 2)
            when (id) {
                "DSD " -> {
                    // 音频块：只有它是最后一块（结束位置 = 文件长度）才能安全追加；
                    // 它后面还有别的 chunk 的话，那可能是旧 `ID3 `，流里没法先读再回头删
                    if (contentLength <= 0 || end != contentLength) return null
                    return StreamPlan(suffix = id3Chunk(tag), patches = dffPatch())
                }
                "ID3 " -> {
                    // 源里的标签 chunk 正好是最后一块：截掉它（连同它自己的补齐字节）
                    if (contentLength > 0 && end != contentLength) return null
                    return StreamPlan(bodyLimit = pos.toLong(), suffix = id3Chunk(tag), patches = dffPatch())
                }
            }
            if (contentLength > 0 && end > contentLength) return null
            if (end > head.size) return null
            pos = end.toInt()
        }
        return null
    }

    private fun id3Chunk(tag: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write("ID3 ".toByteArray(Charsets.ISO_8859_1))
        out.write(StreamIo.u64be(tag.size.toLong()))
        out.write(tag)
        // DSDIFF：奇数长度补 1 字节，且这个补齐字节**不计入** size
        if (tag.size % 2 != 0) out.write(0)
        return out.toByteArray()
    }

    /** `FRM8` 的总长度字段（offset 4，8 字节大端）= 全文件长度 - 12。 */
    private fun dffPatch(): List<StreamPatch> = listOf(
        StreamPatch(4) { total -> StreamIo.u64be(total - 12) },
    )

    // ── 字节序（dsf 小端 / dff 大端，故意分开写，防止混用） ──

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
}
