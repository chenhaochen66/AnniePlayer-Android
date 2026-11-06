package com.annieplayer.annie_music.kt.data.local.stream

import com.annieplayer.annie_music.kt.data.local.tag.Id3v2Tag
import com.annieplayer.annie_music.kt.data.local.tag.TagBag

/**
 * 裸 AAC（ADTS）：一个帧接一个帧，**本身没有任何标签位置**。
 *
 * ```
 * 源：  [旧 ID3v2] FF F1 …帧… FF F1 …帧…
 * 成品：[我们的 ID3v2] FF F1 …帧… FF F1 …帧…
 * ```
 *
 * ADTS 只能靠「前面挂一段 ID3v2」来带标签（ffmpeg / 各家播放器都认这套）。
 * 反正音频帧是纯透传，这一趟零成本。
 *
 * 为什么要单独登记：平台给的字节未必是请求的封装 —— 请求 `.m4a` 却拿回 ADTS 的情况
 * 真实存在，认不出来就会产出「扩展名 m4a、内容 ADTS」的坏文件。
 *
 * 和 MP3 的判定如何互斥：本类先跳过 ID3v2 再看同步字的 layer 位（ADTS 固定 `00`），
 * 而 MPEG 音频层编码里 `00` 是保留值，所以「带 ID3 的 ADTS」只会命中本类、
 * 「带 ID3 的 MP3」只会命中 [Mp3Stream] —— 前提是本类登记在它**前面**。
 */
internal object AacStream : StreamCodec {

    override val id: String = "aac"

    override fun extension(head: ByteArray): String = ".aac"

    override fun matches(head: ByteArray): Boolean {
        val at = (StreamIo.leadingId3Size(head) ?: return false).toInt()
        if (at + 2 > head.size) return false
        if ((head[at].toInt() and 0xFF) != 0xFF) return false
        // 1111 ID 00 PA：同步半字节 + layer=00
        return (head[at + 1].toInt() and 0xF6) == 0xF0
    }

    override fun plan(head: ByteArray, contentLength: Long, bag: TagBag): StreamPlan? {
        val skip = StreamIo.leadingId3Size(head) ?: return null
        return StreamPlan(prefix = Id3v2Tag.encode(bag), skip = skip)
    }
}
