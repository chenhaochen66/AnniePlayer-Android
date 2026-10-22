#include <jni.h>
#include <android/log.h>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <vector>

namespace {

constexpr int kEnc16 = 2;
constexpr int kEnc24 = 21;
constexpr int kEncFloat = 4;

constexpr int kLowShelf = 0;
constexpr int kPeaking = 1;
constexpr int kHighShelf = 2;

constexpr double kPi = 3.14159265358979323846;

struct Biquad {
    float b0 = 1.0f;
    float b1 = 0.0f;
    float b2 = 0.0f;
    float a1 = 0.0f;
    float a2 = 0.0f;
    bool enabled = false;
};

struct TuningState {
    int channels = 2;
    int sampleRate = 44100;
    std::vector<Biquad> bands;
    std::vector<float> z1;
    std::vector<float> z2;
    float preamp = 1.0f;
    bool limiter = false;
    std::mutex mutex;
};

inline float softLimit(float x) {
    float ax = std::fabs(x);
    if (ax <= 0.7f) return x;
    float sign = x < 0.0f ? -1.0f : 1.0f;
    return sign * (0.7f + 0.3f * std::tanh((ax - 0.7f) / 0.3f));
}

inline int readSample(const uint8_t* p, int encoding) {
    if (encoding == kEncFloat) {
        float v;
        std::memcpy(&v, p, sizeof(float));
        return static_cast<int>(v * 8388608.0f);
    }
    if (encoding == kEnc24) {
        int v = static_cast<int>(p[0] | (p[1] << 8) | (p[2] << 16));
        if (v & 0x800000) v |= ~0xFFFFFF;
        return v;
    }
    int16_t v;
    std::memcpy(&v, p, sizeof(int16_t));
    return static_cast<int>(v) << 8;
}

inline void writeSample(uint8_t* p, int encoding, float v) {
    if (encoding == kEncFloat) {
        float f = v;
        std::memcpy(p, &f, sizeof(float));
        return;
    }
    if (encoding == kEnc24) {
        int s = static_cast<int>(v * 8388607.0f);
        if (s > 8388607) s = 8388607;
        if (s < -8388608) s = -8388608;
        p[0] = static_cast<uint8_t>(s & 0xFF);
        p[1] = static_cast<uint8_t>((s >> 8) & 0xFF);
        p[2] = static_cast<uint8_t>((s >> 16) & 0xFF);
        return;
    }
    int s = static_cast<int>(v * 32767.0f);
    if (s > 32767) s = 32767;
    if (s < -32768) s = -32768;
    int16_t out = static_cast<int16_t>(s);
    std::memcpy(p, &out, sizeof(int16_t));
}

void recalcBiquad(Biquad& q, int type, double freq, double qv, double gainDb, double sr) {
    if (std::fabs(gainDb) < 0.01) {
        q.enabled = false;
        return;
    }
    double f = freq;
    if (f < 10.0) f = 10.0;
    if (f > sr * 0.49) f = sr * 0.49;
    double quality = qv;
    if (quality < 0.1) quality = 0.1;
    if (quality > 30.0) quality = 30.0;

    double a = std::pow(10.0, gainDb / 40.0);
    double w0 = 2.0 * kPi * f / sr;
    double cosW = std::cos(w0);
    double sinW = std::sin(w0);

    double b0, b1, b2, a0, a1, a2;
    if (type == kLowShelf) {
        double alpha = sinW / 2.0 * std::sqrt(2.0);
        double k = 2.0 * std::sqrt(a) * alpha;
        b0 = a * ((a + 1.0) - (a - 1.0) * cosW + k);
        b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW);
        b2 = a * ((a + 1.0) - (a - 1.0) * cosW - k);
        a0 = (a + 1.0) + (a - 1.0) * cosW + k;
        a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosW);
        a2 = (a + 1.0) + (a - 1.0) * cosW - k;
    } else if (type == kHighShelf) {
        double alpha = sinW / 2.0 * std::sqrt(2.0);
        double k = 2.0 * std::sqrt(a) * alpha;
        b0 = a * ((a + 1.0) + (a - 1.0) * cosW + k);
        b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW);
        b2 = a * ((a + 1.0) + (a - 1.0) * cosW - k);
        a0 = (a + 1.0) - (a - 1.0) * cosW + k;
        a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosW);
        a2 = (a + 1.0) - (a - 1.0) * cosW - k;
    } else {
        double alpha = sinW / (2.0 * quality);
        b0 = 1.0 + alpha * a;
        b1 = -2.0 * cosW;
        b2 = 1.0 - alpha * a;
        a0 = 1.0 + alpha / a;
        a1 = -2.0 * cosW;
        a2 = 1.0 - alpha / a;
    }

    q.b0 = static_cast<float>(b0 / a0);
    q.b1 = static_cast<float>(b1 / a0);
    q.b2 = static_cast<float>(b2 / a0);
    q.a1 = static_cast<float>(a1 / a0);
    q.a2 = static_cast<float>(a2 / a0);
    q.enabled = true;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeDsp_create(JNIEnv*, jobject, jint channels) {
    auto* state = new TuningState();
    state->channels = channels < 1 ? 1 : channels;
    return reinterpret_cast<jlong>(state);
}

