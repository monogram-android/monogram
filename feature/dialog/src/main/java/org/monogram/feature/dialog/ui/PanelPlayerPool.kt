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
import kotlinx.coroutines.CoroutineDispatcher
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
import org.monogram.feature.dialog.PanelSettleMs
import org.monogram.feature.dialog.PanelTag
import org.monogram.feature.dialog.animationScaleAllowsPlayback
import org.monogram.feature.dialog.panelAdmitsWork
import org.monogram.feature.dialog.panelBucketForSide
import org.monogram.feature.dialog.panelBucketSide
import org.monogram.feature.dialog.panelClipMayLoop
import org.monogram.feature.dialog.panelFrameLimit
import org.monogram.feature.dialog.panelNeedsNativeDecoder
import org.monogram.feature.dialog.scaleRgba
import org.monogram.feature.dialog.scaledSize
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

internal val PanelDecodeDispatcher: CoroutineDispatcher =
    Dispatchers.Default.limitedParallelism(1)

internal interface PanelFrameBitmaps {
    fun decode(rgba: ByteArray, width: Int, height: Int): ImageBitmap?
    fun recycle(image: ImageBitmap)
}

internal object AndroidPanelFrameBitmaps : PanelFrameBitmaps {
    override fun decode(rgba: ByteArray, width: Int, height: Int): ImageBitmap? =
        argbFrameBitmap(rgba, width, height)?.asImageBitmap()

    override fun recycle(image: ImageBitmap) {
        val bitmap = image.asAndroidBitmap()
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}

internal data class PanelCellPlayback(
    val vpxLoop: Boolean,
    val tgsLoop: Boolean,
    val bucket: PanelBucket,
    val decode: Boolean,
    val index: Int = 0,
)

internal val LocalPanelPool = staticCompositionLocalOf<PanelPlayerPool?> { null }

internal class PanelPlayerPool(
    private val cacheDir: File,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val ledger: PanelBudgetLedger = PanelBudgetLedger(),
    private val decodeDispatcher: CoroutineDispatcher = PanelDecodeDispatcher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val bitmapDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val frameBitmaps: PanelFrameBitmaps = AndroidPanelFrameBitmaps,
    private val decodeFrames: suspend (File, PanelTag, Int, Int) -> PanelFrameClip? = { file, tag, side, maxFrames ->
        decodeDocument(file, tag, side, maxFrames)
    },
) {
    private class View(
        val documentId: Long,
        val bucket: PanelBucket,
        val file: File,
        var vpxLoop: Boolean,
        var tgsLoop: Boolean,
        var decode: Boolean,
        var index: Int,
    )

    private class Doc {
        val shown = mutableStateOf<ImageBitmap?>(null)
        val hasFrame = mutableStateOf(false)
        var tag: PanelTag = PanelTag.Webp
        var job: Job? = null
        var generation: Long = 0
        var delayMs: Int = 33
        var posterOnly: Boolean = false
        var reservedTicket: Int? = null
        var reservedGeneration: Long = -1
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
            index = playback.index,
        )
        docs.getOrPut(documentId) { Doc() }
        sync(documentId)
    }

    fun update(viewId: String, playback: PanelCellPlayback) {
        val view = views[viewId] ?: return
        view.index = playback.index
        ledger.placeAnimateWaiter(view.documentId, waiterPlace(view.documentId))
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
        docs.values.forEach { it.generation += 1 }
        views.clear()
        docs.clear()
        running.forEach { it.cancel() }
        ledger.clear()
    }

    private fun sync(documentId: Long) {
        val doc = docs.getOrPut(documentId) { Doc() }
        val related = views.values.filter { it.documentId == documentId }
        if (related.isEmpty()) {
            ledger.unwatchAnimate(documentId)
            cancelDoc(documentId, doc)
            docs.remove(documentId)
            doc.shown.value = null
            return
        }
        if (looping(documentId, doc.tag)) {
            ledger.placeAnimateWaiter(documentId, waiterPlace(documentId))
        } else {
            ledger.unwatchAnimate(documentId)
        }
        val admitNative = wanted(related, doc.tag)
        if (!admitNative) {
            if (doc.job?.isActive == true && !doc.posterOnly) cancelDoc(documentId, doc)
            if (doc.shown.value != null || (doc.job?.isActive == true && doc.posterOnly)) return
            start(documentId, doc, related, allowNative = false)
            return
        }
        if (doc.job?.isActive == true && !doc.posterOnly) return
        if (doc.job?.isActive == true) cancelDoc(documentId, doc)
        start(documentId, doc, related, allowNative = true)
    }

    private fun start(
        documentId: Long,
        doc: Doc,
        related: List<View>,
        allowNative: Boolean,
        reservedTicket: Int? = null,
    ) {
        val file = related.maxBy { panelBucketSide(it.bucket) }.file
        val side = related.maxOf { panelBucketSide(it.bucket) }
        val generation = ++doc.generation
        doc.reservedTicket = reservedTicket
        doc.reservedGeneration = generation
        doc.posterOnly = !allowNative
        doc.job = scope.launch { runDoc(documentId, file, side, generation, allowNative) }
    }

