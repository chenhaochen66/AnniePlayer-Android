// ══════════════════════════════════════════════════════════════════════════════════════
// Oboe 播放内核（`AAUDIO_BACKEND = OBOE` 时使用）
// ══════════════════════════════════════════════════════════════════════════════════════
//
// ## 它替换的是什么
//
// `native-aaudio-probe.cpp` 的播放段是**手写推模式**：Kotlin 侧一个写线程不停
// `AAudioStream_write`，写不下就 `sleep(2ms)` 重试，靠 1.5s 判断"设备是否还在吃数据"。
// 这里是 **Oboe 回调拉模式**：HAL 每要一次数据就调 `onAudioReady`，我们从环形缓冲里
// 拷过去，不够就填静音并记一次 underrun。
//
// **契约层完全不变**：JNI 函数签名与 raw 后端一字不差，`AaudioSink` 一行都不用改。
// 于是 `getFramesRead`（只统计"真数据"帧）仍然驱动位置模型、`written > played` 仍然
// 正确地表达"设备里还有没播完的帧"。
//
// ## 为什么"静音不算帧"
//
// 暂停时 Kotlin 侧只是停写（不 `requestPause`，那在本机 HAL 上会卡在 PAUSING 不动）。
// 回调继续被调用，但环形缓冲空 → 填静音。如果静音也算进 `framesRead`，进度条会在
// 暂停期间继续跑，位置模型立刻失真。所以**只有真正从环形缓冲取出的帧才累加** ——
// 这样"暂停 = 位置冻结"是免费的。
//
// ## 线程模型
//
// * 生产者：Kotlin 侧 `annie-aaudio-write` 线程（唯一写者）
// * 消费者：Oboe 回调线程（实时优先级，唯一读者）
// * `flush/close` 由播放线程调用，且**调用前写线程已被 join**（`AaudioSink.closeAaudio()`
//   的顺序保证），所以环形缓冲始终是严格的单生产者单消费者。
//
// 回调里**绝不能**做：分配内存、加锁、JNI 调用、日志。整段只有 memcpy/memset 与原子加法。
//
// ## 不期待的收益
//
// * 独占成功率：同一个 HAL profile，换封装不会让它更"独占"；
// * 硬件时钟：本机 `AAudioStream_getTimestamp` 100% 失败，Oboe 的 `getTimestamp`
//   在下面同样原样透传（失败就返回 false，让 Kotlin 侧回落到 `getFramesRead`）；
// * 采样率/格式：仍要回读校验（HAL 可以给独占流却换掉规格，那就是"独占着重采样"）。
// ══════════════════════════════════════════════════════════════════════════════════════

#include <oboe/Oboe.h>

#include <android/api-level.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <memory>
#include <vector>

// 环形缓冲与 USB 独占共用一份（语义必须完全一致，见 `pcm-ring.h` 顶部）
#include "pcm-ring.h"

