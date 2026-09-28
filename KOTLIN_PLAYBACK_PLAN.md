# Kotlin 播放引擎改造计划（bit perfect 本地播放）

> **状态：待启动** —— 本文档仅为规划，尚未动手。
> **分支**：`kotlin重构`
> **记录时间**：2026-09-29
> **前置**：RN → Flutter 迁移已完成；RN 代码已移除（保留在 `master` / `web` / `amdl-version`）

---

## 1. 目标与约束

| 项 | 内容 |
|---|---|
| **主目标** | bit perfect 本地播放（按文件采样率/位深直通，不重采样、不软件音量） |
| **副目标** | 音频链路好调试 |
| **硬约束** | **UI 与交互完全一致** —— "迁了架构打开软件感觉不到" |
| **非目标** | 本阶段不重写 UI |

---

## 2. 核心结论

### 2.1 框架与音质无关

插件方案的 Kotlin 代码与纯 Kotlin App 运行在**同一进程、同一沙箱、同一套 Android API**。
MethodChannel 是**控制面**，音频数据**一个字节都不经过 Flutter**。

| 维度 | Flutter + Kotlin 插件 | 全量 Kotlin 重写 |
|---|---|---|
| 音质 / bit perfect | 完全一样 | 完全一样 |
| 性能 | 一样 | 一样 |
| **UI 一致性** | **100% 保持**（UI 没动） | 有「文字排版不一致」风险 |
| 调试体验 | 略差（两个调试器；改 Kotlin 无热重载） | 最爽 |
| 工作量 | ~800~1200 行 Kotlin | 2.5~3.5 万行 Kotlin |

### 2.2 推荐架构：**Kotlin 引擎 + Flutter UI 壳**

```
Kotlin 侧（完整引擎）
  MusicSessionService : MediaSessionService
    ├─ ExoPlayer             解码/输出/采样率直通/CacheDataSource
    ├─ MediaSession          队列/随机/循环/元数据/封面
    ├─ ResolveClient         长青解析 + 预解析下一首
    ├─ AudioOutputController 采样率/位深/设备选择
    └─ UsbDacManager         USB DAC

Flutter 侧（纯 UI 壳）
  MediaController ←── 状态自动同步（position/duration/buffering/queue/currentItem）
    ├─ player_provider  只读镜像 + 少量乐观状态
    ├─ 歌词获取 / 下载触发 / 下载状态（UI 关注，留 Dart）
    └─ 26 widgets / 7 screens  一行不改
```

### 2.3 为什么不直接全量重写

- Flutter 版体量：**65 个文件 / 20,282 行**（widgets 7837、data 5191、features 4706、core 2426）
- Kotlin + Compose 重写现实体量 **2.5~3.5 万行**，周期**几周**
- **对 bit perfect 的推进 = 0**：音频工作与 UI 框架无关，重写只会让音频目标**更晚**达成
- 且会引入「文字排版 / 水波纹 / 滚动物理 / 阴影渲染」不一致风险，直接违背硬约束

---

## 3. 分阶段路线

### 阶段 0 —— 采样率探针（几十行，零风险，**先做这个**）

- **目标**：判定系统 DAC 能否按文件采样率直通
- **方法**：准备 44.1k / 48k / 96k 三个同源文件，依次播放，同时观察：
  ```bash
  adb shell dumpsys media.audio_flinger   # 看输出线程实际采样率/格式
  adb shell dumpsys media.audio_policy    # 看路由到哪个设备
  ```
  \+ 外接 DAC 的屏显
- **判据**：输出跟着文件变 = 直通成立；恒为 48000 = 被重采样
- **产出**：决定后续走 系统直通 / offload / USB 裸访问 哪条路
- **约束**：不动任何现有逻辑，不碰 UI，不改交互

### 阶段 1 —— 下沉「发声」

- Media3 ExoPlayer 替换 `just_audio`
- **接口不变 → UI 零改动、交互零变化**
- 顺带修复：**酷狗直链缓存问题**（`CacheDataSource` 原生处理 302 跳转）
- 顺带删除 workaround：`_setSourceIfCurrent` 串行化令牌、`LockCachingAudioSource` 回退、失败重试
- 验证：音质 + 交互完全无变化

