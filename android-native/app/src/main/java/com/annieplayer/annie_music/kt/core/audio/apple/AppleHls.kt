package com.annieplayer.annie_music.kt.core.audio.apple

import java.net.URI

/** Apple HLS media playlist 中的一个分片。 */
data class AppleMediaSegment(
    val uri: String,
    val rangeStart: Long?,
    val rangeLength: Long?,
    val keyUri: String?,
    val durationSeconds: Double = 0.0,
)

/** Apple HLS media playlist（已解析 `EXT-X-MAP` / `EXT-X-KEY` / `EXT-X-BYTERANGE`）。 */
data class AppleMediaPlaylist(
    val fileUri: String,
    val initUri: String,
    val initStart: Long,
    val initLength: Long?,
    val segments: List<AppleMediaSegment>,
    val keyUris: List<String>,
) {
    fun keyForSegment(index: Int): String {
        if (index == 0) return GENERIC_SKD
        val segmentKey = segments.getOrNull(index)?.keyUri
        if (!segmentKey.isNullOrEmpty() && !segmentKey.startsWith(GENERIC_SKD_PREFIX)) {
            return segmentKey
        }
        return keyUris.firstOrNull {
            it.isNotEmpty() && it != GENERIC_SKD && !it.startsWith(GENERIC_SKD_PREFIX)
        } ?: GENERIC_SKD
    }
}

const val GENERIC_SKD = "skd://itunes.apple.com/P000000000/s1/e1"
private const val GENERIC_SKD_PREFIX = "skd://itunes.apple.com/P000000000"

private val _attr = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

fun parseAppleMediaPlaylist(mediaUrl: String, text: String): AppleMediaPlaylist {
    var fileUri: String? = null
    var initUri: String? = null
    var initStart = 0L
    var initLength: Long? = null
    var currentKey: String? = null
    var pendingRange: Pair<Long, Long>? = null
    var pendingDuration = 0.0
    val segments = mutableListOf<AppleMediaSegment>()
    val keyUris = mutableListOf<String>()

    fun resolve(value: String): String = runCatching {
        URI(mediaUrl).resolve(value).toString()
    }.getOrDefault(value)

    for (raw in text.lineSequence()) {
        val line = raw.trim()
        when {
            line.startsWith("#EXT-X-MAP:") -> {
                val attrs = parseAttrs(line.substringAfter(':'))
                val uri = attrs["URI"] ?: continue
                initUri = resolve(uri)
                fileUri = fileUri ?: initUri
                attrs["BYTERANGE"]?.let { parseRange(it) }?.let { (length, offset) ->
                    initStart = offset ?: 0L
                    initLength = length
                }
            }

            line.startsWith("#EXT-X-KEY:") -> {
                val attrs = parseAttrs(line.substringAfter(':'))
                val method = attrs["METHOD"].orEmpty()
                val keyFormat = attrs["KEYFORMAT"].orEmpty()
                val uri = attrs["URI"]
                if (method == "NONE") {
                    currentKey = null
                } else if (uri != null && (keyFormat.isEmpty() || keyFormat == "com.apple.streamingkeydelivery")) {
                    currentKey = uri
                }
                // PlayReady / Widevine 的 data: URI 直接忽略，保留 Apple skd key。
            }

            line.startsWith("#EXT-X-BYTERANGE:") -> {
                parseRange(line.substringAfter(':'))?.let { (length, offset) ->
                    pendingRange = length to (offset ?: 0L)
                }
            }

            line.startsWith("#EXTINF:") -> {
                pendingDuration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
            }

            line.isNotEmpty() && !line.startsWith("#") -> {
                val uri = resolve(line)
                fileUri = fileUri ?: uri
                val range = pendingRange
                pendingRange = null
                segments += AppleMediaSegment(
                    uri = uri,
                    rangeStart = range?.second,
                    rangeLength = range?.first,
                    keyUri = currentKey,
                    durationSeconds = pendingDuration,
                )
                pendingDuration = 0.0
                keyUris += currentKey.orEmpty()
            }
        }
    }

    val actualInit = initUri ?: fileUri ?: mediaUrl
    return AppleMediaPlaylist(
        fileUri = fileUri ?: mediaUrl,
        initUri = actualInit,
        initStart = initStart,
        initLength = initLength,
        segments = segments,
        keyUris = keyUris,
    )
}

private fun parseAttrs(text: String): Map<String, String> =
    _attr.findAll(text).associate { match ->
        match.groupValues[1].uppercase() to match.groupValues[2].trim().trim('"')
    }

/** `length@offset`。 */
private fun parseRange(raw: String): Pair<Long, Long?>? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    val at = value.indexOf('@')
    if (at < 0) {
        val length = value.toLongOrNull() ?: return null
        return length to null
    }
    val length = value.substring(0, at).toLongOrNull() ?: return null
    val offset = value.substring(at + 1).toLongOrNull() ?: return null
    return length to offset
}
