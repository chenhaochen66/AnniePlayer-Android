package com.annieplayer.annie_music.kt.core.audio.dsd

/**
 * DSD 曲目的**传输方式**——三种模式的区别只有一个：交给 DAC 的到底是什么。
 *
 * | 值 | 送出去的东西 | 帧率 | 需要什么 |
 * |---|---|---|---|
 * | [pcm] | 抽取后的 PCM 网格 | 44.1 家族任一档 | 无 |
 * | [dop] | 伪装成 24-bit PCM 的 1-bit 流 | `bitRate / 16` | 逐字节保真的通路 |
 * | [native] | raw DSD（32-bit word） | `bitRate / 32` | USB 直连 + DAC 声明 raw alt |
 *
 * `pcm` 是终点：doP 与 native 都可能在运行时降回它（见降级链），反过来不成立 ——
 * 一旦抽成 PCM 就再也回不到 1-bit。
 */
enum class DsdTransport(val key: String, val label: String) {
    pcm("pcm", "DSD 转 PCM"),
    dop("dop", "DoP"),
    native("native", "Native DSD"),
    ;

    /** 是不是真的在送 1-bit 流（`pcm` 只是普通 PCM 播放） */
    val isRaw: Boolean get() = this != pcm

    companion object {
        fun fromKey(key: String?): DsdTransport =
            entries.firstOrNull { it.key == key } ?: pcm

        /**
         * 降级顺序：`native → dop → pcm`，**只往下降**。
         *
         * 不反向试：用户选 DoP 说明他要么不想碰厂商私有 alt，要么设备只认 DoP，
         * 我们替他从 DoP 试 Native 等于替他判断设备能力。
         */
        fun fallbackChain(from: DsdTransport): List<DsdTransport> = when (from) {
            native -> listOf(native, dop, pcm)
            dop -> listOf(dop, pcm)
            pcm -> listOf(pcm)
        }
    }
}
