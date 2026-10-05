package com.annieplayer.annie_music.kt.data

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

/**
 * 音频元数据写入（ffmpeg）。
 *
 * 对齐 Flutter 的 ffmpeg 打标（`kkbox_detail_screen.dart#_tagDrmAudio` 与
 * `download_service.dart`）：用 `-c copy` 把元数据（标题 / 艺术家 / 专辑 / 专辑艺术家 /
 * 曲目号 / 碟号 / 年份 / 歌词）与封面（`attached_pic`）写入文件，**覆盖 mp3 / flac / m4a
 * 等全部封装**（替代此前手写的 ID3v2.4 / Vorbis+PICTURE 写入器）。
 *
 * 依赖维护中的社区分叉 `dev.ffmpegkit-maintained:ffmpeg-kit-*`（与原
 * `com.arthenica:ffmpeg-kit-*` 的 `com.arthenica.ffmpegkit` API 完全一致）。
 */
internal object FfmpegTagger {

    private const val TAG = "FfmpegTagger"

    /**
     * 就地为 [inputPath] 写入元数据：生成同扩展名临时文件 → ffmpeg → 覆盖回原文件。
     *
     * @return 是否成功；失败时**保留原文件不动**（对齐 Flutter：`tagged=false` 用原始文件）。
     */
    fun tagInPlace(
        inputPath: String,
        coverPath: String?,
        title: String,
        artist: String,
        album: String,
        lyric: String,
        albumArtist: String?,
        trackNumber: String?,
        discNumber: String?,
        releaseDate: String?,
        isrc: String? = null,
        version: String? = null,
        copyright: String? = null,
    ): Boolean {
        val input = File(inputPath)
        if (!input.exists()) return false
        val cover = coverPath?.let { File(it) }?.takeIf { it.exists() }

        val dot = inputPath.lastIndexOf('.')
        val ext = if (dot >= 0) inputPath.substring(dot) else ".mp3"
        val tmpOut = File(input.parentFile, "tagged_${System.currentTimeMillis()}$ext")
        if (tmpOut.exists()) tmpOut.delete()

        val args = ArrayList<String>()
        args += listOf("-y", "-i", inputPath)
        if (cover != null) args += listOf("-i", cover.absolutePath)
        args += listOf("-map", "0:a")
        if (cover != null) args += listOf("-map", "1:v")
        args += listOf("-c", "copy")
        args += listOf("-metadata", "title=$title")
        args += listOf("-metadata", "artist=$artist")
        if (album.isNotEmpty()) args += listOf("-metadata", "album=$album")
        if (!albumArtist.isNullOrEmpty()) args += listOf("-metadata", "album_artist=$albumArtist")
        if (!trackNumber.isNullOrEmpty()) args += listOf("-metadata", "track=$trackNumber")
        if (!discNumber.isNullOrEmpty()) args += listOf("-metadata", "disc=$discNumber")
        if (!releaseDate.isNullOrEmpty()) args += listOf("-metadata", "date=$releaseDate")
    if (!isrc.isNullOrEmpty()) args += listOf("-metadata", "ISRC=$isrc")
    if (!version.isNullOrEmpty()) args += listOf("-metadata", "comment=$version")
    if (!copyright.isNullOrEmpty()) args += listOf("-metadata", "copyright=$copyright")
        if (lyric.isNotEmpty()) args += listOf("-metadata", "lyrics=$lyric")
        if (cover != null) args += listOf("-disposition:v", "attached_pic")
        args += tmpOut.absolutePath

        val ok = try {
            val session = FFmpegKit.executeWithArguments(args.toTypedArray())
            ReturnCode.isSuccess(session.returnCode)
        } catch (t: Throwable) {
            Log.w(TAG, "[ffmpeg] tag failed: ${t.message}")
            false
        }

        if (!ok || !tmpOut.exists()) {
            if (tmpOut.exists()) tmpOut.delete()
            return false
        }

        // 用打标后的文件替换原文件（对齐 Dart：同目录 rename，失败则复制覆盖）
        return try {
            if (!tmpOut.renameTo(input)) {
                tmpOut.copyTo(input, overwrite = true)
                tmpOut.delete()
            }
            true
        } catch (_: Throwable) {
            try {
                tmpOut.copyTo(input, overwrite = true)
                tmpOut.delete()
                true
            } catch (_: Throwable) {
                false
            }
        }
    }
}
