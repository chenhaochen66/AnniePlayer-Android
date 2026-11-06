package com.annieplayer.annie_music.kt.core.audio

import kotlin.math.abs

/**
 * 调音引擎：同一时间只跑一条链（对齐海贝：均衡器 / PEQ / MSEB 互斥）。
 *
 * ## 为什么有「无」
 *
 * 以前「我不想要任何 DSP」只能靠输出通路的开关表达（系统通路关掉「直通」以外的路、
 * 或 AAudio 的 Bit Perfect），而那些开关讲的是**别的事**：
 *
 * - 「直通」（offload）说的是**不解码** —— 压缩帧整段交给 HAL，所以顺带没法调音；
 * - 「Bit Perfect」说的是**独占流里一个采样都别动**。
 *
 * 两者都不是「我解码，但我不想调音」。而且它们都挂在某一条通路上 —— 换个通路
 * 就没有对应的表达方式了（USB 独占、蓝牙下无从下手）。
 *
 * 「无」把这个意图提到调音自己的位置上：**引擎 = 无 ⇒ 这一首没有 DSP**，
 * 与通路无关，与直通关不关也无关。派生关系见 `tuningEnabled`。
 */
enum class TuningEngine(val key: String, val label: String) {
    /** 不做任何 DSP：不加滤波器、不加前级、不限幅。（放最前 = 它是「关闭」这一档） */
    none("none", "无"),
    graphic("graphic", "均衡器"),
    parametric("parametric", "PEQ"),
    mseb("mseb", "MSEB");

    companion object {
        /**
         * 老存档里没有 `none` 这个值（它是后加的），所以"读不到"仍落回 `graphic` ——
         * 不能落回 `none`，那等于替升级上来的用户把调音关了。
         */
        fun fromKey(key: String?): TuningEngine =
            entries.firstOrNull { it.key == key } ?: graphic
    }
}

/** RBJ biquad 类型（对齐海贝 PEQ 的 Low Shelf / Peaking / High Shelf）。 */
enum class FilterType(val key: String, val label: String) {
    lowShelf("lowShelf", "低架"),
    peaking("peaking", "峰值"),
    highShelf("highShelf", "高架");

    companion object {
        fun fromKey(key: String?): FilterType =
            entries.firstOrNull { it.key == key } ?: peaking
    }
}

/** 单个 biquad 的规格；系数由采样率在 [BiquadEq] 里预计算。 */
data class BiquadSpec(
    val type: FilterType,
    val freq: Float,
    val q: Float,
    val gainDb: Float,
)

/** 10 段图示 EQ 频点（对齐海贝 31–16K）。 */
val GraphicEqFrequencies: FloatArray = floatArrayOf(
    31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f,
)

/** 图示 EQ 预设；`values[0]` 是 Amp（前级），后面 10 个是各段增益。 */
data class GraphicEqPreset(val key: String, val label: String, val values: FloatArray)

