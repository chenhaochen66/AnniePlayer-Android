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
    synchronized(_promiseLock) {
        // 与执行协程的 _taskMap.remove(songId) 同锁，避免「先 remove 后写入」竞态
        _taskMap[songId] = task
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

/**
 * 批量下载中「单首取消」的标记。
 *
 * 批量下载是**一个批次一个队列任务**（key 是 batchId），单曲 id 在 `_taskMap` 里查不到，
 * 所以 `cancelPending(songId)` 曾经是空操作 —— 表现为「下载列表里删掉那一首，它照样下完」。
 * 这里按曲 id 记标记，下载循环每首开始前取走标记并跳过。
 */
private val _cancelledSongs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

/** 取消待执行任务 */
fun cancelPending(songId: String) {
    _taskMap.remove(songId)
    _cancelledSongs.add(songId)
}

/** 取走并清除该曲的取消标记；返回 true 表示这一首应被跳过 */
fun consumeCancelledSong(songId: String): Boolean = _cancelledSongs.remove(songId)
