package com.annieplayer.annie_music.kt.data.local.tag

import java.io.File
import java.io.RandomAccessFile

/**
 * MP4 / M4A 标签（`moov/udta/meta/hdlr/ilst`）。
 *
 * 字段布局对着 ffmpeg 的输出逐字节对齐（含 `©nam` 这种首字节 0xA9 的 atom 名、
 * `data` 盒的 typeFlag：1=UTF-8 文本、0=二进制、13=JPEG、14=PNG）。
 *
 * ## 原地写（本类的核心）
 * MP4 里所有 `stco` 都是**绝对偏移**，所以「在 moov 里塞 udta」会让 mdat 后移、偏移全废。
 * 但反过来做就没有这个问题：
 *
 * ```
 * 1. 把旧 moov 的 8 字节盒头原地改成 size=<原大小> type='free'   ← 只写 8 字节
 * 2. 把「新 moov（含 udta）」追加到文件尾                        ← 只写几 KB~几百 KB
 * ```
 * `mdat` 一个字节都没动，`stco` 全部依然有效。代价是 moov 跑到文件尾 —— 这是合法的
 * MP4 布局（所有非 faststart 的 mp4 都长这样），Media3 / 系统播放器都照读。
 *
 * 如果 moov 本来就在文件尾，直接截断重写 moov，连 free 洞都不用留。
 */
internal object Mp4Codec : TagCodec {

    override val format: String = "M4A"

    private const val TYPE_TRKN = "trkn"
    private const val TYPE_DISK = "disk"
    private const val TYPE_COVR = "covr"
    private const val TYPE_FREEFORM = "----"

    override fun sniff(head: ByteArray): Boolean =
        head.size >= 8 && head.ascii(4, 4) == "ftyp"

    private class Box(val type: String, val start: Int, val size: Int, val headerSize: Int) {
        val payloadStart: Int get() = start + headerSize
        val end: Int get() = start + size
    }

    // ── box 工具（本类私有：标签库要自成一体，不依赖播放侧那份） ──

    private fun boxAt(buf: ByteArray, off: Int, end: Int): Box? {
        if (off + 8 > end) return null
        var size = buf.u32be(off)
        val type = buf.ascii(off + 4, 4)
        var header = 8
        if (size == 1) {
            if (off + 16 > end) return null
            size = buf.u32be(off + 8).toLong().shl(32).or(buf.u32be(off + 12).toLong() and 0xFFFFFFFFL).toInt()
            header = 16
        }
        if (size < header || off + size > end) return null
        return Box(type, off, size, header)
    }

    private fun children(buf: ByteArray, start: Int, end: Int): List<Box> {
        val out = ArrayList<Box>()
        var p = start
        while (true) {
            val box = boxAt(buf, p, end) ?: break
            out.add(box)
            p = box.end
        }
        return out
    }

    private fun boxBytes(type: String, payload: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write((payload.size + 8).toU32be())
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        out.write(payload)
        return out.toByteArray()
    }

    private fun fullBox(type: String, versionFlags: Int, payload: ByteArray): ByteArray =
        boxBytes(type, versionFlags.toU32be() + payload)

    // ── 读 ──

    override fun read(file: File): TagBag? {
        val moov = readTopLevelBox(file, "moov") ?: return null
        val bag = parseMoov(moov.first, moov.second)
        return bag.takeIf { !it.isEmpty() }
    }

    /** 取某个顶层 box 的 (内容, 大小)；找不到返回 null。 */
    private fun readTopLevelBox(file: File, wanted: String, maxScan: Long = 64L * 1024 * 1024): Pair<ByteArray, Int>? {
        if (!file.exists()) return null
        RandomAccessFile(file, "r").use { raf ->
            var pos = 0L
            val head = ByteArray(16)
            while (pos + 8 <= raf.length() && pos <= maxScan) {
                raf.seek(pos)
                if (raf.read(head, 0, 8) != 8) return null
                var size = head.u32be(0).toLong()
                val type = head.ascii(4, 4)
                if (size == 1L) {
                    if (raf.read(head, 8, 8) != 8) return null
                    size = (head.u32be(8).toLong() shl 32) or (head.u32be(12).toLong() and 0xFFFFFFFFL)
                }
                if (size < 8) return null
                if (type == wanted) {
                    if (size > Int.MAX_VALUE) return null
                    val buf = ByteArray(size.toInt())
                    raf.seek(pos)
                    if (raf.read(buf) != buf.size) return null
                    return buf to buf.size
                }
                pos += size
            }
        }
        return null
    }

