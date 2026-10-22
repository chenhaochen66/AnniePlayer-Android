package com.annieplayer.annie_music.kt.core.audio.apple

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Apple fMP4 的最小解析 / 解密后重封装。
 *
 * 只处理音频轨：读取 init 的 ftyp+moov，按 moof/trun 切 sample，
 * 由调用方用 Temari 解密后写入 raw sample 文件，再重封装成普通 m4a。
 */
data class AppleTrackInfo(
    val trackId: Int,
    val codec: String,
    val protectedTrack: Boolean,
    val ivSize: Int,
    val cryptByteBlock: Int,
    val skipByteBlock: Int,
    val constantIv: ByteArray?,
)

data class AppleInitInfo(
    val ftyp: ByteArray,
    val moov: ByteArray,
    val tracks: Map<Int, AppleTrackInfo>,
    val nextOffset: Int,
)

data class AppleSampleInfo(
    val offset: Int,
    val length: Int,
    val duration: Int,
)

data class AppleFragmentInfo(
    val samples: List<AppleSampleInfo>,
    val mdatPayloadOffset: Int,
    val mdatLength: Int,
    val nextOffset: Int,
)

private class Mp4Box(
    val type: String,
    val userType: ByteArray?,
    val size: Int,
    val headerSize: Int,
    val start: Int,
    val payloadStart: Int,
)

private val HE_UUID = byteArrayOf(
    0xA2.toByte(), 0x39, 0x4F, 0x52, 0x5A, 0x9B.toByte(), 0x4F, 0x14,
    0xA2.toByte(), 0x44, 0x6C, 0x42, 0x7C, 0x64, 0x8D.toByte(), 0xF4.toByte(),
)

fun parseAppleInit(bytes: ByteArray, start: Int = 0): AppleInitInfo? {
    val ftyp = boxAt(bytes, start, bytes.size) ?: return null
    if (ftyp.type != "ftyp") return null
    val moovBox = boxAt(bytes, ftyp.start + ftyp.size, bytes.size) ?: return null
    if (moovBox.type != "moov") return null
    val moov = bytes.copyOfRange(moovBox.start, moovBox.start + moovBox.size)
    val tracks = parseTracks(moov)
    return AppleInitInfo(
        ftyp = bytes.copyOfRange(ftyp.start, ftyp.start + ftyp.size),
        moov = moov,
        tracks = tracks,
        nextOffset = moovBox.start + moovBox.size,
    )
}

fun parseAppleFragment(bytes: ByteArray, offset: Int, init: AppleInitInfo): AppleFragmentInfo? {
    var off = offset
    var moof = boxAt(bytes, off, bytes.size)
    while (moof != null && moof.type != "moof") {
        off = moof.start + moof.size
        moof = boxAt(bytes, off, bytes.size)
    }
    if (moof == null || moof.type != "moof") return null
    val mdat = boxAt(bytes, moof.start + moof.size, bytes.size) ?: return null
    if (mdat.type != "mdat") return null

    val trafs = children(bytes, moof.payloadStart, moof.start + moof.size)
        .filter { it.type == "traf" }
    if (trafs.isEmpty()) return null

    val samples = mutableListOf<AppleSampleInfo>()
    var runOffset = 0
    for (traf in trafs) {
        val boxes = children(bytes, traf.payloadStart, traf.start + traf.size)
        val tfhd = boxes.firstOrNull { it.type == "tfhd" } ?: continue
        val tfhdRaw = bytes.copyOfRange(tfhd.payloadStart, tfhd.start + tfhd.size)
        val tfFlags = u32(tfhdRaw, 0) and 0xFFFFFF
        var p = 8
        if (tfFlags and 0x1 != 0) p += 8
        if (tfFlags and 0x2 != 0) p += 4
        var defaultDuration = 0
        var defaultSize = 0
        if (tfFlags and 0x8 != 0) {
            defaultDuration = i32(tfhdRaw, p)
            p += 4
        }
        if (tfFlags and 0x10 != 0) {
            defaultSize = i32(tfhdRaw, p)
        }

        val truns = boxes.filter { it.type == "trun" }
        if (truns.isEmpty()) continue
        val total = truns.sumOf { i32(bytes, it.payloadStart + 4) }
        val ivs = parseSampleIvs(bytes, boxes, total, init)
        var sampleIndex = 0

        for (trun in truns) {
            val raw = bytes.copyOfRange(trun.payloadStart, trun.start + trun.size)
            val flags = u32(raw, 0) and 0xFFFFFF
            val count = i32(raw, 4)
            var q = 8
            if (flags and 0x1 != 0) q += 4
            if (flags and 0x4 != 0) q += 4
            for (i in 0 until count) {
                val duration = if (flags and 0x100 != 0) {
                    i32(raw, q).also { q += 4 }
                } else {
                    defaultDuration
                }
                val length = if (flags and 0x200 != 0) {
                    i32(raw, q).also { q += 4 }
                } else {
                    defaultSize
                }
                if (flags and 0x400 != 0) q += 4
                if (flags and 0x800 != 0) q += 4
                samples += AppleSampleInfo(
                    offset = runOffset,
                    length = length,
                    duration = duration,
                )
                runOffset += length
                sampleIndex++
            }
        }
        // ivs 目前不需要：Temari 模板自行处理 sample 状态。
        @Suppress("UNUSED_VARIABLE")
        val unusedIvs = ivs.take(sampleIndex)
    }

    return AppleFragmentInfo(
        samples = samples,
        mdatPayloadOffset = mdat.payloadStart,
        mdatLength = mdat.start + mdat.size - mdat.payloadStart,
        nextOffset = mdat.start + mdat.size,
    )
}

