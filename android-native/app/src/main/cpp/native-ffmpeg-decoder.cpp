#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>

#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "libavcodec/avcodec.h"
#include "libavutil/channel_layout.h"
#include "libavutil/error.h"
#include "libavutil/frame.h"
#include "libavutil/log.h"
#include "libavutil/mem.h"
#include "libavutil/samplefmt.h"

#define LOG_TAG "AnnieFfmpeg"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

using PFN_find_decoder = const AVCodec *(*)(enum AVCodecID);
using PFN_find_decoder_by_name = const AVCodec *(*)(const char *);
using PFN_alloc_context = AVCodecContext *(*)(const AVCodec *);
using PFN_open2 = int (*)(AVCodecContext *, const AVCodec *, AVDictionary **);
using PFN_send_packet = int (*)(AVCodecContext *, const AVPacket *);
using PFN_receive_frame = int (*)(AVCodecContext *, AVFrame *);
using PFN_flush = void (*)(AVCodecContext *);
using PFN_free_context = void (*)(AVCodecContext **);
using PFN_packet_alloc = AVPacket *(*)();
using PFN_packet_free = void (*)(AVPacket **);
using PFN_new_packet = int (*)(AVPacket *, int);
using PFN_packet_unref = void (*)(AVPacket *);
using PFN_frame_alloc = AVFrame *(*)();
using PFN_frame_free = void (*)(AVFrame **);
using PFN_frame_unref = void (*)(AVFrame *);
using PFN_mallocz = void *(*)(size_t);
using PFN_free = void (*)(void *);
using PFN_channel_layout_default = void (*)(AVChannelLayout *, int);
using PFN_log_set_level = void (*)(int);

struct Api {
    PFN_find_decoder find_decoder = nullptr;
    PFN_find_decoder_by_name find_decoder_by_name = nullptr;
    PFN_alloc_context alloc_context = nullptr;
    PFN_open2 open2 = nullptr;
    PFN_send_packet send_packet = nullptr;
    PFN_receive_frame receive_frame = nullptr;
    PFN_flush flush = nullptr;
    PFN_free_context free_context = nullptr;
    PFN_packet_alloc packet_alloc = nullptr;
    PFN_packet_free packet_free = nullptr;
    PFN_new_packet new_packet = nullptr;
    PFN_packet_unref packet_unref = nullptr;
    PFN_frame_alloc frame_alloc = nullptr;
    PFN_frame_free frame_free = nullptr;
    PFN_frame_unref frame_unref = nullptr;
    PFN_mallocz mallocz = nullptr;
    PFN_free free_mem = nullptr;
    PFN_channel_layout_default channel_layout_default = nullptr;
    PFN_log_set_level log_set_level = nullptr;
};

Api g_api;
std::once_flag g_once;
bool g_ready = false;

template <typename T>
void load(void *handle, const char *name, T &target) {
    target = reinterpret_cast<T>(dlsym(handle, name));
}


