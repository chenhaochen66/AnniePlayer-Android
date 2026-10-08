package com.annieplayer.annie_music.kt.core.state

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 更多设置（当前只有「Qobuz 下载写入翻译歌词」一个开关）。
 *
 * 持久化在 `annie_shared_prefs`（与 Player / Qobuz / Kkbox 同一个 prefs 文件），默认**关**。
 */
object MoreSettings {
    private const val PREFS_NAME = "annie_shared_prefs"
    private const val KEY_QOBUZ_DOWNLOAD_TRANSLATION = "qobuz-download-lyrics-translation"

    private var _prefs: SharedPreferences? = null

    private val _writeQobuzDownloadTranslation = MutableStateFlow(false)

    /** Qobuz 下载是否写入翻译歌词（默认 false） */
    val writeQobuzDownloadTranslation: StateFlow<Boolean> =
        _writeQobuzDownloadTranslation.asStateFlow()

    /** 由 Application / Activity 调用以开启持久化（对齐 QobuzViewModel.init 模式） */
    fun init(context: Context) {
        _prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _writeQobuzDownloadTranslation.value =
            _prefs?.getBoolean(KEY_QOBUZ_DOWNLOAD_TRANSLATION, false) ?: false
    }

    fun setWriteQobuzDownloadTranslation(value: Boolean) {
        _writeQobuzDownloadTranslation.value = value
        _prefs?.edit()?.putBoolean(KEY_QOBUZ_DOWNLOAD_TRANSLATION, value)?.apply()
    }
}

/**
 * 下载时的译文语言选择：调用方 `request(langs).await()` 挂起等待，UI 层选定后 [resolve]。
 *
 * 用全局对象（与 `AnnieAudioHolder` / `cacheService` 同风格）而非注入 ViewModel：
 * 5 个下载入口分散在搜索页与 4 个详情页，逐个注入同一个 VM 反而更绕。
 */
object TranslatePicker {
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    private val _langs = MutableStateFlow<List<String>>(emptyList())
    val langs: StateFlow<List<String>> = _langs.asStateFlow()

    private val _songTitle = MutableStateFlow("")
    val songTitle: StateFlow<String> = _songTitle.asStateFlow()

    private var pending: CompletableDeferred<String?>? = null

    /** 弹面板并挂起等待；返回 `null` 表示「不写入翻译」 */
    fun request(langs: List<String>, songTitle: String = ""): CompletableDeferred<String?> {
        // 上一次请求若无人应答（页面被销毁等），按「不写入翻译」收尾，避免协程永久挂起
        pending?.complete(null)
        val d = CompletableDeferred<String?>()
        pending = d
        _langs.value = langs
        _songTitle.value = songTitle
        _visible.value = true
        return d
    }

    fun resolve(lang: String?) {
        _visible.value = false
        pending?.complete(lang)
        pending = null
    }
}
