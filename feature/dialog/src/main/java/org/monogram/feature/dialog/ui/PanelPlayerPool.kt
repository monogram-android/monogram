package org.monogram.feature.dialog.ui

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.monogram.core.ui.components.LocalMediaAnimationEnabled
import org.monogram.core.ui.components.argbFrameBitmap
import org.monogram.feature.dialog.PanelBucket
import org.monogram.feature.dialog.PanelBudgetLedger
import org.monogram.feature.dialog.PanelFrameCache
import org.monogram.feature.dialog.PanelFrameClip
import org.monogram.feature.dialog.PanelTag
import org.monogram.feature.dialog.animationScaleAllowsPlayback
import org.monogram.feature.dialog.panelBucketSide
import org.monogram.feature.dialog.panelFrameLimit
import org.monogram.feature.dialog.panelNeedsNativeDecoder
import org.monogram.feature.dialog.scaleRgba
import org.monogram.feature.dialog.scaledSize
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

internal data class PanelCellPlayback(
    val vpxLoop: Boolean,
    val tgsLoop: Boolean,
    val bucket: PanelBucket,
    val decode: Boolean,
)

internal val LocalPanelPool = staticCompositionLocalOf<PanelPlayerPool?> { null }

internal class PanelPlayerPool(
    private val cacheDir: File,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val ledger: PanelBudgetLedger = PanelBudgetLedger(),
) {
    private class View(
        val documentId: Long,
        val bucket: PanelBucket,
        val file: File,
        var vpxLoop: Boolean,
        var tgsLoop: Boolean,
        var decode: Boolean,
    )

    private class Doc {
        val shown = mutableStateOf<ImageBitmap?>(null)
        val hasFrame = mutableStateOf(false)
        var tag: PanelTag = PanelTag.Webp
        var job: Job? = null
        var delayMs: Int = 33
        val gate = Mutex()
    }

    private val views = HashMap<String, View>()
    private val docs = HashMap<Long, Doc>()
    private val decodeGate = Mutex()

    fun bind(
        viewId: String,
        documentId: Long,
        bucket: PanelBucket,
        file: File,
        playback: PanelCellPlayback,
    ) {
        views[viewId] = View(
            documentId = documentId,
            bucket = bucket,
            file = file,
            vpxLoop = playback.vpxLoop,
            tgsLoop = playback.tgsLoop,
            decode = playback.decode,
        )
        docs.getOrPut(documentId) { Doc() }
        sync(documentId)
    }

    fun update(viewId: String, playback: PanelCellPlayback) {
        val view = views[viewId] ?: return
        if (
            view.vpxLoop == playback.vpxLoop &&
            view.tgsLoop == playback.tgsLoop &&
            view.decode == playback.decode
        ) {
            return
        }
        view.vpxLoop = playback.vpxLoop
        view.tgsLoop = playback.tgsLoop
        view.decode = playback.decode
        sync(view.documentId)
    }

    fun unbind(viewId: String) {
        val view = views.remove(viewId) ?: return
        sync(view.documentId)
    }

    fun hasFrame(documentId: Long): State<Boolean> =
        docs.getOrPut(documentId) { Doc() }.hasFrame

    fun frame(documentId: Long): ImageBitmap? = docs[documentId]?.shown?.value

    fun close() {
        clear()
        scope.cancel()
    }

    fun clear() {
        val running = docs.values.mapNotNull { it.job }
        val shown = docs.values.map { it.shown.value }
        views.clear()
        docs.clear()
        running.forEach { it.cancel() }
        shown.forEach(::releaseBitmap)
        ledger.clear()
    }

    private fun sync(documentId: Long) {
        val doc = docs.getOrPut(documentId) { Doc() }
        val related = views.values.filter { it.documentId == documentId }
        if (related.isEmpty()) {
            doc.job?.cancel()
            doc.job = null
            docs.remove(documentId)
            releaseBitmap(doc.shown.value)
            doc.shown.value = null
            return
        }
        if (!wanted(related, doc.tag)) {
            doc.job?.cancel()
            doc.job = null
            return
        }
        if (doc.job?.isActive == true) return
        val file = related.maxBy { panelBucketSide(it.bucket) }.file
        val side = related.maxOf { panelBucketSide(it.bucket) }
        doc.job = scope.launch { runDoc(documentId, file, side) }
    }

    private fun wanted(related: List<View>, tag: PanelTag): Boolean = when (tag) {
        PanelTag.Tgs -> related.any { it.tgsLoop || it.decode }
        PanelTag.Webm, PanelTag.WebmAlpha -> related.any { it.vpxLoop || it.decode }
        else -> related.any { it.vpxLoop || it.tgsLoop || it.decode }
    }

    private fun looping(documentId: Long, tag: PanelTag): Boolean {
        val related = views.values.filter { it.documentId == documentId }
        return when (tag) {
            PanelTag.Tgs -> related.any { it.tgsLoop }
            PanelTag.Webm, PanelTag.WebmAlpha -> related.any { it.vpxLoop }
            else -> false
        }
    }

    private suspend fun runDoc(documentId: Long, file: File, side: Int) {
        val doc = docs[documentId] ?: return
        val tag = withContext(Dispatchers.IO) { sniffStickerTag(file) }
        if (docs[documentId] !== doc) return
        doc.tag = tag
        if (tag == PanelTag.Gif || tag == PanelTag.Webp || !wanted(
                views.values.filter { it.documentId == documentId },
                tag
            )
        ) {
            return
        }
        var animateTicket: Int? = null
        var bitmaps = emptyList<ImageBitmap>()
        try {
            var clip = withContext(Dispatchers.IO) { readCached(documentId, tag) }
            if (panelNeedsNativeDecoder(clip?.frames?.size ?: 0)) {
                clip = decodeStill(file, tag, side)
                if (clip != null) {
                    val saved = clip
                    withContext(Dispatchers.IO) { writeAllBuckets(documentId, tag, saved) }
                }
            }
            val ready = clip ?: return
            if (ready.frames.isEmpty() || docs[documentId] !== doc) return
            val first = bitmapOf(ready.frames.first(), ready.width, ready.height) ?: return
            doc.delayMs = ready.delayMs
            replaceShown(doc, first)
            doc.hasFrame.value = true
            if (!looping(documentId, tag)) return
            animateTicket = ledger.tryAnimate(documentId) ?: return
            val full = if (ready.frames.size > 1) {
                ready
            } else {
                decodeStill(file, tag, side, panelFrameLimit(looping = true)) ?: ready
            }
            if (full.frames.size > 1 && full !== ready) {
                withContext(Dispatchers.IO) { writeAllBuckets(documentId, tag, full) }
            }
            bitmaps = withContext(Dispatchers.Default) {
                full.frames.mapNotNull { rgba -> bitmapOf(rgba, full.width, full.height) }
            }
            if (bitmaps.isEmpty() || docs[documentId] !== doc) return
            if (bitmaps.none { it === first }) releaseBitmap(first)
            while (coroutineContext.isActive && looping(documentId, tag) && bitmaps.size > 1) {
                for (frame in bitmaps) {
                    if (!looping(documentId, tag) || docs[documentId] !== doc) return
                    doc.shown.value = frame
                    delay(full.delayMs.toLong().coerceAtLeast(16L))
                }
            }
        } finally {
            animateTicket?.let { ledger.stopAnimate(documentId, it) }
            val keep = docs[documentId]?.shown?.value
            bitmaps.forEach { frame -> if (frame !== keep) releaseBitmap(frame) }
        }
    }

    private suspend fun decodeStill(
        file: File,
        tag: PanelTag,
        side: Int,
        maxFrames: Int = panelFrameLimit(looping = false),
    ): PanelFrameClip? = decodeGate.withLock {
        coroutineContext.ensureActive()
        decodeDocument(file, tag, side, maxFrames)
    }

    private fun readCached(documentId: Long, tag: PanelTag): PanelFrameClip? =
        PanelBucket.entries.mapNotNull { bucket ->
            PanelFrameCache.read(PanelFrameCache.file(cacheDir, documentId, bucket, tag))
        }.maxByOrNull { it.width.toLong() * it.height.toLong() }

    private fun writeAllBuckets(documentId: Long, tag: PanelTag, clip: PanelFrameClip) {
        PanelBucket.entries.forEach { bucket ->
            val side = panelBucketSide(bucket)
            val (width, height) = scaledSize(clip.width, clip.height, side)
            val frames = if (width == clip.width && height == clip.height) {
                clip.frames
            } else {
                clip.frames.map { scaleRgba(it, clip.width, clip.height, width, height) }
            }
            PanelFrameCache.write(
                PanelFrameCache.file(cacheDir, documentId, bucket, tag),
                PanelFrameClip(width, height, clip.delayMs, frames),
            )
        }
    }

    private fun replaceShown(doc: Doc, next: ImageBitmap) {
        val previous = doc.shown.value
        doc.shown.value = next
        if (previous != null && previous !== next) releaseBitmap(previous)
    }
}

