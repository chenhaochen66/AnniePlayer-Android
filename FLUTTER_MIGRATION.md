# Flutter 重构规划（AnnieMusic / 安妮音乐盒）

> 目标：把现有 Expo React Native 应用（`music-player` v1.0.4-release）用 Flutter 完整重写一遍。
> 约束：**前端 UI 与业务逻辑保持原样**；原生模块（kkbox-decoder、media-scanner）可重写；
> 元数据写入直接改用 ffmpeg。重构在独立分支进行，master 继续维护 RN 版。
> 本文档是规划基线，实施中按实际进度回填。

---

## 1. 现状盘点（RN 版）

### 1.1 技术栈

| 层 | RN 版 | Flutter 对应 |
|---|---|---|
| 框架 | Expo SDK 54 / RN 0.81.5 | Flutter（最新稳定版） |
| 路由 | expo-router 6（文件路由） | go_router 或 auto_route |
| 样式 | NativeWind 4（**实际未用**，全部内联 style） | ThemeData + 内联样式 |
| 状态 | zustand 4（8 个 store，5 个持久化）+ react-query | Riverpod（Notifier/AsyncNotifier） |
| 播放 | react-native-track-player 4.1.2（**深度 patch**） | just_audio + audio_service |
| 网络 | fetch | dio（拦截器注入各平台 Header） |
| 存储 | AsyncStorage + expo-file-system | shared_preferences + path_provider |
| 下载 | react-native-blob-util / FileSystem.createDownloadResumable | dio.download + CancelToken |
| 加密 | crypto-js / js-md5 / pako / 手写 RC4/MD5 | package:crypto + 直译算法 |
| 原生模块 | kkbox-decoder（RC4 解密）、media-scanner（打标签+扫描） | 重写：Dart RC4 / ffmpeg 打标 |

### 1.2 代码规模（行数近似）

| 模块 | 规模 | 说明 |
|---|---|---|
| 路由页 `app/` | 7 个文件，约 2100 行 | index / player / downloads / album-detail / kkbox-detail / qobuz-detail / _layout |
| 组件 `components/` | 33 个文件，约 6900 行 | FullPlayer(1001) 最复杂，SingerDetailOverlay(651) 次之 |
| 服务 `services/` | 约 18 个文件 | 6 平台 API + 下载/缓存/播放/歌单 + 苏澜加密 |
| 原生模块 `modules/` | 2 个（仅 Android） | kkbox-decoder、media-scanner |
| 工具 `utils/` | 6 个文件 | id3Writer(约500) / lrcParser / quality / retry / stringUtils / downloadQueue |
| Store / Hooks / Contexts | 8 + 6 + 3 | 播放/UI/下载/选择/账号 等 |
| 类型 `types/` | 1 个文件 | Song / Album / Quality / Qobuz* / Kkbox* |

### 1.3 关键结论（盘点发现）

1. **NativeWind 是死配置**：全仓库 `className=` 匹配为 0，样式 100% 是内联 style 对象。Flutter 无需翻译任何 Tailwind 类。
2. **native 层 `MusicService.kt` 已被深度 patch**：内置了多平台 URL 预取、自管循环/随机、20 首预填队列。这是重构中**技术风险最高**的部分，Flutter 侧需用 just_audio + audio_service + Dart 侧预取逻辑完整复刻。
3. **`patches/react-native-track-player+4.1.2.patch` 已从仓库删除**（最新提交 764e7a4），但 node_modules 里改动仍在。Flutter 重构时以 node_modules 现状为准。
4. **加密逻辑几乎全部可直译 Dart**：苏澜 Ro 算法、kuwo cookie secret、kugou KRC、qobuz/kkbox 签名、id3Writer。注意 `escape/decodeURIComponent/atob` 是废弃 Web API，Dart 需换 `latin1.decode`+`utf8.decode`。
5. **UI 有重复实现**：`downloads.tsx` ≡ `DownloadsOverlay.tsx`、`album-detail.tsx` ≡ `AlbumDetailOverlay.tsx`、FullPlayer 歌词 ≡ LyricsModal。Flutter 端合并为单实现。
6. **类型有历史不一致**：`Platform` 类型缺 `'qobuz'`，但代码大量使用 `qobuz:` 前缀，Flutter 重写时一并修正。
7. **electronAPI / localStorage / fetchPatch / webFileSystem** 是桌面/Web 专用分支，Flutter 移动端可丢弃。

