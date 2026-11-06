package com.annieplayer.annie_music.kt.data.local.stream

import com.annieplayer.annie_music.kt.data.local.tag.Id3v2Tag
import com.annieplayer.annie_music.kt.data.local.tag.TagBag
import com.annieplayer.annie_music.kt.data.local.tag.ascii

/**
 * MP3：标签在**头部**（ID3v2），所以可以边收边写。
 *
 * ```
 * 源：  [旧 ID3v2 …][旧 ID3v2 …]  MPEG 帧 … [ID3v1 128B?]
 * 成品：[我们的 ID3v2]              MPEG 帧 …（ID3v1 丢掉）
 * ```
 *
 * 两个要点：
 * - 源里**可以叠多个 ID3v2**（有的转码/网盘工具会叠），要一路跳到底；
 * - 源里的 **ID3v1**（末尾 128 字节 `TAG`）必须裁掉，否则会出现
 *   「文件里是新标题、播放器显示旧标题」—— 不同实现取标签的优先级不一样。
 *
 * MPEG 帧一律原样透传，不解析、不改长度。
 */
internal object Mp3Stream : StreamCodec {

    override val id: String = "mp3"

    /** ID3v1 定长。 */
    private const val ID3V1_SIZE = 128

    override fun extension(head: ByteArray): String = ".mp3"

    override fun matches(head: ByteArray): Boolean {
        if (head.size >= 3 && head.ascii(0, 3) == "ID3") return true
        if (head.size < 3) return false
        if ((head[0].toInt() and 0xFF) != 0xFF) return false
        val b1 = head[1].toInt() and 0xFF
        if ((b1 and 0xE0) != 0xE0) return false
        // 只看 11 位同步太松（随便一段 0xFF 数据都能命中），再排掉版本/层的保留值
        val version = (b1 ushr 3) and 0x03
        val layer = (b1 ushr 1) and 0x03
        return version != 0x01 && layer != 0x00
    }

    override fun plan(head: ByteArray, contentLength: Long, bag: TagBag): StreamPlan? {
        val skip = StreamIo.leadingId3Size(head) ?: return null
        return StreamPlan(
            prefix = Id3v2Tag.encode(bag),
            skip = skip,
            tailTrim = TailTrim.of(ID3V1_SIZE, "TAG"),
        )
    }
}
