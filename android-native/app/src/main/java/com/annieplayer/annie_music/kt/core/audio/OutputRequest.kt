package com.annieplayer.annie_music.kt.core.audio

import com.annieplayer.annie_music.kt.core.audio.dsd.DsdTransport

/**
 * 一次 `configure` 的**不可变输入**：播放层在开流前算一次，整个 configure 只读它。
 *
 * 为什么必须冻结：这几项过去是三个独立 lambda（`nativeRawAlt` / `rawFlowActive` /
 * `transportTag`），全部读同一批可变全局量，而 `configure` 在不同时刻读了好几次
 * （判复用一次、决定重开时传一次、真正开流又一次）。两次读之间主线程改掉
 * `_dsdTransport`，同一首歌的两半就会走不同的 alt：PCM 字节被装进 raw alt 时
 * 发得出去、进度照走、**完全没有声音**；反过来 raw 帧进 PCM alt 是满幅噪声。
 *
 * 所以「这一次 configure 要什么」是一个值，不是一个随时会变的函数。
 */
data class OutputRequest(
    /** 用户选的通路（**意图**）。sink 只拿它做诊断，不据此决定开哪条流 */
    val pathIntent: OutputPath,
    /** 用户选的 DSD 传输方式（**意图**） */
    val transport: DsdTransport,
    /** 需要设备的 raw alt —— **只有 Native**；DoP 的帧是标准 24 bit PCM，必须留在 PCM alt */
    val rawAlt: Boolean,
    /** 送的不是 PCM 样本（Native 或 DoP）→ DSP 必须旁路、回落系统通路时必须判失败 */
    val rawFrames: Boolean,
    /** 失败记忆用的键（`方式@档位`），必须在开流那一刻定死，不能等失败时再算 */
    val transportTag: String,
) {
    companion object {
        /** 拿不到供应者时的保守值：按 PCM + 系统通路处理（最不容易把帧送错 alt） */
        val Conservative = OutputRequest(
            pathIntent = OutputPath.systemMix,
            transport = DsdTransport.pcm,
            rawAlt = false,
            rawFrames = false,
            transportTag = "",
        )
    }
}
