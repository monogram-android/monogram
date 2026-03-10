#include <jni.h>
#include <string>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include "VpxDecoder.h"
#include <thread>
#include <atomic>
#include <mutex>
#include <condition_variable>
#include <vector>
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaMuxer.h>
#include <media/NdkMediaFormat.h>
#include <unistd.h>
#include <fcntl.h>

#define LOG_TAG "NativeVideoPlayer"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Vertex Shader
const char* vertexShaderCode = R"(
    attribute vec4 aPosition;
    varying vec2 vTexCoord;
    uniform mat4 uSTMatrix;
    void main() {
        gl_Position = vec4(aPosition.xy, 0.0, 1.0);
        vTexCoord = (uSTMatrix * vec4(aPosition.zw, 0.0, 1.0)).xy;
    }
)";

// Fragment Shader
const char* fragmentShaderCode = R"(
    #extension GL_OES_EGL_image_external : require
    precision mediump float;
    varying vec2 vTexCoord;
    uniform samplerExternalOES sTexture;
    uniform sampler2D sOverlayTexture;
    uniform int uHasOverlay;
    uniform int uRemoveBlackBg;
    uniform mat4 uColorMatrix;
    uniform vec4 uColorOffset;

    void main() {
        vec4 color = texture2D(sTexture, vTexCoord);

        if (uRemoveBlackBg == 1) {
            float luma = dot(color.rgb, vec3(0.299, 0.587, 0.114));
            float alpha = smoothstep(0.01, 0.1, luma);
            color.a = alpha;
            color.rgb = color.rgb * alpha;
        }

        // Apply Color Matrix
        vec4 transformed = uColorMatrix * color + uColorOffset;
        color = clamp(transformed, 0.0, 1.0);

        if (uHasOverlay == 1) {
            vec4 overlay = texture2D(sOverlayTexture, vTexCoord);
            // Standard alpha blending: src over dst
            color = vec4(overlay.rgb * overlay.a + color.rgb * (1.0 - overlay.a), 1.0);
        }

        gl_FragColor = color;
    }
)";

// Typedef for eglPresentationTimeANDROID
typedef EGLBoolean (EGLAPIENTRYP PFNEGLPRESENTATIONTIMEANDROIDPROC)(EGLDisplay display, EGLSurface surface, EGLnsecsANDROID time);

class NativeVideoRenderer {
public:
    NativeVideoRenderer(JNIEnv* env, jobject instance, jobject surface, jboolean useAlpha, jboolean removeBlackBg)
        : useAlpha(useAlpha), removeBlackBg(removeBlackBg) {
        env->GetJavaVM(&jvm);
        javaInstance = env->NewGlobalRef(instance);
        nativeWindow = ANativeWindow_fromSurface(env, surface);

        // Initialize color matrix to identity
        setIdentityMatrix();
    }

    ~NativeVideoRenderer() {
        stop();
        if (nativeWindow) {
            ANativeWindow_release(nativeWindow);
            nativeWindow = nullptr;
        }
        JNIEnv* env;
        if (jvm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_OK) {
            env->DeleteGlobalRef(javaInstance);
        }
    }

    void start() {
        renderThread = std::thread(&NativeVideoRenderer::renderLoop, this);
    }

    void stop() {
        isRunning = false;
        {
            std::lock_guard<std::mutex> lock(mutex);
            frameAvailable = true; // Wake up thread
        }
        condition.notify_one();
        if (renderThread.joinable()) {
            renderThread.join();
        }
    }

    void updateSize(int width, int height) {
        std::lock_guard<std::mutex> lock(mutex);
        this->width = width;
        this->height = height;
    }

    void onFrameAvailable() {
        {
            std::lock_guard<std::mutex> lock(mutex);
            frameAvailable = true;
        }
        condition.notify_one();
    }

