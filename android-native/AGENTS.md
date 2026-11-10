# 规则

> 本文件**只放规则**，不记录进度、不记录已完成项。进度看代码本身。

## 0. 最高优先级

- **注释不可信，代码是唯一事实来源。**
  本仓库的注释可能是错的（`native-uac.cpp` 那句「必须先 alt 再设率」与内核、海贝都是反的）、
  可能过期，甚至可能把语句吃掉（见下一条）。所以「注释说……所以应该……」不算推理：
  动手前先在代码里找到**写**这个状态的那行和**读**它的那行，两处对上；对不上就当作注释不存在。
  冲突时改注释或删注释，**不要**以注释为准改代码。
- **动过 `cpp/` 或大段代码之后，扫一遍「`//` 后面还跟着以 `;` 结尾的内容」。**
  历史上批处理回写抹平换行、重建后**六处语句被并进 `//` 注释**，全部编译通过、行为静默改变
  （最严重的一处让 32 位直出分支的 `memcpy` 消失，DSD 从此只有直流和答答声）。
  见到这种行就拆回独立语句。
- **自己写注释：按"必要"，不按"详尽"，默认不写。**
  只在三种情况写：① 结论与直觉相反；② 魔法值 / 外部约束的来源；③ 接口契约看不出来的副作用。
  其余靠命名。禁止大写标题式散文、分隔线、比喻、复述代码语义、"曾经怎么错过"的长篇复盘
  —— 那种文字会被下一个读者当事实继承。实测数字只作**证据**引用，不作设计前提；
  设备型号是测试样本，不是规格来源。
- **出 bug 先查自己的代码，不要急着加日志。**
  先把完整链路读一遍：谁写这个状态、谁读它、语义变了没有；读得出来就直接改。
  只有真的读不出来才加日志，且一次问准 —— 不要一版改一处、装一次、试一回。
  反例 ①：只差一处语义（有防抖 → 无防抖）却先加日志点屏幕试了五轮。
  反例 ②：USB 独占三版靠 dump 定位，而错误就在自己 `open()` 的注释里（alt 与设率顺序猜反了）。
- **改代码只走「读文件 → 定点替换」的编辑工具，禁止使用批量命令和脚本。**
  禁止批处理回写：`Set-Content` / `Out-File` / `-replace`、Python、`sed` / `awk`、循环替换、
  批量重命名。这条是硬性的 —— 曾用 `Set-Content -NoNewline` 把一份 1400 行 Kotlin 的换行全抹平。
  脚本只做**只读**的事（列文件、量体积、跑编译、装 APK、查设备）。
- 只有用户明确说「提交」才 `git commit`；**从不 push**。

## 1. 范围

- 只改 Kotlin 版（`android-native/`），不看 Flutter / Dart 版。
- 非必要不用子 agent。

## 2. 调试纪律