namespace
{

thread_local int g_lastError = 0;

/**
 * 字节口径的 SPSC 环形缓冲。
 *
 * 位置用**单调递增的字节偏移**（不取模），只在拷贝时换算成数组下标 ——
 * 这样 `容量 - (写位置 - 读位置)` 天然正确，不需要处理 wrap 的边界判断
 * （手写 wrap 判断是这类代码最常见的越界来源）。
 */
using annie::ByteRing;

/** 一条 Oboe 流 + 它自己的环形缓冲。句柄就是它的指针。 */
class OboeStream : public oboe::AudioStreamDataCallback,
                   public oboe::AudioStreamErrorCallback
{
public:
    // ── 回调线程 ──────────────────────────────────────────────────────────────

    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* /* stream */, void* audioData, int32_t numFrames ) override
    {
        if( audioData == nullptr || numFrames <= 0 )
        {
            return( oboe::DataCallbackResult::Continue );
        }
        const size_t bytes = static_cast< size_t >( numFrames ) * frameBytes;
        // 暂停 = 只出静音，**不从环形缓冲取数据**：位置因此冻结，恢复后从原位接着播
        if( paused.load( std::memory_order_acquire ))
        {
            std::memset( audioData, 0, bytes );
            return( oboe::DataCallbackResult::Continue );
        }
        const size_t got = ring.read( static_cast< uint8_t* >( audioData ), static_cast< size_t >( numFrames ));
        if( got < static_cast< size_t >( numFrames ))
        {
            // 欠载：剩余填静音。**不计入 framesRead** —— 静音不是媒体时间。
            std::memset(
                static_cast< uint8_t* >( audioData ) + got * frameBytes, 0,
                ( static_cast< size_t >( numFrames ) - got ) * frameBytes );
            underruns.fetch_add( 1, std::memory_order_relaxed );
        }
        if( got > 0 )
        {
            framesRead.fetch_add( static_cast< int64_t >( got ), std::memory_order_relaxed );
            // 增益在消费侧施加：环里存未衰减的原始样本，换音量只剩一个回调周期的延迟
            const size_t channels = subslotBytes > 0 ? frameBytes / subslotBytes : 1;
            annie::pcmApplyGain(
                static_cast< uint8_t* >( audioData ), got * channels, subslotBytes,
                gainQ16.load( std::memory_order_acquire ), ditherRng );
        }
        return( oboe::DataCallbackResult::Continue );
    }

    /**
     * 设备被拔出 / 被别的应用抢走（异步，非回调线程）。
     *
     * 只置标志并把错误码留给 `lastError()`：**不在这里关流、不在这里通知上层**。
     * 关流必须由播放线程做（否则就是那个 use-after-free 崩溃栈）；通知上层靠
     * `nativeWrite` 返回负数，写线程按"写失败"路径上报 —— 与 raw 后端同一条降级通道。
     */
    void onErrorAfterClose( oboe::AudioStream* /* stream */, oboe::Result error ) override
    {
        g_lastError = static_cast< int >( error );
        disconnected.store( true, std::memory_order_release );
    }

    // ── 状态（写线程 / 播放线程读）─────────────────────────────────────────────

    std::shared_ptr< oboe::AudioStream > stream;
    ByteRing ring;
    std::atomic< bool > paused{ false };
    /** 每个样本的字节数（2 / 3 / 4）：`frameBytes / subslotBytes` 就是声道数 */
    size_t subslotBytes = 4;
    /** 软件增益（Q16）与它的抖动状态；都只在回调线程里读/推进 */
    std::atomic< int32_t > gainQ16{ annie::kGainUnity };
    uint32_t ditherRng = 0x9E3779B9u;
    std::atomic< bool > disconnected{ false };
    std::atomic< int64_t > framesRead{ 0 };
    std::atomic< int64_t > framesWritten{ 0 };
    std::atomic< int64_t > underruns{ 0 };
    size_t frameBytes = 4;
    int32_t sampleRate = 0;
};

/** `jjlong` ←→ `OboeStream*` */
OboeStream* asStream( jlong handle )
{
    return( reinterpret_cast< OboeStream* >( handle ));
}

