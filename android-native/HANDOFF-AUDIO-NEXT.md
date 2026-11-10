# 接手文档：音频输出层剩下的三件事

> 写于 2026-11-09。当前代码状态：**步骤 0–4 + 优化 ①②③④ 已完成并装机**，剩下三件事没做。
> 本文件只写"要做什么、为什么这么定、落在哪、怎么验"，不写已经做完的流水账。
> 规矩见根目录 `AGENTS.md`（注释不可信 / 禁批处理改源码 / 改完必须编译 + 单测）。

---

## 0. 先读这一节：现在的结构长什么样

音频输出已经从"一个 1390 行的类靠 `systemSink != null` 分叉"拆成四个文件：

| 文件 | 行数 | 职责 |
|---|---|---|
| `core/audio/OutputPort.kt` | ~50 | **接口**：`write` / `positionUs` / `play` `pause` `flush` `reset` `release` / `playToEndOfStream` / `isEnded` / `hasPendingData` / `isHealthy` |
| `core/audio/ExclusiveOutput.kt` | ~800 | USB 直连（libusb UAC2）与 AAudio 独占（Oboe）**共用同一份编排**，`PcmEngine` 换内核 |
| `core/audio/SystemOutput.kt` | ~50 | Media3 `AudioSink` 薄壳，位置/时钟交给它自己的 tracker |
| `core/audio/AnnieAudioSink.kt` | ~250 | Media3 的 `AudioSink` 唯一实现：**选后端** + 转发 setter，数据路径只有一条 `port?.write(...)` |

三条不变量（改动前必须确认还成立）：

1. **后端在 `configure()` 里一次选定，整首不换。** 运行中失败走 `onEngineFailure` → 上游重建音源，不偷偷切后端。
2. **raw DSD（DoP / Native）绝不允许落到系统通路。** `ExclusiveOutput.configure` 返回 `ExclusiveOpenResult.ForceFail`，facade 不回退、只上报失败。原因：`DefaultAudioSink` 内部 24bit→float 会毁掉 DoP 的 `0x05/0xFA` 帧标记。
3. **一次 configure 的意图是一个不可变值**（`OutputRequest`），不允许中途回读上游可变状态。

另外三个已存在的事实源（**不要再造第二份**）：

- `OutputRequest`（`core/audio/OutputRequest.kt`）—— 这一首要什么：通路意图 / DSD 传输方式 / 要不要 raw alt / 是不是帧 / 传输标签。sink 在 `configure()` 开头取一次。
- `FfmpegOutputStream.Spec` —— **源自己声明**的输出规格 + `transport`。`_fullChainPath`（`MediaPlayerBackend`）是它的 key。`transportOf(path)` 返回 `null` 表示"不是全链路源（流媒体）"。
- native 事件环 + 快照（`native-uac.cpp`）—— `NativeUacStream.snapshot(handle)` / `drainEvents(handle)`；`ExclusiveOutput.logNativeFacts()` 只在故障路径打一条。**这台 ROM 丢 App 日志，native 侧只能靠这条通道开口。**

---

## 一、待做 1：预缓冲 / 攒够才开声

### 目标

起播不再"先出声再卡一下"。桌面 AnnieEngine 的做法是：起播前先攒够一段再开闸（本地 0.3 s / 网络 1.5 s，quickStart（seek / 切歌回退）本地 0.15 s / 网络 0.5 s，上限 8 s）。

### 现在的行为

`MediaPlayerBackend` 用 Media3 的 `LoadControl`（`bufferForPlaybackMs` 现在是 1 s）。它管的是**时间维度够不够**，但不管：

- 解码器刚起来时第一块样本的**抖动**；
- 网络源首包的**突发延迟**（插件直链、KKBOX 代理、Apple 分片）。

### 落点

1. `MediaPlayerBackend` 的 `LoadControl` 构建处：`bufferForPlaybackMs` / `bufferForPlaybackAfterRebufferMs` 现在被改成了 1000。按"本地 / 网络"分档（判据：`_fullChainPath.isNotEmpty()` = 本地全链路）。
2. 与待做 2（淡出）共用同一套"预读窗口"，见下一节的说明 —— **两者一起做**，因为它们都需要"提前看到还没播的样本"。

### 验收

- 本地 Hi-Res 起播：第一声就稳，没有"先响 200 ms 再顿一下"。
- 网络源切歌：不出现"进度条走了 1 秒才出声"。
- seek：`quickStart` 档位生效（响应快于起播）。

---

## 二、待做 2：淡出（暂停 60 ms / 切歌 30 ms 微淡变）

