package com.annieplayer.annie_music.kt.features.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.core.audio.FilterType
import com.annieplayer.annie_music.kt.core.audio.GraphicEqFrequencies
import com.annieplayer.annie_music.kt.core.audio.GraphicEqPresets
import com.annieplayer.annie_music.kt.core.audio.MsebSlider
import com.annieplayer.annie_music.kt.core.audio.PeqBand
import com.annieplayer.annie_music.kt.core.audio.TuningEngine
import com.annieplayer.annie_music.kt.core.state.MoreSettings
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.AppSheet
import com.annieplayer.annie_music.kt.widgets.clickableNoRipple
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/** 调音面板：均衡器 / PEQ / MSEB，参数改动即时下发。 */
@Composable
fun TuningSheet(visible: Boolean, onClose: () -> Unit) {
    val engine by MoreSettings.tuningEngine.collectAsState()
    val gains by MoreSettings.graphicGains.collectAsState()
    val amp by MoreSettings.graphicAmp.collectAsState()
    val peq by MoreSettings.peqBands.collectAsState()
    val peqAmp by MoreSettings.peqAmp.collectAsState()
    val mseb by MoreSettings.mseb.collectAsState()
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.66f).dp

    AppSheet(visible = visible, title = "调音", onClose = onClose) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.sp16),
        ) {
            EngineTabs(engine, onPick = { MoreSettings.setTuningEngine(it) })
            Spacer(Modifier.height(AppSpacing.sp16))
            when (engine) {
                TuningEngine.graphic -> GraphicPanel(
                    gains = gains,
                    amp = amp,
                    onGain = { index, value -> MoreSettings.setGraphicBand(index, value) },
                    onAmp = { MoreSettings.setGraphicAmp(it) },
                )

                TuningEngine.parametric -> PeqPanel(
                    bands = peq,
                    amp = peqAmp,
                    onBand = { index, band -> MoreSettings.setPeqBand(index, band) },
                    onAmp = { MoreSettings.setPeqAmp(it) },
                )

                TuningEngine.mseb -> MsebPanel(mseb, onChange = { MoreSettings.setMseb(it) })
            }
            Spacer(Modifier.height(AppSpacing.sp24))
        }
    }
}

@Composable
private fun EngineTabs(selected: TuningEngine, onPick: (TuningEngine) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.whiteA05, RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (engine in TuningEngine.entries) {
            val active = engine == selected
            Text(
                text = engine.label,
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (active) AppColors.primaryA15 else AppColors.whiteA03,
                        RoundedCornerShape(10.dp),
                    )
                    .clickableNoRipple { onPick(engine) }
                    .padding(vertical = 10.dp),
                color = if (active) AppColors.primary else AppColors.textSecondary,
                fontSize = AppFontSize.size13,
                fontWeight = FontWeight.Medium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

// ── 均衡器 ──

@Composable
private fun GraphicPanel(
    gains: FloatArray,
    amp: Float,
    onGain: (Int, Float) -> Unit,
    onAmp: (Float) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (preset in GraphicEqPresets) {
            Chip(preset.label, selected = false) { MoreSettings.applyGraphicPreset(preset) }
        }
    }
    Spacer(Modifier.height(AppSpacing.sp16))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (index in GraphicEqFrequencies.indices) {
            VerticalBand(
                label = formatHz(GraphicEqFrequencies[index]),
                gain = gains.getOrElse(index) { 0f },
                modifier = Modifier.width(56.dp),
                onGain = { onGain(index, it) },
            )
        }
    }
    Spacer(Modifier.height(AppSpacing.sp12))
    SliderRow("Amp", amp, -12f, 12f, formatDb(amp), onAmp)
}

@Composable
private fun VerticalBand(label: String, gain: Float, modifier: Modifier, onGain: (Float) -> Unit) {
    var height by remember { mutableStateOf(1f) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = formatDb(gain),
            color = AppColors.textSecondary,
            fontSize = AppFontSize.sm,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { height = it.height.toFloat().coerceAtLeast(1f) }
                .pointerInput(height) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        fun applyGain(y: Float) {
                            val value = ((1f - y / height) * 2f - 1f) * 12f
                            onGain(value.coerceIn(-12f, 12f))
                        }
                        var vertical = false
                        var abandoned = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val dx = change.position.x - down.position.x
                            val dy = change.position.y - down.position.y
                            if (!vertical && !abandoned) {
                                // 横向滑动交给外层 horizontalScroll，不抢手势
                                if (abs(dx) > slop && abs(dx) > abs(dy)) {
                                    abandoned = true
                                    break
                                }
                                if (abs(dy) > slop) vertical = true
                            }
                            if (vertical) {
                                applyGain(change.position.y)
                                change.consume()
                            }
                        }
                        // 单击：没有产生有效拖动时，按下的位置直接设值
                        if (!vertical && !abandoned) applyGain(down.position.y)
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val trackW = 14.dp.toPx()
                val midY = size.height / 2f
                drawRoundRect(
                    color = AppColors.whiteA15,
                    topLeft = Offset(cx - trackW / 2f, 0f),
                    size = Size(trackW, size.height),
                    cornerRadius = CornerRadius(trackW / 2f),
                )
                val topY = (1f - ((gain / 12f + 1f) / 2f)) * size.height
                val from = minOf(midY, topY)
                drawRoundRect(
                    color = AppColors.primary,
                    topLeft = Offset(cx - trackW / 2f, from),
                    size = Size(trackW, abs(topY - midY)),
                    cornerRadius = CornerRadius(trackW / 2f),
                )
                drawCircle(AppColors.primary, 14.dp.toPx(), Offset(cx, topY))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            color = AppColors.textTertiary,
            fontSize = AppFontSize.sm,
            maxLines = 1,
        )
    }
}

