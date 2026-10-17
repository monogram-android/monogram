package org.monogram.feature.dialog.ui

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.monogram.core.ui.components.LocalMediaAnimationEnabled
import org.monogram.core.ui.components.argbFrameBitmap
import org.monogram.feature.dialog.PanelFrameClip
import org.monogram.feature.dialog.PanelTag
import org.monogram.feature.dialog.scaleRgba
import org.monogram.feature.dialog.scaledSize
import org.monogram.feature.dialog.webmHasAlpha
import org.monogram.mtproto.VpxNative
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

internal data class VpxDecodedFrame(
    val width: Int,
    val height: Int,
    val rgba: ByteArray,
)

internal data class VpxDecodedAlpha(
    val width: Int,
    val height: Int,
    val alpha: ByteArray,
)

internal interface VpxHandleOwner {
    fun create(): Long
    fun destroy(handle: Long)
    fun decode(handle: Long, packet: ByteArray): VpxDecodedFrame?
    fun decodeAlpha(handle: Long, packet: ByteArray): VpxDecodedAlpha?
}

internal object NativeVpxHandleOwner : VpxHandleOwner {
    override fun create(): Long = VpxNative.create()

    override fun destroy(handle: Long) = VpxNative.destroy(handle)

    override fun decode(handle: Long, packet: ByteArray): VpxDecodedFrame? {
        val frame = VpxNative.decode(handle, packet) ?: return null
        return VpxDecodedFrame(frame.width.toInt(), frame.height.toInt(), frame.rgba)
    }

    override fun decodeAlpha(handle: Long, packet: ByteArray): VpxDecodedAlpha? {
        val mask = VpxNative.decodeAlpha(handle, packet) ?: return null
        return VpxDecodedAlpha(mask.width.toInt(), mask.height.toInt(), mask.alpha)
    }
}

internal suspend fun <T> withOwnedVpxHandles(
    owner: VpxHandleOwner,
    count: Int,
    use: suspend (LongArray) -> T,
): T? {
    val handles = LongArray(count)
    try {
        for (index in handles.indices) {
            currentCoroutineContext().ensureActive()
            val handle = try {
                owner.create()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                0L
            }
            if (handle == 0L) return null
            handles[index] = handle
        }
        return use(handles)
    } finally {
        for (index in handles.indices.reversed()) {
            val handle = handles[index]
            if (handle != 0L) owner.destroy(handle)
        }
    }
}

internal object VpxStickerSlots {
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

    internal fun inUse(): Int = playing.get()

    internal fun resetForTests() {
        playing.set(0)
    }
}

