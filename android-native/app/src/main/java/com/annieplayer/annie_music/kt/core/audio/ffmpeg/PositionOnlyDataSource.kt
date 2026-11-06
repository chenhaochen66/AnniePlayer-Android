package com.annieplayer.annie_music.kt.core.audio.ffmpeg

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.util.Arrays

/**
 * 只推进位置、不搬字节的 [DataSource]，专给 [FfmpegLocalExtractor] 的 `ExtractorInput` 用。
 *
 * 本地全链路的数据全部来自 native（ffmpeg 自己按路径读文件），extractor 从不消费
 * `ExtractorInput` 的内容。但 Media3 的 `ProgressiveMediaPeriod.ExtractingLoadable.load()`
 * 是靠 `getCurrentInputPosition()` —— 也就是这个 input 的位置 —— **每前进 50 KB** 才
 * `close()` 一次加载闸门、把控制权交回播放线程判断「还要不要继续读」。
 *
 * 位置不动，这个钩子永远不触发：loader 会在 `block()` 上永不阻塞，一路连读到 native EOF。
 * DSD64 解成 PCM 是源文件的 6 倍，实测 0.34 秒读进 216 MB 直接撑爆 Java 堆；同时它永远
 * 准备不完（时间轴缺失 → 拖不动进度条），`release()` 时 loader 还在跑（切歌卡死）。
 *
 * 所以这里给一条「位置会动、字节是空的」数据源，让 `read()` 里那句 `input.skip()` 变成
 * 纯位置推进，不产生任何磁盘 IO。
 */
@OptIn(UnstableApi::class)
internal class PositionOnlyDataSource : BaseDataSource(/* isNetwork= */ false) {

    private var uri: Uri? = null
    private var position: Long = 0L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        position = dataSpec.position
        transferStarted(dataSpec)
        // 长度未知：`length` 因此是 UNSET，`DefaultExtractorInput.skip` 不会撞上界
        return dataSpec.length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        Arrays.fill(buffer, offset, offset + length, 0)
        position += length
        bytesTransferred(length)
        return length
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        transferEnded()
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = PositionOnlyDataSource()
    }
}
