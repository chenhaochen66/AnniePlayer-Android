package com.annieplayer.annie_music.kt.core.state

import androidx.lifecycle.ViewModel
import com.annieplayer.annie_music.kt.data.model.DownloadStatus
import com.annieplayer.annie_music.kt.data.model.DownloadTask
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 下载状态（1:1 移植 Flutter `core/providers/` 下的 `download_provider.dart`，
 * 对齐 RN 版 store/useDownloadStore.ts）。
 *
 * 状态为 `songId -> DownloadTask` 的有序 Map；所有迁移保持 Dart 的
 * 「不存在则忽略」「同 id 不重复新增」「completed 过滤」等语义。
 */

/** 下载状态 ViewModel（对齐 Dart `DownloadStore`；Riverpod Notifier -> Kotlin ViewModel + StateFlow） */
class DownloadViewModel : ViewModel() {

    private val _state = MutableStateFlow<Map<String, DownloadTask>>(emptyMap())

    /** 只读状态流 */
    val state: StateFlow<Map<String, DownloadTask>> = _state.asStateFlow()

    /** 新增任务；同 songId 已存在则不重复（对齐 Dart） */
    fun addTask(song: Song, quality: Quality) {
        // 允许同一首歌被反复下载：不再"已有任务就 return"，
        // 重复点下载直接复用同一行（key 仍是 song.id），行状态重置为 downloading。
        _state.value = _state.value + (
            song.id to DownloadTask(
                songId = song.id,
                title = song.title,
                artist = song.artist,
                cover = song.cover,
                quality = quality,
            )
            )
    }

    /** 更新进度；progress < 100 置 downloading，否则 completed（对齐 Dart） */
    fun updateProgress(songId: String, progress: Double) {
        val task = _state.value[songId] ?: return
        _state.value = _state.value + (
            songId to task.copyWith(
                progress = progress,
                status = if (progress < 100) DownloadStatus.downloading else DownloadStatus.completed,
            )
            )
    }

    /** 完成任务（progress=100 + completed + filePath；filePath 为 null 时保留旧值，对齐 Dart） */
    fun completeTask(songId: String, filePath: String? = null) {
        val task = _state.value[songId] ?: return
        _state.value = _state.value + (
            songId to task.copyWith(
                progress = 100.0,
                status = DownloadStatus.completed,
                filePath = filePath,
            )
            )
    }

    fun failTask(songId: String) {
        val task = _state.value[songId] ?: return
        // 已暂停 / 已取消 / 已完成的任务不能被「失败」覆盖：下载协程在 pause/cancel 时同样会
        // 抛异常，调用方的 catch 会误调 failTask，导致「已暂停」被改成「失败」。
        if (task.status == DownloadStatus.paused ||
            task.status == DownloadStatus.cancelled ||
            task.status == DownloadStatus.completed
        ) {
            return
        }
        _state.value = _state.value + (songId to task.copyWith(status = DownloadStatus.error))
    }

    fun removeTask(songId: String) {
        val newState = _state.value.toMutableMap().apply { remove(songId) }
        _state.value = newState
    }

    /** 仅保留非 completed 的任务（顺序与 Dart `Map.fromEntries` 一致） */
    fun clearCompleted() {
        _state.value = _state.value.filterValues { it.status != DownloadStatus.completed }
    }

    /** 暂停：仅 downloading / pending 可暂停（对齐 Dart） */
    fun pauseTask(songId: String) {
        val task = _state.value[songId] ?: return
        if (task.status != DownloadStatus.downloading && task.status != DownloadStatus.pending) return
        _state.value = _state.value + (songId to task.copyWith(status = DownloadStatus.paused))
    }

    fun cancelTask(songId: String) {
        val task = _state.value[songId] ?: return
        _state.value = _state.value + (songId to task.copyWith(status = DownloadStatus.cancelled))
    }
}
