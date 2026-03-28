#include <jni.h>

#include <string>

#include "RLottieDecoder.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_org_monogram_presentation_features_stickers_core_RLottieWrapper_create(JNIEnv *, jobject) {
    return reinterpret_cast<jlong>(new RLottieDecoder());
}

JNIEXPORT jboolean JNICALL
Java_org_monogram_presentation_features_stickers_core_RLottieWrapper_openFromData(
    JNIEnv *env,
    jobject,
    jlong ptr,
    jstring json,
    jstring cacheKey,
    jstring resourcePath
) {
    auto *decoder = reinterpret_cast<RLottieDecoder *>(ptr);
    if (decoder == nullptr || json == nullptr || cacheKey == nullptr || resourcePath == nullptr) {
        return JNI_FALSE;
    }

    const char *jsonChars = env->GetStringUTFChars(json, nullptr);
    const char *keyChars = env->GetStringUTFChars(cacheKey, nullptr);
    const char *pathChars = env->GetStringUTFChars(resourcePath, nullptr);

    const std::string jsonData(jsonChars != nullptr ? jsonChars : "");
    const std::string keyData(keyChars != nullptr ? keyChars : "");
    const std::string pathData(pathChars != nullptr ? pathChars : "");

    if (jsonChars != nullptr) env->ReleaseStringUTFChars(json, jsonChars);
    if (keyChars != nullptr) env->ReleaseStringUTFChars(cacheKey, keyChars);
    if (pathChars != nullptr) env->ReleaseStringUTFChars(resourcePath, pathChars);

    return decoder->openFromData(jsonData, keyData, pathData) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_monogram_presentation_features_stickers_core_RLottieWrapper_renderFrame(
    JNIEnv *env,
    jobject,
    jlong ptr,
    jobject bitmap,
    jint frameNo,
    jint drawLeft,
    jint drawTop,
    jint drawWidth,
    jint drawHeight
) {
    auto *decoder = reinterpret_cast<RLottieDecoder *>(ptr);
    if (decoder == nullptr) {
        return JNI_FALSE;
    }

    return decoder->renderFrame(env, bitmap, frameNo, drawLeft, drawTop, drawWidth, drawHeight)
        ? JNI_TRUE
        : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_org_monogram_presentation_features_stickers_core_RLottieWrapper_getWidth(JNIEnv *, jobject, jlong ptr) {
    auto *decoder = reinterpret_cast<RLottieDecoder *>(ptr);
    return decoder == nullptr ? 0 : decoder->getWidth();
}

JNIEXPORT jint JNICALL
Java_org_monogram_presentation_features_stickers_core_RLottieWrapper_getHeight(JNIEnv *, jobject, jlong ptr) {
    auto *decoder = reinterpret_cast<RLottieDecoder *>(ptr);
    return decoder == nullptr ? 0 : decoder->getHeight();
}

JNIEXPORT jint JNICALL
Java_org_monogram_presentation_features_stickers_core_RLottieWrapper_getTotalFrames(JNIEnv *, jobject, jlong ptr) {
    auto *decoder = reinterpret_cast<RLottieDecoder *>(ptr);
    return decoder == nullptr ? 0 : decoder->getTotalFrames();
}

JNIEXPORT jdouble JNICALL
Java_org_monogram_presentation_features_stickers_core_RLottieWrapper_getFrameRate(JNIEnv *, jobject, jlong ptr) {
    auto *decoder = reinterpret_cast<RLottieDecoder *>(ptr);
    return decoder == nullptr ? 0.0 : decoder->getFrameRate();
}

JNIEXPORT jlong JNICALL
Java_org_monogram_presentation_features_stickers_core_RLottieWrapper_getDurationMs(JNIEnv *, jobject, jlong ptr) {
    auto *decoder = reinterpret_cast<RLottieDecoder *>(ptr);
    return decoder == nullptr ? 0 : decoder->getDurationMs();
}

JNIEXPORT void JNICALL
Java_org_monogram_presentation_features_stickers_core_RLottieWrapper_destroy(JNIEnv *, jobject, jlong ptr) {
    auto *decoder = reinterpret_cast<RLottieDecoder *>(ptr);
    delete decoder;
}

}
