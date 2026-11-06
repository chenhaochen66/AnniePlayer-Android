package com.annieplayer.annie_music.kt.core.platform

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * 下载落点：一个「写完就能用」的字节出口。
 *
 * ## 为什么要有这一层
 * 以前下载是「先落私有临时文件 → 打标 → 再整份复制进媒体库」，一首 100MB 的无损白白
 * 多读多写一遍。现在下载字节**直接写进最终落点**，打标在同一个流里完成（见
 * `StreamTag`），所以既没有临时文件，也没有入库复制。
 *
 * ## 为什么必须支持 [patch]
 * RIFF / FORM / DSF / DSDIFF 的长度字段写在**文件里**，而流式写入时只有最后一个字节落地
 * 才知道最终长度，只能事后回填。MediaStore 条目靠 `openFileDescriptor(uri, "rw")`
 * 拿到可随机写的 fd，和真实文件走同一条路。
 */
interface DownloadSink {

    /** 写入出口。调用方负责 flush；[commit] 里也会兜一次。 */
    val output: OutputStream

    /** 落定并返回对外地址（真实文件绝对路径 / `content://` URI）。 */
    fun commit(): String

    /** 回滚：删掉半成品，不留 pending 条目。 */
    fun abort()

    /** 回填 [offset] 处的定长字段。失败抛异常，由调用方 [abort] 回滚。 */
    fun patch(offset: Long, bytes: ByteArray)
}

/**
 * 真实文件落点（用户自定义下载目录，或 Android 9 及以下的公共音乐目录）。
 *
 * 输出流故意**不加缓冲**：缓冲大小由调用方决定（`StreamTag` 与 Apple 封装都按 256KB 包一层），
 * 这里再套一层只会多一次无谓的内存拷贝。
 */
class FileDownloadSink(private val file: File) : DownloadSink {

    private val stream = FileOutputStream(file)

    override val output: OutputStream get() = stream

    override fun commit(): String {
        runCatching { stream.fd.sync() }
        runCatching { stream.close() }
        return file.absolutePath
    }

    override fun abort() {
        runCatching { stream.close() }
        runCatching { if (file.exists()) file.delete() }
    }

    override fun patch(offset: Long, bytes: ByteArray) {
        runCatching { stream.flush() }
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(offset)
            raf.write(bytes)
        }
    }
}