### 阶段 2 —— 下沉「指挥」

- 队列 / 播放模式 / 解析 / 预解析 移入 Kotlin
- 收益：**UI 进程死了音乐照放**（锁屏、系统杀后台、自动切歌、通知更新全继续）；`MediaController` 自动同步状态
- 代价：引入交互时序风险（见 §5），需配合化解措施（见 §6）

### 阶段 3（可选，未来）—— bit perfect 深化

- `AudioManager.setPreferredMixerAttributes()`（API 34+）
- ExoPlayer offload
- USB DAC 裸访问（UAC 等时传输）

---

## 4. 逐函数映射（基于现状代码）

### `lib/core/audio/audio_backend.dart`（534 行）

| 函数 | 归属 | 说明 |
|---|---|---|
| `_initJustAudio` | Kotlin | → Media3 `ExoPlayer` |
| `_setSource` / `LockCachingAudioSource` | Kotlin | → Media3 `CacheDataSource` |
| `_cacheFileFor` / `_extFromUrl` | Kotlin | Media3 自管缓存 |
| `_setSourceIfCurrent`（串行化+令牌） | Kotlin | ExoPlayer 天然串行 → 直接删 |
| `_handleEnded` | Kotlin | → `onMediaItemTransition` + gapless |
| `play` / `pause` / `seekTo` / `setVolume` | Kotlin | ExoPlayer 直调 |
| `AudioPlayerHandler` | Kotlin | → `MediaSessionService` |
| `_requestNotificationPermission` | Kotlin | 服务侧申请 |
| **采样率/位深直通** | Kotlin（新增） | 现在完全没有 |
| `fetchPreloadUrl` / `_resolveUrl` | **留 Dart**（阶段 1）→ **下沉**（阶段 2） | 纯 HTTP；阶段 2 必须下沉否则 UI 死了断自动切歌 |
| `_prefetchUpcoming` | 同上 | 无缝播放需要 |
| `setQueue` / `skipNext` / `skipPrevious` / `skipTo` / `setAppLoopMode` / `getQueueIndex` | 留 Dart（阶段 1）→ 下沉（阶段 2） | 指挥层 |

### `lib/core/providers/player_provider.dart`（344 行）

| 部分 | 归属 |
|---|---|
| `queue` / `addToQueue` / `removeFromQueue` | 阶段 2 下沉 |
| `next` / `previous` / `playIndex` / `togglePlay` | 阶段 2 下沉 |
| `LoopMode`（顺序/单曲/随机） | 阶段 2 下沉（语义须 1:1 复刻） |
| `_persistVolume` / `_persistLoopMode` / `_persistDownloadedIds` | 留 Dart |
| `_onSongChanged` / `_lyricFetching`（歌词/封面获取） | 留 Dart（UI 关注） |
| `currentTime` / `duration` / `bufferedTime` / `isLyricsVisible` | 留 Dart（从事件流更新） |
| **`PlayerBackend` 接口本身** | **不变** |

> **现成边界**：`setQueue(List<Map<String, String>> items, int startIndex, String loopMode)`
> 队列本来就是序列化后传下去的 —— 边界已存在，无需新设计。

---

## 5. 风险清单

### 5.1 交互时序（最明显）

| 风险 | 说明 |
|---|---|
| 一帧延迟 / 闪烁 / 按钮回弹 | 点歌 → session 处理 → 回调 → UI 更新；播放/暂停按钮可能"先变后弹回再变" |
| 进度条变卡 | MediaController 的 position 回调默认 ~500ms 一次，比现状慢 |
| 歌词面板一帧空 | 当前歌变了要靠回调，歌词（留 Dart）晚一拍 |

⚠️ **关键：服务与 UI 必须同进程**。若放独立进程（`android:process=":player"`）延迟明显。

### 5.2 语义细节（最容易"逻辑不一致"）

