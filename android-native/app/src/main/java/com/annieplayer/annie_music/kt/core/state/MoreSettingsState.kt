package com.annieplayer.annie_music.kt.core.state

import android.content.Context
import android.content.SharedPreferences
import com.annieplayer.annie_music.kt.core.audio.AudioOutputMode
import com.annieplayer.annie_music.kt.core.audio.DSD_DEFAULT_PCM_RATE
import com.annieplayer.annie_music.kt.core.audio.DSD_PCM_RATES
import com.annieplayer.annie_music.kt.core.audio.DSD_RATE_FOLLOW_SYSTEM
import com.annieplayer.annie_music.kt.core.audio.DsdLevel
import com.annieplayer.annie_music.kt.core.audio.DsdOutputMode
import com.annieplayer.annie_music.kt.core.audio.FilterType
import com.annieplayer.annie_music.kt.core.audio.GraphicEqPreset
import com.annieplayer.annie_music.kt.core.audio.MsebValues
import com.annieplayer.annie_music.kt.core.audio.OutputPath
import com.annieplayer.annie_music.kt.core.audio.PeqBand
import com.annieplayer.annie_music.kt.core.audio.TuningEngine
import com.annieplayer.annie_music.kt.core.audio.defaultPeqBands
import com.annieplayer.annie_music.kt.core.audio.OutputToggles
import com.annieplayer.annie_music.kt.core.audio.migrateLegacySettings
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
    private const val KEY_OUTPUT_PATH = "audio-output-path"
    /**
     * 「直通解码」那个开关的历史键（`audio-toggle-offload`）。
     *
     * 功能已整条删除，但**这个键名要留着**：它是判断"老存档有没有写过新模型"的哨兵
     * （见下面的 `togglesSaved`）。删掉常量就会让老存档被重新迁移一遍。
     * 运行时**不再读它的值**。
     */
    private const val KEY_TOGGLE_OFFLOAD_LEGACY = "audio-toggle-offload"
    /** 输出矩阵的开关。与 `KEY_OUTPUT_PATH` 一起构成新模型 */
    private const val KEY_TOGGLE_FOLLOW_SOURCE = "audio-toggle-follow-source"
    private const val KEY_TOGGLE_BIT_PERFECT = "audio-toggle-bit-perfect"
    private const val KEY_TUNING_ENGINE = "audio-tuning-engine"
    private const val KEY_GRAPHIC_GAINS = "audio-graphic-gains"
    private const val KEY_GRAPHIC_AMP = "audio-graphic-amp"
    private const val KEY_PEQ_BANDS = "audio-peq-bands"
    private const val KEY_PEQ_AMP = "audio-peq-amp"
    private const val KEY_MSEB = "audio-mseb"
    private const val KEY_LOUDNESS = "audio-loudness-normalization"
    /**
     * **只读的历史键**（`audio-force-native-rate`）。
     *
     * 它和「自选采样率」一起退役了，现在唯一的用途是下面那次一次性迁移：
     * 老存档里 `true` 表示当年 SRC 是生效的，迁移时据此自动切到调音以保持听感。
     * 不在任何运行时判断里出现。
     */
    private const val KEY_FORCE_NATIVE_RATE = "audio-force-native-rate"
    private const val KEY_DIRECT_DECODE = "audio-direct-decode"
    private const val KEY_SOFTWARE_FLAC = "audio-software-flac"

    /**
     * 「接管采样率 → 输出模式」那次迁移**已完成**的标记。
     *
     * 没有它的时候，`init()` 里那段迁移会**每次启动都跑**：因为当年的判据依赖
     * 「档位 ≠ 跟随源」这个派生标志，条件对任何一个非默认档位的老用户都恒为真 ——
     * 结果就是「用户选透明，重启被改回调音」，而且每次都被写回磁盘，看起来像"设置存不住"。
     *
     * 档位本身已经删了，但**这个标记必须留着**：老用户机器上它可能还是 `false`，
     * 那次迁移仍然要能正确跑一次并且只跑一次。
     */
    private const val KEY_SRC_MIGRATION_DONE = "audio-src-migration-done"

    /** DSD 输出模式（`pcm` / `dop` / `native`）；见 `core/audio/DsdOutput.kt` */
    private const val KEY_DSD_MODE = "audio-dsd-mode"

    /** DSD → PCM 目标率，**每个 DSD 档位一个键**（`audio-dsd-pcm-rate-dsd64` 等） */
    private const val KEY_DSD_PCM_RATE_PREFIX = "audio-dsd-pcm-rate-"

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

    /**
     * 输出通路：系统 / AAudio 独占 / USB 独占（未实现）。
     *
     * **这是用户唯一要选的"大项"**。以前用户先选「透明 / 调音」这个抽象模式，
     * 再猜它能推出什么；现在反过来 —— 选通路，开关是通路的属性，
     * "要不要调音"由 [tuningEnabled] 派生（见 `OutputMatrix.kt`）。
     */
    private val _outputPath = MutableStateFlow(OutputPath.systemMix)

    val outputPath: StateFlow<OutputPath> = _outputPath.asStateFlow()

    /**
     * 通路下方的开关（直通 / 按源采样率 / Bit Perfect）。
     *
     * **开关 = 意愿，不随设备自动关掉**：换到支持的设备立刻生效，
     * 换回不支持的设备仍然开着（只提示"当前不生效"）。频繁插拔的人因此只需设置一次。
     */
    private val _outputToggles = MutableStateFlow(OutputToggles())

    val outputToggles: StateFlow<OutputToggles> = _outputToggles.asStateFlow()

    /**
     * 派生：这一组设置下是否启用 DSP。
     *
     * **不提供 `StateFlow` 版本**：Compose 侧已经 `collectAsState()` 了通路和开关两个流，
     * 在组合期调这个函数就会自然重组；再套一层 `stateIn` 只是多一个要管的 scope。
     * 更关键的是——**"能调音"只能有一个判据**（`tuningEnabled` 纯函数），
     * 散在调用点各算一遍就会重演「关掉直通却仍然直通」那类自相矛盾。
     *
     * @param bluetooth 当前是否蓝牙。**必须由调用方传**：蓝牙下通路开关不参与，
     *   调音恒启用（蓝牙只接管链路参数，不接管调音）。状态层不自己读路由 ——
     *   那是 `AudioOutputController` 的职责，这里多一份就会有两个真相。
     * @param pathOverride **实测生效**的通路。音频路径必须传它 —— 意图 usbDirect 但 USB
     *   没起来时，真跑的是系统 mixer，而系统通路恒可调音；按意图算会把 DSP 关掉，
     *   出现「输出计划说调音、处理器却没启用」。界面显隐不传，按用户选的算。
     *
     * **引擎从本状态里读**（不麻烦调用方传）：它是本类的字段，且「引擎 = 无」
     * 是比通路开关更高优先级的一票否决，漏传就会"选了无却仍在调音"。
     */
    fun tuningEnabledNow(
        bluetooth: Boolean = false,
        pathOverride: com.annieplayer.annie_music.kt.core.audio.OutputPath? = null,
        rawDsd: Boolean = false,
    ): Boolean =
        com.annieplayer.annie_music.kt.core.audio.tuningEnabled(
            pathOverride ?: _outputPath.value,
            _outputToggles.value,
            bluetooth,
            _tuningEngine.value,
            rawDsd,
        )

    private val _tuningEngine = MutableStateFlow(TuningEngine.graphic)

    /** 调音引擎：均衡器 / PEQ / MSEB（互斥） */
    val tuningEngine: StateFlow<TuningEngine> = _tuningEngine.asStateFlow()

    private val _graphicGains = MutableStateFlow(FloatArray(10))

    /** 图示 EQ 10 段增益（dB） */
    val graphicGains: StateFlow<FloatArray> = _graphicGains.asStateFlow()

    private val _graphicAmp = MutableStateFlow(0f)

    /** 图示 EQ 前级 Amp（dB） */
    val graphicAmp: StateFlow<Float> = _graphicAmp.asStateFlow()

    private val _peqBands = MutableStateFlow(defaultPeqBands())

    /** 32 段参量 EQ */
    val peqBands: StateFlow<List<PeqBand>> = _peqBands.asStateFlow()

    private val _peqAmp = MutableStateFlow(0f)

    /** PEQ 前级（dB，范围 -9~+2） */
    val peqAmp: StateFlow<Float> = _peqAmp.asStateFlow()

    private val _mseb = MutableStateFlow(MsebValues())

    /** MSEB 九个滑块 + 前级 */
    val mseb: StateFlow<MsebValues> = _mseb.asStateFlow()

    private val _loudnessNormalization = MutableStateFlow(false)

    /** 调音模式的响度归一化（R128 / ReplayGain 标签） */
    val loudnessNormalization: StateFlow<Boolean> = _loudnessNormalization.asStateFlow()

    private val _dsdMode = MutableStateFlow(DsdOutputMode.pcm)

    /**
     * DSD 输出模式。默认 `pcm`（唯一能真正生效的路径）。
     *
     * DoP / Native 现在灰显 —— 判据在 [dsdModeAvailability]，这里只存用户的选择。
     */
    val dsdMode: StateFlow<DsdOutputMode> = _dsdMode.asStateFlow()

    private val _dsdPcmRates = MutableStateFlow(
        DsdLevel.entries.associateWith { DSD_DEFAULT_PCM_RATE },
    )

    /**
     * DSD → PCM 的目标率，**每个 DSD 档位独立**（DSD64/128/256/512 各一份）。
     *
     * 为什么不共用一个值：同一台机器上 DSD64 降到 44.1k 还能听，DSD512 降到 44.1k
     * 就是 512 倍抽取 —— 用户有权按档位分别决定。
     *
     * 取值有两种：`DSD_PCM_RATES` 里的固定档位，或 [DSD_RATE_FOLLOW_SYSTEM]（0，跟随系统）。
     * 后者表示"不抽到固定档位，直接对齐系统率" —— 普通手机上少一次系统 SRC，
     * 但有跟随能力的播放器应该保持档位（见 `DsdOutput.kt` 里哨兵值的说明）。
     */
    val dsdPcmRates: StateFlow<Map<DsdLevel, Int>> = _dsdPcmRates.asStateFlow()

    /** 某个 DSD 档位当前配置的目标率（可能是 [DSD_RATE_FOLLOW_SYSTEM]） */
    fun dsdPcmRateOf(level: DsdLevel): Int =
        _dsdPcmRates.value[level] ?: DSD_DEFAULT_PCM_RATE

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
        // 引擎：没有新键时，**按老「透明 / 调音」决定**它。
        //
        // 这条是删掉「直通解码」之后补的：以前"不做 DSP"靠 `transparent` 模式 +
        // 直通开关表达；现在只有引擎能表达它。老存档里 `transparent` 的用户如果不迁成
        // 引擎「无」，升级后会掉进 `graphic`（参数全平、行为接近，但模式会显示"调音中"）——
        // 那是"用户没动过却变了"。
        _tuningEngine.value = _prefs?.getString(KEY_TUNING_ENGINE, null)?.let {
            TuningEngine.fromKey(it)
        } ?: if (_prefs?.getString(KEY_AUDIO_MODE, null) == AudioOutputMode.transparent.key) {
            TuningEngine.none
        } else {
            TuningEngine.fromKey(null)
        }
        _graphicGains.value = decodeFloats(_prefs?.getString(KEY_GRAPHIC_GAINS, null), 10)
        _graphicAmp.value = _prefs?.getFloat(KEY_GRAPHIC_AMP, 0f) ?: 0f
        _peqBands.value = decodePeq(_prefs?.getString(KEY_PEQ_BANDS, null))
        _peqAmp.value = _prefs?.getFloat(KEY_PEQ_AMP, 0f) ?: 0f
        _mseb.value = decodeMseb(_prefs?.getString(KEY_MSEB, null))
        _loudnessNormalization.value =
            _prefs?.getBoolean(KEY_LOUDNESS, false) ?: false
        // 「直通解码」开关已并入透明模式的「非独占」策略（第一级就是硬解直通），
        // 不再有"想不想"的问题。老存档里的 `KEY_DIRECT_DECODE=false` 就此作废 ——
        // 留着它会静默把第一级关掉（实测本机存储里正是 false）。
        //
        // 「自选采样率」与它派生的 `KEY_FORCE_NATIVE_RATE` 已**整体删除**：
        // 采样率现在只有一个旋钮（「按源采样率输出」开关），档位恒为「跟随设备」。
        // 老存档里的这两个键不再读取（见下面矩阵迁移里的 legacy 兜底）。
        // ── 输出矩阵迁移（**只在首次升级时跑一次**）──
        //
        // 老存档只有「透明 / 调音」这一个轴，新模型是「通路 + 开关」。
        // `migrateLegacySettings` 把前者翻译成后者（纯函数，可单测）。
        //
        // ⚠️ **判据必须是"新键存不存在"，不能是"老键存不存在"。**
        // 之前的写法是 `if (prefs.contains(KEY_OUTPUT_PATH))` 就重新迁移并 `persistMatrix()`，
        // 而 `KEY_AUDIO_MODE`（值 `transparent`）**永远不会被删除** —— 于是**每次启动**
        // 都按老存档重算一遍，再把结果写回磁盘。
        //
        // 用户看到的现象就是「音频设置有开机回退」：关掉的开关重启后自己又开了、
        // 通路被改回默认。这不是记忆丢失，是每次启动都被老存档覆盖了一次。
        //
        // 哨兵用 `KEY_TOGGLE_FOLLOW_SOURCE`（"按源采样率"这个新键）。它和当年那个
        // `audio-toggle-offload` 一样，只在"新模型是否写过"这个问题上有意义 ——
        // 老键的名字留在上面当历史证据，值不再读。
        val prefs = _prefs
        val togglesSaved = prefs?.contains(KEY_TOGGLE_FOLLOW_SOURCE) == true
        when {
            // ① 已经用新键存过：直接读，**绝不重算**
            togglesSaved -> {
                _outputPath.value = OutputPath.fromKey(prefs?.getString(KEY_OUTPUT_PATH, null))
                _outputToggles.value = OutputToggles(
                    followSourceRate = prefs?.getBoolean(KEY_TOGGLE_FOLLOW_SOURCE, false) ?: false,
                    bitPerfect = prefs?.getBoolean(KEY_TOGGLE_BIT_PERFECT, true) ?: true,
                )
            }
            // ② 老版本升级：没有新键但可能存了老输出通路 → 迁移一次并落盘
            prefs?.contains(KEY_OUTPUT_PATH) == true -> {
                val (path, toggles) = migrateLegacySettings(
                    legacyModeKey = prefs.getString(KEY_AUDIO_MODE, null),
                    legacyPathKey = prefs.getString(KEY_OUTPUT_PATH, null),
                )
                _outputPath.value = path
                _outputToggles.value = toggles
                persistMatrix()
            }
            // ③ 全新安装：默认「系统通路 + 默认开关」，与历史默认一致
            else -> {
                _outputPath.value = OutputPath.systemMix
                _outputToggles.value = OutputToggles()
            }
        }
        // DSD：模式 + 每个档位各自的目标率。脏值一律落回默认（PCM / 176.4k），不让设置把播放搞哑。
        // 档位值允许 `DSD_RATE_FOLLOW_SYSTEM`（0 = 跟随系统），所以校验要单独放它一马。
        _dsdMode.value = DsdOutputMode.fromKey(_prefs?.getString(KEY_DSD_MODE, null))
        _dsdPcmRates.value = DsdLevel.entries.associateWith { level ->
            _prefs?.getInt(KEY_DSD_PCM_RATE_PREFIX + level.name, DSD_DEFAULT_PCM_RATE)
                ?.takeIf { it == DSD_RATE_FOLLOW_SYSTEM || it in DSD_PCM_RATES }
                ?: DSD_DEFAULT_PCM_RATE
        }
        // 迁移续（**只跑一次**）：老版本「接管采样率」开着时 SRC 是生效的；现在 SRC 归调音模式。
        // 为了让升级后听感不变，落回系统通路并关掉「按源采样率」（于是由我方接管 SRC）。
        //
        // ⚠️ 以前这里还有一句 `copy(offload = false)` —— 靠"关掉直通"来打开调音。
        // 直通功能已整条删除，系统通路现在恒可调音，那一句自然消失。
        //
        // 判据只能用**老键**：`KEY_RESAMPLE_RATE` 与 `KEY_FORCE_NATIVE_RATE` 都已随
        // 「自选采样率」一起退役，后者只在这次迁移里作为历史事实被读一次，
        // 不再参与任何运行时判断。`KEY_SRC_MIGRATION_DONE` 保证它只跑一次。
        if (!(_prefs?.getBoolean(KEY_SRC_MIGRATION_DONE, false) ?: false)) {
            val legacyForceNative = _prefs?.getBoolean(KEY_FORCE_NATIVE_RATE, false) ?: false
            val needMigrate = legacyForceNative && !_outputToggles.value.let { toggles ->
                com.annieplayer.annie_music.kt.core.audio.tuningEnabled(
                    _outputPath.value,
                    toggles,
                    engine = _tuningEngine.value,
                )
            }
            if (needMigrate) {
                _outputPath.value = OutputPath.systemMix
                _outputToggles.value = _outputToggles.value.copy(followSourceRate = false)
                persistMatrix()
            }
            _prefs?.edit()?.let { editor ->
                editor.putBoolean(KEY_SRC_MIGRATION_DONE, true)
                editor.apply()
            }
        }
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

    /**
     * 切换输出通路。
     *
     * 通路变了**不动开关** —— 开关表达的是"我想这样"，不是"这台设备能做到"。
     * 换设备后用户不用重新设置，这一条是整套模型的前提。
     */
    fun setOutputPath(path: OutputPath) {
        _outputPath.value = path
        persistMatrix()
    }

    /** 改通路的开关。两个开关各管一摊（只有和自己通路相关的那个会生效）。 */
    fun setOutputToggles(toggles: OutputToggles) {
        _outputToggles.value = toggles
        persistMatrix()
    }

    /** 落盘：通路 + 两个开关。老键（`KEY_AUDIO_MODE`）不再写，留给迁移读一次。 */
    private fun persistMatrix() {
        val t = _outputToggles.value
        _prefs?.edit()?.let { editor ->
            editor.putString(KEY_OUTPUT_PATH, _outputPath.value.key)
            editor.putBoolean(KEY_TOGGLE_FOLLOW_SOURCE, t.followSourceRate)
            editor.putBoolean(KEY_TOGGLE_BIT_PERFECT, t.bitPerfect)
            editor.apply()
        }
    }

    fun setTuningEngine(engine: TuningEngine) {
        _tuningEngine.value = engine
        _prefs?.edit()?.putString(KEY_TUNING_ENGINE, engine.key)?.apply()
    }

    fun setGraphicBand(index: Int, gainDb: Float) {
        val next = _graphicGains.value.copyOf()
        if (index !in next.indices) return
        next[index] = gainDb
        _graphicGains.value = next
        _prefs?.edit()?.putString(KEY_GRAPHIC_GAINS, encodeFloats(next))?.apply()
    }

    fun setGraphicAmp(db: Float) {
        _graphicAmp.value = db
        _prefs?.edit()?.putFloat(KEY_GRAPHIC_AMP, db)?.apply()
    }

    fun applyGraphicPreset(preset: GraphicEqPreset) {
        val gains = preset.values.drop(1).toFloatArray()
        _graphicGains.value = gains
        // 预设只换 10 段曲线，Amp 保持用户当前值（避免每次切预设音量跳变）
        _prefs?.edit()?.putString(KEY_GRAPHIC_GAINS, encodeFloats(gains))?.apply()
    }

    fun setPeqBand(index: Int, band: PeqBand) {
        val next = _peqBands.value.toMutableList()
        if (index !in next.indices) return
        next[index] = band
        _peqBands.value = next
        _prefs?.edit()?.putString(KEY_PEQ_BANDS, encodePeq(next))?.apply()
    }

    fun setPeqAmp(db: Float) {
        _peqAmp.value = db
        _prefs?.edit()?.putFloat(KEY_PEQ_AMP, db)?.apply()
    }

    fun resetPeq() {
        val next = defaultPeqBands()
        _peqBands.value = next
        _peqAmp.value = 0f
        _prefs?.edit()
            ?.putString(KEY_PEQ_BANDS, encodePeq(next))
            ?.putFloat(KEY_PEQ_AMP, 0f)
            ?.apply()
    }

    fun setMseb(values: MsebValues) {
        _mseb.value = values
        _prefs?.edit()?.putString(KEY_MSEB, encodeMseb(values))?.apply()
    }

    fun setLoudnessNormalization(value: Boolean) {
        _loudnessNormalization.value = value
        _prefs?.edit()?.putBoolean(KEY_LOUDNESS, value)?.apply()
    }

    fun setDsdMode(mode: DsdOutputMode) {
        _dsdMode.value = mode
        _prefs?.edit()?.putString(KEY_DSD_MODE, mode.key)?.apply()
    }

    /**
     * 只接受 44.1 家族的固定档位，外加「跟随系统」哨兵值（0）。
     *
     * 其它值一律忽略 —— 脏值不能把播放搞哑。
     */
    fun setDsdPcmRate(level: DsdLevel, rate: Int) {
        if (rate != DSD_RATE_FOLLOW_SYSTEM && rate !in DSD_PCM_RATES) return
        _dsdPcmRates.value = _dsdPcmRates.value + (level to rate)
        _prefs?.edit()?.putInt(KEY_DSD_PCM_RATE_PREFIX + level.name, rate)?.apply()
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

private fun encodeFloats(values: FloatArray): String = values.joinToString(",")

private fun decodeFloats(raw: String?, size: Int): FloatArray {
    val out = FloatArray(size)
    val parts = raw?.split(",") ?: return out
    for (i in 0 until minOf(size, parts.size)) {
        out[i] = parts[i].toFloatOrNull() ?: 0f
    }
    return out
}

private fun encodePeq(bands: List<PeqBand>): String = bands.joinToString(";") { band ->
    "${if (band.enabled) 1 else 0}|${band.freq}|${band.q}|${band.gainDb}|${band.type.key}"
}

private fun decodePeq(raw: String?): List<PeqBand> {
    if (raw.isNullOrBlank()) return defaultPeqBands()
    val parsed = raw.split(";").mapNotNull { item ->
        val p = item.split("|")
        if (p.size != 5) return@mapNotNull null
        PeqBand(
            enabled = p[0] == "1",
            freq = p[1].toFloatOrNull() ?: 1000f,
            q = p[2].toFloatOrNull() ?: 1f,
            gainDb = p[3].toFloatOrNull() ?: 0f,
            type = FilterType.fromKey(p[4]),
        )
    }
    return if (parsed.size == 32) parsed else defaultPeqBands()
}

private fun encodeMseb(v: MsebValues): String = listOf(
    v.masterTemperature, v.bass0, v.bass1, v.thickness, v.vocal,
    v.female, v.maleVocal, v.instruments, v.air, v.preamp,
).joinToString(",")

private fun decodeMseb(raw: String?): MsebValues {
    val values = raw?.split(",")?.mapNotNull { it.toIntOrNull() } ?: return MsebValues()
    if (values.size != 10) return MsebValues()
    return MsebValues(
        masterTemperature = values[0],
        bass0 = values[1],
        bass1 = values[2],
        thickness = values[3],
        vocal = values[4],
        female = values[5],
        maleVocal = values[6],
        instruments = values[7],
        air = values[8],
        preamp = values[9],
    )
}
