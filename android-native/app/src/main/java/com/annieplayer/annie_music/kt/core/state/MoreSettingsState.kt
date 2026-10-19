package com.annieplayer.annie_music.kt.core.state

import android.content.Context
import android.content.SharedPreferences
import com.annieplayer.annie_music.kt.core.audio.AudioOutputMode
import com.annieplayer.annie_music.kt.core.audio.EqPreset
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Quality
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
    private const val KEY_PLAY_QUALITY_PREFIX = "play-quality-"
    private const val PLAY_QUALITY_AUTO = "auto"
    private const val KEY_WRITE_ENHANCED_LYRICS = "write-enhanced-lyrics"
    private const val KEY_SAVE_LRC_FILE = "save-lrc-file"
    private const val KEY_AUDIO_MODE = "audio-output-mode"
    private const val KEY_EQ_PRESET = "audio-eq-preset"
    private const val KEY_LOUDNESS = "audio-loudness-normalization"
    private const val KEY_FORCE_NATIVE_RATE = "audio-force-native-rate"
    private const val KEY_RESAMPLE_RATE = "audio-resample-rate"
    private const val KEY_RESAMPLE_DEPTH = "audio-resample-depth"
    private const val KEY_SOFTWARE_FLAC = "audio-software-flac"

    private var _prefs: SharedPreferences? = null

    private val _writeQobuzDownloadTranslation = MutableStateFlow(false)

    /** Qobuz 下载是否写入翻译歌词（默认 false） */
    val writeQobuzDownloadTranslation: StateFlow<Boolean> =
        _writeQobuzDownloadTranslation.asStateFlow()

    private val _playQuality = MutableStateFlow<Map<MusicPlatform, Quality>>(emptyMap())

    /** 各平台默认播放音质；**表里没有该平台 = 自动**（沿用该平台既有取档规则，即旧行为） */
    val playQuality: StateFlow<Map<MusicPlatform, Quality>> = _playQuality.asStateFlow()

    private val _writeEnhancedLyrics = MutableStateFlow(false)

    /** 下载时把「逐字歌词」写成**增强 LRC**（`[行时间]<00:00.000><词时间>词…`）；默认 false（只写普通 LRC） */
    val writeEnhancedLyrics: StateFlow<Boolean> = _writeEnhancedLyrics.asStateFlow()

    private val _saveLrcFile = MutableStateFlow(false)

    /** 下载时是否在歌曲同目录另存一份同名 `.lrc`（内容与内嵌歌词完全一致）；默认 false */
    val saveLrcFile: StateFlow<Boolean> = _saveLrcFile.asStateFlow()

    private val _audioMode = MutableStateFlow(AudioOutputMode.transparent)

    /** 透明 / 调音（互斥；默认透明，保证「不做 DSP」是默认行为） */
    val audioMode: StateFlow<AudioOutputMode> = _audioMode.asStateFlow()

    private val _eqPreset = MutableStateFlow(EqPreset.flat)

    /** 调音模式的 EQ 预设 */
    val eqPreset: StateFlow<EqPreset> = _eqPreset.asStateFlow()

    private val _loudnessNormalization = MutableStateFlow(false)

    /** 调音模式的响度归一化（R128 / ReplayGain 标签） */
    val loudnessNormalization: StateFlow<Boolean> = _loudnessNormalization.asStateFlow()

    private val _forceNativeRate = MutableStateFlow(false)

    /** 强制重采样到设备原生率（实验项，见 `SincResamplerAudioProcessor`） */
    val forceNativeRate: StateFlow<Boolean> = _forceNativeRate.asStateFlow()

    private val _resampleRate = MutableStateFlow(0)

    /** 重采样目标率（Hz）；0 = 跟随设备原生率 */
    val resampleRate: StateFlow<Int> = _resampleRate.asStateFlow()

    private val _resampleDepth = MutableStateFlow(0)

    /** 重采样输出位深；0 = 跟随输入，16 / 32（float） */
    val resampleDepth: StateFlow<Int> = _resampleDepth.asStateFlow()

    /** 由 Application / Activity 调用以开启持久化（对齐 QobuzViewModel.init 模式） */
    fun init(context: Context) {
        _prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _writeQobuzDownloadTranslation.value =
            _prefs?.getBoolean(KEY_QOBUZ_DOWNLOAD_TRANSLATION, false) ?: false
        // 每平台一个键，存 Quality.name；读不出来或读到 auto 一律当「自动」（容错，不让脏值崩启动）
        _playQuality.value = buildMap {
            for (platform in MusicPlatform.entries) {
                val raw = _prefs?.getString(KEY_PLAY_QUALITY_PREFIX + platform.name, PLAY_QUALITY_AUTO)
                    ?: PLAY_QUALITY_AUTO
                if (raw == PLAY_QUALITY_AUTO) continue
                runCatching { Quality.valueOf(raw) }.getOrNull()?.let { put(platform, it) }
            }
        }
        _writeEnhancedLyrics.value =
            _prefs?.getBoolean(KEY_WRITE_ENHANCED_LYRICS, false) ?: false
        _saveLrcFile.value =
            _prefs?.getBoolean(KEY_SAVE_LRC_FILE, false) ?: false
        _audioMode.value = AudioOutputMode.fromKey(_prefs?.getString(KEY_AUDIO_MODE, null))
        _eqPreset.value = EqPreset.fromKey(_prefs?.getString(KEY_EQ_PRESET, null))
        _loudnessNormalization.value =
            _prefs?.getBoolean(KEY_LOUDNESS, false) ?: false
        _forceNativeRate.value =
            _prefs?.getBoolean(KEY_FORCE_NATIVE_RATE, false) ?: false
        _resampleRate.value = _prefs?.getInt(KEY_RESAMPLE_RATE, 0) ?: 0
        _resampleDepth.value = _prefs?.getInt(KEY_RESAMPLE_DEPTH, 0) ?: 0
    }

    fun setWriteQobuzDownloadTranslation(value: Boolean) {
        _writeQobuzDownloadTranslation.value = value
        _prefs?.edit()?.putBoolean(KEY_QOBUZ_DOWNLOAD_TRANSLATION, value)?.apply()
    }

    /** 该平台配置的档位；返回 null = 自动 */
    fun qualityFor(platform: MusicPlatform): Quality? = _playQuality.value[platform]

    /** [quality] 传 null = 该平台恢复「自动」 */
    fun setPlayQuality(platform: MusicPlatform, quality: Quality?) {
        val next = _playQuality.value.toMutableMap()
        if (quality == null) next.remove(platform) else next[platform] = quality
        _playQuality.value = next
        _prefs?.edit()
            ?.putString(KEY_PLAY_QUALITY_PREFIX + platform.name, quality?.name ?: PLAY_QUALITY_AUTO)
            ?.apply()
    }

    fun setWriteEnhancedLyrics(value: Boolean) {
        _writeEnhancedLyrics.value = value
        _prefs?.edit()?.putBoolean(KEY_WRITE_ENHANCED_LYRICS, value)?.apply()
    }

    fun setSaveLrcFile(value: Boolean) {
        _saveLrcFile.value = value
        _prefs?.edit()?.putBoolean(KEY_SAVE_LRC_FILE, value)?.apply()
    }

    fun setAudioMode(mode: AudioOutputMode) {
        _audioMode.value = mode
        _prefs?.edit()?.putString(KEY_AUDIO_MODE, mode.key)?.apply()
    }

    fun setEqPreset(preset: EqPreset) {
        _eqPreset.value = preset
        _prefs?.edit()?.putString(KEY_EQ_PRESET, preset.key)?.apply()
    }

    fun setLoudnessNormalization(value: Boolean) {
        _loudnessNormalization.value = value
        _prefs?.edit()?.putBoolean(KEY_LOUDNESS, value)?.apply()
    }

    fun setForceNativeRate(value: Boolean) {
        _forceNativeRate.value = value
        _prefs?.edit()?.putBoolean(KEY_FORCE_NATIVE_RATE, value)?.apply()
    }

    fun setResampleRate(rate: Int) {
        _resampleRate.value = rate
        _prefs?.edit()?.putInt(KEY_RESAMPLE_RATE, rate)?.apply()
    }

    fun setResampleDepth(depth: Int) {
        _resampleDepth.value = depth
        _prefs?.edit()?.putInt(KEY_RESAMPLE_DEPTH, depth)?.apply()
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
