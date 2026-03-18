package org.monogram.presentation.features.chats.currentChat.editor.video

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.*
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorMatrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.monogram.presentation.R
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.*
import kotlin.math.roundToInt

data class VideoTrimRange(
    val startMs: Long = 0L,
    val endMs: Long = 0L
)

data class VideoTextElement(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val color: Color,
    val startTimeMs: Long = 0L,
    val endTimeMs: Long = -1L,
    val positionX: Float = 0.5f,
    val positionY: Float = 0.5f,
    val scale: Float = 1f,
    val rotation: Float = 0f
)

data class VideoFilter(
    @StringRes val nameRes: Int,
    val colorMatrix: ColorMatrix
)

enum class VideoQuality(val label: String, val height: Int, val bitrate: Int) {
    P144("144p", 144, 200_000),
    P240("240p", 240, 400_000),
    P360("360p", 360, 700_000),
    P480("480p", 480, 1_200_000),
    P720("720p", 720, 2_500_000),
    P1080("1080p", 1080, 5_000_000),
    ORIGINAL("Original", -1, -1);

    companion object {
        fun fromSliderValue(value: Float): VideoQuality {
            val index = (value * (entries.size - 1)).roundToInt().coerceIn(0, entries.size - 1)
            return entries[index]
        }
    }

    fun toSliderValue(): Float {
        return ordinal.toFloat() / (entries.size - 1)
    }
}

