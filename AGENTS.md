# 规则

> 本文件**只放规则**，不记录进度、不记录已完成项。进度看代码本身。

## 0. 最高优先级

- **禁止用批处理命令 / 脚本修改代码文件。**
  包括但不限于：PowerShell（`Set-Content` / `Out-File` / `-replace` 回写）、Python、`sed` / `awk`、
  循环替换、批量重命名。改代码只能逐个走「读文件 → 定点替换」的编辑工具。
  这条是硬性的：之前用 `Set-Content -NoNewline` 批处理回写，把一整份 1400 行的 Kotlin 文件的换行全部抹平，
  只能靠重排 + 编译报错逐处修复。
- 脚本只允许做**只读**的事：列文件、量体积、跑编译、装 APK、查设备。**写文件一律走编辑工具。**
- 只有用户明确说「提交」才 `git commit`；**从不 push**。

## 1. 范围

- 只改 Kotlin 版（`android-native/`），不看 Flutter / Dart 版。
- 非必要不用子 agent。

## 2. 调试纪律

- 不写调试日志；临时诊断代码（计时打点、临时 dump 文件）用完必须删干净。
- 不截图。
- 改之前先读文件；改完必须跑一次编译（`gradlew :app:compileDebugKotlin`）再下结论，不靠猜。

## 3. 代码分层

- Apple **官方**接口：`data/sources/AppleMusicApi.kt`（`amp-api-edge`，搜索 / 详情 / 专辑 / 歌手 / storefront）。
- Apple **非官方**接口：`data/sources/apple/thirdparty/`（歌词、`/parse/song`、`/key`）。
  新增第三方源时只加一个 `AppleThirdPartyApi` 实现并切 `AppleThirdPartyRegistry.current`，
  **不要动官方文件**。

## 4. 缓存边界（不要另造缓存）

| 名字 | 用途 | 谁用 |
|---|---|---|
| `SimpleCache` / `CacheDataSource` | 流媒体播放分片缓存 | `MediaPlayerBackend` 播放 |
| `CacheService` | API 响应 / 歌词 / 播放列表文本 | 请求层 |
| `_cacheDir` | 下载临时文件、封面临时文件 | 下载 |
| 最终下载文件 | 用户下载结果 | MediaStore / 下载目录 |

- 播放缓存**只服务流媒体播放**；下载绕开它（不读、不写、不建下载分片缓存）。
- 播放列表 `/media.m3u8` **不缓存**（URL 带一次性 nonce）；`init` / `seg/{n}` 用
  `ApplePlaybackServer.cacheKeyFor()` 给的稳定 key，且 key 必须带档位
  （`apple:{adamId}:{tier}:v2:init|seg:{n}`）。
- 下载文件 key 独立：`apple:{adamId}:{variantGroup}`。播放不依赖下载文件，下载不碰播放缓存。

## 5. Apple 实现硬约束

- 所有 Apple 格式（ALAC / AAC / Atmos）统一由 **ffmpeg 解码**，不走 MediaCodec；
  Media3 的网络 / 缓存 / HLS / 时间轴 / MediaSession 全部保留。
- 不用 `MediaDrm`；解密走 Temari（`tmpl_from_json` / `decrypt_sample_ffi` / `tmpl_destroy`）。
- `libtemari.so` 必须带 `SONAME=libtemari.so` 与 `-Wl,-z,max-page-size=16384`，
  否则 `libannietemari.so` 的 `dlopen` 会找构建机绝对路径、真机必失败。
- 固定 key `P000000000` **只用于 segment 0**；`/key` 不接受它，随包内置
  `assets/apple_fixed_key.json`。
- 解密后的样本长度不变；交给 ffmpeg 前不要改长度。
- ALAC 的位深 / 采样率从 `EXT-X-MEDIA` 读，**不要**从 `audioTraits` 猜。
- `/parse/song` 没有国家参数，不跟随选中的国家。
- 档位缺失统一走 `resolveAppleVariantWithFallback`（超高解析 → 无损 → AAC 256）；
  Atmos 不参与 ALAC/AAC 的降级链。
- 本机 HLS 代理地址是**一次性**的（每次 `start()` 换端口）：**禁止缓存复用**，
  Apple 每次切歌都必须真实走一遍 `start()`。
- 播放失败兜底只重试一次（清缓存 + 强制重解析），仍失败自动跳下一首。

## 6. 构建 / 装机

```powershell
# workdir: D:\AnnieMusic\android-native
& .\gradlew.bat :app:assembleDebug --console=plain
& "D:\ANSSDK\platform-tools\adb.exe" install -r --no-streaming app\build\outputs\apk\debug\app-debug.apk
```

- NDK：`27.1.12297006`，仅 `arm64-v8a`。
- Temari（源码变更才需要重编）：
  `powershell -ExecutionPolicy Bypass -File android-native\native\temari\build-android.ps1`
  （源码在 `android-native/native/temari/`，脚本会重编并落盘到 `app/src/main/jniLibs/arm64-v8a/`）。
