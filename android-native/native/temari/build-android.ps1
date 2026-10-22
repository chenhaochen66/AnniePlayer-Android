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
$ndk = "D:\ANSSDK\ndk\27.1.12297006"
$clang = Join-Path $ndk "toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android21-clang.cmd"

if (-not (Test-Path $clang)) { throw "找不到 NDK clang：$clang（改脚本里的 `$ndk 路径）" }

# 本机的 rustup/cargo 是分离安装的（见 android-native/AGENTS.md），这两个变量必须显式给
$env:CARGO_HOME = "D:\AnnieMusic\analysis\rust\cargo"
$env:RUSTUP_HOME = "D:\AnnieMusic\analysis\rust\up"
$env:PATH = "$env:CARGO_HOME\bin;$env:PATH"

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
if (-not (Test-Path $so)) { throw "没找到产物：$so" }

$outDir = Join-Path $here "..\..\app\src\main\jniLibs\arm64-v8a"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
Copy-Item $so (Join-Path $outDir "libtemari.so") -Force

$hash = (Get-FileHash (Join-Path $outDir "libtemari.so") -Algorithm SHA256).Hash
Write-Host ("已复制 -> {0}  ({1:N0} KB, sha256={2})" -f (Join-Path $outDir "libtemari.so"), ((Get-Item $so).Length / 1KB), $hash)
