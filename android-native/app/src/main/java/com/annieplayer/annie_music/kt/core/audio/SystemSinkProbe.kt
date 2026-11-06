package com.annieplayer.annie_music.kt.core.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build

/**
 * 系统通路（`AudioTrack`）**真的**能不能建出某个采样率 —— 实测，不是读声明。
 *
 * ## 为什么需要它
 *
 * 判定"这个率能不能用"以前只有一个判据：
 *
 * ```kotlin
 * platformCanBuildRate(rate)   // AudioFormat.Builder().build() 过不过
 * ```
 *
 * 它只回答 **"`AudioFormat` 这个 API 让不让我构造"**，不回答"建不建得出轨"。
 * 192k 以内两者几乎等价（AOSP 的 HAL 都支持常见档位），所以这个假设一直没暴露；
 * 一旦要到 352.8k / 384k / 768k，它就不成立了 —— 那些率要过两道关：
 *
 * 1. `AudioFormat` 的 `SAMPLE_RATE_HZ_MAX`（192000，写在 framework 里）—— 对象构造校验；
 * 2. `AudioTrack` 真的建出来 + HAL 接受 —— 建轨校验。
 *
 * 第 1 关由 [platformCanBuildRate] 负责（false ⇒ 一定不行，且它**不会误伤**：
 * 拿不到合法 `AudioFormat` 就调不到 `AudioTrack`）。第 2 关才是本文件要补的。
 *
 * ## 判定分三层，这里只做第 ② 层
 *
 * | 层 | 问题 | 证据 |
 * |---|---|---|
 * | ① 构造 | 这个率合法吗 | `AudioFormat.Builder().build()` ← [platformCanBuildRate] |
 * | ② 建轨 | 建得出轨道吗 | `AudioTrack.state == STATE_INITIALIZED` ← **本文件** |
 * | ③ 在跑 | HAL 有没有偷偷改率 | AudioTrack **没有**这个 API（AAudio 有 `stream.sampleRate()`） |
 * | ④ 跑得动 | 会不会 underrun | `getUnderrunCount()`，只能播放中统计 |
 *
 * ③ 在 `AudioTrack` 上做不到：`getSampleRate()` 回显的是构造时传进去的值，
 * HAL 悄悄改成 192k 它照样报 384k。所以本文件**只声称"建得出来"**，
 * 不声称"按这个率在跑"。后者由播放中的 underrun 统计兜（不在这里判）。
 *
 * ## 缓存
 *
 * 缓存键必须够细，少一项就串味：
 *
 * - `deviceId`：换 DAC 就整表失效（同一台机器接不同小尾巴，能力完全不同）
 * - `route`：USB / 有线 / 扬声器是三条不同路径
 * - `rate`：当然
 * - `depth`：384k/16bit 建得出不代表 384k/24bit 建得出
 *
 * ## 探测的**代价**与使用纪律
 *
 * 每建一次轨都会短暂占用输出（毫秒级，`release` 之后释放）。所以：
 *
 * - **必须懒探测**：只在真的要用某个率时探，不在启动时把所有档都探一遍；
 * - **结果用于默认值与灰档理由，不用于永久禁用** —— 换 ROM / 换设备就变了；
 * - 失败**不进永久黑名单**（同 `AaudioSink` 那条纪律），[invalidate] 一律清空。
 */
internal class SinkProbeStore(
    /** 真建一次轨：`(rate, depth, preferredDeviceId) -> 建得出来`。测试注入假实现。 */
    private val opener: (Int, Int, Int) -> Boolean,
) {

    /** 一条缓存记录的键。缺任一项都会把"这台设备的能力"错误套到另一台设备上。 */
    data class Key(
        val deviceId: Int,
        val route: AudioRoute,
        val rate: Int,
        val depth: Int,
    )

    private val cache = HashMap<Key, Boolean>()

    /** 探过没有（区分"探了不行"和"还没探" —— 界面措辞不同） */
    fun isProbed(key: Key): Boolean = cache.containsKey(key)

    fun canBuild(key: Key): Boolean {
        cache[key]?.let { return it }
        val ok = opener(key.rate, key.depth, key.deviceId)
        cache[key] = ok
        return ok
    }

    /**
     * 候选里**实测可用**的最高率；一档都不行返回 0（不猜）。
     *
     * 从高往低试，第一档通过的就是上限 —— "上限"是设备属性，不是产品常量。
     * 这里就是用来取代 `MAX_DSD_PCM_RATE` 那种写死的常量。
     */
    fun highestUsable(
        candidates: List<Int>,
        deviceId: Int,
        route: AudioRoute,
        depth: Int,
    ): Int = candidates
        .asSequence()
        .filter { it > 0 }
        .distinct()
        .sortedDescending()
        .firstOrNull { canBuild(Key(deviceId, route, it, depth)) }
        ?: 0

    fun invalidate() = cache.clear()

    /**
     * **只读缓存**：已知可用的最高率。没探过就是 0。
     *
     * 与 [highestUsable] 的区别是它**不建轨** —— 状态上报（每首歌都会走一次）
     * 必须用它，否则界面刷新会变成十几次建轨。真正的探测只发生在"要决定用哪一档"时。
     */
    fun knownHighest(
        candidates: List<Int>,
        deviceId: Int,
        route: AudioRoute,
        depth: Int,
    ): Int = candidates
        .asSequence()
        .filter { it > 0 }
        .filter { cache[Key(deviceId, route, it, depth)] == true }
        .maxOrNull()
        ?: 0

    /** 测试用：拿到当前缓存条数 */
    fun size(): Int = cache.size
}