// ── PEQ ──

@Composable
private fun PeqPanel(
    bands: List<PeqBand>,
    amp: Float,
    onBand: (Int, PeqBand) -> Unit,
    onAmp: (Float) -> Unit,
) {
    var selected by remember { mutableIntStateOf(0) }
    val band = bands.getOrNull(selected) ?: PeqBand()
    fun update(next: PeqBand) = onBand(selected, next)

    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (index in bands.indices) {
            Chip(
                text = "${index + 1}",
                selected = index == selected,
                onClick = { selected = index },
            )
        }
    }
    Spacer(Modifier.height(AppSpacing.sp12))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("第 ${selected + 1} 段", color = AppColors.textPrimary, fontSize = AppFontSize.size15)
        Spacer(Modifier.weight(1f))
        Text(
            text = "重置 PEQ",
            modifier = Modifier.clickableNoRipple { MoreSettings.resetPeq() },
            color = AppColors.primary,
            fontSize = AppFontSize.size13,
        )
    }
    Spacer(Modifier.height(AppSpacing.sp8))

    val freqPos = ((ln(band.freq.coerceIn(30f, 20000f) / 30f) / ln(20000f / 30f)) * 100f)
    SliderRow("频率", freqPos, 0f, 100f, formatHz(band.freq)) { pos ->
        val freq = (30f * (20000f / 30f).pow(pos / 100f)).coerceIn(30f, 20000f)
        update(band.copy(freq = freq))
    }

    val qPos = ((ln(band.q.coerceIn(0.2f, 20f) / 0.2f) / ln(100f)) * 100f)
    SliderRow("Q 值", qPos, 0f, 100f, String.format("%.2f", band.q)) { pos ->
        val q = (0.2f * 100f.pow(pos / 100f)).coerceIn(0.2f, 20f)
        update(band.copy(q = q))
    }

    SliderRow("增益", band.gainDb, -9f, 9f, formatDb(band.gainDb)) { update(band.copy(gainDb = it)) }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (type in FilterType.entries) {
            Chip(type.label, selected = band.type == type) { update(band.copy(type = type)) }
        }
    }
    Spacer(Modifier.height(AppSpacing.sp8))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("启用本段", color = AppColors.textPrimary, fontSize = AppFontSize.size13)
        Spacer(Modifier.weight(1f))
        Switch(
            checked = band.enabled,
            onCheckedChange = { update(band.copy(enabled = it)) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = AppColors.background,
                checkedTrackColor = AppColors.white,
                uncheckedThumbColor = AppColors.white54,
                uncheckedTrackColor = AppColors.whiteA06,
            ),
        )
    }

    Spacer(Modifier.height(AppSpacing.sp8))
    SliderRow("前级", amp, -9f, 2f, formatDb(amp), onAmp)
}

// ── MSEB ──

@Composable
private fun MsebPanel(values: com.annieplayer.annie_music.kt.core.audio.MsebValues, onChange: (com.annieplayer.annie_music.kt.core.audio.MsebValues) -> Unit) {
    for (slider in MsebSlider.entries) {
        val value = slider.get(values)
        val range = if (slider == MsebSlider.preamp) -45f..45f else -100f..100f
        SliderRow(
            label = slider.label,
            value = value.toFloat(),
            min = range.start,
            max = range.endInclusive,
            valueText = if (slider == MsebSlider.preamp) formatDb(value.toFloat()) else value.toString(),
            onChange = { onChange(slider.set(values, it.toInt())) },
        )
    }
}

// ── 通用控件 ──

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        modifier = Modifier
            .background(
                if (selected) AppColors.primaryA15 else AppColors.whiteA05,
                RoundedCornerShape(8.dp),
            )
            .clickableNoRipple(onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        color = if (selected) AppColors.primary else AppColors.textSecondary,
        fontSize = AppFontSize.sm,
    )
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    valueText: String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = AppColors.textSecondary, fontSize = AppFontSize.size13)
            Spacer(Modifier.weight(1f))
            Text(valueText, color = AppColors.textTertiary, fontSize = AppFontSize.sm)
        }
        Slider(
            value = value.coerceIn(min, max),
            onValueChange = onChange,
            valueRange = min..max,
        )
    }
}

private fun formatDb(value: Float): String =
    if (value > 0f) "+${String.format("%.1f", value)}" else String.format("%.1f", value)

private fun formatHz(value: Float): String =
    if (value >= 1000f) String.format("%.1fk", value / 1000f) else String.format("%.0f", value)