    private fun cancelDoc(documentId: Long, doc: Doc) {
        val reserved = if (doc.reservedGeneration == doc.generation) doc.reservedTicket else null
        doc.reservedTicket = null
        doc.reservedGeneration = -1
        val running = doc.job
        doc.generation += 1
        doc.posterOnly = false
        running?.cancel()
        if (doc.job === running) doc.job = null
        val ticket = reserved ?: ledger.takePendingAnimate(documentId)
        if (ticket != null) {
            val next = ledger.stopAnimate(documentId, ticket)
            if (next != null) resumeAdmitted(next)
        }
    }

    private fun isCurrent(documentId: Long, doc: Doc, generation: Long): Boolean =
        docs[documentId] === doc && doc.generation == generation

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

    private suspend fun runDoc(
        documentId: Long,
        file: File,
        side: Int,
        generation: Long,
        allowNative: Boolean,
    ) {
        val doc = docs[documentId] ?: return
        if (!isCurrent(documentId, doc, generation)) return
        val tag = withContext(ioDispatcher) {
            coroutineContext.ensureActive()
            sniffStickerTag(file)
        }
        if (!isCurrent(documentId, doc, generation)) return
        doc.tag = tag
        if (tag == PanelTag.Gif || tag == PanelTag.Webp) return
        var animateTicket: Int? = null
        val unpublished = ArrayList<ImageBitmap>()
        try {
            var clip = withContext(ioDispatcher) {
                coroutineContext.ensureActive()
                readCached(documentId, tag, side)
            }
            val poster = if (clip != null) {
                clip
            } else {
                withContext(ioDispatcher) {
                    coroutineContext.ensureActive()
                    readBucket(documentId, tag, side)
                }
            }
            if (!isCurrent(documentId, doc, generation)) return
            if (poster != null && poster.frames.isNotEmpty()) {
                val cached =
                    takeBitmap(poster.frames.first(), poster.width, poster.height) ?: return
                unpublished += cached
                if (!publish(documentId, doc, generation, cached)) return
                unpublished.removeAll { it === cached }
                doc.delayMs = poster.delayMs
            }
            val admitNative = allowNative && wanted(
                views.values.filter { it.documentId == documentId },
                tag,
            )
            if (!admitNative) return
            if (panelNeedsNativeDecoder(clip?.frames?.size ?: 0)) {
                clip = decodeStill(file, tag, side)
                if (clip != null && isCurrent(documentId, doc, generation)) {
                    val saved = clip
                    withContext(ioDispatcher) {
                        coroutineContext.ensureActive()
                        writeRequestedBucket(documentId, tag, side, saved)
                    }
                }
            }
            if (!isCurrent(documentId, doc, generation)) return
            val ready = clip ?: return
            if (ready.frames.isEmpty()) return
            if (doc.shown.value == null) {
                val first = takeBitmap(ready.frames.first(), ready.width, ready.height) ?: return
                unpublished += first
                if (!publish(documentId, doc, generation, first)) return
                unpublished.removeAll { it === first }
            }
            doc.delayMs = ready.delayMs
            if (!looping(documentId, tag)) return
            animateTicket = claimReserved(doc, generation)
            if (animateTicket == null) {
                val place = waiterPlace(documentId)
                animateTicket = ledger.tryAnimate(documentId, place)
                if (animateTicket == null) {
                    if (looping(documentId, tag)) ledger.watchAnimate(documentId, place)
                    return
                }
            }
            ledger.unwatchAnimate(documentId)
            if (!isCurrent(documentId, doc, generation)) return
            val full = if (ready.frames.size > 1) {
                ready
            } else {
                decodeStill(file, tag, side, panelFrameLimit(looping = true)) ?: ready
            }
            if (!isCurrent(documentId, doc, generation)) return
            if (full.frames.size <= 1) return
            if (full !== ready) {
                withContext(ioDispatcher) {
                    coroutineContext.ensureActive()
                    writeRequestedBucket(documentId, tag, side, full)
                }
            }
            if (!isCurrent(documentId, doc, generation)) return
            val frames = ArrayList<ImageBitmap>(full.frames.size)
            for (rgba in full.frames) {
                coroutineContext.ensureActive()
                val bitmap = takeBitmap(rgba, full.width, full.height) ?: continue
                unpublished += bitmap
                frames += bitmap
            }
            if (frames.size <= 1 || !isCurrent(documentId, doc, generation)) return
            val delayMs = full.delayMs.toLong().coerceAtLeast(16L)
            val mayLoop = panelClipMayLoop(full)
            while (coroutineContext.isActive && looping(documentId, tag) && isCurrent(
                    documentId,
                    doc,
                    generation
                )
            ) {
                for (frame in frames) {
                    if (!looping(documentId, tag) || !isCurrent(documentId, doc, generation)) return
                    if (!publish(documentId, doc, generation, frame)) return
                    unpublished.removeAll { it === frame }
                    delay(delayMs)
                }
                if (!mayLoop) return
            }
        } finally {
            val ticket = animateTicket ?: claimReserved(doc, generation)
            animateTicket = null
            if (ticket != null) {
                val admitted = ledger.stopAnimate(documentId, ticket)
                if (admitted != null) resumeAdmitted(admitted)
            }
            unpublished.forEach(frameBitmaps::recycle)
        }
    }