/** 把 init 里的 pssh 改成 free，避免 Media3 建 DRM 会话。 */
fun patchAppleInitClear(bytes: ByteArray) {
    fun walk(start: Int, end: Int) {
        for (box in children(bytes, start, end)) {
            when {
                box.type == "pssh" -> {
                    bytes[box.start + 4] = 'f'.code.toByte()
                    bytes[box.start + 5] = 'r'.code.toByte()
                    bytes[box.start + 6] = 'e'.code.toByte()
                    bytes[box.start + 7] = 'e'.code.toByte()
                }
                box.type == "tenc" -> {
                    val v = bytes[box.payloadStart].toInt() and 0xFF
                    val protectedAt = box.payloadStart + if (v >= 1) 6 else 4
                    if (protectedAt + 1 < bytes.size) {
                        bytes[protectedAt] = 0
                        bytes[protectedAt + 1] = 0
                    }
                }
                box.type == "stsd" -> walk(box.payloadStart + 8, box.start + box.size)
                box.type == "enca" || box.type == "encv" -> {
                    // 把加密 sample entry 改回 frma 指定的真实编码，Media3 就不会再走 DRM 解析。
                    val frma = findFrma(bytes, box.payloadStart + 28, box.start + box.size)
                    if (frma != null && frma.payloadStart + 4 <= bytes.size) {
                        System.arraycopy(bytes, frma.payloadStart, bytes, box.start + 4, 4)
                    }
                    walk(box.payloadStart + 28, box.start + box.size)
                }
                box.type == "sinf" || box.type == "schi" ||
                    box.type == "moov" || box.type == "trak" || box.type == "mdia" ||
                    box.type == "minf" || box.type == "stbl" -> {
                    walk(box.payloadStart, box.start + box.size)
                }
            }
        }
    }
    walk(0, bytes.size)
}

/**
 * 解密后把分片里所有「加密辅助信息」盒清掉，Media3 就会当成明文 fMP4 解析。
 *
 * init 里的 `enca`/`sinf`/`tenc`/`pssh` 已经被 [patchAppleInitClear] 抹平（track 上不再有
 * `TrackEncryptionBox`），但 Apple 的分片仍带着 `senc` / `saiz` / `saio` / `uuid`(HE)。
 * Media3 的 `FragmentedMp4Extractor.parseTraf` 在分片出现 `saiz` 时会
 * `Assertions.checkNotNull(trackEncryptionBox)` —— 拿不到就直接 NPE 把播放打断
 * （表现为 `Source error` + `Unexpected NullPointerException`）。
 *
 * 这些盒子的 type 统一改名成 `free`：Media3 的 Atom 解析器只会把它当成未知 leaf 跳过，
 * 既不会挂 CryptoData，也不会再去找 EncryptionBox。
 */
