#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>

#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "libavcodec/avcodec.h"
#include "libavformat/avformat.h"
#include "libavutil/channel_layout.h"
#include "libavutil/frame.h"
#include "libavutil/log.h"
#include "libavutil/mathematics.h"
#include "libavutil/samplefmt.h"
#include "libswresample/swresample.h"

#define LOG_TAG "AnnieFfmpegDemux"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

/**
 * 本地全链路解封装 + 解码 + 重采样（avformat → avcodec → swresample）。
 *
 * 为什么需要它：APE / WavPack / TTA / TAK / DSD(DSF·DFF) 这些容器 Media3 **连解封装都不会**，
 * 只能让 ffmpeg 从文件头开始读。流媒体链路不需要它（容器 Media3 全认识，只需要换解码器），
 * 所以这个模块只服务本地文件 —— 与《播放方案》附录 B 的分层结论一致。
 *
 * 和 native-ffmpeg-decoder.cpp 一样走 dlopen + dlsym：不链接、不额外打包第二份 ffmpeg，
 * 直接用 ffmpeg-kit 自带的 libavformat / libavcodec / libswresample。
 *
 * 输出固定为**交错小端 16/24bit、立体声**（5.1 / 7.1 在这里下混成 2 声道）。
 * 采样率只有一个来源：调用方传进来的 [targetSampleRate]（= 设置层算出的唯一目标率）。
 * 传了就在 swresample 里**一次**转换到位，处理器链因此不再重复重采样；
 * 传 0（跟随设备关掉 / 蓝牙旁路）才保持源率，只把超过 192000 的源率按整数二分夹回合法档位。
 */

namespace {

constexpr int kMaxSourceRate = 192000;
constexpr int kOutChannels = 2;
constexpr int kMaxFramesPerConvert = 16384;

// ── 动态符号表 ──
using PFN_open_input = int (*)(AVFormatContext **, const char *, const AVInputFormat *, AVDictionary **);
using PFN_find_stream_info = int (*)(AVFormatContext *, AVDictionary **);
using PFN_find_best_stream = int (*)(AVFormatContext *, enum AVMediaType, int, int, const AVCodec **, int);
using PFN_close_input = void (*)(AVFormatContext **);
using PFN_read_frame = int (*)(AVFormatContext *, AVPacket *);
using PFN_seek_file = int (*)(AVFormatContext *, int, int64_t, int64_t, int64_t, int);
using PFN_par_to_ctx = int (*)(AVCodecContext *, const AVCodecParameters *);

using PFN_find_decoder = const AVCodec *(*)(enum AVCodecID);
using PFN_alloc_context = AVCodecContext *(*)(const AVCodec *);
using PFN_open2 = int (*)(AVCodecContext *, const AVCodec *, AVDictionary **);
using PFN_send_packet = int (*)(AVCodecContext *, const AVPacket *);
using PFN_receive_frame = int (*)(AVCodecContext *, AVFrame *);
using PFN_flush = void (*)(AVCodecContext *);
using PFN_free_context = void (*)(AVCodecContext **);
using PFN_packet_alloc = AVPacket *(*)();
using PFN_packet_free = void (*)(AVPacket **);
using PFN_packet_unref = void (*)(AVPacket *);
using PFN_frame_alloc = AVFrame *(*)();
using PFN_frame_free = void (*)(AVFrame **);
using PFN_frame_unref = void (*)(AVFrame *);
using PFN_free = void (*)(void *);
using PFN_log_set_level = void (*)(int);
using PFN_rescale_q = int64_t (*)(int64_t, AVRational, AVRational);

using PFN_swr_alloc_set_opts2 = int (*)(SwrContext **, const AVChannelLayout *, enum AVSampleFormat, int,
                                        const AVChannelLayout *, enum AVSampleFormat, int, int, void *);
using PFN_swr_init = int (*)(SwrContext *);
using PFN_swr_convert = int (*)(SwrContext *, uint8_t *const *, int, const uint8_t *const *, int);
using PFN_swr_free = void (*)(SwrContext **);

struct Api {
    PFN_open_input open_input = nullptr;
    PFN_find_stream_info find_stream_info = nullptr;
    PFN_find_best_stream find_best_stream = nullptr;
    PFN_close_input close_input = nullptr;
    PFN_read_frame read_frame = nullptr;
    PFN_seek_file seek_file = nullptr;
    PFN_par_to_ctx par_to_ctx = nullptr;

    PFN_find_decoder find_decoder = nullptr;
    PFN_alloc_context alloc_context = nullptr;
    PFN_open2 open2 = nullptr;
    PFN_send_packet send_packet = nullptr;
    PFN_receive_frame receive_frame = nullptr;
    PFN_flush flush = nullptr;
    PFN_free_context free_context = nullptr;
    PFN_packet_alloc packet_alloc = nullptr;
    PFN_packet_free packet_free = nullptr;
    PFN_packet_unref packet_unref = nullptr;
    PFN_frame_alloc frame_alloc = nullptr;
    PFN_frame_free frame_free = nullptr;
    PFN_frame_unref frame_unref = nullptr;
    PFN_free free_mem = nullptr;
    PFN_log_set_level log_set_level = nullptr;
    PFN_rescale_q rescale_q = nullptr;