private suspend fun bitmapOf(rgba: ByteArray, width: Int, height: Int): ImageBitmap? =
    withContext(Dispatchers.Default) {
        argbFrameBitmap(rgba, width, height)?.asImageBitmap()
    }

private fun releaseBitmap(image: ImageBitmap?) {
    val bitmap = image?.asAndroidBitmap() ?: return
    if (!bitmap.isRecycled) bitmap.recycle()
}

private suspend fun decodeDocument(
    file: File,
    tag: PanelTag,
    side: Int,
    maxFrames: Int,
): PanelFrameClip? = when (tag) {
    PanelTag.Tgs -> {
        val bytes = withContext(Dispatchers.IO) { file.readBytes() }
        decodePanelTgs(bytes, side, maxFrames)
    }

    PanelTag.Webm -> decodePanelVpx(file, side, alpha = false, maxFrames = maxFrames)
    PanelTag.WebmAlpha -> decodePanelVpx(file, side, alpha = true, maxFrames = maxFrames)
    PanelTag.Webp, PanelTag.Gif -> null
}

@Composable
internal fun rememberSettledIdle(scrolling: Boolean, settleMs: Long = 150L): Boolean {
    var idle by remember { mutableStateOf(!scrolling) }
    LaunchedEffect(scrolling) {
        if (scrolling) {
            idle = false
        } else {
            delay(settleMs)
            idle = true
        }
    }
    return idle
}

