package com.annieplayer.annie_music.kt.data.local.stream

import com.annieplayer.annie_music.kt.data.local.tag.TagBag
import com.annieplayer.annie_music.kt.data.local.tag.ascii
import com.annieplayer.annie_music.kt.data.local.tag.u32be
import com.annieplayer.annie_music.kt.data.local.tag.unsynchsafe
import java.io.InputStream
import java.io.OutputStream

/**
 * 一种容器的「边收边封装」实现。
 *
 * 与 `TagCodec`（改一个**已经存在**的文件）互补：这里的输入是网络字节流，输出是成品字节流，
 * 中间不落临时文件、也不回头改文件。所有实现都必须满足三条：
 *
 * 1. **音频字节原样透传** —— 除了 [StreamPlan.skip] 丢掉的旧头部、[StreamPlan.tailTrim]
 *    裁掉的旧尾标签，其余字节一个不改；
 * 2. **纯 JVM** —— 只用 `java.io`，不碰 Android，这样能在单测里逐格式跑；
 * 3. **看不准就放弃** —— [plan] 返回 null 表示这个格式没法安全地流式注入，
 *    调用方会退回「先落盘、再用 AnnieTag 打标」，绝不硬写出坏文件。
 *
 * ## 加新格式
 * 新加一个实现本接口的 `object`，登记进 [StreamTag.codecs] 就完事。
 * 探测、执行、透传、尾部裁剪、回填都在 [StreamTag] 里统一处理，没有第二处分支要改。
 */
internal interface StreamCodec {

    /** 稳定 id（日志 / [StreamOutcome.formatId]），小写、无点。 */
    val id: String

    /** 从头部判定的标准扩展名（带点）。下载落盘用它定文件名。 */
    fun extension(head: ByteArray): String

    /** 只看头部判断是不是本格式。 */
    fun matches(head: ByteArray): Boolean

    /**
     * 生成注入计划。
     *
     * @param head 源文件前 [StreamTag.PEEK] 字节（不足则是整个文件）
     * @param contentLength 源流总长度，未知为 -1。靠它才能判断「某一块是不是最后一块」
     *   （WAV/AIFF 的 `data`、DSDIFF 的 `DSD `），这些格式拿不到长度就得放弃。
     * @return null = 不能流式注入
     */
    fun plan(head: ByteArray, contentLength: Long, bag: TagBag): StreamPlan?

    /**
     * [StreamPlan.skip] 之后的全部写出工作。
     *
     * 默认实现 = 原样透传 body（按 [StreamPlan.bodyLimit] 截断、按 [StreamPlan.tailTrim] 裁尾）
     * + 追加 [StreamPlan.suffix]。
     *
     * 覆盖它的两种情况：
     * - **Ogg**：页头带序号和 CRC，头部页群一变长，后面每一页都得重编号；
     * - **RIFF/AIFF**：音频块之前的旧 `LIST/INFO` 必须逐块筛掉（它们会和新标签抢同名键）。
     *
     * @return prefix 之后**总共**写出的字节数（含 suffix），调用方拿它算总长才能回填长度字段。
     */
    fun pumpBody(
        input: InputStream,
        out: OutputStream,
        plan: StreamPlan,
        onBytes: (Long) -> Unit,
    ): Long {
        val copied = StreamIo.copyBody(input, out, plan, onBytes)
        out.write(plan.suffix)
        return copied + plan.suffix.size
    }
}

/**
 * 一次流式注入的完整描述。**纯数据**，可以脱离 I/O 单独断言。
 *
 * 最终字节流的形状固定是：`prefix` + (`body` 透传) + `suffix`，其中
 * - `prefix` 由我们生成（新容器头 + 标签）；
 * - `body` 从源流 [skip] 字节之后开始，最多 [bodyLimit] 字节，尾部按 [tailTrim] 裁；
 * - `suffix` 追加在 body 后面（尾部标签格式用）。
 */
