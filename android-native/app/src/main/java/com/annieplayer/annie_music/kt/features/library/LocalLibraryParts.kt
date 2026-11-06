package com.annieplayer.annie_music.kt.features.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.annieplayer.annie_music.kt.data.local.LocalGroup
import com.annieplayer.annie_music.kt.data.local.LocalTrackMeta
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.MusicNoteGlyph
import com.annieplayer.annie_music.kt.widgets.PlayingIndicator
import com.annieplayer.annie_music.kt.widgets.SongMoreGlyph
import com.annieplayer.annie_music.kt.widgets.clickableRipple
import com.annieplayer.annie_music.kt.widgets.playingRowBackground

/**
 * 本地曲库的视觉零件（图标 / 宫格 / 统计行 / 歌曲行 / 分组行）。
 *
 * 尺寸全部落在这里，Screen 只做拼接，方便后续单独调其中一块而不动布局逻辑。
 */

/** 宫格入口的四种图形（自绘，风格对齐 `widgets/Icons.kt`：圆头描边 + 9% 笔画） */
enum class LocalCategory { artist, album, folder, recent }

/**
 * 每类入口的强调色 —— **只出现在 30dp 的图标底片里**。
 *
 * 卡片底刻意保持中性：四块彩色卡片并排会像玩具，只有角标带色才像 iOS 设置页那种
 * 「分类有颜色、界面不染色」。色值全部取现有 token，不新增。
 */
private fun LocalCategory.accent(): Color = when (this) {
    LocalCategory.artist -> AppColors.primary   // 靛蓝
    LocalCategory.album -> AppColors.success    // 绿
    LocalCategory.folder -> AppColors.warning   // 琥珀
    LocalCategory.recent -> AppColors.exclusive // 橙红
}

/** 自绘笔画比例（与 `Icons.kt` 的 `GlyphStrokeRatio` 一致，那边是 private，这里独立一份） */
private const val GlyphStrokeRatio = 0.09f

/**
 * 宫格卡片高（两列时宽约 159dp，1.6:1）。
 *
 * 原来是 64dp 的「一行图标 + 文字」横条；改成 100dp 的竖版块，
 * 才装得下「标题在上、计数在下」这两层信息（计数从角标胶囊升级成卡片主信息）。
 */
private val EntryCardHeight = 100.dp

/** 宫格卡片圆角（卡片变大后 18dp 显得方，跟着放大一档） */
private val EntryCardRadius = 20.dp

/** 宫格卡片内边距（上下左右同值：标题贴左上、计数贴右下，靠一个 padding 定住两条边） */
private val EntryCardPadding = 16.dp

/** 宫格卡片的图标边长（裸图标，不带底片） */
private val EntryIconSize = 20.dp

/** 统计行右侧胶囊按钮的高度 */
private val StatsPillHeight = 26.dp

/** 列表封面边长（比搜索结果的 44dp 大一号：曲库是主场页面） */
private val TrackCoverSize = 52.dp

/** 宫格与搜索框之间的间距 */
private val SectionGap = 14.dp

/**
 * 分类图标。
 *
 * 四个图形共用同一笔画与端点风格（[GlyphStrokeRatio] + 圆头），并排放在宫格里粗细一致；
 * 不用 material-icons 是因为它的分类图标粗细不齐（Person 实心、Folder 风格又不同）。
 */
