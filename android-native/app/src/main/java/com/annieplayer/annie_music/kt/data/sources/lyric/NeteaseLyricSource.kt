package com.annieplayer.annie_music.kt.data.sources.lyric

import com.annieplayer.annie_music.kt.data.LyricResult
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.sources.netease.fetchNeteaseLyric

/**
 * 网易云歌词：一次 POST `api/song/lyric`（`lv/tv/yv=-1`）同时拿到原文 / 译文 / 逐字（yrc）。
 *
 * 没有内部降级链 —— 服务端一次给全，缺哪个字段就是没有。
 */
internal object NeteaseLyricSource : LyricSource {

    override val platform: MusicPlatform = MusicPlatform.netease

    override suspend fun fetch(song: Song, req: LyricRequest): LyricResult {
        val r = fetchNeteaseLyric(song.id)
        return LyricResult(lrc = r.lrc, tlyric = r.tlyric, wordLyric = r.wordLyric)
    }
}
