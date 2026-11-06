#include <jni.h>
#include <dlfcn.h>
#include <cstdint>
#include <cstdio>

/**
 * AAudio **独占能力探测**（只探测，不播放）。
 *
 * 播放内核已经整体换成 Oboe（`native-oboe-stream.cpp` + `libannieoboe.so`）。
 * 这个文件留在 `libanniedsp.so` 里只剩一件事：回答"这个采样率 / 位深能不能开成
 * `AAUDIO_SHARING_MODE_EXCLUSIVE`"，供「输出能力（实测）」面板与
 * `resolveOutputPlan` 的降级链使用。
 *
 * ══ 判据只能是实测值 ══
 *
 * AAudio 的 `setSharingMode(EXCLUSIVE)` 只是一个**请求**：HAL 可以不理会，
 * 回来一个 SHARED 流。所以必须 `AAudioStream_getSharingMode()` 回读。
 *
 * ══ 为什么这里**不 include `<aaudio/AAudio.h>`**、也不链接 libaaudio ══
 *
 * 本项目 `minSdk = 24`：
 * 1. 头文件用 `__INTRODUCED_IN(26)` 标了 AAudio 的每个函数，在
 *    `--target=...-android24` 下取它们的地址会直接编译失败；
 * 2. 链接同样不可行 —— `find_library(aaudio)` 在 API 24 的 sysroot 里是 NOTFOUND，
 *    而且会写进 `DT_NEEDED`，导致 API 24/25 的机器连 `libanniedsp.so` 都加载不了，
 *    连带毁掉调音与 ffmpeg。
 *
 * 所以：**自己声明**这些符号的类型（不透明结构 + NDK 稳定 ABI 的枚举值，
 * 数值取自 NDK 27 的 `aaudio/AAudio.h`），运行时 `dlopen("libaaudio.so")` 解析。
 * 老机器上就是"这一项不可用（err:nolib）"，其它功能完全不受影响。
 *
 * ## 回包格式（Kotlin 侧 `parseAaudioProbe` 解析，保持稳定）
 *
 * ```
 * EXCLUSIVE/48000/32/LOW_LATENCY      成功；第二段是流的真实率，第三段是位深
 * SHARED/48000/32/NONE                开出来了，但没拿到独占（HAL 忽略请求）
 * err:-899                            开流失败，数字是 aaudio_result_t
 * err:builder                         连 builder 都没建起来
 * err:nolib / err:symbol              dlopen 失败 / 符号缺失
 * ```
 */

/** 不透明句柄（AAudio.h 里同样是前向声明，我们从不解引用） */
extern "C"
{
struct AAudioStreamBuilder;
struct AAudioStream;
}

using aaudio_result_t = int32_t;

namespace
{
// ── 常量（数值取自 NDK 27 `aaudio/AAudio.h`，属稳定 ABI 面）──
//
// ⚠️ **`aaudio_sharing_mode_t` 是无显式值的枚举，第一项就是 0**：
//
// ```c
// typedef enum {
//     AAUDIO_SHARING_MODE_EXCLUSIVE,   // = 0
//     AAUDIO_SHARING_MODE_SHARED       // = 1
// } aaudio_sharing_mode_t;
// ```
//
// 这里以前写成 `kSharingExclusive = 1`，于是同一份错误被用了两次：
//   · 请求：`setSharingMode(builder, 1)` → 实际请求的是 **SHARED**
//   · 判定：`getSharingMode(stream) == 1` → 回读 SHARED(1) 也等于 1 → **判成"独占"**
//
// 两头用同一个错值互相抵消，于是面板上五行「AAudio 独占 xxx kHz 独占可用」
// 全是假阳性 —— 这一级从来没有真的拿到过 MMAP 独占，声音一直走的是系统 mixer。
// **改这个常量时请连判定一起看**，别只改一边。
constexpr int32_t kOk = 0;                            // AAUDIO_OK
constexpr int32_t kDirectionOutput = 0;               // AAUDIO_DIRECTION_OUTPUT
constexpr int32_t kSharingExclusive = 0;              // AAUDIO_SHARING_MODE_EXCLUSIVE
constexpr int32_t kPerfLowLatency = 12;               // AAUDIO_PERFORMANCE_MODE_LOW_LATENCY
constexpr int32_t kFormatI16 = 1;                     // AAUDIO_FORMAT_PCM_I16
constexpr int32_t kFormatFloat = 2;                   // AAUDIO_FORMAT_PCM_FLOAT
constexpr int32_t kFormatI24Packed = 3;               // AAUDIO_FORMAT_PCM_I24_PACKED
constexpr int32_t kFormatI32 = 4;                     // AAUDIO_FORMAT_PCM_I32

struct AAudioApi
{
    void* handle = nullptr;

