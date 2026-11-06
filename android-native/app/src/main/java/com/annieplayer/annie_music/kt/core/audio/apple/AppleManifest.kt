package com.annieplayer.annie_music.kt.core.audio.apple

import android.util.Base64
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.network.CacheService
import com.annieplayer.annie_music.kt.data.network.httpGetText
import com.annieplayer.annie_music.kt.data.network.retryUntilSuccess
import com.annieplayer.annie_music.kt.data.sources.apple.wrapper.AppleWrapperApi

/**
 * 播放与下载**共用**的 Apple 清单缓存层。
 *
 * ## 为什么要有这一层
 * 播放侧（[ApplePlaybackServer.open]）早就把 master m3u8 文本、档位 media playlist 文本、
 * init 分片都灌进 [CacheService] 了，但下载侧（`AppleDownloader`）三个都没用 —— 每首歌
 * 白付两次 Apple CDN 往返（约 0.65s）和一次 init 往返，而且**播过的歌再下载时完全不复用**。
 *
 * 键必须在两侧**逐字节一致**，否则「播放过再下载」不会命中。所以键的构造统一收到这里，
 * 谁也不许再自己拼字符串。
 *
 * ## 为什么键里带的是 [variantTag] 而不是 variant URI
 * variant URI 里带一次性签名：master 过期后重新拉一次，同一档位的 URI 就变了。用 URI 当键
 * 会让缓存**每次刷新都失效**。`groupId`（`#EXT-X-MEDIA` 的 `AUDIO="…"`，如
 * `audio-alac-stereo-44100-24`）在同一首歌里稳定，且能区分同档位的不同变体
 * （`audio-stereo-256` / `-binaural` / `-downmix` 三个 256k 变体），折损为 0。
 */
internal object AppleManifest {

    /** 清单类缓存的统一 TTL：与 masterUrl 的 30 分钟对齐（签名有效期同源）。 */
    const val PlaylistTtlMs = 30 * 60 * 1000L

    /** 稳定的档位/变体标识（见文件头：不用带签名的 URI）。 */
    fun variantTag(variant: AppleVariant): String =
        variant.groupId.ifEmpty { variant.tier?.cacheTag ?: variant.kind.name.lowercase() }

    private fun songPart(songId: String): String = songId.removePrefix("apple:")

    /**
     * 源分区（本机 / 各远程地址各一份）。
     *
     * 三张清单（master 文本 / 档位 media playlist / init 分片）都是**跟着 masterUrl 走的**，
     * 而 masterUrl 由当前 wrapper 给出 —— 换源后不同 storefront 的账号可能给出不同变体表，
     * 沿用上一台的分区就会解析出对不上的档位。分区由 `AppleWrapperApi` 统一给，
     * 播放 / 下载 / 预取三处自动一致（它们都只经这里拿键）。
     */
    private fun scope(): String = AppleWrapperApi.cacheScope()

    /** master m3u8 文本（与档位无关，一首歌一份）。 */
    fun masterTextKey(songId: String): String =
        "apple:mastertext:" + scope() + ":" + songPart(songId)

    /** 某一档位变体的 media playlist 文本。 */
    fun mediaTextKey(songId: String, variant: AppleVariant): String =
        "apple:mediatext:" + scope() + ":" + songPart(songId) + ":" + variantTag(variant)

    /** 某一档位变体的 init 分片。 */
    fun initKey(songId: String, variant: AppleVariant): String =
        "apple:init:" + scope() + ":" + songPart(songId) + ":" + variantTag(variant)

    /** 取 master m3u8 文本；缓存优先，失败重试一次（清单很小，抖动一次不该让整首失败）。 */
    suspend fun masterText(songId: String, masterUrl: String): String {
        CacheService.get<String>(masterTextKey(songId))
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        val text = retryUntilSuccess { httpGetText(masterUrl) }
        CacheService.set(masterTextKey(songId), text, PlaylistTtlMs)
        return text
    }

    /** 取某一档位的 media playlist 文本；缓存优先。 */
    suspend fun mediaText(songId: String, variant: AppleVariant): String {
        CacheService.get<String>(mediaTextKey(songId, variant))
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        val text = retryUntilSuccess { httpGetText(variant.uri) }
        CacheService.set(mediaTextKey(songId, variant), text, PlaylistTtlMs)
        return text
    }

    /**
     * 取 init 分片。播放侧缓存的是 Base64 字符串（[CacheService] 存的是文本），
     * 这里保持同一格式，两边才能互相命中。
     */
    fun cachedInit(songId: String, variant: AppleVariant): ByteArray? =
        CacheService.get<String>(initKey(songId, variant))
            ?.let { raw ->
                runCatching { Base64.decode(raw, Base64.NO_WRAP) }.getOrNull()
            }
            ?.takeIf { it.isNotEmpty() }

    /** 写入 init 分片缓存（下载与播放都走这里）。 */
    fun storeInit(songId: String, variant: AppleVariant, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        runCatching {
            CacheService.set(
                initKey(songId, variant),
                Base64.encodeToString(bytes, Base64.NO_WRAP),
                PlaylistTtlMs,
            )
        }
    }
}

/**
 * 音质 → Apple 档位。
 *
 * 放在这里是因为播放、下载、预取三处都要用它；以前它是 `AppleDownloader` 里的私有扩展，
 * 预取和播放各抄一份必然走偏。
 */
internal fun Quality.toAppleTier(): AppleTier? = when (this) {
    Quality.alac, Quality.flac -> AppleTier.AlacLossless
    Quality.hires -> AppleTier.AlacHiRes
    Quality.aac256 -> AppleTier.Aac256
    Quality.atmos -> AppleTier.Atmos
    else -> null
}
