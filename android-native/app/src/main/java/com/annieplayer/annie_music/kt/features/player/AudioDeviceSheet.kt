package com.annieplayer.annie_music.kt.features.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.core.audio.AudioOutputController
import com.annieplayer.annie_music.kt.core.audio.AudioRoute
import com.annieplayer.annie_music.kt.core.audio.BluetoothCodec
import com.annieplayer.annie_music.kt.core.audio.BluetoothCodecInfo
import com.annieplayer.annie_music.kt.core.audio.specText
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.AppSheet
import com.annieplayer.annie_music.kt.widgets.clickableNoRipple

/**
 * 播放设备选择面板（播放页右上角入口）。
 *
 * 只做「列出现有输出设备 + 当前选中」；真正改路由由宿主调用
 * `AudioOutputController.selectOutputDevice` + `applyOutputDeviceToPlayer`。
 */
@Composable
fun AudioDeviceSheet(
    visible: Boolean,
    devices: List<AudioOutputController.DeviceInfo>,
    currentDeviceId: Int,
    onSelect: (AudioOutputController.DeviceInfo) -> Unit,
    onClose: () -> Unit,
) {
    val btCodec by BluetoothCodec.info.collectAsState()
    LaunchedEffect(visible) {
        if (visible) BluetoothCodec.refresh()
    }

    AppSheet(visible = visible, title = "播放设备", onClose = onClose) {
        if (devices.isEmpty()) {
            Text(
                text = "暂未检测到可用的输出设备",
                modifier = Modifier.padding(horizontal = AppSpacing.sp16, vertical = AppSpacing.sp16),
                color = AppColors.textTertiary,
                fontSize = AppFontSize.base,
            )
        } else {
            for (device in devices) {
                val route = routeOf(device.type)
                AudioDeviceRow(
                    device = device,
                    selected = device.id == currentDeviceId,
                    btCodec = btCodec.takeIf { route == AudioRoute.bluetooth },
                    onTap = { onSelect(device) },
                )
            }
        }
        Spacer(modifier = Modifier.height(AppSpacing.sp8))
        Text(
            text = "USB / 有线 / 蓝牙设备需要先连接；切换后立即生效",
            modifier = Modifier.padding(horizontal = AppSpacing.sp16),
            color = AppColors.textTertiary,
            fontSize = AppFontSize.sm,
        )
        Spacer(modifier = Modifier.height(AppSpacing.sp16))
    }
}

@Composable
private fun AudioDeviceRow(
    device: AudioOutputController.DeviceInfo,
    selected: Boolean,
    btCodec: BluetoothCodecInfo?,
    onTap: () -> Unit,
) {
    val route = routeOf(device.type)
    val tint = if (selected) AppColors.primary else AppColors.white
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickableNoRipple(onTap)
            .padding(horizontal = AppSpacing.sp16, vertical = AppSpacing.sp14),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    color = if (selected) AppColors.primary.copy(alpha = 0.16f)
                    else Color.White.copy(alpha = 0.06f),
                    shape = RoundedCornerShape(AppSpacing.sp12),
                ),
            contentAlignment = Alignment.Center,
        ) {
            DeviceTypeGlyph(route = route, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp12))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.name.ifEmpty { routeLabel(route) },
                color = if (selected) AppColors.primary else AppColors.white,
                fontSize = AppFontSize.base,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp2))
            Text(
                text = btCodec?.let { bluetoothSubtitle(it) } ?: subtitleOf(device, route),
                color = AppColors.textTertiary,
                fontSize = AppFontSize.sm,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Spacer(modifier = Modifier.width(AppSpacing.sp8))
            CheckGlyph(tint = AppColors.primary, modifier = Modifier.size(18.dp))
        }
    }
}