    private fun claimReserved(doc: Doc, generation: Long): Int? {
        if (doc.reservedGeneration != generation) return null
        val ticket = doc.reservedTicket
        doc.reservedTicket = null
        return ticket
    }

    private fun waiterPlace(documentId: Long): Int =
        views.values.filter { it.documentId == documentId }.minOfOrNull { it.index } ?: 0

    private fun resumeAdmitted(documentId: Long) {
        val doc = docs[documentId]
        val related = views.values.filter { it.documentId == documentId }
        if (doc == null || related.isEmpty() || !looping(documentId, doc.tag)) {
            val ticket = ledger.takePendingAnimate(documentId) ?: return
            val next = ledger.stopAnimate(documentId, ticket)
            if (next != null) resumeAdmitted(next)
            return
        }
        if (doc.job?.isActive == true && !doc.posterOnly) return
        val reserved = ledger.takePendingAnimate(documentId)
        if (doc.job?.isActive == true) cancelDoc(documentId, doc)
        start(documentId, doc, related, allowNative = true, reservedTicket = reserved)
    }

    private suspend fun decodeStill(
        file: File,
        tag: PanelTag,
        side: Int,
        maxFrames: Int = panelFrameLimit(looping = false),
    ): PanelFrameClip? = decodeGate.withLock {
        withContext(decodeDispatcher) {
            coroutineContext.ensureActive()
            decodeFrames(file, tag, side, maxFrames)
        }
    }

    private suspend fun takeBitmap(rgba: ByteArray, width: Int, height: Int): ImageBitmap? =
        withContext(bitmapDispatcher) {
            coroutineContext.ensureActive()
            frameBitmaps.decode(rgba, width, height)
        }

    private fun publish(
        documentId: Long,
        doc: Doc,
        generation: Long,
        frame: ImageBitmap,
    ): Boolean {
        if (!isCurrent(documentId, doc, generation)) return false
        doc.shown.value = frame
        doc.hasFrame.value = true
        return true
    }

    private fun readCached(documentId: Long, tag: PanelTag, side: Int): PanelFrameClip? {
        val bucket = panelBucketForSide(side) ?: return null
        return PanelFrameCache.read(PanelFrameCache.file(cacheDir, documentId, bucket, tag), side)
    }

    private fun readBucket(documentId: Long, tag: PanelTag, side: Int): PanelFrameClip? {
        val bucket = panelBucketForSide(side) ?: return null
        return PanelFrameCache.read(PanelFrameCache.file(cacheDir, documentId, bucket, tag))
    }

    private fun writeRequestedBucket(
        documentId: Long,
        tag: PanelTag,
        side: Int,
        clip: PanelFrameClip
    ) {
        val bucket = panelBucketForSide(side) ?: return
        if (maxOf(clip.width, clip.height) < side) return
        val (width, height) = scaledSize(clip.width, clip.height, side)
        val frames = if (width == clip.width && height == clip.height) {
            clip.frames
        } else {
            clip.frames.map { scaleRgba(it, clip.width, clip.height, width, height) }
        }
        PanelFrameCache.write(
            PanelFrameCache.file(cacheDir, documentId, bucket, tag),
            clip.copy(width = width, height = height, frames = frames),
        )
    }

}

private suspend fun decodeDocument(
    file: File,
    tag: PanelTag,
    side: Int,
    maxFrames: Int,
): PanelFrameClip? = when (tag) {
    PanelTag.Tgs -> {
        coroutineContext.ensureActive()
        decodePanelTgs(file.readBytes(), side, maxFrames)
    }

    PanelTag.Webm -> decodePanelVpx(file, side, alpha = false, maxFrames = maxFrames)
    PanelTag.WebmAlpha -> decodePanelVpx(file, side, alpha = true, maxFrames = maxFrames)
    PanelTag.Webp, PanelTag.Gif -> null
}

@Composable
internal fun rememberPanelReady(
    panelEpoch: Any,
    visible: Boolean,
    started: Boolean,
    gridScrolling: Boolean,
    stripScrolling: Boolean,
    settleMs: Long = PanelSettleMs,
): Boolean {
    val moving = gridScrolling || stripScrolling
    var settled by remember(panelEpoch) { mutableStateOf(false) }
    LaunchedEffect(panelEpoch, visible, started, moving, settleMs) {
        settled = false
        if (visible && started && !moving) {
            delay(settleMs)
            settled = true
        }
    }
    return panelAdmitsWork(visible, started, gridScrolling, stripScrolling, settled)
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
