package com.annieplayer.annie_music.kt.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 调音配置：**引擎 = 无 时一条滤波器都不给**。
 *
 * 这条不是锦上添花。三个引擎的数值是各自独立保存的，切到「无」再切回来必须原样恢复 ——
 * 所以「无」不能靠"把数值清零"实现，只能在 `specs()` / `preampDb()` 里返回空。
 * 也不能实现成"给一条 0 dB 的滤波器"：那也要算系数、也要过 biquad，仍有舍入与相位代价，
 * 而用户要的是"完全不碰"。
 */
class TuningConfigTest {

    private val graphic = TuningConfig(
        engine = TuningEngine.graphic,
        graphicGains = floatArrayOf(6f, -4f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        graphicAmpDb = 3f,
    )

    @Test
    fun `引擎选无时不产生任何滤波器或前级`() {
        val none = graphic.copy(engine = TuningEngine.none)
        assertTrue(none.specs().isEmpty())
        assertEquals(0f, none.preampDb(), 1e-6f)
        assertFalse(none.hasToneChange())
        assertFalse(none.hasBoost())
    }

    @Test
    fun `切回原来的引擎 数值原样恢复`() {
        val none = graphic.copy(engine = TuningEngine.none)
        val back = none.copy(engine = TuningEngine.graphic)
        assertEquals(2, back.specs().size)
        assertEquals(3f, back.preampDb(), 1e-6f)
        assertTrue(back.hasToneChange())
        assertTrue(back.hasBoost())
    }

    @Test
    fun `老存档没有 none 这个键 落回均衡器`() {
        // 关键：不能落回 none。那等于替升级上来的用户把调音关了。
        assertEquals(TuningEngine.graphic, TuningEngine.fromKey(null))
        assertEquals(TuningEngine.graphic, TuningEngine.fromKey("graphic"))
        assertEquals(TuningEngine.none, TuningEngine.fromKey("none"))
    }
}
