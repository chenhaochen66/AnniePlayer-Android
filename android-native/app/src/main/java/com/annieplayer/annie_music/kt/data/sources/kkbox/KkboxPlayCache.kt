package com.annieplayer.annie_music.kt.data.sources.kkbox

import android.content.Context
import com.annieplayer.annie_music.kt.data.model.KkboxSession
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.core.util.effectivePlayQuality
import com.annieplayer.annie_music.kt.core.util.downloadQualityFor
import java.io.File

// ================================================================================================
// KKBOX 高音质播放缓存（DRM 文件解密后的本地副本）
// ================================================================================================
//
// KKBOX 票据里能直接交给播放器的只有 `mp3_128k_chromecast`（明文）。320k / hifi / hires 都是
// kkdrm 包：`*_hls_cenc` 是 HLS + CENC（需要 Widevine，拿不到），而 `*_download_kkdrm` 是整
// 文件 RC4(drop 512)（前 1024 字节是包裹头）—— 后者我们能解，解完就是可直接播的普通音频。
//
// 所以高音质播放 = 先落一份解密文件再播本地。文件按 `歌曲id_档位` 命名，重复播放不再下载。

object KkboxPlayCache {

    /**
     * 缓存目录上限；超出后按最后修改时间删最旧的。
     *
     * 缓存形态和其他平台一致：**跟随播放窗口**（约 30 秒），听完一首才整首落盘。
     * 所以 1GB 大约能留 25~35 首普通无损；Hi-Res 长曲（150~200MB）能留 5~6 首。
     */
    private const val MAX_BYTES = 1024L * 1024 * 1024

    private var _dir: File? = null

    fun init(context: Context) {
        _dir = File(context.cacheDir, "kkbox_play").apply { mkdirs() }
    }

    private fun _dirOrFallback(): File {
        val dir = _dir ?: File(System.getProperty("java.io.tmpdir").orEmpty(), "kkbox_play")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun _safeId(songId: String): String =
        songId.replace(Regex("[^A-Za-z0-9_-]"), "_")

    /**
     * 已缓存的文件；没有返回 null。
     *
     * 两个来源都算命中：
     * 1. 整首下载分支留下的 `<id>_<档位>.<ext>`；
     * 2. 边播边解密（[KkboxPlayServer]）下完的 `play_<id>_<档位>.part` —— 它旁边有 `.done`
     *    标记才算完整，否则那是「正在下」，播不了。
     *
     * `.part` 结尾的一律跳过：整首下载的临时文件也叫 `<id>_<档位>.<ext>.part`，
     * 前缀完全匹配，误命中会把半截文件交给播放器。
     */
    private fun _cached(dir: File, songId: String, quality: Quality): File? {
        val safe = _safeId(songId)
        val prefix = "${safe}_${quality.value}."
        dir.listFiles()?.firstOrNull {
            it.name.startsWith(prefix) && it.length() > 0 && !it.name.endsWith(".part")
        }?.let { return it }
        val streamed = File(dir, "play_${safe}_${quality.value}.part")
        if (streamed.length() > 0 && File(streamed.path + ".done").exists()) return streamed
        return null
    }

    /**
     * 取这首歌在 [quality] 档位下可直接播放的地址。
     *
     * 顺序：明文流（只有 `mp3_128k_chromecast` 一种）→ 解密成本地文件（FLAC 边下边播，
     * m4a/其它整首下完再播）。**很多曲目票据里没有明文档**，那时 128k 也走解密。
     */
    suspend fun playUrl(song: Song, quality: Quality, session: KkboxSession): String {
        val rawId = song.id.removePrefix("kkbox:")

        // 明文流只有 128k 那一档 —— **只在目标就是 128k 时才用**。
        // 放在无条件最前面会让「选了 Hi-Res 也听 128k」（票里几乎总有 chromecast 档）。
        if (quality == Quality.k128k) {
            val plain = runCatching { getPlayUrl(session.sid, rawId, session.kkid) }.getOrNull()
            if (plain != null) {
                if (plain.sid != session.sid) kkboxSessionUpdater(session.copy(sid = plain.sid))
                if (plain.url.isNotEmpty()) return plain.url
            }
        }

        // 2) 解密路径
        val dir = _dirOrFallback()
        _cached(dir, song.id, quality)?.let { return "file://${it.absolutePath}" }

        val format = kkboxQualityFormatMap[kkboxQualityKeyOf(quality)].orEmpty()
        if (format.startsWith("flac")) {
            // 边播边解密这条路原来**从不淘汰**（淘汰只在整首下载分支调），所以 FLAC 能一路堆到
            // 700MB+。这里补上，并把当前这首排除在淘汰之外。
            _evictIfNeeded(dir, keepPrefix = "play_${_safeId(song.id)}_${quality.value}")
            return try {
                KkboxPlayServer.start(song, quality, session)
            } catch (e: Throwable) {
                // 高音质准备失败（票据超时 / 网络被抢 / CDN 抖动）时退回 128k 明文流：
                // 音质降一档，但至少有声音，不至于让界面直接报「加载失败」。
                val plain = runCatching { getPlayUrl(session.sid, rawId, session.kkid) }.getOrNull()
                if (plain != null && plain.url.isNotEmpty()) {
                    if (plain.sid != session.sid) kkboxSessionUpdater(session.copy(sid = plain.sid))
                    plain.url
                } else {
                    throw e
                }
            }
        }

        val ticket = fetchKkboxDownloadTicket(song.id, kkboxQualityKeyOf(quality))
        if (ticket.url.isEmpty()) {
            throw IllegalStateException("KKBOX 未返回可用音源（可能是会员权限或地区限制）")
        }
        if (!isDrmFormat(ticket.formatName)) return ticket.url

        val ext = qualityExtension(formatNameToQuality(ticket.formatName))
        val target = File(dir, "${_safeId(song.id)}_${quality.value}$ext")
        val tmp = File(dir, "${target.name}.part")
        if (tmp.exists()) tmp.delete()

        _evictIfNeeded(dir)
        streamDecryptKkboxAudio(ticket.url, session.licContentKey, tmp.absolutePath)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            // rename 失败（跨分区/占用）：退回直接搬一份，别让播放整条失败
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        return "file://${target.absolutePath}"
    }

    /** 现有缓存超过上限时，按最后修改时间从旧到新删（不动本次要写的那首） */
    private fun _evictIfNeeded(dir: File, keepPrefix: String? = null) {
        // 没有 `.done` 的 `.part` 是**正在下载 / 正在被本地代理读取**的：删掉会直接断流。
        // 有 `.done` 的已经是完整缓存，可以正常淘汰。
        val files = dir.listFiles()?.filter { f ->
            f.isFile && (!f.name.endsWith(".part") || File(f.path + ".done").exists())
        } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_BYTES) break
            if (keepPrefix != null && f.name.startsWith(keepPrefix)) continue
            val len = f.length()
            if (f.delete()) {
                total -= len
                if (f.name.endsWith(".part")) File(f.path + ".done").delete()
            }
        }
    }
}

/** 播放档位：配置档 → 按歌夹档；「自动」→ 这首歌自己的最高档（与队列缓存 key 同一套规则） */
internal fun kkboxPlayQuality(song: Song, configured: Quality?): Quality {
    val effective = effectivePlayQuality(MusicPlatform.kkbox, song.qualities, configured)
    return downloadQualityFor(song, effective)
}
