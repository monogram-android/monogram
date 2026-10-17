package org.monogram.feature.dialog.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.monogram.core.ui.components.LocalMediaAnimationEnabled
import org.monogram.core.ui.components.MediaScrollGate
import org.monogram.core.ui.components.argbFrameBitmap
import org.monogram.feature.dialog.PanelFrameClip
import org.monogram.feature.dialog.scaledSize
import org.monogram.feature.dialog.sourceFrameIndex
import org.monogram.mtproto.LottieNative
import kotlin.math.max
import kotlin.math.roundToInt

internal interface LottieHandleOwner {
    fun create(data: ByteArray): Long
    fun destroy(handle: Long)
    fun frameCount(handle: Long): Int
    fun frameRate(handle: Long): Float
    fun size(handle: Long): Pair<Int, Int>?
    fun renderFrame(handle: Long, frame: Float, width: Int, height: Int): ByteArray
}

internal object NativeLottieHandleOwner : LottieHandleOwner {
    override fun create(data: ByteArray): Long = LottieNative.create(data)

    override fun destroy(handle: Long) = LottieNative.destroy(handle)

    override fun frameCount(handle: Long): Int = LottieNative.frameCount(handle)

    override fun frameRate(handle: Long): Float = LottieNative.frameRate(handle)

    override fun size(handle: Long): Pair<Int, Int> {
        val size = LottieNative.size(handle)
        return size.width.toInt() to size.height.toInt()
    }

    override fun renderFrame(handle: Long, frame: Float, width: Int, height: Int): ByteArray =
        LottieNative.renderFrame(handle, frame, width, height)
}

internal suspend fun <T> useOwnedLottie(
    bytes: ByteArray,
    owner: LottieHandleOwner = NativeLottieHandleOwner,
    use: suspend (Long) -> T,
): T? {
    currentCoroutineContext().ensureActive()
    val handle = try {
        owner.create(bytes)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        0L
    }
    if (handle == 0L) return null
    try {
        return use(handle)
    } finally {
        owner.destroy(handle)
    }
}

internal suspend fun <T> withOwnedLottie(
    bytes: ByteArray,
    dispatcher: CoroutineDispatcher,
    owner: LottieHandleOwner = NativeLottieHandleOwner,
    use: suspend (Long) -> T,
): T? = withContext(dispatcher) {
    useOwnedLottie(bytes, owner, use)
}

internal suspend fun <T> withCompactLottie(
    bytes: ByteArray,
    dispatcher: CoroutineDispatcher,
    owner: LottieHandleOwner = NativeLottieHandleOwner,
    use: suspend (Long) -> T,
): T? {
    CompactVideoSlots.acquire()
    try {
        return withOwnedLottie(bytes, dispatcher, owner, use)
    } finally {
        CompactVideoSlots.release()
    }
}