| 点 | 风险 |
|---|---|
| 随机模式 | Media3 shuffle 有自己的顺序算法，与现状很可能不同 |
| 点歌后队列怎么变 | `setMediaItems` 语义接近但不完全同（index 越界 / 空队列） |
| 从队列删当前项 | Media3 会自动跳下一首，现状可能不是 |
| 单曲循环 + 手动切歌 | `REPEAT_MODE_ONE` 下 `seekToNext` 行为可能不同 |
| 最后一首播完 | 停原地 / 循环 / 跳首 —— 需按 `onMediaItemTransition` 的 reason 映射 |
| 切歌时立即静音 | 现状切歌**立即 `pause()`**，ExoPlayer 可能漏一小段旧歌 |
| 耳机/锁屏按键 | MediaSession 接管后行为可能与 `audio_service` 不同 |
| 音频焦点（来电/他 App 播放） | ExoPlayer 接管后行为可能不同 |

### 5.3 ⚠️ App 内音量滑块 vs bit perfect（**直接冲突，需产品决策**）

- 现状：`setVolume`（App 内音量，且持久化）
- ExoPlayer 的 `player.volume` 是**软件音量** → **一调就不是 bit perfect**
- 必须二选一：bit perfect 时禁用/隐藏滑块，或滑块改为控制系统/USB DAC 硬件音量

### 5.4 其他

- **播放失败的感知**：现状 Dart 有重试 + 自动跳；下沉后必须有**错误事件通道**，否则用户只看到"卡住没声"
- **冷启动白屏一瞬**：队列恢复要等 session 起来 → 用本地持久化先渲染

---

## 6. 化解措施（做齐才能"感觉不到"）

| # | 措施 |
|---|---|
| 1 | **服务与 UI 同进程**（不用 `:player` 独立进程） |
| 2 | **乐观 UI**：交互时先本地置状态，回调再校正；本地要能"赢"（pending 标志 / 版本号） |
| 3 | **位置插值**：Dart 用 `Ticker` 按 `position + elapsed` 平滑推进 |
| 4 | **语义对照表**：把现状行为写成「模式 × 操作 → 期望结果」，Kotlin 照着实现，真机逐条验 |
| 5 | **单一真相**：队列只在 Kotlin session；Dart 纯只读镜像（两边都存 = 必然不一致） |
| 6 | **错误事件通道** |
| 7 | **音量策略先定**（见 §5.3） |

---

## 7. 待决策项（动手前必须定）

| # | 决策 | 倾向 |
|---|---|---|
| 1 | 是否先只做阶段 0+1（发声），阶段 2（指挥）缓做 | **建议：先做阶段 0，再阶段 1** |
| 2 | Kotlin 版 applicationId：同包覆盖 / 并排安装 | **并排**（`com.annieplayer.annie_music.kt`）便于 A/B 对照 |
| 3 | 音量滑块策略（§5.3） | 待定 |
| 4 | 服务是否独立进程 | **否**（同进程） |
| 5 | 队列语义（随机/单曲/删当前项） | 需先写出对照表 |
| 6 | 最终是否全量重写 UI | 阶段 2 完成后重新评估 |

---

## 8. 验证方法

| 手段 | 用途 |
|---|---|
| `adb shell dumpsys media.audio_flinger` | **★ 最有力**：看实际输出采样率/格式/是否走 mixer |
| `adb shell dumpsys media.audio_policy` | 路由到哪个设备 |
| `AudioTrack.getSampleRate/getAudioFormat/getUnderrunCount` | 代码侧自证 |
| `AudioManager.getProperty(PROPERTY_OUTPUT_SAMPLE_RATE)` | 设备原生采样率 |
| `AudioDeviceInfo` dump | 外接 DAC 上报能力 |
| **外接 DAC 屏显** | **硬件铁证** |
| 并排截图对比（两个 applicationId） | UI 一致性收敛 |

---

## 9. Android bit perfect 的现实（期望校准）

