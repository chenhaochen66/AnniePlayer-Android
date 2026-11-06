package com.annieplayer.annie_music.kt.data.local.tag

import java.io.File

/**
 * `AnnieTag` —— 自己实现的多格式标签读写库（Apple 下载 / 本地曲库编辑共用）。
 *
 * ## 为什么不用现成的
 * ffmpeg（以及 TagLib / jaudiotagger / mp4parser）都只能「打开一个已有文件 → 改 → 保存」，
 * 而 MP4 的 `stco` 是绝对偏移：往 moov 里塞 udta 会让 mdat 后移，所以它们只能**整文件重写**。
 * 我们这套每个格式都用「不搬音频」的写法：
 *
 * | 格式 | 原地写的方式 | 写入量 |
 * |---|---|---|
 * | MP4 / M4A | 旧 `moov` 原地改成等长 `free`，新 `moov` 追加到尾部 | 几 KB ~ 几百 KB |
 * | FLAC | 复用 `PADDING` 块补齐差额，块链总长不变 | 几 KB ~ 几百 KB |
 * | MP3 | 复用一个够大的旧 ID3v2 tag 区，剩余补 0 | 几 KB ~ 几百 KB |
 *
 * 三种情况下**音频数据一个字节都不搬**，这正是它比 ffmpeg 快的地方（不是语言快，是趟数少）。
 *
 * ## 覆盖范围
 * - `.m4a` / `.mp4` / `.aac`（MP4 `ilst`）
 * - `.flac`（Vorbis comment + PICTURE）
 * - `.mp3`（ID3v2.4 写、v2.2/2.3/2.4 读，含 ID3v1 兜底读）
 *
 * `.ogg` / `.opus` / `.ape` / `.wv` 暂未实现 —— 它们的注释块是同一套 Vorbis comment / APEv2，
 * 后续按同样的模式补进来即可（读不到时本库会明确返回「不支持的格式」，不会静默跳过）。
 */
object AnnieTag {

    private val codecs: List<TagCodec> = listOf(
        Mp4Codec,      // m4a / mp4 / aac
        FlacCodec,     // flac
        OggCodec,      // ogg / opus / speex
        Id3v2Codec,    // mp3 / tta
        ApeCodec,      // ape / wv / mpc
        DsdCodec,      // dsf / dff
        RiffCodec,     // wav / aiff
    )

    /** 按文件头选 codec；不认识的格式返回 null。 */
    fun codecFor(file: File): TagCodec? {
        if (!file.exists() || file.length() < 8) return null
        val head = runCatching { file.readHead(16) }.getOrNull() ?: return null
        return codecs.firstOrNull { it.sniff(head) }
    }

    /** 读标签；不支持的格式或空标签返回 null。 */
    fun read(file: File): TagBag? = runCatching { codecFor(file)?.read(file) }.getOrNull()

    /** 只取歌词（本地曲库扫描用，省掉整个 TagBag 的构造）。 */
    fun readLyric(file: File): String? = read(file)?.get(TagKey.Lyric)

    /**
     * 写标签。
     *
     * 默认 [WritePolicy.PreferInPlace]：能原地就原地，不能才全量重写。
     * 只覆盖 [bag] 里给了的字段，没给的保留文件里原来的值。
     */
    fun write(file: File, bag: TagBag, policy: WritePolicy = WritePolicy.PreferInPlace): WriteResult {
        val codec = codecFor(file) ?: return WriteResult.fail("", "不支持的格式")
        if (bag.isEmpty()) return WriteResult.ok(codec.format, inPlace = true)
        return runCatching { codec.write(file, bag, policy) }
            .getOrElse { WriteResult.fail(codec.format, it.message ?: it.javaClass.simpleName) }
    }

    /** 这个文件能不能被本库写（用于 UI 上灰掉不支持的条目）。 */
    fun isWritable(file: File): Boolean = codecFor(file) != null

    /**
     * 直接把标签编成 MP4 的 `udta` 盒子。
     *
     * 给「**自己生成容器**」的调用方用（Apple 下载器在内存里拼 `ftyp + moov`）：
     * 标签随 header 一起写出去，音频那一趟完全不用回头改 —— 这是「零趟打标」的入口。
     */
    fun mp4UdtaBox(bag: TagBag): ByteArray = Mp4Codec.buildUdtaBox(bag)

    /**
     * 把封面文件读成 [CoverBytes]（MIME 只看魔数：JPEG `FF D8` / PNG `89 50 4E 47`）。
     *
     * 下载链路与本地库编辑共用，免得两处各写一份魔数判断。
     */
    fun coverFromFile(file: File?): CoverBytes? {
        if (file == null || !file.exists()) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        return coverOf(bytes)
    }

    /** 封面字节 → [CoverBytes]（同上，但入参是已经读进内存的字节）。 */
    fun coverOf(bytes: ByteArray?): CoverBytes? {
        if (bytes == null || bytes.size <= 3) return null
        val png = (bytes[0].toInt() and 0xFF) == 0x89 &&
            bytes[1].toInt() == 0x50 && bytes[2].toInt() == 0x4E && bytes[3].toInt() == 0x47
        return CoverBytes(bytes, if (png) "image/png" else "image/jpeg")
    }
}
