#ifndef MONOGRAM_RLOTTIEDECODER_H
#define MONOGRAM_RLOTTIEDECODER_H

#include <jni.h>
#include <memory>
#include <string>

namespace rlottie {
class Animation;
}

class RLottieDecoder {
public:
    RLottieDecoder();
    ~RLottieDecoder();

    bool openFromData(const std::string &jsonData, const std::string &cacheKey, const std::string &resourcePath);
    bool renderFrame(JNIEnv *env, jobject bitmap, int frameNo, int drawLeft, int drawTop, int drawWidth, int drawHeight);

    int getWidth() const;
    int getHeight() const;
    int getTotalFrames() const;
    double getFrameRate() const;
    long getDurationMs() const;

private:
    std::unique_ptr<rlottie::Animation> animation;
};

#endif