bool loadApi() {
    std::call_once(g_once, [] {
        void *avutil = dlopen("libavutil.so", RTLD_NOW | RTLD_LOCAL);
        void *avcodec = dlopen("libavcodec.so", RTLD_NOW | RTLD_LOCAL);
        if (avutil == nullptr || avcodec == nullptr) {
            LOGE("dlopen failed: %s", dlerror());
            return;
        }

        load(avcodec, "avcodec_find_decoder", g_api.find_decoder);
        load(avcodec, "avcodec_find_decoder_by_name", g_api.find_decoder_by_name);
        load(avcodec, "avcodec_alloc_context3", g_api.alloc_context);
        load(avcodec, "avcodec_open2", g_api.open2);
        load(avcodec, "avcodec_send_packet", g_api.send_packet);
        load(avcodec, "avcodec_receive_frame", g_api.receive_frame);
        load(avcodec, "avcodec_flush_buffers", g_api.flush);
        load(avcodec, "avcodec_free_context", g_api.free_context);
        load(avcodec, "av_packet_alloc", g_api.packet_alloc);
        load(avcodec, "av_packet_free", g_api.packet_free);
        load(avcodec, "av_new_packet", g_api.new_packet);
        load(avcodec, "av_packet_unref", g_api.packet_unref);

        load(avutil, "av_frame_alloc", g_api.frame_alloc);
        load(avutil, "av_frame_free", g_api.frame_free);
        load(avutil, "av_frame_unref", g_api.frame_unref);
        load(avutil, "av_mallocz", g_api.mallocz);
        load(avutil, "av_free", g_api.free_mem);
        load(avutil, "av_channel_layout_default", g_api.channel_layout_default);
        load(avutil, "av_log_set_level", g_api.log_set_level);

        g_ready = g_api.find_decoder && g_api.find_decoder_by_name &&
                  g_api.alloc_context && g_api.open2 &&
                  g_api.send_packet && g_api.receive_frame && g_api.flush &&
                  g_api.free_context && g_api.packet_alloc && g_api.packet_free &&
                  g_api.new_packet && g_api.packet_unref && g_api.frame_alloc &&
                  g_api.frame_free && g_api.frame_unref && g_api.mallocz &&
                  g_api.free_mem && g_api.channel_layout_default;

        if (g_ready && g_api.log_set_level) g_api.log_set_level(AV_LOG_QUIET);
    });
    return g_ready;
}

struct FfmpegState {
    AVCodecContext *ctx = nullptr;
    AVPacket *packet = nullptr;
    AVFrame *frame = nullptr;
    int channels = 2;
    int outputBits = 16;
    std::vector<uint8_t> output;
    std::mutex lock;
};

AVCodecContext *openContext(
    const char *codecName,
    int sampleRate,
    int channels,
    int bitsPerSample,
    const uint8_t *extradata,
    int extradataSize
) {
    const AVCodec *codec = g_api.find_decoder_by_name(codecName);
    if (codec == nullptr) return nullptr;

    AVCodecContext *ctx = g_api.alloc_context(codec);
    if (ctx == nullptr) return nullptr;

    ctx->sample_rate = sampleRate > 0 ? sampleRate : 44100;
    ctx->bits_per_raw_sample = bitsPerSample;
    ctx->request_sample_fmt = AV_SAMPLE_FMT_S32;
    ctx->thread_count = 1;
    ctx->thread_type = 0;
    if (channels > 0) {
        g_api.channel_layout_default(&ctx->ch_layout, channels);
    }

    if (extradata != nullptr && extradataSize > 0) {
        auto *buf = static_cast<uint8_t *>(
            g_api.mallocz(static_cast<size_t>(extradataSize) + AV_INPUT_BUFFER_PADDING_SIZE)
        );
        if (buf == nullptr) {
            g_api.free_context(&ctx);
            return nullptr;
        }
        std::memcpy(buf, extradata, static_cast<size_t>(extradataSize));
        ctx->extradata = buf;
        ctx->extradata_size = extradataSize;
    }

    int openRet = g_api.open2(ctx, codec, nullptr);
    if (openRet < 0) {
        LOGE("avcodec_open2 failed (rate=%d ch=%d bits=%d extra=%d) ret=%d",
             sampleRate, channels, bitsPerSample, extradataSize, openRet);
        g_api.free_context(&ctx);
        return nullptr;
    }    return ctx;
}

void writeSample(uint8_t *out, int index, int outBits, float value) {
    if (outBits == 24) {
        int32_t sample = static_cast<int32_t>(std::lrintf(value * 8388607.0f));
        if (sample > 8388607) sample = 8388607;
        if (sample < -8388608) sample = -8388608;
        out[index * 3] = static_cast<uint8_t>(sample & 0xFF);
        out[index * 3 + 1] = static_cast<uint8_t>((sample >> 8) & 0xFF);
        out[index * 3 + 2] = static_cast<uint8_t>((sample >> 16) & 0xFF);
    } else {
        int32_t sample = static_cast<int32_t>(std::lrintf(value * 32767.0f));
        if (sample > 32767) sample = 32767;
        if (sample < -32768) sample = -32768;
        out[index * 2] = static_cast<uint8_t>(sample & 0xFF);
        out[index * 2 + 1] = static_cast<uint8_t>((sample >> 8) & 0xFF);
    }
}

