package com.annieplayer.annie_music.kt.widgets

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.annieplayer.annie_music.kt.core.audio.DSD_DEFAULT_PCM_RATE
import com.annieplayer.annie_music.kt.core.audio.DSD_PCM_RATES
import com.annieplayer.annie_music.kt.core.audio.DSD_RATE_FOLLOW_SYSTEM
import com.annieplayer.annie_music.kt.core.audio.DsdLevel
import com.annieplayer.annie_music.kt.core.audio.DsdOutputMode
import com.annieplayer.annie_music.kt.core.audio.dsdModeAvailability
import com.annieplayer.annie_music.kt.core.audio.khzText
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing

/**
 * 「DSD 输出」整页面板。
 *
 * 为什么单独开一页而不是塞进更多设置：这三件事（模式 / 目标率 / 为什么 DoP 是灰的）
 * 是一条**独立的轴**，和「透明 vs 调音」「输出方式」正交。塞进设置列表会和
 * 「重采样目标」长得一模一样，反而更容易被误读成同一件事 —— 而这正是要消除的误解。
 *
 * 页内三段，顺序就是判读顺序：
 *
 * 1. **DSD 模式** —— 现在只有 `PCM` 可选；`DoP` / `Native` 灰显并写明缺什么
 *    （判定在 `dsdModeAvailability`，本页只负责呈现，不做判断）；
 * 2. **DSD 转 PCM** —— 按 DSD 档位分别选目标率，候选只有 44.1 家族，
 *    超出平台能力的档位灰显；
 * 3. **说明** —— 只读，把"为什么"和"当前实际走了什么"摆出来。
 */
