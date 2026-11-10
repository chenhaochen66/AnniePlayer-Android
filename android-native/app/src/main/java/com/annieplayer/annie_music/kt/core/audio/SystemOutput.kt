package com.annieplayer.annie_music.kt.core.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer

/**
 * 系统混音通路（`AudioTrack` / AudioFlinger）作为一条 [OutputPort]。
 *
 * 只是薄壳：位置、时钟、暂停、结束判定**全部交给 Media3 的 `DefaultAudioSink` 自己算**。
 * 这里刻意不做任何记账 —— 内核路径已经有了 `AudioTrackPositionTracker`，
 * 我们再算一份就是第二个真相。
 *
 * 唯一带逻辑的一处是 [flush]：`DefaultAudioSink.flush()` 内部会 `audioTrack.pause()`，
 * 而恢复播放只有一条路径（renderer 的 BUFFERING→READY 迁移调 `play()`）。
 * 所以刷完必须按上游意图补一次 `play()`，否则 seek 到已缓冲位置会"没声但状态是 PLAYING"。
 */
@OptIn(UnstableApi::class)
internal class SystemOutput(
    private val sink: AudioSink,
) : OutputPort {

    override val path: OutputPath get() = OutputPath.systemMix

    override fun write(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean = sink.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)

    override fun positionUs(sourceEnded: Boolean): Long = sink.getCurrentPositionUs(sourceEnded)

    override fun play() {
        sink.play()
    }

    override fun pause() {
        sink.pause()
    }

    override fun flush(playRequested: Boolean) {
        sink.flush()
        // 暂停中 seek 不会误播：那时 playRequested 是 false
        if (playRequested) sink.play()
    }

    override fun reset() {
        sink.reset()
    }

    override fun release() {
        sink.release()
    }

    override fun playToEndOfStream() {
        sink.playToEndOfStream()
    }

    override fun isEnded(): Boolean = sink.isEnded

    override fun hasPendingData(): Boolean = sink.hasPendingData()

    /** 系统通路的健康由 `AudioTrack` 自己管，这里没有可查的东西 */
    override fun isHealthy(): Boolean = true
}
