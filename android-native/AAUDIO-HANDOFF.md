# AAudio 独占（Bit Perfect 第二级）接手文档

> 目标读者：**接手这块代码的人（或压缩上下文后的自己）**。
> 配套阅读：根目录 `AGENTS.md`（硬规则）、`core/audio/` 下的源码注释（每个反直觉处都写了"为什么"）。
> 本文只讲**结论与教训**，不重复代码细节；代码里的注释才是最终事实来源。

---

## 1. 这套东西在做什么

播放链路（Media3 1.4.1）：

```
App 资源 → [可选 ffmpeg 软解] → PcmAudioSink（调音/重采样处理器链）
          → AaudioSink（AAudio 独占，内核 = Oboe）
          → 或 DefaultAudioSink（系统 mixer，回落）
```

- **透明模式（transparent）**：目标是 Bit Perfect。`OutputPlan.sampleRate = 0`（不做 SRC），
  优先走 AAudio 独占（EXCLUSIVE + MMAP，无需任何权限），开不出来就回落系统通路。
- **调音模式（tuned）**：走 `DefaultAudioSink` + 软件音量，用户调音优先。
- `AaudioSink` 是一个完整的 `AudioSink` 实现：`configure()` 里**一次性**决定这一首用
  AAudio 还是系统 sink，之后整首不切换；运行中失败通过 `onEngineFailure` 通知上层
  重建音源，并把该采样率记进 `failedRates`（进程级），避免"失败→重建→再失败"死循环。

### 独占内核只有一套：Oboe

以前有两套（手写 AAudio 推模式 `RAW` + Oboe 回调拉模式），**RAW 已经整体删掉**：
`AAUDIO_BACKEND` 开关、`dsp/NativeAaudioStream.kt`、`dsp/AaudioBackend.kt`、
`native-aaudio-probe.cpp` 的播放内核段全部移除。`libanniedsp.so` 现在只剩
**独占能力探测** + DSP + ffmpeg。

删 RAW 的理由不是"Oboe 更好看"，而是它的那套补偿逻辑（写线程、队列高水位、
`STALL_TIMEOUT_MS` 消费看门狗、时间轴代际号）**只在推模式下才需要**。
保留它就等于留着一条永不执行的死路径 + 一个能唤醒它的开关。

---

## 2. 关键文件

| 文件 | 职责 |
|---|---|
| `core/audio/AaudioSink.kt` | **核心**。AudioSink 契约实现：引擎选择 + 位置模型 + 结束判定。**没有写线程** |
| `core/audio/PcmAudioSink.kt` | 两条路共用的包装层：调音/重采样处理器链 + AAC offload 补 ADTS 头 |
| `core/audio/AnnieRenderersFactory.kt` | 装 DSP 链、决定 sink、给 ffmpeg renderer 配 `yieldForOffload` |
| `core/audio/AudioDirectProbe.kt` | 能力探测、失败记录、面板数据；`AAUDIO_SINK_ENABLED` 是总开关（A/B 用） |
| `core/audio/dsp/NativeOboeStream.kt` | 独占流的 JNI 封装（**唯一**的独占内核入口） |
| `core/audio/dsp/NativeAaudio.kt` | 只要探测：`nativeProbe` → `"EXCLUSIVE/48000/32/LOW_LATENCY"` |
| `cpp/native-oboe-stream.cpp` | Oboe 播放内核：环形缓冲 + 回调拉模式 + 参数探测 |
| `cpp/native-aaudio-probe.cpp` | **只剩探测**（dlopen `libaaudio.so` 的 API 表） |
| `cpp/thirdparty/oboe/` | Oboe 1.11.0 头文件（vendored，来源见该目录 README） |
| `core/audio/ffmpeg/FfmpegAudioDecoder.kt` | FLAC/ALAC 软解（`SimpleDecoder` 子类） |

JNI 命名注意：包名段 `annie_music` → 符号里必须是 `annie_1music`，写错只会得到 `err:jni`。

