package com.annieplayer.annie_music.kt.data.local.stream

import com.annieplayer.annie_music.kt.data.local.tag.Id3v2Tag
import com.annieplayer.annie_music.kt.data.local.tag.TagBag
import com.annieplayer.annie_music.kt.data.local.tag.ascii
import com.annieplayer.annie_music.kt.data.local.tag.toU32be
import com.annieplayer.annie_music.kt.data.local.tag.toU32le
import com.annieplayer.annie_music.kt.data.local.tag.u32be
import com.annieplayer.annie_music.kt.data.local.tag.u32le
import java.io.InputStream
import java.io.OutputStream

/**
 * WAV（RIFF）/ AIFF（FORM）：标签是尾部的 `id3 ` / `ID3 ` chunk，所以可以边收边写。
 *
 * ```
 * 源：  RIFF <size> WAVE  fmt … LIST/INFO … data ……
 * 成品：RIFF <重算> WAVE  fmt …              data ……  <补齐> id3 <我们的标签>
 *                             ↑ 旧 LIST/INFO、旧 id3 全部丢掉
 * ```
 *
 * 两个要点：
 * - **旧元数据块必须逐块筛掉，不能只截尾**：ffmpeg 把 `LIST/INFO` 写在 `data`
 *   **前面**，光按「音频块之后截断」处理会留下两套标题 —— ffprobe / 各家播放器各取一个，
 *   用户看到的是旧的。所以这里的 [pumpBody] 是**逐块**复制的，不是整段透传。
 * - **总长要回填**：RIFF/FORM 的第二个字段是全文件长度，流式写入时只有最后一个字节落地
 *   才知道，所以先原样落地、结束时再按 `total - 8` 回填。
 *
 * `data` 长度写成 0 / 0xFFFFFFFF 的「边录边写」wav 也能处理 —— 靠 [contentLength] 定边界。
 *
 * 代价：`LIST` 里如果是 `adtl` 标记（cue 点用的），会被一起丢掉。下载来的文件里没有这种块；
 * `AnnieTag` 的重写路径对老式文本块也是同样的处理，两条路一致。
 */
internal object RiffStream : StreamCodec {

    override val id: String = "riff"

    /** 头部 12 字节：`RIFF<size>WAVE` / `FORM<size>AIFF`。 */
    private const val HEADER = 12

    /** 老式文本块 / 旧 ID3：留下就会和新标签抢同名键。 */
    private val DROP = setOf("LIST", "NAME", "AUTH", "ANNO", "id3 ", "ID3 ")

    override fun extension(head: ByteArray): String =
        if (head.ascii(0, 4) == "RIFF") ".wav" else ".aiff"

    override fun matches(head: ByteArray): Boolean {
        if (head.size < HEADER) return false
        val magic = head.ascii(0, 4)
        return magic == "RIFF" || magic == "FORM"
    }

    private fun isRiff(head: ByteArray): Boolean = head.ascii(0, 4) == "RIFF"

    private fun audioChunkId(riff: Boolean): String = if (riff) "data" else "SSND"

    private fun sizeAt(buf: ByteArray, off: Int, riff: Boolean): Long =
        if (riff) {
            buf.u32le(off).toLong() and 0xFFFFFFFFL
        } else {
            buf.u32be(off).toLong() and 0xFFFFFFFFL
        }

    override fun plan(head: ByteArray, contentLength: Long, bag: TagBag): StreamPlan? {
        val riff = isRiff(head)
        val audioId = audioChunkId(riff)
        // 只验证「确实有音频块」；真正的逐块筛选在 pumpBody 里做（那时才知道实时长度）
        var pos = HEADER
        var found = false
        while (pos + 8 <= head.size) {
            val id = head.ascii(pos, 4)
            val size = sizeAt(head, pos + 4, riff)
            if (id == audioId) {
                found = true
                break
            }
            if (size > Int.MAX_VALUE) return null
            pos += 8 + size.toInt() + (size.toInt() % 2)
        }
        if (!found) return null
        // 边录边写的 wav 会把 data 长度写成 0 / 0xFFFFFFFF，只有拿到总长才切得准
        if (contentLength <= 0 && audioSizeLooksBroken(head, pos, riff)) return null

        val tag = Id3v2Tag.encode(bag)
        val suffix = java.io.ByteArrayOutputStream()
        suffix.write((if (riff) "id3 " else "ID3 ").toByteArray(Charsets.ISO_8859_1))
        suffix.write(if (riff) tag.size.toU32le() else tag.size.toU32be())
        suffix.write(tag)
        // 载荷奇数长度补 1 字节（不计入 chunk 大小）
        if (tag.size % 2 != 0) suffix.write(0)

        return StreamPlan(
            suffix = suffix.toByteArray(),
            patches = listOf(
                StreamPatch(4) { total ->
                    val v = (total - 8).toInt()
                    if (riff) v.toU32le() else v.toU32be()
                },
            ),
        )
    }

    private fun audioSizeLooksBroken(head: ByteArray, pos: Int, riff: Boolean): Boolean {
        if (pos + 8 > head.size) return true
        val size = sizeAt(head, pos + 4, riff)
        return size == 0L || size == 0xFFFFFFFFL
    }

    /**
     * 逐块复制：丢掉旧元数据块，音频块整块复制后**立刻收笔**（后面的尾巴不要），
     * 然后按输出偏移的奇偶补一个对齐字节，最后追加我们的 `id3 ` chunk。
     */
    override fun pumpBody(
        input: InputStream,
        out: OutputStream,
        plan: StreamPlan,
        onBytes: (Long) -> Unit,
    ): Long {
        var written = 0L
        val header = ByteArray(HEADER)
        if (!StreamIo.readFully(input, header)) return 0
        val riff = header.ascii(0, 4) == "RIFF"
        val audioId = audioChunkId(riff)
        out.write(header)
        written += HEADER

        val chunkHeader = ByteArray(8)
        val buf = ByteArray(StreamIo.BUFFER)
        while (StreamIo.readFully(input, chunkHeader)) {
            val id = chunkHeader.ascii(0, 4)
            val declared = if (riff) {
                chunkHeader.u32le(4).toLong() and 0xFFFFFFFFL
            } else {
                chunkHeader.u32be(4).toLong() and 0xFFFFFFFFL
            }
            val isAudio = id == audioId
            // 音频块长度可能是「占位值」，那就一路读到 EOF
            val size = if (isAudio && (declared == 0L || declared == 0xFFFFFFFFL)) -1L else declared

            if (!isAudio && id in DROP) {
                // 旧元数据：连同它的补齐字节一起跳过
                if (size < 0) break
                StreamIo.skipFully(input, size + (size % 2))
                continue
            }

            out.write(chunkHeader)
            written += 8
            var left = size
            while (left != 0L) {
                val want = if (left < 0) buf.size else minOf(buf.size.toLong(), left).toInt()
                val n = input.read(buf, 0, want)
                if (n <= 0) break
                out.write(buf, 0, n)
                written += n
                if (left > 0) left -= n
                onBytes(written)
            }
            if (size > 0 && size % 2 != 0L && !StreamIo.readFully(input, ByteArray(1))) break
            if (isAudio) break
        }

        // chunk 必须落在偶数偏移上
        if (written % 2 != 0L) {
            out.write(0)
            written += 1
        }
        out.write(plan.suffix)
        written += plan.suffix.size
        return written
    }
}