---

## 2. 重构目标与非目标

### 2.1 目标（DO）

- [ ] Flutter 移动端（Android 优先，与 RN 版对齐；iOS 同步支持）
- [ ] UI 视觉还原：页面结构、配色（含动态取色）、间距、字号、动画/手势
- [ ] 业务逻辑还原：6 平台（kuwo/netease/qq/kugou/qobuz/kkbox）搜索、歌单、详情、歌词、下载、播放
- [ ] 加密/签名算法 1:1 直译（服务器端兼容，不能改签名结果）
- [ ] 原生模块重写：KKBox RC4 解密（Dart 实现）、媒体扫描 + ffmpeg 打标签
- [ ] 下载文件元数据写入改用 ffmpeg（替代 id3Writer + MediaScanner.tagAudioFile）

### 2.2 非目标（DON'T）

- [ ] 不重构 UI 视觉设计（保持原样，后续可另开 UI 优化分支）
- [ ] 不迁移桌面/Web 版（electronAPI、fetchPatch、webFileSystem 丢弃）
- [ ] 不在本次重写播放器缓存（CacheConfig）等 RN 版没有的能力
- [ ] 不引入新的业务功能

---

## 3. 技术选型与映射表

### 3.1 页面/组件 → Flutter Widget

| RN 页面/组件 | Flutter 对应 | 复杂度 |
|---|---|---|
| `app/_layout.tsx` | `MaterialApp` + 路由表 + 全局 Provider | 中 |
| `app/index.tsx`（首页搜索） | `HomeScreen`（SearchBar + 结果 + 模式切换） | 高 |
| `app/player.tsx` + `FullPlayer.tsx` | `PlayerScreen`（歌词滚动/进度/控制/音量） | **极高** |
| `app/downloads.tsx` | `DownloadsScreen` | 中 |
| `app/album-detail.tsx` / `AlbumDetailOverlay` | `AlbumDetailScreen`（合并） | 中 |
| `app/kkbox-detail.tsx` | `KkboxDetailScreen` | 中 |
| `app/qobuz-detail.tsx` | `QobuzDetailScreen` | 中 |
| `SingerDetailOverlay.tsx` | `SingerDetailSheet`（手势多选） | 高 |
| `PlaylistDetailOverlay.tsx` | `PlaylistDetailSheet` | 高 |
| `MiniPlayer.tsx` | `MiniPlayerBar`（底部常驻） | 中 |
| `QueueModal.tsx` | `QueueSheet` | 低 |
| `LyricsModal.tsx` | 合并进 PlayerScreen | 中 |
| `MenuDrawer.tsx` | `Drawer` | 低 |
| `ResultItem.tsx` | `SongTile`（滑动多选） | 中 |
| `AuroraBackground.tsx` | `AuroraBackground`（动画渐变） | 中 |
| 其余小组件 | 一一对应小 Widget | 低 |

### 3.2 服务层 → Dart

| RN 服务 | Dart 对应 | 处理方式 |
|---|---|---|
| 苏澜 `annieSource.ts` | `sulan_encrypt.dart` | 直译（SHA256+XOR+MD5） |
| `kuwoApi.ts` | `kuwo_api.dart` | 直译 + Dio（cookie secret 签名） |
| `neteaseApi.ts` | `netease_api.dart` | 直译 + Dio |
| `qqApi.ts` | `qq_api.dart` | 直译（注意 BOM/escape 处理） |
| `kugouApi.ts` | `kugou_api.dart` | 直译（signParams + KRC 解密，pako→archive） |
| `qobuzApi.ts` | `qobuz_api.dart` | 直译（MD5 签名） |
| `kkboxApi.ts` | `kkbox_api.dart` | 直译（RC4 用 Dart 重写，移除原生依赖） |
| `musicApi.ts` | `music_repository.dart` | 分发逻辑直译 |
| `downloadService.ts` | `download_service.dart` | dio.download + CancelToken；打标用 ffmpeg |
| `cacheService.ts` | `cache_service.dart` | shared_preferences + TTL 逻辑直译 |
| `playlistService.ts` | `playlist_service.dart` | 正则解析直译 |
| `assetProxy.ts` | `asset_cache.dart` | path_provider 封面/音频缓存 |
| `playerService.native.ts` + `AudioContext` | `audio_controller.dart`（just_audio + audio_service） | **重点重写**（见 §4） |
| `id3Writer.ts` | —— | **删除，改用 ffmpeg 打标** |
| `webFileSystem/fetchPatch/electronAPI` | —— | 丢弃 |