@Composable
internal fun rememberBandLatch(inBand: Boolean, holdMs: Long = 250L): Boolean {
    var latched by remember { mutableStateOf(inBand) }
    LaunchedEffect(inBand) {
        if (inBand) {
            latched = true
        } else {
            delay(holdMs)
            latched = false
        }
    }
    return latched
}

@Composable
internal fun panelAnimationsEnabled(): Boolean {
    if (!LocalMediaAnimationEnabled.current) return false
    val scale = Settings.Global.getFloat(
        LocalContext.current.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
    return animationScaleAllowsPlayback(scale)
}

@Composable
internal fun PanelPooledGlyph(
    documentId: Long,
    file: File,
    playback: PanelCellPlayback,
    modifier: Modifier = Modifier,
) {
    val pool = LocalPanelPool.current ?: return
    val viewId = remember(documentId, playback.bucket) { UUID.randomUUID().toString() }
    DisposableEffect(viewId, file) {
        pool.bind(viewId, documentId, playback.bucket, file, playback)
        onDispose { pool.unbind(viewId) }
    }
    LaunchedEffect(playback) {
        pool.update(viewId, playback)
    }
    val hasFrame by pool.hasFrame(documentId)
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val frame = pool.frame(documentId) ?: return@Canvas
            val scale = minOf(size.width / frame.width, size.height / frame.height)
            val width = (frame.width * scale).roundToInt().coerceAtLeast(1)
            val height = (frame.height * scale).roundToInt().coerceAtLeast(1)
            drawImage(
                image = frame,
                dstOffset = IntOffset(
                    ((size.width - width) / 2f).roundToInt(),
                    ((size.height - height) / 2f).roundToInt(),
                ),
                dstSize = IntSize(width, height),
            )
        }
    }
}
