package com.annieplayer.annie_music.kt.data.sources.kkbox

import com.annieplayer.annie_music.kt.data.network.globalOkHttp
import com.annieplayer.annie_music.kt.data.network.retryUntilSuccess
import okhttp3.Request
import java.io.File

// ================================================================================================
// KKBOX 音频：解密 / 下载 / 流式解密写盘（纯 Kotlin，替代原生 KkboxDecoder）
// ================================================================================================

/** RC4 drop=512 解密音频数据 */
fun decryptAudio(data: ByteArray, licContentKey: String): ByteArray =
    kkboxRc4(licContentKey, data, 512)

/** 下载并解密音频，返回解密后的字节（Range: bytes=1024- 跳过 KKBOX 包裹头） */
suspend fun downloadKkboxAudio(url: String, licContentKey: String): ByteArray {
    val pair = retryUntilSuccess {
        val req = Request.Builder().url(url)
            .header("User-Agent", kkboxUa)
            .header("Range", "bytes=1024-")
            .build()
        val resp = globalOkHttp.newCall(req).execute()
        resp.use { r -> r.code to (r.body?.bytes() ?: ByteArray(0)) }
    }
    val status = pair.first
    if (status < 200 || status >= 300) throw IllegalStateException("下载失败: $status")
    return decryptAudio(pair.second, licContentKey)
}

/**
 * 流式下载 + RC4(drop=512) 分块解密写文件（纯 Kotlin，无原生）。
 *
 * [onProgress] 回调范围 0..1，语义对齐 RN 版 JS 回退分支。
 */
suspend fun streamDecryptKkboxAudio(
    url: String,
    licContentKey: String,
    outputPath: String,
    onProgress: ((Double) -> Unit)? = null,
) {
    onProgress?.invoke(0.1)

    val path = if (outputPath.startsWith("file://")) outputPath.substring(7) else outputPath
    val file = File(path)
    val parent = file.parentFile
    if (parent != null && !parent.exists()) {
        parent.mkdirs()
    }

    val resp = retryUntilSuccess {
        val req = Request.Builder().url(url)
            .header("User-Agent", kkboxUa)
            .header("Range", "bytes=1024-")
            .build()
        globalOkHttp.newCall(req).execute()
    }
    resp.use { r ->
        val status = r.code
        if (status < 200 || status >= 300) throw IllegalStateException("下载失败: $status")

        val total = r.header("Content-Length")?.toLongOrNull() ?: 0L
        val rc4 = KkboxRc4(licContentKey, 512)
        val out = file.outputStream()
        var received = 0
        try {
            val input = r.body?.byteStream()
            if (input != null) {
                input.use { ins ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = ins.read(buffer)
                        if (n <= 0) break
                        val chunk = if (n == buffer.size) buffer.copyOf() else buffer.copyOf(n)
                        out.write(rc4.process(chunk))
                        received += n
                        if (total > 0) {
                            onProgress?.invoke(
                                0.4 + 0.5 * (received.toDouble() / total.toDouble()),
                            )
                        }
                    }
                }
            }
        } finally {
            out.flush()
            out.close()
        }
    }
    onProgress?.invoke(1.0)
}
