package com.annieplayer.annie_music.kt.data.sources.kkbox

import com.annieplayer.annie_music.kt.data.network.globalOkHttp
import com.annieplayer.annie_music.kt.data.network.retryUntilSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    val pair = withContext(Dispatchers.IO) {
        retryUntilSuccess {
            val req = Request.Builder().url(url)
                .header("User-Agent", kkboxUa)
                .header("Range", "bytes=1024-")
                .build()
            val resp = globalOkHttp.newCall(req).execute()
            resp.use { r -> r.code to (r.body?.bytes() ?: ByteArray(0)) }
        }
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
    onTotal: ((Long) -> Unit)? = null,
    onProgress: ((Double) -> Unit)? = null,
) {
    onProgress?.invoke(0.1)

    val path = if (outputPath.startsWith("file://")) outputPath.substring(7) else outputPath
    val file = File(path)
    val parent = file.parentFile
    if (parent != null && !parent.exists()) {
        parent.mkdirs()
    }

    withContext(Dispatchers.IO) {
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
            // 解密后的文件长度 = 本次响应的字节数（请求 Range 从 1024 起，被跳过的包裹头不落盘）
            if (total > 0) onTotal?.invoke(total)
            val rc4 = KkboxRc4(licContentKey, 512)
            val out = file.outputStream()
            var received = 0
            // 进度回调按 1% / 256KB 节流：每块都回调会把 Compose 状态更新压到几千次，下载反而更慢
            var lastReported = -1
            try {
                val input = r.body?.byteStream()
                if (input != null) {
                    input.use { ins ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = ins.read(buffer)
                            if (n <= 0) break
                            rc4.processInPlace(buffer, 0, n)
                            out.write(buffer, 0, n)
                            received += n
                            val percent = if (total > 0) (received * 100 / total).toInt() else -1
                            if (percent != lastReported) {
                                lastReported = percent
                                onProgress?.invoke(
                                    if (total > 0) 0.4 + 0.5 * (received.toDouble() / total.toDouble()) else 0.6,
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
    }
    onProgress?.invoke(1.0)
}
