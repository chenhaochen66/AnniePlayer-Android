package com.annieplayer.annie_music.kt.core.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.Color
import com.annieplayer.annie_music.kt.data.network.globalOkHttp
import com.annieplayer.annie_music.kt.ui.theme.ColorPalette
import java.io.File
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * 封面动态取色（1:1 移植 Flutter `core/utils/color_extractor.dart`，对齐 RN useColorExtractor.ts）。
 *
 * primary = 全图像素算术平均；secondary = vibrant/muted/darkVibrant/darkMuted，
 * 兜底 #8b5cf6（primary 兜底 #6366f1 由 [ColorPalette.fallback] 承担）。
 *
 * 从封面（本地缓存文件或网络 URL）解码并量化，提取 primary/secondary 主色调；
 * 解码在后台线程执行（对应 Dart 的 compute isolate），不阻塞 UI。
 * 任何异常（下载/解码失败）静默返回 null，由调用方保留默认色板。
 */

/** 封面下载 UA（对齐 Dart `_coverUa`，浏览器 UA 绕过防盗链） */
private const val _coverUa =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

/** secondary 兜底：vibrant/muted/darkVibrant/darkMuted 全落空时取 0xFF8B5CF6 */
private val _fallbackSecondary: Int = 0xFF8B5CF6.toInt()

/** 取色结果缓存（key = 封面地址/本地路径；null 表示失败，避免重复下载解码） */
private val _cache = HashMap<String, ColorPalette?>()

/**
 * 从封面提取动态色板；失败返回 null。
 *
 * 返回值仅 primary/secondary 为提取结果，background/surface 为默认值；
 * 调用方应使用 copyWith 合并，避免覆盖现有背景色。
 */
suspend fun extractPaletteFromCover(coverSource: String): ColorPalette? {
    if (coverSource.isEmpty()) return null
    if (_cache.containsKey(coverSource)) return _cache[coverSource]
    try {
        val bytes = _loadCoverBytes(coverSource)
        if (bytes == null || bytes.isEmpty()) {
            _cache[coverSource] = null
            return null
        }
        val argb = withContext(Dispatchers.Default) { _extractPaletteIsolate(bytes) }
        if (argb == null || argb.size < 2) {
            _cache[coverSource] = null
            return null
        }
        val palette = ColorPalette.fallback.copyWith(
            primary = Color(argb[0].toLong() and 0xFFFFFFFFL),
            secondary = Color(argb[1].toLong() and 0xFFFFFFFFL),
        )
        _cache[coverSource] = palette
        return palette
    } catch (_: Throwable) {
        _cache[coverSource] = null
        return null
    }
}

/** 读取封面字节：优先本地缓存文件，否则下载（浏览器 UA 绕过防盗链） */
private suspend fun _loadCoverBytes(source: String): ByteArray? {
    var path = source
    if (path.startsWith("file://")) path = URI(path).path ?: path
    val file = File(path)
    if (file.exists()) {
        return withContext(Dispatchers.IO) { file.readBytes() }
    }
    return withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(source)
            .header("User-Agent", _coverUa)
            .build()
        globalOkHttp.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $source")
            resp.body?.bytes()
        }
    }
}

/**
 * 解码 → 缩到 64x64 → 提取色板，返回 [primary, secondary] 的 ARGB。
 *
 * - primary：全图像素算术平均 RGB（对齐 RN Android 的 average 语义）。
 * - secondary：量化直方图候选中按 AndroidX Palette 目标打分，
 *   依次退化为 Vibrant → Muted → DarkVibrant → DarkMuted，仍无则 #8b5cf6。
 */