@Composable
fun LocalCategoryGlyph(
    kind: LocalCategory,
    modifier: Modifier = Modifier,
    tint: Color = AppColors.white70,
) {
    Canvas(modifier = modifier) {
        val d = size.minDimension
        val stroke = Stroke(
            width = d * GlyphStrokeRatio,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        val cx = size.width / 2f
        val cy = size.height / 2f

        when (kind) {
            // 人形：头 + 肩弧
            LocalCategory.artist -> {
                drawCircle(tint, radius = d * 0.185f, center = Offset(cx, d * 0.33f), style = stroke)
                val shoulder = Path().apply {
                    moveTo(d * 0.18f, d * 0.80f)
                    quadraticBezierTo(cx, d * 0.44f, d * 0.82f, d * 0.80f)
                }
                drawPath(shoulder, tint, style = stroke)
            }

            // 唱片套：圆角方 + 内圈 + 中心孔
            LocalCategory.album -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(d * 0.14f, d * 0.14f),
                    size = Size(d * 0.72f, d * 0.72f),
                    cornerRadius = CornerRadius(d * 0.16f, d * 0.16f),
                    style = stroke,
                )
                drawCircle(tint, radius = d * 0.135f, center = Offset(cx, cy), style = stroke)
                drawCircle(tint, radius = d * 0.032f, center = Offset(cx, cy))
            }

            // 文件夹：盖片折角 + 实体
            LocalCategory.folder -> {
                val lid = Path().apply {
                    moveTo(d * 0.15f, d * 0.40f)
                    lineTo(d * 0.15f, d * 0.28f)
                    lineTo(d * 0.43f, d * 0.28f)
                    lineTo(d * 0.50f, d * 0.38f)
                }
                drawPath(lid, tint, style = stroke)
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(d * 0.15f, d * 0.38f),
                    size = Size(d * 0.70f, d * 0.36f),
                    cornerRadius = CornerRadius(d * 0.09f, d * 0.09f),
                    style = stroke,
                )
            }

            // 时钟：圆 + 时针分针
            LocalCategory.recent -> {
                drawCircle(tint, radius = d * 0.34f, center = Offset(cx, cy), style = stroke)
                drawLine(
                    tint,
                    start = Offset(cx, cy),
                    end = Offset(cx, d * 0.30f),
                    strokeWidth = d * GlyphStrokeRatio,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    tint,
                    start = Offset(cx, cy),
                    end = Offset(d * 0.68f, d * 0.56f),
                    strokeWidth = d * GlyphStrokeRatio,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

/**
 * 宫格入口卡片：**竖版块**（两列，约 159×100dp），左上「图标 + 标题」，右下大号计数。
 *
 * 与上一版（64dp 横条：图标底片 + 标题 + 右侧计数胶囊）的区别只在排版：
 * 计数从「像角标的小胶囊」升级成卡片的主信息 —— 字号 26sp、字重 SemiBold、右对齐。
 * 选中态、按压反馈、渐变底这些语义都保留。
 *
 * ## 没有描边（用户要求「不要有明显的边缘」）
 * 上一版有 1dp 13% 白发丝描边。64dp 横条时它是必要的（否则灰底贴在深色背景上读不出边界），
 * 但卡片放大到 100dp 之后，这圈描边反过来变成「一个框」——矩形轮廓比底色本身更抢眼。
 * 现在边界只靠**竖向渐变底的明度差**表达（顶部 6% → 底部 3% 白），四个角自然淡出。
 * 选中态也一并不描边：底色换主色 12% + 文字/图标转主色，已经足够区分。
 *
 * 三个刻意的取值：
 * 1. **图标裸放**（20dp，分类色）。原来套 30dp 底片是为了在 64dp 横条里撑住高度；
 *    现在卡片本身够大，底片反而变成「卡中卡」，去掉之后四张卡的第一行才齐。
 * 2. **计数用 [AppColors.textSecondary]**（70% 白）。它比标题大一号却更淡 ——
 *    标题是「这是什么」，计数是「里面有多少」，主从关系靠字号给，不靠亮度抢。
 * 3. **`tnum` 等宽数字**。四张卡并排、位数不同时右缘不抖，否则 7 / 30 / 128 会参差。
 *
 * 计数为空（如「最近播放」没有数据源）时该行留白，卡片高度不变 —— 四格仍然等大。
 */
@Composable
fun LocalEntryCard(
    kind: LocalCategory,
    label: String,
    trailing: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(EntryCardRadius)
    val accent = kind.accent()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressTint by animateColorAsState(
        targetValue = if (pressed) AppColors.whiteA05 else Color.Transparent,
        animationSpec = tween(durationMillis = 90),
        label = "localEntryPress",
    )
    Column(
        modifier = modifier
            .height(EntryCardHeight)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    if (selected) {
                        listOf(AppColors.primaryA12, AppColors.primaryA12)
                    } else {
                        listOf(AppColors.whiteA06, AppColors.whiteA03)
                    },
                ),
            )
            .background(pressTint)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(EntryCardPadding),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LocalCategoryGlyph(
                kind = kind,
                modifier = Modifier.size(EntryIconSize),
                tint = if (selected) AppColors.primary else accent,
            )
            Spacer(modifier = Modifier.width(AppSpacing.sp8))
            Text(
                text = label,
                color = if (selected) AppColors.primary else AppColors.textPrimary,
                fontSize = AppFontSize.size15,
                fontWeight = AppFontWeight.medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = trailing.orEmpty(),
            color = if (selected) AppColors.primary else AppColors.textSecondary,
            fontSize = AppFontSize.size26,
            fontWeight = AppFontWeight.semiBold,
            lineHeight = 30.sp,
            style = TextStyle(fontFeatureSettings = "tnum"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 统计行：`213 首  4.8 GB`（左，两级字号）+ 三个胶囊按钮（右：升降序 / 排序 / 重扫）。
 *
 * 改版前是一行 11sp 灰字 + 三个裸文字按钮，整条读起来像一句说明、不像工具条。
 * 现在把「数量」提成 15sp SemiBold（这一条里唯一的主信息），体积降为 11sp 注释；
 * 右侧三个动作收进同规格胶囊（26dp / pill / 5% 白底）——
 * 同样的元素，靠字号差和「工具该有外壳」把它从文案变成控件。
 *
 * 读取中时左侧换成进度文案（主色），下方 2dp 进度轨不变。
 */
@Composable
fun LocalStatsRow(
    countText: String,
    sizeText: String,
    progressText: String?,
    progress: Float?,
    sortLabel: String,
    onSort: () -> Unit,
    ascending: Boolean,
    onToggleOrder: () -> Unit,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (progressText != null) {
                Text(
                    text = progressText,
                    color = AppColors.primary,
                    fontSize = AppFontSize.xs,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Row(
                    modifier = Modifier.weight(1f),
                    // 底部对齐：11sp 的体积贴着 15sp 数字的基线走，不浮在中间
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        text = countText,
                        color = AppColors.textPrimary,
                        fontSize = AppFontSize.size15,
                        fontWeight = AppFontWeight.semiBold,
                        maxLines = 1,
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.sp6))
                    Text(
                        text = sizeText,
                        color = AppColors.white38,
                        fontSize = AppFontSize.xs,
                        maxLines = 1,
                        modifier = Modifier.padding(bottom = 1.dp),
                    )
                }
            }
            StatsPill(text = if (ascending) "↑ 升序" else "↓ 降序", onClick = onToggleOrder)
            Spacer(modifier = Modifier.width(AppSpacing.sp6))
            StatsPill(text = sortLabel, onClick = onSort)
            Spacer(modifier = Modifier.width(AppSpacing.sp6))
            StatsPill(text = "重扫", onClick = onRescan)
        }
        if (progress != null) {
            Spacer(modifier = Modifier.height(AppSpacing.sp6))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(AppColors.whiteA06),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(2.dp)
                        .background(AppColors.primary),
                )
            }
        }
    }
}

