package com.annieplayer.annie_music.kt.core.audio

import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.annieplayer.annie_music.kt.R

/**
 * 媒体会话服务（1:1 移植 Dart `audio_service` 的 `AudioPlayerHandler` 角色）。
 *
 * 职责：通知栏 / 锁屏 / 蓝牙与耳机线控（play/pause/seek/skipNext/skipPrevious）。
 *
 * ## 与 `MediaPlayerBackend` 的所有权关系
 * 播放器与 `MediaSession` 由 [MediaPlayerBackend.setup] 创建并放入进程级 [AnnieAudioHolder]；
 * 本服务只是它们的服务宿主（与 UI **同进程**，不设 `android:process`，保证低延迟，见
 * `KOTLIN_PLAYBACK_PLAN.md` §5.1）。因此：
 * - `onGetSession` 直接返回 holder 里的会话；
 * - `onDestroy` **不释放**播放器/会话（所有权在 backend），否则 UI 仍在前台时会切歌/续播失效。
 *
 * 通知渠道 id 与 Dart 的 `androidNotificationChannelId`
 * (`com.annieplayer.annie_music.channel.audio`) 保持一致；名称复用 `@string/app_name`。
 */
@androidx.annotation.OptIn(UnstableApi::class)
class MusicSessionService : MediaSessionService() {
    override fun onCreate() {
        super.onCreate()
        // Media3 会自动把 player 状态（播放/暂停/进度/标题/封面）映射到通知与锁屏
        val provider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(CHANNEL_ID)
            .setChannelName(R.string.app_name)
            .build()
        setMediaNotificationProvider(provider)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        AnnieAudioHolder.session

    /**
     * 用户从最近任务划掉界面：未在播放则结束服务（停止通知）；
     * 正在播放则保留（后台继续播放 / 自动切歌，对齐 audio_service 行为）。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player: ExoPlayer? = AnnieAudioHolder.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        // 不释放共享的 player / MediaSession：它们由 MediaPlayerBackend 持有并复用
        // 但需复位 backend 的前台服务启动标志，否则后续播放不会再拉起服务/通知。
        AnnieAudioHolder.onServiceStopped?.invoke()
        super.onDestroy()
    }

    companion object {
        /** 与 Dart `AudioServiceConfig.androidNotificationChannelId` 一致 */
        const val CHANNEL_ID: String = "com.annieplayer.annie_music.channel.audio"
    }
}