val GraphicEqPresets: List<GraphicEqPreset> = listOf(
    GraphicEqPreset("flat", "平直", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
    GraphicEqPreset("pop", "流行", floatArrayOf(-6f, 3f, 1f, 0f, -2f, -4f, -4f, -2f, 0f, 1f, 2f)),
    GraphicEqPreset("rock", "摇滚", floatArrayOf(-6f, -2f, 0f, 2f, 4f, -2f, -2f, 0f, 0f, 4f, 4f)),
    GraphicEqPreset("jazz", "爵士", floatArrayOf(-6f, 0f, 0f, 0f, 4f, 4f, 4f, 0f, 2f, 3f, 4f)),
    GraphicEqPreset("classic", "古典", floatArrayOf(-6f, 0f, 8f, 8f, 4f, 0f, 0f, 0f, 0f, 2f, 2f)),
    GraphicEqPreset("dance", "舞曲", floatArrayOf(-6f, -2f, 3f, 4f, 1f, -2f, -2f, 0f, 0f, 4f, 4f)),
    GraphicEqPreset("metal", "金属", floatArrayOf(-6f, -6f, 0f, 0f, 0f, 0f, 0f, 2f, 0f, 2f, 0f)),
    GraphicEqPreset("voice", "人声", floatArrayOf(-6f, -4f, 0f, 2f, 1f, 0f, 0f, 0f, 0f, -4f, -6f)),
    GraphicEqPreset("blues", "蓝调", floatArrayOf(-6f, -2f, 0f, 2f, 1f, 0f, 0f, 0f, 0f, -2f, -4f)),
)

/** PEQ 单段（对齐海贝：频率 30–20000、Q 0.2–20、增益 -9~+9、三种滤波器）。 */
data class PeqBand(
    val enabled: Boolean = true,
    val freq: Float = 1000f,
    val q: Float = 1f,
    val gainDb: Float = 0f,
    val type: FilterType = FilterType.peaking,
)

/** 32 段默认值：30 Hz–20 kHz 对数分布，全部 Peaking、0 dB。 */
fun defaultPeqBands(): List<PeqBand> = List(32) { index ->
    val freq = 30.0 * Math.pow(20000.0 / 30.0, index / 31.0)
    PeqBand(freq = freq.toFloat())
}

/** MSEB 九个滑块 + 前级（对齐海贝的 -100~100 / preamp -45~45）。 */
data class MsebValues(
    val masterTemperature: Int = 0,
    val bass0: Int = 0,
    val bass1: Int = 0,
    val thickness: Int = 0,
    val vocal: Int = 0,
    val female: Int = 0,
    val maleVocal: Int = 0,
    val instruments: Int = 0,
    val air: Int = 0,
    val preamp: Int = 0,
) {
    fun specs(): List<BiquadSpec> {
        val out = ArrayList<BiquadSpec>(12)
        fun add(type: FilterType, freq: Float, q: Float, gain: Float) {
            if (abs(gain) > 0.01f) out.add(BiquadSpec(type, freq, q, gain))
        }
        // 温度：正数偏暖（低频抬、高频降），负数偏冷
        add(FilterType.lowShelf, 250f, 0.7f, masterTemperature * 0.05f)
        add(FilterType.highShelf, 5000f, 0.7f, -masterTemperature * 0.05f)
        add(FilterType.lowShelf, 60f, 0.7f, bass0 * 0.08f)
        add(FilterType.lowShelf, 150f, 0.7f, bass1 * 0.08f)
        add(FilterType.peaking, 350f, 1.0f, thickness * 0.06f)
        add(FilterType.peaking, 400f, 1.0f, maleVocal * 0.06f)
        add(FilterType.peaking, 1200f, 1.0f, vocal * 0.06f)
        add(FilterType.peaking, 3000f, 1.0f, female * 0.06f)
        add(FilterType.peaking, 6000f, 1.0f, instruments * 0.05f)
        add(FilterType.highShelf, 12000f, 0.7f, air * 0.08f)
        return out
    }
}

/** MSEB 滑块定义：字段 + 文案（顺序按海贝设置页）。 */
enum class MsebSlider(val label: String, val get: (MsebValues) -> Int, val set: (MsebValues, Int) -> MsebValues) {
    temperature("温度", { it.masterTemperature }, { v, x -> v.copy(masterTemperature = x) }),
    bass0("低频 1", { it.bass0 }, { v, x -> v.copy(bass0 = x) }),
    bass1("低频 2", { it.bass1 }, { v, x -> v.copy(bass1 = x) }),
    thickness("厚度", { it.thickness }, { v, x -> v.copy(thickness = x) }),
    vocal("人声", { it.vocal }, { v, x -> v.copy(vocal = x) }),
    female("女声", { it.female }, { v, x -> v.copy(female = x) }),
    maleVocal("男声", { it.maleVocal }, { v, x -> v.copy(maleVocal = x) }),
    instruments("器乐", { it.instruments }, { v, x -> v.copy(instruments = x) }),
    air("空气感", { it.air }, { v, x -> v.copy(air = x) }),
    preamp("前级", { it.preamp }, { v, x -> v.copy(preamp = x) }),
}

/** 一次调音的完整配置；音频线程只读。 */
data class TuningConfig(
    val engine: TuningEngine = TuningEngine.graphic,
    val graphicGains: FloatArray = FloatArray(GraphicEqFrequencies.size),
    val graphicAmpDb: Float = 0f,
    val peqBands: List<PeqBand> = emptyList(),
    val peqAmpDb: Float = 0f,
    val mseb: MsebValues = MsebValues(),
    val loudnessDb: Float = 0f,
    val limiter: Boolean = false,
) {
    fun specs(): List<BiquadSpec> = when (engine) {
        // 「无」= 一条滤波器都不给。不是"给一条 0 dB 的"——那也会量化和相位代价。
        TuningEngine.none -> emptyList()

        TuningEngine.graphic -> GraphicEqFrequencies.mapIndexed { index, freq ->
            BiquadSpec(FilterType.peaking, freq, 1.41f, graphicGains.getOrElse(index) { 0f })
        }.filter { abs(it.gainDb) > 0.01f }

        TuningEngine.parametric -> peqBands
            .filter { it.enabled && abs(it.gainDb) > 0.01f }
            .map { BiquadSpec(it.type, it.freq, it.q, it.gainDb) }

        TuningEngine.mseb -> mseb.specs()
    }

    fun preampDb(): Float = when (engine) {
        TuningEngine.none -> 0f
        TuningEngine.graphic -> graphicAmpDb
        TuningEngine.parametric -> peqAmpDb
        TuningEngine.mseb -> mseb.preamp.toFloat()
    }

    /** 引擎是否有实际改动（用于播放页徽章 / 限幅判断） */
    fun hasToneChange(): Boolean = specs().isNotEmpty() || abs(preampDb()) > 0.01f

    fun hasBoost(): Boolean = specs().any { it.gainDb > 0.01f } || preampDb() > 0.01f
}
