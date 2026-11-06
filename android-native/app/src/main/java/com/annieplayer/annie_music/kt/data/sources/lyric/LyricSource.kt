package com.annieplayer.annie_music.kt.data.sources.lyric

import com.annieplayer.annie_music.kt.data.LyricResult
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song

/**
 * 拉不到歌词时的统一占位文本。
 *
 * 它只由门面（`MusicRepository.fetchLyric`）在**最后**填；各平台实现不许自己填 ——
 * 否则门面分不清「这歌真没有词」和「这次请求失败了」，失败会被当成结果缓存一整个 TTL
 * （表现成「网络明明好了，歌词还是不出来」）。
 */
internal const val NO_LYRIC = "[00:00.00]暂无歌词"

/**
 * 一次取词请求里与平台无关的参数。实现按需取用，不用的忽略。
 *
 * 之所以集中成一个对象：平台从 2 个涨到 7 个之后，若每个实现各带一套可选参数，
 * 门面就得记住谁要什么（酷狗要 keyword、Qobuz 要 token + 译语），加一个平台就要改门面的调用点。
 * 放进请求对象后，加平台只加文件。
 *
 * KKBOX 的会话 `sid` 不在这里 —— 它连同「不进缓存」的语义一起留在门面（见 [LyricSources]）。
 */
internal data class LyricRequest(
    /** 想要的译文语言；null = 只要原文 */
    val translateLang: String? = null,
    /** 没显式指定语言时是否自动取清单里的第一个（播放页默认行为） */
    val autoPickTranslate: Boolean = false,
)

/**
 * 一个平台的歌词来源。
 *
 * ## 契约（合并范式之后，这几条比代码结构更重要）
 *
 * 1. **不许返回占位文本**：真的没歌词就返回 `lrc = ""`；请求失败**抛异常**。
 *    「占位」和「要不要缓存」只由门面决定。
 * 2. `wordLyric` **只能是增强 LRC**（`[mm:ss.xx]` 行 + 行内 `<mm:ss.xx>` 逐字段）。
 *    **没有逐字就给 null** —— 渲染层见到 null 会自动按行高亮，不需要平台给开关。
 *    新平台若直接返回原始 krc/qrc/yrc，播放页会静默不显示逐字（而不是报错），
 *    所以转换责任在实现里，别推给渲染层。
 * 3. `tlyric` 必须与 `lrc` **行对齐**（实现自己负责，不指望渲染层兜）。
 * 4. 平台内部的降级链（QQ `qrc→lrc`、酷我 `lrcx→lrc`、网易 yrc 为空）留在实现内，
 *    接口只暴露最终结果。
 * 5. 实现**不许自己缓存** —— 缓存 key 里要带译语语言、auto 标记、Apple 源标记，
 *    这些都是门面才知道的事。
 *
 * ## 加一个新平台
 * 在本目录加一个实现 [LyricSource] 的 `object`，登记进 [LyricSources.all]，完事。
 * 匹配靠 [platform]（不看 id 前缀），所以**顺序无关**，也不存在「漏了就掉进某个兜底平台」。
 */
internal interface LyricSource {

    /** 本实现负责的平台。一对一匹配键，注册顺序无关。 */
    val platform: MusicPlatform

    suspend fun fetch(song: Song, req: LyricRequest): LyricResult
}

/**
 * 注册表。
 *
 * 按 [LyricSource.platform] 一对一，**没有兜底**：认不出就返回 null，门面给占位。
 * 以前是按 `id.startsWith(...)` 分派、最后 `else` 落到酷我 —— 于是任何未知平台都会
 * 静默去请求酷我，漏了分支还能「碰巧」出歌词，是最难查的一类问题。
 *
 * KKBOX 不在这里：它要会话 `sid`、且**不进通用缓存**，留在门面当特例。
 */
internal object LyricSources {

    private val all: List<LyricSource> = listOf(
        NeteaseLyricSource,
        QqLyricSource,
        KugouLyricSource,
        KuwoLyricSource,
        QobuzLyricSource,
        AppleLyricSource,
    )

    fun of(song: Song): LyricSource? =
        all.firstOrNull { it.platform == song.platform }
}