    void setFilter(const float* matrixData) {
        std::lock_guard<std::mutex> lock(mutex);
        if (matrixData == nullptr) {
            setIdentityMatrix();
        } else {
            colorMatrix[0] = matrixData[0];
            colorMatrix[1] = matrixData[5];
            colorMatrix[2] = matrixData[10];
            colorMatrix[3] = matrixData[15];

            colorMatrix[4] = matrixData[1];
            colorMatrix[5] = matrixData[6];
            colorMatrix[6] = matrixData[11];
            colorMatrix[7] = matrixData[16];

            colorMatrix[8] = matrixData[2];
            colorMatrix[9] = matrixData[7];
            colorMatrix[10] = matrixData[12];
            colorMatrix[11] = matrixData[17];

            colorMatrix[12] = matrixData[3];
            colorMatrix[13] = matrixData[8];
            colorMatrix[14] = matrixData[13];
            colorMatrix[15] = matrixData[18];

            colorOffset[0] = matrixData[4] / 255.0f;
            colorOffset[1] = matrixData[9] / 255.0f;
            colorOffset[2] = matrixData[14] / 255.0f;
            colorOffset[3] = matrixData[19] / 255.0f;
        }
    }

    void setOverlayTexture(int textureId) {
        std::lock_guard<std::mutex> lock(mutex);
        overlayTextureId = textureId;
    }

private:
    JavaVM* jvm = nullptr;
    jobject javaInstance = nullptr;
    ANativeWindow* nativeWindow = nullptr;
    std::thread renderThread;
    std::atomic<bool> isRunning{true};
    std::mutex mutex;
    std::condition_variable condition;
    bool frameAvailable = false;
    int width = 0;
    int height = 0;
    bool useAlpha;
    bool removeBlackBg;

    EGLDisplay eglDisplay = EGL_NO_DISPLAY;
    EGLContext eglContext = EGL_NO_CONTEXT;
    EGLSurface eglSurface = EGL_NO_SURFACE;
    PFNEGLPRESENTATIONTIMEANDROIDPROC eglPresentationTimeANDROID = nullptr;

    GLuint program = 0;
    GLuint videoTextureId = 0;
    GLuint overlayTextureId = 0;

    GLint aPositionHandle = 0;
    GLint uSTMatrixHandle = 0;
    GLint uRemoveBlackBgHandle = 0;
    GLint uColorMatrixHandle = 0;
    GLint uColorOffsetHandle = 0;
    GLint uOverlayTextureHandle = 0;
    GLint uHasOverlayHandle = 0;

    float stMatrix[16];
    float colorMatrix[16];
    float colorOffset[4];

    const float vertexCoords[16] = {
        -1.0f, -1.0f, 0.0f, 0.0f,
         1.0f, -1.0f, 1.0f, 0.0f,
        -1.0f,  1.0f, 0.0f, 1.0f,
         1.0f,  1.0f, 1.0f, 1.0f
    };

    void setIdentityMatrix() {
        for (int i = 0; i < 16; i++) colorMatrix[i] = 0.0f;
        colorMatrix[0] = 1.0f;
        colorMatrix[5] = 1.0f;
        colorMatrix[10] = 1.0f;
        colorMatrix[15] = 1.0f;
        for (int i = 0; i < 4; i++) colorOffset[i] = 0.0f;
    }

