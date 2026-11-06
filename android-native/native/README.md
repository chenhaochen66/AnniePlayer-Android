# `native/` —— 项目的原生代码

这里放**所有需要自己编译的原生代码**。APK 里最终会出现三个由我们构建的 `.so`，它们的来源各不相同：

```
native/temari/            ← Rust crate（Apple FairPlay SAMPLE-AES 解密）
      │  cargo build --target aarch64-linux-android
      ▼
app/src/main/jniLibs/arm64-v8a/libtemari.so        ← 产物（**已提交进仓库**）

app/src/main/cpp/         ← C++（CMake，由 Gradle 的 externalNativeBuild 编译）
      ├─ native-temari.cpp    → libannietemari.so   ← JNI 包装，DT_NEEDED 指向 libtemari.so
      └─ native-dsp.cpp 等    → libanniedsp.so      ← 调音 / ffmpeg 本地解码 / r8brain 重采样
```

APK 里最终只有这两个自建 C++ 库 + `libtemari.so` + `libtaglib.so`（第三方 TagLib JNI，随仓库提交）。

> 曾经还有一个 `shim-dl-android.cpp`（`libdl_android.so` 桩），配合把 `libaudioclient.so` 及其依赖
> 闭包（52 个系统库副本，9.7 MB）打进 APK。实测这些副本**从未被映射** —— 系统库在进程启动时
> 就已按同名加载（`/system/lib64/libaudioclient.so`、`/apex/.../bionic/libdl_android.so`），
> APK 内的副本永远轮不到，代码里也没有任何 `dlopen("libaudioclient.so")`。
> 已于 2026-10 整体移除，只保留真正被调用的 `libtemari.so` 与 `libtaglib.so`。

## 为什么 Rust 不交给 CMake

`app/src/main/cpp/CMakeLists.txt` 里 `temari` 是 **IMPORTED** target：

```cmake
add_library(temari SHARED IMPORTED)
set_target_properties(temari PROPERTIES
        IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libtemari.so)
```

它只引用预编译产物，不参与任何 Rust 编译。Rust 侧是独立 crate（自带 `Cargo.toml` /
`rust-toolchain.toml` / `.gitignore`），由 `temari/build-android.ps1`（Windows）或
`temari/build-android.sh`（macOS / Linux）交叉编译，产物拷进 `jniLibs` 后被 CMake 和 APK 打包一起用。

## 构建时会发生什么

`app/build.gradle.kts` 里注册了 `buildTemari`，挂在 `preBuild` 上：

| 情况 | 行为 |
|---|---|
| 本机有 cargo，且 `src/` 或 `Cargo.toml` 比产物新 | 自动交叉编译，更新 `libtemari.so` |
| 本机有 cargo，源码没变 | Gradle UP-TO-DATE，跳过（按内容快照判定） |
| **本机没有 cargo**，但仓库里有 `libtemari.so` | 跳过 + WARN，用已提交的产物（**照样能编出 APK**） |
| 两者都没有 | 构建失败，提示装 Rust |
| 非 Windows | Gradle 跳过该任务，用已提交产物；想重编就手工跑 `build-android.sh` |

所以：**克隆 → `gradlew assembleDebug` 一定能出包**，装不装 Rust 都行。
装了 Rust 的人改 `temari/src/*.rs` 后会自动重编。

## 改 Rust 源码时

```powershell
# 手工编（Gradle 也会自动跑，两者等价）
powershell -ExecutionPolicy Bypass -File native\temari\build-android.ps1
```

编完记得把 `app/src/main/jniLibs/arm64-v8a/libtemari.so` **一起提交** —— 它是"没装 Rust 的人也能编"的兜底。

两条链接参数不能去掉（脚本里已写死）：

- `-Wl,-soname,libtemari.so` —— 否则 `libannietemari.so` 的 `DT_NEEDED` 会记录构建机绝对路径，真机加载失败；
- `-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384` —— Android 15+ 的 16KB 页对齐要求。

## 验收（改完 `.so` 之后）

```powershell
# SONAME 必须是 libtemari.so
& $env:ANDROID_NDK_HOME\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe -d `
    app\src\main\jniLibs\arm64-v8a\libtemari.so | Select-String SONAME

# LOAD 段对齐必须是 0x4000
& $env:ANDROID_NDK_HOME\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe -l `
    app\src\main\jniLibs\arm64-v8a\libtemari.so | Select-String LOAD
```

`libannietemari.so` 的 `DT_NEEDED` 里也要能看到 `libtemari.so`（那是 `target_link_libraries` 的结果）。
