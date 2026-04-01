package org.monogram.presentation.features.stickers.core

import org.monogram.presentation.core.util.coRunCatching
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.max

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
    @Volatile
    private var isActiveController = true
    private var frontBitmap: Bitmap? = null
    private var backBitmap: Bitmap? = null
    private var spareBitmap: Bitmap? = null
    private var decoder: RLottieWrapper? = null
    
    override fun start() {
        val previousJob = renderJob
        renderJob = scope.launch(renderDispatcher) {
            previousJob?.cancelAndJoin()
            loadAndRender()
        }
    }

    override fun setPaused(paused: Boolean) {
        isPaused = paused
    }

    override fun release() {
        isActiveController = false
        renderJob?.cancel()
        renderJob = null
        currentImageBitmap = null
    }

    override suspend fun renderFirstFrame(): ImageBitmap? = withContext(renderDispatcher) {
        val file = File(filePath)
        if (!file.exists()) return@withContext null

        val localDecoder = RLottieWrapper()
        try {
            if (!localDecoder.open(file)) {
                return@withContext null
            }

            val compositionWidth = localDecoder.getWidth().coerceAtLeast(1)
            val compositionHeight = localDecoder.getHeight().coerceAtLeast(1)
            val extraPaddingX = minOf((compositionWidth * OVERFLOW_PADDING_RATIO).toInt(), MAX_OVERFLOW_PADDING_PX)
            val extraPaddingY = minOf((compositionHeight * OVERFLOW_PADDING_RATIO).toInt(), MAX_OVERFLOW_PADDING_PX)
            val renderWidth = maxOf(reqWidth, compositionWidth + extraPaddingX * 2)
            val renderHeight = maxOf(reqHeight, compositionHeight + extraPaddingY * 2)
            val boundsLeft = (renderWidth - compositionWidth) / 2
            val boundsTop = (renderHeight - compositionHeight) / 2

            val bitmap = BitmapPool.obtain(renderWidth, renderHeight)

            bitmap.eraseColor(0)
            val rendered = localDecoder.renderFrame(
                bitmap = bitmap,
                frameNo = 0,
                drawLeft = boundsLeft,
                drawTop = boundsTop,
                drawWidth = compositionWidth,
                drawHeight = compositionHeight
            )
            if (!rendered) {
                BitmapPool.recycle(bitmap)
                return@withContext null
            }

            val imageBitmap = bitmap.asImageBitmap()
            imageBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            localDecoder.release()
        }
    }

    private suspend fun loadAndRender() {
        var fBitmap: Bitmap? = null
        var bBitmap: Bitmap? = null
        var sBitmap: Bitmap? = null

        try {
            while (isPaused && scope.isActive) {
                delay(50)
            }

            val file = File(filePath)
            if (!file.exists()) return

            val localDecoder = RLottieWrapper()
            if (!coRunCatching { localDecoder.open(file) }.getOrDefault(false)) {
                localDecoder.release()
                return
            }
            decoder = localDecoder

            val compositionWidth = localDecoder.getWidth().coerceAtLeast(1)
            val compositionHeight = localDecoder.getHeight().coerceAtLeast(1)
            val extraPaddingX = minOf((compositionWidth * OVERFLOW_PADDING_RATIO).toInt(), MAX_OVERFLOW_PADDING_PX)
            val extraPaddingY = minOf((compositionHeight * OVERFLOW_PADDING_RATIO).toInt(), MAX_OVERFLOW_PADDING_PX)
            val renderWidth = maxOf(reqWidth, compositionWidth + extraPaddingX * 2)
            val renderHeight = maxOf(reqHeight, compositionHeight + extraPaddingY * 2)
            val boundsLeft = (renderWidth - compositionWidth) / 2
            val boundsTop = (renderHeight - compositionHeight) / 2

            fBitmap = BitmapPool.obtain(renderWidth, renderHeight)
            bBitmap = BitmapPool.obtain(renderWidth, renderHeight)
            sBitmap = BitmapPool.obtain(renderWidth, renderHeight)
            val firstBitmap = requireNotNull(fBitmap)

            if (!isActiveController) {
                return
            }

            frontBitmap = fBitmap
            backBitmap = bBitmap
            spareBitmap = sBitmap

            firstBitmap.eraseColor(0)
            val firstFrameRendered = localDecoder.renderFrame(
                bitmap = firstBitmap,
                frameNo = 0,
                drawLeft = boundsLeft,
                drawTop = boundsTop,
                drawWidth = compositionWidth,
                drawHeight = compositionHeight
            )
            if (!firstFrameRendered) {
                return
            }

            currentImageBitmap = firstBitmap.copy(Bitmap.Config.ARGB_8888, false).asImageBitmap()

            val totalFrames = localDecoder.getTotalFrames().coerceAtLeast(1)
            val frameRate = localDecoder.getFrameRate().takeIf { it > 0.0 }
                ?: run {
                    val durationMs = localDecoder.getDurationMs().coerceAtLeast(1L)
                    max(totalFrames / (durationMs / 1000.0), 1.0)
                }
            val normalizedFrameRate = frameRate.coerceIn(1.0, 120.0)

            var lastFrameTime = System.nanoTime()
            val frameDurationMs = max(1L, (1000.0 / normalizedFrameRate).toLong())
            var frameAccumulator = 0.0
            var frameNo = 0

            while (isActiveController && scope.isActive) {
                val now = System.nanoTime()
                if (isPaused) {
                    delay(100)
                    lastFrameTime = System.nanoTime()
                    continue
                }

                val dtMs = (now - lastFrameTime) / 1_000_000.0
                frameAccumulator += dtMs * normalizedFrameRate / 1000.0
                val framesToAdvance = frameAccumulator.toInt()
                if (framesToAdvance <= 0) {
                    lastFrameTime = now
                    delay(1)
                    continue
                }
                frameNo = (frameNo + framesToAdvance) % totalFrames
                frameAccumulator -= framesToAdvance

                val localBackBitmap = backBitmap ?: break

                localBackBitmap.eraseColor(0)
                val rendered = localDecoder.renderFrame(
                    bitmap = localBackBitmap,
                    frameNo = frameNo,
                    drawLeft = boundsLeft,
                    drawTop = boundsTop,
                    drawWidth = compositionWidth,
                    drawHeight = compositionHeight
                )
                if (!rendered) {
                    break
                }

                val previousFront = frontBitmap
                frontBitmap = backBitmap
                backBitmap = spareBitmap
                spareBitmap = previousFront

                val localFrontBitmap = frontBitmap
                if (localFrontBitmap != null) {
                    currentImageBitmap = localFrontBitmap.copy(Bitmap.Config.ARGB_8888, false).asImageBitmap()
                    frameVersion++
                }

                lastFrameTime = now

                val workTime = (System.nanoTime() - now) / 1_000_000
                val delayTime = (frameDurationMs - workTime).coerceAtLeast(0)
                delay(delayTime)
            }
        } finally {
            withContext(NonCancellable) {
                val decoderToRelease = decoder
                decoder = null

                currentImageBitmap = null

                val bitmapsToRecycle = mutableSetOf<Bitmap>()
                frontBitmap?.let(bitmapsToRecycle::add)
                backBitmap?.let(bitmapsToRecycle::add)
                spareBitmap?.let(bitmapsToRecycle::add)
                fBitmap?.let(bitmapsToRecycle::add)
                bBitmap?.let(bitmapsToRecycle::add)
                sBitmap?.let(bitmapsToRecycle::add)

                frontBitmap = null
                backBitmap = null
                spareBitmap = null

                decoderToRelease?.release()
                for (bitmap in bitmapsToRecycle) {
                    BitmapPool.recycle(bitmap)
                }
            }
        }
    }
    
    companion object {
        private val renderDispatcher = Dispatchers.Default.limitedParallelism(8)
        private const val OVERFLOW_PADDING_RATIO = 0.20f
        private const val MAX_OVERFLOW_PADDING_PX = 96
    }
}
