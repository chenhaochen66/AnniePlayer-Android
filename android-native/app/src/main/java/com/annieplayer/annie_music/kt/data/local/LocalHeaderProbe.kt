package com.annieplayer.annie_music.kt.data.local

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 纯 Kotlin 的**文件头解析**：位深 / 采样率 / 声道。
 *
 * 为什么需要它 —— TagLib 的 `AudioProperties` 只暴露 `length / bitrate / sampleRate / channels`，
 * **没有位深**；而位深恰恰是本地曲库最该显示的字段（`FLAC 24bit` 和 `FLAC 16bit` 是两种东西）。
 *
 * 为什么不写 native 库：这些参数全部写在**文件头固定偏移**上，读几十到几百字节就能算出来，
 * 一个 `RandomAccessFile` 足够；为此引一个 native 库要交叉编译 + 多一份 so + 崩溃面，
 * 得不偿失。实测单首 1~2ms，和 TagLib 同量级。
 *
 * 覆盖：FLAC / WAV / AIFF+C / APE / MP4(M4A·ALAC·AAC) / DSF / DFF。
 * 其余容器（WMA / WavPack / 特殊封装）返回 null，由 ffprobe 兜底。
 */
internal object LocalHeaderProbe {

    /** AAC `samplingFrequencyIndex` → Hz（index 15 是显式 24bit 值，这里不支持） */
    private val ASC_SAMPLE_RATES = intArrayOf(
        96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000,
        22_050, 16_000, 12_000, 11_025, 8_000, 7_350,
    )

    /**
     * 头解析结果。
     *
     * [codec] 是**真实编码名**（`flac` / `alac` / `aac` / `wav` / `ape` / `dsd`…），
     * 比扩展名可靠：`.m4a` 既可能是 ALAC 也可能是 AAC，只有读 `stsd` 里的 sample entry
     * （`alac` / `mp4a`）才分得清 —— 曲库列表的音质角标、播放页的「编码」都靠它。
     */
    data class Stream(
        val bitDepth: Int,
        val sampleRate: Int,
        val channels: Int,
        val codec: String = "",
    ) {
        val isEmpty: Boolean get() = bitDepth <= 0 && sampleRate <= 0 && channels <= 0 && codec.isEmpty()
    }

    /** 视为无损的编码（DSD 也算） */
    private val LOSSLESS_CODECS = setOf(
        "flac", "alac", "ape", "wav", "aiff", "aif", "wv", "tta", "dsd",
    )

