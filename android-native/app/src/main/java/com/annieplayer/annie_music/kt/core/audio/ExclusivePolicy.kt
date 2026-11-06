package com.annieplayer.annie_music.kt.core.audio

/**
 * 独占为什么没成立 / 为什么不该试 —— **唯一的一套原因字面量**。
 *
 * 以前这些原因是散着写的：`configure` 里一串 `when` 拼字符串、`handleBuffer` 里两句
 * 中文、面板另有一段文案、`failedRates` 又是第三份状态。同一个概念要跨四个文件拼起来
 * 才能回答"这首为什么降级"。现在收敛成枚举：日志、面板、单测共用同一套词。
 */
enum class ExclusiveDenial(val label: String) {
    /**
     * 用户没选「AAudio 独占」通路，或蓝牙把通路回退成了系统 mixer —— 这种时候根本不该试。
     *
     * 以前这一条叫 `NotTransparent("非透明模式")`：旧模型下"调音"是唯一能挡住独占的东西。
     * 新模型里**调音与独占可以并存**（关掉 Bit Perfect = 允许调音，数据仍走独占流），
     * 挡住独占的只剩"通路没选独占"。`label` 也跟着改 —— 界面上不该再出现"透明模式"。
     */
    PathNotExclusive("当前通路不走独占"),

    /** 这个采样率在本进程里运行时失败过（进程级黑名单，避免"失败→重建→再失败"死循环） */
    Blacklisted("该采样率此前失败过"),

    /** 探测矩阵已经测出"这个率 + 这个格式拿不到独占"，不必再试 */
    ProbedShared("探测显示拿不到独占"),

    /** `open` 直接返回 0 */
    OpenFailed("开流失败"),

    /** Oboe 落到了 OpenSL ES 后端（它没有 EXCLUSIVE 概念） */
    OpenSlBackend("走的是 OpenSL ES"),

    /** 流开出来了，但 HAL 给的是共享流（会过 mixer） */
    SharedStream("只拿到共享流"),

    /** HAL 把采样率改成了别的（那是"独占着重采样"，比走 mixer 更不可信） */
    RateChanged("HAL 改了采样率"),

    /** HAL 把样本格式换成了不兼容的东西（降位深 / 浮点与整数互换） */
    FormatChanged("HAL 改了样本格式"),

    /** 运行中写入失败（`onErrorAfterClose` 之后一直是这个状态） */
    WriteFailed("写入失败"),

    /** 环形缓冲写不进、设备也不再读走 —— 这条流已经不消费数据了 */
    Stalled("设备不消费数据"),
}

/**
 * 这一首要不要试独占 / 试了之后成不成。
 *
 * `Possible` 只是"值得试"，不代表一定成：真正的判据永远是开流后**回读**到的
 * sharing / rate / format（HAL 可以不遵守请求）。
 */
sealed interface ExclusiveVerdict {
    /** 值得试 */
    data object Possible : ExclusiveVerdict

    /** 不试 / 试了失败，带原因与可选细节（例如 `48000 Hz`、`err=-899`） */
    data class Denied(
        val denial: ExclusiveDenial,
        val detail: String = "",
    ) : ExclusiveVerdict
}

/**
 * 试不试独占 —— **纯函数**，不含任何 Android 依赖，可单测。
 *
 * 三个输入都是"事实"，不在这里做任何设备/路由猜测：
 *
 * @param wanted         用户与当前路由是否允许试独占（选了「AAudio 独占」通路、且不是蓝牙）
 * @param runtimeFailed  这个采样率是否在本进程里运行时失败过
 * @param probedExclusive 探测矩阵的结论：`true` 能独占 / `false` 拿不到 / `null` 还没探到
 *
 * `probedExclusive == null` 时**允许试**（Unknown 不等于 Impossible）：
 * 探测是异步的、且绑定当前路由，启动早期或刚插拔设备时它本来就是空的 ——
 * 那时拦下来会让"插上耳机第一首永远赶不上独占"。
 */
fun decideExclusive(
    wanted: Boolean,
    runtimeFailed: Boolean,
    probedExclusive: Boolean?,
): ExclusiveVerdict = when {
    !wanted -> ExclusiveVerdict.Denied(ExclusiveDenial.PathNotExclusive)
    runtimeFailed -> ExclusiveVerdict.Denied(ExclusiveDenial.Blacklisted)
    probedExclusive == false -> ExclusiveVerdict.Denied(ExclusiveDenial.ProbedShared)
    else -> ExclusiveVerdict.Possible
}