/** `aaudio_format_t` 常量（与 raw 后端、与 Kotlin 侧 `aaudioFormatOf` 必须一字不差） */
constexpr int kFormatI16 = 1;
constexpr int kFormatFloat = 2;
constexpr int kFormatI24Packed = 3;

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativeOpen(
        JNIEnv* /* env */, jclass /* 静态方法 */,
        jint sampleRate, jint depth, jint channels, jboolean exclusive )
{
    g_lastError = 0;
    if( sampleRate <= 0 || channels <= 0 )
    {
        g_lastError = -1;
        return( 0 );
    }

    oboe::AudioFormat format = oboe::AudioFormat::Float;
    int depthBytes = 4;
    if( depth == 16 )
    {
        format = oboe::AudioFormat::I16;
        depthBytes = 2;
    }
    else if( depth == 24 )
    {
        // Oboe 的 I24 就是 AAudio 的 PCM_I24_PACKED（3 字节紧密排列）
        format = oboe::AudioFormat::I24;
        depthBytes = 3;
    }

    // 与 raw 后端同一个数字：**必须显式给缓冲容量**，否则 HAL 会挑一个巨大的默认值
    // （本机实测灌到 1,110,016 帧 ≈ 25 秒后 write 永久 WOULD_BLOCK）。
    int32_t capacityFrames = sampleRate / 10;
    if( capacityFrames < 512 )
    {
        capacityFrames = 512;
    }

    auto* created = new OboeStream();
    created->frameBytes = static_cast< size_t >( depthBytes ) * static_cast< size_t >( channels );
    created->subslotBytes = static_cast< size_t >( depthBytes );
    created->sampleRate = sampleRate;
    // 环形缓冲给设备缓冲的 4 倍，且不少于 250ms —— 上游一次 `handleBuffer` 可能送来
    // 一大块（几十 KB），太小会让写线程频繁返回"满了"，白白增加 CPU 唤醒。
    size_t ringBytes = static_cast< size_t >( capacityFrames ) * 4 * created->frameBytes;
    const size_t ringFloor = static_cast< size_t >( sampleRate ) / 4 * created->frameBytes;
    created->ring.init( std::max( ringBytes, ringFloor ), created->frameBytes );

    oboe::AudioStreamBuilder builder;
    // ⚠️ **必须显式要 AAudio**，这是"OBOE 内核下独占不生效"的唯一原因。
    //
    // Oboe 的默认后端是**编译期**决定的（源码里 `#if __ANDROID_API__ >= 27` → AAudio，
    // 否则 OpenSLES）。我们链接的 `liboboe.so` 预编译产物是按 **api 21** 编的
    // （见 `thirdparty/oboe/README.md` 的 `abi.json`），于是 `AudioApi::Unspecified`
    // 会安静地落到 **OpenSL ES**。而 OpenSL 根本没有 EXCLUSIVE 概念 ——
    // `setSharingMode(Exclusive)` 被直接忽略，`getSharingMode()` 永远回 SHARED，
    // 表现就是"内核是 OBOE，但 AAudio 独占从来不会生效"。
    //
    // 对照：RAW 后端直接 dlopen `libaaudio.so` 调 C API，不存在这层默认值，
    // 所以切回 RAW 独占立刻正常 —— 这正是实测到的差异。
    //
    // API 26 才有 AAudio（24/25 上独占那一级本来就不成立），所以按**运行期** API 判断，
    // 不按编译期：老机器保留 Unspecified 让它走 OpenSL（能播，只是不独占）。
    if( android_get_device_api_level() >= 26 )
    {
        builder.setAudioApi( oboe::AudioApi::AAudio );
    }
    builder.setDirection( oboe::Direction::Output )
        ->setSampleRate( sampleRate )
        ->setChannelCount( static_cast< int32_t >( channels ))
        ->setFormat( format )
        ->setSharingMode( exclusive == JNI_TRUE ? oboe::SharingMode::Exclusive : oboe::SharingMode::Shared )
        ->setPerformanceMode( oboe::PerformanceMode::LowLatency )
        ->setBufferCapacityInFrames( capacityFrames )
        // 数据回调不要缓冲：我们自己在环形缓冲里管节奏（`framesPerDataCallback = 0`
        // 表示"跟 burst 一样大"，这是低延迟 + 足够安全余量的默认选择）。
        ->setFramesPerDataCallback( 0 )
        ->setDataCallback( created )
        ->setErrorCallback( created );

    // ⚠️ 不指定 `setAudioApi`：让 Oboe 自己挑 —— API ≥ 26 走 AAudio，
    // API 24/25 自动退到 OpenSL ES。硬钉 AAudio 会让 minSdk 24 的机器直接开不出来。
    //
    // 用"出参"这个重载：1.11.0 的头文件里**没有**无参的 `ResultWithValue openStream()`。
    std::shared_ptr< oboe::AudioStream > stream;
    const oboe::Result opened = builder.openStream( stream );
    if( opened != oboe::Result::OK || !stream )
    {
        g_lastError = static_cast< int >( opened );
        delete created;
        return( 0 );
    }
    created->stream = std::move( stream );

    // open 之后才知道 burst 有多长，这时才能把**实际**缓冲深度压到目标值。
    // 压不下去也没关系：调用方会回读 `bufferCapacity/bufferSize` 如实上报。
    const int32_t burst = created->stream->getFramesPerBurst();
    int32_t want = capacityFrames;
    if( burst > 0 && want < burst * 2 )
    {
        want = burst * 2;
    }
    created->stream->setBufferSizeInFrames( want );

    const oboe::Result started = created->stream->requestStart();
    if( started != oboe::Result::OK )
    {
        g_lastError = static_cast< int >( started );
        created->stream->close();
        created->stream.reset();
        delete created;
        return( 0 );
    }

    return( reinterpret_cast< jlong >( created ));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativeLastError(
        JNIEnv* /* env */, jclass /* 静态方法 */ )
{
    return( g_lastError );
}

/**
 * 写入 PCM。返回**写入的帧数**；0 = 环形缓冲满了（调用方稍后重试同一位置）；
 * 负数 = 流已断开/无效（调用方走 `onEngineFailure`）。
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativeWrite(
        JNIEnv* env, jclass /* 静态方法 */, jlong handle, jbyteArray data,
        jint byteOffset, jint byteCount, jint frameBytes )
{
    OboeStream* s = asStream( handle );
    if( s == nullptr || frameBytes <= 0 )
    {
        return( -1 );
    }
    if( s->disconnected.load( std::memory_order_acquire ))
    {
        return( -1 );
    }
    if( byteCount <= 0 || data == nullptr )
    {
        return( 0 );
    }
    const int32_t frames = byteCount / frameBytes;
    if( frames <= 0 )
    {
        return( 0 );
    }

    jbyte* raw = env->GetByteArrayElements( data, nullptr );
    if( raw == nullptr )
    {
        return( -2 );
    }
    const size_t written = s->ring.write(
        reinterpret_cast< const uint8_t* >( raw ) + byteOffset, static_cast< size_t >( frames ));
    env->ReleaseByteArrayElements( data, raw, JNI_ABORT );

    if( written > 0 )
    {
        s->framesWritten.fetch_add( static_cast< int64_t >( written ), std::memory_order_relaxed );
    }
    return( static_cast< jint >( written ));
}