### 为什么不能像淡入那样做

淡入（**已完成**，`dsp/FadeAudioProcessor.kt`）在处理器链上就能做，因为数据在流。**淡出不行**：Media3 一旦 `pause()` 就**不再交数据**，处理器链上已经没有可乘的样本。所以淡出必须在**下游**：留一个 T 毫秒预读，把静音延迟 T 毫秒。

### 两个后端各自的做法

| 后端 | 做什么 |
|---|---|
| **独占**（`ExclusiveOutput`） | 在 native 的 `fill()` 里做：`pause()` 不再立刻置 `paused`，而是进入"淡出中"状态，先按 60 ms 线性降到 0 再切静音。降完后 `paused=true`。`fill()` 已有 `silenceFill()` 与 `padFramesLeft` 机制，落点是 `native-uac.cpp` 里 `paused.load()` 那段分支 |
| **系统通路**（`SystemOutput` / `DefaultAudioSink`） | `AudioTrack` 的 pause 是即时的，没法延迟。可行做法：`SystemOutput.pause()` 先记下"想暂停"，用 `setVolumeAction` 在 60 ms 内把 `AudioTrack` 音量拉到 0（`AudioSink.setVolume` 是即时的），再真正 `pause()`；恢复时反向。**或者**接受系统通路没有淡出（它本来就有 AudioFlinger 的 ramp）—— 我倾向后者，先只做独占，避免与 Media3 的音量语义打架 |

切歌 30 ms 微淡变：独占侧同样是 `fill()` 里"换曲后第一块/最后一块"的斜坡；系统通路由 `AudioTrack` 自己处理。

### 验收

- 独占通路：暂停/继续听不到"哒"；连续切歌 20 次听不到咔哒。
- 暂停后**位置必须冻结**（`framesRead` 不再涨），恢复后从原处继续，一个样本不丢。

---

## 三、待做 3：真交叉淡入（最大的一项）

### 语义（已与用户确认，别再改）

- **上一首的音频不会被截短**：A 播到自然结尾，只是在最后 T 秒被衰减。
- **提前的是"归属点"**：播放列表在 `endA − T` 就切到 B（通知栏标题、位置显示、`seekToNext` 都按这个走），但 A 的尾巴还在响。
- **B 必须提前准备好**：`endA − T − δ` 之前要拿到 URL、解密、open、首包解码完成。
- **触发靠前瞻**：判据是 `endA − position`（渲染器时钟），**不能**用我方 `framesWritten`（比播放位置超前一个缓冲）。
- **曲线**：等功率（sin/cos 四分之一周期），A 的淡出与 B 的淡入功率和恒定。
- **A 提前自然结束 / 用户 seek 到尾部**：重叠窗要能缩短，斜坡按实际长度重算。
- **重叠中点"下一首"**：从当前混合电平硬切，不能把两路一起重启。
- **DSD 不参与**：DoP / Native 的字节是帧，混音会毁标记 → 遇到 DSD 曲目自动退回严格 gapless，设置页写一行说明。
- **交叉区间内不可能 bit perfect**（浮点混音 + 增益）：输出胶囊的 `tier` 必须降级说明，别一边混音一边挂 `bitPerfect`。
- **预取是 best-effort**：`endA − T − δ` 时 B 还不可播 → 本次不做交叉，走 `T = 0`（严格 gapless），并用事件通道记一笔。**绝不卡住等 B。**

### 五条前置（必须先做，否则交叉一定会抖）

| # | 前置 | 位置 | 状态 |
|---|---|---|---|
| 1 | `FfmpegOutputStream` **单槽 → 双槽**（按 path 保留最近 2 条） | `core/audio/ffmpeg/FfmpegOutputStream.kt` 的 `current: Entry?` | **硬约束**。交叉窗口内两路 extractor 同时活着，B 一 publish 就把 A 的规格顶掉 → `_currentFullChainSpec()` 拿到 null → 那一瞬间 `dsdTargetRate` 变 0、计划被推翻 |
| 2 | `KkboxPlayServer` 的"前台"**单值 → 集合** | `data/sources/kkbox/KkboxPlayServer.kt`（`markForegroundByUrl` / `markAllBackground`，`_sessions` 本身已是 LinkedHashMap，多会话具备） | 前台标记是本地代理读线程的调度信号；B 没被标前台 → 预读降速 → 淡出完了接不上 |
| 3 | **统一"只取前 T+δ 秒"预取**（`Range: bytes=0-N`），覆盖两条缓存链路（KKBOX 走 `bypassDiskCache` 自己就是缓存；其它走 `CacheDataSource + SimpleCache`，LRU 512 MB） | 新模块，或挂在现有代理/缓存入口 | 别整首下：T 最大 10 s，176.4k/24bit ≈ 5.3 MB/s → 最多 53 MB |
| 4 | **插件直链缓存住，交叉窗口内绝不重取** | 落雪插件取链路径（网易/QQ/酷狗/酷我） | 这些直链**有时效且取一次少一次**；交叉前重取可能拿不到或烧掉配额 |
| 5 | 本地/网络源都在 deadline 前完成 `open` + 首包解码 | `NativeFfmpegDemux.open()` 找 stream info 要几百毫秒 | 不能等到 `endA − T − δ` 才 open，否则头被吃掉 |