@Composable
fun DsdOutputSheet(
    visible: Boolean,
    mode: DsdOutputMode,
    rates: Map<DsdLevel, Int>,
    /** 平台能建出的最高 PCM 率（`dsdMaxPcmRate()`），用来灰掉 352.8k */
    maxPcmRate: Int,
    /** 当前在播曲目的 DSD 档位；`null` = 不是 DSD（本页设置不参与） */
    currentLevel: DsdLevel?,
    /**
     * 当前档位「意图 → 实际」的差异说明（`dsdRateNotice().text`）；`null` = 没有差异。
     *
     * 只作用于 [currentLevel] 那一行：其它档位此刻没有"实际"，不该显示。
     */
    currentRateNotice: String? = null,
    /**
     * 当前通路能否**逐字节保真**（USB 直连成立）。DoP 与 Native 的硬前提：
     * 过一次 mixer，`STREAM_MUSIC` 的音量就是一次 float 乘法，DoP 的帧标记就在被乘的位上。
     */
    exclusiveReady: Boolean = false,
    /** DAC 是否暴露了 raw DSD 通道。Native 的硬前提，未知时那一项灰着 */
    usbDacReady: Boolean = false,
    /** 当前是否蓝牙输出：蓝牙下 DSD 档会让位给 codec 率 */
    isBluetooth: Boolean = false,
    /** 系统 mixer 实际会用的率；0 = 未知。给「跟随系统」那一项显示目标用 */
    systemRate: Int = 0,
    /** 当前链路实况（宿主用 `chainText` 算好）；空 = 还没起播 */
    currentChain: String,
    onPickMode: (DsdOutputMode) -> Unit,
    onPickRate: (DsdLevel, Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 档位选择走通用的 `DropdownSelectRow`（和设置页同一套）：
    // 固定宽值的 chip + 贴值下沿的浮层 + 页面级展开状态 + 返回先收下拉。
    val dropdown = rememberDropdownController()
    BackHandler(enabled = visible) {
        // 有档位浮层开着时只收浮层，不关这一页
        if (!dropdown.handleBack()) onClose()
    }

    val scrimAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.easeInOutQuad),
        label = "dsdScrim",
    )
    val slide by animateFloatAsState(
        targetValue = if (visible) 0f else 1f,
        animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.easeInOutQuad),
        label = "dsdSlide",
    )
    // 完全不可见时整段不组合：不占位、不吃命中测试（Compose 里 `enabled=false` 仍然挡点击）
    if (!visible && scrimAlpha == 0f) return

    val density = LocalDensity.current

    Box(modifier = modifier.fillMaxSize()) {
        val scrimInteraction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = scrimAlpha }
                .clickable(
                    enabled = visible,
                    interactionSource = scrimInteraction,
                    indication = null,
                    onClick = {},
                )
                .background(AppColors.scrim50),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = with(density) { (slide * 900.dp.toPx()) } }
                .background(AppColors.background)
                .clickableNoRipple { }
                .systemBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = AppSpacing.sp24,
                        top = AppSpacing.sp40,
                        end = AppSpacing.sp24,
                        bottom = AppSpacing.sp8,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SheetBackButton(onClose = onClose)
                Spacer(modifier = Modifier.width(AppSpacing.sp8))
                Text(
                    text = "DSD 输出",
                    modifier = Modifier.weight(1f),
                    color = AppColors.textPrimary,
                    fontSize = AppFontSize.size20,
                    fontWeight = FontWeight.Bold,
                )
            }

            Text(
                text = "DSD 的采样率必须落在 DSD 基频的 2 的幂分频上，才能整数抽取；" +
                    "所以它有一条独立的目标率，与 PCM 曲目的「重采样目标」互不干涉",
                color = AppColors.textTertiary,
                fontSize = AppFontSize.sm,
                lineHeight = 18.sp,
                modifier = Modifier.padding(
                    start = AppSpacing.sp24,
                    end = AppSpacing.sp24,
                    bottom = AppSpacing.sp12,
                ),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AppSpacing.sp24),
            ) {
                // ── 模式：一行分段控件 ──
                //
                // 以前是三个 68dp 的卡片，两个还灰着 —— 光这一块吃掉 200dp，
                // 而它只是三选一。改成一行分段后高度降到 48dp，
                // 少掉的那 150dp 正好留给下面真正要看的档位和说明。
                DsdSectionTitle("DSD 模式")
                DsdModeTabs(
                    mode = mode,
                    onPick = onPickMode,
                    exclusiveReady = exclusiveReady,
                    usbDacReady = usbDacReady,
                )
                // 灰项的原因塞不进行内（一行只有一两个词），挪到这里一行小字 + 「说明」区详述
                val blocked = DsdOutputMode.entries
                    .filter { !dsdModeAvailability(it, exclusiveReady, usbDacReady).enabled }
                if (blocked.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(AppSpacing.sp8))
                    DsdHint(
                        blocked.joinToString(" / ") { it.label } + " 现在选不了，会落回 PCM：" +
                            blocked.joinToString("；") {
                                dsdModeAvailability(it, exclusiveReady, usbDacReady).reason
                            },
                    )
                }

                Spacer(modifier = Modifier.height(AppSpacing.sp12))
                DsdSectionTitle("DSD 转 PCM")
                // **这一段始终可见**：它不只是 PCM 模式的输出率，更是 DoP / Native
                // 走不成时的落点。藏起来就会变成「选了 Native，却无从决定降级到多少」——
                // 而 DSD512 这类设备接不住的档位恰恰要靠它兜底。
                if (mode != DsdOutputMode.pcm) {
                    DsdHint("${mode.label} 走不成时，会按下面的档位转成 PCM")
                }
                for (level in DsdLevel.entries) {
                        // 兜底只能是**默认值**，不能是 `DSD_PCM_RATES.last()`（那是 352.8k，
                        // 一个永远灰着、任何设备都建不出来的档）。
                        val current = rates[level] ?: DSD_DEFAULT_PCM_RATE
                        DropdownSelectRow(
                            selectKey = level.name,
                            label = level.label,
                            value = if (current == DSD_RATE_FOLLOW_SYSTEM) {
                                "跟随系统"
                            } else {
                                khzText(current)
                            },
                            subtitle = if (level == currentLevel) currentRateNotice else null,
                            options = buildList {
                                for (rate in DSD_PCM_RATES) {
                                    add(
                                        DropdownOption(
                                            label = khzText(rate),
                                            selected = current == rate,
                                            // 超平台能力的档列出来但灰掉，说明"做不到"
                                            enabled = rate <= maxPcmRate,
                                            onSelect = { onPickRate(level, rate) },
                                        ),
                                    )
                                }
                                // 不是采样率，是**抽取策略** —— 放最后一个
                                add(
                                    DropdownOption(
                                        label = "自动（跟随系统）",
                                        selected = current == DSD_RATE_FOLLOW_SYSTEM,
                                        onSelect = { onPickRate(level, DSD_RATE_FOLLOW_SYSTEM) },
                                    ),
                                )
                            },
                        controller = dropdown,
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.sp8))
                }

                Spacer(modifier = Modifier.height(AppSpacing.sp12))
                DsdSectionTitle("说明")
                // 顺序即优先级：此刻的结论 → 此刻的链路 → 三条不变的事实
                DsdCurrentHint(currentLevel = currentLevel, isBluetooth = isBluetooth, rates = rates)
                Spacer(modifier = Modifier.height(AppSpacing.sp8))
                DsdHint("当前链路：${currentChain.ifEmpty { "起播后显示" }}")
                Spacer(modifier = Modifier.height(AppSpacing.sp8))
                // 档位是意图，不会因设备变化被改掉 —— 不说清用户会以为"拔一次线设置就丢了"
                DsdHint("档位不会因换设备丢失，条件恢复后自动生效。")
                Spacer(modifier = Modifier.height(AppSpacing.sp8))
                // DoP / Native 为什么不可选：`DsdModeTabs` 只写「未实现」，机制由这里给
                DsdHint("DoP 需逐字节保真，系统混音会改写帧标记，只能走独占。")
                Spacer(modifier = Modifier.height(AppSpacing.sp8))
                DsdHint("Native 需独占且 DAC 支持 Native DSD；系统 API 建不出 UAC2 这条流。")
                // 底部留白避开迷你播放条
                Spacer(modifier = Modifier.height(AppSpacing.sheetBottomInset))
            }
        }
    }
}

