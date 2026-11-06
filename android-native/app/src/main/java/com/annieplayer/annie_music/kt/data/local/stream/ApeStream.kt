package com.annieplayer.annie_music.kt.data.local.stream

import com.annieplayer.annie_music.kt.data.local.tag.TagBag
import com.annieplayer.annie_music.kt.data.local.tag.ascii

/**
 * APEv2 系（`.ape` / `.wv` / `.mpc` / `.tta`）—— **登记但不流式注入**。
 *
 * 登记它们的价值在 [extension]：下载链路原先只认 flac/mp3/m4a，遇到这几种会把内容写成
 * 请求档位的扩展名。现在扩展名由这里统一给，格式判断不会退化成猜。
 *
 * ## 为什么不做流式
 * 这几种的标签是**文件末尾的 APEv2**（`items + footer`，footer 里带长度）。
 * 「加标签」= 截尾 + 追加，本来是最省的；但流式写入时**源里可能已经有一条 APEv2**，
 * 而它的起点要靠末尾 32 字节的 footer 反推 —— 读到那一刻，前面那些字节早就写进目标了，
 * 退不回来。
 *
 * 能想到的绕法都不划算：
 * - 整段 hold 到结束再判断 → 等于把整首歌缓存在内存里；
 * - 直接追加新的（读取方会取最后一条 = 我们的）→ 旧标签字节会被解码器当成音频尾巴，
 *   `.wv` / `.mpc` 会直接报块错误。
 *
 * 所以这里明确放弃，交给「先落盘、再用 `AnnieTag` 打标」——`AnnieTag` 对这几种是
 * 截尾追加，也是零音频搬运，只是多一趟落盘。真实下载链路里这几种本来也不出现，
 * 为了它们引入「可能写出坏文件」的风险不值。
 */
internal object ApeStream : StreamCodec {

    override val id: String = "ape"

    private val MAGICS = listOf(
        "MAC " to ".ape",   // Monkey's Audio
        "wvpk" to ".wv",    // WavPack
        "MPCK" to ".mpc",   // Musepack SV8
        "MP+" to ".mpc",    // Musepack SV7
        "TTA1" to ".tta",   // True Audio
    )

    override fun extension(head: ByteArray): String =
        MAGICS.firstOrNull { matchesMagic(head, it.first) }?.second ?: ".ape"

    override fun matches(head: ByteArray): Boolean =
        MAGICS.any { matchesMagic(head, it.first) }

    private fun matchesMagic(head: ByteArray, magic: String): Boolean =
        head.size >= magic.length && head.ascii(0, magic.length) == magic

    override fun plan(head: ByteArray, contentLength: Long, bag: TagBag): StreamPlan? = null
}