fun patchAppleSegmentClear(bytes: ByteArray) {
    val cryptoTypes = setOf("senc", "saiz", "saio")
    fun walk(start: Int, end: Int) {
        for (box in children(bytes, start, end)) {
            val encryptedAux = box.type in cryptoTypes ||
                (box.type == "uuid" && box.userType?.contentEquals(HE_UUID) == true)
            if (encryptedAux) {
                val at = box.start + 4
                bytes[at] = 'f'.code.toByte()
                bytes[at + 1] = 'r'.code.toByte()
                bytes[at + 2] = 'e'.code.toByte()
                bytes[at + 3] = 'e'.code.toByte()
            } else if (box.type == "moof" || box.type == "traf") {
                walk(box.payloadStart, box.start + box.size)
            }
        }
    }
    walk(0, bytes.size)
}

/**
 * 生成普通（非分片）m4a 的 ftyp+moov。
 * sample 数据由调用方按顺序写在 mdat 中。
 */
fun buildAppleHeader(
    ftyp: ByteArray,
    moov: ByteArray,
    codec: String,
    durations: List<Int>,
    sizes: List<Int>,
): ByteArray {
    val rebuilt = rebuildMoov(moov, codec, durations, sizes)
    val header = ftyp + rebuilt
    patchStco(header, header.size + 8)
    patchDurations(header, durations)
    return header
}

/**
 * 把 raw sample 文件封装成最终 m4a。
 *
 * @param rawSamples 已解密、按 sample 顺序拼接的裸音频帧
 */
fun writeAppleMp4(
    header: ByteArray,
    rawSamples: File,
    output: File,
) {
    val sampleBytes = rawSamples.length()
    val large = sampleBytes + 8 > 0xFFFFFFFFL
    output.parentFile?.mkdirs()
    RandomAccessFile(output, "rw").use { out ->
        out.setLength(0)
        out.write(header)
        if (large) {
            writeU32(out, 1)
            out.write("mdat".toByteArray(Charsets.US_ASCII))
            writeU64(out, sampleBytes + 16)
        } else {
            writeU32(out, (sampleBytes + 8).toInt())
            out.write("mdat".toByteArray(Charsets.US_ASCII))
        }
        RandomAccessFile(rawSamples, "r").use { src ->
            // 整段裸样本可能几百 MB，用 transferTo 走内核零拷贝，避免 256KB 缓冲的往返 copy
            val srcChannel = src.channel
            val dstChannel = out.channel
            val total = srcChannel.size()
            var position = 0L
            while (position < total) {
                val moved = srcChannel.transferTo(position, total - position, dstChannel)
                if (moved <= 0L) break
                position += moved
            }
        }
    }
}

/** 修正 ALAC 每帧末尾的 3 个保留位；返回修正帧数。 */
fun alacFix(file: File): Int {
    RandomAccessFile(file, "rw").use { raf ->
        val moov = findMoovBytes(raf) ?: return 0
        val cookie = findAlacCookie(moov) ?: return 0
        val off = if (cookie.size >= 8 && String(cookie, 4, 4, Charsets.US_ASCII) == "alac") 12 else 0
        val body = cookie.copyOfRange(off, cookie.size)
        if (body.size < 24) return 0
        val frameLength = i32(body, 0)
        val channels = body[9].toInt() and 0xFF
        val depth = body[5].toInt() and 0xFF
        if (frameLength <= 0 || channels <= 0 || depth <= 0) return 0
        val expected = (frameLength * channels * depth + 32 + 7) / 8

        val stbl = findStbl(moov) ?: return 0
        val stsz = children(stbl, 8, stbl.size).firstOrNull { it.type == "stsz" } ?: return 0
        val stco = children(stbl, 8, stbl.size).firstOrNull { it.type == "stco" } ?: return 0
        val count = i32(moov, stsz.payloadStart + 8)
        val per = i32(moov, stsz.payloadStart + 4)
        val nco = i32(moov, stco.payloadStart + 4)
        var dataStart = if (nco > 0) i32(moov, stco.payloadStart + 8).toLong() else 0L
        var fixed = 0

        val starts = LongArray(count)
        val sizes = IntArray(count)
        if (per != 0) {
            for (i in 0 until count) sizes[i] = per
        } else {
            var p = stsz.payloadStart + 12
            for (i in 0 until count) {
                sizes[i] = i32(moov, p)
                p += 4
            }
        }
        for (i in 0 until count) {
            starts[i] = dataStart
            dataStart += sizes[i]
        }
        val pair = ByteArray(2)
        for (i in 0 until count) {
            val s = sizes[i]
            val d = starts[i] + s
            if (starts[i] < 0 || d > raf.length() || s <= 2 || kotlin.math.abs(s - expected) > 4) continue
            raf.seek(d - 2)
            // 一次 readFully 取 2 字节，省掉逐字节 read() 的往返
            raf.readFully(pair)
            val b1 = pair[0].toInt() and 0xFF
            val b2 = pair[1].toInt() and 0xFF
            if ((((b1 and 1) shl 2) or ((b2 shr 6) and 3)) != 7) {
                raf.seek(d - 2)
                raf.write((b1 and 0xFE) or 1)
                raf.write((b2 and 0x3F) or 0xC0)
                fixed++
            }
        }
        return fixed
    }
}

