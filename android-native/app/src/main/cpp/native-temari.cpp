#include <jni.h>

#include <cstdint>
#include <cstring>
#include <vector>

extern "C" {
void *tmpl_from_json(const char *json, size_t len);
void tmpl_destroy(void *tmpl);
size_t decrypt_sample_ffi(
    const void *tmpl,
    const uint8_t *sample,
    size_t sample_len,
    uint8_t *out
);
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_apple_TemariDecryptor_nativeAvailable(
    JNIEnv *, jobject
) {
    return JNI_TRUE;
}

JNIEXPORT jlong JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_apple_TemariDecryptor_nativeCreate(
    JNIEnv *env, jobject, jbyteArray templateJson
) {
    if (templateJson == nullptr) return 0;
    const jsize length = env->GetArrayLength(templateJson);
    if (length <= 0) return 0;

    jbyte *bytes = env->GetByteArrayElements(templateJson, nullptr);
    if (bytes == nullptr) return 0;
    void *handle = tmpl_from_json(
        reinterpret_cast<const char *>(bytes),
        static_cast<size_t>(length)
    );
    env->ReleaseByteArrayElements(templateJson, bytes, JNI_ABORT);
    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT jbyteArray JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_apple_TemariDecryptor_nativeDecrypt(
    JNIEnv *env, jobject, jlong templateHandle, jbyteArray sample
) {
    auto *handle = reinterpret_cast<void *>(templateHandle);
    if (handle == nullptr || sample == nullptr) return nullptr;

    const jsize length = env->GetArrayLength(sample);
    if (length <= 0) return nullptr;

    jbyte *input = env->GetByteArrayElements(sample, nullptr);
    if (input == nullptr) return nullptr;

    std::vector<uint8_t> output(static_cast<size_t>(length));
    const size_t written = decrypt_sample_ffi(
        handle,
        reinterpret_cast<const uint8_t *>(input),
        static_cast<size_t>(length),
        output.data()
    );
    env->ReleaseByteArrayElements(sample, input, JNI_ABORT);

    if (written != static_cast<size_t>(length)) return nullptr;

    jbyteArray result = env->NewByteArray(length);
    if (result == nullptr) return nullptr;
    env->SetByteArrayRegion(
        result, 0, length, reinterpret_cast<const jbyte *>(output.data())
    );
    return result;
}

JNIEXPORT void JNICALL
Java_com_annieplayer_annie_1music_kt_core_audio_apple_TemariDecryptor_nativeDestroy(
    JNIEnv *, jobject, jlong templateHandle
) {
    tmpl_destroy(reinterpret_cast<void *>(templateHandle));
}

}  // extern "C"
