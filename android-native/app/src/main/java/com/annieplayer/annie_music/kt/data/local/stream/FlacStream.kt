package com.annieplayer.annie_music.kt.data.local.stream

import com.annieplayer.annie_music.kt.data.local.tag.FlacCodec
import com.annieplayer.annie_music.kt.data.local.tag.TagBag
import com.annieplayer.annie_music.kt.data.local.tag.VorbisComment
import com.annieplayer.annie_music.kt.data.local.tag.ascii

/**
 * FLAC：标签在**头部块链**里，所以可以边收边写。
 *
 * ```
 * 源：  fLaC [STREAMINFO][PADDING?][SEEKTABLE?][PICTURE?][VORBIS_COMMENT?]  帧 …
 * 成品：fLaC [STREAMINFO][VORBIS_COMMENT][PICTURE?][PADDING 8K]            帧 …
 * ```
 *
 * 两条硬规矩：
 * - **`STREAMINFO` 必须是第一个块**，而且只能有一个 —— 所以原样搬过来，不重建
 *   （里面还有整个音频流的 MD5，重建必错）；
 * - `SEEKTABLE` / `APPLICATION` / `CUESHEET` / 旧 `PADDING` 全部丢掉：它们要么对播放无影响，
 *   要么依赖旧的文件偏移（`CUESHEET` 里带样本偏移，挪了位置反而错）。
 *   `HARD_PADDING` 就是给「以后改注释」预留的，正好由我们重写。
 *
 * 末尾留 8KB `PADDING` 是**故意的**：以后在本地曲库里编辑标签时，`AnnieTag` 能整段原地写，
 * 一次重写都不用。8KB 相对于一首几十 MB 的无损是 0.03%。
 */
internal object FlacStream : StreamCodec {

    override val id: String = "flac"

    private const val TYPE_STREAMINFO = 0
    private const val TYPE_PADDING = 1
    private const val TYPE_VORBIS_COMMENT = 4
    private const val TYPE_PICTURE = 6

    /** `STREAMINFO` 固定 34 字节。 */
    private const val STREAMINFO_SIZE = 34

    /** 给以后原地编辑预留的空间。 */
    private const val PADDING = 8 * 1024

    override fun extension(head: ByteArray): String = ".flac"

    override fun matches(head: ByteArray): Boolean =
        head.size >= 4 && head.ascii(0, 4) == "fLaC"

    override fun plan(head: ByteArray, contentLength: Long, bag: TagBag): StreamPlan? {
        if (!matches(head)) return null
        var pos = 4
        var streamInfo: ByteArray? = null
        var last = false
        while (!last) {
            if (pos + 4 > head.size) return null
            val flagType = head[pos].toInt() and 0xFF
            last = (flagType and 0x80) != 0
            val type = flagType and 0x7F
            val len = ((head[pos + 1].toInt() and 0xFF) shl 16) or
                ((head[pos + 2].toInt() and 0xFF) shl 8) or
                (head[pos + 3].toInt() and 0xFF)
            if (pos + 4 + len > head.size) return null
            if (type == TYPE_STREAMINFO && streamInfo == null) {
                streamInfo = head.copyOfRange(pos + 4, pos + 4 + len)
            }
            pos += 4 + len
        }
        val si = streamInfo ?: return null
        if (si.size != STREAMINFO_SIZE) return null

        val newHead = java.io.ByteArrayOutputStream(PADDING + si.size + 4096)
        newHead.write('f'.code)
        newHead.write('L'.code)
        newHead.write('a'.code)
        newHead.write('C'.code)
        writeBlock(newHead, TYPE_STREAMINFO, si)
        writeBlock(newHead, TYPE_VORBIS_COMMENT, VorbisComment.encode(bag))
        bag.cover?.let { writeBlock(newHead, TYPE_PICTURE, FlacCodec.picturePayload(it)) }
        // 最后一个块要置 last 位
        writeBlock(newHead, TYPE_PADDING, ByteArray(PADDING), last = true)

        return StreamPlan(prefix = newHead.toByteArray(), skip = pos.toLong())
    }

    private fun writeBlock(
        out: java.io.ByteArrayOutputStream,
        type: Int,
        payload: ByteArray,
        last: Boolean = false,
    ) {
        out.write((if (last) 0x80 else 0) or (type and 0x7F))
        out.write((payload.size ushr 16) and 0xFF)
        out.write((payload.size ushr 8) and 0xFF)
        out.write(payload.size and 0xFF)
        out.write(payload)
    }
}
