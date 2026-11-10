package com.annieplayer.annie_music.kt.widgets

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing

/**
 * 「加入到歌单」二级面板：歌曲 → 我喜欢 / 自建歌单。
 *
 * 从 `SongActionsSheet.kt` 单独拆出来：那里的 ⋮ 动作行（谁调它、什么顺序、什么色调）与
 * 这里的「收进哪个容器」是两件事，代码里各占一份也没关系，混在一个 1100 行的文件里
 * 改一个目标容器的视觉要去读另一套排版规则。
 *
 * 分边规则（谁能加、那一边共几首）全部由宿主算好传进来 —— 本文件不判断平台。
 */

/**
 * 面板里的一行：一个目标容器 + 这首歌是否已经在里面。
 *
 * [alreadyContains] 为 true 的行画成「已加入」并且**不可点** —— 再加一次没有意义，
 * 底层（`appendPlaylistSong` / `toggleSong`）也会静默跳过或反向取消。
 */
data class SongPlaylistOption(
    val playlistId: String,
    val name: String,
    val trackCount: Int,
    val alreadyContains: Boolean,
)

/**
 * 二级：选择播放列表（「加入到歌单」）。
 *
 * **第一行固定是「我喜欢」**：收藏单曲和加进歌单在用户手里是同一个动作（「把这首收起来」），
 * 分成两个入口只会让人多点一次。[onAddToFavorites] 为 null 时整行不渲染。
 *
 * 「我喜欢」是**开关**：点一下收藏（行变「已收藏」），再点一下取消收藏（行变回「+」）。
 * 自建歌单不是开关 —— 已加入就不可点，拿走一首走 ⋮ 的「从本歌单移除」。
 *
 * 高度：`contentMaxHeightFraction = 0.5f` = 最多半屏，条目多时在面板内向下滑。
 * 滚动由 [AppSheet] 统一提供，本组件不再套自己的滚动容器（套了就是两层滚动）。
 */
@Composable
internal fun PlaylistPickerSheet(
    visible: Boolean,
    options: List<SongPlaylistOption>,
    favoriteSelected: Boolean,
    favoriteCount: Int,
    onClose: () -> Unit,
    onSelect: (playlistId: String) -> Unit,
    onAddToFavorites: (() -> Unit)?,
) {
    AppSheet(
        visible = visible,
        // 标题是「添加到」而不是「选择播放列表」：第一行是「我喜欢」，它不是一个播放列表。
        // 有直接宾语就能同时盖住两个目标（收藏夹 / 歌单），不必再分两层。
        title = "添加到",
        onClose = onClose,
        contentMaxHeightFraction = 0.5f,
    ) {
        // 左右内缩 8：行自己的按压底色是一块圆角带，通栏到底会贴住面板边缘
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.sp8),
        ) {
            onAddToFavorites?.let { addToFavorites ->
                PickerRow(
                    title = "我喜欢",
                    subtitle = if (favoriteSelected) {
                        // 已收藏时把「再点一下」写在副标题里：右侧胶囊只是个状态标签，
                        // 不写这句就没人会想到它还能点。
                        if (favoriteCount > 0) "$favoriteCount 首 · 再点一下取消收藏" else "再点一下取消收藏"
                    } else if (favoriteCount > 0) {
                        "$favoriteCount 首"
                    } else {
                        "还没有收藏的单曲"
                    },
                    selected = favoriteSelected,
                    selectedLabel = "已收藏",
                    // 「我喜欢」是开关：已收藏再点一下就取消（歌单不行，见 PickerRow 注释）
                    deselectable = true,
                    leading = {
                        PickerTile(active = favoriteSelected, tint = AppColors.heart) {
                            HeartGlyph(
                                modifier = Modifier.size(AppIconSize.icon20),
                                // 已收藏才用正红；未收藏跟其它图标同一档，否则整行像已经点亮
                                tint = if (favoriteSelected) AppColors.heart else AppColors.glyphStrong,
                                filled = favoriteSelected,
                            )
                        }
                    },
                    onTap = addToFavorites,
                )
            }

            PickerSectionLabel(title = "自建歌单", count = options.size)
            if (options.isEmpty()) {
                PickerEmptyHint()
            } else {
                options.forEach { option ->
                    PickerRow(
                        title = option.name,
                        subtitle = "${option.trackCount} 首",
                        selected = option.alreadyContains,
                        leading = {
                            PickerTile(active = option.alreadyContains, tint = AppColors.primary) {
                                PlaylistParseGlyph(
                                    modifier = Modifier.size(AppIconSize.icon20),
                                    tint = if (option.alreadyContains) {
                                        AppColors.primary
                                    } else {
                                        AppColors.glyphMid
                                    },
                                )
                            }
                        },
                        onTap = { onSelect(option.playlistId) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(AppSpacing.sp8))
        }
    }
}

/**
 * 面板的一行：左 40dp 图标底片 + 名称 / 首数 + 右侧「+」圆片或「已加入」胶囊。
 *
 * 三点与 `AppSheetRow` 不同：
 * 1. **图标有底片**：每行代表一个容器（歌单 / 收藏夹），底片把「容器」和 ⋮ 面板里的
 *    「动作」区分开。
 * 2. **右侧是状态而不是箭头**：点下去就地生效、不换页，所以画「+」圆片 / 「已加入」胶囊，
 *    一眼看出哪几行已经加过。
 * 3. **按压反馈**：整行 5% 白底、90ms，纯 draw 阶段，不触发布局。
 *
 * [deselectable]（默认 false）决定「已选中之后还能不能点」：
 * - **false**（自建歌单）：已加入就不可点。再加一次没意义，而「从歌单里拿掉」是另一个语义，
 *   ⋮ 面板里有专门的「从本歌单移除」，不该混进这个「只做加法」的面板。
 * - **true**（我喜欢）：这是一个开关 —— 再点一下就是取消收藏，副标题会写明。
 */
@Composable
private fun PickerRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    leading: @Composable () -> Unit,
    onTap: () -> Unit,
    selectedLabel: String = "已加入",
    deselectable: Boolean = false,
) {
    val clickableRow = !selected || deselectable
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressedBg by animateColorAsState(
        targetValue = if (pressed && clickableRow) AppColors.whiteA05 else Color.Transparent,
        animationSpec = tween(durationMillis = 90),
        label = "pickerRowPress",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.r10))
            .background(pressedBg)
            .then(
                // 不可点的行**整条 clickable 都不挂**（不是 clickable(enabled = false)：
                // 那样它仍占命中测试，只是不响应）
                if (clickableRow) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onTap,
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = AppSpacing.sp8, vertical = AppSpacing.sp6),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(modifier = Modifier.width(AppSpacing.sp12))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                // 已加入降一档而不是压到禁用色：它仍是有效信息，只是不用再点了
                color = if (selected) AppColors.textSecondary else AppColors.textPrimary,
                fontSize = AppFontSize.base,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp2))
            Text(
                text = subtitle,
                color = AppColors.textTertiary,
                fontSize = AppFontSize.sm,
                maxLines = 1,
            )
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp8))
        if (selected) {
            Text(
                text = selectedLabel,
                color = AppColors.primary,
                fontSize = AppFontSize.size10,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(AppRadius.r6))
                    .background(AppColors.primaryA08)
                    .padding(horizontal = AppSpacing.sp8, vertical = AppSpacing.sp3),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(AppSpacing.sp28)
                    .clip(CircleShape)
                    .background(AppColors.primaryA08),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = AppColors.primary,
                    modifier = Modifier.size(AppIconSize.icon16),
                )
            }
        }
    }
}

