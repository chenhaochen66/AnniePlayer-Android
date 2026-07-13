package expo.modules.mediascanner

import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

class MediaScannerModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("MediaScanner")

    AsyncFunction("scanFile") { path: String ->
      val context = appContext.reactContext ?: throw Exception("reactContext is null")
      val file = File(path)
      if (!file.exists()) throw Exception("File not found: $path")
      val displayName = file.name

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val mimeType = when {
          displayName.endsWith(".flac") -> "audio/flac"
          displayName.endsWith(".mp3") -> "audio/mpeg"
          displayName.endsWith(".m4a") -> "audio/mp4"
          else -> "audio/*"
        }

        val values = ContentValues().apply {
          put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
          put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
          put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Annieplayer")
          put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val newUri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
        if (newUri != null) {
          FileInputStream(file).use { input ->
            context.contentResolver.openOutputStream(newUri)?.use { output ->
              input.copyTo(output)
            }
          }
          values.clear()
          values.put(MediaStore.Audio.Media.IS_PENDING, 0)
          context.contentResolver.update(newUri, values, null, null)
        }
        newUri?.toString()
      } else {
        android.media.MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
        null
      }
    }

    AsyncFunction("tagAudioFile") { sourcePath: String, title: String, artist: String, album: String?, lyric: String, coverBase64: String?, coverUrl: String, extra: Map<String, String>? ->
      tagAudioFile(sourcePath, title, artist, album ?: "", lyric ?: "", coverBase64 ?: "", coverUrl ?: "",
        extra?.get("albumArtist") ?: "",
        extra?.get("trackNumber") ?: "",
        extra?.get("discNumber") ?: "",
        extra?.get("releaseDate") ?: "")
    }
  }

  // ── public entry point ──────────────────────────────────────────────

  @Throws(Exception::class)
  fun tagAudioFile(sourcePath: String, title: String, artist: String, album: String, lyric: String, coverBase64: String, coverUrl: String, albumArtist: String = "", trackNumber: String = "", discNumber: String = "", releaseDate: String = ""): String {
    val sourceFile = File(sourcePath)
    if (!sourceFile.exists()) throw Exception("File not found: $sourcePath")
    val tempOut = File(sourcePath + ".tagged")
    try {
      val magic = ByteArray(4)
      FileInputStream(sourceFile).use { fis -> DataInputStream(fis).readFully(magic) }

      if (magic[0] == 'f'.code.toByte() && magic[1] == 'L'.code.toByte() && magic[2] == 'a'.code.toByte() && magic[3] == 'C'.code.toByte()) {
        FileInputStream(sourceFile).use { fis ->
          val input = DataInputStream(fis)
          input.readFully(ByteArray(4)) // skip "fLaC"
          tempOut.outputStream().buffered(65536).use { output ->
            output.write("fLaC".toByteArray())
            tagFlac(input, output, title, artist, album, lyric, coverBase64, coverUrl, albumArtist, trackNumber, discNumber, releaseDate)
          }
        }
      } else if (magic[0] == 'I'.code.toByte() && magic[1] == 'D'.code.toByte() && magic[2] == '3'.code.toByte()) {
        FileInputStream(sourceFile).use { fis ->
          val input = DataInputStream(fis)
          input.readFully(ByteArray(3)) // skip "ID3"
          tempOut.outputStream().buffered(65536).use { output ->
            tagMp3(input, output, title, artist, album, lyric, coverBase64, coverUrl, albumArtist, trackNumber, discNumber, releaseDate)
          }
        }
      } else {
        // MP3 without existing ID3 tag – write new tag then copy all audio
        FileInputStream(sourceFile).use { fis ->
          val input = DataInputStream(fis)
          tempOut.outputStream().buffered(65536).use { output ->
            tagMp3NoTag(input, output, title, artist, album, lyric, coverBase64, coverUrl, albumArtist, trackNumber, discNumber, releaseDate)
          }
        }
      }

      sourceFile.delete()
      tempOut.renameTo(sourceFile)
    } catch (e: Exception) {
      tempOut.delete()
      throw e
    }
    return sourcePath
  }

  // ── FLAC ────────────────────────────────────────────────────────────

  private fun tagFlac(input: DataInputStream, output: BufferedOutputStream, title: String, artist: String, album: String, lyric: String, coverBase64: String, coverUrl: String, albumArtist: String, trackNumber: String, discNumber: String, releaseDate: String) {
    val siHeader = ByteArray(4).also { input.readFully(it) }
    val siData = ByteArray(34).also { input.readFully(it) }
    val streamInfoWasLast = (siHeader[0].toInt() and 0x80) != 0
    siHeader[0] = (siHeader[0].toInt() and 0x7F).toByte()
    output.write(siHeader)
    output.write(siData)

    if (!streamInfoWasLast) {
      while (true) {
        val h = ByteArray(4).also { input.readFully(it) }
        val isLast = (h[0].toInt() and 0x80) != 0
        val len = ((h[1].toInt() and 0xFF) shl 16) or ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
        skipFully(input, len.toLong())
        if (isLast) break
      }
    }

    val hasPic = coverBase64.isNotEmpty()
    val vorbisData = buildVorbisComment(title, artist, album, lyric, albumArtist, trackNumber, discNumber, releaseDate)
    output.write(flacBlockHeader(4, vorbisData.size, !hasPic))
    output.write(vorbisData)

    if (hasPic) {
      val coverBytes = Base64.decode(coverBase64, Base64.DEFAULT)
      val picData = buildPictureBlock(coverBytes, coverUrl)
      output.write(flacBlockHeader(6, picData.size, true))
      output.write(picData)
    }

    input.copyTo(output, 65536)
  }

  // ── MP3 (with existing ID3 tag) ─────────────────────────────────────

  private fun tagMp3(input: DataInputStream, output: BufferedOutputStream, title: String, artist: String, album: String, lyric: String, coverBase64: String, coverUrl: String, albumArtist: String, trackNumber: String, discNumber: String, releaseDate: String) {
    val rest = ByteArray(7).also { input.readFully(it) }
    val tagSize = ((rest[3].toInt() and 0x7F) shl 21) or
                  ((rest[4].toInt() and 0x7F) shl 14) or
                  ((rest[5].toInt() and 0x7F) shl 7) or
                  (rest[6].toInt() and 0x7F)
    skipFully(input, tagSize.toLong())

    val id3 = buildID3v2Tag(title, artist, album, lyric, coverBase64, coverUrl, albumArtist, trackNumber, discNumber, releaseDate)
    output.write(id3)
    input.copyTo(output, 65536)
  }

  // ── MP3 (without existing ID3 tag) ──────────────────────────────────

  private fun tagMp3NoTag(input: DataInputStream, output: BufferedOutputStream, title: String, artist: String, album: String, lyric: String, coverBase64: String, coverUrl: String, albumArtist: String, trackNumber: String, discNumber: String, releaseDate: String) {
    val id3 = buildID3v2Tag(title, artist, album, lyric, coverBase64, coverUrl, albumArtist, trackNumber, discNumber, releaseDate)
    output.write(id3)
    input.copyTo(output, 65536)
  }

  // ── VORBIS_COMMENT ──────────────────────────────────────────────────

  private fun buildVorbisComment(title: String, artist: String, album: String, lyric: String, albumArtist: String, trackNumber: String, discNumber: String, releaseDate: String): ByteArray {
    val vendor = "reference libFLAC 1.4.3".toByteArray(Charsets.UTF_8)
    val fields = mutableListOf<String>().apply {
      add("TITLE=$title"); add("ARTIST=$artist")
      if (album.isNotEmpty()) add("ALBUM=$album")
      if (albumArtist.isNotEmpty()) add("ALBUMARTIST=$albumArtist")
      if (trackNumber.isNotEmpty()) add("TRACKNUMBER=$trackNumber")
      if (discNumber.isNotEmpty()) add("DISCNUMBER=$discNumber")
      if (releaseDate.isNotEmpty()) add("DATE=$releaseDate")
      if (lyric.isNotEmpty()) add("LYRICS=$lyric")
    }
    val fbs = fields.map { it.toByteArray(Charsets.UTF_8) }
    var size = 4 + vendor.size + 4 + fbs.sumOf { 4 + it.size }
    val data = ByteArray(size)
    var p = 0
    p = writeLE32(vendor.size, data, p); vendor.copyInto(data, p); p += vendor.size
    p = writeLE32(fields.size, data, p)
    for (fb in fbs) { p = writeLE32(fb.size, data, p); fb.copyInto(data, p); p += fb.size }
    return data
  }

  // ── PICTURE block for FLAC ──────────────────────────────────────────

  private fun buildPictureBlock(coverBytes: ByteArray, coverUrl: String): ByteArray {
    val mime = if (coverUrl.contains("png", ignoreCase = true)) "image/png" else "image/jpeg"
    val mimeB = mime.toByteArray(Charsets.US_ASCII)
    val desc = ByteArray(0)

    var w = 0; var h = 0
    if (coverBytes.size > 10 && coverBytes[0] == 0xFF.toByte() && coverBytes[1] == 0xD8.toByte()) {
      var j = 2
      while (j < coverBytes.size - 8) {
        if (coverBytes[j] != 0xFF.toByte()) { j++; continue }
        val marker = coverBytes[j + 1].toInt() and 0xFF
        if (marker in 0xC0..0xC3) {
          h = ((coverBytes[j + 5].toInt() and 0xFF) shl 8) or (coverBytes[j + 6].toInt() and 0xFF)
          w = ((coverBytes[j + 7].toInt() and 0xFF) shl 8) or (coverBytes[j + 8].toInt() and 0xFF); break
        }
        if (marker == 0xD9 || marker == 0xDA) break
        val sl = ((coverBytes[j + 2].toInt() and 0xFF) shl 8) or (coverBytes[j + 3].toInt() and 0xFF)
        if (sl < 2) break; j += sl + 2
      }
    }

    val data = ByteArray(4 + 4 + mimeB.size + 4 + desc.size + 4 + 4 + 4 + 4 + 4 + coverBytes.size)
    var p = 0
    p = writeBE32(3, data, p); p = writeBE32(mimeB.size, data, p); mimeB.copyInto(data, p); p += mimeB.size
    p = writeBE32(desc.size, data, p); desc.copyInto(data, p); p += desc.size
    p = writeBE32(w, data, p); p = writeBE32(h, data, p)
    p = writeBE32(24, data, p); p = writeBE32(0, data, p); p = writeBE32(coverBytes.size, data, p)
    coverBytes.copyInto(data, p)
    return data
  }

  // ── FLAC block header ───────────────────────────────────────────────

  private fun flacBlockHeader(type: Int, length: Int, isLast: Boolean): ByteArray {
    return byteArrayOf(
      ((if (isLast) 0x80 else 0) or (type and 0x7F)).toByte(),
      ((length shr 16) and 0xFF).toByte(),
      ((length shr 8) and 0xFF).toByte(),
      (length and 0xFF).toByte()
    )
  }

  // ── ID3v2 tag builder ────────────────────────────────────────────────

  private fun buildID3v2Tag(title: String, artist: String, album: String, lyric: String, coverBase64: String, coverUrl: String, albumArtist: String, trackNumber: String, discNumber: String, releaseDate: String): ByteArray {
    val frames = mutableListOf<ByteArray>()
    txtFrame("TIT2", title)?.let { frames.add(it) }
    txtFrame("TPE1", artist)?.let { frames.add(it) }
    if (album.isNotEmpty()) txtFrame("TALB", album)?.let { frames.add(it) }
    if (albumArtist.isNotEmpty()) txtFrame("TPE2", albumArtist)?.let { frames.add(it) }
    if (trackNumber.isNotEmpty()) txtFrame("TRCK", trackNumber)?.let { frames.add(it) }
    if (discNumber.isNotEmpty()) txtFrame("TPOS", discNumber)?.let { frames.add(it) }
    if (releaseDate.isNotEmpty()) txtFrame("TDRC", releaseDate)?.let { frames.add(it) }
    if (coverBase64.isNotEmpty()) {
      val cb = Base64.decode(coverBase64, Base64.DEFAULT)
      frames.add(apicFrame(coverUrl, cb))
    }
    if (lyric.isNotEmpty()) usltFrame(lyric)?.let { frames.add(it) }
    if (frames.isEmpty()) return ByteArray(0)

    val total = frames.sumOf { it.size }
    val h = ByteArray(10).apply {
      this[0] = 0x49; this[1] = 0x44; this[2] = 0x33; this[3] = 3; this[4] = 0
      this[6] = ((total shr 21) and 0x7F).toByte()
      this[7] = ((total shr 14) and 0x7F).toByte()
      this[8] = ((total shr 7) and 0x7F).toByte()
      this[9] = (total and 0x7F).toByte()
    }
    val r = ByteArray(10 + total)
    h.copyInto(r); var off = 10
    for (f in frames) { f.copyInto(r, off); off += f.size }
    return r
  }

  private fun txtFrame(id: String, text: String): ByteArray? {
    if (text.isEmpty()) return null
    val d = encUtf16(text)
    val fd = ByteArray(1 + d.size).apply { this[0] = 1; d.copyInto(this, 1) }
    return createID3Frame(id, fd)
  }

  private fun apicFrame(coverUrl: String, coverBytes: ByteArray): ByteArray {
    val mime = if (coverUrl.contains("png", ignoreCase = true)) "image/png" else "image/jpeg"
    val mb = mime.toByteArray(Charsets.US_ASCII)
    val desc = ByteArray(0)
    val fd = ByteArray(1 + mb.size + 1 + 1 + 1 + desc.size + coverBytes.size)
    var p = 0
    fd[p++] = 0; mb.copyInto(fd, p); p += mb.size; fd[p++] = 0; fd[p++] = 3; fd[p++] = 0
    desc.copyInto(fd, p); p += desc.size; coverBytes.copyInto(fd, p)
    return createID3Frame("APIC", fd)
  }

  private fun usltFrame(lyric: String): ByteArray? {
    if (lyric.isEmpty()) return null
    val d = encUtf16(lyric)
    val lang = byteArrayOf('c'.code.toByte(), 'h'.code.toByte(), 'i'.code.toByte())
    val desc = encUtf16("")
    val fd = ByteArray(1 + lang.size + desc.size + d.size)
    var p = 0
    fd[p++] = 1; lang.copyInto(fd, p); p += lang.size; desc.copyInto(fd, p); p += desc.size; d.copyInto(fd, p)
    return createID3Frame("USLT", fd)
  }

  private fun createID3Frame(frameId: String, data: ByteArray): ByteArray {
    val id = frameId.toByteArray(Charsets.US_ASCII)
    val r = ByteArray(10 + data.size)
    id.copyInto(r)
    r[4] = ((data.size shr 24) and 0xFF).toByte()
    r[5] = ((data.size shr 16) and 0xFF).toByte()
    r[6] = ((data.size shr 8) and 0xFF).toByte()
    r[7] = (data.size and 0xFF).toByte()
    r[8] = 0; r[9] = 0
    data.copyInto(r, 10)
    return r
  }

  private fun encUtf16(s: String): ByteArray {
    val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    val enc = s.toByteArray(Charsets.UTF_16LE)
    val nul = byteArrayOf(0x00.toByte(), 0x00.toByte())
    return bom + enc + nul
  }

  // ── helpers ──────────────────────────────────────────────────────────

  private fun skipFully(input: DataInputStream, n: Long) {
    var remaining = n
    while (remaining > 0) remaining -= input.skip(remaining)
  }

  private fun writeLE32(v: Int, d: ByteArray, off: Int): Int {
    d[off] = (v and 0xFF).toByte()
    d[off + 1] = ((v shr 8) and 0xFF).toByte()
    d[off + 2] = ((v shr 16) and 0xFF).toByte()
    d[off + 3] = ((v shr 24) and 0xFF).toByte()
    return off + 4
  }

  private fun writeBE32(v: Int, d: ByteArray, off: Int): Int {
    d[off] = ((v shr 24) and 0xFF).toByte()
    d[off + 1] = ((v shr 16) and 0xFF).toByte()
    d[off + 2] = ((v shr 8) and 0xFF).toByte()
    d[off + 3] = (v and 0xFF).toByte()
    return off + 4
  }
}