/** 统计行的胶囊按钮：三个同规格并排，才不会被读成三句文案 */
@Composable
private fun StatsPill(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(StatsPillHeight)
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(AppColors.whiteA05)
            .clickableRipple { onClick() }
            .padding(horizontal = AppSpacing.sp10),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = AppColors.white60,
            fontSize = AppFontSize.xs,
            maxLines = 1,
        )
    }
}

/** 分区标题 + 返回（进入艺术家 / 专辑 / 文件夹分组后显示） */
@Composable
fun LocalSectionHeader(
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = AppSpacing.sp8, bottom = AppSpacing.sp4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(AppRadius.r6))
                    .clickableRipple { onBack() }
                    .padding(horizontal = AppSpacing.sp8, vertical = AppSpacing.sp4),
            ) {
                Text(text = "← 返回", color = AppColors.primary, fontSize = AppFontSize.size13)
            }
            Spacer(modifier = Modifier.width(AppSpacing.sp4))
        }
        Text(
            text = title,
            color = AppColors.textPrimary,
            fontSize = AppFontSize.size17,
            fontWeight = AppFontWeight.semiBold,
            letterSpacing = 0.2.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (!subtitle.isNullOrEmpty()) {
            Spacer(modifier = Modifier.width(AppSpacing.sp8))
            Text(
                text = subtitle,
                // 35% 白在 11sp 下读起来像禁用态，提到 38% 才是「注释」
                color = AppColors.white38,
                fontSize = AppFontSize.xs,
                maxLines = 1,
            )
        }
    }
}

/**
 * 歌曲行：封面 52dp + 两行文字 + 音质/大小/时长 chip 行 + 行尾 ⋮。
 *
 * 比搜索结果的 `SongTile` 少一行（本地文件没有「来源平台」这个概念），第三行换成
 * 编码/大小/时长这类**文件本身**的信息 —— 本地曲库里这才是用户想看的。
 */
