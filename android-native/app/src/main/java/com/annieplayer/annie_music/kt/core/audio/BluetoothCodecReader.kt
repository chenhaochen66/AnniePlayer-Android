package com.annieplayer.annie_music.kt.core.audio

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 蓝牙当前实际正在使用的编码信息 */
data class BluetoothCodecInfo(
    val deviceName: String,
    val codecName: String,
    val sampleRate: Int,
    val bitsPerSample: Int,
    /** LDAC 码率（bit/s）；其它 codec 为 0 */
    val bitrate: Int,
)

/**
 * 蓝牙 codec 读取。
 *
 * `BluetoothA2dp.getCodecStatus()` / `BluetoothCodecStatus` / `BluetoothCodecConfig` 都是
 * **`@SystemApi`**（编译期不可见），所以只能反射调用；失败（被 hiddenapi 限制、OEM 不实现、
 * 没权限）时静默降级为 null，不影响播放。
 *
 * 这是**唯一**能拿到「蓝牙此刻真在跑什么」的途径：`AudioDeviceInfo.sampleRates` 只给
 * codec 能力集合，这里给的是协商结果（LDAC 990/660/330 kbps 也能读出来）。
 */
object BluetoothCodec {

    private var appContext: Context? = null
    private var adapter: BluetoothAdapter? = null
    private var a2dp: BluetoothA2dp? = null

    private val _info = MutableStateFlow<BluetoothCodecInfo?>(null)
    val info: StateFlow<BluetoothCodecInfo?> = _info.asStateFlow()

    /** codec 信息变化后通知宿主刷新播放页状态 */
    var onInfoChanged: (() -> Unit)? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        connectProxy()
    }

    fun hasPermission(): Boolean {
        val ctx = appContext ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** 权限刚授予时调用，重新建立 A2DP 代理 */
    fun onPermissionGranted() {
        connectProxy()
    }

    @SuppressLint("MissingPermission")
    private fun connectProxy() {
        val ctx = appContext ?: return
        val btAdapter = adapter ?: return
        if (!hasPermission()) return
        if (a2dp != null) return
        try {
            btAdapter.getProfileProxy(
                ctx,
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        if (profile == BluetoothProfile.A2DP) {
                            a2dp = proxy as? BluetoothA2dp
                            refresh()
                        }
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        if (profile == BluetoothProfile.A2DP) a2dp = null
                    }
                },
                BluetoothProfile.A2DP,
            )
        } catch (_: Throwable) {
        }
    }

    @SuppressLint("MissingPermission")
    fun refresh() {
        _info.value = readInfo()
        onInfoChanged?.invoke()
    }

    @SuppressLint("MissingPermission")
    private fun readInfo(): BluetoothCodecInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !hasPermission()) return null
        val proxy = a2dp
        if (proxy == null) {
            connectProxy()
            return null
        }
        return try {
            val device = proxy.connectedDevices.firstOrNull() ?: return null
            readCodec(proxy, device)
        } catch (_: Throwable) {
            null
        }
    }

    private fun readCodec(proxy: BluetoothA2dp, device: BluetoothDevice): BluetoothCodecInfo? = try {
        val status = proxy.javaClass
            .getMethod("getCodecStatus", BluetoothDevice::class.java)
            .invoke(proxy, device) ?: return null
        val config = status.javaClass.getMethod("getCodecConfig").invoke(status) ?: return null
        val configClass = config.javaClass
        val codecType = configClass.getMethod("getCodecType").invoke(config) as? Int ?: return null
        // 这两个 getter 返回的是 BluetoothCodecConfig 的位掩码常量，不是 Hz / bit
        val sampleRateMask = configClass.getMethod("getSampleRate").invoke(config) as? Int ?: 0
        val bitsMask = configClass.getMethod("getBitsPerSample").invoke(config) as? Int ?: 0
        val specific1 = configClass.getMethod("getCodecSpecific1").invoke(config) as? Long ?: 0L
        BluetoothCodecInfo(
            deviceName = device.name ?: "",
            codecName = codecNameOf(codecType),
            sampleRate = sampleRateOf(sampleRateMask),
            bitsPerSample = bitsOf(bitsMask),
            // 只有 LDAC 的 codecSpecific1 才是码率
            bitrate = if (codecType == CODEC_TYPE_LDAC && specific1 in 1..5_000_000) {
                specific1.toInt()
            } else {
                0
            },
        )
    } catch (_: Throwable) {
        null
    }

    /** `BluetoothCodecConfig.SAMPLE_RATE_*` 位掩码 → Hz */
    private fun sampleRateOf(mask: Int): Int = when (mask) {
        0x1 -> 44_100
        0x2 -> 48_000
        0x4 -> 88_200
        0x8 -> 96_000
        0x10 -> 176_400
        0x20 -> 192_000
        else -> 0
    }

    /** `BluetoothCodecConfig.BITS_PER_SAMPLE_*` 位掩码 → bit */
    private fun bitsOf(mask: Int): Int = when (mask) {
        0x1 -> 16
        0x2 -> 24
        0x4 -> 32
        else -> 0
    }

    /** codec 类型常量（含 ColorOS 把 aptX Adaptive 映射成 16 的情况） */
    private fun codecNameOf(type: Int): String = when (type) {
        0 -> "SBC"
        1 -> "AAC"
        2 -> "aptX"
        3 -> "aptX HD"
        4 -> "LDAC"
        5 -> "aptX Adaptive"
        6 -> "Opus"
        16 -> "aptX Adaptive"
        17 -> "aptX TWS+"
        19 -> "LHDC"
        else -> "未知"
    }

    private const val CODEC_TYPE_LDAC = 4
}
