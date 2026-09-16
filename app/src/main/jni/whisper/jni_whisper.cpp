// SPDX-License-Identifier: GPL-3.0-only
#include <jni.h>
#include <string>
#include <android/log.h>
#include "whisper.h"

#define TAG "JNI_Whisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_helium314_keyboard_latin_voice_WhisperEngine_initContext(
        JNIEnv *env,
        jobject /* this */,
        jstring jModelPath) {
    if (jModelPath == nullptr) {
        LOGE("Model path is null");
        return 0;
    }

    const char *model_path = env->GetStringUTFChars(jModelPath, nullptr);
    if (!model_path) {
        LOGE("Failed to get model path UTF chars");
        return 0;
    }

    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false; // CPU inference with ARM NEON for broad compatibility

    struct whisper_context *ctx = whisper_init_from_file_with_params(model_path, cparams);
    env->ReleaseStringUTFChars(jModelPath, model_path);

    if (!ctx) {
        LOGE("whisper_init_from_file_with_params returned null");
        return 0;
    }

    LOGI("Whisper context initialized successfully");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_helium314_keyboard_latin_voice_WhisperEngine_freeContext(
        JNIEnv * /* env */,
        jobject /* this */,
        jlong contextPtr) {
    if (contextPtr == 0) {
        return;
    }

    auto *ctx = reinterpret_cast<struct whisper_context *>(contextPtr);
    whisper_free(ctx);
    LOGI("Whisper context freed");
}

JNIEXPORT jstring JNICALL
Java_helium314_keyboard_latin_voice_WhisperEngine_fullTranscribe(
        JNIEnv *env,
        jobject /* this */,
        jlong contextPtr,
        jint numThreads,
        jfloatArray audioData) {
    if (contextPtr == 0) {
        LOGE("Context pointer is null in fullTranscribe");
        return nullptr;
    }

    if (audioData == nullptr) {
        LOGE("audioData array is null in fullTranscribe");
        return nullptr;
    }

    auto *ctx = reinterpret_cast<struct whisper_context *>(contextPtr);
    jsize n_samples = env->GetArrayLength(audioData);
    if (n_samples <= 0) {
        return env->NewStringUTF("");
    }

    jfloat *samples = env->GetFloatArrayElements(audioData, nullptr);
    if (!samples) {
        LOGE("Failed to get float array elements");
        return nullptr;
    }

    struct whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.n_threads = (numThreads > 0) ? numThreads : 2;
    wparams.print_progress = false;
    wparams.print_special = false;
    wparams.print_realtime = false;
    wparams.print_timestamps = false;
    wparams.translate = false;
    wparams.no_context = true;
    wparams.single_segment = true;
    wparams.suppress_blank = true;
    wparams.suppress_non_speech_tokens = true;

    // Dynamic Audio Context (audio_ctx) optimization:
    // Whisper standard window is 1500 frames (30s at 160 samples/frame).
    // Calculate actual frames needed based on audio sample duration + margin.
    // Clamped between 128 (min threshold) and 1500 (max 30s window).
    int dynamic_ctx = (n_samples / 160) + 32;
    if (dynamic_ctx < 128) dynamic_ctx = 128;
    if (dynamic_ctx > 1500) dynamic_ctx = 1500;
    wparams.audio_ctx = dynamic_ctx;

    int ret = whisper_full(ctx, wparams, samples, n_samples);
    env->ReleaseFloatArrayElements(audioData, samples, JNI_ABORT);

    if (ret != 0) {
        LOGE("whisper_full inference failed with error code: %d", ret);
        return nullptr;
    }

    std::string resultText;
    const int n_segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_segments; ++i) {
        const char *seg_text = whisper_full_get_segment_text(ctx, i);
        if (seg_text != nullptr) {
            resultText += seg_text;
        }
    }

    return env->NewStringUTF(resultText.c_str());
}

// JNI bindings for com.example.ime.voice.WhisperEngine
JNIEXPORT jlong JNICALL
Java_com_example_ime_voice_WhisperEngine_initContext(
        JNIEnv *env,
        jobject thiz,
        jstring jModelPath) {
    return Java_helium314_keyboard_latin_voice_WhisperEngine_initContext(env, thiz, jModelPath);
}

JNIEXPORT void JNICALL
Java_com_example_ime_voice_WhisperEngine_freeContext(
        JNIEnv *env,
        jobject thiz,
        jlong contextPtr) {
    Java_helium314_keyboard_latin_voice_WhisperEngine_freeContext(env, thiz, contextPtr);
}

JNIEXPORT jstring JNICALL
Java_com_example_ime_voice_WhisperEngine_fullTranscribe(
        JNIEnv *env,
        jobject thiz,
        jlong contextPtr,
        jint numThreads,
        jfloatArray audioData) {
    return Java_helium314_keyboard_latin_voice_WhisperEngine_fullTranscribe(env, thiz, contextPtr, numThreads, audioData);
}

} // extern "C"
