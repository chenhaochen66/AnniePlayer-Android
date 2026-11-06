package com.annieplayer.annie_music.kt.core.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.pow

/**
 * 设备侧硬件音量的能力与当前档位（**1/256 dB 衰减量**：0 = 不衰减，负值 = 衰减多少 dB）。
 *
 * 数值越小越响之外要记住一点：UAC 的 Volume 控制语义就是"衰减量"，不是"增益"。
 * 所以 `db` 可以直接当 dB 用，不需要再取反。
 */
data class HardwareVolumeCaps(
    val min: Int,
    val max: Int,
    val step: Int,
    val value: Int,
) {
    val db: Float get() = value / 256f

    fun stepped(up: Boolean): Int =
        (if (up) value + step else value - step).coerceIn(min, max)

    fun withValue(v: Int): HardwareVolumeCaps = copy(value = v.coerceIn(min, max))
}

/**
 * 独占通路的**专属音量**（音量键 + 右侧音量条的唯一真相）。
 *
 * ## 为什么必须单独一层
 *
 * 独占流绕开 AudioFlinger，`AudioTrack` / mixer 的那条音量通路对它不存在 —— 系统音量键
 * 调的是一个和声音无关的旋钮。所以这条通路的音量只能由我方记、我方施加。
 *
 * ## 与输出胶囊无关
 *
 * 这里改增益**不动** pill 的层级 / 颜色 / 文案（bit perfect 标志不随音量变化）。
 * 音量是用户对响度的选择，不是"链路做了什么处理"的证据，两者不能混在一个标志里。
 *
 * ## 单位
 *
 * 存 dB，不存 0..1。人耳对数感知，线性百分比的手感是错的（0.5 线性 ≈ −6 dB，
 * 听起来只"小一点"，到 0.1 才明显）。而且 dB 能直接算出代价：每 −6 dB 约丢 1 bit，
 * 24 bit 源在 −24 dB 只剩 20 bit —— 音量条上要显示这个数，用户自己判断值不值。
 *
 * ## DSD 直出
 *
 * DoP / Native 送的不是 PCM 样本，乘任何增益都会连帧标记 / 1-bit 流一起毁掉。
 * 那时 [linear] 恒为 1（不衰减），按键仍被**消费**（[step] 返回 true），
 * 由界面说明"由设备控制"，而不是让按键看起来坏了。
 */
object ExclusiveVolume {

    /** 静音档就是 [MIN_DB]：−60 dB 已在绝大多数设备的噪声底之下。 */
    const val MIN_DB = -60f
    const val MAX_DB = 0f
    const val STEP_DB = 1f

    /** 持久化的最小接口。生产实现是 SharedPreferences，测试用内存表。 */
    interface Store {
        fun get(key: String): Float?
        fun put(key: String, db: Float)
    }

