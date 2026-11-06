package com.annieplayer.annie_music.kt.data.local.tag

/**
 * 与封装格式无关的标签字段。
 *
 * 字段集合刻意与 `LocalTrackMeta`（本地曲库）和下载打标写入的那一套**对齐**：
 * 少一个字段就意味着「下载时写了、编辑时看不到」或者反过来。
 */
enum class TagKey(val label: String) {
    Title("标题"),
    Artist("艺术家"),
    Album("专辑"),
    AlbumArtist("专辑艺术家"),
    Track("音轨号"),
    Disc("碟号"),
    Year("年份"),
    Genre("流派"),
    Composer("作曲"),
    Isrc("ISRC"),
    Comment("备注/版本"),
    Copyright("版权"),
    Lyric("歌词"),
}

/** 封面：原始文件字节 + 类型，不做任何重编码。 */
data class CoverBytes(val bytes: ByteArray, val mime: String) {
    val isPng: Boolean get() = mime == "image/png"

    override fun equals(other: Any?): Boolean =
        this === other || (other is CoverBytes && mime == other.mime && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + mime.hashCode()
}

/**
 * 一份标签载荷。
 *
 * 字段分两类：
 * - [text]：**标准字段**（枚举 [TagKey]），UI 有中文名和固定顺序，个别字段（trkn/disk）还要按格式
 *   做二进制编码 —— 所以它们是显式声明的。
 * - [extra]：**长尾字段**，键用各格式的「原生自由键」：
 *   MP4 是 `----` freeform 的 name（如 `BPM`）、Vorbis 是任意大写 `KEY`、ID3 是 `TXXX` 的描述。
 *   加这类字段**不需要改打标库**，而且读取时未知的键会原样落进这里 —— 保证「读出→写回」不丢数据。
 *
 * 写入语义：给了就覆盖，没给就保留文件里原来的值（各 codec 内部会先读一遍现有标签再合并）。
 */
data class TagBag(
    val text: Map<TagKey, String> = emptyMap(),
    val cover: CoverBytes? = null,
    val extra: Map<String, String> = emptyMap(),
) {
    operator fun get(key: TagKey): String? = text[key]?.takeIf { it.isNotEmpty() }

    fun isEmpty(): Boolean =
        text.values.all { it.isEmpty() } && cover == null && extra.values.all { it.isEmpty() }

    companion object {
        fun of(vararg pairs: Pair<TagKey, String?>): TagBag = TagBag(
            text = pairs.mapNotNull { (k, v) -> v?.takeIf { it.isNotEmpty() }?.let { k to it } }.toMap(),
        )

        /** 长尾字段的便捷构造（`extraOf("BPM" to "128")`）。 */
        fun extraOf(vararg pairs: Pair<String, String?>): TagBag = TagBag(
            extra = pairs.mapNotNull { (k, v) -> v?.takeIf { it.isNotEmpty() }?.let { k to it } }.toMap(),
        )
    }
}

/** 写入策略。 */
enum class WritePolicy {
    /**
     * 优先**原地**改（FLAC 复用 PADDING、MP4 旧 moov 改 free + 尾部追加新 moov）。
     * 原地写只碰几 KB，不动音频数据；做不到再退化为全量重写。
     */
    PreferInPlace,

    /** 强制全量重写（下载刚生成的文件用这个，反正音频还没写完）。 */
    FullRewrite,
}

/** 写入结果。[inPlace] = true 表示没有搬运音频数据。 */
data class WriteResult(
    val ok: Boolean,
    val format: String = "",
    val inPlace: Boolean = false,
    val message: String = "",
) {
    companion object {
        fun fail(format: String, message: String) = WriteResult(false, format, false, message)
        fun ok(format: String, inPlace: Boolean) = WriteResult(true, format, inPlace)
    }
}