    PFN_swr_alloc_set_opts2 swr_alloc_set_opts2 = nullptr;
    PFN_swr_init swr_init = nullptr;
    PFN_swr_convert swr_convert = nullptr;
    PFN_swr_free swr_free = nullptr;
};

Api g_api;
std::once_flag g_once;
bool g_ready = false;

template <typename T>
void load(void *handle, const char *name, T &target) {
    if (handle != nullptr) target = reinterpret_cast<T>(dlsym(handle, name));
}

bool loadApi() {
    std::call_once(g_once, [] {
        void *avutil = dlopen("libavutil.so", RTLD_NOW | RTLD_LOCAL);
        void *avcodec = dlopen("libavcodec.so", RTLD_NOW | RTLD_LOCAL);
        void *avformat = dlopen("libavformat.so", RTLD_NOW | RTLD_LOCAL);
        void *swresample = dlopen("libswresample.so", RTLD_NOW | RTLD_LOCAL);
        if (avutil == nullptr || avcodec == nullptr || avformat == nullptr || swresample == nullptr) {
            LOGE("dlopen failed: %s", dlerror());
            return;
        }

        load(avformat, "avformat_open_input", g_api.open_input);
        load(avformat, "avformat_find_stream_info", g_api.find_stream_info);
        load(avformat, "av_find_best_stream", g_api.find_best_stream);
        load(avformat, "avformat_close_input", g_api.close_input);
        load(avformat, "av_read_frame", g_api.read_frame);
        load(avformat, "avformat_seek_file", g_api.seek_file);

        load(avcodec, "avcodec_find_decoder", g_api.find_decoder);
        load(avcodec, "avcodec_alloc_context3", g_api.alloc_context);
        load(avcodec, "avcodec_open2", g_api.open2);
        load(avcodec, "avcodec_send_packet", g_api.send_packet);
        load(avcodec, "avcodec_receive_frame", g_api.receive_frame);
        load(avcodec, "avcodec_flush_buffers", g_api.flush);
        load(avcodec, "avcodec_free_context", g_api.free_context);
        load(avcodec, "avcodec_parameters_to_context", g_api.par_to_ctx);
        load(avcodec, "av_packet_alloc", g_api.packet_alloc);
        load(avcodec, "av_packet_free", g_api.packet_free);
        load(avcodec, "av_packet_unref", g_api.packet_unref);

        load(avutil, "av_frame_alloc", g_api.frame_alloc);
        load(avutil, "av_frame_free", g_api.frame_free);
        load(avutil, "av_frame_unref", g_api.frame_unref);
        load(avutil, "av_free", g_api.free_mem);
        load(avutil, "av_log_set_level", g_api.log_set_level);
        load(avutil, "av_rescale_q", g_api.rescale_q);

        load(swresample, "swr_alloc_set_opts2", g_api.swr_alloc_set_opts2);
        load(swresample, "swr_init", g_api.swr_init);
        load(swresample, "swr_convert", g_api.swr_convert);
        load(swresample, "swr_free", g_api.swr_free);

        g_ready = g_api.open_input && g_api.find_stream_info && g_api.find_best_stream &&
                  g_api.close_input && g_api.read_frame && g_api.seek_file &&
                  g_api.find_decoder && g_api.alloc_context && g_api.open2 &&
                  g_api.send_packet && g_api.receive_frame && g_api.flush &&
                  g_api.free_context && g_api.par_to_ctx && g_api.packet_alloc &&
                  g_api.packet_free && g_api.packet_unref && g_api.frame_alloc &&
                  g_api.frame_free && g_api.frame_unref && g_api.rescale_q &&
                  g_api.swr_alloc_set_opts2 && g_api.swr_init && g_api.swr_convert &&
                  g_api.swr_free;

        if (g_ready && g_api.log_set_level) g_api.log_set_level(AV_LOG_ERROR);
        if (!g_ready) LOGE("ffmpeg demux symbols incomplete");
    });
    return g_ready;
}

struct DemuxState {
    AVFormatContext *fmt = nullptr;
    AVCodecContext *ctx = nullptr;
    AVPacket *packet = nullptr;
    AVFrame *frame = nullptr;
    SwrContext *swr = nullptr;
    int audioIndex = -1;
    int outRate = 44100;
    int outBits = 24;
    int64_t durationUs = 0;
    int64_t lastPtsUs = 0;
    /**
     * 时间轴由**累计输出帧数**推导，不问解码器要 pts。
     *
     * dsddec 的 AVFrame 不保证每个都带 `best_effort_timestamp`，一旦 `AV_NOPTS_VALUE`
     * 就只能照抄上一块 —— 时间轴先卡住、随后一次性跳过去（实测每 2 秒跳 209 ms，
     * `DefaultAudioSink` 因此判 timestamp 不连续并重置同步）。解码成连续 PCM 之后，
     * 时间轴本来就该由样本数唯一确定，这里改由自己累加。
     */
    int64_t emittedFrames = 0;
    /** 本次 seek 的起点（微秒），[emittedFrames] 相对它计数 */
    int64_t basePtsUs = 0;
    AVRational timeBase{1, 1000000};
    bool eof = false;
    /**
     * 只解封装、不解码（DSD 直出专用）。
     *
     * 打开时**不建解码器、不建 swr**，`read` 直接吐 `AVPacket` 的载荷，也就是原始
     * 1-bit 交错字节。DoP / Native 两条路都要这层原样数据 —— 任何一次解码都会
     * 把它变成 PCM，而 PCM 再也变不回 DSD。
     */
    bool rawDsd = false;
    /** 上一包没吐完的尾巴：packet 边界和调用方缓冲边界不一定对齐 */
    std::vector<uint8_t> pending;
    /**
     * raw DSD 的左右声道暂存。
     *
     * DSF / DSDIFF 的 data 是**每声道独立的 block**（ffmpeg 按 planar 解出来），
     * 而 DoP / Native 的帧结构是「左 N bit + 右 N bit」的**字节交错**，所以要先拆再并。
     *
     * **一个 packet 里已经包含全部声道**（`dsfdec.c` 把 header 的每声道块长乘上声道数
     * 当 `block_align`，`dsddec.c` 的 planar 分支按 `data + j * nb_samples` 取第 j 声道），
     * 所以拆声道必须在 **packet 内部**做。跨 packet 配对等于把相隔一个块（11.6 ms）的
     * 数据当成左右声道 —— 听感正是「有声音但满幅噪音」。
     */
    std::vector<uint8_t> rawLeft;
    std::vector<uint8_t> rawRight;
    /** 源数据是 planar（每声道一段连续 block）；false = 已经交错（DFF） */
    bool rawPlanar = true;
    /** 源声道数；每 packet 内的 block 数 */
    int rawChannels = 2;
    /** 本次输出第一个立体声帧的 pts（微秒） */
    int64_t rawPtsUs = 0;
    /** 1 bit 采样率；用于按已输出字节数推进 pts（每字节 = 8 个 1-bit 采样） */
    int rawBitRate = 0;
    /**
     * 源数据是否 **LSB-first**（DSF），输出前要逐字节位反转。
     *
     * DAC 侧（DoP 的 16 位数据字段、native 的 DSD_U32）都要求字节内 **bit7 是最早的样本**，
     * 而 DSF 规范规定 bits_per_sample == 1 时按 LSB-first 存储（`dsfdec.c` 对应
     * `AV_CODEC_ID_DSD_LSBF_PLANAR`），DFF 则是 MSB-first。
     * 拿反了不会报错：整段变成「低频轮廓还在、高频全错」的沙沙声。
     */
    bool rawBitReverse = false;
    std::vector<uint8_t> swrOut;
    std::vector<uint8_t> pcm;
    std::mutex lock;
};

/** 字节内位序反转（LSB-first ↔ MSB-first）。arm64 上会编成单条 `rbit`。 */
inline uint8_t reverseBits8(uint8_t v) {
    v = static_cast<uint8_t>(((v & 0xF0) >> 4) | ((v & 0x0F) << 4));
    v = static_cast<uint8_t>(((v & 0xCC) >> 2) | ((v & 0x33) << 2));
    return static_cast<uint8_t>(((v & 0xAA) >> 1) | ((v & 0x55) << 1));
}

/**
 * 把一个 packet 拆成左右两侧，追加到 `rawLeft` / `rawRight`。
 *
 * 同一次调用里的两个输出块来自**同一时间区间**——这是整个 raw 通路唯一的前提。
 * planar 时 packet 是 `[ch0][ch1][ch2]…` 各 `len/channels` 字节（见 `dsddec.c`：
 * `data + j * nb_samples`，stride 1）；非 planar 时是 `L0 R0 L1 R1 …`。
 * 单声道两侧填同一份，否则右侧永远空、整首歌一个字节都吐不出去。
 */
void splitChannels(DemuxState *state, const uint8_t *p, size_t len) {
    const size_t ch = state->rawChannels > 0 ? static_cast<size_t>(state->rawChannels) : 1;
    if (!state->rawPlanar) {
        if (ch <= 2) {
            for (size_t i = 0; i + ch <= len; i += ch) {
                state->rawLeft.push_back(p[i]);
                state->rawRight.push_back(ch == 2 ? p[i + 1] : p[i]);
            }
            return;
        }
        len -= len % ch;
    }
    const size_t per = len / ch;
    if (per == 0) return;
    state->rawLeft.insert(state->rawLeft.end(), p, p + per);
    state->rawRight.insert(
        state->rawRight.end(), p + (ch >= 2 ? per : 0), p + (ch >= 2 ? 2 * per : per)
    );
}

void writeSample(uint8_t *out, int index, int outBits, float value) {
    if (outBits == 24) {
        int32_t sample = static_cast<int32_t>(value * 8388607.0f);
        if (sample > 8388607) sample = 8388607;
        if (sample < -8388608) sample = -8388608;
        out[index * 3] = static_cast<uint8_t>(sample & 0xFF);
        out[index * 3 + 1] = static_cast<uint8_t>((sample >> 8) & 0xFF);
        out[index * 3 + 2] = static_cast<uint8_t>((sample >> 16) & 0xFF);
    } else {
        int32_t sample = static_cast<int32_t>(value * 32767.0f);
        if (sample > 32767) sample = 32767;
        if (sample < -32768) sample = -32768;
        out[index * 2] = static_cast<uint8_t>(sample & 0xFF);
        out[index * 2 + 1] = static_cast<uint8_t>((sample >> 8) & 0xFF);
    }
}

/** swr 输出（交错 S32）→ 交错 16/24bit 小端；原地转换（3 字节/样本 < 4 字节/样本，安全） */
int convertInterleaved(uint8_t *buffer, int frames, int outBits) {
    auto *samples = reinterpret_cast<const int32_t *>(buffer);
    const int total = frames * kOutChannels;
    for (int i = 0; i < total; ++i) {
        writeSample(buffer, i, outBits, static_cast<float>(samples[i]) * (1.0f / 2147483648.0f));
    }
    return total * (outBits == 24 ? 3 : 2);
}

void releaseState(DemuxState *state) {
    if (state == nullptr) return;
    if (state->swr != nullptr) g_api.swr_free(&state->swr);
    if (state->packet != nullptr) g_api.packet_free(&state->packet);
    if (state->frame != nullptr) g_api.frame_free(&state->frame);
    if (state->ctx != nullptr) g_api.free_context(&state->ctx);
    if (state->fmt != nullptr) g_api.close_input(&state->fmt);
    delete state;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_ffmpeg_NativeFfmpegDemux_nativeAvailable(
    JNIEnv *, jobject
) {
    return loadApi() ? JNI_TRUE : JNI_FALSE;
}

/**
 * 打开本地文件并建好解码 / 重采样链。
 *
 * @param targetSampleRate 输出目标采样率（设置层算出的唯一值）；0 = 保持源率（只夹到 ≤192k 的合法档位）
 * @param keepSourceRate **保持源率、不在这里重采样**（DSD 专用）
 * @param info 输出：`[0]=采样率 [1]=声道(恒 2) [2]=位深 [3]=时长ms`
 * @return 句柄；0 = 打不开
 *
 * ## 为什么需要 `keepSourceRate`（DSD 的抽取必须交给 r8brain）
 *
 * DSD 这条路以前是本文件一条龙：`dsddec` 解成 352800 Hz float，再由 `libswresample`
 * 降到 176400。但 swresample 用的是**默认参数** —— `filter_size=32`、`phase_shift=10`、
 * `cutoff=0.97`、线性相位、未设 dither。对 DSD 来说这是整条链最弱的一环，原因是
 * **DSD 的噪声整形把量化噪声整个推到 20 kHz 以上**（20k–100k 全是噪声）：
 *
 * - 32 抽头的抽取滤波器阻带约 −60 dB，那批超声噪声会**折回音频带**，
 *   这是 DSD 转 PCM 最经典的失败模式；
 * - 线性相位 ⇒ 每张鼓的长出一段**前置回声**，正是本应用花整轮排查才消掉的那层「沙」
 *   （见 `native-r8b-resampler.cpp` 的 `kTransBand`）。
 *
 * 所以 DSD 改成两级：**本文件只负责 1-bit → PCM 网格（352800 Hz，float，格式无损）**，
 * 抽取由下游 `PcmResamplerAudioProcessor` 的 r8brain 做（2% 过渡带、206 dB、最小相位）。
 *
 * 352800→176400 命中 `CDSPResampler` 构造里的 `CommonRatios` 表 `{num=1, den=2}`，
 * 走的是**单级 2:1 多相抽取**（`CDSPFIRFilterCache::getLPFilter(0.5, 2.0, …)`，
 * 与 44100→48000 用的主滤波器是**同一套参数**，实测 `flt_len = 1621`），
 * 不是 `CDSPHBDownsampler` 那种半带级联 —— 后者只在 4:1 以上的降采样分支里出现。
 *
 * 判据在 Kotlin 侧（`FfmpegLocalRoute` / `dsdFullChainRate`），本文件只认这个布尔值 ——
 * 不在 native 里猜"这是不是 DSD"。
 */
JNIEXPORT jlong JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_ffmpeg_NativeFfmpegDemux_open(
    JNIEnv *env,
    jobject,
    jstring path,
    jint targetSampleRate,
    jint outBits,
    jintArray info,
    jboolean keepSourceRate
) {
    if (!loadApi() || path == nullptr) return 0;

    const char *chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) return 0;
    std::string filePath(chars);
    env->ReleaseStringUTFChars(path, chars);

    auto *state = new DemuxState();
    state->outBits = outBits == 16 ? 16 : 24;

    if (g_api.open_input(&state->fmt, filePath.c_str(), nullptr, nullptr) < 0) {
        LOGE("avformat_open_input failed: %s", filePath.c_str());
        releaseState(state);
        return 0;
    }
    if (g_api.find_stream_info(state->fmt, nullptr) < 0) {
        LOGE("avformat_find_stream_info failed");
        releaseState(state);
        return 0;
    }

    const int idx = g_api.find_best_stream(state->fmt, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
    if (idx < 0) {
        LOGE("no audio stream");
        releaseState(state);
        return 0;
    }
    state->audioIndex = idx;

    AVStream *stream = state->fmt->streams[idx];
    state->timeBase = stream->time_base;

    const AVCodec *codec = g_api.find_decoder(stream->codecpar->codec_id);
    if (codec == nullptr) {
        LOGE("no decoder for codec_id=%d", static_cast<int>(stream->codecpar->codec_id));
        releaseState(state);
        return 0;
    }
    state->ctx = g_api.alloc_context(codec);
    if (state->ctx == nullptr || g_api.par_to_ctx(state->ctx, stream->codecpar) < 0) {
        LOGE("parameters_to_context failed");
        releaseState(state);
        return 0;
    }
    state->ctx->thread_count = 1;
    state->ctx->thread_type = 0;
    if (g_api.open2(state->ctx, codec, nullptr) < 0) {
        LOGE("avcodec_open2 failed (codec=%s)", codec->name);
        releaseState(state);
        return 0;
    }

    const int sourceRate = state->ctx->sample_rate > 0 ? state->ctx->sample_rate : 44100;
    // 采样率只有一个来源：targetSampleRate（设置层的目标率），或 keepSourceRate（DSD）。
    // 给了 target 就直接对齐到它 —— 一次 swresample 完成，下游 r8brain 因为 srcRate == target
    // 会自动旁路，全程只有一次重采样。
    // 没给（蓝牙旁路 / 关掉无 SRC 输出）才保持源率，只把超过 AudioFormat 合法上限
    // （192000）的源率整数二分夹回去：768k → 192000。
    //
    // **keepSourceRate 连这个夹取都要跳过**：DSD64 的 352800 不是"太高的非法档位"，
    // 它是 dsddec 的中间格式（2822400 / 8），必须原样交给下游 r8brain 做 2:1 抽取。
    int rate = sourceRate;
    if (keepSourceRate == JNI_TRUE) {
        rate = sourceRate;
    } else if (targetSampleRate > 0) {
        rate = targetSampleRate;
    } else {
        while (rate > kMaxSourceRate) rate /= 2;
    }
    state->outRate = rate;

    AVChannelLayout outLayout = AV_CHANNEL_LAYOUT_STEREO;
    AVChannelLayout monoLayout = AV_CHANNEL_LAYOUT_MONO;
    const AVChannelLayout *inLayout =
        state->ctx->ch_layout.nb_channels > 0 ? &state->ctx->ch_layout : &monoLayout;

    if (g_api.swr_alloc_set_opts2(
            &state->swr,
            &outLayout,
            AV_SAMPLE_FMT_S32,
            rate,
            inLayout,
            state->ctx->sample_fmt,
            sourceRate,
            0,
            nullptr
        ) < 0 || state->swr == nullptr || g_api.swr_init(state->swr) < 0) {
        LOGE("swr init failed (in=%d out=%d fmt=%d)", sourceRate, rate, state->ctx->sample_fmt);
        releaseState(state);
        return 0;
    }

    state->packet = g_api.packet_alloc();
    state->frame = g_api.frame_alloc();
    if (state->packet == nullptr || state->frame == nullptr) {
        releaseState(state);
        return 0;
    }

    state->timeBase = stream->time_base;
    int64_t durationUs = state->fmt->duration;
    if (durationUs <= 0 && stream->duration > 0) {
        durationUs = g_api.rescale_q(stream->duration, stream->time_base, AV_TIME_BASE_Q);
    }
    state->durationUs = durationUs > 0 ? durationUs : 0;
    state->swrOut.resize(static_cast<size_t>(kMaxFramesPerConvert) * kOutChannels * 4);
    state->pcm.reserve(static_cast<size_t>(kMaxFramesPerConvert) * kOutChannels * 3);

    if (info != nullptr && env->GetArrayLength(info) >= 4) {
        jint values[4] = {
            rate,
            kOutChannels,
            state->outBits,
            static_cast<jint>(state->durationUs / 1000),
        };
        env->SetIntArrayRegion(info, 0, 4, values);
    }

    LOGI("opened rate=%d srcRate=%d bits=%d durMs=%lld file=%s",
         rate, sourceRate, state->outBits,
         static_cast<long long>(state->durationUs / 1000), filePath.c_str());
    return reinterpret_cast<jlong>(state);
}

/**
 * 打开成 **raw DSD**：只解封装，不解码。
 *
 * 与 [open] 的区别只有两处：不建解码器 / 不建 swr，`read` 直接吐 1-bit 载荷。
 * 之所以是独立入口而不是给 [open] 加一个参数 —— JNI 签名一旦两边产物不一致，
 * native 会按错位读参数直接崩，这个坑踩过一次。多一个符号没有代价。
 *
 * `info` 回填：`[0]` = **1 bit 采样率**（2822400 / 5644800 …，不是 PCM 网格），
 * `[1]` = 声道数，`[2]` = 0（位深对 1-bit 流没有意义），`[3]` = 时长（毫秒）。
 */
JNIEXPORT jlong JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_ffmpeg_NativeFfmpegDemux_openRaw(
    JNIEnv *env,
    jobject,
    jstring path,
    jintArray info
) {
    if (!loadApi() || path == nullptr) return 0;

    const char *chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) return 0;
    std::string filePath(chars);
    env->ReleaseStringUTFChars(path, chars);

    auto *state = new DemuxState();
    state->rawDsd = true;

    if (g_api.open_input(&state->fmt, filePath.c_str(), nullptr, nullptr) < 0) {
        LOGE("openRaw: avformat_open_input failed: %s", filePath.c_str());
        releaseState(state);
        return 0;
    }
    if (g_api.find_stream_info(state->fmt, nullptr) < 0) {
        LOGE("openRaw: find_stream_info failed");
        releaseState(state);
        return 0;
    }
    const int idx = g_api.find_best_stream(state->fmt, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
    if (idx < 0) {
        LOGE("openRaw: no audio stream");
        releaseState(state);
        return 0;
    }
    state->audioIndex = idx;
    AVStream *stream = state->fmt->streams[idx];
    state->timeBase = stream->time_base;
    state->packet = g_api.packet_alloc();
    if (state->packet == nullptr) {
        releaseState(state);
        return 0;
    }

    // ffmpeg 的 dsf/dff demuxer 报的 `sample_rate` 是 **bitRate / 8**（PCM 网格：
    // DSD64 → 352800），**不是** 1-bit 率。实测确认：
    //   `ffprobe -show_entries stream=sample_rate` → 352800，codec_name=dsd_lsbf_planar。
    // 直接拿它当 1-bit 率会让下面的 `wordRate` 算小 8 倍（352800/32 = 11025 而不是
    // 2822400/32 = 88200），设备按 1/8 的速度消费数据流 —— 听感就是满幅噪声。
    const int gridRate = stream->codecpar->sample_rate > 0 ? stream->codecpar->sample_rate : 0;
    const int bitRate = gridRate * 8;
    const int channels = stream->codecpar->ch_layout.nb_channels > 0
        ? stream->codecpar->ch_layout.nb_channels
        : 2;

    // DSF（bits_per_sample == 1）是 LSB-first，DFF 是 MSB-first。ffmpeg 的 dsfdec 已经
    // 把这件事编码进 codec_id，直接照抄即可，不要自己嗅探文件头。
    const AVCodecID codecId = stream->codecpar->codec_id;
    state->rawBitReverse =
        (codecId == AV_CODEC_ID_DSD_LSBF || codecId == AV_CODEC_ID_DSD_LSBF_PLANAR);
    state->rawPlanar =
        (codecId == AV_CODEC_ID_DSD_LSBF_PLANAR || codecId == AV_CODEC_ID_DSD_MSBF_PLANAR);
    state->rawChannels = channels;

    int64_t durationUs = state->fmt->duration;
    if (durationUs <= 0 && stream->duration > 0) {
        durationUs = g_api.rescale_q(stream->duration, stream->time_base, AV_TIME_BASE_Q);
    }
    state->durationUs = durationUs > 0 ? durationUs : 0;
    state->rawPtsUs = 0;
    state->rawBitRate = bitRate;

    if (info != nullptr && env->GetArrayLength(info) >= 4) {
        jint values[4];
        values[0] = bitRate;
        values[1] = channels;
        values[2] = 0;
        values[3] = static_cast<jint>(state->durationUs / 1000);
        env->SetIntArrayRegion(info, 0, 4, values);
    }

    LOGI("openRaw bitRate=%d ch=%d rev=%d durMs=%lld file=%s",
         bitRate, channels, state->rawBitReverse ? 1 : 0,
         static_cast<long long>(state->durationUs / 1000), filePath.c_str());
    return reinterpret_cast<jlong>(state);
}

/**
 * 读一段 PCM。
 *
 * @param out  目标缓冲（容量即上限）
 * @param meta 输出：`[0]` = 这段第一个样本的 pts（微秒）
 * @return 写入字节数；0 = 结束；<0 = 错误
 */
JNIEXPORT jint JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_ffmpeg_NativeFfmpegDemux_read(
    JNIEnv *env,
    jobject,
    jlong handle,
    jbyteArray out,
    jlongArray meta
) {
    auto *state = reinterpret_cast<DemuxState *>(handle);
    if (state == nullptr || !loadApi()) return -1;

    std::lock_guard<std::mutex> guard(state->lock);
    const jint capacity = env->GetArrayLength(out);
    if (capacity <= 0) return 0;

    // ── 原始 DSD：只解封装，把 1-bit 载荷按**字节交错**吐出去 ──
    //
    // DSF 的 packet 是 planar：一个 packet 里 `[ch0][ch1]…` 各连续一段（见 `splitChannels`）。
    // DoP / Native 要的是 `L0 R0 L1 R1…`，所以要在一个 packet **内部**拆声道再交错。
    if (state->rawDsd) {
        state->pcm.clear();
        // 一次至少留 8 字节位置：交付粒度见下面的 `n -= n % 4`
        while (static_cast<int>(state->pcm.size()) + 8 <= capacity) {
            // 攒齐一对（L + R）block
            while ((state->rawLeft.empty() || state->rawRight.empty()) && !state->eof) {
                const int ret = g_api.read_frame(state->fmt, state->packet);
                if (ret == AVERROR_EOF) {
                    state->eof = true;
                    break;
                }
                if (ret < 0) break;
                if (state->packet->stream_index != state->audioIndex) {
                    g_api.packet_unref(state->packet);
                    continue;
                }
                if (state->packet->pts != AV_NOPTS_VALUE) {
                    const int64_t us = g_api.rescale_q(
                        state->packet->pts, state->timeBase, AVRational{1, 1000000}
                    );
                    // 只有"这一对之前两侧都空"才是新一段的起点
                    if (state->rawLeft.empty() && state->rawRight.empty()) {
                        state->rawPtsUs = us;
                    }
                    state->lastPtsUs = us;
                }
                // 空 packet 不能推进任何状态
                const size_t len = static_cast<size_t>(state->packet->size);
                if (len > 0) {
                    splitChannels(state, state->packet->data, len);
                }
                g_api.packet_unref(state->packet);
            }
            if (state->rawLeft.empty() || state->rawRight.empty()) break;

            const size_t room = static_cast<size_t>(capacity - state->pcm.size()) / 2;
            // **必须按 8 字节的整数倍交付**。上游 DoP 按 4 字节、Native 按 8 字节切帧，
            // 余数会被它们丢掉；丢掉 2 / 4 / 6 字节就等于把之后整条流挪了位 ——
            // 从此全是噪声且完全看不出原因。余数留在 rawLeft/rawRight 里，下次接着用。
            size_t n = std::min(std::min(state->rawLeft.size(), state->rawRight.size()), room);
            n -= n % 4;
            if (n == 0) break;
            for (size_t i = 0; i < n; i++) {
                const uint8_t l = state->rawLeft[i];
                const uint8_t r = state->rawRight[i];
                if (state->rawBitReverse) {
                    state->pcm.push_back(reverseBits8(l));
                    state->pcm.push_back(reverseBits8(r));
                } else {
                    state->pcm.push_back(l);
                    state->pcm.push_back(r);
                }
            }
            state->rawLeft.erase(state->rawLeft.begin(), state->rawLeft.begin() + n);
            state->rawRight.erase(state->rawRight.begin(), state->rawRight.begin() + n);
        }
        const int rawTotal = static_cast<int>(state->pcm.size());
        if (rawTotal > 0) {
            env->SetByteArrayRegion(
                out, 0, rawTotal, reinterpret_cast<const jbyte *>(state->pcm.data())
            );
        }
        if (meta != nullptr && env->GetArrayLength(meta) >= 1) {
            const jlong pts = state->rawPtsUs;
            env->SetLongArrayRegion(meta, 0, 1, &pts);
            // 1-bit 流的时间轴按**比特**走：每字节 8 个采样点，一个立体声帧是 2 字节。
            // 不推进就会让每一块都报同一个 pts —— Media3 会判时间轴停滞。
            if (state->rawBitRate > 0 && rawTotal > 0) {
                state->rawPtsUs += static_cast<int64_t>(rawTotal / 2) * 8 * 1000000LL /
                    state->rawBitRate;
            }
        }
        return rawTotal;
    }

    state->pcm.clear();
    // 上一块塞不下的尾巴先补上：容量不是「每帧字节数」的整数倍，直接丢弃会让
    // 立体声交错永久错位（差 1-5 字节 → 之后整段左右颠倒），也会累积成时间轴漂移
    if (!state->pending.empty()) {
        const int room = std::min(capacity, static_cast<int>(state->pending.size()));
        state->pcm.insert(
            state->pcm.end(), state->pending.begin(), state->pending.begin() + room
        );
        state->pending.erase(state->pending.begin(), state->pending.begin() + room);
    }

    while (static_cast<int>(state->pcm.size()) < capacity) {
        int ret = g_api.receive_frame(state->ctx, state->frame);
        if (ret == AVERROR(EAGAIN)) {
            // eof 之后再 EAGAIN = 解码器已经掏空，真结束
            if (state->eof) break;
            const int readRet = g_api.read_frame(state->fmt, state->packet);
            if (readRet == AVERROR_EOF) {
                state->eof = true;
                g_api.send_packet(state->ctx, nullptr);  // 冲刷解码器
                continue;
            }
            if (readRet < 0) break;
            if (state->packet->stream_index != state->audioIndex) {
                g_api.packet_unref(state->packet);
                continue;
            }
            g_api.send_packet(state->ctx, state->packet);
            g_api.packet_unref(state->packet);
            continue;
        }
        if (ret == AVERROR_EOF) {
            // 把重采样器内部残留吐干净（整数分频时也就几十个样本），否则结尾会缺一小段
            if (!state->eof) {
                uint8_t *tailPtr = state->swrOut.data();
                const int tail = g_api.swr_convert(
                    state->swr, &tailPtr, kMaxFramesPerConvert, nullptr, 0
                );
                if (tail > 0) {
                    const int tailBytes = convertInterleaved(state->swrOut.data(), tail, state->outBits);
                    if (static_cast<int>(state->pcm.size()) + tailBytes <= capacity) {
                        state->pcm.insert(
                            state->pcm.end(), state->swrOut.data(), state->swrOut.data() + tailBytes
                        );
                    }
                }
            }
            state->eof = true;
            break;
        }
        if (ret < 0) {
            LOGE("receive_frame failed: %d", ret);
            break;
        }

        uint8_t *swrPtr = state->swrOut.data();
        const int converted = g_api.swr_convert(
            state->swr,
            &swrPtr,
            kMaxFramesPerConvert,
            const_cast<const uint8_t *const *>(
                reinterpret_cast<uint8_t *const *>(state->frame->extended_data)
            ),
            state->frame->nb_samples
        );
        g_api.frame_unref(state->frame);

        if (converted < 0) {
            LOGE("swr_convert failed: %d", converted);
            break;
        }
        if (converted == 0) continue;

        const int written = convertInterleaved(state->swrOut.data(), converted, state->outBits);
        if (static_cast<int>(state->pcm.size()) + written > capacity) {
            // swr 已经吃掉了这批输入，写不下的字节就是永久缺失的音频，留下来下次吐
            state->pending.assign(state->swrOut.data(), state->swrOut.data() + written);
            break;
        }
        state->pcm.insert(state->pcm.end(), state->swrOut.data(), state->swrOut.data() + written);
    }

    const int total = static_cast<int>(state->pcm.size());
    if (total > 0) {
        env->SetByteArrayRegion(out, 0, total, reinterpret_cast<const jbyte *>(state->pcm.data()));
    }
    if (meta != nullptr && env->GetArrayLength(meta) >= 1) {
        // 输出恒为立体声、定长样本：帧数与字节数严格对应，时间轴不会漂
        const int bytesPerFrame = 2 * (state->outBits == 24 ? 3 : 2);
        const jlong pts = state->basePtsUs + state->emittedFrames * 1000000LL / state->outRate;
        env->SetLongArrayRegion(meta, 0, 1, &pts);
        state->emittedFrames += total / bytesPerFrame;
    }
    return total;
}

JNIEXPORT void JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_ffmpeg_NativeFfmpegDemux_seek(
    JNIEnv *, jobject, jlong handle, jlong positionUs
) {
    auto *state = reinterpret_cast<DemuxState *>(handle);
    if (state == nullptr || !loadApi()) return;
    std::lock_guard<std::mutex> guard(state->lock);

    // `stream_index = -1` 是**必须**的：只有它才把 min/max/target 解释成 AV_TIME_BASE（微秒）。
    // 一旦传具体的 audioIndex，这三个值就按**该流的 time_base** 解释 —— DSD 的 time_base 是
    // 每字节一个 tick（2822400），把 126339000 微秒当成 tick 会直接越过文件末尾。
    // 实测后果：`avformat_seek_file` 返回 0（"成功"），下一次 `read_frame` 立刻 EOF，
    // 播放器判定整首播完 → 单曲循环 seek 回 0，表现为「一点进度条就跳回开头」。
    // AVSEEK_FLAG_BACKWARD：落在目标之前的关键位置，音频解码才拿得到起点。
    const int64_t target = positionUs > 0 ? positionUs : 0;
    g_api.seek_file(state->fmt, -1, INT64_MIN, target, INT64_MAX, AVSEEK_FLAG_BACKWARD);
    // raw DSD 没有解码器（`ctx == nullptr`），不能无条件 flush
    if (state->ctx != nullptr) {
        g_api.flush(state->ctx);
    }
    if (state->swr != nullptr) {
        g_api.swr_convert(state->swr, nullptr, 0, nullptr, 0);  // 丢掉重采样器残留
    }
    // seek 之后上一包没吐完的尾巴属于旧位置，必须丢
    state->pending.clear();
    state->rawLeft.clear();
    state->rawRight.clear();
    state->rawPtsUs = target;
    state->eof = false;
    state->lastPtsUs = target;
    state->basePtsUs = target;
    state->emittedFrames = 0;
}

JNIEXPORT void JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_ffmpeg_NativeFfmpegDemux_release(
    JNIEnv *, jobject, jlong handle
) {
    auto *state = reinterpret_cast<DemuxState *>(handle);
    if (state == nullptr) return;
    releaseState(state);
}

}  // extern "C"