// ── moov 重建 ──

private fun rebuildMoov(moov: ByteArray, codec: String, durations: List<Int>, sizes: List<Int>): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    for (box in children(moov, 8, moov.size)) {
        if (box.type == "mvex" || box.type == "pssh" || box.type == "udta") continue
        val raw = moov.copyOfRange(box.start, box.start + box.size)
        out.write(if (box.type == "trak") rebuildTrak(raw, codec, durations, sizes) else raw)
    }
    return boxBytes("moov", out.toByteArray())
}

private fun rebuildTrak(trak: ByteArray, codec: String, durations: List<Int>, sizes: List<Int>): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    for (box in children(trak, 8, trak.size)) {
        val raw = trak.copyOfRange(box.start, box.start + box.size)
        out.write(if (box.type == "mdia") rebuildMdia(raw, codec, durations, sizes) else raw)
    }
    return boxBytes("trak", out.toByteArray())
}

private fun rebuildMdia(mdia: ByteArray, codec: String, durations: List<Int>, sizes: List<Int>): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    for (box in children(mdia, 8, mdia.size)) {
        val raw = mdia.copyOfRange(box.start, box.start + box.size)
        out.write(if (box.type == "minf") rebuildMinf(raw, codec, durations, sizes) else raw)
    }
    return boxBytes("mdia", out.toByteArray())
}

private fun rebuildMinf(minf: ByteArray, codec: String, durations: List<Int>, sizes: List<Int>): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    for (box in children(minf, 8, minf.size)) {
        val raw = minf.copyOfRange(box.start, box.start + box.size)
        out.write(if (box.type == "stbl") rebuildStbl(raw, codec, durations, sizes) else raw)
    }
    return boxBytes("minf", out.toByteArray())
}

private fun rebuildStbl(stbl: ByteArray, codec: String, durations: List<Int>, sizes: List<Int>): ByteArray {
    val stsd = children(stbl, 8, stbl.size).firstOrNull { it.type == "stsd" }
        ?: error("stbl has no stsd")
    val stsdRaw = stbl.copyOfRange(stsd.start, stsd.start + stsd.size)
    val out = java.io.ByteArrayOutputStream()
    out.write(rebuildStsd(stsdRaw, codec))
    out.write(stts(durations))
    out.write(stsc(sizes.size))
    out.write(stsz(sizes))
    out.write(stco())
    return boxBytes("stbl", out.toByteArray())
}

private fun rebuildStsd(stsd: ByteArray, codec: String): ByteArray {
    val first = boxAt(stsd, 16, stsd.size) ?: error("stsd has no entry")
    val entry = stsd.copyOfRange(first.start, first.start + first.size)
    val clean = cleanEntry(entry, first.type, codec)
    val head = stsd.copyOfRange(8, 12)
    val body = java.io.ByteArrayOutputStream()
    body.write(head)
    writeU32(body, 1)
    body.write(clean)
    return boxBytes("stsd", body.toByteArray())
}

