package com.annieplayer.annie_music.kt.data.local.stream

import com.annieplayer.annie_music.kt.data.local.tag.TagBag
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream

/**
 * `StreamTag` —— 边收边封装 + 直写（下载链路的「零趟打标」）。
 *
 * ## 它和 AnnieTag 的分工
 * - `AnnieTag`：文件**已经存在**，要改标签。需要随机访问，装不下就整份重写。
 * - `StreamTag`：字节**正在从网上来**，边收边把容器头和标签写出去，落地的就是成品。
 *
 * 两者共用同一套标签编码（`Id3v2Tag` / `VorbisComment` / `ApeV2Tag` / `Mp4Codec`），
 * 只是写入时机不同 —— 所以分成两个库，同一层语义，谁都不依赖对方。
 *
 * ## 一股流做完三件事
 * 以前下载是「写文件 N → 打标 0~2N → 入库 2N」；这里合成一趟：读网络 → 改头部 → 写出。
 * 音频字节一次都不搬，也不产生中间文件。
 *
 * ## 加新格式
 * 在 `data/local/stream/` 下加一个 `StreamCodec` 实现，登记进 [codecs]。
 * 探测、执行、透传、尾部裁剪、回填都在本文件里统一处理，没有第二处分支要改。
 * 目前登记了 8 类（覆盖 mp3 / flac / ogg / opus / m4a / ape 系 / dsd 系 / wav / aiff），
 * 其中 [Mp4Stream] 与 [ApeStream] 会**主动放弃**（原因写在各自文件里），回退到落盘 + `AnnieTag`。
 */
object StreamTag {

    /**
     * 头部预读上限。
     *
     * 要装得下：FLAC 的完整块链（含内嵌封面）、Ogg 的头部页群、DSDIFF 的 `FVER`/`PROP`
     * 加 `DSD ` chunk 头。256KB 对真实文件绰绰有余；超出就说明结构不常见，
     * 各 codec 会返回 null 交给落盘路径，**不会猜**。
     */
    const val PEEK: Int = 256 * 1024

    private val codecs: List<StreamCodec> = listOf(
        Mp4Stream,   // ftyp 在 offset 4，判定最严
        FlacStream,  // fLaC
        OggStream,   // OggS（vorbis / opus / speex）
        DsdStream,   // DSD / FRM8
        RiffStream,  // RIFF / FORM
        ApeStream,   // MAC / wvpk / MPCK / TTA1
        AacStream,   // ID3 + ADTS 同步（必须排在 Mp3Stream 前，两者都能以 ID3 开头）
        Mp3Stream,   // ID3 或 0xFF 帧同步 —— 最宽松，必须放最后
    )

    /** 探测格式；不认识的返回 null。 */
    internal fun probe(head: ByteArray): StreamCodec? = codecs.firstOrNull { it.matches(head) }

    /**
     * 从头部推出落盘该用的扩展名（带点）；不认识返回 null。
     *
     * 下载链路原先靠一段只认 flac/mp3/m4a 的私有嗅探，现在统一走这里 ——
     * 加格式只改 codec，扩展名跟着就来。
     */
    fun extensionOf(head: ByteArray): String? = probe(head)?.extension(head)

    /**
     * 只问「能不能流式写」，不产生任何副作用。
     *
     * 调用方靠它在**动手之前**分流：能流式就直写落点，不能就先落盘再打标。
     * 之所以不写成「先写着看，失败再回退」——回退时目标里已经有半截字节，
     * MediaStore 那边会留下一条坏条目。
     */
    fun canStream(head: ByteArray, contentLength: Long, bag: TagBag): Boolean =
        planOf(head, contentLength, bag) != null

    private fun planOf(head: ByteArray, contentLength: Long, bag: TagBag): StreamPlan? {
        val codec = probe(head) ?: return null
        return runCatching { codec.plan(head, contentLength, bag) }.getOrNull()
    }

    /**
     * 边收边写：把 [head] + [rest] 变换成「已带标签的成品」写进 [out]。
     *
     * @param head 已经从源流读出来的前 [PEEK] 字节
     * @param rest [head] 之后的源流（本方法不负责关闭它）
     * @param contentLength 源流总长度，未知传 -1
     * @param onBytes 已消费的源字节数（进度用）
     * @return null 表示**字节一个都没动**：这个格式不能流式注入，调用方必须走落盘路径
     */
    fun write(
        head: ByteArray,
        rest: InputStream,
        contentLength: Long,
        bag: TagBag,
        out: OutputStream,
        onBytes: (Long) -> Unit = {},
    ): StreamOutcome? {
        // 探测和规划只看 head / contentLength，这一步失败时 rest 一个字节都没被读走，
        // 所以调用方可以安全地回退。
        val codec = probe(head) ?: return null
        val plan = planOf(head, contentLength, bag) ?: return null

        val input = SequenceInputStream(ByteArrayInputStream(head), rest)
        out.write(plan.prefix)
        StreamIo.skipFully(input, plan.skip)
        // pumpBody 负责「prefix 之后的一切」，包括 suffix —— RIFF 需要自己插对齐字节，
        // 所以不能让外面替它写。
        val written = codec.pumpBody(input, out, plan, onBytes)
        out.flush()

        val total = plan.prefix.size + written
        return StreamOutcome(
            formatId = codec.id,
            totalBytes = total,
            patches = plan.patches.map { it.offset to it.encode(total) },
        )
    }
}

/**
 * 一次流式注入的结果。
 *
 * [patches] 由调用方回填 —— 库本身只输出流，不假设目标可随机访问；
 * 需要回填的格式（RIFF/FORM 总长、DSF 指针、DSDIFF `FRM8` 长度）靠这一步才算完整。
 */
class StreamOutcome internal constructor(
    /** 命中的格式 id（`mp3` / `flac` / …），日志用。 */
    val formatId: String,
    /** 写出的总字节数。 */
    val totalBytes: Long,
    /** 要回填的字段：偏移 → 字节。 */
    val patches: List<Pair<Long, ByteArray>>,
)