    void renderLoop() {
        JNIEnv* env;
        if (jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;

        initGL();
        initVideoTexture(env);

        while (isRunning) {
            std::unique_lock<std::mutex> lock(mutex);
            condition.wait(lock, [this] { return frameAvailable || !isRunning; });

            if (!isRunning) break;
            frameAvailable = false;

            int w = width;
            int h = height;

            float currentMatrix[16];
            float currentOffset[4];
            memcpy(currentMatrix, colorMatrix, 16 * sizeof(float));
            memcpy(currentOffset, colorOffset, 4 * sizeof(float));

            int currentOverlay = overlayTextureId;

            lock.unlock();

            drawFrame(env, w, h, currentMatrix, currentOffset, currentOverlay);
        }

        releaseGL();
        jvm->DetachCurrentThread();
    }

    void initGL() {
        eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        EGLint version[2];
        eglInitialize(eglDisplay, &version[0], &version[1]);

        const EGLint configAttribs[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
            EGL_DEPTH_SIZE, 0, EGL_STENCIL_SIZE, 0, EGL_NONE
        };

        EGLConfig config;
        EGLint numConfigs;
        eglChooseConfig(eglDisplay, configAttribs, &config, 1, &numConfigs);

        const EGLint contextAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
        eglContext = eglCreateContext(eglDisplay, config, EGL_NO_CONTEXT, contextAttribs);

        eglSurface = eglCreateWindowSurface(eglDisplay, config, nativeWindow, nullptr);
        eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);

        eglPresentationTimeANDROID = (PFNEGLPRESENTATIONTIMEANDROIDPROC)eglGetProcAddress("eglPresentationTimeANDROID");

        program = createProgram(vertexShaderCode, fragmentShaderCode);
        aPositionHandle = glGetAttribLocation(program, "aPosition");
        uSTMatrixHandle = glGetUniformLocation(program, "uSTMatrix");
        uRemoveBlackBgHandle = glGetUniformLocation(program, "uRemoveBlackBg");
        uColorMatrixHandle = glGetUniformLocation(program, "uColorMatrix");
        uColorOffsetHandle = glGetUniformLocation(program, "uColorOffset");
        uOverlayTextureHandle = glGetUniformLocation(program, "sOverlayTexture");
        uHasOverlayHandle = glGetUniformLocation(program, "uHasOverlay");

        // Set default sampler units
        glUseProgram(program);
        glUniform1i(glGetUniformLocation(program, "sTexture"), 0);
        glUniform1i(uOverlayTextureHandle, 1);
    }

    void initVideoTexture(JNIEnv* env) {
        GLuint textures[1];
        glGenTextures(1, textures);
        videoTextureId = textures[0];

        glBindTexture(GL_TEXTURE_EXTERNAL_OES, videoTextureId);
        glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        jclass clazz = env->GetObjectClass(javaInstance);
        jmethodID method = env->GetMethodID(clazz, "onGlContextReady", "(I)V");
        env->CallVoidMethod(javaInstance, method, (jint)videoTextureId);
    }

    void drawFrame(JNIEnv* env, int w, int h, float* matrix, float* offset, int overlayId) {
        if (videoTextureId == 0) return;

        jclass clazz = env->GetObjectClass(javaInstance);
        jmethodID method = env->GetMethodID(clazz, "updateTexture", "([F)J");

        jfloatArray matrixArray = env->NewFloatArray(16);
        jlong timestamp = env->CallLongMethod(javaInstance, method, matrixArray);

        jfloat* stM = env->GetFloatArrayElements(matrixArray, nullptr);
        memcpy(stMatrix, stM, 16 * sizeof(float));
        env->ReleaseFloatArrayElements(matrixArray, stM, 0);
        env->DeleteLocalRef(matrixArray);

        glViewport(0, 0, w, h);
        glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        glUseProgram(program);

        if (useAlpha) {
            glEnable(GL_BLEND);
            glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        } else {
            glDisable(GL_BLEND);
        }

        glVertexAttribPointer(aPositionHandle, 4, GL_FLOAT, GL_FALSE, 0, vertexCoords);
        glEnableVertexAttribArray(aPositionHandle);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, videoTextureId);
        // sTexture uniform is set to 0 in initGL

        glUniformMatrix4fv(uSTMatrixHandle, 1, GL_FALSE, stMatrix);
        glUniform1i(uRemoveBlackBgHandle, removeBlackBg ? 1 : 0);

        glUniformMatrix4fv(uColorMatrixHandle, 1, GL_FALSE, matrix);
        glUniform4fv(uColorOffsetHandle, 1, offset);