/**
 * 设置软件增益（Q16，65536 = 1.0）。
 *
 * 只写 atomic，`onAudioReady` 每次回调读一次 —— 所以换音量在下一个回调周期生效，
 * 不受环形缓冲里已提交数据的影响（在写侧乘的话要等 250 ms 才听得到）。
 */
extern "C" JNIEXPORT void JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativeSetGain(
        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle, jint gainQ16 )
{
    if( OboeStream* s = asStream( handle ); s != nullptr )
    {
        const int32_t clamped = gainQ16 < 0 ? 0
                                : ( gainQ16 > annie::kGainUnity ? annie::kGainUnity : gainQ16 );
        s->gainQ16.store( clamped, std::memory_order_release );
    }
}

/** 暂停 = 回调改出静音、**不从环形缓冲取数据**（位置随之冻结）；流本身保持 STARTED */
extern "C" JNIEXPORT void JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativePause(
        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle )
{
    OboeStream* s = asStream( handle );
    if( s != nullptr )
    {
        s->paused.store( true, std::memory_order_release );
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativeResume(
        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle )
{
    OboeStream* s = asStream( handle );
    if( s == nullptr )
    {
        return;
    }
    s->paused.store( false, std::memory_order_release );
    if( s->stream )
    {
        // 已经从 STARTING/STARTED 再 requestStart 会返回 -895(INVALID_STATE)，
        // 无害但会污染日志（实测每首歌两条）。这里先看状态。
        const oboe::StreamState state = s->stream->getState();
        if( state != oboe::StreamState::Started && state != oboe::StreamState::Starting )
        {
            s->stream->requestStart();
        }
    }
}

/**
 * 实际用的后端：1 = AAudio，2 = OpenSL ES，0 = 拿不到。
 *
 * 必须能读出来：独占成不成立完全取决于它 —— OpenSL 没有 EXCLUSIVE 概念，
 * 拿到 SHARED 是必然结果而不是设备拒绝。以前这条信息只在代码注释里，
 * 出问题时只能干瞪眼。
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativeAudioApi(
        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle )
{
    OboeStream* s = asStream( handle );
    if( s == nullptr || !s->stream )
    {
        return( 0 );
    }
    return( s->stream->getAudioApi() == oboe::AudioApi::AAudio ? 1 : 2 );
}

// ── 独占能力探测（用来分辨"为什么 Oboe 拿不到 EXCLUSIVE"）─────────────────────────────
//
// 背景：同一台机器上，RAW 后端（直接调 AAudio C API、推模式）能拿到 EXCLUSIVE，
// Oboe 后端（回调拉模式）只拿到 SHARED。两边请求的参数看起来一模一样，差异只剩
// "有没有设 data callback"和"有没有设 bufferCapacity"。
//
// 与其猜，不如把三个组合各开一条流、回读 `getSharingMode()`，一次把答案测出来：
//   [0] callback + capacity   ← 播放路径当前用的组合
//   [1] 无 callback + capacity
//   [2] 无 callback + 不设 capacity
// 返回值：1 = EXCLUSIVE，0 = SHARED，<= -100 = 开流失败（-100 - result）。
//
// 只 open 不 requestStart：不出声、不占用设备超过一瞬，探测完立刻 close。

namespace
{

/** 探测专用：回调永远不会被调（不 start），存在只是为了满足 "callback 模式" */
class SilentCallback : public oboe::AudioStreamDataCallback
{
public:
    oboe::DataCallbackResult onAudioReady( oboe::AudioStream* /* stream */, void* /* audioData */,
                                           int32_t /* numFrames */ ) override
    {
        return( oboe::DataCallbackResult::Continue );
    }
};

int32_t probeSharingOnce( int32_t sampleRate, int depth, int channels,
                          bool withCallback, bool withCapacity,
                          oboe::PerformanceMode performanceMode )
{
    oboe::AudioFormat format = oboe::AudioFormat::Float;
    if( depth == 16 )
    {
        format = oboe::AudioFormat::I16;
    }
    else if( depth == 24 )
    {
        format = oboe::AudioFormat::I24;
    }

    int32_t capacityFrames = sampleRate / 10;
    if( capacityFrames < 512 )
    {
        capacityFrames = 512;
    }

    SilentCallback* callback = withCallback ? new SilentCallback() : nullptr;

    oboe::AudioStreamBuilder builder;
    if( android_get_device_api_level() >= 26 )
    {
        builder.setAudioApi( oboe::AudioApi::AAudio );
    }
    builder.setDirection( oboe::Direction::Output )
        ->setSampleRate( sampleRate )
        ->setChannelCount( static_cast< int32_t >( channels ))
        ->setFormat( format )
        ->setSharingMode( oboe::SharingMode::Exclusive )
        ->setPerformanceMode( performanceMode );
    if( withCapacity )
    {
        builder.setBufferCapacityInFrames( capacityFrames );
    }
    if( withCallback )
    {
        builder.setDataCallback( callback );
    }

    std::shared_ptr< oboe::AudioStream > stream;
    const oboe::Result opened = builder.openStream( stream );
    int32_t result;
    if( opened != oboe::Result::OK || !stream )
    {
        result = -100 - static_cast< int32_t >( opened );
    }
    else
    {
        // 把"实际走的是哪个后端"编进返回值的高位：
        //   +0  = AAudio、+10 = OpenSL ES
        // 这一步是为了排除"Oboe 悄悄走了 OpenSL 所以必然共享"这种误判 ——
        // OpenSL 没有 EXCLUSIVE 概念，共享是它的必然结果，与 HAL 无关。
        const bool onAaudio = stream->getAudioApi() == oboe::AudioApi::AAudio;
        result = ( onAaudio ? 0 : 10 ) +
                 ( stream->getSharingMode() == oboe::SharingMode::Exclusive ? 1 : 0 );
        stream->close();
        stream.reset();
    }
    delete callback;
    return( result );
}

} // namespace

extern "C" JNIEXPORT jintArray JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativeProbeSharingMode(
        JNIEnv* env, jclass /* 静态方法 */, jint sampleRate, jint depth, jint channels )
{
    namespace ob = oboe;
    jint values[ 5 ];
    // 前三组：低延迟模式下的参数对比（callback / capacity）
    values[ 0 ] = probeSharingOnce( sampleRate, depth, channels, true, true, ob::PerformanceMode::LowLatency );
    values[ 1 ] = probeSharingOnce( sampleRate, depth, channels, false, true, ob::PerformanceMode::LowLatency );
    values[ 2 ] = probeSharingOnce( sampleRate, depth, channels, false, false, ob::PerformanceMode::LowLatency );
    // 后两组：**关键假设** —— MIUI 那条 `forbiding ull track route` 剥的是 ULL 路由标志，
    // 而 `LOW_LATENCY` 就是 ULL 的触发器。音乐播放不需要低延迟，只需要"不转换"，
    // 所以如果 NONE 能绕过剥离逻辑拿到 EXCLUSIVE，这一级就成立。
    values[ 3 ] = probeSharingOnce( sampleRate, depth, channels, false, false, ob::PerformanceMode::None );
    values[ 4 ] = probeSharingOnce( sampleRate, depth, channels, true, false, ob::PerformanceMode::None );
    jintArray out = env->NewIntArray( 5 );
    if( out != nullptr )
    {
        env->SetIntArrayRegion( out, 0, 5, values );
    }
    return( out );
}

/**
 * 丢弃未播数据。
 *
 * 注意：这只清**我方环形缓冲**，HAL 手里已经拿走的样本来不及撤回 —— 所以
 * `AaudioSink.flush()` 走的仍然是"关流 + 重开"（与 raw 后端一致的行为）。
 * 这个函数留着是为了接口完整，不承担 seek 的正确性。
 */
extern "C" JNIEXPORT void JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativeFlush(
        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle )
{
    OboeStream* s = asStream( handle );
    if( s == nullptr )
    {
        return;
    }
    s->ring.clear();
    s->framesRead.store( 0, std::memory_order_relaxed );
    s->framesWritten.store( 0, std::memory_order_relaxed );
}

extern "C" JNIEXPORT void JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativeClose(
        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle )
{
    OboeStream* s = asStream( handle );
    if( s == nullptr )
    {
        return;
    }
    if( s->stream )
    {
        s->stream->requestStop();
        s->stream->close();
        s->stream.reset();
    }
    delete s;
}

/** 硬件时钟：`out[0]` = 已播到的帧位置，`out[1]` = 对应 nanoTime；false = 拿不到 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativeTimestamp(
        JNIEnv* env, jclass /* 静态方法 */, jlong handle, jlongArray out )
{
    OboeStream* s = asStream( handle );
    if( s == nullptr || !s->stream || out == nullptr )
    {
        return( JNI_FALSE );
    }
    int64_t framePosition = 0;
    int64_t timeNanos = 0;
    const oboe::Result r = s->stream->getTimestamp( CLOCK_MONOTONIC, &framePosition, &timeNanos );
    if( r != oboe::Result::OK )
    {
        return( JNI_FALSE );
    }
    const jlong values[ 2 ] = { static_cast< jlong >( framePosition ), static_cast< jlong >( timeNanos ) };
    env->SetLongArrayRegion( out, 0, 2, values );
    return( JNI_TRUE );
}

