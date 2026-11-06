package com.annieplayer.annie_music.kt.ui.widgets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.annieplayer.annie_music.kt.core.audio.ExclusiveVolume
import com.annieplayer.annie_music.kt.core.audio.effectiveBits
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 独占通路的音量条（按音量键才出现，4 秒后淡出）。
 *
 * 形状是**刻度柱**而不是连续条：每段对应固定 dB，亮到第几段就是响度落在哪，
 * 一眼能读出"还有多少余量"，也不会因为一次按键只挪了 1/61 而看不出变化。
 *
 * 配色沿用播放页输出胶囊的语义（金 = 独占通路正在逐字节直出），**但音量本身不参与
 * 胶囊判定** —— 这是两件独立的事，混在一个标志里会让人以为调音量破坏了直出。
 */
@Composable
fun BoxScope.ExclusiveVolumeBar() {
    val state by ExclusiveVolume.state.collectAsState()
    var visible by remember { mutableStateOf(false) }
    // tick 每次按键自增 → 这里重启计时，连续按键不闪断
    LaunchedEffect(state.tick) {
        if (state.tick == 0L) return@LaunchedEffect
        visible = true
        delay(4000)
        visible = false
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)) + slideInHorizontally(tween(160)) { it / 2 },
        exit = fadeOut(tween(260)) + slideOutHorizontally(tween(260)) { it / 2 },
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .padding(end = 14.dp, top = 96.dp),
    ) {
        VolumeCard(state)
    }
}

private val AccentGold = Color(0xFFF5C451)
/** 我方数字衰减（会丢位深）。与金区分开：金 = 衰减在 DAC 内部，数据一个字节没动。 */
private val AccentBlue = Color(0xFF60A5FA)
private val LockedGrey = Color(0xFF9CA3AF)
private val CardBg = Color(0xE6101013)
private val CardEdge = Color(0x1FFFFFFF)
private val TrackOff = Color(0x22FFFFFF)

private const val SEGMENTS = 24

@Composable
private fun VolumeCard(state: ExclusiveVolume.State) {
    // 金 = 硬件音量（衰减在 DAC 内部，PCM 一字节未动）；蓝 = 我方数字衰减（会丢位深）；
    // 灰 = 既没有硬件音量又是 DSD 直出，改不了。
    val accent = when {
        state.hardware != null -> AccentGold
        state.locked -> LockedGrey
        else -> AccentBlue
    }
    val shownDb = state.effectiveDb
    val fraction = ((shownDb - ExclusiveVolume.MIN_DB) /
        (ExclusiveVolume.MAX_DB - ExclusiveVolume.MIN_DB)).coerceIn(0f, 1f)
    val lit = (fraction * SEGMENTS).roundToInt().coerceIn(if (state.locked) 0 else 1, SEGMENTS)
    Column(
        modifier = Modifier
            .width(56.dp)
            .height(240.dp)
            .background(CardBg, RoundedCornerShape(28.dp))
            .border(1.dp, CardEdge, RoundedCornerShape(28.dp))
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "${shownDb.roundToInt()}",
            color = if (state.locked) LockedGrey else Color.White,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = "dB",
            color = Color(0x8AFFFFFF),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(10.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 从上往下画：最高段在最上
            for (i in SEGMENTS - 1 downTo 0) {
                val on = i < lit
                Box(
                    modifier = Modifier
                        .width(if ((i + 1) % 6 == 0) 22.dp else 16.dp)
                        .height(3.dp)
                        .background(
                            if (on) accent else TrackOff,
                            RoundedCornerShape(1.5.dp),
                        ),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = when {
                state.hardware != null -> "DAC"
                state.locked -> "DSD"
                state.routeKey == "usbDirect" -> "USB"
                state.routeKey == "aaudioExclusive" -> "AA"
                else -> "—"
            },
            color = accent.copy(alpha = 0.85f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
        )
        // 软件衰减的代价：每 −6 dB 约丢 1 bit。硬件音量不损失位深，所以不写这一行。
        if (state.hardware == null && !state.locked && state.db < -0.5f) {
            Text(
                text = "≈${effectiveBits(24, state.db)} bit",
                color = Color(0x66FFFFFF),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
