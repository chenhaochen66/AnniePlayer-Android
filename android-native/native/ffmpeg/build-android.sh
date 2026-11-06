#!/usr/bin/env bash
# ffmpeg 裁剪版 → Android arm64 交叉编译（Linux / WSL 用这个；Windows 用 build-android.ps1）
#
# 用法：
#   bash native/ffmpeg/build-android.sh            # 增量
#   CLEAN=1 bash native/ffmpeg/build-android.sh    # 重新 configure
#
# 依赖：
#   · Android NDK r27c（默认 ~/android-ndk-r27c，可用 ANDROID_NDK_HOME 覆盖）
#   · 宿主 gcc / make（Ubuntu: apt install build-essential）
#   · ffmpeg 源码：src/ffmpeg（fetch-source.ps1 或手工放一份 8.1.3）
#
# 产物：app/src/main/jniLibs/arm64-v8a/lib{avcodec,avformat,avutil,swresample}.so
#
# 为什么自己编 / 裁掉什么，见 configure.sh 与 build-android.ps1 的文件头。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SRC="$HERE/src/ffmpeg"
PREFIX="$HERE/prefix-linux"
OUT="$REPO/app/src/main/jniLibs/arm64-v8a"
JOBS="${JOBS:-$(nproc)}"

NDK="${ANDROID_NDK_HOME:-$HOME/android-ndk-r27c}"
if [ ! -d "$NDK" ]; then
  echo "NDK 未找到：$NDK（设置 ANDROID_NDK_HOME，或下载 android-ndk-r27c-linux.zip 解到 \$HOME）" >&2
  exit 1
fi
if [ ! -f "$SRC/configure" ]; then
  echo "ffmpeg 源码未就绪：$SRC" >&2
  exit 1
fi

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
SYSROOT="$TOOLCHAIN/sysroot"
export TARGET_TRIPLE="aarch64-linux-android24"

