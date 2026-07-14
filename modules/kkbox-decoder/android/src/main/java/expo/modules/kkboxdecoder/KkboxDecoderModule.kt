package expo.modules.kkboxdecoder

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class KkboxDecoderModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("KkboxDecoder")

    AsyncFunction("decryptKkboxAudio") { sourceUrl: String, licContentKey: String, outputPath: String ->
      decryptKkboxAudio(sourceUrl, licContentKey, outputPath)
    }
  }

  fun decryptKkboxAudio(sourceUrl: String, licContentKey: String, outputPath: String): String {
    val file = File(outputPath)
    file.parentFile?.mkdirs()

    val client = OkHttpClient.Builder()
      .connectTimeout(60, TimeUnit.SECONDS)
      .readTimeout(120, TimeUnit.SECONDS)
      .build()

    val request = Request.Builder()
      .url(sourceUrl)
      .header("User-Agent", "okhttp/3.14.9")
      .header("Range", "bytes=1024-")
      .build()

    val response = client.newCall(request).execute()
    if (!response.isSuccessful) {
      val code = response.code
      val msg = response.message
      response.close()
      throw Exception("HTTP $code: $msg")
    }
    val body = response.body ?: throw Exception("No response body")
    val contentLength = body.contentLength()
    val bodyStream = body.byteStream()

    val rc4 = Rc4(licContentKey, 512)
    try {
      FileOutputStream(file).use { output ->
        bodyStream.use { input ->
          val buf = ByteArray(131072)
          var bytesRead: Int
          while (input.read(buf).also { bytesRead = it } != -1) {
            val chunk = if (bytesRead == buf.size) buf else buf.copyOf(bytesRead)
            output.write(rc4.process(chunk))
          }
        }
      }
    } catch (e: Exception) {
      // Delete partial file on error
      if (file.exists()) file.delete()
      throw e
    }

    return outputPath
  }
}

class Rc4(key: String, drop: Int) {
  private val s = IntArray(256)
  private var i = 0
  private var j = 0

  init {
    val keyBytes = key.map { it.code }
    for (k in 0..255) s[k] = k
    var j = 0
    for (k in 0..255) {
      j = (j + s[k] + keyBytes[k % keyBytes.size]) and 0xFF
      val tmp = s[k]; s[k] = s[j]; s[j] = tmp
    }
    for (n in 0 until drop) {
      this.i = (this.i + 1) and 0xFF
      this.j = (this.j + s[this.i]) and 0xFF
      val tmp = s[this.i]; s[this.i] = s[this.j]; s[this.j] = tmp
    }
  }

  fun process(data: ByteArray): ByteArray {
    val out = ByteArray(data.size)
    for (k in data.indices) {
      i = (i + 1) and 0xFF
      j = (j + s[i]) and 0xFF
      val tmp = s[i]; s[i] = s[j]; s[j] = tmp
      out[k] = (data[k].toInt() xor s[(s[i] + s[j]) and 0xFF]).toByte()
    }
    return out
  }
}
