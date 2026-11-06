package com.annieplayer.annie_music.kt.data.sources.lyric

import com.annieplayer.annie_music.kt.data.LyricResult
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.sources.qq.fetchQQLyric

/**
 * QQ 音乐歌词：`GetPlayLyricInfo` 先要 QRC（逐字），解不开/该曲没有就退回普通 LRC。
 *
 * 这条降级链留在实现里：门面只关心「最终有没有词、有没有逐字」，不关心试了几次。
 */
internal object QqLyricSource : LyricSource {

    override val platform: MusicPlatform = MusicPlatform.qq

    override suspend fun fetch(song: Song, req: LyricRequest): LyricResult {
        val r = fetchQQLyric(song.id)
        return LyricResult(lrc = r.lrc, tlyric = r.tlyric, wordLyric = r.wordLyric)
    }
}
