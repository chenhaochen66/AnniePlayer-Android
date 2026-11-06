# 获取 ffmpeg 源码（8.1.3）→ src/ffmpeg
#
# 用法：powershell -ExecutionPolicy Bypass -File .\fetch-source.ps1
#
# 为什么是 8.1.3：`app/src/main/cpp/ffmpeg/include` 里放的头文件就是 8.1.3
# （libavcodec 62.28.103 / libavformat 62.12 / libavutil 60.26 / libswresample 6.3）。
# 头文件与运行时 so 必须同 minor 才安全 —— 结构体布局只在 major 内冻结，
# 跨 minor 编译出来的 .so 用旧头解释仍能跑，但新增字段会读错。
#
# 为什么用 .tar.gz 而不是 .tar.xz：Windows 自带的 bsdtar 解 xz 需要外部 `xz` 程序，
# 本机没有；gzip 是内建的。
$ErrorActionPreference = "Stop"

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$srcParent = Join-Path $here "src"
$target = Join-Path $srcParent "ffmpeg"
$tarball = Join-Path $srcParent "ffmpeg-8.1.3.tar.gz"

if (Test-Path (Join-Path $target "configure")) {
    Write-Host "源码已存在：$target（要重下先删掉该目录）"
    exit 0
}

New-Item -ItemType Directory -Force -Path $srcParent | Out-Null
if (-not (Test-Path $tarball)) {
    Write-Host "下载 ffmpeg-8.1.3.tar.gz ..."
    & curl.exe -L --retry 3 -m 1800 -o $tarball "https://ffmpeg.org/releases/ffmpeg-8.1.3.tar.gz"
    if ($LASTEXITCODE -ne 0) { throw "下载失败（exit=$LASTEXITCODE）" }
}

Write-Host "解压 ..."
& tar.exe -xzf $tarball -C $srcParent
if ($LASTEXITCODE -ne 0) { throw "解压失败（exit=$LASTEXITCODE）" }

$extracted = Join-Path $srcParent "ffmpeg-8.1.3"
if (-not (Test-Path $extracted)) { throw "解压后没找到 $extracted" }
Move-Item $extracted $target -Force

if (-not (Test-Path (Join-Path $target "configure"))) { throw "源码不完整：$target" }
Write-Host "OK -> $target"
