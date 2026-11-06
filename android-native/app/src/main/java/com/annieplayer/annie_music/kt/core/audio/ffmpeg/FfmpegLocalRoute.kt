package com.annieplayer.annie_music.kt.core.audio.ffmpeg

import com.annieplayer.annie_music.kt.data.local.LocalLibraryStore
import java.util.Locale

/**
 * 本地文件「走不走 ffmpeg 全链路」的判定（**唯一一处**）。
 *
 * 判据：
 * 1. **Media3 连解封装都不会的容器** → 全链路（ffmpeg 从文件头开始读）；
 * 2. **采样率超过 192k**（384k/768k 母带、DSD 直转产物）→ 全链路，
 *    因为 `AudioFormat` 的合法档位最高就是 192000，必须由全链路整数降档；
 * 3. **位深超过 24bit**（32bit int / float）→ 全链路，避开 16bit 截断；
 * 4. 其余（≤192k 且 ≤24bit：flac / alac / aac / mp3 / wav / aiff …）→ 保持现状：
 *    Media3 解封装 + ffmpeg 解码器。
 *
 * 没有转码兜底：全链路的定位就是**直接播原始文件**——不产中间文件、不吃缓存配额、
 * 不产生 352.8k 那种非法采样率的转码产物，也不会因为转码等待而卡住首播。
 */
internal object FfmpegLocalRoute {

    /** 高解析门槛：超过它就是 AudioFormat 的非法档位 */
    private const val MAX_LEGAL_RATE = 192_000

    /** 位深门槛：超过它就交给全链路 */
    private const val MAX_MEDIA3_BITS = 24

    /**
     * Media3（ExoPlayer 1.4.x）自带 extractor 能直接 demux 的容器。
     *
     * 不在这里的一律走全链路：ape / wv / tta / tak / dsf / dff / wma / dts 变体…
     */
    private val MEDIA3_CONTAINERS = setOf(
        "mp3", "mp2", "mpga", "flac", "m4a", "m4b", "mp4", "aac", "wav", "wave",
        "ogg", "oga", "opus", "mka", "amr", "3gp", "ac3", "eac3", "caf",
        // ⚠️ aiff / aif / aifc **刻意不在这里**。
        //
        // Media3 的 `AiffExtractor` 确实能 demux，所以它「看起来」该走 Media3；
        // 但实测 24bit big-endian PCM 交给 AudioSink 后**没有声音**（24bit/96k 的 aiff
        // 点了不播、也不报错）。ffmpeg 侧 `aiff` demuxer + `pcm_s24be` decoder 都在
        // （`--enable-demuxer=aiff` / `--enable-decoder=pcm_s24be`），而且全链路输出的是
        // 统一的小端 PCM，绕开了这条路径。**别把它们加回白名单**，除非实测确认修好了。
    )

    fun useFullChain(path: String): Boolean {
        if (path.isEmpty()) return false
        if (!NativeFfmpegDemux.isAvailable()) return false
        val ext = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        // 冷门容器：Media3 根本没有对应 Extractor，只能 ffmpeg 从文件头读
        if (ext.isNotEmpty() && ext !in MEDIA3_CONTAINERS) return true
        // 常见容器但高解析：索引里有记录才判，没扫到就先按现状播
        val meta = LocalLibraryStore.trackOf(path) ?: return false
        if (meta.sampleRate > MAX_LEGAL_RATE) return true
        if (meta.bitDepth > MAX_MEDIA3_BITS) return true
        return false
    }

    /** 输出位深：蓝牙链路物理 16bit；否则跟随源（16bit 源不白抬到 24），未知按 24 */
    fun outputBits(path: String, prefer24: Boolean): Int {
        if (!prefer24) return NativeFfmpegDemux.BITS_16
        val meta = LocalLibraryStore.trackOf(path)
        if (meta != null && meta.bitDepth == 16) return NativeFfmpegDemux.BITS_16
        return NativeFfmpegDemux.BITS_24
    }
}
