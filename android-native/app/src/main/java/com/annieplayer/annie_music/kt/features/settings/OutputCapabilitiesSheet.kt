package com.annieplayer.annie_music.kt.features.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.annieplayer.annie_music.kt.core.audio.AudioDirectProbe
import com.annieplayer.annie_music.kt.core.audio.DsdFormatProbe
import com.annieplayer.annie_music.kt.core.audio.MixerAttributesProbe
import com.annieplayer.annie_music.kt.core.audio.UsbAudioDirect
import com.annieplayer.annie_music.kt.core.audio.dsp.NativeUacStream
import com.annieplayer.annie_music.kt.widgets.AppSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「输出能力（实测）」—— 本机**真的**能做到什么。
 *
 * 全部数据来自 [AudioDirectProbe.report]：设备声明率、`AudioFormat` 可建档、
 * 真建轨实测（>192k）、AAudio 独占矩阵、最近一次播放失败。
 *
 * ## 曾经这里是「本机直通能力」
 *
 * 那一版大半篇幅在列"哪种编码能建 offload 轨"。**offload 直通功能已整条删除**，
 * 于是那批行（编码×采样率、策略声明、API 不可达、兜底候选）一起没了 ——
 * 面板只保留仍然有意义、且**能验证**的那几行。
 *
 * 定位是"状态快照"，事件流在「日志诊断」页。
 */
@Composable
fun OutputCapabilitiesSheet(
    visible: Boolean,
    onClose: () -> Unit,
) {
    AppSheet(
        visible = visible,
        title = "输出能力（实测）",
        onClose = onClose,
        // 从设置页打开的纯信息面板，底下没有迷你播放条 → 不预留那 100dp
        reserveBottomInset = false,
    ) {
        // 打开时取快照；「重新探测」会重跑并刷新
        var reload by remember { mutableIntStateOf(0) }
        var probing by remember { mutableStateOf(false) }
        val rows = remember(visible, reload) {
            if (visible) AudioDirectProbe.report() else emptyList()
        }
        val screenH = LocalConfiguration.current.screenHeightDp.dp
        val cap = (screenH * 0.6f - 130.dp).coerceAtLeast(200.dp)
        val scroll = rememberScrollState()
        val context = LocalContext.current
        val usbText = produceState(initialValue = "读取中…", visible, reload) {
            value = if (visible) withContext(Dispatchers.IO) { usbDirectReport(context) } else ""
        }
        val dsdText = produceState(initialValue = "读取中…", visible, reload) {
            value = if (visible) withContext(Dispatchers.IO) { DsdFormatProbe.report() } else ""
        }
        val mixerText = produceState(initialValue = "读取中…", visible, reload) {
            value = if (visible) withContext(Dispatchers.IO) {
                MixerAttributesProbe.report(context)
            } else {
                ""
            }
        }
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            ) {
                Text(
                    text = if (probing) "探测中…" else "重新探测",
                    color = Color.White.copy(alpha = if (probing) 0.45f else 0.8f),
                    fontSize = 13.sp,
                    modifier = Modifier.clickable(enabled = !probing) {
                        probing = true
                        // direct 端口的占用状态会变，DSD 建轨结论必须跟着重跑
                        DsdFormatProbe.invalidate()
                        AudioDirectProbe.reprobeAsync {
                            probing = false
                            reload++
                        }
                    },
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "刷新",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 13.sp,
                    modifier = Modifier.clickable { reload++ },
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = cap)
                    // 点击事件就地吃掉：否则会穿透到 scrim，一碰就把面板关掉
                    .pointerInput(Unit) { detectTapGestures { } }
                    .verticalScroll(scroll)
                    .padding(start = 16.dp, end = 24.dp, bottom = 12.dp),
            ) {
                if (rows.isEmpty()) {
                    Text(
                        text = "还没有探测结果",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                rows.forEach { (label, value) ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(
                            text = label,
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 12.sp,
                        )
                        Text(
                            text = value,
                            color = Color.White.copy(alpha = 0.92f),
                            fontSize = 14.sp,
                        )
                    }
                }
                Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp)) {
                    Text(
                        text = "USB 独占",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 12.sp,
                    )
                    Text(
                        text = usbText.value,
                        color = Color.White.copy(alpha = 0.92f),
                        fontSize = 13.sp,
                    )
                }
                Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp)) {
                    Text(
                        text = "DSD 编码建轨",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 12.sp,
                    )
                    Text(
                        text = dsdText.value,
                        color = Color.White.copy(alpha = 0.92f),
                        fontSize = 13.sp,
                    )
                }
                Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp)) {
                    Text(
                        text = "Bit Perfect 混音",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 12.sp,
                    )
                    Text(
                        text = mixerText.value,
                        color = Color.White.copy(alpha = 0.92f),
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

/** USB 独占现状：设备、授权、描述符里的 alt / 时钟源 / 设备自报的采样率区间。 */
private fun usbDirectReport(context: Context): String {
    if (!UsbAudioDirect.platformReady()) return "系统版本过低（需要 Android 8.0+）"
    if (!NativeUacStream.available()) return "libannieuac 未加载"
    val devices = UsbAudioDirect.audioDevices(context)
    if (devices.isEmpty()) return "未检测到 USB 音频设备"
    return devices.joinToString("\n") { device ->
        val title = listOfNotNull(device.manufacturerName, device.productName)
            .joinToString(" ")
            .ifEmpty { device.deviceName }
        val body = if (UsbAudioDirect.hasPermission(context, device)) {
            UsbAudioDirect.describe(context, device) ?: "描述符读取失败"
        } else {
            "未授权（播放时申请）"
        }
        "$title\n$body"
    }
}
