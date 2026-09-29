package com.annieplayer.annie_music.kt.data.network

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * 缓存服务（1:1 移植 Flutter `lib/data/cache_service.dart`，原版基于 shared_preferences）。
 *
 * 存储格式与 Dart 完全一致：`api_cache:<key>` -> `{"data": <json>, "expiry": <epochMs>}`，
 * 并维护 `api_cache_size` 累计体积。命中且未过期返回数据；过期即删除并返回 null。
 *
 * 与 Dart 的差异（有意为之）：
 * - Dart 通过 SharedPreferences 异步读写；这里**内存镜像始终可用**，另在 [init] 之后
 *   额外持久化到 SharedPreferences。未调用 [init] 时退化为纯内存，TTL 与 key 语义不变。
 * - Dart 的 `get<T>` 命中后有 `jsonDecode(jsonEncode(data)) as T` 兜底反序列化；
 *   本工程写入缓存的永远是 JSON 形状（Map/List/String），读回来形状相同，
 *   故用 `as? T` 等价替代。
 * - 所有异常都被吞掉并返回 null / 静默失败，与 Dart 的 try/catch 行为一致。
 */
object CacheService {

    private const val CACHE_PREFIX = "api_cache:"
    private const val CACHE_SIZE_KEY = "api_cache_size"
    private const val PREFS_NAME = "annie_api_cache"

    /** 内存镜像：无论是否 init 都可用，保证缓存语义不依赖初始化时机 */
    private val memory = ConcurrentHashMap<String, String>()

    private var prefs: SharedPreferences? = null

    /**
     * 由 Application / Activity 调用以开启持久化。
     * 未调用时缓存退化为进程内内存缓存（行为语义不变，仅不跨进程重启）。
     */
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ---------------- 存储访问 ----------------
    // 标记 @PublishedApi internal：inline 的 get<T> 需要访问这些成员。

    @PublishedApi
    internal fun readRaw(key: String): String? =
        prefs?.getString(CACHE_PREFIX + key, null) ?: memory[CACHE_PREFIX + key]

    @PublishedApi
    internal fun writeRaw(key: String, raw: String) {
        memory[CACHE_PREFIX + key] = raw
        prefs?.edit()?.putString(CACHE_PREFIX + key, raw)?.apply()
    }

    @PublishedApi
    internal fun removeRaw(key: String) {
        memory.remove(CACHE_PREFIX + key)
        prefs?.edit()?.remove(CACHE_PREFIX + key)?.apply()
    }

    @PublishedApi
    internal fun getCacheSize(): Int = prefs?.getInt(CACHE_SIZE_KEY, 0) ?: 0

    @PublishedApi
    internal fun setCacheSize(size: Int) {
        prefs?.edit()?.putInt(CACHE_SIZE_KEY, size)?.apply()
    }

    @PublishedApi
    internal fun addToSize(delta: Int) = setCacheSize(getCacheSize() + delta)

    // ---------------- 公开 API（调用点与 Flutter 完全同名） ----------------

    /** 读取缓存；过期即删除并返回 null，解析失败也返回 null */
    inline fun <reified T> get(key: String): T? {
        return try {
            val raw = readRaw(key) ?: return null
            val entry = json.parseToJsonElement(raw) as? JsonObject ?: return null
            val expiry = ((entry["expiry"] as? JsonPrimitive)?.content)?.toLongOrNull() ?: return null
            if (System.currentTimeMillis() > expiry) {
                removeRaw(key)
                addToSize(-raw.length)
                return null
            }
            val data = jsonElementToAny(entry["data"]) ?: return null
            data as? T
        } catch (_: Throwable) {
            null
        }
    }

    /** 写入缓存（ttlMs 毫秒后过期）；失败静默，与 Dart 一致 */
    fun set(key: String, data: Any?, ttlMs: Long) {
        try {
            val entry = buildJsonObject {
                put("data", anyToJsonElement(data))
                put("expiry", JsonPrimitive(System.currentTimeMillis() + ttlMs))
            }
            val raw = entry.toString()
            val existing = readRaw(key)
            writeRaw(key, raw)
            addToSize(raw.length - (existing?.length ?: 0))
        } catch (_: Throwable) {
            // 与 Dart 一致：set 失败只记录，不抛
        }
    }

    /** 清空全部缓存条目并重置体积计数 */
    fun clear() {
        try {
            val memKeys = memory.keys.toList()
            for (k in memKeys) {
                if (k.startsWith(CACHE_PREFIX)) memory.remove(k)
            }
            val p = prefs
            if (p != null) {
                val prefsKeys = p.all.keys.toList()
                val editor = p.edit()
                for (k in prefsKeys) {
                    if (k.startsWith(CACHE_PREFIX)) editor.remove(k)
                }
                editor.apply()
            }
            setCacheSize(0)
        } catch (_: Throwable) {
            // 与 Dart 一致：静默
        }
    }

    /** 当前缓存累计体积（对齐 Dart `getSize()`） */
    fun getSize(): Int = getCacheSize()
}

/** 全局单例（对齐 Flutter 的 `final cacheService = CacheService.instance`） */
val cacheService: CacheService = CacheService