internal object SystemSinkProbe {

    /**
     * 「实测可用最高率」的候选表（从高往低试）。
     *
     * 只用于**显示**和"上限是多少"，不用于计划 —— 真正的选率判据见
     * `MediaPlayerBackend._currentPlan` 的 `canBuildRate`。
     *
     * 含 44.1 / 48 两个家族到 768k：DSD 的整数抽取只走 44.1 家族，
     * 但 PCM 母带会出现在任意一档，界面要能如实报"这台机器实测到哪"。
     */
    val PROBE_CANDIDATES: List<Int> = listOf(
        44_100, 48_000, 88_200, 96_000, 176_400, 192_000,
        352_800, 384_000, 705_600, 768_000,
    )

    /**
     * 第 ① 层之外的那一半：**真建一次 `AudioTrack`**。
     *
     * 只建、不写、立刻 `release()`。用 `MODE_STREAM` + `getMinBufferSize`，
     * 与真实播放的构造参数保持一致 —— 用 `MODE_STATIC` 探出来的结论不可信
     * （静态模式走的 buffer 分配路径不同）。
     */
    private fun openReal(rate: Int, depth: Int, deviceId: Int): Boolean {
        if (rate <= 0) return false
        val encoding = when (depth) {
            16 -> AudioFormat.ENCODING_PCM_16BIT
            // 24 Bit 没有公开的 `AudioFormat` 常量（AOSP 那个 `ENCODING_PCM_24BIT_PACKED` 是 @hide），
            // 而且我们真正交给 sink 的一律是 float（`PLAN_FLOAT_DEPTH`）—— 用 float 探更贴近真实播放。
            else -> AudioFormat.ENCODING_PCM_FLOAT
        }
        // 第 ① 层：拿不到合法 AudioFormat 就什么都不用谈了（这也是"不会误伤"的那一半）
        val format = runCatching {
            AudioFormat.Builder()
                .setSampleRate(rate)
                .setEncoding(encoding)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()
        }.getOrNull() ?: return false

        val minBuffer = runCatching {
            AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, encoding)
        }.getOrDefault(0)
        if (minBuffer <= 0) return false

        val track = runCatching {
            AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
                format,
                minBuffer,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
        }.getOrNull() ?: return false

        return try {
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                false
            } else {
                // 路由校验：建出来了，但要确认它真的落在期望的设备上。
                // 不做这一步的话，"USB 小尾巴插着、声音从扬声器出来"也会被记成成功。
                // `getRoutedDevice()` 是 API 24+，更老的系统上跳过这一项（不因此判失败）。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && deviceId > 0) {
                    val routed = runCatching { track.routedDevice?.id }.getOrNull()
                    routed == null || routed == deviceId
                } else {
                    true
                }
            }
        } finally {
            runCatching { track.release() }
        }
    }

    private val store = SinkProbeStore(::openReal)

    fun canBuild(deviceId: Int, route: AudioRoute, rate: Int, depth: Int): Boolean =
        store.canBuild(SinkProbeStore.Key(deviceId, route, rate, depth))

    fun isProbed(deviceId: Int, route: AudioRoute, rate: Int, depth: Int): Boolean =
        store.isProbed(SinkProbeStore.Key(deviceId, route, rate, depth))

    fun highestUsable(
        candidates: List<Int>,
        deviceId: Int,
        route: AudioRoute,
        depth: Int,
    ): Int = store.highestUsable(candidates, deviceId, route, depth)

    /** 只读缓存（不建轨）：状态上报用 */
    fun knownHighest(
        candidates: List<Int>,
        deviceId: Int,
        route: AudioRoute,
        depth: Int,
    ): Int = store.knownHighest(candidates, deviceId, route, depth)

    /** 设备 / 路由变化时整表失效（缓存键里已经有这两项，但换了 ROM 也得能手动清） */
    fun invalidate() = store.invalidate()
}
