# Temari → Android arm64 交叉编译 + 落盘
#
# 用法（在任意目录）：powershell -ExecutionPolicy Bypass -File .\build-android.ps1
#
# 产物：app/src/main/jniLibs/arm64-v8a/libtemari.so
# 说明：`libannietemari.so`（cpp/native-temari.cpp 的 JNI 包装）在运行时 dlopen 这个 .so，
#       所以它必须带 SONAME=libtemari.so，否则 dlopen 会去找构建机的绝对路径而失败。
#       max-page-size=16384 是 Android 15+ 16KB 页对齐的硬性要求。
$ErrorActionPreference = "Stop"

$here = Split-Path -Parent $MyInvocation.MyCommand.Path

# 产物落点（下面「没装 Rust」的降级分支也要用，所以提前算）
$outDir = Join-Path $here "..\..\app\src\main\jniLibs\arm64-v8a"
$outSo = Join-Path $outDir "libtemari.so"

# 本机的 rustup/cargo 是分离安装的（见 android-native/AGENTS.md）。
# **只在目录存在时**才覆盖 —— 别人克隆下来没有这个目录，必须用系统 PATH 里的 cargo。
# 两个路径都能用环境变量覆盖（换机器，或自测「没装 Rust」的降级分支）。
#
# 位置在仓库根的 `.toolchains/rust/` 而不是 `analysis/rust/`：`analysis/` 放的是**逆向分析
# 资产**（_probe 的 Apple 逆向脚本、ak1ra、wrapper、反编译产物…），工具链跟它们混在一起
# 会让「哪些能删、哪些不能删」看不出来。`.toolchains/` 专门放本机工具链（已 ignore）。
$localCargoHome = if ($env:ANNIE_CARGO_HOME) {
    $env:ANNIE_CARGO_HOME
} else {
    "D:\AnnieMusic\.toolchains\rust\cargo"
}
$localRustupHome = if ($env:ANNIE_RUSTUP_HOME) {
    $env:ANNIE_RUSTUP_HOME
} else {
    "D:\AnnieMusic\.toolchains\rust\up"
}
if (Test-Path $localCargoHome) {
    $env:CARGO_HOME = $localCargoHome
    $env:RUSTUP_HOME = $localRustupHome
    $env:PATH = "$env:CARGO_HOME\bin;$env:PATH"
}

# 降级：Gradle 的 buildTemari 会调用本脚本。没装 Rust **不能**让整个 APK 构建失败 ——
# 仓库里带着 libtemari.so，直接用它即可（只有改了 .rs 想重编时才需要 cargo）。
if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) {
    if (Test-Path $outSo) {
        $kb = [math]::Round((Get-Item $outSo).Length / 1KB)
        Write-Warning ("cargo not found; reusing the committed libtemari.so ({0} KB). " -f $kb +
            "Install Rust (https://rustup.rs) to rebuild from source.")
        exit 0
    }
    throw ("Rust toolchain not found (https://rustup.rs) and no committed libtemari.so " +
        "to fall back to.")
}

# NDK：环境变量 → 本机固定路径 → 标准 SDK 目录（别人克隆时用前两个之一）
$ndk = $null
foreach ($candidate in @(
        $env:ANDROID_NDK_HOME,
        "D:\ANSSDK\ndk\27.1.12297006",
        (Join-Path $env:LOCALAPPDATA "Android\Sdk\ndk\27.1.12297006"))) {
    if ($candidate -and (Test-Path $candidate)) { $ndk = $candidate; break }
}
if (-not $ndk) {
    throw ("NDK 27.1.12297006 not found. Set ANDROID_NDK_HOME, or install it under " +
        "%LOCALAPPDATA%\Android\Sdk\ndk\.")
}

$clang = Join-Path $ndk "toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android21-clang.cmd"
if (-not (Test-Path $clang)) { throw "找不到 NDK clang：$clang" }

$env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $clang
$env:RUSTFLAGS = "-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384 -C link-arg=-Wl,-soname,libtemari.so"

Push-Location $here
try {
    # 幂等：已经装过就是 no-op
    & rustup target add aarch64-linux-android
    & cargo build --release --target aarch64-linux-android
    if ($LASTEXITCODE -ne 0) { throw "cargo build 失败（exit=$LASTEXITCODE）" }
} finally {
    Pop-Location
}

$so = Join-Path $here "target\aarch64-linux-android\release\libtemari.so"
if (-not (Test-Path $so)) { throw "没找到 cargo 产物：$so" }

New-Item -ItemType Directory -Force -Path $outDir | Out-Null
Copy-Item $so $outSo -Force

# 这里**不用 Get-FileHash**：Gradle 的 Exec 调起来的 powershell 里它会 CommandNotFound
# （模块自动加载环境与交互式会话不同），而那是会把整个构建判失败的错误。
# 大小足够确认落盘成功；真要核对 SHA256 就手工跑一次。
$kb = [int]((Get-Item $outSo).Length / 1KB)
Write-Host ("copied -> {0} ({1} KB)" -f $outSo, $kb)