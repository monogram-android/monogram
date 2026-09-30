package org.monogram.feature.dialog.ui

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.monogram.core.ui.components.LocalMediaAnimationEnabled
import org.monogram.core.ui.components.argbFrameBitmap
import org.monogram.mtproto.VpxNative
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

private object VpxStickerSlots {
    private const val MAX = 8
    private val playing = AtomicInteger(0)

    fun tryAcquire(): Boolean {
        while (true) {
            val current = playing.get()
            if (current >= MAX) return false
            if (playing.compareAndSet(current, current + 1)) return true
        }
    }

    suspend fun acquire() {
        while (true) {
            currentCoroutineContext().ensureActive()
            if (tryAcquire()) return
            delay(50L)
        }
    }

    fun release() {
        playing.updateAndGet { value -> (value - 1).coerceAtLeast(0) }
    }
}
@Composable
internal fun VpxStickerPlayer(
    file: File,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    val animationEnabled = LocalMediaAnimationEnabled.current
    val playable = active && animationEnabled
    var image by remember(file.absolutePath) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file.absolutePath, playable) {
        if (!playable) return@LaunchedEffect

        VpxStickerSlots.acquire()
        try {
            withContext(Dispatchers.Default) {
                playVpxLoop(file) { frame ->
                    if (!isActive) return@playVpxLoop false
                    image = frame
                    true
                }
            }
        } finally {
            VpxStickerSlots.release()
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
    if (runCatching { MatroskaAlphaReader(file).hasAlpha }.getOrDefault(false)) {
        playAlphaVpxLoop(file, onFrame)
    } else {
        playExtractorVpxLoop(file, onFrame)
    }
}

private suspend fun playAlphaVpxLoop(file: File, onFrame: (ImageBitmap) -> Boolean) {
    var colorHandle = 0L
    var alphaHandle = 0L
    try {
        val reader = MatroskaAlphaReader(file)
        colorHandle = VpxNative.create()
        alphaHandle = VpxNative.create()
        while (true) {
            var rendered = false
            for (sample in reader.samples()) {
                currentCoroutineContext().ensureActive()
                val color = runCatching { VpxNative.decode(colorHandle, sample.color) }.getOrNull()
                val alpha = sample.alpha?.let {
                    runCatching {
                        VpxNative.decodeAlpha(
                            alphaHandle,
                            it
                        )
                    }.getOrNull()
                }
                if (color == null || color.rgba.isEmpty()) continue
                if (alpha != null && (alpha.width != color.width || alpha.height != color.height || alpha.alpha.size != color.width.toInt() * color.height.toInt())) continue
                if (alpha != null) for (i in alpha.alpha.indices) color.rgba[i * 4 + 3] =
                    alpha.alpha[i]
                val bitmap = argbFrameBitmap(color.rgba, color.width.toInt(), color.height.toInt())
                    ?: continue
                rendered = true
                if (!onFrame(bitmap.asImageBitmap())) return
                delay(33L)
            }
            if (!rendered) return
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return
    } finally {
        if (alphaHandle != 0L) VpxNative.destroy(alphaHandle)
        if (colorHandle != 0L) VpxNative.destroy(colorHandle)
    }
}

private suspend fun playExtractorVpxLoop(file: File, onFrame: (ImageBitmap) -> Boolean) {
    val extractor = MediaExtractor()
    var handle = 0L
    try {
        extractor.setDataSource(file.absolutePath)
        val track = selectVpxTrack(extractor) ?: return
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        handle = VpxNative.create()
        val packet = ByteBuffer.allocate(format.maxInputSizeOr(256 * 1024))
        var badPackets = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            packet.clear()
            val size = extractor.readSampleData(packet, 0)
            if (size < 0) {
                extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC); badPackets = 0; continue
            }
            extractor.advance()
            val bytes = ByteArray(size)
            packet.position(0); packet.get(bytes)
            val frame = runCatching { VpxNative.decode(handle, bytes) }.getOrNull()
            if (frame != null && frame.rgba.isNotEmpty() && frame.width > 0u && frame.height > 0u) {
                badPackets = 0
                val bitmap = argbFrameBitmap(frame.rgba, frame.width.toInt(), frame.height.toInt())
                if (bitmap == null || !onFrame(bitmap.asImageBitmap())) return
                delay(frameDelayMs(format))
            } else {
                badPackets++
                if (badPackets >= 120) return
                delay(8L)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return
    } finally {
        if (handle != 0L) VpxNative.destroy(handle); extractor.release()
    }
}

data class AlphaSample(val color: ByteArray, val alpha: ByteArray?)

private class MatroskaAlphaReader(file: File) {
    private val data = run { require(file.length() <= 64L * 1024 * 1024); file.readBytes() }
    val hasAlpha: Boolean
    private val parsedSamples: List<AlphaSample>

    init {
        val result = EbmlParser(data).parse()
        hasAlpha = result.alphaMode
        parsedSamples = result.samples
    }

    fun samples(): List<AlphaSample> = parsedSamples
}

private class EbmlParser(private val data: ByteArray) {
    data class Result(val alphaMode: Boolean, val samples: List<AlphaSample>)
    private data class Block(val payload: ByteArray, val alpha: ByteArray?)

    private var alphaMode = false
    private val samples = ArrayList<AlphaSample>()
    fun parse(): Result {
        walk(0, data.size, 0)
        return Result(alphaMode, samples)
    }

    private fun walk(begin: Int, end: Int, depth: Int) {
        if (depth > 12) return
        var p = begin
        while (p < end) {
            val id = readId(p) ?: return
            p += id.second
            val size = readVint(p) ?: return
            p += size.second
            val stop = if (size.first < 0) end else minOf(
                end,
                p + size.first.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            )
            when (id.first) {
                0x53C0L -> if (p < stop && data[p].toInt() != 0) alphaMode = true
                0xA0L -> parseBlockGroup(p, stop, depth + 1)
                0x1654AE6BL, 0x1F43B675L, 0x18538067L, 0xAEL, 0xE0L -> walk(p, stop, depth + 1)
            }
            p = stop
        }
    }

    private fun parseBlockGroup(begin: Int, end: Int, depth: Int) {
        var color: ByteArray? = null;
        var alpha: ByteArray? = null;
        var p = begin
        while (p < end) {
            val id = readId(p) ?: return; p += id.second;
            val size = readVint(p) ?: return; p += size.second
            val stop = minOf(
                end,
                p + if (size.first < 0) end - p else size.first.coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
            )
            when (id.first) {
                0xA1L -> color = blockData(p, stop)
                0x75A1L -> parseAdditions(p, stop).also { alpha = it }
            }
            p = stop
        }
        color?.let { samples += AlphaSample(it, alpha) }
    }

    private fun parseAdditions(begin: Int, end: Int): ByteArray? {
        var p = begin
        while (p < end) {
            val id = readId(p) ?: return null; p += id.second;
            val size = readVint(p) ?: return null; p += size.second
            val stop = minOf(
                end,
                p + if (size.first < 0) end - p else size.first.coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
            )
            if (id.first == 0xA6L) {
                var q = p;
                var addId = 0L;
                var payload: ByteArray? = null
                while (q < stop) {
                    val child = readId(q) ?: break; q += child.second;
                    val cs = readVint(q) ?: break; q += cs.second;
                    val ce = minOf(
                        stop,
                        q + if (cs.first < 0) stop - q else cs.first.coerceAtMost(Int.MAX_VALUE.toLong())
                            .toInt()
                    ); if (child.first == 0xEEL) addId =
                        number(q, ce); if (child.first == 0xA5L) payload =
                        data.copyOfRange(q, ce); q = ce
                }
                if (addId == 1L) return payload
            }
            p = stop
        }
        return null
    }

    private fun blockData(begin: Int, end: Int): ByteArray? {
        val h = readVint(begin)
            ?: return null; return if (begin + h.second + 3 <= end) data.copyOfRange(
            begin + h.second + 3,
            end
        ) else null
    }

    private fun number(a: Int, b: Int): Long {
        var n = 0L; for (i in a until b) n = (n shl 8) or (data[i].toLong() and 255); return n
    }

    private fun readId(p: Int): Pair<Long, Int>? {
        if (p >= data.size) return null;
        val v = data[p].toInt() and 255;
        val n = when {
            v and 0x80 != 0 -> 1; v and 0x40 != 0 -> 2; v and 0x20 != 0 -> 3; v and 0x10 != 0 -> 4; else -> return null
        }; if (p + n > data.size) return null; return number(p, p + n) to n
    }

    private fun readVint(p: Int): Pair<Long, Int>? {
        if (p >= data.size) return null;
        val v = data[p].toInt() and 255;
        val n = when {
            v and 0x80 != 0 -> 1; v and 0x40 != 0 -> 2; v and 0x20 != 0 -> 3; v and 0x10 != 0 -> 4; v and 0x08 != 0 -> 5; v and 0x04 != 0 -> 6; v and 0x02 != 0 -> 7; v and 0x01 != 0 -> 8; else -> return null
        }; if (p + n > data.size) return null;
        var x = (v and ((1 shl (8 - n)) - 1)).toLong(); for (i in 1 until n) x =
            (x shl 8) or (data[p + i].toLong() and 255); return (if (x == (1L shl (7 * n)) - 1) -1 else x) to n
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
    val requested = if (containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
        runCatching { getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }.getOrDefault(fallback)
    } else {
        fallback
    }
    return requested.coerceIn(16 * 1024, 4 * 1024 * 1024)
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