# ffmpeg 不支持 out-of-tree 构建（configure 必须在源码树里跑），而源码树如果在
# `/mnt/d`（WSL 的 9p 挂载）上，编译会被文件系统拖慢好几倍。所以：源码在 /mnt 下时
# 先镜像一份到 ext4（$HOME/.ffmpeg-build）再编，编完只把产物拷回 Windows 侧。
case "$SRC" in
  /mnt/*)
    MIRROR="${FFMPEG_MIRROR_DIR:-$HOME/.ffmpeg-build}"
    mkdir -p "$MIRROR"
    if [ ! -d "$MIRROR/ffmpeg" ] || [ "${CLEAN:-0}" = "1" ]; then
      echo "== mirror source -> $MIRROR/ffmpeg (ext4) =="
      rm -rf "$MIRROR/ffmpeg"
      cp -a "$SRC" "$MIRROR/ffmpeg"
      # 源码树可能带着上一次（也许是跑到一半被打断的）configure 产物一起被复制过来。
      # 那类 Makefile 里的 SRC_PATH 指向原来的绝对路径，make 会直接报
      #「/tests/Makefile: No such file or directory」—— 只在**全新镜像**后清一次。
      # ⚠️ **不要删 Makefile**。那是 ffmpeg 源码自带的主 Makefile（约 7.5 KB，
      # 首行 `MAIN_MAKEFILE=1`）。删掉之后 configure 会补一个「转发型」Makefile
      # （`include ./Makefile`）—— 因为在源码内构建时 configure 把 `source_path` 算成 `.`，
      # 这个转发文件 include 的正是自己，于是 make 递归打开同一个文件，
      # 表现成 `Too many open files` 或 `Segmentation fault`。
      # 这里只清 configure 的**产物**，主 Makefile 缺了就补回去。
      rm -f "$MIRROR/ffmpeg/config.h" "$MIRROR/ffmpeg/config.asm" \
            "$MIRROR/ffmpeg/.config" "$MIRROR/ffmpeg/ffbuild/config.mak" \
            "$MIRROR/ffmpeg/ffbuild/config.log" "$MIRROR/ffmpeg/ffbuild/config.fate" \
            2>/dev/null || true
      # 同理，`ffbuild/arch.mak` 也是**源码自带**的（不是 configure 产物），
      # 删掉会得到 `common.mak:214: ffbuild/arch.mak: No such file or directory`。
      for keep in Makefile ffbuild/arch.mak; do
        if [ ! -s "$MIRROR/ffmpeg/$keep" ]; then
          cp "$SRC/$keep" "$MIRROR/ffmpeg/$keep"
        fi
      done
    fi
    BUILD_SRC="$MIRROR/ffmpeg"
    # prefix 也放 ext4：make install 会在里面反复 mkdir/copy，走 9p 同样慢
    PREFIX="$MIRROR/prefix"
    ;;
  *)
    BUILD_SRC="$SRC"
    ;;
esac

if [ "${CLEAN:-0}" = "1" ] && [ -f "$BUILD_SRC/Makefile" ]; then
  ( cd "$BUILD_SRC" && make distclean ) || true
fi

export SRC_DIR="$BUILD_SRC"
export PREFIX
export CC_BIN="$TOOLCHAIN/bin/clang"
export CXX_BIN="$TOOLCHAIN/bin/clang++"
export SYSROOT
export AR_BIN="$TOOLCHAIN/bin/llvm-ar"
export NM_BIN="$TOOLCHAIN/bin/llvm-nm"
export RANLIB_BIN="$TOOLCHAIN/bin/llvm-ranlib"
export STRIP_BIN="$TOOLCHAIN/bin/llvm-strip"

# ── ffmpeg-kit 兼容垫片 ─────────────────────────────────────────────────────
# `libffmpegkit.so`（ffmpeg-kit 的 JNI 层）引用了 `av_set_saf_open` / `av_set_saf_close`，
# 那是他们 fork 出来的 Android SAF 补丁，**上游 ffmpeg 8.1.3 全树没有这两个符号**。
# 缺符号时 Android 的 linker 在 dlopen 阶段就失败（不是延迟绑定），表现成
# FFprobeKit / 抽封面整体不可用。
#
# ⚠️ 必须放在 **libavutil**，不是 libavformat。实测它引用的是带版本的符号：
#     av_set_saf_open@LIBAVUTIL_60
# 而 bionic 的 linker **按版本名匹配**。放 libavformat 里只会导出
# `av_set_saf_open@@LIBAVFORMAT_62`，版本对不上 → 依然报
#     cannot locate symbol "av_set_saf_open" referenced by libffmpegkit.so
# libavutil 的 version script 是 `av*` 通配，所以放这里自然拿到 @@LIBAVUTIL_60。
#
# 本播放器只走 file:// 与 http(s)://，从不打开 content:// URI，这两个函数永远不会被
# 调用；这里只提供「能被解析」的符号。
#
# 补丁每次构建都幂等应用（源码镜像重建后会丢），因此放在 configure 之前。
if ! grep -q 'annie_saf_shim' "$BUILD_SRC/libavutil/Makefile" 2>/dev/null; then
  cat > "$BUILD_SRC/libavutil/safstub.c" <<'SHIM_C'
/* annie_saf_shim: 见 native/ffmpeg/build-android.sh 的说明
 *
 * ffmpeg 默认开 -Wmissing-prototypes（且这里是 -Werror），所以必须先给出原型，
 * 否则 safstub.c 直接编译失败（而报错信息只提 "no previous prototype"，
 * 很容易误以为是函数名写错）。 */
void *av_set_saf_open(void *opaque, void *callback);
int av_set_saf_close(void *opaque);

void *av_set_saf_open(void *opaque, void *callback)
{
    (void)opaque;
    (void)callback;
    return 0;
}

int av_set_saf_close(void *opaque)
{
    (void)opaque;
    return 0;
}
SHIM_C
  # `+=` 追加到 OBJS：Makefile 顺序执行，library.mak 用到 OBJS 时已经包含它
  printf '\n# annie_saf_shim\nOBJS += safstub.o\n' >> "$BUILD_SRC/libavutil/Makefile"
fi

# 旧版垫片曾经塞在 libavformat/avio.c 里。镜像若是旧的会带着它，重复定义会让
# 两个库各导出一份同名但版本不同的符号 —— 这里幂等地把那段切掉。
if grep -q 'annie_saf_shim' "$BUILD_SRC/libavformat/avio.c" 2>/dev/null; then
  awk '/annie_saf_shim: 见 native\/ffmpeg\/build-android.sh/ { exit } { print }' \
      "$BUILD_SRC/libavformat/avio.c" > "$BUILD_SRC/libavformat/avio.c.tmp"
  mv "$BUILD_SRC/libavformat/avio.c.tmp" "$BUILD_SRC/libavformat/avio.c"
