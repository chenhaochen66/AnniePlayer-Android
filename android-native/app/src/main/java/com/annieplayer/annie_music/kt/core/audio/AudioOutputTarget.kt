package com.annieplayer.annie_music.kt.core.audio

/**
 * 目标率没能按理想值落地的原因（**不再静默回退**，必须让界面能说清）。
 *
 * 采样率决策已经全部收敛到 [resolveOutputPlan]，这里只留"落点可能不如预期"这一种语义，
 * 供播放页胶囊/设置页标注使用。
 *
 * ⚠️ **原来的 `platformRejected`（平台直接拒绝用户选的档位）已删除** ——
 * 「自选采样率」整块移除后，目标率恒为「跟随设备」算出来的 `autoRate`，
 * 那是本机自己的值，不存在"用户要求了但平台不认"的情形。
 */
enum class RateFallback {
    /** 正常生效 */
    none,

    /** 平台建得出来，但输出设备没声明这一档 → 最终仍可能被 mixer 拉回 primary */
    deviceNotDeclared,
}