### 3.3 原生模块

| RN 模块 | Flutter 方案 | 理由 |
|---|---|---|
| `kkbox-decoder`（RC4-drop512 + Range 头） | 纯 Dart 实现（约 40 行）+ `Range: bytes=1024-` | 算法简单，跨端统一，解决只有 Android 的问题 |
| `media-scanner`（MediaStore 扫描） | `on_audio_query` 或直接 MediaStore | 扫媒体库即可 |
| `media-scanner`（FLAC/M4A/MP3 打标签） | **ffmpeg**（`ffmpeg_kit_flutter`） | 用户指定；MP3/FLAC/M4A 全格式统一 |

---

## 4. 核心风险与对策

### 4.1 播放链路（最高风险）

RN 版 patch 后的 track-player 能力：
- 应用级预取队列：`prefetchAllUrls`（并发预取后续所有曲目 URL）+ `fillPlayerQueue(20)` + 每 3s 补 10 首
- 自管循环/随机：`setAppLoopMode`（single→ONE，其余 OFF）+ 应用层 handleNext/handlePrevious
- 多平台 URL 获取移入原生：qobuz（MD5+伪造头）、netease（c.wwwweb.top）、kuwo（convert_url_with_sign）
- MediaSession NEXT/PREVIOUS 直接接管；`absoluteIndex` 事件字段

**Flutter 对策**：
- `just_audio` + `audio_service`（后台播放、通知、锁屏控制）
- Dart 侧实现 `PreloadController`：解析后续 URL → 缓存 → 动态 `setAudioSources` 预填队列
- 循环/随机逻辑放 `Notifier` 层复刻（shuffle/sequential/single）
- 进度同步用 audio_service 事件流（替代 RN 版 500ms 轮询 workaround）
- 歌词高亮用 `ScrollController` + 逐行尺寸测量

### 4.2 加密/签名兼容（高优先）

所有签名必须与服务器端一致，Dart 直译后**必须跑差分测试**：对同一输入，Dart 输出与 Python/JS 基准输出逐字符一致（苏澜算法已有此验证方法）。

### 4.3 下载与 ffmpeg 打标

RN 版下载流程：`createDownloadResumable` → 下载 → `tagAudioFile`（原生 Kotlin 写 ID3/FLAC/M4A）→ `scanFile` 进媒体库。
Flutter 版：`dio.download`（CancelToken 实现暂停/取消）→ 落盘 → **ffmpeg 写元数据**（封面/歌词/曲目号/年份）→ 媒体库扫描。
注意：ffmpeg 对 M4A（stco/co64 修正）与现有 Kotlin 实现的行为对齐需验证（封面嵌入格式、歌词编码 UTF-16 vs UTF-8）。

### 4.4 UI 还原细节

- 动态取色：`useColorExtractor`（react-native-image-colors / canvas）→ Flutter 用 `image_palette_generator` 或自实现量化
- 手势：SingerDetailOverlay/ResultItem 的 Pan+Tap 滑动多选 → `GestureDetector` 组合
- 极光背景：`withRepeat` 循环 → `AnimationController` 重复动画
- 安卓返回键：RN `BackHandler` → Flutter `PopScope`
- SplashScreen 用旧版 `Animated`：→ `AnimatedOpacity` 等

### 4.5 账号/Token 存储

RN 版 `useKkboxStore` **明文存 KKBox 密码**（含 session）。Flutter 版改用 `flutter_secure_storage`，字段结构保持兼容以便用户迁移。

---

## 5. 分支与版本策略

- **master**：继续维护 RN 版（bug 修复、音乐源接口变更等）
- **flutter-migration**：Flutter 重写工作分支，从 master 最新切出
  - 命名建议：`flutter-migration`（或 `feat/flutter`）
  - 合并策略：完成后 feature 分支合入 master 并发布 v2.0.0；RN 版归档 tag `v1.x-rn`