    /** 解析失败 / 不认识的容器返回 null */
    fun probe(file: File): Stream? {
        if (!file.isFile || file.length() < 16) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                when {
                    _ascii(raf, 0, 4) == "fLaC" -> _flac(raf)
                    _ascii(raf, 0, 4) == "RIFF" && _ascii(raf, 8, 4) == "WAVE" -> _wav(raf)
                    _ascii(raf, 0, 4) == "FORM" -> _aiff(raf)
                    _ascii(raf, 0, 4) == "MAC " -> _ape(raf)
                    _ascii(raf, 4, 4) == "ftyp" -> _mp4(raf)
                    _ascii(raf, 0, 4) == "DSD " -> _dsf(raf)
                    _ascii(raf, 0, 4) == "FRM8" -> _dff(raf)
                    _ascii(raf, 0, 4) == "OggS" -> _ogg(raf)
                    _ascii(raf, 0, 4) == "wvpk" -> _wv(raf)
                    _ascii(raf, 0, 4) == "TTA1" -> _tta(raf)
                    // ASF / WMA：Header Object GUID 30 26 B2 75 …
                    _magic(raf, 0, 0x30, 0x26, 0xB2, 0x75) -> _wma(raf)
                    // 下面两种没有唯一魔数，放最后按同步字判，避免抢走上面那些容器
                    _magic(raf, 0, 0x0B, 0x77) -> _ac3(raf)
                    _ascii(raf, 0, 3) == "ID3" -> _mp3(raf)
                    _mpegSync(raf) -> _mp3(raf)
                    else -> null
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** 前几个字节是否等于给定值（按无符号比较） */
    private fun _magic(raf: RandomAccessFile, off: Long, vararg bytes: Int): Boolean {
        raf.seek(off)
        val b = _read(raf, bytes.size) ?: return false
        if (b.size < bytes.size) return false
        return bytes.indices.all { (b[it].toInt() and 0xFF) == bytes[it] }
    }

    /** 头部是不是 MPEG 帧同步（`0xFF 0xEx`） */
    private fun _mpegSync(raf: RandomAccessFile): Boolean {
        raf.seek(0)
        val b = _read(raf, 2) ?: return false
        if (b.size < 2) return false
        return (b[0].toInt() and 0xFF) == 0xFF && ((b[1].toInt() and 0xE0) == 0xE0)
    }

    /**
     * 用头解析补齐 [meta] 缺失的位深 / 采样率 / 声道，并**纠正编码名**。
     *
     * 编码必须由头解析说了算：扫描阶段 TagLib 只能拿到扩展名（`.m4a`），
     * 分不清 ALAC 还是 AAC —— 不纠正的话 ALAC 会被当成有损、列表角标和播放页「编码」都没有它。
     */
    fun fill(meta: LocalTrackMeta): LocalTrackMeta {
        val needMore = meta.bitDepth <= 0 || meta.sampleRate <= 0 || meta.channels <= 0
        // 扩展名分不清容器里到底是什么编码的，这几种也强制读头
        val ambiguous = meta.codec.lowercase() in setOf("m4a", "m4b", "mp4", "caf", "aiff", "aif")
        // DSD 也必须由头解析说了算：ffprobe 对 dsf/dff 报的是**解码器中间格式**
        // （`dsd_lsbf_planar` / 8bit / 源率÷8），不是文件规格（1bit / 2.8224MHz）。
        // 光看 needMore 它永远进不来 —— 中间格式每个字段都是非零的，会一直被当成「已齐全」。
        val dsd = _isDsd(meta)
        if (!needMore && !ambiguous && !dsd) return sanitize(meta)
        val s = probe(File(meta.path))
        if (s == null) {
            // 头解析不认识这个容器（罕见格式，实测 54/54 已全覆盖，这里只是保险）→
            // 交给 ffprobe 出规格。ffprobe 也失败就如实留 0，不再回退 TagLib 的可疑值。
            val p = runCatching { LocalTagProbe.probe(meta.path) }.getOrNull()
                ?: return sanitize(meta)
            return sanitize(
                meta.copy(
                    codec = p.codec.ifEmpty { meta.codec },
                    sampleRate = p.sampleRate,
                    bitDepth = p.bitDepth,
                    channels = p.channels,
                ),
            )
        }
        if (dsd) {
            val oneBitRate = s.sampleRate.takeIf { it > 0 } ?: meta.sampleRate
            return meta.copy(
                bitDepth = s.bitDepth.takeIf { it > 0 } ?: 1,
                sampleRate = oneBitRate,
                channels = s.channels.takeIf { it > 0 } ?: meta.channels,
                codec = dsdCodecName(oneBitRate),
                lossless = true,
            )
        }
        val codec = s.codec.ifEmpty { meta.codec.lowercase() }
        // 位深 / 采样率 / 声道：**头解析优先**（读容器真实字段），头解析给 0 就是 0。
        //
        // 为什么必须覆盖而不是"只补空"：`.m4a` 的 `mp4a` sample entry 里 `samplerate`
        // 是 16.16 定点，整数部分只有 16 位（≤65535）—— 96k/192k 根本装不下，容器只能写
        // core rate（48k）；位深那栏也常被写成 16。TagLib 读的就是这两个字段，所以它给出的
        // 48000/16 看着"齐全"，旧的 `if (meta.x > 0)` 写法就一直让真值进不来。
        // 真值只在 `mdhd.timescale` / `esds → AudioSpecificConfig` / ALAC magic cookie 里。
        return sanitize(
            meta.copy(
                bitDepth = s.bitDepth,
                sampleRate = s.sampleRate,
                channels = s.channels,
                codec = codec,
                lossless = codec.lowercase() in LOSSLESS_CODECS,
            ),
        )
    }

    /**
     * 把**不存在的字段**清成 0（未知），而不是留一个"看起来合理"的默认值。
     *
     * 只处理一件事：有损编码没有位深概念（AAC/MP3/AC3 的 `sample_fmt` 是 `fltp`，
     * 不存在"16 bit 采样"这回事）。TagLib 却会给 16 / 32 这种容器默认值 ——
     * 那是**假信息**，界面上显示"AC3 32 Bit"会直接把判断带偏。
     *
     * 无损格式的位深是真实存在的，保持原样。
     */
    private fun sanitize(meta: LocalTrackMeta): LocalTrackMeta {
        val codec = meta.codec.lowercase()
        val lossless = codec in LOSSLESS_CODECS || codec.startsWith("pcm")
        val cleaned = if (lossless || meta.bitDepth <= 0) meta else meta.copy(bitDepth = 0)
        // 码率同样是"假信息清理"的一部分：ffprobe 会拿未压缩速率（`采样率×位深×声道`）
        // 冒充码率，见 `LocalBitrate.kt`。放在这里是因为 `fill()` 的**每一条**返回路径
        // 都会过 sanitize —— 漏掉任何一条，那条路的 ALAC 就还是 2304K。
        return cleaned.withTrueBitrate()
    }

    /** 这条记录是不是 DSD（ffprobe 的 `dsd_*` 编码名 / 我们自己给的 `dsd64` / dsf·dff 扩展名） */
    private fun _isDsd(meta: LocalTrackMeta): Boolean =
        meta.codec.lowercase().startsWith("dsd") || meta.ext in setOf("dsf", "dff")

    /**
     * 按 **1bit 采样率**命名 DSD 档位：2822400 = DSD64、5644800 = DSD128。
     *
     * 索引里存这个名字而不是 `dsd_lsbf_planar`：后者是 ffmpeg 解码器实现细节，
     * 曲库角标和播放页「编码」都要显示人能读的档位。
     */
    private fun dsdCodecName(oneBitRate: Int): String = when (oneBitRate) {
        2_822_400 -> "dsd64"
        5_644_800 -> "dsd128"
        11_289_600 -> "dsd256"
        22_579_200 -> "dsd512"
        else -> "dsd"
    }

    // ── 各容器 ──

    /**
     * FLAC：`fLaC` 之后第一个元数据块就是 STREAMINFO（34 字节）：
     * 10~17 字节里打包了 20bit 采样率、3bit 声道-1、5bit 位深-1、36bit 总样本数。
     */
    private fun _flac(raf: RandomAccessFile): Stream? {
        raf.seek(4)
        while (true) {
            val head = _read(raf, 4) ?: return null
            val last = (head[0].toInt() and 0x80) != 0
            val type = head[0].toInt() and 0x7F
            val len = ((head[1].toInt() and 0xFF) shl 16) or
                ((head[2].toInt() and 0xFF) shl 8) or (head[3].toInt() and 0xFF)
            if (type == 0) {
                val b = _read(raf, 34) ?: return null
                val sampleRate = ((b[10].toInt() and 0xFF) shl 12) or
                    ((b[11].toInt() and 0xFF) shl 4) or
                    ((b[12].toInt() and 0xF0) ushr 4)
                val channels = ((b[12].toInt() and 0x0E) ushr 1) + 1
                val bitDepth = (((b[12].toInt() and 0x01) shl 4) or
                    ((b[13].toInt() and 0xF0) ushr 4)) + 1
                return Stream(bitDepth = bitDepth, sampleRate = sampleRate, channels = channels, codec = "flac")
            }
            if (last) return null
            raf.seek(raf.filePointer + len)
        }
    }

    /** WAV：遍历 chunk 找 `fmt `，`wBitsPerSample` 就是位深 */
    private fun _wav(raf: RandomAccessFile): Stream? {
        var pos = 12L
        repeat(32) {
            raf.seek(pos)
            val id = _ascii(raf, pos, 4) ?: return null
            val size = _u32le(raf, pos + 4) ?: return null
            if (id == "fmt ") {
                val b = _read(raf, 16) ?: return null
                val channels = _u16le(b, 2)
                val sampleRate = _u32le(b, 4).toInt()
                val bits = _u16le(b, 14)
                return Stream(bitDepth = bits, sampleRate = sampleRate, channels = channels, codec = "wav")
            }
            pos += 8 + size + (size and 1L)
        }
        return null
    }

    /** AIFF / AIFC：`COMM` 里 numChannels(2) + numSampleFrames(4) + sampleSize(2) + 80bit 采样率 */
    private fun _aiff(raf: RandomAccessFile): Stream? {
        var pos = 12L
        repeat(32) {
            val id = _ascii(raf, pos, 4) ?: return null
            val size = _u32be(raf, pos + 4) ?: return null
            if (id == "COMM") {
                raf.seek(pos + 8)
                val b = _read(raf, 18) ?: return null
                val channels = _u16be(b, 0)
                val bits = _u16be(b, 6)
                val sampleRate = _extended80(b, 8).roundToInt()
                return Stream(bitDepth = bits, sampleRate = sampleRate, channels = channels, codec = "aiff")
            }
            pos += 8 + size + (size and 1L)
        }
        return null
    }

    /**
     * APE（Monkey's Audio）：`MAC ` 描述符之后是 APE_HEADER，
     * 其偏移 16/18/20 分别是 bitsPerSample / channels / sampleRate。
     */
    private fun _ape(raf: RandomAccessFile): Stream? {
        val descriptorBytes = _u32le(raf, 8) ?: return null
        if (descriptorBytes <= 0 || descriptorBytes > 0x10000) return null
        raf.seek(descriptorBytes + 16)
        val b = _read(raf, 8) ?: return null
        val bits = _u16le(b, 0)
        val channels = _u16le(b, 2)
        val sampleRate = _u32le(b, 4).toInt()
        if (bits <= 0 && sampleRate <= 0) return null
        return Stream(bitDepth = bits, sampleRate = sampleRate, channels = channels, codec = "ape")
    }

    /**
     * MP4 / M4A：`moov → trak → mdia → minf → stbl → stsd` 的第一个 sample entry。
     *
     * AudioSampleEntry 布局（相对 entry 内容起点）：
     * `6 reserved + 2 dataRefIndex + 8 reserved + 2 channelcount + 2 samplesize + 2 pre_defined
     *  + 2 reserved + 4 samplerate(16.16)`。
     *
     * ALAC 的真实位深在 sample entry 之后的 magic cookie 里（偏移 5），
     * `samplesize` 字段常被写成 16 —— 所以对 `alac` 优先读 cookie。
     */
    private fun _mp4(raf: RandomAccessFile): Stream? {
        val end = raf.length()
        val stsd = _findBox(raf, 0, end, listOf("moov", "trak", "mdia", "minf", "stbl", "stsd")) ?: return null
        // stsd: version/flags(4) + entryCount(4)，之后是第一个 sample entry
        val entryStart = stsd.first + 8
        if (entryStart + 8 > stsd.second) return null
        val entrySize = _u32be(raf, entryStart) ?: return null
        val entryType = _ascii(raf, entryStart + 4, 4) ?: return null
        val content = entryStart + 8
        if (content + 28 > end) return null
        raf.seek(content + 16)
        val b = _read(raf, 12) ?: return null
        val channels = _u16be(b, 0)
        val samplesize = _u16be(b, 2)
        val sampleRate = (_u32be(b, 8) ushr 16).toInt()
        // sample entry 的类型就是真实编码 —— `.m4a` 的 ALAC / AAC 只有这里能分开
        val entryCodec = when (entryType.lowercase()) {
            "alac" -> "alac"
            "mp4a" -> "aac"
            "ac-3" -> "ac3"
            "ec-3" -> "eac3"
            "flac" -> "flac"
            "opus" -> "opus"
            else -> ""
        }

        if (entryType == "alac") {
            // ALAC magic cookie：ALACSpecificConfig，bitDepth 在偏移 5，numChannels 在 9，
            // sampleRate 在 20（32 位，不像 sample entry 的 16.16 定点，96k/192k 也装得下）。
            //
            // **必须从 `content + 28` 开始找**：前面那 28 字节是 AudioSampleEntry 固定字段
            // （6 reserved + 2 dataRefIndex + 8 reserved + 2 channels + 2 samplesize +
            //  2 pre_defined + 2 reserved + 4 samplerate）。从 `content` 直接扫的话，
            // 第一个"box"读到的是全 0 的 reserved → size=0 → 一步跳到末尾 → 永远找不到 cookie，
            // 于是退回 sample entry 的 16bit/44.1k（ALAC 显示错就是这么来的）。
            val cookie = _findBox(raf, content + 28, entryStart + entrySize, listOf("alac"))
            if (cookie != null) {
                raf.seek(cookie.first)
                val avail = (raf.length() - cookie.first).toInt().coerceIn(0, 32)
                val raw = if (avail >= 24) _read(raf, avail) else null
                if (raw != null) {
                    // `alac` box 是 **full box**：4 字节 version/flags 之后才是 24 字节的
                    // ALACSpecificConfig。少跳这 4 字节会整体错位一格 ——
                    // `c[5]`（bitDepth）读到 0、`c[9]`（numChannels）读到 24，
                    // 于是 `depth in 1..64` 判定失败，cookie 白读、退回 sample entry。
                    // 个别封装不带那 4 字节，所以两种偏移都试，用 bitDepth 合法性选。
                    for (base in intArrayOf(4, 0)) {
                        if (raw.size < base + 24) continue
                        val depth = raw[base + 5].toInt() and 0xFF
                        if (depth !in 1..64) continue
                        val ch = raw[base + 9].toInt() and 0xFF
                        val rate = _u32be(raw, base + 20).toInt()
                        return Stream(
                            bitDepth = depth,
                            sampleRate = rate.takeIf { it > 0 } ?: sampleRate,
                            channels = if (ch > 0) ch else channels,
                            codec = "alac",
                        )
                    }
                }
            }
        }
        // AAC 的真实采样率**不在** sample entry 里：那个字段是 16.16 定点，整数部分只有 16 位
        // （最大 65535），96k 根本装不下 —— 容器只能写 core rate（48k），真值在
        // `esds → DecoderSpecificInfo(AudioSpecificConfig)` 的 samplingFrequencyIndex 里。
        if (entryType == "mp4a") {
            val esds = _findBox(raf, content + 28, entryStart + entrySize, listOf("esds"))
            if (esds != null) {
                val ascRate = _ascSampleRate(raf, esds)
                if (ascRate != null && ascRate > sampleRate) {
                    return Stream(
                        bitDepth = samplesize,
                        sampleRate = ascRate,
                        channels = channels,
                        codec = "aac",
                    )
                }
            }
        }
        if (samplesize <= 0 && sampleRate <= 0) return null
        return Stream(bitDepth = samplesize, sampleRate = sampleRate, channels = channels, codec = entryCodec)
    }

    /**
     * 从 `esds` 里挖 AAC 的真实采样率（`AudioSpecificConfig.samplingFrequencyIndex`）。
     *
     * 为什么必须走这里：`AudioSampleEntry.samplerate` 是 16.16 定点，整数部分只有 16 位
     * （≤65535），96k/192k 装不进去 —— 容器只能写 core rate，真值只在这里。
     *
     * 只做一层朴素 descriptor 遍历（0x03 ES → 0x04 DecoderConfig → 0x05 DecoderSpecificInfo），
     * 拿不到就返回 null，调用方继续用容器字段，不猜。
     */
    private fun _ascSampleRate(raf: RandomAccessFile, esds: Pair<Long, Long>): Int? {
        val len = (esds.second - esds.first).toInt().coerceIn(8, 256)
        raf.seek(esds.first)
        val b = _read(raf, len) ?: return null
        var i = 4 // 跳过 version/flags
        while (i < b.size) {
            val tag = b[i].toInt() and 0xFF
            i++
            var size = 0
            var more = true
            while (i < b.size && more) {
                val v = b[i].toInt() and 0xFF
                i++
                size = (size shl 7) or (v and 0x7F)
                more = (v and 0x80) != 0
            }
            when {
                tag == 0x05 -> {
                    if (i + 1 >= b.size) return null
                    val a0 = b[i].toInt() and 0xFF
                    val a1 = b[i + 1].toInt() and 0xFF
                    val aot = a0 ushr 3
                    val idx = if (aot == 31) ((a0 and 0x07) shl 1) or (a1 ushr 7) else ((a0 and 0x07) shl 1) or (a1 ushr 7)
                    return ASC_SAMPLE_RATES.getOrNull(idx)
                }
                // 这两个 descriptor 有固定头，跳过它继续往里找子 descriptor
                tag == 0x03 -> i += 3
                tag == 0x04 -> i += 13
                else -> i += size
            }
        }
        return null
    }

    /**
     * DSF：`DSD ` 块 28 字节 → `fmt ` 块（ID 4 + **8 字节** chunk size），内容起点 = 28 + 12 = 40：
     * `version(4) formatId(4) channelType(4) channelNum(4) samplingFrequency(4) bitsPerSample(4)…`。
     *
     * 内容起点别再按「ID + 4 字节 size」算：DSF 的 chunk size 是 **8 字节**，
     * 少算 4 会让「采样率」读到 `channelNum`（DSD64 就变成 1Hz 这种垃圾值）。
     * DSD 的位深恒为 1。
     */
    private fun _dsf(raf: RandomAccessFile): Stream? {
        if (_ascii(raf, 28, 4) != "fmt ") return null
        val fmtContent = 28L + 12
        val channelNum = (_u32le(raf, fmtContent + 12) ?: return null).toInt()
        val sampleRate = (_u32le(raf, fmtContent + 16) ?: return null).toInt()
        if (sampleRate <= 0) return null
        return Stream(bitDepth = 1, sampleRate = sampleRate, channels = channelNum, codec = "dsd")
    }

    /** DFF（DSDIFF）：`PROP → SND → FS` 里是采样率，`CHNL` 里是声道数；DSD 固定 1bit */
    private fun _dff(raf: RandomAccessFile): Stream? {
        val end = raf.length()
        val prop = _findBox(raf, 12, end, listOf("PROP")) ?: return null
        // PROP 内容前 4 字节是 "SND "
        val fs = _findBox(raf, prop.first + 4, prop.second, listOf("FS  ")) ?: return null
        val sr = (_u32be(raf, fs.first) ?: 0L).toInt()
        val chnl = _findBox(raf, prop.first + 4, prop.second, listOf("CHNL"))
        val channels = if (chnl != null) (_u16be(raf, chnl.first) ?: 0) else 0
        if (sr <= 0 && channels <= 0) return null
        return Stream(bitDepth = 1, sampleRate = sr, channels = channels, codec = "dsd")
    }

    // ── box / 字节工具 ──

    /**
     * 在 `[start, end)` 范围内按 [path] 逐层查找 box，返回内容区间 `(contentStart, boxEnd)`。
     *
     * 只遍历同一层的兄弟 box，找到同名就下探一层；找不到返回 null。
     */
    private fun _findBox(
        raf: RandomAccessFile,
        start: Long,
        end: Long,
        path: List<String>,
    ): Pair<Long, Long>? {
        if (path.isEmpty()) return null
        val want = path.first()
        var pos = start
        var guard = 0
        while (pos + 8 <= end && guard++ < 256) {
            val size = _u32be(raf, pos) ?: return null
            val type = _ascii(raf, pos + 4, 4) ?: return null
            // size==0 → 一直到文件末尾；size==1 → 64 位长度（这里只处理常见形式）
            val boxSize = when {
                size == 0L -> end - pos
                size == 1L -> _u64be(raf, pos + 8) ?: return null
                else -> size
            }
            if (boxSize < 8) return null
            val contentStart = pos + 8
            val boxEnd = (pos + boxSize).coerceAtMost(end)
            if (type == want) {
                return if (path.size == 1) {
                    contentStart to boxEnd
                } else {
                    _findBox(raf, contentStart, boxEnd, path.drop(1))
                }
            }
            pos = boxEnd
        }
        return null
    }

    private fun _read(raf: RandomAccessFile, n: Int): ByteArray? {
        val b = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = raf.read(b, read, n - read)
            if (r <= 0) return null
            read += r
        }
        return b
    }

    /** 读 4 字节 ASCII（越界返回 null） */
    private fun _ascii(raf: RandomAccessFile, pos: Long, n: Int): String? {
        if (pos < 0 || pos + n > raf.length()) return null
        raf.seek(pos)
        val b = _read(raf, n) ?: return null
        return String(b, Charsets.US_ASCII)
    }

    private fun _u16be(raf: RandomAccessFile, pos: Long): Int? {
        raf.seek(pos)
        val b = _read(raf, 2) ?: return null
        return _u16be(b, 0)
    }

    /**
     * MP3：跳过 ID3v2 后找第一个 MPEG 帧同步（`0xFF 0xEx`），规格全在 4 字节帧头里。
     *
     * 有损编码没有位深概念 —— 位深恒 0（界面上会改显示比特率）。
     */
    private fun _mp3(raf: RandomAccessFile): Stream? {
        val end = raf.length()
        var pos = 0L
        raf.seek(0)
        val id3 = _read(raf, 10)
        if (id3 != null && id3.size == 10 &&
            id3[0] == 'I'.code.toByte() && id3[1] == 'D'.code.toByte() && id3[2] == '3'.code.toByte()
        ) {
            // ID3v2 头 10 字节，size 是 4 个 7-bit 大端（不含头本身）
            val size = ((id3[6].toInt() and 0x7F) shl 21) or
                ((id3[7].toInt() and 0x7F) shl 14) or
                ((id3[8].toInt() and 0x7F) shl 7) or
                (id3[9].toInt() and 0x7F)
            pos = 10L + size
        }
        // 只在头部 64KB 里找：正常 MP3 第一帧就在前面，找不到说明不是 MP3
        val limit = (pos + 65536).coerceAtMost(end)
        while (pos + 4 <= limit) {
            raf.seek(pos)
            val b = _read(raf, 4) ?: return null
            val b0 = b[0].toInt() and 0xFF
            val b1 = b[1].toInt() and 0xFF
            if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) {
                val verBits = (b1 ushr 3) and 0x03   // 0=MPEG2.5 2=MPEG2 3=MPEG1
                val layer = (b1 ushr 1) and 0x03     // 1=III 2=II 3=I
                if (verBits != 1 && layer != 0) {
                    val b2 = b[2].toInt() and 0xFF
                    val srIdx = (b2 ushr 2) and 0x03
                    if (srIdx != 3) {
                        val base = when (verBits) {
                            3 -> intArrayOf(44100, 48000, 32000)
                            2 -> intArrayOf(22050, 24000, 16000)
                            else -> intArrayOf(11025, 12000, 8000)
                        }
                        val mode = ((b[3].toInt() and 0xFF) ushr 6) and 0x03
                        return Stream(
                            bitDepth = 0,
                            sampleRate = base[srIdx],
                            channels = if (mode == 3) 1 else 2,
                            codec = "mp3",
                        )
                    }
                }
            }
            pos++
        }
        return null
    }