int convertFrame(
    const AVFrame *frame,
    uint8_t *out,
    int capacity,
    int outBits,
    int fallbackChannels
) {
    int channels = frame->ch_layout.nb_channels;
    if (channels <= 0) channels = fallbackChannels;
    const int samples = frame->nb_samples;
    if (channels <= 0 || samples <= 0) return 0;

    const int bytesPerSample = outBits == 24 ? 3 : 2;
    const int needed = samples * channels * bytesPerSample;
    if (needed > capacity) return -1001;

    const int total = samples * channels;
    switch (frame->format) {
        case AV_SAMPLE_FMT_S32: {
            const auto *src = reinterpret_cast<const int32_t *>(frame->data[0]);
            for (int i = 0; i < total; ++i) {
                writeSample(out, i, outBits, src[i] * (1.0f / 2147483648.0f));
            }
            break;
        }
        case AV_SAMPLE_FMT_S32P: {
            for (int c = 0; c < channels; ++c) {
                const auto *src = reinterpret_cast<const int32_t *>(frame->data[c]);
                for (int s = 0; s < samples; ++s) {
                    writeSample(out, s * channels + c, outBits,
                                src[s] * (1.0f / 2147483648.0f));
                }
            }
            break;
        }
        case AV_SAMPLE_FMT_S16: {
            const auto *src = reinterpret_cast<const int16_t *>(frame->data[0]);
            for (int i = 0; i < total; ++i) {
                writeSample(out, i, outBits, src[i] * (1.0f / 32768.0f));
            }
            break;
        }
        case AV_SAMPLE_FMT_S16P: {
            for (int c = 0; c < channels; ++c) {
                const auto *src = reinterpret_cast<const int16_t *>(frame->data[c]);
                for (int s = 0; s < samples; ++s) {
                    writeSample(out, s * channels + c, outBits,
                                src[s] * (1.0f / 32768.0f));
                }
            }
            break;
        }
        case AV_SAMPLE_FMT_FLT: {
            const auto *src = reinterpret_cast<const float *>(frame->data[0]);
            for (int i = 0; i < total; ++i) {
                writeSample(out, i, outBits, src[i]);
            }
            break;
        }
        case AV_SAMPLE_FMT_FLTP: {
            for (int c = 0; c < channels; ++c) {
                const auto *src = reinterpret_cast<const float *>(frame->data[c]);
                for (int s = 0; s < samples; ++s) {
                    writeSample(out, s * channels + c, outBits, src[s]);
                }
            }
            break;
        }
        default:
            return -1002;
    }
    return needed;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_ffmpeg_NativeFfmpegDecoder_nativeAvailable(
    JNIEnv *, jobject
) {
    return loadApi() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_ffmpeg_NativeFfmpegDecoder_create(
    JNIEnv *env,
    jobject,
    jstring codecName,
    jint sampleRate,
    jint channels,
    jint bitsPerSample,
    jint outputBits,
    jbyteArray extradata
) {
    if (!loadApi() || codecName == nullptr) return 0;

    const char *nameChars = env->GetStringUTFChars(codecName, nullptr);
    if (nameChars == nullptr) return 0;
    std::string codec(nameChars);
    env->ReleaseStringUTFChars(codecName, nameChars);

    std::vector<uint8_t> extra;
    if (extradata != nullptr) {
        const jsize size = env->GetArrayLength(extradata);
        if (size > 0) {
            extra.resize(static_cast<size_t>(size));
            env->GetByteArrayRegion(
                extradata, 0, size, reinterpret_cast<jbyte *>(extra.data())
            );
        }
    }

    AVCodecContext *ctx = openContext(
        codec.c_str(),
        sampleRate,
        channels,
        bitsPerSample,
        extra.empty() ? nullptr : extra.data(),
        static_cast<int>(extra.size())
    );
    if (ctx == nullptr && !extra.empty()) {
        // 某些流没有 STREAMINFO；帧头自带参数时仍可解。
        ctx = openContext(codec.c_str(), sampleRate, channels, bitsPerSample, nullptr, 0);
    }
    if (ctx == nullptr) return 0;

    auto *state = new FfmpegState();
    state->ctx = ctx;
    state->packet = g_api.packet_alloc();
    state->frame = g_api.frame_alloc();
    state->channels = channels > 0 ? channels : 2;
    state->outputBits = outputBits == 24 ? 24 : 16;
    if (state->packet == nullptr || state->frame == nullptr) {
        if (state->packet != nullptr) g_api.packet_free(&state->packet);
        if (state->frame != nullptr) g_api.frame_free(&state->frame);
        g_api.free_context(&state->ctx);
        delete state;
        return 0;
    }
    return reinterpret_cast<jlong>(state);
}

JNIEXPORT jint JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_ffmpeg_NativeFfmpegDecoder_decode(
    JNIEnv *env,
    jobject,
    jlong handle,
    jbyteArray input,
    jbyteArray output
) {
    auto *state = reinterpret_cast<FfmpegState *>(handle);
    if (state == nullptr || !loadApi()) return -1;

    std::lock_guard<std::mutex> guard(state->lock);
    const jsize inputSize = env->GetArrayLength(input);
    const jint capacity = env->GetArrayLength(output);
    if (inputSize <= 0) return 0;

    int ret = g_api.new_packet(state->packet, inputSize);
    if (ret < 0) return ret;
    env->GetByteArrayRegion(
        input, 0, inputSize, reinterpret_cast<jbyte *>(state->packet->data)
    );

    ret = g_api.send_packet(state->ctx, state->packet);
    while (ret == AVERROR(EAGAIN)) {
        int drain = g_api.receive_frame(state->ctx, state->frame);
        if (drain < 0) break;
        g_api.frame_unref(state->frame);
        ret = g_api.send_packet(state->ctx, state->packet);
    }
    g_api.packet_unref(state->packet);
    if (ret < 0) return ret;

    state->output.resize(static_cast<size_t>(capacity));
    int total = 0;
    while (true) {
        ret = g_api.receive_frame(state->ctx, state->frame);
        if (ret < 0) {
            if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
            return ret;
        }
        const int written = convertFrame(
            state->frame,
            state->output.data() + total,
            capacity - total,
            state->outputBits,
            state->channels
        );
        g_api.frame_unref(state->frame);
        if (written < 0) return written;
        total += written;
    }

    if (total > 0) {
        env->SetByteArrayRegion(
            output, 0, total, reinterpret_cast<jbyte *>(state->output.data())
        );
    }
    return total;
}

JNIEXPORT void JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_ffmpeg_NativeFfmpegDecoder_reset(
    JNIEnv *, jobject, jlong handle
) {
    auto *state = reinterpret_cast<FfmpegState *>(handle);
    if (state == nullptr || !loadApi()) return;
    std::lock_guard<std::mutex> guard(state->lock);
    g_api.flush(state->ctx);
    g_api.packet_unref(state->packet);
    g_api.frame_unref(state->frame);
}

JNIEXPORT void JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_ffmpeg_NativeFfmpegDecoder_release(
    JNIEnv *, jobject, jlong handle
) {
    auto *state = reinterpret_cast<FfmpegState *>(handle);
    if (state == nullptr) return;
    if (loadApi()) {
        if (state->packet != nullptr) g_api.packet_free(&state->packet);
        if (state->frame != nullptr) g_api.frame_free(&state->frame);
        if (state->ctx != nullptr) g_api.free_context(&state->ctx);
    }
    delete state;
}

}  // extern "C"
