package com.annieplayer.annie_music.kt.core.audio.apple

import java.io.Closeable

/**
 * Temari FairPlay SAMPLE-AES 解密器。
 *
 * 使用 `libtemari.so`（Rust cdylib）的 C FFI，模板 JSON 由 `/key` 返回的
 * 解密上下文构造。模板句柄不可跨进程复用。
 */
internal object TemariDecryptor {

    private val loaded: Boolean = runCatching {
        System.loadLibrary("annietemari")
    }.isSuccess

    private val available: Boolean by lazy {
        loaded && runCatching { nativeAvailable() }.getOrDefault(false)
    }

    fun isAvailable(): Boolean = available

    /**
     * 用 `/key` 返回的 JSON 创建模板。失败返回 null。
     */
    fun create(templateJson: ByteArray): Handle? {
        if (!available || templateJson.isEmpty()) return null
        val pointer = runCatching { nativeCreate(templateJson) }.getOrDefault(0L)
        return if (pointer == 0L) null else Handle(pointer)
    }

    class Handle internal constructor(private var pointer: Long) : Closeable {

        @Synchronized
        fun decrypt(sample: ByteArray): ByteArray? {
            if (pointer == 0L || sample.isEmpty()) return null
            return runCatching { nativeDecrypt(pointer, sample) }.getOrNull()
        }

        @Synchronized
        override fun close() {
            val current = pointer
            pointer = 0L
            if (current != 0L) {
                runCatching { nativeDestroy(current) }
            }
        }
    }

    private external fun nativeAvailable(): Boolean

    private external fun nativeCreate(templateJson: ByteArray): Long

    private external fun nativeDecrypt(templateHandle: Long, sample: ByteArray): ByteArray?

    private external fun nativeDestroy(templateHandle: Long)
}
