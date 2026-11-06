package com.annieplayer.annie_music.kt.core.diagnostics

import android.os.SystemClock

/**
 * 跨模块的运行时事件日志 —— **按时间顺序记"刚才发生了什么"**。
 *
 * 为什么不用 `android.util.Log`：这台 ROM 会按进程配额丢掉 App 日志
 * （实测 `annie-audio` 一条都收不到），出问题时现场完全不可见。
 * 这里自带一个环形缓冲，界面上直接读得到，绕开 logcat 的配额。
 *
 * 与「本机直通能力」那类面板的分工：
 * - 能力页 / [com.annieplayer.annie_music.kt.core.audio.AudioDirectProbe.report]：
 *   **状态快照**，回答"现在是什么样"，读一次就有值；
 * - 本日志：**事件流**，回答"刚才发生过什么"，平时可能是空的。
 *
 * 所以任何模块想留痕，直接一行：
 * ```
 * RuntimeLog.record("audio", "建轨 96000Hz fmt=0x6 生效=96000Hz")
 * ```
 * 不要为了写一条日志去碰 UI，UI 只负责把 [snapshot] 渲染出来。
 *
 * 不做 `tag` 白名单、不做级别过滤：需要分类时按 `tag` 分组展示即可，
 * 写入侧保持零成本。
 */
object RuntimeLog {

    /** 一条事件。`atMs` 是 `elapsedRealtime`，与墙钟无关，用于算相对间隔 */
    data class Entry(
        val tag: String,
        val message: String,
        val atMs: Long,
    )

    /**
     * 容量：够看一轮问题定位，又不至于把内存拖起来。
     * 512 条 × 平均 120 字节 ≈ 60 KB 上限。
     */
    const val CAPACITY = 512

    private val slots = arrayOfNulls<Entry>(CAPACITY)

    /** 累计写入条数（用原子递增，避免多线程抢锁） */
    private val written = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 总开关。排查完关掉可以省下全部开销；
     * 关闭时 [record] 直接返回，不分配、不取时间。
     */
    @Volatile
    var enabled: Boolean = true

    /**
     * 记一条。**可以在任意线程调用**（音频线程、解码线程、主线程）。
     *
     * 实现是无锁的：多条线程同时写不同槽位互不影响；同一槽位被覆盖时
     * 引用赋值本身是原子的（64 位），最坏情况是读到上一条或下一条，
     * 对"看日志"这个用途完全够用 —— 不值得为此上一把锁。
     */
    fun record(tag: String, message: String) {
        if (!enabled) return
        val index = written.getAndIncrement()
        // `getAndIncrement` 会绕回负数，必须用 floorMod 取正余数
        slots[Math.floorMod(index, CAPACITY)] =
            Entry(tag, message, SystemClock.elapsedRealtime())
    }

    /**
     * 按**时间倒序**（最新在前）取快照。
     *
     * 为什么倒序：出问题时要先看最后发生了什么，往上翻才是历史。
     */
    fun snapshot(): List<Entry> {
        val end = written.get()
        val start = (end - CAPACITY).coerceAtLeast(0)
        if (end <= start) return emptyList()
        val out = ArrayList<Entry>(end - start)
        for (index in start until end) {
            slots[Math.floorMod(index, CAPACITY)]?.let { out.add(it) }
        }
        out.reverse()
        return out
    }

    /** 清空（界面上的「清空」按钮用） */
    fun clear() {
        slots.fill(null)
        written.set(0)
    }
}