---

## 3. 已经修好、且**不要再动**的部分

### 3.1 位置模型

```kotlin
位置 = startTimeBasis() + framesToUs(已播帧)
已播帧 = min(playedFramesNow(), writtenFramesByUs)      // 必须封顶
startTimeBasis = startMediaTimeUs (+ outputStreamOffsetUs，若 PTS 未含)
```

- `startMediaTimeUs` = **第一块真正写进设备的 PCM 的 PTS**（`handleBuffer` 的
  `presentationTimeUs`）。等价于 `DefaultAudioSink.startMediaTimeUs`。
- `playedFramesNow()` 两级：`getTimestamp` → `getFramesRead`。
  **`getFramesRead` 在 native 侧只统计"真数据"帧**（回调填的静音不算），
  所以暂停时它冻结 —— "暂停 = 位置不动"是免费的。
- 首次写入前必须返回 `CURRENT_POSITION_NOT_SET`。

**踩过的坑（两个方向都不要走）**：
- ❌ 位置用"已写帧"直接当已播 → 超前一个缓冲；缓冲被 HAL 给成 25 秒时灌满就冻结。
- ❌ 试图"减去 `outputStreamOffsetUs`" → 位置小于 `startPositionUs` ⇒ `periodPositionUs`
  变负 ⇒ 进度条钉在原点、整条链失效。`positionTracker` 返回的是**内部换算后**的值。

### 3.2 seek：必须**重建整条流**

`flush()` 里不是 `requestFlush`，而是 `closeAaudio()` + 同参数重开 + 更新基准。

- 证据（MIUI 机器上实测）：`requestFlush` 之后 **`played(相对) > written`** ——
  设备里旧音频还在播，说明 `AAudioStream_requestFlush` **返回成功但不做事**。
- 官方 `DefaultAudioSink` 遇到不听话的输出层也是**重建 AudioTrack**。
- 代价：每次 seek 多几十毫秒；换来时间轴彻底干净。

### 3.3 句柄生命周期

```kotlin
private fun closeAaudio() {
    val h = handle
    handle = 0L                        // 所有读者立刻看到 0，走 NOT_SET
    if (h != 0L) NativeOboeStream.close(h)
}
```

- ❌ 先 `close` 后清零造成过 `SIGSEGV/SIGABRT`，崩溃栈：
  `ExoPlayer:Playb → playedUsNow → getCurrentPositionUs`（use-after-free）。
- native 的 `nativeClose` 是 `requestStop → close → 释放对象`，其中 `close()` 阻塞到
  回调线程退出，所以**不需要自己的写线程去 join**（那条线程已经不存在了）。

### 3.4 `hasPendingData()` 必须是真实语义

```kotlin
if (handle == 0L) return false
return writtenFramesByUs > playedFramesNow()
```

两个方向都试过，都会坏：
- **恒 `true`** → `isEnded()` 永远 false（**不会自动切歌**）；且播放器停在 `STATE_READY`
  进不了 `STATE_BUFFERING`，READY 下的加载判据是 `totalBufferedDuration < minBufferUs`，
  缓冲虚高时它不加载，与"解码器等数据"互相等待。
- **过早 `false`** → `isReady()` 掉下去 ⇒ BUFFERING ⇒ renderer 被 stop ⇒ 解码器停摆。

### 3.5 FLAC 永不让位给 MediaCodec

`AnnieRenderersFactory.yieldForOffload` 里对 `MimeTypes.AUDIO_FLAC` 直接 `false`。
三条依据：我们的 sink 不做 offload；MediaCodec 的 flac 解码器在 **seek 之后不再出帧**；
自研 ffmpeg 在同样操作下健康。**这也是"同一首歌两次播放表现不一致"的根源。**

### 3.6 `outputStreamOffsetUs` 必须实现