    /**
     * OGG：第一个页（`OggS`）的 payload 是 codec identification header。
     * - Vorbis：`\x01vorbis`，声道在 +11、采样率在 +12（4 字节 LE）
     * - Opus：`OpusHead`，采样率恒 48000，声道在 +9
     */
    private fun _ogg(raf: RandomAccessFile): Stream? {
        raf.seek(0)
        val buf = _read(raf, 128) ?: return null
        if (buf.size < 28) return null
        if (!(buf[0] == 'O'.code.toByte() && buf[1] == 'g'.code.toByte() &&
                buf[2] == 'g'.code.toByte() && buf[3] == 'S'.code.toByte())
        ) {
            return null
        }
        val segCount = buf[26].toInt() and 0xFF
        val off = 27 + segCount
        if (off + 16 > buf.size) return null
        fun tag(matches: String): Boolean {
            if (off + matches.length > buf.size) return false
            for (i in matches.indices) {
                if (buf[off + i] != matches[i].code.toByte()) return false
            }
            return true
        }
        return when {
            tag("\u0001vorbis") -> {
                val ch = buf[off + 11].toInt() and 0xFF
                val rate = (buf[off + 12].toInt() and 0xFF) or
                    ((buf[off + 13].toInt() and 0xFF) shl 8) or
                    ((buf[off + 14].toInt() and 0xFF) shl 16) or
                    ((buf[off + 15].toInt() and 0xFF) shl 24)
                Stream(0, rate, ch, "vorbis")
            }
            tag("OpusHead") -> Stream(0, 48_000, buf[off + 9].toInt() and 0xFF, "opus")
            else -> null
        }
    }

