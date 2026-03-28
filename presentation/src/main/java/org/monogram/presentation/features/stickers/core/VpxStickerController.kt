package org.monogram.presentation.features.stickers.core

import android.graphics.Bitmap
import android.os.Process
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class VpxStickerController(
    private val filePath: String,
    private val scope: CoroutineScope,
) : StickerController {
    private var frontBitmap: Bitmap? = null
    private var backBitmap: Bitmap? = null
    
    override var currentImageBitmap by mutableStateOf<ImageBitmap?>(null)
        private set

    override var frameVersion by mutableLongStateOf(0L)
        private set

    private var decoder: VpxWrapper? = null
    private var isActive = true
    private var isPaused = false
    private val decoderMutex = Mutex()
    private var renderJob: Job? = null

    override fun start() {
        val previousJob = renderJob
        renderJob = scope.launch(Dispatchers.IO) {
            previousJob?.cancelAndJoin()
            initializeAndLoop()
        }
    }

    override fun setPaused(paused: Boolean) {
        isPaused = paused
    }

    private suspend fun initializeAndLoop() {
        while (isPaused && scope.isActive) {
            delay(50)
        }

        val tempDecoder = VpxWrapper()
        val file = File(filePath)

        if (!file.exists()) {
            return
        }

        val opened = try {
            tempDecoder.open(file)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }

        if (!opened) {
            tempDecoder.release()
            return
        }

        val (w, h) = decoderMutex.withLock {
            if (!isActive) {
                tempDecoder.release()
                return
            }
            decoder = tempDecoder
            Pair(tempDecoder.getVideoWidth(), tempDecoder.getVideoHeight())
        }

        if (w <= 0 || h <= 0) {
            release()
            return
        }

        try {
            frontBitmap = BitmapPool.obtain(w, h)
            backBitmap = BitmapPool.obtain(w, h)
        } catch (_: OutOfMemoryError) {
            release()
            return
        }

        decoderMutex.withLock {
            decoder?.renderFrame(frontBitmap!!)
        }
        currentImageBitmap = frontBitmap!!.asImageBitmap()

        withContext(Dispatchers.Default) {
            renderLoop()
        }
    }

    private suspend fun renderLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)

        while (isActive) {
            val startTime = System.currentTimeMillis()
            var delayMs: Long = -1

            decoderMutex.withLock {
                val localDecoder = decoder
                if (isActive && localDecoder != null) {
                    try {
                        delayMs = localDecoder.renderFrame(backBitmap!!)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            if (delayMs == -2L) {
                awaitCancellation()
            }

            if (!isActive) break

            if (isPaused) {
                delay(100)
                continue
            }

            if (delayMs < 0) {
                delay(50)
                continue
            }

            val temp = frontBitmap
            frontBitmap = backBitmap
            backBitmap = temp

            currentImageBitmap = frontBitmap!!.asImageBitmap()
            frameVersion++

            val workTime = System.currentTimeMillis() - startTime
            val sleepTime = (delayMs - workTime).coerceAtLeast(0)
            delay(sleepTime)
        }

        currentImageBitmap = null
    }

    override fun release() {
        isActive = false
        renderJob?.cancel()
        currentImageBitmap = null

        scope.launch(Dispatchers.IO) {
            renderJob?.join()

            decoderMutex.withLock {
                try {
                    decoder?.release()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                decoder = null
            }
            frontBitmap?.let { BitmapPool.recycle(it) }
            backBitmap?.let { BitmapPool.recycle(it) }
            frontBitmap = null
            backBitmap = null
        }
    }

    override suspend fun renderFirstFrame(): ImageBitmap? = withContext(Dispatchers.IO) {
        val file = File(filePath)
        if (!file.exists()) return@withContext null

        val tempDecoder = VpxWrapper()
        try {
            if (!tempDecoder.open(file)) return@withContext null

            val w = tempDecoder.getVideoWidth()
            val h = tempDecoder.getVideoHeight()
            if (w <= 0 || h <= 0) return@withContext null

            val bitmap = BitmapPool.obtain(w, h)
            tempDecoder.renderFrame(bitmap)

            bitmap.asImageBitmap()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            try {
                tempDecoder.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}