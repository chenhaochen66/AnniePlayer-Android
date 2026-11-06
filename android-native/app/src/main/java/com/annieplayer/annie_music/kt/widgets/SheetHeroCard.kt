package com.annieplayer.annie_music.kt.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing

/** 身份卡上的一枚规格角标。[accent] 为 true 时用主色（音质档位这类「规格」）。 */
data class SheetHeroChip(
    val text: String,
    val accent: Boolean = false,
)

/**
 * 面板顶部的「这是什么」卡：封面 + 标题 + 副标题 + 可选规格角标。
 *
 * 存在的理由：浮层里一旦只有一串同权重的动作行，用户在动手之前得先确认「我操作的是
 * 哪首歌 / 哪个文件」—— 靠标题栏的「歌曲操作」「歌曲详情」是确认不了的。把身份信息
 * 提到最前面，下面的动作才有主语。
 *
 * 视觉规格（与全站卡片一致，不要各自发挥）：
 * - 竖向渐变 [AppColors.whiteA06] → [AppColors.whiteA03]，圆角 [AppRadius.r16]
 * - 封面 60dp / 圆角 [AppRadius.r10] / 底色 [AppColors.whiteA04]，无封面时画音符
 * - 标题 17sp SemiBold，副标题 13sp [AppColors.textSecondary]，角标 10sp 胶囊
 *
 * 目前两个面板共用：歌曲操作（[SongActionsSheet]）与本地文件详情（`LocalFileDetailsSheet`）。
 */
@Composable
fun SheetHeroCard(
    title: String,
    subtitle: String,
    cover: String?,
    modifier: Modifier = Modifier,
    chips: List<SheetHeroChip> = emptyList(),
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.sp16, vertical = AppSpacing.sp6)
            .clip(RoundedCornerShape(AppRadius.r16))
            .background(
                Brush.verticalGradient(
                    listOf(AppColors.whiteA06, AppColors.whiteA03),
                ),
            )
            .padding(AppSpacing.sp12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(AppSpacing.sp60)
                .clip(RoundedCornerShape(AppRadius.r10))
                .background(AppColors.whiteA04),
            contentAlignment = Alignment.Center,
        ) {
            if (cover != null) {
                AsyncImage(
                    model = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                MusicNoteGlyph(
                    modifier = Modifier.size(AppIconSize.icon24),
                    tint = AppColors.whiteA15,
                )
            }
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp12))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = AppColors.textPrimary,
                fontSize = AppFontSize.size17,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp2))
            Text(
                text = subtitle,
                color = AppColors.textSecondary,
                fontSize = AppFontSize.size13,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (chips.isNotEmpty()) {
                Spacer(modifier = Modifier.height(AppSpacing.sp8))
                // 规格角标：音质档位用主色，时长 / 体积用中性 —— 一眼分出「规格」与「属性」
                Row(verticalAlignment = Alignment.CenterVertically) {
                    chips.forEachIndexed { index, chip ->
                        if (index > 0) Spacer(modifier = Modifier.width(AppSpacing.sp6))
                        HeroChip(text = chip.text, accent = chip.accent)
                    }
                }
            }
        }
    }
}

/** 规格胶囊（音质档位用主色，其余中性灰） */
@Composable
private fun HeroChip(text: String, accent: Boolean = false) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 25))
            .background(if (accent) AppColors.primaryA15 else AppColors.whiteA06)
            .padding(horizontal = AppSpacing.sp8, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (accent) AppColors.primary else AppColors.white60,
            fontSize = AppFontSize.size10,
            fontWeight = if (accent) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}