- 文档与清单随分支演进持续回填

---

## 6. 分阶段实施计划

### Phase 0 — 脚手架与骨架（约 0.5 周）
- [ ] `flutter create` + 目录结构（features/core/data/widgets 分层）
- [ ] go_router 路由表（7 页面对齐 RN 版导航）
- [ ] ThemeData（tokens.ts 两套色板合并 + 动态取色预留）
- [ ] 数据模型（Song/Album/Quality/DownloadTask/Qobuz*/Kkbox* 移植，修正 qobuz 类型缺失）
- [ ] 空壳页面逐个渲染（验证路由/主题/占位）

### Phase 1 — 网络与加密层（约 1 周）
- [ ] dio 封装 + 各平台拦截器（UA/Cookie/Secret 头逐条保留）
- [ ] 6 平台 API 直译 Dart
- [ ] 苏澜/kuwo/kugou/qobuz/kkbox 签名算法 + **差分测试套件**
- [ ] KRC 解密 / 歌词解析 / 歌单链接解析
- [ ] cache_service + shared_preferences

### Phase 2 — 播放核心（约 1 周，最高风险先行）
- [ ] just_audio + audio_service 集成（后台/通知/锁屏）
- [ ] PreloadController（预取 + 预填队列）复刻 patch 行为
- [ ] 循环/随机逻辑 + 事件同步
- [ ] KKBox RC4 解密纯 Dart 实现

### Phase 3 — 下载与打标（约 1 周）
- [ ] dio.download + CancelToken（暂停/取消/进度）
- [ ] ffmpeg 元数据写入（封面/歌词/曲目号/年份）
- [ ] 媒体库扫描（on_audio_query / MediaStore）
- [ ] 文件名规则、翻译歌词合并、enrich 逻辑

### Phase 4 — UI 逐页移植（约 2-3 周，并行）
- [ ] 首页（搜索/模式切换/双列结果/批量下载）
- [ ] 歌单/专辑/歌手浮层（含滑动多选手势）
- [ ] PlayerScreen（歌词滚动/进度/控制/音量——最高成本）
- [ ] 下载页 / MiniPlayer / 队列 / 抽屉 / 账号管理 / 启动页
- [ ] 动态取色 + 极光背景

### Phase 5 — 打磨与验收（约 1 周）
- [ ] 动画细节对齐（上滑/淡入/骨架屏）
- [ ] Android 返回键、通知、后台行为
- [ ] 端到端冒烟（6 平台搜索→播放→下载→打标→媒体库）
- [ ] 与 RN 版并排 UI 截图对比验收

**合计约 6-7 周**（单人全职；可按 Phase 并行压缩）。

---

## 7. 验收标准

- [ ] 6 平台搜索/歌单/详情/歌词与 RN 版返回一致
- [ ] 播放：队列预取、切歌、循环/随机、后台播放、锁屏控制与 RN 版一致
- [ ] 下载：暂停/取消/进度、文件名规则、ffmpeg 打标后元数据完整（封面/歌词/曲目号）
- [ ] UI：与 RN 版并排截图逐页对比无视觉回归
- [ ] 加密差分测试全部通过（无服务器 222222 类签名错误）
- [ ] 动态取色、极光背景、手势多选等动画/交互还原

---

## 8. 实施备忘（踩坑清单）

- `escape/decodeURIComponent` 是废弃 Web API：qqApi 歌词、kugouApi KRC、id3 相关一律用 `latin1/utf8` 转换
- kkbox `getTicket` 含状态码重试/续期逻辑（-1/-4/2/-2），必须保留
- qobuz token 校验用 10s 超时（AbortController）→ dio `ConnectionTimeout`
- kuwo 搜索返回单引号伪 JSON，需 `'`→`"` + 正则兜底解析
- 苏澜 Ro 的 `time` 用 `Date.now()`（毫秒），G 为 `'0'+时间戳` 取前 14 位
- 下载扩展名：flac/hires→`.flac`，其余→`.mp3`；非法字符替换为 `_`
- KKBox 明文密码迁移到 flutter_secure_storage（结构兼容）