# 规则

> 本文件**只放规则**，不记录进度、不记录已完成项。进度看代码本身。

## 0. 最高优先级

- **脚本可以改代码，但必须先备份、改完校验、确认无误再删备份。**
  仍然**优先**用编辑工具做定点替换；只有「一次要动几十处、逐处改不现实」时才用脚本
  （PowerShell 的 `Set-Content` / `Out-File` / `-replace` 回写、Python、`sed` / `awk`、
  循环替换、批量重命名都算）。
  用脚本之前和之后，按这个顺序走：

  1. **先备份**：把这次**会被改到**的文件复制到 `android-native/.tmp-backup/<yyyyMMdd-HHmm>/`，
     目录结构保持原样（例如 `app/src/main/java/.../Foo.kt` → `.tmp-backup/<ts>/app/src/main/java/.../Foo.kt`）。
     `.tmp-backup/` 已被 `.gitignore` 的 `**/.tmp-*/` 覆盖，不会入库。
  2. **再改**：脚本只碰备份里列过的那批文件，不顺手扩大范围。
  3. **然后校验**：`git diff` 逐文件看差异（尤其确认换行/编码没变）、
     `gradlew :app:compileDebugKotlin` 过编译、涉及行为时跑 `gradlew :app:testDebugUnitTest`。
  4. **最后清理**：全部通过后删掉整个备份目录。**备份没删之前，不要开始别的改动**——
     中途又改别的文件会让「回滚点」和「当前状态」对不上。

  背景：之前用 `Set-Content -NoNewline` 批处理回写，把一整份 1400 行的 Kotlin 文件的换行全部抹平，
  只能靠重排 + 编译报错逐处修复。有备份就能一键回滚，不用从那堆报错里往回拼。

- 脚本做**只读**的事不需要备份：列文件、量体积、跑编译、装 APK、查设备。
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

---

## 99. 临时提醒（**commit 后删掉本节**）

> 本节是唯一一次性的内容，和文件头「本文件只放规则」冲突，所以提交完立刻删除。

`android-native/app/src/main/jniLibs/arm64-v8a/` 下这批由 `native/ffmpeg/` 自编 / 抽取的 so
**还没有进 git**（`git status` 里是 `??`），合计约 6.29 MB：

```
libavcodec.so   libavformat.so   libavutil.so    libswresample.so
libavfilter.so  libswscale.so    libavdevice.so
libffmpegkit.so libffmpegkit_abidetect.so       libc++_shared.so
```

它们和已入库的 `libtemari.so` / `libtaglib.so` 是**同一类东西：构建产物要入库**——
`.gitignore` 里「ffmpeg 自编」那段写明了理由：带上这几个 so，克隆下来直接
`assembleRelease` 就能出包，不需要 WSL + Linux NDK + 10 分钟交叉编译。

`libffmpegkit*.so` / `libc++_shared.so` 是从 AAR 抽出来的第三方二进制（LGPL），
许可证文本已随 `res/raw/license_*.txt` 一起入库，再分发没问题。

**所以下次用户说「提交」时，先把这批 so 摆出来问一句，别擅自 add：**

> 这批自编 / 抽取的 so 要不要一起提交？（10 个，约 6.29 MB）
> `libavcodec.so` `libavformat.so` `libavutil.so` `libswresample.so`
> `libavfilter.so` `libswscale.so` `libavdevice.so`
> `libffmpegkit.so` `libffmpegkit_abidetect.so` `libc++_shared.so`

然后按用户的答复执行：

- **说要** → `git add android-native/app/src/main/jniLibs/arm64-v8a/*.so`
- **说不要** → 只提交代码改动，so 继续留在工作区当 `??`
- **说只加一部分** → 只 `git add` 他点名的那些，别替他扩大范围

无论加不加，**这次提交完成后删掉本节**。