    aaudio_result_t ( *createBuilder )( AAudioStreamBuilder** ) = nullptr;
    aaudio_result_t ( *deleteBuilder )( AAudioStreamBuilder* ) = nullptr;
    void ( *setDirection )( AAudioStreamBuilder*, int32_t ) = nullptr;
    void ( *setSampleRate )( AAudioStreamBuilder*, int32_t ) = nullptr;
    void ( *setChannelCount )( AAudioStreamBuilder*, int32_t ) = nullptr;
    void ( *setFormat )( AAudioStreamBuilder*, int32_t ) = nullptr;
    void ( *setSharingMode )( AAudioStreamBuilder*, int32_t ) = nullptr;
    void ( *setPerformanceMode )( AAudioStreamBuilder*, int32_t ) = nullptr;
    aaudio_result_t ( *openStream )( AAudioStreamBuilder*, AAudioStream** ) = nullptr;
    int32_t ( *getSharingMode )( AAudioStream* ) = nullptr;
    int32_t ( *getSampleRate )( AAudioStream* ) = nullptr;
    int32_t ( *getFormat )( AAudioStream* ) = nullptr;
    int32_t ( *getPerformanceMode )( AAudioStream* ) = nullptr;
    aaudio_result_t ( *close )( AAudioStream* ) = nullptr;

