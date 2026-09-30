package org.monogram.feature.dialog.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.monogram.core.ui.components.LocalMediaAnimationEnabled
import org.monogram.core.ui.components.argbFrameBitmap
import org.monogram.mtproto.LottieNative
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun StickerPlayer(
    lottieBytes: ByteArray,
    modifier: Modifier = Modifier,
    displaySize: Dp = 192.dp,
    active: Boolean = true,
) {
    val animationEnabled = LocalMediaAnimationEnabled.current
    val displaySizePx = with(LocalDensity.current) { displaySize.roundToPx() }
    var bitmap by remember(lottieBytes, displaySizePx) { mutableStateOf<Bitmap?>(null) }
    var frameVersion by remember(lottieBytes, displaySizePx) { mutableStateOf(0) }

    LaunchedEffect(lottieBytes, displaySizePx, active, animationEnabled) {
        if (!active || !animationEnabled) return@LaunchedEffect
        CompactVideoSlots.acquire()
        var handle = 0L
        try {
            handle = withContext(Dispatchers.Default) {
                runCatching { LottieNative.create(lottieBytes) }.getOrDefault(0L)
            }
            if (handle == 0L) return@LaunchedEffect

            val frames = withContext(Dispatchers.Default) {
                runCatching { LottieNative.frameCount(handle) }.getOrDefault(1).coerceIn(1, 600)
            }
            val fps = withContext(Dispatchers.Default) {
                runCatching { LottieNative.frameRate(handle) }.getOrDefault(30f)
                    .coerceIn(1f, 60f)
            }
            val size = withContext(Dispatchers.Default) {
                runCatching { LottieNative.size(handle) }.getOrNull()
            }
            val width = max(1, size?.width?.toInt() ?: displaySizePx)
            val height = max(1, size?.height?.toInt() ?: displaySizePx)
            val scale = displaySizePx.toFloat() / max(width, height).toFloat()
            val outW = max(1, (width * scale).roundToInt())
            val outH = max(1, (height * scale).roundToInt())

            suspend fun drawFrame(frameIndex: Int): Boolean {
                val frame = withContext(Dispatchers.Default) {
                    runCatching {
                        LottieNative.renderFrame(handle, frameIndex.toFloat(), outW, outH)
                    }.getOrNull()
                } ?: return false
                val rendered = withContext(Dispatchers.Default) {
                    argbFrameBitmap(frame, outW, outH)
                } ?: return false
                bitmap = rendered
                frameVersion++
                return true
            }

            if (!drawFrame(0)) return@LaunchedEffect
            if (!active || !animationEnabled || frames <= 1) return@LaunchedEffect

            val framesPerUpdate = if (fps > 30f) 2 else 1
            val frameDelay = if (fps > 30f) 33L else (1000f / fps)
                .toLong().coerceAtLeast(24L)
            var frameIndex = framesPerUpdate % frames
            while (isActive) {
                if (!drawFrame(frameIndex)) return@LaunchedEffect
                frameIndex = (frameIndex + framesPerUpdate) % frames
                delay(frameDelay)
            }
        } finally {
            if (handle != 0L) withContext(Dispatchers.Default) { LottieNative.destroy(handle) }
            CompactVideoSlots.release()
        }
    }

    // Animation invalidates drawing, not the composition containing the sticker.
    val currentBitmap = bitmap

    @Suppress("UNUSED_VARIABLE")
    val _frameVersion = frameVersion
    Canvas(modifier = modifier.size(displaySize)) {
        currentBitmap?.let { frame ->
            val scale = minOf(size.width / frame.width, size.height / frame.height)
            val width = (frame.width * scale).roundToInt().coerceAtLeast(1)
            val height = (frame.height * scale).roundToInt().coerceAtLeast(1)
            drawImage(
                image = frame.asImageBitmap(),
                dstOffset = IntOffset(
                    ((size.width - width) / 2).roundToInt(),
                    ((size.height - height) / 2).roundToInt(),
                ),
                dstSize = IntSize(width, height),
            )
        }
    }
}