internal class StreamPlan(
    /** 先写出去的字节：新容器头 + 我们的标签。 */
    val prefix: ByteArray = StreamIo.EMPTY,
    /** 源流开头要丢掉的字节数（源里原有的标签/头部，已经被 [prefix] 取代）。 */
    val skip: Long = 0L,
    /** body 之后追加的字节（尾部标签：APEv2 / ID3 / `id3 ` chunk）。 */
    val suffix: ByteArray = StreamIo.EMPTY,
    /** body 末尾要裁掉的旧尾标签。 */
    val tailTrim: TailTrim = TailTrim.NONE,
    /** body 最多透传多少字节（**从 body 起点算**，不是文件绝对偏移）；-1 = 全部。 */
    val bodyLimit: Long = -1L,
    /** 结束后要回填的字段（总长度、指针）。 */
    val patches: List<StreamPatch> = emptyList(),
)

/**
 * 尾部裁剪规则：源文件末尾若是一段旧标签，就别写出去。
 *
 * 之所以要裁：mp3 带 ID3v1 时，读标签的一方可能优先取到那条旧的 —— 表现是
 * 「文件里明明写了新标题，播放器显示的还是旧的」。
 */
internal class TailTrim private constructor(val size: Int, private val magic: String) {

    val enabled: Boolean get() = size > 0

    /** [tail] 必须正好是文件末尾 [size] 字节。 */
    fun matches(tail: ByteArray): Boolean {
        if (!enabled || tail.size != size) return false
        if (magic.isEmpty()) return true
        for (i in magic.indices) {
            if (tail[i] != magic[i].code.toByte()) return false
        }
        return true
    }

    companion object {
        val NONE = TailTrim(0, "")

        /** [magic] 为空表示「不管内容，末尾这么多字节一律丢」。 */
        fun of(size: Int, magic: String = ""): TailTrim = TailTrim(size, magic)
    }
}

/**
 * 结束后要回填的一个字段。
 *
 * [encode] 只拿**文件总长**：长度字段（RIFF/FORM 的全长、DSDIFF 的 `FRM8` 长度、DSF 的总长）
 * 都只依赖它；别的量（比如 DSF 的 ID3 指针 = 音频结束位置）在 [StreamPlan] 阶段就是已知常量，
 * 由 codec 自己闭包捕获。这样 [StreamPatch] 不需要在「body 结束」和「总长」之间做区分，
 * 少一个容易搞错的参数。
 */
internal class StreamPatch(
    /** 绝对偏移（从文件起始算）。 */
    val offset: Long,
    val encode: (total: Long) -> ByteArray,
)

/** 回填用的定长整数编码（DSF 小端、DSDIFF/RIFF 大端都有）。 */
internal object StreamIo {

    val EMPTY = ByteArray(0)

    const val BUFFER = 256 * 1024

    fun u64le(v: Long): ByteArray = ByteArray(8) { i -> ((v ushr (8 * i)) and 0xFF).toByte() }

    fun u64be(v: Long): ByteArray = ByteArray(8) { i -> ((v ushr (8 * (7 - i))) and 0xFF).toByte() }

    /** 读满 [buf]；返回 false 表示流已经在中间结束（只可能出现在文件尾）。 */
    fun readFully(input: InputStream, buf: ByteArray, off: Int = 0, len: Int = buf.size): Boolean {
        var read = 0
        while (read < len) {
            val n = input.read(buf, off + read, len - read)
            if (n <= 0) return false
            read += n
        }
        return true
    }