**已在位、可复用**：`ApplePrefetch`（`LOOKAHEAD = 2`，队列 + 去重 + `ahead(upcoming, quality)`，按分片解密）—— Apple 侧只需要保证 δ 覆盖分片粒度，以及**解密吞吐错峰**（Temari 是 CPU 活，别和 A 的实时解码抢）。

### 主体设计

```
播放层（Kotlin）
  ├─ 归属点提前 T：判定 / 通知栏 / 位置显示
  ├─ 双路喂料：A 与 B 各自的 extractor → 各自的 float 流
  └─ 可中止：重叠中"下一首" → 丢旧路输入，从当前混合电平继续
        ↓
native（新增 CrossfadeMixer）
  两路 float 输入（各自可不同采样率，统一到输出率）→ 等功率合并 → 一路 float 输出
        ↓
现有 OutputPort.write(...)   ← 三条输出通路都不用改
```

为什么必须放 native：Media3 的管道是"单渲染器 + 单 sink"，sink 层无法混两路；而在 Kotlin 里做混音会引入每块一次额外拷贝与一个跨语言时序面。混音器只做 float，不碰 DSD（DSD 已在前面排除）。

### UI 规格（用户已确认）

位置：**设置 → 播放相关 → 播放音质选择的下方**。

```
播放相关
  播放音质        [ 无损 / Hi-Res ▾ ]
  无缝切歌        [ 开 ]
    过渡时长      [———●———————]  − 0.8 s +
```

- **开关** = 启用无缝切歌；**关掉 = 严格 gapless**（不重建流、不插静音、不重置环）。
- **滑条** = 交叉淡入时长 **0 – 10 s**，步进 **0.1 s**。
- **`−` / `+`**：每按一次 ±0.1 s，长按连续步进。
- **时长 = 0 + 开关开**：严格 gapless + 30 ms 微淡变兜掉边界咔哒。
- **默认值**：开关**开**、时长 **0 s**（gapless 没理由关；交叉是口味，默认不加工）。

### 与淡入淡出的关系（已定的边界）

- 淡变里"起播/seek 80 ms 淡入"**不受这个开关控制** —— 它的职责是防爆音，不是"切歌过渡"。绑进开关会让用户一关就听到咔哒。
- 交叉运行时，那 30 ms 微淡变要**停用**（两者会打架）。

### 验收

1. 开关关：连播同格式曲目**没有任何断点**（用 `audio-status.txt` + 耳朵双验）。
2. 开关开、时长 0：同上 + 边界无咔哒。
3. 开关开、时长 3 s：两首真的重叠（能听出和声交织），位置显示与通知栏在 A 的尾巴期间已经切到 B。
4. 重叠中按"下一首"：立刻切走，没有第二次开头。
5. 交叉期间输出胶囊显示降级（非 bit perfect）。
6. DSD 曲目：自动退回严格 gapless，设置页有一行说明。
7. 人为掐掉 B 的网络：**降级为 gapless**，不卡停、不半交叉、日志里有一笔。

---

## 四、已经踩过的坑（别再踩一遍）