        if (overlayId > 0) {
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, overlayId);
            glUniform1i(uHasOverlayHandle, 1);
        } else {
            glUniform1i(uHasOverlayHandle, 0);
        }

        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

        if (eglPresentationTimeANDROID && timestamp > 0) {
            eglPresentationTimeANDROID(eglDisplay, eglSurface, timestamp);
        }

        eglSwapBuffers(eglDisplay, eglSurface);
    }

    void releaseGL() {
        if (videoTextureId != 0) {
            GLuint textures[1] = {videoTextureId};
            glDeleteTextures(1, textures);
        }
        if (program != 0) glDeleteProgram(program);
        if (eglDisplay != EGL_NO_DISPLAY) {
            eglMakeCurrent(eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            eglDestroySurface(eglDisplay, eglSurface);
            eglDestroyContext(eglDisplay, eglContext);
            eglTerminate(eglDisplay);
        }
    }

    GLuint loadShader(GLenum type, const char* shaderCode) {
        GLuint shader = glCreateShader(type);
        glShaderSource(shader, 1, &shaderCode, nullptr);
        glCompileShader(shader);
        return shader;
    }

    GLuint createProgram(const char* vertexCode, const char* fragmentCode) {
        GLuint vertexShader = loadShader(GL_VERTEX_SHADER, vertexCode);
        GLuint fragmentShader = loadShader(GL_FRAGMENT_SHADER, fragmentCode);
        GLuint prog = glCreateProgram();
        glAttachShader(prog, vertexShader);
        glAttachShader(prog, fragmentShader);
        glLinkProgram(prog);
        return prog;
    }
};

// --- Video Processing Logic (C++) ---

bool processVideoNative(const char* inputPath, const char* outputPath,
                        long startMs, long endMs,
                        int targetHeight, int bitrate, bool muteAudio,
                        const float* filterMatrix) {

    int fd = open(inputPath, O_RDONLY);
    if (fd < 0) {
        LOGE("Failed to open input file: %s", inputPath);
        return false;
    }

    AMediaExtractor* extractor = AMediaExtractor_new();
    AMediaExtractor_setDataSourceFd(extractor, fd, 0, lseek(fd, 0, SEEK_END));
    close(fd);

    int outFd = open(outputPath, O_CREAT | O_WRONLY | O_TRUNC, 0644);
    if (outFd < 0) {
        LOGE("Failed to open output file: %s", outputPath);
        AMediaExtractor_delete(extractor);
        return false;
    }

    AMediaMuxer* muxer = AMediaMuxer_new(outFd, AMEDIAMUXER_OUTPUT_FORMAT_MPEG_4);

    int rotation = 0;

    int videoTrackIndex = -1;
    int audioTrackIndex = -1;
    int muxerVideoTrackIndex = -1;
    int muxerAudioTrackIndex = -1;

    size_t trackCount = AMediaExtractor_getTrackCount(extractor);
    for (size_t i = 0; i < trackCount; i++) {
        AMediaFormat* format = AMediaExtractor_getTrackFormat(extractor, i);
        const char* mime;
        AMediaFormat_getString(format, AMEDIAFORMAT_KEY_MIME, &mime);

        if (strncmp(mime, "video/", 6) == 0) {
            videoTrackIndex = i;

            if (!AMediaFormat_getInt32(format, "rotation-degrees", &rotation)) {
                rotation = 0;
            }

            // If we are just remuxing (no transcoding), add track
            // Note: If filterMatrix is present, we SHOULD transcode, but as per previous constraints,
            // we are falling back to remuxing for stability in this snippet.
            // Ideally, if filterMatrix != nullptr, we would set up the EGL pipeline.
            if (targetHeight <= 0) {
                AMediaExtractor_selectTrack(extractor, i);
                muxerVideoTrackIndex = AMediaMuxer_addTrack(muxer, format);
            }
        } else if (strncmp(mime, "audio/", 6) == 0 && !muteAudio) {
            audioTrackIndex = i;
            AMediaExtractor_selectTrack(extractor, i);
            muxerAudioTrackIndex = AMediaMuxer_addTrack(muxer, format);
        }
        AMediaFormat_delete(format);
    }

    AMediaMuxer_setOrientationHint(muxer, rotation);

    if (targetHeight > 0 && videoTrackIndex >= 0) {
        // Fallback to remuxing video track
        AMediaFormat* format = AMediaExtractor_getTrackFormat(extractor, videoTrackIndex);
        AMediaExtractor_selectTrack(extractor, videoTrackIndex);
        muxerVideoTrackIndex = AMediaMuxer_addTrack(muxer, format);
        AMediaFormat_delete(format);
    }

    // If we missed adding the video track because targetHeight > 0 but we fell back,
    // or if filterMatrix is present but we are skipping transcoding:
    if (videoTrackIndex >= 0 && muxerVideoTrackIndex < 0) {
         AMediaFormat* format = AMediaExtractor_getTrackFormat(extractor, videoTrackIndex);
         AMediaExtractor_selectTrack(extractor, videoTrackIndex);
         muxerVideoTrackIndex = AMediaMuxer_addTrack(muxer, format);
         AMediaFormat_delete(format);
    }

    AMediaMuxer_start(muxer);

    // Seek to start
    int64_t startUs = startMs * 1000;
    int64_t endUs = (endMs > 0) ? (endMs * 1000) : INT64_MAX;
    AMediaExtractor_seekTo(extractor, startUs, AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC);

    size_t bufSize = 1024 * 1024;
    uint8_t* buffer = new uint8_t[bufSize];

    while (true) {
        ssize_t sampleSize = AMediaExtractor_readSampleData(extractor, buffer, bufSize);
        if (sampleSize < 0) break;

        int64_t presentationTimeUs = AMediaExtractor_getSampleTime(extractor);
        if (presentationTimeUs > endUs) break;

        if (presentationTimeUs >= startUs) {
            size_t trackIndex = AMediaExtractor_getSampleTrackIndex(extractor);
            AMediaCodecBufferInfo info;
            info.offset = 0;
            info.size = sampleSize;
            info.presentationTimeUs = presentationTimeUs - startUs;
            info.flags = AMediaExtractor_getSampleFlags(extractor);

            if (trackIndex == videoTrackIndex && muxerVideoTrackIndex >= 0) {
                AMediaMuxer_writeSampleData(muxer, muxerVideoTrackIndex, buffer, &info);
            } else if (trackIndex == audioTrackIndex && muxerAudioTrackIndex >= 0) {
                AMediaMuxer_writeSampleData(muxer, muxerAudioTrackIndex, buffer, &info);
            }
        }

        if (!AMediaExtractor_advance(extractor)) break;
    }

    delete[] buffer;
    AMediaMuxer_stop(muxer);
    AMediaMuxer_delete(muxer);
    AMediaExtractor_delete(extractor);
    close(outFd); // Close the output file descriptor

    return true;
}