/** 已写进我方的帧数（不含被丢弃的部分）；-1 = 句柄无效 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativeFramesWritten(
        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle )
{
    OboeStream* s = asStream( handle );
    if( s == nullptr )
    {
        return( -1 );
    }
    return( static_cast< jlong >( s->framesWritten.load( std::memory_order_relaxed )));
}

/** 设备真正取走的帧数（静音填充不算）—— 位置模型的主要来源之一 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativeFramesRead(
        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle )
{
    OboeStream* s = asStream( handle );
    if( s == nullptr )
    {
        return( 0 );
    }
    return( static_cast< jlong >( s->framesRead.load( std::memory_order_relaxed )));
}

/** 流的真实共享模式：1 = EXCLUSIVE，0 = SHARED，-1 = 句柄无效（与 raw 后端同约定） */
extern "C" JNIEXPORT jint JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativeSharingMode(
        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle )
{
    OboeStream* s = asStream( handle );
    if( s == nullptr || !s->stream )
    {
        return( -1 );
    }
    return( s->stream->getSharingMode() == oboe::SharingMode::Exclusive ? 1 : 0 );
}

/** 流的**真实**采样率；0 = 拿不到。与请求值不等 = 中间被重采样了 */
extern "C" JNIEXPORT jint JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativeSampleRate(
        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle )
{
    OboeStream* s = asStream( handle );
    if( s == nullptr || !s->stream )
    {
        return( 0 );
    }
    return( s->stream->getSampleRate() );
}

