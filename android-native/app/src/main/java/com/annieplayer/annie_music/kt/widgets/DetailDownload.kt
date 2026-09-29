package com.annieplayer.annie_music.kt.widgets

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.annieplayer.annie_music.kt.core.routing.BackTarget
import com.annieplayer.annie_music.kt.core.routing.OverlayVisibilityFlags
import com.annieplayer.annie_music.kt.core.routing.handleBack
import com.annieplayer.annie_music.kt.core.state.AlbumDetailData
import com.annieplayer.annie_music.kt.core.state.DownloadViewModel
import com.annieplayer.annie_music.kt.core.state.PlayerViewModel
import com.annieplayer.annie_music.kt.core.state.SingerDetailData
import com.annieplayer.annie_music.kt.core.state.UiState
import com.annieplayer.annie_music.kt.core.state.UiViewModel
import com.annieplayer.annie_music.kt.data.DownloadDirService
import com.annieplayer.annie_music.kt.data.LyricResult
import com.annieplayer.annie_music.kt.data.enrichSong
import com.annieplayer.annie_music.kt.data.fetchArtistAlbumsByPlatform
import com.annieplayer.annie_music.kt.data.fetchArtistAvatarByPlatform
import com.annieplayer.annie_music.kt.data.fetchArtistSongsByPlatform
import com.annieplayer.annie_music.kt.data.fetchDownloadUrlBySong
import com.annieplayer.annie_music.kt.data.fetchLyric
import com.annieplayer.annie_music.kt.data.fetchPlayUrlBySong
import com.annieplayer.annie_music.kt.data.model.Album
import com.annieplayer.annie_music.kt.data.model.DownloadStatus
import com.annieplayer.annie_music.kt.data.model.DownloadTask
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.network.globalOkHttp
import com.annieplayer.annie_music.kt.data.sources.buildAlbumCoverUrl
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

// ================================================================================================
// 共用组件：下载助手（等价 Flutter `lib/data/download_service.dart` 的 downloadSong，
// Kotlin 端暂未做 ffmpeg 元数据写入，下载原始音频文件并返回保存路径）
// ================================================================================================

/** 下载请求 UA（对齐 Flutter `_chromeUa`） */
private const val DetailChromeUa =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36"

/**
 * 下载一首歌到「用户自定义目录」或应用缓存 downloads/，返回最终文件路径。
 *
 * 行为对齐 Dart `downloadSong` 的下载段：进度回调、临时文件、最终重命名；
 * 差异：无 ffmpeg 打标 / 无 MediaStore 扫描（Kotlin 侧尚未移植这两个依赖）。
 */
internal suspend fun detailDownloadSong(
    context: Context,
    songId: String,
    title: String,
    artist: String,
    coverUrl: String,
    album: String?,
    downloadUrl: String,
    quality: Quality,
    onProgress: (Double) -> Unit,
    lyric: String,
    tlyric: String? = null,
    albumArtist: String? = null,
    trackNumber: String? = null,
    discNumber: String? = null,
    releaseDate: String? = null,
): String = withContext(Dispatchers.IO) {
    val ext = if (quality == Quality.flac || quality == Quality.hires) ".flac" else ".mp3"
    val safeName = "$title - $artist$ext".replace(Regex("""[/\\?%*:|"<>]"""), "_")
    val customDir = DownloadDirService.getDownloadDirPath()
    val dir = if (!customDir.isNullOrEmpty()) File(customDir) else File(context.cacheDir, "downloads")
    if (!dir.exists()) dir.mkdirs()
    val safeSongId = songId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
    val temp = File(dir, "tmp_$safeSongId$ext")
    if (temp.exists()) temp.delete()

    val request = Request.Builder()
        .url(downloadUrl)
        .header("User-Agent", DetailChromeUa)
        .build()
    globalOkHttp.newCall(request).execute().use { resp ->
        if (!resp.isSuccessful) throw IOException("Download failed: ${resp.code}")
        val body = resp.body ?: throw IOException("Download failed")
        val total = body.contentLength()
        body.byteStream().use { input ->
            temp.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var received = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    received += read
                    if (total > 0) onProgress(received.toDouble() / total)
                }
            }
        }
    }
    if (!temp.exists()) throw IOException("Download failed")
    onProgress(0.5)

    // 封面 / 歌词 / 元数据：Kotlin 侧暂无 ffmpeg，跳过打标（保留原始音频文件）。
    // 下方 onProgress(0.8) 与 Dart 的进度节点对齐。
    onProgress(0.8)

    val finalFile = File(dir, safeName)
    if (finalFile.exists()) finalFile.delete()
    val renamed = temp.renameTo(finalFile)
    val out = if (renamed) {
        finalFile
    } else {
        temp.copyTo(finalFile, overwrite = true)
        temp.delete()
        finalFile
    }
    onProgress(1.0)
    out.absolutePath
}
