package com.annieplayer.annie_music.kt.data.sources.apple.wrapper

import android.content.Context
import android.content.SharedPreferences
import com.annieplayer.annie_music.kt.data.model.AppleWrapper
import com.annieplayer.annie_music.kt.data.network.json
import com.annieplayer.annie_music.kt.data.network.jsonElementToAny
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Apple Wrapper **远程地址**表（用户自填，可多条、单选使用中）。
 *
 * 与 Qobuz Token 管理同款语义：列表 + 唯一 active。区别是**没有硬编码默认值** ——
 * 地址（以及里面的 token）属于服务方，不该打进包里。
 *
 * 只在「使用本机服务」关闭时生效（开关在 `AppleServiceSettings`，默认开 → 走 127.0.0.1:12341）。
 *
 * 落盘：`annie_shared_prefs` 的 `apple-wrappers`（JSON 数组：id / url / isActive）。
 */
object AppleWrapperRegistry {

    private const val PREFS_NAME = "annie_shared_prefs"
    private const val KEY_WRAPPERS = "apple-wrappers"

    private var prefs: SharedPreferences? = null

    private val _wrappers = MutableStateFlow<List<AppleWrapper>>(emptyList())

    /** 已添加的地址（UI 直接订阅） */
    val wrappers: StateFlow<List<AppleWrapper>> = _wrappers.asStateFlow()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _wrappers.value = _load()
    }

    // ── 增删改 ──

    /**
     * 添加一条地址。
     *
     * @return 新条目；地址解析不出来或已存在时返回 null（UI 据此提示，不要静默吞掉）
     */
    fun add(rawUrl: String): AppleWrapper? {
        val endpoint = parseWrapperUrl(rawUrl) ?: return null
        val url = rawUrl.trim()
        if (_wrappers.value.any { it.url == url }) return null
        val wrapper = AppleWrapper(
            id = _generateId(),
            url = url,
            // 第一条自动生效（与 Qobuz Token 一致）
            isActive = _wrappers.value.isEmpty(),
        )
        // 保险：endpoint 解析通过但 base 拼不出来（理论上不会）
        if (endpoint.base.isEmpty()) return null
        _wrappers.value = _wrappers.value + wrapper
        _persist()
        return wrapper
    }

    /** 改地址文本；改成解析不出来的内容时返回 false（保持原值） */
    fun update(id: String, rawUrl: String): Boolean {
        if (parseWrapperUrl(rawUrl) == null) return false
        val url = rawUrl.trim()
        if (_wrappers.value.any { it.id != id && it.url == url }) return false
        _wrappers.value = _wrappers.value.map { if (it.id == id) it.copy(url = url) else it }
        _persist()
        return true
    }

    fun remove(id: String) {
        val wasActive = _wrappers.value.firstOrNull { it.id == id }?.isActive == true
        val left = _wrappers.value.filterNot { it.id == id }
        // 删掉的是 active → 顶上下一条，避免「列表里有地址但没人用」的死状态
        _wrappers.value = if (wasActive && left.isNotEmpty()) {
            left.mapIndexed { index, item -> item.copy(isActive = index == 0) }
        } else {
            left
        }
        _persist()
    }

    fun setActive(id: String) {
        _wrappers.value = _wrappers.value.map { it.copy(isActive = it.id == id) }
        _persist()
    }

    // ── 查询 ──

    /** 使用中的那条；没有标记时回退首条，列表为空返回 null */
    fun active(): AppleWrapper? =
        _wrappers.value.firstOrNull { it.isActive } ?: _wrappers.value.firstOrNull()

    /** 使用中地址解析后的端点；没配地址返回 null */
    internal fun activeEndpoint(): WrapperEndpoint? = active()?.let { parseWrapperUrl(it.url) }

    // ── 持久化 ──

    private fun _load(): List<AppleWrapper> {
        val raw = prefs?.getString(KEY_WRAPPERS, null) ?: return emptyList()
        return runCatching {
            (jsonElementToAny(json.parseToJsonElement(raw)) as? List<*>).orEmpty().mapNotNull { item ->
                val map = item as? Map<*, *> ?: return@mapNotNull null
                val id = map["id"] as? String ?: return@mapNotNull null
                val url = map["url"] as? String ?: return@mapNotNull null
                if (parseWrapperUrl(url) == null) return@mapNotNull null
                AppleWrapper(id = id, url = url, isActive = map["isActive"] as? Boolean ?: false)
            }
        }.getOrDefault(emptyList())
    }

    private fun _persist() {
        val p = prefs ?: return
        runCatching {
            val arr = buildJsonArray {
                for (w in _wrappers.value) {
                    add(
                        buildJsonObject {
                            put("id", w.id)
                            put("url", w.url)
                            put("isActive", w.isActive)
                        },
                    )
                }
            }
            p.edit().putString(KEY_WRAPPERS, arr.toString()).apply()
        }
    }

    private fun _generateId(): String =
        System.currentTimeMillis().toString(36) + kotlin.random.Random.nextInt(1 shl 20).toString(36)
}