/** 流的**真实**格式（`aaudio_format_t` 口径）；0 = 拿不到 */
extern "C" JNIEXPORT jint JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativeFormat(
        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle )
{
    OboeStream* s = asStream( handle );
    if( s == nullptr || !s->stream )
    {
        return( 0 );
    }
    switch( s->stream->getFormat())
    {
        case oboe::AudioFormat::I16:
            return( kFormatI16 );
        case oboe::AudioFormat::Float:
            return( kFormatFloat );
        case oboe::AudioFormat::I24:
            return( kFormatI24Packed );
        default:
            return( 0 );
    }
}

/** 缓冲容量（帧）；0 = 拿不到。远大于请求值 = HAL 不接受小缓冲 */
extern "C" JNIEXPORT jint JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativeBufferCapacity(
        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle )
{
    OboeStream* s = asStream( handle );
    if( s == nullptr || !s->stream )
    {
        return( 0 );
    }
    return( s->stream->getBufferCapacityInFrames() );
}

/** 当前生效的缓冲深度（帧）；0 = 拿不到 */
extern "C" JNIEXPORT jint JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativeBufferSize(
        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle )
{
    OboeStream* s = asStream( handle );
    if( s == nullptr || !s->stream )
    {
        return( 0 );
    }
    return( s->stream->getBufferSizeInFrames() );
}

/** 流状态：枚举值与 `aaudio_stream_state_t` 一致（1 OPEN … 12 DISCONNECTED） */
extern "C" JNIEXPORT jint JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativeStreamState(
        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle )
{
    OboeStream* s = asStream( handle );
    if( s == nullptr || !s->stream )
    {
        return( 0 );
    }
    return( static_cast< jint >( s->stream->getState()));
}

/** 欠载次数（静音填充的调用数）—— 只给"输出能力（实测）"面板用，不参与任何判定 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeOboeStream_nativeUnderrunCount(
        JNIEnv* /* env */, jclass /* 静态方法 */, jlong handle )
{
    OboeStream* s = asStream( handle );
    if( s == nullptr )
    {
        return( 0 );
    }
    return( static_cast< jlong >( s->underruns.load( std::memory_order_relaxed )));
}
