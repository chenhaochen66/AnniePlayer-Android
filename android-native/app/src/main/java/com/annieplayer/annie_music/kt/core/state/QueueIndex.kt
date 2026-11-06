package com.annieplayer.annie_music.kt.core.state

/**
 * 队列「重排 / 摘项」之后，当前播放项的**新下标**。
 *
 * 为什么单独一个文件、而且 App 层（`PlayerViewModel`）与引擎层（`MediaPlayerBackend`）
 * **都必须调这两个函数**：同一个公式在两处各写一遍，只要有一处写错，App 的 `currentIndex`
 * 与引擎的 `_currentAbsIndex` 就会错位 —— 表现是「队列抽屉里的当前曲高亮错位」或者
 * 「下一首跳到奇怪的地方」。这类不同步极难靠现象定位，所以让公式只有一份，并用单测钉住。
 */

/**
 * 拖拽：把第 [from] 项挪到第 [to] 后，当前曲的新下标。
 *
 * 三种情况：
 * - 被移动的正是当前曲 → 新下标就是 [to]；
 * - 移动是从当前曲**下方**往上跨过它（`from < current && to >= current`）→ 当前曲被顶下一格；
 * - 从当前曲**上方**往下跨过它（`from > current && to <= current`）→ 当前曲被顶上一格；
 * - 其余情况（移动完全发生在当前曲同一侧）→ 不动。
 */
internal fun indexAfterMove(current: Int, from: Int, to: Int): Int = when {
    from == current -> to
    from < current && to >= current -> current - 1
    from > current && to <= current -> current + 1
    else -> current
}

/**
 * 摘项：摘掉第 [removed] 项后（剩余 [remainingSize] 项），当前曲的新下标。
 *
 * - 摘的是当前曲前面的项 → 指针前移一格；
 * - 摘的是当前曲后面的项 → 不动；
 * - 摘的正是当前曲（`removed == current`）→ 保持原值再夹到有效范围。
 *   **调用方必须先切歌**；这条分支只是兜底，保证下标永远不越界。
 */
internal fun indexAfterRemoval(current: Int, removed: Int, remainingSize: Int): Int {
    val raw = if (removed < current) current - 1 else current
    return raw.coerceIn(0, (remainingSize - 1).coerceAtLeast(0))
}
