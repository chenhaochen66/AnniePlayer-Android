package com.annieplayer.annie_music.kt.data.sources.lyric

import com.annieplayer.annie_music.kt.data.LyricResult
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.sources.qobuz.fetchQobuzLyrics
import com.annieplayer.annie_music.kt.data.sources.qobuz.qobuzTokenProvider

/**
 * Qobuz 官方歌词：两步（`track/lyricsUrl` 取签名 URL → 裸 GET 正文）。
 *
 * 与其它平台的两点不同，都在这一个文件里收口：
 * - **要 token**：没登录就抛异常（返回空会让门面以为「这歌没词」并缓存下来）；
 * - **译文是官方多语言**：可用语言清单通过 [LyricResult.translateLangs] 带回去，
 *   播放页据此弹语言选择；本次实际取到的语言放 [LyricResult.translateLang]。
 *
 * 没有逐字歌词（`wordLyric` 恒为 null），渲染层会自动按行高亮。
 */
internal object QobuzLyricSource : LyricSource {

    override val platform: MusicPlatform = MusicPlatform.qobuz

    override suspend fun fetch(song: Song, req: LyricRequest): LyricResult {
        val token = qobuzTokenProvider().orEmpty()
        if (token.isEmpty()) throw IllegalStateException("Qobuz 未登录，无法取歌词")
        val r = fetchQobuzLyrics(
            trackId = song.id.removePrefix("qobuz:"),
            token = token,
            translateLang = req.translateLang,
            autoPickFirst = req.autoPickTranslate,
        )
        return LyricResult(
            lrc = r.lrc,
            tlyric = r.tlyric,
            wordLyric = null,
            translateLangs = r.translateLangs,
            translateLang = r.translateLang,
        )
    }
}
