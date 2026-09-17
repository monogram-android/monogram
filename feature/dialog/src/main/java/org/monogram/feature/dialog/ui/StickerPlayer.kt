package org.monogram.feature.dialog.ui

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
    var handle by remember { mutableStateOf(0L) }
    var bitmap by remember(lottieBytes) { mutableStateOf<Bitmap?>(null) }

    DisposableEffect(lottieBytes) {
        val created = runCatching { LottieNative.create(lottieBytes) }.getOrDefault(0L)
        handle = created
        onDispose {
            if (created != 0L) {
                LottieNative.destroy(created)
            }
        }
    }

    LaunchedEffect(handle, displaySizePx, active, animationEnabled) {
        if (handle == 0L) return@LaunchedEffect
        val frames = runCatching { LottieNative.frameCount(handle) }.getOrDefault(1).coerceAtLeast(1)
        val fps = runCatching { LottieNative.frameRate(handle) }.getOrDefault(30f).coerceAtLeast(1f)
        val size = runCatching { LottieNative.size(handle) }.getOrNull()
        val width = max(1, size?.width?.toInt() ?: displaySizePx)
        val height = max(1, size?.height?.toInt() ?: displaySizePx)
        val scale = displaySizePx.toFloat() / max(width, height).toFloat()
        val outW = max(1, (width * scale).roundToInt())
        val outH = max(1, (height * scale).roundToInt())
        suspend fun drawFrame(frameIndex: Int) {
            val bmp = withContext(Dispatchers.Default) {
                val rgba = runCatching {
                    LottieNative.renderFrame(handle, frameIndex.toFloat(), outW, outH)
                }.getOrNull()
                if (rgba == null) null else argbFrameBitmap(rgba, outW, outH)
            } ?: return
            bitmap = bmp
        }
        if (bitmap == null) drawFrame(0)
        if (!active || !animationEnabled) return@LaunchedEffect
        var frameIndex = 1 % frames
        while (isActive) {
            drawFrame(frameIndex)
            frameIndex = (frameIndex + 1) % frames
            delay((1000f / fps).toLong().coerceAtLeast(16L))
        }
    }

    // Animation invalidates drawing, not the composition containing the sticker.
    Canvas(modifier = modifier.size(displaySize)) {
        bitmap?.let { frame ->
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