internal suspend fun decodePanelTgs(
    bytes: ByteArray,
    maxSide: Int,
    maxFrames: Int = 1,
    owner: LottieHandleOwner = NativeLottieHandleOwner,
): PanelFrameClip? = useOwnedLottie(bytes, owner) { handle ->
    val frameCount = try {
        owner.frameCount(handle)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        1
    }.coerceIn(1, 600)
    val fps = try {
        owner.frameRate(handle)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        30f
    }.coerceIn(1f, 60f)
    val size = try {
        owner.size(handle)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
    val (width, height) = scaledSize(
        size?.first ?: maxSide,
        size?.second ?: maxSide,
        maxSide,
    )
    val step = if (fps > 30f) 2 else 1
    val delayMs = if (fps > 30f) 33 else (1000f / fps).toLong().coerceAtLeast(24L)
    val frames = ArrayList<ByteArray>(frameCount)
    var index = 0
    val limit = maxFrames.coerceIn(1, 16)
    while (index < frameCount && frames.size < limit) {
        currentCoroutineContext().ensureActive()
        val rgba = try {
            owner.renderFrame(handle, index.toFloat(), width, height)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: break
        if (rgba.size != width * height * 4) break
        frames += rgba
        index += step
    }
    if (frames.isEmpty()) {
        null
    } else {
        val sourceDurationMs =
            (frameCount.toDouble() * 1000.0 / fps.toDouble()).toLong().coerceAtLeast(0L)
        PanelFrameClip(
            width,
            height,
            delayMs.toInt(),
            frames,
            complete = index >= frameCount,
            sourceDurationMs = sourceDurationMs,
        )
    }
}

internal suspend fun playOwnedSticker(
    lottieBytes: ByteArray,
    displaySizePx: Int,
    animationEnabled: Boolean,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    owner: LottieHandleOwner = NativeLottieHandleOwner,
    publish: (Bitmap) -> Unit,
) {
    withCompactLottie(lottieBytes, dispatcher, owner) { handle ->
        val frames = try {
            owner.frameCount(handle)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            1
        }.coerceIn(1, 600)
        val fps = try {
            owner.frameRate(handle)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            30f
        }.coerceIn(1f, 60f)
        val size = try {
            owner.size(handle)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val width = max(1, size?.first ?: displaySizePx)
        val height = max(1, size?.second ?: displaySizePx)
        val scale = displaySizePx.toFloat() / max(width, height).toFloat()
        val outW = max(1, (width * scale).roundToInt())
        val outH = max(1, (height * scale).roundToInt())

        suspend fun drawFrame(frameIndex: Int): Boolean {
            currentCoroutineContext().ensureActive()
            val rgba = try {
                owner.renderFrame(handle, frameIndex.toFloat(), outW, outH)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return false
            val rendered = argbFrameBitmap(rgba, outW, outH) ?: return false
            withContext(Dispatchers.Main.immediate) {
                publish(rendered)
            }
            return true
        }

        if (!drawFrame(0)) return@withCompactLottie
        if (!animationEnabled || frames <= 1) return@withCompactLottie

        val presentMs = if (fps > 30f) 33L else (1000f / fps).toLong().coerceAtLeast(24L)
        val startNs = System.nanoTime()
        var pausedNs = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            if (MediaScrollGate.isScrolling()) {
                val pauseStart = System.nanoTime()
                delay(presentMs)
                pausedNs += System.nanoTime() - pauseStart
                continue
            }
            val elapsedMs =
                ((System.nanoTime() - startNs - pausedNs) / 1_000_000L).coerceAtLeast(0L)
            val frameIndex = sourceFrameIndex(elapsedMs, frames, fps)
            if (!drawFrame(frameIndex)) return@withCompactLottie
            delay(presentMs)
        }
    }
}

@Composable
fun StickerPlayer(
    lottieBytes: ByteArray,
    modifier: Modifier = Modifier,
    displaySize: Dp = 192.dp,
    active: Boolean = true,
) {
    val animationEnabled = LocalMediaAnimationEnabled.current
    val displaySizePx = with(LocalDensity.current) { displaySize.roundToPx() }
    val frame = remember(lottieBytes, displaySizePx) { mutableStateOf<Bitmap?>(null) }
    val tick = remember(lottieBytes, displaySizePx) { mutableStateOf(0) }
    DisposableEffect(lottieBytes, displaySizePx) {
        onDispose { frame.value = null }
    }

    LaunchedEffect(lottieBytes, displaySizePx, active, animationEnabled) {
        if (!active) return@LaunchedEffect
        playOwnedSticker(
            lottieBytes = lottieBytes,
            displaySizePx = displaySizePx,
            animationEnabled = animationEnabled,
        ) { rendered ->
            frame.value = rendered
            tick.value++
        }
    }

    Canvas(modifier = modifier.size(displaySize)) {
        tick.value
        frame.value?.takeIf { !it.isRecycled }?.let { bitmap ->
            val scale = minOf(size.width / bitmap.width, size.height / bitmap.height)
            val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
            val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
            drawImage(
                image = bitmap.asImageBitmap(),
                dstOffset = IntOffset(
                    ((size.width - width) / 2).roundToInt(),
                    ((size.height - height) / 2).roundToInt(),
                ),
                dstSize = IntSize(width, height),
            )
        }
    }
}