private fun _extractPaletteIsolate(bytes: ByteArray): List<Int>? {
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val small = Bitmap.createScaledBitmap(decoded, 64, 64, true)

    // 5-bit/通道量化，累积每桶 RGB 和与像素数，供候选色打分
    val buckets = HashMap<Int, _Bucket>()
    var sumR = 0
    var sumG = 0
    var sumB = 0
    var pixelCount = 0

    val width = small.width
    val height = small.height
    val pixels = IntArray(width * height)
    small.getPixels(pixels, 0, width, 0, 0, width, height)

    for (pixel in pixels) {
        val r = (pixel ushr 16) and 0xFF
        val g = (pixel ushr 8) and 0xFF
        val b = pixel and 0xFF
        sumR += r
        sumG += g
        sumB += b
        pixelCount++

        val key = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
        val bucket = buckets.getOrPut(key) { _Bucket() }
        bucket.r += r
        bucket.g += g
        bucket.b += b
        bucket.count++
    }
    if (small !== decoded) small.recycle()
    decoded.recycle()
    if (pixelCount == 0) return null

    // primary = 全图像素算术平均（RN Android average）
    val primary = _argb(sumR / pixelCount, sumG / pixelCount, sumB / pixelCount)

    // secondary = vibrant → muted → darkVibrant → darkMuted → #8b5cf6
    val candidates = buckets.values.toList()
    val secondary = _bestMatch(candidates) { _isVibrant(it) }
        ?: _bestMatch(candidates) { _isMuted(it) }
        ?: _bestMatch(candidates) { _isDarkVibrant(it) }
        ?: _bestMatch(candidates) { _isDarkMuted(it) }
        ?: _fallbackSecondary

    return listOf(primary, secondary)
}

/**
 * 在候选中挑选满足 [predicate] 且「饱和度加权占比」最高者的平均色（ARGB）。
 *
 * 打分 = 该色块像素占比 × HSV 饱和度，对应 RN/AndroidX 兼顾出现频率与鲜艳度的取色取向。
 */
private fun _bestMatch(buckets: List<_Bucket>, predicate: (_Hsv) -> Boolean): Int? {
    var bestScore = -1.0
    var best: _Bucket? = null
    for (bucket in buckets) {
        val hsv = _hsvFromRgb(bucket.avgR, bucket.avgG, bucket.avgB)
        if (!predicate(hsv)) continue
        val score = bucket.count * hsv.saturation
        if (score > bestScore) {
            bestScore = score
            best = bucket
        }
    }
    val matched = best ?: return null
    return _argb(matched.avgR, matched.avgG, matched.avgB)
}

/** HSV 的饱和度 / 明度（对齐 Dart `HSVColor.fromColor`，仅取谓词用到的 s/v） */
private class _Hsv(
    val saturation: Double,
    val value: Double,
)

/** RGB(0..255) → HSV 的 s/v（逐条对齐 Dart HSVColor.fromColor 公式） */
private fun _hsvFromRgb(r: Int, g: Int, b: Int): _Hsv {
    val rf = r / 255.0
    val gf = g / 255.0
    val bf = b / 255.0
    val max = maxOf(rf, gf, bf)
    val min = minOf(rf, gf, bf)
    val delta = max - min
    val saturation = if (max == 0.0) 0.0 else delta / max
    return _Hsv(saturation, max)
}

/** Vibrant：S≥0.35，V∈[0.3,0.7] */
private fun _isVibrant(hsv: _Hsv): Boolean =
    hsv.saturation >= 0.35 && hsv.value >= 0.3 && hsv.value <= 0.7

/** Muted：S∈[0.1,0.4] */
private fun _isMuted(hsv: _Hsv): Boolean =
    hsv.saturation >= 0.1 && hsv.saturation <= 0.4

/** DarkVibrant：V<0.3 且 S≥0.35 */
private fun _isDarkVibrant(hsv: _Hsv): Boolean =
    hsv.value < 0.3 && hsv.saturation >= 0.35

/** DarkMuted：V<0.3 且 S∈[0.1,0.4] */
private fun _isDarkMuted(hsv: _Hsv): Boolean =
    hsv.value < 0.3 && hsv.saturation >= 0.1 && hsv.saturation <= 0.4

/** 组装 ARGB（0xAARRGGBB，alpha 固定 0xFF） */
private fun _argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

/** 量化桶：保存 RGB 累加和与像素数，平均色即该候选色的代表色 */
private class _Bucket {
    var r = 0
    var g = 0
    var b = 0
    var count = 0

    val avgR: Int get() = r / count
    val avgG: Int get() = g / count
    val avgB: Int get() = b / count
}