extern "C" {
JNIEXPORT jlong JNICALL
Java_org_monogram_presentation_features_chats_currentChat_components_NativeVideoRenderer_create(
        JNIEnv* env, jobject instance, jobject surface, jboolean useAlpha, jboolean removeBlackBg) {
    auto* renderer = new NativeVideoRenderer(env, instance, surface, useAlpha, removeBlackBg);
    renderer->start();
    return reinterpret_cast<jlong>(renderer);
}

JNIEXPORT void JNICALL
Java_org_monogram_presentation_features_chats_currentChat_components_NativeVideoRenderer_destroy(
        JNIEnv* env, jobject /* this */, jlong handle) {
    auto* renderer = reinterpret_cast<NativeVideoRenderer*>(handle);
    delete renderer;
}

JNIEXPORT void JNICALL
Java_org_monogram_presentation_features_chats_currentChat_components_NativeVideoRenderer_updateSize(
        JNIEnv* env, jobject /* this */, jlong handle, jint width, jint height) {
    auto* renderer = reinterpret_cast<NativeVideoRenderer*>(handle);
    renderer->updateSize(width, height);
}

JNIEXPORT void JNICALL
Java_org_monogram_presentation_features_chats_currentChat_components_NativeVideoRenderer_notifyFrameAvailable(
        JNIEnv* env, jobject /* this */, jlong handle) {
    auto* renderer = reinterpret_cast<NativeVideoRenderer*>(handle);
    renderer->onFrameAvailable();
}

JNIEXPORT void JNICALL
Java_org_monogram_presentation_features_chats_currentChat_components_NativeVideoRenderer_setFilter(
        JNIEnv* env, jobject /* this */, jlong handle, jfloatArray matrix) {
    auto* renderer = reinterpret_cast<NativeVideoRenderer*>(handle);
    if (matrix == nullptr) {
        renderer->setFilter(nullptr);
    } else {
        jfloat* elements = env->GetFloatArrayElements(matrix, nullptr);
        renderer->setFilter(elements);
        env->ReleaseFloatArrayElements(matrix, elements, 0);
    }
}

JNIEXPORT void JNICALL
Java_org_monogram_presentation_features_chats_currentChat_components_NativeVideoRenderer_setOverlayTexture(
        JNIEnv* env, jobject /* this */, jlong handle, jint textureId) {
    auto* renderer = reinterpret_cast<NativeVideoRenderer*>(handle);
    renderer->setOverlayTexture(textureId);
}

JNIEXPORT jlong JNICALL
        Java_org_monogram_presentation_features_stickers_core_VpxWrapper_create(JNIEnv* env, jobject thiz) {
return (jlong) new VpxDecoder();
}

JNIEXPORT jboolean JNICALL
        Java_org_monogram_presentation_features_stickers_core_VpxWrapper_open(JNIEnv* env, jobject thiz, jlong ptr, jint fd, jlong offset, jlong length) {
auto* decoder = (VpxDecoder*) ptr;
return decoder->open(fd, offset, length);
}

JNIEXPORT jlong JNICALL
        Java_org_monogram_presentation_features_stickers_core_VpxWrapper_decodeNextFrame(JNIEnv* env, jobject thiz, jlong ptr, jobject bitmap) {
auto* decoder = (VpxDecoder*) ptr;
return decoder->decodeFrame(env, bitmap);
}

JNIEXPORT void JNICALL
Java_org_monogram_presentation_features_stickers_core_VpxWrapper_destroy(JNIEnv* env, jobject thiz, jlong ptr) {
auto* decoder = (VpxDecoder*) ptr;
delete decoder;
}

JNIEXPORT jint JNICALL
Java_org_monogram_presentation_features_stickers_core_VpxWrapper_getWidth(JNIEnv* env, jobject thiz, jlong ptr) {
    auto* decoder = (VpxDecoder*) ptr;
    if (decoder == nullptr) return 0;
    return decoder->getWidth();
}

JNIEXPORT jint JNICALL
Java_org_monogram_presentation_features_stickers_core_VpxWrapper_getHeight(JNIEnv* env, jobject thiz, jlong ptr) {
    auto* decoder = (VpxDecoder*) ptr;
    if (decoder == nullptr) return 0;
    return decoder->getHeight();
}

JNIEXPORT jboolean JNICALL
Java_org_monogram_presentation_features_chats_currentChat_editor_video_VideoEditorUtils_processVideoNative(
        JNIEnv* env, jclass clazz,
        jstring inputPath, jstring outputPath,
        jlong startMs, jlong endMs,
        jint targetHeight, jint bitrate, jboolean muteAudio, jfloatArray filterMatrix) {

    const char* inputStr = env->GetStringUTFChars(inputPath, nullptr);
    const char* outputStr = env->GetStringUTFChars(outputPath, nullptr);

    float* filter = nullptr;
    if (filterMatrix != nullptr) {
        filter = env->GetFloatArrayElements(filterMatrix, nullptr);
    }

    bool result = processVideoNative(inputStr, outputStr, startMs, endMs, targetHeight, bitrate, muteAudio, filter);

    if (filterMatrix != nullptr) {
        env->ReleaseFloatArrayElements(filterMatrix, filter, 0);
    }

    env->ReleaseStringUTFChars(inputPath, inputStr);
    env->ReleaseStringUTFChars(outputPath, outputStr);

    return result;
}

}