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
// 共用组件：自绘图标（material-icons-core 未覆盖的形状）
// 音质选择器不在此处：复用共用组件 QualityPickerSheet（QualityPicker.kt）
// ================================================================================================

/** chevron_left：返回箭头 / 分页上一页 */
@Composable
internal fun DetailChevronLeft(modifier: Modifier = Modifier, tint: Color = AppColors.textSecondary) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.12f
        drawLine(tint, Offset(w * 0.62f, h * 0.16f), Offset(w * 0.30f, h * 0.5f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.30f, h * 0.5f), Offset(w * 0.62f, h * 0.84f), stroke, StrokeCap.Round)
    }
}

/** chevron_right：分页下一页 */
@Composable
internal fun DetailChevronRight(modifier: Modifier = Modifier, tint: Color = AppColors.textSecondary) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.12f
        drawLine(tint, Offset(w * 0.38f, h * 0.16f), Offset(w * 0.70f, h * 0.5f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.70f, h * 0.5f), Offset(w * 0.38f, h * 0.84f), stroke, StrokeCap.Round)
    }
}

/** arrow_upward：专辑「时间正序」 */
@Composable
internal fun DetailArrowUpward(modifier: Modifier = Modifier, tint: Color = AppColors.whiteA45) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.14f
        drawLine(tint, Offset(w * 0.5f, h * 0.86f), Offset(w * 0.5f, h * 0.18f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.18f, h * 0.5f), Offset(w * 0.5f, h * 0.16f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.82f, h * 0.5f), Offset(w * 0.5f, h * 0.16f), stroke, StrokeCap.Round)
    }
}

/** arrow_downward：专辑「时间倒序」 */
@Composable
internal fun DetailArrowDownward(modifier: Modifier = Modifier, tint: Color = AppColors.whiteA45) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.14f
        drawLine(tint, Offset(w * 0.5f, h * 0.14f), Offset(w * 0.5f, h * 0.82f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.18f, h * 0.5f), Offset(w * 0.5f, h * 0.84f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.82f, h * 0.5f), Offset(w * 0.5f, h * 0.84f), stroke, StrokeCap.Round)
    }
}

/** error_outline：描边圆 + 感叹号（加载失败态，36dp） */
@Composable
internal fun DetailErrorOutline(modifier: Modifier = Modifier, tint: Color = AppColors.textDisabled) {
    Canvas(modifier = modifier) {
        val min = size.minDimension
        val stroke = min * 0.09f
        drawCircle(
            color = tint,
            radius = (min - stroke) / 2f,
            style = Stroke(width = stroke),
        )
        drawLine(tint, Offset(min * 0.5f, min * 0.28f), Offset(min * 0.5f, min * 0.55f), stroke, StrokeCap.Round)
        drawCircle(color = tint, radius = min * 0.055f, center = Offset(min * 0.5f, min * 0.72f))
    }
}

/** error（实心）：下载失败徽标（对齐 Flutter `Icons.error`） */
@Composable
internal fun DetailErrorSolid(modifier: Modifier = Modifier, tint: Color = AppColors.danger) {
    Canvas(modifier = modifier) {
        val min = size.minDimension
        val stroke = min * 0.11f
        drawCircle(color = tint, radius = min / 2f)
        drawLine(
            color = AppColors.background,
            start = Offset(min * 0.5f, min * 0.26f),
            end = Offset(min * 0.5f, min * 0.56f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawCircle(color = AppColors.background, radius = min * 0.07f, center = Offset(min * 0.5f, min * 0.74f))
    }
}

/** check：选中态白色对勾 */
@Composable
internal fun DetailCheckMark(modifier: Modifier = Modifier, tint: Color = AppColors.white) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.13f
        val path = Path().apply {
            moveTo(w * 0.22f, h * 0.52f)
            lineTo(w * 0.43f, h * 0.73f)
            lineTo(w * 0.78f, h * 0.28f)
        }
        drawPath(path = path, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round))
    }
}

/** album：描边圆 + 圆心实点（FLAC / HiRes 行图标、专辑封面兜底） */
@Composable
internal fun DetailAlbumGlyph(modifier: Modifier = Modifier, tint: Color = AppColors.white10) {
    Canvas(modifier = modifier) {
        val min = size.minDimension
        val stroke = min * 0.11f
        drawCircle(
            color = tint,
            radius = (min - stroke) / 2f,
            style = Stroke(width = stroke),
        )
        drawCircle(color = tint, radius = min * 0.14f)
    }
}

/** download（实心）：批量下载按钮图标 */
@Composable
internal fun DetailDownloadSolid(modifier: Modifier = Modifier, tint: Color = AppColors.white) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.12f
        val path = Path().apply {
            moveTo(w * 0.36f, h * 0.10f)
            lineTo(w * 0.64f, h * 0.10f)
            lineTo(w * 0.64f, h * 0.44f)
            lineTo(w * 0.82f, h * 0.44f)
            lineTo(w * 0.50f, h * 0.80f)
            lineTo(w * 0.18f, h * 0.44f)
            lineTo(w * 0.36f, h * 0.44f)
            close()
        }
        drawPath(path = path, color = tint)
        drawLine(tint, Offset(w * 0.20f, h * 0.92f), Offset(w * 0.80f, h * 0.92f), stroke, StrokeCap.Round)
    }
}

/** check_box_outline_blank：专辑 / 歌单多选未选中态 */
@Composable
internal fun DetailCheckBoxBlank(modifier: Modifier = Modifier, tint: Color = AppColors.whiteA25) {
    Canvas(modifier = modifier) {
        val min = size.minDimension
        val stroke = min * 0.11f
        drawRoundRect(
            color = tint,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(min - stroke, min - stroke),
            cornerRadius = CornerRadius(min * 0.16f, min * 0.16f),
            style = Stroke(width = stroke),
        )
    }
}

/** person_outline：头像兜底（100 圆形内的 36dp 描边人像） */
@Composable
internal fun DetailPersonOutline(modifier: Modifier = Modifier, tint: Color = AppColors.textDisabled) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        drawCircle(
            color = tint,
            radius = w * 0.17f,
            center = Offset(w * 0.5f, h * 0.31f),
            style = Stroke(width = stroke),
        )
        drawArc(
            color = tint,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.16f, h * 0.50f),
            size = Size(w * 0.68f, h * 0.62f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}