private fun cleanEntry(entry: ByteArray, oldType: String, codec: String): ByteArray {
    if (oldType != "enca" && oldType != "encv") return entry
    val fourcc = when (codec) {
        "alac" -> "alac"
        "mp4a" -> "mp4a"
        "ec-3" -> "ec-3"
        "ac-3" -> "ac-3"
        else -> "mp4a"
    }
    val inner = entry.copyOfRange(8, entry.size)
    var cut = inner.size
    var e = 8
    while (e + 4 <= inner.size) {
        val tag = String(inner, e, 4, Charsets.US_ASCII)
        if (tag == "sinf" || tag == "schm") {
            val size = u32(inner, e - 4)
            if (size >= 8 && e - 4 + size <= inner.size) cut = e - 4
            break
        }
        e++
    }
    val content = inner.copyOfRange(0, cut)
    val body = java.io.ByteArrayOutputStream()
    writeU32(body, 8 + content.size)
    body.write(fourcc.toByteArray(Charsets.US_ASCII))
    body.write(content)
    return body.toByteArray()
}

private fun stts(durations: List<Int>): ByteArray {
    val runs = mutableListOf<IntArray>()
    for (d in durations) {
        if (runs.isNotEmpty() && runs.last()[1] == d) {
            runs.last()[0]++
        } else {
            runs += intArrayOf(1, d)
        }
    }
    val body = java.io.ByteArrayOutputStream()
    writeU32(body, runs.size)
    for (run in runs) {
        writeU32(body, run[0])
        writeU32(body, run[1])
    }
    return fullBox("stts", body.toByteArray())
}

private fun stsc(count: Int): ByteArray {
    val body = java.io.ByteArrayOutputStream()
    writeU32(body, 1)
    writeU32(body, 1)
    writeU32(body, count)
    writeU32(body, 1)
    return fullBox("stsc", body.toByteArray())
}

private fun stsz(sizes: List<Int>): ByteArray {
    val body = java.io.ByteArrayOutputStream()
    writeU32(body, 0)
    writeU32(body, sizes.size)
    for (s in sizes) writeU32(body, s)
    return fullBox("stsz", body.toByteArray())
}

private fun stco(): ByteArray {
    val body = java.io.ByteArrayOutputStream()
    writeU32(body, 1)
    writeU32(body, 0)
    return fullBox("stco", body.toByteArray())
}

private fun patchStco(file: ByteArray, chunkOffset: Int) {
    val ftyp = boxAt(file, 0, file.size) ?: return
    val moov = boxAt(file, ftyp.start + ftyp.size, file.size) ?: return
    fun rec(start: Int, end: Int): Boolean {
        for (box in children(file, start, end)) {
            if (box.type == "stco") {
                writeU32Into(file, box.payloadStart + 8, chunkOffset)
                return true
            }
            if (box.type in CONTAINERS && rec(box.payloadStart, box.start + box.size)) return true
        }
        return false
    }
    rec(moov.payloadStart, moov.start + moov.size)
}

private fun patchDurations(file: ByteArray, durations: List<Int>) {
    val total = durations.sum()
    val ftyp = boxAt(file, 0, file.size) ?: return
    val moov = boxAt(file, ftyp.start + ftyp.size, file.size) ?: return
    fun rec(start: Int, end: Int) {
        for (box in children(file, start, end)) {
            val p = box.payloadStart
            when (box.type) {
                "mvhd", "tkhd", "mdhd" -> {
                    val v = file[p].toInt() and 0xFF
                    val at = when (box.type) {
                        "mvhd" -> p + if (v == 1) 20 else 16
                        "tkhd" -> p + if (v == 1) 28 else 20
                        else -> p + if (v == 1) 28 else 16
                    }
                    if (v == 1) writeU64Into(file, at, total.toLong()) else writeU32Into(file, at, total)
                }
                "elst" -> {
                    val v = file[p].toInt() and 0xFF
                    val n = i32(file, p + 4)
                    var q = p + 8
                    repeat(n) {
                        if (v == 1) {
                            writeU64Into(file, q, total.toLong())
                            writeU64Into(file, q + 8, 0L)
                            q += 20
                        } else {
                            writeU32Into(file, q, total)
                            writeU32Into(file, q + 4, 0)
                            q += 12
                        }
                    }
                }
            }
            if (box.type in CONTAINERS_WITH_EDTS) rec(box.payloadStart, box.start + box.size)
        }
    }
    rec(moov.payloadStart, moov.start + moov.size)
}