    private fun parseMoov(moov: ByteArray, size: Int): TagBag {
        val out = LinkedHashMap<TagKey, String>()
        val extra = LinkedHashMap<String, String>()
        var cover: CoverBytes? = null
        val moovBox = boxAt(moov, 0, size) ?: return TagBag()
        val meta = children(moov, moovBox.payloadStart, moovBox.end)
            .firstNotNullOfOrNull { child ->
                when (child.type) {
                    "udta" -> children(moov, child.payloadStart, child.end).firstOrNull { it.type == "meta" }
                    "meta" -> child
                    else -> null
                }
            } ?: return TagBag()
        // meta 是 full box：跳过 4 字节 version/flags
        val ilst = children(moov, meta.payloadStart + 4, meta.end).firstOrNull { it.type == "ilst" }
            ?: return TagBag()
        for (item in children(moov, ilst.payloadStart, ilst.end)) {
            val data = children(moov, item.payloadStart, item.end).firstOrNull { it.type == "data" } ?: continue
            if (data.payloadStart + 8 > data.end) continue
            val typeFlag = moov.u32be(data.payloadStart)
            val valueStart = data.payloadStart + 8
            val value = moov.copyOfRange(valueStart, data.end)
            when (item.type) {
                "©nam" -> put(out, TagKey.Title, utf8(value))
                "©ART" -> put(out, TagKey.Artist, utf8(value))
                "©alb" -> put(out, TagKey.Album, utf8(value))
                "aART" -> put(out, TagKey.AlbumArtist, utf8(value))
                "©day" -> put(out, TagKey.Year, utf8(value))
                "©gen" -> put(out, TagKey.Genre, utf8(value))
                "©wrt" -> put(out, TagKey.Composer, utf8(value))
                "©cmt" -> put(out, TagKey.Comment, utf8(value))
                "cprt" -> put(out, TagKey.Copyright, utf8(value))
                "©lyr" -> put(out, TagKey.Lyric, utf8(value))
                TYPE_TRKN -> put(out, TagKey.Track, formatSlashPair(pairAt(value, 2), pairAt(value, 4)))
                TYPE_DISK -> put(out, TagKey.Disc, formatSlashPair(pairAt(value, 2), pairAt(value, 4)))
                TYPE_COVR -> if (cover == null) {
                    cover = CoverBytes(value, if (typeFlag == 14) "image/png" else "image/jpeg")
                }
                TYPE_FREEFORM -> {
                    val name = freeformName(moov, item)
                    if (!name.isNullOrEmpty()) {
                        val text = utf8(value)
                        if (name.equals("ISRC", ignoreCase = true)) {
                            put(out, TagKey.Isrc, text)
                        } else if (text.isNotEmpty()) {
                            // 没映射的自由键原样保留：读出来能再写回去，不丢用户的字段
                            extra.putIfAbsent(name, text)
                        }
                    }
                }
            }
        }
        return TagBag(out, cover, extra)
    }

    private fun pairAt(value: ByteArray, off: Int): Int? =
        if (value.size >= off + 2) value.u16be(off) else null

    private fun utf8(bytes: ByteArray): String = String(bytes, Charsets.UTF_8).trimEnd('\u0000')

    private fun put(out: MutableMap<TagKey, String>, key: TagKey, value: String?) {
        if (!value.isNullOrEmpty() && out[key].isNullOrEmpty()) out[key] = value
    }

    /** `----` 自由格式 atom 的 name（如 `com.apple.iTunes:ISRC` 里的 ISRC）。 */
    private fun freeformName(buf: ByteArray, item: Box): String? =
        children(buf, item.payloadStart, item.end)
            .firstOrNull { it.type == "name" }
            ?.let { String(buf, it.payloadStart + 4, it.end - it.payloadStart - 4, Charsets.UTF_8) }

    // ── 写 ──

    override fun write(file: File, bag: TagBag, policy: WritePolicy): WriteResult {
        val located = locate(file) ?: return WriteResult.fail(format, "找不到 moov")
        val (moovOffset, moovSize) = located
        val moov = runCatching { readRange(file, moovOffset, moovSize) }.getOrNull()
            ?: return WriteResult.fail(format, "读 moov 失败")
        val existing = runCatching { read(file) }.getOrNull()
        val merged = TagBag(
            text = (existing?.text.orEmpty() + bag.text).filterValues { it.isNotEmpty() },
            cover = bag.cover ?: existing?.cover,
            extra = (existing?.extra.orEmpty() + bag.extra).filterValues { it.isNotEmpty() },
        )
        val newMoov = buildMoov(moov, merged) ?: return WriteResult.fail(format, "重建 moov 失败")

        val ok = runCatching {
            RandomAccessFile(file, "rw").use { raf ->
                val atEnd = moovOffset + moovSize >= raf.length()
                if (atEnd) {
                    // moov 本来就在文件尾：截断后重写，连 free 洞都不用留
                    raf.setLength(moovOffset)
                } else {
                    // 旧 moov 原地改成等长 free，新 moov 追加到尾部 → mdat 不动，stco 全部有效
                    raf.seek(moovOffset)
                    raf.write(moovSize.toU32be())
                    raf.write("free".toByteArray(Charsets.ISO_8859_1))
                }
                raf.seek(raf.length())
                raf.write(newMoov)
                raf.channel.force(true)
            }
            true
        }.getOrDefault(false)

        return if (ok) WriteResult.ok(format, inPlace = true) else WriteResult.fail(format, "写 moov 失败")
    }

