package com.annieplayer.annie_music.kt.data.local.tag

import java.io.File

/**
 * 一种容器格式的标签读写实现。
 *
 * 实现约束（为了能在 JVM 上跑单测，也是为了让代码可复用）：
 * - **不依赖任何 Android API**，只用 `java.io` / `java.nio`；
 * - [write] 必须自己保证原子性：要么改成功，要么文件保持原样（内部用临时文件 + rename 或原地补丁 + fsync）；
 * - [write] 只覆盖 [TagBag] 里**给了的**字段，没给的保留文件里的原值。
 */
interface TagCodec {

    /** 人类可读的格式名（日志/结果用）。 */
    val format: String

    /** 只看文件头判断是不是本格式。 */
    fun sniff(head: ByteArray): Boolean

    /** 读标签；不是本格式返回 null。 */
    fun read(file: File): TagBag?

    fun write(file: File, bag: TagBag, policy: WritePolicy): WriteResult
}

// ================================================================================================
// 共用小工具（各 codec 都要，放在这里避免三份）
// ================================================================================================

internal fun File.readHead(count: Int): ByteArray {
    val buf = ByteArray(count)
    inputStream().use { input ->
        var read = 0
        while (read < count) {
            val n = input.read(buf, read, count - read)
            if (n <= 0) break
            read += n
        }
        if (read < count) return buf.copyOf(read)
    }
    return buf
}

internal fun ByteArray.u32be(off: Int): Int =
    ((this[off].toInt() and 0xFF) shl 24) or
        ((this[off + 1].toInt() and 0xFF) shl 16) or
        ((this[off + 2].toInt() and 0xFF) shl 8) or
        (this[off + 3].toInt() and 0xFF)

internal fun ByteArray.u32le(off: Int): Int =
    (this[off].toInt() and 0xFF) or
        ((this[off + 1].toInt() and 0xFF) shl 8) or
        ((this[off + 2].toInt() and 0xFF) shl 16) or
        ((this[off + 3].toInt() and 0xFF) shl 24)

internal fun ByteArray.u16be(off: Int): Int =
    ((this[off].toInt() and 0xFF) shl 8) or (this[off + 1].toInt() and 0xFF)

internal fun ByteArray.ascii(off: Int, len: Int): String =
    String(this, off, len, Charsets.ISO_8859_1)

internal fun ByteArray.bytes(off: Int, len: Int): ByteArray = copyOfRange(off, off + len)

/** ID3 的 synchsafe 整数（每字节只留低 7 位）。 */
internal fun synchsafe(v: Int): Int =
    ((v and 0x7F) shl 0) or (((v shr 7) and 0x7F) shl 8) or
        (((v shr 14) and 0x7F) shl 16) or (((v shr 21) and 0x7F) shl 24)

internal fun unsynchsafe(v: Int): Int =
    (v and 0x7F) or ((v shr 8) and 0x7F shl 7) or ((v shr 16) and 0x7F shl 14) or ((v shr 24) and 0x7F shl 21)

internal fun ByteArray.toIntArray(): IntArray = IntArray(size) { this[it].toInt() and 0xFF }

internal fun Int.toU32be(): ByteArray = byteArrayOf(
    ((this ushr 24) and 0xFF).toByte(),
    ((this ushr 16) and 0xFF).toByte(),
    ((this ushr 8) and 0xFF).toByte(),
    (this and 0xFF).toByte(),
)

internal fun Int.toU32le(): ByteArray = byteArrayOf(
    (this and 0xFF).toByte(),
    ((this ushr 8) and 0xFF).toByte(),
    ((this ushr 16) and 0xFF).toByte(),
    ((this ushr 24) and 0xFF).toByte(),
)

internal fun Int.toU16be(): ByteArray = byteArrayOf(
    ((this ushr 8) and 0xFF).toByte(),
    (this and 0xFF).toByte(),
)

internal fun Int.synchsafeBytes(): ByteArray = byteArrayOf(
    ((this ushr 21) and 0x7F).toByte(),
    ((this ushr 14) and 0x7F).toByte(),
    ((this ushr 7) and 0x7F).toByte(),
    (this and 0x7F).toByte(),
)

/** 从 "3/12" 这种写法里取两个整数。 */
internal fun parseSlashPair(value: String?): Pair<Int?, Int?> {
    if (value.isNullOrBlank()) return null to null
    val parts = value.split('/')
    return parts[0].trim().toIntOrNull() to parts.getOrNull(1)?.trim()?.toIntOrNull()
}

internal fun formatSlashPair(first: Int?, second: Int?): String? = when {
    first == null && second == null -> null
    second == null -> first?.toString()
    first == null -> "/$second"
    else -> "$first/$second"
}

/**
 * 把 [newBytes] 就地写进 [file] 的 [offset] 处（长度必须一致），并强制落盘。
 *
 * 原地写是这套库的核心收益：FLAC 复用 PADDING、MP3 复用 ID3 的 padding、
 * MP4 把旧 moov 改成 free —— 全都只碰几 KB，音频一个字节都不搬。
 */
internal fun patchInPlace(file: File, offset: Long, bytes: ByteArray) {
    java.io.RandomAccessFile(file, "rw").use { raf ->
        raf.seek(offset)
        raf.write(bytes)
        raf.channel.force(true)
    }
}

/** 临时文件 + 原子 rename；任何异常都保证原文件不受影响。 */
internal inline fun writeAtomically(file: File, crossinline body: (java.io.OutputStream) -> Unit): Boolean {
    val tmp = File(file.parentFile, "${file.name}.annietag.tmp")
    return try {
        java.io.FileOutputStream(tmp).use { out ->
            java.io.BufferedOutputStream(out, 256 * 1024).use { buf -> body(buf) }
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        true
    } catch (_: Throwable) {
        runCatching { tmp.delete() }
        false
    }
}
