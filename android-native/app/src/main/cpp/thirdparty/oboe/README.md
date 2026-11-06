# Oboe（vendored）

**版本**：1.11.0
**上游**：<https://github.com/google/oboe>
**许可证**：Apache License 2.0（见 <https://www.apache.org/licenses/LICENSE-2.0>）
**取件方式**：从 **阿里云 Google 镜像** 拉 AAR，再从 AAR 里取出产物

```
https://maven.aliyun.com/repository/google/com/google/oboe/oboe/1.11.0/oboe-1.11.0.aar
```

## 目录里的东西

| 路径 | 是什么 |
|---|---|
| `include/oboe/*.h` | AAR 的 `prefab/modules/oboe/include/oboe/`，**未改动**（16 个头，178 KB） |
| `../../jniLibs/arm64-v8a/liboboe.so` | AAR 的 `prefab/modules/oboe/libs/android.arm64-v8a/liboboe.so`，`llvm-strip --strip-unneeded`（3.59 MB → 320 KB） |

## 为什么是 vendoring 而不是 Gradle 依赖

`com.google.oboe:oboe` 的 AAR 只提供 **prefab** 包，官方用法是
`find_package(oboe REQUIRED CONFIG)`。实测 **AGP 9.1.0 不会把 AAR 的 prefab 透给
`externalNativeBuild`** —— `oboe_DIR` 从未被注入，configure 阶段直接报
`Could not find oboeConfig.cmake`。

于是改成与 `libtemari.so` 完全相同的做法：CMake 里 `add_library(oboe SHARED IMPORTED)`
指向 `jniLibs/${ANDROID_ABI}/liboboe.so`。好处是构建不再依赖"AGP 会不会正确解析 prefab"，
离线/换机器也能编。

## 重新取件（升级版本时）

```powershell
$ver = "1.11.0"
$url = "https://maven.aliyun.com/repository/google/com/google/oboe/oboe/$ver/oboe-$ver.aar"
$tmp = "$env:TEMP\oboe-$ver.aar"
Invoke-WebRequest -Uri $url -OutFile $tmp
Expand-Archive $tmp -DestinationPath "$env:TEMP\oboe-$ver" -Force

# 头文件
Copy-Item "$env:TEMP\oboe-$ver\prefab\modules\oboe\include\oboe" `
          "app\src\main\cpp\thirdparty\oboe\include\oboe" -Recurse -Force

# 库（必须 strip，未 strip 的 arm64 so 有 3.6 MB）
& "$env:ANDROID_NDK_HOME\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe" `
  --strip-unneeded -o "app\src\main\jniLibs\arm64-v8a\liboboe.so" `
  "$env:TEMP\oboe-$ver\prefab\modules\oboe\libs\android.arm64-v8a\liboboe.so"
```

## 关于 ABI 的两个事实（升级前先看）

1. **`abi.json` 写的是 `"api":21, "ndk":21, "stl":"c++_shared"`** ——
   这个 so 依赖 `libc++_shared.so`。本工程的 APK 里那份来自 ffmpeg-kit（jniLibs），
   两份会在打包时被 `pickFirsts += "**/libc++_shared.so"` 合并成一份。
   引擎必须**比其他所有 so 新或同代**：libc++ 保持向后兼容，反过来不成立。
2. **`DT_NEEDED` 里没有 `libaaudio.so`**（已用 `llvm-readelf -d` 核对）——
   Oboe 是运行时 `dlopen` 拿 AAudio 的，所以 API 24/25 上加载 `liboboe.so` 不会失败，
   那些机器只是会走 OpenSL ES 后端（没有 EXCLUSIVE 概念，`sharingMode` 会报 SHARED，
   由 `AaudioSink.configure` 判为"独占未生效"并回落系统通路 —— 这是**正确**行为）。
