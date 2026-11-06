package com.annieplayer.annie_music.kt.data.sources.kkbox

import android.content.Context
import com.annieplayer.annie_music.kt.core.platform.MediaStoreScan
import com.annieplayer.annie_music.kt.data.DownloadDirService
import com.annieplayer.annie_music.kt.data.DownloadService
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import java.io.File

// ================================================================================================
// KKBOX 下载（官方票据 + DRM 解密）
// ================================================================================================
//
// KKBOX 的高音质档全是 **kkdrm 加密包**（`flac_24_download_kkdrm` / `flac_16_download_kkdrm` /
// `aac_320k_m4a_kkdrm1` / `mp3_192k_kkdrm1`），只有 `mp3_128k_chromecast` 是明文 —— 那是播放档。
// 所以「下载」在 KKBOX 上必须经过 `streamDecryptKkboxAudio`（RC4 drop=512 + 跳过 1024 字节包裹头），
// 不能像四平台那样把直链丢给通用下载器。
//
// 历史包袱已清掉：以前仓库层把 KKBOX 的下载转给**酷我直链**，而 KKBOX 的 track id 在酷我
// 根本不存在 —— 那条路只会 404，或者更糟：解析到别的歌。现在只走官方。

object KkboxDownloader {

    /** 解密中间文件的落地目录（= app cacheDir）；未 init 时退化为 JVM 临时目录 */
    private var _cacheDir: File? = null

    fun init(context: Context) {
        _cacheDir = context.cacheDir
    }

    /**
     * 下载并落盘一首 KKBOX 歌。
     *
     * @param onProgress 0..1
     * @return 自定义目录时是最终文件路径；默认目录入库成功时是 `content://` URI
     */
    suspend fun download(song: Song, onProgress: (Double) -> Unit): String {
        // 官方票据：档位固定要最高档，票里没有该格式时接口自己按 hiRes → hifi → 320k → 192k → 128k 降
        val ticket = fetchKkboxDownloadTicket(song.id, "hires")
        val rawId = song.id.removePrefix("kkbox:")
        val qualityKey = formatNameToQuality(ticket.formatName)
        val lyric = runCatching { getSongLyrics(ticket.sid, rawId).lrc }.getOrDefault("")

        if (!isDrmFormat(ticket.formatName)) {
            // 官方唯一的明文档（mp3_128k_chromecast）：直接走通用流式下载
            return DownloadService.downloadSong(
                songId = song.id,
                title = song.title,
                artist = song.artist,
                coverUrl = song.cover,
                album = song.album,
                downloadUrl = ticket.url,
                quality = Quality.k128k,
                onProgress = onProgress,
                lyric = lyric,
                albumArtist = song.albumArtist,
                trackNumber = song.trackNumber,
                releaseDate = song.releaseDate,
                artistNames = song.artistNames,
            )
        }

        // DRM：解密需要会话里的 licContentKey（票据只给地址，不给密钥）
        val session = kkboxSessionProvider()
            ?: throw IllegalStateException("KKBOX 需要先登录账号才能下载")
        val ext = qualityExtension(qualityKey)
        val dir = _cacheDir ?: File(System.getProperty("java.io.tmpdir").orEmpty())
        val safeId = song.id.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val tmp = File(dir, "kkbox_drm_$safeId$ext")
        if (tmp.exists()) tmp.delete()

        // 解密后的样本长度不变、内容就是完整音频，落盘后按常规写标签
        streamDecryptKkboxAudio(ticket.url, session.licContentKey, tmp.absolutePath) { p ->
            onProgress(p * 0.9)
        }
        runCatching {
            DownloadService.tagAudioFile(
                inputPath = tmp.absolutePath,
                title = song.title,
                artist = song.artist,
                album = song.album.orEmpty(),
                coverUrl = song.cover,
                lyric = lyric,
                albumArtist = song.albumArtist,
                trackNumber = song.trackNumber,
                releaseDate = song.releaseDate,
                coverCacheKey = song.id,
            )
        }
        onProgress(0.97)
        return _deliver(tmp, "${_baseName(song)}$ext")
    }

    /** `歌手 - 标题`，去掉文件系统非法字符（与 `DownloadService` 的命名规则一致） */
    private fun _baseName(song: Song): String =
        "${song.title} - ${song.artist}".replace(Regex("[/\\\\?%*:|\"<>]"), "_")

    /**
     * 把打好标签的成品挪到最终落点：自定义目录 → 真实文件；默认目录 → 系统媒体库。
     *
     * 与 `DownloadService._downloadLegacy` 同一套语义（那边是流式路径，这里已经有成品文件）。
     */
    private fun _deliver(tmp: File, displayName: String): String {
        val customDir = DownloadDirService.getDownloadDirPath()
        if (!customDir.isNullOrEmpty()) {
            val dir = File(customDir)
            if (!dir.exists()) dir.mkdirs()
            val target = File(dir, displayName)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            return target.absolutePath
        }
        val uri = runCatching { MediaStoreScan.scanFileToMediaStore(tmp.absolutePath) }.getOrNull()
        if (!uri.isNullOrEmpty()) {
            runCatching { tmp.delete() }
            return uri
        }
        return tmp.absolutePath
    }
}
