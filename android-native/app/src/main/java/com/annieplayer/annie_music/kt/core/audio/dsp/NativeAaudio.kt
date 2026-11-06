package com.annieplayer.annie_music.kt.core.audio.dsp

/**
 * AAudio 独占能力探测的 JNI 入口。**只探测，不播放**。
 *
 * 为什么是 AAudio 而不是 `AudioTrack`：`AudioTrack` 无论怎么写都会进 AudioFlinger 的
 * mixer 线程（Java 侧没有 PCM 直通标志），只有 AAudio 的
 * `AAUDIO_SHARING_MODE_EXCLUSIVE` + MMAP 才能真正绕开 mixer —— 而它
 * **不需要任何系统级权限**，普通安装可用。这是"Bit Perfect"那一级唯一能落地的实现方式。
 *
 * 本对象只负责"能不能开"。真用它播放是 Oboe 内核的事（见 [NativeOboeStream]）。
 */
object NativeAaudio {

    /**
     * `aaudio_format_t`（**不是位深**）。
     *
     * 32 位在 AAudio 里有两种互不兼容的东西 —— 整数 [FORMAT_I32] 与浮点 [FORMAT_FLOAT]，
     * 而 HAL 的 MMAP profile 往往只声明其中一种。用"位深"当参数就分不出这两者，
     * 会把"这台 HAL 的 MMAP 端口不支持 float"误读成"这台设备不支持独占"。
     */
    const val FORMAT_I16 = 1
    const val FORMAT_FLOAT = 2
    const val FORMAT_I24 = 3
    const val FORMAT_I32 = 4

    /** `libanniedsp.so` 是否加载成功；用 `null` 表示"还没试过" */
    @Volatile
    private var _loaded: Boolean? = null

    private fun loaded(): Boolean {
        _loaded?.let { return it }
        val ok = runCatching { System.loadLibrary("anniedsp") }.isSuccess
        _loaded = ok
        return ok
    }

    fun isAvailable(): Boolean = loaded()

    /**
     * 探测一次 [sampleRate] + [pcmFormat] 能否开成独占流。
     *
     * 回包格式见 `native-aaudio-probe.cpp` 的注释；解析在 `parseAaudioProbe`。
     * 任何异常都收敛成 `err:…` 字符串 —— 探测失败不该让能力面板崩掉。
     */
    fun probe(sampleRate: Int, pcmFormat: Int): String {
        if (!loaded()) return "err:nolib"
        return runCatching { nativeProbe(sampleRate, pcmFormat) }.getOrNull() ?: "err:jni"
    }

    private external fun nativeProbe(sampleRate: Int, pcmFormat: Int): String?
}