fun getPresetVideoFilters(): List<VideoFilter> {
    return listOf(
        VideoFilter(R.string.video_filter_original, ColorMatrix()),
        VideoFilter(R.string.video_filter_bw, ColorMatrix().apply { setToSaturation(0f) }),
        VideoFilter(
            R.string.video_filter_sepia, ColorMatrix(
                floatArrayOf(
                    0.393f, 0.769f, 0.189f, 0f, 0f,
                    0.349f, 0.686f, 0.168f, 0f, 0f,
                    0.272f, 0.534f, 0.131f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        ),
        VideoFilter(
            R.string.video_filter_vintage, ColorMatrix(
                floatArrayOf(
                    0.9f, 0f, 0f, 0f, 0f,
                    0f, 0.7f, 0f, 0f, 0f,
                    0f, 0f, 0.5f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        ),
        VideoFilter(
            R.string.video_filter_cool, ColorMatrix(
                floatArrayOf(
                    1f, 0f, 0f, 0f, 0f,
                    0f, 1f, 0.5f, 0f, 0f,
                    0f, 0f, 1.5f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        ),
        VideoFilter(
            R.string.video_filter_warm, ColorMatrix(
                floatArrayOf(
                    1.2f, 0f, 0f, 0f, 0f,
                    0f, 1f, 0f, 0f, 0f,
                    0f, 0f, 0.8f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        ),
        VideoFilter(
            R.string.video_filter_polaroid, ColorMatrix(
                floatArrayOf(
                    1.438f, -0.062f, -0.062f, 0f, 0f,
                    -0.122f, 1.378f, -0.122f, 0f, 0f,
                    -0.016f, -0.016f, 1.483f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        ),
        VideoFilter(
            R.string.video_filter_invert, ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
    )
}

fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

object VideoEditorUtils {
    init {
        System.loadLibrary("native-lib")
    }

    external fun processVideoNative(
        inputPath: String,
        outputPath: String,
        startMs: Long,
        endMs: Long,
        targetHeight: Int,
        bitrate: Int,
        muteAudio: Boolean,
        filterMatrix: FloatArray?
    ): Boolean
}

suspend fun processVideo(
    context: Context,
    inputPath: String,
    trimRange: VideoTrimRange = VideoTrimRange(),
    filter: VideoFilter? = null,
    textElements: List<VideoTextElement> = emptyList(),
    quality: VideoQuality = VideoQuality.P720,
    muteAudio: Boolean = false
): String = withContext(Dispatchers.IO) {

    val retriever = MediaMetadataRetriever()
    var duration = 0L
    var originalHeight = 0
    var originalWidth = 0
    var originalBitrate = 0
    var rotation = 0

    try {
        val uri = Uri.parse(inputPath)
        if (uri.scheme == "content") {
            retriever.setDataSource(context, uri)
        } else {
            retriever.setDataSource(inputPath)
        }
        duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        originalHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        originalWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        originalBitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
        rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
    } catch (e: Exception) {
        e.printStackTrace()
    } finally {
        try {
            retriever.release()
        } catch (e: Exception) {
        }
    }

    val effectiveEndMs = if (trimRange.endMs == 0L) duration else trimRange.endMs
    val isTrimmed = trimRange.startMs > 0L || (duration > 0 && effectiveEndMs < duration - 100)

    if (!isTrimmed &&
        quality == VideoQuality.ORIGINAL && !muteAudio &&
        filter == null && textElements.isEmpty()
    ) {
        return@withContext inputPath
    }

    if (!isTrimmed && quality == VideoQuality.P720 && !muteAudio && filter == null && textElements.isEmpty() &&
        inputPath.contains(context.cacheDir.absolutePath) && File(inputPath).name.startsWith("processed_video_")
    ) {
        return@withContext inputPath
    }

    val outputFile = File(context.cacheDir, "processed_video_${System.currentTimeMillis()}.mp4")

    val naturalHeight = if (rotation == 90 || rotation == 270) originalWidth else originalHeight

    val targetHeight = if (quality == VideoQuality.ORIGINAL) -1
    else if (naturalHeight > 0 && quality.height > naturalHeight) naturalHeight
    else quality.height

    val targetBitrate = if (quality == VideoQuality.ORIGINAL) -1
    else if (originalBitrate > 0 && quality.bitrate > originalBitrate) (originalBitrate * 0.9).toInt()
    else quality.bitrate

    try {
        val success = if (targetHeight > 0 || isTrimmed || muteAudio || filter != null) {
            VideoTranscoder(
                inputPath,
                outputFile.absolutePath,
                trimRange.startMs,
                effectiveEndMs,
                targetHeight,
                targetBitrate,
                muteAudio,
                rotation,
                filter?.colorMatrix?.values
            ).start()
        } else {
            VideoEditorUtils.processVideoNative(
                inputPath = inputPath,
                outputPath = outputFile.absolutePath,
                startMs = trimRange.startMs,
                endMs = if (effectiveEndMs > 0) effectiveEndMs else -1L,
                targetHeight = targetHeight,
                bitrate = targetBitrate,
                muteAudio = muteAudio,
                filterMatrix = filter?.colorMatrix?.values
            )
        }

        if (success) {
            return@withContext outputFile.absolutePath
        } else {
            return@withContext inputPath
        }

    } catch (e: Exception) {
        Log.e("VideoEditorUtils", "Error processing video", e)
        return@withContext inputPath
    }
}

private class VideoTranscoder(
    private val inputPath: String,
    private val outputPath: String,
    private val startMs: Long,
    private val endMs: Long,
    private val targetHeight: Int,
    private val targetBitrate: Int,
    private val muteAudio: Boolean,
    private val rotation: Int,
    private val filterMatrix: FloatArray?
) {
    private val timeoutUs = 10000L
    private var muxerStarted = false
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1

    fun start(): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(inputPath)

            var videoInputTrack = -1
            var audioInputTrack = -1

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoInputTrack = i
                } else if (mime.startsWith("audio/") && !muteAudio) {
                    audioInputTrack = i
                }
            }

            if (videoInputTrack == -1) return false

            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val videoFormat = extractor.getTrackFormat(videoInputTrack)
            val rawWidth = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val rawHeight = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)

            val (naturalWidth, naturalHeight) = if (rotation == 90 || rotation == 270) {
                rawHeight to rawWidth
            } else {
                rawWidth to rawHeight
            }

            val ratio = naturalWidth.toFloat() / naturalHeight.toFloat()
            val newHeight = if (targetHeight > 0) targetHeight else naturalHeight
            val newWidth = (newHeight * ratio).toInt() and 0xFFFFFFFE.toInt()

            muxer.setOrientationHint(0)

            val outputVideoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, newWidth, newHeight)
            outputVideoFormat.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            outputVideoFormat.setInteger(MediaFormat.KEY_BIT_RATE, if (targetBitrate > 0) targetBitrate else 2_000_000)
            outputVideoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            outputVideoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5)

            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(outputVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = InputSurface(encoder.createInputSurface())
            inputSurface.makeCurrent()
            encoder.start()

            extractor.selectTrack(videoInputTrack)
            val decoder = MediaCodec.createDecoderByType(videoFormat.getString(MediaFormat.KEY_MIME)!!)

            val renderer = TextureRenderer(filterMatrix)
            val surfaceTexture = SurfaceTexture(renderer.getTextureId())
            val surface = Surface(surfaceTexture)

            decoder.configure(videoFormat, surface, null, 0)
            decoder.start()

            if (startMs > 0) {
                extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var decoderDone = false
            var encoderDone = false
            var extractorDone = false

            while (!encoderDone) {
                if (!extractorDone) {
                    val inputBufIndex = decoder.dequeueInputBuffer(timeoutUs)
                    if (inputBufIndex >= 0) {
                        val inputBuf = decoder.getInputBuffer(inputBufIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuf, 0)
                        if (sampleSize < 0 || (endMs > 0 && extractor.sampleTime > endMs * 1000)) {
                            decoder.queueInputBuffer(inputBufIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            extractorDone = true
                        } else {
                            decoder.queueInputBuffer(inputBufIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                if (!decoderDone) {
                    val outIndex = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                    if (outIndex >= 0) {
                        val render = bufferInfo.size > 0 && (bufferInfo.presentationTimeUs >= startMs * 1000)
                        decoder.releaseOutputBuffer(outIndex, render)
                        if (render) {
                            surfaceTexture.updateTexImage()
                            renderer.draw(newWidth, newHeight, surfaceTexture)
                            inputSurface.setPresentationTime(bufferInfo.presentationTimeUs * 1000)
                            inputSurface.swapBuffers()
                        }
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            decoderDone = true
                            encoder.signalEndOfInputStream()
                        }
                    }
                }

                val encIndex = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (encIndex >= 0) {
                    val encodedData = encoder.getOutputBuffer(encIndex)!!
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) bufferInfo.size = 0
                    if (bufferInfo.size != 0) {
                        if (!muxerStarted) {
                            videoTrackIndex = muxer.addTrack(encoder.outputFormat)
                            if (audioInputTrack != -1) {
                                audioTrackIndex = muxer.addTrack(extractor.getTrackFormat(audioInputTrack))
                            }
                            muxer.start()
                            muxerStarted = true
                        }
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(encIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) encoderDone = true
                } else if (encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxerStarted) {
                        videoTrackIndex = muxer.addTrack(encoder.outputFormat)
                        if (audioInputTrack != -1) {
                            audioTrackIndex = muxer.addTrack(extractor.getTrackFormat(audioInputTrack))
                        }
                        muxer.start()
                        muxerStarted = true
                    }
                }
            }

            decoder.stop(); decoder.release()
            encoder.stop(); encoder.release()
            surface.release(); surfaceTexture.release()
            inputSurface.release()

            if (audioInputTrack != -1 && muxerStarted) {
                extractor.unselectTrack(videoInputTrack)
                extractor.selectTrack(audioInputTrack)
                extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val audioBuf = ByteBuffer.allocate(256 * 1024)
                while (true) {
                    val sampleSize = extractor.readSampleData(audioBuf, 0)
                    if (sampleSize < 0 || (endMs > 0 && extractor.sampleTime > endMs * 1000)) break
                    if (extractor.sampleTime >= startMs * 1000) {
                        bufferInfo.offset = 0
                        bufferInfo.size = sampleSize
                        bufferInfo.presentationTimeUs = extractor.sampleTime
                        bufferInfo.flags = extractor.sampleFlags
                        muxer.writeSampleData(audioTrackIndex, audioBuf, bufferInfo)
                    }
                    extractor.advance()
                }
            }

            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            extractor.release()
            try {
                muxer?.stop()
            } catch (e: Exception) {
            }
            muxer?.release()
        }
    }

    private class TextureRenderer(private val filterMatrix: FloatArray?) {
        private var program = 0
        private var textureId = 0
        private var uSTMatrixHandle = 0
        private var uFilterMatrixHandle = 0
        private var uFilterOffsetHandle = 0
        private val stMatrix = FloatArray(16)
        private val vertexData = floatArrayOf(-1.0f, -1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f)
        private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(vertexData.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertexData).apply { position(0) }

        private val vertexShaderCode = """
            uniform mat4 uSTMatrix;
            attribute vec4 aPosition;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = aPosition;
                vec2 texCoord = (aPosition.xy + 1.0) / 2.0;
                vTextureCoord = (uSTMatrix * vec4(texCoord, 0.0, 1.0)).xy;
            }
        """
        private val fragmentShaderCode = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            uniform mat4 uFilterMatrix;
            uniform vec4 uFilterOffset;
            void main() {
                vec4 color = texture2D(sTexture, vTextureCoord);
                gl_FragColor = uFilterMatrix * color + uFilterOffset;
            }
        """

        init {
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0); textureId = textures[0]
            GLES20.glBindTexture(0x8D65, textureId)
            GLES20.glTexParameterf(0x8D65, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST.toFloat())
            GLES20.glTexParameterf(0x8D65, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
            GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            program = createProgram(vertexShaderCode, fragmentShaderCode)
            uSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
            uFilterMatrixHandle = GLES20.glGetUniformLocation(program, "uFilterMatrix")
            uFilterOffsetHandle = GLES20.glGetUniformLocation(program, "uFilterOffset")
        }

        fun getTextureId(): Int = textureId
        fun draw(width: Int, height: Int, surfaceTexture: SurfaceTexture) {
            GLES20.glViewport(0, 0, width, height)
            GLES20.glUseProgram(program)
            surfaceTexture.getTransformMatrix(stMatrix)
            GLES20.glUniformMatrix4fv(uSTMatrixHandle, 1, false, stMatrix, 0)

            val fMatrix = FloatArray(16)
            val fOffset = FloatArray(4)
            if (filterMatrix != null) {
                for (i in 0..3) {
                    for (j in 0..3) {
                        fMatrix[j * 4 + i] = filterMatrix[i * 5 + j]
                    }
                    fOffset[i] = filterMatrix[i * 5 + 4] / 255f
                }
            } else {
                android.opengl.Matrix.setIdentityM(fMatrix, 0)
            }
            GLES20.glUniformMatrix4fv(uFilterMatrixHandle, 1, false, fMatrix, 0)
            GLES20.glUniform4fv(uFilterOffsetHandle, 1, fOffset, 0)

            val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(0x8D65, textureId)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun createProgram(vertexSource: String, fragmentSource: String): Int {
            val vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER)
                .apply { GLES20.glShaderSource(this, vertexSource); GLES20.glCompileShader(this) }
            val fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER)
                .apply { GLES20.glShaderSource(this, fragmentSource); GLES20.glCompileShader(this) }
            return GLES20.glCreateProgram()
                .apply { GLES20.glAttachShader(this, vs); GLES20.glAttachShader(this, fs); GLES20.glLinkProgram(this) }
        }
    }

    private class InputSurface(surface: Surface) {
        private var eglDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface = EGL14.EGL_NO_SURFACE

        init {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2); EGL14.eglInitialize(eglDisplay, version, 0, version, 1)
            val attribList = intArrayOf(
                EGL14.EGL_RED_SIZE,
                8,
                EGL14.EGL_GREEN_SIZE,
                8,
                EGL14.EGL_BLUE_SIZE,
                8,
                EGL14.EGL_RENDERABLE_TYPE,
                EGL14.EGL_OPENGL_ES2_BIT,
                0x3142,
                1,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0)
            eglContext = EGL14.eglCreateContext(
                eglDisplay,
                configs[0],
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0
            )
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
        }

        fun makeCurrent() {
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        }

        fun swapBuffers() {
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }

        fun setPresentationTime(nsecs: Long) {
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsecs)
        }

        fun release() {
            if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(eglDisplay, eglSurface); EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglReleaseThread(); EGL14.eglTerminate(eglDisplay)
            }
        }
    }
}
