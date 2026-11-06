# Annie Music · android-native 项目地图

> 由 codegraph 索引 + 源码清点生成。索引状态：946 文件 / 19,730 节点 / 32,961 边。
> 只描述结构与数据流，不含进度。

## 0. 一句话

单 Activity 的 Kotlin + Jetpack Compose 音乐播放器：**单屏内容 + 覆盖层栈 + 一条全局详情真栈**，
播放侧是自研 ffmpeg 解码链路（Apple 走 Temari 解密 + 本机 HLS 代理），数据侧接 8 个平台 + 落雪插件源，
外加一套完全独立的**本地曲库**（文件扫描 → TagLib/头解析 → 索引缓存）。

## 1. 规模

| 项 | 数值 |
|---|---|
| Kotlin | 168 文件 / 49,972 行 |
| codegraph 索引 | 946 文件（含 740 个 ffmpeg 头文件）/ 49.6 MB |
| 原生库 | `libtaglib.so`（TagLib JNI, arm64）、`libtemari.so`（Rust, Apple 解密） |
| 最大文件 | `widgets/SingerDetailSheet.kt` 1890、`core/audio/MediaPlayerBackend.kt` 1719、`widgets/AuroraBackdrop.kt` 1592、`features/shell/AppShell.kt` 1522（**`data/sources` 已无 400 行以上的文件**） |

## 2. 分层

```
MainActivity（唯一 Activity）
└ AnnieApp
  └ AppShell ─────────────────────────────── 界面装配 + 全部浮层（1173 行）
      ├ Column（页面内容，AppRouter 决定）
      │   ├ SearchScreen / DownloadsScreen / MoreSettingsScreen / PlaylistParseScreen
      │   ├ LocalLibraryScreen（本地曲库主页）
      │   └ detailStack 槽位：栈底→栈顶逐层渲染（云端详情 + 本地四页共用这一条）
      └ 浮层，自底向上（Overlay.RENDER_ORDER）
         GlobalOverlays → MenuDrawer → DownloadQueue → MiniPlayer → PlayerOverlay
         → SongActionsSheet → ArtistPickerSheet → QualityPicker(下载) → QualityPicker(批量)
         → QueueSheet → ContactModal → 下载中 toast

core/      播放引擎 / 全局状态 / 路由 / 平台能力 / 工具
data/      模型 / 各平台 API / 仓库 / 下载 / 缓存 / 本地曲库
features/  一个功能一个包（页面 + ViewModel + 局部组件）
widgets/   跨功能复用 UI（43 文件 / 13.9k 行）
ui/theme   设计令牌：Color / Dimens / Type / Motion / Theme
```

## 3. 目录职责