private fun mdhdTimescale(moov: ByteArray): Int {
    for (trak in children(moov, 8, moov.size)) {
        if (trak.type != "trak") continue
        for (mdia in children(moov, trak.payloadStart, trak.start + trak.size)) {
            if (mdia.type != "mdia") continue
            for (mdhd in children(moov, mdia.payloadStart, mdia.start + mdia.size)) {
                if (mdhd.type != "mdhd") continue
                val v = moov[mdhd.payloadStart].toInt() and 0xFF
                return if (v == 1) i32(moov, mdhd.payloadStart + 20) else i32(moov, mdhd.payloadStart + 12)
            }
        }
    }
    return 44100
}

// ── stsd / sinf / tenc ──

private fun parseTracks(moov: ByteArray): Map<Int, AppleTrackInfo> {
    val result = mutableMapOf<Int, AppleTrackInfo>()
    for (trak in children(moov, 8, moov.size)) {
        if (trak.type != "trak") continue
        var trackId = -1
        var info: AppleTrackInfo? = null
        for (box in children(moov, trak.payloadStart, trak.start + trak.size)) {
            when (box.type) {
                "tkhd" -> {
                    val p = box.payloadStart
                    val v = moov[p].toInt() and 0xFF
                    trackId = i32(moov, p + if (v == 1) 20 else 12)
                }
                "mdia" -> {
                    for (minf in children(moov, box.payloadStart, box.start + box.size)) {
                        if (minf.type != "minf") continue
                        for (stbl in children(moov, minf.payloadStart, minf.start + minf.size)) {
                            if (stbl.type != "stbl") continue
                            for (stsd in children(moov, stbl.payloadStart, stbl.start + stbl.size)) {
                                if (stsd.type != "stsd") continue
                                val entry = boxAt(moov, stsd.payloadStart + 8, stsd.start + stsd.size)
                                    ?: continue
                                info = parseEntry(moov, entry)
                            }
                        }
                    }
                }
            }
        }
        if (trackId != -1 && info != null) result[trackId] = info
    }
    return result
}

private fun parseEntry(buf: ByteArray, entry: Mp4Box): AppleTrackInfo {
    var codec = entry.type
    var protected = entry.type == "enca" || entry.type == "encv"
    var ivSize = 0
    var crypt = 0
    var skip = 0
    var constantIv: ByteArray? = null
    var pos = entry.start + 8
    val end = entry.start + entry.size
    while (pos + 8 <= end) {
        if (!looksLikeBox(buf, pos, end)) {
            pos += 4
            continue
        }
        val child = boxAt(buf, pos, end) ?: break
        if (child.type == "sinf") {
            val sinf = parseSinf(buf, child)
            if (sinf.codec != null) codec = sinf.codec
            if (sinf.ivSize > 0) ivSize = sinf.ivSize
            crypt = sinf.crypt
            skip = sinf.skip
            constantIv = sinf.constantIv
        }
        pos += child.size
    }
    return AppleTrackInfo(
        trackId = 0,
        codec = codec,
        protectedTrack = protected,
        ivSize = ivSize,
        cryptByteBlock = crypt,
        skipByteBlock = skip,
        constantIv = constantIv,
    )
}

private fun looksLikeBox(buf: ByteArray, pos: Int, end: Int): Boolean {
    if (pos + 8 > end) return false
    val size = i32(buf, pos)
    if (size < 8 || pos + size > end) return false
    for (i in 4..7) {
        val c = buf[pos + i].toInt() and 0xFF
        if (c < 32 || c >= 127) return false
    }
    return true
}

private class SinfInfo(
    val codec: String?,
    val ivSize: Int,
    val crypt: Int,
    val skip: Int,
    val constantIv: ByteArray?,
)

