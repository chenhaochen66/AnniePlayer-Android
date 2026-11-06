package com.annieplayer.annie_music.kt.data.local.stream

import com.annieplayer.annie_music.kt.data.local.tag.Mp4Codec
import com.annieplayer.annie_music.kt.data.local.tag.TagBag
import com.annieplayer.annie_music.kt.data.local.tag.ascii
import com.annieplayer.annie_music.kt.data.local.tag.u32be

/**
 * MP4 / M4A（`.m4a`）：标签在 `moov/udta` 里，位置取决于封装工具。
 *
 * ```
 * 源：  ftyp  moov(旧标签)  mdat…
 * 成品：ftyp  free(等长)     mdat…  moov(新标签)
 *                  ↑ 只把这 4 字节的盒类型改成 free，mdat 一个字节都不动
 * ```
 *
 * ## 为什么不能像 mp3/flac 那样换个头部
 * MP4 的 `stco` 是**绝对偏移**：只要 `moov` 变长，后面的 `mdat` 就整体后移，所有偏移全废，
 * 只能整份重写 —— 这就是 ffmpeg / TagLib 改 m4a 标签慢的根本原因。
 *
 * 这里的做法是「**旧 moov 原地作废 + 新 moov 追加到尾部**」：`moov` 的字节留在原地
 * （所以 `mdat` 位置不变、`stco` 全部有效），只把它 8 字节盒头里的类型改成 `free`
 * （读取方会整块跳过），新的 moov 连带标签追加在文件末尾。代价只是多出一个
 * 「旧 moov 大小」的空洞（几十 KB），换来音频零搬运。
 *
 * 只有一处限制：`moov` 必须落在头部预读窗口里（faststart 的 mp4、以及 moov 在前的 m4a）。
 * moov 在文件尾的文件拿不到它的字节，只能返回 null 走落盘路径。
 *
 * 注意：**Apple 下载不走这里** —— 那边的 `ftyp + mdat + moov` 是我们自己拼的，
 * 标签直接进 moov（见 `buildAppleTrailingMoov(udta = …)`），比这里还少一次回填。
 */
internal object Mp4Stream : StreamCodec {

    override val id: String = "mp4"

    private const val BOX_HEADER = 8

    override fun extension(head: ByteArray): String = ".m4a"

    override fun matches(head: ByteArray): Boolean =
        head.size >= 8 && head.ascii(4, 4) == "ftyp"

    override fun plan(head: ByteArray, contentLength: Long, bag: TagBag): StreamPlan? {
        var pos = 0
        var moovAt = -1
        var moovSize = 0
        while (pos + BOX_HEADER <= head.size) {
            var size = head.u32be(pos).toLong()
            val type = head.ascii(pos + 4, 4)
            if (size == 1L) {
                // 64 位长度：头 16 字节
                if (pos + 16 > head.size) return null
                size = (head.u32be(pos + 8).toLong() shl 32) or (head.u32be(pos + 12).toLong() and 0xFFFFFFFFL)
            }
            if (size < BOX_HEADER) return null
            if (type == "moov") {
                if (pos + size > head.size) return null // moov 比预读窗口还大，放弃
                moovAt = pos
                moovSize = size.toInt()
                break
            }
            if (type == "mdat") return null // moov 在 mdat 之后，拿不到字节
            if (pos + size > head.size) return null
            pos += size.toInt()
        }
        if (moovAt < 0) return null

        val moov = head.copyOfRange(moovAt, moovAt + moovSize)
        val newMoov = Mp4Codec.buildMoov(moov, bag) ?: return null

        return StreamPlan(
            // 源字节整段透传（旧 moov 会原样落地），新的 moov 追加在最后
            suffix = newMoov,
            patches = listOf(StreamPatch(moovAt + 4L) { "free".toByteArray(Charsets.ISO_8859_1) }),
        )
    }
}
