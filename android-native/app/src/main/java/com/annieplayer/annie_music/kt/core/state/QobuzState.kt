package com.annieplayer.annie_music.kt.core.state

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import com.annieplayer.annie_music.kt.data.model.QobuzToken
import com.annieplayer.annie_music.kt.data.network.json
import com.annieplayer.annie_music.kt.data.network.jsonElementToAny
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Qobuz Token 状态（1:1 移植 Flutter `core/providers/` 下的 `qobuz_provider.dart`，
 * 对齐 RN 版 store/useQobuzStore.ts）。
 *
 * 持久化：SharedPreferences key `qobuz-tokens`（JSON 数组，字段 id / name / token /
 * isActive 与 Dart 逐键一致）。
 */
class QobuzViewModel : ViewModel() {

    private val _state = MutableStateFlow<List<QobuzToken>>(emptyList())

    /** 只读状态流 */
    val state: StateFlow<List<QobuzToken>> = _state.asStateFlow()

    init {
        _load()
    }

    private fun _load() {
        val p = _prefs ?: return
        val raw = p.getString(STORAGE_KEY, null) ?: return
        try {
            val list = jsonElementToAny(json.parseToJsonElement(raw)) as List<*>
            _state.value = list.map { item ->
                val map = item as Map<*, *>
                QobuzToken(
                    id = map["id"] as String,
                    name = map["name"] as String,
                    token = map["token"] as String,
                    isActive = map["isActive"] as? Boolean ?: false,
                )
            }
        } catch (_: Throwable) {
            // 读取失败：保留空列表（对齐 Dart 空 catch）
        }
    }

    private fun _persist() {
        val p = _prefs ?: return
        try {
            val arr = buildJsonArray {
                for (t in _state.value) {
                    add(
                        buildJsonObject {
                            put("id", t.id)
                            put("name", t.name)
                            put("token", t.token)
                            put("isActive", t.isActive)
                        },
                    )
                }
            }
            p.edit().putString(STORAGE_KEY, arr.toString()).apply()
        } catch (_: Throwable) {
            // 持久化失败静默忽略
        }
    }

    fun addToken(token: QobuzToken) {
        _state.value = _state.value + token
        _persist()
    }

    fun updateToken(token: QobuzToken) {
        _state.value = _state.value.map { if (it.id == token.id) token else it }
        _persist()
    }

    fun removeToken(id: String) {
        _state.value = _state.value.filter { it.id != id }
        _persist()
    }

    fun setActive(id: String) {
        _state.value = _state.value.map { it.copyWith(isActive = it.id == id) }
        _persist()
    }

    /** 当前活跃 token；无 isActive 时回退首个，空列表返回 null（对齐 Dart） */
    fun getActiveToken(): QobuzToken? {
        for (t in _state.value) {
            if (t.isActive) return t
        }
        return _state.value.firstOrNull()
    }

    companion object {
        /** Token 列表持久化 key（与 Dart 完全一致） */
        private const val STORAGE_KEY = "qobuz-tokens"

        /** 与 Player/Kkbox 状态共用一个 SharedPreferences 文件（对应 Dart 单一 prefs store） */
        private const val PREFS_NAME = "annie_shared_prefs"

        private var _prefs: SharedPreferences? = null

        /** 由 Application / Activity 调用以开启持久化（对齐 CacheService.init 模式） */
        fun init(context: Context) {
            _prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }
}
