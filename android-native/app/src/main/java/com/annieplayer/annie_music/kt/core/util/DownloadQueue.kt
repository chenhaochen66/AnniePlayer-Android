package com.annieplayer.annie_music.kt.core.util

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 下载串行队列（1:1 移植 Flutter `core/utils/download_queue.dart`，对齐 RN 版 utils/downloadQueue.ts）。
 */

/** 队列任务类型（对齐 Dart `typedef QueueTask = Future<void> Function()`） */
typealias QueueTask = suspend () -> Unit

/** songId -> 待执行任务（取消时移除；执行时按 id 取出最新任务） */
private val _taskMap = ConcurrentHashMap<String, QueueTask>()

/** 串行 promise 链尾（保证任务严格按入队顺序逐个执行） */
private var _currentPromise: CompletableDeferred<Unit> =
    CompletableDeferred<Unit>().apply { complete(Unit) }

/** 保护链尾读改写，确保链式顺序与入队顺序一致 */
private val _promiseLock = Any()

/** 执行队列的协程作用域（等价 Dart 的全局 Future 链调度） */
private val _queueScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** 将下载任务加入串行队列（同一时间只执行一个） */
fun enqueueDownload(songId: String, task: QueueTask) {
    _taskMap[songId] = task
    synchronized(_promiseLock) {
        val prev = _currentPromise
        val next = CompletableDeferred<Unit>()
        _currentPromise = next
        _queueScope.launch {
            prev.join()
            val fn = _taskMap.remove(songId)
            if (fn != null) {
                try {
                    fn()
                } catch (_: Throwable) {
                    // 对齐 Dart：任务异常由 onError 吞掉，不阻断后续队列
                }
            }
            next.complete(Unit)
        }
    }
}

/** 取消待执行任务 */
fun cancelPending(songId: String) {
    _taskMap.remove(songId)
}
