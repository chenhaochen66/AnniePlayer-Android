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

/**
 * `tanh` 的高精度有理近似（Pade [5/4]）。
 *
 * 原实现直接调 `std::tanh`：在 A53 上那是一条 50~100 周期的函数调用，而这个软限幅
 * 是**逐样本**跑的（44.1k × 2ch = 8.8 万次/秒；DSD 抽到 176.4k 时是 35 万次/秒）。
 *
 * 精度：|x| ≤ 3 时与 std::tanh 的差 < 5e-4，代入 `0.7 + 0.3·tanh(...)` 后最大偏差
 * 约 1.5e-4（≈ −76 dBFS）—— 低于 24bit PCM 的量化台阶，听不出差别。
 * 超出 ±8 直接夹到 ±1（此时真值已是 0.99999999）。
 */
inline float fastTanh(float x) {
    if (x > 8.0f) return 1.0f;
    if (x < -8.0f) return -1.0f;
    const float x2 = x * x;
    const float x4 = x2 * x2;
    const float t = x * (x4 + 105.0f * x2 + 945.0f) / (15.0f * x4 + 420.0f * x2 + 945.0f);
    return t > 1.0f ? 1.0f : (t < -1.0f ? -1.0f : t);
}

inline float softLimit(float x) {
    float ax = std::fabs(x);
    if (ax <= 0.7f) return x;
    float sign = x < 0.0f ? -1.0f : 1.0f;
    return sign * (0.7f + 0.3f * fastTanh((ax - 0.7f) / 0.3f));
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

/**
 * 核心处理：逐 band 双二阶 → 预放大 → 可选软限幅。**原地**改 `buffer`。
 *
 * 四个 JNI 入口（direct buffer / byte array / float array）共用这一份，
 * 所以性能改动只需要写一次。
 *
 * 相比原先内联在四个入口里的那四份拷贝，这里做了三件事：
 *
 * 1. **不再每样本算 `i % channels` 与 `band * channels + channel`。**
 *    除数 `channels` 是运行期值（不是编译期常量），ARM 上每次都是硬件 `sdiv`
 *    （约 10~20 周期），而它原本在**每个 band × 每个样本**都要算一次。现在改成
 *    「按声道外循环、样本步长 = channels」，状态放在局部变量里，滤波完只写回两次。
 * 2. `std::tanh` → [fastTanh] 有理近似（见那里的精度说明）。
 * 3. limiter 的 `if` 提到循环外，不再每样本判一次分支。
 *
 * 调用方必须先拿到 `state->mutex`。
 */
void applyBandsAndGain(TuningState& st, float* buffer, int total) {
    const int channels = st.channels;
    const int bandCount = static_cast<int>(st.bands.size());

    for (int band = 0; band < bandCount; ++band) {
        const Biquad& bq = st.bands[band];
        if (!bq.enabled) continue;

        // 系数与状态都进局部：内层循环不再解引用 vector
        const float b0 = bq.b0;
        const float b1 = bq.b1;
        const float b2 = bq.b2;
        const float a1 = bq.a1;
        const float a2 = bq.a2;
        float* z1 = st.z1.data() + band * channels;
        float* z2 = st.z2.data() + band * channels;

        for (int ch = 0; ch < channels; ++ch) {
            float s1 = z1[ch];
            float s2 = z2[ch];
            for (int i = ch; i < total; i += channels) {
                const float x = buffer[i];
                const float y = b0 * x + s1;
                s1 = b1 * x - a1 * y + s2;
                s2 = b2 * x - a2 * y;
                buffer[i] = y;
            }
            z1[ch] = s1;
            z2[ch] = s2;
        }
    }

    const float gain = st.preamp;
    if (st.limiter) {
        for (int i = 0; i < total; ++i) {
            buffer[i] = softLimit(buffer[i] * gain);
        }
    } else {
        for (int i = 0; i < total; ++i) {
            buffer[i] *= gain;
        }
    }
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

    applyBandsAndGain(*state, buffer.data(), total);

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
    applyBandsAndGain(*state, buf, total);

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

    applyBandsAndGain(*state, buffer.data(), total);

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
    jbyte* out = env->GetByteArrayElements(output, nullptr);    if (in == nullptr || out == nullptr) {
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

    applyBandsAndGain(*state, buffer.data(), total);

    // **NaN / Inf 闸。**
    //
    // PEQ 是 IIR：一个非有限值进入滤波器状态，之后每一块输出都是 NaN 且永远拉不回来 ——
    // 症状正是"某一首之后所有歌都没声"（NaN 送到 DAC 侧会被钳成满幅直流）。
    // 放在"算完、写回之前"，代价是一次分支预测必中的比较。
    for (int i = 0; i < total; ++i) {
        if (!std::isfinite(buffer[i])) buffer[i] = 0.0f;
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
