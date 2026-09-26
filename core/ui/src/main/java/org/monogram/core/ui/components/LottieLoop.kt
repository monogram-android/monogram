package org.monogram.core.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.monogram.mtproto.LottieNative
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun LottieLoop(
    bytes: ByteArray,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val displayPx = with(LocalDensity.current) { size.roundToPx().coerceAtLeast(1) }
    var handle by remember(bytes) { mutableStateOf(0L) }
    var bitmap by remember(bytes) { mutableStateOf<Bitmap?>(null) }
    val animationEnabled = LocalMediaAnimationEnabled.current
    DisposableEffect(bytes) {
        val created = runCatching { LottieNative.create(bytes) }.getOrDefault(0L)
        handle = created
        onDispose {
            if (created != 0L) {
                LottieNative.destroy(created)
            }
        }
    }
    LaunchedEffect(handle, displayPx, animationEnabled) {
        if (handle == 0L) return@LaunchedEffect
        val frames = runCatching { LottieNative.frameCount(handle) }.getOrDefault(1).coerceAtLeast(1)
        val fps = runCatching { LottieNative.frameRate(handle) }.getOrDefault(30f).coerceAtLeast(1f)
        val sizeInfo = runCatching { LottieNative.size(handle) }.getOrNull()
        val width = max(1, sizeInfo?.width?.toInt() ?: displayPx)
        val height = max(1, sizeInfo?.height?.toInt() ?: displayPx)
        val scale = displayPx.toFloat() / max(width, height).toFloat()
        val outW = max(1, (width * scale).roundToInt())
        val outH = max(1, (height * scale).roundToInt())
        var frameIndex = 0
        while (isActive) {
            val bmp = withContext(Dispatchers.Default) {
                val rgba = runCatching {
                    LottieNative.renderFrame(handle, frameIndex.toFloat(), outW, outH)
                }.getOrNull()
                if (rgba == null) null else argbFrameBitmap(rgba, outW, outH)
            }
            if (bmp != null) bitmap = bmp
            frameIndex = (frameIndex + 1) % frames
            if (!animationEnabled) return@LaunchedEffect
            delay((1000f / fps).toLong().coerceAtLeast(16L))
        }
    }
    // Read frame state only during drawing; animation must not recompose its owner.
    Canvas(modifier = modifier.size(size)) {
        bitmap?.let { frame ->
            val scale = minOf(this.size.width / frame.width, this.size.height / frame.height)
            val width = (frame.width * scale).roundToInt().coerceAtLeast(1)
            val height = (frame.height * scale).roundToInt().coerceAtLeast(1)
            drawImage(
                image = frame.asImageBitmap(),
                dstOffset = IntOffset(
                    ((this.size.width - width) / 2).roundToInt(),
                    ((this.size.height - height) / 2).roundToInt(),
                ),
                dstSize = IntSize(width, height),
            )
        }
    }
}
