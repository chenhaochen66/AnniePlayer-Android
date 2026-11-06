package com.annieplayer.annie_music.kt.core.audio

/**
 * AAudio 独占探测的回包解析（**纯函数，可单测**）。
 *
 * native 侧只回一行文本（格式见 `native-aaudio-probe.cpp`），翻译成"能看懂的一句话"
 * 放这里 —— 面板里那几行字不该在 Composable 里拼。
 */
internal data class AaudioProbeResult(
    /** 是否真的拿到 `AAUDIO_SHARING_MODE_EXCLUSIVE`（**唯一判据**，请求不代表拿到） */
    val exclusive: Boolean,
    /** 流的实际采样率（可能被 HAL 改过；独占下通常等于请求值） */
    val sampleRate: Int,
    val depth: Int,
    val lowLatency: Boolean,
    /**
     * 非 null = 开流失败。**保留原始标记**，不要归一成一个数字：
     * `builder`（连 builder 都没建起来）/ `symbol`（dlopen 到了但缺符号）/
     * `nolib`（没有 libaaudio）/ 数字（`aaudio_result_t`，如 `-899`）。
     * 归一成 -1 会让"缺符号"和"HAL 拒绝"看起来一模一样 —— 实测就直接踩到了这个坑。
     */
    val errorTag: String? = null,
)

/**
 * 解析 native 回包；无法识别返回 null（不回退到"看起来像成功"—— 那是编）。
 *
 * ```
 * EXCLUSIVE/48000/32/LOW_LATENCY   → 独占可用
 * SHARED/48000/32/NONE             → 开出来了但没拿到独占
 * err:-899                         → 失败（aaudio_result_t）
 * err:builder / err:nolib / err:jni → 前置失败，错误码记 -1
 * ```
 */
internal fun parseAaudioProbe(raw: String?): AaudioProbeResult? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    if (text.startsWith("err:")) {
        val tag = text.removePrefix("err:").trim().ifEmpty { "unknown" }
        return AaudioProbeResult(
            exclusive = false,
            sampleRate = 0,
            depth = 0,
            lowLatency = false,
            errorTag = tag,
        )
    }
    val parts = text.split('/')
    if (parts.size < 4) return null
    val rate = parts[1].toIntOrNull() ?: return null
    val depth = parts[2].toIntOrNull() ?: return null
    return AaudioProbeResult(
        exclusive = parts[0] == "EXCLUSIVE",
        sampleRate = rate,
        depth = depth,
        lowLatency = parts[3] == "LOW_LATENCY",
    )
}

/**
 * 面板里那一行值。
 *
 * 三态要分清，不能都写成"不支持"：**独占可用** / **只拿到共享**（HAL 忽略了请求，
 * 说明这条路仍会过 mixer）/ **开流失败**（带错误码，便于对照 AAudio 的错误表）。
 */
internal fun aaudioProbeLabel(raw: String?): String {
    val result = parseAaudioProbe(raw) ?: return "未知"
    result.errorTag?.let { return "不可用（err:$it）" }
    if (!result.exclusive) return "仅共享（HAL 未给独占）"
    val deep = depthText(result.depth, float = result.depth >= 32)
    val at = khzText(result.sampleRate)
    val spec = listOf(deep, at).filter { it.isNotEmpty() }.joinToString(" / ")
    return if (result.lowLatency) "独占可用 · $spec · 低延迟" else "独占可用 · $spec"
}