- 不写调试日志；临时诊断代码（计时打点、临时 dump 文件）用完必须删干净。
- 不截图。
- 改之前先读文件；改完必须跑一次编译（`gradlew :app:compileDebugKotlin`）再下结论，不靠猜。
- 编译过了还要跑一次本地单测（`gradlew :app:testDebugUnitTest`）—— 编译只证明「能过编译」，
  证明不了「行为对」。能测的地方见 [6.1](#61-本地单元测试jvm改完就跑)。

## 3. 代码分层

- **两种包，一条依赖方向**：`data/sources/<platform>/` 放「怎么跟这家平台说话」（HTTP / 签名 /
  UA / mapper / 音质 / **元数据**）；`data/sources/<purpose>/`（`home`、`lyric`）放「一种能力」的
  契约 + 注册表 + 每平台一个文件。**依赖只能 用途包 → 平台包，反向禁止**（平台包 import 用途包
  就会成环）。判断一个文件该放哪边，只问一句：它描述的是「平台怎么说话」还是「一种能力的调度」。
- **直链取链不在平台包里**：国内四平台（网易 / QQ / 酷狗 / 酷我）的播放与下载统一由落雪插件
  解析，内置直链接口**已删除**（无降级、无跨源兜底）；KKBOX 走它自己的官方票据接口
  （`data/sources/kkbox/KkboxPlay.kt` + `KkboxDownloader`），Apple 走 `ApplePlaybackServer` /
  `AppleDownloader`。要给某个平台加取链，先想清楚为什么不走插件。
- `data/sources/common/`：各平台**逐字节相同**的原语（`jsTruthy`、`asText`、`encodeComponent`、
  `decodeHtmlEntities`、`signParams`）。同名不同义的（各平台 `_strOr` / `_asMap` / `_toInt`）
  **留在各自平台包里**，别硬合并语义。
- Apple **官方**接口：`data/sources/apple/AppleMusicApi.kt`（`amp-api-edge`，搜索 / 详情 / 专辑 /
  歌手 / storefront）。
- Apple **非官方**接口：`data/sources/apple/wrapper/`（歌词、`/m3u8`、`/key`）。
  服务是 WorldObservationLog/wrapper 那一族，接口形状固定（`/status` `/m3u8?adamId=`
  `/key?adamId=&uri=` `/lyrics?adamId=&language=&syllable=`）。
  **只有两个源，二选一、不自动回落**：`AppleServiceSettings.useLocal` 开 → 本机 WrapperLite
  （`127.0.0.1:12341`）；关 → 用户在设置页添加的远程地址（`AppleWrapperRegistry` 里选中的那条，
  userinfo 按 HTTP Basic 发）。两边都不可用就直接报错。
  **禁止**再加第三方站点、CF 挑战 / WebView 指纹兜底、跨源混取、以及「本地失败自动回落远程」
  这类静默换源（历史上 ak1ra 那套就是这么来的，已整条删除）。
- 主页：`data/sources/home/` 里 `HomeFeedApi.kt` 只放契约（`HomeShelf` 常量 / `fetchHomeShelves`
  注册表 / 共用取值工具），实现一家一个文件（`NeteaseHome` / `QqHome` / `KuwoHome` / `KugouHome`）。
  酷我 feed 的 XML 解析是纯函数，单独 `KuwoHotPage.kt` 并带单测。
- 歌词：每个平台一个 `LyricSource`（`data/sources/lyric/`），按 `platform` **一对一**匹配，
  登记进 `LyricSources.all`。**不许有兜底平台**（以前 `else` 落到酷我，漏注册也能「碰巧」出歌词）；
  也不许在各实现里自己填占位文本或自己缓存 —— 占位与缓存只在门面 `fetchLyric` 做。
- 传输层只在平台包里有一份（例：`data/sources/kugou/KugouHttp.kt` 的 `kugouSignedPost` /
  `kugouMid` / `kugouPlainHeaders`）。用途包要发同样的签名请求，**调它**，不要抄一份。



## 6. 构建 / 装机

```powershell
# workdir: D:\AnnieMusic\android-native
& .\gradlew.bat :app:assembleDebug --console=plain
& "D:\ANSSDK\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
```

- **装机别用 `--no-streaming`（push 安装）**：这台机器上它会“Success”但 dex 没完整注册，
  症状是启动即崩 `ClassNotFoundException: androidx.startup.InitializationProvider`
  （连 `androidx.core.app.CoreComponentFactory` 都找不到），而 APK 里那些类其实是齐的。
  遇到这个现象先按坏装处理：改成普通流式安装重来一次，不要急着改代码、也不要 `clean`。

- NDK：`27.1.12297006`，仅 `arm64-v8a`。
- Temari（Apple 解密库，Rust）：**Gradle 会自动编**，不需要手工跑脚本。
  `app/build.gradle.kts` 的 `buildTemari` 挂在 `preBuild` 上，输入是
  `native/temari/src/` + `Cargo.toml`/`Cargo.lock`/`rust-toolchain.toml`，输出是
  `app/src/main/jniLibs/arm64-v8a/libtemari.so`；源码没变时 UP-TO-DATE 跳过。
  - 有 cargo → 交叉编译；**没 cargo 但仓库里有 `.so` → 跳过 + WARN**（照样出包）；
    两者都没有才失败。非 Windows 跳过该任务，用已提交产物。
  - 手工重编（与 Gradle 等价）：`powershell -ExecutionPolicy Bypass -File android-native\native\temari\build-android.ps1`
    （macOS/Linux 用同目录的 `build-android.sh`，需 `ANDROID_NDK_HOME`）。
  - **产物 `libtemari.so` 是入库的**：改了 Rust 源码重编后要把它一起提交，
    否则没装 Rust 的人会拿到旧实现。详见 `native/README.md`。

### 6.1 本地单元测试（JVM，改完就跑）

```powershell
# workdir: D:\AnnieMusic\android-native
& .\gradlew.bat :app:testDebugUnitTest --console=plain
# 只看结果：app\build\test-results\testDebugUnitTest\*.xml
```

- 位置：`app/src/test/java/...`（纯 JVM），夹具放 `app/src/test/resources/fixtures/`。
- **能测就测，改完就跑**；不比编译多花几秒，但能挡住「编译通过、行为错」的那一类。
- 测试里**不许**碰 Android 类（`MediaStore` / `ContentResolver` / `MediaMetadataRetriever` / `Context`），
  一碰就 `not mocked` 直接红。碰到这种依赖，说明这段逻辑该往下游拆。

**测什么**（这些已经证明值得测）：

| 类别 | 例子 |
|---|---|
| 字节级编解码 | `AnnieTag` 各格式的读写往返（还断言「音频字节一个都没变」） |
| 解析器 | master m3u8 / media playlist / `resolveAppleVariantWithFallback` |
| 纯映射 | `TagMapper.ofSong()`（下载到底会写哪些字段）、平台响应 → `Song` |
| 键构造 | `AppleManifest.*Key()`（播放与下载必须一字不差，否则缓存不命中） |
| 文本处理 | LRC / 逐字歌词 / 文件名拆歌手 |

**两条硬要求**：

1. **跨实现校验**：只做「自己写自己读」等于自证循环。写完把文件留在
   `app/build/annietag-verify/`，再用 **PC 上的 ffprobe** 反向读一遍：
   ```powershell
   & ffprobe -v error -show_entries format_tags -of default=noprint_wrappers=1 app\build\annietag-verify\xxx.m4a
   # Ogg/Opus 的标签在 stream_tags 里，不在 format_tags
   ```
   已靠这一步抓到 10 个真 bug（FLAC `PICTURE` 是大端、ID3 `USLT` 语言码要 `und`、
   Ogg 的 framing bit 与整页 CRC、TTA 用 APEv2 不是 ID3、DSDIFF 首个 chunk 从 16 开始……）。
2. **断言红了先怀疑实现**：不许为了让测试变绿去放宽断言。确实要改断言时，
   在测试注释里写清「为什么原来的期望是错的」。

**为了让代码可测**：把「不碰 Android、不碰网络」的纯逻辑抽出来单独放
（例：`TagMapper.ofSong()`、`AppleManifest` 的键构造函数），
不要在 Composable / ViewModel / 下载器里内联一段只能上真机验的字符串拼接。

## 7. 页面栈与返回手势

### 7.1 四层结构

| 层 | 承载 | 唯一事实来源 | 返回语义 |
|---|---|---|---|
| **L0 Tab** | `AppTab.Home / PlaylistParse / Library` | `AppShellState.selectedTab` | 非默认 tab 时退回主页 |
| **L1 页面栈** | `UiState.pageStack: List<AppRoute>`，13 种 Route | `UiState.pushRoute / popRoute / clearRoutes` | 弹栈顶 |
| **L2 模态层** | `UiState` 的布尔 + `Overlay.RENDER_ORDER` | `core/routing/OverlayStack.kt` | 关最上层的那个 |
| **L3 页内形态** | 详情页自己的 tab / 多选（如歌手的「全部歌曲」） | 页面内部状态 | 先回上一层形态，不出栈 |

L3 **不是**独立页面，不许为它 `pushRoute`；它压在 L1 的某一页**内部**。

### 7.2 出栈只有一条路径

```
系统返回 → AppShell 唯一的 PAGE 处理器（handleBack == BackTarget.PAGE）
        → PageCloseRelay.requestCloseTop()
        → 栈顶页注册的 requestClose（播滑出动画）
        → popRoute()
```

- **栈内页面禁止自己 `BackHandler { popRoute() }`。** 那是第二条出栈路径，会造成
  「返回键失效 / 关错页 / 下层页面没动画」。这一整类 bug 都源自重复的出栈权。
- 顶栏返回箭头 / 关闭按钮**必须**用 `LocalOverlayFrame` 给的 `requestClose`，
  不许直接传 `onClose` / `popRoute`（那样会硬切，没有滑出动画）。

### 7.3 栈内页面的写法

- 一律用 `LocalOverlayFrame(onClose = { popRoute() }) { requestClose -> ... }` 包。
  它提供 scrim、150ms 滑入/滑出、`closing` 防重入，并**自动把自己的 `requestClose`
  注册进 `PageCloseRelay`**。
- 页面**没有**页内状态回退需求时：**完全不声明 `BackHandler`**。
- 有页内状态回退需求时，只能这样写：

  ```kotlin
  BackHandler(enabled = isTop && 本页条件) { 只改本页状态；绝不调 popRoute() }
  ```

- `isTop` 的实参**必须是宿主的 `localTop`**（= 栈顶 **且** `pageCanConsumeBack`），
  不许传裸 `isTop`。传裸值会让「⋮ 面板 / 播放页开着」时页面仍吃返回，在浮层背后把自己关掉；
  被压在下面时更会抢走上层页面的返回，表现成「返回键毫无反应」。

### 7.4 新增浮层（L2）必须做三件事

`OverlayVisibilityFlags` 现在与 `BACK_RULES` 一一对应、**覆盖全部 20 种可见浮层**
（含两个不在 `UiState` 里的搜索页状态：`searchSelectionOverlay` / `searchSelection`）。
新增浮层时三件都要做，缺一件就会退化成「靠注册顺序兜住」：

1. **加可见性状态**：优先加在 `UiState`；状态不在 `UiState` 里的（如 `TranslatePicker.visible`、
   `SearchScreenState.sourcePickerVisible`、`SearchSelectionViewModel.state`）就在
   `toOverlayFlags` 加一个入参，由宿主传。
2. **登记进模型**：`OverlayVisibilityFlags` 加字段 + `BACK_RULES` 加规则。
   **位置一律排在 `PAGE` 之上** —— 浮层出现时详情页不许再吃返回，
   否则页面会在面板背后把自己的子标签 / 多选状态悄悄改掉（表现为「返回键没反应」）。
3. **声明在 `pageStack` 之后**，并 `BackHandler(enabled = visible) { onClose() }`。

- 为什么 2 和 3 都要：**2 决定裁决**（`handleBack` 返回什么、`localTop` 是否为 true）；
  **3 决定实际互斥**（逆向派发谁先收到）。两者取其一都能工作，但只做 3 就是「靠顺序巧合」，
  只做 2 则浮层可能被页面盖住 / 抢返回。
- `toOverlayFlags()` 在 `AppShell` 里**只算一处**（`val overlayFlags = ...`），
  根返回 / 搜索态返回 / 页面栈返回共用同一份。**不许在各处再单独算一遍** ——
  以前就是散着算，漏传那两个非 `UiState` 开关，导致 `handleBack` 以为「没有浮层」。
- 等价手段是开头 `if (!visible) return`（整段不组合，handler 也就不存在）。
  **选一种即可**，不要既早退又给 `enabled`，那只会让「到底谁在吃返回」更难查。
- 浮层**不许**无条件注册 `BackHandler { }`（连 `enabled` 都不写）。

### 7.5 注册顺序即优先级

`OnBackPressedDispatcher` 按**注册逆序**派发，且**跳过未启用的回调**。所以：

- 绘制顺序 = 组合顺序；组合顺序 = 注册顺序；注册顺序的逆序 = 返回优先级。
- 想让 A 比 B 先收到返回，就让 A **声明在 B 之后**。这条只对同层（同一父节点内）成立。
- 优先级表只在 `core/routing/OverlayStack.kt` 里写一次（`BACK_RULES` +
  `RENDER_ORDER`），**不许在别处再硬编码一份顺序**。`handleBack` 是纯函数，不依赖
  ViewModel / Composable。

### 7.6 `PageCloseRelay` 的使用约束

- 它是**进程级单例的有序列表**，"列表尾部 = 栈顶"。
- 注册必须**每页只做一次**：`rememberUpdatedState(requestClose)` + `DisposableEffect(Unit)`。
  - 不许把 `requestClose` 当 `DisposableEffect` 的 key。它通常是每次重组新建的 lambda，
    key 变化会「撤销 + 重新注册」，等价于把该页挪到队尾 —— 一个被压在下面的页面只要重组一次，
    就抢走「栈顶」身份，返回时关错页面。
- 出栈时 `unregister` **只移除自己那一项**，不许把整个列表清空。
  清空会让父页找不到自己的注册者，退化成 `popRoute()` —— 表现为「第一次返回有滑出动画，
  从子页退回父页后再返回，父页瞬间消失」。

### 7.7 页面内的全屏面板必须交给宿主渲染

- 页面**不许内联**渲染「需要盖住迷你播放条 / 底栏」的全屏面板。
  内联就等于画在页面内部，而迷你条与底栏都在页面之上 → 面板底部（音质档位、取消按钮）
  会被压住，表现为「面板能开，但最下面点不到」。
- 页面侧唯一入口是 `DetailQualityPickerHost`（`LocalDetailQualityPickerHost`）：
  页面只**提供内容**，由 `AppShell` 在 `AppBottomBar` **之后**统一渲染
  （`detailPickerHost.slot?.invoke()`，对应 `Overlay.QUALITY_PICKER_*` 层）。
- 已接入：专辑 / 歌手 / 歌单详情、Qobuz 详情。新加「多选批量下载」的页面必须走这条路。
- 无宿主时（独立整页版、预览）退化为内联渲染是**正确**的，保留这个 fallback。
- 现状是两套并存，**有意为之、暂时不统一**：
  - 单曲下载（⋮ → 下载）走 `UiState.pickerVisible` + `pickerSong`，`AppShell` 直接渲染；
  - 选择浮层批量下载走 `DownloadOrchestrator.batchPickerVisible`，`AppShell` 直接渲染；
  - 页面内批量下载走页面本地状态 + 宿主槽位。
  统一状态源属于另一个量级的改动（会动三个页面 + 选择浮层 + 编排器），现在没有 bug 收益。

### 7.8 宿主槽位的四条纪律

1. **只在打开时抢占槽位**：`if (open) host.show(owner, panel)`；关闭时不写。
2. **关闭时不清槽**：保留内容，让面板自带的淡出动画播完（`QualityPickerSheet` 的 `fade`）。
3. **页面退出必须清自己的槽**：`DisposableEffect(owner) { onDispose { host.clear(owner) } }`。
   `clear` 只在 `owner` 是自己时才清，否则会误清上层详情页的面板。
4. **可见性标志必须在组合期读一次**：

   ```kotlin
   val pickerOpen = st.pickerVisible        // 组合期读 → 建立依赖
   SideEffect { if (pickerOpen) host.show(owner, panel) }
   ```

   只在 `SideEffect` 块里读的话，`false → true` 不会触发本页重组 → `SideEffect` 不重跑 →
   槽位永远不写 → **第一次点下载什么都不弹**，要等下一次无关重组（例如退出多选）才把面板
   「迟到」地挂上去。这就是「第一次不弹、返回之后突然冒出来、第二次正常」的标准成因。

## 8. Compose 写法红线

- **状态的读取位置决定它能不能触发重绘。**
  `SideEffect` / `LaunchedEffect` / `DisposableEffect` 块**内部**的读取**不算**组合依赖。
  要「状态变了界面跟着变」，就在组合期读一次（哪怕只是 `val x = state` 赋给局部量）。
  排查「改了状态但界面没反应」时，先找这个。
- **`clickable(enabled = false)` 在 Compose 里依然占据命中测试**，不能用它实现「隐藏就不挡点击」。
  要让浮层完全不参与，就让整段不组合（例如 `if (!visible && fade == 0f) return`）。
  反面教材与完整推导见 `widgets/QualityPicker.kt` 里「全屏浮层不得吃掉下层点击」那段注释。
- 全屏浮层的遮罩若常驻，会吃掉下层所有按钮的点击（顶栏、搜索框、下拉、⇄ 全部点不动）。
  判据同第一条：不参与命中测试的唯一手段是**不组合**。

---

## 99. 待办（临时进度，做完即删）

> **本节违反「AGENTS.md 只放规则」的原则，是有意为之的例外**：这些上下文一旦压缩就丢，
> 又不能从代码里反推（做了一半、边界条件、已定但没落地的决定）。
> **做完一项删一项；全部完成就把整个第 99 节删掉。不写已完成项——写了就成了坟场。**

### 99.2 候选率集合：加兜底 + 按"信设备"处理 192k 上限

现状（`MediaPlayerBackend.kt` 1456 行附近）候选率 = 平台可建 ∪ 设备声明 ∪ codec ∪ 计划 ∪ 用户选，
**没有"标准档位兜底"** —— 设备沉默时（部分 USB DAC 的 `getSampleRates()` 不可靠）拿不到 384k。

1. 加固定兜底列表：44.1 家族 + 48 家族到 768k，并进那个 `buildList`
2. **`MAX_LEGAL_PCM_RATE = 192_000`（`DsdOutput.kt`）按 A 处理**：信设备，不再用 `AudioFormat`
   合法上限卡死（它把 352.8k 永久灰掉）。建轨失败的率交给 `failedRates` 记住并降档
3. `AAUDIO_PROBE_RATES` 硬编码 5 档（44.1/48/88.2/96/192），**没有 384k**。改成跟随候选率，
   但**懒探测**：启动只探常用档，其余用到才探（`probedExclusive == null` 本就允许试，
   行为不会漏，只是面板少一行）

### 99.3 换设备的一次性提示（Snackbar）

已做：设置页**常驻**那一行（`OutputAdvice` + `OutputAdviceTest`）。没做：设备变化时的一次性提示。

接 `AudioOutputController.onDevicesChanged` → 一条一次性 UI 事件。文案已定：

| 事件 | 文案 |
|---|---|
| 插 USB DAC | `已连接 USB DAC`（独占仍不可用则追 `独占仍不支持`） |
| 插有线 | `已切换到有线` |
| 拔掉回扬声器 | `已回到扬声器 · 仅 48 kHz` |
| 当前通路在新设备失效 | `独占在新设备上不生效 · 开关保持开启` |

**最后一句必须有** —— 开关是"意愿"，不随设备回滚，要明确告诉用户没被重置。

### 99.4 换设备的音频行为（用户说"还有别的要求"，**等他提**）

已知代价：`onDevicesChanged` 连着 `_reloadCurrentSource()` 两次（当场一次、700ms 重探后又一次），
每次重建都要重新解析 URL + 重新 configure；暂停中也重建（白花一次）。**先别动，等需求。**

### 99.5 文档同步

`AAUDIO-HANDOFF.md` 仍按"透明 / 调音"两模式描述，第 1、2 节的链路图与文件表已过时。
矩阵重构后要改：链路图、`OutputPath` 说明，并把 `bitPerfectMixer` 那条标成"已删除"。

### 99.6 未验证项（有设备才能验）

- **AAudio 通路的单曲循环**：`AaudioSink.flush()` 里补 `play()` 那个修复只在**系统通路**验过
  （两台机都拿不到独占）。等小尾巴 / 换 ROM 后补验。
- **`resetprop aaudio.mmap_policy 3`**：PLQ110 有 root，还没试。这是唯一能区分
  "MMAP 是 ROM 禁的还是 HAL 不给"的手段。
- **JM13（或任何 DAP）的 `PROPERTY_OUTPUT_SAMPLE_RATE` 是否动态**：见 99.7。

### 99.7 「按源采样率输出」= 完全交给系统（**已决策，不再是待办**）

**决策**：开关打开就**一律按源率建轨、不做任何预转换**，不看系统主率。

```kotlin
!tuned && followSourceRate && sourceRate > 0 -> 0
```

**为什么砍掉"源率 == 主率"那个智能判据**（曾经实现过 `sourceRateIsSafe`）：

- 它依赖 `PROPERTY_OUTPUT_SAMPLE_RATE`，而那是 `AudioManager` 的**静态属性**
  （primary output 的默认率，不是此刻实际率）。
- 做了动态跟随的 DAP（[飞傲官方 FAQ](https://www.fiio.com/newsinfo/69479.html) 明确说
  **第三方 App 也不 SRC**）那个属性照样报 48000 → 判据把"系统本来能原样"判成"会被转"
  → 替系统白转一遍，**废掉设备能力**。
- 而 App 层**事后测不到**系统是否 SRC（`getTimestamp().framePosition` 按 track 自己的率计帧），
  所以"先试再兜底"也不成立。

**通用教训**：凡是"替用户判断设备能力"的智能逻辑，都会误伤一部分设备，而用户无法绕过。
给开关 + 写清风险，比给一个自作聪明的判定更可靠。这条值得推广到别处。

文案（设置页）：
`按源率建轨、不做预转换；若系统固定主率，重采样会由系统完成，质量低于本应用`

**三态方案（Off/Auto/Force）已否决** —— 有了 Force 语义就不需要 Auto，两态足够。

**JM13 具体行为仍未实测**（搜到的都是 X5 / M11S / M21 / R9）。插上后要确认：
1. `PROPERTY_OUTPUT_SAMPLE_RATE` 是否随曲目变（决定这个开关在它上面是"必要"还是"多余"）
2. 有没有给第三方 App 开 `AUDIO_OUTPUT_FLAG_DIRECT` 端口
3. Android 版本（决定 AAudio 独占那条路能不能走）

### 99.8 等设备：飞傲 KA13（小尾巴）+ JM21（DAP）

**用户已下单，到货前不写 USB 独占 / DoP。** 下面这份清单是前面所有推断的验证项 ——
推断已经落到代码里（见 C 节），等真机能验的就只有这些。

**到货前不要再动**：`configure.sh` 的 `dst`（一行、零依赖，但没样本可验）、USB 直连、
DoP packer。

#### A. KA13（USB 小尾巴）—— 验「高率到底能不能到」

按顺序做，前一项决定后一项还有没有意义：

1. `AudioDeviceInfo.sampleRates` 声明了什么？有没有 384k / 768k / 352.8k？
   **只是声明**，不代表跑得动。
2. `platformCanBuildRate(384000)` / `(352800)` 返回什么？
   **等价于问「这台系统的 framework 有没有放开 192k 上限」** —— 返回 false 就到此为止，
   除 USB 直连外没有别的路。
3. 若能过第 2 步：`SystemSinkProbe` 建不建得出 352.8k/384k 的轨？
   看「输出能力（实测）」页的「系统建轨实测（>192k）」那一行。
4. AAudio EXCLUSIVE 在 352.8k / 384k 上能不能开？
   现在 `AAUDIO_PROBE_RATES` 只到 192k 五档，要临时加两档才看得见。
   **这一步决定 DoP 有没有戏**：EXCLUSIVE 拿得到、且回读率与请求一致 ⇒ DoP 可以走 A 路。
5. KA13 的 DSD 能力（DoP / native）在 Android 侧**看不到** —— 那是 DAC 描述符里的信息，
   只能靠 USB 直连读 `FORMAT_TYPE` 才知道。别指望探针能给答案。

#### B. JM21（DAP）—— 验「系统通路保不保真」

1. `PROPERTY_OUTPUT_SAMPLE_RATE` 是静态 48000 还是随曲目变？（99.7 的核心问题）
2. 放一首 44.1k、一首 96k：系统通路按源率建轨后，`actualRate` 真的跟着走吗？
3. 有没有 `AUDIO_OUTPUT_FLAG_DIRECT` 给第三方 App？（决定 mixer 会不会 clamp DoP 帧）
4. AAudio EXCLUSIVE 能不能拿到？回读率一致吗？
5. **拿海贝音乐在同一台机器上试 DSD native**：
   - 海贝能跑 ⇒ HAL 对第三方开放了那条路，值得逆向研究它走哪条
   - 海贝也跑不了 ⇒ 那条路只给飞傲自家 App，对我们等同不存在
6. 若 DoP 走系统通路能正常出声 ⇒ 说明通路保真，DoP packer 优先级最高

#### C. 已就绪（到货即可直接验，不用再改）

- `SystemSinkProbe`：真建轨探测 + `(deviceId, route, rate, depth)` 缓存 + 设备变更失效
- 「输出能力（实测）」页的「系统建轨实测（>192k）」行
- `dsdMaxPcmRate(probedMax)`：上限按实测取，不再钉死 176.4k
- `dsdModeAvailability` 的 `exclusiveReady` 语义已改成「有一条逐字节保真的独占流」，
  **不再绑 USB 独占**（DoP 实现时不会误绑）
- DSD → PCM 抽取已由 r8brain 做（native 只解到 352800，`keepSourceRate`）
- `AudioFormat` 192k 上限已抽成 `AUDIO_FORMAT_MAX_RATE`，`>192k` 才走建轨验证

#### D. 两条路怎么选（按实测二选一）

- **A 路可行**（EXCLUSIVE 回读保真 / JM21 系统通路保真）→ 做 DoP packer（~100 行 native），
  覆盖小尾巴 + DAP，不需要 USB 直连
- **A 路不可行** → 才考虑 USB 直连（libusb + `UsbDeviceConnection.getFileDescriptor()`，
  ~1000 行，且要先验 SELinux 放不放 `USBDEVFS_SUBMITURB`）

**海贝逆向产物（`D:\haibei`）的用法**：只借「哪些参数必须交给用户」
（`uac_dsd_max_sample_rate` / `uac_dsd_compensate` 这两个 `native_set*` 旋钮），不抄代码。


