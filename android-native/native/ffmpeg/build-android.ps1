# ffmpeg 裁剪版 → Android arm64 交叉编译 + 落盘
#
# 用法（在任意目录）：
#   powershell -ExecutionPolicy Bypass -File .\build-android.ps1            # 增量
#   powershell -ExecutionPolicy Bypass -File .\build-android.ps1 -Clean     # 重新 configure
#
# 产物：app/src/main/jniLibs/arm64-v8a/lib{avcodec,avformat,avutil,swresample}.so
#
# ## 为什么要自己编
#
# 上游 `dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.9` 的 libavcodec.so 有 11.09 MB，
# 里面**编进了完整视频解码器**（h264/hevc/vp8/vp9/av1/mpeg4/mpeg2video/theora/mjpeg）——
# 一个音频播放器永远用不到。加上白带的 avfilter(2.78) / swscale(0.52) / avdevice(0.05)，
# 单个 ABI 有 20.35 MB 是纯 ffmpeg。
#
# 本例只开 cpp 里 dlsym 得到的那些符号所对应的编解码器（见 configure.sh 的清单），
# 预期总产物 ~5 MB。
#
# ## 依赖
#
# - NDK 27.1.12297006（提供 clang / llvm-ar / make）
# - Git for Windows（提供 MSYS 的 sh/sed/awk —— ffmpeg 的 configure 是 sh 脚本）
# - 源码：`src/ffmpeg`（跑一次 `fetch-source.ps1`，或用你已有的 ffmpeg 8.1.3 源码树）
param(
    [int]$Jobs = 8,
    [switch]$Clean
)
$ErrorActionPreference = "Stop"

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Resolve-Path (Join-Path $here "..\..")
$srcDir = Join-Path $here "src\ffmpeg"
$prefix = Join-Path $here "prefix"
$outDir = Join-Path $repo "app\src\main\jniLibs\arm64-v8a"

if (-not (Test-Path (Join-Path $srcDir "configure"))) {
    throw "ffmpeg 源码未就绪：$srcDir（先跑 fetch-source.ps1）"
}

# ── NDK（与 temari 脚本同一套解析顺序） ────────────────────────────────────────
$ndk = $null
foreach ($candidate in @(
        $env:ANDROID_NDK_HOME,
        "D:\ANSSDK\ndk\27.1.12297006",
        (Join-Path $env:LOCALAPPDATA "Android\Sdk\ndk\27.1.12297006"))) {
    if ($candidate -and (Test-Path $candidate)) { $ndk = $candidate; break }
}
if (-not $ndk) {
    throw "NDK 27.1.12297006 not found. Set ANDROID_NDK_HOME."
}

$toolchain = Join-Path $ndk "toolchains\llvm\prebuilt\windows-x86_64"
$sysroot = Join-Path $toolchain "sysroot"
$makeDir = Join-Path $ndk "prebuilt\windows-x86_64\bin"
$make = Join-Path $makeDir "make.exe"

foreach ($p in @($toolchain, $sysroot, $make)) {
    if (-not (Test-Path $p)) { throw "NDK 缺件：$p" }
}

# ── MSYS 工具（configure 是 sh 脚本；make 的 recipe 也要 sh 执行） ─────────────
$sh = $null
foreach ($c in @("D:\Git\usr\bin\sh.exe", (Join-Path $env:ProgramFiles "Git\usr\bin\sh.exe"))) {
    if (Test-Path $c) { $sh = $c; break }
}
if (-not $sh) {
    $g = Get-Command sh.exe -ErrorAction SilentlyContinue
    if ($g) { $sh = $g.Source }
}
if (-not $sh) { throw "找不到 MSYS sh.exe（需要 Git for Windows）" }
$msysBin = Split-Path -Parent $sh

# 路径统一成正斜杠：bash 与 Windows 程序都能理解，且能原样写进 Makefile
function WinSlash([string]$p) { $p -replace '\\', '/' }
$ccBin = WinSlash (Join-Path $toolchain "bin\clang.exe")
$cxxBin = WinSlash (Join-Path $toolchain "bin\clang++.exe")
$arBin = WinSlash (Join-Path $toolchain "bin\llvm-ar.exe")
$nmBin = WinSlash (Join-Path $toolchain "bin\llvm-nm.exe")
$ranlibBin = WinSlash (Join-Path $toolchain "bin\llvm-ranlib.exe")
$stripBin = WinSlash (Join-Path $toolchain "bin\llvm-strip.exe")
foreach ($b in @($ccBin, $cxxBin, $arBin, $nmBin, $ranlibBin, $stripBin)) {
    if (-not (Test-Path $b)) { throw "找不到 NDK 工具：$b" }
}

# ── configure ────────────────────────────────────────────────────────────────
if ($Clean) {
    Push-Location $srcDir
    try {
        if (Test-Path "Makefile") { & $make distclean 2>&1 | Out-Null }
    } finally { Pop-Location }
}

$env:SRC_DIR = WinSlash $srcDir
$env:PREFIX = WinSlash $prefix
$env:CC_BIN = $ccBin
$env:CXX_BIN = $cxxBin
$env:SYSROOT = WinSlash $sysroot
$env:AR_BIN = $arBin
$env:NM_BIN = $nmBin
$env:RANLIB_BIN = $ranlibBin
$env:STRIP_BIN = $stripBin
$env:TARGET_TRIPLE = "aarch64-linux-android24"

if (-not (Test-Path (Join-Path $srcDir "Makefile"))) {
    Write-Host "== configure =="
    & $sh (Join-Path $here "configure.sh")
    if ($LASTEXITCODE -ne 0) { throw "configure 失败（exit=$LASTEXITCODE）" }
}

# ── make ─────────────────────────────────────────────────────────────────────
# PATH 必须是 Windows 格式（make 是原生程序）：MSYS bin 提供 sh/sed，NDK bin 提供 clang。
$origPath = $env:PATH
$env:PATH = "$msysBin;$makeDir;$($toolchain)\bin;$origPath"
$env:SHELL = WinSlash $sh
try {
    Write-Host "== make -j$Jobs =="
    Push-Location $srcDir
    try {
        & $make "-j$Jobs" "SHELL=$($env:SHELL)"
        if ($LASTEXITCODE -ne 0) { throw "make 失败（exit=$LASTEXITCODE）" }
        & $make "install" "SHELL=$($env:SHELL)"
        if ($LASTEXITCODE -ne 0) { throw "make install 失败（exit=$LASTEXITCODE）" }
    } finally { Pop-Location }
} finally {
    $env:PATH = $origPath
}

# ── 落盘 ─────────────────────────────────────────────────────────────────────
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$wanted = @("libavcodec.so", "libavformat.so", "libavutil.so", "libswresample.so")
foreach ($n in $wanted) {
    $from = Join-Path $prefix "lib\$n"
    if (-not (Test-Path $from)) { throw "make install 没产出 $n（$from）" }
    $to = Join-Path $outDir $n
    Copy-Item $from $to -Force
    $kb = [int]((Get-Item $to).Length / 1KB)
    Write-Host ("copied -> {0} ({1} KB)" -f $n, $kb)
}

$total = ($wanted | ForEach-Object { (Get-Item (Join-Path $outDir $_)).Length } | Measure-Object -Sum).Sum
Write-Host ("total ffmpeg so = {0:N2} MB" -f ($total / 1MB))
