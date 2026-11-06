package com.annieplayer.annie_music.kt.core.audio

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import com.annieplayer.annie_music.kt.core.audio.dsd.UsbDacCaps
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.annieplayer.annie_music.kt.core.audio.dsp.NativeUacStream

/**
 * USB 独占的设备侧：找设备、拿授权、把 usbfs fd 交给 native。
 *
 * 下限是 API 26（`claimInterface(dev, force)` 引进的那一版）。**不按 34 划线**：
 * 34 只是第一次验证过的版本，把它当闸门等于用版本号代理能力 ——
 * 而真实门槛是 `libusb_submit_transfer` 的返回值，由 `AudioDirectProbe` 记住实测结论。
 * 等时传输被 sepolicy 拦住时表现为建流失败 → 走降级链，不是崩。
 */
internal object UsbAudioDirect {

    const val MIN_SDK_INT: Int = 26

    private const val ACTION_PERMISSION = "com.annieplayer.annie_music.kt.USB_PERMISSION"

    private var receiver: BroadcastReceiver? = null
    private var pendingCallback: ((Boolean) -> Unit)? = null

    /**
     * 我方 claim / release 接口会让内核驱动回绑或摘除，系统因此看到一次设备增删。
     * 那是**我方动作的回声**，不是用户插拔 —— 必须挡住，否则
     * 「重建 → 设备事件 → 再重建」会自激，换率切歌时正好踩两次。
     */
    @Volatile private var selfChangeUntilMs = 0L

    fun markSelfChange(windowMs: Long = 800) {
        selfChangeUntilMs = android.os.SystemClock.elapsedRealtime() + windowMs
    }

    fun selfEcho(): Boolean =
        selfChangeUntilMs != 0L && android.os.SystemClock.elapsedRealtime() < selfChangeUntilMs

    fun platformReady(): Boolean = Build.VERSION.SDK_INT >= MIN_SDK_INT

    fun isAudioDevice(device: UsbDevice): Boolean {
        for (index in 0 until device.interfaceCount) {
            if (device.getInterface(index).interfaceClass == UsbConstants.USB_CLASS_AUDIO) return true
        }
        return false
    }

    fun audioDevices(context: Context): List<UsbDevice> = runCatching {
        val manager = context.getSystemService(UsbManager::class.java) ?: return emptyList()
        manager.deviceList.values.filter { isAudioDevice(it) }
    }.getOrDefault(emptyList())

    fun hasPermission(context: Context, device: UsbDevice): Boolean = runCatching {
        context.getSystemService(UsbManager::class.java)?.hasPermission(device) == true
    }.getOrDefault(false)

    /**
     * 确保已接入的 USB 音频设备都拿到访问权限；拿到（或本来就有）时回调 true。
     *
     * 为什么必须有这一步：`USB_DEVICE_ATTACHED` 把应用列进选择器，只代表"系统愿意让
     * 你打开"，**不等于授权**。没有 `UsbManager.requestPermission` 走那一遍，`openDevice`
     * 会一直返回 null，表现就是"插了 DAC 但应用说检测不到/没权限"。
     *
     * 只请求第一个缺权限的设备：系统弹窗是串行的，一次一个，重复请求会互相打断。
     */
    fun ensurePermissions(context: Context, onResult: (Boolean) -> Unit = {}) {
        val devices = audioDevices(context)
        if (devices.isEmpty()) {
            onResult(false)
            return
        }
        val missing = devices.firstOrNull { !hasPermission(context, it) }
        if (missing == null) {
            onResult(true)
            return
        }
        requestPermission(context, missing, onResult)
    }

    fun requestPermission(context: Context, device: UsbDevice, onResult: (Boolean) -> Unit) {
        val manager = runCatching { context.getSystemService(UsbManager::class.java) }.getOrNull()
        if (manager == null) {
            onResult(false)
            return
        }
        if (manager.hasPermission(device)) {
            onResult(true)
            return
        }
        ensureReceiver(context)
        pendingCallback = onResult
        val flags = PendingIntent.FLAG_MUTABLE
        val intent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_PERMISSION).setPackage(context.packageName),
            flags,
        )
        manager.requestPermission(device, intent)
    }

    /** 打开的连接必须活到流结束 —— fd 一关，native 侧立刻失效。 */
    class Session(
        val device: UsbDevice,
        val connection: UsbDeviceConnection,
    ) {
        val fd: Int get() = connection.fileDescriptor

        fun close() {
            runCatching { connection.close() }
        }
    }

    fun open(context: Context, device: UsbDevice): Session? = runCatching {
        val manager = context.getSystemService(UsbManager::class.java) ?: return null
        if (!manager.hasPermission(device)) return null
        val connection = manager.openDevice(device) ?: return null
        Session(device, connection)
    }.getOrNull()

    /** 只读探测：开连接 → 读描述符与设备声明的率区间 → 立刻关掉 */
    fun describe(context: Context, device: UsbDevice): String? {
        val session = open(context, device) ?: return null
        return try {
            NativeUacStream.describeFd(session.fd).ifEmpty { null }
        } finally {
            session.close()
        }
    }

    /**
     * 只读能力探测：这个 DAC 有没有 raw DSD 通道、两侧各声明了哪些率。
     *
     * 走 `describe` 同一条路（开连接 → 解析描述符 → 关），**不 claim 接口**，
     * 所以在播放中调用也不会干扰已经在跑的流。
     *
     * @return `null` = 读不出来（无权限 / 打开失败），调用方应把它当作"未知"而不是"不支持"
     */
    fun capabilities(context: Context, device: UsbDevice): UsbDacCaps? {
        if (!platformReady()) return null
        val session = open(context, device) ?: return null
        return try {
            NativeUacStream.capabilities(session.fd)
        } finally {
            session.close()
        }
    }

    private fun ensureReceiver(context: Context) {
        if (receiver != null) return
        val created = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != ACTION_PERMISSION) return
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                val callback = pendingCallback
                pendingCallback = null
                releaseReceiver(ctx ?: context)
                callback?.invoke(granted)
            }
        }
        receiver = created
        ContextCompat.registerReceiver(
            context.applicationContext,
            created,
            IntentFilter(ACTION_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun releaseReceiver(context: Context) {
        val current = receiver ?: return
        receiver = null
        runCatching { context.applicationContext.unregisterReceiver(current) }
    }
}