    /** 找 moov 的 (偏移, 大小)。 */
    private fun locate(file: File): Pair<Long, Int>? {
        if (!file.exists()) return null
        RandomAccessFile(file, "r").use { raf ->
            var pos = 0L
            val head = ByteArray(16)
            while (pos + 8 <= raf.length()) {
                raf.seek(pos)
                if (raf.read(head, 0, 8) != 8) return null
                var size = head.u32be(0).toLong()
                val type = head.ascii(4, 4)
                if (size == 1L) {
                    if (raf.read(head, 8, 8) != 8) return null
                    size = (head.u32be(8).toLong() shl 32) or (head.u32be(12).toLong() and 0xFFFFFFFFL)
                }
                if (size < 8) return null
                if (type == "moov") return pos to size.toInt()
                pos += size
            }
        }
        return null
    }

    private fun readRange(file: File, offset: Long, size: Int): ByteArray {
        val buf = ByteArray(size)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            raf.readFully(buf)
        }
        return buf
    }

    /** 把 moov 里除 udta 外的子 box 原样保留，再把我们生成的 udta 追加进去。 */
    internal fun buildMoov(moov: ByteArray, bag: TagBag): ByteArray? {
        val moovBox = boxAt(moov, 0, moov.size) ?: return null
        val out = java.io.ByteArrayOutputStream()
        for (child in children(moov, moovBox.payloadStart, moovBox.end)) {
            if (child.type == "udta" || child.type == "meta") {
                // 原有的 udta 丢掉（由我们重建）；顶层 meta 一般是 ISO 的键值写法，保留
                if (child.type == "udta") continue
            }
            out.write(moov.copyOfRange(child.start, child.end))
        }
        out.write(buildUdta(bag))
        return boxBytes("moov", out.toByteArray())
    }

    internal fun buildUdtaBox(bag: TagBag): ByteArray = buildUdta(bag)

    private fun buildUdta(bag: TagBag): ByteArray {
        val ilst = java.io.ByteArrayOutputStream()
        fun text(type: String, value: String?) {
            if (value.isNullOrEmpty()) return
            ilst.write(item(type, 1, value.toByteArray(Charsets.UTF_8)))
        }
        text("©nam", bag[TagKey.Title])
        text("©ART", bag[TagKey.Artist])
        text("©alb", bag[TagKey.Album])
        text("aART", bag[TagKey.AlbumArtist])
        text("©day", bag[TagKey.Year])
        text("©gen", bag[TagKey.Genre])
        text("©wrt", bag[TagKey.Composer])
        text("©cmt", bag[TagKey.Comment])
        text("cprt", bag[TagKey.Copyright])
        text("©lyr", bag[TagKey.Lyric])
        bag[TagKey.Track]?.let { ilst.write(item(TYPE_TRKN, 0, slashPairPayload(it))) }
        bag[TagKey.Disc]?.let { ilst.write(item(TYPE_DISK, 0, slashPairPayload(it))) }
        bag[TagKey.Isrc]?.let { ilst.write(freeformItem("ISRC", it)) }
        // 长尾字段：一律走 `----` freeform（MP4 唯一的「任意键值」机制）
        for ((name, value) in bag.extra) {
            if (value.isEmpty()) continue
            if (name.equals("ISRC", ignoreCase = true)) continue // 标准字段已写过
            ilst.write(freeformItem(name, value))
        }
        bag.cover?.let { ilst.write(item(TYPE_COVR, if (it.isPng) 14 else 13, it.bytes)) }

        val hdlr = fullBox(
            "hdlr",
            0,
            ByteArray(4) + // pre_defined
                "mdir".toByteArray(Charsets.ISO_8859_1) +
                ByteArray(12) + // reserved
                byteArrayOf(0), // 空名字
        )
        val meta = fullBox("meta", 0, hdlr + boxBytes("ilst", ilst.toByteArray()))
        return boxBytes("udta", meta)
    }

    /** 一个 ilst 条目：`type` 盒里包一个 `data` 盒。 */
    private fun item(type: String, typeFlag: Int, payload: ByteArray): ByteArray =
        boxBytes(type, fullBox("data", typeFlag, ByteArray(4) + payload))

    /** `trkn` / `disk` 的二进制 payload：reserved(2) | 序号(2) | 总数(2) | reserved(2)。 */
    private fun slashPairPayload(value: String): ByteArray {
        val (first, second) = parseSlashPair(value)
        val out = ByteArray(8)
        (first ?: 0).toU16be().copyInto(out, 2)
        (second ?: 0).toU16be().copyInto(out, 4)
        return out
    }

    /** `----` 自由格式条目（`mean` + `name` + `data`），iTunes 用它存 ISRC 这类扩展字段。 */
    private fun freeformItem(name: String, value: String): ByteArray {
        val mean = fullBox("mean", 0, "com.apple.iTunes".toByteArray(Charsets.UTF_8))
        val nameBox = fullBox("name", 0, name.toByteArray(Charsets.UTF_8))
        val data = fullBox("data", 1, ByteArray(4) + value.toByteArray(Charsets.UTF_8))
        return boxBytes(TYPE_FREEFORM, mean + nameBox + data)
    }
}