| 坑 | 事实 |
|---|---|
| NaN → **满幅直流** | `std::min(1.0f, NaN)` 按 `b < a ? b : a` 求值，NaN 比较恒 false → 返回 **1.0f**。所以"钳位"会把 NaN 变成 +1.0 而不是丢掉。三处闸已加（`push()` float 分支、PEQ 写回前、r8brain 输出低通后） |
| IIR 被 NaN 污染是**永久**的 | r8brain / PEQ 一旦状态里有非有限值，之后整条流都是 NaN → 症状"某一首之后所有歌都没声"。所以闸在**每级处理之后**，不是入口 |
| `dead` 只在 `startStream()` 清 | 复用分支会一直抱着死流 → **连普通 PCM 也一起哑**。上层现在有三道检查：`configure()` 开头、`reopen` 之后、`write` 之前 |
| `reopen` 返回 true ≠ 流还活着 | 换率时端点可能在这里错一次 transfer（`dead` 由事件线程异步置上） |
| 系统通路会毁掉 DoP 帧 | `DefaultAudioSink` 内部 24bit→float 把 `0x05/0xFA` 除成 ~1e-7 直流 |
| 别在 `configure()` 里回读全局量 | 那个"两读两写"窗口就是 DoP→PCM 没声的根因；现在靠 `OutputRequest` + `FfmpegOutputStream.transport` 消掉 |
| `play()` 靠不住 | 它只挂在 `BUFFERING→READY` 迁移上；"播放中切模式"没有那次迁移 → 一律走 `applyPlayIntentToEngine()` |
| `_effectivePath` 曾是 24 处的第二真相 | 现在只有 `SinkRouting?` 一个不可变三元组（path / notice / depth），`_routingStale` 只记"可信度"，不记值 |
| `audio-status.txt` 只按字符串去重会骗人 | 连播两首同为 176.4k 的曲目时状态行一字不差 → 新曲一行都没有。现在判据加了"`AudioInfo` 换了实例"，行首有时刻 |
| ROM 丢 App 日志 | `__android_log_print` 也丢。native 侧唯一可用的通道是事件环 + 快照（步骤 4 已建） |

---

## 五、环境与命令

```powershell
# workdir: D:\AnnieMusic\android-native
& .\gradlew.bat :app:compileDebugKotlin          # 改完先编译
& .\gradlew.bat :app:testDebugUnitTest           # 414 项，必须全绿
& .\gradlew.bat :app:assembleDebug               # 出包
& "D:\ANSSDK\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk   # 别加 --no-streaming
& "D:\ANSSDK\platform-tools\adb.exe" -s 192.168.3.10:5555 shell "am force-stop com.annieplayer.annie_music.kt"
& "D:\ANSSDK\platform-tools\adb.exe" -s 192.168.3.10:5555 shell "am start -n com.annieplayer.annie_music.kt/com.annieplayer.annie_music.kt.MainActivity"
```

- 设备：`PLQ110`（Android 16 / SDK 36），WiFi adb `192.168.3.10:5555`，DAC = **FIIO KA13**（VID `0x2972` / PID `0x0062`）。
- KA13 描述符关键事实：AS 接口 alt 1/2/3 = PCM 16/24/32（subslot 2/3/4，maxPacket 200/300/400），**alt 4 = raw DSD**（`bmFormats` bit31，subslot 4）。**没有 Clock Selector**，时钟源在 AudioControl 接口（id=1）。
- 现场取证：抓包见我方 EP `0x03` 的 iso OUT 每 microframe 字节数 → ÷每帧字节 ×8000 = 真实采样率；`debugfs` 里 `usbmon/1u` 可用（`mount -t debugfs none /sys/kernel/debug`，用完记得 umount）。
- 状态文件：`files/audio-status.txt`（每曲一行，`at= want= eff= planPath= planRate= srcRate= decoded= willResample= downgrade= tier= raw= tag=`）。设备内读取：
  `adb -s 192.168.3.10:5555 shell "run-as com.annieplayer.annie_music.kt tail -3 files/audio-status.txt"`

---

## 六、提交状态

最后一次提交是 `a42a784`（USB 独占 + DSD 三模式 + r8brain + Kotlin 分层重构）。**之后的全部改动都还没提交**，包括：

- 步骤 1：`OutputRequest` 冻结 + `SinkRouting` 单一事实源 + 状态行每曲必留
- 步骤 2：DSD 封装下沉 native（`packDop` / `packNativeWords` / `carry` / `configRawPack`）
- 步骤 3：`OutputPort` / `ExclusiveOutput` / `SystemOutput` / `AnnieAudioSink`（改名 + 拆分）
- 步骤 4：native 事件环 + 快照 + `logNativeFacts`
- 优化 ①：`FfmpegOutputStream.Spec.transport` + `OutputRequest` 从源声明取值
- 优化 ②：三处 NaN 闸
- 优化 ③：`FadeAudioProcessor`（30 ms 换曲 / 80 ms seek）
- 优化 ④：`reopen` 3 次退避（60/180/420 ms）

接手第一件事：**先跑一遍编译 + 单测确认现在的状态**，再决定要不要先提交这些改动（用户说过只有明确说"提交"才 commit）。
