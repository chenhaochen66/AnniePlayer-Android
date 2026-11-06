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

    private val _context: Context = context.applicationContext

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

    /**
     * 设备原生输出采样率（Hz）= 系统 mixer 的率，也就是**普通 track 的最终输出率**。
     *
     * 拿不到就返 **0（未知）**，绝不回落一个猜的值 —— 界面宁可显示"未知"，
     * 也不能把 48000 这种数字当成事实报出去（那正是"以为改了其实没改"的来源）。
     */
    fun nativeSampleRate(): Int =
        _audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0

    /** 设备原生输出缓冲帧数；拿不到返回 0（未知） */
    fun nativeFramesPerBuffer(): Int =
        _audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0

    /**
     * 当前输出设备声明的采样率（`AudioDeviceInfo.sampleRates`）。
     *
     * 注意：**这只是"声明"** —— 列进下拉只说明"设备说支持"，不代表输出会是这个率
     * （实测：PCM track 声明 96k 仍被 mixer 拉到 primary）。
     * `getAudioProfiles()` 更细（按编码分组），但当前 API 访问路径受限，暂用这个；
     * 拿不到就返回空，调用方自己退回"无档位"而不是猜。
     */
    fun devicePcmRates(): List<Int> {
        val device = _findOutputDevice() ?: return emptyList()
        return runCatching { device.sampleRates.toList().distinct().sorted() }
            .getOrDefault(emptyList())
    }

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

    /**
     * 当前输出通路（徽章据此决定「能不能宣称直通」）。
     *
     * **不能只看 `getDevices(OUTPUTS)`** —— 这一步是实测踩到的坑：
     * 本机（ColorOS）的枚举列表里**没有 A2DP 设备**，于是 `_findOutputDevice()`
     * 落到扬声器，`currentRoute()` 恒为 `speaker`，而音频实际走的是 a2dp 线程
     * （`dumpsys media.audio_flinger` 显示 `AUDIO_DEVICE_OUT_BLUETOOTH_A2DP`）。
     *
     * 后果是**整条蓝牙专属逻辑静默失效**：按源率不被排除、位深不跟 codec 走
     * （于是往整数链路交 float）、直通不被拒、提示不说"蓝牙"。
     * 这类"判据拿不到事实"的 bug 最难查，因为每个分支单独看都是对的。
     *
     * `isBluetoothA2dpOn()` 虽然 deprecated（官方建议改用 `getDevices`），但它问的
     * 正是"A2DP 现在是不是媒体路由"，恰恰是枚举列表缺项时要的那个答案。
     */
    fun currentRoute(): AudioRoute {
        val auto = _routeOf(_findOutputDevice())
        if (auto == AudioRoute.bluetooth) return auto
        val a2dpOn = runCatching { _audioManager.isBluetoothA2dpOn() }.getOrDefault(false)
        return if (a2dpOn) AudioRoute.bluetooth else auto
    }

    /** 当前输出设备名（徽章详情用；取不到为空串） */
    fun currentDeviceName(): String =
        _findOutputDevice()?.productName?.toString().orEmpty()

    /**
     * 本应用是否持有 `MODIFY_AUDIO_ROUTING`。
     *
     * `setPreferredMixerAttributes` / `getPreferredMixerAttributes` 都在这个权限后面，
     * 而它的保护级是 **`signature|privileged|role`**（本机实测）：普通安装拿不到。
     * 少数魔改的国砖播放器 ROM 会把它放开，或用 priv-app 方式装进 `/system/priv-app`。
     *
     * 为什么要先问再调用：没有权限时 `setPreferredMixerAttributes` 会抛
     * `SecurityException`，而现有实现是 `catch (_: Throwable) { false }` ——
     * 结果是"每次都试、每次都失败、每次都静默"，策略刷新一多就成了噪音。
     * 先问一次，没有权限就**连请求都不发**，梯子直接跳到下一级。
     */
    fun hasAudioRoutingPermission(): Boolean =
        _context.checkSelfPermission(PERM_MODIFY_AUDIO_ROUTING) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * 系统级 bit-perfect 是否值得尝试（API 34+ + 外接通路 + 有路由权限）。
     *
     * 排除蓝牙与内置扬声器：前者链路参数由 codec 决定，后者 profile 通常只有
     * 48000/16bit 一档 —— 两者请求必然失败，而每次失败都可能让部分 ROM 走一遍
     * 「重配输出」的短暂静音。
     */
    fun canAttemptBitPerfect(): Boolean =
        isBitPerfectPassthroughSupported &&
            hasAudioRoutingPermission() &&
            currentRoute() in setOf(AudioRoute.usb, AudioRoute.wired)

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
        // 蓝牙/扬声器不做无谓尝试（见 [canAttemptBitPerfect]）
        if (!canAttemptBitPerfect()) return false
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
        // 厂商私有类型不做特殊处理（本机 3.5mm 上报 0x20000，认不出来就按 other）：
        // 路由只描述"系统认到的设备"，能不能独占由 sink 侧自己试，不在路由上猜。
        else -> AudioRoute.other
    }

    private companion object {
        const val NO_DEVICE: Int = -1

        /** `signature|privileged|role` 级；清单里声明了也授不下来，但国砖 ROM / priv-app 可以 */
        const val PERM_MODIFY_AUDIO_ROUTING = "android.permission.MODIFY_AUDIO_ROUTING"

        /** 哪些 `AudioFormat` 编码算 PCM（用来从 audioProfiles 里挑 PCM 档位） */
        val PCM_ENCODINGS: Set<Int> = setOf(
            AudioFormat.ENCODING_PCM_16BIT,
            AudioFormat.ENCODING_PCM_24BIT_PACKED,
            AudioFormat.ENCODING_PCM_32BIT,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
    }
}
