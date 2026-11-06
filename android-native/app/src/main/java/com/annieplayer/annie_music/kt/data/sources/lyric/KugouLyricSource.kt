package com.annieplayer.annie_music.kt.data.sources.lyric

import com.annieplayer.annie_music.kt.data.LyricResult
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.sources.kugou.fetchKugouLyric

/**
 * 酷狗歌词：`lyrics.kugou.com` 搜索 + 下载两步。
 *
 * **搜索要 `title + artist` 当关键字**（只有 hash 命中不了），所以这里的入参是整首 [Song]
 * 而不是 id —— 这也正是「用 [LyricRequest] 而不是给每个实现开一套参数」的原因之一。
 */
internal object KugouLyricSource : LyricSource {

    override val platform: MusicPlatform = MusicPlatform.kugou

    override suspend fun fetch(song: Song, req: LyricRequest): LyricResult {
        val r = fetchKugouLyric(song.id, "${song.title} ${song.artist}")
        return LyricResult(lrc = r.lrc, tlyric = r.tlyric, wordLyric = r.wordLyric)
    }
}