| 包 | 职责 | 关键文件 |
|---|---|---|
| `core/audio` | 播放后端、输出设备、蓝牙编解码、调音 | `MediaPlayerBackend`（建 ExoPlayer/队列/缓存）、`AnnieRenderersFactory`、`AudioOutputController`、`AudioOutputTarget`（目标率唯一来源）、`MusicSessionService` |
| `core/audio/ffmpeg` | 自研 ffmpeg 解码器 / 全链路 / 路由策略 | `FfmpegAudioDecoder`、`FfmpegAudioRenderer`、`NativeFfmpegDecoder`、`NativeFfmpegDemux`（avformat+avcodec+swr）、`FfmpegLocalExtractor`、`FfmpegOutputStream`、`FfmpegLocalRoute` |
| `core/audio/apple` | Apple 播放专用链路 | `ApplePlaybackServer`（本机 HLS 代理，端口一次性）、`AppleVariant`（档位/降级）、`AppleMp4`、`TemariDecryptor` |
| `core/audio/dsp` | 重采样 / 调音处理器 | `SincResamplerAudioProcessor`、`TuningAudioProcessor`、`NativeDsp` |
| `core/state` | 全部 ViewModel 状态 | `PlayerState`（队列+续播）、`UiState`（**detailStack**）、`MoreSettingsState`、`DownloadState`、`KkboxState`、`QobuzState` |
| `core/routing` | 覆盖层顺序与返回裁决（纯数据） | `OverlayStack`（`Overlay.RENDER_ORDER`、`BackTarget`、`handleBack`）、`AppRouter` |
| `core/platform` | 系统集成 | `StoragePermission`、`MediaStoreScan`、`BatteryOptimization`、`AppExit` |
| `core/util` | 歌词解码/解析、取色、下载队列 | `LrcParser`、`QrcDes`/`KrcDes`/`LrcxDes`、`ColorExtractor` |
| `data/model` | 领域模型 | `Models.kt`（`Song` / `Album` / `Quality` / `MusicPlatform` …） |
| `data/sources` | **跨平台公共件** + 平台包入口 | `ArtistNames`（`splitArtistNames` / `splitArtistIds`） |
| `data/sources/common` | 各平台**逐字节相同**的原语 | `Text`（`jsTruthy` / `firstTruthy` / `asText` / `encodeComponent` / `decodeHtmlEntities`）、`Sign`（`md5Hex` / `signParams`） |
| `data/sources/{kuwo,kugou,qq,netease,kkbox,qobuz,apple}` | **平台包**：怎么跟这家说话（一家 7–13 个文件，按用途再分） | 例：酷我 `KuwoConfig/Json/Cover/Quality/Mapper/Search/Artist/Album/Play/Lyric/Secret/Playlist/Types`；酷狗另有 `KugouHttp`（传输层唯一一份） |
| `data/sources/apple/thirdparty` | Apple 非官方接口（歌词/解析/密钥） | `AppleThirdPartyApi`、`AppleChallenge`、`AppleWebViewFetch`、`LocalWrapperAppleApi` |
| `data/sources/home` | **用途包**：主页（契约 + 每平台一文件） | `HomeFeedApi`（契约/注册表）、`NeteaseHome`、`QqHome`、`KuwoHome`、`KugouHome`、`KuwoHotPage`（纯解析） |
| `data/sources/lyric` | **用途包**：歌词（契约 + 每平台一文件） | `LyricSource`（契约/注册表）、6 个 `*LyricSource` |
| `data/source` | 落雪（Lx）插件源 | `LxPluginHost`、`LxPluginRegistry`、`LxIdentity`、`MusicSourceProvider` |
| `data` | 仓库 + 下载 + 缓存 + 续播 | `MusicRepository`（聚合入口）、`DownloadService`、`AssetCache`、`PlayerResumeStore`、`FfmpegTagger` |
| `data/network` | HTTP + 响应缓存 | `HttpClient`、`CacheService` |
| `data/local` | **本地曲库**（扫描/探针/存储/排序） | 见 §5 |
| `features/shell` | 壳层装配 | `AppShell`（内容+浮层）、`AppBottomBar` |
| `features/search` | 搜索页 | `SearchScreen`、`SearchScreenState`、`SearchSources`、`HomeOverlays` |
| `features/player` | 播放页 | `PlayerScreen`、`PlayerControls`、`PlayerLyrics`、`AudioDeviceSheet` |
| `features/library` | **本地曲库界面** | 见 §5 |
| `features/details` | ⋮ → 歌手/专辑跳转 | `DetailNavigator` |
| `features/downloads` | 下载页与编排 | `DownloadsScreen`、`DownloadOrchestrator` |
| `features/settings` | 设置页 / 调音面板 | `MoreSettingsScreen`、`TuningSheet` |
| `features/kkbox` · `features/qobuz` | 平台详情页 | `KkboxDetailScreen`、`QobuzDetailScreen` |
| `widgets` | 复用 UI | `SingerDetailSheet`、`AlbumDetailSheet`、`PlaylistDetailSheet`、`MenuDrawer`、`QueueSheet`、`SongActionsSheet`、`ScrollBar`、`Icons`（自绘图标） |
| `ui/theme` | 令牌 | `AppColors` / `AppSpacing` / `AppRadius` / `AppFontSize` / `AppDuration` / `AppEasing` |

## 4. 关键数据流

