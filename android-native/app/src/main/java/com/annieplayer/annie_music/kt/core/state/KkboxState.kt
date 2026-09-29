package com.annieplayer.annie_music.kt.core.state

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import com.annieplayer.annie_music.kt.data.model.KkboxAccount
import com.annieplayer.annie_music.kt.data.model.KkboxSession
import com.annieplayer.annie_music.kt.data.network.json
import com.annieplayer.annie_music.kt.data.network.jsonElementToAny
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * KKBOX 账号状态（1:1 移植 Flutter `core/providers/` 下的 `kkbox_provider.dart`，
 * 对齐 RN 版 store/useKkboxStore.ts）。
 *
 * 持久化：账号列表存于 SharedPreferences key `kkbox-accounts`（JSON 数组，字段
 * id / name / email / isActive / session 与 Dart **逐键一致**，且与 Dart 一样
 * **不含 password**）。
 *
 * 与 Dart 的唯一差异（不可移植项）：Dart 用 `flutter_secure_storage` 单独存放密码
 * （key `kkbox_pw_{id}`）；本工程未引入安全存储依赖，故退化为 SharedPreferences，
 * **key 字符串保持 `kkbox_pw_{id}` 不变**，语义一致（读写 / 删除同一 key）。
 */
class KkboxViewModel : ViewModel() {

    private val _state = MutableStateFlow<List<KkboxAccount>>(emptyList())

    /** 只读状态流 */
    val state: StateFlow<List<KkboxAccount>> = _state.asStateFlow()

    init {
        _load()
    }

    /** 从 SharedPreferences 读取账号列表；密码按 id 单键读取（缺失回退空串，对齐 Dart） */
    private fun _load() {
        val p = _prefs ?: return
        val raw = p.getString(STORAGE_KEY, null) ?: return
        try {
            val list = jsonElementToAny(json.parseToJsonElement(raw)) as List<*>
            _state.value = list.map { item ->
                val map = item as Map<*, *>
                // 密码从（等价安全存储的）单键读取
                val password = p.getString(_pwKey(map["id"] as String), null) ?: ""
                val sessionRaw = map["session"] as? Map<*, *>
                KkboxAccount(
                    id = map["id"] as String,
                    name = map["name"] as String,
                    email = map["email"] as String,
                    password = password,
                    session = if (sessionRaw == null) {
                        null
                    } else {
                        KkboxSession(
                            sid = sessionRaw["sid"] as? String ?: "",
                            licContentKey = sessionRaw["licContentKey"] as? String ?: "",
                            highQuality = sessionRaw["highQuality"] as? Boolean ?: false,
                            kkid = sessionRaw["kkid"] as? String ?: "",
                            email = sessionRaw["email"] as? String ?: "",
                            loggedIn = sessionRaw["loggedIn"] as? Boolean ?: false,
                        )
                    },
                    isActive = map["isActive"] as? Boolean ?: false,
                )
            }
        } catch (_: Throwable) {
            // 读取失败：保留空列表（对齐 Dart 的空 catch）
        }
    }

    /** 写回账号列表（JSON 数组；不含 password，密码单独存） */
    private fun _persist() {
        val p = _prefs ?: return
        try {
            val arr = buildJsonArray {
                for (a in _state.value) {
                    add(
                        buildJsonObject {
                            put("id", a.id)
                            put("name", a.name)
                            put("email", a.email)
                            put("isActive", a.isActive)
                            val s = a.session
                            if (s == null) {
                                put("session", JsonNull)
                            } else {
                                put(
                                    "session",
                                    buildJsonObject {
                                        put("sid", s.sid)
                                        put("licContentKey", s.licContentKey)
                                        put("highQuality", s.highQuality)
                                        put("kkid", s.kkid)
                                        put("email", s.email)
                                        put("loggedIn", s.loggedIn)
                                    },
                                )
                            }
                        },
                    )
                }
            }
            p.edit().putString(STORAGE_KEY, arr.toString()).apply()
        } catch (_: Throwable) {
            // 持久化失败静默忽略
        }
    }

    /** 新增账号：密码先入单键存储，列表追加后写回（对齐 Dart） */
    fun addAccount(account: KkboxAccount) {
        _prefs?.edit()?.putString(_pwKey(account.id), account.password)?.apply()
        _state.value = _state.value + account
        _persist()
    }

    fun updateAccount(account: KkboxAccount) {
        _prefs?.edit()?.putString(_pwKey(account.id), account.password)?.apply()
        _state.value = _state.value.map { a -> if (a.id == account.id) account else a }
        _persist()
    }

    fun removeAccount(id: String) {
        _prefs?.edit()?.remove(_pwKey(id))?.apply()
        _state.value = _state.value.filter { it.id != id }
        _persist()
    }

    fun setActive(id: String) {
        _state.value = _state.value.map { it.copyWith(isActive = it.id == id) }
        _persist()
    }

    fun updateAccountSession(id: String, session: KkboxSession) {
        _state.value = _state.value.map { if (it.id == id) it.copyWith(session = session) else it }
        _persist()
    }

    /**
     * 清空会话。
     *
     * 注意：与 Dart 一致 —— [KkboxAccount.copyWith] 的 `session` 使用 `?: this.session`
     * 回退，传 `null` **不会**清空会话，因此本方法在 Dart 与原实现中同样是「无实际清空」
     * 的行为，这里逐字保留该语义。
     */
    fun clearAccountSession(id: String) {
        _state.value = _state.value.map { if (it.id == id) it.copyWith(session = null) else it }
        _persist()
    }

    /** 当前活跃账号；无 isActive 时回退首个，空列表返回 null（对齐 Dart） */
    fun getActiveAccount(): KkboxAccount? {
        for (a in _state.value) {
            if (a.isActive) return a
        }
        return _state.value.firstOrNull()
    }

    private fun _pwKey(id: String): String = "kkbox_pw_$id"

    companion object {
        /** 账号列表持久化 key（与 Dart 完全一致） */
        private const val STORAGE_KEY = "kkbox-accounts"

        /** 与 Player/Qobuz 状态共用一个 SharedPreferences 文件（对应 Dart 单一 prefs store） */
        private const val PREFS_NAME = "annie_shared_prefs"

        private var _prefs: SharedPreferences? = null

        /** 由 Application / Activity 调用以开启持久化（对齐 CacheService.init 模式） */
        fun init(context: Context) {
            _prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }
}