    data class State(
        /** 当前音量的归属通路（`OutputPath.key`）；空 = 还没上报 */
        val routeKey: String = "",
        /** 现在是否有独占流可调（USB 独占 / AAudio 独占且已生效） */
        val available: Boolean = false,
        /** 这一首是 DSD 直出（DoP / Native）：可以按，但不能改我方增益 */
        val raw: Boolean = false,
        /**
         * 设备侧硬件音量（Feature Unit 的 Volume 控制）。非 null 时**优先**：
         * 衰减在 DAC 内部完成，送出去的 PCM 一个字节都不动。
         */
        val hardware: HardwareVolumeCaps? = null,
        val db: Float = MAX_DB,
        /** 每次按键自增。界面靠它重置"自动消失"计时（不用另带时间戳）。 */
        val tick: Long = 0L,
    ) {
        /** 按键被消费、但增益不可改 —— 界面据此说明原因。 */
        val locked: Boolean get() = available && raw && hardware == null

        /** 当前实际生效的衰减（硬件与软件共用一套 dB，界面只显示这一个数）。 */
        val effectiveDb: Float get() = hardware?.db ?: db
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** 持久化后端；未注入时退化为纯内存（语义不变，只是重启不保留）。 */
    @Volatile
    private var store: Store? = null

    /**
     * 硬件音量的下发口（收到 1/256 dB 衰减量）。null = 这条流没有 Hardware Volume。
     *
     * 它只做一次 atomic store，真正的控制传输在 native 事件线程上排队执行
     * （见 `applyPendingHardwareVolume`）—— 在播放线程上做同步控制传输会直接欠载。
     */
    @Volatile
    private var hwWriter: ((Int) -> Unit)? = null

    fun init(store: Store) {
        this.store = store
    }

    /** 施加到 PCM 上的线性增益。raw / 非独占 / **硬件音量可用** 时一律 1f。 */
    val linear: Float
        get() {
            val s = _state.value
            if (!s.available || s.raw) return 1f
            if (s.hardware != null) return 1f
            return linearOf(s.db)
        }

    /** 当前按键是否归我们管（调用方据此决定吞事件还是交回系统）。 */
    val handles: Boolean get() = _state.value.available

    /**
     * 报告设备侧硬件音量能力（每次 `configure` 建立流之后调一次）。
     *
     * [writer] 收到的是 1/256 dB 衰减量，由 native 侧排队下发（不得在播放线程做控制传输）。
     * 传 null 表示这条流没有硬件音量，退回软件衰减。
     */
    fun attachHardware(caps: HardwareVolumeCaps?, writer: ((Int) -> Unit)?) {
        hwWriter = writer
        val cur = _state.value
        if (caps == null) {
            if (cur.hardware != null) _state.value = cur.copy(hardware = null)
            return
        }
        if (cur.hardware != caps) _state.value = cur.copy(hardware = caps)
    }

    fun setRoute(routeKey: String, available: Boolean, raw: Boolean) {
        val cur = _state.value
        if (cur.routeKey == routeKey && cur.available == available && cur.raw == raw) return
        // 换通路 / 重新可用时才回读存档：同一条通路内的按键不能被一次 configure 打回旧值
        val db = if (available && routeKey != cur.routeKey) {
            (store?.get(keyOf(routeKey)) ?: MAX_DB).coerceIn(MIN_DB, MAX_DB)
        } else {
            cur.db
        }
        _state.value = cur.copy(routeKey = routeKey, available = available, raw = raw, db = db)
    }

    /**
     * 一次按键。
     *
     * @return true = 已消费（调用方必须吞掉 key event，系统音量条才不会弹）；
     *         false = 这条通路不归我们管，交回系统。
     */
    fun step(up: Boolean): Boolean {
        val s = _state.value
        if (!s.available) return false
        // 硬件音量优先：衰减在 DAC 内部，PCM 一个字节都不动。**DSD 直出也一样能用**
        // （那是设备自己的衰减，不碰 1-bit 流），所以这一支不看 raw。
        val hw = s.hardware
        if (hw != null) {
            val next = hw.stepped(up)
            if (next != hw.value) {
                val updated = hw.withValue(next)
                _state.value = s.copy(hardware = updated, tick = s.tick + 1)
                hwWriter?.invoke(next)
            } else {
                _state.value = s.copy(tick = s.tick + 1)
            }
            return true
        }
        if (s.raw) {
            // 没有硬件音量又碰上 DSD 直出：乘增益会毁掉 1-bit 流。仍要消费并刷新界面，
            // 静默无效会让人以为按键坏了。
            _state.value = s.copy(tick = s.tick + 1)
            return true
        }
        val next = if (up) {
            (s.db + STEP_DB).coerceAtMost(MAX_DB)
        } else {
            (s.db - STEP_DB).coerceAtLeast(MIN_DB)
        }
        _state.value = s.copy(db = next, tick = s.tick + 1)
        store?.put(keyOf(s.routeKey), next)
        return true
    }

    /** 供界面滑块直接设定（0..1 线性）。越界静默夹住。 */
    fun setLinear(linear: Float) {
        val s = _state.value
        if (!s.available) return
        val l = linear.coerceIn(0f, 1f)
        // 硬件音量优先：把 0..1 线性摊到设备声明的档位区间上。**DSD 直出同样适用**
        // —— 衰减在 DAC 内部，不碰 1-bit 流。
        val hw = s.hardware
        if (hw != null) {
            val v = (hw.min + (hw.max - hw.min) * l).toInt().coerceIn(hw.min, hw.max)
            if (v != hw.value) {
                _state.value = s.copy(hardware = hw.withValue(v), tick = s.tick + 1)
                hwWriter?.invoke(v)
            }
            return
        }
        if (s.raw) return
        val db = dbOf(l).coerceIn(MIN_DB, MAX_DB)
        if (db == s.db) return
        _state.value = s.copy(db = db, tick = s.tick + 1)
        store?.put(keyOf(s.routeKey), db)
    }

    /** 测试用：清回初始态（不走真实 store）。 */
    internal fun resetForTest() {
        _state.value = State()
        store = null
        hwWriter = null
    }

    private fun keyOf(routeKey: String): String = "exclusive-vol:$routeKey"
}

/** dB → 线性。−60 dB → 0.001。 */
internal fun linearOf(db: Float): Float = 10f.pow(db / 20f)

/** 线性 → dB。0 及以下按静音档处理。 */
internal fun dbOf(linear: Float): Float =
    if (linear <= 0.0001f) ExclusiveVolume.MIN_DB else 20f * kotlin.math.log10(linear)

/**
 * 当前增益等效损失了多少位深（用于音量条上的代价提示）。
 *
 * 每 6.02 dB 掉 1 bit 是量化噪声的常识：位深提供的动态范围 ≈ `6.02 * bits`。
 * `sourceBits` 为 0（未知）时返回 0，宁可不写也不猜。
 */
internal fun effectiveBits(sourceBits: Int, db: Float): Int {
    if (sourceBits <= 0) return 0
    val lost = (-db / 6.0206f)
    return (sourceBits - lost).toInt().coerceIn(1, sourceBits)
}
