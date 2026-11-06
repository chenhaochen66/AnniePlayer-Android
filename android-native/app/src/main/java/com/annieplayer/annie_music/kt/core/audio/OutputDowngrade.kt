package com.annieplayer.annie_music.kt.core.audio

/**
 * 降级原因 —— 「意图没生效」的唯一解释来源。
 *
 * 文案要求：**一句原因、一句怎么办**。不解释原理，也不写错误码 ——
 * 用户此刻只想知道两件事：为什么没走成、我要不要动手。
 */
enum class DowngradeReason(
    val label: String,
    val advice: String,
) {
    UsbNoPermission("未授予 USB 访问权限", "允许访问后自动恢复"),
    UsbNoDevice("未检测到 USB 音频设备", "接入后自动恢复"),
    UsbPlatformTooOld("系统版本过低", "需要 Android 8.0 及以上"),
    UsbOpenFailed("设备拒绝了这条 USB 流", "可改用 AAudio 独占"),
    UsbStreamLost("USB 流已中断", "已回落到系统通路"),
    AaudioDenied("本机不支持独占输出", "换设备后自动恢复"),
    AaudioStreamLost("独占流已中断", "已回落到系统通路"),
    BluetoothTakesOver("蓝牙接管了链路", "断开蓝牙后自动恢复"),
}

/**
 * 一次降级。**纯数据**，产生它的函数不读也不写 `outputPath`。
 *
 * 之所以强调这点：设置里的选择是「意愿」，降级是「事实」。任何一处把事实写回意愿，
 * 用户换回支持的设备后就得重新设置一遍。
 */
data class DowngradeNotice(
    val wanted: OutputPath,
    val actual: OutputPath,
    val reason: DowngradeReason,
    /** 具体项（例：`ExclusiveDenial.RateChanged` 的 label）；空 = 一句话已经够用 */
    val detail: String = "",
) {
    /** 同一 (意图, 生效, 原因, 具体项) 在一次降级期间只提示一次；恢复后清掉即可再次提示 */
    val dedupeKey: String get() = "${wanted.key}|${actual.key}|${reason.name}|$detail"

    /** 弹窗第一行：`USB 独占 → 系统 · AudioTrack` */
    val transition: String get() = "${wanted.label} → ${actual.label}"

    /** 原因行：`本机不支持独占输出（实际 48000 Hz）` */
    val reasonText: String get() = if (detail.isEmpty()) reason.label else "${reason.label}（$detail）"
}

/**
 * 意图与生效不一致时的提示；一致（或本来就选系统通路）返回 null。
 *
 * @param reason 拿不到具体原因时按意图兜底 —— 不编「未知错误」这种话
 * @param detail 具体项，见 [DowngradeNotice.detail]
 */
fun downgradeNoticeFor(
    wanted: OutputPath,
    actual: OutputPath,
    reason: DowngradeReason? = null,
    detail: String = "",
): DowngradeNotice? {
    if (wanted == OutputPath.systemMix || actual == wanted) return null
    val why = reason ?: when (wanted) {
        OutputPath.usbDirect -> DowngradeReason.UsbOpenFailed
        OutputPath.aaudioExclusive -> DowngradeReason.AaudioDenied
        OutputPath.systemMix -> return null
    }
    return DowngradeNotice(wanted, actual, why, detail)
}