/** 行首的 40dp 图标底片：未激活是中性白，激活后染上该行自己的色（心=红、歌单=主色）。 */
@Composable
private fun PickerTile(active: Boolean, tint: Color, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(AppSpacing.sp40)
            .clip(RoundedCornerShape(AppRadius.r10))
            .background(
                Brush.verticalGradient(
                    colors = if (active) {
                        listOf(tint.copy(alpha = 0.22f), tint.copy(alpha = 0.07f))
                    } else {
                        listOf(AppColors.whiteA06, AppColors.whiteA03)
                    },
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** 分节小标题 + 向右延伸的细线 + 条目数（与本地歌曲详情页的分节标题同一条语言）。 */
@Composable
private fun PickerSectionLabel(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = AppSpacing.sp8,
                end = AppSpacing.sp8,
                top = AppSpacing.sp14,
                bottom = AppSpacing.sp4,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = AppColors.textTertiary,
            fontSize = AppFontSize.size10,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
        )
        Spacer(modifier = Modifier.width(AppSpacing.sp8))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(AppColors.whiteA04),
        )
        if (count > 0) {
            Spacer(modifier = Modifier.width(AppSpacing.sp8))
            Text(text = "$count", color = AppColors.textTertiary, fontSize = AppFontSize.size10)
        }
    }
}

/**
 * 「还没有自建播放列表」的空态。
 *
 * 不只是一句「没有」：给出**下一步在哪**（播放列表页右上角 +）。空态是新用户第一次点
 * 「加入到歌单」时唯一看到的东西，只写「没有」等于告诉他这条路走不通。
 */
@Composable
private fun PickerEmptyHint() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.r10))
            .background(AppColors.whiteA03)
            .padding(horizontal = AppSpacing.sp14, vertical = AppSpacing.sp14),
    ) {
        Text(
            text = "还没有自建播放列表",
            color = AppColors.textSecondary,
            fontSize = AppFontSize.size13,
        )
        Spacer(modifier = Modifier.height(AppSpacing.sp3))
        Text(
            text = "在播放列表页点右上角 + 新建",
            color = AppColors.textTertiary,
            fontSize = AppFontSize.sm,
        )
    }
}
