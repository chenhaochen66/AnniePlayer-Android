package expo.modules.mediascanner

import android.util.Base64
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object M4AMetadataWriter {

  fun write(path: String, title: String, artist: String, album: String, lyric: String,
            coverBase64: String, coverUrl: String, albumArtist: String,
            trackNumber: String, discNumber: String, releaseDate: String) {
    val file = File(path)
    val src = file.readBytes()
    val dst = injectMeta(src, title, artist, album, lyric, coverBase64, coverUrl,
      albumArtist, trackNumber, discNumber, releaseDate)
    val tmp = File(file.path + ".tagged")
    tmp.writeBytes(dst)
    file.delete()
    tmp.renameTo(file)
  }

  // ── public entry point (called from tagAudioFile) ─────────────────────

  fun tagM4aFile(sourcePath: String, title: String, artist: String, album: String,
                 lyric: String, coverBase64: String, coverUrl: String,
                 albumArtist: String, trackNumber: String, discNumber: String,
                 releaseDate: String) {
    write(sourcePath, title, artist, album, lyric, coverBase64, coverUrl,
      albumArtist, trackNumber, discNumber, releaseDate)
  }

  // ── main ──────────────────────────────────────────────────────────────

  private fun injectMeta(src: ByteArray, title: String, artist: String, album: String,
                          lyric: String, coverBase64: String, coverUrl: String,
                          albumArtist: String, trackNumber: String, discNumber: String,
                          releaseDate: String): ByteArray {

    // Search from ftyp if present (handles files with a proprietary header before M4A)
    val ftyp = findBox(src, 0, "ftyp")
    val m4aStart = ftyp?.first ?: 0
    val moov = findBox(src, m4aStart, "moov") ?: return src
    val moovStart = moov.first
    val oldMoovSize = readBoxSize(src, moovStart)
    val moovEnd = moovStart + oldMoovSize
    val moovBody = moovStart + 8

    val udta = findBox(src, moovBody, "udta")
    val udtaStart = udta?.first ?: moovEnd
    val udtaBody = udtaStart + 8

    val meta = if (udta != null) findBox(src, udtaBody, "meta") else null
    val metaBody = if (meta != null) meta.first + 8 + 4 else moovEnd

    val ilst = if (meta != null) findBox(src, metaBody, "ilst") else null

    val ilstData = buildIlstData(title, artist, album, lyric, coverBase64, coverUrl,
      albumArtist, trackNumber, discNumber, releaseDate)

    // Reconstruct moov with new ilst inserted/replaced or udta/meta created
    val newMoov = rebuildMoov(src, moovStart, moovEnd, udta, meta, ilst, ilstData)

    val before = src.copyOfRange(0, moovStart)
    val after = src.copyOfRange(moovEnd, src.size)
    val result = before + newMoov + after

    // Adjust stco offsets if moov is before mdat and moov size changed
    val newMoovSize = newMoov.size
    val delta = newMoovSize - oldMoovSize
    if (delta != 0) {
      val mdat = findBox(src, 0, "mdat")
      if (mdat != null && mdat.first > moovStart) {
        return adjustStco(result, delta)
      }
    }
    return result
  }

  private fun rebuildMoov(src: ByteArray, moovStart: Int, moovEnd: Int,
                           udta: Pair<Int, String>?,
                           meta: Pair<Int, String>?,
                           ilst: Pair<Int, String>?,
                           ilstData: ByteArray): ByteArray {
    val moovBodyStart = moovStart + 8
    val moovBodyEnd = moovEnd

    val newIlst = box("ilst", ilstData)

    if (udta == null) {
      // No udta at all – append new udta at end of moov
      val newUdta = buildUdtaWithMetaData(ilstData)
      val body = src.copyOfRange(moovBodyStart, moovBodyEnd) + newUdta
      return box("moov", body)
    }

    val udtaStart = udta.first
    val udtaSize = readBoxSize(src, udtaStart)
    val udtaBodyStart = udtaStart + 8
    val udtaBodyEnd = udtaStart + udtaSize

    if (meta == null) {
      // udta exists, meta doesn't – insert meta into udta
      val newMeta = buildMetaWithIlst(ilstData)
      val newUdtaBody = src.copyOfRange(udtaBodyStart, udtaBodyEnd) + newMeta
      val newUdta = box("udta", newUdtaBody)
      val bodyStart = src.copyOfRange(moovBodyStart, udtaStart)
      val bodyEnd = src.copyOfRange(udtaBodyEnd, moovBodyEnd)
      return box("moov", bodyStart + newUdta + bodyEnd)
    }

    val metaStart = meta.first
    val metaSize = readBoxSize(src, metaStart)
    val metaBodyStart = metaStart + 8 + 4 // skip size+type+version+flags
    val metaBodyEnd = metaStart + metaSize

    if (ilst == null) {
      // meta exists, ilst doesn't – append ilst to meta body
      val newMetaBody = src.copyOfRange(metaBodyStart, metaBodyEnd) + newIlst
      val metaFullBox = ByteBuffer.allocate(4).putInt(0).array() + newMetaBody
      val newMeta = box("meta", metaFullBox)
      val bodyBefore = src.copyOfRange(moovBodyStart, metaStart)
      val bodyAfter = src.copyOfRange(metaBodyEnd, moovBodyEnd)
      return box("moov", bodyBefore + newMeta + bodyAfter)
    }

    // Replace existing ilst
    val ilstStart = ilst.first
    val ilstSize = readBoxSize(src, ilstStart)
    val bodyBefore = src.copyOfRange(moovBodyStart, ilstStart)
    val bodyAfter = src.copyOfRange(ilstStart + ilstSize, moovBodyEnd)
    return box("moov", bodyBefore + newIlst + bodyAfter)
  }

  // ── box parsing ───────────────────────────────────────────────────────

  private fun readBoxSize(data: ByteArray, offset: Int): Int {
    return ((data[offset].toInt() and 0xFF) shl 24) or
           ((data[offset + 1].toInt() and 0xFF) shl 16) or
           ((data[offset + 2].toInt() and 0xFF) shl 8) or
           (data[offset + 3].toInt() and 0xFF)
  }

  private fun readBoxType(data: ByteArray, offset: Int): String {
    return String(data, offset + 4, 4, Charsets.US_ASCII)
  }

  private fun box(type: String, body: ByteArray): ByteArray {
    val size = 8 + body.size
    val buf = ByteBuffer.allocate(size)
    buf.order(ByteOrder.BIG_ENDIAN)
    buf.putInt(size)
    buf.put(type.toByteArray(Charsets.ISO_8859_1))
    buf.put(body)
    return buf.array()
  }

  // returns (startOffset, type) or null
  private fun findBox(data: ByteArray, start: Int, targetType: String): Pair<Int, String>? {
    var off = start
    while (off + 8 <= data.size) {
      if (off < 0) break
      val size = readBoxSize(data, off)
      if (size < 8) break
      if (off + size > data.size) break
      val type = readBoxType(data, off)
      if (type == targetType) return Pair(off, type)
      off += size
    }
    return null
  }

  // ── metadata building ─────────────────────────────────────────────────

  private fun buildIlstData(title: String, artist: String, album: String,
                            lyric: String, coverBase64: String, coverUrl: String,
                            albumArtist: String, trackNumber: String, discNumber: String,
                            releaseDate: String): ByteArray {
    val items = mutableListOf<ByteArray>()

    if (title.isNotEmpty()) items.add(ilstItem("\u00A9nam", textData(title)))
    if (artist.isNotEmpty()) items.add(ilstItem("\u00A9ART", textData(artist)))
    if (album.isNotEmpty()) items.add(ilstItem("\u00A9alb", textData(album)))
    if (albumArtist.isNotEmpty()) items.add(ilstItem("aART", textData(albumArtist)))
    if (releaseDate.isNotEmpty()) items.add(ilstItem("\u00A9day", textData(releaseDate)))
    if (lyric.isNotEmpty()) items.add(ilstItem("\u00A9lyr", textData(lyric)))

    if (trackNumber.isNotEmpty()) {
      val tn = trackNumber.toIntOrNull() ?: 0
      items.add(ilstItem("trkn", binaryData(tn)))
    }
    if (discNumber.isNotEmpty()) {
      val dn = discNumber.toIntOrNull() ?: 0
      items.add(ilstItem("disk", binaryData(dn)))
    }

    if (coverBase64.isNotEmpty()) {
      val cb = Base64.decode(coverBase64, Base64.DEFAULT)
      val isPng = coverUrl.contains("png", ignoreCase = true)
      items.add(ilstItem("covr", coverData(cb, isPng)))
    }

    val total = items.sumOf { it.size }
    val buf = ByteBuffer.allocate(total)
    for (item in items) buf.put(item)
    return buf.array()
  }

  private fun ilstItem(key: String, value: ByteArray): ByteArray {
    return box(key, value)
  }

  private fun textData(value: String): ByteArray {
    val bytes = value.toByteArray(Charsets.UTF_8)
    return box("data", ByteBuffer.allocate(8 + bytes.size).apply {
      order(ByteOrder.BIG_ENDIAN)
      putInt(1)  // type: UTF-8 text
      putInt(0)  // locale
      put(bytes)
    }.array())
  }

  private fun binaryData(value: Int): ByteArray {
    // trkn/disk format: 00 00 00 00 00 NN 00 00
    val body = ByteArray(8).apply {
      this[5] = value.toByte()
    }
    return box("data", ByteBuffer.allocate(8 + body.size).apply {
      order(ByteOrder.BIG_ENDIAN)
      putInt(0)  // type: implicit (binary)
      putInt(0)  // locale
      put(body)
    }.array())
  }

  private fun coverData(bytes: ByteArray, isPng: Boolean): ByteArray {
    return box("data", ByteBuffer.allocate(8 + bytes.size).apply {
      order(ByteOrder.BIG_ENDIAN)
      putInt(0)   // type 0 = binary data (JPEG/PNG auto-detected from magic)
      putInt(0)   // locale
      put(bytes)
    }.array())
  }

  // ── udta builder (used when udta or meta doesn't exist) ───────────────

  private fun buildUdtaWithMetaData(ilstData: ByteArray): ByteArray {
    return box("udta", buildMetaWithIlst(ilstData))
  }

  private fun buildMetaWithIlst(ilstData: ByteArray): ByteArray {
    val hdlrBody = ByteBuffer.allocate(25).apply {
      order(ByteOrder.BIG_ENDIAN)
      putInt(0)      // version(1)+flags(3)
      putInt(0)      // predefined
      put("mdir".toByteArray(Charsets.US_ASCII)) // handler type
      putInt(0)      // reserved
      putInt(0)      // reserved
      put(0)         // name (empty)
    }
    val metaBody = ByteBuffer.allocate(4).putInt(0).array() + // version(1)+flags(3)
      box("hdlr", hdlrBody.array()) +
      box("ilst", ilstData)
    return box("meta", metaBody)
  }

  // ── stco / co64 adjustment ────────────────────────────────────────────

  private fun adjustStco(data: ByteArray, delta: Int): ByteArray {
    val result = data.copyOf()
    val moov = findBox(data, 0, "moov") ?: return result
    val moovSize = readBoxSize(data, moov.first)
    adjustStcoRecursive(result, moov.first, moov.first + moovSize, delta)
    return result
  }

  private fun adjustStcoRecursive(data: ByteArray, rangeStart: Int, rangeEnd: Int, delta: Int) {
    var off = rangeStart
    while (off + 8 <= rangeEnd) {
      val size = readBoxSize(data, off)
      if (size < 8) break
      val end = off + size
      if (end > rangeEnd) break
      val type = readBoxType(data, off)
      when (type) {
        "stco" -> adjustStcoEntries(data, off, delta)
        "co64" -> adjustCo64Entries(data, off, delta)
        else -> if (end <= rangeEnd) adjustStcoRecursive(data, off + 8, end, delta)
      }
      off = end
    }
  }

  private fun adjustStcoEntries(data: ByteArray, boxStart: Int, delta: Int) {
    val bodyStart = boxStart + 8 + 4
    if (bodyStart + 4 > data.size) return
    val count = ((data[bodyStart].toInt() and 0xFF) shl 24) or
                ((data[bodyStart + 1].toInt() and 0xFF) shl 16) or
                ((data[bodyStart + 2].toInt() and 0xFF) shl 8) or
                (data[bodyStart + 3].toInt() and 0xFF)
    var pos = bodyStart + 4
    for (i in 0 until count) {
      if (pos + 4 > data.size) break
      val oldOff = ((data[pos].toInt() and 0xFF) shl 24) or
                   ((data[pos + 1].toInt() and 0xFF) shl 16) or
                   ((data[pos + 2].toInt() and 0xFF) shl 8) or
                   (data[pos + 3].toInt() and 0xFF)
      val newOff = oldOff + delta
      data[pos] = ((newOff shr 24) and 0xFF).toByte()
      data[pos + 1] = ((newOff shr 16) and 0xFF).toByte()
      data[pos + 2] = ((newOff shr 8) and 0xFF).toByte()
      data[pos + 3] = (newOff and 0xFF).toByte()
      pos += 4
    }
  }

  private fun adjustCo64Entries(data: ByteArray, boxStart: Int, delta: Int) {
    val bodyStart = boxStart + 8 + 4
    if (bodyStart + 4 > data.size) return
    val count = ((data[bodyStart].toInt() and 0xFF) shl 24) or
                ((data[bodyStart + 1].toInt() and 0xFF) shl 16) or
                ((data[bodyStart + 2].toInt() and 0xFF) shl 8) or
                (data[bodyStart + 3].toInt() and 0xFF)
    var pos = bodyStart + 4
    for (i in 0 until count) {
      if (pos + 8 > data.size) break
      val oldOff = ((data[pos].toLong() and 0xFF) shl 56) or
                   ((data[pos + 1].toLong() and 0xFF) shl 48) or
                   ((data[pos + 2].toLong() and 0xFF) shl 40) or
                   ((data[pos + 3].toLong() and 0xFF) shl 32) or
                   ((data[pos + 4].toLong() and 0xFF) shl 24) or
                   ((data[pos + 5].toLong() and 0xFF) shl 16) or
                   ((data[pos + 6].toLong() and 0xFF) shl 8) or
                   (data[pos + 7].toLong() and 0xFF)
      val newOff = oldOff + delta
      data[pos] = ((newOff shr 56).toInt() and 0xFF).toByte()
      data[pos + 1] = ((newOff shr 48).toInt() and 0xFF).toByte()
      data[pos + 2] = ((newOff shr 40).toInt() and 0xFF).toByte()
      data[pos + 3] = ((newOff shr 32).toInt() and 0xFF).toByte()
      data[pos + 4] = ((newOff shr 24).toInt() and 0xFF).toByte()
      data[pos + 5] = ((newOff shr 16).toInt() and 0xFF).toByte()
      data[pos + 6] = ((newOff shr 8).toInt() and 0xFF).toByte()
      data[pos + 7] = (newOff and 0xFF).toInt().toByte()
      pos += 8
    }
  }
}
