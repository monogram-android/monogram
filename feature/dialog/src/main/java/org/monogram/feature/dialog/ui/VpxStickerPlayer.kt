package org.monogram.feature.dialog.ui

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.monogram.core.ui.components.LocalMediaAnimationEnabled
import org.monogram.core.ui.components.argbFrameBitmap
import org.monogram.mtproto.VpxNative
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

@Composable
internal fun VpxStickerPlayer(
    file: File,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    val animationEnabled = LocalMediaAnimationEnabled.current
    val playable = active && animationEnabled
    var slot by remember { mutableStateOf(false) }
    val owned = remember { AtomicBoolean(false) }
    LaunchedEffect(playable) {
        if (playable) {
            if (!owned.get()) {
                val acquired = CompactVideoSlots.tryAcquire()
                owned.set(acquired)
                slot = acquired
            }
        } else if (owned.getAndSet(false)) {
            CompactVideoSlots.release()
            slot = false
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (owned.getAndSet(false)) CompactVideoSlots.release()
        }
    }
    var image by remember(file) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file, playable, slot) {
        if (!playable || !slot) return@LaunchedEffect
        withContext(Dispatchers.Default) {
            playVpxLoop(file) { frame ->
                if (!isActive) return@playVpxLoop false
                image = frame
                true
            }
        }
    }
    if (image == null) {
        VideoStill(file = file, modifier = modifier, contentScale = ContentScale.Fit)
        return
    }
    Canvas(modifier = modifier) {
        image?.let { frame ->
            val scale = minOf(size.width / frame.width, size.height / frame.height)
            val width = (frame.width * scale).roundToInt().coerceAtLeast(1)
            val height = (frame.height * scale).roundToInt().coerceAtLeast(1)
            drawImage(
                image = frame,
                dstOffset = IntOffset(
                    ((size.width - width) / 2).roundToInt(),
                    ((size.height - height) / 2).roundToInt(),
                ),
                dstSize = IntSize(width, height),
            )
        }
    }
}

private suspend fun playVpxLoop(
    file: File,
    onFrame: (ImageBitmap) -> Boolean,
) {
    val extractor = MediaExtractor()
    var handle = 0L
    try {
        extractor.setDataSource(file.absolutePath)
        val track = selectVpxTrack(extractor) ?: return
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        handle = VpxNative.create()
        val frameDelayMs = frameDelayMs(format)
        val maxInput = format.maxInputSizeOr(256 * 1024)
        val packet = ByteBuffer.allocate(maxInput)
        while (true) {
            // EOF and undecodable packets must also observe cancellation.
            currentCoroutineContext().ensureActive()
            packet.clear()
            var size = extractor.readSampleData(packet, 0)
            if (size < 0) {
                extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                continue
            }
            extractor.advance()
            val bytes = ByteArray(size)
            packet.position(0)
            packet.get(bytes)
            val frame = runCatching { VpxNative.decode(handle, bytes) }.getOrNull()
            if (frame != null && frame.rgba.isNotEmpty() && frame.width > 0u && frame.height > 0u) {
                val bitmap = argbFrameBitmap(
                    frame.rgba,
                    frame.width.toInt(),
                    frame.height.toInt(),
                )
                if (bitmap == null || !onFrame(bitmap.asImageBitmap())) return
                delay(frameDelayMs)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return
    } finally {
        if (handle != 0L) VpxNative.destroy(handle)
        extractor.release()
    }
}

private fun selectVpxTrack(extractor: MediaExtractor): Int? {
    for (index in 0 until extractor.trackCount) {
        val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
        if (mime.contains("vp8", ignoreCase = true) || mime.contains("vp9", ignoreCase = true)) {
            return index
        }
    }
    return null
}

private fun MediaFormat.maxInputSizeOr(fallback: Int): Int {
    return if (containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
        getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(16 * 1024)
    } else {
        fallback
    }
}

private fun frameDelayMs(format: MediaFormat): Long {
    val fps = runCatching {
        if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
        } else {
            30f
        }
    }.getOrDefault(30f).coerceAtLeast(1f)
    return (1000f / fps).toLong().coerceIn(16L, 80L)
}
