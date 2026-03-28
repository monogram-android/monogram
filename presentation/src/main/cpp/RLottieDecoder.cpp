#include "RLottieDecoder.h"

#include <android/bitmap.h>
#include <algorithm>
#include <cstdint>

#include "third_party/rlottie/inc/rlottie.h"

RLottieDecoder::RLottieDecoder() = default;
RLottieDecoder::~RLottieDecoder() = default;

static inline uint32_t argbToRgba(uint32_t pixel) {
    return (pixel & 0xFF00FF00u)
        | ((pixel & 0x00FF0000u) >> 16)
        | ((pixel & 0x000000FFu) << 16);
}

static void swizzleArgbToRgba(
    uint32_t *buffer,
    int bitmapWidth,
    int bytesPerLine,
    int left,
    int top,
    int width,
    int height
) {
    if (buffer == nullptr || bitmapWidth <= 0 || bytesPerLine <= 0) {
        return;
    }

    const int stridePixels = bytesPerLine / static_cast<int>(sizeof(uint32_t));
    if (stridePixels <= 0) {
        return;
    }

    const int right = left + width;
    const int bottom = top + height;
    for (int y = top; y < bottom; y++) {
        auto *row = buffer + y * stridePixels;
        for (int x = left; x < right; x++) {
            row[x] = argbToRgba(row[x]);
        }
    }
}

bool RLottieDecoder::openFromData(
    const std::string &jsonData,
    const std::string &cacheKey,
    const std::string &resourcePath
) {
    animation = rlottie::Animation::loadFromData(jsonData, cacheKey, resourcePath, false);
    return animation != nullptr;
}

bool RLottieDecoder::renderFrame(
    JNIEnv *env,
    jobject bitmap,
    int frameNo,
    int drawLeft,
    int drawTop,
    int drawWidth,
    int drawHeight
) {
    if (animation == nullptr || bitmap == nullptr) {
        return false;
    }

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return false;
    }

    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return false;
    }

    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return false;
    }

    const auto width = static_cast<size_t>(info.width);
    const auto height = static_cast<size_t>(info.height);
    const auto bytesPerLine = static_cast<size_t>(info.stride);

    auto safeDrawLeft = std::max(0, drawLeft);
    auto safeDrawTop = std::max(0, drawTop);
    auto safeDrawWidth = std::max(1, drawWidth);
    auto safeDrawHeight = std::max(1, drawHeight);

    if (safeDrawLeft >= static_cast<int>(width) || safeDrawTop >= static_cast<int>(height)) {
        AndroidBitmap_unlockPixels(env, bitmap);
        return false;
    }

    safeDrawWidth = std::min(safeDrawWidth, static_cast<int>(width) - safeDrawLeft);
    safeDrawHeight = std::min(safeDrawHeight, static_cast<int>(height) - safeDrawTop);

    rlottie::Surface surface(
        reinterpret_cast<uint32_t *>(pixels),
        width,
        height,
        bytesPerLine
    );
    surface.setDrawRegion(
        static_cast<size_t>(safeDrawLeft),
        static_cast<size_t>(safeDrawTop),
        static_cast<size_t>(safeDrawWidth),
        static_cast<size_t>(safeDrawHeight)
    );

    auto safeFrame = std::max(0, frameNo);
    auto totalFrames = static_cast<int>(animation->totalFrame());
    if (totalFrames > 0) {
        safeFrame %= totalFrames;
    }

    animation->renderSync(static_cast<size_t>(safeFrame), surface, true);

    swizzleArgbToRgba(
        reinterpret_cast<uint32_t *>(pixels),
        static_cast<int>(width),
        static_cast<int>(bytesPerLine),
        safeDrawLeft,
        safeDrawTop,
        safeDrawWidth,
        safeDrawHeight
    );

    AndroidBitmap_unlockPixels(env, bitmap);
    return true;
}

int RLottieDecoder::getWidth() const {
    if (animation == nullptr) {
        return 0;
    }
    size_t width = 0;
    size_t height = 0;
    animation->size(width, height);
    return static_cast<int>(width);
}

int RLottieDecoder::getHeight() const {
    if (animation == nullptr) {
        return 0;
    }
    size_t width = 0;
    size_t height = 0;
    animation->size(width, height);
    return static_cast<int>(height);
}

int RLottieDecoder::getTotalFrames() const {
    if (animation == nullptr) {
        return 0;
    }
    return static_cast<int>(animation->totalFrame());
}

double RLottieDecoder::getFrameRate() const {
    if (animation == nullptr) {
        return 0.0;
    }
    return animation->frameRate();
}

long RLottieDecoder::getDurationMs() const {
    if (animation == nullptr) {
        return 0;
    }
    return static_cast<long>(animation->duration() * 1000.0);
}