@Composable
fun LocalTrackRow(
    meta: LocalTrackMeta,
    isActive: Boolean,
    playingTint: Color,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    /** 非空时在最左侧显示两位曲目号（专辑 / 艺术家详情页用；总列表页不显示，省宽度） */
    index: Int? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.r8))
            .then(if (isActive) Modifier.playingRowBackground(playingTint) else Modifier)
            .clickableRipple { onClick() }
            .padding(
                start = AppSpacing.sp8,
                end = AppSpacing.sp4,
                top = AppSpacing.sp6,
                bottom = AppSpacing.sp6,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            // 定宽 + 居中，避免 9 → 10 时行首跳动
            Text(
                text = "%02d".format(index + 1),
                color = if (isActive) playingTint else AppColors.textTertiary,
                fontSize = AppFontSize.size13,
                fontWeight = FontWeight.Medium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.width(26.dp),
            )
            Spacer(modifier = Modifier.width(AppSpacing.sp6))
        }
        Box(
            modifier = Modifier
                .size(TrackCoverSize)
                .clip(RoundedCornerShape(AppRadius.r8))
                .background(AppColors.whiteA05),
            contentAlignment = Alignment.Center,
        ) {
            if (meta.coverPath != null) {
                AsyncImage(
                    model = "file://${meta.coverPath}",
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                MusicNoteGlyph(modifier = Modifier.size(AppIconSize.icon20), tint = AppColors.whiteA15)
            }
            if (isActive) PlayingIndicator(tint = playingTint, modifier = Modifier.size(18.dp))
        }

        Spacer(modifier = Modifier.width(AppSpacing.sp12))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = meta.title.ifBlank { meta.file.nameWithoutExtension },
                color = if (isActive) playingTint else AppColors.textPrimary,
                fontSize = AppFontSize.size15,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp2))
            Text(
                // 多歌手统一成 `A / B`（见 LocalTrackMeta.artistDisplay）；原始写法留在文件标签里
                text = listOfNotNull(
                    meta.artistDisplay.takeIf { it.isNotBlank() },
                    meta.album.takeIf { it.isNotBlank() && it != meta.folderName },
                ).joinToString(" · ").ifEmpty { meta.folderName },
                color = AppColors.textSecondary,
                fontSize = AppFontSize.size13,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp2))
            Text(
                text = meta.metaLine,
                color = AppColors.textTertiary,
                fontSize = AppFontSize.size10,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // 行尾 ⋮：文件详情 / 加入队列
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(AppRadius.r8))
                .clickableRipple { onMore() }
                .padding(AppSpacing.sp10),
            contentAlignment = Alignment.Center,
        ) {
            SongMoreGlyph(modifier = Modifier.size(AppIconSize.icon18), tint = AppColors.glyphMid)
        }
    }
}

/** 分组行（艺术家 / 专辑 / 文件夹）：封面 + 标题 + 副标题 + 数量 */
@Composable
fun LocalGroupRow(
    group: LocalGroup,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.r8))
            .clickableRipple { onClick() }
            .padding(horizontal = AppSpacing.sp8, vertical = AppSpacing.sp6),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(TrackCoverSize)
                .clip(RoundedCornerShape(AppRadius.r8))
                .background(AppColors.whiteA05),
            contentAlignment = Alignment.Center,
        ) {
            if (group.coverPath != null) {
                AsyncImage(
                    model = "file://${group.coverPath}",
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LocalCategoryGlyph(
                    kind = LocalCategory.folder,
                    modifier = Modifier.size(AppIconSize.icon20),
                    tint = AppColors.whiteA15,
                )
            }
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp12))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = group.title,
                color = AppColors.textPrimary,
                fontSize = AppFontSize.size15,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (group.subtitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(AppSpacing.sp2))
                Text(
                    text = group.subtitle,
                    color = AppColors.textSecondary,
                    fontSize = AppFontSize.size13,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = "${group.count}",
            color = AppColors.textTertiary,
            fontSize = AppFontSize.xs,
        )
    }
}

/**
 * 专辑网格卡片（2 列）：方形封面 + 专辑名 + 艺术家 · 首数。
 *
 * 视觉对齐流媒体的专辑搜索结果网格；本地没有「发行公司/音质角标」这类云端字段，
 * 副标题就放「艺术家 · N 首」，信息更少但看得懂。
 */
@Composable
fun LocalAlbumCard(
    group: LocalGroup,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.r10))
            .clickableRipple { onClick() },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(AppRadius.r10))
                .background(AppColors.whiteA05),
            contentAlignment = Alignment.Center,
        ) {
            if (group.coverPath != null) {
                AsyncImage(
                    model = "file://${group.coverPath}",
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                MusicNoteGlyph(
                    modifier = Modifier.size(AppIconSize.icon28),
                    tint = AppColors.whiteA15,
                )
            }
        }
        Spacer(modifier = Modifier.height(AppSpacing.sp8))
        Text(
            text = group.title,
            color = AppColors.textPrimary,
            fontSize = AppFontSize.size13,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(AppSpacing.sp2))
        Text(
            text = listOfNotNull(
                group.subtitle.takeIf { it.isNotBlank() },
                "${group.count} 首",
            ).joinToString(" · "),
            color = AppColors.textTertiary,
            fontSize = AppFontSize.xs,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = AppSpacing.sp12),
        )
    }
}
