package org.monogram.presentation.features.stickers.core

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.airbnb.lottie.LottieFeatureFlag
import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.GZIPInputStream

class LottieStickerController(
    private val filePath: String,
    private val scope: CoroutineScope,
    private val reqWidth: Int = 512,
    private val reqHeight: Int = 512
) : StickerController {

    override var currentImageBitmap by mutableStateOf<ImageBitmap?>(null)
        private set

    override var frameVersion by mutableLongStateOf(0L)
        private set

    private var renderJob: Job? = null
    private var isPaused = false
    private var isActiveController = true
    private var frontBitmap: Bitmap? = null
    private var backBitmap: Bitmap? = null
    
    override fun start() {
        renderJob?.cancel()
        renderJob = scope.launch(renderDispatcher) {
            loadAndRender()
        }
    }

    override fun setPaused(paused: Boolean) {
        isPaused = paused
    }

    override fun release() {
        isActiveController = false
        renderJob?.cancel()
        scope.launch(renderDispatcher) {
            renderJob?.join()

            frontBitmap?.let { BitmapPool.recycle(it) }
            backBitmap?.let { BitmapPool.recycle(it) }
            frontBitmap = null
            backBitmap = null
        }
    }

    override suspend fun renderFirstFrame(): ImageBitmap? = withContext(renderDispatcher) {
        val file = File(filePath)
        if (!file.exists()) return@withContext null

        try {
            val fis = FileInputStream(file)
            val bis = BufferedInputStream(fis)
            bis.mark(4)
            val magic = bis.read() or (bis.read() shl 8)
            bis.reset()

            val isGzip = magic == GZIPInputStream.GZIP_MAGIC
            val inputStream = if (isGzip) GZIPInputStream(bis) else bis

            val compositionResult = LottieCompositionFactory.fromJsonInputStreamSync(inputStream, filePath)
            val composition = compositionResult.value ?: return@withContext null

            val bitmap = BitmapPool.obtain(reqWidth, reqHeight)

            val drawable = LottieDrawable().apply {
                enableFeatureFlag(LottieFeatureFlag.MergePathsApi19, true)
            }
            drawable.composition = composition
            drawable.setBounds(0, 0, reqWidth, reqHeight)
            drawable.progress = 0f

            val canvas = Canvas(bitmap)
            bitmap.eraseColor(0)
            drawable.draw(canvas)

            val imageBitmap = bitmap.asImageBitmap()
            imageBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private suspend fun loadAndRender() {
        while (isPaused && scope.isActive) {
            delay(50)
        }

        val file = File(filePath)
        if (!file.exists()) return

        val composition: LottieComposition = try {
            val fis = FileInputStream(file)
            val bis = BufferedInputStream(fis)
            bis.mark(4)
            val magic = bis.read() or (bis.read() shl 8)
            bis.reset()

            val isGzip = magic == GZIPInputStream.GZIP_MAGIC
            val inputStream = if (isGzip) GZIPInputStream(bis) else bis

            val compositionResult = LottieCompositionFactory.fromJsonInputStreamSync(inputStream, filePath)
            compositionResult.value ?: return
        } catch (e: Exception) {
            e.printStackTrace()
            return
        }

        val fBitmap = BitmapPool.obtain(reqWidth, reqHeight)
        val bBitmap = BitmapPool.obtain(reqWidth, reqHeight)

        if (!isActiveController) {
             BitmapPool.recycle(fBitmap)
             BitmapPool.recycle(bBitmap)
             return
        }

        frontBitmap = fBitmap
        backBitmap = bBitmap

        val drawable = LottieDrawable().apply {
            enableFeatureFlag(LottieFeatureFlag.MergePathsApi19, true)
        }
        drawable.composition = composition
        drawable.repeatCount = LottieDrawable.INFINITE
        drawable.setBounds(0, 0, reqWidth, reqHeight)

        val frontCanvas = Canvas(fBitmap)
        fBitmap.eraseColor(0)
        drawable.draw(frontCanvas)

        currentImageBitmap = fBitmap.asImageBitmap()

        var lastFrameTime = System.nanoTime()
        val frameDurationMs = (1000f / composition.frameRate).toLong()

        while (isActiveController && scope.isActive) {
            val now = System.nanoTime()
            if (isPaused) {
                delay(100)
                lastFrameTime = System.nanoTime()
                continue
            }

            val dt = (now - lastFrameTime) / 1_000_000f // ms

            val currentProgress = drawable.progress
            val advance = dt / composition.duration
            drawable.progress = (currentProgress + advance) % 1f
            
            val localBackBitmap = backBitmap ?: break

            localBackBitmap.eraseColor(0)
            val canvas = Canvas(localBackBitmap)
            drawable.draw(canvas)
            
            val temp = frontBitmap
            frontBitmap = backBitmap
            backBitmap = temp

            val localFrontBitmap = frontBitmap
            if (localFrontBitmap != null) {
                currentImageBitmap = localFrontBitmap.asImageBitmap()
                frameVersion++
            }
            
            lastFrameTime = now
            
            val workTime = (System.nanoTime() - now) / 1_000_000
            val delayTime = (frameDurationMs - workTime).coerceAtLeast(0)
            delay(delayTime)
        }
    }
    
    companion object {
        private val renderDispatcher = Dispatchers.Default.limitedParallelism(8)
    }
}