#include <jni.h>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <vector>

namespace {

constexpr int kEnc16 = 2;
constexpr int kEnc24 = 21;
constexpr int kEncFloat = 4;

constexpr int kTaps = 48;
constexpr int kHalf = 24;
constexpr int kPhases = 512;
constexpr int kRingFrames = 65536;

constexpr double kPi = 3.14159265358979323846;

inline float softLimit(float x) {
    float ax = std::fabs(x);
    if (ax <= 0.7f) return x;
    float sign = x < 0.0f ? -1.0f : 1.0f;
    return sign * (0.7f + 0.3f * std::tanh((ax - 0.7f) / 0.3f));
}

inline float readSample(const uint8_t* p, int encoding) {
    if (encoding == kEncFloat) {
        float v;
        std::memcpy(&v, p, sizeof(float));
        return v;
    }
    if (encoding == kEnc24) {
        int v = static_cast<int>(p[0] | (p[1] << 8) | (p[2] << 16));
        if (v & 0x800000) v |= ~0xFFFFFF;
        return static_cast<float>(v) / 8388608.0f;
    }
    int16_t v;
    std::memcpy(&v, p, sizeof(int16_t));
    return static_cast<float>(v) / 32768.0f;
}

inline void writeSample(uint8_t* p, int encoding, float v, bool dither) {
    if (encoding == kEncFloat) {
        std::memcpy(p, &v, sizeof(float));
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
    if (dither) {
        v += (static_cast<float>(std::rand()) / RAND_MAX -
              static_cast<float>(std::rand()) / RAND_MAX) / 32768.0f;
    }
    int s = static_cast<int>(v * 32767.0f);
    if (s > 32767) s = 32767;
    if (s < -32768) s = -32768;
    int16_t out = static_cast<int16_t>(s);
    std::memcpy(p, &out, sizeof(int16_t));
}

double besselI0(double x) {
    double sum = 1.0;
    double term = 1.0;
    double half = x / 2.0;
    for (int k = 1; k < 32; ++k) {
        term *= (half / k) * (half / k);
        sum += term;
        if (term < 1e-12) break;
    }
    return sum;
}

struct RsState {
    int channels = 2;
    int srcRate = 44100;
    int dstRate = 48000;
    double ratio = 1.0;
    std::vector<float> coeffs;
    std::vector<float> ring;
    std::vector<float> scratch;
    int ringFrames = 0;
    long long ringStart = 0;
    long long totalIn = 0;
    double srcPos = 0.0;
    std::mutex mutex;
};

void buildCoeffs(RsState* s) {
    s->ratio = static_cast<double>(s->srcRate) / s->dstRate;
    s->coeffs.assign(kPhases * kTaps, 0.0f);
    double fc = 0.5 * std::min(1.0, static_cast<double>(s->dstRate) / s->srcRate) * 0.94;
    const double beta = 8.0;
    const double i0Beta = besselI0(beta);
    for (int phase = 0; phase < kPhases; ++phase) {
        double frac = static_cast<double>(phase) / kPhases;
        double sum = 0.0;
        for (int j = 0; j < kTaps; ++j) {
            double d = (j - (kHalf - 1)) - frac;
            double arg = 2.0 * fc * d;
            double sinc = std::fabs(arg) < 1e-9 ? 1.0 : std::sin(kPi * arg) / (kPi * arg);
            double r = d / kHalf;
            double window = std::fabs(r) >= 1.0 ? 0.0 : besselI0(beta * std::sqrt(1.0 - r * r)) / i0Beta;
            double v = 2.0 * fc * sinc * window;
            s->coeffs[phase * kTaps + j] = static_cast<float>(v);
            sum += v;
        }
        if (sum != 0.0) {
            for (int j = 0; j < kTaps; ++j) {
                s->coeffs[phase * kTaps + j] =
                    static_cast<float>(s->coeffs[phase * kTaps + j] / sum);
            }
        }
    }
}

void ensureSpace(RsState* s, int extraFrames) {
    const int capacity = kRingFrames;
    if (s->ringFrames + extraFrames <= capacity) return;
    long long keepFrom = static_cast<long long>(std::floor(s->srcPos)) - kHalf;
    int drop = static_cast<int>(keepFrom - s->ringStart);
    if (drop < 0) drop = 0;
    if (drop > s->ringFrames) drop = s->ringFrames;
    if (drop > 0) {
        int remain = s->ringFrames - drop;
        std::memmove(s->ring.data(), s->ring.data() + drop * s->channels,
                     remain * s->channels * sizeof(float));
        s->ringStart += drop;
        s->ringFrames = remain;
    }
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeResampler_create(
        JNIEnv*, jobject, jint channels, jint srcRate, jint dstRate) {
    auto* s = new RsState();
    s->channels = channels < 1 ? 1 : channels;
    s->srcRate = srcRate > 0 ? srcRate : 44100;
    s->dstRate = dstRate > 0 ? dstRate : 48000;
    s->ring.assign(kRingFrames * s->channels, 0.0f);
    buildCoeffs(s);
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT jint JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeResampler_processBytes(
        JNIEnv* env, jobject, jlong handle, jbyteArray input, jbyteArray output,
        jint inFrames, jint inEncoding, jint outEncoding, jboolean dither) {
    auto* s = reinterpret_cast<RsState*>(handle);
    if (s == nullptr) return 0;
    jbyte* in = env->GetByteArrayElements(input, nullptr);
    jbyte* out = env->GetByteArrayElements(output, nullptr);
    if (in == nullptr || out == nullptr) {
        if (in != nullptr) env->ReleaseByteArrayElements(input, in, JNI_ABORT);
        if (out != nullptr) env->ReleaseByteArrayElements(output, out, JNI_ABORT);
        return 0;
    }

    std::lock_guard<std::mutex> lock(s->mutex);
    const int channels = s->channels;
    const int inBytes = inEncoding == kEncFloat ? 4 : (inEncoding == kEnc24 ? 3 : 2);
    const int outBytes = outEncoding == kEncFloat ? 4 : (outEncoding == kEnc24 ? 3 : 2);
    const int totalIn = inFrames * channels;
    const int outCapacityBytes = env->GetArrayLength(output);
    const int outFrameCapacity = outCapacityBytes / (outBytes * channels);

    if (s->ringFrames + inFrames > kRingFrames) {
        ensureSpace(s, inFrames);
    }

    auto* inPtr = reinterpret_cast<uint8_t*>(in);
    int base = s->ringFrames * channels;
    for (int i = 0; i < totalIn; ++i) {
        s->ring[base + i] = readSample(inPtr + i * inBytes, inEncoding);
    }
    s->ringFrames += inFrames;
    s->totalIn += inFrames;

    auto* outPtr = reinterpret_cast<uint8_t*>(out);
    int produced = 0;
    while (produced < outFrameCapacity &&
           s->srcPos + kHalf < static_cast<double>(s->totalIn)) {
        long long center = static_cast<long long>(std::floor(s->srcPos));
        double frac = s->srcPos - center;
        int phase = static_cast<int>(frac * kPhases);
        if (phase < 0) phase = 0;
        if (phase >= kPhases) phase = kPhases - 1;
        const float* coeff = &s->coeffs[phase * kTaps];
        long long firstFrame = center - kHalf + 1;
        const int ringEnd = s->ringFrames * channels;

        for (int c = 0; c < channels; ++c) {
            float acc = 0.0f;
            long long relFrame = firstFrame - s->ringStart;
            int rel = static_cast<int>(relFrame) * channels + c;
            for (int j = 0; j < kTaps; ++j) {
                float v = (rel < 0 || rel >= ringEnd) ? 0.0f : s->ring[rel];
                acc += v * coeff[j];
                rel += channels;
            }
            writeSample(outPtr + (produced * channels + c) * outBytes,
                        outEncoding, acc, dither == JNI_TRUE);
        }
        ++produced;
        s->srcPos += s->ratio;
    }

    env->ReleaseByteArrayElements(input, in, JNI_ABORT);
    env->ReleaseByteArrayElements(output, out, 0);
    return produced * outBytes * channels;
}

JNIEXPORT void JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeResampler_reset(
        JNIEnv*, jobject, jlong handle) {
    auto* s = reinterpret_cast<RsState*>(handle);
    if (s == nullptr) return;
    std::lock_guard<std::mutex> lock(s->mutex);
    s->ringFrames = 0;
    s->ringStart = 0;
    s->totalIn = 0;
    s->srcPos = 0.0;
    std::fill(s->ring.begin(), s->ring.end(), 0.0f);
}

JNIEXPORT void JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_dsp_NativeResampler_release(
        JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<RsState*>(handle);
}

}  // extern "C"
