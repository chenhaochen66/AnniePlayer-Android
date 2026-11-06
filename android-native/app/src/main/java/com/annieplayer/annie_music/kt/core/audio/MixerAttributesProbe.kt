package com.annieplayer.annie_music.kt.core.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * 「这台设备支持哪些 bit-perfect mixer 配置」。
 *
 * ## 为什么查这个
 *
 * DoP 要求 24-bit 帧里的标记位一个都不能变，而 `AudioTrack` 永远经过 mixer。
 * `AUDIO_OUTPUT_FLAG_DIRECT` 从 App 侧够不到（`audio_flags_mask_t` 里没有它，
 * APM 对线性 PCM 又固定 mixer 优先），所以"绕开 mixer"只剩 AAudio 独占和 USB 直连。
 *
 * 但 Android 14 给了第三条：`MIXER_BEHAVIOR_BIT_PERFECT` —— **不混音、不重采样、
 * 不动音量**。不绕开 mixer，而是让 mixer 承诺不改数据。这条对 DoP 同样成立，
 * 而且它是官方机制，不是灰色手段。
 *
 * 能查到对应 format/rate，才谈得上在系统通路上送 DoP。
 */
object MixerAttributesProbe {

    fun report(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return "需要 Android 14 及以上"
        }
        val manager = runCatching {
            context.getSystemService(AudioManager::class.java)
        }.getOrNull() ?: return "拿不到 AudioManager"
        val devices = runCatching {
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        }.getOrDefault(emptyArray())
        if (devices.isEmpty()) return "没有输出设备"
        return collect(manager, devices)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun collect(manager: AudioManager, devices: Array<AudioDeviceInfo>): String {
        val lines = mutableListOf<String>()
        for (device in devices) {
            val attrs = runCatching {
                manager.getSupportedMixerAttributes(device)
            }.getOrDefault(emptyList())
            if (attrs.isEmpty()) continue
            val name = device.productName?.toString()?.takeIf { it.isNotEmpty() }
                ?: deviceTypeName(device.type)
            for (a in attrs) {
                lines += "$name · ${behaviorName(a.mixerBehavior)} · ${formatName(a.format)}"
            }
        }
        return if (lines.isEmpty()) "本机不支持指定 mixer 配置" else lines.joinToString("\n")
    }

    private fun behaviorName(behavior: Int): String =
        if (behavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT) "BIT_PERFECT" else "DEFAULT"

    private fun formatName(format: AudioFormat): String =
        "${encodingName(format.encoding)} ${format.sampleRate}Hz ${channelName(format.channelMask)}"

    private fun encodingName(encoding: Int): String = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> "PCM_8"
        AudioFormat.ENCODING_PCM_16BIT -> "PCM_16"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM_24_PACKED"
        AudioFormat.ENCODING_PCM_32BIT -> "PCM_32"
        AudioFormat.ENCODING_PCM_FLOAT -> "FLOAT"
        else -> "0x${encoding.toString(16)}"
    }

    private fun channelName(mask: Int): String = when (mask) {
        AudioFormat.CHANNEL_OUT_MONO -> "1ch"
        AudioFormat.CHANNEL_OUT_STEREO -> "2ch"
        else -> "mask0x${mask.toString(16)}"
    }

    private fun deviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired_hp"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_hs"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "usb_device"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "usb_headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bt_a2dp"
        else -> "type$type"
    }
}
