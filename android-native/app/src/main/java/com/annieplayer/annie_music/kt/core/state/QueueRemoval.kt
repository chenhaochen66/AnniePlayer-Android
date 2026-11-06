package com.annieplayer.annie_music.kt.core.state

/**
 * 「从播放队列里摘掉若干首歌」的**纯计算**部分。
 *
 * 为什么抽出来单独一个文件：这段逻辑全是下标运算（谁先删、删完当前项指针落在哪），
 * 写在 `PlayerViewModel.removeSongsFromQueue` 里就只能上真机看现象 —— 而它恰好错过一次
 * （用户反馈「删了文件，播放队列不跟着更新」）。放到这里就可以用纯 JVM 单测钉住
 * 「顺序 → 结果」的对应关系。
 *
 * 顺序是正确性的一部分：**必须先摘「不是当前播放」的项，最后才处理当前曲**。
 * 反过来做会踩两个坑：
 * 1. `next()` 可能正好落到另一首待删的歌上（于是引擎又指到一个即将被删的位置）；
 * 2. 摘掉当前项后 `currentIndex` 已经位移，再用旧下标删就会删错歌。
 */
internal data class QueueRemovalPlan(
    /**
     * 先摘这些下标，**降序** —— 从后往前删，前面的下标才不会位移。
     *
     * 不含当前曲：它要等别的项都摘完、切歌之后才摘。
     */
    val removeFirst: List<Int> = emptyList(),
    /** 当前曲是否也在待删名单里（摘完 [removeFirst] 之后：先切歌，再摘） */
    val currentInIds: Boolean = false,
    /** 整个队列都要摘（App 层清空 + 停播，不走逐项删除） */
    val clearAll: Boolean = false,
) {
    /** 会被摘掉的条数（[clearAll] 时用队列长度，不用这个值） */
    val hitCount: Int get() = removeFirst.size + if (currentInIds) 1 else 0

    /** 什么都没命中（调用方据此提前返回） */
    val none: Boolean get() = !clearAll && removeFirst.isEmpty() && !currentInIds
}

/**
 * 算出摘除计划。
 *
 * @param queueIds 当前队列的歌曲 id（顺序 = 播放顺序）
 * @param removeIds 要摘掉的 id 集合（空串会被忽略）
 * @param currentIndex 当前播放位置
 */
internal fun planQueueRemoval(
    queueIds: List<String>,
    removeIds: Set<String>,
    currentIndex: Int,
): QueueRemovalPlan {
    if (queueIds.isEmpty() || removeIds.isEmpty()) return QueueRemovalPlan()
    val hits = queueIds.withIndex()
        .filter { it.value.isNotEmpty() && it.value in removeIds }
        .map { it.index }
    if (hits.isEmpty()) return QueueRemovalPlan()
    // 整队都要摘：逐项删最后还是要清空，直接走 clearAll 更省事也更好读
    if (hits.size == queueIds.size) return QueueRemovalPlan(clearAll = true)

    val currentId = queueIds.getOrNull(currentIndex)
    val currentInIds = currentId != null && currentId.isNotEmpty() && currentId in removeIds
    val others = hits.filter { it != currentIndex }.sortedDescending()
    return QueueRemovalPlan(removeFirst = others, currentInIds = currentInIds)
}
