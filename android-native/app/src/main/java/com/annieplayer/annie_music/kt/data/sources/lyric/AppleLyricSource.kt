package com.annieplayer.annie_music.kt.data.sources.lyric

import com.annieplayer.annie_music.kt.data.LyricResult
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.sources.apple.wrapper.AppleWrapperApi

/**
 * Apple Music 歌词（TTML → 增强 LRC，带逐字）。
 *
 * 只有一条数据源：用户配置的 Apple Wrapper 地址（`AppleWrapperApi`）。
 * 没配地址时返回空词，不再尝试任何第三方站点。
 *
 * Apple 没有译文（`tlyric` 恒为 null）。
 */
internal object AppleLyricSource : LyricSource {

    override val platform: MusicPlatform = MusicPlatform.apple

    override suspend fun fetch(song: Song, req: LyricRequest): LyricResult {
        val r = AppleWrapperApi.fetchLyric(song.id)
        return LyricResult(lrc = r.lrc, tlyric = r.tlyric, wordLyric = r.wordLyric)
    }
}
