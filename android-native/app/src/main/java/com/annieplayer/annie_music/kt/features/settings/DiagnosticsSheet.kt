package com.annieplayer.annie_music.kt.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.annieplayer.annie_music.kt.core.audio.dsp.DspSelfTest
import com.annieplayer.annie_music.kt.core.diagnostics.RuntimeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.annieplayer.annie_music.kt.widgets.AppSheet
import com.annieplayer.annie_music.kt.widgets.ThinScrollbar

/**
 * 「日志诊断」—— 通用的运行时事件列表，**不限于音频**。
 *
 * 数据全部来自 [RuntimeLog]：任何模块调 `RuntimeLog.record(tag, message)` 就往里写，
 * 这里只负责按时间倒序渲染。想加一类别的事件，不需要动这个文件 ——
 * 换一个 `tag` 记就行；需要分类展示时再按 `tag` 分组。
 *
 * 和「本机直通能力」的分工：那页是**状态快照**（现在是什么样），这页是**事件流**（刚才发生过什么）。
 */
@Composable
fun DiagnosticsSheet(
    visible: Boolean,
    onClose: () -> Unit,
) {
    AppSheet(
        visible = visible,
        title = "日志诊断",
        onClose = onClose,
        // 从设置页打开的纯信息面板，底下没有迷你播放条 → 不预留那 100dp
        reserveBottomInset = false,
    ) {
        // 打开时取快照；运行中新事件不会自己冒出来，靠「刷新」重读
        var reload by remember { mutableIntStateOf(0) }
        val entries = remember(visible, reload) {
            if (visible) RuntimeLog.snapshot() else emptyList()
        }
        val screenH = LocalConfiguration.current.screenHeightDp.dp
        val cap = (screenH * 0.6f - 130.dp).coerceAtLeast(200.dp)
        val scroll = rememberScrollState()
        Column(Modifier.fillMaxWidth()) {
            // 顶栏：条数 + 刷新 + 清空
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "共 ${entries.size} 条",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "刷新",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 13.sp,
                    modifier = Modifier.clickable { reload++ },
                )
                Spacer(Modifier.width(20.dp))
                Text(
                    text = "清空",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 13.sp,
                    modifier = Modifier.clickable {
                        RuntimeLog.clear()
                        reload++
                    },
                )
            }
            // ── 数字链路自检 ──
            // 拿**设备上真实的 .so** 跑一遍重采样再量：通带增益 / THD+N / 混叠抑制。
            // 只覆盖数字段；模拟端（DAC、耳放）这里量不到，别把它当整机实测。
            var selfTest by remember(visible) { mutableStateOf<List<DspSelfTest.Result>?>(null) }
            var running by remember(visible) { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            LaunchedEffect(visible) {
                // 每次打开重置，避免上一次的结论被当成这次的
                if (visible) { selfTest = null; running = false }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            ) {
                Text(
                    text = if (running) "自检中…" else "数字链路自检",
                    color = if (running) Color.White.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.9f),
                    fontSize = 13.sp,
                    modifier = Modifier.clickable(enabled = !running) {
                        running = true
                        scope.launch {
                            selfTest = withContext(Dispatchers.IO) { DspSelfTest.runAll() }
                            running = false
                        }
                    },
                )
                selfTest?.let { results ->
                    if (results.isEmpty()) {
                        Text(
                            text = "native 库不可用，跳过（这不是「通过」）",
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    } else {
                        results.forEach { r ->
                            val mark = if (r.ok) "✓" else "✗"
                            val detail = if (r.ok) {
                                "增益 %+.3f dB · THD+N %.1f dB · 峰值 %.3f".format(
                                    r.gainDb, r.thdPlusNDb, r.peak,
                                )
                            } else {
                                r.reason
                            }
                            Text(
                                text = "$mark ${r.label} · $detail",
                                color = if (r.ok) Color(0xFF8BD450) else Color(0xFFFF7A7A),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // 点击事件就地吃掉：否则会穿透到 scrim，一碰就把面板关掉
                    .pointerInput(Unit) { detectTapGestures { } }
                    .heightIn(max = cap),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scroll)
                        // 右侧多留一点，别让滑条压住正文
                        .padding(start = 16.dp, end = 24.dp),
                ) {
                    if (entries.isEmpty()) {
                        Text(
                            text = "暂无日志",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            entries.forEach { entry ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    // 自设备启动起的时刻：只有先后关系有意义，不显示墙钟
                                    Text(
                                        text = formatClock(entry.atMs),
                                        color = Color.White.copy(alpha = 0.4f),
                                        fontSize = 12.sp,
                                        modifier = Modifier.width(78.dp),
                                    )
                                    Text(
                                        text = entry.tag,
                                        color = Color.White.copy(alpha = 0.72f),
                                        fontSize = 12.sp,
                                        modifier = Modifier.width(64.dp),
                                    )
                                    Text(
                                        text = entry.message,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
                ThinScrollbar(
                    scroll = scroll,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .padding(start = 2.dp, end = 6.dp),
                )
            }
            // ── 固定在面板底部的说明（独立一行，不参与滚动）──
            Text(
                text = "日志来自应用内的环形缓冲（最近 ${RuntimeLog.CAPACITY} 条），" +
                    "不经过 logcat —— 本 ROM 会按进程配额丢弃应用日志。\n" +
                    "任何模块都可以调 RuntimeLog.record(tag, message) 往里写；" +
                    "这里只按时间倒序展示，不按级别过滤。",
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
            )
        }
    }
}

/** `elapsedRealtime` → `mm:ss.mmm`（小时不显示，够看先后顺序） */
private fun formatClock(ms: Long): String {
    val totalSec = ms / 1000
    val minute = (totalSec / 60) % 100
    val second = totalSec % 60
    val milli = ms % 1000
    return "%02d:%02d.%03d".format(minute, second, milli)
}