private fun parseSinf(buf: ByteArray, sinf: Mp4Box): SinfInfo {
    var codec: String? = null
    var ivSize = 0
    var crypt = 0
    var skip = 0
    var constantIv: ByteArray? = null
    for (child in children(buf, sinf.payloadStart, sinf.start + sinf.size)) {
        when (child.type) {
            "frma" -> if (child.start + 4 <= buf.size) {
                codec = String(buf, child.payloadStart, 4, Charsets.US_ASCII)
            }
            "schi" -> {
                for (tenc in children(buf, child.payloadStart, child.start + child.size)) {
                    if (tenc.type != "tenc") continue
                    val p = tenc.payloadStart
                    val v = buf[p].toInt() and 0xFF
                    var q = p + 4
                    if (v >= 1) {
                        crypt = buf[q].toInt() and 0xFF
                        skip = buf[q + 1].toInt() and 0xFF
                        q += 2
                    }
                    q += 1 // isProtected
                    ivSize = buf[q].toInt() and 0xFF
                    q += 1
                    q += 16 // kid
                    if (q < tenc.start + tenc.size) {
                        val n = buf[q].toInt() and 0xFF
                        q += 1
                        if (n > 0 && q + n <= buf.size) {
                            constantIv = buf.copyOfRange(q, q + n)
                        }
                    }
                }
            }
        }
    }
    return SinfInfo(codec, ivSize, crypt, skip, constantIv)
}

// ── sample IV（解析但不参与解密） ──

private fun parseSampleIvs(
    buf: ByteArray,
    boxes: List<Mp4Box>,
    total: Int,
    init: AppleInitInfo,
): List<ByteArray?> {
    val senc = boxes.firstOrNull { it.type == "senc" }
        ?: boxes.firstOrNull { it.type == "uuid" && it.userType?.contentEquals(HE_UUID) == true }
        ?: return emptyList()
    val p = senc.payloadStart
    val flags = u32(buf, p) and 0xFFFFFF
    val count = i32(buf, p + 4).coerceAtMost(total)
    val ivLen = init.tracks.values.firstOrNull()?.ivSize?.takeIf { it > 0 } ?: 8
    var q = p + 8
    val result = mutableListOf<ByteArray?>()
    for (i in 0 until count) {
        if (flags and 0x2 != 0) {
            result += null
        } else {
            if (q + ivLen > senc.start + senc.size) break
            result += buf.copyOfRange(q, q + ivLen)
            q += ivLen
        }
    }
    while (result.size < total) result += null
    return result
}

// ── alac cookie ──

private fun findMoovBytes(raf: RandomAccessFile): ByteArray? {
    raf.seek(0)
    if (raf.length() < 8) return null
    val head = ByteArray(16)
    val read = raf.read(head)
    if (read < 8) return null
    var ftypSize = i32(head, 0)
    if (ftypSize == 1) {
        if (read < 16) return null
        ftypSize = u64(head, 8).toInt()
    }
    if (ftypSize < 8) return null
    raf.seek(ftypSize.toLong())
    val moovHead = ByteArray(16)
    if (raf.read(moovHead) < 8) return null
    val moovSize = i32(moovHead, 0)
    if (moovSize < 8) return null
    raf.seek(ftypSize.toLong())
    val data = ByteArray(moovSize)
    raf.readFully(data)
    return data
}

private fun findStbl(moov: ByteArray): ByteArray? {
    for (trak in children(moov, 8, moov.size)) {
        if (trak.type != "trak") continue
        for (mdia in children(moov, trak.payloadStart, trak.start + trak.size)) {
            if (mdia.type != "mdia") continue
            for (minf in children(moov, mdia.payloadStart, mdia.start + mdia.size)) {
                if (minf.type != "minf") continue
                for (stbl in children(moov, minf.payloadStart, minf.start + minf.size)) {
                    if (stbl.type == "stbl") return moov.copyOfRange(stbl.start, stbl.start + stbl.size)
                }
            }
        }
    }
    return null
}

internal fun findAlacCookie(moov: ByteArray): ByteArray? {
    val stbl = findStbl(moov) ?: return null
    val stsd = children(stbl, 8, stbl.size).firstOrNull { it.type == "stsd" } ?: return null
    var pos = stsd.payloadStart + 8
    val end = stsd.start + stsd.size
    while (pos + 8 <= end) {
        val entry = boxAt(stbl, pos, end) ?: break
        var q = entry.start + entry.headerSize + 28
        val entryEnd = entry.start + entry.size
        if (entry.type == "alac") q = entry.start + entry.headerSize
        while (q + 8 <= entryEnd) {
            val child = boxAt(stbl, q, entryEnd) ?: break
            if (child.type == "alac") {
                return stbl.copyOfRange(child.start, child.start + child.size)
            }
            if (child.type == "sinf") {
                for (inner in children(stbl, child.payloadStart, child.start + child.size)) {
                    if (inner.type == "alac") {
                        return stbl.copyOfRange(inner.start, inner.start + inner.size)
                    }
                }
            }
            q += child.size
        }
        pos += entry.size
    }
    return null
}