fi

# ── DSDIFF（.dff）demuxer ────────────────────────────────────────────────────
# 上游 ffmpeg 8.1.3 **只有 DSF，没有 DSDIFF**（ffmpeg-kit 8.1.9 也没有，我比对过
# 两边 libavformat 的字符串表）。所以 `.dff` 无法「移植」，只能按 Sony 的
# DSDIFF 1.5 规范自己写一份，见 patches/dsdiffdec.c。
#
# 集成点有三处，缺一不可：
#   1. libavformat/dsdiffdec.c            —— 实现
#   2. libavformat/Makefile              —— OBJS-$(CONFIG_DSDIFF_DEMUXER)
#   3. libavformat/allformats.c          —— extern 声明。
#      configure 用 `find_things_extern demuxer FFInputFormat libavformat/allformats.c`
#      扫描 extern 行来生成 DEMUXER_LIST；**少了这行，--enable-demuxer=dsdiff
#      会被静默忽略**（和之前 wavpack/dff 那两个错名一样的坑：configure 不报错）。
if ! grep -q 'DSDIFF_DEMUXER' "$BUILD_SRC/libavformat/Makefile" 2>/dev/null; then
  cp "$HERE/patches/dsdiffdec.c" "$BUILD_SRC/libavformat/dsdiffdec.c"
  printf '\nOBJS-$(CONFIG_DSDIFF_DEMUXER)             += dsdiffdec.o\n' \
      >> "$BUILD_SRC/libavformat/Makefile"
  sed -i 's/^extern const FFInputFormat  ff_dsf_demuxer;$/extern const FFInputFormat  ff_dsf_demuxer;\nextern const FFInputFormat  ff_dsdiff_demuxer;/' \
      "$BUILD_SRC/libavformat/allformats.c"
  grep -q 'ff_dsdiff_demuxer' "$BUILD_SRC/libavformat/allformats.c" \
    || { echo "DSDIFF 注册失败：allformats.c 里没有 ff_dsf_demuxer 这行可插入" >&2; exit 1; }
fi

# 判据是 **configure 的产物**（ffbuild/config.mak），不是 Makefile —— 后者是源码自带的，
# 永远存在；用 Makefile 判断会漏掉「config.mak/arch.mak 被清掉」的情况，
# 那样 make 一启动就报 `ffbuild/arch.mak: No such file or directory`。
if [ ! -f "$BUILD_SRC/ffbuild/config.mak" ]; then
  echo "== configure =="
  bash "$HERE/configure.sh"
fi

echo "== make -j$JOBS =="
# ffmpeg 的并行构建会同时打开上千个文件（每个 .d 依赖文件 + 头文件），
# WSL 默认 ulimit -n 1024 会直接「Too many open files」。
ulimit -n 65536 2>/dev/null || ulimit -n 16384 2>/dev/null || true
( cd "$BUILD_SRC" && make -j"$JOBS" )
( cd "$BUILD_SRC" && make install )

mkdir -p "$OUT"
# 7 个都要：`libffmpegkit.so` 的 DT_NEEDED 写死了 avfilter/swscale/avdevice，
# 少一个就是 dlopen 失败（表现为 FFprobeKit / 抽封面整体不可用）。
LIBS="libavcodec.so libavformat.so libavutil.so libswresample.so libavfilter.so libswscale.so libavdevice.so"
for n in $LIBS; do
  if [ ! -e "$PREFIX/lib/$n" ]; then
    echo "make install 没产出 $n" >&2
    exit 1
  fi
  # install 出来的 so 没 strip；AGP 打包前也会 strip 一次，但先在这里做掉，
  # 好让脚本报的体积就是最终进 APK 的量级。
  "$STRIP_BIN" --strip-unneeded "$PREFIX/lib/$n" 2>/dev/null || true
  cp -fL "$PREFIX/lib/$n" "$OUT/$n"
  printf 'copied -> %s (%s KB)\n' "$n" "$(( $(stat -c%s "$OUT/$n") / 1024 ))"
done

total=0
for n in $LIBS; do total=$(( total + $(stat -c%s "$OUT/$n") )); done
awk -v t="$total" 'BEGIN { printf "total ffmpeg so = %.2f MB\n", t/1048576 }'
