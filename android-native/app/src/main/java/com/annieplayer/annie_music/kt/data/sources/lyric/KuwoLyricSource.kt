package com.annieplayer.annie_music.kt.data.sources.lyric

import com.annieplayer.annie_music.kt.data.LyricResult
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.sources.kuwo.fetchKuwoLyric

/**
 * 酷我歌词：PC 端接口，优先 LRCX（逐字），该曲没有就退同一接口的普通 LRC。
 *
 * 酷我的 id 是**无前缀**的纯数字（`MUSIC_xxx` 去掉前缀后的样子），但个别来源（落雪插件）
 * 会带上 `kuwo:` 前缀，这里统一剥掉再请求。
 */
internal object KuwoLyricSource : LyricSource {

    override val platform: MusicPlatform = MusicPlatform.kuwo

    override suspend fun fetch(song: Song, req: LyricRequest): LyricResult {
        val r = fetchKuwoLyric(song.id.removePrefix("kuwo:"))
        return LyricResult(lrc = r.lrc, tlyric = r.tlyric, wordLyric = r.wordLyric)
    }
}
