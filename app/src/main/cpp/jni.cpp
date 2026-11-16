#include <jni.h>
#include <atomic>
#include <cstring>
#include <string>
#include "whisper.h"

static std::atomic<bool> g_abort(false);
static bool abort_cb(void *) { return g_abort.load(); }

#define FN(name) Java_com_vault_legendas_WhisperNative_##name

extern "C" {

JNIEXPORT jlong JNICALL FN(nInit)(JNIEnv *env, jobject, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL FN(nFree)(JNIEnv *, jobject, jlong ptr) {
    if (ptr != 0) whisper_free(reinterpret_cast<whisper_context *>(ptr));
}

JNIEXPORT void JNICALL FN(nCancel)(JNIEnv *, jobject, jboolean flag) {
    g_abort.store(flag == JNI_TRUE);
}

JNIEXPORT jint JNICALL FN(nRun)(JNIEnv *env, jobject, jlong ptr, jint threads, jstring lang, jfloatArray audio) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    const char *l = env->GetStringUTFChars(lang, nullptr);
    std::string language(l);
    env->ReleaseStringUTFChars(lang, l);

    jsize n = env->GetArrayLength(audio);
    jfloat *data = env->GetFloatArrayElements(audio, nullptr);

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.print_realtime = false;
    p.print_progress = false;
    p.print_timestamps = false;
    p.print_special = false;
    p.translate = false;
    p.language = language.c_str();
    p.n_threads = threads;
    p.offset_ms = 0;
    p.no_context = true;
    p.single_segment = false;
    // legendas curtas: quebra a cada ~64 caracteres, em limite de palavra
    p.token_timestamps = true;
    p.max_len = 64;
    p.split_on_word = true;
    // sem repetição por fallback de temperatura: tempo previsível (o padrão pode refazer um trecho até 5 vezes)
    p.temperature_inc = 0.0f;
    p.greedy.best_of = 1;
    p.abort_callback = abort_cb;
    p.abort_callback_user_data = nullptr;

    int rc = whisper_full(ctx, p, data, n);
    env->ReleaseFloatArrayElements(audio, data, JNI_ABORT);
    return rc;
}

JNIEXPORT jint JNICALL FN(nCount)(JNIEnv *, jobject, jlong ptr) {
    return whisper_full_n_segments(reinterpret_cast<whisper_context *>(ptr));
}

// texto em bytes UTF-8 (o Kotlin decodifica; evita crash com bytes inválidos no NewStringUTF)
JNIEXPORT jbyteArray JNICALL FN(nText)(JNIEnv *env, jobject, jlong ptr, jint i) {
    const char *t = whisper_full_get_segment_text(reinterpret_cast<whisper_context *>(ptr), i);
    size_t len = t ? strlen(t) : 0;
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(len));
    if (len > 0) env->SetByteArrayRegion(arr, 0, static_cast<jsize>(len), reinterpret_cast<const jbyte *>(t));
    return arr;
}

JNIEXPORT jlong JNICALL FN(nT0)(JNIEnv *, jobject, jlong ptr, jint i) {
    return whisper_full_get_segment_t0(reinterpret_cast<whisper_context *>(ptr), i);
}

JNIEXPORT jlong JNICALL FN(nT1)(JNIEnv *, jobject, jlong ptr, jint i) {
    return whisper_full_get_segment_t1(reinterpret_cast<whisper_context *>(ptr), i);
}

JNIEXPORT jfloat JNICALL FN(nNoSpeech)(JNIEnv *, jobject, jlong ptr, jint i) {
    return whisper_full_get_segment_no_speech_prob(reinterpret_cast<whisper_context *>(ptr), i);
}

JNIEXPORT jstring JNICALL FN(nLang)(JNIEnv *env, jobject, jlong ptr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    const char *s = whisper_lang_str(whisper_full_lang_id(ctx));
    return env->NewStringUTF(s ? s : "auto");
}

}  // extern "C"
