package com.annieplayer.annie_music.kt.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * 音频输出控制器：采样率 / 位深直通 + 输出设备选择的 **groundwork**（`KOTLIN_PLAYBACK_PLAN.md`
 * 阶段 0/3）。
 *
 * ## 能力与 API 级别（务必区分）
 * | 能力 | API 要求 | 说明 |
 * |---|---|---|
 * | 枚举输出设备 / 查询原生采样率 | API 23+ (M) | `getDevices` / `getProperty`，minSdk 24 即可用 |
 * | 24-bit PCM（`ENCODING_PCM_24BIT_PACKED`） | **API 31+ (S)** | 低于 31 只能用 16-bit / float |
 * | 系统级 bit-perfect 直通（`setPreferredMixerAttributes` + `MIXER_BEHAVIOR_BIT_PERFECT`） | **API 34+ (UpsideDownCake)** | 目标机 Android 16 / API 36 可用；低于 34 一律降级 |
 * | 指定播放输出设备（`ExoPlayer.setPreferredAudioDevice`） | 由 Media3 提供 | 见 [applyOutputDeviceToPlayer] |
 *
 * 重要：**即使 API 用对，OEM 的 HAL 仍可能偷偷重采样**——只有外接 DAC 屏显 / `dumpsys
 * media.audio_flinger` 是铁证（计划 §9）。本类只负责「把请求下发到位」，不保证最终直通。
 */
@androidx.annotation.OptIn(UnstableApi::class)
class AudioOutputController(context: Context) {

    private val _audioManager: AudioManager = requireNotNull(
        context.applicationContext.getSystemService(AudioManager::class.java),
    ) { "AudioManager 不可用" }

    /** 最近一次选择的输出设备 id；-1 表示未显式指定，由系统路由（此时按优先级自动挑） */
    private var _preferredDeviceId: Int = NO_DEVICE

    /** 设备插拔 / 手动切换后通知宿主刷新状态（backend 据此重算 route 与徽章） */
    var onDevicesChanged: (() -> Unit)? = null

