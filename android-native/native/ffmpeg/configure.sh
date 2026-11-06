#!/usr/bin/env bash
# ffmpeg 裁剪版 configure（由 build-android.sh / build-android.ps1 调用，不要手工跑）
#
# ## 目标：**只替换 libav\*.so，保留 ffmpeg-kit 的 Java 层**
#
# `dev.ffmpegkit-maintained:ffmpeg-kit-audio` 这个 AAR 里有三样东西：
#   1. 我们的自编 so 要替换的 `libav{codec,format,util,swresample,filter,swscale,avdevice}.so`
#   2. **必须保留**的 `libffmpegkit.so`（FFmpegKit / FFprobeKit 的 JNI 实现）
#   3. Java 类（classes.jar）
#
# 第 2 项的 `DT_NEEDED` 写死了 7 个 libav\*.so，且它引用的是**带符号版本**的名字
# （`avcodec_open2@LIBAVCODEC_62`，.so 里有 `.gnu.version_d`）。所以这里：
#   · 库级：avcodec / avformat / avutil / swresample / avfilter / swscale / avdevice **全要**
#   · major 必须与 ffmpeg-kit 8.1.9 一致（62 / 62 / 60 / 6 / 11 / 8 / 62）
#   · 不加 `--disable-symver`：让 ffmpeg 生成版本脚本，否则 libffmpegkit 的
#     versioned 引用在没有版本定义时可能被 bionic 拒绝
#
# ## 真正裁掉的是「编解码器」，不是库
#
# 上游 audio 包里 libavcodec 有 11 MB，因为编进了完整视频解码器
# （h264/hevc/vp8/vp9/av1/mpeg4/mpeg2video/theora）—— 播放器一个都用不到。
# 这里 `--disable-everything` 后只开：
#   · 解码：音频全家族（FLAC/ALAC/AAC/MP3/AC3/EAC3/Opus/Vorbis/APE/TTA/WavPack/DSD/PCM）+ 封面图（mjpeg/png）
#   · 编码：**只有** mjpeg / png（`LocalTagProbe.extractCover` 抽封面用）
#   · 滤镜：**只有** scale / format（同上，`-vf scale=` 会用）+ aresample 家族
#   · 解封装/封装：音频容器 + image2（抽封面输出）
#
# avfilter 里真正占体积的是各个 filter 的实现，filter 一裁，avfilter 也就瘦了。
set -e

: "${SRC_DIR:?SRC_DIR 未设置}"
: "${PREFIX:?PREFIX 未设置}"
: "${CC_BIN:?CC_BIN 未设置}"
: "${SYSROOT:?SYSROOT 未设置}"
: "${AR_BIN:?AR_BIN 未设置}"
: "${NM_BIN:?NM_BIN 未设置}"
: "${RANLIB_BIN:?RANLIB_BIN 未设置}"
: "${STRIP_BIN:?STRIP_BIN 未设置}"
: "${TARGET_TRIPLE:=aarch64-linux-android24}"

cd "$SRC_DIR"

./configure \
  --target-os=android \
  --arch=aarch64 \
  --cpu=armv8-a \
  --enable-cross-compile \
  --cc="$CC_BIN --target=$TARGET_TRIPLE --sysroot=$SYSROOT" \
  --cxx="$CXX_BIN --target=$TARGET_TRIPLE --sysroot=$SYSROOT" \
  --ar="$AR_BIN" \
  --nm="$NM_BIN" \
  --ranlib="$RANLIB_BIN" \
  --strip="$STRIP_BIN" \
  --sysroot="$SYSROOT" \
  --prefix="$PREFIX" \
  --enable-shared \
  --disable-static \
  --disable-programs \
  --disable-doc \
  --disable-debug \
  --disable-iconv \
  --disable-autodetect \
  --enable-zlib \
  --enable-jni \
  --disable-everything \
  --enable-avfilter \
  --enable-swscale \
  --enable-avdevice \
  --enable-swresample \
  --enable-protocol=file,pipe,android_content,http,tcp \
  --enable-demuxer=flac,mp3,mov,ogg,wav,aiff,ape,dsdiff,dsf,matroska,tta,wv,caf,aac,ac3,eac3,dts,image2 \
  --enable-decoder=flac,alac,aac,aac_latm,mp3,mp3float,mp3adufloat,ac3,eac3,opus,vorbis,ape,tta,wavpack,dsd_lsbf,dsd_msbf,dsd_lsbf_planar,dsd_msbf_planar,mjpeg,png,pcm_s16le,pcm_s16be,pcm_s24le,pcm_s24be,pcm_s32le,pcm_s32be,pcm_f32le,pcm_f64le,pcm_u8,pcm_alaw,pcm_mulaw \
  --enable-parser=flac,mpegaudio,aac,ac3,opus,vorbis,dca \
  --enable-encoder=mjpeg,png \
  --enable-muxer=image2,mp4,mov,ipod,flac,mp3,ogg,wav \
  --enable-filter=scale,format,aresample,aformat,null,anull,split,trim \
  --enable-bsf=null \
  --extra-cflags="-O3 -fPIC -ffunction-sections -fdata-sections" \
  --extra-ldflags="-Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"

echo "configure done"
