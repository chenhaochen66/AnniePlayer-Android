package com.annieplayer.annie_music.kt.core.audio

import java.nio.ByteBuffer

/**
 * 一条**输出后端**的完整契约：写、时钟、生命周期。
 *
 * 存在的理由只有一个：以前三条通路（USB 直连 / AAudio 独占 / 系统混音）的编排全挤在
 * 一个类里，靠 `systemSink != null` 与 `handle != 0` 两个标志区分"现在走哪条"——
 * 于是每个方法开头都要写一遍 `systemSink?.let { ...; return }`，写路径有两份、
 * 时钟有两份、静音/结束判定有两份。
 *
 * 现在每个后端自己实现这一组原语，[AnnieAudioSink] 只做三件事：
 * 选后端、把 Media3 的 setter 转给它、把它的状态转回 Media3。
 *
 * ## 契约（照 Media3 `AudioSink` 的语义，不另立一套）
 *
 * - [write] 返回 `true` = **这块已经收下**（实现方负责推进 `buffer.position`）；
 *   返回 `false` = 没接收，Media3 会用**同一块**重试。
 * - [positionUs] 返回媒体时间微秒；还没建立时间轴时返回 `C.TIME_UNSET`。
 * - [isEnded] / [hasPendingData] 与 `DefaultAudioSink` 同构，两者互为反义的一半。
 */
internal interface OutputPort {

    /** 但这条通路的名字（上报与诊断用） */
    val path: OutputPath

    /**
     * 写一块 PCM / 已封装的 DSD 字节。
     *
     * @return true = 已消费（可能推进了 `buffer.position` 的部分字节）；false = 请用同一块重试
     */
    fun write(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean

    /** 当前位置（微秒）；`C.TIME_UNSET` = 时间轴还没建立 */
    fun positionUs(sourceEnded: Boolean): Long

    fun play()

    fun pause()

    fun flush(playRequested: Boolean)

    fun reset()

    fun release()

    /** 上游已宣告流结束 */
    fun playToEndOfStream()

    /** 播完（= 已宣告结束且管道里没东西了） */
    fun isEnded(): Boolean

    /** 管道里还有没播出去的数据 */
    fun hasPendingData(): Boolean

    /**
     * 这条流现在还正常吗。
     *
     * 系统通路恒 `true`（它的健康由 `AudioTrack` 自己管）；独占通路查 native 的 `dead`。
     * 只有它能回答"我们写进去了但设备根本没在出声"。
     */
    fun isHealthy(): Boolean
}