它是 `AudioSink` 的 **default 方法** —— 不实现照样编译通过，静默丢弃。
本机实测 offset ≈ **1e12 µs**，漏了它 `player.currentPosition` 会变成天文负数，
进度条不动、加载停止、播放停住。这条已经修好，别再"优化"掉。

---

## 4. 两个**致命**的认知错误（本轮最大收获）

### 4.1 `AAUDIO_SHARING_MODE_EXCLUSIVE` 是 0，不是 1

```c
typedef enum {
    AAUDIO_SHARING_MODE_EXCLUSIVE,   // = 0
    AAUDIO_SHARING_MODE_SHARED       // = 1
} aaudio_sharing_mode_t;
```

`constexpr int32_t kSharingExclusive = 1;` 这个错值被用了**两次**：

| 用途 | 实际行为 |
|---|---|
| 请求 `setSharingMode(builder, 1)` | 请求的是 **SHARED** |
| 判定 `getSharingMode(stream) == 1` | 回读 SHARED(1) 也等于 1 → **判成"独占"** |

两头互相抵消，于是面板五行「AAudio 独占 xx kHz 独占可用」**全是假阳性**，
这一级从来没有真的拿到过 MMAP 独占，声音一直走系统 mixer。这也解释了为什么
"独占生效"从来没有带来过任何音质变化。

**推论**：「OBOE 不生效、RAW 生效」这个观察本身是假的 —— 不生效的那个才是诚实的。

### 4.2 换设备后先看系统属性，不要先怀疑代码

```
adb shell getprop | grep -i aaudio
```

| 属性 | 含义 |
|---|---|
| `aaudio.mmap_policy` | `1`=NEVER（**MMAP 被禁，独占必然不可用**）/ `2`=AUTO / `3`=ALWAYS |
| `aaudio.mmap_exclusive_policy` | 同上，管 EXCLUSIVE |
| `aaudio.hw_burst_min_usec` | HAL 的最小 burst，2000 = 硬件本身支持低延迟 |

实测两台：

| 设备 | 系统 | `mmap_policy` | 独占结果 |
|---|---|---|---|
| 小米 23049RAD8C（MIUI） | Android 13 / SDK 33 | **1 (NEVER)** | 全部 SHARED。logcat 有 `MiAudioPolicyManager: Remove AUDIO_OUTPUT_FLAG_RAW … for forbiding ull track route` |
| PLQ110（ColorOS） | Android 16 / SDK 36 | 2 (AUTO) | 内置扬声器上仍全部 SHARED；**待测 USB/有线** |

**属性是 read-only**（`setprop` 被拒），只能刷 ROM 或换机。
判断一台新设备能不能走 B 级，先看这一条，再看 HAL profile。

另外 `PERFORMANCE_MODE_NONE` 也测过 —— **不能绕过**（MIUI 剥的是属性层的 MMAP，
不是 ULL 分支）。这条结论省得以后重试。

---

## 5. 排查方法论（这轮最大的教训）

1. **先读 ExoPlayer 的判据，再改代码。** 反编译命令：

   ```powershell
   $jp="C:\Program Files\Eclipse Adoptium\jdk-11.0.31.11-hotspot\bin\javap.exe"
   $jar="C:\Users\86188\.gradle\caches\9.3.1\transforms\ae23d587b0ae4435c8ee94f6fe792943\workspace\transformed\media3-exoplayer-1.4.1-api.jar"
   & $jp -p -c -classpath $jar androidx.media3.exoplayer.ExoPlayerImplInternal
   # 还反编译过：DefaultAudioSink / AudioTrackPositionTracker / MediaCodecAudioRenderer
   #              MediaCodecRenderer / DecoderAudioRenderer / DefaultLoadControl
   ```
   靠它确定的事实：
   - `periodPositionUs = playingPeriodHolder.toPeriodTime(rendererPositionUs) = renderer − startPositionUs`
   - `MediaCodecAudioRenderer.isReady() = hasPendingData() || super.isReady()`
   - `doSomeWork`：READY 时只要 `!renderersReadyOrEnded` 就落 BUFFERING，
     而出来要过 `shouldStartPlayback(rebuffering=true)`
   - `DefaultLoadControl` 默认 `min=max=50s`、起播 2.5s、**重缓冲 5s**、后缓冲 0
