package com.annieplayer.annie_music.kt.data.local.tag

import java.io.File
import java.io.RandomAccessFile

/**
 * APEv2 容器接线：`.ape`（Monkey's Audio）/ `.wv`（WavPack）/ `.mpc`（Musepack）。
 *
 * 这三种格式的标签都在**文件尾**（APEv2），所以「改标签」= 截掉旧标签 + 追加新标签，
 * **音频一个字节都不搬** —— 和 TTA / DSF 是同一类好处，只是换了个尾块结构。
 *
 * 只写最小形式（items + footer，不写前置 header）：所有主流播放器都认，而且能让
 * 「删旧 + 追加新」保持简单。
 */
internal object ApeCodec : TagCodec {

    override val format: String = "APEv2"

    override fun sniff(head: ByteArray): Boolean {
        if (head.size < 4) return false
        val magic = head.ascii(0, 4)
        // TTA 也用 APEv2（实测 ffmpeg 写的就是尾部 APETAGEX + 前置 header 标志）。
        // 不要按「TTA 用 ID3」去实现 —— 那条约定已经被 TTA 自己的工具链抛弃了。
        return magic == "MAC " || magic == "wvpk" || magic == "MPCK" || magic == "MP+" || magic == "TTA1"
    }

    override fun read(file: File): TagBag? {
        val tag = readTagBytes(file) ?: return null
        return ApeV2Tag.decode(tag).takeIf { !it.isEmpty() }
    }

    private fun readTagBytes(file: File): ByteArray? {
        val start = ApeV2Tag.findTagStart(file)
        if (start < 0) return null
        val len = (file.length() - start).toInt()
        if (len <= ApeV2Tag.FOOTER_SIZE) return null
        val all = ByteArray(len)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            if (raf.read(all) != len) return null
        }
        // 剥掉可选的前置 header
        return if (all.size >= 8 && all.ascii(0, 8) == "APETAGEX") all.copyOfRange(32, all.size) else all
    }

    override fun write(file: File, bag: TagBag, policy: WritePolicy): WriteResult {
        val existing = runCatching { read(file) }.getOrNull()
        val merged = TagBag(
            text = (existing?.text.orEmpty() + bag.text).filterValues { it.isNotEmpty() },
            cover = bag.cover ?: existing?.cover,
            extra = (existing?.extra.orEmpty() + bag.extra).filterValues { it.isNotEmpty() },
        )
        val tag = ApeV2Tag.encode(merged)
        val oldStart = ApeV2Tag.findTagStart(file)
        val audioEnd = if (oldStart >= 0) oldStart else file.length()

        val ok = runCatching {
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(audioEnd)
                raf.seek(audioEnd)
                raf.write(tag)
                raf.channel.force(true)
            }
            true
        }.getOrDefault(false)

        // 标签在尾部，所以永远是「原地」（堆在文件末尾），不搬音频
        return if (ok) WriteResult.ok(format, inPlace = true) else WriteResult.fail(format, "写 APEv2 失败")
    }
}