### 4.1 播放（云端 Apple 为例）
```
点歌 → PlayerState.setQueue
     → MediaPlayerBackend._setSource
        ├ Apple：解析档位 AppleVariant → ApplePlaybackServer.start()（本机 HLS，端口一次性）
        │        → 分片 Temari 解密 → FfmpegAudioRenderer（自研解码）
        └ 其他平台：直连 URL / 下载文件 → Media3 默认 renderer
     → SincResampler / Tuning processor → AudioOutputController（输出设备/蓝牙编解码）
     → MusicSessionService（MediaSession / 通知）
     → PlayerResumeStore 每 5s 写 player_state.json（重启续播）
```
- 本地歌走 `file://`，**无转码**：Media3 认识容器就走 `FfmpegAudioRenderer`（本地/流媒体统一）；
  Media3 解不了封装的（APE/WV/TTA/TAK/DSF/DFF/WMA/DTS）或 >192k / >24bit 的，
  走 `FfmpegLocalRoute.useFullChain` → `FfmpegLocalExtractor`（avformat+avcodec+swresample 全链路）。
- 采样率只有一处决策：`AudioOutputTarget.resolve()`（蓝牙旁路 / USB=设备最高 / 有线·扬声器=primary rate）。
  全链路把它当**唯一目标率**交给 native，一次 swresample 到位，处理器链不再重复重采样；
  真实输出规格由 `FfmpegOutputStream` 回报给播放页「输出」胶囊。

### 4.2 页面栈（全 App 只有一条）
```
UiState.detailStack: List<DetailEntry>（栈底→栈顶）
  ├ SingerDetailEntry / AlbumDetailEntry / PlaylistDetailEntry …
  └ LocalSectionEntry / LocalAlbumEntry / LocalArtistEntry / LocalFolderEntry
    + LocalArtistSongsEntry / LocalArtistAlbumsEntry（歌手详情的两个「全部」子页）
AppShell.detailStack 槽位 → 逐层渲染，栈顶先消费返回
返回裁决：core/routing/OverlayStack.handleBack(flags)
  优先级 songActions → artistPicker → qualityPicker → queue → playerOverlay → detail → downloadQueue → menuDrawer
```

### 4.3 搜索 / 详情跳转
```
SearchScreenState → 勾选的 SearchSources → MusicRepository → data/sources/*Api
⋮ SongActionsSheet → DetailNavigator（先收播放页 → 解析 id → pushDetail）
本地歌 → AppShell 内直接 pushDetail(LocalArtistEntry / LocalAlbumEntry)
```

### 4.4 下载
```
QualityPickerSheet → DownloadService（临时文件 → 最终文件）
 → DownloadDirService / MediaStoreScan 落库；进度进 DownloadState / DownloadQueue
 → 播放缓存（SimpleCache）与下载文件**互不复用**
```

## 5. 本地曲库子系统

### 5.1 数据层 `data/local`

| 文件 | 职责 |
|---|---|
| `LocalTrack.kt` | `LocalTrackMeta`（可序列化索引单元）、`LocalSort`、`groupTracks`、`sortTracks`、`filterTracks`、`countGroups`、`metaLine`/`qualityChip` |
| `LocalCollation.kt` | 排序与分桶：ICU `zh_CN` Collator（中文按拼音）+ `Any-Latin` 罗马化分桶（汉字→拼音、假名→罗马字）+ 同桶拉丁优先 |
| `LocalMediaScanner.kt` | 目录遍历（受 `scan_dirs.json` 目录 mtime 缓存约束） |
| `LocalScanIndex.kt` | 目录级 mtime 缓存，二次启动跳过未变目录 |
| `LocalTagLibProbe.kt` | TagLib JNI 读 tag（主路径；fd 必须 `dup().detachFd()`） |
| `LocalTagProbe.kt` | `MediaMetadataRetriever` 兜底 |
| `LocalFastProbe.kt` | ffprobe 兜底（APE/DSD/损坏文件） |
| `LocalHeaderProbe.kt` | 纯 Kotlin 解析 FLAC/WAV/AIFF/APE/MP4/DSF/DFF 头，拿位深/采样率/声道/编码（**DSD 强制走它**：ffprobe 报的是解码器中间格式） |
| `LocalLibraryStore.kt` | 索引/封面/最近播放/排序偏好读写 |

### 5.2 磁盘布局
```
filesDir/local_library/
  index.json      v7   曲库索引（path 为键，含 probed/coverPath/位深等）
  covers/              <hash>.jpg 抽出来的封面
  recent.json     v2   最近播放（path / at / count，上限 100）
  scan_dirs.json       扫描根目录 + 目录 mtime
SharedPreferences("local_library_prefs")
  sort_songs|artists|albums|folders      各页排序模式
  sort_<page>_asc                        各页升降序
filesDir/player_queue.json / player_state.json   播放续播
```