| 路径 | 把握 | 说明 |
|---|---|---|
| 系统 DAC + `setPreferredMixerAttributes`（API 34+） | 中 | 可能被 HAL 挡，需实测 |
| `AudioTrack` 直开原生采样率 | 中 | 同上 |
| Offload（FLAC/MP3，API 29+） | 较高 | 绕过 mixer，但仍有 DSP 风险 |
| **USB DAC 裸访问（UAC 等时传输）** | 能做但难 | 唯一可能"保证"的路；难在时序/缓冲/爆音，需长期打磨 |

⚠️ 即使 API 用对，**OEM 的 HAL 仍可能偷偷重采样** —— 只有 DAC 屏显 / 测量是铁证。
⚠️ DSD 需要 DoP 或原生 DSD（仅 USB）。

---

## 10. 工作量估算

| 项 | 行数 |
|---|---|
| 阶段 1（下沉发声）Kotlin 新增 | ~400~600 行 |
| 阶段 2（下沉指挥）改写 + 解析下沉 | ~400 行 |
| 阶段 2 删除（Dart 事件管道/队列镜像/位置同步） | **-200 行** |
| 阶段 2 Dart 新角色（只读镜像 + 乐观状态） | ~100 行 |
| Kotlin 引擎总计 | ~800~1200 行 |
| **UI 改动** | **0 行** |

---

## 11. 关键代码位置索引

| 文件 | 行数 | 作用 |
|---|---|---|
| `lib/core/audio/audio_backend.dart` | 534 | 音频引擎层（下沉主体） |
| `lib/core/providers/player_provider.dart` | 344 | 指挥层 + `PlayerBackend` 接口 |
| `lib/core/models/models.dart` | 417 | 数据模型 |
| `lib/features/player/player_screen.dart` | 1437 | 播放页（最大单文件，UI 不动） |
| `lib/widgets/song_actions_sheet.dart` | — | ⋮ 歌曲操作面板 |
| `lib/widgets/player_overlay.dart` | — | 播放页浮层 + 返回优先级 |

### 关键接口（**保持不变**）

```dart
// lib/core/providers/player_provider.dart
abstract class PlayerBackend {
  Future<void> setup();
  Future<void> setQueue(List<Map<String, String>> items, int startIndex, String loopMode);
  Future<void> skipNext();
  Future<void> skipPrevious();
  Future<void> skipTo(int index);
  Future<void> setAppLoopMode(String loopMode);
  Future<int> getQueueIndex();
  Future<void> play();
  Future<void> pause();
  Future<void> seekTo(double seconds);
  Future<void> setVolume(double volume);
  Stream<Map<String, dynamic>> get events;
}
```

---

## 12. 依赖映射参考

| Flutter | Kotlin |
|---|---|
| `just_audio` 0.10.6 + `audio_service` 0.18.19 | **Media3 ExoPlayer + MediaSessionService** |
| `go_router` 18.0.1 | Navigation Compose（或单 Activity + 状态驱动浮层栈，更贴合现有浮层式 UI） |
| `flutter_riverpod` 3.4.3 | ViewModel + StateFlow（或 Hilt/Koin） |
| `dio` 5.11.1 | OkHttp + kotlinx.serialization |
| `shared_preferences` 2.5.5 | DataStore |
| `flutter_secure_storage` 11.2.0 | EncryptedSharedPreferences |
| `image` 4.10.1 | Bitmap + Coil |
| `archive` 4.3.0 | java.util.zip / zip4j |
| `ffmpeg_kit_flutter_minimal` | ⚠️ ffmpeg-kit 官方已停止维护 → 自编译 libav / Media3 Transformer / JNI |

---

## 13. 环境备忘

- 设备：PLQ110，Android 16 / API 36
- 包名：`com.annieplayer.annie_music`
- 构建：`flutter_app/`（Flutter SDK `D:\flutter\flutter`，Android SDK `D:\ANSSDK`）
- 解析接口：`POST https://musicserver.haitangw.cc/v1/music/resolve-url`（成功 201 + `{"code":0,...,"data":{"url":...}}`；无效 id 503）
- 酷狗直链为 `http://` 且会 302 跳转（Media3 `CacheDataSource` 原生处理）