    private val deviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            onDevicesChanged?.invoke()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            onDevicesChanged?.invoke()
        }
    }

    init {
        _audioManager.registerAudioDeviceCallback(deviceCallback, null)
    }

    /** 输出设备描述（采样率 / 通道 / 编码为该设备上报的能力） */
    data class DeviceInfo(
        val id: Int,
        val type: Int,
        val name: String,
        val sampleRates: List<Int>,
        val channelCounts: List<Int>,
        val encodings: List<Int>,
        val isUsb: Boolean,
    )

    /** 系统级 bit-perfect 直通（API 34+）是否可用 */
    val isBitPerfectPassthroughSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /** 24-bit PCM 是否可用（API 31+） */
    val is24BitPcmSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** 设备原生输出采样率（Hz），拿不到时回落 48000（对齐计划 §8 的探针） */
    fun nativeSampleRate(): Int =
        _audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000

    /** 设备原生输出缓冲帧数，拿不到时回落 256 */
    fun nativeFramesPerBuffer(): Int =
        _audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 256

    fun listOutputDevices(): List<DeviceInfo> =
        _audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            // SCO 是通话音路、EARPIECE 是听筒，媒体播放不该出现在选择列表里
            .filter {
                it.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO &&
                    it.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            }
            .map { d -> _toDeviceInfo(d) }

    /**
     * 选择输出设备。返回该 id 是否存在；不存在则不改动当前选择。
     * 真正生效需再调用 [applyOutputDeviceToPlayer]（或 [applyBitPerfectPassthrough]）。
     */
    fun selectOutputDevice(deviceId: Int): Boolean {
        val exists = _audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.id == deviceId }
        if (exists) {
            _preferredDeviceId = deviceId
            onDevicesChanged?.invoke()
        }
        return exists
    }

    /** 当前选择（未显式选择时按 USB -> 有线 -> 内置扬声器 的优先级自动挑） */
    fun currentOutputDevice(): DeviceInfo? = _findOutputDevice()?.let { _toDeviceInfo(it) }

    /** 当前输出通路（徽章据此决定「能不能宣称直通」） */
    fun currentRoute(): AudioRoute = _routeOf(_findOutputDevice())

    /** 当前输出设备名（徽章详情用；取不到为空串） */
    fun currentDeviceName(): String =
        _findOutputDevice()?.productName?.toString().orEmpty()

    /** 系统级 bit-perfect 是否值得尝试（API 34+ 且外接 USB） */
    fun canAttemptBitPerfect(): Boolean =
        isBitPerfectPassthroughSupported && currentRoute() == AudioRoute.usb

    /** 把所选设备作为 ExoPlayer 的首选输出设备（Media3 负责真正路由） */
    fun applyOutputDeviceToPlayer(player: ExoPlayer): Boolean {
        val device = _findOutputDevice() ?: return false
        player.setPreferredAudioDevice(device)
        return true
    }

    /**
     * bit-perfect 直通（**API 34+**）：按 [sampleRate] / [bitDepth] 组装 `AudioFormat`，
     * 以 `MIXER_BEHAVIOR_BIT_PERFECT` 调用 `AudioManager.setPreferredMixerAttributes`。
     *
     * 低于 API 34 直接返回 false（不做任何事）；24-bit 在 API 31+ 才使用
     * `ENCODING_PCM_24BIT_PACKED`，否则降级 16-bit。返回值仅表示「请求是否被系统接受」。
     */
    fun applyBitPerfectPassthrough(sampleRate: Int, bitDepth: Int): Boolean {
        if (!isBitPerfectPassthroughSupported) return false
        val device = _findOutputDevice() ?: return false
        return _applyBitPerfect(sampleRate, bitDepth, device)
    }

    /** 清除本 App 之前下发的 bit-perfect mixer 属性（API 34+） */
    fun clearBitPerfectPassthrough(): Boolean {
        if (!isBitPerfectPassthroughSupported) return false
        val device = _findOutputDevice() ?: return false
        return _clearBitPerfect(device)
    }

    /**
     * 调试快照（等价计划 §8 的 `dumpsys` 探针的代码侧自证）：原生采样率 / 直通支持 / 设备清单。
     */
    fun snapshot(): Map<String, Any?> = mapOf(
        "nativeSampleRate" to nativeSampleRate(),
        "nativeFramesPerBuffer" to nativeFramesPerBuffer(),
        "bitPerfectSupported(API34+)" to isBitPerfectPassthroughSupported,
        "pcm24BitSupported(API31+)" to is24BitPcmSupported,
        "preferredDeviceId" to _preferredDeviceId,
        "devices" to listOutputDevices().map { d ->
            mapOf(
                "id" to d.id,
                "name" to d.name,
                "type" to d.type,
                "isUsb" to d.isUsb,
                "sampleRates" to d.sampleRates,
                "channelCounts" to d.channelCounts,
                "encodings" to d.encodings,
            )
        },
    )

    // ── 内部实现 ──

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun _applyBitPerfect(sampleRate: Int, bitDepth: Int, device: AudioDeviceInfo): Boolean {
        val encoding = when (bitDepth) {
            24 -> if (is24BitPcmSupported) {
                AudioFormat.ENCODING_PCM_24BIT_PACKED
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }
            32 -> AudioFormat.ENCODING_PCM_FLOAT
            else -> AudioFormat.ENCODING_PCM_16BIT
        }
        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val mixer = AudioMixerAttributes.Builder(format)
            .setMixerBehavior(AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT)
            .build()
        return try {
            _audioManager.setPreferredMixerAttributes(_mediaAttributes(), device, mixer)
        } catch (_: Throwable) {
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun _clearBitPerfect(device: AudioDeviceInfo): Boolean = try {
        _audioManager.clearPreferredMixerAttributes(_mediaAttributes(), device)
    } catch (_: Throwable) {
        false
    }

    private fun _mediaAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    /** 显式选择优先；未选择时按 USB -> 有线 -> 蓝牙 -> 内置扬声器 挑（对齐系统媒体路由习惯） */
    private fun _findOutputDevice(): AudioDeviceInfo? {
        val devices = _audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        if (_preferredDeviceId != NO_DEVICE) {
            return devices.firstOrNull { it.id == _preferredDeviceId }
        }
        return devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        } ?: devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
        } ?: devices.firstOrNull {
            // 只认 A2DP（媒体）；SCO/LE 通话链路不选
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        } ?: devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
        } ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    }

    private fun _toDeviceInfo(d: AudioDeviceInfo): DeviceInfo = DeviceInfo(
        id = d.id,
        type = d.type,
        name = d.productName.toString(),
        sampleRates = d.sampleRates.toList(),
        channelCounts = d.channelCounts.toList(),
        encodings = d.encodings.toList(),
        isUsb = d.type == AudioDeviceInfo.TYPE_USB_DEVICE || d.type == AudioDeviceInfo.TYPE_USB_HEADSET,
    )

    private fun _routeOf(d: AudioDeviceInfo?): AudioRoute = when (d?.type) {
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET -> AudioRoute.usb
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> AudioRoute.wired
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> AudioRoute.bluetooth
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRoute.speaker
        else -> AudioRoute.other
    }

    private companion object {
        const val NO_DEVICE: Int = -1
    }
}
