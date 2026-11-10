package com.annieplayer.annie_music.kt.features.favorites

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.annieplayer.annie_music.kt.core.compose.activityViewModel
import com.annieplayer.annie_music.kt.core.state.FavoriteViewModel
import com.annieplayer.annie_music.kt.data.favorites.PlaylistScope
import com.annieplayer.annie_music.kt.data.favorites.UserPlaylist
import com.annieplayer.annie_music.kt.ui.theme.AnnieTypography
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.clickableRipple
import kotlinx.coroutines.launch
import java.util.UUID

/** 弹窗里封面方块的边长 */
private val CoverBoxSize = 132.dp

/**
 * **新建自建播放列表**（收藏库播放列表页「自建」tab 的 `+`）。
 *
 * 样式与项目其它确认弹窗一致（`AlertDialog` + `AppColors.surface`）：
 * 封面方块（点它从系统文件管理器/相册选图）+ 标题输入 + 取消/创建。
 *
 * ## 封面
 * 选完立刻读成压缩后的 JPEG（`readPlaylistCoverBytes`，长边 ≤ 512），创建时连同歌单一起
 * 写进 SQLite 的 `cover_blob`。**不记住 `content://`**：那个读权限出了本次授权就失效，
 * 重启后封面会变空白。
 *
 * 不选就用占位图；「自动取第一首歌的封面」还没有（导入歌单之后才谈得上）。
 */
@Composable
internal fun NewPlaylistDialog(
    visible: Boolean,
    scope: PlaylistScope,
    onDismiss: () -> Unit,
    /** 创建完成 → 宿主把这个 id 的详情页压进栈 */
    onCreated: (playlistId: String) -> Unit,
    favoriteViewModel: FavoriteViewModel = activityViewModel(),
) {
    if (!visible) return

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var title by remember(visible) { mutableStateOf("") }
    var pickedUri by remember(visible) { mutableStateOf<android.net.Uri?>(null) }
    var busy by remember(visible) { mutableStateOf(false) }

    // 系统相册 / 文件管理器（Photo Picker：不需要任何存储权限）
    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) pickedUri = uri }

    val canCreate = title.isNotBlank() && !busy

    fun create() {
        if (!canCreate) return
        busy = true
        val uri = pickedUri
        coroutineScope.launch {
            val coverBytes = uri?.let { readPlaylistCoverBytes(context, it) }
            val id = UUID.randomUUID().toString()
            favoriteViewModel.saveUserPlaylist(
                UserPlaylist(
                    id = id,
                    name = title.trim(),
                    scope = scope,
                    createdAt = System.currentTimeMillis(),
                ),
                coverBlob = coverBytes,
            )
            busy = false
            onCreated(id)
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(text = "新建播放列表", style = AnnieTypography.dialogTitle) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // ── 封面：点一下选图 ──
                Box(
                    modifier = Modifier
                        .size(CoverBoxSize)
                        .clip(RoundedCornerShape(AppRadius.r10))
                        .background(AppColors.whiteA05)
                        .clickableRipple {
                            pickImage.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (pickedUri != null) {
                        AsyncImage(
                            model = pickedUri,
                            contentDescription = "封面",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(AppColors.heart),
                            contentAlignment = Alignment.Center,
                        ) {
                            CameraGlyph(modifier = Modifier.size(26.dp), tint = AppColors.white)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(AppSpacing.sp6))
                Text(
                    text = if (pickedUri == null) "点封面选择图片" else "已选择 · 点封面可重选",
                    color = AppColors.white38,
                    fontSize = AppFontSize.xs,
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp16))

                // ── 标题 ──
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppColors.whiteA05, RoundedCornerShape(AppRadius.r8))
                        .padding(AppSpacing.sp12),
                ) {
                    if (title.isEmpty()) {
                        Text(
                            text = "播放列表标题",
                            color = AppColors.white30,
                            fontSize = AppFontSize.size15,
                        )
                    }
                    BasicTextField(
                        value = title,
                        onValueChange = { title = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = AppColors.white,
                            fontSize = AppFontSize.size15,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Start,
                        ),
                        cursorBrush = SolidColor(AppColors.heart),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = canCreate, onClick = { create() }) {
                Text(
                    text = if (busy) "创建中…" else "创建",
                    color = if (canCreate) AppColors.primary else AppColors.textDisabled,
                    fontWeight = AppFontWeight.semiBold,
                )
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text(text = "取消", color = AppColors.textTertiary)
            }
        },
        containerColor = AppColors.surface,
    )
}

/** 相机剪影（圆角机身 + 顶部取景凸起 + 镜头圈）—— 核心图标集里没有 PhotoCamera */
@Composable
private fun CameraGlyph(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.075f
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.06f, h * 0.24f),
            size = Size(w * 0.88f, h * 0.62f),
            cornerRadius = CornerRadius(w * 0.12f, w * 0.12f),
            style = Stroke(width = stroke),
        )
        val lid = Path().apply {
            moveTo(w * 0.34f, h * 0.24f)
            lineTo(w * 0.40f, h * 0.13f)
            lineTo(w * 0.60f, h * 0.13f)
            lineTo(w * 0.66f, h * 0.24f)
        }
        drawPath(lid, tint, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(
            color = tint,
            radius = w * 0.20f,
            center = Offset(w * 0.5f, h * 0.55f),
            style = Stroke(width = stroke),
        )
    }
}
