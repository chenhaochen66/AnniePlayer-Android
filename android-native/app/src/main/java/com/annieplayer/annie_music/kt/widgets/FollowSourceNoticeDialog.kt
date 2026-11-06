package com.annieplayer.annie_music.kt.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing

/**
 * 「强制按源采样率输出」的说明弹窗 —— 优势与代价摊开写（通俗口径）。
 *
 * 为什么需要它：这个开关**有两面**，写进副标题会把那一行撑成一段话，
 * 而只说好的一面等于骗人。所以副标题只留"它做什么"，两面都放这里。
 *
 * 用 `Dialog` 而不是内联浮层：它自带返回键处理（`onDismissRequest`），
 * 不必为一次性的说明再往 `BACK_RULES` 里加规则。
 */
@Composable
fun FollowSourceNoticeDialog(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .background(AppColors.modalPanel, RoundedCornerShape(AppRadius.r20)),
        ) {
            // 与其余说明弹窗同一套收边：一道 1.5dp 渐变细线，轻，但不是"没设计"
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.5.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(AppColors.primary, AppColors.primaryA12),
                        ),
                    ),
            )

            Column(modifier = Modifier.padding(AppSpacing.sp20)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "采样率",
                        color = AppColors.primary,
                        fontSize = AppFontSize.xs,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier
                            .background(AppColors.primaryA15, RoundedCornerShape(AppRadius.r4))
                            .padding(horizontal = AppSpacing.sp6, vertical = AppSpacing.sp2),
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.sp8))
                    Text(
                        text = "强制按源采样率输出",
                        color = AppColors.textPrimary,
                        fontSize = AppFontSize.size17,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Spacer(Modifier.height(AppSpacing.sp14))

                for ((index, line) in FOLLOW_SOURCE_NOTICE.withIndex()) {
                    if (index > 0) Spacer(Modifier.height(AppSpacing.sp10))
                    Text(
                        text = line,
                        color = if (index == 0) {
                            AppColors.textSecondary
                        } else {
                            AppColors.textTertiary
                        },
                        fontSize = AppFontSize.size13,
                        lineHeight = 20.sp,
                    )
                }

                Spacer(Modifier.height(AppSpacing.sp20))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "知道了",
                        color = AppColors.black,
                        fontSize = AppFontSize.size13,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .background(AppColors.primary, RoundedCornerShape(AppRadius.pill))
                            .clickableNoRipple(onDismiss)
                            .padding(horizontal = AppSpacing.sp16, vertical = AppSpacing.sp8),
                    )
                }
            }
        }
    }
}

/**
 * 说明正文。**通俗优先**：短句、日常词，能用"鼓点""余响"就不写"瞬态""振铃"。
 *
 * 顺序即判断顺序：行为 → 两条收益 → 代价 → 适用范围。
 *
 * 三件事不能省：
 * 1. **点名本应用用的重采样器（r8brain）** —— 用户要判断"交出去到底损失什么"，
 *    得知道原来用的是谁；代价里"拿精度换安静"那句才有参照。
 * 2. **收益二必须归因到"素材来源"，不能归因到曲风** —— 实测过：同样是 16 bit /
 *    44.1 kHz 的电子舞曲，有的有沙、有的完全没有（差别在该文件顶端 20 kHz 以上
 *    有没有编码残留）。写成"电子舞曲有问题"是错的，会误伤整类曲风。
 * 3. **代价要挂在"系统确实转换"这个前提上**，并给出可忽略的情形（能跟随源率的设备）。
 *
 * 曾经有一条「不是高频太多（砍 38 dB 也无效）」的说明，已**经用户要求删除**：
 * 那条是给排查方向用的，对只想决定"开不开"的人属于噪音。
 */
private val FOLLOW_SOURCE_NOTICE: List<String> = listOf(
    "不做重采样，按源率交付；要转时由系统 AudioTrack 完成。",
    "收益一 · 原样送达：设备能跟随源率时（多数国砖如此），全程零转换。",
    "收益二 · 消除部分歌曲的沙沙底噪：问题在素材 —— 某些平台的 16bit/44.1k 版本" +
        "顶端带编码残留，同曲风的别的版本却干净。鼓点密的素材撞上本应用那级陡峭滤波器" +
        "（r8brain），每个鼓点约 18 ms 余响连成一片就是沙；不转就没有。",
    "代价 · 仅当系统需要转换时：滤波器更简单、高频上限更低，顶端空气感减少，" +
        "即拿精度换安静（本应用残差 −117.5 dB、声道互不干扰、无预振铃）。" +
        "设备能跟随源率时（部分国产播放器）可忽略此代价。",
    "蓝牙只有收益一（采样率由耳机编解码器决定）；调音照常生效。",
)