    /**
     * WavPack：`wvpk` 块头 32 字节。
     *
     * `flags`（偏移 24，LE）里：bit0~1 = 每样本字节数-1，bit2 = 单声道，
     * bit23~26 = 采样率索引。**无损**，位深是真实值。
     */
    private fun _wv(raf: RandomAccessFile): Stream? {
        raf.seek(0)
        val b = _read(raf, 32) ?: return null
        if (b.size < 32) return null
        val flags = (b[24].toInt() and 0xFF) or
            ((b[25].toInt() and 0xFF) shl 8) or
            ((b[26].toInt() and 0xFF) shl 16) or
            ((b[27].toInt() and 0xFF) shl 24)
        val bytesPerSample = (flags and 0x03) + 1
        val mono = (flags and 0x04) != 0
        val rateIdx = (flags ushr 23) and 0x0F
        val rate = WV_RATES.getOrNull(rateIdx) ?: 0
        if (rate <= 0) return null
        return Stream(
            bitDepth = bytesPerSample * 8,
            sampleRate = rate,
            channels = if (mono) 1 else 2,
            codec = "wv",
        )
    }

    /**
     * TTA：`TTA1` 头，全部小端。
     * `4..5 format / 6..7 channels / 8..9 bitsPerSample / 10..13 sampleRate / 14..17 samples`
     */
    private fun _tta(raf: RandomAccessFile): Stream? {
        raf.seek(0)
        val b = _read(raf, 22) ?: return null
        if (b.size < 22) return null
        fun u16le(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        fun u32le(o: Int) = (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)
        val channels = u16le(6)
        val bits = u16le(8)
        val rate = u32le(10)
        if (rate <= 0) return null
        return Stream(bitDepth = bits, sampleRate = rate, channels = channels, codec = "tta")
    }

    /**
     * AC3：`0x0B77` 同步字之后是 `fscod(2) frmsizecod(6) bsid(5) bsmod(3) acmod(3)`。
     * 有损，位深恒 0。
     */
    private fun _ac3(raf: RandomAccessFile): Stream? {
        raf.seek(0)
        val b = _read(raf, 8) ?: return null
        if (b.size < 8) return null
        if (!((b[0].toInt() and 0xFF) == 0x0B && (b[1].toInt() and 0xFF) == 0x77)) return null
        val b2 = b[2].toInt() and 0xFF
        val fscod = (b2 ushr 6) and 0x03
        val rate = when (fscod) {
            0 -> 48_000
            1 -> 44_100
            2 -> 32_000
            else -> 0
        }
        if (rate <= 0) return null
        // acmod 在 b[5] 的高 3 位；acmod=1 时另有 2 位决定单/双声道，这里按 2 处理即可
        val acmod = ((b[5].toInt() and 0xFF) ushr 5) and 0x07
        val channels = when (acmod) {
            0 -> 2
            1 -> 2
            2 -> 2
            3 -> 2
            4 -> 2
            5 -> 2
            6 -> 2
            else -> 1
        }
        return Stream(bitDepth = 0, sampleRate = rate, channels = channels, codec = "ac3")
    }

    /** WavPack 的采样率索引表（`flags` bit23~26） */
    private val WV_RATES = intArrayOf(
        6_000, 8_000, 9_600, 11_025, 12_000, 16_000, 22_050, 24_000,
        32_000, 44_100, 48_000, 64_000, 88_200, 96_000, 192_000, 0,
    )

    /**
     * WMA（ASF 容器）：翻 Header Object 里的子对象，找 **Stream Properties Object**，
     * 再往后 54 字节就是 WAVEFORMATEX。
     *
     * 布局（全部小端）：
     * - Header Object：GUID(16) + size(8) + objectCount(4) + reserved(2)
     * - Stream Properties Object：GUID(16) + size(8) + [streamType(16) + errType(16) +
     *   timeOffset(8) + typeLen(4) + errLen(4) + flags(2) + reserved(4)] = 54，之后是
     *   WAVEFORMATEX：formatTag(2) channels(2) sampleRate(4) avgBytes(4) blockAlign(2) bits(2)
     *
     * 有损，位深恒 0。
     */
    private fun _wma(raf: RandomAccessFile): Stream? {
        raf.seek(0)
        val head = _read(raf, 30) ?: return null
        if (head.size < 30) return null
        if (!_magic(raf, 0, 0x30, 0x26, 0xB2, 0x75)) return null
        fun u32le(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)
        val objectCount = u32le(head, 24)
        val fileEnd = raf.length()
        var pos = 30L
        repeat(objectCount.coerceIn(1, 64)) {
            if (pos + 24 > fileEnd) return null
            raf.seek(pos)
            val obj = _read(raf, 24) ?: return null
            if (obj.size < 24) return null
            // size 是 64 位 LE，实际上限远小于 Long
            val size = (u32le(obj, 16).toLong() and 0xFFFFFFFFL) or
                ((u32le(obj, 20).toLong() and 0xFFFFFFFFL) shl 32)
            if (size < 24) return null
            // Stream Properties Object：GUID `B7DC0791-A9B7-11CF-…`。
            // **ASF 的 GUID 是混合字节序**：前 3 段按小端落盘，所以文件里读到的
            // 前四字节是 `91 07 DC B7`，不是 `B7 DC 07 91`。
            val isStreamProps = (obj[0].toInt() and 0xFF) == 0x91 &&
                (obj[1].toInt() and 0xFF) == 0x07 &&
                (obj[2].toInt() and 0xFF) == 0xDC &&
                (obj[3].toInt() and 0xFF) == 0xB7
            if (isStreamProps) {
                raf.seek(pos + 24 + 54)
                val wf = _read(raf, 16) ?: return null
                if (wf.size < 16) return null
                val channels = (wf[2].toInt() and 0xFF) or ((wf[3].toInt() and 0xFF) shl 8)
                val rate = u32le(wf, 4)
                if (rate > 0) {
                    return Stream(bitDepth = 0, sampleRate = rate, channels = channels, codec = "wma")
                }
            }
            pos += size
        }
        return null
    }

    private fun _u32be(raf: RandomAccessFile, pos: Long): Long? {
        raf.seek(pos)
        val b = _read(raf, 4) ?: return null
        return (((b[0].toLong() and 0xFF) shl 24) or ((b[1].toLong() and 0xFF) shl 16) or
            ((b[2].toLong() and 0xFF) shl 8) or (b[3].toLong() and 0xFF))
    }

    private fun _u64be(raf: RandomAccessFile, pos: Long): Long? {
        raf.seek(pos)
        val b = _read(raf, 8) ?: return null
        var v = 0L
        for (i in 0..7) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    private fun _u16le(raf: RandomAccessFile, pos: Long): Int? {
        raf.seek(pos)
        val b = _read(raf, 2) ?: return null
        return _u16le(b, 0)
    }

    private fun _u32le(raf: RandomAccessFile, pos: Long): Long? {
        raf.seek(pos)
        val b = _read(raf, 4) ?: return null
        return (((b[3].toLong() and 0xFF) shl 24) or ((b[2].toLong() and 0xFF) shl 16) or
            ((b[1].toLong() and 0xFF) shl 8) or (b[0].toLong() and 0xFF))
    }

    private fun _u64le(raf: RandomAccessFile, pos: Long): Long? {
        raf.seek(pos)
        val b = _read(raf, 8) ?: return null
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    private fun _u16be(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun _u16le(b: ByteArray, o: Int): Int =
        ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o].toInt() and 0xFF)

    private fun _u32le(b: ByteArray, o: Int): Long =
        ((b[o + 3].toLong() and 0xFF) shl 24) or ((b[o + 2].toLong() and 0xFF) shl 16) or
            ((b[o + 1].toLong() and 0xFF) shl 8) or (b[o].toLong() and 0xFF)

    private fun _u32be(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    /** AIFF 的 80 位 IEEE 754 extended（采样率） */
    private fun _extended80(b: ByteArray, o: Int): Double {
        val exp = (((b[o].toInt() and 0x7F) shl 8) or (b[o + 1].toInt() and 0xFF)) - 16383
        var mant = 0.0
        for (i in 0..7) mant = mant * 256.0 + (b[o + 2 + i].toInt() and 0xFF)
        return mant * 2.0.pow((exp - 63).toDouble())
    }
}