internal suspend fun runLegacyVpxSlot(
    dispatcher: CoroutineDispatcher,
    block: suspend () -> Unit,
) {
    VpxStickerSlots.acquire()
    try {
        withContext(dispatcher) {
            currentCoroutineContext().ensureActive()
            block()
        }
    } finally {
        VpxStickerSlots.release()
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
    org.monogram.core.ui.rememberFileLease(file)
    val frame = remember(file.absolutePath) { mutableStateOf<ImageBitmap?>(null) }
    val hasFrame = remember(file.absolutePath) { mutableStateOf(false) }
    LaunchedEffect(file.absolutePath, playable) {
        if (!playable) return@LaunchedEffect
        playLegacyVpx(file) { next ->
            withContext(Dispatchers.Main.immediate) {
                frame.value = next
                if (!hasFrame.value) hasFrame.value = true
                isActive
            }
        }
    }
    Box(modifier) {
        if (!hasFrame.value) {
            VideoStill(
                file = file,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            val current = frame.value
            val drawn = current?.takeIf { !it.asAndroidBitmap().isRecycled } ?: return@Canvas
            val scale = minOf(size.width / drawn.width, size.height / drawn.height)
            val width = (drawn.width * scale).roundToInt().coerceAtLeast(1)
            val height = (drawn.height * scale).roundToInt().coerceAtLeast(1)
            drawImage(
                image = drawn,
                dstOffset = IntOffset(
                    ((size.width - width) / 2).roundToInt(),
                    ((size.height - height) / 2).roundToInt(),
                ),
                dstSize = IntSize(width, height),
            )
        }
    }
}

internal fun sniffStickerTag(file: File): PanelTag {
    if (gzipFile(file)) return PanelTag.Tgs
    if (!webmFile(file)) return PanelTag.Webp
    val alpha = runCatching { MatroskaAlphaReader(file).hasAlpha }.getOrNull()
        ?: return PanelTag.WebmAlpha
    return if (alpha) PanelTag.WebmAlpha else PanelTag.Webm
}

internal suspend fun decodePanelVpx(
    file: File,
    maxSide: Int,
    alpha: Boolean,
    maxFrames: Int = 1,
    owner: VpxHandleOwner = NativeVpxHandleOwner,
): PanelFrameClip? {
    val limit = maxFrames.coerceIn(1, 16)
    return if (alpha) decodeAlphaPanel(file, maxSide, limit, owner) else decodeColorPanel(
        file,
        maxSide,
        limit,
        owner,
    )
}

private suspend fun decodeAlphaPanel(
    file: File,
    maxSide: Int,
    maxFrames: Int,
    owner: VpxHandleOwner,
): PanelFrameClip? {
    return try {
        currentCoroutineContext().ensureActive()
        val reader = MatroskaAlphaReader(file)
        withOwnedVpxHandles(owner, 2) { handles ->
            val colorHandle = handles[0]
            val alphaHandle = handles[1]
            val frames = ArrayList<ByteArray>(8)
            var width = 0
            var height = 0
            var sourceEnded = true
            for (sample in reader.samples()) {
                currentCoroutineContext().ensureActive()
                if (frames.size >= maxFrames) {
                    sourceEnded = false
                    break
                }
                val color = decodeColorFrame(owner, colorHandle, sample.color) ?: continue
                if (color.rgba.isEmpty() || color.width == 0 || color.height == 0) continue
                val mask = sample.alpha?.let { decodeAlphaFrame(owner, alphaHandle, it) }
                val pixels = color.width * color.height
                if (mask != null && (mask.width != color.width || mask.height != color.height || mask.alpha.size != pixels)) {
                    continue
                }
                val rgba = color.rgba
                if (mask != null) {
                    for (i in mask.alpha.indices) rgba[i * 4 + 3] = mask.alpha[i]
                } else {
                    for (i in rgba.indices step 4) rgba[i + 3] = 255.toByte()
                }
                val (scaledWidth, scaledHeight) = scaledSize(color.width, color.height, maxSide)
                val scaled = scaleRgba(rgba, color.width, color.height, scaledWidth, scaledHeight)
                if (scaled.size != scaledWidth * scaledHeight * 4) continue
                width = scaledWidth
                height = scaledHeight
                frames += scaled
            }
            if (frames.isEmpty()) {
                null
            } else {
                PanelFrameClip(
                    width,
                    height,
                    33,
                    frames,
                    complete = sourceEnded,
                    sourceDurationMs = frames.size.toLong() * 33L,
                )
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

private suspend fun decodeColorPanel(
    file: File,
    maxSide: Int,
    maxFrames: Int,
    owner: VpxHandleOwner,
): PanelFrameClip? {
    return try {
        currentCoroutineContext().ensureActive()
        withOwnedVpxHandles(owner, 1) { handles ->
            val handle = handles[0]
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                val track = selectVpxTrack(extractor)
                if (track == null) {
                    null
                } else {
                    extractor.selectTrack(track)
                    val format = extractor.getTrackFormat(track)
                    val packet = ByteBuffer.allocate(format.maxInputSizeOr(256 * 1024))
                    val frames = ArrayList<ByteArray>(8)
                    var width = 0
                    var height = 0
                    var badPackets = 0
                    var sourceEnded = false
                    while (frames.size < maxFrames) {
                        currentCoroutineContext().ensureActive()
                        packet.clear()
                        val size = extractor.readSampleData(packet, 0)
                        if (size < 0) {
                            sourceEnded = true
                            break
                        }
                        extractor.advance()
                        val bytes = ByteArray(size)
                        packet.position(0)
                        packet.get(bytes)
                        val frame = decodeColorFrame(owner, handle, bytes)
                        if (frame == null || frame.rgba.isEmpty() || frame.width == 0 || frame.height == 0) {
                            badPackets++
                            if (badPackets >= 120) break
                            continue
                        }
                        badPackets = 0
                        val (scaledWidth, scaledHeight) = scaledSize(
                            frame.width,
                            frame.height,
                            maxSide
                        )
                        val scaled =
                            scaleRgba(
                                frame.rgba,
                                frame.width,
                                frame.height,
                                scaledWidth,
                                scaledHeight
                            )
                        if (scaled.size != scaledWidth * scaledHeight * 4) continue
                        width = scaledWidth
                        height = scaledHeight
                        frames += scaled
                    }
                    if (!sourceEnded && frames.size >= maxFrames) {
                        packet.clear()
                        sourceEnded = extractor.readSampleData(packet, 0) < 0
                    }
                    if (frames.isEmpty()) {
                        null
                    } else {
                        val delayMs = frameDelayMs(format).toInt()
                        PanelFrameClip(
                            width,
                            height,
                            delayMs,
                            frames,
                            complete = sourceEnded,
                            sourceDurationMs = vpxSourceDurationMs(format, frames.size, delayMs),
                        )
                    }
                }
            } finally {
                extractor.release()
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

internal suspend fun playLegacyVpx(
    file: File,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    owner: VpxHandleOwner = NativeVpxHandleOwner,
    onFrame: suspend (ImageBitmap) -> Boolean,
) {
    runLegacyVpxSlot(dispatcher) {
        val alpha = try {
            MatroskaAlphaReader(file).hasAlpha
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        when (alpha) {
            true -> withOwnedVpxHandles(owner, 2) { handles ->
                playAlphaVpxLoop(file, handles[0], handles[1], owner, onFrame)
            }

            false -> withOwnedVpxHandles(owner, 1) { handles ->
                playExtractorVpxLoop(file, handles[0], owner, onFrame)
            }

            null -> Unit
        }
    }
}

private suspend fun playAlphaVpxLoop(
    file: File,
    colorHandle: Long,
    alphaHandle: Long,
    owner: VpxHandleOwner,
    onFrame: suspend (ImageBitmap) -> Boolean,
) {
    try {
        val reader = MatroskaAlphaReader(file)
        while (true) {
            var rendered = false
            for (sample in reader.samples()) {
                currentCoroutineContext().ensureActive()
                val color = decodeColorFrame(owner, colorHandle, sample.color)
                val alpha = sample.alpha?.let { decodeAlphaFrame(owner, alphaHandle, it) }
                if (color == null || color.rgba.isEmpty()) continue
                if (alpha != null && (alpha.width != color.width || alpha.height != color.height || alpha.alpha.size != color.width * color.height)) continue
                if (alpha != null) {
                    for (i in alpha.alpha.indices) color.rgba[i * 4 + 3] = alpha.alpha[i]
                } else {
                    for (i in color.rgba.indices step 4) color.rgba[i + 3] = 255.toByte()
                }
                val bitmap = argbFrameBitmap(color.rgba, color.width, color.height) ?: continue
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
    }
}

private suspend fun playExtractorVpxLoop(
    file: File,
    handle: Long,
    owner: VpxHandleOwner,
    onFrame: suspend (ImageBitmap) -> Boolean,
) {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(file.absolutePath)
        val track = selectVpxTrack(extractor) ?: return
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val packet = ByteBuffer.allocate(format.maxInputSizeOr(256 * 1024))
        var badPackets = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            packet.clear()
            val size = extractor.readSampleData(packet, 0)
            if (size < 0) {
                extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                badPackets = 0
                continue
            }
            extractor.advance()
            val bytes = ByteArray(size)
            packet.position(0)
            packet.get(bytes)
            val frame = decodeColorFrame(owner, handle, bytes)
            if (frame != null && frame.rgba.isNotEmpty() && frame.width > 0 && frame.height > 0) {
                badPackets = 0
                val bitmap = argbFrameBitmap(frame.rgba, frame.width, frame.height)
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
        extractor.release()
    }
}

private fun decodeColorFrame(
    owner: VpxHandleOwner,
    handle: Long,
    packet: ByteArray,
): VpxDecodedFrame? = try {
    owner.decode(handle, packet)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

private fun decodeAlphaFrame(
    owner: VpxHandleOwner,
    handle: Long,
    packet: ByteArray,
): VpxDecodedAlpha? = try {
    owner.decodeAlpha(handle, packet)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

data class AlphaSample(val color: ByteArray, val alpha: ByteArray?)

internal class MatroskaAlphaReader(file: File) {
    private val data = run { require(file.length() <= 64L * 1024 * 1024); file.readBytes() }
    val hasAlpha: Boolean
    private val parsedSamples: List<AlphaSample>

    init {
        val result = EbmlParser(data).parse()
        hasAlpha = webmHasAlpha(result.alphaMode, result.samples.any { it.alpha != null })
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
        var color: ByteArray? = null
        var alpha: ByteArray? = null
        var p = begin
        while (p < end) {
            val id = readId(p) ?: return; p += id.second
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
            val id = readId(p) ?: return null; p += id.second
            val size = readVint(p) ?: return null; p += size.second
            val stop = minOf(
                end,
                p + if (size.first < 0) end - p else size.first.coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
            )
            if (id.first == 0xA6L) {
                var q = p
                var addId = 0L
                var payload: ByteArray? = null
                while (q < stop) {
                    val child = readId(q) ?: break; q += child.second
                    val cs = readVint(q) ?: break; q += cs.second
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
        if (p >= data.size) return null
        val v = data[p].toInt() and 255
        val n = when {
            v and 0x80 != 0 -> 1; v and 0x40 != 0 -> 2; v and 0x20 != 0 -> 3; v and 0x10 != 0 -> 4; else -> return null
        }; if (p + n > data.size) return null; return number(p, p + n) to n
    }

    private fun readVint(p: Int): Pair<Long, Int>? {
        if (p >= data.size) return null
        val v = data[p].toInt() and 255
        val n = when {
            v and 0x80 != 0 -> 1; v and 0x40 != 0 -> 2; v and 0x20 != 0 -> 3; v and 0x10 != 0 -> 4; v and 0x08 != 0 -> 5; v and 0x04 != 0 -> 6; v and 0x02 != 0 -> 7; v and 0x01 != 0 -> 8; else -> return null
        }; if (p + n > data.size) return null
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

private fun vpxSourceDurationMs(format: MediaFormat, frameCount: Int, delayMs: Int): Long {
    val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
        runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(-1L)
    } else {
        -1L
    }
    if (durationUs > 0L) return durationUs / 1000L
    return frameCount.toLong() * delayMs.toLong()
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