2. **A/B 对照优于推理。** `AAUDIO_SINK_ENABLED = false` 换成官方 `DefaultAudioSink`，
   一次就能把"是我方还是 ExoPlayer/数据源"切成两半。
3. **看门狗只信"数据在不在动"，绝不信 `getState`。**
   这条 MMAP 流长期返回 `st=4 (PAUSING)`，而同期数据正常流动。
4. **先量系统属性/平台能力，再量自己的代码。** 4.2 那条如果一开始就查，
   能省掉整轮 Oboe 参数排查。
5. **诊断代码用完删干净**（AGENTS 硬规则）。本轮临时用过：`probeSharingMode` 的
   多组合探测（**这个留下了**，它是能力探测不是调试日志）、各种计数打点。
6. 常用真机命令：

   ```powershell
   $adb="D:\ANSSDK\platform-tools\adb.exe"
   & $adb -s <serial> logcat -c
   & $adb -s <serial> logcat -d | Select-String "AAudio|Oboe|AnnieAudio"
   & $adb -s <serial> shell "dumpsys media_session"
   & $adb -s <serial> shell "getprop | grep -i aaudio"
   ```
   `dumpsys media_session` 的 `position` 是 `player.getCurrentPosition()`（ms）。
   输入注入在 MIUI 上被拦（`INJECT_EVENTS`），必要时用 scrcpy 的 tap（走另一条注入路径）。

---

## 6. 播放缓冲策略（`LoadControl`）

`MediaPlayerBackend._buildLoadControl()` 显式给了五个数，**不要再回退到默认值**：

| 参数 | 值 | 为什么 |
|---|---|---|
| `minBufferMs` | 20 s | 一屏之外的跨越量 |
| `maxBufferMs` | 30 s | 流媒体约 1 MB 以内，本地文件几乎瞬间满 |
| `bufferForPlaybackMs` | 1 s | 起播门槛（默认 2.5 s） |
| `bufferForPlaybackAfterRebufferMs` | **2 s** | **seek 手感主要看这个**（默认 5 s） |
| `backBufferMs` | 40 s | 往回拖不再重新解析/下载（默认 0） |
| `prioritizeTimeOverSizeThresholds` | true | 只按时间判，行为不随码率漂移 |

默认值那套是给视频调的：`min=max=50s` 会让判据被 12.5 MB 字节兜底接管，
同一首歌 flac / aac 表现不同。

---

## 7. 已知未完成 / 待验证

- [ ] **USB DAC / 有线耳机下的独占**（PLQ110 的 `mmap_policy=2`，扬声器上拿不到是正常的；
      小尾巴到了要重测面板那 5 行）
- [ ] `st=4` 这类 HAL 谎报还没有在面板上如实标注
- [ ] 面板「AAudio 独占生效」仍只反映 `recordAaudioActive`，与"这一刻是否真在独占"有偏差
- [ ] 自动切歌：`hasPendingData()` 换成新语义后应当在 `AaudioSink` 侧继续成立，
      但**删写线程后尚未真机验证**
- [ ] delete `AudioDirectProbe` 里 `AAUDIO_SINK_ENABLED` 的过期注释（A/B 开关仍有用，注释旧）

---

## 8. 下一步路线

### 8.1 淡入淡出 / 无缝切歌（未开工）

