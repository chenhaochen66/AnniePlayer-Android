cmake_minimum_required(VERSION 3.22.1)
project(anniedsp C CXX)

# r8brain 要求 ≥ C++11；NDK 各版本默认值不一致，显式钉住避免"换 NDK 就编不过"
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(temari SHARED IMPORTED)
set_target_properties(temari PROPERTIES
        IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libtemari.so)

# native-ffmpeg-demux.cpp：本地全链路（avformat + avcodec + swresample）。
# 头文件来自 ffmpeg 8.1.3（与运行时 ffmpeg-kit-audio 8.1.9 同属 libavformat major 62，
# 结构体布局在 major 内冻结 → 二进制兼容）。include/libavformat 下只放 4 个公共头。
#
# native-r8b-resampler.cpp：重采样（r8brain-free-src，MIT，header-only，见 thirdparty/r8brain）。
#
# native-aaudio-probe.cpp：AAudio 独占能力探测（开流→读 sharingMode→立刻关闭）。
# 只为"Bit Perfect 那一级能不能用 AAudio 实现"提供实测证据，不参与播放。
add_library(anniedsp SHARED
        native-dsp.cpp
        native-r8b-resampler.cpp
        native-ffmpeg-decoder.cpp
        native-ffmpeg-demux.cpp
        native-aaudio-probe.cpp)

target_include_directories(anniedsp PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/ffmpeg/include
        ${CMAKE_CURRENT_SOURCE_DIR}/thirdparty/r8brain)

# `R8B_EXTFFT=1`：把低通滤波器的 FFT 块零填充加长一倍。
# 上游说明「延迟不是问题就开」——播放场景不在乎几十毫秒初始延迟，开了更省算力。
target_compile_definitions(anniedsp PRIVATE R8B_EXTFFT=1)

# 注意：这里**不能**有 `-ffast-math`。
# 它会打开 `-ffinite-math-only`、允许重排浮点运算，并让 denormal/NaN 语义失效；
# r8brain 的滤波器设计与双精度累加依赖 IEEE 语义，DSP 链也不该为了几个百分点
# 牺牲可预测性（之前的 sinc 实现同样被这个 flag 影响着）。
target_compile_options(anniedsp PRIVATE
        -O3
        -fno-exceptions
        -fno-rtti
        -funroll-loops
        -Wall)

find_library(log-lib log)
find_library(dl-lib dl)
# 注意：**不**在这里 `find_library(aaudio)`。本项目 minSdk = 24，而 libaaudio 只在
# API ≥ 26 的 sysroot 里存在 → 直接链接会在 configure 阶段报 NOTFOUND，且会写成
# `DT_NEEDED` 让 API 24/25 上连 libanniedsp.so 都加载不了。探测改用 dlopen
# （见 native-aaudio-probe.cpp），老机器只是"这一项不可用"。
target_link_libraries(anniedsp ${log-lib} ${dl-lib})

# Temari 单独一个 JNI 库，避免 libtemari 加载失败时连带 anniedsp（调音/ffmpeg）。
add_library(annietemari SHARED native-temari.cpp)
target_compile_options(annietemari PRIVATE -O3 -fno-exceptions -fno-rtti -Wall)
target_link_libraries(annietemari ${log-lib} ${dl-lib} temari)

# ══════════════════════════════════════════════════════════════════════════════════════
# Oboe 后端（`AAUDIO_BACKEND = OBOE` 时使用）—— 独立成一个 so，理由见下
# ══════════════════════════════════════════════════════════════════════════════════════
#
# 为什么单独一个库而不是塞进 anniedsp：
#   1. `anniedsp` 全程 `-fno-exceptions -fno-rtti`（r8brain / ffmpeg 那条链要的是
#      可预测的浮点与零开销），而 Oboe 的头文件按标准 C++ 写 —— 混在同一个 target 里
#      只能靠 `set_source_files_properties` 逐文件覆盖编译选项，容易在换 NDK 时静默失效。
#   2. Oboe 是可选的：`libannieoboe.so` 加载失败只意味着"这个后端不可用"，
#      不能连带把调音/软解也拖下水（和 `libtemari.so` 的隔离理由一样）。
#
# `find_package(oboe REQUIRED CONFIG)` 来自 AAR 里的 **prefab** 包（`liboboe.so` + 头文件，
# 由 `app/build.gradle.kts` 的 `com.google.oboe:oboe` 依赖带进来，走阿里云镜像）。
# 不需要手工拷 .so，也不要 `add_subdirectory`。
#
# ⚠️ **prefab 在 AGP 9.1.0 上不通**（实测：`find_package(oboe)` 报
# `Could not find oboeConfig.cmake`，`oboe_DIR` 从未被注入）。所以现在改用 vendoring，
# 与 `temari` 同一套做法：头文件在 `thirdparty/oboe/include`，
# `liboboe.so` 在 `jniLibs/arm64-v8a`（来源与版本见 `thirdparty/oboe/README.md`）。
add_library(oboe SHARED IMPORTED)
set_target_properties(oboe PROPERTIES
        IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/liboboe.so)

find_library(android-lib android)
find_library(opensles-lib OpenSLES)

add_library(annieoboe SHARED native-oboe-stream.cpp)
target_include_directories(annieoboe PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/thirdparty/oboe/include)
target_compile_options(annieoboe PRIVATE -O3 -Wall)
target_link_libraries(annieoboe
        oboe
        ${log-lib}
        ${dl-lib}
        ${android-lib}
        ${opensles-lib})

# libusb（thirdparty/libusb，1.0.27 + 官方 android/config.h），编成 libusb-1.0.so。
# 没有 autotools 依赖，源文件清单固定；udev / netlink 在 Android 上都不用。
add_library(libusb SHARED
        thirdparty/libusb/core.c
        thirdparty/libusb/descriptor.c
        thirdparty/libusb/io.c
        thirdparty/libusb/sync.c
        thirdparty/libusb/strerror.c
        thirdparty/libusb/hotplug.c
        thirdparty/libusb/os/linux_usbfs.c
        thirdparty/libusb/os/events_posix.c
        thirdparty/libusb/os/threads_posix.c)
set_target_properties(libusb PROPERTIES OUTPUT_NAME "usb-1.0")
target_include_directories(libusb PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/thirdparty/libusb)
target_compile_options(libusb PRIVATE -O2 -Wall -Wno-unused-parameter -Wno-sign-compare)
target_link_libraries(libusb ${log-lib})

# USB 独占。单独成库：libusb 或 USB 相关加载失败不该拖累调音与软解。
add_library(annieuac SHARED native-uac.cpp)
target_include_directories(annieuac PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/thirdparty/libusb)
target_compile_options(annieuac PRIVATE -O3 -Wall)
target_link_libraries(annieuac libusb ${log-lib} ${dl-lib})
