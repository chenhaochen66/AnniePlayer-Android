#!/usr/bin/env bash
# Temari → Android arm64 交叉编译（macOS / Linux）
#
# 与 build-android.ps1 完全等价：同一个 NDK clang、同一组 RUSTFLAGS、同一个落点。
# Windows 上请用 build-android.ps1（Gradle 的 buildTemari 也会自动调它）。
#
# 用法：
#   ANDROID_NDK_HOME=/path/to/ndk/27.1.12297006 ./build-android.sh
#
# 降级：没装 cargo 时不做任何事并返回 0（沿用仓库里已提交的 libtemari.so），
# 只有「没 cargo 且没有已提交产物」才失败 —— 与 PowerShell 版一致。
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
out_dir="$here/../../app/src/main/jniLibs/arm64-v8a"
out_so="$out_dir/libtemari.so"

# ------------------------------------------------------------------------------------------------
# 工具链检查（可降级）
# ------------------------------------------------------------------------------------------------
if ! command -v cargo >/dev/null 2>&1; then
    if [ -f "$out_so" ]; then
        echo "WARN: cargo not found; reusing the committed libtemari.so ($(wc -c < "$out_so") bytes)." >&2
        echo "WARN: install Rust (https://rustup.rs) to rebuild from source." >&2
        exit 0
    fi
    echo "ERROR: Rust toolchain not found (https://rustup.rs) and no committed libtemari.so." >&2
    exit 1
fi

ndk="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [ -z "$ndk" ] || [ ! -d "$ndk" ]; then
    echo "ERROR: NDK 27.1.12297006 not found. Set ANDROID_NDK_HOME." >&2
    exit 1
fi

case "$(uname -s)" in
    Darwin) host_tag="darwin-x86_64" ;;
    Linux)  host_tag="linux-x86_64" ;;
    *)      echo "ERROR: unsupported host: $(uname -s)" >&2; exit 1 ;;
esac
# NDK r27 没有 arm64 宿主预编译包，Apple Silicon 走 x86_64（Rosetta）
clang="$ndk/toolchains/llvm/prebuilt/$host_tag/bin/aarch64-linux-android21-clang"
if [ ! -x "$clang" ]; then
    echo "ERROR: NDK clang not found: $clang" >&2
    exit 1
fi

# ------------------------------------------------------------------------------------------------
# 交叉编译
# ------------------------------------------------------------------------------------------------
# 这两组链接参数是**必须**的，缺任何一个真机就起不来：
#   SONAME           —— libannietemari.so 的 DT_NEEDED 记录它；少了会记成构建机绝对路径
#   max-page-size    —— Android 15+ 的 16KB 页对齐要求
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$clang"
export RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384 -C link-arg=-Wl,-soname,libtemari.so"

cd "$here"
# 幂等：已经装过就是 no-op
rustup target add aarch64-linux-android
cargo build --release --target aarch64-linux-android

built="$here/target/aarch64-linux-android/release/libtemari.so"
if [ ! -f "$built" ]; then
    echo "ERROR: cargo produced no artifact at $built" >&2
    exit 1
fi

mkdir -p "$out_dir"
cp "$built" "$out_so"

sha="$(shasum -a 256 "$out_so" 2>/dev/null | cut -d' ' -f1 || sha256sum "$out_so" | cut -d' ' -f1)"
echo "copied -> $out_so ($(wc -c < "$out_so") bytes, sha256=$sha)"