    /** 丢掉 [count] 字节（源里被取代的旧头部）。 */
    fun skipFully(input: InputStream, count: Long) {
        if (count <= 0) return
        var left = count
        val buf = ByteArray(minOf(left, 64L * 1024L).toInt().coerceAtLeast(1))
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n <= 0) return
            left -= n
        }
    }

    /**
     * 源开头一共叠了几个 ID3v2、加起来多少字节（mp3 / adts 共用）。
     *
     * 只读每段的 10 字节头就能算出来，**不需要**那段的正文 —— 所以即使某个标签有几十 MB
     * （塞了大封面），也能正确跳过而不用全部读进内存。
     *
     * @return null = 头部字段不合法（大小字段解出负数）
     */
    fun leadingId3Size(head: ByteArray): Long? {
        var pos = 0L
        while (pos + ID3_HEADER <= head.size && head.ascii(pos.toInt(), 3) == "ID3") {
            val at = pos.toInt()
            val major = head[at + 3].toInt() and 0xFF
            val flags = head[at + 5].toInt() and 0xFF
            // 大小字段是 synchsafe：4 个字节各只用低 7 位
            val body = unsynchsafe(head.u32be(at + 6))
            if (body < 0) return null
            var total = ID3_HEADER.toLong() + body
            // v2.4 的 footer 额外占 10 字节，且**不计入** size
            if (major == 4 && (flags and 0x10) != 0) total += ID3_HEADER
            pos += total
        }
        return pos
    }

    private const val ID3_HEADER = 10

    /** 默认透传：原样复制 body，按 [StreamPlan.bodyLimit] 截断，按 [StreamPlan.tailTrim] 裁尾。 */
    fun copyBody(
        input: InputStream,
        out: OutputStream,
        plan: StreamPlan,
        onBytes: (Long) -> Unit,
    ): Long {
        val buf = ByteArray(BUFFER)
        val hold = TailHold(out, plan.tailTrim.size)
        var consumed = 0L
        while (true) {
            val want = if (plan.bodyLimit < 0) {
                buf.size
            } else {
                minOf(buf.size.toLong(), plan.bodyLimit - consumed).toInt()
            }
            if (want <= 0) break
            val n = input.read(buf, 0, want)
            if (n <= 0) break
            consumed += n
            hold.write(buf, 0, n)
            onBytes(consumed)
        }
        hold.finish(plan.tailTrim)
        return hold.emitted
    }
}

/**
 * 前向缓冲：写入时**永远攒着最后 [holdSize] 字节**，其余立刻写出去。
 *
 * 这样不用知道文件多长，也能在读到 EOF 的那一刻判定「尾巴是不是旧标签」并决定丢不丢。
 * [holdSize] 为 0 时退化成直通，多一层 if 的代价可以忽略。
 */
private class TailHold(private val out: OutputStream, private val holdSize: Int) {

    private val hold = ByteArray(holdSize.coerceAtLeast(0))
    private var len = 0
    private var written = 0L

    /** 已经写出去的字节数。 */
    val emitted: Long get() = written

    fun write(buf: ByteArray, off: Int, count: Int) {
        if (holdSize == 0) {
            out.write(buf, off, count)
            written += count
            return
        }
        var o = off
        var c = count
        if (len + c <= holdSize) {
            buf.copyInto(hold, len, o, o + c)
            len += c
        } else {
            // 先把 hold 里该吐的部分吐掉，空出位置接新字节
            var need = len + c - holdSize
            val fromHold = minOf(need, len)
            if (fromHold > 0) {
                out.write(hold, 0, fromHold)
                written += fromHold
                hold.copyInto(hold, 0, fromHold, len)
                len -= fromHold
                need -= fromHold
            }
            if (need > 0) {
                out.write(buf, o, need)
                written += need
                o += need
                c -= need
            }
            if (c > 0) {
                buf.copyInto(hold, len, o, o + c)
                len += c
            }
        }
    }

    fun finish(trim: TailTrim) {
        val keep = if (trim.enabled && bytesOf(hold, len).let { trim.matches(it) }) 0 else len
        if (keep > 0) {
            out.write(hold, 0, keep)
            written += keep
        }
        len = 0
    }

    private fun bytesOf(buf: ByteArray, size: Int): ByteArray =
        if (size == buf.size) buf else buf.copyOf(size)
}