### 5.3 界面层 `features/library`（13 文件）

| 文件 | 页面 |
|---|---|
| `LocalLibraryScreen.kt` | 曲库主页：顶栏 + 搜索 + 四宫格 + 统计/排序/重扫 + 全部歌曲 + 右侧字母条 |
| `LocalArtistListScreen.kt` | 歌手列表（名字/歌曲数，升降序，持久化） |
| `LocalArtistDetailScreen.kt` | 歌手详情（歌曲/专辑各只预览 10 项，超出才给「全部 ›」） |
| `LocalArtistAllScreens.kt` | 歌手的「全部歌曲」「全部专辑」子页（各带排序 + 升降序，持久化） |
| `LocalAlbumListScreen.kt` | 专辑列表（发行时间/专辑名/艺术家/歌曲数，全部可升降序，持久化） |
| `LocalAlbumDetailScreen.kt` | 专辑详情（`CD n` 分段 + 曲目号排序 + 封面取色背景） |
| `LocalFolderListScreen.kt` | 文件夹列表 + 文件夹内容 |
| `LocalRecentListScreen.kt` | 最近播放（固定按播放次数 desc，无排序切换，行首数字=次数） |
| `LocalDetailCommon.kt` | 共用件：`LocalDetailTopBar`/`LocalSortRow`/`LocalOrderToggle`/`LocalSongRow`/`LocalOverlayFrame`/`rememberSortPreference` |
| `LocalLibraryParts.kt` | 主页部件：`LocalStatsRow`、`LocalEntryCard`、`LocalTrackRow`、空态 |
| `LocalAlphabetIndex.kt` | 右侧字母索引条 + 滚动圆点 + 槽位换算 |
| `LocalLibraryViewModel.kt` | 扫描/探针/封面队列/排序状态机（`UiState`） |
| `LocalNavigator.kt` | `LocalRoute` 与专辑/歌手内排序 |
| `LocalLibraryDetailHost.kt` | 详情栈条目 → 具体页面的外壳 |

### 5.4 字母索引条行为（当前约定）
- 只在**按字母排序**时出现（标题/艺术家/专辑）；此时取代右侧细滑条，滑条改成跟随滚动的白色小圆点。
- 其它排序（时长/添加时间）字母条消失，`shortScrollbar` 回来。
- 条子顶部 = **第一首歌当前的 y**：未上滑时从第一首那里往下排（下面被底部导航栏挡住是允许的）；
  上滑到第一首贴住搜索栏位置时整体移到列表顶部，27 个槽正好铺到导航栏上方。
- 槽高 = `(列表区高 − bottomBarInset − 8dp) / 27`，字号随槽高走（≈12sp）。
- 白色圆点圆心与字母槽中心重合，画在字母下层；`dotSlot` 用**槽位**而不是 0..1 比例，
  保证「圆点滑到哪个字母 ⇔ 那个字母区间第一首顶在搜索栏」；降序时槽位镜像翻转。

## 6. 原生与资源

```
app/src/main/cpp/ffmpeg/include/      ffmpeg 头文件（740 个 .h，只读引用）
app/src/main/jniLibs/arm64-v8a/
  libtaglib.so      TagLib JNI（本地曲库 tag 读取）
  libtemari.so      Rust 实现（native/temari/，Apple 分片解密；须带 SONAME + 16 KB 页对齐）
native/temari/      cargo 工程 + build-android.ps1（源码变更才需重编）
app/src/main/assets/apple_fixed_key.json    固定 key P000000000（仅 segment 0）
```

## 7. 用 codegraph 查这个项目

索引已在 `android-native/.codegraph/`（49.6 MB）。常用命令：

```powershell
codegraph status                                  # 索引状态
codegraph query "LocalAlphabetIndex"              # 找符号
codegraph node "PlayerState.togglePlay"           # 单符号源码 + 调用轨迹
codegraph callers "handleBack"                    # 谁在调用
codegraph impact "sortTracks"                     # 改动影响面
codegraph explore "本地曲库 扫描 探针 索引 缓存"     # 一次拿到相关源码 + 调用链
codegraph sync                                    # 增量同步（改完代码后）
```
MCP 侧对应 `codegraph_explore`（记得传 `projectPath`）。