JNIEXPORT void JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeDsp_configure(
        JNIEnv* env, jobject, jlong handle, jint sampleRate,
        jintArray types, jfloatArray freqs, jfloatArray qs, jfloatArray gains,
        jfloat preampDb, jboolean limiter) {
    auto* state = reinterpret_cast<TuningState*>(handle);
    if (state == nullptr) return;

    jsize count = env->GetArrayLength(types);
    std::vector<int> t(count);
    std::vector<float> f(count), q(count), g(count);
    env->GetIntArrayRegion(types, 0, count, t.data());
    env->GetFloatArrayRegion(freqs, 0, count, f.data());
    env->GetFloatArrayRegion(qs, 0, count, q.data());
    env->GetFloatArrayRegion(gains, 0, count, g.data());

    std::lock_guard<std::mutex> lock(state->mutex);
    state->sampleRate = sampleRate > 0 ? sampleRate : 44100;
    state->bands.assign(count, Biquad{});
    for (int i = 0; i < count; ++i) {
        recalcBiquad(state->bands[i], t[i], f[i], q[i], g[i], state->sampleRate);
    }
    state->z1.assign(count * state->channels, 0.0f);
    state->z2.assign(count * state->channels, 0.0f);
    state->preamp = static_cast<float>(std::pow(10.0, preampDb / 20.0));
    state->limiter = limiter == JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeDsp_process(
        JNIEnv* env, jobject, jlong handle, jobject input, jobject output,
        jint frames, jint encoding) {
    auto* state = reinterpret_cast<TuningState*>(handle);
    if (state == nullptr) return;

    auto* in = static_cast<uint8_t*>(env->GetDirectBufferAddress(input));
    auto* out = static_cast<uint8_t*>(env->GetDirectBufferAddress(output));
    if (in == nullptr || out == nullptr) return;

    std::lock_guard<std::mutex> lock(state->mutex);
    const int channels = state->channels;
    const int total = frames * channels;
    const int sampleBytes = encoding == kEncFloat ? 4 : (encoding == kEnc24 ? 3 : 2);

    thread_local std::vector<float> buffer;
    if (static_cast<int>(buffer.size()) < total) buffer.resize(total);

    for (int i = 0; i < total; ++i) {
        buffer[i] = static_cast<float>(readSample(in + i * sampleBytes, encoding)) / 8388608.0f;
    }

    const int bandCount = static_cast<int>(state->bands.size());
    for (int band = 0; band < bandCount; ++band) {
        const Biquad& bq = state->bands[band];
        if (!bq.enabled) continue;
        for (int i = 0; i < total; ++i) {
            const int channel = i % channels;
            const int idx = band * channels + channel;
            const float x = buffer[i];
            const float y = bq.b0 * x + state->z1[idx];
            state->z1[idx] = bq.b1 * x - bq.a1 * y + state->z2[idx];
            state->z2[idx] = bq.b2 * x - bq.a2 * y;
            buffer[i] = y;
        }
    }

    const float gain = state->preamp;
    const bool limiter = state->limiter;
    for (int i = 0; i < total; ++i) {
        float v = buffer[i] * gain;
        if (limiter) v = softLimit(v);
        buffer[i] = v;
    }

    for (int i = 0; i < total; ++i) {
        writeSample(out + i * sampleBytes, encoding, buffer[i]);
    }
}

JNIEXPORT void JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeDsp_processFloat(
        JNIEnv* env, jobject, jlong handle, jfloatArray samples, jint total) {
    auto* state = reinterpret_cast<TuningState*>(handle);
    if (state == nullptr) return;
    jfloat* buf = env->GetFloatArrayElements(samples, nullptr);
    if (buf == nullptr) return;

    std::lock_guard<std::mutex> lock(state->mutex);
    const int channels = state->channels;
    const int bandCount = static_cast<int>(state->bands.size());
    for (int band = 0; band < bandCount; ++band) {
        const Biquad& bq = state->bands[band];
        if (!bq.enabled) continue;
        for (int i = 0; i < total; ++i) {
            const int channel = i % channels;
            const int idx = band * channels + channel;
            const float x = buf[i];
            const float y = bq.b0 * x + state->z1[idx];
            state->z1[idx] = bq.b1 * x - bq.a1 * y + state->z2[idx];
            state->z2[idx] = bq.b2 * x - bq.a2 * y;
            buf[i] = y;
        }
    }

    const float gain = state->preamp;
    const bool limiter = state->limiter;
    for (int i = 0; i < total; ++i) {
        float v = buf[i] * gain;
        if (limiter) v = softLimit(v);
        buf[i] = v;
    }

    env->ReleaseFloatArrayElements(samples, buf, 0);
}

JNIEXPORT jint JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeDsp_processBuffer(
        JNIEnv* env, jobject, jlong handle, jobject input, jobject output,
        jint inputOffset, jint frames, jint encoding) {
    auto* state = reinterpret_cast<TuningState*>(handle);
    if (state == nullptr) return 0;

    auto* inBase = static_cast<uint8_t*>(env->GetDirectBufferAddress(input));
    auto* outBase = static_cast<uint8_t*>(env->GetDirectBufferAddress(output));
    if (inBase == nullptr || outBase == nullptr) return 0;
    auto* in = inBase + inputOffset;

    std::lock_guard<std::mutex> lock(state->mutex);
    const int channels = state->channels;
    const int total = frames * channels;
    const int sampleBytes = encoding == kEncFloat ? 4 : (encoding == kEnc24 ? 3 : 2);

    thread_local std::vector<float> buffer;
    if (static_cast<int>(buffer.size()) < total) buffer.resize(total);

    for (int i = 0; i < total; ++i) {
        buffer[i] = static_cast<float>(readSample(in + i * sampleBytes, encoding)) / 8388608.0f;
    }

    const int bandCount = static_cast<int>(state->bands.size());
    for (int band = 0; band < bandCount; ++band) {
        const Biquad& bq = state->bands[band];
        if (!bq.enabled) continue;
        for (int i = 0; i < total; ++i) {
            const int channel = i % channels;
            const int idx = band * channels + channel;
            const float x = buffer[i];
            const float y = bq.b0 * x + state->z1[idx];
            state->z1[idx] = bq.b1 * x - bq.a1 * y + state->z2[idx];
            state->z2[idx] = bq.b2 * x - bq.a2 * y;
            buffer[i] = y;
        }
    }

    const float gain = state->preamp;
    const bool limiter = state->limiter;
    for (int i = 0; i < total; ++i) {
        float v = buffer[i] * gain;
        if (limiter) v = softLimit(v);
        buffer[i] = v;
    }

    for (int i = 0; i < total; ++i) {
        writeSample(outBase + i * sampleBytes, encoding, buffer[i]);
    }
    return total * sampleBytes;
}

JNIEXPORT jint JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeDsp_processBytes(
        JNIEnv* env, jobject, jlong handle, jbyteArray input, jbyteArray output,
        jint frames, jint encoding) {
    auto* state = reinterpret_cast<TuningState*>(handle);
    if (state == nullptr) return 0;
    jbyte* in = env->GetByteArrayElements(input, nullptr);
    jbyte* out = env->GetByteArrayElements(output, nullptr);
    if (in == nullptr || out == nullptr) {
        if (in != nullptr) env->ReleaseByteArrayElements(input, in, JNI_ABORT);
        if (out != nullptr) env->ReleaseByteArrayElements(output, out, JNI_ABORT);
        return 0;
    }

    std::lock_guard<std::mutex> lock(state->mutex);
    const int channels = state->channels;
    const int total = frames * channels;
    const int sampleBytes = encoding == kEncFloat ? 4 : (encoding == kEnc24 ? 3 : 2);

    thread_local std::vector<float> buffer;
    if (static_cast<int>(buffer.size()) < total) buffer.resize(total);
    auto* inBytes = reinterpret_cast<uint8_t*>(in);
    auto* outBytes = reinterpret_cast<uint8_t*>(out);

    for (int i = 0; i < total; ++i) {
        buffer[i] = static_cast<float>(readSample(inBytes + i * sampleBytes, encoding)) / 8388608.0f;
    }

    const int bandCount = static_cast<int>(state->bands.size());
    for (int band = 0; band < bandCount; ++band) {
        const Biquad& bq = state->bands[band];
        if (!bq.enabled) continue;
        for (int i = 0; i < total; ++i) {
            const int channel = i % channels;
            const int idx = band * channels + channel;
            const float x = buffer[i];
            const float y = bq.b0 * x + state->z1[idx];
            state->z1[idx] = bq.b1 * x - bq.a1 * y + state->z2[idx];
            state->z2[idx] = bq.b2 * x - bq.a2 * y;
            buffer[i] = y;
        }
    }

    const float gain = state->preamp;
    const bool limiter = state->limiter;
    for (int i = 0; i < total; ++i) {
        float v = buffer[i] * gain;
        if (limiter) v = softLimit(v);
        buffer[i] = v;
    }

    for (int i = 0; i < total; ++i) {
        writeSample(outBytes + i * sampleBytes, encoding, buffer[i]);
    }

    env->ReleaseByteArrayElements(input, in, JNI_ABORT);
    env->ReleaseByteArrayElements(output, out, 0);
    return total * sampleBytes;
}

JNIEXPORT void JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeDsp_reset(JNIEnv*, jobject, jlong handle) {
    auto* state = reinterpret_cast<TuningState*>(handle);
    if (state == nullptr) return;
    std::lock_guard<std::mutex> lock(state->mutex);
    std::fill(state->z1.begin(), state->z1.end(), 0.0f);
    std::fill(state->z2.begin(), state->z2.end(), 0.0f);
}

JNIEXPORT void JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeDsp_release(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<TuningState*>(handle);
}

}  // extern "C"