// ── 基础 box 工具 ──

private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl")
private val CONTAINERS_WITH_EDTS = CONTAINERS + "edts"

private fun boxAt(buf: ByteArray, off: Int, end: Int): Mp4Box? {
    if (off + 8 > end) return null
    var size = i32(buf, off)
    val type = String(buf, off + 4, 4, Charsets.US_ASCII)
    var header = 8
    if (size == 1) {
        if (off + 16 > end) return null
        size = u64(buf, off + 8).toInt()
        header = 16
    } else if (size == 0) {
        size = end - off
    }
    var userType: ByteArray? = null
    if (type == "uuid") {
        if (off + header + 16 > end) return null
        userType = buf.copyOfRange(off + header, off + header + 16)
        header += 16
    }
    if (size < header || off + size > end) return null
    return Mp4Box(type, userType, size, header, off, off + header)
}

private fun children(buf: ByteArray, start: Int, end: Int): List<Mp4Box> {
    val result = mutableListOf<Mp4Box>()
    var off = start
    while (off + 8 <= end) {
        val box = boxAt(buf, off, end) ?: break
        result += box
        off = box.start + box.size
    }
    return result
}

private fun findFrma(buf: ByteArray, start: Int, end: Int): Mp4Box? {
    for (box in children(buf, start, end)) {
        if (box.type == "frma") return box
        if (box.type == "sinf" || box.type == "schi") {
            findFrma(buf, box.payloadStart, box.start + box.size)?.let { return it }
        }
    }
    return null
}

private fun boxBytes(type: String, payload: ByteArray): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    writeU32(out, 8 + payload.size)
    out.write(type.toByteArray(Charsets.US_ASCII))
    out.write(payload)
    return out.toByteArray()
}

private fun fullBox(type: String, payload: ByteArray): ByteArray {
    val body = java.io.ByteArrayOutputStream()
    writeU32(body, 0)
    body.write(payload)
    return boxBytes(type, body.toByteArray())
}

private fun i32(buf: ByteArray, off: Int): Int =
    ((buf[off].toInt() and 0xFF) shl 24) or
        ((buf[off + 1].toInt() and 0xFF) shl 16) or
        ((buf[off + 2].toInt() and 0xFF) shl 8) or
        (buf[off + 3].toInt() and 0xFF)

private fun u32(buf: ByteArray, off: Int): Int = i32(buf, off)

private fun u64(buf: ByteArray, off: Int): Long =
    (i32(buf, off).toLong() shl 32) or (i32(buf, off + 4).toLong() and 0xFFFFFFFFL)

private fun writeU32(out: java.io.OutputStream, value: Int) {
    out.write((value ushr 24) and 0xFF)
    out.write((value ushr 16) and 0xFF)
    out.write((value ushr 8) and 0xFF)
    out.write(value and 0xFF)
}

private fun writeU32(raf: RandomAccessFile, value: Int) {
    raf.write((value ushr 24) and 0xFF)
    raf.write((value ushr 16) and 0xFF)
    raf.write((value ushr 8) and 0xFF)
    raf.write(value and 0xFF)
}

private fun writeU64(raf: RandomAccessFile, value: Long) {
    writeU32(raf, (value ushr 32).toInt())
    writeU32(raf, value.toInt())
}

private fun writeU32Into(buf: ByteArray, off: Int, value: Int) {
    buf[off] = (value ushr 24).toByte()
    buf[off + 1] = (value ushr 16).toByte()
    buf[off + 2] = (value ushr 8).toByte()
    buf[off + 3] = value.toByte()
}

private fun writeU64Into(buf: ByteArray, off: Int, value: Long) {
    ByteBuffer.wrap(buf, off, 8).order(ByteOrder.BIG_ENDIAN).putLong(value)
}
