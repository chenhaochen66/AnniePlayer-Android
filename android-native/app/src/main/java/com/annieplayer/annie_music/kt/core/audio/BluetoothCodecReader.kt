package com.annieplayer.annie_music.kt.core.audio

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothCodecConfig
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.annieplayer.annie_music.kt.core.diagnostics.RuntimeLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 蓝牙当前实际正在使用的编码信息 */
data class BluetoothCodecInfo(
    val deviceName: String,
    val codecName: String,
    val sampleRate: Int,
    val bitsPerSample: Int,
    /** codec 码率（bit/s）；0 = 该 codec 没把 bps 暴露在这个字段里（LHDC 就是） */
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
    private var codecReceiver: BroadcastReceiver? = null

    private val _info = MutableStateFlow<BluetoothCodecInfo?>(null)
    val info: StateFlow<BluetoothCodecInfo?> = _info.asStateFlow()

    /** codec 信息变化后通知宿主刷新播放页状态 */
    var onInfoChanged: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val retryRunnable = Runnable { readAndPublish() }
    private var retryCount = 0

    fun init(context: Context) {
        appContext = context.applicationContext
        adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        registerCodecReceiver()
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
        registerCodecReceiver()
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

    /**
     * 监听 **codec 配置变化**广播。
     *
     * 为什么非要这个：`onDevicesChanged` 只在**音频路由**变时触发。而换 codec、
     * 改开发者选项里的「蓝牙音频采样率」时路由没变 —— 设备还是那台耳机、还是蓝牙输出，
     * 于是设备回调一次都不来，界面就永远停在旧值上。
     *
     * `ACTION_CODEC_CONFIG_CHANGED` 是 `@SystemApi`（SDK 里没有常量，这里用原串），
     * 由蓝牙进程带 `BLUETOOTH_CONNECT` 发出；本应用已持有该权限，所以收得到。
     * 系统广播来自别的 uid，**必须 `RECEIVER_EXPORTED`**，`NOT_EXPORTED` 收不到。
     */
    private fun registerCodecReceiver() {
        val ctx = appContext ?: return
        if (codecReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                RuntimeLog.record("蓝牙", "codec 配置变化 → 重读 ${intent?.action}")
                refresh()
            }
        }
        val filter = IntentFilter(ACTION_CODEC_CONFIG_CHANGED)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
            } else {
                ctx.registerReceiver(r, filter)
            }
            codecReceiver = r
        }
    }

    /**
     * 重新读一次 codec 状态。
     *
     * 三条规矩，都是踩过的坑：
     * 1. **结果没变就不上报** —— 否则每次重读都触发一次输出策略重算 / 界面重组；
     * 2. **读不到时保留旧值 + 重试**，不直接清空：重新协商的瞬间 `getCodecStatus`
     *    可能给不出 config，一清空就会让版本号 / 采样率 / 位深集体消失；
     * 3. **真的没有已连接设备才清空** —— 那时留着上一台耳机的 LHDC 才是错的。
     */
    fun refresh() {
        retryCount = 0
        handler.removeCallbacks(retryRunnable)
        readAndPublish()
    }

    @SuppressLint("MissingPermission")
    private fun readAndPublish() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !hasPermission()) {
            publish(null)
            return
        }
        val proxy = a2dp
        if (proxy == null) {
            connectProxy()
            scheduleRetry()
            return
        }
        val device = runCatching { proxy.connectedDevices.firstOrNull() }.getOrNull()
        if (device == null) {
            // 真的没有已连接的 A2DP 设备 → 立刻清空
            publish(null)
            return
        }
        val read = runCatching { readCodec(proxy, device) }.getOrNull()
        if (read == null) {
            scheduleRetry()
            return
        }
        publish(read)
    }

    /** 只有值真的变了才上报（`BluetoothCodecInfo` 是 data class，相等即"没变"） */
    private fun publish(info: BluetoothCodecInfo?) {
        if (_info.value == info) return
        _info.value = info
        onInfoChanged?.invoke()
    }

    /**
     * 协商窗口内的重试：300ms / 900ms / 2s 各一次，之后放弃。
     *
     * 为什么不一直轮询：轮询会一直被 `getCodecStatus` 的 binder 调用牵着走，
     * 而 codec 变化本来就有广播可听 —— 重试只是兜底那些不发广播的机型。
     */
    private fun scheduleRetry() {
        if (retryCount >= RETRY_DELAYS_MS.size) return
        handler.postDelayed(retryRunnable, RETRY_DELAYS_MS[retryCount++])
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
            // 版本号跟着 **codecType** 走，不跟 priority —— 见 `codecNameOf`
            codecName = codecNameOf(codecType),
            sampleRate = sampleRateOf(sampleRateMask),
            bitsPerSample = bitsOf(bitsMask),
            bitrate = bitrateOf(specific1),
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

    /**
     * `codecSpecific1` → 码率（bit/s）；读不到就是 0（界面不显示，不留空壳）。
     *
     * 判据是**值域**而不是 codec 类型：
     * - LDAC 按 AOSP 约定存 bps（990000 / 660000 / 330000）；
     * - 有的厂商 codec 也存 bps，判定同一套区间即可覆盖，不必枚举 codec 类型；
     * - 反例（必须挡住）：本机 LHDC V5 的 `codecSpecific1` 是 **32777**（标志位），
     *   落在区间外 → 自然过滤。LHDC 真正的传输码率只出现在 `dumpsys bluetooth_manager`
     *   的厂商段（实测 400000 bps / ABR 动态），属于 `A2dpCodecStatus.dump()` 的私有字段，
     *   第三方 App 拿不到 —— 宁可写不出，也不拿标志位冒充码率。
     */
    private fun bitrateOf(specific1: Long): Int =
        specific1.takeIf { it in MIN_CODEC_BITRATE..MAX_CODEC_BITRATE }?.toInt() ?: 0

    /** `BluetoothCodecConfig.BITS_PER_SAMPLE_*` 位掩码 → bit */
    private fun bitsOf(mask: Int): Int = when (mask) {
        0x1 -> 16
        0x2 -> 24
        0x4 -> 32
        else -> 0
    }

    /**
     * codec 类型 → 名字。
     *
     * AOSP 那部分**直接引用常量**（`BluetoothCodecConfig.SOURCE_CODEC_TYPE_*`，SDK stub 里 public，
     * 值：SBC=0 / AAC=1 / aptX=2 / aptX HD=3 / LDAC=4 **LC3=5** / Opus=6）。
     * ⚠️ 注意 5 是 **LC3**，不是 aptX Adaptive —— 后者是厂商私有值 16。
     *
     * 厂商私有那几个没有公开规范，值取自**本机实测**（PLQ110 / ColorOS）：
     * `dumpsys bluetooth_manager` 会自己列出 `codecName:xxx,mCodecType:N`，
     * 给的是 16=aptX Adaptive、17=aptX TWS+、**18=LHDC V3、19=LHDC V5**。
     * 其余未知值一律显示原始 type，不猜。
     *
     * ## 版本号为什么跟 **codecType** 走，不跟 `codecPriority`
     *
     * 曾经用 priority 认代际（9001=V3 / 9002=V5）。但**开发者选项里改「蓝牙音频采样率」
     * 会让厂商把生效配置的 priority 改写成哨兵值 `1000000`** —— 实测：
     * ```
     * 改之前：mCodecConfig {mCodecType:19, mCodecPriority:9002}   → LHDC V5
     * 改之后：mCodecConfig {mCodecType:19, mCodecPriority:1000000} → 版本号凭空消失
     * ```
     * 同一份 dump 的 `mCodecsLocalCapabilities` 却始终是
     * `{mCodecType:19, mCodecPriority:9002}` 与 `{mCodecType:18, mCodecPriority:9001}` ——
     * 说明**两代 LHDC 本来就是两个 codecType**，priority 才是会变的那一个。
     */
    private fun codecNameOf(type: Int): String = when (type) {
        BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC -> "SBC"
        BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC -> "AAC"
        BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX -> "aptX"
        BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD -> "aptX HD"
        BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC -> "LDAC"
        BluetoothCodecConfig.SOURCE_CODEC_TYPE_LC3 -> "LC3"
        BluetoothCodecConfig.SOURCE_CODEC_TYPE_OPUS -> "Opus"
        // 以下为厂商私有扩展（AOSP 无定义），值经本机 dumpsys 核对
        16 -> "aptX Adaptive"
        17 -> "aptX TWS+"
        CODEC_TYPE_LHDC_V3 -> "LHDC V3"
        CODEC_TYPE_LHDC_V5 -> "LHDC V5"
        else -> "未知(type=$type)"
    }

    /** 厂商私有 codec 类型：LHDC V3（`mCodecType:18`）/ LHDC V5（`mCodecType:19`） */
    private const val CODEC_TYPE_LHDC_V3 = 18
    private const val CODEC_TYPE_LHDC_V5 = 19

    /**
     * `BluetoothA2dp.ACTION_CODEC_CONFIG_CHANGED` 的字面值。
     *
     * 该常量是 `@SystemApi`，编译期不可见（本文件其余 codec API 也是反射，原因相同），
     * 所以写原串；值取自 AOSP `BluetoothA2dp.java` 的声明，不需要猜。
     */
    private const val ACTION_CODEC_CONFIG_CHANGED =
        "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED"

    /** 协商窗口内的重试节奏（毫秒）：够覆盖 A2DP 重新协商，又不会长期占用 binder */
    private val RETRY_DELAYS_MS = longArrayOf(300L, 900L, 2_000L)

    /** `codecSpecific1` 里可能出现的码率区间（覆盖 LDAC 330–990 kbps 与 LHDC 400–1000 kbps） */
    private const val MIN_CODEC_BITRATE = 100_000L
    private const val MAX_CODEC_BITRATE = 1_500_000L
}