@Composable
private fun DsdSectionTitle(text: String) {
    Text(
        text = text,
        color = AppColors.textSecondary,
        fontSize = AppFontSize.sm,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp,
        modifier = Modifier.padding(bottom = AppSpacing.sp8),
    )
}

@Composable
private fun DsdHint(text: String) {
    Text(
        text = text,
        color = AppColors.textTertiary,
        fontSize = AppFontSize.sm,
        lineHeight = 18.sp,
    )
}

/**
 * 「本页设置此刻是否生效」的一行结论。
 *
 * 三种情况说清楚，省得用户在蓝牙上把 DSD 档拉到 176.4 然后怀疑设置坏了：
 * 1. 当前不是 DSD → 本页不参与；
 * 2. 是 DSD 但是蓝牙输出 → 末端 codec 只吃 48 家族，DSD 档让位给 codec 率；
 * 3. 是 DSD 且非蓝牙 → 按所选档位抽成 PCM。
 */
@Composable
private fun DsdCurrentHint(
    currentLevel: DsdLevel?,
    isBluetooth: Boolean,
    rates: Map<DsdLevel, Int>,
) {
    val text = when {
        currentLevel == null -> "当前曲目不是 DSD，本页不参与。"
        isBluetooth -> "当前 ${currentLevel.label}，蓝牙输出：让位给 codec 率。"
        else -> {
            val rate = rates[currentLevel] ?: DSD_DEFAULT_PCM_RATE
            // 「整数抽取」是事实：目标率只允许 44.1 家族（基频的 2 的幂分频），
            // 所以这一步永远不是任意比重采样。滤波由我方 r8brain 做，不是 swresample。
            "当前 ${currentLevel.label} → ${khzText(rate)} PCM（整数抽取，本应用完成）"
        }
    }
    Text(
        text = text,
        color = if (currentLevel == null) AppColors.textTertiary else AppColors.textSecondary,
        fontSize = AppFontSize.sm,
        lineHeight = 18.sp,
    )
}

@Composable
private fun DsdModeTabs(
    mode: DsdOutputMode,
    onPick: (DsdOutputMode) -> Unit,
    exclusiveReady: Boolean,
    usbDacReady: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.whiteA05, RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (m in DsdOutputMode.entries) {
            val availability = dsdModeAvailability(m, exclusiveReady, usbDacReady)
            val active = m == mode
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .background(
                        if (active) AppColors.primaryA15 else AppColors.whiteA03,
                        RoundedCornerShape(10.dp),
                    )
                    // **可点**：模式是"意愿"，能不能生效由实际链路决定（降级是事实，
                    // 不写回设置）。以前 `enabled=false` 时直接吞掉点击，用户连选都选不了，
                    // 也就永远看不到"为什么没生效"——那正好把最需要解释的情况藏起来了。
                    .clickableNoRipple { onPick(m) },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = m.label,
                    color = when {
                        active -> AppColors.primary
                        availability.enabled -> AppColors.textSecondary
                        else -> AppColors.textDisabled
                    },
                    fontSize = AppFontSize.size13,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                )
                // 固定占一行高度，这样三个格子高度一致、不会因为一行字跳动。
                // **不写"未实现"**：两条通路都做完了，能不能生效取决于通路与设备，
                // 那由下面那行提示逐条说明，格子里只标"要额外条件"。
                Text(
                    text = if (availability.enabled) "" else "需 USB 直连",
                    color = AppColors.textDisabled,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

/** 自绘对勾：全站图标都是 Canvas 家族，不引 material 图标 */
@Composable
private fun DsdCheckGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.18f, h * 0.52f)
            lineTo(w * 0.42f, h * 0.76f)
            lineTo(w * 0.84f, h * 0.26f)
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = w * 0.14f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