/** 蓝牙行优先显示系统给出的**实际** codec / 采样率 / 位深 / 码率；offload 机型可能拿不到有效值 */
private fun bluetoothSubtitle(codec: BluetoothCodecInfo): String? {
    // ColorOS 等 offload 模式下 getCodecStatus 返回 NONE（sampleRate=0 / codec 未知），
    // 直接拼出来就是「未知 · 0.0 kHz / 2 bit」这种垃圾，退回设备能力列表
    if (codec.sampleRate <= 0 || codec.codecName.isEmpty() || codec.codecName.startsWith("未知")) return null
    return buildList {
        add("蓝牙")
        add(codec.codecName)
        // 与播放页同一套配对规则：位深在前、采样率在后、单位 `Bit` / `kHz`
        val spec = specText(codec.bitsPerSample, codec.sampleRate)
        if (spec.isNotEmpty()) add(spec)
        if (codec.bitrate > 0) add("${codec.bitrate / 1000} kbps")
    }.joinToString(" · ")
}

private fun subtitleOf(device: AudioOutputController.DeviceInfo, route: AudioRoute): String {
    val rates = device.sampleRates
        .distinct()
        .sorted()
        .take(4)
        .joinToString(" / ") { if (it % 1000 == 0) "${it / 1000}" else "%.1f".format(it / 1000f) }
    val channels = if (device.channelCounts.isNotEmpty()) "${device.channelCounts.max()} ch" else ""
    return listOf(routeLabel(route), channels, if (rates.isEmpty()) "" else "$rates kHz")
        .filter { it.isNotEmpty() }
        .joinToString(" · ")
}

private fun routeOf(type: Int): AudioRoute = when (type) {
    android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
    android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> AudioRoute.usb
    android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
    android.media.AudioDeviceInfo.TYPE_LINE_ANALOG,
    android.media.AudioDeviceInfo.TYPE_LINE_DIGITAL -> AudioRoute.wired
    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
    android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER -> AudioRoute.bluetooth
    android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRoute.speaker
    else -> AudioRoute.other
}

private fun routeLabel(route: AudioRoute): String = when (route) {
    AudioRoute.usb -> "USB"
    AudioRoute.wired -> "有线"
    AudioRoute.bluetooth -> "蓝牙"
    AudioRoute.speaker -> "扬声器"
    AudioRoute.other -> "其它"
}

/** 设备图标：USB 画插头、蓝牙画符号、其余画耳机 */
@Composable
internal fun DeviceTypeGlyph(route: AudioRoute, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.10f
        when (route) {
            AudioRoute.usb -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.30f, h * 0.34f),
                    size = Size(w * 0.40f, h * 0.32f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.06f),
                    style = Stroke(width = stroke),
                )
                drawLine(tint, Offset(w * 0.50f, h * 0.66f), Offset(w * 0.50f, h * 0.86f), stroke, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.38f, h * 0.86f), Offset(w * 0.62f, h * 0.86f), stroke, StrokeCap.Round)
            }
            AudioRoute.bluetooth -> {
                val p = Path().apply {
                    moveTo(w * 0.42f, h * 0.18f)
                    lineTo(w * 0.42f, h * 0.82f)
                    lineTo(w * 0.72f, h * 0.64f)
                    lineTo(w * 0.30f, h * 0.36f)
                    moveTo(w * 0.42f, h * 0.18f)
                    lineTo(w * 0.72f, h * 0.36f)
                    lineTo(w * 0.30f, h * 0.64f)
                }
                drawPath(p, tint, style = Stroke(width = stroke, cap = StrokeCap.Round))
            }
            else -> {
                // 耳机：上弧 + 两个耳罩
                drawArc(
                    color = tint,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(w * 0.18f, h * 0.22f),
                    size = Size(w * 0.64f, h * 0.52f),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.14f, h * 0.52f),
                    size = Size(w * 0.16f, h * 0.28f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.06f),
                )
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.70f, h * 0.52f),
                    size = Size(w * 0.16f, h * 0.28f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.06f),
                )
            }
        }
    }
}

/** 选中勾 */
@Composable
private fun CheckGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val p = Path().apply {
            moveTo(w * 0.18f, h * 0.52f)
            lineTo(w * 0.42f, h * 0.76f)
            lineTo(w * 0.84f, h * 0.26f)
        }
        drawPath(p, tint, style = Stroke(width = w * 0.12f, cap = StrokeCap.Round))
    }
}