    bool ready() const
    {
        return( handle != nullptr && createBuilder && deleteBuilder && setDirection &&
            setSampleRate && setChannelCount && setFormat && setSharingMode &&
            setPerformanceMode && openStream && getSharingMode && getSampleRate &&
            getFormat && getPerformanceMode && close );
    }
};

/** 进程内只解析一次；`handle == nullptr` 就代表"这台机器没有 libaaudio" */
const AAudioApi& api()
{
    static AAudioApi instance;
    static bool resolved = false;
    if( resolved )
    {
        return( instance );
    }

    resolved = true;
    void* handle = dlopen( "libaaudio.so", RTLD_NOW | RTLD_LOCAL );
    if( handle == nullptr )
    {
        return( instance );
    }

    instance.handle = handle;
    instance.createBuilder =
        reinterpret_cast< decltype( instance.createBuilder )>( dlsym( handle, "AAudio_createStreamBuilder" ));
    instance.deleteBuilder =
        reinterpret_cast< decltype( instance.deleteBuilder )>( dlsym( handle, "AAudioStreamBuilder_delete" ));
    instance.setDirection =
        reinterpret_cast< decltype( instance.setDirection )>( dlsym( handle, "AAudioStreamBuilder_setDirection" ));
    instance.setSampleRate =
        reinterpret_cast< decltype( instance.setSampleRate )>( dlsym( handle, "AAudioStreamBuilder_setSampleRate" ));
    instance.setChannelCount =
        reinterpret_cast< decltype( instance.setChannelCount )>( dlsym( handle, "AAudioStreamBuilder_setChannelCount" ));
    instance.setFormat =
        reinterpret_cast< decltype( instance.setFormat )>( dlsym( handle, "AAudioStreamBuilder_setFormat" ));
    instance.setSharingMode =
        reinterpret_cast< decltype( instance.setSharingMode )>( dlsym( handle, "AAudioStreamBuilder_setSharingMode" ));
    instance.setPerformanceMode =
        reinterpret_cast< decltype( instance.setPerformanceMode )>( dlsym( handle, "AAudioStreamBuilder_setPerformanceMode" ));
    instance.openStream =
        reinterpret_cast< decltype( instance.openStream )>( dlsym( handle, "AAudioStreamBuilder_openStream" ));
    instance.getSharingMode =
        reinterpret_cast< decltype( instance.getSharingMode )>( dlsym( handle, "AAudioStream_getSharingMode" ));
    instance.getSampleRate =
        reinterpret_cast< decltype( instance.getSampleRate )>( dlsym( handle, "AAudioStream_getSampleRate" ));
    instance.getFormat =
        reinterpret_cast< decltype( instance.getFormat )>( dlsym( handle, "AAudioStream_getFormat" ));
    instance.getPerformanceMode =
        reinterpret_cast< decltype( instance.getPerformanceMode )>( dlsym( handle, "AAudioStream_getPerformanceMode" ));
    instance.close = reinterpret_cast< decltype( instance.close )>( dlsym( handle, "AAudioStream_close" ));
    return( instance );
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeAaudio_nativeProbe(
        JNIEnv* env, jobject /* object 单例 */, jint sampleRate, jint pcmFormat)
{
    // ⚠️ 注意符号名里的 `annie_1music`：JNI 把包名段内的下划线转义成 `_1`。
    // 写成 `annie_music` 会得到 UnsatisfiedLinkError，Kotlin 侧只看到 `err:jni`
    // —— 实测就是这么踩到的（面板五行全是 err:jni）。
    const AAudioApi& a = api();
    if( !a.ready() )
    {
        return( env->NewStringUTF( a.handle == nullptr ? "err:nolib" : "err:symbol" ));
    }

    AAudioStreamBuilder* builder = nullptr;
    aaudio_result_t r = a.createBuilder( &builder );
    if( r != kOk || builder == nullptr )
    {
        return( env->NewStringUTF( "err:builder" ));
    }

    /**
     * `pcmFormat` 直接是 `aaudio_format_t`，**不是位深**。
     *
     * 为什么不传"16/24/32"：32 位在 AAudio 里有两种互不兼容的东西 ——
     * `PCM_I32 = 4`（整数）和 `PCM_FLOAT = 2`（浮点）。HAL 的 MMAP profile 往往
     * 只声明其中一种：本机 PLQ110 的 `mmap_no_irq_out` 只有整数位深（16 / 8_24 /
     * 24_PACKED / 32），**没有 float**。用"32"去请求 float，结果必然是共享流，
     * 却会被误读成"这台设备不支持独占" —— 一个十足的假阴性。
     */
    int32_t format = pcmFormat;
    if( format != kFormatI16 && format != kFormatFloat &&
        format != kFormatI24Packed && format != kFormatI32 )
    {
        format = kFormatFloat;
    }
    jint bits = 32;
    if( format == kFormatI16 )
    {
        bits = 16;
    }
    else if( format == kFormatI24Packed )
    {
        bits = 24;
    }

    a.setDirection( builder, kDirectionOutput );
    a.setSampleRate( builder, sampleRate );
    a.setChannelCount( builder, 2 );
    a.setFormat( builder, format );
    // 请求独占：绕开 AudioFlinger 的 mixer（这是"不做任何转换"的唯一途径）
    a.setSharingMode( builder, kSharingExclusive );
    a.setPerformanceMode( builder, kPerfLowLatency );

    AAudioStream* stream = nullptr;
    r = a.openStream( builder, &stream );
    if( r != kOk || stream == nullptr )
    {
        a.deleteBuilder( builder );
        char buf[ 32 ];
        snprintf( buf, sizeof( buf ), "err:%d", static_cast< int >( r ));
        return( env->NewStringUTF( buf ));
    }

    const int32_t mode = a.getSharingMode( stream );
    const int32_t rate = a.getSampleRate( stream );
    const int32_t got = a.getFormat( stream );
    const int32_t perf = a.getPerformanceMode( stream );

    // 位深按回来的格式反推：只用于让人看懂"实际开出来的是几位"
    int gotBits = bits;
    if( got == kFormatI16 )
    {
        gotBits = 16;
    }
    else if( got == kFormatI24Packed )
    {
        gotBits = 24;
    }
    else if( got == kFormatFloat || got == kFormatI32 )
    {
        gotBits = 32;
    }

    a.close( stream );
    a.deleteBuilder( builder );

    char buf[ 96 ];
    snprintf( buf, sizeof( buf ), "%s/%d/%d/%s",
             mode == kSharingExclusive ? "EXCLUSIVE" : "SHARED",
             static_cast< int >( rate ),
             gotBits,
             perf == kPerfLowLatency ? "LOW_LATENCY" : "NONE" );
    return( env->NewStringUTF( buf ));
}