| 功能 | 非独占（`DefaultAudioSink`） | 独占（`AaudioSink`） |
|---|---|---|
| 播放淡入 | 不用改 sink（`PcmAudioSink` 加一个增益斜坡 processor） | 同左 |
| 暂停淡出 | 不用改 sink（应用层先降 `player.volume` 再 `pause()`） | **必须改**：透明模式不下发音量，只能在 sink 里继续写渐弱数据；或**明确放弃淡出** |
| gapless 容器级 | 不改（解码器 + MediaSource 负责） | 不改 |
| gapless 衔接 | 基本不改 | **必须改**：见下 |

**独占下 gapless 的两个具体隐患**：

1. `handleDiscontinuity()` 现在**直接等于 `flush()`**，而 `flush()` 会重建整条流。
   Media3 在曲目边界/时间轴不连续时会调它 → 直接断流，gapless 被破坏。
   **必须把"seek（时间轴跳变，该断）"与"换曲（时间轴连续，不能断）"分开。**
2. 回调拉模式下切换点会空（环形缓冲读空 = 回调填静音 = 一个 burst 的静音缺口）。
   gapless 要求"上一首最后一个样本与下一首第一个样本之间设备里始终有数据"。

### 8.2 真·直通（B 级）的另一条路

若 `aaudio.mmap_policy` 这一关过不去，剩下的是 **AudioTrack + `AUDIO_OUTPUT_FLAG_DIRECT`**
（Flick 的 `audiotrack_direct.rs` 那条路，dlopen `libmedia.so`）或自带 UAC2 驱动。
**Oboe 和 AAudio 在这条路上都帮不上忙。**

还有一条被现实否掉的路：`AudioManager.setPreferredMixerAttributes` +
`MIXER_BEHAVIOR_BIT_PERFECT`（"Bit-Perfect-Mixer"）。它要求 **API 34+** 且持有
`MODIFY_AUDIO_ROUTING` —— 这个权限的保护级是 `signature|privileged|role`，
**普通安装拿不到**。所以那条降级链在普通安装上形同虚设，别把希望放在它上面。

---

## 9. 硬规则（重复一遍，违反会直接返工）

- **禁止用批处理/脚本改代码文件**（`Set-Content`/`-replace`/`sed`…）。只走「读文件 → 定点替换」。
  脚本只允许做**只读**的事（列文件、跑编译、装机、查设备）。
- **不写调试日志、不截图**；临时诊断代码用完删干净。
- 改完必须：`gradlew :app:compileDebugKotlin` → `:app:testDebugUnitTest` → `:app:assembleDebug` → `adb install -r`。
  **不要**给 `assembleDebug` 套 `Select-Object -First N`（会掐断构建，产出
  `INSTALL_PARSE_FAILED_NO_CERTIFICATES`）。
- 只有明确说「提交」才 `git commit`；**从不 push**。
- NDK `27.1.12297006`，仅 `arm64-v8a`。adb：`D:\ANSSDK\platform-tools\adb.exe`。
- 改任何"看起来像位置/时间"的东西之前，先想清楚它的**轴**（stream / renderer / period / window），
  以及它是否含 `outputStreamOffsetUs`。
- **Oboe 的默认后端是编译期决定的**（预编译 `liboboe.so` 按 api 21 编 → 默认 OpenSL ES）。
  所以 `native-oboe-stream.cpp` 里那句 `setAudioApi(AAudio)`（运行期 API ≥ 26 判断）
  **不能删**，删了独占直接不可能成立，而且症状只是"共享"，看起来像 HAL 的事。

---

## 10. 当前真机

| | 小米 23049RAD8C | PLQ110 |
|---|---|---|
| serial | `c16dc0e0` | `3B161X00DCU00000` |
| 系统 | Android 13 / SDK 33（MIUI） | Android 16 / SDK 36（ColorOS） |
| `aaudio.mmap_policy` | 1 = NEVER | 2 = AUTO |
| 独占实测 | 全部 SHARED | 内置扬声器上 SHARED，**外接待测** |
| 其它 | 3.5 mm 口报 vendor type `0x20000 (h2w)`，路由判不出 `wired` | 设备主率 48 kHz / 16 bit |
